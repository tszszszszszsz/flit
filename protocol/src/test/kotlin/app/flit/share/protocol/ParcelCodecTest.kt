package app.flit.share.protocol

import app.flit.share.protocol.parcel.ParcelCodec
import app.flit.share.protocol.tls.TlsIdentity
import java.security.MessageDigest
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PURE frame-layer tests for the [ParcelCodec] seam. No Android, camera, socket,
 * file, clock or Gradle dependency: every fixture is an in-memory [String] or
 * [ByteArray]. Frame strings stay opaque here — tests read metadata only through
 * [ParcelCodec.parse] and never edit or reorder frame internals.
 *
 * Two real identities are used: [sender] signs through `encodeParcel` and the
 * recipient certificate is passed explicitly to seal the payload; [recipient]
 * is passed to `complete` together with its fingerprint so the envelope can be
 * opened. No certificate pin setup is needed in the test itself.
 */
class ParcelCodecTest {

    private val sender = TlsIdentity.generate()
    private val recipient = TlsIdentity.generate()

    private fun encode(payload: ByteArray, parcelId: String): List<String> =
        ParcelCodec.encodeParcel(
            payload,
            parcelId,
            recipient.fingerprint,
            recipient.certificate,
            sender.fingerprint,
            sender,
        )

    private fun sha256Hex(payload: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

    private fun assertEncodeRefused(
        payload: ByteArray = "payload".toByteArray(),
        parcelId: String = "parcel-refusal",
        recipientFp: String = recipient.fingerprint,
        recipientCert: X509Certificate = recipient.certificate,
        signerFp: String = sender.fingerprint,
        identity: TlsIdentity = sender,
    ) {
        val frames = ParcelCodec.encodeParcel(
            payload, parcelId, recipientFp, recipientCert, signerFp, identity,
        )
        assertTrue(frames.isEmpty(), "encode emits no frame when an input is refused")
    }

    @Test
    fun `parse returns null for empty junk and non-parcel strings`() {
        val junk = listOf(
            "",
            " ",
            "not-a-frame",
            "FLITPARCEL1",
            "flit://add?name=friend&key=abc",
            "flit://pair?id=abcdef",
        )
        for (frame in junk) {
            assertNull(ParcelCodec.parse(frame), "'$frame' must not parse as a parcel frame")
        }
    }

    @Test
    fun `encodeParcel emits parseable frames with consistent metadata`() {
        val payload = "air gap bytes".toByteArray()
        val frames = encode(payload, "parcel-meta")

        assertTrue(frames.isNotEmpty(), "one payload yields at least one frame")
        val descriptors = frames.map { ParcelCodec.parse(it) }
        assertTrue(descriptors.all { it != null }, "every emitted frame parses")
        val first = checkNotNull(descriptors.first())

        assertEquals("parcel-meta", first.parcelId)
        assertEquals(frames.size, first.count)
        assertTrue(first.totalBytes > 0)
        assertTrue(descriptors.all { it!!.parcelId == first.parcelId })
        assertTrue(descriptors.all { it!!.count == first.count })
        assertTrue(descriptors.all { it!!.totalBytes == first.totalBytes })
        assertTrue(descriptors.all { it!!.crc >= 0 }, "CRC32 is a non-negative value")
    }

    @Test
    fun `frame descriptors expose one-based sequence and a final count`() {
        val payload = ByteArray(2048) { (it % 251).toByte() }
        val frames = encode(payload, "parcel-seq")

        assertTrue(frames.size > 1, "a 2 KiB payload needs more than one frame")
        val parsed = frames.map { checkNotNull(ParcelCodec.parse(it)) { "frame must parse" } }
        assertEquals((1..frames.size).toList(), parsed.map { it.seq })
        assertEquals(frames.size, parsed.last().count)
        assertEquals(parsed.last().seq, parsed.last().count, "the last frame is the final one")
    }

    @Test
    fun `complete returns the original payload and its metadata`() {
        val payload = "signed air-gap payload".toByteArray()
        val frames = encode(payload, "parcel-roundtrip")

        val opened = checkNotNull(ParcelCodec.complete(frames, recipient.fingerprint, recipient))
        assertEquals("parcel-roundtrip", opened.parcelId)
        assertEquals(recipient.fingerprint, opened.recipientFp)
        assertEquals(sender.fingerprint, opened.signerFp)
        assertTrue(payload.contentEquals(opened.bytes), "opened bytes equal the sealed payload")
        assertEquals(sha256Hex(payload).lowercase(), opened.sha256.lowercase())
        assertEquals(64, opened.sha256.length)
    }

    @Test
    fun `complete round-trips arbitrary binary bytes including zero`() {
        val payload = ByteArray(512) { if (it % 3 == 0) 0.toByte() else (it * 31).toByte() }
        val frames = encode(payload, "parcel-binary")

        val opened = checkNotNull(ParcelCodec.complete(frames, recipient.fingerprint, recipient))
        assertTrue(payload.contentEquals(opened.bytes))
        assertEquals(sha256Hex(payload).lowercase(), opened.sha256.lowercase())
    }

    @Test
    fun `complete accepts frames in reverse order`() {
        val payload = ByteArray(1024) { (it xor 0x7f).toByte() }
        val frames = encode(payload, "parcel-reversed")

        val opened = checkNotNull(
            ParcelCodec.complete(frames.reversed(), recipient.fingerprint, recipient),
        )
        assertTrue(payload.contentEquals(opened.bytes))
    }

    @Test
    fun `complete deduplicates repeated frames`() {
        val payload = ByteArray(1024) { it.toByte() }
        val frames = encode(payload, "parcel-duplicate")

        val withRepeats = frames + listOf(frames.first(), frames.last()) + frames
        val opened = checkNotNull(ParcelCodec.complete(withRepeats, recipient.fingerprint, recipient))
        assertTrue(payload.contentEquals(opened.bytes))
    }

    @Test
    fun `complete is stateless across repeated calls`() {
        val payload = "repeat".toByteArray()
        val frames = encode(payload, "parcel-stateless")

        val first = checkNotNull(ParcelCodec.complete(frames, recipient.fingerprint, recipient))
        val second = checkNotNull(ParcelCodec.complete(frames, recipient.fingerprint, recipient))
        assertTrue(payload.contentEquals(first.bytes))
        assertTrue(first.bytes.contentEquals(second.bytes))
    }

    @Test
    fun `complete refuses an incomplete sequence`() {
        val frames = encode(ByteArray(2048) { it.toByte() }, "parcel-missing")
        assertTrue(frames.size > 1)

        assertNull(ParcelCodec.complete(frames.dropLast(1), recipient.fingerprint, recipient))
    }

    @Test
    fun `complete refuses frames from two different parcels`() {
        val first = encode("one".toByteArray(), "parcel-one")
        val second = encode("two".toByteArray(), "parcel-two")

        assertNull(ParcelCodec.complete(first + second, recipient.fingerprint, recipient))
        assertNull(ParcelCodec.complete(first.dropLast(1) + second, recipient.fingerprint, recipient))
    }

    @Test
    fun `complete refuses an empty or junk-contaminated frame set`() {
        val frames = encode("x".toByteArray(), "parcel-junk")

        assertNull(ParcelCodec.complete(emptyList(), recipient.fingerprint, recipient))
        assertNull(ParcelCodec.complete(listOf("not-a-frame"), recipient.fingerprint, recipient))
        assertNull(ParcelCodec.complete(frames + "not-a-frame", recipient.fingerprint, recipient))
    }

    @Test
    fun `encodeParcel refuses a payload over the air-gap byte cap`() {
        val huge = ByteArray(131_073) { 1 }

        val attempted = runCatching { encode(huge, "parcel-huge") }
        assertTrue(
            attempted.isFailure || attempted.getOrNull().orEmpty().isEmpty(),
            "a payload over MAX_AIR_GAP_PARCEL_BYTES yields no frames",
        )
    }

    @Test
    fun `frame count stays within the 64-frame wire cap`() {
        val payload = ByteArray(3072) { (it xor 0x5a).toByte() }
        val frames = encode(payload, "parcel-cap")

        assertTrue(frames.isNotEmpty())
        assertTrue(frames.size <= 64, "the wire cap is 64 frames")
        val opened = checkNotNull(ParcelCodec.complete(frames, recipient.fingerprint, recipient))
        assertTrue(payload.contentEquals(opened.bytes))
    }

    @Test
    fun `complete refuses a wrong recipient fingerprint`() {
        val stranger = TlsIdentity.generate()
        val frames = encode("for recipient".toByteArray(), "parcel-wrong-fp")

        assertNull(ParcelCodec.complete(frames, "C".repeat(64), recipient))
        assertNull(
            ParcelCodec.complete(frames, stranger.fingerprint, stranger),
            "another device cannot open a parcel sealed to the recipient",
        )
        assertNull(
            ParcelCodec.complete(frames, recipient.fingerprint, stranger),
            "the right fingerprint with the wrong private key opens nothing",
        )
    }

    @Test
    fun `encodeParcel refuses an empty payload`() {
        assertEncodeRefused(payload = ByteArray(0))
    }

    @Test
    fun `encodeParcel refuses a payload above the effective envelope cap`() {
        // 8 KiB is inside the defensive 128 KiB frame cap, but the sealed
        // envelope exceeds CourierEnvelope.MAX_ENVELOPE_CHARS after base64,
        // so seal refuses and no sequence is emitted.
        assertEncodeRefused(payload = ByteArray(8192) { 7 })
    }

    @Test
    fun `encodeParcel refuses blank and oversized parcel ids`() {
        assertEncodeRefused(parcelId = "   ")
        assertEncodeRefused(parcelId = "I".repeat(257))
    }

    @Test
    fun `encodeParcel refuses a certificate whose fingerprint differs from recipientFp`() {
        val stranger = TlsIdentity.generate()
        assertEncodeRefused(recipientCert = stranger.certificate)
    }

    @Test
    fun `encodeParcel refuses a signerFp that is not the identity fingerprint`() {
        val stranger = TlsIdentity.generate()
        assertEncodeRefused(signerFp = stranger.fingerprint)
    }

    @Test
    fun `parse refuses non-canonical numeric fields`() {
        val frame = encode("canonical".toByteArray(), "parcel-numeric").first()
        val parts = frame.split(".").toMutableList()
        fun mutated(index: Int, value: String): String =
            parts.toMutableList().also { it[index] = value }.joinToString(".")

        assertNull(
            ParcelCodec.parse(mutated(1, " 1")),
            "a version with leading whitespace is not canonical",
        )
        assertNull(
            ParcelCodec.parse(mutated(3, "1_0")),
            "a seq with a digit separator is not canonical",
        )
        assertNull(
            ParcelCodec.parse(mutated(4, "1e0")),
            "a count in exponent form is not canonical",
        )
        assertNull(
            ParcelCodec.parse(mutated(6, "99999999999999999999")),
            "an overflowing total is not canonical",
        )
    }

    @Test
    fun `parse refuses padded base64 fields`() {
        // "abcdef" encodes to eight unpadded URL-safe chars; two '=' after a
        // full quantum leave an invalid ending unit for the URL-safe decoder.
        val frame = encode("padded".toByteArray(), "abcdef").first()
        val parts = frame.split(".").toMutableList()

        val paddedId = parts[2] + "=="
        assertNull(
            ParcelCodec.parse(parts.toMutableList().also { it[2] = paddedId }.joinToString(".")),
        )
        val paddedChunk = parts[10] + "===="
        assertNull(
            ParcelCodec.parse(parts.toMutableList().also { it[10] = paddedChunk }.joinToString(".")),
        )
    }
}
