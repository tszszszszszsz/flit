package app.flit.share.protocol.server

import app.flit.share.protocol.Protocol
import app.flit.share.protocol.model.PeerInfo
import app.flit.share.protocol.tls.TlsIdentity
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import io.ktor.utils.io.readAvailable
import io.netty.channel.ChannelOption
import io.netty.channel.FixedRecvByteBufAllocator
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/** Raw socket address of the peer, captured before ktor wraps the call. */
private val peerAddrAttr = AttributeKey<InetSocketAddress>("flit-peer-addr")

/**
 * Peer IP from the Setup-phase attribute (raw `InetSocketAddress` of the
 * channel). MUST NOT go through `request.origin`: ktor's netty connection
 * point calls `InetSocketAddress.getHostName()`, which blocks ~5s per call on
 * a literal LAN IP doing reverse-DNS that never resolves — stalling the route
 * handler past any client timeout. `address.hostAddress` is a pure string
 * conversion.
 */
internal fun ApplicationCall.peerIp(): String =
    attributes.getOrNull(peerAddrAttr)?.address?.hostAddress ?: ""

/**
 * [HttpExchange] view over a ktor [ApplicationCall] — the TCP adapter that
 * feeds the same [ServerCore] the pipe binder (LinkServer) feeds.
 */
private class KtorExchange(private val call: ApplicationCall) : HttpExchange {
    override val method: String = call.request.httpMethod.value

    // Routing already matched this exact path; path() is the request URI's
    // path (no query), which for our exact-match routes is the route itself.
    override val path: String = call.request.path()

    // The Setup-phase capture; never request.origin (reverse-DNS stall —
    // see peerIp()).
    override val origin: String = call.peerIp()

    override fun query(name: String): String? = call.request.queryParameters[name]

    override fun header(name: String): String? = call.request.headers[name]

    override suspend fun readBody(buf: ByteArray, offset: Int, len: Int): Int =
        call.request.receiveChannel().readAvailable(buf, offset, len)

    override suspend fun respondText(status: Int, contentType: String, body: String) {
        call.respondText(body, ContentType.parse(contentType), HttpStatusCode.fromValue(status))
    }

    override suspend fun respondText(status: Int, contentType: String, body: String, headers: Map<String, String>) {
        headers.forEach { (name, value) -> call.response.headers.append(name, value) }
        call.respondText(body, ContentType.parse(contentType), HttpStatusCode.fromValue(status))
    }

    override suspend fun respondStatus(status: Int) {
        call.respond(HttpStatusCode.fromValue(status))
    }
}

/**
 * LocalSend v2.2 HTTP server: /info, /register, /prepare-upload, /upload, /cancel —
 * the ktor/netty listener, one transport of the same [ServerCore] routes this
 * class hosts. Route semantics live in [ServerCore]; this file holds only
 * listener + transport pinning (netty tuning, TLS connector, peer-IP capture).
 *
 * Flit↔Flit extensions: multi-socket uploads (X-Flit-Multi +
 * Content-Range), resumable interrupted files (X-Flit-Resume), partial probe.
 * Old peers never send these headers, and v2.2 senders are compatible.
 */
class ProtocolServer(
    private val identity: TlsIdentity,
    delegate: ServerDelegate,
    config: ServerConfig = ServerConfig(),
) : AutoCloseable {

    /** The one set of route semantics; Bluetooth serves it via [LinkServer]. */
    private val core = ServerCore(identity, config, delegate)

    /** Sole purpose of `server`/`actualPort` is the TCP listener lifecycle. */
    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null
    private val actualPort = AtomicInteger(-1)

    val port: Int get() = actualPort.get().takeIf { it > 0 } ?: core.port
    val scheme: String get() = core.scheme

    fun selfInfo(): PeerInfo = core.selfInfo()

    fun start() {
        check(server == null) { "server already started" }
        Files.createDirectories(core.config.destDir)

        val module: suspend Application.() -> Unit = {
            // Capture the peer's raw socket address at the earliest pipeline
            // phase, while `call` is still the engine's NettyApplicationCall
            // (routing wraps it later, hiding the channel). Later readers must
            // use this attribute — see peerIp() for why `request.origin` is
            // unusable (it reverse-resolves, blocking route handlers ~5s on
            // LAN IPs).
            intercept(ApplicationCallPipeline.Setup) {
                (context as? NettyApplicationCall)?.let { nc ->
                    (nc.context.channel().remoteAddress() as? InetSocketAddress)?.let {
                        context.attributes.put(peerAddrAttr, it)
                    }
                }
            }
            routing {
                get(Protocol.PATH_INFO) { core.handle(KtorExchange(call)) }
                get(Protocol.PATH_REGISTER) { core.handle(KtorExchange(call)) }
                post(Protocol.PATH_REGISTER) { core.handle(KtorExchange(call)) }
                post(Protocol.PATH_PREPARE_UPLOAD) { core.handle(KtorExchange(call)) }
                post(Protocol.PATH_UPLOAD) { core.handle(KtorExchange(call)) }
                post(Protocol.PATH_CANCEL) { core.handle(KtorExchange(call)) }
                get(Protocol.PATH_PARTIAL) { core.handle(KtorExchange(call)) }
                post(Protocol.PATH_PANIC) { core.handle(KtorExchange(call)) }
                post(Protocol.PATH_CIRCLE_JOIN) { core.handle(KtorExchange(call)) }
                post(Protocol.PATH_CIRCLE_SYNC) { core.handle(KtorExchange(call)) }
            }
        }

        val configBlock: io.ktor.server.engine.ApplicationEngine.Configuration.() -> Unit = {
            if (core.config.tls) {
                sslConnector(identity.keyStore, identity.alias, { identity.secretChars() }, { identity.secretChars() }) {
                    host = core.config.host
                    this.port = core.config.port
                }
            } else {
                connectors = mutableListOf<io.ktor.server.engine.EngineConnectorConfig>(
                    EngineConnectorBuilder().apply {
                        host = core.config.host
                        this.port = core.config.port
                    },
                )
            }
            if (this is io.ktor.server.netty.NettyApplicationEngine.Configuration) {
                // HTTP/2 drags ALPN negotiation into every TLS connection. Peers that
                // don't offer ALPN (JDK/Conscrypt stacks, some Dart/jvm clients) then
                // get their connection reset — on Android Conscrypt this even throws
                // an AssertionError inside netty's JdkAlpnSslEngine and RSTs mid
                // handshake. LocalSend v2.2 is HTTP/1.1 anyway.
                enableHttp2 = false
                // Bulk-upload throughput: netty's adaptive allocator grows slowly
                // and the default SO_RCVBUF trims the TCP window on phones, so
                // large-file transfers stream in small costly slices. Pin big
                // allocations up front.
                configureBootstrap = {
                    childOption(ChannelOption.SO_RCVBUF, 4 * 1024 * 1024)
                    childOption(ChannelOption.RCVBUF_ALLOCATOR, FixedRecvByteBufAllocator(256 * 1024))
                    childOption(ChannelOption.TCP_NODELAY, true)
                }
            }
        }

        core.sweepOnStart()
        val e = embeddedServer(Netty, applicationEnvironment { }, configBlock, module).let { it.start(wait = false) }
        server = e
        runBlocking {
            val p = e.engine.resolvedConnectors().first().port
            actualPort.set(p)
            core.notePort(p)
        }
    }

    /**
     * Serve one externally-arrived link through exactly the same route
     * semantics the TCP listener uses — the door a relayed pipe
     * (milestone E) or a radio carrier (milestone B) walks through. The
     * [Link] is already connected; TLS wrapping, if the carrier wants it,
     * is the carrier's business. One link, one exchange, then closed —
     * [LinkServer]'s contract.
     */
    suspend fun serve(link: app.flit.share.protocol.client.Link) {
        LinkServer(core).serve(link)
    }

    override fun close() {
        server?.stop(gracePeriodMillis = 300, timeoutMillis = 2000)
        server = null
        actualPort.set(-1)
    }
}
