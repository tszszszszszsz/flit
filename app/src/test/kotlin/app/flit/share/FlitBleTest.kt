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
            FlitBle.stance(apUp = true, perms = true, radioOn = true, hidden = false, phase = FlitBle.DutyPhase.ALWAYS),
        )
    }

    @Test
    fun `arbiter — no ap turns both radios on`() {
        assertEquals(
            FlitBle.Stance(advertise = true, scan = true),
            FlitBle.stance(apUp = false, perms = true, radioOn = true, hidden = false, phase = FlitBle.DutyPhase.ALWAYS),
        )
    }

    @Test
    fun `arbiter — hidden mode listens but never announces`() {
        assertEquals(
            FlitBle.Stance(advertise = false, scan = true),
            FlitBle.stance(apUp = false, perms = true, radioOn = true, hidden = true, phase = FlitBle.DutyPhase.ALWAYS),
        )
    }

    @Test
    fun `arbiter — missing permissions or a dead radio keep everything off`() {
        assertEquals(
            FlitBle.Stance(advertise = false, scan = false),
            FlitBle.stance(apUp = false, perms = false, radioOn = true, hidden = false, phase = FlitBle.DutyPhase.ALWAYS),
        )
        assertEquals(
            FlitBle.Stance(advertise = false, scan = false),
            FlitBle.stance(apUp = false, perms = true, radioOn = false, hidden = false, phase = FlitBle.DutyPhase.ALWAYS),
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

    @Test
    fun `ask — access point up owes nothing, asked or not`() {
        assertNull(FlitBle.raiseAsk(apUp = true, permsOk = false, radioOn = false, askedPerms = false, askedPower = false))
        assertNull(FlitBle.raiseAsk(apUp = true, permsOk = true, radioOn = true, askedPerms = true, askedPower = true))
    }

    @Test
    fun `ask — permissions go first, even with the radio dark`() {
        assertEquals(
            FlitBle.DirectAsk.PERMISSIONS,
            FlitBle.raiseAsk(apUp = false, permsOk = false, radioOn = false, askedPerms = false, askedPower = false),
            "without rights the radio fact is unreadable; the sheet takes its turn first",
        )
    }

    @Test
    fun `ask — power only after the sheet had its turn`() {
        assertEquals(
            FlitBle.DirectAsk.POWER,
            FlitBle.raiseAsk(apUp = false, permsOk = true, radioOn = false, askedPerms = true, askedPower = false),
        )
        assertEquals(
            FlitBle.DirectAsk.POWER,
            FlitBle.raiseAsk(apUp = false, permsOk = false, radioOn = false, askedPerms = true, askedPower = false),
        )
    }

    @Test
    fun `ask — an answer shuts the book for good, a healed fact retires it`() {
        // Both asked: never nag again, whatever the facts are.
        assertNull(FlitBle.raiseAsk(apUp = false, permsOk = false, radioOn = false, askedPerms = true, askedPower = true))
        // Half-asked: silence only for the question that was answered.
        assertNull(FlitBle.raiseAsk(apUp = false, permsOk = true, radioOn = true, askedPerms = true, askedPower = true))
        // Crisis healed by facts: grants arrived on their own.
        assertNull(FlitBle.raiseAsk(apUp = false, permsOk = true, radioOn = true, askedPerms = false, askedPower = false))
    }

    @Test
    fun `duty constants close the ten-on forty-five-off cycle`() {
        assertEquals(5_000L, FlitBle.DUTY_TICK_MS)
        assertEquals(10_000L, FlitBle.DUTY_ON_MS)
        assertEquals(45_000L, FlitBle.DUTY_OFF_MS)
        assertEquals(11L, FlitBle.DUTY_PERIOD_TICKS)
        assertEquals(55_000L, FlitBle.DUTY_PERIOD_MS)
    }

    @Test
    fun `phase wakes for two ticks then rests for nine, every cycle`() {
        val first = listOf(true, true) + List(9) { false }
        assertEquals(first, (0 until 11).map { FlitBle.phaseOn(it.toLong()) })
        assertEquals(first, (11 until 22).map { FlitBle.phaseOn(it.toLong()) }) // not a one-shot
        assertTrue(FlitBle.phaseOn(22))
        assertFalse(FlitBle.phaseOn(21))
    }

    @Test
    fun `phase budgets are injectable and fail closed`() {
        val seq = (0L..7L).map { FlitBle.phaseOn(it, onTicks = 1, periodTicks = 4) }
        assertEquals(listOf(true, false, false, false, true, false, false, false), seq)
        assertFalse(FlitBle.phaseOn(0, onTicks = 0), "a zero window never wakes")
        assertFalse(FlitBle.phaseOn(0, onTicks = -1))
        assertTrue(FlitBle.phaseOn(99, onTicks = 4, periodTicks = 4), "a full-on cycle stays on")
        assertTrue(FlitBle.phaseOn(99, onTicks = 7, periodTicks = 4))
    }

    @Test
    fun `negative ticks wrap instead of waking early`() {
        assertTrue(FlitBle.phaseOn(-11)) // floorMod 0 of 11
        assertTrue(FlitBle.phaseOn(-10))
        assertFalse(FlitBle.phaseOn(-9))
        assertFalse(FlitBle.phaseOn(-1))
    }

    @Test
    fun `clock tick samples wall time at the engine cadence`() {
        assertEquals(0L, FlitBle.clockTick(0L))
        assertEquals(0L, FlitBle.clockTick(4_999L))
        assertEquals(1L, FlitBle.clockTick(5_000L))
        assertEquals(11L, FlitBle.clockTick(55_000L))
    }

    @Test
    fun `four-arg stance stays source-compatible and samples the clock phase`() {
        val before = FlitBle.clockTick()
        val actual = FlitBle.stance(apUp = false, perms = true, radioOn = true, hidden = false)
        val after = FlitBle.clockTick()
        fun expected(tick: Long) = FlitBle.stance(
            apUp = false, perms = true, radioOn = true, hidden = false, tick = tick,
        )
        // A tick boundary may fall on either side of the call; nothing else moves.
        assertTrue(actual == expected(before) || actual == expected(after))
    }

    @Test
    fun `stop rule — a live peer ends scanning but keeps the announce in its window`() {
        val onTick = FlitBle.DUTY_ON_TICKS - 1
        val args = arrayOf(false, true, true, false) // no ap, perms, powered, not hidden
        fun stance(live: Boolean, tick: Long) = FlitBle.stance(
            apUp = args[0], perms = args[1], radioOn = args[2], hidden = args[3],
            livePeer = live, tick = tick,
        )
        assertEquals(FlitBle.Stance(advertise = true, scan = true), stance(false, onTick))
        assertEquals(FlitBle.Stance(advertise = true, scan = false), stance(true, onTick),
            "discovery succeeded: ears off, the announce still runs")
    }

    @Test
    fun `duty rule — the resting window keeps both radios off with or without a peer`() {
        val offTick = FlitBle.DUTY_ON_TICKS // first of the nine resting ticks
        val idle = FlitBle.stance(apUp = false, perms = true, radioOn = true, hidden = false, tick = offTick)
        val withPeer = FlitBle.stance(
            apUp = false, perms = true, radioOn = true, hidden = false,
            livePeer = true, tick = offTick,
        )
        assertEquals(FlitBle.Stance(advertise = false, scan = false), idle)
        assertEquals(idle, withPeer)
    }

    @Test
    fun `stop rule — hidden mode listens only inside the window, never with a live peer`() {
        fun hidden(live: Boolean, tick: Long) = FlitBle.stance(
            apUp = false, perms = true, radioOn = true, hidden = true,
            livePeer = live, tick = tick,
        )
        assertEquals(FlitBle.Stance(advertise = false, scan = true), hidden(false, 0))
        assertEquals(FlitBle.Stance(advertise = false, scan = false), hidden(false, 2))
        assertEquals(FlitBle.Stance(advertise = false, scan = false), hidden(true, 0),
            "hidden mode still obeys the live-peer stop rule")
    }

    @Test
    fun `hard facts beat the phase — access point, permissions and power always dark`() {
        val always = FlitBle.DutyPhase.ALWAYS
        assertEquals(
            FlitBle.Stance(advertise = false, scan = false),
            FlitBle.stance(apUp = true, perms = true, radioOn = true, hidden = false, phase = always, tick = 0),
        )
        assertEquals(
            FlitBle.Stance(advertise = false, scan = false),
            FlitBle.stance(apUp = false, perms = false, radioOn = true, hidden = false, phase = always, tick = 0),
        )
        assertEquals(
            FlitBle.Stance(advertise = false, scan = false),
            FlitBle.stance(apUp = false, perms = true, radioOn = false, hidden = false, phase = always, tick = 0),
        )
    }

    @Test
    fun `phase source is injectable`() {
        val base = { tick: Long, phase: FlitBle.DutyPhase ->
            FlitBle.stance(apUp = false, perms = true, radioOn = true, hidden = false, phase = phase, tick = tick)
        }
        assertEquals(FlitBle.Stance(advertise = false, scan = false), base(100, FlitBle.DutyPhase.NEVER))
        assertEquals(FlitBle.Stance(advertise = true, scan = true), base(100, FlitBle.DutyPhase.ALWAYS))
        val odd = FlitBle.DutyPhase { it % 2L == 1L }
        assertEquals(FlitBle.Stance(advertise = true, scan = true), base(1, odd))
        assertEquals(FlitBle.Stance(advertise = false, scan = false), base(2, odd))
    }

    @Test
    fun `live peer rows are fresh and non-manual only`() {
        val now = 100_000L
        val ttl = 30_000L
        assertFalse(FlitBle.hasLivePeer(emptyMap(), now, ttl))
        assertFalse(FlitBle.hasLivePeer(mapOf("manual@10.0.0.2:25000" to now), now, ttl))
        assertFalse(FlitBle.hasLivePeer(mapOf("fp" to now - ttl), now, ttl), "edge is expired")
        assertFalse(FlitBle.hasLivePeer(mapOf<String, Long?>("fp" to null), now, ttl))
        assertTrue(FlitBle.hasLivePeer(mapOf("fp" to now - ttl + 1), now, ttl))
        assertTrue(FlitBle.hasLivePeer(mapOf("fp" to now), now, ttl))
        assertTrue(
            FlitBle.hasLivePeer(mapOf("manual@10.0.0.2:25000" to now - 1_000, "fp" to now - 1_000), now, ttl),
            "a stale manual stub must not hide a live real row",
        )
    }
}
