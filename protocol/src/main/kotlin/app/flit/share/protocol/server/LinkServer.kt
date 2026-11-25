package app.flit.share.protocol.server

import app.flit.share.protocol.client.Link
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Serves [ServerCore]'s exact exchange semantics over any [Link] — the
 * binder Bluetooth RFCOMM (or any future carrier) plugs into. Hand-parsed
 * HTTP/1.1: request line, headers, Content-Length bodies, one
 * request-response per connection (`Connection: close` is the wire contract
 * over every pipe Flit speaks).
 *
 * Bodies: our own client is the only peer that talks over Bluetooth, and it
 * always declares Content-Length — chunked transfer-encoding requests are
 * refused. PeerHttpClient parses responses from the same hand-built wire, so
 * the two ends share one deliberately simple dialect.
 */
class LinkServer(private val core: ServerCore) {

    /** Handles one already-connected link through a single exchange, then closes it. */
    suspend fun serve(link: Link) {
        // Take each stream ONCE: a carrier may mint a fresh wrapper per access
        // and discard half-received record state between reads (the pipe-parity
        // test caught exactly that — request line read from one wrapper, header
        // bytes forever lost in the first wrapper's buffer). One exchange, one
        // instance per direction.
        val input = link.input
        val output = link.output
        try {
            val first = readCrlfLine(input) ?: return // nothing arrived; dead link
            val pieces = first.split(" ")
            if (pieces.size < 2) {
                writeResponse(output, 400, "text/plain", "garbage request line".toByteArray(StandardCharsets.UTF_8))
                return
            }
            val method = pieces[0].uppercase()
            val target = pieces[1]

            val qIdx = target.indexOf('?')
            var path = if (qIdx >= 0) target.substring(0, qIdx) else target
            if (path.length > 1 && path.endsWith("/")) path = path.removeSuffix("/") // ktor routes ignore a trailing slash
            val params = parseQuery(if (qIdx >= 0) target.substring(qIdx + 1) else null)
            val headers = parseHeaders(input)

            val contentLength = headers["content-length"]?.toLongOrNull() ?: -1L
            val te = headers["transfer-encoding"]
            if (te?.isNotBlank() == true || contentLength > 0 && method == "GET") {
                // Not a dialect we share; refuse before touching the body.
                writeResponse(output, 400, "text/plain", "unsupported body framing".toByteArray(StandardCharsets.UTF_8))
                return
            }
            core.handle(
                LinkExchange(
                    input = input,
                    output = output,
                    origin = link.origin.orEmpty(),
                    method = method,
                    path = path,
                    params = params,
                    headers = headers,
                    contentLength = maxOf(0L, contentLength),
                ),
            )
        } catch (_: IOException) {
            // Carrier vanished mid-exchange: the link closes below, ServerCore
            // sees a truncated body and does its resume bookkeeping.
        } finally {
            runCatching { link.close() }
        }
    }

    /** Links all point here; headers parse once, keys lowercase. */
    private fun parseHeaders(input: InputStream): HashMap<String, String> {
        val headers = HashMap<String, String>()
        while (true) {
            val line = readCrlfLine(input) ?: break
            if (line.isEmpty()) break // end of headers
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        return headers
    }

    /** Only the shapes our client sends: `a=1&b=2`, URL-decoded per component. */
    private fun parseQuery(raw: String?): HashMap<String, String> {
        val params = HashMap<String, String>()
        if (raw.isNullOrBlank()) return params
        for (kv in raw.split('&')) {
            if (kv.isEmpty()) continue
            val eq = kv.indexOf('=')
            runCatching {
                if (eq >= 0) {
                    params[URLDecoder.decode(kv.substring(0, eq), "UTF-8")] =
                        URLDecoder.decode(kv.substring(eq + 1), "UTF-8")
                } else {
                    params[URLDecoder.decode(kv, "UTF-8")] = ""
                }
            } // undecodable junk: skip the component, keep serving
        }
        return params
    }

    /** One CSV-free header/request line (CRLF or LF terminated), or null at EOF. */
    private fun readCrlfLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        var sawAny = false
        while (true) {
            val b = input.read()
            if (b < 0) return if (sawAny && bytes.size() > 0) bytes.toString("UTF-8") else null
            sawAny = true
            if (b == '\n'.code) break
            if (b != '\r'.code) {
                bytes.write(b)
                if (bytes.size() > 16 * 1024) throw IOException("TLS request line exceeded limits")
            }
        }
        return bytes.toString("UTF-8")
    }

    private fun writeResponse(out: OutputStream, status: Int, contentType: String, body: ByteArray) {
        val head = "HTTP/1.1 ${reason(status)}\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(StandardCharsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "200 OK"
        204 -> "204 No Content"
        400 -> "400 Bad Request"
        403 -> "403 Forbidden"
        404 -> "404 Not Found"
        409 -> "409 Conflict"
        422 -> "422 Unprocessable Entity"
        500 -> "500 Internal Server Error"
        else -> "$status Status"
    }
}

private class LinkExchange(
    private val input: InputStream,
    private val output: OutputStream,
    override val origin: String,
    override val method: String,
    override val path: String,
    private val params: Map<String, String>,
    private val headers: Map<String, String>,
    contentLength: Long,
) : HttpExchange {

    private var remainingBody = contentLength
    /** First response wins: one exchange per link means one real response. */
    private var responded = false

    override fun query(name: String): String? = params[name]

    override fun header(name: String): String? = headers[name.lowercase()]

    override suspend fun readBody(buf: ByteArray, offset: Int, len: Int): Int {
        if (remainingBody <= 0L) return 0
        val want = minOf(len.toLong(), remainingBody).toInt()
        val n = try {
            input.read(buf, offset, want)
        } catch (_: IOException) {
            return 0 // carrier died mid-body; the digest gate judges the result
        }
        if (n <= 0) return 0
        remainingBody -= n
        return n
    }

    override suspend fun respondText(status: Int, contentType: String, body: String) {
        if (responded) return
        responded = true
        val bytes = body.toByteArray(Charsets.UTF_8)
        writeHead(status, contentType, bytes.size.toLong())
        output.write(bytes)
        output.flush()
    }

    override suspend fun respondText(status: Int, contentType: String, body: String, headers: Map<String, String>) {
        if (responded) return
        responded = true
        val bytes = body.toByteArray(Charsets.UTF_8)
        writeHead(status, contentType, bytes.size.toLong(), headers)
        output.write(bytes)
        output.flush()
    }

    override suspend fun respondStatus(status: Int) {
        if (responded) return
        responded = true
        writeHead(status, null, 0L)
    }

    private fun writeHead(status: Int, contentType: String?, len: Long, headers: Map<String, String> = emptyMap()) {
        val head = buildString {
            append("HTTP/1.1 ").append(reason(status)).append("\r\n")
            if (contentType != null) append("Content-Type: ").append(contentType).append("\r\n")
            headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("Content-Length: ").append(len).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(StandardCharsets.ISO_8859_1))
        output.flush()
    }

    /** Same mapping as TCP's peerIp(): a stable label, not a DNS adventure. */
    private fun reason(status: Int): String = when (status) {
        200 -> "200 OK"
        204 -> "204 No Content"
        400 -> "400 Bad Request"
        403 -> "403 Forbidden"
        404 -> "404 Not Found"
        409 -> "409 Conflict"
        422 -> "422 Unprocessable Entity"
        500 -> "500 Internal Server Error"
        else -> "$status Status"
    }
}
