package app.flit.share

import app.flit.share.protocol.parcel.ParcelCodec
import app.flit.share.protocol.tls.TlsIdentity

/**
 * Android-free intake state for one QR parcel scan.
 *
 * Frames arrive as opaque strings from the camera analyzer or the paste field.
 * Each is parsed with [ParcelCodec.parse] only to read `parcelId`, `seq` and
 * `count`; the original string is stored verbatim and is never rewritten. The
 * state binds itself to the first accepted frame's parcel id and count, keeps
 * at most one copy per `(parcelId, seq)` pair, and folds the kept strings into
 * [ParcelCodec.complete] once every sequence in `1..count` is covered.
 *
 * This is the frozen app-side seam: plain Kotlin, no Android class, no camera,
 * no file, no persistence and no second codec. CRC, digest, envelope signature
 * and the recipient gate all stay inside [ParcelCodec]. A refusal leaves the
 * accepted frames intact so a later frame can still complete the parcel; a
 * verified payload is handed out once and the state stays consumed.
 *
 * @param recipientFp the receiving app's fingerprint, passed unchanged to
 *   [ParcelCodec.complete] as the recipient gate.
 * @param identity the receiving app's [TlsIdentity]; supplies the private key
 *   the codec needs to open the envelope.
 * @param maxFrames app-side assembly cap, defaulting to the codec's cap. A
 *   value outside `1..ParcelCodec.MAX_FRAMES` is clamped, so the app can never
 *   hold more frames than the codec accepts.
 */
class ParcelScanState(
    private val recipientFp: String,
    private val identity: TlsIdentity,
    maxFrames: Int = ParcelCodec.MAX_FRAMES,
) {
    private val cap: Int = maxFrames.coerceIn(1, ParcelCodec.MAX_FRAMES)
    private val seen = LinkedHashSet<Pair<String, Int>>()
    private val kept = ArrayList<String>()
    private var consumed = false

    /** Accepted opaque frame strings, in first-accepted order. */
    val frames: List<String> get() = kept.toList()

    /** First accepted parcel id, or null before the first frame. */
    var parcelId: String? = null
        private set

    /** Count the first accepted frame declared, or null before that frame. */
    var count: Int? = null
        private set

    /** Numeric view of the same scan state used by the Compose screen. */
    data class Progress(val received: Int, val count: Int?)

    val progressInfo: Progress get() = Progress(kept.size, count)

    /** `frame i of n`, with `n` unknown (`?`) until the first frame is kept. */
    val progress: String get() = "frame ${kept.size} of ${count ?: "?"}"

    /** True after a verified payload has been handed out. */
    val isComplete: Boolean get() = consumed

    /**
     * Offers one decoded string to this scan. Returns true only when the string
     * is newly accepted: junk, a repeat, a foreign id, a disagreeing count, an
     * over-cap frame and anything offered after completion are all ignored.
     */
    fun add(frame: String): Boolean {
        if (consumed) return false
        val descriptor = ParcelCodec.parse(frame) ?: return false
        if (descriptor.count < 1 || descriptor.count > ParcelCodec.MAX_FRAMES) return false
        if (descriptor.seq < 1 || descriptor.seq > descriptor.count) return false

        val currentId = parcelId
        val currentCount = count
        if (currentId == null) {
            parcelId = descriptor.parcelId
            count = descriptor.count
        } else if (descriptor.parcelId != currentId || descriptor.count != currentCount) {
            return false
        }

        val key = descriptor.parcelId to descriptor.seq
        if (!seen.add(key)) return false
        if (kept.size >= cap) {
            seen.remove(key)
            return false
        }
        kept.add(frame)
        return true
    }

    /**
     * Completes the parcel. Returns a payload only when accepted frames cover
     * `1..count` exactly once and [ParcelCodec.complete] verifies CRC, digest,
     * envelope signature and recipient. A refusal keeps the frames for another
     * attempt; success consumes the state so a second call returns null.
     */
    fun complete(): ParcelCodec.ParcelPayload? {
        if (consumed) return null
        val agreed = count ?: return null
        val id = parcelId ?: return null
        if (agreed < 1 || agreed > cap) return null
        if (kept.size != agreed) return null
        if ((1..agreed).any { (id to it) !in seen }) return null

        val payload = ParcelCodec.complete(kept, recipientFp, identity) ?: return null
        consumed = true
        return payload
    }

    /** Drops every in-memory frame, as when the scan dialog closes. */
    fun clear() {
        seen.clear()
        kept.clear()
        parcelId = null
        count = null
        consumed = false
    }
}
