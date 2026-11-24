package app.flit.share

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import app.flit.share.protocol.client.Link
import app.flit.share.protocol.client.LinkProvider
import app.flit.share.protocol.client.TlsLink
import app.flit.share.protocol.server.ProtocolServer
import app.flit.share.protocol.tls.TlsIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 */
internal object FlitBluetooth {

    const val SERVICE_NAME = "Flit"

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun adapter(ctx: Context): BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun radioOn(ctx: Context): Boolean = runCatching { adapter(ctx)?.isEnabled == true }.getOrDefault(false)

    /** One stable stream pair per socket — LinkServer takes each stream once. */
    private class SocketLink(private val socket: BluetoothSocket) : Link {
        override val input: InputStream = socket.inputStream
        override val output: OutputStream = socket.outputStream

        /** The MAC, never a reverse lookup: trust and session binding key on it. */
        override val origin: String? get() = runCatching { socket.remoteDevice?.address }.getOrNull()
        override fun close() { runCatching { socket.close() } }
    }

    /** Sender's dial: host is the peer's Bluetooth address. */
    class LinkProvider(private val ctx: Context, private val identity: TlsIdentity) : app.flit.share.protocol.client.LinkProvider {
        override suspend fun connect(host: String, port: Int, tls: Boolean, timeoutMs: Int): Link =
            withContext(Dispatchers.IO) {
                val adapter = adapter(ctx) ?: throw IOException("Bluetooth unavailable")
                if (!hasPermission(ctx)) throw IOException("Nearby-device permission not granted")
                runCatching { adapter.cancelDiscovery() }
                val device = runCatching { adapter.getRemoteDevice(host) }
                    .getOrElse { throw IOException("bad Bluetooth address: $host") }
                val socket = runCatching {
                    device.createRfcommSocketToServiceRecord(FlitBle.SERVICE_UUID)
                }.getOrElse { throw IOException("RFCOMM dial unavailable: ${it.message}") }
                val connected = withTimeoutOrNull(timeoutMs.toLong()) {
                    try { socket.connect(); true } catch (e: Exception) { false }
                } == true
                if (!connected) {
                    runCatching { socket.close() }
                    throw IOException("Bluetooth connect timed out: $host")
                }
                val raw = SocketLink(socket)
                if (tls) TlsLink(raw, identity, clientMode = true) else raw
            }
    }

    /**
     * Receiver's accept loop, the RFCOMM twin of the relay subscription
     * worker. Holds the server socket so engine shutdown can unblock accept.
     */
    fun startServer(
        ctx: Context,
        identity: TlsIdentity,
        server: ProtocolServer,
        scope: CoroutineScope,
        onLog: (String) -> Unit,
        onSocket: (BluetoothServerSocket) -> Unit,
    ) {
        if (!hasPermission(ctx)) {
            onLog("bluetooth link off — nearby permission not granted")
            return
        }
        val adapter = adapter(ctx)
        if (adapter == null || !adapter.isEnabled) {
            onLog("bluetooth link waiting — radio off")
            return
        }
        val listening = runCatching {
            adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, FlitBle.SERVICE_UUID)
        }.getOrNull()
        if (listening == null) {
            onLog("bluetooth link could not listen")
            return
        }
        onSocket(listening)
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                val socket = try {
                    listening.accept()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    break // closed by engine stop
                } ?: break
                launch(Dispatchers.IO) {
                    runCatching {
                        server.serve(TlsLink(SocketLink(socket), identity, clientMode = false))
                    }.onFailure { onLog("bluetooth link: ${it.message ?: it.javaClass.simpleName}") }
                }
            }
        }
    }
}
