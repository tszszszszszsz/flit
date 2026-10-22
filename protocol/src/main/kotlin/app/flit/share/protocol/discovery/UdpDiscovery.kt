package app.flit.share.protocol.discovery

import app.flit.share.protocol.Protocol
import app.flit.share.protocol.model.PeerInfo
import app.flit.share.protocol.model.WireJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v2.2 UDP discovery: periodic multicast announcements on 224.0.0.167:53317.
 * Responses happen over HTTP (`POST /register`), so receiving an announcement
 * always triggers an HTTP register back to the sender.
 */
class UdpDiscovery(
    private val udpPort: Int = Protocol.MULTICAST_PORT,
    private val own: () -> PeerInfo,
    private val onPeerDiscovered: (ip: InetAddress, info: PeerInfo) -> Unit,
    private val registerPoster: suspend (ip: String, port: Int, protocol: String, own: PeerInfo) -> Unit,
) {
    private val group: InetAddress = InetAddress.getByName(Protocol.MULTICAST_GROUP)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopped = AtomicBoolean(false)

    private var socket: MulticastSocket? = null
    private var announceJob: Job? = null
    private var listenJob: Job? = null
    private var joined: List<NetworkInterface> = emptyList()

    private suspend fun postRegister(ip: String, port: Int, protocol: String) {
        registerPoster(ip, port, protocol, own())
    }

    suspend fun start(announceIntervalMillis: Long = ANNOUNCE_INTERVAL) {
        val s = MulticastSocket(null as InetSocketAddress?)
        s.reuseAddress = true
        s.bind(InetSocketAddress(udpPort))
        // Java quirk: `loopbackMode = false` ENABLES loopback delivery (same-host peers).
        s.loopbackMode = false

        val ifs = eligibleNetworkInterfaces()
        joined = ifs
        for (ni in ifs) {
            runCatching { s.joinGroup(InetSocketAddress(group, udpPort), ni) }
        }
        socket = s

        listenJob = scope.launch(Dispatchers.IO) { receiveLoop(s) }
        // double burst on start for slow joiners
        announceOnce()
        delay(START_BURST_DELAY)
        announceOnce()
        announceJob = scope.launch {
            while (isActive) {
                delay(announceIntervalMillis)
                announceOnce()
            }
        }
    }

    fun announceOnce() {
        val s = socket ?: return
        val payload = WireJson.encode(own().sansAnnounce()).toByteArray(Charsets.UTF_8)
        if (joined.isEmpty()) {
            runCatching { s.send(DatagramPacket(payload, payload.size, group, udpPort)) }
            return
        }
        for (ni in joined) {
            runCatching { s.send(DatagramPacket(payload, payload.size, group, udpPort), 1, ni) }
        }
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        scope.coroutineContext[Job]?.cancelChildren()
        socket?.close()
        socket = null
    }

    private suspend fun receiveLoop(s: MulticastSocket) {
        val buf = ByteArray(65507)
        val packet = DatagramPacket(buf, buf.size)
        while (true) {
            runCatching { s.receive(packet) }.onFailure { return } // closed
            if (stopped.get()) return
            val json = String(packet.data, 0, packet.length, Charsets.UTF_8)
            val info = runCatching { WireJson.decode<PeerInfo>(json) }.getOrNull() ?: continue
            if (info.fingerprint == own().fingerprint) continue
            val ip = packet.address ?: continue
            onPeerDiscovered(ip, info)
            if (ip.hostAddress != null && info.port in 1..65535) {
                scope.launch {
                    runCatching { postRegister(ip.hostAddress!!, info.port, info.protocol) }
                }
            }
        }
    }

    companion object {
        const val ANNOUNCE_INTERVAL: Long = 3_000
        const val START_BURST_DELAY: Long = 400

        /** Interfaces worth joining for announcements: up, not loopback, multicast-capable IPv4. */
        fun eligibleNetworkInterfaces(): List<NetworkInterface> =
            NetworkInterface.getNetworkInterfaces()?.asSequence()
                ?.filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
                ?.toList()
                .orEmpty()
    }
}
