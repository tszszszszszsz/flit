package app.flit.share

import app.flit.share.protocol.parcel.ParcelCodec
import app.flit.share.protocol.tls.TlsIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PURE app-side intake tests for [ParcelScanState]. No Android, Compose,
 * camera, socket, file or Gradle dependency: every fixture is an opaque frame
 * `String` produced by the real [ParcelCodec] from a real receiving
 * [TlsIdentity]. The state's own CRC/digest/signature internals are never
 * touched here — those cases belong to the 2026-12-03 protocol suite.
 *
 * Contract exercised below (the API `ParcelScanState` exposes):
 *  - `ParcelScanState(recipientFp, identity, maxFrames = ParcelCodec.MAX_FRAMES)`
 *    with `maxFrames` clamped into `1..ParcelCodec.MAX_FRAMES`
 *  - `frames`: accepted opaque strings, in first-accepted order
 *  - `parcelId`: first accepted parcel id, null before the first frame
 *  - `count`: agreed count from the first frame, null before the first frame
 *  - `progress`: "frame i of n" with n = count, or "?" before the first frame
 *  - `add(frame): Boolean` — true only when the frame is newly accepted; a
 *    consumed scan accepts nothing
 *  - `complete(): ParcelCodec.ParcelPayload?` — once only, on full coverage
 *    and real codec verification; a failed attempt keeps the frames
 *  - `clear()` — drops every frame and metadata, including the consumed flag
 */
class ParcelScanStateTest {

    private val sender = TlsIdentity.generate()
    private val receiver = TlsIdentity.generate()
    private val stranger = TlsIdentity.generate()

    private fun payload(size: Int): ByteArray = ByteArray(size) { (it * 31 % 251).toByte() }

    /** Frames addressed to [recipient], signed by the test sender. */
    private fun framesFor(
        payload: ByteArray,
        parcelId: String,
        recipient: TlsIdentity = receiver,
    ): List<String> = ParcelCodec.encodeParcel(
        payload,
        parcelId,
        recipient.fingerprint,
        recipient.certificate,
        sender.fingerprint,
        sender,
    )

    private fun state(
        recipient: TlsIdentity = receiver,
        maxFrames: Int = ParcelCodec.MAX_FRAMES,
    ): ParcelScanState = ParcelScanState(recipient.fingerprint, recipient, maxFrames)

    @Test
    fun `a fresh scan holds no frames and reports an unknown total`() {
        val s = state()
        assertTrue(s.frames.isEmpty())
        assertNull(s.parcelId)
        assertNull(s.count)
        assertTrue(s.progress.contains("?"), "before the first frame the total is unknown")
        assertNull(s.complete(), "an empty scan cannot complete")
    }

    @Test
    fun `in order frames complete to the original payload`() {
        val bytes = payload(1400)
        val frames = framesFor(bytes, "parcel-in-order")
        assertTrue(frames.size > 1, "fixture must need several frames")
        val s = state()

        frames.forEach { assertTrue(s.add(it), "each new frame is accepted") }

        assertEquals(frames.toSet(), s.frames.toSet())
        assertEquals("parcel-in-order", s.parcelId)
        assertEquals(frames.size, s.count)
        val opened = checkNotNull(s.complete())
        assertTrue(bytes.contentEquals(opened.bytes))
        assertEquals("parcel-in-order", opened.parcelId)
        assertEquals(receiver.fingerprint, opened.recipientFp)
        assertEquals(sender.fingerprint, opened.signerFp)
    }

    @Test
    fun `out of order frames complete to the original payload`() {
        val bytes = payload(1600)
        val frames = framesFor(bytes, "parcel-shuffled")
        assertTrue(frames.size > 2, "fixture must need several frames")
        val s = state()
        val shuffled = frames.indices.shuffled().map { frames[it] }

        shuffled.forEach { assertTrue(s.add(it)) }

        assertEquals(frames.toSet(), s.frames.toSet())
        assertEquals(frames.size, s.count)
        val opened = checkNotNull(s.complete())
        assertTrue(bytes.contentEquals(opened.bytes))
    }

    @Test
    fun `a duplicate seq keeps the first copy and still completes`() {
        val bytes = payload(1200)
        val frames = framesFor(bytes, "parcel-duplicate")
        val s = state()

        frames.forEach { assertTrue(s.add(it)) }
        assertFalse(s.add(frames.first()), "the repeat is ignored")
        assertFalse(s.add(frames.last()), "the repeat is ignored again")

        assertEquals(frames.toSet(), s.frames.toSet(), "no extra copy is stored")
        assertEquals(frames.size, s.frames.size)
        assertTrue(checkNotNull(s.complete()).bytes.contentEquals(bytes))
    }

    @Test
    fun `a missing frame leaves the parcel incomplete until it arrives`() {
        val bytes = payload(1600)
        val frames = framesFor(bytes, "parcel-missing")
        assertTrue(frames.size > 2)
        val s = state()

        frames.dropLast(1).forEach { assertTrue(s.add(it)) }
        assertEquals(frames.dropLast(1).toSet(), s.frames.toSet())
        assertNull(s.complete(), "an incomplete sequence never completes")

        assertTrue(s.add(frames.last()), "the missing frame is still accepted after a failed attempt")
        assertEquals(frames.toSet(), s.frames.toSet())
        assertTrue(checkNotNull(s.complete()).bytes.contentEquals(bytes))
    }

    @Test
    fun `a foreign parcel id is ignored while another parcel is in progress`() {
        val bytes = payload(1400)
        val mine = framesFor(bytes, "parcel-mine")
        val foreign = framesFor(payload(900), "parcel-foreign")
        val s = state()

        assertTrue(s.add(mine.first()))
        foreign.forEach { assertFalse(s.add(it), "frames of another parcel are refused") }

        assertEquals(setOf(mine.first()), s.frames.toSet())
        assertEquals("parcel-mine", s.parcelId, "the first id wins and is not replaced")
        assertEquals(mine.size, s.count)
        assertNull(s.complete(), "foreign frames do not fill the gap")

        mine.drop(1).forEach { assertTrue(s.add(it)) }
        assertTrue(checkNotNull(s.complete()).bytes.contentEquals(bytes))
    }

    @Test
    fun `a frame with a different count for the same parcel id is ignored`() {
        // encodeParcel takes parcelId independently of payload size, so the
        // same id can legitimately be encoded with two different counts. The
        // state must bind itself to the count of the first accepted frame.
        val small = payload(80)
        val big = payload(1600)
        val fewFrames = framesFor(small, "parcel-same-id")
        val manyFrames = framesFor(big, "parcel-same-id")
        assertTrue(fewFrames.isNotEmpty() && manyFrames.size > fewFrames.size)
        val s = state()

        fewFrames.forEach { assertTrue(s.add(it)) }
        val disagreeing = manyFrames.first {
            checkNotNull(ParcelCodec.parse(it)).seq == fewFrames.size + 1
        }
        assertFalse(s.add(disagreeing), "same id but a different count is refused")

        assertEquals(fewFrames.toSet(), s.frames.toSet())
        assertEquals(fewFrames.size, s.count, "the first frame's count wins")
        assertTrue(checkNotNull(s.complete()).bytes.contentEquals(small))
    }

    @Test
    fun `frames beyond the cap are ignored and progress stays at the cap`() {
        // The app sets its cap to the codec's MAX_FRAMES; inject 1 here so the
        // cap path is observable with codec-produced frames (encodeParcel
        // itself refuses any payload needing more than 64 frames).
        val bytes = payload(1400)
        val frames = framesFor(bytes, "parcel-over-cap")
        assertTrue(frames.size > 1)
        val s = state(maxFrames = 1)

        assertTrue(s.add(frames[0]))
        assertFalse(s.add(frames[1]), "a frame past the app cap is ignored")
        assertFalse(s.add(frames.last()))

        assertEquals(setOf(frames.first()), s.frames.toSet())
        assertEquals(1, s.frames.size)
        assertEquals(frames.size, s.count, "the declared count is unchanged")
        assertNull(s.complete(), "an over-cap parcel cannot complete")
    }

    @Test
    fun `an undecodable frame is ignored and does not disturb progress`() {
        val bytes = payload(1200)
        val frames = framesFor(bytes, "parcel-junk")
        val s = state()

        frames.forEach { assertTrue(s.add(it)) }
        val before = s.progress
        assertFalse(s.add("not-a-parcel-frame"))
        assertFalse(s.add(""))
        assertEquals(before, s.progress, "junk leaves progress untouched")
        assertEquals(frames.toSet(), s.frames.toSet())
        assertTrue(checkNotNull(s.complete()).bytes.contentEquals(bytes))
    }

    @Test
    fun `progress counts accepted frames against the declared total`() {
        val bytes = payload(1600)
        val frames = framesFor(bytes, "parcel-progress")
        val count = frames.size
        assertTrue(count > 2)
        val s = state()

        assertTrue(s.progress.contains("?"), "unknown total before the first frame")
        frames.forEachIndexed { index, frame ->
            assertTrue(s.add(frame))
            val accepted = index + 1
            assertEquals(count, s.count)
            assertEquals(accepted, s.frames.size)
            assertTrue(
                s.progress.contains("$accepted of $count"),
                "progress must read frame $accepted of $count, got '${s.progress}'",
            )
        }
        assertTrue(s.progress.contains("$count of $count"))
    }

    @Test
    fun `a parcel addressed to another recipient never completes here`() {
        val bytes = payload(1000)
        val frames = framesFor(bytes, "parcel-wrong-recipient", recipient = stranger)
        val s = state(recipient = receiver)

        frames.forEach { assertTrue(s.add(it), "well-formed frames are stored even for another recipient") }
        assertEquals(frames.toSet(), s.frames.toSet())
        assertNull(s.complete(), "the codec recipient gate must refuse it")
    }

    @Test
    fun `a successful complete is handed out once and a second call returns null`() {
        val bytes = payload(600)
        val frames = framesFor(bytes, "parcel-once")
        val s = state()
        frames.forEach { assertTrue(s.add(it)) }

        val first = checkNotNull(s.complete())
        assertTrue(bytes.contentEquals(first.bytes))
        assertNull(s.complete(), "a verified payload is delivered exactly once")
        assertNull(s.complete(), "and stays consumed")
    }

    @Test
    fun `clear resets every field and reopens a consumed scan`() {
        val bytes = payload(600)
        val frames = framesFor(bytes, "parcel-before-clear")
        val s = state()
        frames.forEach { assertTrue(s.add(it)) }
        assertNotNull(s.complete())
        assertNull(s.complete(), "the scan is consumed before clear")

        s.clear()

        assertTrue(s.frames.isEmpty())
        assertNull(s.parcelId)
        assertNull(s.count)
        assertTrue(s.progress.contains("?"))
        assertNull(s.complete(), "cleared means empty, not re-delivered")

        val nextBytes = payload(700)
        val next = framesFor(nextBytes, "parcel-after-clear")
        next.forEach { assertTrue(s.add(it), "a cleared scan accepts a new parcel") }
        assertEquals("parcel-after-clear", s.parcelId)
        assertTrue(checkNotNull(s.complete()).bytes.contentEquals(nextBytes))
    }

    @Test
    fun `add is refused after a successful complete`() {
        val bytes = payload(600)
        val frames = framesFor(bytes, "parcel-sealed")
        val s = state()
        frames.forEach { assertTrue(s.add(it)) }
        assertNotNull(s.complete())

        assertFalse(s.add(frames.first()), "a repeat of an old frame is refused")
        val other = framesFor(payload(700), "parcel-next")
        assertFalse(s.add(other.first()), "a well-formed new parcel is refused too")
        assertEquals(frames.toSet(), s.frames.toSet(), "the consumed frame set is unchanged")
    }

    @Test
    fun `a parseable tampered frame fails complete and keeps frames for retry`() {
        // Two parcels share id and count but carry different digest bytes:
        // every frame parses, yet the mixed set cannot verify as a whole.
        val goodBytes = ByteArray(1400) { 0 }
        val otherBytes = ByteArray(1400) { 1 }
        val good = framesFor(goodBytes, "parcel-tampered")
        val other = framesFor(otherBytes, "parcel-tampered")
        assertEquals(good.size, other.size, "same size means the same chunk count")
        assertTrue(good.size > 1)
        val s = state()

        good.dropLast(1).forEach { assertTrue(s.add(it)) }
        val tampered = other.last()
        assertNotNull(ParcelCodec.parse(tampered), "the swapped frame parses on its own")
        assertTrue(s.add(tampered), "a well-formed frame at a new seq is stored")
        assertEquals(good.dropLast(1).size + 1, s.frames.size)

        assertNull(s.complete(), "a frame outside the whole-parcel digest fails verification")
        assertEquals(good.dropLast(1).toSet() + tampered, s.frames.toSet(), "frames survive a failed complete")
        assertEquals(good.size, s.count)
        assertNull(s.complete(), "a second failed attempt also keeps the frames")

        s.clear()
        good.forEach { assertTrue(s.add(it)) }
        assertTrue(checkNotNull(s.complete()).bytes.contentEquals(goodBytes))
    }

    @Test
    fun `maxFrames clamps zero negative and values above the codec cap`() {
        val bytes = payload(1400)
        val frames = framesFor(bytes, "parcel-clamp")
        assertTrue(frames.size > 1)

        for (bad in listOf(0, -1, Int.MIN_VALUE)) {
            val s = ParcelScanState(receiver.fingerprint, receiver, bad)
            assertTrue(s.add(frames[0]), "a $bad cap clamps up to at least one frame")
            assertFalse(s.add(frames[1]), "a $bad cap must not allow a second frame")
            assertEquals(1, s.frames.size)
            assertNull(s.complete())
        }

        val over = ParcelScanState(receiver.fingerprint, receiver, ParcelCodec.MAX_FRAMES + 100)
        frames.forEach { assertTrue(over.add(it), "a cap above MAX must not reject valid frames") }
        assertTrue(checkNotNull(over.complete()).bytes.contentEquals(bytes))
    }

    @Test
    fun `first copy wins when a duplicate seq carries different bytes`() {
        val firstBytes = ByteArray(1400) { 0 }
        val secondBytes = ByteArray(1400) { 1 }
        val first = framesFor(firstBytes, "parcel-first-copy")
        val second = framesFor(secondBytes, "parcel-first-copy")
        assertEquals(first.size, second.size)
        val s = state()

        first.forEach { assertTrue(s.add(it)) }
        second.forEach { assertFalse(s.add(it), "same (parcelId, seq), different bytes: first wins") }

        assertEquals(first.toSet(), s.frames.toSet())
        assertEquals(first.size, s.frames.size)
        assertTrue(checkNotNull(s.complete()).bytes.contentEquals(firstBytes))
    }

    @Test
    fun `a well formed foreign first frame is rejected or recoverable via clear`() {
        val foreign = framesFor(payload(1000), "parcel-foreign-first")
        val mine = framesFor(payload(800), "parcel-mine-first")
        val s = state()
        val accepted = s.add(foreign.first())

        if (!accepted) {
            assertTrue(s.frames.isEmpty(), "a rejected first frame binds nothing")
            assertTrue(s.add(mine.first()), "the scan is still usable")
            assertEquals("parcel-mine-first", s.parcelId)
        } else {
            assertEquals("parcel-foreign-first", s.parcelId)
            s.clear()
            assertTrue(s.frames.isEmpty())
            assertNull(s.parcelId)
            assertTrue(s.add(mine.first()))
            assertEquals("parcel-mine-first", s.parcelId)
        }
    }
}
