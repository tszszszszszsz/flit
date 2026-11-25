package app.flit.share.protocol.model

import kotlinx.serialization.Serializable

/**
 * Flit-only panic alert — an additive wire type under `/api/flit/v1`; no
 * v2.2 route or DTO changes. Receiving it never acts on the recipient's
 * device: it is delivered to the app delegate, which decides what (if
 * anything) a human sees.
 *
 * [sig] is Base64 of a signature over every immutable field — id, senderFp,
 * sentMs, text AND [hops] — see `PanicCore.canonical`. Because hops is signed,
 * a relay that increments it changes the signed bytes and MUST re-sign with
 * its own identity (`PanicCore.forward`); a bumped count with the original
 * signature fails verification.
 */
@Serializable
data class PanicAlert(
    val id: String,
    val senderFp: String,
    val sentMs: Long,
    val text: String,
    val hops: Int = 0,
    val sig: String = "",
    /** Original author, preserved across re-signed forwards for trust gating. */
    val originFp: String = "",
)
