package app.flit.share.protocol.client

import app.flit.share.protocol.tls.TlsIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

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
class PeerHttpClient(
    private val identity: TlsIdentity,
    /** Where fresh links come from; TCP+TLS is the native default. */
    private val links: LinkProvider = TcpLinkProvider(identity),
) : AutoCloseable {

    /** Test hooks (never set in shipping code paths). */
    companion object {
        /** Abort the first upload body after N bytes (dead-sender emulation). */
        @Volatile
        var testAbortBytes: Long = 0L
    }

    /** FLIT_TEST_ABORT_BYTES fires on the first upload body only. */
    private val abortOnceFired = java.util.concurrent.atomic.AtomicBoolean(false)

    @Synchronized
    private fun attemptUnused(): Boolean = abortOnceFired.compareAndSet(false, true)

    class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
        fun text(): String = String(body, Charsets.UTF_8)
    }

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
        /** Milestone E: the fingerprint a relay route must pair with; direct routes pass null. */
        relayFor: String? = null,
        /** Sender-side pin: the TLS peer must present this certificate digest before any request byte. */
        expectServerFp: String? = null,
    ): Response = withContext(Dispatchers.IO) {
        raw(
            method, host, port, tls, pathAndQuery, headers, bodyProvider, contentLength,
            bodyProgress, timeoutMs, relayFor, expectServerFp,
        )
    }

    private suspend fun raw(
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
        relayFor: String?,
        expectServerFp: String?,
    ): Response {
        // Fail closed on any carrier: a pin is a TLS statement, so a plain
        // route can never satisfy it. TCP/TlsLink also check inside connect();
        // this is the end-to-end guard for providers that wrap other pipes.
        if (!tls && !expectServerFp.isNullOrBlank()) {
            throw IOException("plain HTTP cannot satisfy a TLS peer fingerprint pin")
        }
        links.connect(host, port, tls, timeoutMs.toInt(), relayFor, expectServerFp).use { link ->
            // The certificate must come from the carrier's real handshake,
            // never from a header, a config value or a caller-supplied label.
            // This runs before the request head is built or written.
            TlsIdentity.checkPeerPin(link.peerCertificate, expectServerFp)
            val out = link.output
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

            // Test knob: abort the FIRST upload body after N bytes — dead-link
            // emulation without MIUI tricks. Sources: FLIT_TEST_ABORT_BYTES
            // env (harness) or companion set from the app's debug props.
            val abortAfter = testAbortBytes.takeIf { it > 0 }
                ?: System.getenv("FLIT_TEST_ABORT_BYTES")?.trim()?.toLongOrNull()?.takeIf { it > 0 }
                ?: Long.MAX_VALUE
            bodyProvider?.invoke()?.use { input ->
                val buf = ByteArray(64 * 1024)
                var sent = 0L
                while (sent < contentLength) {
                    val want = minOf(buf.size.toLong(), contentLength - sent).toInt()
                    val n = input.read(buf, 0, want)
                    if (n <= 0) throw EOFException("body source ended after $sent of $contentLength bytes")
                    out.write(buf, 0, n)
                    out.flush()
                    sent += n
                    if (bodyProgress != null) bodyProgress(sent)
                    if (sent >= abortAfter && attemptUnused()) { // dead-sender simulation, once
                        runCatching { link.close() }
                        throw java.io.IOException("FLIT_TEST_ABORT after $sent bytes")
                    }
                }
                out.flush()
            }

            val inp = link.input.buffered()
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
