package app.flit.share.protocol.client

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * One file on the way out. Implementations must return a fresh, independent
 * stream from every [openStream] call — the client re-opens between checksum
 * retries — and never assume the stream position persists between calls.
 */
interface OutgoingFile {
    /** Name announced on the wire; receivers use it (and dedup collisions). */
    val wireName: String

    /** Exact byte count; must agree with what [openStream] yields. */
    val size: Long

    /** MIME type announced as `fileType`. */
    val mime: String

    /** Fresh stream for the transfer pass. Caller closes. */
    fun openStream(): InputStream
}

/** Desktop/JVM adapter for regular paths. */
class PathOutgoing(
    val path: Path,
    override val wireName: String,
    mimeOverride: String? = null,
) : OutgoingFile {
    override val size: Long = Files.size(path)
    override val mime: String =
        mimeOverride ?: runCatching { Files.probeContentType(path) }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: "application/octet-stream"

    override fun openStream(): InputStream = java.io.FileInputStream(path.toFile()).buffered()
}
