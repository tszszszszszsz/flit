package app.flit.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Share-sheet helpers: peer initials for shortcut tiles, deterministic hues,
 * and banner phrasing. Small surfaces, but they are the app's *identity* —
 * a wrong glyph or a hue that drifts per restart is a visible regression.
 */
class SheetUiTest {

    @Test
    fun `initials take letters from the first two words`() {
        assertEquals("AK", SheetUi.initials("Anne Katrin"))
        assertEquals("A", SheetUi.initials("andré"))
        assertEquals("FM", SheetUi.initials("Flit · M2101K7BNY")) // separator · is not a word
        assertEquals("S", SheetUi.initials("Sol"))
        assertEquals("?", SheetUi.initials(""))
        assertEquals("D4", SheetUi.initials("Desk 4")) // digits count as glyphs
    }

    @Test
    fun `hues are in range and stable per fingerprint`() {
        val fp = "AFF5EBF981DE0B15BD7CCFDF488619036DAC50C51330BC0D921592E71BD325DF"
        val h1 = SheetUi.hueFor(fp)
        assertTrue(h1 in 0..359, "hue must be a degree")
        assertEquals(h1, SheetUi.hueFor(fp))
        // differing fingerprints should spread across many hues, not collapse
        val hues = (0 until 32).map { SheetUi.hueFor(it.toString().repeat(4)) }.toSet()
        assertTrue(hues.size >= 8, "32 distinct fingerprints should cover many hues, got $hues")
    }

    @Test
    fun `count phrasing matches the rule of one`() {
        assertEquals("1 file", SheetUi.countSummary(1))
        assertEquals("0 files", SheetUi.countSummary(0))
        assertEquals("12 files", SheetUi.countSummary(12))
    }
}
