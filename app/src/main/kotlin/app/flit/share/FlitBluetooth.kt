package app.flit.share

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
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

    private const val RETRY_INITIAL_MS = 2_000L
    private const val RETRY_MAX_MS = 60_000L

    /** One listener per engine start; a restart cancels a pending retry. */
    @Volatile
    private var serverJob: Job? = null

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

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
                            throw IOException("bluetooth TLS handshake failed: ${e.message ?: e.javaClass.simpleName}", e)
                        } ?: throw IOException("bluetooth TLS handshake timed out: $host")
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
     * enabled) or until listen() succeeds; a published listener is one-shot —
     * engine stop closes it, accept() then returns and we do not re-listen.
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
            var waitMs = RETRY_INITIAL_MS
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
                try {
                    onSocket(listening)
                    serveAccepted(listening, identity, server, onLog)
                } finally {
                    runCatching { listening.close() }
                }
                return@launch
            }
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
        onLog("$why — retry in ${waitMs / 1000}s")
        delay(waitMs)
        return minOf(waitMs * 2, RETRY_MAX_MS)
    }

    private suspend fun CoroutineScope.serveAccepted(
        listening: BluetoothServerSocket,
        identity: TlsIdentity,
        server: ProtocolServer,
        onLog: (String) -> Unit,
    ) {
        while (isActive) {
            val socket = try {
                listening.accept()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                break // engine stop closed the listener
            } ?: break
            // Child coroutine: one dead peer cannot stall the accept loop, and
            // scope cancellation closes every in-flight link.
            launch(Dispatchers.IO) { serveOne(socket, identity, server, onLog) }
        }
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
        val link = try {
            TlsLink(raw, identity, clientMode = false)
        } catch (e: CancellationException) {
            raw.close()
            throw e
        } catch (t: Throwable) {
            raw.close() // LinkServer never received the link — close the carrier ourselves.
            onLog("bluetooth link handshake: ${t.message ?: t.javaClass.simpleName}")
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
