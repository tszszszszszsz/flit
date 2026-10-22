package app.flit.share.harness

import app.flit.share.protocol.Protocol
import app.flit.share.protocol.client.ProtocolClient
import app.flit.share.protocol.client.SendSpec
import app.flit.share.protocol.client.SendTarget
import app.flit.share.protocol.discovery.UdpDiscovery
import app.flit.share.protocol.model.FileInfo
import app.flit.share.protocol.model.PeerInfo
import app.flit.share.protocol.model.PrepareUploadRequest
import app.flit.share.protocol.model.WireJson
import app.flit.share.protocol.server.PrepareDecision
import app.flit.share.protocol.server.ProtocolServer
import app.flit.share.protocol.server.ServerConfig
import app.flit.share.protocol.server.ServerDelegate
import app.flit.share.protocol.server.UploadResult
import app.flit.share.protocol.tls.TlsIdentity
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.isRegularFile
import kotlin.system.exitProcess

// ==== tiny arg parser ========================================================

private class ParsedArgs(argv: Array<String>) {
    val flags = LinkedHashMap<String, String>()
    val positional = mutableListOf<String>()

    init {
        var i = 0
        while (i < argv.size) {
            val a = argv[i]
            if (a.startsWith("--")) {
                if (i + 1 < argv.size && VALUE_FLAGS.contains(a)) {
                    flags[a] = argv[i + 1]; i += 2
                } else {
                    flags[a] = ""; i++
                }
            } else {
                positional.add(a); i++
            }
        }
    }

    fun opt(k: String): String? = flags[k]?.takeIf { it.isNotEmpty() }
    fun optInt(k: String, def: Int): Int = opt(k)?.toIntOrNull() ?: def
    fun has(k: String): Boolean = flags.containsKey(k)

    companion object {
        val VALUE_FLAGS = setOf("--alias", "--port", "--dir", "--udp-port", "--wait", "--pin", "--parallel", "--announce-to")
    }
}

/** Stable identity in ./flit-harness so fingerprints survive restarts (peers remember us). */
private fun loadOrCreateIdentity(): TlsIdentity {
    val dir = Path.of("flit-harness")
    Files.createDirectories(dir)
    val file = dir.resolve("identity.p12")
    return if (Files.exists(file)) TlsIdentity.load(file.toFile())
    else TlsIdentity.generate().also { it.save(file.toFile()) }
}

/** --announce-to a:port,b:port — unicast announce copies for hosts where multicast is stripped. */
private fun announceTargets(arg: String?): List<InetSocketAddress> =
    arg?.split(',')?.mapNotNull { token ->
        val parts = token.trim().split(':')
        if (parts.size != 2) return@mapNotNull null
        runCatching { InetSocketAddress(parts[0], parts[1].toInt()) }.getOrNull()
    }.orEmpty()

private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss")
private fun ts(): String = LocalTime.now().format(fmt)

fun main(argv: Array<String>) {
    val args = ParsedArgs(argv)
    val cmd = args.positional.firstOrNull() ?: run { usage(); return }
    try {
        when (cmd) {
            "serve" -> kotlinx.coroutines.runBlocking { serve(args) }
            "discover" -> kotlinx.coroutines.runBlocking { discover(args) }
            "send" -> kotlinx.coroutines.runBlocking { send(args) }
            "info" -> kotlinx.coroutines.runBlocking { info(args) }
            else -> usage()
        }
    } catch (e: Exception) {
        System.err.println("!! ${e.message ?: e.javaClass.simpleName}")
        if (System.getenv("FLIT_TRACE") != null) e.printStackTrace()
        exitProcess(1)
    }
}

private fun usage() {
    println(
        """flit harness — LocalSend v2.2 interop checker

serve [--alias N] [--port P] [--dir D] [--udp-port N] [--http] [--announce-to ip:port,...]
discover [--wait S] [--udp-port N] [--http] [--announce-to ip:port,...]
send <ip[:port] | alias> <file...> [--http] [--pin PIN] [--parallel N]
info <ip[:port]> [--http]
"""
    )
}

// ==== shared "console delegate" receiving protocol callbacks ==================

/** Registers collect into `seen` (dedup by fingerprint, both UDP announce + HTTP register). */
internal class ConsoleDelegate : ServerDelegate {
    val seen = ConcurrentHashMap<String, Pair<String, PeerInfo>>() // fingerprint → (ip, info)

    override fun onRegister(info: PeerInfo, ip: String) {
        if (seen.containsKey(info.fingerprint)) return
        seen[info.fingerprint] = ip to info
        println("${ts()} seen ${info.alias} ($ip:${info.port}) type=${info.deviceType ?: "?"} proto=${info.protocol} v=${info.version}")
    }

    fun announceSeen(ip: InetAddress, info: PeerInfo) {
        if (seen.containsKey(info.fingerprint)) return
        seen[info.fingerprint] = (ip.hostAddress ?: "?") to info
        println("${ts()} seen ${info.alias} (multi from $ip) type=${info.deviceType ?: "?"} proto=${info.protocol} port=${info.port} v=${info.version}")
    }

    override fun onPrepare(request: PrepareUploadRequest, peerIp: String): PrepareDecision {
        println("${ts()} offer from ${request.info.alias} ($peerIp): ${request.files.size} file(s)")
        for ((id, f) in request.files) {
            println("${ts()}   - $id → ${f.fileName} (${f.size} bytes, ${f.fileType}, sha=${f.sha256?.take(12) ?: "<none>"})")
        }
        return PrepareDecision.Accept(request.files.keys) // harness auto-accepts
    }

    override fun onUploadStart(sessionId: String, fileId: String, file: FileInfo) {
        println("${ts()} recv-start ${file.fileName} id=$fileId")
    }

    override fun onUploadFinish(sessionId: String, fileId: String, file: FileInfo, result: UploadResult) {
        when (result) {
            is UploadResult.Saved -> println("${ts()} recv-ok   ${file.fileName} → ${result.path} (${result.bytes} bytes)")
            is UploadResult.Mismatch -> println("${ts()} recv-BAD  ${file.fileName} checksum mismatch")
            is UploadResult.Error -> println("${ts()} recv-ERR  ${file.fileName}")
        }
    }

    override fun onSessionEnd(sessionId: String, cancelled: Boolean) {
        println("${ts()} session ${sessionId.take(8)} ${if (cancelled) "cancelled" else "finished"}")
    }
}

// ==== commands ===============================================================

private suspend fun serve(args: ParsedArgs) {
    val identity = loadOrCreateIdentity()
    val alias = args.opt("--alias") ?: "Flit (${System.getenv("USER") ?: "dev"})"
    val destDir = Path.of(args.opt("--dir") ?: "flit-downloads")
    val config = ServerConfig(
        alias = alias,
        port = args.optInt("--port", Protocol.DEFAULT_PORT),
        tls = !args.has("--http"),
        destDir = destDir,
        deviceModel = System.getenv("FLIT_MODEL"),
        deviceType = "headless",
    )
    val delegate = ConsoleDelegate()
    val server = ProtocolServer(identity, delegate, config)
    server.start()

    val client = ProtocolClient(identity)
    val discovery = UdpDiscovery(
        udpPort = args.optInt("--udp-port", Protocol.MULTICAST_PORT),
        own = server::selfInfo,
        onPeerDiscovered = delegate::announceSeen,
        registerPoster = { ip, port, protocol, self ->
            client.register(SendTarget(ip, port, protocol == "https"), self)
        },
        fallbackPeers = announceTargets(args.opt("--announce-to")),
    )
    discovery.start()

    println("${ts()} serving ${server.scheme}://0.0.0.0:${server.port} dir=${config.destDir.toAbsolutePath()}")
    println("${ts()} fingerprint ${identity.fingerprint}")

    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { discovery.stop(); server.close() }
        println("\n${ts()} bye")
    })
    while (true) {
        Thread.sleep(1_000_000)
    }
}

private suspend fun discover(args: ParsedArgs) {
    val identity = loadOrCreateIdentity()
    val waitSecs = args.optInt("--wait", 8).toLong()
    val tls = !args.has("--http")
    val alias = args.opt("--alias") ?: "Flit-discover"

    val delegate = ConsoleDelegate()
    val server = ProtocolServer(
        identity, delegate,
        ServerConfig(alias = alias, port = 0, tls = tls, destDir = Path.of("flit-tmp-discover")),
    )
    server.start()

    val client = ProtocolClient(identity)
    val discovery = UdpDiscovery(
        udpPort = args.optInt("--udp-port", Protocol.MULTICAST_PORT),
        own = server::selfInfo,
        onPeerDiscovered = delegate::announceSeen,
        registerPoster = { ip, port, protocol, self ->
            client.register(SendTarget(ip, port, protocol == "https"), self)
        },
        fallbackPeers = announceTargets(args.opt("--announce-to")),
    )
    discovery.start(announceIntervalMillis = 600)

    println("${ts()} discovering (udp ${args.optInt("--udp-port", Protocol.MULTICAST_PORT)})…")
    kotlinx.coroutines.delay(waitSecs * 1000)
    discovery.stop()
    server.close()

    if (delegate.seen.isEmpty()) {
        println("${ts()} no peers found")
        exitProcess(2)
    }
    for ((fp, pair) in delegate.seen) {
        val (ip, info) = pair
        println("${ts()} peer ${info.alias} ip=$ip:${info.port} tls=${info.protocol == "https"} type=${info.deviceType} v=${info.version} fp=${fp.take(12)}…")
    }
}

private suspend fun send(args: ParsedArgs) {
    if (args.positional.size < 3) { usage(); return }
    val targetArg = args.positional[1]
    val paths = args.positional.drop(2)
    val identity = loadOrCreateIdentity()
    val alias = args.opt("--alias") ?: "Flit-sender"

    val target: SendTarget = resolveTarget(targetArg, args, identity, alias)

    val specs = expandSpecs(paths)
    if (specs.isEmpty()) {
        System.err.println("no files found in given paths")
        exitProcess(1)
    }

    val self = PeerInfo(
        alias = alias,
        version = Protocol.VERSION,
        deviceModel = System.getenv("FLIT_MODEL"),
        deviceType = "headless",
        fingerprint = identity.fingerprint,
        port = Protocol.DEFAULT_PORT,
        protocol = "https",
    )
    val client = ProtocolClient(identity)
    client.use {
        val report = it.sendFiles(
            target = target,
            self = self,
            specs = specs,
            pin = args.opt("--pin"),
            parallelism = args.optInt("--parallel", 2),
        )
        println("${ts()} sent ${report.sent.size}/${specs.size} to ${target.ip}:${target.port}")
        for (s in report.sent) println("${ts()}   ok $s")
        for (f in report.failed) println("${ts()}   FAIL ${f.fileName}: ${f.reason}")
        if (report.failed.isNotEmpty()) exitProcess(3)
    }
}

/** ip[:port] directly, or a short discovery rendez-vous by alias. */
private suspend fun resolveTarget(targetArg: String, args: ParsedArgs, identity: TlsIdentity, alias: String): SendTarget {
    if (targetArg.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?$"))) {
        val (host, port) = splitHostPort(targetArg)
        return SendTarget(host, port, !args.has("--http"))
    }

    val delegate = ConsoleDelegate()
    val server = ProtocolServer(
        identity, delegate,
        ServerConfig(alias = alias, port = 0, destDir = Path.of("flit-tmp-discover")),
    )
    server.start()
    val client = ProtocolClient(identity)
    val discovery = UdpDiscovery(
        udpPort = args.optInt("--udp-port", Protocol.MULTICAST_PORT),
        own = server::selfInfo,
        onPeerDiscovered = delegate::announceSeen,
        registerPoster = { ip, port, protocol, self ->
            client.register(SendTarget(ip, port, protocol == "https"), self)
        },
    )
    discovery.start(announceIntervalMillis = 500)
    kotlinx.coroutines.delay(args.optInt("--wait", 5) * 1000L)
    discovery.stop()
    server.close()

    val found = delegate.seen.entries
        .map { it.value }
        .firstOrNull { it.second.alias.equals(targetArg, ignoreCase = true) }
        ?: throw IllegalStateException("no device named '$targetArg' found (peers seen: ${delegate.seen.size})")
    val peer = found.second
    return SendTarget(found.first, peer.port, peer.protocol == "https")
}

private fun splitHostPort(s: String): Pair<String, Int> {
    val idx = s.lastIndexOf(':')
    if (idx <= 0) return s to Protocol.DEFAULT_PORT
    val port = s.substring(idx + 1).toIntOrNull() ?: throw IllegalStateException("bad port in '$s'")
    return s.substring(0, idx) to port
}

private fun expandSpecs(paths: List<String>): List<SendSpec> {
    val out = mutableListOf<SendSpec>()
    for (raw in paths) {
        val root = Path.of(raw)
        if (Files.isRegularFile(root)) {
            out.add(SendSpec(root, root.fileName.toString()))
        } else if (Files.isDirectory(root)) {
            Files.walk(root).use { stream ->
                stream.filter { it.isRegularFile() }.forEach { child ->
                    val relName = root.relativize(child).toString().replace('\\', '/')
                    out.add(SendSpec(child, relName))
                }
            }
        }
    }
    return out
}

private suspend fun info(args: ParsedArgs) {
    if (args.positional.size < 2) { usage(); return }
    val (host, port) = splitHostPort(args.positional[1])
    val identity = loadOrCreateIdentity()
    val client = ProtocolClient(identity)
    client.use {
        val peer = it.info(SendTarget(host, port, !args.has("--http")))
        if (peer == null) {
            System.err.println("no answer from $host:$port")
            exitProcess(2)
        }
        println(WireJson.encode(peer.sansAnnounce()))
    }
}
