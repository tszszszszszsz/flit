package app.flit.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The direct-peer discovery wire, tested with nothing but bytes: record
 * budgets, roundtrips in both record kinds, codepoint-safe alias clipping,
 * garbage filtering, self-ghost checks, and the dormancy arbiter's truth
 * table (access point owns the roster; hidden mode listens, never announces).
 */
class FlitBleTest {

    private val fpBytes = ByteArray(32) { (it * 7 + 0xA1).toByte() }
    private val fp = fpBytes.joinToString("") { "%02X".format(it) }

    @Test
    fun `extended record roundtrips fingerprint and alias`() {
        val payload = assertIs<ByteArray>(FlitBle.buildPayload("Flit (szszszs)", fp, legacy = false))
        assertEquals(2 + 32 + "Flit (szszszs)".length, payload.size)
        val ann = assertIs<FlitBle.Ann>(FlitBle.parse(payload))
        assertEquals(fp, ann.fingerprint)
        assertEquals("Flit (szszszs)", ann.alias)
        assertFalse(ann.truncated)
    }

    @Test
    fun `legacy record carries the short fingerprint and fits the budget`() {
        val payload = assertIs<ByteArray>(FlitBle.buildPayload("Flit", fp, legacy = true))
        // Rule of the record: flags(3) + service-data header+UUID(18) + payload <= 31.
        assertTrue(
            payload.size + FlitBle.SERVICE_DATA_OVERHEAD + 3 <= FlitBle.LEGACY_RECORD_BYTES,
            "legacy record over budget: payload=${payload.size}",
        )
        assertEquals(10, payload.size, "legacy payload must be magic+version+fp8")
        val ann = assertIs<FlitBle.Ann>(FlitBle.parse(payload))
        assertEquals(fp.take(16), ann.fingerprint)
        assertEquals("", ann.alias)
        assertTrue(ann.truncated)
    }

    @Test
    fun `record arithmetic still closes`() {
        assertEquals(10, FlitBle.LEGACY_PAYLOAD_BUDGET)
    }

    @Test
    fun `extended alias clips on a codepoint boundary`() {
        // 33 CJK characters = 99 bytes; the 64-byte budget backs up to the
        // last complete character, never to a stray continuation byte.
        val long = "谢谢你".repeat(11)
        val payload = assertIs<ByteArray>(FlitBle.buildPayload(long, fp, legacy = false))
        val ann = assertIs<FlitBle.Ann>(FlitBle.parse(payload))
        assertEquals("谢谢你".repeat(7), ann.alias) // 21 chars = 63 bytes
        assertTrue(long.startsWith(ann.alias), "clipped alias must be a clean prefix")
    }

    @Test
    fun `ascii alias under the budget travels untouched`() {
        val ann = assertIs<FlitBle.Ann>(FlitBle.parse(FlitBle.buildPayload("szszszs", fp, legacy = false)))
        assertEquals("szszszs", ann.alias)
    }

    @Test
    fun `garbage never decodes`() {
        assertNull(FlitBle.parse(null))
        assertNull(FlitBle.parse(ByteArray(0)))
        assertNull(FlitBle.parse(byteArrayOf(0x45, 0x11))) // wrong magic
        assertNull(FlitBle.parse(byteArrayOf(0x46, 0x01))) // claims full fp, has none
        assertNull(FlitBle.parse(byteArrayOf(0x46, 0x21, 1, 2, 3, 4, 5, 6, 7, 8))) // future version
    }

    @Test
    fun `bad fingerprints never encode`() {
        assertNull(FlitBle.buildPayload("a", "short", legacy = false))
        assertNull(FlitBle.buildPayload("a", fp.replace('A', 'G'), legacy = false))
    }

    @Test
    fun `self ghost checks both record kinds`() {
        assertTrue(FlitBle.isSelf(fp, truncated = false, selfFp = fp))
        assertTrue(FlitBle.isSelf(fp.take(16), truncated = true, selfFp = fp))
        // The legacy prefix check is blinded to the tail by definition: another
        // device whose announced prefix differs at the first byte is not us.
        assertFalse(FlitBle.isSelf(fp.take(16), truncated = true, selfFp = "0A" + fp.drop(2)))
        assertFalse(FlitBle.isSelf(fp, truncated = false, selfFp = null))
    }

    @Test
    fun `arbiter — access point up keeps the radio dark`() {
        assertEquals(
            FlitBle.Stance(advertise = false, scan = false),
            FlitBle.stance(apUp = true, perms = true, radioOn = true, hidden = false),
        )
    }

    @Test
    fun `arbiter — no ap turns both radios on`() {
        assertEquals(
            FlitBle.Stance(advertise = true, scan = true),
            FlitBle.stance(apUp = false, perms = true, radioOn = true, hidden = false),
        )
    }

    @Test
    fun `arbiter — hidden mode listens but never announces`() {
        assertEquals(
            FlitBle.Stance(advertise = false, scan = true),
            FlitBle.stance(apUp = false, perms = true, radioOn = true, hidden = true),
        )
    }

    @Test
    fun `arbiter — missing permissions or a dead radio keep everything off`() {
        assertEquals(
            FlitBle.Stance(advertise = false, scan = false),
            FlitBle.stance(apUp = false, perms = false, radioOn = true, hidden = false),
        )
        assertEquals(
            FlitBle.Stance(advertise = false, scan = false),
            FlitBle.stance(apUp = false, perms = true, radioOn = false, hidden = false),
        )
    }

    @Test
    fun `rank rule — radio touches only lapses, never fresh address-bearing rows`() {
        val now = 1_000_000L
        assertTrue(FlitBle.rowIsFree(null, now), "unknown peer row is free")
        assertTrue(FlitBle.rowIsFree(now - FlitBle.FRESH_TOUCH_MS, now), "expired row is free")
        assertFalse(FlitBle.rowIsFree(now - 100, now), "fresh UDP/manual rows are owned")
        // Boundary honesty: exactly at the window edge the row judges free —
        // the radio refreshes an entry the reaper is about to reap anyway.
        assertTrue(FlitBle.rowIsFree(0L, FlitBle.FRESH_TOUCH_MS))
    }
}
