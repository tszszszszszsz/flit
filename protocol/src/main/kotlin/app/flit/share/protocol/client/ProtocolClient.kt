package app.flit.share.protocol.client

import app.flit.share.protocol.Protocol
import app.flit.share.protocol.model.FileInfo
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
        parallelism: Int = 2,
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

        val sem = Semaphore(parallelism)
        val results: List<Pair<String, String?>> = coroutineScope {
            prepared.files.map { (fileId, token) ->
                async {
                    sem.withPermit {
                        val info = infos[fileId] ?: return@withPermit fileId to "unmatched file id"
                        val source = outgoingById[fileId] ?: return@withPermit fileId to "unmatched file id"
                        try {
                            uploadAttempts(target, prepared.sessionId, fileId, token, info, source, progress)
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
        sessionId: String,
        fileId: String,
        token: String,
        info: FileInfo,
        source: OutgoingFile,
        progress: ((fileName: String, bytesSent: Long, totalBytes: Long) -> Unit)?,
    ): Pair<String, String?> {
        check(info.id == fileId)
        val uploadPath = "${Protocol.PATH_UPLOAD}?sessionId=${enc(sessionId)}&fileId=${enc(fileId)}&token=${enc(token)}"
        var attempt = 0
        var carried = 0L // bytes shipped in earlier (failed) attempts, keeps progress monotonic
        while (attempt < 3) { // mirrors upstream MAX_UPLOAD_ATTEMPTS
            attempt++
            val resp = http.exchange(
                method = "POST", host = target.ip, port = target.port, tls = target.tls,
                pathAndQuery = uploadPath,
                headers = mapOf("Content-Type" to "application/octet-stream"),
                bodyProvider = { source.openStream() },
                contentLength = source.size,
                bodyProgress = progress?.let { cb -> { sent: Long -> cb(info.fileName, carried + sent, info.size) } },
                timeoutMs = UPLOAD_TIMEOUT_MS,
            )
            when (resp.status) {
                200 -> return fileId to null
                422 -> {
                    carried = info.size // body was fully shipped; receiver rejected the digest
                    progress?.invoke(info.fileName, carried, info.size)
                } // retry with the SAME token (upstream semantics)
                else -> return fileId to "receiver answered ${resp.status}"
            }
        }
        return fileId to "checksum never matched (3 tries)"
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
