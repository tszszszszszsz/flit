package app.flit.share

import java.security.MessageDigest

/**
 * The prepare-upload trust decision, as a JVM-pure function of its arguments.
 *
 * The sender claims a fingerprint in `peerInfo.fingerprint`; the receiver
 * independently computes the SHA-256 of the DER certificate carried in the
 * prepare header and passes that digest in as [presentedFp]. TrustGate binds
 * the two, then consults the local stores the caller supplies. It owns no
 * clock, no socket, no file, no pin map: it never writes to any store and it
 * never puts certificate bytes into a [Result]. The only material it returns
 * is a 12-character short code suitable for a log line.
 *
 * Decision order, when at least one gate is enabled:
 * 1. No usable presented digest -> [Reason.CertAbsent].
 * 2. Presented digest != declared fingerprint -> [Reason.CertMismatch].
 * 3. `onlyTrusted` on and the fingerprint is not pinned -> [Reason.NotTrusted].
 * 4. `qrOnly` on and the fingerprint is not QR-known -> [Reason.QrUnknown].
 * Otherwise the offer is accepted. With both gates off every offer is
 * accepted, including one with no certificate at all: v2.2 senders stay
 * compatible and nothing is consulted.
 *
 * Fingerprints are compared as bounded uppercase hex (surrounding whitespace
 * and colon separators ignored), the same canonical form the protocol uses
 * on the wire. Any malformed, oversized or hostile input is refused without
 * throwing, and a failing store callback fails closed instead of granting
 * trust. Repeated calls with equal arguments give equal results; no state is
 * kept between calls, so a rotated certificate or a restarted app is judged
 * only by the stores handed in for that call.
 */
object TrustGate {
    /** Canonical protocol fingerprints are 64 uppercase hex chars (SHA-256). */
    const val MIN_FINGERPRINT_CHARS = 64

    /** Longer digests (e.g. SHA-512, 128 chars) are accepted too. */
    const val MAX_FINGERPRINT_CHARS = 128

    /** Raw bound applied before trimming or colon removal. */
    const val MAX_INPUT_CHARS = 256

    /** Length of [Result.code], the only identity material a result carries. */
    const val CODE_CHARS = 12

    /** Domain tag keeps short codes distinct from other digests in the app. */
    const val DOMAIN = "FLITGATE1"

    /** Why a prepare offer was declined. Null on an accepted offer. */
    enum class Reason { CertAbsent, CertMismatch, NotTrusted, QrUnknown }

    /** Runtime gates. Both default off, so an old caller keeps old behavior. */
    data class Config(
        val onlyTrusted: Boolean = false,
        val qrOnly: Boolean = false,
    )

    /** Read-only view of the persisted trust lists. No write is ever attempted. */
    interface Stores {
        fun pinned(fp: String): Boolean
        fun known(fp: String): Boolean
    }

    /**
     * One verdict. [code] is always exactly [CODE_CHARS] hex characters: the
     * leading 12 of a usable declared fingerprint, otherwise a domain-tagged
     * digest of the bounded input. It never contains certificate bytes, raw
     * input text, whitespace or newlines.
     */
    data class Result(
        val accept: Boolean,
        val reason: Reason? = null,
        val code: String,
    )

    /**
     * Decides one prepare offer. Total on every value of [declaredFp] and
     * [presentedFp]: null, blank, non-hex, oversized and hostile strings all
     * produce a bounded result rather than an exception.
     */
    fun evaluate(
        declaredFp: String?,
        presentedFp: String?,
        config: Config,
        stores: Stores,
    ): Result {
        val declared = canonical(declaredFp)
        val code = codeFor(declaredFp, declared)

        if (!config.onlyTrusted && !config.qrOnly) return Result(true, null, code)

        val presented = canonical(presentedFp)
        if (presented == null) return refused(Reason.CertAbsent, code)
        if (declared == null || declared != presented) return refused(Reason.CertMismatch, code)

        if (config.onlyTrusted && !allowed { stores.pinned(declared) }) {
            return refused(Reason.NotTrusted, code)
        }
        if (config.qrOnly && !allowed { stores.known(declared) }) {
            return refused(Reason.QrUnknown, code)
        }
        return Result(true, null, code)
    }

    private fun refused(reason: Reason, code: String) = Result(false, reason, code)

    /** A broken or throwing store is never allowed to grant trust. */
    private inline fun allowed(query: () -> Boolean): Boolean =
        runCatching(query).getOrDefault(false)

    /** Normalizes a wire fingerprint, or returns null when it is unusable. */
    private fun canonical(input: String?): String? {
        if (input == null || input.length > MAX_INPUT_CHARS) return null
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val hex = buildString(trimmed.length) {
            for (c in trimmed) if (c != ':') append(c)
        }
        if (hex.length < MIN_FINGERPRINT_CHARS || hex.length > MAX_FINGERPRINT_CHARS) return null
        if (hex.length % 2 != 0) return null
        for (c in hex) if (!isHex(c)) return null
        return hex.uppercase()
    }

    /** A stable 12-char label; unusable input is hashed, never echoed. */
    private fun codeFor(raw: String?, canonical: String?): String {
        if (canonical != null) return canonical.take(CODE_CHARS)
        val digest = runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(DOMAIN.toByteArray(Charsets.US_ASCII))
            md.update(0)
            md.update((raw ?: "").take(MAX_INPUT_CHARS).toByteArray(Charsets.UTF_8))
            md.digest()
        }.getOrNull() ?: return "0".repeat(CODE_CHARS)
        return buildString(CODE_CHARS) {
            for (b in digest) {
                append(HEX[(b.toInt() ushr 4) and 0x0F])
                if (length == CODE_CHARS) break
                append(HEX[b.toInt() and 0x0F])
                if (length == CODE_CHARS) break
            }
        }
    }

    private fun isHex(c: Char): Boolean =
        c in '0'..'9' || c in 'A'..'F' || c in 'a'..'f'

    private const val HEX = "0123456789ABCDEF"
}
