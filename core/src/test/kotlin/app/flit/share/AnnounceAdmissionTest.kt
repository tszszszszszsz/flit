package app.flit.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Admission arithmetic only. No socket, no Android, no clock: every timestamp
 * and every registry is supplied by the test. Each branch of [AnnounceAdmission]
 * has a case here, including hostile values and a misbehaving caller map.
 */
class AnnounceAdmissionTest {

    private fun info(
        fingerprint: String? = FP_A,
        alias: String? = "Phone",
        port: Int = 17_600,
        protocol: String? = "https",
        sourceIp: String? = "192.168.1.20",
    ): AnnounceInfo = AnnounceInfo(fingerprint, alias, port, protocol, sourceIp)

    private fun admit(
        i: AnnounceInfo = info(),
        ownFp: String = FP_B,
        nowMs: Long = 10_000L,
        seen: Map<String, Long> = emptyMap(),
    ): Boolean = AnnounceAdmission.admit(i, ownFp, nowMs, seen)

    // ---- fingerprint canonicalization ----------------------------------------

    @Test
    fun `validFingerprint accepts canonical lowercase and trimmed forms`() {
        assertTrue(AnnounceAdmission.validFingerprint(FP_A))
        assertTrue(AnnounceAdmission.validFingerprint(FP_A.lowercase()))
        assertTrue(AnnounceAdmission.validFingerprint("  $FP_A  "))
        assertTrue(AnnounceAdmission.validFingerprint("\t${FP_A.lowercase()}\n"))
    }

    @Test
    fun `validFingerprint rejects null blank short long and non hex`() {
        for (fp in listOf(
            null,
            "",
            "   ",
            "A".repeat(63),
            "A".repeat(65),
            "G".repeat(64),
            FP_A.take(63) + " ",
            FP_A.replaceRange(0, 1, "-"),
        )) {
            assertFalse(AnnounceAdmission.validFingerprint(fp), "fp=$fp")
        }
    }

    @Test
    fun `validFingerprint rejects manual stubs colon separators and inner whitespace`() {
        assertFalse(AnnounceAdmission.validFingerprint("manual@127.0.0.1:47317"))
        assertFalse(AnnounceAdmission.validFingerprint("A".repeat(31) + ":" + "A".repeat(32)))
        assertFalse(AnnounceAdmission.validFingerprint("A".repeat(31) + " " + "A".repeat(32)))
    }

    @Test
    fun `validFingerprint stays total on a huge hostile string`() {
        assertFalse(AnnounceAdmission.validFingerprint("A".repeat(100_000)))
        assertFalse(AnnounceAdmission.validFingerprint("\uD83D\uDE00".repeat(5_000)))
        assertFalse(AnnounceAdmission.validFingerprint("\u0000".repeat(64)))
    }

    // ---- port ----------------------------------------------------------------

    @Test
    fun `validPort accepts only 1 through 65535`() {
        for (p in listOf(1, 17_600, 65_535)) assertTrue(AnnounceAdmission.validPort(p), "port=$p")
        for (p in listOf(-1, 0, 65_536, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertFalse(AnnounceAdmission.validPort(p), "port=$p")
        }
    }

    // ---- identity refusals ---------------------------------------------------

    @Test
    fun `a well formed announce is admitted and leaves seen untouched`() {
        val registry = mapOf(FP_C to 0L)
        assertTrue(admit(seen = registry))
        assertEquals(1, registry.size)
        assertEquals(0L, registry[FP_C])
    }

    @Test
    fun `null and blank fingerprints are refused`() {
        for (fp in listOf(null, "", "   ", "\n\t")) assertFalse(admit(info(fingerprint = fp)), "fp=$fp")
    }

    @Test
    fun `malformed fingerprints are refused`() {
        for (fp in listOf(
            "ABC",
            "Z".repeat(64),
            "A".repeat(63),
            "A".repeat(65),
            "A".repeat(32) + "\n" + "A".repeat(31),
            "A".repeat(63) + "g",
        )) {
            assertFalse(admit(info(fingerprint = fp)), "fp=$fp")
        }
    }

    @Test
    fun `a manual stub fingerprint is refused`() {
        assertFalse(admit(info(fingerprint = "manual@127.0.0.1:47317")))
        assertFalse(admit(info(fingerprint = "  MANUAL@deadbeef  ")))
    }

    @Test
    fun `our own fingerprint is refused even with different case and whitespace`() {
        assertFalse(admit(info(fingerprint = FP_A), ownFp = "  ${FP_A.lowercase()}  "))
        assertFalse(admit(info(fingerprint = FP_A.lowercase()), ownFp = FP_A))
        assertTrue(admit(info(fingerprint = FP_A), ownFp = FP_B))
    }

    // ---- endpoint and label --------------------------------------------------

    @Test
    fun `out of range ports are refused`() {
        for (p in listOf(-1, 0, 65_536, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertFalse(admit(info(port = p)), "port=$p")
        }
        assertTrue(admit(info(port = 1)))
        assertTrue(admit(info(port = 65_535)))
    }

    @Test
    fun `alias length is capped at 64 while null and blank aliases pass`() {
        assertFalse(admit(info(alias = "x".repeat(65))))
        assertTrue(admit(info(alias = "x".repeat(64))))
        assertTrue(admit(info(alias = null)))
        assertTrue(admit(info(alias = "")))
    }

    // ---- source address ------------------------------------------------------

    @Test
    fun `loopback unspecified multicast reserved and cgnat sources are refused`() {
        for (ip in listOf(
            "127.0.0.1", "127.255.255.254", "0.0.0.0", "224.0.0.167",
            "239.255.255.250", "255.255.255.255", "240.0.0.1", "100.64.0.1",
        )) {
            assertFalse(admit(info(sourceIp = ip)), "ip=$ip")
        }
    }

    @Test
    fun `off link global sources are refused`() {
        for (ip in listOf("8.8.8.8", "1.1.1.1", "172.15.255.255", "172.32.0.1", "192.169.0.1")) {
            assertFalse(admit(info(sourceIp = ip)), "ip=$ip")
        }
    }

    @Test
    fun `private and link local ipv4 sources are admitted`() {
        for (ip in listOf(
            "10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.255",
            "192.168.1.20", "169.254.1.1",
        )) {
            assertTrue(admit(info(sourceIp = ip)), "ip=$ip")
        }
    }

    @Test
    fun `ipv6 on link ranges are admitted while loopback multicast and global are refused`() {
        for (ip in listOf("fe80::1", "feb0::1234", "FE80::1", "fc00::1", "fdff::abcd", "fe80:0:0:0:0:0:0:1")) {
            assertTrue(admit(info(sourceIp = ip)), "ip=$ip")
        }
        for (ip in listOf("::1", "::", "ff02::1", "2001:db8::1", "fec0::1", "fe80::zz", "1:2:3:4:5:6:7:8")) {
            assertFalse(admit(info(sourceIp = ip)), "ip=$ip")
        }
    }

    @Test
    fun `brackets zones trimming and malformed source text are handled`() {
        assertTrue(admit(info(sourceIp = "fe80::1%eth0")))
        assertTrue(admit(info(sourceIp = "[fe80::1]")))
        assertTrue(admit(info(sourceIp = "  10.0.0.1  ")))
        for (ip in listOf(
            "[::1]", "fe80::1]", "[fe80::1", "10.0.0.256", "010.0.0.1",
            "10.0.0", "10.0.0.1:80", "::ffff:192.168.1.5", "2001:db8::" + "x".repeat(100),
        )) {
            assertFalse(admit(info(sourceIp = ip)), "ip=$ip")
        }
    }

    @Test
    fun `a missing or blank source address is refused`() {
        assertFalse(admit(info(sourceIp = null)))
        assertFalse(admit(info(sourceIp = "")))
        assertFalse(admit(info(sourceIp = "   ")))
    }

    // ---- replay window -------------------------------------------------------

    @Test
    fun `a second announce inside the window is refused and the boundary admits`() {
        assertFalse(admit(nowMs = 9_000L, seen = mapOf(FP_A to 9_000L)))
        assertFalse(admit(nowMs = 9_999L, seen = mapOf(FP_A to 9_000L)))
        assertTrue(admit(nowMs = 10_000L, seen = mapOf(FP_A to 9_000L)))
        assertTrue(admit(nowMs = 11_000L, seen = mapOf(FP_A to 9_000L)))
    }

    @Test
    fun `a future timestamp fails closed and a wrapped gap is not a replay`() {
        assertFalse(admit(nowMs = 0L, seen = mapOf(FP_A to Long.MAX_VALUE)))
        assertFalse(admit(nowMs = Long.MIN_VALUE, seen = mapOf(FP_A to Long.MIN_VALUE + 500)))
        assertTrue(admit(nowMs = Long.MAX_VALUE, seen = mapOf(FP_A to Long.MIN_VALUE)))
    }

    @Test
    fun `a different fingerprint is not debounced by another entry`() {
        assertTrue(admit(info(fingerprint = FP_C), nowMs = 10_000L, seen = mapOf(FP_A to 9_999L)))
    }

    // ---- capacity ------------------------------------------------------------

    @Test
    fun `a full registry refuses a new peer but admits a refresh`() {
        val full = ((0 until AnnounceAdmission.MAX_PEERS - 1).map { fp(it + 10) } + FP_C)
            .associateWith { 0L }
        assertEquals(AnnounceAdmission.MAX_PEERS, full.size)
        assertFalse(admit(info(fingerprint = FP_A), nowMs = 10_000L, seen = full))
        assertTrue(admit(info(fingerprint = FP_C), nowMs = 10_000L, seen = full))
    }

    @Test
    fun `a registry one below the cap admits a new peer`() {
        val nearlyFull = (0 until AnnounceAdmission.MAX_PEERS - 1).associate { fp(it) to 0L }
        assertEquals(AnnounceAdmission.MAX_PEERS - 1, nearlyFull.size)
        assertTrue(admit(info(fingerprint = FP_A), nowMs = 10_000L, seen = nearlyFull))
    }

    @Test
    fun `the cap counts caller state not anything the gate remembers`() {
        val empty = emptyMap<String, Long>()
        assertTrue(admit(nowMs = 10_000L, seen = empty))
        assertTrue(admit(nowMs = 10_000L, seen = empty))
        val full = (0 until AnnounceAdmission.MAX_PEERS).associate { fp(it) to 0L }
        assertFalse(admit(nowMs = 10_000L, seen = full))
        assertTrue(admit(nowMs = 10_000L, seen = empty))
    }

    // ---- totality and purity -------------------------------------------------

    @Test
    fun `hostile field values never throw and never admit`() {
        val hostile = listOf(
            info(fingerprint = "A".repeat(100_000)),
            info(alias = "x".repeat(100_000)),
            info(port = Int.MIN_VALUE),
            info(sourceIp = "\uD800".repeat(1_000)),
            info(fingerprint = "\u0000" + FP_A.drop(1)),
        )
        for (i in hostile) assertFalse(admit(i))
    }

    @Test
    fun `a throwing caller map fails closed`() {
        val throwingGet = object : Map<String, Long> by emptyMap<String, Long>() {
            override fun get(key: String): Long? = error("map broke")
        }
        assertFalse(admit(seen = throwingGet))

        val throwingSize = object : Map<String, Long> by mapOf(FP_A to 0L) {
            override val size: Int get() = error("map broke")
        }
        assertFalse(admit(seen = throwingSize))
    }

    @Test
    fun `the verdict is deterministic and the gate keeps no state`() {
        val registry = mapOf(FP_C to 0L)
        val first = admit(nowMs = 10_000L, seen = registry)
        val full = (0 until AnnounceAdmission.MAX_PEERS).associate { fp(it) to 0L }
        assertFalse(admit(nowMs = 10_000L, seen = full))
        val second = admit(nowMs = 10_000L, seen = registry)
        assertTrue(first)
        assertEquals(first, second)
        assertEquals(1, registry.size)
    }

    private companion object {
        val FP_A: String = "A".repeat(64)
        val FP_B: String = "B".repeat(64)
        val FP_C: String = "C".repeat(64)

        fun fp(seed: Int): String = seed.toString(16).padStart(64, '0').uppercase()
    }
}
