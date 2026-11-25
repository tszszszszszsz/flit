package app.flit.share.protocol.server

/**
 * One HTTP exchange, transport-neutral. This is the seam that allows the
 * exact same wire semantics (v2.2 routes, Flit extensions) to ride TCP/ktor
 * *and* any other pipe — Bluetooth RFCOMM — without forking the protocol:
 * ServerCore consumes this and knows nothing about sockets.
 *
 * Threading: single sequential consumer per exchange (one request-response
 * per `Connection: close` connection over every carrier Flit speaks).
 */
interface HttpExchange {
    /** HTTP method as sent: "GET"/"POST". */
    val method: String

    /** Matched request path, e.g. Protocol.PATH_UPLOAD. */
    val path: String

    /**
     * Stable peer label for this connection: the remote IP on TCP, the
     * remote device address over Bluetooth. Drives trust checks and the
     * interrupted-parts registry — the label Flit compares is all it needs
     * to know about who is talking.
     */
    val origin: String

    /** First decoded query parameter value, or null. */
    fun query(name: String): String?

    /** Header value, name matched case-insensitively, or null. */
    fun header(name: String): String?

    /**
     * Pulls the request body into [buf][off..off+len); 0 (or negative) =
     * body over. Main use is the big upload body pump, so implementations
     * may block; returning 0 mid-body (carrier died) is allowed — the digest
     * gate judges the result.
     */
    suspend fun readBody(buf: ByteArray, offset: Int, len: Int): Int

    /** Whole body as UTF-8 text (small: JSON payloads only). */
    suspend fun readBodyText(): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = readBody(buf, 0, buf.size)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /**
     * Bounded variant for control routes: `null` means the body exceeded
     * [maxBytes], so the caller can answer 400 instead of buffering a flood.
     * The v2.2 routes keep using the unbounded [readBodyText] above.
     */
    suspend fun readBodyCapped(maxBytes: Int): String? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = readBody(buf, 0, buf.size)
            if (n <= 0) break
            total += n
            if (total > maxBytes) return null
            out.write(buf, 0, n)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** Text response ([status] plain integer; contentType like "application/json"). */
    suspend fun respondText(status: Int, contentType: String, body: String)

    /** Status-only response, empty body. */
    suspend fun respondStatus(status: Int)
}
