package app.flit.share.protocol

import app.flit.share.protocol.client.Link
import app.flit.share.protocol.client.LinkProvider
import app.flit.share.protocol.client.PathOutgoing
import app.flit.share.protocol.client.PeerHttpClient
import app.flit.share.protocol.client.ProtocolClient
import app.flit.share.protocol.client.SendTarget
import app.flit.share.protocol.client.TcpLinkProvider
import app.flit.share.protocol.client.TlsLink
import app.flit.share.protocol.model.PeerInfo
import app.flit.share.protocol.tls.StreamTls
import app.flit.share.protocol.tls.TlsIdentity
import app.flit.share.protocol.transport.PipePair
import kotlinx.coroutines.runBlocking
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Collections
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sender-side identity binding. The prepare request must prove possession of
 * the private key for the certificate it presents (a bare DER is public and
 * replayable), and a declared server pin must be checked against the TLS peer
 * certificate before the first request byte goes out.
 */
class PeerIdentityPinTest {

    /** Canned HTTP answer plus a capture of everything the client wrote. */
    private class RecordingLink(response: String) : Link {
        val request = ByteArrayOutputStream()
        override val input: InputStream = ByteArrayInputStream(response.toByteArray(Charsets.US_ASCII))
        override val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) = request.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = request.write(b, off, len)
        }
        override fun close() {}
    }

    private fun peer(): PeerInfo = PeerInfo(
        alias = "sender", version = Protocol.VERSION, fingerprint = "AA", protocol = "http",
    ).sansAnnounce()

    private fun wireHeaders(raw: String): Map<String, String> =
        raw.substringBefore("\r\n\r\n").lineSequence().drop(1)
            .mapNotNull { line ->
                val i = line.indexOf(':')
                if (i > 0) line.substring(0, i).lowercase() to line.substring(i + 1).trim() else null
            }
            .toMap()

    private fun wireBody(raw: String): String {
        val at = raw.indexOf("\r\n\r\n")
        assertTrue(at > 0, "no header/body separator in the recorded request")
        return raw.substring(at + 4)
    }

    /** Runs the whole server side (including handshake) on its own thread. */
    private fun tlsServer(
        pair: PipePair,
        identity: TlsIdentity,
        work: (StreamTls) -> Unit,
    ): () -> Throwable? {
        val error = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        thread(isDaemon = true) {
            try {
                val link = pair.serverLink("peer")
                StreamTls.startServer(link.input, link.output, identity).use { srv -> work(srv) }
            } catch (t: Throwable) {
                error.set(t)
            } finally {
                done.countDown()
            }
        }
        return {
            assertTrue(done.await(30, TimeUnit.SECONDS), "piped TLS server timed out")
            error.get()
        }
    }

    @Test
    fun `prepare headers bind the sender certificate to a signature over the exact body`() {
        val identity = TlsIdentity.generate()
        val client = ProtocolClient(identity, links = LinkProvider { _, _, _, _ -> error("unused") })
        val body = "{\"info\":{\"alias\":\"sender\"},\"files\":{}}"
        try {
            for (multi in listOf(false, true)) {
                val h = client.prepareHeaders(multi, body)
                assertEquals(Protocol.FLIT_CLIENT_VALUE, h[Protocol.HEADER_FLIT_CLIENT])
                assertEquals(if (multi) "1" else null, h[ProtocolClient.MULTI_HEADER])
                assertTrue(h[Protocol.HEADER_PREPARE_CERT]!!.isNotBlank())
                assertEquals(
                    identity.certificate.encoded.asList(),
                    Base64.getDecoder().decode(h[Protocol.HEADER_PREPARE_CERT]!!).asList(),
                )
                val sig = h[Protocol.HEADER_PREPARE_SIG]!!
                assertTrue(
                    TlsIdentity.verifySignature(identity.certificate, body.toByteArray(Charsets.UTF_8), sig),
                    "signature must verify over the exact body bytes with the presented cert",
                )
                assertFalse(
                    TlsIdentity.verifySignature(
                        identity.certificate,
                        (body + " ").toByteArray(Charsets.UTF_8),
                        sig,
                    ),
                    "a byte-changed body must not verify — the DER alone is replayable",
                )
                assertFalse(
                    TlsIdentity.verifySignature(
                        TlsIdentity.generate().certificate, body.toByteArray(Charsets.UTF_8), sig,
                    ),
                    "another device's certificate must not verify the signature",
                )
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `every prepare on the wire carries cert and signature over its own body`() = runBlocking {
        val identity = TlsIdentity.generate()
        val seen = Collections.synchronizedList(mutableListOf<RecordingLink>())
        val declined = "HTTP/1.1 403 Declined\r\nX-Flit-Refusal: declined\r\nContent-Length: 2\r\n\r\nno"
        val provider = object : LinkProvider {
            override suspend fun connect(host: String, port: Int, tls: Boolean, timeoutMs: Int): Link =
                RecordingLink(declined).also { seen.add(it) }

            override suspend fun connect(
                host: String,
                port: Int,
                tls: Boolean,
                timeoutMs: Int,
                relayFor: String?,
                expectServerFp: String?,
            ): Link = connect(host, port, tls, timeoutMs)
        }
        val payload = Files.createTempFile("prepare-proof", ".bin").also { Files.write(it, "abc".toByteArray()) }
        val client = ProtocolClient(identity, links = provider)
        try {
            val report = client.sendFiles(
                SendTarget("127.0.0.1", 53317, tls = false),
                peer(),
                listOf(PathOutgoing(payload, "a.bin")),
            )
            assertTrue(report.declined)

            val wire = seen.single().request.toString(Charsets.US_ASCII)
            val headers = wireHeaders(wire)
            assertEquals(
                identity.certificate.encoded.asList(),
                Base64.getDecoder().decode(headers[Protocol.HEADER_PREPARE_CERT.lowercase()]!!).asList(),
            )
            assertTrue(
                TlsIdentity.verifySignature(
                    identity.certificate,
                    wireBody(wire).toByteArray(Charsets.UTF_8),
                    headers[Protocol.HEADER_PREPARE_SIG.lowercase()]!!,
                ),
                "the header signature must cover the body actually written to the carrier",
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `SendTarget pin reaches the link provider and defaults to null`() = runBlocking {
        val identity = TlsIdentity.generate()
        val seen = Collections.synchronizedList(mutableListOf<String?>())
        val canned = "HTTP/1.1 500 Nope\r\nContent-Length: 0\r\n\r\n"
        val provider = object : LinkProvider {
            override suspend fun connect(host: String, port: Int, tls: Boolean, timeoutMs: Int): Link =
                RecordingLink(canned)

            override suspend fun connect(
                host: String,
                port: Int,
                tls: Boolean,
                timeoutMs: Int,
                relayFor: String?,
                expectServerFp: String?,
            ): Link = RecordingLink(canned).also { seen.add(expectServerFp) }
        }
        val client = ProtocolClient(identity, links = provider)
        try {
            assertNull(client.info(SendTarget("127.0.0.1", 1, tls = false)))
            val pinnedClient = PeerHttpClient(identity, links = provider)
            assertFailsWith<IOException> {
                pinnedClient.exchange(
                    method = "GET", host = "127.0.0.1", port = 1, tls = false,
                    pathAndQuery = Protocol.PATH_INFO, timeoutMs = 1_000,
                    expectServerFp = identity.fingerprint,
                )
            }
            assertEquals(listOf(null), seen, "a pinned plain HTTP dial must not reach the carrier")
        } finally {
            client.close()
        }
    }

    @Test
    fun `a pin on a plain HTTP route throws before a socket is opened`() = runBlocking {
        val identity = TlsIdentity.generate()
        assertNull(SendTarget("127.0.0.1", 1, tls = false).expectServerFp)
        val thrown = assertFailsWith<IOException> {
            TcpLinkProvider(identity).connect("127.0.0.1", 1, false, 100, null, identity.fingerprint)
        }
        assertTrue(thrown.message!!.contains("plain HTTP"), "unexpected: ${thrown.message}")
    }

    @Test
    fun `a piped TLS peer pin accepts a match and no pin keeps legacy behavior`() {
        val sender = TlsIdentity.generate()
        val server = TlsIdentity.generate()
        for (pin in listOf(server.fingerprint, null)) {
            val pair = PipePair()
            val finish = tlsServer(pair, server) { srv ->
                assertEquals(0x42, srv.input.read())
                srv.output.write(0x43)
            }
            val link = TlsLink(pair.clientLink(), sender, clientMode = true, expectServerFp = pin)
            try {
                link.output.write(0x42)
                assertEquals(0x43, link.input.read())
            } finally {
                link.close()
            }
            assertNull(finish(), "pin=$pin")
        }
    }

    @Test
    fun `a mismatched TLS peer pin aborts before any HTTP request byte is written`() = runBlocking {
        val sender = TlsIdentity.generate()
        val server = TlsIdentity.generate()
        val wrong = TlsIdentity.generate().fingerprint
        val pair = PipePair()
        val serverSaw = AtomicReference<Int?>(null)
        val finish = tlsServer(pair, server) { srv -> serverSaw.set(srv.input.read()) }
        val provider = object : LinkProvider {
            override suspend fun connect(host: String, port: Int, tls: Boolean, timeoutMs: Int): Link =
                error("unused")

            override suspend fun connect(
                host: String,
                port: Int,
                tls: Boolean,
                timeoutMs: Int,
                relayFor: String?,
                expectServerFp: String?,
            ): Link = TlsLink(pair.clientLink(), sender, clientMode = true, expectServerFp = expectServerFp)
        }
        val client = PeerHttpClient(sender, links = provider)
        try {
            val thrown = assertFailsWith<IOException> {
                client.exchange(
                    method = "GET", host = "127.0.0.1", port = 1, tls = true,
                    pathAndQuery = Protocol.PATH_INFO, timeoutMs = 2_000,
                    expectServerFp = wrong,
                )
            }
            assertTrue(thrown.message!!.contains("does not match"), "unexpected: ${thrown.message}")
            val observed = serverSaw.get()
            assertTrue(
                observed == null || observed == -1,
                "the peer must not receive a single request byte: $observed",
            )
            assertNull(finish())
        } finally {
            client.close()
        }
    }

    @Test
    fun `a rogue TLS server certificate is rejected by the client before the request`() = runBlocking {
        val identity = TlsIdentity.generate()
        val rogue = TlsIdentity.generate()
        val wrote = java.util.concurrent.atomic.AtomicBoolean(false)
        val canned = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok"
        // A carrier that ignores the pin entirely still cannot smuggle a
        // different certificate past PeerHttpClient's end-to-end guard.
        val provider = object : LinkProvider {
            override suspend fun connect(host: String, port: Int, tls: Boolean, timeoutMs: Int): Link =
                error("unused")

            override suspend fun connect(
                host: String,
                port: Int,
                tls: Boolean,
                timeoutMs: Int,
                relayFor: String?,
                expectServerFp: String?,
            ): Link = object : Link {
                override val input = ByteArrayInputStream(canned.toByteArray(Charsets.US_ASCII))
                override val output: OutputStream = object : OutputStream() {
                    override fun write(b: Int) = wrote.set(true)
                    override fun write(b: ByteArray, off: Int, len: Int) = wrote.set(true)
                }
                override val peerCertificate: X509Certificate? get() = rogue.certificate
                override fun close() {}
            }
        }
        val client = PeerHttpClient(identity, links = provider)
        try {
            val thrown = assertFailsWith<IOException> {
                client.exchange(
                    method = "GET", host = "127.0.0.1", port = 1, tls = true,
                    pathAndQuery = Protocol.PATH_INFO, timeoutMs = 2_000,
                    expectServerFp = identity.fingerprint,
                )
            }
            assertTrue(thrown.message!!.contains("does not match"), "unexpected: ${thrown.message}")
            assertFalse(wrote.get(), "not a single request byte may reach a rogue server")
        } finally {
            client.close()
        }
    }

    @Test
    fun `verified pins survive an unauthenticated pin flood`() {
        val viaSignature = TlsIdentity.generate()
        val viaTrustedAction = TlsIdentity.generate()
        val bytes = "verified body".toByteArray(Charsets.UTF_8)
        val sig = viaSignature.sign(bytes)
        val flood = List(TlsIdentity.UNVERIFIED_PIN_QUOTA + 16) { TlsIdentity.generate() }
        try {
            // The verify-then-pin order used by CircleCore/CourierEnvelope/
            // CircleResponse/panic promotes the pin to the protected tier.
            assertTrue(TlsIdentity.verifyWithCertificate(viaSignature.certificate, bytes, sig))
            TlsIdentity.pin(viaSignature.certificate)
            TlsIdentity.pinVerified(viaTrustedAction.certificate)

            flood.forEach { TlsIdentity.pin(it.certificate) }

            assertNotNull(TlsIdentity.pinned(viaSignature.fingerprint))
            assertNotNull(TlsIdentity.pinned(viaTrustedAction.fingerprint))
            assertTrue(TlsIdentity.verify(viaSignature.fingerprint, bytes, sig))
        } finally {
            (listOf(viaSignature, viaTrustedAction) + flood).forEach { TlsIdentity.forgetPin(it.fingerprint) }
        }
    }

    @Test
    fun `noncanonical Base64 signatures are refused`() {
        val id = TlsIdentity.generate()
        val bytes = "prepare body".toByteArray(Charsets.UTF_8)
        val sig = id.sign(bytes)
        assertTrue(TlsIdentity.verifyWithCertificate(id.certificate, bytes, sig))
        val decoded = Base64.getDecoder().decode(sig)
        val unpadded = Base64.getEncoder().encodeToString(decoded).trimEnd('=')
        assertFalse(TlsIdentity.verifyWithCertificate(id.certificate, bytes, unpadded), "unpadded signature")
        assertFalse(TlsIdentity.verifyWithCertificate(id.certificate, bytes, "$sig="), "extra padding")
        assertTrue(TlsIdentity.verifyWithCertificate(id.certificate, bytes, "  $sig  "), "outer whitespace is trimmed")

        // Java's decoder accepts non-zero unused bits in the final Base64
        // character; decode-then-re-encode must catch that noncanonical form.
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val lastGroupChar = sig.length - 2
        val idx = alphabet.indexOf(sig[lastGroupChar])
        val mutated = sig.substring(0, lastGroupChar) +
            alphabet[(idx + 1) % alphabet.length] + sig.takeLast(1)
        assertFalse(TlsIdentity.verifyWithCertificate(id.certificate, bytes, mutated), "noncanonical trailing bits")
    }

    /** A self-signed certificate valid only in the past (no KeyUsage extension). */
    private fun certAround(notBefore: Long, notAfter: Long, keyUsage: Int? = null): Pair<X509Certificate, String> {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=flit-test")
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(System.currentTimeMillis()),
            Date(notBefore),
            Date(notAfter),
            name,
            kp.public,
        )
        if (keyUsage != null) {
            builder.addExtension(Extension.keyUsage, true, KeyUsage(keyUsage))
        }
        val cert = JcaX509CertificateConverter().getCertificate(
            builder.build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private)),
        )
        val canonical = "certificate gate".toByteArray(Charsets.UTF_8)
        val signer = Signature.getInstance("SHA256withRSA").apply {
            initSign(kp.private)
            update(canonical)
        }
        return cert to Base64.getEncoder().encodeToString(signer.sign())
    }

    /** Many distinct valid certs from ONE key pair: DER differs by serial, signing is cheap. */
    private fun validCertificates(count: Int): List<Triple<X509Certificate, ByteArray, String>> {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=marker-flood")
        val now = System.currentTimeMillis()
        return (0 until count).map { i ->
            val builder = JcaX509v3CertificateBuilder(
                name,
                BigInteger.valueOf(now + i),
                Date(now - 86_400_000L),
                Date(now + 86_400_000L),
                name,
                kp.public,
            )
            builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
            val cert = JcaX509CertificateConverter().getCertificate(
                builder.build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private)),
            )
            val bytes = "marker-flood-$i".toByteArray(Charsets.UTF_8)
            val signer = Signature.getInstance("SHA256withRSA").apply {
                initSign(kp.private)
                update(bytes)
            }
            Triple(cert, bytes, Base64.getEncoder().encodeToString(signer.sign()))
        }
    }

    @Test
    fun `recent verification markers stay hard bounded and a verified pin survives`() {
        val trusted = TlsIdentity.generate()
        val canonical = "verified earlier".toByteArray(Charsets.UTF_8)
        val sig = trusted.sign(canonical)
        val flood = validCertificates(TlsIdentity.MAX_PINS + 64)
        try {
            assertTrue(TlsIdentity.verifyWithCertificate(trusted.certificate, canonical, sig))
            TlsIdentity.pin(trusted.certificate) // marker consumed -> protected tier
            assertTrue(TlsIdentity.verifiedPinFingerprints().contains(trusted.fingerprint))

            // All inside the same 5 s grace window: the marker ring must cap
            // itself instead of growing with every verified certificate.
            flood.forEach { (cert, bytes, signature) ->
                assertTrue(TlsIdentity.verifyWithCertificate(cert, bytes, signature))
            }
            assertEquals(TlsIdentity.MAX_PINS, TlsIdentity.recentVerifiedSize())

            assertNotNull(TlsIdentity.pinned(trusted.fingerprint))
            assertTrue(TlsIdentity.verify(trusted.fingerprint, canonical, sig))
            assertTrue(TlsIdentity.verifiedPinFingerprints().contains(trusted.fingerprint))

            // The oldest marker was evicted, so its later bare pin is not
            // promoted into the protected tier.
            val oldest = TlsIdentity.fingerprintOf(flood.first().first)
            TlsIdentity.pin(flood.first().first)
            assertFalse(TlsIdentity.verifiedPinFingerprints().contains(oldest))
        } finally {
            TlsIdentity.forgetPin(trusted.fingerprint)
            flood.forEach { TlsIdentity.forgetPin(TlsIdentity.fingerprintOf(it.first)) }
        }
    }

    @Test
    fun `an expired certificate cannot verify a signature even when pinned`() {
        val now = System.currentTimeMillis()
        val (cert, sig) = certAround(now - 172_800_000L, now - 86_400_000L)
        val bytes = "certificate gate".toByteArray(Charsets.UTF_8)
        val fp = TlsIdentity.fingerprintOf(cert)
        assertFalse(TlsIdentity.verifyWithCertificate(cert, bytes, sig))
        TlsIdentity.pinVerified(cert)
        try {
            assertFalse(TlsIdentity.verify(fp, bytes, sig), "expired pins must never verify")
        } finally {
            TlsIdentity.forgetPin(fp)
        }
    }

    @Test
    fun `a certificate that forbids digital signatures cannot verify`() {
        val now = System.currentTimeMillis()
        // keyEncipherment bit only: the extension is present but signing is not allowed.
        val (cert, sig) = certAround(now - 86_400_000L, now + 86_400_000L, KeyUsage.keyEncipherment)
        assertFalse(
            TlsIdentity.verifyWithCertificate(cert, "certificate gate".toByteArray(Charsets.UTF_8), sig),
        )
    }
}
