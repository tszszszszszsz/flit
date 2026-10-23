package app.flit.share.protocol.client

import app.flit.share.protocol.tls.TlsIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A deliberately tiny HTTP/1.1 client for LocalSend peers.
 *
 * Why not a framework: real LocalSend servers (rustls-based) require a client
 * certificate in the TLS handshake; the JDK TLS stack with our issuer-agnostic
 * KeyManager ([TlsIdentity.keyManagers]) handles exactly that. One exchange per
 * connection keeps the state machine trivial, and the v2 protocol never needs
 * redirects, cookies, or compression - just POST/GET with content-length.
 *
 * Hostname verification is intentionally absent (SSLSocket default): peers are
 * reached by IP and authenticated by the announced fingerprint instead.
 */
class PeerHttpClient(private val identity: TlsIdentity) : AutoCloseable {

    class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
        fun text(): String = String(body, Charsets.UTF_8)
    }

    private val sslFactory: SSLSocketFactory = identity.sslContext().socketFactory

    suspend fun exchange(
        method: String,
        host: String,
        port: Int,
        tls: Boolean,
        pathAndQuery: String,
        headers: Map<String, String> = emptyMap(),
        bodyProvider: (() -> InputStream)? = null,
        contentLength: Long = 0,
        bodyProgress: ((Long) -> Unit)? = null,
        timeoutMs: Long = 10_000,
    ): Response = withContext(Dispatchers.IO) {
        raw(method, host, port, tls, pathAndQuery, headers, bodyProvider, contentLength, bodyProgress, timeoutMs)
    }

    private fun raw(
        method: String,
        host: String,
        port: Int,
        tls: Boolean,
        pathAndQuery: String,
        headers: Map<String, String>,
        bodyProvider: (() -> InputStream)?,
        contentLength: Long,
        bodyProgress: ((Long) -> Unit)?,
        timeoutMs: Long,
    ): Response {
        val socket: Socket = if (tls) sslFactory.createSocket() else Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), timeoutMs.toInt())
            socket.soTimeout = timeoutMs.toInt()
            if (tls) (socket as SSLSocket).startHandshake()

            val out = socket.getOutputStream()
            val head = StringBuilder().apply {
                append(method).append(' ').append(pathAndQuery).append(" HTTP/1.1\r\n")
                append("Host: ").append(host).append(':').append(port).append("\r\n")
                append("User-Agent: Flit\r\n")
                headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
                if (bodyProvider != null) append("Content-Length: ").append(contentLength).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            // US_ASCII: header bytes only; body is written separately.
            out.write(head.toString().toByteArray(Charsets.US_ASCII))
            out.flush()

            bodyProvider?.invoke()?.use { input ->
                val buf = ByteArray(64 * 1024)
                var sent = 0L
                while (sent < contentLength) {
                    val want = minOf(buf.size.toLong(), contentLength - sent).toInt()
                    val n = input.read(buf, 0, want)
                    if (n <= 0) throw EOFException("body source ended after $sent of $contentLength bytes")
                    out.write(buf, 0, n)
                    sent += n
                    if (bodyProgress != null) bodyProgress(sent)
                }
                out.flush()
            }

            val inp = socket.getInputStream().buffered()
            val statusLine = readLine(inp) ?: throw EOFException("server sent no response")
            val status = statusLine.split(" ").getOrNull(1)?.toIntOrNull()
                ?: throw IllegalStateException("bad status line from peer: $statusLine")

            val respHeaders = HashMap<String, String>()
            while (true) {
                val line = readLine(inp) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) respHeaders[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }

            val body = when {
                respHeaders["transfer-encoding"]?.contains("chunked") == true -> readChunked(inp)
                (respHeaders["content-length"]?.toLongOrNull() ?: 0L) > 0 ->
                    readFully(inp, respHeaders["content-length"]!!.toLong())
                else -> readAll(inp)
            }
            return Response(status, respHeaders, body)
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Reads one header line without the trailing CRLF; null at clean EOF. */
    private fun readLine(inp: InputStream): String? {
        val buf = ByteArray(256)
        var n = 0
        while (true) {
            val b = inp.read()
            if (b < 0) return if (n == 0) null else String(buf, 0, n, Charsets.US_ASCII)
            if (b == '\n'.code) {
                var len = n
                if (len > 0 && buf[len - 1] == '\r'.code.toByte()) len--
                return String(buf, 0, len, Charsets.US_ASCII)
            }
            if (n >= buf.size) throw IllegalStateException("header line too long")
            buf[n++] = b.toByte()
        }
    }

    private fun readFully(inp: InputStream, length: Long): ByteArray {
        val out = ByteArrayOutputStream(length.coerceAtMost(1 shl 22).toInt())
        val buf = ByteArray(64 * 1024)
        var got = 0L
        while (got < length) {
            val want = minOf(buf.size.toLong(), length - got).toInt()
            val n = inp.read(buf, 0, want)
            if (n <= 0) throw EOFException("peer closed after $got of $length body bytes")
            out.write(buf, 0, n)
            got += n
        }
        return out.toByteArray()
    }

    private fun readChunked(inp: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(inp) ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                // consume trailers up to the blank line
                while (true) {
                    val t = readLine(inp) ?: break
                    if (t.isEmpty()) break
                }
                break
            }
            val chunk = readFully(inp, size.toLong())
            out.write(chunk)
            readLine(inp) // trailing CRLF after each chunk
        }
        return out.toByteArray()
    }

    /** Reads until EOF (safe with Connection: close, which we always send). */
    private fun readAll(inp: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (true) {
            val n = inp.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    override fun close() {
        // no pooled resources; sockets close per exchange
    }
}
