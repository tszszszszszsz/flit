package app.flit.share

import android.app.Application

/**
 * Process-level setup. Netty (via ktor-server-netty) is initialized lazily by
 * the engine; probing host-native transports (epoll/kqueue, Linux/macOS ELFs)
 * is pointless — and can hard-fail — on Android, so we pin the NIO transport
 * here, before any engine touch.
 */
class FlitApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FlitEngine.initAppCtx(this)
        // Roster reads must be safe from the first activity touch: the share
        // sheet fires MainActivity before any engine start.
        FlitRoster.init(this)
        System.setProperty("io.netty.transport.noNative", "true")
    }
}
