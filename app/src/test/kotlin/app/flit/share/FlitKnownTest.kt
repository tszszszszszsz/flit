package app.flit.share

import app.flit.share.FlitKnown.RoutePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The known-device store is exercised without a device: JSON is pure and the
 * prefs wrapper sits on a tiny [FlitKnown.Store] seam, so only the adapter in
 * FlitKnown.kt knows Android exists.
 */
class FlitKnownTest {

    private class MemoryStore : FlitKnown.Store {
        val strings = mutableMapOf<String, String>()
        val bools = mutableMapOf<String, Boolean>()

        override fun string(key: String) = strings[key]
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun bool(key: String, default: Boolean) = bools[key] ?: default
        override fun putBool(key: String, value: Boolean) { bools[key] = value }
    }

    private fun device(
        fp: String = "fp-1",
        alias: String = "Ada's phone",
        policy: RoutePolicy = RoutePolicy.AUTO,
    ) = FlitKnown.Device(
        fingerprint = fp,
        alias = alias,
        label = "desk",
        mac = "AA:BB:CC:DD:EE:FF",
        relay = "relay.example:4337",
        token = "pair-token",
        routePolicy = policy,
        relayFor = true,
        panicForward = true,
        scannedMs = 1_700_000_000_000L,
    )

    @Test
    fun `encode decode roundtrip keeps every field`() {
        val source = listOf(device(), device(fp = "fp-2", alias = "Ben", policy = RoutePolicy.RELAY_ONLY))
        val raw = encode(source)
        assertEquals(source, decode(raw))
        assertEquals(source, FlitKnown.decode(FlitKnown.encode(source)))
    }

    @Test
    fun `empty and missing input decode to no devices`() {
        assertEquals(emptyList(), decode(null))
        assertEquals(emptyList(), decode(""))
        assertEquals(emptyList(), decode("   "))
        assertEquals(emptyList(), decode("[]"))
    }

    @Test
    fun `old rows pick up defaults and future keys are ignored`() {
        val old = decode("""[{"fingerprint":"old","alias":"Old phone","futureField":"x"}]""")
        assertEquals(1, old.size)
        assertEquals("old", old[0].fingerprint)
        assertEquals("Old phone", old[0].alias)
        assertEquals("", old[0].label)
        assertEquals(null, old[0].mac)
        assertEquals(null, old[0].relay)
        assertEquals(null, old[0].token)
        assertEquals(RoutePolicy.AUTO, old[0].routePolicy)
        assertEquals(false, old[0].relayFor)
        assertEquals(false, old[0].panicForward)
        assertEquals(0L, old[0].scannedMs)
    }

    @Test
    fun `a corrupt blob is ignored instead of crashing`() {
        assertEquals(emptyList(), decode("{not json"))
    }

    @Test
    fun `qr only defaults false and toggles`() {
        val prefs = MemoryStore()
        assertFalse(FlitKnown.qrOnly(prefs))
        FlitKnown.setQrOnly(prefs, true)
        assertTrue(FlitKnown.qrOnly(prefs))
        FlitKnown.setQrOnly(prefs, false)
        assertFalse(FlitKnown.qrOnly(prefs))
        assertEquals(false, prefs.bools["known.qrOnly"])
    }

    @Test
    fun `policy is overridden by the latest put, unknown fingerprints stay auto`() {
        val prefs = MemoryStore()
        assertEquals(RoutePolicy.AUTO, FlitKnown.policy(prefs, "nobody"))
        FlitKnown.put(prefs, device(policy = RoutePolicy.WIFI_ONLY))
        assertEquals(RoutePolicy.WIFI_ONLY, FlitKnown.policy(prefs, "fp-1"))
        FlitKnown.put(prefs, device(policy = RoutePolicy.BLUETOOTH_ONLY))
        assertEquals(RoutePolicy.BLUETOOTH_ONLY, FlitKnown.policy(prefs, "fp-1"))
        assertEquals(1, FlitKnown.all(prefs).size)
    }

    @Test
    fun `a re-scan merges local choices and refreshes the device details`() {
        val prefs = MemoryStore()
        FlitKnown.put(prefs, device(policy = RoutePolicy.RELAY_ONLY).copy(scannedMs = 100L))
        val scanned = FlitKnown.Device(
            fingerprint = "fp-1",
            alias = "New alias",
            mac = "11:22:33:44:55:66",
            relay = "10.0.0.2:4337",
            token = "new-token",
            scannedMs = 200L,
        )
        FlitKnown.put(prefs, scanned)

        val row = FlitKnown.all(prefs).single()
        assertEquals("fp-1", row.fingerprint)
        assertEquals("New alias", row.alias)
        assertEquals("desk", row.label, "a QR has no label — keep the local note")
        assertEquals(RoutePolicy.RELAY_ONLY, row.routePolicy, "local route choice survives a re-scan")
        assertTrue(row.relayFor, "an opt-in is not silently cleared by a scan")
        assertTrue(row.panicForward)
        assertEquals(200L, row.scannedMs)
        assertEquals("11:22:33:44:55:66", row.mac)
        assertEquals("10.0.0.2:4337", row.relay)
        assertEquals("new-token", row.token)
    }

    @Test
    fun `an older or blank re-scan never rolls back saved fields`() {
        val prefs = MemoryStore()
        FlitKnown.put(prefs, device(policy = RoutePolicy.BLUETOOTH_ONLY).copy(scannedMs = 900L))
        FlitKnown.put(
            prefs,
            FlitKnown.Device(
                fingerprint = "fp-1",
                alias = "",
                label = "",
                mac = null,
                relay = null,
                token = null,
                scannedMs = 10L,
            ),
        )
        val row = FlitKnown.all(prefs).single()
        assertEquals("Ada's phone", row.alias)
        assertEquals("desk", row.label)
        assertEquals("AA:BB:CC:DD:EE:FF", row.mac)
        assertEquals("relay.example:4337", row.relay)
        assertEquals("pair-token", row.token)
        assertEquals(RoutePolicy.BLUETOOTH_ONLY, row.routePolicy)
        assertTrue(row.relayFor)
        assertTrue(row.panicForward)
        assertEquals(900L, row.scannedMs, "an older scan time must not rewind the row")
    }

    @Test
    fun `replace writes explicit false and blank values a settings toggle needs`() {
        val prefs = MemoryStore()
        FlitKnown.put(prefs, device())
        val current = FlitKnown.all(prefs).single()
        FlitKnown.replace(
            prefs,
            current.copy(relayFor = false, panicForward = false, label = "", routePolicy = RoutePolicy.WIFI_ONLY),
        )
        val row = FlitKnown.all(prefs).single()
        assertFalse(row.relayFor)
        assertFalse(row.panicForward)
        assertEquals("", row.label)
        assertEquals(RoutePolicy.WIFI_ONLY, row.routePolicy)
        assertEquals(1, FlitKnown.all(prefs).size, "merge and replace both keep one row per fingerprint")
    }

    @Test
    fun `forget drops only the named device`() {
        val prefs = MemoryStore()
        FlitKnown.put(prefs, device())
        FlitKnown.put(prefs, device(fp = "fp-2", alias = "Ben"))
        assertEquals(2, FlitKnown.all(prefs).size)
        FlitKnown.forget(prefs, "fp-1")
        assertEquals(listOf("fp-2"), FlitKnown.all(prefs).map { it.fingerprint })
        FlitKnown.forget(prefs, "missing")
        assertEquals(1, FlitKnown.all(prefs).size)
    }

    @Test
    fun `prefs rows live under the flit keys`() {
        val prefs = MemoryStore()
        FlitKnown.put(prefs, device())
        FlitKnown.setQrOnly(prefs, true)
        assertEquals(1, decode(prefs.strings["known.devices"]).size)
        assertEquals(true, prefs.bools["known.qrOnly"])
    }
}
