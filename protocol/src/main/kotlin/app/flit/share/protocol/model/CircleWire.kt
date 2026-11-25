package app.flit.share.protocol.model

import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.tls.TlsIdentity
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Flit-only Circle request, additive under `/api/flit/v1`. One shape serves
 * both the join handshake and the event-sync exchange; [action] is
 * [ACTION_JOIN] or [ACTION_SYNC].
 *
 * [invite] is the bearer token from the QR (never a group key). [key] lives
 * only in the response, inside the authenticated TLS session. [events] is the
 * caller's bounded log delta; [body] is spare JSON for future verbs.
 */
@Serializable
data class CircleRequest(
    val action: String,
    val circleId: String,
    val invite: String? = null,
    val memberFp: String = "",
    val events: List<CircleEvent> = emptyList(),
    val body: String? = null,
) {
    companion object {
        const val ACTION_JOIN = "join"
        const val ACTION_SYNC = "sync"
    }
}

/**
 * The answer to a [CircleRequest]. A refused join/sync is still a well-formed
 * 200 response with [ok] false and a human-readable [reason], so a caller can
 * tell "not enabled / bad invite" from a dead carrier.
 *
 * [members] is the list of current member fingerprints (the app owns aliases
 * and roles). [key] is the group key for a successful join, delivered inside
 * the pinned TLS session — never in a QR code or over a blind relay in clear.
 */
@Serializable
data class CircleResponse(
    val ok: Boolean,
    val key: String = "",
    val members: List<String> = emptyList(),
    val events: List<CircleEvent> = emptyList(),
    val reason: String = "",
    /** DER cert of the answering host; joiners pin it to the QR fingerprint. */
    val serverCert: String = "",
    /** Signature over every field except [serverCert] and [sig]. */
    val sig: String = "",
) {
    companion object {
        fun refused(reason: String): CircleResponse = CircleResponse(ok = false, reason = reason)

        /** Deterministic bytes covered by the host signature. */
        fun canonical(r: CircleResponse): ByteArray {
            val bytes = ByteArrayOutputStream()
            val out = DataOutputStream(bytes)
            out.writeUTF("FLITCIRCLE1")
            out.writeBoolean(r.ok)
            out.writeUTF(r.key)
            out.writeInt(r.members.size)
            r.members.forEach { out.writeUTF(it) }
            out.writeInt(r.events.size)
            r.events.forEach {
                out.writeUTF(it.id)
                out.writeUTF(it.circleId)
                out.writeUTF(it.authorFp)
                out.writeLong(it.lamport)
                out.writeUTF(it.type)
                out.writeUTF(it.body)
                out.writeLong(it.createdMs)
            }
            out.writeUTF(r.reason)
            out.flush()
            return bytes.toByteArray()
        }

        fun sign(r: CircleResponse, identity: TlsIdentity): CircleResponse {
            val encoded = Base64.getEncoder().encodeToString(identity.certificate.encoded)
            return r.copy(
                serverCert = encoded,
                sig = identity.sign(canonical(r.copy(serverCert = "", sig = ""))),
            )
        }

        /** Verify signature AND that the signer cert's fingerprint matches its claim. */
        fun verify(r: CircleResponse, expectedHostFp: String? = null): Boolean {
            if (r.sig.isBlank() || r.serverCert.isBlank()) return false
            val cert = runCatching {
                CertificateFactory.getInstance("X.509")
                    .generateCertificate(Base64.getDecoder().decode(r.serverCert).inputStream()) as X509Certificate
            }.getOrNull() ?: return false
            val fp = runCatching { TlsIdentity.fingerprintOf(cert) }.getOrNull() ?: return false
            if (expectedHostFp != null && !fp.equals(expectedHostFp, true)) return false
            val bare = r.copy(serverCert = "", sig = "")
            // Verify against the cert IN THE ANSWER before touching the global
            // pin map — otherwise a forged answer cannot be checked on first
            // contact and pin-after-verify becomes pin-before-verify.
            if (!TlsIdentity.verifyWithCertificate(cert, canonical(bare), r.sig)) return false
            TlsIdentity.pin(cert)
            return true
        }
    }
}
