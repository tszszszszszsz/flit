package app.flit.share.protocol

import app.flit.share.protocol.model.FileInfo
import java.security.MessageDigest

/**
 * LocalSend wire protocol v2.2 defaults.
 *
 * Reference: https://github.com/localsend/protocol (sections 1–7).
 * Clean-room implementation — no upstream code, spec only.
 */
object Protocol {
    /** Protocol version announced on the wire (major.minor). */
    const val VERSION: String = "2.2"

    /** Default TCP port for the HTTP transfer server. */
    const val DEFAULT_PORT: Int = 53317

    /** Default UDP multicast announcement port. */
    const val MULTICAST_PORT: Int = DEFAULT_PORT

    /** Multicast group. Some Android devices reject anything outside 224.0.0.0/24. */
    const val MULTICAST_GROUP: String = "224.0.0.167"

    const val API_ROOT: String = "/api/localsend/v2"

    const val PATH_INFO: String = "$API_ROOT/info"
    const val PATH_REGISTER: String = "$API_ROOT/register"
    const val PATH_PREPARE_UPLOAD: String = "$API_ROOT/prepare-upload"
    const val PATH_UPLOAD: String = "$API_ROOT/upload"
    const val PATH_CANCEL: String = "$API_ROOT/cancel"

    // Flit extension (Flit↔Flit only; v2.2 wire stays untouched, other
    // LocalSend-compatible apps simply never call these).
    const val FLIT_ROOT: String = "/api/flit/v1"
    const val PATH_PARTIAL: String = "$FLIT_ROOT/partial"

    /**
     * Identity of a resumable file across sessions: name + size + content
     * hash. Nobody ever uploads against a token they didn't first offer to
     * us in prepare (the part registry is verified against the live
     * session's FileInfo), so this value only scans; it doesn't grant.
     */
    fun resumeToken(info: FileInfo): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(info.fileName.toByteArray(Charsets.UTF_8))
        md.update(byteArrayOf(0))
        md.update(info.size.toString().toByteArray(Charsets.US_ASCII))
        md.update(byteArrayOf(0))
        md.update((info.sha256 ?: "").toByteArray(Charsets.US_ASCII))
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        return hex.take(32)
    }
}
