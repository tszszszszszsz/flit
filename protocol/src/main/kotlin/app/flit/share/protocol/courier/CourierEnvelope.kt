package app.flit.share.protocol.courier

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.model.WireJson
import app.flit.share.protocol.tls.TlsIdentity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.Key
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import java.security.spec.MGF1ParameterSpec

/** The tenth Circle event verb — an opaque, signed courier envelope in its body. */
const val PARCEL = "parcel"

/** The exact, closed key set of a [PARCEL] body. An extra key is refused. */
val PARCEL_BODY_KEYS: Set<String> = setOf("toFp", "envelope")

/** Base64 cap for the encoded envelope placed in the parcel body (about 9216 raw bytes). */
const val MAX_ENVELOPE_CHARS = 12 * 1024

/** AES-256 content key length. */
const val KEY_BYTES = 32

/** GCM nonce length. */
const val IV_BYTES = 12

/** The one accepted envelope algorithm. */
const val ALG = "AESGCM+RSAOAEP256"

private const val DOMAIN = "FLITCOURIER1"
private const val GCM_TAG_BITS = 128

/**
 * A signed, recipient-encrypted blob of Circle events carried for an absent
 * member. [sig] and [cert] travel outside the canonical bytes: [cert] is bound
 * to [fromFp] by its own SHA-256 DER before the signature is checked, so a
 * substituted certificate cannot borrow another device's signature.
 *
 * The codec is stateless. The content key exists only inside [seal] and the
 * RSA-wrapped blob; no plaintext, key or private material is persisted here.
 */
data class CourierEnvelope(
    val id: String,
    val circleId: String,
    val fromFp: String,
    val toFp: String,
    val createdMs: Long,
    val alg: String,
    val ivB64: String,
    val cipherB64: String,
    val keyWrapB64: String,
    val sig: String = "",
    val cert: String = "",
) {
    companion object {
        /**
         * Deterministic signing input: a domain tag plus 4-byte big-endian
         * length-prefixed UTF-8 fields, so no delimiter can be smuggled through
         * a field. Covers every field except [CourierEnvelope.sig] and
         * [CourierEnvelope.cert].
         */
        fun canonical(e: CourierEnvelope): ByteArray {
            val bytes = ByteArrayOutputStream()
            val out = DataOutputStream(bytes)
            field(out, DOMAIN)
            field(out, e.id)
            field(out, e.circleId)
            field(out, e.fromFp)
            field(out, e.toFp)
            out.writeLong(e.createdMs)
            field(out, e.alg)
            field(out, e.ivB64)
            field(out, e.cipherB64)
            field(out, e.keyWrapB64)
            out.flush()
            return bytes.toByteArray()
        }

        /**
         * Canonical bytes plus the two excluded fields, Base64-encoded for the
         * `envelope` member of a [PARCEL] body. The caller must keep the result
         * within [MAX_ENVELOPE_CHARS] so the framed body stays under the Circle
         * body cap.
         */
        fun encode(e: CourierEnvelope): String {
            val bytes = ByteArrayOutputStream()
            val out = DataOutputStream(bytes)
            out.write(canonical(e))
            field(out, e.sig)
            field(out, e.cert)
            out.flush()
            return Base64.getEncoder().encodeToString(bytes.toByteArray())
        }

        /**
         * Reverses [encode]. Returns null for a null/blank or oversized input,
         * bad Base64, a truncated or oversized field, a wrong domain tag or any
         * trailing bytes. Semantic checks (algorithm, signature, window) belong
         * to [verify] and [open], not to the byte codec.
         */
        fun decode(raw: String?): CourierEnvelope? {
            if (raw.isNullOrBlank() || raw.length > MAX_ENVELOPE_CHARS) return null
            return runCatching {
                val decoded = Base64.getDecoder().decode(raw)
                val input = DataInputStream(ByteArrayInputStream(decoded))
                if (readField(input, DOMAIN.length) != DOMAIN) return null
                val id = readField(input, CircleCore.MAX_FIELD_CHARS) ?: return null
                val circleId = readField(input, CircleCore.MAX_FIELD_CHARS) ?: return null
                val fromFp = readField(input, CircleCore.MAX_FIELD_CHARS) ?: return null
                val toFp = readField(input, CircleCore.MAX_FIELD_CHARS) ?: return null
                val createdMs = input.readLong()
                val alg = readField(input, CircleCore.MAX_FIELD_CHARS) ?: return null
                val ivB64 = readField(input, MAX_ENVELOPE_CHARS) ?: return null
                val cipherB64 = readField(input, MAX_ENVELOPE_CHARS) ?: return null
                val keyWrapB64 = readField(input, MAX_ENVELOPE_CHARS) ?: return null
                val sig = readField(input, CircleCore.MAX_SIG_CHARS) ?: return null
                val cert = readField(input, CircleCore.MAX_CERT_CHARS) ?: return null
                if (input.available() != 0) return null
                val envelope = CourierEnvelope(
                    id, circleId, fromFp, toFp, createdMs, alg,
                    ivB64, cipherB64, keyWrapB64, sig, cert,
                )
                if (!validShape(envelope)) return null
                envelope
            }.getOrNull()
        }

        /**
         * Seals [events] to [recipientCert] with a fresh content key, signs the
         * canonical bytes with [identity] and returns null when any input is
         * refused: a missing recipient certificate, a sender fingerprint that
         * is not the signer's own, an oversized plaintext or an oversized
         * resulting envelope. No envelope is created on refusal.
         */
        fun seal(
            events: List<CircleEvent>,
            circleId: String,
            fromFp: String,
            toFp: String,
            recipientCert: X509Certificate?,
            identity: TlsIdentity,
            nowMs: Long,
        ): CourierEnvelope? {
            val senderFp = fromFp.trim().uppercase()
            val targetFp = toFp.trim().uppercase()
            val circle = circleId.trim()
            if (recipientCert == null) return null
            if (circle.isBlank() || circle.length > CircleCore.MAX_FIELD_CHARS) return null
            if (senderFp.isBlank() || senderFp.length > CircleCore.MAX_FIELD_CHARS) return null
            if (targetFp.isBlank() || targetFp.length > CircleCore.MAX_FIELD_CHARS) return null
            if (nowMs <= 0L) return null
            if (!identity.fingerprint.equals(senderFp, ignoreCase = true)) return null
            if (events.any { !CircleCore.validShape(it) }) return null

            val plaintext = runCatching { WireJson.encode(events) }.getOrNull() ?: return null
            val plainBytes = plaintext.toByteArray(Charsets.UTF_8)
            if (plainBytes.isEmpty() || plainBytes.size > MAX_ENVELOPE_CHARS * 3 / 4) return null

            val random = SecureRandom()
            val contentKey = ByteArray(KEY_BYTES).also { random.nextBytes(it) }
            val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
            val cipherBytes = runCatching {
                Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.ENCRYPT_MODE, SecretKeySpec(contentKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
                }.doFinal(plainBytes)
            }.getOrNull() ?: return null
            val wrappedKey = runCatching {
                Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
                    init(Cipher.ENCRYPT_MODE, recipientCert.publicKey, oaepSpec())
                }.doFinal(contentKey)
            }.getOrNull() ?: return null

            val unsigned = CourierEnvelope(
                id = UUID.randomUUID().toString(),
                circleId = circle,
                fromFp = senderFp,
                toFp = targetFp,
                createdMs = nowMs,
                alg = ALG,
                ivB64 = Base64.getEncoder().encodeToString(iv),
                cipherB64 = Base64.getEncoder().encodeToString(cipherBytes),
                keyWrapB64 = Base64.getEncoder().encodeToString(wrappedKey),
                sig = "",
                cert = Base64.getEncoder().encodeToString(identity.certificate.encoded),
            )
            val signed = unsigned.copy(sig = identity.sign(canonical(unsigned)))
            if (encode(signed).length > MAX_ENVELOPE_CHARS) return null
            return signed
        }

        /**
         * Signature-only check. The certificate in [CourierEnvelope.cert] is
         * parsed, its own digest is bound to [CourierEnvelope.fromFp], and the
         * signature is verified against that certificate BEFORE it is pinned —
         * a failure never writes a pin. The replay window is applied by [open].
         */
        fun verify(e: CourierEnvelope): Boolean {
            if (!validShape(e) || e.alg != ALG) return false
            if (e.sig.isBlank() || e.sig.length > CircleCore.MAX_SIG_CHARS) return false
            if (e.cert.isBlank() || e.cert.length > CircleCore.MAX_CERT_CHARS) return false
            val cert = parseCert(e.cert) ?: return false
            val fp = runCatching { TlsIdentity.fingerprintOf(cert) }.getOrNull() ?: return false
            if (!fp.equals(e.fromFp, ignoreCase = true)) return false
            if (!TlsIdentity.verifyWithCertificate(cert, canonical(e), e.sig)) return false
            TlsIdentity.pin(cert)
            return true
        }

        /**
         * Verifies, applies the replay/skew window to [CourierEnvelope.createdMs]
         * and opens the blob with [identity]'s private key. Returns null on any
         * refusal: bad signature, wrong recipient, stale/future clock, corrupt
         * wrap or IV, undecodable plaintext or an event that fails Circle's
         * shape gate. This layer owns no replay set and asserts no authorship —
         * `CircleCore.verify` does that at fold time.
         */
        fun open(e: CourierEnvelope, identity: TlsIdentity, nowMs: Long): List<CircleEvent>? {
            if (!verify(e)) return null
            if (!e.toFp.equals(identity.fingerprint, ignoreCase = true)) return null
            if (nowMs <= 0L || nowMs - e.createdMs > CircleCore.REPLAY_WINDOW_MS ||
                e.createdMs - nowMs > CircleCore.CLOCK_SKEW_MS
            ) return null

            return runCatching {
                val iv = Base64.getDecoder().decode(e.ivB64)
                val cipherBytes = Base64.getDecoder().decode(e.cipherB64)
                if (iv.size != IV_BYTES) return null
                val privateKey = identity.keyStore.getKey(identity.alias, identity.secretChars()) as PrivateKey
                val contentKey: Key = Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
                    init(Cipher.DECRYPT_MODE, privateKey, oaepSpec())
                }.doFinal(Base64.getDecoder().decode(e.keyWrapB64)).let { unwrapped ->
                    if (unwrapped.size != KEY_BYTES) return null
                    SecretKeySpec(unwrapped, "AES") as Key
                }
                val plainBytes: ByteArray = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, contentKey, GCMParameterSpec(GCM_TAG_BITS, iv))
                }.doFinal(cipherBytes)
                if (plainBytes.isEmpty() || plainBytes.size > MAX_ENVELOPE_CHARS * 3 / 4) return null
                val events = WireJson.json.decodeFromString<List<CircleEvent>>(String(plainBytes, Charsets.UTF_8))
                if (events.any { !CircleCore.validShape(it) }) return null
                events
            }.getOrNull()
        }

        private fun validShape(e: CourierEnvelope): Boolean =
            e.id.isNotBlank() && e.id.length <= CircleCore.MAX_FIELD_CHARS &&
                e.circleId.isNotBlank() && e.circleId.length <= CircleCore.MAX_FIELD_CHARS &&
                e.fromFp.isNotBlank() && e.fromFp.length <= CircleCore.MAX_FIELD_CHARS &&
                e.toFp.isNotBlank() && e.toFp.length <= CircleCore.MAX_FIELD_CHARS &&
                e.createdMs > 0L &&
                e.alg.isNotBlank() && e.alg.length <= CircleCore.MAX_FIELD_CHARS &&
                e.ivB64.isNotBlank() && e.ivB64.length <= MAX_ENVELOPE_CHARS &&
                e.cipherB64.isNotBlank() && e.cipherB64.length <= MAX_ENVELOPE_CHARS &&
                e.keyWrapB64.isNotBlank() && e.keyWrapB64.length <= MAX_ENVELOPE_CHARS

        private fun parseCert(b64: String): X509Certificate? = runCatching {
            CertificateFactory.getInstance("X.509")
                .generateCertificate(Base64.getDecoder().decode(b64).inputStream()) as? X509Certificate
        }.getOrNull()

        private fun oaepSpec(): OAEPParameterSpec = OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT,
        )

        private fun field(out: DataOutputStream, value: String) {
            val raw = value.toByteArray(Charsets.UTF_8)
            out.writeInt(raw.size)
            out.write(raw)
        }

        private fun readField(input: DataInputStream, maxBytes: Int): String? {
            val length = input.readInt()
            if (length < 0 || length > maxBytes) return null
            val bytes = ByteArray(length)
            input.readFully(bytes)
            return String(bytes, Charsets.UTF_8)
        }
    }
}
