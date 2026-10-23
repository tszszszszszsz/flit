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
import java.io.InputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

data class SendTarget(val ip: String, val port: Int, val tls: Boolean) {
    fun baseUrl(): String = "${if (tls) "https" else "http"}://$ip:$port"
}

data class SendError(val fileName: String, val reason: String)

data class SendReport(val sent: List<String>, val failed: List<SendError>, val sessionId: String?)

class TransferException(val status: Int, val detail: String) :
    Exception("prepare-upload failed: $status $detail")

/** LocalSend v2.2 client: register, info, and file sending over the upload API. */
class ProtocolClient(
    private val identity: TlsIdentity,
) : AutoCloseable {

    private val http = PeerHttpClient(identity)

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    suspend fun info(target: SendTarget, timeoutMs: Long = 2500): PeerInfo? =
        withTimeoutOrNull(timeoutMs) {
            runCatching {
                json<PeerInfo>(
                    http.exchange(
                        method = "GET", host = target.ip, port = target.port, tls = target.tls,
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
        val prepare = postJson(
            target,
            preparePath,
            WireJson.encode(PrepareUploadRequest(self.sansAnnounce(), prepareHashMap)),
            timeoutMs = ACCEPT_TIMEOUT_MS, // receivers may wait for a human to accept
        )
        if (prepare.status == 204) {
            return SendReport(emptyList(), emptyList(), null)
        }
        if (prepare.status != 200) {
            throw TransferException(prepare.status, prepare.text())
        }
        val prepared = json<PrepareUploadResponse>(prepare)

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
                            uploadAttempts(target, prepared.sessionId, fileId, token, info, source, progress) {
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
        reprepare: (suspend () -> Pair<String, String>?)? = null,
    ): Pair<String, String?> {
        check(info.id == fileId)
        val contentToken = Protocol.resumeToken(info)
        val total = source.size
        var sessionId = sessionId0
        var token = token0
        var attempt = 0
        var carried = 0L // bytes receiver-confirmed to exist; keeps progress monotonic
        while (attempt < 3) { // mirrors upstream MAX_UPLOAD_ATTEMPTS
            attempt++
            // A Flit receiver may still hold an interrupted prefix of this
            // file (resume); the probe doubles as a liveness ping.
            var base = 0L
            if (carried > 0) {
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
                if (attempt >= 3) return fileId to "send failed: ${e.message ?: e.javaClass.simpleName}"
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

    private suspend fun queryPartial(target: SendTarget, contentToken: String): Long? {
        return runCatching {
            val resp = http.exchange(
                method = "GET", host = target.ip, port = target.port, tls = target.tls,
                pathAndQuery = "${Protocol.PATH_PARTIAL}?token=${enc(contentToken)}",
                timeoutMs = 2_000,
            )
            if (resp.status == 200) json<PartialInfo>(resp).size else null
        }.getOrNull()
    }

    private suspend fun postJson(target: SendTarget, pathAndQuery: String, jsonBody: String, timeoutMs: Long) =
        http.exchange(
            method = "POST", host = target.ip, port = target.port, tls = target.tls,
            pathAndQuery = pathAndQuery,
            headers = mapOf("Content-Type" to "application/json"),
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
