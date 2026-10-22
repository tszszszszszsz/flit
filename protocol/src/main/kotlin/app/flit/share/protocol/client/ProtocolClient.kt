package app.flit.share.protocol.client

import app.flit.share.protocol.Protocol
import app.flit.share.protocol.model.FileInfo
import app.flit.share.protocol.model.PeerInfo
import app.flit.share.protocol.model.PrepareUploadRequest
import app.flit.share.protocol.model.PrepareUploadResponse
import app.flit.share.protocol.model.WireJson
import app.flit.share.protocol.tls.TlsIdentity
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.client.call.body
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.FileInputStream
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

data class SendTarget(val ip: String, val port: Int, val tls: Boolean) {
    fun baseUrl(): String = "${if (tls) "https" else "http"}://$ip:$port"
}

data class SendSpec(val path: Path, val wireName: String)

data class SendError(val fileName: String, val reason: String)

data class SendReport(val sent: List<String>, val failed: List<SendError>, val sessionId: String?)

class TransferException(val status: HttpStatusCode, val detail: String) :
    Exception("prepare-upload failed: ${status.value} $detail")

/** LocalSend v2.2 client: register, info, and file sending over the upload API. */
class ProtocolClient(
    private val identity: TlsIdentity,
    httpClient: HttpClient? = null,
) : AutoCloseable {

    private val client: HttpClient =
        httpClient ?: HttpClient(CIO) {
            engine { sslContext = identity.sslContext() }
            install(ContentNegotiation) { json(WireJson.json) }
        }

    private fun url(target: SendTarget, path: String): String = target.baseUrl() + path

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    suspend fun info(target: SendTarget, timeoutMs: Long = 2500): PeerInfo? =
        withTimeoutOrNull(timeoutMs) {
            runCatching {
                client.get(url(target, Protocol.PATH_INFO)).body<PeerInfo>()
            }.getOrNull()
        }

    /** Registers us with a target — used by discovery to answer announcements. */
    suspend fun register(target: SendTarget, self: PeerInfo, timeoutMs: Long = 2500): PeerInfo? =
        withTimeoutOrNull(timeoutMs) {
            runCatching {
                client.post(url(target, Protocol.PATH_REGISTER)) {
                    contentType(ContentType.Application.Json)
                    setBody(self.sansAnnounce())
                }.body<PeerInfo>()
            }.getOrNull()
        }

    suspend fun sendFiles(
        target: SendTarget,
        self: PeerInfo,
        specs: List<SendSpec>,
        pin: String? = null,
        parallelism: Int = 2,
    ): SendReport {
        check(specs.isNotEmpty()) { "no files given" }

        val infos: Map<String, FileInfo> = specs.associate { s ->
            val id = "f${UUID.randomUUID().toString().substring(0, 8)}"
            val mime = guessMime(s.path)
            id to FileInfo(
                id = id,
                fileName = s.wireName,
                size = Files.size(s.path),
                fileType = mime,
                sha256 = sha256Hex(s.path),
            )
        }
        val pathsById = specs.associate { it to infos.entries.first { (_, info) -> info.fileName == it.wireName } }
        val pathById: Map<String, Path> = specs.associate { s ->
            val info = infos.entries.first { (_, info) -> info.fileName == s.wireName }
            info.key to s.path
        }

        val preparePath = Protocol.PATH_PREPARE_UPLOAD +
            (pin?.let { "?pin=" + enc(it) } ?: "")
        val prepareUrl = url(target, preparePath)

        val resp: HttpResponse = client.post(prepareUrl) {
            contentType(ContentType.Application.Json)
            setBody(PrepareUploadRequest(self.sansAnnounce(), infos))
        }
        when {
            resp.status == HttpStatusCode.NoContent ->
                return SendReport(emptyList(), emptyList(), null)

            resp.status != HttpStatusCode.OK -> {
                val detail = runCatching { resp.bodyAsText() }.getOrDefault("")
                throw TransferException(resp.status, detail)
            }
        }
        val prepared = resp.body<PrepareUploadResponse>();

        val sem = Semaphore(parallelism)
        val results: List<Pair<String, String?>> = prepared.files.map { (fileId, token) ->
            async {
                sem.withPermit {
                    val path = pathById[fileId] ?: return@withPermit fileId to "missing local path"
                    val info = infos.getValue(fileId)
                    try {
                        uploadAttempts(target, prepared.sessionId, fileId, token, path, info)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        info.fileName to (e.message ?: "error")
                    }
                }
            }
        }.awaitAll()

        val anyFailed = results.any { it.second != null }
        if (anyFailed) {
            // best-effort cancel so the receiver doesn't lock its single slot
            withTimeoutOrNull(2000) {
                runCatching {
                    client.post(url(target, "${Protocol.PATH_CANCEL}?sessionId=" + enc(prepared.sessionId)))
                }
            }
        }
        val sent = results.filter { it.second == null }.mapNotNull { r -> infos[r.first]?.fileName }
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
        path: Path,
        info: FileInfo,
    ): Pair<String, String?> {
        val uploadUrl = url(
            target,
            "${Protocol.PATH_UPLOAD}?sessionId=${enc(sessionId)}&fileId=${enc(fileId)}&token=${enc(token)}",
        )
        var attempt = 0
        while (attempt < 3) { // mirrors upstream MAX_UPLOAD_ATTEMPTS
            attempt++
            val resp = client.post(uploadUrl) {
                contentType(ContentType.Application.OctetStream)
                setBody(FileContent(path, Files.size(path)))
            }
            when (resp.status) {
                HttpStatusCode.OK -> return info.fileName to null
                HttpStatusCode.UnprocessableEntity -> {} // retry: same token per upstream semantics
                else -> return info.fileName to "receiver answered ${resp.status.value}"
            }
        }
        return info.fileName to "checksum never matched (${attempt} tries)"
    }

    private fun guessMime(path: Path): String =
        runCatching { Files.probeContentType(path) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "application/octet-stream"

    private fun sha256Hex(path: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(path.toFile()).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    override fun close() {
        client.close()
    }
}

private class FileContent(
    private val path: Path,
    override val contentLength: Long,
) : OutgoingContent.WriteChannelContent() {
    override suspend fun writeTo(channel: ByteWriteChannel) {
        FileInputStream(path.toFile()).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                channel.writeFully(buf, 0, n)
                channel.flush()
            }
        }
    }
}
