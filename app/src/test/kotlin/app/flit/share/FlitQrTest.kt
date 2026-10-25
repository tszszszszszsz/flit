package app.flit.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pairing codes: parse strictness, wire roundtrips, and — the important
 * part — that pixels rendered/sampled in every path we ship decode back to
 * the exact URI (canvas-gray, rotated camera Y-plane, RGB screenshots).
 */
class FlitQrTest {

    private val sample = QrAdd(
        ip = "192.168.100.80",
        port = 53317,
        tls = true,
        fingerprint = "B68ABD124F4062D9BD3459F981F7CF5514BC75372441798FA32B9A7364085E4A",
        alias = "Flit · M2101K7BNY",
    )

    @Test
    fun `uri roundtrip keeps every field`() {
        val parsed = parseAddCode(sample.uri())
        assertEquals(sample.copy(alias = "Flit · M2101K7BNY"), parsed)
    }

    @Test
    fun `parameter order does not matter and dupes resolve last-wins`() {
        val shuffled = "flit://add?alias=Zed&v=1&port=47317&ip=10.0.0.7&tls=1"
        val p = parseAddCode(shuffled)
        assertEquals("10.0.0.7", p?.ip)
        assertEquals(47317, p?.port)
        assertEquals("Zed", p?.alias)
        assertTrue(p?.fingerprint?.isEmpty() ?: false) // fp-less codes still add, just unforged trust
    }

    @Test
    fun `junk is refused - the paste field never receives misfloated trust`() {
        assertNull(parseAddCode(""))
        assertNull(parseAddCode("192.168.1.42:53317"))      // address path, not a code
        assertNull(parseAddCode("flit://add"))               // no query
        assertNull(parseAddCode("flit://add?v=2&ip=1.2.3.4&port=1")) // unknown version
        assertNull(parseAddCode("flit://add?ip=&port=53317"))  // empty ip
        assertNull(parseAddCode("flit://add?ip=x&port=0"))     // port out of range
        assertNull(parseAddCode("flit://add?ip=x&port=99999"))
        assertNull(parseAddCode("https://localsend/..."))    // other schemes
    }

    /**
     * Wire-format lock: the literal string decoded off a deployed phone's
     * on-screen QR (Redmi Note 10S, "My code", capture → harness). Any change
     * to field names, ordering, or encoding that makes real-world peers'
     * screens unscannable turns this red.
     */
    @Test
    fun `on-screen deployed code parses field-for-field`() {
        val fromScreen = "flit://add?v=1&ip=192.168.100.80&port=53317&tls=1" +
            "&fp=8463ED27F287D6C3D10CEBC60A7EFEFF0FF971B9C2F6C45371F975256F4DAE58" +
            "&alias=Flit+%C2%B7+M2101K7BNY"
        val p = parseAddCode(fromScreen) ?: error("deployed screen code must parse")
        assertEquals("192.168.100.80", p.ip)
        assertEquals(53317, p.port)
        assertEquals(true, p.tls)
        assertEquals(64, p.fingerprint.length)
        assertTrue(p.fingerprint.all { it in "0123456789ABCDEF" }, "fp must be hex")
        assertEquals("8463ED27F287", p.fingerprint.take(12))  // the 12 shown in UI
        assertEquals("Flit · M2101K7BNY", p.alias)
        assertEquals(fromScreen, p.uri()) // and it re-encodes byte-for-byte
    }

    @Test
    fun `tls=0 is honored`() {
        val zeroed = "flit://add?v=1&ip=192.168.1.42&port=53317&tls=0&fp=ABC"
        assertEquals(false, parseAddCode(zeroed)?.tls)
    }

    @Test
    fun `unicode alias survives url encoding`() {
        val weird = QrAdd(ip = "fe80::1%wlan0".let { "10.1.2.3" }, port = 53317, fingerprint = "", alias = "Zoë's π ← ★")
        val p = parseAddCode(weird.uri())
        assertEquals("Zoë's π ← ★", p?.alias)
    }

    @Test
    fun `rendered code decodes from the canvas-gray path`() {
        val uri = sample.uri()
        val decoded = Qr.decodeGray(Qr.gray(Qr.encode(uri, 160)), 160, 160)
        assertEquals(uri, decoded)
    }

    @Test
    fun `rendered code decodes after camera-style rotation`() {
        val uri = sample.uri()
        val m = Qr.encode(uri, 160)
        val g = Qr.gray(m)
        for (rot in 1..3) {
            val (g2, w, h) = Qr.rotateGray(g, 160, 160, rot)
            assertEquals(uri, Qr.decodeGray(g2, w, h), "rotation $rot must not tilt the finders")
        }
    }

    @Test
    fun `rendered code decodes from rgb pixels (screenshot path)`() {
        val uri = sample.uri()
        val m = Qr.encode(uri, 160)
        val px = IntArray(160 * 160) { i -> if (m.get(i % 160, i / 160)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        assertEquals(uri, Qr.decodeRgb(px, 160, 160))
    }

    @Test
    fun `camera y-plane with strides decodes like the desktop path`() {
        // Simulate a CameraX Y plane: rowStride > width, pixelStride 2,
        // 90° sensor rotation. Decode must survive all of that.
        val uri = sample.uri()
        val frameSize = 280
        val m = Qr.encode(uri, 200)
        require(m.width <= frameSize && m.height <= frameSize)
        // center the code inside the frame: luminance 255 elsewhere
        val w = frameSize
        val h = frameSize
        val frame = ByteArray(w * h) { 0xFF.toByte() }
        val off = (w - m.width) / 2 to (h - m.height) / 2
        for (r in 0 until m.height) for (c in 0 until m.width) {
            frame[(off.second + r) * w + (off.first + c)] = if (m.get(c, r)) 0 else 0xFF.toByte()
        }
        val pixelStride = 2 // interleaved plane, like some drivers
        val rows = w * pixelStride + 16 // padded rows, like a real driver (stencil must not reach into the next row)
        // Exactly the capacity the camera path computes: tail row keeps room
        // for the pixel-stride fence.
        val plane = ByteArray(rows * (h - 1) + (w - 1) * pixelStride + 1)
        for (r in 0 until h) for (c in 0 until w) {
            val src = frame[r * w + c]
            plane[r * rows + c * pixelStride] = src
        }
        val decoded = Qr.decodeYPlane(
            y = plane,
            width = frameSize,
            height = frameSize,
            rowStride = rows,
            pixelStride = pixelStride,
            rotationTimes = 1,
        )
        assertEquals(uri, decoded, "stride+rotation resilience is the scanner contract")
    }

    @Test
    fun `a skewed frame that is not a code stays calm`() {
        val noise = ByteArray(100 * 100) { (((it * 31) % 7) * 36).toByte() }
        assertNull(Qr.decodeGray(noise, 100, 100))
        assertNull(Qr.decodeRgb(IntArray(100 * 100) { it * 7919 }, 100, 100))
    }
}
