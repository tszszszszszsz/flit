package app.flit.share.protocol

import app.flit.share.protocol.ideas.peeralias.PeerAlias
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure derivation tests for [PeerAlias]. The four fixed vectors exercise the
 * three fingerprints that share the stem `AAAA` plus one with stem `BBBB`, so
 * a shared leading prefix is proven not to produce a shared label.
 */
class PeerAliasTest {
    private val fpA = "A".repeat(64) // AAAA-72E2FE (also the doc example)
    private val fpB = "B".repeat(64) // BBBB-45D4CE
    private val fpA0 = "AAAA" + "0".repeat(60) // AAAA-4DED47
    private val fpA1 = "AAAA" + "1".repeat(60) // AAAA-8F254B

    private fun aliasOf(fingerprint: String) = PeerAlias.of(fingerprint)!!

    private fun independentSuffix(canonical: String): String {
        val input = "FLITALIAS1".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0) +
            canonical.toByteArray(Charsets.US_ASCII)
        val digest = MessageDigest.getInstance("SHA-256").digest(input)
        val hex = "0123456789ABCDEF"
        val out = StringBuilder(PeerAlias.SUFFIX_CHARS)
        for (i in 0 until 3) {
            val b = digest[i].toInt() and 0xFF
            out.append(hex[b ushr 4]).append(hex[b and 0x0F])
        }
        return out.toString()
    }

    @Test
    fun `four fixed vectors are stable`() {
        assertEquals("AAAA-72E2FE", PeerAlias.text(fpA))
        assertEquals("BBBB-45D4CE", PeerAlias.text(fpB))
        assertEquals("AAAA-4DED47", PeerAlias.text(fpA0))
        assertEquals("AAAA-8F254B", PeerAlias.text(fpA1))

        val alias = aliasOf(fpA)
        assertEquals("AAAA", alias.stem)
        assertEquals("72E2FE", alias.suffix)
        assertEquals("AAAA-72E2FE", alias.text)
        assertEquals(alias.text, alias.toString())
        assertEquals(11, PeerAlias.ALIAS_CHARS)
    }

    @Test
    fun `suffix cross-checks against independent domain-tagged SHA-256`() {
        for (fp in listOf(fpA, fpB, fpA0, fpA1)) {
            assertEquals(independentSuffix(fp), aliasOf(fp).suffix, "fingerprint $fp")
        }
        val fp128 = "AB".repeat(64)
        assertEquals(independentSuffix(fp128), aliasOf(fp128).suffix)
    }

    @Test
    fun `fingerprints sharing a stem get distinct suffixes`() {
        val stems = listOf(aliasOf(fpA), aliasOf(fpA0), aliasOf(fpA1)).map { it.stem }
        assertEquals(listOf("AAAA", "AAAA", "AAAA"), stems)
        val suffixes = listOf(aliasOf(fpA), aliasOf(fpA0), aliasOf(fpA1)).map { it.suffix }
        assertEquals(3, suffixes.toSet().size, "a shared prefix must not share a label")
        assertEquals("BBBB", aliasOf(fpB).stem)
    }

    @Test
    fun `normalization folds case surrounding whitespace and colons`() {
        val expected = aliasOf(fpA).text
        assertEquals(expected, PeerAlias.text(fpA.lowercase()))
        assertEquals(expected, PeerAlias.text("  $fpA  "))
        assertEquals(expected, PeerAlias.text("\t$fpA\n"))

        val colonized = fpA.chunked(2).joinToString(":")
        assertEquals(expected, PeerAlias.text(colonized))
        assertEquals(expected, PeerAlias.text("  ${colonized.lowercase()}  "))
        assertEquals(expected, PeerAlias.text(colonized + ":"))
    }

    @Test
    fun `derivation is deterministic and order-independent for the 128-char form`() {
        val fp128 = "AB".repeat(64)
        val first = PeerAlias.text(fp128)
        assertNotNull(first)
        assertEquals(11, first.length)
        repeat(8) { assertEquals(first, PeerAlias.text(fp128)) }
        PeerAlias.text(fpB)
        PeerAlias.text(fpA0)
        assertEquals(first, PeerAlias.text(fp128))
        assertEquals(first, PeerAlias.text(fp128.lowercase()))
    }

    @Test
    fun `accepts exactly the minimum and maximum canonical lengths`() {
        assertNotNull(PeerAlias.of("A".repeat(PeerAlias.MIN_FINGERPRINT_CHARS)))
        assertNotNull(PeerAlias.of("AB".repeat(64))) // 128 hex chars
        assertNull(PeerAlias.of("A".repeat(62)))
        assertNull(PeerAlias.of("A".repeat(63))) // short and odd
        assertNull(PeerAlias.of("A".repeat(65))) // odd
        assertNull(PeerAlias.of("AB".repeat(65))) // 130, beyond max canonical
    }

    @Test
    fun `the 256-char cap is applied before trimming and colon removal`() {
        val atCap = " ".repeat(96) + fpA + " ".repeat(96) // exactly 256 raw chars
        assertEquals(PeerAlias.MAX_INPUT_CHARS, atCap.length)
        assertNotNull(PeerAlias.of(atCap))

        assertNull(PeerAlias.of(" ".repeat(200) + fpA)) // 264 raw, trims to valid
        assertNull(PeerAlias.of(fpA + " ".repeat(200)))
        assertNull(PeerAlias.of(fpA.chunked(2).joinToString(":") + ":".repeat(200)))
    }

    @Test
    fun `null blank and whitespace-only inputs are refused`() {
        assertNull(PeerAlias.of(null))
        assertNull(PeerAlias.text(null))
        assertNull(PeerAlias.of(""))
        assertNull(PeerAlias.of("   "))
        assertNull(PeerAlias.of("\t\n\r"))
        assertNull(PeerAlias.of("\u00A0"))
    }

    @Test
    fun `short odd-length and non-hex inputs are refused`() {
        assertNull(PeerAlias.of("A".repeat(63)))
        assertNull(PeerAlias.of("A".repeat(65)))
        assertNull(PeerAlias.of("G".repeat(64)))
        assertNull(PeerAlias.of("Z".repeat(64)))
        assertNull(PeerAlias.of("O".repeat(64)))
        assertNull(PeerAlias.of("A".repeat(63) + "G"))
    }

    @Test
    fun `embedded whitespace control characters and stray punctuation are refused`() {
        assertNull(PeerAlias.of(fpA.take(32) + "  " + fpA.drop(34)))
        assertNull(PeerAlias.of(fpA.take(32) + "\t\t" + fpA.drop(34)))
        assertNull(PeerAlias.of(fpA.take(32) + "\n\n" + fpA.drop(34)))
        assertNull(PeerAlias.of(fpA.take(32) + "\u0000\u0000" + fpA.drop(34)))
        assertNull(PeerAlias.of(fpA.take(32) + "--" + fpA.drop(34)))
        assertNull(PeerAlias.of(fpA.take(63) + "\u00E9")) // non-ASCII hex lookalike
    }

    @Test
    fun `isValid checks shape only while matches verifies derivation case-insensitively`() {
        val alias = aliasOf(fpA)
        assertTrue(PeerAlias.isValid(alias.text))
        assertTrue(PeerAlias.isValid(alias.text.lowercase()))
        assertTrue(PeerAlias.isValid("1234-ABCDEF"))
        assertFalse(PeerAlias.isValid("12345-ABCDE")) // separator in the wrong place
        assertFalse(PeerAlias.isValid("1234ABCDEF")) // no separator
        assertFalse(PeerAlias.isValid("1234-ABCDE")) // too short
        assertFalse(PeerAlias.isValid("1234-ABCDEFG")) // too long
        assertFalse(PeerAlias.isValid("123G-ABCDEF")) // non-hex
        assertFalse(PeerAlias.isValid(null))

        assertTrue(PeerAlias.matches(fpA, "aaaa-72e2fe"))
        assertTrue(PeerAlias.matches(fpA.lowercase(), alias.text))
        assertFalse(PeerAlias.matches(fpA, "AAAA-72E2FF")) // wrong suffix
        assertFalse(PeerAlias.matches(fpA0, alias.text)) // same stem, different fingerprint
        assertFalse(PeerAlias.matches(fpA, "1234-ABCDEF")) // well-shaped but wrong
        assertFalse(PeerAlias.matches(fpA, "not-an-alias")) // malformed alias
        assertFalse(PeerAlias.matches(null, alias.text))
        assertFalse(PeerAlias.matches("G".repeat(64), alias.text)) // unusable fingerprint
    }

    @Test
    fun `hostile inputs are refused without throwing or unbounded work`() {
        val hostile = listOf(
            "A".repeat(100_000),
            " ".repeat(100_000) + fpA,
            "\u0000".repeat(1_000),
            "\uD83D\uDE42".repeat(500),
            ":".repeat(1_000) + fpA,
            "A".repeat(255) + "\uFFFF", // exactly at cap, but not hex/canonical
        )
        for (input in hostile) {
            assertNull(PeerAlias.of(input), "hostile input must be refused")
            assertNull(PeerAlias.text(input))
            assertFalse(PeerAlias.isValid(input))
            assertFalse(PeerAlias.matches(input, "AAAA-72E2FE"))
        }
    }
}
