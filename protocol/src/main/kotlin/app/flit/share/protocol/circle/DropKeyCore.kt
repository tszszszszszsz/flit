package app.flit.share.protocol.circle

import app.flit.share.protocol.model.WireJson
import app.flit.share.protocol.tls.TlsIdentity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * Pure JVM core for one content key per Circle file drop. The core mints a
 * fresh symmetric key and IV, wraps the key to each current member's RSA
 * certificate, seals bytes with AES-GCM and encodes the keyed `file`
 * announcement body. It holds no key cache, no sealed bytes and no drop
 * state, and never writes to the [TlsIdentity] pin map.
 *
 * The sealed bytes are opaque to the courier envelope and the QR frame
 * codec; this object adds no route, frame, carrier or signature primitive.
 */
object DropKeyCore {
    /** Length of a freshly minted AES-256 content key. */
    const val KEY_BYTES = 32

    /** GCM nonce length; the sealed form carries this prefix verbatim. */
    const val IV_BYTES = 12

    /** GCM authentication tag length, in bytes (128-bit tag). */
    const val TAG_BYTES = 16

    /** Hard cap on wrapped entries in one keyed body. */
    const val MAX_WRAPS = 32

    /** Base64 cap per wrapped value (a 2048-bit RSA wrap is 344 chars). */
    const val MAX_WRAPPED_CHARS = 512

    /** Longest accepted landing file name, in UTF-16 code units. */
    const val MAX_NAME_CHARS = 256

    /**
     * Windows device names: reserved even when lower-cased or given an
     * extension, so a landing name whose stem matches one is refused.
     */
    private val RESERVED_NAMES = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
        "CONIN$", "CONOUT$",
    )

    /** AAD domain tag, length-prefixed by the [CircleCore.canonical] field rule. */
    const val DOMAIN = "FLITDROP1"

    /** One minted drop key and its sealing IV. Neither is derived from group state. */
    data class Key(val bytes: ByteArray, val iv: ByteArray)

    /** Typed form of the keyed `file` announcement body. */
    data class KeyedFile(
        val name: String,
        val size: Long,
        val sha256: String,
        val keyGen: Int,
        val cipherSha: String,
        val iv: String,
        val wraps: Map<String, String>,
    )

    private val random = SecureRandom()

    /**
     * Pure gate for the single file name a keyed drop may use as its landing
     * destination. Receivers join this name to a directory verbatim
     * (`resolveSibling` in the CLI, `File(parent, name)` in the app), so every
     * form that could select another directory, a device or a disguised
     * sibling is refused. Total on hostile input: no path resolution, no
     * store, no exception.
     *
     * A name is accepted only when it is non-blank, at most [MAX_NAME_CHARS],
     * has no separator, drive/stream marker or ISO control character, no `.`
     * or `..` segment, no trailing dot or space, and is not a Windows reserved
     * device name (with or without an extension).
     */
    fun safeLandingName(name: String): Boolean {
        if (name.isBlank() || name.length > MAX_NAME_CHARS) return false
        if (name.any { c ->
            c == '/' || c == '\\' || c == ':' || c.code < 0x20 || c.code in 0x7F..0x9F
        }) return false
        if (name == "." || name == ".." || name.contains("..")) return false
        if (name.endsWith('.') || name.endsWith(' ')) return false
        return name.substringBefore('.').uppercase(Locale.ROOT) !in RESERVED_NAMES
    }

    /**
     * Draws a fresh key and IV from [SecureRandom]. Neither value comes from
     * the group key, the QR invite or the file hash, so two mints never match.
     */
    fun mint(): Key {
        val bytes = ByteArray(KEY_BYTES)
        val iv = ByteArray(IV_BYTES)
        random.nextBytes(bytes)
        random.nextBytes(iv)
        return Key(bytes, iv)
    }

    /**
     * Wraps [key] once per recipient with RSA-OAEP/SHA-256 and returns the
     * Base64 blobs keyed by uppercase certificate fingerprint. A roster above
     * [MAX_WRAPS] is refused rather than trimmed. A fingerprint whose
     * certificate cannot encrypt (for example a non-RSA key) gets no entry;
     * there is no group-key fallback or escrow.
     *
     * This primitive trusts the caller's map key as the fingerprint. When the
     * key is not already known to be [TlsIdentity.fingerprintOf] of its cert,
     * use [wrapVerified] instead so a mislabelled roster cannot seal a key.
     */
    fun wrap(key: ByteArray, recipients: Map<String, X509Certificate>): Map<String, String> {
        require(recipients.size <= MAX_WRAPS) { "recipient roster exceeds MAX_WRAPS" }
        val out = LinkedHashMap<String, String>()
        for ((fingerprint, cert) in recipients) {
            val fp = fingerprint.trim().uppercase()
            val wrapped = runCatching {
                val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
                cipher.init(Cipher.ENCRYPT_MODE, cert.publicKey, oaepSpec())
                Base64.getEncoder().encodeToString(cipher.doFinal(key))
            }.getOrNull() ?: continue
            out[fp] = wrapped
        }
        return out
    }

    /**
     * Explicitly bound variant of [wrap]: every map key must be the SHA-256 DER
     * fingerprint of its own certificate ([TlsIdentity.fingerprintOf]), not just
     * a label the caller claims. A misbound entry is refused with no wrap at
     * all — a *total* refusal (a mismatch never throws) and a *bounded* one
     * (at most [MAX_WRAPS] entries, each check O(1)). Correctly bound inputs
     * keep exactly the [wrap] behaviour and wire form.
     */
    fun wrapVerified(key: ByteArray, recipients: Map<String, X509Certificate>): Map<String, String> {
        require(recipients.size <= MAX_WRAPS) { "recipient roster exceeds MAX_WRAPS" }
        val verified = LinkedHashMap<String, X509Certificate>()
        for ((fingerprint, cert) in recipients) {
            val claimed = fingerprint.trim().uppercase()
            val actual = runCatching { TlsIdentity.fingerprintOf(cert) }.getOrNull() ?: continue
            if (!actual.equals(claimed, ignoreCase = true)) continue
            verified[claimed] = cert
        }
        return wrap(key, verified)
    }

    /**
     * Decrypts the blob stored for this device's fingerprint with the local
     * private key. A missing fingerprint, a wrong identity or a malformed blob
     * returns null without throwing and leaves the pin map untouched.
     */
    fun unwrap(wrapped: Map<String, String>, identity: TlsIdentity): ByteArray? {
        val blob = wrapped[identity.fingerprint] ?: wrapped[identity.fingerprint.lowercase()]
            ?: return null
        val cipher = runCatching { Base64.getDecoder().decode(blob) }.getOrNull() ?: return null
        return identity.decryptOaepSha256(cipher)
    }

    /**
     * AES-256-GCM seal with the key's IV and a length-prefixed [DOMAIN] AAD.
     * The result is exactly `IV || ciphertext || tag`.
     */
    fun seal(key: Key, plaintext: ByteArray): ByteArray {
        require(key.bytes.size == KEY_BYTES) { "content key must be $KEY_BYTES bytes" }
        require(key.iv.size == IV_BYTES) { "IV must be $IV_BYTES bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.bytes, "AES"), gcmSpec(key.iv))
        cipher.updateAAD(aad())
        val body = cipher.doFinal(plaintext)
        val out = ByteArray(IV_BYTES + body.size)
        System.arraycopy(key.iv, 0, out, 0, IV_BYTES)
        System.arraycopy(body, 0, out, IV_BYTES, body.size)
        return out
    }

    /**
     * Opens an `IV || ciphertext || tag` value. A short value, a bad key or any
     * GCM authentication failure returns null; the caller keeps the sealed
     * bytes for inspection.
     */
    fun open(key: ByteArray, sealed: ByteArray): ByteArray? {
        if (sealed.size < IV_BYTES + TAG_BYTES) return null
        val iv = sealed.copyOfRange(0, IV_BYTES)
        val body = sealed.copyOfRange(IV_BYTES, sealed.size)
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), gcmSpec(iv))
            cipher.updateAAD(aad())
            cipher.doFinal(body)
        }.getOrNull()
    }

    /**
     * Builds the exact keyed `file` body accepted by [CircleCore.validFileBody].
     * An unsafe landing name is refused here too, so a sender never mints an
     * event that [parseFileKeyBody] would refuse on arrival.
     */
    fun keyedFileBody(v: KeyedFile): String {
        require(safeLandingName(v.name)) { "unsafe landing name" }
        return buildJsonObject {
            put("name", JsonPrimitive(v.name))
            put("size", JsonPrimitive(v.size))
            put("sha256", JsonPrimitive(v.sha256))
            put("keyGen", JsonPrimitive(v.keyGen))
            put("cipherSha", JsonPrimitive(v.cipherSha))
            put("iv", JsonPrimitive(v.iv))
            put("wraps", buildJsonObject {
                v.wraps.forEach { (fp, wrapped) -> put(fp, JsonPrimitive(wrapped)) }
            })
        }.toString()
    }

    /**
     * Parses a keyed `file` body, or returns null when [CircleCore.validFileBody]
     * refuses it, when it is not the keyed form, or when its `name` fails
     * [safeLandingName]. No partial value is returned. Refusing the name here
     * is what stops a signed keyed event from selecting a landing path outside
     * the receiver's directory: callers only ever see a safe single file name.
     */
    fun parseFileKeyBody(body: String): KeyedFile? {
        if (!CircleCore.validFileBody(body)) return null
        return runCatching {
            val obj = parseBody(body) ?: return null
            if (obj.keys != CircleCore.KEYED_FILE_BODY_KEYS) return null
            val name = obj.string("name") ?: return null
            if (!safeLandingName(name)) return null
            val sha256 = obj.string("sha256") ?: return null
            val size = obj.number("size")?.toLongOrNull() ?: return null
            val keyGen = obj.number("keyGen")?.toIntOrNull() ?: return null
            val cipherSha = obj.string("cipherSha") ?: return null
            val iv = obj.string("iv") ?: return null
            val wrapsObj = obj["wraps"] as? JsonObject ?: return null
            val wraps = LinkedHashMap<String, String>()
            for ((fp, value) in wrapsObj) {
                val b64 = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                wraps[fp] = b64
            }
            KeyedFile(name, size, sha256, keyGen, cipherSha, iv, wraps)
        }.getOrNull()
    }

    /** Provenance identifier published for one signed `file` event generation. */
    fun keyId(dropId: String, keyGen: Int): String = "$dropId:$keyGen"

    private fun gcmSpec(iv: ByteArray) = GCMParameterSpec(TAG_BYTES * 8, iv)

    /** The single OAEP configuration, shared by wrap and the identity decrypt. */
    private fun oaepSpec() = OAEPParameterSpec(
        "SHA-256",
        "MGF1",
        MGF1ParameterSpec.SHA256,
        PSource.PSpecified.DEFAULT,
    )

    /** Four-byte big-endian UTF-8 field, the same rule as [CircleCore.canonical]. */
    private fun aad(): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        val raw = DOMAIN.toByteArray(Charsets.UTF_8)
        out.writeInt(raw.size)
        out.write(raw)
        out.flush()
        return bytes.toByteArray()
    }

    private fun parseBody(body: String): JsonObject? =
        runCatching { WireJson.json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.number(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content
}
