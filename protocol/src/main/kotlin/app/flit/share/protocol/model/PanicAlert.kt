package app.flit.share.protocol.model

import kotlinx.serialization.Serializable

/**
 * Flit-only panic alert — an additive wire type under `/api/flit/v1`; no
 * v2.2 route or DTO changes. Receiving it never acts on the recipient's
 * device: it is delivered to the app delegate, which decides what (if
 * anything) a human sees.
 *
 * [sig] is Base64 of a signature over [PanicAlert]'s immutable core fields
 * (id, senderFp, sentMs, text) — see `PanicCore.canonical`. [hops] is
 * deliberately outside the signed bytes so a relay can increment it without
 * breaking the original sender's signature.
 */
@Serializable
data class PanicAlert(
    val id: String,
    val senderFp: String,
    val sentMs: Long,
    val text: String,
    val hops: Int = 0,
    val sig: String = "",
)
