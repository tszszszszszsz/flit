package app.flit.share.protocol

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.model.CircleRequest
import app.flit.share.protocol.model.WireJson
import app.flit.share.protocol.tls.TlsIdentity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure JVM tests for the stateless JOIN proof. Every clock is fixed, every
 * vector is computed from the documented framing without calling the
 * production nonce builder for its own expected bytes, and no route, Gradle
 * task, socket or Android class is involved. The proof is a digest of a
 * domain-tagged nonce carried in a signed JOIN event; the host recomputes it
 * from the request and accepts only the current five-minute bucket.
 */
class JoinProofTest {

    private val identities = mutableListOf<TlsIdentity>()

    private fun identity(): TlsIdentity = TlsIdentity.generate().also { identities += it }

    @BeforeTest
    fun resetState() {
        CircleCore.clear()
        identities.clear()
    }

    @AfterTest
    fun forgetGeneratedPins() {
        identities.forEach { TlsIdentity.forgetPin(it.fingerprint) }
        identities.clear()
        CircleCore.clear()
    }

    // ---- bucket -----------------------------------------------------------

    @Test
    fun `bucket grid is exactly one clock skew wide and total on hostile input`() {
        assertEquals(CircleCore.CLOCK_SKEW_MS, CircleCore.JOIN_PROOF_BUCKET_MS)
        assertEquals(5 * 60 * 1000L, CircleCore.JOIN_PROOF_BUCKET_MS)
        assertEquals(6_000_000L, CircleCore.joinProofBucket(BUCKET_START))
        assertEquals(6_000_000L, CircleCore.joinProofBucket(NOW))
        assertEquals(6_000_000L, CircleCore.joinProofBucket(BUCKET_START + BUCKET_MS - 1L))
        assertEquals(6_000_001L, CircleCore.joinProofBucket(BUCKET_START + BUCKET_MS))
        assertEquals(5_999_999L, CircleCore.joinProofBucket(BUCKET_START - 1L))
        assertEquals(-1L, CircleCore.joinProofBucket(-1L))
    }

    // ---- nonce framing ----------------------------------------------------

    @Test
    fun `nonce framing matches an independent golden vector and is deterministic`() {
        val produced = CircleCore.joinProofNonce(" abcd ", " circle-1 ", "invite-1", " ef01 ", 6_000_000L)
        assertContentEquals(hex(NONCE_GOLDEN), produced, "FLITJOIN1 framing changed")
        assertContentEquals(
            independentNonce("abcd", "circle-1", "invite-1", "ef01", 6_000_000L),
            produced,
        )
        assertContentEquals(
            produced,
            CircleCore.joinProofNonce("ABCD", "CIRCLE-1", "INVITE-1", "EF01", 6_000_000L),
            "normalisation is not Locale.ROOT trim/uppercase",
        )
    }

    @Test
    fun `nonce binds every field and the bucket with no delimiter smuggling`() {
        val base = CircleCore.joinProofNonce(HOST, CIRCLE, INVITE, "ABCD", 6_000_000L)
        val changed = listOf(
            CircleCore.joinProofNonce("BBBB", CIRCLE, INVITE, "ABCD", 6_000_000L),
            CircleCore.joinProofNonce(HOST, "circle-2", INVITE, "ABCD", 6_000_000L),
            CircleCore.joinProofNonce(HOST, CIRCLE, "invite-2", "ABCD", 6_000_000L),
            CircleCore.joinProofNonce(HOST, CIRCLE, INVITE, "DEAD", 6_000_000L),
            CircleCore.joinProofNonce(HOST, CIRCLE, INVITE, "ABCD", 6_000_001L),
        )
        assertEquals(5, changed.size)
        changed.forEachIndexed { index, bytes ->
            assertFalse(bytes.contentEquals(base), "nonce field ${index + 1} is not bound")
        }
        assertFalse(
            CircleCore.joinProofNonce(HOST, "a", "bc", "ABCD", 6_000_000L)
                .contentEquals(CircleCore.joinProofNonce(HOST, "ab", "c", "ABCD", 6_000_000L)),
            "length prefix is not separating fields",
        )
        assertFalse(
            CircleCore.joinProofNonce(HOST, CIRCLE, "invite\u0000-1", "ABCD", 6_000_000L)
                .contentEquals(base),
        )
    }

    // ---- body and its gate -------------------------------------------------

    @Test
    fun `body builder emits exactly one key holding a 32-byte unpadded Base64URL digest`() {
        val id = identity()
        val bucket = CircleCore.joinProofBucket(NOW)
        val body = CircleCore.joinProofBody(HOST, CIRCLE, INVITE, id.fingerprint, bucket)

        assertEquals(setOf("proof"), CircleCore.JOIN_PROOF_BODY_KEYS)
        assertTrue(CircleCore.validJoinProofBody(body))
        assertEquals(body, CircleCore.joinProofBody(HOST, CIRCLE, INVITE, id.fingerprint, bucket))
        val obj = WireJson.json.parseToJsonElement(body) as JsonObject
        assertEquals(setOf("proof"), obj.keys)
        val value = obj["proof"] as JsonPrimitive
        assertTrue(value.isString)
        val proof = value.content
        assertEquals(43, proof.length)
        assertFalse(proof.any { it == '=' || it == '+' || it == '/' })
        val decoded = Base64.getUrlDecoder().decode(proof)
        assertEquals(32, decoded.size)
        assertContentEquals(
            MessageDigest.getInstance("SHA-256")
                .digest(CircleCore.joinProofNonce(HOST, CIRCLE, INVITE, id.fingerprint, bucket)),
            decoded,
            "proof is not SHA-256 of the domain-tagged nonce",
        )
    }

    @Test
    fun `body gate refuses every malformed shape without throwing`() {
        val id = identity()
        val body = CircleCore.joinProofBody(HOST, CIRCLE, INVITE, id.fingerprint, CircleCore.joinProofBucket(NOW))
        val proof = proofOf(body)
        val padded = Base64.getUrlEncoder().encodeToString(Base64.getUrlDecoder().decode(proof))
        val short = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(31))
        val standardAlphabet = "+" + proof.drop(1)

        for (bad in listOf(
            "",
            "{",
            "{}",
            """{"proof":"$proof","extra":1}""",
            """{"proof":123}""",
            """{"proof":""}""",
            """{"proof":"${"p".repeat(CircleCore.MAX_FIELD_CHARS + 1)}"}""",
            """{"proof":"not!base64"}""",
            """{"proof":"$padded"}""",
            """{"proof":"$short"}""",
            """{"proof":"$standardAlphabet"}""",
            """{"proof":"$proof","hostFp":"$HOST"}""",
        )) {
            assertFalse(CircleCore.validJoinProofBody(bad), "gate accepted $bad")
        }
        assertTrue(CircleCore.validJoinProofBody(body))
    }

    // ---- failure gate ------------------------------------------------------

    @Test
    fun `a properly minted proof passes and a non-join request needs none`() {
        val id = identity()
        val e = proofEvent(id)
        assertTrue(CircleCore.verify(e), "minted JOIN proof does not verify")
        assertNull(CircleCore.joinProofFailure(request(id.fingerprint, listOf(e)), HOST, NOW))
        assertNull(
            CircleCore.joinProofFailure(
                request(id.fingerprint, emptyList(), action = CircleRequest.ACTION_SYNC), HOST, NOW,
            ),
            "sync was asked for a join proof",
        )
        assertEquals(
            "join proof replay",
            CircleCore.joinProofFailure(
                request(id.fingerprint, listOf(proofEvent(id, created = NOW + 999L))),
                HOST, NOW + 999L,
            ),
            "the same proof digest re-signed later is a replay, not a fresh proof",
        )
    }

    @Test
    fun `a missing join event or a legacy join body means proof required`() {
        val id = identity()
        assertEquals(
            "join proof required",
            CircleCore.joinProofFailure(request(id.fingerprint, emptyList()), HOST, NOW),
        )
        val legacy = CircleCore.create(
            CIRCLE, id.fingerprint, id, CircleEvent.JOIN, """{"alias":"alice"}""", 0L, NOW,
        )
        assertEquals(
            "join proof required",
            CircleCore.joinProofFailure(request(id.fingerprint, listOf(legacy)), HOST, NOW),
        )
        val message = CircleCore.create(
            CIRCLE, id.fingerprint, id, CircleEvent.MESSAGE, """{"text":"hi"}""", 0L, NOW,
        )
        assertEquals(
            "join proof required",
            CircleCore.joinProofFailure(request(id.fingerprint, listOf(message)), HOST, NOW),
        )
    }

    @Test
    fun `a malformed or extra-key proof body means invalid join proof`() {
        val id = identity()
        val junk = CircleCore.create(
            CIRCLE, id.fingerprint, id, CircleEvent.JOIN, """{"proof":"!!!"}""", 0L, NOW,
        )
        assertEquals(
            "invalid join proof",
            CircleCore.joinProofFailure(request(id.fingerprint, listOf(junk)), HOST, NOW),
        )
        val extra = CircleCore.create(
            CIRCLE, id.fingerprint, id, CircleEvent.JOIN,
            """{"proof":"${proofOf(proofBody(id))}","createdMs":$NOW}""", 0L, NOW,
        )
        assertEquals(
            "invalid join proof",
            CircleCore.joinProofFailure(request(id.fingerprint, listOf(extra)), HOST, NOW),
        )
    }

    @Test
    fun `host circle invite and member are each bound by the failure gate`() {
        val id = identity()
        val e = proofEvent(id)
        val good = request(id.fingerprint, listOf(e))
        assertNull(CircleCore.joinProofFailure(good, HOST, NOW))

        assertEquals("invalid join proof", CircleCore.joinProofFailure(good, "BBBB", NOW))
        assertEquals(
            "invalid join proof",
            CircleCore.joinProofFailure(request(id.fingerprint, listOf(e), circle = "circle-2"), HOST, NOW),
        )
        assertEquals(
            "invalid join proof",
            CircleCore.joinProofFailure(request(id.fingerprint, listOf(e), invite = "invite-2"), HOST, NOW),
        )
        assertEquals("invalid join proof", CircleCore.joinProofFailure(request("DEAD", listOf(e)), HOST, NOW))

        val otherHostBody = CircleCore.joinProofBody("BBBB", CIRCLE, INVITE, id.fingerprint, CircleCore.joinProofBucket(NOW))
        val otherHost = CircleCore.create(CIRCLE, id.fingerprint, id, CircleEvent.JOIN, otherHostBody, 0L, NOW)
        assertEquals(
            "invalid join proof",
            CircleCore.joinProofFailure(request(id.fingerprint, listOf(otherHost)), HOST, NOW),
            "an event carrying a proof for a different host was accepted",
        )
    }

    @Test
    fun `a tampered signature changed body or swapped certificate is refused`() {
        val id = identity()
        val e = proofEvent(id)
        val stranger = identity()
        for (bad in listOf(
            e.copy(body = proofBody(id, invite = "invite-2")),
            e.copy(sig = ""),
            e.copy(sig = "not base64 !!"),
            e.copy(cert = Base64.getEncoder().encodeToString(stranger.certificate.encoded)),
            e.copy(authorFp = stranger.fingerprint),
        )) {
            assertEquals(
                "invalid join proof",
                CircleCore.joinProofFailure(request(id.fingerprint, listOf(bad)), HOST, NOW),
                "an unauthentic proof event was admitted",
            )
        }
        assertNull(CircleCore.joinProofFailure(request(id.fingerprint, listOf(e)), HOST, NOW))
    }

    // ---- buckets and replay ------------------------------------------------

    @Test
    fun `a stale bucket is refused as join proof replay`() {
        val id = identity()
        val staleAt = NOW - 2L * BUCKET_MS
        val e = proofEvent(id, created = staleAt)
        assertEquals(5_999_998L, CircleCore.joinProofBucket(staleAt))
        assertEquals(
            "join proof replay",
            CircleCore.joinProofFailure(request(id.fingerprint, listOf(e)), HOST, NOW),
        )
    }

    @Test
    fun `a future bucket is refused as join proof replay`() {
        val id = identity()
        val futureAt = NOW + 2L * BUCKET_MS
        val e = proofEvent(id, created = futureAt)
        assertEquals(6_000_002L, CircleCore.joinProofBucket(futureAt))
        assertEquals(
            "join proof replay",
            CircleCore.joinProofFailure(request(id.fingerprint, listOf(e)), HOST, NOW),
        )
    }

    @Test
    fun `a captured proof cannot cross a bucket or be duplicated in one request`() {
        val id = identity()
        val e = proofEvent(id)
        val req = request(id.fingerprint, listOf(e))
        assertNull(CircleCore.joinProofFailure(req, HOST, NOW))
        assertEquals(
            "join proof replay",
            CircleCore.joinProofFailure(req, HOST, NOW + 2L * BUCKET_MS),
            "a captured proof survived two bucket rollovers",
        )
        assertEquals(
            "join proof replay",
            CircleCore.joinProofFailure(request(id.fingerprint, listOf(e, e.copy())), HOST, NOW),
            "the same proof was accepted twice in one request",
        )
    }

    @Test
    fun `a forged author fingerprint is refused without pinning the victim`() {
        val victim = identity()
        val attacker = identity()
        TlsIdentity.forgetPin(victim.fingerprint)
        val forgedBody = CircleCore.joinProofBody(
            HOST, CIRCLE, INVITE, victim.fingerprint, CircleCore.joinProofBucket(NOW),
        )
        val forged = CircleCore.create(
            CIRCLE, victim.fingerprint, attacker, CircleEvent.JOIN, forgedBody, 0L, NOW,
        )
        assertNull(TlsIdentity.pinned(victim.fingerprint), "victim was pinned before the check")
        assertFalse(CircleCore.verify(forged))
        assertEquals(
            "invalid join proof",
            CircleCore.joinProofFailure(request(victim.fingerprint, listOf(forged)), HOST, NOW),
        )
        assertNull(TlsIdentity.pinned(victim.fingerprint), "failed proof poisoned the pin store")
    }

    @Test
    fun `a validly signed but refused JOIN proof leaves the pin store unchanged`() {
        val signer = identity()
        // All three events carry a genuine signature over their own bytes.
        // Each is refused at a LATER gate, after signature verification.
        val wrongInvite = selfSignedProofEvent(signer, proofBody(signer, invite = "invite-2"))
        val staleAt = NOW - 2L * BUCKET_MS
        val staleBucket = selfSignedProofEvent(
            signer,
            CircleCore.joinProofBody(
                HOST, CIRCLE, INVITE, signer.fingerprint, CircleCore.joinProofBucket(staleAt),
            ),
            created = staleAt,
        )
        val accepted = selfSignedProofEvent(signer, proofBody(signer))

        TlsIdentity.forgetPin(signer.fingerprint)
        assertNull(TlsIdentity.pinned(signer.fingerprint), "fixture started pinned")

        assertEquals(
            "invalid join proof",
            CircleCore.joinProofFailure(request(signer.fingerprint, listOf(wrongInvite)), HOST, NOW),
        )
        assertNull(TlsIdentity.pinned(signer.fingerprint), "wrong-invite proof pinned its certificate")

        assertEquals(
            "join proof replay",
            CircleCore.joinProofFailure(request(signer.fingerprint, listOf(staleBucket)), HOST, NOW),
        )
        assertNull(TlsIdentity.pinned(signer.fingerprint), "stale-bucket proof pinned its certificate")

        assertNull(CircleCore.joinProofFailure(request(signer.fingerprint, listOf(accepted)), HOST, NOW))
        assertNotNull(TlsIdentity.pinned(signer.fingerprint), "an accepted proof did not pin its certificate")

        assertEquals(
            "join proof replay",
            CircleCore.joinProofFailure(request(signer.fingerprint, listOf(accepted)), HOST, NOW),
        )
    }

    // ---- helpers -----------------------------------------------------------

    private fun proofBody(
        signer: TlsIdentity,
        memberFp: String = signer.fingerprint,
        host: String = HOST,
        invite: String? = INVITE,
        bucket: Long = CircleCore.joinProofBucket(NOW),
    ): String = CircleCore.joinProofBody(host, CIRCLE, invite, memberFp, bucket)

    private fun proofEvent(
        signer: TlsIdentity,
        memberFp: String = signer.fingerprint,
        created: Long = NOW,
        host: String = HOST,
        invite: String? = INVITE,
    ): CircleEvent = CircleCore.create(
        CIRCLE, memberFp, signer, CircleEvent.JOIN,
        proofBody(signer, memberFp, host, invite, CircleCore.joinProofBucket(created)),
        0L, created,
    )

    /** Signs a proof event directly, bypassing create()/sign(), which pin. */
    private fun selfSignedProofEvent(
        signer: TlsIdentity,
        body: String,
        created: Long = NOW,
    ): CircleEvent {
        val bare = CircleEvent(
            id = UUID.randomUUID().toString(),
            circleId = CIRCLE,
            authorFp = signer.fingerprint,
            lamport = 0L,
            type = CircleEvent.JOIN,
            body = body,
            sig = "",
            createdMs = created,
            cert = Base64.getEncoder().encodeToString(signer.certificate.encoded),
        )
        return bare.copy(sig = signer.sign(CircleCore.canonical(bare)))
    }

    private fun request(
        memberFp: String,
        events: List<CircleEvent>,
        circle: String = CIRCLE,
        invite: String? = INVITE,
        action: String = CircleRequest.ACTION_JOIN,
    ): CircleRequest = CircleRequest(action, circle, invite, memberFp, events)

    private fun proofOf(body: String): String =
        ((WireJson.json.parseToJsonElement(body) as JsonObject)["proof"] as JsonPrimitive).content

    /** Rebuilds the documented nonce layout without calling the production builder. */
    private fun independentNonce(
        hostFp: String,
        circleId: String,
        invite: String,
        memberFp: String,
        bucket: Long,
    ): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        fun field(value: String) {
            val raw = value.trim().uppercase().toByteArray(Charsets.UTF_8)
            out.writeInt(raw.size)
            out.write(raw)
        }
        field("FLITJOIN1")
        field(hostFp)
        field(circleId)
        field(invite)
        field(memberFp)
        out.writeLong(bucket)
        out.flush()
        return bytes.toByteArray()
    }

    private companion object {
        const val HOST = "AAAA"
        const val CIRCLE = "circle-1"
        const val INVITE = "invite-1"
        const val BUCKET_MS = 300_000L
        const val BUCKET_START = 1_800_000_000_000L
        const val NOW = 1_800_000_001_234L

        /** Length-prefixed FLITJOIN1 + ABCD + CIRCLE-1 + INVITE-1 + EF01 + 6,000,000. */
        const val NONCE_GOLDEN =
            "00000009464c49544a4f494e31" +
                "0000000441424344" +
                "00000008434952434c452d31" +
                "00000008494e564954452d31" +
                "0000000445463031" +
                "00000000005b8d80"

        fun hex(value: String): ByteArray = ByteArray(value.length / 2) { i ->
            ((Character.digit(value[i * 2], 16) shl 4) or Character.digit(value[i * 2 + 1], 16)).toByte()
        }
    }
}
