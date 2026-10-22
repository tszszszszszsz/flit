package app.flit.share.protocol

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
}
