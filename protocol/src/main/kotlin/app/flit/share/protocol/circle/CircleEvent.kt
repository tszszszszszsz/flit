package app.flit.share.protocol.circle

import kotlinx.serialization.Serializable

/**
 * One immutable, signed entry in a Circle's append-only event log.
 *
 * The signature covers every field except [sig] and [cert] (see
 * `CircleCore.canonical`). [cert] is safe to leave out because its own SHA-256
 * DER must equal [authorFp] before `CircleCore.verify` accepts it: a swapped
 * or stripped certificate cannot borrow another device's signature. [type]
 * is one of the ten
 * verbs below; [body] is a small JSON object whose shape depends on [type]:
 *
 * ```
 * message  {"text":"..."}
 * file     {"name":"...","size":123,"sha256":"..."}
 * poll     {"pollId":"...","question":"...","options":["a","b"]}
 * vote     {"pollId":"...","option":1}  |  {"kickId":"...","approve":true}
 * join     {"alias":"..."}
 * leave    {}
 * kick     {"kickId":"...","targetFp":"...","mode":"admin"|"vote","approve":true}
 * role     {"targetFp":"...","role":"ADMIN"|"MEMBER"}
 * rekey    {"keyB64":"...","gen":1}
 * parcel   {"toFp":"...","envelope":"<base64 envelope bytes>"}
 * ```
 *
 * The event never carries file bytes — the sender delivers them separately
 * with `ProtocolClient.sendFiles` (the direct per-member transfer path), one peer at a
 * time, and the receiver lands them hash-exact. A [FILE] body is exactly
 * `{name,size,sha256}`: it never carries the sender's local spool path, a
 * queue token, or any other key, so recipients locate the bytes by hash,
 * never by a path published in a signed, fanned-out event.
 * `CircleCore.validFileBody` enforces that shape. The event DOES carry a
 * group key inside a `rekey` body (`keyB64`), delivered only to
 * an already-joined member over the pinned session; a chat/poll/file body
 * never holds one. It also carries the signer's certificate so a receiver
 * that has never met the author can pin it and verify the signature;
 * [authorFp] must equal that certificate's fingerprint.
 */
@Serializable
data class CircleEvent(
    val id: String,
    val circleId: String,
    val authorFp: String,
    val lamport: Long,
    val type: String,
    val body: String,
    val sig: String,
    val createdMs: Long,
    /** Base64 DER of the author's cert; pins on first sight, never a key. */
    val cert: String = "",
) {
    companion object {
        const val MESSAGE = "message"
        const val FILE = "file"
        const val POLL = "poll"
        const val VOTE = "vote"
        const val JOIN = "join"
        const val LEAVE = "leave"
        const val KICK = "kick"
        const val ROLE = "role"
        const val REKEY = "rekey"
        const val PARCEL = "parcel"

        /** The only types a well-formed log may contain. */
        val TYPES: Set<String> = setOf(MESSAGE, FILE, POLL, VOTE, JOIN, LEAVE, KICK, ROLE, REKEY, PARCEL)
    }
}
