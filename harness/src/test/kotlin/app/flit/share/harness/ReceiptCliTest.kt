package app.flit.share.harness

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.circle.DropKeyCore
import app.flit.share.protocol.tls.TlsIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure seams of the CLI's `circle receipt` path. No socket, no host process and
 * no device claim: every case drives the same production [CircleCore],
 * [DropKeyCore] and [TlsIdentity] functions the command calls, over
 * [CircleCliState] as the CLI's on-disk log. The two-node wire walk belongs to
 * `CircleHarnessTest`; this class pins the resolution, refusal, mint and
 * confirmation steps in isolation so a regression names the exact step that
 * broke.
 */
class ReceiptCliTest {
    private val circleId = "cli-receipt-circle"

    @BeforeTest
    fun resetReplayMemory() = CircleCore.clear()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun bodyObject(body: String): JsonObject? =
        runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()

    private fun text(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    /** The CLI's `circleEvent` helper + local append, as `circle receipt` does. */
    private fun postReceipt(
        st: CircleCliState,
        signer: TlsIdentity,
        dropId: String,
        sha256: String,
        keyId: String,
    ): CircleEvent {
        // Mirrors Harness.circleEvent (private): same circleId, author, identity,
        // next lamport and closed body, then the local append the CLI performs.
        val ev = CircleCore.create(
            circleId = st.circleId.ifBlank { circleId },
            authorFp = signer.fingerprint,
            identity = signer,
            type = CircleEvent.RECEIPT,
            body = circleReceiptBody(dropId, sha256, keyId),
            lamport = (st.events.maxOfOrNull { it.lamport } ?: -1L) + 1L,
        )
        st.events.add(ev)
        return ev
    }

    /** A signed `file` announcement with a real keyed body, as `circle file` mints it. */
    private fun addKeyedFile(
        st: CircleCliState,
        signer: TlsIdentity,
        plaintext: ByteArray,
        keyGen: Int = 1,
        name: String = "drop.bin",
    ): Pair<String, String> {
        val key = DropKeyCore.mint()
        val sealed = DropKeyCore.seal(key, plaintext)
        val sha = sha256Hex(plaintext)
        val body = circleFileAnnouncement(
            name = name,
            size = plaintext.size.toLong(),
            sha256 = sha,
            keyGen = keyGen,
            cipherSha = sha256Hex(sealed),
            iv = Base64.getEncoder().encodeToString(key.iv),
            wraps = DropKeyCore.wrap(key.bytes, mapOf(signer.fingerprint to signer.certificate)),
        )
        val ev = CircleCore.create(
            circleId, signer.fingerprint, signer, CircleEvent.FILE, body,
            lamport = (st.events.maxOfOrNull { it.lamport } ?: -1L) + 1L,
        )
        st.events.add(ev)
        return ev.id to sha
    }

    private fun addLegacyFile(st: CircleCliState, signer: TlsIdentity, plaintext: ByteArray): Pair<String, String> {
        val sha = sha256Hex(plaintext)
        val body = circleFileAnnouncement("plain.bin", plaintext.size.toLong(), sha)
        val ev = CircleCore.create(
            circleId, signer.fingerprint, signer, CircleEvent.FILE, body,
            lamport = (st.events.maxOfOrNull { it.lamport } ?: -1L) + 1L,
        )
        st.events.add(ev)
        return ev.id to sha
    }

    // ---- resolution ---------------------------------------------------------

    @Test
    fun `circle receipt resolves a keyed file event by plaintext sha and derives its keyId`() {
        val sender = TlsIdentity.generate()
        val st = CircleCliState(circleId = circleId, members = mutableListOf(sender.fingerprint))
        val (dropId, sha) = addKeyedFile(st, sender, ByteArray(128) { it.toByte() }, keyGen = 3)
        val (otherDropId, otherSha) = addKeyedFile(st, sender, ByteArray(64) { (it * 2).toByte() }, keyGen = 7)

        val target = circleReceiptTarget(st.events, sha)

        assertNotNull(target)
        assertEquals(dropId, target.dropId, "sha must select the landed file event, not the newest one")
        assertEquals(sha, target.sha256, "correlation is the plaintext digest")
        assertEquals(DropKeyCore.keyId(dropId, 3), target.keyId, "keyId comes from the event's own keyGen")
        assertFalse(target.dropId == otherDropId)
        assertFalse(target.keyId == DropKeyCore.keyId(otherDropId, 7))

        // An explicit --drop-id narrows the same lookup.
        assertEquals(target, circleReceiptTarget(st.events, sha, dropId = dropId))
        assertNull(circleReceiptTarget(st.events, sha, dropId = otherDropId))
        assertNull(circleReceiptTarget(st.events, otherSha, dropId = dropId))
    }

    @Test
    fun `circle receipt refuses an unknown sha a legacy drop and a wrong drop id`() {
        val sender = TlsIdentity.generate()
        val st = CircleCliState(circleId = circleId, members = mutableListOf(sender.fingerprint))
        val (legacyDropId, legacySha) = addLegacyFile(st, sender, ByteArray(32) { it.toByte() })
        val before = st.events.size

        // No keyed file event with that digest: no fabricated drop id or key id.
        assertNull(circleReceiptTarget(st.events, "f".repeat(64)))
        // A plaintext file event has no keyGen or wraps: no receipt for it.
        assertNull(circleReceiptTarget(st.events, legacySha))
        assertNull(circleReceiptTarget(st.events, legacySha, dropId = legacyDropId))
        assertEquals(before, st.events.size, "a refused lookup must not mint an event")
    }

    // ---- mint / post --------------------------------------------------------

    @Test
    fun `circle receipt posts a signed body with exactly dropId sha256 and keyId`() {
        val sender = TlsIdentity.generate()
        val st = CircleCliState(circleId = circleId, members = mutableListOf(sender.fingerprint))
        val (dropId, sha) = addKeyedFile(st, sender, ByteArray(96) { (it + 1).toByte() })
        val target = circleReceiptTarget(st.events, sha)!!
        val before = st.events.size

        val ev = postReceipt(st, sender, target.dropId, target.sha256, target.keyId)

        assertEquals(before + 1, st.events.size, "the receipt is appended to the CLI log")
        assertEquals(CircleEvent.RECEIPT, ev.type)
        assertEquals(circleId, ev.circleId)
        assertEquals(sender.fingerprint, ev.authorFp)
        assertTrue(CircleCore.verify(ev), "the posted receipt must verify with its own cert")
        assertTrue(CircleCore.validReceiptBody(ev.body))

        val fields = bodyObject(ev.body)!!
        assertEquals(CircleCore.RECEIPT_BODY_KEYS, fields.keys)
        assertEquals(dropId, text(fields, "dropId"))
        assertEquals(sha, text(fields, "sha256"), "correlation is the plaintext digest")
        assertEquals(DropKeyCore.keyId(dropId, 1), text(fields, "keyId"))
        assertFalse(fields.keys.any { it in setOf("landedMs", "path", "status", "key", "cipherSha") })
        assertFalse(ev.body.contains("flitdrop"), "no spool path in the signed body")
    }

    // ---- confirmation tally -------------------------------------------------

    @Test
    fun `circle sync counts one confirmed author per member from the CLI log`() {
        val sender = TlsIdentity.generate()
        val alice = TlsIdentity.generate()
        val bob = TlsIdentity.generate()
        val st = CircleCliState(
            circleId = circleId,
            members = mutableListOf(sender.fingerprint, alice.fingerprint, bob.fingerprint),
        )
        val payload = ByteArray(64) { it.toByte() }
        val (dropId, sha) = addKeyedFile(st, sender, payload)
        val a1 = postReceipt(st, alice, dropId, sha, DropKeyCore.keyId(dropId, 1))
        val b1 = postReceipt(st, bob, dropId, sha, DropKeyCore.keyId(dropId, 1))
        // A later re-announcement by the same author changes only provenance.
        val a2 = postReceipt(st, alice, dropId, sha, DropKeyCore.keyId(dropId, 9))
        // The same content announced again under a new id is another candidate
        // drop; the union across ids still keeps one entry per author.
        val (reDropId) = addKeyedFile(st, sender, payload, keyGen = 2, name = "drop-again.bin")
        postReceipt(st, alice, reDropId, sha, DropKeyCore.keyId(reDropId, 2))

        val confirmed = circleConfirmedAuthors(st, sha)

        assertEquals(setOf(alice.fingerprint, bob.fingerprint), confirmed)
        assertEquals(2, circleConfirmedCount(st, sha))
        assertEquals(1, confirmed.count { it == alice.fingerprint }, "alice counted twice")
        assertEquals(1, confirmed.count { it == bob.fingerprint }, "bob counted twice")
        assertEquals(confirmed, CircleCore.receiptAuthors(listOf(a2, b1, a1), dropId, sha, st.members),
            "arrival order changed the confirmation count")
    }

    @Test
    fun `confirmation ignores forged non member and other drop receipts`() {
        val sender = TlsIdentity.generate()
        val alice = TlsIdentity.generate()
        val stranger = TlsIdentity.generate()
        val attacker = TlsIdentity.generate()
        val st = CircleCliState(
            circleId = circleId,
            members = mutableListOf(sender.fingerprint, alice.fingerprint),
        )
        val (dropId, sha) = addKeyedFile(st, sender, ByteArray(48) { it.toByte() })
        postReceipt(st, alice, dropId, sha, DropKeyCore.keyId(dropId, 1))
        postReceipt(st, stranger, dropId, sha, DropKeyCore.keyId(dropId, 1))
        postReceipt(st, alice, "a-different-drop", sha, DropKeyCore.keyId(dropId, 1))
        val forged = CircleCore.create(
            circleId, alice.fingerprint, attacker, CircleEvent.RECEIPT,
            circleReceiptBody(dropId, sha, DropKeyCore.keyId(dropId, 1)), lamport = 9L,
        )

        assertEquals(
            setOf(alice.fingerprint),
            circleConfirmedAuthors(st.events + forged, st.members, sha),
            "a forged receipt in the same log must not add a confirmation",
        )
        assertFalse(CircleCore.verify(forged), "test forgery unexpectedly verified")
        assertEquals(1, circleConfirmedCount(st, sha), "only the member's real receipt counts")
    }
}
