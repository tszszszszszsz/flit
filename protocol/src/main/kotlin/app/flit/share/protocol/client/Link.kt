package app.flit.share.protocol.client

import app.flit.share.protocol.tls.StreamTls
import app.flit.share.protocol.tls.TlsIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
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
}

/** Fresh link per exchange — we always send `Connection: close`. */
fun interface LinkProvider {
    suspend fun connect(host: String, port: Int, tls: Boolean, timeoutMs: Int): Link
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

    override suspend fun connect(host: String, port: Int, tls: Boolean, timeoutMs: Int): Link {
        return withContext(Dispatchers.IO) {
            val socket: Socket = if (tls) sslFactory.createSocket() else Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = timeoutMs
                if (tls) (socket as SSLSocket).startHandshake()
                SocketLink(socket)
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw e
            }
        }
    }
}

private class SocketLink(private val socket: Socket) : Link {
    override val input: InputStream get() = socket.getInputStream()
    override val output: OutputStream get() = socket.getOutputStream()
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
 */
class TlsLink(
    private val raw: Link,
    identity: TlsIdentity,
    clientMode: Boolean,
) : Link {

    private val tls = if (clientMode) {
        StreamTls.startClient(raw.input, raw.output, identity)
    } else {
        StreamTls.startServer(raw.input, raw.output, identity)
    }

    override val input: InputStream = tls.input
    override val output: OutputStream = tls.output

    override fun close() {
        runCatching { tls.close() } // close_notify so the peer sees clean EOF, not an abrupt carrier drop
        raw.close()
    }
}
