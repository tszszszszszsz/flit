package app.flit.share.protocol.client

import app.flit.share.protocol.tls.StreamTls
import app.flit.share.protocol.tls.TlsIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A live byte-pipe to a peer: exactly what [PeerHttpClient] needs to speak
 * HTTP/1.1. TCP+TLS (the protocol's native transport) is the default; the
 * Bluetooth phase rides this same seam with an RFCOMM-backed provider —
 * same wire semantics over a second pipe, zero protocol fork
 * (docs/transport-bluetooth.md).
 */
interface Link : AutoCloseable {
    val input: InputStream
    val output: OutputStream

    /**
     * Remote-side label when the pipe knows one: an IP on TCP, a device
     * address over Bluetooth. Drives the receiver's trust checks and the
     * interrupted-parts registry; null when the carrier can't tell.
     */
    val origin: String? get() = null

    /**
     * Leaf certificate the carrier authenticated in the handshake, when it
     * can tell. [PeerHttpClient] compares its digest with the declared pin
     * before writing the first request byte, whatever the carrier is; links
     * that never terminate TLS leave this null (a pin then fails closed).
     */
    val peerCertificate: X509Certificate? get() = null
}

/** Fresh link per exchange — we always send `Connection: close`. */
fun interface LinkProvider {
    suspend fun connect(host: String, port: Int, tls: Boolean, timeoutMs: Int): Link

    /**
     * Milestone E: an optional target hint for transports that need to know
     * *which peer* a dial is for before any protocol byte is spoken (a relay
     * pairs by fingerprint). Direct carriers — TCP, Bluetooth, the sim —
     * ignore it through this default, so their wiring is unchanged.
     */
    suspend fun connect(host: String, port: Int, tls: Boolean, timeoutMs: Int, relayFor: String?) =
        connect(host, port, tls, timeoutMs)

    /**
     * Sender-side peer pin, forwarded from [PeerHttpClient] on every dial.
     * Carriers that can inspect the TLS peer override this to compare its
     * certificate digest with [expectServerFp] before the first request byte;
     * the default drops it so Bluetooth, sim and relay providers stay as they
     * were. A null pin always means the legacy trust-any behavior.
     */
    suspend fun connect(
        host: String,
        port: Int,
        tls: Boolean,
        timeoutMs: Int,
        relayFor: String?,
        expectServerFp: String?,
    ): Link = connect(host, port, tls, timeoutMs, relayFor)
}

/**
 * The native transport: a plain TCP socket, upgraded to TLS with the owner's
 * identity when [tls] is set. The connect/handshake sequence is preserved
 * byte-for-byte from the implementation that used to live inline in
 * [PeerHttpClient] — this seam exists so the same client can speak over
 * transports that are not TCP, not to change the TCP one.
 */
class TcpLinkProvider(identity: TlsIdentity) : LinkProvider {

    // Built once per provider (the client owns one provider), as before.
    private val sslFactory: SSLSocketFactory = identity.sslContext().socketFactory

    override suspend fun connect(host: String, port: Int, tls: Boolean, timeoutMs: Int): Link =
        connect(host, port, tls, timeoutMs, null, null)

    override suspend fun connect(
        host: String,
        port: Int,
        tls: Boolean,
        timeoutMs: Int,
        relayFor: String?,
        expectServerFp: String?,
    ): Link = withContext(Dispatchers.IO) {
        // A pin is a TLS statement. On a plain carrier it cannot be honored,
        // so fail here — no socket, no request byte, no silent downgrade.
        if (!tls && !expectServerFp.isNullOrBlank()) {
            throw IOException("plain HTTP cannot satisfy a TLS peer fingerprint pin")
        }
        val socket: Socket = if (tls) sslFactory.createSocket() else Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            if (tls) {
                val ssl = socket as SSLSocket
                ssl.startHandshake()
                // Pin the certificate the handshake actually presented, not
                // an IP, a listener config or a caller-supplied label.
                val peer = runCatching {
                    ssl.session.peerCertificates.firstOrNull() as? X509Certificate
                }.getOrNull()
                TlsIdentity.checkPeerPin(peer, expectServerFp)
            }
            SocketLink(socket)
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }
}

internal class SocketLink(private val socket: Socket) : Link {
    override val input: InputStream get() = socket.getInputStream()
    override val output: OutputStream get() = socket.getOutputStream()

    /** Pure string conversion — never hostName, which reverse-resolves and blocks. */
    override val origin: String?
        get() = runCatching { (socket.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress }.getOrNull()

    override val peerCertificate: X509Certificate?
        get() = runCatching {
            (socket as? SSLSocket)?.session?.peerCertificates?.firstOrNull() as? X509Certificate
        }.getOrNull()

    override fun close() {
        runCatching { socket.close() }
    }
}

/**
 * Any [Link] upgraded to mutual TLS across its streams — this is how the
 * Bluetooth phase speaks the same wire TLS the TCP path uses, without
 * sockets. Handshake runs at construction (blocking, like
 * [javax.net.ssl.SSLSocket.startHandshake]); [input]/[output] are the
 * decrypted plain-text streams.
 *
 * @param clientMode true = presents as TLS client (senders); false = server.
 * @param expectServerFp when set on a client link, the peer's leaf certificate
 *   must hash to this fingerprint. Checked at construction — after the
 *   handshake but before any plaintext (HTTP) byte is written — so a wrong
 *   peer never sees a request. Null keeps the legacy trust-any behavior.
 */
class TlsLink(
    private val raw: Link,
    identity: TlsIdentity,
    clientMode: Boolean,
    expectServerFp: String? = null,
) : Link {

    private val tls = if (clientMode) {
        StreamTls.startClient(raw.input, raw.output, identity)
    } else {
        StreamTls.startServer(raw.input, raw.output, identity)
    }

    init {
        if (clientMode && !expectServerFp.isNullOrBlank()) {
            try {
                TlsIdentity.checkPeerPin(tls.peerCertificate(), expectServerFp)
            } catch (e: Exception) {
                runCatching { tls.close() }
                raw.close()
                throw e
            }
        }
    }

    override val input: InputStream = tls.input
    override val output: OutputStream = tls.output

    /** The carrier is still who it was; TLS never changes the peer label. */
    override val origin: String? get() = raw.origin
    override val peerCertificate: X509Certificate? get() = tls.peerCertificate()

    override fun close() {
        runCatching { tls.close() } // close_notify so the peer sees clean EOF, not an abrupt carrier drop
        raw.close()
    }
}
