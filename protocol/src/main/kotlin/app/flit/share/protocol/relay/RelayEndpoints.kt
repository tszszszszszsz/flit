package app.flit.share.protocol.relay

import app.flit.share.protocol.client.Link
import app.flit.share.protocol.client.LinkProvider
import app.flit.share.protocol.client.SocketLink
import app.flit.share.protocol.client.TcpLinkProvider
import app.flit.share.protocol.client.TlsLink
import app.flit.share.protocol.tls.TlsIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * The sender's relay dial (milestone E). A normal route dials the peer; a
 * relay route dials the relay, asks for the peer by fingerprint, then runs
 * the *unmodified* protocol — TLS included — inside the paired pipe. The
 * relay never sees a protocol byte. Direct routes still go through the
 * untouched TCP provider; the hint is the only difference.
 */
class RelayLinkProvider(
    private val identity: TlsIdentity,
    private val token: String? = null,
) : LinkProvider {

    private val tcp = TcpLinkProvider(identity)

    override suspend fun connect(host: String, port: Int, tls: Boolean, timeoutMs: Int): Link =
        tcp.connect(host, port, tls, timeoutMs)

    override suspend fun connect(host: String, port: Int, tls: Boolean, timeoutMs: Int, relayFor: String?): Link {
        if (relayFor.isNullOrBlank()) return tcp.connect(host, port, tls, timeoutMs)
        return withContext(Dispatchers.IO) {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = timeoutMs
                val out = socket.getOutputStream()
                out.write((RelayHello.Request(relayFor, token).encode() + "\n").toByteArray(Charsets.US_ASCII))
                out.flush()
                val reply = readReply(socket)
                if (reply != "OK") throw IOException("relay refused: ${reply ?: "closed before pairing"}")
                // A paired pipe can idle for the length of a large upload;
                // the relay's short handshake timeout must not apply to it.
                socket.soTimeout = 0
                socket.keepAlive = true
                val raw = SocketLink(socket)
                if (tls) TlsLink(raw, identity, clientMode = true) else raw
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw e
            }
        }
    }
}

/**
 * The receiver's subscription (milestone E). Hold one `sub` for our own
 * fingerprint until a sender pairs, then hand the raw pipe to the server's
 * external door — [app.flit.share.protocol.server.ProtocolServer.serve] —
 * which wraps it in server-mode TLS before speaking the protocol. One
 * subscription serves one exchange; the worker re-subscribes immediately
 * (prepare, /partial and each upload chunk arrive on their own pipes).
 *
 * [parkTimeoutMs] = 0 waits indefinitely, which is a subscription's true
 * lifetime; a bounded value lets callers that cannot cancel a blocking
 * read retire their slot and retry with backoff.
 */
object RelaySubscriber {

    suspend fun subscribe(
        host: String,
        port: Int,
        selfFingerprint: String,
        token: String? = null,
        connectTimeoutMs: Int = 10_000,
        parkTimeoutMs: Long = 0,
        /** Lets a long-lived worker hold the socket so shutdown can unblock a parked read. */
        onSocket: (Socket) -> Unit = {},
    ): Link = withContext(Dispatchers.IO) {
        val socket = Socket()
        try {
            onSocket(socket)
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.soTimeout = parkTimeoutMs.toInt()
            socket.keepAlive = true
            val out = socket.getOutputStream()
            out.write((RelayHello.Subscribe(selfFingerprint, token).encode() + "\n").toByteArray(Charsets.US_ASCII))
            out.flush()
            val reply = try {
                readReply(socket)
            } catch (e: SocketTimeoutException) {
                throw IOException("relay did not pair within ${parkTimeoutMs}ms", e)
            }
            if (reply != "OK") throw IOException("relay refused: ${reply ?: "closed before pairing"}")
            // Paired: no read deadline for a long-lived protocol exchange.
            socket.soTimeout = 0
            SocketLink(socket)
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }
}

/** One line, bounded by the relay's own hello limit; null at a clean close. */
private fun readReply(socket: Socket): String? {
    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
    val line = reader.readLine() ?: return null
    return line.trim().takeIf { it.isNotBlank() && it.length <= RelayHello.MAX_LINE }
}
