package app.flit.share.protocol

import app.flit.share.protocol.tls.StreamTls
import app.flit.share.protocol.tls.TlsIdentity
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The TLS-over-streams shim must behave like the TLS-over-socket the whole
 * protocol already rides: a handshake over an arbitrary pipe, byte-exact
 * payloads in both directions, clean close_notify EOF. Client and server run
 * on separate threads over JVM pipes — the exact shape the Bluetooth carrier
 * will have (two streams, no socket). Note the pipe rule that shapes the
 * tests too: an exchange, including its close, all happens on one side's
 * thread (client actions on main, server actions inside [ServerTlsJob]).
 */
class StreamTlsTest {

    /** Two independent carrier directions: a writes→b reads, b writes→a reads. */
    private class PipePair {
        val aOut = PipedOutputStream()
        val bIn = PipedInputStream(PIPE)
        val bOut = PipedOutputStream()
        val aIn = PipedInputStream(PIPE)

        init {
            aOut.connect(bIn)
            bOut.connect(aIn)
        }
    }

    /**
     * Runs handshake + the whole server-side exchange on its own thread
     * (StreamTls is single-threaded sequential by contract, and piped
     * writers need their paired reader alive). [awaitHandshake] lets the
     * client side know the server engine finished — a client's handshake
     * done does NOT imply the server's did.
     */
    private class ServerTlsJob(private val work: (StreamTls) -> Unit) {
        private val handshook = CountDownLatch(1)
        val errRef = AtomicReference<Throwable?>()
        private val fin = CountDownLatch(1)

        fun start(p: PipePair, identity: TlsIdentity): ServerTlsJob {
            thread(isDaemon = true) {
                try {
                    val tls = StreamTls.startServer(p.bIn, p.bOut, identity)
                    handshook.countDown()
                    work(tls)
                } catch (t: Throwable) {
                    errRef.set(t)
                    System.err.println("[StreamTlsTest] server side failed: ${t}")
                    t.stackTrace.take(8).forEach { System.err.println("[StreamTlsTest]   at $it") }
                }
                handshook.countDown()
                fin.countDown()
            }
            return this
        }

        fun awaitHandshake() {
            assertTrue(handshook.await(60, TimeUnit.SECONDS), "server handshake timed out")
        }

        fun finish() {
            assertTrue(fin.await(90, TimeUnit.SECONDS), "server side did not finish in 90s")
            errRef.get()?.let { throw it }
        }
    }

    private fun InputStream.readFully(b: ByteArray, off: Int, len: Int) {
        var at = off
        var left = len
        while (left > 0) {
            val n = read(b, at, left)
            if (n < 0) fail("unexpected EOF after ${len - left} of $len bytes")
            at += n
            left -= n
        }
    }

    /** Varying write sizes to stress record boundaries on both engines. */
    private fun OutputStream.writeChunks(src: ByteArray, rnd: Random) {
        var at = 0
        while (at < src.size) {
            val n = minOf(src.size - at, 1 + rnd.nextInt(63 * 1024))
            write(src, at, n)
            at += n
        }
    }

    @Test
    fun `piped handshake closes the loop in both directions`() {
        val w1 = ByteArray(1000) { (it % 251).toByte() }
        val w2 = ByteArray(3) { (it + 40).toByte() }
        val w3 = ByteArray(13) { (it + 90).toByte() }

        val p = PipePair()
        val identity = TlsIdentity.generate()
        val server = ServerTlsJob { tls ->
            val got = ByteArray(w1.size)
            tls.input.readFully(got, 0, got.size)
            assertContentEquals(w1, got)

            // TWO records written before the client ever reads — records must
            // bank up in the pending stash without loss or reordering.
            tls.output.write(w2)
            tls.output.write(w3)
            tls.close()
        }.start(p, identity)

        val client = StreamTls.startClient(p.aIn, p.aOut, identity)
        server.awaitHandshake()
        client.output.write(w1)

        val rd = ByteArray(16)
        client.input.readFully(rd, 0, rd.size)
        assertContentEquals(w2, rd.copyOfRange(0, 3))
        assertContentEquals(w3, rd.copyOfRange(3, 16))

        // close_notify received and drained → clean EOF, not an error
        assertEquals(-1, client.input.read())
        client.close()

        server.finish()
    }

    @Test
    fun `multi-megabyte sequential duplex roundtrip stays byte-exact`() {
        val size = 4 * 1024 * 1024 + 12345
        val up = ByteArray(size).also { Random(7).nextBytes(it) }
        val down = ByteArray(size).also { Random(8).nextBytes(it) }

        val p = PipePair()
        val identity = TlsIdentity.generate()
        val server = ServerTlsJob { tls ->
            val gotUp = ByteArray(size)
            tls.input.readFully(gotUp, 0, size)
            assertContentEquals(up, gotUp)

            tls.output.writeChunks(down, Random(102))
            tls.close()
        }.start(p, identity)

        val client = StreamTls.startClient(p.aIn, p.aOut, identity)
        server.awaitHandshake()
        client.output.writeChunks(up, Random(101))

        val gotDown = ByteArray(size)
        client.input.readFully(gotDown, 0, size)
        assertContentEquals(down, gotDown)

        assertEquals(-1, client.input.read())
        client.close()

        server.finish()
    }

    @Test
    fun `half-open close still lets the peer see a clean end`() {
        val p = PipePair()
        val identity = TlsIdentity.generate()
        val server = ServerTlsJob { tls ->
            assertEquals(-1, tls.input.read())
            tls.close()
        }.start(p, identity)

        val client = StreamTls.startClient(p.aIn, p.aOut, identity)
        server.awaitHandshake()
        client.close() // before anyone exchanged a payload byte

        server.finish()
    }

    @Test
    fun `close is idempotent and single bytes ride too`() {
        val p = PipePair()
        val identity = TlsIdentity.generate()
        val server = ServerTlsJob { tls ->
            assertEquals(0x5A, tls.input.read())
            tls.output.write(7)
            assertEquals(-1, tls.input.read()) // close_notify from the client
            tls.close()
        }.start(p, identity)

        val client = StreamTls.startClient(p.aIn, p.aOut, identity)
        server.awaitHandshake()
        client.output.write(0x5A)
        assertEquals(7, client.input.read())

        client.close()
        client.close() // must not throw

        server.finish()
    }

    companion object {
        private const val PIPE = 512 * 1024
    }
}
