package app.flit.share.protocol.transport

import app.flit.share.protocol.client.Link
import app.flit.share.protocol.client.LinkProvider
import app.flit.share.protocol.client.TlsLink
import app.flit.share.protocol.tls.TlsIdentity
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.channels.Channel

/**
 * The simulated carrier: an in-memory duplex pipe with a wire's manners. It
 * exists so "the same semantics over a second transport" is *testable
 * anywhere* — the parity suite (the full receiver flow, plain and TLS) and
 * the harness's `bt-sim` rig both run on exactly this carrier, so what the
 * proof passes on is what the rig passes on. RFCOMM's actual socket arrives
 * in the app that dials into [LinkProvider]; it only ever meets this shape.
 *
 * Unbounded queueing: the simulated wire never blocks its writer. Back‑
 * pressure is a socket property, not a semantics property — the phase-B
 * proofs are about the HTTP/TLS spine, not flow control.
 */

/**
 * One direction: an in-memory carrier half. Writes chunk into an
 * unbounded queue (the wire never blocks the writer); [In] drains it;
 * a writer close delivers EOF.
 */
internal class Conduit {
    private val q = LinkedBlockingQueue<Any>()
    private val EOF = Any()
    @Volatile
    private var readerGone = false

    inner class Out : OutputStream() {
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            if (readerGone) return // destination neither reads nor cares
            q.put(b.copyOfRange(off, off + len))
        }

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun close() {
            readerGone = true
            q.put(EOF)
        }
    }

    /** Strictly one reader per direction, sequential — the exchange shape. */
    inner class In : InputStream() {
        private var cur: ByteArray? = null
        private var at = 0
        private var eof = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (true) {
                val c = cur
                if (c != null) {
                    val n = minOf(len, c.size - at)
                    System.arraycopy(c, at, b, off, n)
                    at += n
                    if (at == c.size) {
                        cur = null
                        at = 0
                    }
                    return n
                }
                if (eof) return -1
                val next = q.take()
                if (next == EOF) {
                    eof = true
                    continue
                }
                cur = next as ByteArray
            }
        }

        override fun close() {
            eof = true
            readerGone = true
            q.clear()
        }
    }

    fun out(): OutputStream = Out()
    fun inp(): InputStream = In()
}

/**
 * A pair of conduits crossed into a link pair, and nothing else: one
 * "connection" of the simulated carrier. Fresh pair per exchange — the
 * client's own rule (see [Link]) — matches a connect-per-exchange carrier.
 * The harness sees only the two link ends; the conduits stay private.
 */
class PipePair {
    private val toServer = Conduit()
    private val toClient = Conduit()

    private val clientIn: InputStream by lazy { toClient.inp() }
    private val clientOut: OutputStream by lazy { toServer.out() }
    private val serverIn: InputStream by lazy { toServer.inp() }
    private val serverOut: OutputStream by lazy { toClient.out() }

    fun clientLink(): Link = object : Link {
        // ONE stream object per direction, forever: a carrier side that
        // hands out a fresh wrapper per access would orphan whatever a
        // previous access buffered mid-chunk (real RFCOMM carriers keep
        // one stream per connection; so does SocketLink/TlsLink).
        override val input: InputStream get() = clientIn
        override val output: OutputStream get() = clientOut
        override fun close() {
            runCatching { clientOut.close() }
            runCatching { clientIn.close() }
        }
    }

    fun serverLink(origin: String): Link = object : Link {
        override val input: InputStream get() = serverIn
        override val output: OutputStream get() = serverOut
        override val origin: String
            get() = origin
        override fun close() {
            runCatching { serverOut.close() }
            runCatching { serverIn.close() }
        }
    }
}

/**
 * Dials by handing the server half of a fresh conduit pair to the accept
 * loop — the carrier is "locality-free": host/port mean nothing (the pipe
 * is the connection already); the same shape a Bluetooth dialer wraps when
 * the underlying socket already both directions.
 */
class SimLinkProvider(
    private val identity: TlsIdentity,
    private val tls: Boolean,
    private val inbox: Channel<PipePair>,
) : LinkProvider {    override suspend fun connect(host: String, port: Int, tls: Boolean, timeoutMs: Int): Link {
        val p = PipePair()
        inbox.send(p)
        val raw = p.clientLink()
        return if (tls) TlsLink(raw, identity, clientMode = true) else raw
    }
}
