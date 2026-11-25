package app.flit.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The "sharing desired" row is the one piece of state a killed process cannot
 * forget: `running` dies with the process, user intent does not. Every check
 * here is pure — no Android context, no engine, no device — because the codec
 * and the restore decision are exactly the seams that must hold when an
 * external viewer drags our activity through a configuration change or death.
 */
class SharingRestoreTest {

    // ==== default false =========================================================

    @Test
    fun `a fresh row is not desired and carries no timestamp`() {
        val row = SharingDesired()
        assertFalse(row.desired)
        assertEquals(0L, row.updatedMs)
    }

    @Test
    fun `absent blank and corrupt rows all decode to not desired`() {
        assertFalse(SharingDesired.decode(null).desired)
        assertFalse(SharingDesired.decode("").desired)
        assertFalse(SharingDesired.decode("   ").desired)
        assertFalse(SharingDesired.decode("{ not json").desired)
        assertFalse(SharingDesired.decode("null").desired)
        assertEquals(SharingDesired(), SharingDesired.decode("garbage"))
    }

    // ==== set true / false ======================================================

    @Test
    fun `with flips the intent and stamps the row`() {
        val on = SharingDesired().with(true, atMs = 1234L)
        assertTrue(on.desired)
        assertEquals(1234L, on.updatedMs)

        val off = on.with(false, atMs = 5678L)
        assertFalse(off.desired)
        assertEquals(5678L, off.updatedMs)
    }

    @Test
    fun `a row round-trips through the persisted string with its intent and stamp`() {
        val row = SharingDesired(desired = true, updatedMs = 9_876_543_210L)
        assertEquals(row, SharingDesired.decode(SharingDesired.encode(row)))
    }

    // ==== old-row decode ========================================================

    @Test
    fun `old bare-word rows decode to the right intent`() {
        for (raw in listOf("true", "1", "yes")) {
            assertTrue(SharingDesired.decode(raw).desired, "old row '$raw' meant sharing was on")
        }
        for (raw in listOf("false", "0", "no")) {
            assertFalse(SharingDesired.decode(raw).desired, "old row '$raw' meant sharing was off")
        }
    }

    @Test
    fun `old rows decode case-insensitively and ignore surrounding whitespace`() {
        assertTrue(SharingDesired.decode("  YES  ").desired)
        assertTrue(SharingDesired.decode("\tTrue\n").desired)
        assertFalse(SharingDesired.decode(" NO ").desired)
        // An old row carried no timestamp — decoding must not invent one.
        assertEquals(0L, SharingDesired.decode("1").updatedMs)
    }

    @Test
    fun `a row from a build with extra keys still decodes — the envelope can grow`() {
        val raw = """{"desired":true,"updatedMs":42,"futureField":"ignore me"}"""
        val row = SharingDesired.decode(raw)
        assertTrue(row.desired)
        assertEquals(42L, row.updatedMs)
    }

    // ==== idempotence ===========================================================

    @Test
    fun `decode is idempotent — encode-decode-encode reproduces the same bytes`() {
        val row = SharingDesired(desired = true, updatedMs = 555L)
        val encoded = SharingDesired.encode(row)
        assertEquals(encoded, SharingDesired.encode(SharingDesired.decode(encoded)))
        // And a second decode of the same row changes nothing at all.
        assertEquals(SharingDesired.decode(encoded), SharingDesired.decode(encoded))
    }

    @Test
    fun `re-applying the same intent is idempotent in desired state`() {
        val once = SharingDesired().with(true, atMs = 100L)
        val twice = once.with(true, atMs = 100L)
        assertEquals(once, twice)
        val offTwice = twice.with(false, atMs = 200L).with(false, atMs = 200L)
        assertFalse(offTwice.desired)
    }

    // ==== pure restore decision (survives process death) =========================

    @Test
    fun `restore fires only for desired intent after process death`() {
        val wanted = SharingDesired(desired = true)
        assertTrue(SharingDesired.shouldRestore(wanted, running = false, starting = false))
        assertFalse(SharingDesired.shouldRestore(SharingDesired(), running = false, starting = false))
    }

    @Test
    fun `restore never restarts a session already up or coming up`() {
        val wanted = SharingDesired(desired = true)
        assertFalse(SharingDesired.shouldRestore(wanted, running = true, starting = false))
        assertFalse(SharingDesired.shouldRestore(wanted, running = false, starting = true))
        assertFalse(SharingDesired.shouldRestore(wanted, running = true, starting = true))
    }
}
