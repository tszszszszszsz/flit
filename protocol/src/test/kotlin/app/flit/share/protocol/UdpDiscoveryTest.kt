package app.flit.share.protocol

import app.flit.share.protocol.discovery.AnnounceAdmission
import app.flit.share.protocol.discovery.AnnounceAdmission.Decision
import app.flit.share.protocol.discovery.UdpDiscovery
import app.flit.share.protocol.model.PeerInfo
import app.flit.share.protocol.model.WireJson
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Announce-flood admission contract. The gate is driven with an injected
 * clock and scope, and dispatch is exercised directly (no socket, no Android
 * state), so these tests pin the decisions and the "reject before callback"
 * rule, including identity/source binding and permit cleanup.
 */
class UdpDiscoveryTest {
    /** Canonical wire identity: 64 uppercase hex characters. */
    private fun fp(seed: Long): String = seed.toString(16).uppercase().padStart(64, '0')

    private val selfFp = fp(0)
    private val fpA = fp(1)
    private val fpB = fp(2)
    private val fpC = fp(3)
    private val self = PeerInfo(
        alias = "self", version = "2.2", fingerprint = selfFp,
        port = 4242, protocol = "https",
    )
    private val ipA: InetAddress = InetAddress.getByName("192.0.2.10")
    private val ipB: InetAddress = InetAddress.getByName("192.0.2.11")

    private fun json(fingerprint: String, port: Int = 4242): String = WireJson.encode(
        self.copy(alias = "peer", fingerprint = fingerprint, port = port),
    )

    private fun discovery(
        onSeen: (InetAddress, PeerInfo) -> Unit = { _, _ -> },
        onRegister: suspend (String, Int, String, PeerInfo) -> Unit = { _, _, _, _ -> },
        policy: AnnounceAdmission.Policy = AnnounceAdmission.Policy(),
        now: () -> Long = { 0L },
        announce: Boolean = true,
        scopeFactory: () -> CoroutineScope = { CoroutineScope(SupervisorJob() + Dispatchers.IO) },
    ): UdpDiscovery = UdpDiscovery(
        own = { self },
        onPeerDiscovered = onSeen,
        registerPoster = onRegister,
        announce = announce,
        admissionPolicy = policy,
        nowMillis = now,
        scopeFactory = scopeFactory,
    )

    @Test
    fun `default policy matches the flood limits`() {
        val p = AnnounceAdmission.Policy()
        assertEquals(1_000L, p.minFingerprintIntervalMs)
        assertEquals(256, p.maxFingerprints)
        assertEquals(64, p.maxPerSourceIp)
        assertEquals(8, p.maxConcurrentRegisters)
    }

    @Test
    fun `normal announce reaches the callback and one register`() {
        val seen = AtomicInteger()
        val posts = AtomicInteger()
        val registered = CountDownLatch(1)
        val disc = discovery(
            onSeen = { _, info ->
                seen.incrementAndGet()
                assertEquals(fpA, info.fingerprint)
            },
            onRegister = { _, _, _, _ ->
                posts.incrementAndGet()
                registered.countDown()
            },
        )

        assertTrue(disc.dispatchAnnounce(json(fpA), ipA))
        assertEquals(1, seen.get())
        assertTrue(registered.await(2, TimeUnit.SECONDS))
        assertEquals(1, posts.get())
    }

    @Test
    fun `repeat flood from one IP is throttled before the callback`() {
        var now = 5_000L
        val seen = AtomicInteger()
        val disc = discovery(onSeen = { _, _ -> seen.incrementAndGet() }, now = { now }, announce = false)
        val payload = json(fpA)

        assertTrue(disc.dispatchAnnounce(payload, ipA))
        repeat(64) { assertFalse(disc.dispatchAnnounce(payload, ipA)) }
        now += 999
        assertFalse(disc.dispatchAnnounce(payload, ipA))
        now += 1
        assertTrue(disc.dispatchAnnounce(payload, ipA))
        assertEquals(2, seen.get())
    }

    @Test
    fun `gate classifies a repeat as too soon and allows it after the interval`() {
        var now = 100L
        val gate = AnnounceAdmission({ now }, AnnounceAdmission.Policy())
        assertEquals(Decision.ACCEPT, gate.decide(fpA, "192.0.2.10", selfFp, 4242, false))
        assertEquals(Decision.TOO_SOON, gate.decide(fpA.lowercase(), "192.0.2.10", selfFp, 4242, false))
        now += 1_000
        assertEquals(Decision.ACCEPT, gate.decide(fpA, "192.0.2.10", selfFp, 4242, false))
        assertEquals(1, gate.trackedCount())
    }

    @Test
    fun `unique fingerprint flood stops at the source cap and the table cap`() {
        val gate = AnnounceAdmission(
            { 0L },
            AnnounceAdmission.Policy(maxFingerprints = 8, maxPerSourceIp = 3),
        )
        repeat(3) { i ->
            assertEquals(Decision.ACCEPT, gate.decide(fp(i + 10L), "192.0.2.10", selfFp, 4242, false))
        }
        repeat(32) { i ->
            assertEquals(Decision.SOURCE_CAP, gate.decide(fp(i + 13L), "192.0.2.10", selfFp, 4242, false))
        }
        assertEquals(3, gate.trackedCount())
        assertEquals(3, gate.sourceCount("192.0.2.10"))

        repeat(5) { i ->
            assertEquals(Decision.ACCEPT, gate.decide(fp(i + 100L), "192.0.2.${20 + i}", selfFp, 4242, false))
        }
        assertEquals(8, gate.trackedCount())
        repeat(16) { i ->
            assertEquals(Decision.FINGERPRINT_CAP, gate.decide(fp(i + 200L), "192.0.2.${40 + i}", selfFp, 4242, false))
        }
        assertEquals(8, gate.trackedCount())
    }

    @Test
    fun `unique fingerprint flood cannot drive unbounded callbacks`() {
        val seen = AtomicInteger()
        val disc = discovery(
            onSeen = { _, _ -> seen.incrementAndGet() },
            policy = AnnounceAdmission.Policy(maxFingerprints = 4, maxPerSourceIp = 2),
            announce = false,
        )
        repeat(100) { i -> disc.dispatchAnnounce(json(fp(i + 300L)), ipA) }
        assertEquals(2, seen.get())
    }

    @Test
    fun `stale fingerprints are forgotten so new peers can appear`() {
        var now = 1_000L
        val gate = AnnounceAdmission(
            { now },
            AnnounceAdmission.Policy(maxFingerprints = 1, fingerprintTtlMs = 5L),
        )
        assertEquals(Decision.ACCEPT, gate.decide(fpA, "192.0.2.10", selfFp, 4242, false))
        now += 4
        assertEquals(Decision.FINGERPRINT_CAP, gate.decide(fpB, "192.0.2.11", selfFp, 4242, false))
        now += 1
        assertEquals(Decision.ACCEPT, gate.decide(fpB, "192.0.2.11", selfFp, 4242, false))
        assertEquals(1, gate.trackedCount())
    }

    @Test
    fun `malformed self and out of range port are refused before any callback`() {
        val seen = AtomicInteger()
        val posts = AtomicInteger()
        val disc = discovery(
            onSeen = { _, _ -> seen.incrementAndGet() },
            onRegister = { _, _, _, _ -> posts.incrementAndGet() },
        )

        listOf("", "   ", "BAD\nFP", "X".repeat(200), selfFp.lowercase(), "manual@127.0.0.1:47317")
            .forEach { fingerprint -> assertFalse(disc.dispatchAnnounce(json(fingerprint), ipA)) }
        listOf(-1, 0, 65_536, Int.MAX_VALUE).forEach { port ->
            assertFalse(disc.dispatchAnnounce(json(fpC, port), ipA))
        }
        assertFalse(disc.dispatchAnnounce("not json", ipA))
        assertFalse(disc.dispatchAnnounce(json(fpC), null))

        assertEquals(0, seen.get())
        assertEquals(0, posts.get())
    }

    @Test
    fun `gate classifies invalid input and blank source`() {
        val gate = AnnounceAdmission({ 0L }, AnnounceAdmission.Policy())
        assertEquals(Decision.INVALID, gate.decide("  ", "192.0.2.10", selfFp, 4242, false))
        assertEquals(Decision.INVALID, gate.decide("bad\tnp", "192.0.2.10", selfFp, 4242, false))
        assertEquals(Decision.INVALID, gate.decide("manual@127.0.0.1:47317", "192.0.2.10", selfFp, 4242, false))
        assertEquals(Decision.INVALID, gate.decide(fpA, "", selfFp, 4242, false))
        assertEquals(Decision.INVALID, gate.decide(fpA, "192.0.2.10", selfFp, 0, false))
        assertEquals(Decision.SELF, gate.decide(selfFp.lowercase(), "192.0.2.10", selfFp, 4242, false))
        assertEquals(0, gate.trackedCount())
    }

    @Test
    fun `register jobs are bounded and extra announces are dropped before dispatch`() {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val seen = AtomicInteger()
        val posts = AtomicInteger()
        val disc = discovery(
            onSeen = { _, _ -> seen.incrementAndGet() },
            onRegister = { _, _, _, _ ->
                posts.incrementAndGet()
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
            },
            policy = AnnounceAdmission.Policy(maxConcurrentRegisters = 2),
        )
        try {
            repeat(8) { i -> disc.dispatchAnnounce(json(fp(i + 400L)), ipA) }
            assertEquals(2, seen.get())
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(2, posts.get())
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `register busy refuses without consuming fingerprint state`() {
        val gate = AnnounceAdmission({ 0L }, AnnounceAdmission.Policy(maxConcurrentRegisters = 1))
        assertEquals(Decision.ACCEPT, gate.decide(fpA, "192.0.2.10", selfFp, 4242, true))
        assertEquals(Decision.REGISTER_BUSY, gate.decide(fpB, "192.0.2.10", selfFp, 4242, true))
        assertEquals(1, gate.trackedCount())
        assertEquals(0, gate.availableRegisters())
        // Hidden mode still listens: no register permit needed.
        assertEquals(Decision.ACCEPT, gate.decide(fpB, "192.0.2.10", selfFp, 4242, false))
        gate.releaseRegister()
        assertEquals(1, gate.availableRegisters())
        assertEquals(Decision.ACCEPT, gate.decide(fpC, "192.0.2.10", selfFp, 4242, true))
    }

    @Test
    fun `only canonical 64-hex fingerprints pass and manual stubs are refused`() {
        val gate = AnnounceAdmission({ 0L }, AnnounceAdmission.Policy())
        // Surrounding whitespace and lowercase input normalize to the same key.
        assertEquals(Decision.ACCEPT, gate.decide("  ${fpA.lowercase()}  ", "192.0.2.10", selfFp, 4242, false))
        assertEquals(Decision.TOO_SOON, gate.decide(fpA, "192.0.2.10", selfFp, 4242, false))
        listOf(
            "manual@127.0.0.1:47317",
            "MANUAL@127.0.0.1:47317",
            "A".repeat(63),
            "A".repeat(65),
            "G".repeat(64),
            fpA.dropLast(1) + "!",
        ).forEach { bad ->
            assertEquals(Decision.INVALID, gate.decide(bad, "192.0.2.11", selfFp, 4242, false), "fp=$bad")
        }
        assertEquals(1, gate.trackedCount())
    }

    @Test
    fun `known fingerprint cannot be refreshed from a different source IP`() {
        var now = 0L
        val gate = AnnounceAdmission({ now }, AnnounceAdmission.Policy())
        assertEquals(Decision.ACCEPT, gate.decide(fpA, "192.0.2.10", selfFp, 4242, false))
        assertEquals(Decision.SOURCE_MISMATCH, gate.decide(fpA, "192.0.2.11", selfFp, 4242, false))
        now += 2_000
        assertEquals(Decision.SOURCE_MISMATCH, gate.decide(fpA.lowercase(), "192.0.2.11", selfFp, 4242, false))
        assertEquals(1, gate.trackedCount())
        assertEquals(1, gate.sourceCount("192.0.2.10"))
        assertEquals(0, gate.sourceCount("192.0.2.11"))
        assertEquals(Decision.ACCEPT, gate.decide(fpA, "192.0.2.10", selfFp, 4242, false))
    }

    @Test
    fun `source mismatch never reaches the callback`() {
        val seen = AtomicInteger()
        val disc = discovery(onSeen = { _, _ -> seen.incrementAndGet() }, announce = false)
        assertTrue(disc.dispatchAnnounce(json(fpA), ipA))
        assertFalse(disc.dispatchAnnounce(json(fpA), ipB))
        assertEquals(1, seen.get())
    }

    @Test
    fun `cancelled register launch releases its permit`() {
        val cancelled = CoroutineScope(SupervisorJob() + Dispatchers.IO).apply { cancel() }
        val seen = AtomicInteger()
        val started = AtomicInteger()
        val disc = discovery(
            onSeen = { _, _ -> seen.incrementAndGet() },
            onRegister = { _, _, _, _ -> started.incrementAndGet() },
            policy = AnnounceAdmission.Policy(maxConcurrentRegisters = 1),
            scopeFactory = { cancelled },
        )
        assertTrue(disc.dispatchAnnounce(json(fpA), ipA))
        assertEquals(0, started.get())
        // With a single permit, the next announce still passes if cleanup ran.
        assertTrue(disc.dispatchAnnounce(json(fpB), ipA))
        assertEquals(2, seen.get())
    }
}
