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
