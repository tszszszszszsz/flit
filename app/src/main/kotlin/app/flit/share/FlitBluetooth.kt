package app.flit.share

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.flit.share.protocol.client.Link
import app.flit.share.protocol.client.TlsLink
import app.flit.share.protocol.server.ProtocolServer
import app.flit.share.protocol.tls.TlsIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The missing half of phase B: a real Bluetooth data carrier.
 *
 * BLE only ever discovered peers; the bytes still rode TCP. This file plugs
 * classic Bluetooth RFCOMM into the same [Link] pipe the protocol already
 * speaks — no protocol fork, no extra wire format. TLS runs *inside* the
 * socket exactly as it does over TCP or a relay, so fingerprints, trust,
 * resume and the death contract are unchanged.
 *
 * Android-side only on purpose: the JVM protocol module stays pure.
 *
 * What cannot be verified here: one phone and a bench box with no Bluetooth
 * adapter mean the two-phone RFCOMM dial/accept handshake, the connectable
 * advertising window and a permission/power sheet answered after the option
 * was enabled have no hardware evidence yet. Only compilation and the JVM
 * protocol tests run in this environment; a real device walk is still owed.
 */
internal object FlitBluetooth {

    const val SERVICE_NAME = "Flit"

    /** One listener at a time; a restart or stop cancels a pending retry. */
    @Volatile
    private var serverJob: Job? = null

    /**
     * RFCOMM needs the modern nearby-device grant on Android 12+, but the
     * legacy BLUETOOTH(/ADMIN) pair on 26–30 — where BLUETOOTH_CONNECT does not
     * exist and was never requested. The names and the SDK split are pure
     * policy (see [FlitBluetoothPolicy]); the runtime only maps them to grants.
     */
    fun hasPermission(ctx: Context): Boolean {
        val sdkInt = Build.VERSION.SDK_INT
        val granted = FlitBluetoothPolicy.requiredPermissions(sdkInt)
            .filterTo(HashSet()) { name ->
                ContextCompat.checkSelfPermission(ctx, name) == PackageManager.PERMISSION_GRANTED
            }
        return FlitBluetoothPolicy.allRequiredGranted(sdkInt, granted)
    }

    private fun adapter(ctx: Context): BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun radioOn(ctx: Context): Boolean = runCatching { adapter(ctx)?.isEnabled == true }.getOrDefault(false)

    private fun ready(ctx: Context, adapter: BluetoothAdapter?): Boolean =
        adapter != null && hasPermission(ctx) && runCatching { adapter.isEnabled }.getOrDefault(false)

    /** One stable stream pair per socket — LinkServer takes each stream once. */
    private class SocketLink(private val socket: BluetoothSocket) : Link {
        override val input: InputStream = socket.inputStream
        override val output: OutputStream = socket.outputStream

        /** The MAC, never a reverse lookup: trust and session binding key on it. */
        override val origin: String? get() = runCatching { socket.remoteDevice?.address }.getOrNull()
        override fun close() { runCatching { socket.close() } }
    }

    private class DialResult(val connected: Boolean, val error: String?)

    /** Sender's dial: host is the peer's Bluetooth address. */
    class LinkProvider(private val ctx: Context, private val identity: TlsIdentity) : app.flit.share.protocol.client.LinkProvider {
        override suspend fun connect(host: String, port: Int, tls: Boolean, timeoutMs: Int): Link =
            withContext(Dispatchers.IO) {
                val adapter = adapter(ctx) ?: throw IOException("Bluetooth unavailable")
                if (!hasPermission(ctx)) throw IOException("Nearby-device permission not granted")
                if (!runCatching { adapter.isEnabled }.getOrDefault(false)) throw IOException("Bluetooth radio off")
                runCatching { adapter.cancelDiscovery() }
                val device = runCatching { adapter.getRemoteDevice(host) }
                    .getOrElse { throw IOException("bad Bluetooth address: $host") }
                val socket = runCatching {
                    device.createRfcommSocketToServiceRecord(FlitBle.SERVICE_UUID)
                }.getOrElse { throw IOException("RFCOMM dial unavailable: ${it.message ?: it.javaClass.simpleName}") }
                var handedOff = false
                try {
                    val outcome = withTimeoutOrNull(timeoutMs.toLong()) {
                        try {
                            socket.connect()
                            DialResult(true, null)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            DialResult(false, e.message ?: e.javaClass.simpleName)
                        }
                    }
                    if (outcome?.connected != true) {
                        throw IOException("bluetooth connect to $host ${outcome?.error?.let { "failed: $it" } ?: "timed out"}")
                    }
                    val raw = SocketLink(socket)
                    if (!tls) {
                        handedOff = true
                        raw
                    } else {
                        // The handshake blocks on RFCOMM too; a stuck peer must not
                        // pin an IO thread forever. Closing raw below breaks it.
                        val wrapped = try {
                            withTimeoutOrNull(timeoutMs.toLong()) { TlsLink(raw, identity, clientMode = true) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            throw IOException(
                                FlitBluetoothPolicy.handshakeFailureLog(
                                    host, timeout = false, detail = e.message ?: e.javaClass.simpleName,
                                ), e,
                            )
                        } ?: throw IOException(
                            FlitBluetoothPolicy.handshakeFailureLog(host, timeout = true, detail = null),
                        )
                        handedOff = true
                        wrapped
                    }
                } finally {
                    if (!handedOff) runCatching { socket.close() }
                }
            }
    }

    /**
     * Receiver's accept loop, the RFCOMM twin of the relay subscription
     * worker. Acquisition retries behind the scope's back until permission
     * and radio are there (the sheet may be answered after the option was
     * enabled) or until listen() succeeds. A published listener is no longer
     * one-shot: a transient accept() failure closes that listener, waits a
     * calm, bounded delay, listens again and re-publishes the new socket.
     * Only a cancelled/inactive loop is a real stop. Log lines follow the
     * facts — "listening" is written only after listen() actually returned a
     * socket, and "stopped listening" only when that publication ends.
     */
    fun startServer(
        ctx: Context,
        identity: TlsIdentity,
        server: ProtocolServer,
        scope: CoroutineScope,
        onLog: (String) -> Unit,
        onSocket: (BluetoothServerSocket) -> Unit,
    ) {
        serverJob?.cancel()
        serverJob = scope.launch(Dispatchers.IO) {
            var waitMs = FlitBluetoothPolicy.RETRY_INITIAL_MS
            var published = false
            while (isActive) {
                val adapter = adapter(ctx)
                if (!ready(ctx, adapter)) {
                    waitMs = retryAfter("bluetooth link waiting — permission or radio off", waitMs, onLog)
                    continue
                }
                val listening = try {
                    adapter!!.listenUsingRfcommWithServiceRecord(SERVICE_NAME, FlitBle.SERVICE_UUID)
                } catch (t: Throwable) {
                    if (!isActive) return@launch
                    waitMs = retryAfter(
                        "bluetooth link listen failed: ${t.message ?: t.javaClass.simpleName}",
                        waitMs, onLog,
                    )
                    continue
                }
                // A cancellation that lands after listen() must not publish a
                // socket the engine no longer holds.
                if (!isActive) {
                    runCatching { listening.close() }
                    return@launch
                }
                onSocket(listening)
                published = true
                onLog("bluetooth link: listening")
                // A fresh listener earns a fresh backoff; a later transient
                // accept() failure must not inherit an old, capped delay.
                waitMs = FlitBluetoothPolicy.RETRY_INITIAL_MS
                val decision = try {
                    serveAccepted(listening, identity, server, onLog)
                } finally {
                    runCatching { listening.close() }
                }
                when (decision.action) {
                    FlitBluetoothPolicy.AcceptAction.STOP -> {
                        if (published) onLog("bluetooth link: stopped listening")
                        return@launch
                    }
                    FlitBluetoothPolicy.AcceptAction.RELISTEN -> {
                        // The reason already says the accept path died; the
                        // retry line adds the honest delay before re-listening.
                        waitMs = retryAfter(decision.log ?: "bluetooth link accept ended", waitMs, onLog)
                    }
                }
            }
            if (published) onLog("bluetooth link: stopped listening")
        }
    }

    /**
     * Engine stop while the retry loop is still waiting: the engine's scope
     * outlives a stop, so the job needs an explicit cancellation signal.
     * Children of the accept loop are cancelled with it.
     */
    fun stopServer() {
        serverJob?.cancel()
        serverJob = null
    }

    /** Calm, cancellable backoff: 2s, 4s, 8s … capped at a minute. */
    private suspend fun retryAfter(why: String, waitMs: Long, onLog: (String) -> Unit): Long {
        onLog(FlitBluetoothPolicy.retryLog(why, waitMs))
        delay(waitMs)
        return FlitBluetoothPolicy.nextBackoffMs(waitMs)
    }

    /**
     * Accepts until the listener dies or the job is cancelled. A transient
     * exception is no longer mistaken for an engine stop: the caller closes
     * the listener and re-listens behind the calm delay. Cancellation and a
     * closed/inactive scope are a real stop and carry no failure claim.
     */
    private suspend fun CoroutineScope.serveAccepted(
        listening: BluetoothServerSocket,
        identity: TlsIdentity,
        server: ProtocolServer,
        onLog: (String) -> Unit,
    ): FlitBluetoothPolicy.AcceptDecision {
        while (isActive) {
            val socket = try {
                listening.accept()
            } catch (e: CancellationException) {
                return FlitBluetoothPolicy.acceptExit(active = false, cancelled = true, failure = null)
            } catch (t: Exception) {
                return FlitBluetoothPolicy.acceptExit(
                    active = isActive,
                    cancelled = false,
                    failure = t.message ?: t.javaClass.simpleName,
                )
            } ?: return FlitBluetoothPolicy.acceptExit(
                active = isActive,
                cancelled = false,
                failure = "no socket returned",
            )
            // Child coroutine: one dead peer cannot stall the accept loop, and
            // scope cancellation closes every in-flight link.
            launch(Dispatchers.IO) { serveOne(socket, identity, server, onLog) }
        }
        return FlitBluetoothPolicy.acceptExit(active = false, cancelled = false, failure = null)
    }

    private suspend fun serveOne(
        socket: BluetoothSocket,
        identity: TlsIdentity,
        server: ProtocolServer,
        onLog: (String) -> Unit,
    ) {
        val raw = try {
            SocketLink(socket)
        } catch (t: Throwable) {
            runCatching { socket.close() }
            onLog("bluetooth link socket: ${t.message ?: t.javaClass.simpleName}")
            return
        }
        val peer = raw.origin ?: "peer"
        // The dialer bounds its TLS handshake with the caller's timeout; the
        // receiver must be bounded too. A stuck sender must not pin this IO
        // thread forever. Closing raw on timeout/failure breaks the blocked
        // handshake even though withTimeoutOrNull only cancels the coroutine.
        val link = try {
            withTimeoutOrNull(FlitBluetoothPolicy.HANDSHAKE_TIMEOUT_MS) {
                TlsLink(raw, identity, clientMode = false)
            }
        } catch (e: CancellationException) {
            raw.close()
            throw e
        } catch (t: Throwable) {
            raw.close() // LinkServer never received the link — close the carrier ourselves.
            onLog(FlitBluetoothPolicy.handshakeFailureLog(peer, timeout = false, detail = t.message ?: t.javaClass.simpleName))
            return
        } ?: run {
            raw.close()
            onLog(FlitBluetoothPolicy.handshakeFailureLog(peer, timeout = true, detail = null))
            return
        }
        try {
            server.serve(link) // LinkServer closes the link in its own finally.
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            onLog("bluetooth link: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            runCatching { link.close() }
        }
    }
}

/**
 * Pure policy for the RFCOMM carrier — the parts that must hold on a bench
 * with no radio and no Android runtime. The Android-facing object above asks
 * this one which permissions are real, how long to back off, how an accept()
 * ended, and what a failed handshake is honestly called. Every branch has JVM
 * coverage in [app.flit.share.FlitBluetoothTest].
 */
internal object FlitBluetoothPolicy {

    const val PERM_CONNECT = "android.permission.BLUETOOTH_CONNECT"
    const val PERM_LEGACY = "android.permission.BLUETOOTH"
    const val PERM_LEGACY_ADMIN = "android.permission.BLUETOOTH_ADMIN"

    /** Android 12 replaced the install-time Bluetooth permissions with runtime ones. */
    const val RUNTIME_PERM_SDK = 31

    const val RETRY_INITIAL_MS = 2_000L
    const val RETRY_MAX_MS = 60_000L

    /** Receiver's TLS bound. The dialer uses the caller's connect timeout. */
    const val HANDSHAKE_TIMEOUT_MS = 30_000L

    /**
     * API 26–30 speak the legacy names declared in the manifest, including
     * BLUETOOTH_ADMIN for the listening side. BLUETOOTH_CONNECT is not granted
     * (or even requested) there, so checking it alone kills RFCOMM on those
     * devices. Android 12+ checks only the nearby-device connect grant; scan
     * and advertise belong to FlitBleRadio, not to this carrier.
     */
    fun requiredPermissions(sdkInt: Int): List<String> =
        if (sdkInt >= RUNTIME_PERM_SDK) listOf(PERM_CONNECT)
        else listOf(PERM_LEGACY, PERM_LEGACY_ADMIN)

    /**
     * Every permission this API level needs must actually be granted. Extra
     * grants (e.g. a CONNECT name on a legacy device) never stand in for the
     * missing ones, and no requirements at all is never a yes.
     */
    fun allRequiredGranted(sdkInt: Int, granted: Set<String>): Boolean {
        val needed = requiredPermissions(sdkInt)
        return needed.isNotEmpty() && needed.all(granted::contains)
    }

    /** Calm, cancellable backoff: 2s, 4s, 8s … capped at a minute, no overflow. */
    fun nextBackoffMs(waitMs: Long): Long {
        if (waitMs <= 0) return RETRY_INITIAL_MS
        return minOf(waitMs.coerceAtMost(RETRY_MAX_MS / 2) * 2, RETRY_MAX_MS)
    }

    /** The retry line names the reason and the real delay; no "listening" claim. */
    fun retryLog(why: String, waitMs: Long): String = "$why — retry in ${waitMs / 1000}s"

    enum class AcceptAction { RELISTEN, STOP }

    data class AcceptDecision(val action: AcceptAction, val log: String?)

    /**
     * How accept() left the listener. Cancellation or an inactive scope is a
     * genuine stop: quiet, no retry. Anything else while still active is
     * transient — re-listen and say what actually happened. This function never
     * claims "listening": only a successful listen() may do that.
     */
    fun acceptExit(active: Boolean, cancelled: Boolean, failure: String?): AcceptDecision = when {
        cancelled || !active -> AcceptDecision(AcceptAction.STOP, null)
        failure != null -> AcceptDecision(AcceptAction.RELISTEN, "bluetooth link accept failed: $failure")
        else -> AcceptDecision(AcceptAction.RELISTEN, "bluetooth link accept ended — will listen again")
    }

    /** Timeout and failure are different claims, and both name the peer. */
    fun handshakeFailureLog(peer: String, timeout: Boolean, detail: String?): String =
        if (timeout) "bluetooth link TLS handshake timed out: $peer"
        else "bluetooth link TLS handshake failed: ${detail ?: peer}"
}
