package app.flit.share

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

// Both spellings work for callers: the nested types are the real declarations,
// these aliases keep the bare names from the contract usable in this package.
typealias RoutePolicy = FlitKnown.RoutePolicy
typealias Device = FlitKnown.Device

/** Pure JSON codec over the known-device store (no Android involved). */
fun encode(devices: List<Device>): String = FlitKnown.encode(devices)

/** Pure JSON codec over the known-device store (no Android involved). */
fun decode(raw: String?): List<Device> = FlitKnown.decode(raw)

/**
 * Locally known devices: QR-paired peers and their routing preferences.
 *
 * Everything that touches the wire is pure JSON so it can be tested on the
 * JVM; the Android half is a thin SharedPreferences adapter over [Store].
 * Nothing here talks to a cloud, an account, or a remote service.
 */
object FlitKnown {

    /** Which carrier may be used to reach a known device. */
    enum class RoutePolicy { AUTO, WIFI_ONLY, BLUETOOTH_ONLY, RELAY_ONLY }

    @Serializable
    data class Device(
        /** TlsIdentity fingerprint — stable until reinstall, pinned by QR. */
        val fingerprint: String,
        /** What this device is called locally (user-editable). */
        val alias: String,
        /** Optional free-form note shown next to the alias. */
        val label: String = "",
        /** Bluetooth MAC for the direct carrier, when it has one. */
        val mac: String? = null,
        /** "host:port" of a relay this device can be reached through. */
        val relay: String? = null,
        /** Pairing token carried by a v2 QR code. */
        val token: String? = null,
        val routePolicy: RoutePolicy = RoutePolicy.AUTO,
        /** This device may relay other people's traffic for us. */
        val relayFor: Boolean = false,
        /** Forward panic alerts onward from this device (explicit only). */
        val panicForward: Boolean = false,
        /** When a QR code for this device was last scanned. */
        val scannedMs: Long = 0,
    )

    private const val PREFS_NAME = "flit"
    private const val KEY_DEVICES = "known.devices"
    private const val KEY_QR_ONLY = "known.qrOnly"

    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(Device.serializer())

    // ==== pure codec ============================================================

    fun encode(devices: List<Device>): String = json.encodeToString(listSerializer, devices)

    /**
     * Old rows lack the newer fields and simply pick up their defaults; keys
     * written by a newer version are ignored. Unparseable/blobs from a broken
     * install are treated as no known devices rather than crashing the app.
     */
    fun decode(raw: String?): List<Device> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(listSerializer, raw) }.getOrDefault(emptyList())
    }

    // ==== the seam the prefs wrapper is built on ================================

    /** Minimal prefs surface; a fake implementation keeps store logic on the JVM. */
    interface Store {
        fun string(key: String): String?
        fun putString(key: String, value: String)
        fun bool(key: String, default: Boolean): Boolean
        fun putBool(key: String, value: Boolean)
    }

    fun all(store: Store): List<Device> = decode(store.string(KEY_DEVICES))

    private fun save(store: Store, devices: List<Device>) =
        store.putString(KEY_DEVICES, encode(devices))

    /**
     * One row per fingerprint. A re-scan refreshes the device details (alias,
     * address, relay, token, scanned time) but must not erase the preferences
     * this device owner chose locally: route policy, relay-forward and
     * panic-forward flags, and the free-form label. Those are merged from the
     * existing row whenever the incoming value is absent or left at its
     * default, so scanning the same QR twice is idempotent for settings.
     * An explicitly non-default policy/capability in [device] still wins; use
     * [replace] when a caller needs to write an explicit false/blank value.
     */
    fun put(store: Store, device: Device) {
        val existing = all(store).firstOrNull { it.fingerprint == device.fingerprint }
        val merged = if (existing == null) device else device.copy(
            alias = device.alias.ifBlank { existing.alias },
            label = device.label.ifBlank { existing.label },
            mac = device.mac?.takeIf { it.isNotBlank() } ?: existing.mac,
            relay = device.relay?.takeIf { it.isNotBlank() } ?: existing.relay,
            token = device.token?.takeIf { it.isNotBlank() } ?: existing.token,
            routePolicy = if (device.routePolicy == RoutePolicy.AUTO) {
                existing.routePolicy
            } else device.routePolicy,
            relayFor = device.relayFor || existing.relayFor,
            panicForward = device.panicForward || existing.panicForward,
            scannedMs = maxOf(device.scannedMs, existing.scannedMs),
        )
        replace(store, merged)
    }

    /**
     * Explicit whole-row write for settings controls that must be able to
     * clear a value (turn relayFor off, blank a label, reset a policy). QR
     * intake uses [put] so a re-scan never clobbers those local choices.
     */
    fun replace(store: Store, device: Device) {
        val next = all(store).filterNot { it.fingerprint == device.fingerprint } + device
        save(store, next)
    }

    fun forget(store: Store, fp: String) = save(store, all(store).filterNot { it.fingerprint == fp })

    fun qrOnly(store: Store): Boolean = store.bool(KEY_QR_ONLY, false)

    fun setQrOnly(store: Store, on: Boolean) = store.putBool(KEY_QR_ONLY, on)

    fun policy(store: Store, fp: String): RoutePolicy =
        all(store).firstOrNull { it.fingerprint == fp }?.routePolicy ?: RoutePolicy.AUTO

    // ==== Android adapter (thin; no logic lives here) ===========================

    private fun store(ctx: Context): Store {
        val prefs = (ctx.applicationContext ?: ctx).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return object : Store {
            override fun string(key: String): String? = prefs.getString(key, null)
            override fun putString(key: String, value: String) {
                prefs.edit().putString(key, value).apply()
            }
            override fun bool(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
            override fun putBool(key: String, value: Boolean) {
                prefs.edit().putBoolean(key, value).apply()
            }
        }
    }

    fun all(ctx: Context): List<Device> = all(store(ctx))

    fun put(ctx: Context, device: Device) = put(store(ctx), device)

    fun replace(ctx: Context, device: Device) = replace(store(ctx), device)

    fun forget(ctx: Context, fp: String) = forget(store(ctx), fp)

    fun qrOnly(ctx: Context): Boolean = qrOnly(store(ctx))

    fun setQrOnly(ctx: Context, on: Boolean) = setQrOnly(store(ctx), on)

    fun policy(ctx: Context, fp: String): RoutePolicy = policy(store(ctx), fp)
}
