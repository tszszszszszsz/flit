package app.flit.share

import java.net.InetAddress

/**
 * The sweep's subnet math, pure and keyed: a multicast-blocked access
 * point swallows discovery announces while both phones sit on the same
 * LAN, fully reachable to each other's [protocol v2.2][1] server. So
 * when discovery has gone silent, we walk the range and knock on the
 * serving port — a TCP dail that refuses or answers costs the same and
 * invents nothing: only a machine running the server becomes a row.
 *
 * [1]: https://localsend.github.io/spec
 */
object FlitSubnet {

    /**
     * Addresses to knock on for a v4 network the device itself sits on,
     * ascending, every usable host of the range except us and the broadcast.
     * IPv6 ranges are not a neighborhood we walk (link-local sweeping is
     * its own animal), a prefix outside 8..32 walks nothing, and a list of
     * knocks beyond [cap] is refused whole — a /16 is not a neighborhood,
     * and crawling an entire facility's lease block would be rude. A tiny
     * range walks whichever neighbors it has, self included or not: a
     * 2-phone hotspot's /30 is exactly one knock, and that knock is real.
     */
    fun hosts(ip: String, prefix: Int, cap: Int = 1024): List<String> {
        val bytes = runCatching { InetAddress.getByName(ip).address }.getOrNull()
            ?: return emptyList()
        if (bytes.size != 4 || prefix !in 8..32) return emptyList()
        val self = pack(bytes)
        val mask = maskFor(prefix)
        val network = self and mask
        val start = network + 1
        val endExclusive = network or mask.inv() // the broadcast address
        // Admission BEFORE materialization: the walk may hold our own
        // address (hence cap + 1 as the ranged upper bound), Long math so
        // a giant cap cannot wrap — a /8 is refused here, never allocated.
        if (endExclusive.toLong() - start > cap + 1L) return emptyList()
        val knocks = (start until endExclusive).map { unpack(it) }.filterNot { it == ip }
        if (knocks.isEmpty() || knocks.size > cap) return emptyList()
        return knocks
    }

    private fun pack(b: ByteArray): Int =
        ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)

    private fun unpack(v: Int): String =
        listOf(v ushr 24, v ushr 16, v ushr 8, v).joinToString(".") { (it and 0xFF).toString() }

    private fun maskFor(prefix: Int): Int = -(1 shl (32 - prefix))

    /** Log-shape of a walk: `192.168.100.0/24`. Pure integer math, no DNS. */
    fun label(ip: String, prefix: Int): String {
        val bytes = runCatching { InetAddress.getByName(ip).address }.getOrNull()
            ?: return "($ip/$prefix)"
        if (bytes.size != 4) return "($ip/$prefix)"
        val network = pack(bytes) and maskFor(prefix)
        return "${unpack(network)}/$prefix"
    }
}
