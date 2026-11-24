package app.flit.share.protocol.client

import app.flit.share.protocol.Protocol
import app.flit.share.protocol.model.FileInfo
import app.flit.share.protocol.model.PartialInfo
import app.flit.share.protocol.model.PeerInfo
import app.flit.share.protocol.model.PrepareUploadRequest
import app.flit.share.protocol.model.PrepareUploadResponse
import app.flit.share.protocol.model.WireJson
import app.flit.share.protocol.tls.TlsIdentity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

/**
 * A route to a peer. [relayFor] is milestone E's one extra field: when set,
 * `host:port` names a self-hosted relay instead of the peer and the hint
 * names the fingerprint to pair with. Direct routes leave it null and are
 * byte-for-byte unchanged.
 */
data class SendTarget(
    val ip: String,
    val port: Int,
    val tls: Boolean,
    val relayFor: String? = null,
) {
    fun baseUrl(): String = "${if (tls) "https" else "http"}://$ip:$port"
}

data class SendError(val fileName: String, val reason: String)

data class SendReport(val sent: List<String>, val failed: List<SendError>, val sessionId: String?)

/**
 * The carrier died and there is no receiver answer to speak of — connect
 * refused, reset mid-body, EOF. The contract this type enforces: a returned
 * [SendReport] means the far side SPOKE (sent, refused, declined — the
 * batch's story is over); this means the exchange never concluded, so the
 * batch stays owed exactly as it was and the engine hunts the peer later.
 */
class TransferUnreachable(message: String, cause: Throwable? = null) : IOException(message, cause)

class TransferException(val status: Int, val detail: String) :
    Exception("prepare-upload failed: $status $detail")

/** LocalSend v2.2 client: register, info, and file sending over the upload API. */
class ProtocolClient(
    private val identity: TlsIdentity,
    /** Own links: TCP+TLS default; Bluetooth providers ride this in Phase B. */
    links: LinkProvider = TcpLinkProvider(identity),
) : AutoCloseable {

    private val http = PeerHttpClient(identity, links)

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    suspend fun info(target: SendTarget, timeoutMs: Long = 2500): PeerInfo? =
        withTimeoutOrNull(timeoutMs) {
            runCatching {
                json<PeerInfo>(
                    http.exchange(
                        method = "GET", host = target.ip, port = target.port, tls = target.tls,
                        relayFor = target.relayFor,
                        pathAndQuery = Protocol.PATH_INFO, timeoutMs = timeoutMs,
                    ),
                )
            }.getOrNull()
        }

    /** Registers us with a target — how discovery announces are answered. Throws on failure. */
    suspend fun register(target: SendTarget, self: PeerInfo, timeoutMs: Long = 2500): PeerInfo? =
        withTimeoutOrNull(timeoutMs) {
            json<PeerInfo>(
                postJson(
                    target,
                    Protocol.PATH_REGISTER,
                    WireJson.encode(self.sansAnnounce()),
                    timeoutMs = timeoutMs,
                ),
            )
        }

    suspend fun cancelTransfer(target: SendTarget, sessionId: String) {
        withTimeoutOrNull(2000) {
            runCatching {
                http.exchange(
                    method = "POST", host = target.ip, port = target.port, tls = target.tls,
                    relayFor = target.relayFor,
                    pathAndQuery = "${Protocol.PATH_CANCEL}?sessionId=${enc(sessionId)}",
                    timeoutMs = 2000,
                )
            }
        }
    }

    suspend fun sendFiles(
        target: SendTarget,
        self: PeerInfo,
        files: Collection<OutgoingFile>,
        pin: String? = null,
        parallelism: Int = 4,
        multi: Boolean = false,
        progress: ((fileName: String, bytesSent: Long, totalBytes: Long) -> Unit)? = null,
    ): SendReport {
        check(files.isNotEmpty()) { "no files given" }

        val infos: HashMap<String, FileInfo> = HashMap()
        val outgoingById: HashMap<String, OutgoingFile> = HashMap()
        val prepareHashMap: LinkedHashMap<String, FileInfo> = LinkedHashMap()
        for (f in files) {
            val id = "f${UUID.randomUUID().toString().substring(0, 8)}"
            val info = FileInfo(
                id = id,
                fileName = f.wireName,
                size = f.size,
                fileType = f.mime,
                sha256 = sha256Hex { f.openStream() },
            )
            infos[id] = info
            outgoingById[id] = f
            prepareHashMap[id] = info
        }

        val preparePath = Protocol.PATH_PREPARE_UPLOAD + (pin?.let { "?pin=" + enc(it) } ?: "")
        val prepare = try {
            postJson(
                target,
                preparePath,
                WireJson.encode(PrepareUploadRequest(self.sansAnnounce(), prepareHashMap)),
                timeoutMs = ACCEPT_TIMEOUT_MS, // receivers may wait for a human to accept
                extraHeaders = if (multi) mapOf(MULTI_HEADER to "1") else emptyMap(),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: IOException) {
            // The batch's first handshake never reached a receiver: there is
            // no answer to report, so the caller learns the carrier died —
            // same contract the per-file attempts enforce below.
            throw TransferUnreachable("receiver unreachable: ${e.message ?: e.javaClass.simpleName}", e)
        }
        if (prepare.status == 204) {
            return SendReport(emptyList(), emptyList(), null)
        }
        if (prepare.status != 200) {
            throw TransferException(prepare.status, prepare.text())
        }
        val prepared = json<PrepareUploadResponse>(prepare)
        // The receiver arms multi-socket sending when (and only when) we sent
        // the header; the `multipart` hint is its go signal. Absence (a v2.2
        // peer never has the field) falls back to classic single-stream.
        val chunkBytes = prepared.multipart ?: 0L

        // Single-file snapshot for mid-transfer re-prepare (resume path).
        val prepareMap1 = LinkedHashMap<String, FileInfo>(prepareHashMap)
        val sem = Semaphore(parallelism)
        val results: List<Pair<String, String?>> = coroutineScope {
            prepared.files.map { (fileId, token) ->
                async {
                    sem.withPermit {
                        val info = infos[fileId] ?: return@withPermit fileId to "unmatched file id"
                        val source = outgoingById[fileId] ?: return@withPermit fileId to "unmatched file id"
                        try {
                            // Turbo lane: split large files across concurrent
                            // sockets when the receiver signed up for it.
                            // Resume-aware: probe first and start the ranges
                            // at the base the receiver advertises, so a retry
                            // after a death extends the kept prefix instead
                            // of re-sending the whole file.
                            var carried = 0L
                            if (multi && chunkBytes > 0 && info.size >= TURBO_MIN_BYTES) {
                                carried = runCatching {
                                    queryPartial(target, Protocol.resumeToken(info))
                                }.getOrNull() ?: 0L
                                if (carried !in 1 until source.size) carried = 0
                                val turboErr = turboUpload(
                                    target, prepared.sessionId, fileId, token, info, source,
                                    from = carried, progress = progress,
                                )
                                if (turboErr == null) return@withPermit fileId to null
                                // Turbo was cut short: whatever covered bytes
                                // from zero are salvageable — the receiver's
                                // /partial answers with that base, and the
                                // single-stream resume extends it.
                                carried = runCatching {
                                    queryPartial(target, Protocol.resumeToken(info))
                                }.getOrNull() ?: 0L
                                if (carried !in 1 until source.size) carried = 0
                            }
                            uploadAttempts(target, prepared.sessionId, fileId, token, info, source, initialCarried = carried, progress = progress) {
                                // A fresh session for just this file, so a
                                // resumed part can be re-claimed after the
                                // receiver reaped the dead one.
                                val resp = postJson(
                                    target,
                                    Protocol.PATH_PREPARE_UPLOAD + (pin?.let { "?pin=" + enc(it) } ?: ""),
                                    WireJson.encode(PrepareUploadRequest(self.sansAnnounce(), prepareMap1)),
                                    ACCEPT_TIMEOUT_MS,
                                )
                                if (resp.status != 200) return@uploadAttempts null
                                val p = json<PrepareUploadResponse>(resp)
                                val t = p.files[fileId] ?: return@uploadAttempts null
                                p.sessionId to t
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: TransferUnreachable) {
                            throw e
                        } catch (e: IOException) {
                            // A carrier-shaped failure anywhere in the per-file
                            // attempts (connect refused, reset mid-body, EOF —
                            // even a source that suddenly cannot be read) never
                            // concludes an exchange: there is no receiver answer
                            // to report, so the batch stays owed.
                            throw TransferUnreachable(e.message ?: "carrier died mid-exchange", e)
                        } catch (e: Throwable) {
                            fileId to (e.message ?: "error")
                        }
                    }
                }
            }.awaitAll()
        }

        val anyFailed = results.any { it.second != null }
        if (anyFailed) cancelTransfer(target, prepared.sessionId)

        val sent = results.filter { it.second == null }.map { r -> infos.getValue(r.first).fileName }
        val failed = results.filter { it.second != null }.map { (fileId, reason) ->
            SendError(infos[fileId]?.fileName ?: fileId, reason ?: "error")
        }
        return SendReport(sent, failed, prepared.sessionId)
    }

    private suspend fun uploadAttempts(
        target: SendTarget,
        sessionId0: String,
        fileId: String,
        token0: String,
        info: FileInfo,
        source: OutgoingFile,
        progress: ((fileName: String, bytesSent: Long, totalBytes: Long) -> Unit)?,
        initialCarried: Long = 0,
        reprepare: (suspend () -> Pair<String, String>?)? = null,
    ): Pair<String, String?> {
        check(info.id == fileId)
        val contentToken = Protocol.resumeToken(info)
        val total = source.size
        var sessionId = sessionId0
        var token = token0
        var attempt = 0
        var carried = initialCarried.coerceIn(0, total) // receiver-confirmed bytes; keeps progress monotonic
        while (attempt < 3) { // mirrors upstream MAX_UPLOAD_ATTEMPTS
            attempt++
            // A Flit receiver may still hold an interrupted prefix of this
            // file: not only from a connection this process lost (the catch
            // below probes), but from a previous RUN whose sender died
            // mid-file — the receiver's parts registry answers from its own
            // disk. A fresh entry probes on the first attempt; old v2.2
            // receivers 404 and cost one round trip. The probe doubles as a
            // liveness ping.
            var base = 0L
            if (carried > 0 || attempt == 1) {
                base = queryPartial(target, contentToken) ?: 0
                if (base >= total) base = 0 // full-size buffer with wrong hash: resend
                if (base !in 1 until total) base = 0
                if (base != carried) {
                    carried = base
                    progress?.invoke(info.fileName, carried, total)
                }
            }
            val uploadPath = "${Protocol.PATH_UPLOAD}?sessionId=${enc(sessionId)}&fileId=${enc(fileId)}&token=${enc(token)}"
            val resp: PeerHttpClient.Response? = try {
                http.exchange(
                    method = "POST", host = target.ip, port = target.port, tls = target.tls,
                    relayFor = target.relayFor,
                    pathAndQuery = uploadPath,
                    headers = buildMap {
                        put("Content-Type", "application/octet-stream")
                        if (base > 0) put("X-Flit-Resume", contentToken)
                    },
                    bodyProvider = {
                        val st = source.openStream()
                        if (base > 0) st.skipFlit(base)
                        st
                    },
                    contentLength = total - base,
                    bodyProgress = progress?.let { cb ->
                        { sent: Long -> cb(info.fileName, base + sent, total) }
                    },
                    timeoutMs = UPLOAD_TIMEOUT_MS,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Transport died mid-body. The receiver records its prefix as
                // it winds down, and the sender is usually a few ms ahead of
                // that — poll briefly instead of one-shot probing.
                if (attempt >= 3) {
                    // Attempts exhausted on the carrier itself: no receiver
                    // answer exists. Throwing (not reporting) is the contract
                    // the engine's checkpoint rides — a report would read as
                    // "the far side spoke" and conclude a story that never
                    // happened.
                    throw TransferUnreachable("send failed: ${e.message ?: e.javaClass.simpleName}", e)
                }
                var probed = 0L
                for (idx in 0 until 4) {
                    val v = try { queryPartial(target, contentToken) } catch (t: Throwable) {
                        null
                    } ?: 0
                    if (v > probed) probed = v
                    if (probed > 0) break
                }
                if (probed in 1 until total) {
                    // Same-session resume: the receiver still holds the slot
                    // and the old token claims fine (final sha gates it).
                    carried = probed
                    progress?.invoke(info.fileName, carried, total)
                }
                // No confirmed prefix (or probe silent): fall through to a
                // plain same-session retry; a refused claim then goes to the
                // reprepare branch below. Final sha keeps everything safe.
                continue
            }
            when (resp!!.status) {
                200 -> return fileId to null
                422 -> {
                    // body was fully shipped; receiver rejected the digest.
                    // (upstream semantics: retry with the SAME token)
                    carried = total
                    progress?.invoke(info.fileName, carried, total)
                }
                else -> {
                    // Claim refused (session was reaped/replaced): a fresh
                    // session keeps the on-disk part resumable.
                    val fresh = if (attempt < 3 && reprepare != null) reprepare() else null
                    if (fresh == null) return fileId to "receiver answered ${resp.status}"
                    sessionId = fresh.first
                    token = fresh.second
                }
            }
        }
        return fileId to "checksum never matched (3 tries)"
    }

    /** Drop the first n bytes without reading them into memory. */
    private fun InputStream.skipFlit(n: Long) {
        var rem = n
        val b = ByteArray(64 * 1024)
        while (rem > 0) {
            val got = read(b, 0, minOf(rem, b.size.toLong()).toInt())
            if (got < 0) throw EOFException("stream too short to skip $n bytes")
            rem -= got
        }
    }

    /**
     * Multi-socket (Flit↔Flit) upload: split the bytes from [from] to the
     * end into contiguous ranges and push each on its own connection to the
     * normal upload route with `Content-Range`. Ranges are disjoint, so the
     * receiver pieces them into its temp with no coordination on our side.
     * One failed stream cancels the others and surfaces the failure; the
     * caller falls back to a plain single-stream resume of the receiver's
     * salvage base.
     *
     * [from] is the receiver's advertised prefix: ranges begin there and
     * every request declares it in `X-Flit-Base`, so the receiver can seed
     * its coverage ledger with the same bytes and refuse a disagreeing view.
     *
     * @return null when every stream confirmed its range; an error message
     *   otherwise (sender then continues on the single-stream path).
     */
    private suspend fun turboUpload(
        target: SendTarget,
        sessionId: String,
        fileId: String,
        token: String,
        info: FileInfo,
        source: OutgoingFile,
        from: Long,
        progress: ((fileName: String, bytesSent: Long, totalBytes: Long) -> Unit)?,
    ): String? {
        val total = source.size
        if (from < 0 || from >= total) return "multi-stream: nothing to resume"
        val span = total - from
        val streamCount = minOf(
            TURBO_MAX_STREAMS,
            maxOf(1, ((span + SPLIT_TARGET_BYTES - 1) / SPLIT_TARGET_BYTES).toInt()),
        )
        if (streamCount < 2) return "multi-stream: remainder not worth splitting"
        val uploadPath =
            "${Protocol.PATH_UPLOAD}?sessionId=${enc(sessionId)}&fileId=${enc(fileId)}&token=${enc(token)}"
        return try {
            coroutineScope {
                (0 until streamCount).map { i ->
                    val start = from + span * i / streamCount
                    val endExcl = from + span * (i + 1) / streamCount
                    val len = endExcl - start
                    val last = endExcl - 1
                    async {
                        var attempt = 0
                        while (attempt < 2) { // transport hiccup: one clean range retry
                            attempt++
                            val resp = try {
                                http.exchange(
                                    method = "POST", host = target.ip, port = target.port, tls = target.tls,
                                    relayFor = target.relayFor,
                                    pathAndQuery = uploadPath,
                                    headers = buildMap {
                                        put("Content-Type", "application/octet-stream")
                                        // The receiver's coverage ledger reads exactly this.
                                        put("Content-Range", "bytes $start-$last/$total")
                                        put("X-Flit-Base", from.toString())
                                    },
                                    bodyProvider = {
                                        val st = source.openStream()
                                        st.skipFlit(start) // cheap: buffered reads, no memory copy
                                        RangeStream(st, len)
                                    },
                                    contentLength = len,
                                    bodyProgress = progress?.let { cb ->
                                        var lastReport = 0L
                                        { sent: Long ->
                                            if (sent - lastReport >= PROGRESS_STEP) {
                                                lastReport = sent
                                                cb(info.fileName, start + sent, total)
                                            }
                                        }
                                    },
                                    timeoutMs = UPLOAD_TIMEOUT_MS,
                                )
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                if (attempt >= 2) throw e
                                continue // reopen + re-skip, resend the whole range
                            }
                            if (resp.status == 200) return@async
                            throw IllegalStateException("range $start-$last answered ${resp.status}")
                        }
                        throw EOFException("range $start-$last never settled")
                    }
                }.awaitAll()
            }
            progress?.invoke(info.fileName, total, total) // all ranges confirmed
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            "multi-stream: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * A fresh range view over one already-positioned source stream: serves
     * exactly [remaining] bytes. One per exchange — sources are never shared
     * between concurrent streams.
     */
    private class RangeStream(private val src: InputStream, private var remaining: Long) : InputStream() {
        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = src.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }

        override fun close() = src.close()
    }

    private suspend fun queryPartial(target: SendTarget, contentToken: String): Long? {
        return runCatching {
            val resp = http.exchange(
                method = "GET", host = target.ip, port = target.port, tls = target.tls,
                relayFor = target.relayFor,
                pathAndQuery = "${Protocol.PATH_PARTIAL}?token=${enc(contentToken)}",
                timeoutMs = 2_000,
            )
            if (resp.status == 200) json<PartialInfo>(resp).size else null
        }.getOrNull()
    }

    private suspend fun postJson(
        target: SendTarget,
        pathAndQuery: String,
        jsonBody: String,
        timeoutMs: Long,
        extraHeaders: Map<String, String> = emptyMap(),
    ) = http.exchange(
        method = "POST", host = target.ip, port = target.port, tls = target.tls,
        relayFor = target.relayFor,
        pathAndQuery = pathAndQuery,
        headers = buildMap {
            put("Content-Type", "application/json")
            putAll(extraHeaders)
        },
        bodyProvider = { jsonBody.byteInputStream(Charsets.UTF_8) },
        contentLength = jsonBody.toByteArray(Charsets.UTF_8).size.toLong(),
        timeoutMs = timeoutMs,
    )

    private inline fun <reified T> json(resp: PeerHttpClient.Response): T {
        val text = resp.text().trim()
        if (text.isEmpty()) throw IllegalStateException("peer answered ${resp.status} with empty body")
        return WireJson.decode(text)
    }

    override fun close() {
        http.close()
    }

    companion object {
        /** Receivers may hold prepare until a human accepts the offer. */
        private const val ACCEPT_TIMEOUT_MS = 30_000L
        private const val UPLOAD_TIMEOUT_MS = 120_000L

        /** Files at least this big get the concurrent-socket treatment. */
        private val TURBO_MIN_BYTES = 16L shl 20 // 16 MiB
        private val TURBO_MAX_STREAMS = 4

        /** Rough split target per stream when deciding on a stream count. */
        private val SPLIT_TARGET_BYTES = 16L shl 20

        /** Coarse per-stream progress callbacks so 4 streams don't spam 40 Hz. */
        private const val PROGRESS_STEP = 512L * 1024L

        /** Marks a prepare-upload as multi-socket capable (Flit↔Flit only). */
        internal const val MULTI_HEADER = "X-Flit-Multi"

        /** Hex SHA-256 of a freshly opened stream; used as the upload checksum. */
        fun sha256Hex(open: () -> InputStream): String {
            val md = MessageDigest.getInstance("SHA-256")
            open().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
