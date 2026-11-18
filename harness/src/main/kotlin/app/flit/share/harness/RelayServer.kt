package app.flit.share.harness

import app.flit.share.protocol.relay.RelayCore
import app.flit.share.protocol.relay.RelayHello
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * The socket half of milestone E: `flit-cli relay`. Accept, read exactly
 * one bounded hello, let [RelayCore] decide, tell both sides `OK` — then go
 * blind. Bytes are copied verbatim in both directions; the TLS, register,
 * prepare and resume inside the pipe are the peers' own, so the relay can
 * neither read nor forge a transfer (docs, milestone E).
 *
 * One pair, one story: whichever side arrives second owns the copy loops;
 * the parked side simply waits for the pair to conclude, which is exactly
 * the lifetime the receiver's subscription deserves. [parkTimeoutMs] = 0
 * means "wait forever" (a subscription is long-lived by design); tests and
 * cautious operators can bound it.
 */
class RelayServer(
    token: String? = null,
    private val parkTimeoutMs: Long = 0,
    private val handshakeTimeoutMs: Int = 10_000,
) : AutoCloseable {

    private val core = RelayCore(token)
    private val tickets = AtomicLong()
    private val parked = ConcurrentHashMap<String, Parked>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null

    /** The actually-bound port (useful with `start(0)`); -1 before start. */
    val port: Int get() = server?.localPort ?: -1

    private class Parked(val socket: Socket) {
        /** Completes when the pair (if any) has concluded; never completes exceptionally. */
        val done = CompletableFuture<Unit>()
    }

    fun start(bindPort: Int = 0, bindHost: String = "0.0.0.0"): RelayServer {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(bindHost, bindPort))
        server = socket
        scope.launch {
            while (isActive) {
                val conn = try {
                    socket.accept()
                } catch (_: Exception) {
                    break // closed: stop accepting; live pairs keep their own sockets
                }
                launch { handle(conn) }
            }
        }
        return this
    }

    private suspend fun handle(sock: Socket) {
        try {
            sock.soTimeout = handshakeTimeoutMs
            val line = readHelloLine(sock.getInputStream())
            val hello = line?.let { RelayHello.parse(it) }
            if (hello == null) {
                reject(sock, "bad-hello")
                return
            }
            val ticket = "c" + tickets.incrementAndGet()
            when (val decision = core.offer(hello, ticket)) {
                is RelayCore.Decision.Refused -> reject(sock, decision.reason)
                is RelayCore.Decision.Paired -> pair(ticket, decision.partnerTicket, sock)
                is RelayCore.Decision.Parked -> awaitPartner(ticket, sock)
            }
        } catch (_: Exception) {
            // A connection that dies mid-handshake is nobody's business.
            runCatching { sock.close() }
        }
    }

    /** Parked: the subscription waits, possibly forever, for a sender. */
    private fun awaitPartner(ticket: String, sock: Socket) {
        val self = Parked(sock)
        parked[ticket] = self
        try {
            if (parkTimeoutMs > 0) self.done.get(parkTimeoutMs, TimeUnit.MILLISECONDS)
            else self.done.get()
        } catch (_: TimeoutException) {
            if (parked.remove(ticket, self)) {
                // Nobody came: retire the slot so a ghost can never pair.
                core.withdraw(ticket)
                reject(sock, "timeout")
            } else {
                // A partner claimed this ticket at the buzzer; let it play out.
                self.done.get()
            }
        } catch (_: InterruptedException) {
            parked.remove(ticket, self)
            core.withdraw(ticket)
        } finally {
            runCatching { sock.close() }
        }
    }

    /** Arrived second: tell both sides OK and pump until the pair ends. */
    private suspend fun pair(ticket: String, partnerTicket: String, sock: Socket) {
        log("pair $partnerTicket + $ticket")
        val partner = parked.remove(partnerTicket)
        if (partner == null) {
            reject(sock, "partner-gone")
            return
        }
        try {
            writeLine(sock, "OK")
            writeLine(partner.socket, "OK")
            sock.soTimeout = 0
            partner.socket.soTimeout = 0
            copyPair(sock, partner.socket)
        } catch (_: Exception) {
            // Half a handshake: the pair simply never starts.
        } finally {
            runCatching { sock.close() }
            runCatching { partner.socket.close() }
            partner.done.complete(Unit)
            log("pair $partnerTicket + $ticket closed")
        }
    }

    /**
     * Both directions at once; whichever ends first closes the pair, which
     * unblocks the other direction's read. The sockets are closed by the
     * completion hooks, so `joinAll` cannot hang on a blocked read.
     */
    private suspend fun copyPair(a: Socket, b: Socket) {
        kotlinx.coroutines.coroutineScope {
            val jobs = listOf(
                launch(Dispatchers.IO) { pump(a.getInputStream(), b.getOutputStream()) },
                launch(Dispatchers.IO) { pump(b.getInputStream(), a.getOutputStream()) },
            )
            jobs.forEach { job ->
                job.invokeOnCompletion {
                    runCatching { a.close() }
                    runCatching { b.close() }
                }
            }
            jobs.joinAll()
        }
    }

    private fun pump(from: InputStream, to: OutputStream) {
        try {
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: Exception) {
            // Closed or reset: the pair is over; that is all this pump knows.
        }
    }

    private fun readHelloLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (out.size() <= RelayHello.MAX_LINE) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) return out.toString(Charsets.US_ASCII).trimEnd('\r')
            out.write(b)
        }
        return null
    }

    private fun writeLine(sock: Socket, line: String) {
        sock.getOutputStream().write((line + "\n").toByteArray(Charsets.US_ASCII))
        sock.getOutputStream().flush()
    }

    private fun reject(sock: Socket, reason: String) {
        runCatching { writeLine(sock, "ERR $reason") }
        runCatching { sock.close() }
    }

    override fun close() {
        scope.cancel()
        runCatching { server?.close() }
        server = null
        parked.values.forEach {
            runCatching { it.socket.close() }
            it.done.complete(Unit)
        }
        parked.clear()
    }

    private fun log(line: String) = println("${ts()} $line")
}
