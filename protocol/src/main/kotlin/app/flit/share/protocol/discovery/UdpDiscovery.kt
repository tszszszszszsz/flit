package app.flit.share.protocol.discovery

import app.flit.share.protocol.Protocol
import app.flit.share.protocol.model.AnnounceMessage
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
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.Semaphore
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
    /** Explicit unicast announce targets (networks that strip multicast, e.g. containers). */
    private val fallbackPeers: List<java.net.InetSocketAddress> = emptyList(),
    /**
     * Hidden mode: never announce, never register back. We still listen, so
     * announcing peers appear in OUR list — but their rosters never learn
     * about this device, which stays reachable only by address or by
     * initiating a send.
     */
    private val announce: Boolean = true,
    /**
     * Bounds on inbound announces. Defaults are small enough that one LAN
     * speaker cannot drive unbounded discovery callbacks or register jobs.
     * App and harness call sites omit these and keep the defaults.
     */
    private val admissionPolicy: AnnounceAdmission.Policy = AnnounceAdmission.Policy(),
    /** Monotonic milliseconds, injected so admission has no clock dependency. */
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    /** Scope for register jobs; injectable so cancellation before launch is testable. */
    private val scopeFactory: () -> CoroutineScope = { CoroutineScope(SupervisorJob() + Dispatchers.IO) },
) {
    private val group: InetAddress = InetAddress.getByName(Protocol.MULTICAST_GROUP)
    private val scope: CoroutineScope = scopeFactory()
    private val stopped = AtomicBoolean(false)
    private val admission = AnnounceAdmission(nowMillis, admissionPolicy)

    private var socket: MulticastSocket? = null
    /** Plain UDP socket used for all sends (see announceOnce). */
    private var announceSocket: DatagramSocket? = null
    private var announceJob: Job? = null
    private var listenJob: Job? = null
    private var joined: List<NetworkInterface> = emptyList()

    private suspend fun postRegister(ip: String, port: Int, protocol: String) {
        registerPoster(ip, port, protocol, own())
    }

    /**
     * Creates the UDP sockets. Runs BEFORE egress pinning (see FlitEngine): a
     * precaution — netd-marked sockets showed suspicious multicast behavior on
     * this device, and unmarked discovery sockets cost nothing.
     */
    fun create() {
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
        announceSocket = DatagramSocket().apply {
            // connect() forces the route resolution up front — mirrors what a
            // raw `nc -u` does, whose datagrams demonstrably leave this device.
            runCatching { connect(group, udpPort) }
        }
    }

    suspend fun start(announceIntervalMillis: Long = ANNOUNCE_INTERVAL) {
        check(socket != null) { "create() must run before start()" }

        listenJob = scope.launch(Dispatchers.IO) { receiveLoop(socket!!) }
        if (announce) {
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
    }

    fun announceOnce() {
        val s = announceSocket ?: return
        val o = own()
        val payload = WireJson.encode(
            AnnounceMessage(
                alias = o.alias, version = o.version, deviceModel = o.deviceModel,
                deviceType = o.deviceType, fingerprint = o.fingerprint,
                port = o.port, protocol = o.protocol, download = o.download,
            ),
        ).toByteArray(Charsets.UTF_8)
        val packet = DatagramPacket(payload, payload.size, group, udpPort)
        synchronized(this) {
            // Plain UDP socket for sends: identical to a raw `nc -u` on the
            // wire (ephemeral source port, no multicast-socket state). The
            // listener socket stays a MulticastSocket for group membership.
            runCatching { s.send(packet) }.onFailure {
                println("Flit-announce send failed: $it")
            }
            // Unicast fallback: same announce payload, delivered point-to-point.
            for (peer in fallbackPeers) {
                runCatching { s.send(DatagramPacket(payload, payload.size, peer)) }
            }
        }
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        scope.coroutineContext[Job]?.cancelChildren()
        socket?.close()
        socket = null
        announceSocket?.close()
        announceSocket = null
    }

    private suspend fun receiveLoop(s: MulticastSocket) {
        val buf = ByteArray(65507)
        val packet = DatagramPacket(buf, buf.size)
        while (true) {
            runCatching { s.receive(packet) }.onFailure { return } // closed
            if (stopped.get()) return
            dispatchAnnounce(
                String(packet.data, 0, packet.length, Charsets.UTF_8),
                packet.address,
            )
        }
    }

    /**
     * Decode, validate and admit one announce. Split out of the socket loop so
     * the full path (including "no callback on reject") is testable without a
     * multicast socket. Returns true only when [onPeerDiscovered] was called.
     */
    fun dispatchAnnounce(json: String, sourceIp: InetAddress?): Boolean {
        val info = runCatching { WireJson.decode<PeerInfo>(json) }.getOrNull() ?: return false
        val ip = sourceIp ?: return false
        val decision = admission.decide(
            fingerprint = info.fingerprint,
            sourceIp = ip.hostAddress ?: "",
            ownFingerprint = own().fingerprint,
            port = info.port,
            needsRegister = announce,
        )
        if (decision != AnnounceAdmission.Decision.ACCEPT) return false
        try {
            onPeerDiscovered(ip, info)
        } catch (t: Throwable) {
            // No job was launched, so the permit taken at admission is ours.
            if (announce) admission.releaseRegister()
            throw t
        }
        // Hidden mode: the announce itself carries the peer's info, but
        // answering with a register would publish our identity. Listen
        // only — sends to them still reveal us when the user chooses.
        if (announce) {
            val host = ip.hostAddress!!
            if (!scope.isActive) {
                admission.releaseRegister()
                return true
            }
            val entered = AtomicBoolean(false)
            val job = scope.launch {
                entered.set(true)
                try {
                    postRegister(host, info.port, info.protocol)
                } finally {
                    admission.releaseRegister()
                }
            }
            // A job cancelled before its block runs never reaches the inner
            // finally; the completion handler returns the permit in that case.
            job.invokeOnCompletion { if (!entered.get()) admission.releaseRegister() }
        }
        return true
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

/**
 * Admission control for inbound UDP announces: injected clock, fixed state,
 * no network and no platform dependencies.
 *
 * Every check happens before the caller dispatches the peer. Fingerprints must
 * be canonical 64-hex identities (trimmed and uppercased); `manual@` stubs,
 * bad ports, our own fingerprint, a repeat inside the interval, a refresh from
 * a different source IP, a saturated table or source, and a saturated register
 * pool all refuse the announce. Once a fingerprint ages out it can be announced
 * again; the table itself never grows past the configured cap.
 */
class AnnounceAdmission(
    private val clock: () -> Long,
    private val policy: Policy = Policy(),
) {
    data class Policy(
        /** Minimum spacing between accepted announces with the same fingerprint. */
        val minFingerprintIntervalMs: Long = 1_000L,
        /** Hard ceiling on simultaneously tracked fingerprints. */
        val maxFingerprints: Int = 256,
        /** Hard ceiling on distinct fingerprints claiming one source IP. */
        val maxPerSourceIp: Int = 64,
        /** Stale entries are forgotten, so new peers can appear after a flood. */
        val fingerprintTtlMs: Long = 60_000L,
        /** In-flight register jobs; extra announces are dropped, not queued. */
        val maxConcurrentRegisters: Int = 8,
    )

    enum class Decision {
        ACCEPT, INVALID, SELF, TOO_SOON, SOURCE_MISMATCH,
        FINGERPRINT_CAP, SOURCE_CAP, REGISTER_BUSY,
    }

    private data class Seen(var at: Long, var sourceIp: String)

    private val seen = LinkedHashMap<String, Seen>()
    private val registers = Semaphore(policy.maxConcurrentRegisters.coerceAtLeast(1))

    /** Decide in one atomic step; a REGISTER_BUSY refusal consumes no state. */
    @Synchronized
    fun decide(
        fingerprint: String,
        sourceIp: String,
        ownFingerprint: String,
        port: Int,
        needsRegister: Boolean,
    ): Decision {
        // Bound the raw input before trimming or scanning hostile strings.
        if (fingerprint.length > MAX_FINGERPRINT_INPUT_CHARS) return Decision.INVALID
        val trimmedFp = fingerprint.trim()
        if (trimmedFp.startsWith(MANUAL_PREFIX, ignoreCase = true)) return Decision.INVALID
        val fp = canonical(trimmedFp) ?: return Decision.INVALID
        val ip = sourceIp.trim()
        if (ip.isEmpty() || ip.length > MAX_IP_LENGTH) return Decision.INVALID
        if (port !in 1..65535) return Decision.INVALID
        // Canonical comparison: a lowercase copy of our own certificate must
        // not make us register back to a speaker claiming our identity.
        if (fp == canonical(ownFingerprint)) return Decision.SELF

        val now = clock()
        var existing = seen[fp]
        if (existing != null) {
            val age = now - existing.at
            when {
                age >= policy.fingerprintTtlMs -> {
                    // Stale identity: forget it so a device that moved can return.
                    seen.remove(fp)
                    existing = null
                }
                existing.sourceIp != ip -> return Decision.SOURCE_MISMATCH
                age < policy.minFingerprintIntervalMs -> return Decision.TOO_SOON
            }
        }
        if (existing == null) {
            val cutoff = now - policy.fingerprintTtlMs
            seen.entries.removeAll { it.value.at <= cutoff }
            if (seen.size >= policy.maxFingerprints.coerceAtLeast(1)) return Decision.FINGERPRINT_CAP
            if (seen.values.count { it.sourceIp == ip } >= policy.maxPerSourceIp.coerceAtLeast(1)) {
                return Decision.SOURCE_CAP
            }
        }
        if (needsRegister && !registers.tryAcquire()) return Decision.REGISTER_BUSY

        seen[fp] = Seen(now, ip)
        return Decision.ACCEPT
    }

    /** Trimmed, uppercased 64-hex form, or null for any unusable value. */
    private fun canonical(fingerprint: String): String? {
        if (fingerprint.length > MAX_FINGERPRINT_INPUT_CHARS) return null
        val trimmed = fingerprint.trim()
        if (trimmed.length != FINGERPRINT_CHARS) return null
        return buildString(FINGERPRINT_CHARS) {
            for (c in trimmed) {
                when (c) {
                    in '0'..'9' -> append(c)
                    in 'a'..'f' -> append(c - 32)
                    in 'A'..'F' -> append(c)
                    else -> return null
                }
            }
        }
    }

    /** Called by a register job's finally block, including on failure. */
    fun releaseRegister() {
        registers.release()
    }

    @Synchronized fun trackedCount(): Int = seen.size

    @Synchronized fun sourceCount(ip: String): Int = seen.values.count { it.sourceIp == ip.trim() }

    fun availableRegisters(): Int = registers.availablePermits()

    private companion object {
        const val FINGERPRINT_CHARS = 64
        const val MAX_FINGERPRINT_INPUT_CHARS = 256
        const val MAX_IP_LENGTH = 64
        const val MANUAL_PREFIX = "manual@"
    }
}
