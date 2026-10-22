package app.flit.share.protocol.net

import java.net.Inet4Address
import java.net.NetworkInterface

/** Site-local IPv4 candidates, most useful for "discover by scanning" fallbacks. */
fun localIpv4s(): List<String> =
    NetworkInterface.getNetworkInterfaces()?.asSequence()
        ?.filter { it.isUp && !it.isLoopback }
        ?.flatMap { it.inetAddresses.asSequence().filterIsInstance<Inet4Address>() }
        ?.map { it.hostAddress ?: "" }
        ?.filter { it.isNotEmpty() && !it.startsWith("169.254.") }
        ?.toList()
        .orEmpty()
