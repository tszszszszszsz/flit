package app.flit.share.protocol

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.tls.TlsIdentity
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure roll-up of signed delivery receipts. Identities are generated locally
 * and every timestamp is explicit: no socket, Android class or clock sleep.
 * The tally verifies signatures and reads only the supplied member set; it
 * never touches the bounded replay memory.
 */
class ReceiptTallyTest {
    private val now = 1_700_000_000_000L

    @BeforeTest
    fun resetReplayMemory() {
        CircleCore.clear()
    }

    private fun receipt(
        author: TlsIdentity,
        dropId: String = "drop-1",
        sha256: String = "abc123",
        keyId: String = "key-1",
        lamport: Long = 0L,
        createdMs: Long = now,
    ): CircleEvent = CircleCore.create(
        "circle-1",
        author.fingerprint,
        author,
        CircleEvent.RECEIPT,
        """{"dropId":"$dropId","sha256":"$sha256","keyId":"$keyId"}""",
        lamport,
        createdMs,
    )

    /** A receipt whose claimed author is [victim] but whose cert and sig are [signer]'s. */
    private fun forgedReceipt(victim: TlsIdentity, signer: TlsIdentity): CircleEvent = CircleCore.create(
        "circle-1",
        victim.fingerprint,
        signer,
        CircleEvent.RECEIPT,
        """{"dropId":"drop-1","sha256":"abc123","keyId":"key-1"}""",
        1L,
        now,
    )

    // ---- one receipt per member --------------------------------------------

    @Test
    fun `latest receipt per member counts once and arrival order does not matter`() {
        val a = TlsIdentity.generate()
        val b = TlsIdentity.generate()
        val members = listOf(a.fingerprint, b.fingerprint)
        // Same drop and digest, different keyId: a rekey does not invalidate the
        // earlier acknowledgement. Both are a's, so a contributes one entry.
        val older = receipt(a, keyId = "key-old", lamport = 1L)
        val newer = receipt(a, keyId = "key-new", lamport = 2L)
        val fromB = receipt(b, lamport = 3L)

        val first = CircleCore.receiptAuthors(listOf(older, fromB, newer), "drop-1", "abc123", members)
        val reversed = CircleCore.receiptAuthors(listOf(newer, fromB, older), "drop-1", "abc123", members)

        assertEquals(setOf(a.fingerprint, b.fingerprint), first.toSet())
        assertEquals(first.toSet(), reversed.toSet(), "peers tallied the same receipts differently")
        assertEquals(1, first.count { it == a.fingerprint }, "member counted twice")
        assertEquals(1, first.count { it == b.fingerprint }, "member counted twice")
    }

    // ---- member gate -------------------------------------------------------

    @Test
    fun `non members and departed members are excluded from the roll up`() {
        val member = TlsIdentity.generate()
        val outsider = TlsIdentity.generate()
        val departed = TlsIdentity.generate()
        val events = listOf(receipt(member), receipt(outsider), receipt(departed))

        assertEquals(
            setOf(member.fingerprint),
            CircleCore.receiptAuthors(events, "drop-1", "abc123", listOf(member.fingerprint)).toSet(),
            "outsider receipt counted",
        )
        // Same normalisation as CircleCore.tally: a lower-case roster entry
        // still names the member.
        assertEquals(
            setOf(member.fingerprint),
            CircleCore.receiptAuthors(events, "drop-1", "abc123", listOf(member.fingerprint.lowercase())).toSet(),
            "member fingerprint was not normalised",
        )
        // The gate is the supplied set, not anything the tally remembers.
        assertEquals(
            setOf(outsider.fingerprint, departed.fingerprint),
            CircleCore.receiptAuthors(
                events, "drop-1", "abc123",
                listOf(outsider.fingerprint, departed.fingerprint),
            ).toSet(),
        )
    }

    // ---- signature gate ----------------------------------------------------

    @Test
    fun `forged signatures and tampered bodies are excluded`() {
        val member = TlsIdentity.generate()
        val attacker = TlsIdentity.generate()
        val victim = TlsIdentity.generate()
        val tamperedAuthor = TlsIdentity.generate()
        val blankSigAuthor = TlsIdentity.generate()

        val forgedAsMember = forgedReceipt(member, attacker)
        val forgedAsVictim = forgedReceipt(victim, attacker)
        val tampered = receipt(tamperedAuthor).copy(
            body = """{"dropId":"drop-1","sha256":"abc123","keyId":"key-changed"}""",
        )
        val blankSig = receipt(blankSigAuthor).copy(sig = "")

        assertFalse(CircleCore.verify(forgedAsMember), "test forgery unexpectedly verified")
        assertTrue(
            CircleCore.receiptAuthors(
                listOf(forgedAsMember), "drop-1", "abc123", listOf(member.fingerprint),
            ).toSet().isEmpty(),
            "receipt signed by another identity counted for the claimed author",
        )
        assertTrue(
            CircleCore.receiptAuthors(
                listOf(forgedAsVictim), "drop-1", "abc123", listOf(victim.fingerprint),
            ).toSet().isEmpty(),
            "forged receipt counted for a member with no real receipt",
        )
        assertTrue(
            CircleCore.receiptAuthors(
                listOf(tampered), "drop-1", "abc123", listOf(tamperedAuthor.fingerprint),
            ).toSet().isEmpty(),
            "body edited after signing still counted",
        )
        assertTrue(
            CircleCore.receiptAuthors(
                listOf(blankSig), "drop-1", "abc123", listOf(blankSigAuthor.fingerprint),
            ).toSet().isEmpty(),
            "unsigned receipt counted",
        )
        // A valid receipt beside a forgery still counts.
        assertEquals(
            setOf(member.fingerprint),
            CircleCore.receiptAuthors(
                listOf(receipt(member), forgedAsMember), "drop-1", "abc123",
                listOf(member.fingerprint, victim.fingerprint),
            ).toSet(),
        )
    }

    // ---- drop correlation --------------------------------------------------

    @Test
    fun `receipts for another drop or another digest are ignored`() {
        val right = TlsIdentity.generate()
        val wrongDrop = TlsIdentity.generate()
        val wrongSha = TlsIdentity.generate()
        val members = listOf(right.fingerprint, wrongDrop.fingerprint, wrongSha.fingerprint)
        val events = listOf(
            receipt(right),
            receipt(wrongDrop, dropId = "other-drop"),
            receipt(wrongSha, sha256 = "other-sha"),
        )

        assertEquals(
            setOf(right.fingerprint),
            CircleCore.receiptAuthors(events, "drop-1", "abc123", members).toSet(),
            "a mismatched dropId or sha256 was counted",
        )
        assertEquals(
            emptySet<String>(),
            CircleCore.receiptAuthors(events, "drop-x", "abc123", members).toSet(),
        )
        assertEquals(
            emptySet<String>(),
            CircleCore.receiptAuthors(events, "drop-1", "different-sha", members).toSet(),
        )
    }

    // ---- duplicate suppression --------------------------------------------

    @Test
    fun `repeated receipts count once`() {
        val a = TlsIdentity.generate()
        val b = TlsIdentity.generate()
        val one = receipt(a, lamport = 1L)
        // Same id, re-signed with a later lamport: still one member, not two.
        val sameId = CircleCore.sign(one.copy(sig = "", lamport = 2L), a)
        val events = listOf(one, one, sameId, receipt(b), receipt(b, lamport = 5L))

        val tally = CircleCore.receiptAuthors(events, "drop-1", "abc123", listOf(a.fingerprint, b.fingerprint))

        assertEquals(setOf(a.fingerprint, b.fingerprint), tally.toSet())
        assertEquals(1, tally.count { it == a.fingerprint }, "a counted twice")
        assertEquals(1, tally.count { it == b.fingerprint }, "b counted twice")
    }

    // ---- bounded replay ----------------------------------------------------

    @Test
    fun `tally leaves bounded replay memory alone and replay rules stay the same`() {
        val a = TlsIdentity.generate()
        val events = listOf(receipt(a, lamport = 1L))

        assertEquals(
            setOf(a.fingerprint),
            CircleCore.receiptAuthors(events, "drop-1", "abc123", listOf(a.fingerprint)).toSet(),
        )
        // Tallying does not reserve the id; the bounded set belongs to
        // evaluate/accept/fresh exactly as before RECEIPT was added.
        assertTrue(CircleCore.fresh(events.first().id, now), "tally reserved a replay id")

        val next = receipt(a, lamport = 2L)
        assertEquals(CircleCore.Decision.Accept, CircleCore.evaluate(next, now))
        assertEquals(CircleCore.Decision.Duplicate, CircleCore.evaluate(next, now + 1L))
        assertEquals(
            setOf(a.fingerprint),
            CircleCore.receiptAuthors(listOf(next), "drop-1", "abc123", listOf(a.fingerprint)).toSet(),
            "a reserved replay id changed the tally",
        )

        val stale = receipt(a, lamport = 3L, createdMs = now - CircleCore.REPLAY_WINDOW_MS - 1L)
        val future = receipt(a, lamport = 4L, createdMs = now + CircleCore.CLOCK_SKEW_MS + 1L)
        assertEquals(CircleCore.Decision.OutsideWindow, CircleCore.evaluate(stale, now))
        assertEquals(CircleCore.Decision.OutsideWindow, CircleCore.evaluate(future, now))
    }
}
