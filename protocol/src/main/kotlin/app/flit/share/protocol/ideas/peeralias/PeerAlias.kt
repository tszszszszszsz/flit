package app.flit.share.protocol.ideas.peeralias

import java.security.MessageDigest

/**
 * A short, stable label for one certificate fingerprint.
 *
 * A [PeerAlias] is built from two independent halves. The [stem] is the
 * leading hex of the fingerprint itself, so a person can compare the label
 * against the fingerprint shown by the app. The [suffix] is a truncated
 * SHA-256 of the whole canonical fingerprint under a fixed domain tag, so a
 * shared leading prefix does not produce a shared label.
 *
 * Nothing here reads a pin map, a roster, a clock, a socket or a file. The
 * same fingerprint always yields the same alias on any JVM, in any order of
 * calls. Every input is checked against a size bound before it is copied or
 * hashed, so hostile strings cannot make this do unbounded work.
 */
object PeerAlias {
    /** Canonical protocol fingerprints are 64 uppercase-hex characters (SHA-256). */
    const val MIN_FINGERPRINT_CHARS = 64

    /** Longer digests (for example SHA-512, 128 chars) are accepted too. */
    const val MAX_FINGERPRINT_CHARS = 128

    /** Raw bound applied before trimming, colon removal or hashing. */
    const val MAX_INPUT_CHARS = 256

    /** Leading hex characters copied from the fingerprint. */
    const val STEM_CHARS = 4

    /** Hex characters taken from the digest; 24 bits of collision margin. */
    const val SUFFIX_CHARS = 6

    /** Visual split between the two halves. */
    const val SEPARATOR = '-'

    /** Fixed total length of every alias text. */
    const val ALIAS_CHARS = STEM_CHARS + 1 + SUFFIX_CHARS

    /** Domain tag keeps these digests distinct from other hashing in the app. */
    const val DOMAIN = "FLITALIAS1"

    private const val HEX = "0123456789ABCDEF"

    /** The two halves of one short label; [text] is the display form. */
    data class Alias(val stem: String, val suffix: String) {
        val text: String get() = "$stem$SEPARATOR$suffix"
        override fun toString(): String = text
    }

    /**
     * Derives the alias for [fingerprint], or null when the input is not a
     * bounded hex fingerprint. Null, blank, odd-length, short, over-long and
     * non-hex inputs are all refused without throwing.
     *
     * Uppercase and lowercase hex are equivalent, surrounding whitespace is
     * ignored, and colon separators are stripped, so the usual written forms
     * of a fingerprint all map to one canonical value.
     */
    fun of(fingerprint: String?): Alias? {
        val canonical = canonical(fingerprint) ?: return null
        return Alias(canonical.take(STEM_CHARS), suffixOf(canonical))
    }

    /** Display form of [of], or null when [fingerprint] is refused. */
    fun text(fingerprint: String?): String? = of(fingerprint)?.text

    /**
     * Shape check only: exactly [ALIAS_CHARS] characters, uppercase or
     * lowercase hex halves and one [SEPARATOR] between them. It does not
     * consult a fingerprint or any stored state.
     */
    fun isValid(text: String?): Boolean {
        if (text == null || text.length != ALIAS_CHARS) return false
        for (i in text.indices) {
            val c = text[i]
            if (i == STEM_CHARS) {
                if (c != SEPARATOR) return false
            } else if (!isHex(c)) {
                return false
            }
        }
        return true
    }

    /**
     * True when [alias] is exactly the alias of [fingerprint]. Comparison is
     * ASCII case-insensitive. An invalid alias or an unusable fingerprint is
     * always false.
     */
    fun matches(fingerprint: String?, alias: String?): Boolean {
        if (alias == null || !isValid(alias)) return false
        val expected = of(fingerprint) ?: return false
        val upper = alias.uppercase()
        return expected.stem == upper.substring(0, STEM_CHARS) &&
            expected.suffix == upper.substring(STEM_CHARS + 1)
    }

    /** Normalizes to uppercase hex, or returns null for anything out of bounds. */
    private fun canonical(fingerprint: String?): String? {
        if (fingerprint == null || fingerprint.length > MAX_INPUT_CHARS) return null
        val trimmed = fingerprint.trim()
        if (trimmed.isEmpty()) return null
        val hex = buildString(trimmed.length) {
            for (c in trimmed) if (c != ':') append(c)
        }
        if (hex.length < MIN_FINGERPRINT_CHARS || hex.length > MAX_FINGERPRINT_CHARS) return null
        if (hex.length % 2 != 0) return null
        for (c in hex) if (!isHex(c)) return null
        return hex.uppercase()
    }

    /** First [SUFFIX_CHARS] hex nibbles of a domain-tagged SHA-256 digest. */
    private fun suffixOf(canonical: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(DOMAIN.toByteArray(Charsets.US_ASCII))
        digest.update(0)
        digest.update(canonical.toByteArray(Charsets.US_ASCII))
        val bytes = digest.digest()
        val count = (SUFFIX_CHARS + 1) / 2
        val out = StringBuilder(SUFFIX_CHARS)
        for (i in 0 until count) {
            val b = bytes[i].toInt() and 0xFF
            out.append(HEX[b ushr 4])
            if (out.length < SUFFIX_CHARS) out.append(HEX[b and 0x0F])
        }
        return out.toString()
    }

    private fun isHex(c: Char): Boolean =
        c in '0'..'9' || c in 'A'..'F' || c in 'a'..'f'
}
