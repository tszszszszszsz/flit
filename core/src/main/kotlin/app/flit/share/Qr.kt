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
 * v3 adds a circle invite: [circle] (the circle id) and [invite] (the invite
 * token), alongside the host fingerprint and v2 route hints already present.
 * [v] is kept on the wire so a v1 code re-encodes byte-for-byte unchanged.
 *
 * A QR invite carries NO group key — only who to ask (host fingerprint),
 * which circle, the invite token and how to reach the host. The key is
 * exchanged over the pinned route after the invite is accepted. Emitting a
 * key on a scannable surface would defeat the revocation/rekey contract.
 *
 * The scheme is deliberately ours (`flit://`): LocalSend peers never emit
 * it and Flit never emits anything resembling theirs.
 *
 * A code is an untrusted routing instruction. Dialable fields are confined
 * to the local network: `parseAddCode` refuses a non-LAN [ip] and the
 * [lanOnly] mark tells the app that a [relay] host is off-LAN too.
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
    /** Circle id, v3 invites only. Ignored by v1/v2 parsers. */
    val circle: String? = null,
    /** Invite token, v3 invites only. Never a group key. */
    val invite: String? = null,
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
        // v3 invite fields. Kept off the wire when blank so v1/v2 codes keep
        // their exact historical byte shape. No key is ever emitted here.
        if (!circle.isNullOrBlank()) append("&circle=").append(URLEncoder.encode(circle, Charsets.UTF_8.name()))
        if (!invite.isNullOrBlank()) append("&invite=").append(URLEncoder.encode(invite, Charsets.UTF_8.name()))
    }

    /**
     * LAN-only mark carried by every parsed code. Derived from the two
     * dialable fields, never put on the wire and never part of equality, so
     * v1/v2/v3 codes still round-trip byte-for-byte. A relay hostname is not
     * an IP literal and is therefore off-LAN by inspection (DNS can point it
     * anywhere); the app gates on this before dialing a scanned code.
     */
    val lanOnly: Boolean
        get() = (ip.isBlank() || isLanAddress(ip)) &&
            (relay.isNullOrBlank() || FlitRoute.parseRelay(relay)?.let { isLanAddress(it.first) } == true)
}

/**
 * Whether [host] is a literal IP address on the local network: RFC1918
 * space, IPv4 loopback/link-local, IPv6 loopback (`::1`), ULA (`fc00::/7`)
 * and IPv6 link-local (`fe80::/10`). IPv4-mapped IPv6 is judged by its
 * embedded IPv4 half.
 *
 * Pure: no DNS, no sockets, no clock. A hostname, a public address, an
 * unspecified/multicast/reserved address or anything malformed is false —
 * a name cannot be known to stay on-link by inspection, so it is refused
 * even when it currently happens to resolve to a private address.
 */
fun isLanAddress(host: String): Boolean {
    val raw = host.trim().trim('[', ']').substringBefore('%')
    if (raw.isEmpty()) return false
    parseIpv4(raw)?.let { return isLanIpv4(it) }
    val v6 = parseIpv6(raw) ?: return false
    // ::ffff:a.b.c.d — the v4 half decides, including its loopback/LAN ranges.
    if (v6.take(5).all { it == 0 } && v6[5] == 0xFFFF) {
        return isLanIpv4(intArrayOf(v6[6] ushr 8, v6[6] and 0xFF, v6[7] ushr 8, v6[7] and 0xFF))
    }
    if (v6.take(7).all { it == 0 } && v6[7] == 1) return true              // ::1
    if ((v6[0] and 0xFE00) == 0xFC00) return true                          // fc00::/7 ULA
    if ((v6[0] and 0xFFC0) == 0xFE80) return true                          // fe80::/10 link-local
    return false
}

/** Strict dotted-quad: four decimal octets, no sign, no leading zeros. */
private fun parseIpv4(raw: String): IntArray? {
    val parts = raw.split('.')
    if (parts.size != 4) return null
    val out = IntArray(4)
    for (i in 0..3) {
        val p = parts[i]
        if (p.isEmpty() || p.length > 1 && p[0] == '0' || !p.all { it in '0'..'9' }) return null
        val n = p.toIntOrNull() ?: return null
        if (n !in 0..255) return null
        out[i] = n
    }
    return out
}

private fun isLanIpv4(b: IntArray): Boolean = when {
    b[0] == 10 -> true                          // 10.0.0.0/8
    b[0] == 172 && b[1] in 16..31 -> true       // 172.16.0.0/12
    b[0] == 192 && b[1] == 168 -> true          // 192.168.0.0/16
    b[0] == 127 -> true                         // loopback
    b[0] == 169 && b[1] == 254 -> true          // link-local
    else -> false
}

/**
 * Strict IPv6 literal → eight 16-bit words. Accepts `::` compression and
 * an embedded IPv4 tail, rejects shorthand, extra colons and junk.
 */
private fun parseIpv6(raw: String): IntArray? {
    var s = raw
    if (s.contains('.')) {
        val lastColon = s.lastIndexOf(':')
        if (lastColon < 0 || lastColon == s.length - 1) return null
        val tail = parseIpv4(s.substring(lastColon + 1)) ?: return null
        s = s.substring(0, lastColon + 1) +
            ((tail[0] shl 8) or tail[1]).toString(16) + ":" +
            ((tail[2] shl 8) or tail[3]).toString(16)
    }
    fun hexGroup(seg: String): Int? {
        if (seg.isEmpty() || seg.length > 4 || !seg.all { it in "0123456789abcdefABCDEF" }) return null
        return seg.toInt(16)
    }
    val groups = ArrayList<Int>(8)
    val marks = s.indexOf("::")
    if (marks >= 0) {
        if (s.indexOf("::", marks + 2) >= 0) return null
        val halves = s.split("::", limit = 2)
        fun expand(part: String): List<Int>? =
            if (part.isEmpty()) emptyList() else part.split(':').map { hexGroup(it) ?: return null }
        val left = expand(halves[0]) ?: return null
        val right = if (halves.size > 1) expand(halves[1]) ?: return null else emptyList()
        if (left.size + right.size >= 8) return null // :: must compress a group
        groups.addAll(left)
        repeat(8 - left.size - right.size) { groups.add(0) }
        groups.addAll(right)
    } else {
        val segs = s.split(':')
        if (segs.size != 8) return null
        for (seg in segs) groups.add(hexGroup(seg) ?: return null)
    }
    return if (groups.size == 8) groups.toIntArray() else null
}

/**
 * Tolerant parser for what lands in the scanner / paste field. Accepts
 * `flit://add?...` codes of any parameter order, v1, v2 and v3 alike. Anything
 * else is not a pairing: `null` (plain `ip:port` strings belong to the Add
 * dialog's address path, which is pre-existing behavior).
 *
 * v1 must carry ip + a valid port, exactly as before. v2/v3 may drop both
 * when they carry a well-formed relay; a port is only range-checked when an
 * ip is present. v1 ignores every later hint. v3 must additionally carry a
 * non-blank circle id and invite token — an invite code missing either is
 * malformed and refused rather than silently downgraded to a pairing.
 *
 * LAN gate: an [ip] is a literal address by contract (the sender announces
 * its on-link IPv4), so a public or malformed one is refused here. A relay
 * host may be a name and is kept, but the returned code's [QrAdd.lanOnly]
 * mark is false; the scanner / deep-link call site gates on it before dialing.
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
    if (version !in 1..3) return null
    val ip = params["ip"]?.trim().orEmpty()
    if (ip.isNotEmpty() && !isLanAddress(ip)) return null // no off-LAN dial from a code
    val port = (params["port"] ?: params["p"])?.toIntOrNull() ?: 0
    if (version == 1 && (ip.isEmpty() || port !in 1..65535)) return null
    val relay = if (version >= 2) params["relay"]?.trim()?.ifBlank { null } else null
    if (relay != null && FlitRoute.parseRelay(relay) == null) return null
    if (version >= 2 && ip.isEmpty() && relay == null) return null
    if (version >= 2 && ip.isNotEmpty() && port !in 1..65535) return null
    val circle = if (version >= 3) params["circle"]?.trim()?.ifBlank { null } else null
    val invite = if (version >= 3) params["invite"]?.trim()?.ifBlank { null } else null
    // There is deliberately no key parameter on the wire: a code that tried
    // to smuggle one cannot feed a group key into the store through here.
    if (version >= 3 && (circle == null || invite == null)) return null
    return QrAdd(
        ip = ip,
        port = port,
        tls = (params["tls"] ?: "1") != "0",
        fingerprint = (params["fp"] ?: params["f"] ?: "").trim().uppercase().ifBlank { "" },
        alias = (params["alias"] ?: params["a"] ?: "").trim(),
        mac = if (version >= 2) params["mac"]?.trim()?.ifBlank { null } else null,
        relay = relay,
        token = if (version >= 2) params["token"]?.trim()?.ifBlank { null } else null,
        circle = circle,
        invite = invite,
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
