package app.flit.share.protocol.tls

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * A self-signed device identity used for both roles:
 * server TLS listener and client certificate presented on mutual-TLS handshakes.
 *
 * The announced [fingerprint] is the uppercase-hex SHA-256 of the DER certificate,
 * exactly what official LocalSend servers verify in register/prepare.
 */
class TlsIdentity private constructor(
    val alias: String,
    val keyStore: KeyStore,
    // Held as an immutable String: Ktor/netty zero-fill password arrays after use,
    // so every consumer must get its own fresh copy (see [secretChars]).
    private val secret: String,
) {
    val certificate: X509Certificate get() = keyStore.getCertificate(alias) as X509Certificate

    /** A fresh handle each time: the store owns the key, never a cached array. */
    private val privateKey: PrivateKey get() = keyStore.getKey(alias, secretChars()) as PrivateKey

    /** Uppercase-hex SHA-256 of the certificate, announced as `fingerprint` on the wire. */
    val fingerprint: String by lazy { certificateFingerprint(certificate) }

    /** Signs caller-supplied canonical bytes with this device's private key. */
    fun sign(bytes: ByteArray): String {
        val signer = Signature.getInstance(signatureAlgorithm(privateKey.algorithm))
        signer.initSign(privateKey)
        signer.update(bytes)
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    /**
     * Decrypts one RSA-OAEP/SHA-256 ciphertext with this device's private key.
     * Digest and MGF1 are both pinned to SHA-256 via an explicit [OAEPParameterSpec].
     * Returns null on any failure and never reads or writes the pin store.
     */
    fun decryptOaepSha256(cipher: ByteArray): ByteArray? = try {
        val params = OAEPParameterSpec(
            "SHA-256",
            "MGF1",
            MGF1ParameterSpec.SHA256,
            PSource.PSpecified.DEFAULT,
        )
        val dec = Cipher.getInstance("RSA/ECB/OAEPPadding")
        dec.init(Cipher.DECRYPT_MODE, privateKey, params)
        dec.doFinal(cipher)
    } catch (_: Exception) {
        null
    }

    /** Instance form of the fingerprint-pinned signature check (see companion). */
    fun verify(fp: String, bytes: ByteArray, sig: String): Boolean =
        TlsIdentity.verify(fp, bytes, sig)

    /** Panic compatibility wrapper — identical bytes and algorithm, no second crypto path. */
    fun signPanic(canonical: ByteArray): String = sign(canonical)

    /** Panic compatibility wrapper — identical check, no second crypto path. */
    fun verifyPanic(senderFp: String, canonical: ByteArray, sig: String): Boolean =
        verify(senderFp, canonical, sig)

    /** Fresh mutable copy per call — consumers may zero it after use. */
    fun secretChars(): CharArray = secret.toCharArray()

    /**
     * Key managers whose client-cert selection ignores the peer's `certificate_authorities` list.
     *
     * LocalSend peers are self-signed with no CA hierarchy; v2 servers (rustls-based) list their
     * own subject DN as the only acceptable issuer. Honoring that list makes stock SunX509 select
     * NO certificate and send an empty client flight — then the peer kills the handshake with
     * `certificate_required` (rustls alert 116). Selection must match by key algorithm only.
     */
    fun keyManagers(): Array<KeyManager> {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, secretChars())
        val inner = kmf.keyManagers?.filterIsInstance<X509ExtendedKeyManager>()?.firstOrNull()
            ?: return kmf.keyManagers ?: emptyArray()
        val delegatePrivate = inner.getPrivateKey(alias)

        return arrayOf(object : X509ExtendedKeyManager() {
            override fun chooseClientAlias(
                keyType: Array<out String>,
                issuers: Array<Principal>?,
                socket: Socket?,
            ): String = inner.chooseClientAlias(keyType, null, socket) ?: aliasIfUsable(keyType) ?: alias

            override fun chooseEngineClientAlias(
                keyType: Array<out String>?,
                issuers: Array<Principal>?,
                engine: SSLEngine?,
            ): String = inner.chooseEngineClientAlias(keyType, null, engine) ?: aliasIfUsable(keyType.orEmpty()) ?: alias

            override fun chooseServerAlias(
                keyType: String,
                issuers: Array<Principal>?,
                socket: Socket?,
            ): String = inner.chooseServerAlias(keyType, null, socket) ?: aliasIfUsable(arrayOf(keyType)) ?: alias

            override fun chooseEngineServerAlias(
                keyType: String,
                issuers: Array<Principal>?,
                engine: SSLEngine?,
            ): String = inner.chooseEngineServerAlias(keyType, null, engine) ?: aliasIfUsable(arrayOf(keyType)) ?: alias

            override fun getClientAliases(keyType: String, issuers: Array<Principal>?): Array<String> =
                inner.getClientAliases(keyType, null) ?: emptyArray()

            override fun getServerAliases(keyType: String, issuers: Array<Principal>?): Array<String> =
                inner.getServerAliases(keyType, null) ?: emptyArray()

            override fun getCertificateChain(alias: String?): Array<X509Certificate> =
                inner.getCertificateChain(alias) ?: emptyArray()

            override fun getPrivateKey(alias: String?): PrivateKey =
                if (alias == this@TlsIdentity.alias) delegatePrivate ?: inner.getPrivateKey(alias)
                else inner.getPrivateKey(alias)

            /** The stock KM may still fail its own issuer-free selection for exotic key types; ours is universal. */
            private fun aliasIfUsable(keyTypes: Array<out String>): String? {
                val alg = delegatePrivate?.algorithm ?: return null
                return if (keyTypes.any { it.equals(alg, ignoreCase = true) }) alias else null
            }
        })
    }

    /** One TLS context fits both roles: presents our key, trusts any peer cert. */
    fun sslContext(): SSLContext = SSLContext.getInstance("TLS").apply {
        init(keyManagers(), arrayOf(trustAllManager()), null)
    }

    fun save(file: File) {
        val fos: OutputStream = file.outputStream()
        try {
            keyStore.store(fos, secretChars())
        } finally {
            fos.close()
        }
    }

    companion object {
        const val DEFAULT_ALIAS = "flit-device"
        const val DEFAULT_PASSWORD = "flit"

        /**
         * Maximum number of certificates kept as signature pins. A flood of
         * first-seen Circle/panic certificates must not grow the store without
         * bound; the least recently accessed pin is evicted at this cap.
         */
        const val MAX_PINS = 512

        /**
         * Unauthenticated route pins (a certificate off a wire header, with no
         * verified signature behind it) share only this small LRU. A flood of
         * them can evict other *unverified* pins, never a verified one.
         */
        const val UNVERIFIED_PIN_QUOTA = 16

        /** How long a successful cert-in-hand verification may promote one pin. */
        private const val VERIFY_GRACE_MS = 5_000L

        /**
         * Two-tier pin store: verified pins (installed only after a signature
         * check or an out-of-band trusted action) live in a protected tier;
         * bare [pin] calls land in the small unverified tier and cannot evict
         * the protected ones. Keyed by the SHA-256 of the certificate's DER —
         * the very same value announced as a device fingerprint — so a
         * claimed senderFp can never borrow another device's identity.
         */
        private val panicPins = PinCache(MAX_PINS, UNVERIFIED_PIN_QUOTA)

        /**
         * Short-lived provenance marker: every successful [verifyWithCertificate]
         * records the verified digest. The very next [pin] of that same cert
         * (CircleCore/CourierEnvelope/CircleResponse/panic routes all follow
         * this exact verify-then-pin order) is promoted to the protected tier
         * without those owners needing a new API. Consumed on use.
         *
         * Access-ordered ring: a hard cap at [MAX_PINS] even when more valid
         * certificates are verified inside one grace window. Oldest-accessed
         * marker is dropped unconditionally, so a flood can never grow it
         * without bound; the 5 s grace check at consume time is unchanged.
         */
        private val recentVerifiedLock = Any()
        private val recentVerified = object : LinkedHashMap<String, Long>(MAX_PINS, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
                size > MAX_PINS
        }

        /**
         * Pins [cert] under its own fingerprint; returns that fingerprint.
         * Unverified by default: the small quota bounds what an untrusted
         * route header can write. A verification just completed for this same
         * certificate promotes the pin to the protected tier.
         */
        fun pin(cert: X509Certificate): String {
            val fp = certificateFingerprint(cert)
            if (consumeRecentVerification(fp)) panicPins.putVerified(fp, cert)
            else panicPins.putUnverified(fp, cert)
            return fp
        }

        /**
         * Pins a certificate trusted out of band (QR pairing, an explicit
         * trusted-device action, or a signature already verified through this
         * API). Protected tier: unverified route floods never evict it.
         */
        fun pinVerified(cert: X509Certificate): String {
            val fp = certificateFingerprint(cert)
            panicPins.putVerified(fp, cert)
            synchronized(recentVerifiedLock) { recentVerified.remove(fp) }
            return fp
        }

        private fun consumeRecentVerification(fp: String): Boolean {
            val at = synchronized(recentVerifiedLock) {
                recentVerified.remove(fp.trim().uppercase())
            } ?: return false
            return System.currentTimeMillis() - at <= VERIFY_GRACE_MS
        }

        private fun markRecentVerification(cert: X509Certificate) {
            val fp = certificateFingerprint(cert)
            synchronized(recentVerifiedLock) { recentVerified[fp] = System.currentTimeMillis() }
        }

        /** Test hook: the marker ring is hard-bounded at [MAX_PINS]. */
        internal fun recentVerifiedSize(): Int = synchronized(recentVerifiedLock) { recentVerified.size }

        fun pinned(fingerprint: String): X509Certificate? = panicPins.get(fingerprint)

        fun forgetPin(fingerprint: String) {
            panicPins.remove(fingerprint)
            synchronized(recentVerifiedLock) { recentVerified.remove(fingerprint.trim().uppercase()) }
        }

        /** Snapshot of protected-tier fingerprints, for app/desktop gate tests. */
        fun verifiedPinFingerprints(): Set<String> = panicPins.verifiedFingerprints()

        fun fingerprintOf(cert: X509Certificate): String = certificateFingerprint(cert)

        /** Static form of the pinned signature check, usable by CircleCore/PanicCore. */
        fun verify(fp: String, bytes: ByteArray, sig: String): Boolean =
            verifySignature(fp, bytes, sig)

        /**
         * Verify against a certificate we already hold in hand (e.g. the cert
         * embedded in a first-seen Circle event). Unlike [verify], this never
         * reads or writes the shared pin map: the caller binds [cert] to the
         * claimed fingerprint first, checks the signature, and only then pins.
         * A forged event can therefore no longer poison the pin store.
         */
        fun verifyWithCertificate(cert: X509Certificate, bytes: ByteArray, sig: String): Boolean {
            if (!verifySignatureWithCertificate(cert, bytes, sig)) return false
            // Only a signature that passed (valid cert, canonical Base64,
            // matching key) may mark this digest for the protected pin tier.
            markRecentVerification(cert)
            return true
        }

        /**
         * Public name for the cert-in-hand check used by the prepare route:
         * verify a signature over the EXACT body bytes with the certificate
         * carried in the request. The caller binds that certificate to its
         * own DER digest before trusting anything it claims.
         */
        fun verifySignature(cert: X509Certificate, bytes: ByteArray, sig: String): Boolean =
            verifyWithCertificate(cert, bytes, sig)

        /**
         * Sender-side TLS peer check, run after the handshake and before the
         * first request byte. A null or blank pin keeps the legacy behavior;
         * a declared pin requires the peer's leaf certificate digest to match
         * exactly (case-insensitive hex). Absent cert and mismatch both fail.
         */
        internal fun checkPeerPin(cert: X509Certificate?, expectedFp: String?) {
            if (expectedFp.isNullOrBlank()) return
            val presented = cert?.let(::certificateFingerprint)
                ?: throw IOException("TLS peer presented no certificate for the expected pin")
            if (!presented.equals(expectedFp.trim(), ignoreCase = true)) {
                throw IOException("TLS peer certificate does not match the expected fingerprint")
            }
        }

        /** Panic compatibility wrapper — identical check, no second crypto path. */
        fun verifyPanic(senderFp: String, canonical: ByteArray, sig: String): Boolean =
            verify(senderFp, canonical, sig)

        fun generate(alias: String = DEFAULT_ALIAS): TlsIdentity {
            val password = DEFAULT_PASSWORD.toCharArray()
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val now = System.currentTimeMillis()
            val name = X500Name("CN=flit, O=Flit")
            val builder = JcaX509v3CertificateBuilder(
                name,
                BigInteger.valueOf(now), // uniqueness within a second; good enough for a device cert
                Date(now - TimeUnit.DAYS.toMillis(1)),
                Date(now + TimeUnit.DAYS.toMillis(365L * 30)),
                name,
                keyPair.public,
            )
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
            builder.addExtension(
                Extension.keyUsage, true,
                KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyCertSign),
            )
            builder.addExtension(
                Extension.extendedKeyUsage, false,
                ExtendedKeyUsage(arrayOf(KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth)),
            )
            builder.addExtension(
                Extension.subjectAlternativeName, false,
                GeneralNames(arrayOf(
                    GeneralName(GeneralName.dNSName, "localhost"),
                    GeneralName(GeneralName.iPAddress, "127.0.0.1"),
                )),
            )
            val cert = JcaX509CertificateConverter().getCertificate(
                builder.build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)),
            )
            cert.checkValidity()
            cert.verify(keyPair.public)

            val ks = KeyStore.getInstance("PKCS12")
            ks.load(null)
            ks.setKeyEntry(alias, keyPair.private, password, arrayOf(cert))
            return TlsIdentity(alias, ks, DEFAULT_PASSWORD)
        }

        fun load(file: File, alias: String = DEFAULT_ALIAS): TlsIdentity {
            val ks = KeyStore.getInstance("PKCS12")
            file.inputStream().use { ks.load(it, DEFAULT_PASSWORD.toCharArray()) }
            try {
                val c = ks.getCertificate(alias) as X509Certificate
                c.checkValidity()
            } catch (e: CertificateException) {
                throw IllegalStateException("stored identity certificate is not valid: ${e.message}", e)
            }
            return TlsIdentity(alias, ks, DEFAULT_PASSWORD)
        }
    }
}

/**
 * Bounded, thread-safe two-tier pin store keyed by uppercase-hex certificate
 * fingerprint. Both tiers are access-ordered LRUs ([get] promotes an entry);
 * eviction in one tier never touches the other, so an unauthenticated flood
 * of [putUnverified] calls cannot push out a [putVerified] pin.
 *
 * Kept internal (not part of the public TlsIdentity API) so the eviction
 * policy can be exercised directly with a small capacity in unit tests.
 * [unverifiedCapacity] defaults to [capacity], preserving the original
 * single-tier behaviour for callers that construct their own cache.
 */
internal class PinCache(
    private val capacity: Int = TlsIdentity.MAX_PINS,
    private val unverifiedCapacity: Int = capacity,
) {
    init {
        require(capacity > 0) { "capacity must be positive" }
        require(unverifiedCapacity > 0) { "unverified capacity must be positive" }
    }

    private val lock = Any()
    private val verified = lru(capacity)
    private val unverified = lru(unverifiedCapacity)

    private fun lru(cap: Int): LinkedHashMap<String, X509Certificate> =
        object : LinkedHashMap<String, X509Certificate>(cap, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, X509Certificate>?): Boolean =
                size > cap
        }

    /** Legacy single-tier write: an unauthenticated pin, with a small quota. */
    fun put(fingerprint: String, cert: X509Certificate) = putUnverified(fingerprint, cert)

    fun putUnverified(fingerprint: String, cert: X509Certificate) = synchronized(lock) {
        val fp = fingerprint.trim().uppercase()
        // A verified pin is never downgraded or evicted by a bare pin call.
        if (!verified.containsKey(fp)) unverified[fp] = cert
    }

    fun putVerified(fingerprint: String, cert: X509Certificate) = synchronized(lock) {
        val fp = fingerprint.trim().uppercase()
        unverified.remove(fp)
        verified[fp] = cert
    }

    /** Returns the pinned certificate; verified tier wins and both promote recency. */
    fun get(fingerprint: String): X509Certificate? = synchronized(lock) {
        val fp = fingerprint.trim().uppercase()
        verified[fp] ?: unverified[fp]
    }

    /** Immutable snapshot of protected-tier keys — never a live view. */
    fun verifiedFingerprints(): Set<String> = synchronized(lock) { verified.keys.toSet() }

    fun remove(fingerprint: String) = synchronized(lock) {
        val fp = fingerprint.trim().uppercase()
        unverified.remove(fp)
        verified.remove(fp)
    }

    fun size(): Int = synchronized(lock) { verified.size + unverified.size }

    fun clear() = synchronized(lock) {
        verified.clear()
        unverified.clear()
    }
}

/** Uppercase-hex SHA-256 of a certificate's DER encoding — the wire fingerprint. */
private fun certificateFingerprint(cert: X509Certificate): String =
    MessageDigest.getInstance("SHA-256")
        .digest(cert.encoded)
        .joinToString("") { "%02X".format(it) }

/** RSA identities are what generate() mints; loaded EC keys stay usable too. */
private fun signatureAlgorithm(keyAlgorithm: String): String =
    if (keyAlgorithm.equals("EC", ignoreCase = true)) "SHA256withECDSA" else "SHA256withRSA"

/** Shared by the instance and companion verify/verifyPanic entry points. */
private fun verifySignature(senderFp: String, canonical: ByteArray, sig: String): Boolean {
    val fp = senderFp.trim().uppercase()
    val cert = TlsIdentity.pinned(fp) ?: return false
    if (TlsIdentity.fingerprintOf(cert) != fp) return false
    return verifySignatureWithCertificate(cert, canonical, sig)
}

/** OID of the X.509 KeyUsage extension. */
private const val OID_KEY_USAGE = "2.5.29.15"

/**
 * The one crypto check, reused by pin-based and cert-in-hand verification
 * (so [TlsIdentity.verifyWithCertificate] enforces all three layers):
 * 1. the certificate is currently valid,
 * 2. it is allowed to sign when it carries a KeyUsage extension,
 * 3. the signature is canonical Base64 — decode, re-encode, compare to the
 *    trimmed input — so unpadded or non-canonical strings cannot be smuggled
 *    past a verifier that would have accepted them.
 */
private fun verifySignatureWithCertificate(cert: X509Certificate, canonical: ByteArray, sig: String): Boolean {
    return try {
        cert.checkValidity()
        if (cert.getExtensionValue(OID_KEY_USAGE) != null) {
            val usage = cert.keyUsage
            if (usage == null || usage.size <= 0 || !usage[0]) return false // digitalSignature
        }
        val trimmed = sig.trim()
        val decoded = Base64.getDecoder().decode(trimmed)
        if (Base64.getEncoder().encodeToString(decoded) != trimmed) return false
        val verifier = Signature.getInstance(signatureAlgorithm(cert.publicKey.algorithm))
        verifier.initVerify(cert.publicKey)
        verifier.update(canonical)
        verifier.verify(decoded)
    } catch (_: Exception) {
        false // expired/not-yet-valid, malformed base64, wrong algorithm, or forgery
    }
}

/** Trusts everything — peers are self-signed by design; identity is the announced fingerprint. */
fun trustAllManager(): X509TrustManager = object : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
