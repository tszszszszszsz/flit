package app.flit.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure JVM pins for the Android-free courier card projection. Nothing here
 * builds a `parcel` event, opens an envelope or touches Compose: only the
 * outcome mapping, the five carry-copy rows, alias rendering and the
 * three-state Open affordance are in scope.
 *
 * Fingerprints and hashes are upper- or lower-case hex on the wire, so every
 * comparison path is exercised with mixed case and surrounding whitespace.
 */
class CourierCardUiTest {

    private val sha = "0123456789abcdef".repeat(4)
    private val otherSha = "fedcba9876543210".repeat(4)

    private val courierFp = "aAbBcCdDeEfF0011".repeat(4)
    private val toFp = "0011aAbBcCdDeEfF".repeat(4)
    private val thirdFp = "fFeEdDcCbBaA1122".repeat(4)
    private val fromFp = "1122fFeEdDcCbBaA".repeat(4)
    // Digits first so `take(8)` is the same before and after case-folding.
    private val unknownFp = "12345678" + "E".repeat(56)

    private val members = listOf(
        FlitCircleStore.Member(fp = courierFp, alias = "Cid"),
        FlitCircleStore.Member(fp = toFp, alias = "Bea"),
        FlitCircleStore.Member(fp = thirdFp, alias = "Tariq"),
    )

    private fun handoff(
        sha256: String = sha,
        name: String = "notes.txt",
        size: Long = 4096L,
        toFp: String = this.toFp,
        courierFp: String = this.courierFp,
        fromFp: String = this.fromFp,
        outcome: String = "waiting",
    ) = CourierCardUi.CourierHandoff(
        sha256 = sha256,
        name = name,
        size = size,
        toFp = toFp,
        courierFp = courierFp,
        fromFp = fromFp,
        outcome = outcome,
    )

    @Test
    fun `carryOutcome maps the three exact values and defaults everything else to waiting`() {
        assertEquals(CourierCardUi.CarryOutcome.WAITING, CourierCardUi.carryOutcome("waiting"))
        assertEquals(CourierCardUi.CarryOutcome.HANDED, CourierCardUi.carryOutcome("handed"))
        assertEquals(CourierCardUi.CarryOutcome.FAILED, CourierCardUi.carryOutcome("failed"))

        assertEquals(CourierCardUi.CarryOutcome.WAITING, CourierCardUi.carryOutcome(null))
        assertEquals(CourierCardUi.CarryOutcome.WAITING, CourierCardUi.carryOutcome(""))
        assertEquals(CourierCardUi.CarryOutcome.WAITING, CourierCardUi.carryOutcome("   "))
        assertEquals(CourierCardUi.CarryOutcome.WAITING, CourierCardUi.carryOutcome("Handed"))
        assertEquals(CourierCardUi.CarryOutcome.WAITING, CourierCardUi.carryOutcome(" HANDED "))
        assertEquals(CourierCardUi.CarryOutcome.WAITING, CourierCardUi.carryOutcome("sent"))
    }

    @Test
    fun `courierLine renders the three courier rows`() {
        assertEquals(
            "Carrying for Bea · waiting for a handoff",
            CourierCardUi.courierLine(handoff(outcome = "waiting"), courierFp, members),
        )
        assertEquals(
            "Carrying for Bea · handoff will retry",
            CourierCardUi.courierLine(handoff(outcome = "failed"), courierFp, members),
        )
        assertEquals(
            "Handed off to Bea",
            CourierCardUi.courierLine(handoff(outcome = "handed"), courierFp, members),
        )
    }

    @Test
    fun `courierLine renders recipient and third-member rows`() {
        for (raw in listOf("waiting", "handed", "failed")) {
            assertEquals(
                "A courier carried a copy for you",
                CourierCardUi.courierLine(handoff(outcome = raw), toFp, members),
                "recipient view with outcome $raw",
            )
        }
        assertEquals(
            "Cid is carrying a copy for Bea",
            CourierCardUi.courierLine(handoff(outcome = "waiting"), thirdFp, members),
        )
        assertEquals(
            "Cid is carrying a copy for Bea",
            CourierCardUi.courierLine(handoff(outcome = "handed"), thirdFp, members),
        )
    }

    @Test
    fun `a handed outcome on another device never claims the handoff`() {
        val handed = handoff(outcome = "handed")
        val recipientLine = CourierCardUi.courierLine(handed, toFp, members)
        val thirdLine = CourierCardUi.courierLine(handed, thirdFp, members)

        assertEquals("A courier carried a copy for you", recipientLine)
        assertEquals("Cid is carrying a copy for Bea", thirdLine)
        assertFalse(recipientLine!!.contains("Handed off"))
        assertFalse(thirdLine!!.contains("Handed off"))
    }

    @Test
    fun `aliasOf bounds long labels and falls back to the first eight fingerprint chars`() {
        val longAlias = "A".repeat(200)
        assertEquals(
            "A".repeat(FlitCircleStore.MAX_ALIAS_CHARS),
            CourierCardUi.aliasOf(toFp, listOf(FlitCircleStore.Member(fp = toFp, alias = longAlias))),
        )
        assertEquals("12345678", CourierCardUi.aliasOf(unknownFp, members))
    }

    @Test
    fun `aliasOf falls back after a folded match to a blank alias`() {
        // The member is found by the trimmed, case-folded fingerprint, and a
        // blank alias falls back to the first eight chars of that same folded
        // key: mixed-case input renders as a lowercase label.
        val foldedBlankAlias = FlitCircleStore.Member(
            fp = "  ${courierFp.lowercase()}  ",
            alias = "   ",
        )
        assertEquals(
            courierFp.lowercase().take(8),
            CourierCardUi.aliasOf(courierFp, listOf(foldedBlankAlias)),
        )
        assertEquals(
            courierFp.lowercase().take(8),
            CourierCardUi.aliasOf(courierFp.uppercase(), listOf(foldedBlankAlias)),
        )
        assertEquals(
            unknownFp.lowercase().take(8),
            CourierCardUi.aliasOf(unknownFp, listOf(foldedBlankAlias)),
        )

        // The third-member carry line resolves its courier label the same way.
        assertEquals(
            "${courierFp.lowercase().take(8)} is carrying a copy for Bea",
            CourierCardUi.courierLine(
                handoff(outcome = "waiting"),
                thirdFp,
                listOf(foldedBlankAlias) + members.filter { it.fp != courierFp },
            ),
        )
    }

    @Test
    fun `courierLine never copies name fromFp or envelope-like text`() {
        val base64ish = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo="
        val secretFrom = "SECRETFROMFINGERPRINT"
        val line = CourierCardUi.courierLine(
            handoff(name = base64ish, fromFp = secretFrom, outcome = "waiting"),
            courierFp,
            members,
        )

        assertEquals("Carrying for Bea · waiting for a handoff", line)
        assertFalse(line!!.contains(base64ish))
        assertFalse(line.contains(secretFrom))
        assertFalse(line.contains(fromFp))
    }

    @Test
    fun `no carry line without a handoff or with a blank sha256`() {
        assertNull(CourierCardUi.courierLine(null, courierFp, members))
        assertNull(CourierCardUi.courierLine(handoff(sha256 = ""), courierFp, members))
        assertNull(CourierCardUi.courierLine(handoff(sha256 = "   "), courierFp, members))
    }

    @Test
    fun `courierLine matches fingerprints case-insensitively after trimming`() {
        val handed = handoff(outcome = "handed")

        assertEquals(
            "Handed off to Bea",
            CourierCardUi.courierLine(handed, "  ${courierFp.lowercase()}  ", members),
        )
        assertEquals(
            "Handed off to Bea",
            CourierCardUi.courierLine(handed, courierFp.uppercase(), members),
        )

        val messyMembers = listOf(
            FlitCircleStore.Member(fp = "  ${courierFp.lowercase()}  ", alias = "Cid"),
            FlitCircleStore.Member(fp = toFp.uppercase(), alias = "Bea"),
        )
        assertEquals("Handed off to Bea", CourierCardUi.courierLine(handed, courierFp, messyMembers))
        assertEquals(
            "Cid is carrying a copy for Bea",
            CourierCardUi.courierLine(handed, thirdFp, messyMembers),
        )
    }

    @Test
    fun `openAffordance covers resolving landed and absent`() {
        // FileCard switches on this exact enum, including the same-package
        // typealias it uses at the call site: pin the shape, not just the copy.
        assertEquals(
            listOf("RESOLVING", "LANDED", "ABSENT"),
            enumValues<CourierCardUi.OpenAffordance>().map { it.name },
        )
        val viaAlias: OpenAffordance = CourierCardUi.OpenAffordance.LANDED
        assertEquals(CourierCardUi.OpenAffordance.LANDED, viaAlias)

        assertEquals(
            CourierCardUi.OpenAffordance.RESOLVING,
            CourierCardUi.openAffordance(sha, emptySet(), sha),
        )
        assertEquals(
            CourierCardUi.OpenAffordance.LANDED,
            CourierCardUi.openAffordance(sha, setOf(sha), null),
        )
        assertEquals(
            CourierCardUi.OpenAffordance.ABSENT,
            CourierCardUi.openAffordance(sha, emptySet(), null),
        )
        assertEquals(
            CourierCardUi.OpenAffordance.ABSENT,
            CourierCardUi.openAffordance(sha, emptySet(), ""),
        )
        assertEquals(
            CourierCardUi.OpenAffordance.ABSENT,
            CourierCardUi.openAffordance(sha, emptySet(), "   "),
        )
        assertEquals(
            CourierCardUi.OpenAffordance.ABSENT,
            CourierCardUi.openAffordance("", setOf(sha), null),
        )
        assertEquals(
            CourierCardUi.OpenAffordance.ABSENT,
            CourierCardUi.openAffordance("   ", setOf(sha), null),
        )
        assertEquals(
            CourierCardUi.OpenAffordance.ABSENT,
            CourierCardUi.openAffordance(otherSha, setOf(sha), null),
        )
    }

    @Test
    fun `openAffordance normalizes hashes and gives resolving precedence`() {
        val landedUpper = setOf(sha.uppercase())

        assertEquals(
            CourierCardUi.OpenAffordance.LANDED,
            CourierCardUi.openAffordance("  $sha  ", landedUpper, null),
        )
        assertEquals(
            CourierCardUi.OpenAffordance.RESOLVING,
            CourierCardUi.openAffordance(sha, landedUpper, "  ${sha.uppercase()}  "),
        )
        assertEquals(
            CourierCardUi.OpenAffordance.ABSENT,
            CourierCardUi.openAffordance(sha, emptySet(), otherSha),
        )
    }

    @Test
    fun `landed sha keeps landed while a carry line exists and absent sha offers no open`() {
        val line = CourierCardUi.courierLine(handoff(outcome = "waiting"), courierFp, members)
        assertTrue(line != null)

        assertEquals(
            CourierCardUi.OpenAffordance.LANDED,
            CourierCardUi.openAffordance(sha, setOf(sha.uppercase()), null),
        )
        assertEquals(
            CourierCardUi.OpenAffordance.RESOLVING,
            CourierCardUi.openAffordance(otherSha, setOf(sha), otherSha),
        )
        assertEquals(
            CourierCardUi.OpenAffordance.ABSENT,
            CourierCardUi.openAffordance(otherSha, setOf(sha), null),
        )
    }
}
