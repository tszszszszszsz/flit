package app.flit.share.protocol.model

import kotlinx.serialization.Serializable

/**
 * Device announcement / registration info (v2.2).
 *
 * Wire fields are camelCase. `port` and `protocol` are required in multicast
 * announcements, but omitted from register/info responses — kotlinx-serialization
 * omits them when they equal their default values, which keeps both shapes valid.
 * `announce` is a legacy v2.0 field: tolerated when reading, never written.
 */
@Serializable
data class PeerInfo(
    val alias: String,
    val version: String,
    val deviceModel: String? = null,
    val deviceType: String? = null,
    val fingerprint: String,
    val port: Int = app.flit.share.protocol.Protocol.DEFAULT_PORT,
    val protocol: String = "https",
    val download: Boolean = false,
    val announce: Boolean? = null,
) {
    fun sansAnnounce(): PeerInfo = copy(announce = null)

    /**
     * The UDP announcement payload. Requires concrete port/protocol (a device
     * announcing without serving is a protocol violation).
     */
    fun announcePayload(): AnnounceMessage = AnnounceMessage(
        alias = alias,
        version = version,
        deviceModel = deviceModel,
        deviceType = deviceType,
        fingerprint = fingerprint,
        port = port,
        protocol = protocol,
        download = download,
    )
}

/** Optional timestamp metadata, ISO-8601 "2021-01-01T12:34:56Z". */
@Serializable
data class FileMetadata(
    val modified: String? = null,
    val accessed: String? = null,
)

@Serializable
data class FileInfo(
    val id: String,
    val fileName: String,
    val size: Long,
    val fileType: String,
    val sha256: String? = null,
    val preview: String? = null,
    val metadata: FileMetadata? = null,
)

@Serializable
data class PrepareUploadRequest(
    val info: PeerInfo,
    val files: Map<String, FileInfo>,
)

@Serializable
data class PrepareUploadResponse(
    val sessionId: String,
    /** file id → per-file transfer token; only accepted files. */
    val files: Map<String, String>,
    /**
     * Flit↔Flit extension: multi-socket upload capability. Present only when
     * the sender signaled `X-Flit-Multi` in its prepare-upload request; the
     * value is the receiver's suggested chunk size in bytes. Chunks are then
     * POSTed to the normal upload route with a `Content-Range` header.
     * v2.2 peers never see this (receiver only adds it on the header, and
     * responses omit nulls) — its absence on a peer's answer is the graceful
     * fallback to single-stream sending.
     */
    val multipart: Long? = null,
)

/** What the receiver still holds of a previously interrupted file. */
@Serializable
data class PartialInfo(val size: Long)

/**
 * Multicast announcement wire shape: port + protocol are always present here
 * (never default-omitted), unlike register/info responses.
 */
@Serializable
data class AnnounceMessage(
    val alias: String,
    val version: String,
    val deviceModel: String? = null,
    val deviceType: String? = null,
    val fingerprint: String,
    val port: Int,
    val protocol: String,
    val download: Boolean = false,
)
