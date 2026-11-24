package app.flit.share

import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.EncodeHintType
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Flit's pairing code.
 *
 * A device shows a QR of this URI on its "My code" screen; the other phone
 * scans it, `parseAddCode` reads the payload, and `addFromScan` gets a code
 * that is guaranteed typed correctly. The fingerprint rides along
 * out-of-band: the scanner marks the peer's code `scanned` (a "✓ code"
 * badge in the peer card) and pins it — the receiver trust gate (Options
 * "only trusted") is satisfied by human-eyeball verification.
 *
 * v1 carries only the address. v2 adds optional carrier hints — a Bluetooth
 * [mac], a [relay] ("host:port") and its [token] — and can be relay-only: a
 * code with no local address still tells the scanner how to reach its peer.
 * [v] is kept on the wire so a v1 code re-encodes byte-for-byte unchanged.
 *
 * The scheme is deliberately ours (`flit://`): LocalSend peers never emit
 * it and Flit never emits anything resembling theirs.
 */
data class QrAdd(
    val ip: String = "",
    val port: Int = 0,
    val tls: Boolean = true,
    val fingerprint: String = "",
    val alias: String = "",
    /** Bluetooth carrier address, v2 only. */
    val mac: String? = null,
    /** Relay "host:port", v2 only; may be the code's only route. */
    val relay: String? = null,
    /** Optional relay credential, v2 only. */
    val token: String? = null,
    val v: Int = 2,
) {
    fun uri(): String = buildString {
        append("flit://add?v=").append(v)
        if (ip.isNotBlank()) {
            append("&ip=").append(URLEncoder.encode(ip, Charsets.UTF_8.name()))
            append("&port=").append(port)
        } else if (port > 0) {
            // Relay-only codes have no ip; keep an explicitly given port.
            append("&port=").append(port)
        }
        // v1 wrote tls only when true — keep that literal shape for deployed
        // codes. v2 writes it always so tls=false survives a roundtrip.
        if (v >= 2) append("&tls=").append(if (tls) '1' else '0')
        else if (tls) append("&tls=1")
        if (fingerprint.isNotBlank()) append("&fp=").append(fingerprint.uppercase())
        if (alias.isNotBlank()) append("&alias=").append(URLEncoder.encode(alias, Charsets.UTF_8.name()))
        if (!mac.isNullOrBlank()) append("&mac=").append(URLEncoder.encode(mac, Charsets.UTF_8.name()))
        if (!relay.isNullOrBlank()) append("&relay=").append(URLEncoder.encode(relay, Charsets.UTF_8.name()))
        if (!token.isNullOrBlank()) append("&token=").append(URLEncoder.encode(token, Charsets.UTF_8.name()))
    }
}

/**
 * Tolerant parser for what lands in the scanner / paste field. Accepts
 * `flit://add?...` codes of any parameter order, v1 and v2 alike. Anything
 * else is not a pairing: `null` (plain `ip:port` strings belong to the Add
 * dialog's address path, which is pre-existing behavior).
 *
 * v1 must carry ip + a valid port, exactly as before. v2 may drop both when
 * it carries a well-formed relay; a port is only range-checked when an ip
 * is present. v1 codes ignore the v2-only hints.
 */
fun parseAddCode(text: String): QrAdd? {
    val t = text.trim()
    if (t.isBlank()) return null
    val lowered = t.lowercase()
    if (!lowered.startsWith("flit://add") && !lowered.startsWith("flit://pair")) return null
    val query = t.substringAfter('?').takeIf { it.isNotBlank() } ?: return null
    val params = LinkedHashMap<String, String>()
    for (pair in query.split('&')) {
        if (pair.isEmpty()) continue
        val k = pair.substringBefore('=').trim().lowercase()
        val v = runCatching {
            URLDecoder.decode(pair.substringAfter('=', ""), Charsets.UTF_8.name())
        }.getOrDefault("")
        if (k.isNotEmpty()) params[k] = v
    }
    val version = (params["v"] ?: "1").toIntOrNull() ?: return null
    if (version !in 1..2) return null
    val ip = params["ip"]?.trim().orEmpty()
    val port = (params["port"] ?: params["p"])?.toIntOrNull() ?: 0
    if (version == 1 && (ip.isEmpty() || port !in 1..65535)) return null
    val relay = if (version == 2) params["relay"]?.trim()?.ifBlank { null } else null
    if (relay != null && FlitRoute.parseRelay(relay) == null) return null
    if (version == 2 && ip.isEmpty() && relay == null) return null
    if (version == 2 && ip.isNotEmpty() && port !in 1..65535) return null
    return QrAdd(
        ip = ip,
        port = port,
        tls = (params["tls"] ?: "1") != "0",
        fingerprint = (params["fp"] ?: params["f"] ?: "").trim().uppercase().ifBlank { "" },
        alias = (params["alias"] ?: params["a"] ?: "").trim(),
        mac = if (version == 2) params["mac"]?.trim()?.ifBlank { null } else null,
        relay = relay,
        token = if (version == 2) params["token"]?.trim()?.ifBlank { null } else null,
        v = version,
    )
}

/** QR rendering + decoding for pairing codes. Pure JVM (tests run on it). */
object Qr {

    /** Dense-but-scannable: EC level M, thin quiet zone. */
    fun encode(text: String, size: Int): BitMatrix {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 2,
        )
        return QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints)
    }

    /**
     * BitMatrix → luminance bytes (QR black = 0, white = 255), square.
     * The same gray layout the decoders below and the camera path (sampled
     * Y plane) consume.
     */
    fun gray(m: BitMatrix): ByteArray {
        val w = m.width
        val out = ByteArray(w * w)
        for (y in 0 until w) for (x in 0 until w) {
            out[y * w + x] = if (m.get(x, y)) 0 else 0xFF.toByte()
        }
        return out
    }

    /** One grayscale image, clockwise 90° steps. Returns (gray, w, h). */
    fun rotateGray(src: ByteArray, w: Int, h: Int, times: Int): Triple<ByteArray, Int, Int> {
        var cur = src
        var cw = w
        var ch = h
        repeat(times.coerceIn(0, 3)) {
            val out = ByteArray(cw * ch)
            for (y in 0 until ch) for (x in 0 until cw) {
                out[x * ch + (ch - 1 - y)] = cur[y * cw + x]
            }
            cur = out
            val t = cw; cw = ch; ch = t
        }
        return Triple(cur, cw, ch)
    }

    /**
     * The only format the camera produces that matters: one Y (luminance)
     * plane with independent row/pixel strides. Stride-aware sampling into
     * a compact gray block, then rotated, then decoded.
     */
    fun decodeYPlane(
        y: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        rotationTimes: Int,
    ): String? {
        require(width > 0 && height > 0 && pixelStride >= 1) { "bad plane geometry" }
        // A plane whose row stencil reaches into the next row cannot exist on
        // real sensors — reject impossible geometry loudly instead of decoding
        // cross-treaded noise (which is how this slipped past a test).
        require(rowStride >= (width - 1) * pixelStride + 1) {
            "impossible plane: rowStride $rowStride < ${(width - 1) * pixelStride + 1}"
        }
        // The sampler touches row*rowStride + col*pixelStride for every row —
        // callers under-provision that tail get caught here, not with an OOB.
        require(
            y.size >= rowStride * (height - 1) + (width - 1) * pixelStride + 1,
        ) { "plane buffer too short (${y.size} < ${rowStride * (height - 1) + (width - 1) * pixelStride + 1})" }
        val compact = ByteArray(width * height)
        var idx = 0
        for (row in 0 until height) {
            for (col in 0 until width) {
                compact[idx++] = y[row * rowStride + col * pixelStride]
            }
        }
        val (g, w, h) = rotateGray(compact, width, height, rotationTimes)
        return decodeGray(g, w, h)
    }

    /** Decode a compact gray block (desktop tests, honor rotation elsewhere). */
    fun decodeGray(gray: ByteArray, width: Int, height: Int): String? {
        val bmp = BinaryBitmap(HybridBinarizer(GraySource(gray, width, height)))
        val reader = MultiFormatReader()
        return try {
            reader.decode(bmp).text
        } catch (_: NotFoundException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    /** Colors-aware decode for screenshots / images (RGB ints, ARGB or RGB same). */
    fun decodeRgb(pixels: IntArray, width: Int, height: Int): String? {
        val bmp = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels)))
        val reader = MultiFormatReader()
        return try {
            reader.decode(bmp).text
        } catch (_: NotFoundException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Luminance view over a compact gray block. Own implementation rather than
 * zxing's planar source because CameraX plane strides vary and the desktop
 * render path needs a stride-free one.
 */
internal class GraySource(private val gray: ByteArray, width: Int, height: Int) : LuminanceSource(width, height) {
    override fun getRow(y: Int, row: ByteArray?): ByteArray {
        val out = row ?: ByteArray(width)
        System.arraycopy(gray, y * width, out, 0, width)
        return out
    }

    override fun getMatrix(): ByteArray = gray

    override fun isCropSupported(): Boolean = false
    override fun rotateCounterClockwise(): LuminanceSource =
        GraySource(Qr.rotateGray(gray, width, height, 1).first, height, width)
}
