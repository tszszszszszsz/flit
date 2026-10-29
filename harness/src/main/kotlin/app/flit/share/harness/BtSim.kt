package app.flit.share.harness

import app.flit.share.protocol.Protocol
import app.flit.share.protocol.client.ProtocolClient
import app.flit.share.protocol.client.SendTarget
import app.flit.share.protocol.client.TlsLink
import app.flit.share.protocol.model.PeerInfo
import app.flit.share.protocol.server.LinkServer
import app.flit.share.protocol.server.ServerConfig
import app.flit.share.protocol.server.ServerCore
import app.flit.share.protocol.tls.TlsIdentity
import app.flit.share.protocol.transport.PipePair
import app.flit.share.protocol.transport.SimLinkProvider
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import kotlin.system.exitProcess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * `bt-sim` — phase B's rig half. Both ends of a transfer run in one process,
 * and the transport between them is the shared simulated carrier
 * ([SimLinkProvider]/[PipePair] — the exact pipes the protocol parity suite
 * proves on). The receiver is the same [ServerCore] the ktor binder feeds,
 * spoken to through the same [LinkServer] the Bluetooth RFCOMM binder rides,
 * wrapped in the same [TlsLink] TLS when enabled.
 *
 * What a green run certifies end-to-end: /info, /register, /prepare-upload,
 * /upload with sha receipts, session close — the send rig's usual report,
 * over a non-TCP carrier. With no files given, one synthetic random file of
 * `--bytes` megabytes rides instead (a worst-case body, freely).
 *
 * (docs/transport-bluetooth.md — B4: "host-side pipe endpoints so the
 * existing serve/send rig exercises v2 semantics over the simulated
 * transport end-to-end.")
 */
internal suspend fun btSim(args: ParsedArgs) {
    if (args.positional.size < 2 && args.opt("--bytes") == null) {
        System.err.println("bt-sim: give files or --bytes MB")
        exitProcess(1)
    }
    val tls = !args.has("--no-tls")

    // One identity per side, minted once — the receiver's TLS label must be
    // the same on every connection it answers (it is the fingerprint the
    // sender's pin rides, and the /info yearbook is read against it).
    val receiverIdentity = TlsIdentity.generate()
    val senderIdentity = TlsIdentity.generate()

    val dest = Path.of(args.opt("--dir") ?: "flit-sim-received").also { Files.createDirectories(it) }
    val receiverLink = LinkServer(
        ServerCore(
            receiverIdentity,
            ServerConfig(
                alias = args.opt("--alias") ?: "Flit (sim-receiver)",
                destDir = dest,
                deviceType = "headless",
                // The label follows the carrier: a plain-pipe run must not
                // advertise https to honest clients reading /info.
                tls = tls,
            ),
            ConsoleDelegate(),
        ),
    )
    val inbox = Channel<PipePair>(Channel.UNLIMITED)
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val accept = scope.launch {
        while (isActive) {
            val pair = inbox.receive()
            launch(Dispatchers.IO) {
                // One exchange = one fresh pair; the receiver's origin label
                // stands where a carrier address would (rfcomm:xx) — trust
                // checks ride this string, so the flow is exercised, not
                // sidestepped.
                val raw = pair.serverLink("bt-sim")
                try {
                    receiverLink.serve(if (tls) TlsLink(raw, receiverIdentity, clientMode = false) else raw)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    System.err.println("!! serve crashed: $t")
                }
            }
        }
    }

    // Outgoing specs exactly as the send rig builds them; no files → one
    // synthetic random file (--bytes MB, default 4), a worst-case body.
    val synthetic: Path? = if (args.positional.size > 1) null else
        Files.createTempDirectory("flit-btsim").resolve("sim-${args.optInt("--bytes", 4)}.bin").apply {
            Files.write(this, ByteArray(args.optInt("--bytes", 4) * 1024 * 1024).also { Random(1777).nextBytes(it) })
        }
    val specs = expandSpecs(args.positional.drop(1).ifEmpty { listOf(synthetic!!.toString()) })
    println("${ts()} bt-sim over the ${if (tls) "TLS" else "plain"} pipe carrier — ${specs.size} file(s) into ${dest.toAbsolutePath()}")

    val senderSelf = PeerInfo(
        alias = args.opt("--sender") ?: "Flit (sim-sender)",
        version = Protocol.VERSION,
        deviceType = "headless",
        fingerprint = senderIdentity.fingerprint,
        port = Protocol.DEFAULT_PORT,
        protocol = "https",
    )
    ProtocolClient(senderIdentity, links = SimLinkProvider(senderIdentity, tls, inbox)).use { client ->
        val target = SendTarget("sim", 0, tls)
        // The verb floor of v2 over the pipe: info, register, then the send flow.
        val info = client.info(target)
        if (info == null) {
            System.err.println("!! bt-sim: /info over the simulated carrier gave no answer")
            exitProcess(2)
        }
        println("${ts()} info: ${info.alias} v=${info.version} protocol=${info.protocol} fp=${info.fingerprint.take(12)}…")
        client.register(target, senderSelf)
        val report = client.sendFiles(
            target = target,
            self = senderSelf,
            files = specs,
            pin = args.opt("--pin"),
            parallelism = args.optInt("--parallel", 4),
            multi = args.has("--multi"),
        )
        println("${ts()} sent ${report.sent.size}/${specs.size} over the simulated transport")
        for (s in report.sent) println("${ts()}   ok $s")
        for (f in report.failed) println("${ts()}   FAIL ${f.fileName}: ${f.reason}")
        inbox.close()
        if (report.failed.isNotEmpty()) exitProcess(3)
    }
    scope.cancel()
}
