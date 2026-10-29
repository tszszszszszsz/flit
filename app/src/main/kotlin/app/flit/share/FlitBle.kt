package app.flit.share

import java.util.UUID

/**
 * Direct-peer discovery over Bluetooth LE — the engine layer no setting ever
 * names. The brief that lives in docs/transport-bluetooth.md: while an access
 * point exists between devices, Wi-Fi discovery owns the roster and this radio
 * stays dark; the moment it can't (airplane mode with radios hot, hotspot dead),
 * a peer shows up in the same list and files stream the same way.
 *
 * This file holds the pieces that must be true with nothing but bytes and
 * arithmetic, so they test on the JVM — no radio, no Android:
 *
 *  - [buildPayload]/[parse]: the announcement that rides inside the service
 *    data of our own 128-bit service UUID. The fingerprint travels BINARY —
 *    64 hex characters cannot fit a legacy 31-byte advertising record, and
 *    bytes are the honest answer to a wire budget. Extended advertising
 *    carries the full 32-byte fingerprint + alias; the legacy fallback carries
 *    a short fingerprint prefix and no alias (such a row labels itself with
 *    the device address — the roster already does that for shy announces).
 *  - [stance]: the dormancy arbiter. A pure function of the world — access
 *    point up, permissions granted, radio powered, hidden mode — deciding
 *    advertise and/or scan. Turning dark is a decision this returns.
 *
 * Nothing here is user-facing: no strings, no settings, no new vocabulary.
 */
object FlitBle {

    /** Our announce service UUID — random, constant, ours. */
    val SERVICE_UUID: UUID = UUID.fromString("e0e9c7f8-4d2a-4b5e-9f3c-1a7d8b2f4c6e")

    private const val MAGIC: Byte = 0x46 // 'F'

    /** Format version rides the high nibble; the low nibble carries flags. */
    private const val VERSION: Int = 1

    private const val FLAG_TRUNCATED: Int = 0x1

    private const val FULL_FP_BYTES = 32
    private const val TRUNC_FP_BYTES = 8

    private const val ALIAS_CAP = 64

    /**
     * Advertising-record arithmetic, documented so the wire contract survives
     * the next reader: a legacy record holds 31 bytes total. Flags cost 3
     * (header + one flags byte), and a 128-bit UUID service-data structure
     * costs 18 (header + UUID) — leaving exactly 10 for the payload:
     * magic + version + the 8-byte fingerprint prefix. No alias in legacy.
     */
    const val SERVICE_DATA_OVERHEAD = 18
    const val LEGACY_RECORD_BYTES = 31
    const val LEGACY_PAYLOAD_BUDGET = LEGACY_RECORD_BYTES - 3 - SERVICE_DATA_OVERHEAD // == 10

    /** Decoded announcement — fingerprint hex is uppercase, like TlsIdentity's. */
    data class Ann(
        val fingerprint: String,
        val alias: String,
        /** True when the fp is a short prefix (legacy record). */
        val truncated: Boolean,
    )

    /**
     * Encodes the announce. [legacy] shortens the fingerprint to its first 8
     * bytes and drops the alias so the service-data structure still fits a
     * legacy record. [fingerprint] is the 64-hex wire identity; anything else
     * is refused — an announce shorter than the format is a caller bug, never
     * a wire reality.
     */
    fun buildPayload(alias: String, fingerprint: String, legacy: Boolean): ByteArray? {
        val hex = fingerprint.replace(":", "")
        if (hex.length != FULL_FP_BYTES * 2) return null
        val fp = hexToBytesOrNull(hex) ?: return null
        val fpLen = if (legacy) TRUNC_FP_BYTES else FULL_FP_BYTES
        val aliasBytes = if (legacy) ByteArray(0) else clipUtf8(alias, ALIAS_CAP) ?: ByteArray(0)
        val out = ByteArray(2 + fpLen + aliasBytes.size)
        out[0] = MAGIC
        out[1] = (((VERSION shl 4) or (if (legacy) FLAG_TRUNCATED else 0)) and 0xFF).toByte()
        System.arraycopy(fp, 0, out, 2, fpLen)
        System.arraycopy(aliasBytes, 0, out, 2 + fpLen, aliasBytes.size)
        return out
    }

    /** Decodes a service-data payload; null for anything not ours. */
    fun parse(bytes: ByteArray?): Ann? {
        if (bytes == null) return null
        if (bytes.size < 2 || bytes[0] != MAGIC) return null
        val verb = bytes[1].toInt() and 0xFF
        val version = (verb shr 4) and 0xF
        if (version != VERSION) return null
        val truncated = (verb and FLAG_TRUNCATED) != 0
        val fpLen = if (truncated) TRUNC_FP_BYTES else FULL_FP_BYTES
        if (bytes.size < 2 + fpLen) return null
        val fp = bytesToHex(bytes, 2, fpLen)
        val alias = if (bytes.size > 2 + fpLen) {
            String(bytes, 2 + fpLen, bytes.size - 2 - fpLen, Charsets.UTF_8)
        } else ""
        return Ann(fingerprint = fp, alias = alias, truncated = truncated)
    }

    /**
     * Is an announce from this device itself — the roster's self-ghost check
     * in both record kinds: exact fingerprint, or the legacy prefix of it.
     */
    fun isSelf(fpHex: String, truncated: Boolean, selfFp: String?): Boolean {
        if (selfFp == null) return false
        val self = selfFp.replace(":", "").uppercase()
        val fp = fpHex.uppercase()
        return if (truncated) self.startsWith(fp) else self == fp
    }

    /** One tick of radio intent — what the engine's flip loop feeds and applies. */
    data class Stance(
        val advertise: Boolean,
        val scan: Boolean,
    )

    /**
     * How fresh an address-bearing touch (UDP announce, manual probe) must be
     * to own its roster row over the radio: transports rank silently, so a
     * direct announce may only fill lapses — it can never rewrite a Wi-Fi
     * row's address to something without a socket. The rule of the one-list
     * roster the design doc pins.
     */
    const val FRESH_TOUCH_MS = 10_000L

    /** True when the radio may write the row: the last touch is a lapse. */
    fun rowIsFree(lastTouchMs: Long?, now: Long): Boolean =
        lastTouchMs == null || now - lastTouchMs >= FRESH_TOUCH_MS

    /**
     * Decides the next stance. The rule is short on purpose: any reason the
     * usual Wi-Fi path can't carry discovery turns this radio on; the exact
     * reverse holds when the access point is back. Hidden mode keeps listen
     * but never announces — the radio stays invisible, like UDP hidden mode.
     */
    fun stance(
        apUp: Boolean,
        perms: Boolean,
        radioOn: Boolean,
        hidden: Boolean,
    ): Stance = when {
        apUp -> Stance(advertise = false, scan = false)
        !radioOn || !perms -> Stance(advertise = false, scan = false)
        hidden -> Stance(advertise = false, scan = true)
        else -> Stance(advertise = true, scan = true)
    }

    /**
     * Clips a UTF-8 string to the byte budget without splitting a codepoint,
     * dropping the alias whole when even one character doesn't fit.
     */
    internal fun clipUtf8(s: String, budget: Int): ByteArray? {
        val raw = s.toByteArray(Charsets.UTF_8)
        if (raw.size <= budget) return raw
        var keep = budget
        while (keep > 0 && (raw[keep].toInt() and 0xC0) == 0x80) keep-- // inside a multibyte char
        if (keep == 0) return null
        return raw.copyOf(keep)
    }

    private fun hexToBytesOrNull(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun bytesToHex(bytes: ByteArray, off: Int, len: Int): String {
        val hex = "0123456789ABCDEF"
        val sb = StringBuilder(len * 2)
        for (i in off until off + len) {
            val b = bytes[i].toInt() and 0xFF
            sb.append(hex[b shr 4]).append(hex[b and 0xF])
        }
        return sb.toString()
    }
}
