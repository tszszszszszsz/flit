package app.flit.share.protocol

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.tls.TlsIdentity
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Canonical stability across the eleventh Circle verb. The ten pre-receipt
 * verbs keep their byte-for-byte canonical forms after RECEIPT joins TYPES, and
 * a receipt is signed and verified by the same FLITCIRCLE1 canonical path as
 * every other event — no second primitive, no second domain tag.
 *
 * The golden bytes below are generated independently from the documented field
 * rule (domain tag, then four-byte length-prefixed UTF-8 fields, with lamport
 * and createdMs as eight-byte longs). They do not call CircleCore.canonical.
 * No socket, Android class or clock sleep is used; createdMs is explicit.
 */
class ReceiptCanonicalTest {

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

    // ---- canonical vectors -------------------------------------------------

    @Test
    fun `canonical vectors for the ten pre-receipt verbs are byte-identical`() {
        assertEquals(
            PRE_RECEIPT + CircleEvent.RECEIPT,
            CircleEvent.TYPES,
            "RECEIPT must be additive to TYPES",
        )
        assertEquals(
            PRE_RECEIPT,
            CircleEvent.TYPES - CircleEvent.RECEIPT,
            "adding the receipt verb removed no existing verb",
        )
        for ((type, expectedHex) in GOLDEN) {
            val event = vectorEvent(type, BODIES.getValue(type))
            assertContentEquals(
                hex(expectedHex),
                CircleCore.canonical(event),
                "$type canonical bytes changed",
            )
        }
    }

    @Test
    fun `receipt canonical bytes match the independent vector and carry the FLITCIRCLE1 tag`() {
        val receipt = vectorEvent(CircleEvent.RECEIPT, RECEIPT_BODY)
        val bytes = CircleCore.canonical(receipt)

        assertContentEquals(hex(RECEIPT_GOLDEN), bytes, "receipt canonical bytes changed")
        assertContentEquals(TAG_FIELD, bytes.copyOfRange(0, TAG_FIELD.size), "domain tag is not FLITCIRCLE1")
        assertContentEquals(
            CircleCore.canonical(vectorEvent(CircleEvent.MESSAGE, BODIES.getValue(CircleEvent.MESSAGE)))
                .copyOfRange(0, TAG_FIELD.size),
            bytes.copyOfRange(0, TAG_FIELD.size),
            "receipt uses the same domain tag field as a pre-receipt verb",
        )
    }

    // ---- receipt signature -------------------------------------------------

    @Test
    fun `a minted receipt verifies over its FLITCIRCLE1 canonical bytes`() {
        val id = identity()
        val event = CircleCore.create(
            circleId = "circle-1",
            authorFp = id.fingerprint,
            identity = id,
            type = CircleEvent.RECEIPT,
            body = RECEIPT_BODY,
            lamport = 7L,
            createdMs = FIXED_MS,
        )

        assertEquals(CircleEvent.RECEIPT, event.type)
        assertEquals(RECEIPT_BODY, event.body)
        assertEquals(id.fingerprint, event.authorFp)
        assertTrue(event.sig.isNotBlank())
        assertTrue(CircleCore.verify(event), "fresh receipt does not verify")

        val signed = CircleCore.canonical(event)
        assertTrue(id.verify(id.fingerprint, signed, event.sig), "signature is not over the canonical bytes")
        assertTrue(
            TlsIdentity.verifyWithCertificate(id.certificate, signed, event.sig),
            "embedded certificate does not verify the canonical bytes",
        )
        assertContentEquals(signed, CircleCore.canonical(event.copy()), "a copy changed the signed bytes")
    }

    @Test
    fun `the FLITCIRCLE1 domain tag is covered by the receipt signature`() {
        val id = identity()
        val event = CircleCore.create(
            circleId = "circle-1",
            authorFp = id.fingerprint,
            identity = id,
            type = CircleEvent.RECEIPT,
            body = RECEIPT_BODY,
            lamport = 7L,
            createdMs = FIXED_MS,
        )

        assertContentEquals(
            independentCanonical(event),
            CircleCore.canonical(event),
            "independent field writer disagrees with CircleCore.canonical",
        )
        assertFalse(
            TlsIdentity.verifyWithCertificate(
                id.certificate,
                independentCanonical(event, "FLITCIRCLE2"),
                event.sig,
            ),
            "changing only the domain tag still verified",
        )
    }

    @Test
    fun `editing a signed receipt breaks verify until it is re-signed`() {
        val id = identity()
        val stranger = identity()
        val event = CircleCore.create(
            circleId = "circle-1",
            authorFp = id.fingerprint,
            identity = id,
            type = CircleEvent.RECEIPT,
            body = RECEIPT_BODY,
            lamport = 7L,
            createdMs = FIXED_MS,
        )
        assertTrue(CircleCore.verify(event))

        val edits = listOf(
            "body" to event.copy(body = RECEIPT_BODY.replace("key-1", "key-2")),
            "type" to event.copy(type = CircleEvent.MESSAGE),
            "lamport" to event.copy(lamport = event.lamport + 1),
            "createdMs" to event.copy(createdMs = event.createdMs + 1),
            "circleId" to event.copy(circleId = "other"),
            "id" to event.copy(id = "other"),
            "authorFp" to event.copy(authorFp = "DEADBEEF"),
            "cert" to event.copy(cert = certB64(stranger)),
            "sig" to event.copy(sig = "not base64 !!"),
            "blank sig" to event.copy(sig = ""),
        )
        for ((label, edited) in edits) {
            assertFalse(CircleCore.verify(edited), "$label edit still verifies")
        }

        val reSigned = CircleCore.sign(event.copy(sig = ""), id)
        assertTrue(CircleCore.verify(reSigned), "re-signed receipt does not verify")
        assertContentEquals(CircleCore.canonical(event), CircleCore.canonical(reSigned), "re-signing altered the signed bytes")
    }

    @Test
    fun `a receipt cert bound to a foreign author does not verify and writes no pin`() {
        val signer = identity()
        val victim = identity()
        TlsIdentity.forgetPin(victim.fingerprint) // first-seen claimed author

        val forged = CircleCore.create(
            circleId = "circle-1",
            authorFp = victim.fingerprint,
            identity = signer,
            type = CircleEvent.RECEIPT,
            body = RECEIPT_BODY,
            lamport = 7L,
            createdMs = FIXED_MS,
        )

        assertFalse(CircleCore.verify(forged), "signer's receipt verified under the victim's fingerprint")
        assertNull(TlsIdentity.pinned(victim.fingerprint), "failed verify pinned the forged certificate")
        assertFalse(
            TlsIdentity.verify(victim.fingerprint, CircleCore.canonical(forged), forged.sig),
            "foreign fingerprint verified the signature",
        )
    }

    // ---- helpers -----------------------------------------------------------

    private fun vectorEvent(type: String, body: String): CircleEvent = CircleEvent(
        id = "id-1",
        circleId = "circle-1",
        authorFp = "ABCD",
        lamport = 7L,
        type = type,
        body = body,
        sig = "",
        createdMs = FIXED_MS,
    )

    /** Rebuilds the documented canonical layout without calling CircleCore.canonical. */
    private fun independentCanonical(e: CircleEvent, tag: String = "FLITCIRCLE1"): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        fun field(value: String) {
            val raw = value.toByteArray(Charsets.UTF_8)
            out.writeInt(raw.size)
            out.write(raw)
        }
        field(tag)
        field(e.id)
        field(e.circleId)
        field(e.authorFp)
        out.writeLong(e.lamport)
        field(e.type)
        field(e.body)
        out.writeLong(e.createdMs)
        out.flush()
        return bytes.toByteArray()
    }

    private fun certB64(id: TlsIdentity): String =
        Base64.getEncoder().encodeToString(id.certificate.encoded)

    companion object {
        private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { i ->
            ((Character.digit(value[i * 2], 16) shl 4) or Character.digit(value[i * 2 + 1], 16)).toByte()
        }

        private const val FIXED_MS = 1_800_000_000_000L
        private const val RECEIPT_BODY = """{"dropId":"drop-1","sha256":"deadbeef","keyId":"key-1"}"""

        /** Four-byte big-endian length 11 followed by the UTF-8 domain tag. */
        private val TAG_FIELD: ByteArray =
            hex("0000000b") + "FLITCIRCLE1".toByteArray(Charsets.UTF_8)

        private val PRE_RECEIPT = setOf(
            CircleEvent.MESSAGE,
            CircleEvent.FILE,
            CircleEvent.POLL,
            CircleEvent.VOTE,
            CircleEvent.JOIN,
            CircleEvent.LEAVE,
            CircleEvent.KICK,
            CircleEvent.ROLE,
            CircleEvent.REKEY,
            CircleEvent.PARCEL,
        )

        private val BODIES = mapOf(
            CircleEvent.MESSAGE to """{"text":"hi"}""",
            CircleEvent.FILE to """{"name":"a.txt","size":12,"sha256":"abc"}""",
            CircleEvent.POLL to """{"pollId":"p1","question":"q","options":["a","b"]}""",
            CircleEvent.VOTE to """{"pollId":"p1","option":1}""",
            CircleEvent.JOIN to """{"alias":"alice"}""",
            CircleEvent.LEAVE to "{}",
            CircleEvent.KICK to """{"kickId":"k1","targetFp":"ABCD","mode":"admin","approve":true}""",
            CircleEvent.ROLE to """{"targetFp":"ABCD","role":"ADMIN"}""",
            CircleEvent.REKEY to """{"keyB64":"a2V5","gen":1}""",
            CircleEvent.PARCEL to """{"toFp":"ABCD","envelope":"ZW52"}""",
        )

        /** Golden canonical bytes, generated independently from the documented field rule. */
        private val GOLDEN = mapOf(
            CircleEvent.MESSAGE to
                "0000000b464c4954434952434c45310000000469642d3100000008636972636c" +
                "652d3100000004414243440000000000000007000000076d6573736167650000" +
                "000d7b2274657874223a226869227d000001a3185c5000",
            CircleEvent.FILE to
                "0000000b464c4954434952434c45310000000469642d3100000008636972636c" +
                "652d31000000044142434400000000000000070000000466696c65000000297b" +
                "226e616d65223a22612e747874222c2273697a65223a31322c22736861323536" +
                "223a22616263227d000001a3185c5000",
            CircleEvent.POLL to
                "0000000b464c4954434952434c45310000000469642d3100000008636972636c" +
                "652d310000000441424344000000000000000700000004706f6c6c000000327b" +
                "22706f6c6c4964223a227031222c227175657374696f6e223a2271222c226f70" +
                "74696f6e73223a5b2261222c2262225d7d000001a3185c5000",
            CircleEvent.VOTE to
                "0000000b464c4954434952434c45310000000469642d3100000008636972636c" +
                "652d310000000441424344000000000000000700000004766f74650000001a7b" +
                "22706f6c6c4964223a227031222c226f7074696f6e223a317d000001a3185c50" +
                "00",
            CircleEvent.JOIN to
                "0000000b464c4954434952434c45310000000469642d3100000008636972636c" +
                "652d3100000004414243440000000000000007000000046a6f696e000000117b" +
                "22616c696173223a22616c696365227d000001a3185c5000",
            CircleEvent.LEAVE to
                "0000000b464c4954434952434c45310000000469642d3100000008636972636c" +
                "652d3100000004414243440000000000000007000000056c6561766500000002" +
                "7b7d000001a3185c5000",
            CircleEvent.KICK to
                "0000000b464c4954434952434c45310000000469642d3100000008636972636c" +
                "652d3100000004414243440000000000000007000000046b69636b0000003f7b" +
                "226b69636b4964223a226b31222c227461726765744670223a2241424344222c" +
                "226d6f6465223a2261646d696e222c22617070726f7665223a747275657d0000" +
                "01a3185c5000",
            CircleEvent.ROLE to
                "0000000b464c4954434952434c45310000000469642d3100000008636972636c" +
                "652d310000000441424344000000000000000700000004726f6c65000000227b" +
                "227461726765744670223a2241424344222c22726f6c65223a2241444d494e22" +
                "7d000001a3185c5000",
            CircleEvent.REKEY to
                "0000000b464c4954434952434c45310000000469642d3100000008636972636c" +
                "652d31000000044142434400000000000000070000000572656b657900000019" +
                "7b226b6579423634223a2261325635222c2267656e223a317d000001a3185c50" +
                "00",
            CircleEvent.PARCEL to
                "0000000b464c4954434952434c45310000000469642d3100000008636972636c" +
                "652d31000000044142434400000000000000070000000670617263656c000000" +
                "217b22746f4670223a2241424344222c22656e76656c6f7065223a225a573532" +
                "227d000001a3185c5000",
        )

        private const val RECEIPT_GOLDEN =
            "0000000b464c4954434952434c45310000000469642d3100000008636972636c" +
            "652d310000000441424344000000000000000700000007726563656970740000" +
            "00377b2264726f704964223a2264726f702d31222c22736861323536223a2264" +
            "65616462656566222c226b65794964223a226b65792d31227d000001a3185c50" +
            "00"
    }
}
