package app.flit.share.protocol

import app.flit.share.protocol.parcel.ParcelCodec
import app.flit.share.protocol.tls.TlsIdentity
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

    companion object {
        const val PARCEL_ID = "tamper-parcel-1"
    }
}
