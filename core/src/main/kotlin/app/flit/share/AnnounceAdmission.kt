package app.flit.share

/**
 * One advertised device, flattened to values that can be judged without a
 * socket. [protocol] is carried for the caller but is deliberately not judged
 * here: the gate decides *whether* an announce may enter the registry, not
 * which transport the peer later uses. [sourceIp] is the datagram's source
 * address as text, never resolved through DNS.
 */
data class AnnounceInfo(
    val fingerprint: String?,
    val alias: String?,
    val port: Int,
    val protocol: String?,
    val sourceIp: String?,
)

/**
 * UDP announce admission as a pure JVM function of its arguments.
 *
 * The listener hands in one decoded announce plus the state it already owns:
 * [ownFp] (this device's identity), [nowMs] (a caller-supplied clock) and
 * [seen] (fingerprint -> last admitted time in the same clock). The gate owns
 * no clock, no socket, no Android API and no mutable state; it never writes to
 * [seen], so a fresh call with the same arguments always gives the same answer.
 * [seen] must be keyed by the announce fingerprint in canonical form (trimmed,
 * uppercased hex); on a `true` verdict the caller is expected to insert that
 * key mapped to [nowMs].
 *
 * Refusals, in order:
 * 1. No usable fingerprint: null, blank, non-hex, not exactly 64 chars after
 *    trimming. Case is normalized, surrounding whitespace is ignored.
 * 2. A `manual@` stub identity, which is a local placeholder, not a device.
 * 3. Our own fingerprint (compared in canonical form), including loopback
 *    self-announces that slipped through the wire-level identity check.
 * 4. A port outside 1..65535.
 * 5. An alias longer than 64 chars. A null alias is treated as empty: only a
 *    maximum is enforced here.
 * 6. A source that is not a plausible on-link neighbor. "On-link" is this
 *    gate's conservative, socket-free approximation: RFC1918 IPv4, IPv4
 *    link-local 169.254/16, IPv6 link-local fe80::/10 and IPv6 unique-local
 *    fc00::/7. Loopback, unspecified, multicast, reserved and global
 *    (off-link) sources are refused, as is any malformed address text.
 * 7. A second announce from the same fingerprint strictly inside 1000ms of
 *    its recorded time. At exactly 1000ms the announce is admitted again.
 * 8. A new fingerprint when the caller's [seen] map is already at [MAX_PEERS].
 *    A refresh of a fingerprint already in the map is still admitted, so a
 *    full registry never freezes the peers it already holds.
 *
 * Every input path is total: nulls, hostile strings, extreme timestamps and a
 * throwing caller map produce a bounded verdict instead of an exception. A map
 * that throws on lookup or size fails closed. This is admission arithmetic,
 * not a security proof; the caller still owns rate limiting on the socket and
 * the trust decision on later requests.
 */
object AnnounceAdmission {
    /** Maximum number of registry entries the caller's [seen] map may hold. */
    const val MAX_PEERS = 256

    private const val FINGERPRINT_CHARS = 64
    private const val MAX_FINGERPRINT_INPUT_CHARS = 256
    private const val MAX_ALIAS_CHARS = 64
    private const val MAX_IP_INPUT_CHARS = 64
    private const val DEBOUNCE_MS = 1_000L
    private const val MANUAL_PREFIX = "manual@"

    /** True iff [fp] is 64 hex characters, ignoring surrounding whitespace. */
    fun validFingerprint(fp: String?): Boolean = canonical(fp) != null

    /** True iff [port] is a usable listening port. */
    fun validPort(port: Int): Boolean = port in 1..65_535

    /** Whether one announce may be entered into the caller's registry. */
    fun admit(
        info: AnnounceInfo,
        ownFp: String,
        nowMs: Long,
        seen: Map<String, Long>,
    ): Boolean {
        // Identity. The raw length guard keeps a hostile string bounded before
        // any trim or scan; canonical() repeats it for its own callers.
        val raw = info.fingerprint
        if (raw == null || raw.length > MAX_FINGERPRINT_INPUT_CHARS || raw.isBlank()) return false
        if (raw.trim().startsWith(MANUAL_PREFIX, ignoreCase = true)) return false
        val fp = canonical(raw) ?: return false
        if (fp == canonical(ownFp)) return false

        // Advertised endpoint and label.
        if (!validPort(info.port)) return false
        val alias = info.alias
        if (alias != null && alias.length > MAX_ALIAS_CHARS) return false
        if (!onLinkSource(info.sourceIp)) return false

        // Replay window and capacity, both read from caller-owned state.
        val last = runCatching { seen[fp] }.getOrElse { return false }
        if (last != null && tooSoon(nowMs, last)) return false
        val size = runCatching { seen.size }.getOrElse { return false }
        if (last == null && size >= MAX_PEERS) return false
        return true
    }

    /** Trimmed, uppercased 64-hex form, or null for any unusable value. */
    private fun canonical(fp: String?): String? {
        if (fp == null || fp.length > MAX_FINGERPRINT_INPUT_CHARS) return null
        val trimmed = fp.trim()
        if (trimmed.length != FINGERPRINT_CHARS) return null
        return buildString(FINGERPRINT_CHARS) {
            for (c in trimmed) {
                when (c) {
                    in '0'..'9' -> append(c)
                    in 'a'..'f' -> append(c - 32) // ASCII lower -> upper, locale-free
                    in 'A'..'F' -> append(c)
                    else -> return null
                }
            }
        }
    }

    /**
     * Duplicate check without Long underflow. A future timestamp is treated as
     * a duplicate (fail closed); arithmetic that wraps means the real gap is
     * enormous, so such a value cannot be a 1000ms replay.
     */
    private fun tooSoon(nowMs: Long, thenMs: Long): Boolean {
        if (thenMs > nowMs) return true
        val diff = nowMs - thenMs
        return diff >= 0 && diff < DEBOUNCE_MS
    }

    /** Pure textual source check; no InetAddress (which may block on DNS). */
    private fun onLinkSource(sourceIp: String?): Boolean {
        if (sourceIp == null) return false
        val raw = sourceIp.trim()
        if (raw.isEmpty() || raw.length > MAX_IP_INPUT_CHARS) return false

        var ip = raw
        if (ip[0] == '[') {
            if (ip.length < 3 || ip[ip.length - 1] != ']') return false
            ip = ip.substring(1, ip.length - 1)
        }
        val zone = ip.indexOf('%')
        if (zone >= 0) ip = ip.substring(0, zone)
        if (ip.isEmpty()) return false

        return if (ip.indexOf(':') >= 0) onLinkIPv6(ip) else onLinkIPv4(ip)
    }

    /** Strict dotted-quad parse, then a conservative on-link whitelist. */
    private fun onLinkIPv4(ip: String): Boolean {
        val parts = ip.split('.', limit = 4)
        if (parts.size != 4) return false
        val octets = IntArray(4)
        for (i in 0..3) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3) return false
            if (part.length > 1 && part[0] == '0') return false
            var value = 0
            for (c in part) {
                if (c !in '0'..'9') return false
                value = value * 10 + (c - '0')
            }
            if (value > 255) return false
            octets[i] = value
        }
        val a = octets[0]
        val b = octets[1]
        return when {
            a == 10 -> true                 // 10/8
            a == 172 && b in 16..31 -> true // 172.16/12
            a == 192 && b == 168 -> true    // 192.168/16
            a == 169 && b == 254 -> true    // 169.254/16 link-local
            else -> false                   // 127/8 loopback, 0/8 unspecified,
                                            // 224/4 multicast, 240/4 reserved,
                                            // and all off-link global unicast
        }
    }

    /** Strict IPv6 parse (compressed form allowed), then the same whitelist. */
    private fun onLinkIPv6(ip: String): Boolean {
        if (ip.length !in 2..45) return false
        val double = ip.indexOf("::")
        if (double >= 0 && ip.indexOf("::", double + 2) >= 0) return false
        val head = if (double >= 0) ip.substring(0, double) else ip
        val tail = if (double >= 0) ip.substring(double + 2) else ""
        val h = hextets(head) ?: return false
        val t = hextets(tail) ?: return false
        val present = h.size + t.size
        val full = if (double < 0) {
            if (present != 8) return false
            h + t
        } else {
            if (present >= 8) return false // :: must compress at least one group
            h + List(8 - present) { 0 } + t
        }
        if (full.all { it == 0 }) return false                            // ::
        if (full.take(7).all { it == 0 } && full[7] == 1) return false    // ::1
        val first = full[0]
        if ((first and 0xFFC0) == 0xFE80) return true // fe80::/10 link-local
        if ((first and 0xFE00) == 0xFC00) return true // fc00::/7 unique-local
        return false                                 // ff00::/8, 2000::/3, ...
    }

    /** One colon-separated IPv6 side, or null if any group is malformed. */
    private fun hextets(s: String): List<Int>? {
        if (s.isEmpty()) return emptyList()
        val out = ArrayList<Int>(8)
        for (part in s.split(':')) {
            if (part.isEmpty() || part.length > 4) return null
            var value = 0
            for (c in part) {
                val digit = when (c) {
                    in '0'..'9' -> c - '0'
                    in 'a'..'f' -> c - 'a' + 10
                    in 'A'..'F' -> c - 'A' + 10
                    else -> return null
                }
                value = value * 16 + digit
            }
            out.add(value)
        }
        return out
    }
}
