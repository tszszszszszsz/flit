package app.flit.share.protocol.relay

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
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
 * The protocol-side socket relay. Accept, read exactly one bounded hello,
 * let [RelayCore] decide, tell both sides `OK` — then go blind. Bytes are
 * copied verbatim; TLS, register, prepare and resume stay between the peers.
 *
 * Hardening over the bare pairer:
 *
 *  - the default park wait is finite ([RelayCore.DEFAULT_PARK_TIMEOUT_MS]),
 *    so a stalled hello cannot hold a socket or a registry slot forever;
 *  - the caller's IP is handed to [RelayCore.offer], which backs off repeated
 *    wrong-token guesses per address;
 *  - [RelayCore]'s global park cap bounds memory under a subscription flood;
 *  - binding anything other than loopback without a high-entropy token is
 *    refused at startup, before the listener accepts a connection; the same
 *    policy is passed to [RelayCore] so every hello is gated there too.
 *
 * The wire grammar and the reply lines are unchanged: a peer that spoke to
 * the old relay speaks to this one without a configuration change.
 * [parkTimeoutMs] = 0 keeps the old explicit "wait forever" option.
 */
class RelayServer(
    token: String? = null,
    private val parkTimeoutMs: Long = RelayCore.DEFAULT_PARK_TIMEOUT_MS,
    private val handshakeTimeoutMs: Int = 10_000,
    maxParked: Int = RelayCore.DEFAULT_MAX_PARKED,
) : AutoCloseable {
    private val token: String? = token
    private val parkCap: Int = maxParked
    // Keep the registry's park lifetime at least as long as the socket wait:
    // the socket withdraws its ticket at the buzzer, so the core never picks
    // a live wait clean out from under a caller that configured a longer one.
    private val parkTtl: Long =
        if (parkTimeoutMs > 0) maxOf(parkTimeoutMs, RelayCore.DEFAULT_PARK_TIMEOUT_MS)
        else RelayCore.DEFAULT_PARK_TIMEOUT_MS

    /** Built by [start], once the bind address says whether the gate is mandatory. */
    private lateinit var core: RelayCore
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

    init {
        require(parkTimeoutMs >= 0) { "parkTimeoutMs must be non-negative" }
        require(handshakeTimeoutMs > 0) { "handshakeTimeoutMs must be positive" }
    }

    /** Binding loopback without a token stays possible for local development. */
    fun start(bindPort: Int = 0, bindHost: String = "127.0.0.1"): RelayServer {
        val loopback = runCatching { InetAddress.getByName(bindHost).isLoopbackAddress }.getOrDefault(false)
        RelayCore.bindTokenProblem(token, loopback)?.let { problem ->
            throw IllegalArgumentException("refusing non-loopback relay bind on $bindHost with $problem token")
        }
        core = if (loopback) {
            RelayCore(token = token, maxParked = parkCap, parkTtlMs = parkTtl)
        } else {
            // A production bind enforces the token policy in the pairing core
            // as well, so no hello can slip past the gate after startup.
            RelayCore(
                token = token,
                maxParked = parkCap,
                parkTtlMs = parkTtl,
                requireStrongToken = true,
            )
        }
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

    /** Live park count, exposed so an operator can watch the global cap. */
    fun parkedCount(): Int = if (::core.isInitialized) core.parkedCount() else 0

    private suspend fun handle(sock: Socket) {
        try {
            sock.soTimeout = handshakeTimeoutMs
            val ip = (sock.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress
            val line = readHelloLine(sock.getInputStream())
            val hello = line?.let { RelayHello.parse(it) }
            if (hello == null) {
                reject(sock, "bad-hello")
                return
            }
            val ticket = "c" + tickets.incrementAndGet()
            when (val decision = core.offer(hello, ticket, ip)) {
                is RelayCore.Decision.Refused -> reject(sock, decision.reason)
                is RelayCore.Decision.Paired -> pair(ticket, decision.partnerTicket, sock)
                is RelayCore.Decision.Parked -> awaitPartner(ticket, sock)
            }
        } catch (_: Exception) {
            // A connection that dies mid-handshake is nobody's business.
            runCatching { sock.close() }
        }
    }

    /** Parked: the subscription waits for a sender, but only for a finite time by default. */
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
}
