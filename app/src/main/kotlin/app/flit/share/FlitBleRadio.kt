package app.flit.share

import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.PeriodicAdvertisingParameters
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * The radio layer of direct-peer discovery: the thinnest possible adapter
 * between Android's Bluetooth LE stack and [FlitBle]'s pure wire. Independent
 * of the engine — it takes a stance and identity, translates them into
 * advertise/scan state, and hands decoded announcements back. Every runtime
 * call is defended (radios vanish, permissions lapse, firmware rewrites its
 * opinion of itself); failures are logged, never thrown.
 *
 * Runs only while the engine's foreground service does: the manifest grants
 * the connectedDevice service type so background radio use is legal on
 * Android 14+. No strings, no settings, no vocabulary ever surfaces here.
 */
internal class FlitBleRadio(
    private val ctx: Context,
    /** A decoded peer announcement arrived over the air. */
    private val onPeer: (addr: String, ann: FlitBle.Ann) -> Unit,
    /** One neighbor line for the debug scan dump (rate-limited by address). */
    private val onDump: (String) -> Unit,
) {

    private var advertising = false
    private var advertised: ByteArray? = null
    private var scanning = false
    private var scanningDump = false
    private val seenDump = HashMap<String, String>()

    /** Runtime status. */
    fun hasPermissions(): Boolean = BLE_PERMS.all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    fun radioOn(): Boolean = runCatching { adapter()?.isEnabled == true }.getOrDefault(false)

    /** Applies a stance; returns true when the radio state actually changed. */
    fun apply(stance: FlitBle.Stance, alias: String, fingerprint: String, dump: Boolean): Boolean {
        val advBefore = advertising
        val scanBefore = scanning
        if (stance.advertise) startAdvertising(alias, fingerprint) else stopAdvertising()
        if (stance.scan) startScan(dump) else stopScan()
        return advBefore != advertising || scanBefore != scanning
    }

    /** Full teardown (engine stop): quiet about anything already quiet. */
    fun stopAll() {
        stopAdvertising()
        stopScan()
    }

    // ---- advertising -------------------------------------------------------

    private fun startAdvertising(alias: String, fingerprint: String) {
        val adv = advertiser() ?: return
        val extended = extendedSupported()
        val payload = FlitBle.buildPayload(alias, fingerprint, legacy = !extended) ?: return
        if (advertising && advertised?.contentEquals(payload) == true) return
        stopAdvertising() // rebuild the record from a clean slate (e.g. rename)
        val data = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(FlitBle.SERVICE_UUID), payload)
            .setIncludeDeviceName(false)
            .build()
        if (extended) {
            val params = AdvertisingSetParameters.Builder()
                .setLegacyMode(false)
                .setConnectable(false)
                .setScannable(false)
                .setIncludeTxPower(false)
                .setInterval(AdvertisingSetParameters.INTERVAL_MEDIUM)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
                .build()
            runCatching {
                adv.startAdvertisingSet(params, data, null as AdvertiseData?, null as PeriodicAdvertisingParameters?, null as AdvertiseData?, setCallback)
            }.onFailure { Log.w(TAG, "advertising set failed", it); return }
        } else {
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(false)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .build()
            runCatching { adv.startAdvertising(settings, data, legacyCallback) }
                .onFailure { Log.w(TAG, "legacy advertising failed", it); return }
        }
        advertising = true
        advertised = payload
    }

    /**
     * Overflow chips lack extended advertising: those ride the legacy record —
     * a short fingerprint, no alias. The wire format's own fallback, not a
     * degradation the surface ever learns about.
     */
    private fun extendedSupported(): Boolean =
        runCatching { adapter()?.isLeExtendedAdvertisingSupported == true }.getOrDefault(false)

    private fun stopAdvertising() {
        if (!advertising) return
        val adv = advertiser()
        runCatching { adv?.stopAdvertisingSet(setCallback) }
        runCatching { adv?.stopAdvertising(legacyCallback) }
        advertising = false
        advertised = null
    }

    private val legacyCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {}
        override fun onStartFailure(error: Int) {
            Log.w(TAG, "legacy advertising start failed: $error")
            advertising = false
        }
    }

    private val setCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
            if (status != ADVERTISE_SUCCESS) Log.w(TAG, "advertising set status $status")
        }
    }

    // ---- scanning ----------------------------------------------------------

    private fun startScan(dump: Boolean) {
        if (scanning && scanningDump == dump) return
        val sc = scanner() ?: return
        runCatching { stopScan() }
        val filters = if (dump) emptyList() else listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(FlitBle.SERVICE_UUID)).build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER) // the engine is long-lived; the roster, patient
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        runCatching { sc.startScan(filters, settings, scanCallback) }
            .onFailure { Log.w(TAG, "scan start failed", it); return }
        scanning = true
        scanningDump = dump
    }

    private fun stopScan() {
        if (!scanning) return
        runCatching { scanner()?.stopScan(scanCallback) }
        scanning = false
        scanningDump = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val ann = FlitBle.parse(
                runCatching { result.scanRecord?.getServiceData(ParcelUuid(FlitBle.SERVICE_UUID)) }.getOrNull(),
            )
            if (ann != null) runCatching { onPeer(result.device.address, ann) }
            if (scanningDump) {
                val addr = runCatching { result.device.address }.getOrNull() ?: return
                val name = runCatching { result.scanRecord?.deviceName }
                    .getOrNull()
                    ?.ifBlank { null }
                    ?: runCatching { result.device.name }.getOrNull()?.ifBlank { null }
                val first = synchronized(seenDump) {
                    seenDump.putIfAbsent(addr, name ?: "").also { if (seenDump.size > DUMP_CAP) seenDump.clear() }
                }
                if (first == null) onDump("nearby ${name ?: "an unnamed radio"} ($addr)")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
            scanning = false
        }
    }

    // ---- stack access --------------------------------------------------------

    private fun adapter() = runCatching {
        ctx.getSystemService(BluetoothManager::class.java)?.adapter
    }.getOrNull()

    private fun advertiser(): BluetoothLeAdvertiser? = runCatching { adapter()?.bluetoothLeAdvertiser }.getOrNull()

    private fun scanner(): BluetoothLeScanner? = runCatching { adapter()?.bluetoothLeScanner }.getOrNull()

    companion object {
        const val TAG = "FlitBle"

        /**
         * The nearby-device permission names the no-AP moment's system sheet
         * carries, readable without an instantiation: the activity launches
         * the sheet with these verbatim — the vocabulary shown is the
         * system's own, never ours.
         */
        val nearbyPerms: Array<String> get() = BLE_PERMS.toTypedArray()

        /** The dump dedupe rewrites when environments get crowded. */
        const val DUMP_CAP = 128

        val BLE_PERMS = listOf(
            android.Manifest.permission.BLUETOOTH_ADVERTISE,
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
        )
    }
}
