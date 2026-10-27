package app.flit.share.protocol.tls

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException

/**
 * TLS over arbitrary [InputStream]/[OutputStream] pairs — the pipe [TlsLink]
 * rides, and later every Bluetooth carrier.
 *
 * The JDK offers TLS only through sockets: [SSLEngine] needs real sockets and
 * there is no public API that upgrades two already-open streams. RFCOMM gives
 * us streams, not sockets. This class is that missing adapter: an [SSLEngine]
 * driven by hand over raw streams, exposing plain `input`/`output` streams so
 * the same HTTP code that talks over TCP talks over any pipe.
 *
 * Threading: exclusive, sequential use — write, then read (exactly the
 * request-response-per-connection pattern every Flit exchange follows;
 * `Connection: close` means one exchange per connection). All engine state is
 * guarded by one monitor because handshake pumping interleaves wrap/unwrap.
 */
class StreamTls private constructor(
    private val engine: SSLEngine,
    private val rawIn: InputStream,
    private val rawOut: OutputStream,
) : Closeable {

    private val mutex = Any()

    // ==== engine-facing state (guarded by [mutex]) ===========================

    /** Ciphertext read from [rawIn]; bytes at [netPos] until [netLen] are unconsumed. */
    private var netBuf = ByteArray(2 * CHUNK)
    private var netPos = 0
    private var netLen = 0

    /** Decrypted, not-yet-read application bytes (peer → us). */
    private var pending = EMPTY
    private var pendingPos = 0

    /** Scratch for [SSLEngine.unwrap]; contents are stashed into [pending]. */
    private val appBuf = ByteBuffer.allocate(CHUNK + 2048)

    /** Scratch for [SSLEngine.wrap]; ciphertext is flushed to [rawOut] per record. */
    private var outBuf = ByteBuffer.allocate(CHUNK + 2048)

    private var handshakeDone = false

    /** The peer sent close_notify. Pending bytes drain first, then EOF. */
    private var inboundClosed = false

    /** Carrier EOF seen; with everything drained, reads return -1. */
    private var rawEof = false

    // ==== plain-text surface =================================================

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (readInto(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = readInto(b, off, len)
        override fun available(): Int = synchronized(mutex) { pending.size - pendingPos }
        override fun close() = this@StreamTls.close()
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            ONE[0] = b.toByte()
            writeInto(ONE, 0, 1)
        }
        override fun write(b: ByteArray, off: Int, len: Int) = writeInto(b, off, len)
        override fun flush() {
            // writeInto flushes after every record; nothing to add.
            runCatching { rawOut.flush() }
        }

        override fun close() = this@StreamTls.close()
    }

    /**
     * Drives the handshake to completion. Blocking and sequential: when the
     * engine asks for data it reads [rawIn], and the peer does the same on its
     * own thread — the flight exchange is the standard TLS dance.
     */
    fun handshake() {
        synchronized(mutex) {
            engine.beginHandshake()
            var guard = 0
            while (true) {
                when (engine.handshakeStatus) {
                    SSLEngineResult.HandshakeStatus.FINISHED,
                    SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING,
                    -> {
                        handshakeDone = true
                        return
                    }

                    SSLEngineResult.HandshakeStatus.NEED_TASK -> runTasks()
                    SSLEngineResult.HandshakeStatus.NEED_WRAP -> wrapEmpty()
                    SSLEngineResult.HandshakeStatus.NEED_UNWRAP,
                    SSLEngineResult.HandshakeStatus.NEED_UNWRAP_AGAIN,
                    -> pumpUnwrap()
                }
                if (++guard > 4096) throw SSLException("TLS handshake did not converge")
            }
        }
    }

    /** Reads up to [len] decrypted bytes into [b][off..]; 1..len, or -1 at EOF. */
    private fun readInto(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        synchronized(mutex) {
            // Hand off already-decrypted bytes first — never unwrap for fun.
            val handoff = servePending(b, off, len)
            if (handoff > 0) return handoff

            // Nothing buffered, no plaintext coming: close_notify received (the
            // peer's language for done), inbound engine-done, or carrier gone.
            if (inboundClosed || engine.isInboundDone || rawEof) return -1

            var guard = 0
            while (true) {
                if (++guard > 8192) throw SSLException("TLS read did not converge")

                when (engine.handshakeStatus) {
                    SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                        runTasks()
                        continue
                    }

                    SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                        wrapEmpty()
                        continue
                    }

                    // NEED_UNWRAP runs through the unwrap below; FINISHED can
                    // appear on the client for the server's finish flight.
                    else -> Unit
                }

                // A fully-received record? Unwrap what's buffered.
                if (netLen > netPos) {
                    val view = ByteBuffer.wrap(netBuf, netPos, netLen - netPos)
                    appBuf.clear()
                    val r = engine.unwrap(view, appBuf)
                    netPos = view.position()

                    when (r.status) {
                        SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                            // Partial record — top up from the carrier below.
                        }

                        SSLEngineResult.Status.BUFFER_OVERFLOW -> continue // appBuf always fits one record

                        SSLEngineResult.Status.CLOSED -> {
                            // Peer's close_notify. Final app bytes may ride along.
                            inboundClosed = true
                            if (r.bytesProduced() > 0) {
                                stashProduced(r.bytesProduced())
                                val n = servePending(b, off, len)
                                if (n > 0) return n
                            }
                            return -1
                        }

                        SSLEngineResult.Status.OK -> {}
                    }

                    if (r.bytesProduced() > 0) {
                        stashProduced(r.bytesProduced())
                        val n = servePending(b, off, len)
                        if (n > 0) return n
                    }
                    if (r.status != SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                        // Record consumed, zero plaintext (e.g. the record was
                        // run-delegated TLS machinery) — loop and keep pumping.
                        continue
                    }
                }

                // No usable record buffered: line up a blocking carrier read.
                if (!readRaw()) return -1
            }
        }
    }

    /** Compacts [netBuf] and pulls one chunk from the carrier (true = data, false = EOF). */
    private fun readRaw(): Boolean {
        if (netPos > 0) {
            val keep = netLen - netPos
            netBuf.copyInto(netBuf, 0, netPos, netLen)
            netPos = 0
            netLen = keep
        }
        if (netLen == netBuf.size) netBuf = netBuf.copyOf(netBuf.size * 2)
        val got = try {
            rawIn.read(netBuf, netLen, netBuf.size - netLen)
        } catch (e: IOException) {
            if (!handshakeDone) throw SSLException("carrier died before TLS handshake completed", e)
            throw e
        }
        if (got < 0) {
            rawEof = true
            if (!handshakeDone) throw SSLException("peer closed during TLS handshake")
            return false
        }
        netLen += got
        return true
    }

    /** Copies buffered plaintext into [b], 1..len; 0 when none buffered. */
    private fun servePending(b: ByteArray, off: Int, len: Int): Int {
        val have = pending.size - pendingPos
        if (have <= 0) return 0
        val n = minOf(len, have)
        System.arraycopy(pending, pendingPos, b, off, n)
        pendingPos += n
        if (pendingPos == pending.size) {
            pending = EMPTY
            pendingPos = 0
        }
        return n
    }

    /** Wraps [b][off..off+len] as TLS record(s), flushing each to [rawOut]. */
    private fun writeInto(b: ByteArray, off: Int, len: Int) {
        if (len == 0) return
        if (off < 0 || off + len > b.size) throw IndexOutOfBoundsException()
        synchronized(mutex) {
            if (engine.isOutboundDone) throw IOException("TLS output is closed")
            var srcOff = off
            var srcRemain = len
            var guard = 0
            while (true) {
                if (++guard > 8192) throw SSLException("TLS write did not converge")
                if (engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) runTasks()

                val src = ByteBuffer.wrap(b, srcOff, srcRemain)
                outBuf.clear()
                val r = engine.wrap(src, outBuf)
                when (r.status) {
                    SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                        outBuf = ByteBuffer.allocate(engine.session.packetBufferSize + 2048)
                        continue // SSLEngine consumed nothing on overflow
                    }

                    SSLEngineResult.Status.CLOSED -> throw IOException("TLS output closed by peer")
                    SSLEngineResult.Status.BUFFER_UNDERFLOW ->
                        throw SSLException("unexpected underflow while writing")
                    SSLEngineResult.Status.OK -> {}
                }

                if (r.bytesProduced() > 0) {
                    outBuf.flip()
                    // OutputStream contract: writes the full span or blocks/errors.
                    rawOut.write(outBuf.array(), outBuf.position(), outBuf.remaining())
                    rawOut.flush()
                }

                srcOff += r.bytesConsumed()
                srcRemain -= r.bytesConsumed()
                if (srcRemain == 0) return
                if (r.bytesConsumed() == 0) {
                    // Handshake appendages may need promoting (client finish
                    // flow) before more app data fits.
                    if (engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP ||
                        engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK
                    ) {
                        continue
                    }
                    throw SSLException("TLS write consumed nothing")
                }
            }
        }
    }

    /** Stashes freshly-decrypted app bytes from [appBuf] into [pending]. */
    private fun stashProduced(produced: Int) {
        if (produced == 0) return
        val fresh = ByteArray(produced)
        appBuf.flip()
        appBuf.get(fresh)
        val rest = if (pendingPos < pending.size) pending.copyOfRange(pendingPos, pending.size) else EMPTY
        pending = rest + fresh
        pendingPos = 0
    }

    /**
     * One unwrap attempt while handshaking — [handshake]'s NEED_UNWRAP pump.
     * Unwraps a buffered record if the bytes present form one; otherwise
     * blocks on the carrier for the rest of the record (or its first bytes).
     */
    private fun pumpUnwrap() {
        if (netLen > netPos) {
            val view = ByteBuffer.wrap(netBuf, netPos, netLen - netPos)
            appBuf.clear()
            val r = engine.unwrap(view, appBuf)
            netPos = view.position()
            when (r.status) {
                SSLEngineResult.Status.CLOSED -> inboundClosed = true
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                    // Partial record: block on the carrier for the rest.
                    readRaw()
                    return
                }
                else -> {}
            }
            if (r.bytesProduced() > 0) stashProduced(r.bytesProduced())
            return
        }
        // Nothing usable buffered: block for carrier bytes.
        if (!readRaw()) throw SSLException("peer closed during TLS handshake")
    }

    private fun wrapEmpty() {
        while (true) {
            if (engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) runTasks()
            outBuf.clear()
            val r = engine.wrap(ByteBuffer.allocate(0), outBuf)
            if (r.bytesProduced() > 0) {
                outBuf.flip()
                rawOut.write(outBuf.array(), outBuf.position(), outBuf.remaining())
                rawOut.flush()
            }
            when (r.status) {
                SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                    outBuf = ByteBuffer.allocate(engine.session.packetBufferSize + 2048)
                    continue
                }

                else -> return
            }
        }
    }

    private fun runTasks() {
        while (true) {
            val task = engine.delegatedTask ?: return
            task.run()
        }
    }

    /** close_notify out, then the carrier streams. Idempotent. */
    override fun close() {
        synchronized(mutex) {
            pending = EMPTY
            pendingPos = 0
            if (engine.isOutboundDone) {
                quietCloseCarrier()
                return
            }
            engine.closeOutbound()
            var guard = 0
            while (!engine.isOutboundDone && guard++ < 64) {
                if (engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                    runTasks()
                    continue
                }
                outBuf.clear()
                val r = try {
                    engine.wrap(ByteBuffer.allocate(0), outBuf)
                } catch (_: SSLException) {
                    break // mid-handshake close: best effort
                }
                if (r.bytesProduced() > 0) {
                    outBuf.flip()
                    runCatching {
                        rawOut.write(outBuf.array(), outBuf.position(), outBuf.remaining())
                        rawOut.flush()
                    }
                }
                if (r.bytesProduced() == 0 && r.status != SSLEngineResult.Status.BUFFER_OVERFLOW) break
            }
            rawEof = true
            quietCloseCarrier()
        }
    }

    private fun quietCloseCarrier() {
        runCatching { rawOut.close() }
        runCatching { rawIn.close() }
    }

    companion object {
        private val EMPTY = ByteArray(0)
        private val ONE = ByteArray(1)
        private const val CHUNK = 16 * 1024

        /** Client mode: presents [identity], trusts the peer's self-signed cert. */
        fun startClient(rawIn: InputStream, rawOut: OutputStream, identity: TlsIdentity): StreamTls {
            val engine = identity.sslContext().createSSLEngine()
            engine.useClientMode = true
            // Flit trusts at the application layer (roster fingerprints), never
            // by hostname; keep the engine from inventing its own checks.
            engine.sslParameters = engine.sslParameters.apply { endpointIdentificationAlgorithm = null }
            return StreamTls(engine, rawIn, rawOut).also { it.handshake() }
        }

        /** Server mode: [wantClientAuth] — present a cert if you have one. */
        fun startServer(
            rawIn: InputStream,
            rawOut: OutputStream,
            identity: TlsIdentity,
            wantClientAuth: Boolean = true,
        ): StreamTls {
            val engine = identity.sslContext().createSSLEngine()
            engine.useClientMode = false
            engine.wantClientAuth = wantClientAuth
            return StreamTls(engine, rawIn, rawOut).also { it.handshake() }
        }
    }
}
