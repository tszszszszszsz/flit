package app.flit.share.protocol

import app.flit.share.protocol.courier.CourierEnvelope
import app.flit.share.protocol.parcel.ParcelCodec
import app.flit.share.protocol.tls.TlsIdentity
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PURE tamper cases for the frozen `ParcelCodec` seam. Every case drives opaque
 * frame strings only: no camera, QR renderer, socket, file or clock of our own.
 * Identities are generated in-process; the recipient certificate is passed to
 * `encodeParcel` and the recipient identity is passed to `complete`, so signer
 * and recipient are independent devices here.
 *
 * Frame strings are never interpreted here. A corrupted frame is produced by
 * changing one character of an intact string, searching outward from the
 * middle so the change lands in the bulk data region rather than a prefix.
 */
class ParcelCodecTamperTest {

    private data class Endpoints(val recipient: TlsIdentity, val signer: TlsIdentity)

    private fun endpoints(): Endpoints = Endpoints(TlsIdentity.generate(), TlsIdentity.generate())

    private fun encode(e: Endpoints, payload: ByteArray): List<String> =
        encode(e.recipient, e.signer, payload)

    private fun encode(recipient: TlsIdentity, signer: TlsIdentity, payload: ByteArray): List<String> {
        val frames = ParcelCodec.encodeParcel(
            payload,
            PARCEL_ID,
            recipient.fingerprint,
            recipient.certificate,
            signer.fingerprint,
            signer,
        )
        assertTrue(frames.isNotEmpty(), "a well-formed parcel must emit at least one frame")
        return frames
    }

    private fun payload(): ByteArray = ByteArray(1500) { (it * 31).toByte() }

    /**
     * First single-character replacement, scanned from the middle outward, that
     * makes `parse` refuse the frame. Grammar-agnostic: no field name or order
     * is assumed, only that the bulk of a frame is covered by its CRC.
     */
    private fun firstParseBreakingMutation(frame: String): String? {
        val center = frame.length / 2
        for (distance in 0 until frame.length) {
            for (index in listOf(center + distance, center - distance)) {
                if (index !in frame.indices) continue
                val replacement = if (frame[index] != 'Q') 'Q' else 'W'
                val changed = frame.substring(0, index) + replacement + frame.substring(index + 1)
                if (ParcelCodec.parse(changed) == null) return changed
            }
        }
        return null
    }

    // Independent CRC/digest rebuild helpers. They mirror the documented wire
    // form and canonical byte layout, but share no code with ParcelCodec, so a
    // frame that passes parse after a field change proves the change is covered
    // by a correctly recomputed CRC rather than by a parser blind spot.

    private fun b64(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun unb64(part: String): ByteArray = Base64.getUrlDecoder().decode(part)

    private fun field(out: DataOutputStream, bytes: ByteArray) {
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    /** CRC32 over the domain-tagged, length-prefixed canonical frame bytes. */
    private fun frameCrc(parts: List<String>): Long {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        field(out, ParcelCodec.FRAME_MAGIC.toByteArray(Charsets.UTF_8))
        out.writeInt(1)
        field(out, unb64(parts[2]))
        out.writeInt(parts[3].toInt())
        out.writeInt(parts[4].toInt())
        out.writeInt(parts[5].toInt())
        out.writeInt(parts[6].toInt())
        field(out, unb64(parts[7]))
        field(out, unb64(parts[8]))
        field(out, unb64(parts[9]))
        field(out, unb64(parts[10]))
        out.flush()
        val crc = CRC32()
        crc.update(bytes.toByteArray())
        return crc.value
    }

    /** Replaces the trailing crc8 field with a CRC of the (possibly tampered) fields. */
    private fun reCrc(parts: List<String>): String {
        val rebuilt = parts.toMutableList()
        rebuilt[11] = String.format("%08x", frameCrc(rebuilt))
        return rebuilt.joinToString(".")
    }

    /** Flips one byte inside a decoded field, then repairs the frame CRC. */
    private fun flipField(frame: String, index: Int): String {
        val parts = frame.split('.').toMutableList()
        val bytes = unb64(parts[index])
        bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
        parts[index] = b64(bytes)
        return reCrc(parts)
    }

    /** SHA-256 of the whole framed envelope plus its routing metadata. */
    private fun parcelDigest(parcelId: String, signerFp: String, recipientFp: String, envelope: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        field(out, "FLITPARD1".toByteArray(Charsets.UTF_8))
        field(out, parcelId.toByteArray(Charsets.UTF_8))
        field(out, signerFp.toByteArray(Charsets.UTF_8))
        field(out, recipientFp.toByteArray(Charsets.UTF_8))
        field(out, envelope)
        out.flush()
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
    }

    /** Concatenates intact chunks by their wire seq into the framed envelope bytes. */
    private fun envelopeOf(frames: List<String>): ByteArray = ByteArrayOutputStream().also { out ->
        frames.sortedBy { it.split('.')[3].toInt() }.forEach { out.write(unb64(it.split('.')[10])) }
    }.toByteArray()

    /** Rebuilds a frame from fields with a fresh CRC. */
    private fun frameOf(
        parcelId: String,
        seq: Int,
        count: Int,
        sz: Int,
        total: Int,
        digest: ByteArray,
        signerFp: String,
        recipientFp: String,
        chunk: ByteArray,
    ): String = reCrc(
        listOf(
            ParcelCodec.FRAME_MAGIC,
            "1",
            b64(parcelId.toByteArray(Charsets.UTF_8)),
            seq.toString(),
            count.toString(),
            sz.toString(),
            total.toString(),
            b64(digest),
            b64(signerFp.toByteArray(Charsets.UTF_8)),
            b64(recipientFp.toByteArray(Charsets.UTF_8)),
            b64(chunk),
            "00000000",
        ),
    )

    @Test
    fun `a changed byte in one frame fails its CRC and complete refuses the parcel`() {
        val e = endpoints()
        val frames = encode(e, payload())
        val intact = frames.first()
        assertNotNull(ParcelCodec.parse(intact), "the frame must parse before tampering")

        val broken = firstParseBreakingMutation(intact)
            ?: error("no single-character change broke the frame CRC")
        assertNull(ParcelCodec.parse(broken), "the mutated frame must be refused by parse")

        val tamperedSet = frames.toMutableList().also { it[0] = broken }
        assertNull(ParcelCodec.complete(tamperedSet, e.recipient.fingerprint, e.recipient))
    }

    @Test
    fun `a sequence missing one frame never completes`() {
        val e = endpoints()
        val frames = encode(e, payload())
        assertTrue(frames.size >= 2, "the fixture must span more than one frame")
        assertTrue(frames.all { ParcelCodec.parse(it) != null }, "every emitted frame parses")

        assertNull(ParcelCodec.complete(frames.dropLast(1), e.recipient.fingerprint, e.recipient))
    }

    @Test
    fun `frames reassemble byte-exactly when presented out of order`() {
        val e = endpoints()
        val data = payload()
        val frames = encode(e, data)
        assertTrue(frames.size >= 2)

        val opened = ParcelCodec.complete(frames.reversed(), e.recipient.fingerprint, e.recipient)
        assertNotNull(opened)
        assertContentEquals(data, opened.bytes)
        assertEquals(e.recipient.fingerprint, opened.recipientFp)
        assertEquals(e.signer.fingerprint, opened.signerFp)
    }

    @Test
    fun `a frame carrying a second signer's envelope is refused`() {
        val recipient = TlsIdentity.generate()
        val signer = TlsIdentity.generate()
        val otherSigner = TlsIdentity.generate()
        val data = payload()
        val mine = encode(recipient, signer, data)
        val theirs = encode(recipient, otherSigner, data)

        val forged = mine.toMutableList().also { it[0] = theirs[0] }
        assertNull(
            ParcelCodec.complete(forged, recipient.fingerprint, recipient),
            "a frame signed by another key cannot join this parcel",
        )
    }

    @Test
    fun `complete refuses a parcel addressed to a different recipient`() {
        val e = endpoints()
        val stranger = TlsIdentity.generate()
        val frames = encode(e, payload())

        assertNull(
            ParcelCodec.complete(frames, stranger.fingerprint, e.recipient),
            "a claimed fingerprint that is not the envelope recipient is refused",
        )
        assertNull(
            ParcelCodec.complete(frames, e.recipient.fingerprint, stranger),
            "a stranger without the wrapping private key cannot open the parcel",
        )
    }

    @Test
    fun `an oversized frame string and an oversized parcel are refused`() {
        val oversized = "FLITPARCEL1?" + "A".repeat(200_000)
        assertNull(ParcelCodec.parse(oversized))

        val e = endpoints()
        val tooBig = ByteArray(512 * 64 + 1) { (it % 251).toByte() }
        val attempt = runCatching {
            ParcelCodec.encodeParcel(
                tooBig,
                PARCEL_ID,
                e.recipient.fingerprint,
                e.recipient.certificate,
                e.signer.fingerprint,
                e.signer,
            )
        }
        val frames = attempt.getOrNull().orEmpty()
        assertTrue(
            attempt.isFailure || frames.isEmpty() ||
                ParcelCodec.complete(frames, e.recipient.fingerprint, e.recipient) == null,
            "a payload past the frame capacity must not yield a completable parcel",
        )
    }

    @Test
    fun `a re-CRC'd chunk parses but complete refuses on the whole-parcel digest`() {
        val e = endpoints()
        val frames = encode(e, payload())
        val tampered = flipField(frames.first(), 10)

        assertNotNull(ParcelCodec.parse(tampered), "a valid CRC must let parse accept the changed chunk")
        val tamperedSet = frames.toMutableList().also { it[0] = tampered }
        assertNull(
            ParcelCodec.complete(tamperedSet, e.recipient.fingerprint, e.recipient),
            "the reassembled envelope no longer matches the declared sha",
        )
    }

    @Test
    fun `a re-CRC'd sha field parses but complete refuses the tampered digest`() {
        val e = endpoints()
        val frames = encode(e, payload())

        val oneFrame = flipField(frames.first(), 7)
        assertNotNull(ParcelCodec.parse(oneFrame))
        val conflicting = frames.toMutableList().also { it[0] = oneFrame }
        assertNull(
            ParcelCodec.complete(conflicting, e.recipient.fingerprint, e.recipient),
            "one altered sha disagrees with the other frames",
        )

        val allTampered = frames.map { flipField(it, 7) }
        assertTrue(allTampered.all { ParcelCodec.parse(it) != null }, "every re-CRC'd frame parses")
        assertNull(
            ParcelCodec.complete(allTampered, e.recipient.fingerprint, e.recipient),
            "a consistently changed sha still disagrees with the envelope bytes",
        )
    }

    @Test
    fun `a re-CRC'd routing field parses but complete refuses the changed route`() {
        val e = endpoints()
        val frames = encode(e, payload())
        val stranger = TlsIdentity.generate()

        val forRecipient = frames.map { frame ->
            val parts = frame.split('.').toMutableList()
            parts[9] = b64(stranger.fingerprint.toByteArray(Charsets.UTF_8))
            reCrc(parts)
        }
        assertTrue(forRecipient.all { ParcelCodec.parse(it) != null })
        assertNull(
            ParcelCodec.complete(forRecipient, e.recipient.fingerprint, e.recipient),
            "the frames no longer name the caller's fingerprint",
        )

        val forSigner = frames.map { frame ->
            val parts = frame.split('.').toMutableList()
            parts[8] = b64(stranger.fingerprint.toByteArray(Charsets.UTF_8))
            reCrc(parts)
        }
        assertTrue(forSigner.all { ParcelCodec.parse(it) != null })
        assertNull(
            ParcelCodec.complete(forSigner, e.recipient.fingerprint, e.recipient),
            "the claimed signer no longer matches the signed envelope",
        )

        // Repair the digest too, so refusal can only come from the routing check.
        val head = frames.first().split('.')
        val parcelId = unb64(head[2]).toString(Charsets.UTF_8)
        val signerFp = unb64(head[8]).toString(Charsets.UTF_8)
        val route = stranger.fingerprint
        val redirected = frames.map { frame ->
            val parts = frame.split('.').toMutableList()
            parts[9] = b64(route.toByteArray(Charsets.UTF_8))
            reCrc(parts)
        }
        val repaired = parcelDigest(parcelId, signerFp, route, envelopeOf(redirected))
        val consistentRoute = redirected.map { frame ->
            val parts = frame.split('.').toMutableList()
            parts[7] = b64(repaired)
            reCrc(parts)
        }
        assertTrue(consistentRoute.all { ParcelCodec.parse(it) != null })
        assertNull(
            ParcelCodec.complete(consistentRoute, route, e.recipient),
            "the envelope itself still names the original recipient",
        )
    }

    @Test
    fun `a corrupt signature with structurally consistent frames is refused`() {
        val e = endpoints()
        val frames = encode(e, payload())
        val parts = frames.first().split('.')
        val parcelId = unb64(parts[2]).toString(Charsets.UTF_8)
        val signerFp = unb64(parts[8]).toString(Charsets.UTF_8)
        val recipientFp = unb64(parts[9]).toString(Charsets.UTF_8)
        val sz = parts[5].toInt()
        val count = parts[4].toInt()

        val envelope = CourierEnvelope.decode(String(envelopeOf(frames), Charsets.UTF_8))
            ?: error("intact frames must carry a decodable envelope")
        val forged = envelope.copy(
            sig = Base64.getEncoder().encodeToString(ByteArray(256) { 7 }),
        )
        val forgedBytes = CourierEnvelope.encode(forged).toByteArray(Charsets.UTF_8)
        val digest = parcelDigest(parcelId, signerFp, recipientFp, forgedBytes)
        val rebuilt = (0 until count).map { index ->
            val from = index * sz
            val chunk = forgedBytes.copyOfRange(from, minOf(from + sz, forgedBytes.size))
            frameOf(parcelId, index + 1, count, sz, forgedBytes.size, digest, signerFp, recipientFp, chunk)
        }

        assertTrue(rebuilt.all { ParcelCodec.parse(it) != null }, "all rebuilt frames parse and CRC")
        assertNull(
            ParcelCodec.complete(rebuilt, recipientFp, e.recipient),
            "a forged signature fails envelope verification even when every frame is consistent",
        )
    }

    companion object {
        const val PARCEL_ID = "tamper-parcel-1"
    }
}
