package app.flit.share.protocol.parcel

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.courier.CourierEnvelope
import app.flit.share.protocol.courier.MAX_ENVELOPE_CHARS
import app.flit.share.protocol.model.WireJson
import app.flit.share.protocol.tls.TlsIdentity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.UUID
import java.util.zip.CRC32

/**
 * JVM-pure QR parcel codec.
 *
 * One parcel is carried as an ordered [List] of opaque UTF-8 `String` frames.
 * The codec owns the frame layout, the per-frame CRC32, the whole-parcel
 * digest, the sequence/count rules and the size caps. It owns no camera, no
 * Android class, no socket and no persistence.
 *
 * There is exactly one crypto path: [ParcelCodec.encodeParcel] calls
 * [CourierEnvelope.seal] and [CourierEnvelope.encode], and
 * [ParcelCodec.complete] calls [CourierEnvelope.decode],
 * [CourierEnvelope.verify] and [CourierEnvelope.open]. This file contains no
 * second `Cipher`, `Signature` or key-handling code.
 *
 * Frame wire form (one QR code carries one frame), dot-separated:
 *
 * ```
 * FLITPARCEL1.1.<idB64>.<seq>.<count>.<sz>.<total>.<shaB64>.<signerB64>.<recipientB64>.<chunkB64>.<crc8>
 * ```
 *
 * `B64` is unpadded Base64 with the URL-safe alphabet. `crc8` is eight
 * lowercase hex digits of a CRC32 taken over domain-tagged, length-prefixed
 * canonical bytes with the CRC excluded. `sha` is a 32-byte SHA-256 over the
 * same kind of canonical bytes for the whole framed envelope, repeated in
 * every frame so a single lost frame cannot remove the verification metadata.
 * The `flit://add` and `flit://pair` schemes never match the magic, so a
 * pairing code is never a parcel and a parcel is never a pairing code.
 */
object ParcelCodec {

    /** Largest sequence emitted or accepted (wire and app display cap). */
    const val MAX_FRAMES = 64

    /** Defensive frame-layer ceiling, named apart from `CourierEnvelope.MAX_ENVELOPE_CHARS`. */
    const val MAX_AIR_GAP_PARCEL_BYTES = 131072

    /**
     * Largest raw payload accepted for encoding. `CourierEnvelope.MAX_ENVELOPE_CHARS * 3 / 4`
     * bounds envelope plaintext at 9216 bytes; 5120 leaves conservative room for the
     * CircleEvent JSON wrapper, its base64 text, the envelope fields, signer cert and
     * signature. [MAX_AIR_GAP_PARCEL_BYTES] stays the defensive frame-layer ceiling.
     */
    const val MAX_AIR_GAP_PAYLOAD_BYTES = 5120

    /** Largest raw chunk carried by one frame. */
    const val MAX_CHUNK_BYTES = 512

    /** Frame magic and CRC domain tag. */
    const val FRAME_MAGIC = "FLITPARCEL1"

    private const val VERSION = 1
    private const val DIGEST_TAG = "FLITPARD1"
    private const val HASH_BYTES = 32
    private const val MAX_FIELD_CHARS = CircleCore.MAX_FIELD_CHARS
    private const val MAX_FRAME_CHARS = 2048
    private const val CIRCLE_ID = "parcel"

    private val b64: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val b64url: Base64.Decoder = Base64.getUrlDecoder()

    /**
     * The public, opaque view of one parsed frame. A caller can read
     * [parcelId], [seq], [count] and [totalBytes] for progress without
     * decoding any payload bytes; [crc] is the unsigned CRC32 value.
     */
    data class FrameDescriptor(
        val parcelId: String,
        val seq: Int,
        val count: Int,
        val totalBytes: Int,
        val crc: Long,
    )

    /** The verified, decrypted parcel returned by [complete]. */
    data class ParcelPayload(
        val parcelId: String,
        val recipientFp: String,
        val signerFp: String,
        val sha256: String,
        val bytes: ByteArray,
    )

    /**
     * Full internal frame view. Kept private so callers can only ever see the
     * opaque string plus the small [FrameDescriptor].
     */
    private data class FullFrame(
        val parcelId: String,
        val seq: Int,
        val count: Int,
        val sz: Int,
        val total: Int,
        val sha: ByteArray,
        val signerFp: String,
        val recipientFp: String,
        val chunk: ByteArray,
        val crc: Long,
    )

    /** Parses one frame string. Returns null on any refusal, never throws. */
    fun parse(frame: String): FrameDescriptor? = runCatching {
        val f = parseFull(frame) ?: return null
        FrameDescriptor(f.parcelId, f.seq, f.count, f.total, f.crc)
    }.getOrNull()

    /**
     * Seals [payload] through `CourierEnvelope.seal`, encodes the envelope,
     * frames those bytes and returns the ordered frame strings.
     *
     * [recipientCert] is the recipient's certificate in hand; `seal` binds it
     * to [recipientFp] by its own SHA-256 DER, so a parcel cannot be addressed
     * to one fingerprint and encrypted to another. [identity] is the signer.
     * An empty list is returned when [payload] is oversized or `seal` refuses;
     * no frame is emitted on refusal.
     */
    fun encodeParcel(
        payload: ByteArray,
        parcelId: String,
        recipientFp: String,
        recipientCert: X509Certificate,
        signerFp: String,
        identity: TlsIdentity,
    ): List<String> = runCatching {
        if (payload.isEmpty() || payload.size > MAX_AIR_GAP_PAYLOAD_BYTES) return emptyList()
        if (parcelId.isBlank() || parcelId.length > MAX_FIELD_CHARS) return emptyList()
        val sender = signerFp.trim().uppercase()
        val target = recipientFp.trim().uppercase()
        if (sender.isBlank() || sender.length > MAX_FIELD_CHARS) return emptyList()
        if (target.isBlank() || target.length > MAX_FIELD_CHARS) return emptyList()

        val nowMs = System.currentTimeMillis()
        val event = CircleEvent(
            id = UUID.randomUUID().toString(),
            circleId = CIRCLE_ID,
            authorFp = sender,
            lamport = 0L,
            type = CircleEvent.MESSAGE,
            body = """{"text":"${b64.encodeToString(payload)}"}""",
            sig = "",
            createdMs = nowMs,
            cert = "",
        )
        if (!CircleCore.validShape(event)) return emptyList()

        val sealed = CourierEnvelope.seal(
            listOf(event), CIRCLE_ID, sender, target, recipientCert, identity, nowMs,
        ) ?: return emptyList()
        val envelope = CourierEnvelope.encode(sealed)
        if (envelope.length > MAX_ENVELOPE_CHARS) return emptyList()
        val envelopeBytes = envelope.toByteArray(Charsets.UTF_8)
        if (envelopeBytes.isEmpty() || envelopeBytes.size > MAX_AIR_GAP_PARCEL_BYTES) return emptyList()

        val count = (envelopeBytes.size + MAX_CHUNK_BYTES - 1) / MAX_CHUNK_BYTES
        if (count < 1 || count > MAX_FRAMES) return emptyList()

        val digest = parcelDigest(parcelId, sender, target, envelopeBytes)
        val frames = (0 until count).map { index ->
            val from = index * MAX_CHUNK_BYTES
            val chunk = envelopeBytes.copyOfRange(from, minOf(from + MAX_CHUNK_BYTES, envelopeBytes.size))
            frameOf(parcelId, index + 1, count, MAX_CHUNK_BYTES, envelopeBytes.size, digest, sender, target, chunk)
        }

        return frames
    }.getOrNull() ?: emptyList()

    /**
     * Verifies every frame (layout, CRC, sequence, count and whole-parcel
     * digest), checks the recipient, then opens the envelope through
     * `CourierEnvelope.open` with [identity]'s private key. Returns null unless
     * all checks pass and the opened parcel is addressed to [recipientFp].
     * The caller can run this on any device that holds the recipient identity.
     */
    fun complete(frames: List<String>, recipientFp: String, identity: TlsIdentity): ParcelPayload? = runCatching {
        if (frames.isEmpty()) return null
        val target = recipientFp.trim().uppercase()
        if (target.isBlank() || target.length > MAX_FIELD_CHARS) return null

        val parsed = frames.map { parseFull(it) ?: return null }
        val first = parsed.first()
        if (parsed.any {
                it.parcelId != first.parcelId || it.count != first.count || it.sz != first.sz ||
                    it.total != first.total || !it.sha.contentEquals(first.sha) ||
                    it.signerFp != first.signerFp || it.recipientFp != first.recipientFp
            }
        ) return null
        if (!first.recipientFp.equals(target, ignoreCase = true)) return null
        if (first.count < 1 || first.count > MAX_FRAMES) return null
        if (first.total < 1 || first.total > MAX_AIR_GAP_PARCEL_BYTES) return null
        if (first.sz < 1 || first.sz > MAX_CHUNK_BYTES) return null
        if (first.count != (first.total + first.sz - 1) / first.sz) return null
        if (first.sha.size != HASH_BYTES) return null

        val bySeq = LinkedHashMap<Int, FullFrame>()
        for (f in parsed) {
            val previous = bySeq[f.seq]
            if (previous != null && !previous.chunk.contentEquals(f.chunk)) return null
            bySeq[f.seq] = f
        }
        if (bySeq.size != first.count || (1..first.count).any { it !in bySeq }) return null
        if ((1 until first.count).any { bySeq[it]!!.chunk.size != first.sz }) return null

        val envelopeBytes = ByteArrayOutputStream().also { out ->
            (1..first.count).forEach { out.write(bySeq[it]!!.chunk) }
        }.toByteArray()
        if (envelopeBytes.size != first.total) return null
        if (!MessageDigest.isEqual(parcelDigest(first.parcelId, first.signerFp, first.recipientFp, envelopeBytes), first.sha)) {
            return null
        }

        val envelope = CourierEnvelope.decode(String(envelopeBytes, Charsets.UTF_8)) ?: return null
        if (!CourierEnvelope.verify(envelope)) return null
        if (!envelope.toFp.equals(first.recipientFp, ignoreCase = true)) return null
        if (!envelope.fromFp.equals(first.signerFp, ignoreCase = true)) return null

        val events = CourierEnvelope.open(envelope, identity, System.currentTimeMillis()) ?: return null
        if (events.size != 1) return null
        val body = WireJson.json.parseToJsonElement(events[0].body) as? JsonObject ?: return null
        val encoded = (body["text"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return null
        val bytes = runCatching { b64url.decode(encoded) }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_AIR_GAP_PAYLOAD_BYTES) return null

        return ParcelPayload(
            parcelId = first.parcelId,
            recipientFp = first.recipientFp,
            signerFp = first.signerFp,
            sha256 = sha256Hex(bytes),
            bytes = bytes,
        )
    }.getOrNull()

    /** Rebuilds canonical CRC bytes and compares with the stored value. */
    private fun parseFull(frame: String): FullFrame? {
        val text = frame.trim()
        if (text.isBlank() || text.length > MAX_FRAME_CHARS) return null
        val parts = text.split('.')
        if (parts.size != 12) return null
        if (parts[0] != FRAME_MAGIC) return null
        val version = canonicalInt(parts[1]) ?: return null
        if (version != VERSION) return null

        val parcelId = decodeText(parts[2]) ?: return null
        if (parcelId.isBlank() || parcelId.length > MAX_FIELD_CHARS) return null
        val seq = canonicalInt(parts[3]) ?: return null
        val count = canonicalInt(parts[4]) ?: return null
        val sz = canonicalInt(parts[5]) ?: return null
        val total = canonicalInt(parts[6]) ?: return null
        if (count < 1 || count > MAX_FRAMES) return null
        if (seq < 1 || seq > count) return null
        if (sz < 1 || sz > MAX_CHUNK_BYTES) return null
        if (total < 1 || total > MAX_AIR_GAP_PARCEL_BYTES) return null
        if (count != (total + sz - 1) / sz) return null

        val sha = decodeBytes(parts[7]) ?: return null
        if (sha.size != HASH_BYTES) return null
        val signerFp = decodeText(parts[8]) ?: return null
        val recipientFp = decodeText(parts[9]) ?: return null
        if (signerFp.isBlank() || signerFp.length > MAX_FIELD_CHARS) return null
        if (recipientFp.isBlank() || recipientFp.length > MAX_FIELD_CHARS) return null

        val chunk = decodeBytes(parts[10]) ?: return null
        val expected = minOf(sz, total - (seq - 1) * sz)
        if (chunk.size != expected) return null
        if (seq != count && chunk.size != sz) return null

        val crc = parseCrc(parts[11]) ?: return null
        val candidate = FullFrame(
            parcelId, seq, count, sz, total, sha, signerFp, recipientFp, chunk, crc,
        )
        if (crc(candidate) != crc) return null
        return candidate
    }

    /** Builds one frame string, including its CRC over canonical bytes. */
    private fun frameOf(
        parcelId: String,
        seq: Int,
        count: Int,
        sz: Int,
        total: Int,
        sha: ByteArray,
        signerFp: String,
        recipientFp: String,
        chunk: ByteArray,
    ): String {
        val f = FullFrame(parcelId, seq, count, sz, total, sha, signerFp, recipientFp, chunk, 0L)
        val crc = crc(f)
        return listOf(
            FRAME_MAGIC,
            VERSION.toString(),
            b64.encodeToString(parcelId.toByteArray(Charsets.UTF_8)),
            seq.toString(),
            count.toString(),
            sz.toString(),
            total.toString(),
            b64.encodeToString(sha),
            b64.encodeToString(signerFp.toByteArray(Charsets.UTF_8)),
            b64.encodeToString(recipientFp.toByteArray(Charsets.UTF_8)),
            b64.encodeToString(chunk),
            String.format("%08x", crc),
        ).joinToString(".")
    }

    /** Domain-tagged, length-prefixed CRC input; the CRC itself is excluded. */
    private fun canonical(f: FullFrame): ByteArray = ByteArrayOutputStream().also { bytes ->
        val out = DataOutputStream(bytes)
        field(out, FRAME_MAGIC.toByteArray(Charsets.UTF_8))
        out.writeInt(VERSION)
        field(out, f.parcelId.toByteArray(Charsets.UTF_8))
        out.writeInt(f.seq)
        out.writeInt(f.count)
        out.writeInt(f.sz)
        out.writeInt(f.total)
        field(out, f.sha)
        field(out, f.signerFp.toByteArray(Charsets.UTF_8))
        field(out, f.recipientFp.toByteArray(Charsets.UTF_8))
        field(out, f.chunk)
        out.flush()
    }.toByteArray()

    private fun crc(f: FullFrame): Long {
        val crc = CRC32()
        crc.update(canonical(f))
        return crc.value
    }

    /** Whole-parcel digest bound to the framed envelope bytes and routing metadata. */
    private fun parcelDigest(parcelId: String, signerFp: String, recipientFp: String, envelopeBytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(ByteArrayOutputStream().also { bytes ->
            val out = DataOutputStream(bytes)
            field(out, DIGEST_TAG.toByteArray(Charsets.UTF_8))
            field(out, parcelId.toByteArray(Charsets.UTF_8))
            field(out, signerFp.toByteArray(Charsets.UTF_8))
            field(out, recipientFp.toByteArray(Charsets.UTF_8))
            field(out, envelopeBytes)
            out.flush()
        }.toByteArray())

    private fun field(out: DataOutputStream, value: ByteArray) {
        out.writeInt(value.size)
        out.write(value)
    }

    /** Canonical decimal only: ASCII digits, no sign, no leading zero, no non-ASCII digit. */
    private fun canonicalInt(part: String): Int? {
        if (part.isEmpty() || part.any { it !in '0'..'9' }) return null
        if (part.length > 1 && part[0] == '0') return null
        return part.toIntOrNull()
    }

    private fun decodeText(part: String): String? = decodeBytes(part)?.let {
        String(it, Charsets.UTF_8)
    }

    /** Rejects padding and any non-canonical re-encoding of the same bytes. */
    private fun decodeBytes(part: String): ByteArray? {
        if (part.isEmpty() || '=' in part) return null
        return runCatching {
            val decoded = b64url.decode(part)
            if (b64.encodeToString(decoded) != part) null else decoded
        }.getOrNull()
    }

    private fun parseCrc(part: String): Long? {
        if (part.length != 8 || part.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return part.toLong(16)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
