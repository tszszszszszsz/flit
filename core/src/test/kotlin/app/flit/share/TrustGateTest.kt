package app.flit.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gate arithmetic, not plumbing: who may send a prepare offer. The two
 * persisted lists live in the caller-owned [TrustGate.Stores], so fresh store
 * instances here model a rotated certificate or an app restart — the gate
 * must never remember a previous verdict on its own.
 */
class TrustGateTest {

    private class MemoryStores(
        pinned: Set<String> = emptySet(),
        known: Set<String> = emptySet(),
    ) : TrustGate.Stores {
        val pins = pinned.toMutableSet()
        val knownFps = known.toMutableSet()
        var pinQueries = 0
        var knownQueries = 0

        override fun pinned(fp: String): Boolean {
            pinQueries++
            return fp in pins
        }

        override fun known(fp: String): Boolean {
            knownQueries++
            return fp in knownFps
        }
    }

    private class ThrowingStores : TrustGate.Stores {
        override fun pinned(fp: String): Boolean = error("store broke")
        override fun known(fp: String): Boolean = error("store broke")
    }

    @Test
    fun `gates off accept a null presented certificate without consulting anything`() {
        val stores = MemoryStores()
        val result = TrustGate.evaluate(FP_A, null, TrustGate.Config(), stores)

        assertTrue(result.accept)
        assertNull(result.reason)
        assertEquals(0, stores.pinQueries)
        assertEquals(0, stores.knownQueries)
    }

    @Test
    fun `gates off accept hostile input and stay total`() {
        val result = TrustGate.evaluate(
            null,
            "not a digest\n".repeat(200),
            TrustGate.Config(onlyTrusted = false, qrOnly = false),
            ThrowingStores(),
        )

        assertTrue(result.accept)
        assertNull(result.reason)
        assertEquals(TrustGate.CODE_CHARS, result.code.length)
    }

    @Test
    fun `an enabled gate with no presented certificate is CertAbsent`() {
        for (config in listOf(
            TrustGate.Config(onlyTrusted = true),
            TrustGate.Config(qrOnly = true),
            TrustGate.Config(onlyTrusted = true, qrOnly = true),
        )) {
            val result = TrustGate.evaluate(FP_A, null, config, MemoryStores(setOf(FP_A), setOf(FP_A)))
            assertFalse(result.accept)
            assertEquals(TrustGate.Reason.CertAbsent, result.reason)
        }
    }

    @Test
    fun `a blank or malformed presented digest counts as no certificate`() {
        val config = TrustGate.Config(onlyTrusted = true)
        for (presented in listOf("", "   ", "not-hex", FP_A.take(32), "Z".repeat(64))) {
            val result = TrustGate.evaluate(FP_A, presented, config, MemoryStores(setOf(FP_A)))
            assertFalse(result.accept)
            assertEquals(TrustGate.Reason.CertAbsent, result.reason, "presented=$presented")
        }
    }

    @Test
    fun `a digest of another certificate is CertMismatch before any list check`() {
        val stores = MemoryStores(setOf(FP_A), setOf(FP_A))
        val result = TrustGate.evaluate(FP_A, FP_B, TrustGate.Config(true, true), stores)

        assertFalse(result.accept)
        assertEquals(TrustGate.Reason.CertMismatch, result.reason)
        assertEquals(0, stores.pinQueries)
        assertEquals(0, stores.knownQueries)
    }

    @Test
    fun `a missing or malformed declared fingerprint cannot claim a presented cert`() {
        val config = TrustGate.Config(onlyTrusted = true)
        for (declared in listOf(null, "", "ABC", "Q".repeat(64))) {
            val result = TrustGate.evaluate(declared, FP_A, config, MemoryStores(setOf(FP_A)))
            assertFalse(result.accept)
            assertEquals(TrustGate.Reason.CertMismatch, result.reason, "declared=$declared")
        }
    }

    @Test
    fun `case colons and whitespace do not change the verdict`() {
        val declared = "  ${FP_A.lowercase().chunked(2).joinToString(":")}  "
        val presented = FP_A.lowercase()
        val result = TrustGate.evaluate(
            declared,
            presented,
            TrustGate.Config(true, true),
            MemoryStores(setOf(FP_A), setOf(FP_A)),
        )

        assertTrue(result.accept)
        assertNull(result.reason)
        assertEquals(FP_A.take(12), result.code)
    }

    @Test
    fun `onlyTrusted accepts a pinned peer and refuses an unpinned one`() {
        val config = TrustGate.Config(onlyTrusted = true)
        val pinned = TrustGate.evaluate(FP_A, FP_A, config, MemoryStores(setOf(FP_A)))
        val unpinned = TrustGate.evaluate(FP_A, FP_A, config, MemoryStores(setOf(FP_B)))

        assertTrue(pinned.accept)
        assertNull(pinned.reason)
        assertFalse(unpinned.accept)
        assertEquals(TrustGate.Reason.NotTrusted, unpinned.reason)
    }

    @Test
    fun `qrOnly accepts a QR-known peer and refuses an unknown one`() {
        val config = TrustGate.Config(qrOnly = true)
        val known = TrustGate.evaluate(FP_A, FP_A, config, MemoryStores(known = setOf(FP_A)))
        val unknown = TrustGate.evaluate(FP_A, FP_A, config, MemoryStores(known = setOf(FP_B)))

        assertTrue(known.accept)
        assertNull(known.reason)
        assertFalse(unknown.accept)
        assertEquals(TrustGate.Reason.QrUnknown, unknown.reason)
    }

    @Test
    fun `both gates require both lists and report the first missing one`() {
        val config = TrustGate.Config(true, true)
        val both = TrustGate.evaluate(FP_A, FP_A, config, MemoryStores(setOf(FP_A), setOf(FP_A)))
        val pinnedOnly = TrustGate.evaluate(FP_A, FP_A, config, MemoryStores(setOf(FP_A), setOf(FP_B)))
        val knownOnly = TrustGate.evaluate(FP_A, FP_A, config, MemoryStores(setOf(FP_B), setOf(FP_A)))
        val neither = TrustGate.evaluate(FP_A, FP_A, config, MemoryStores())

        assertTrue(both.accept)
        assertEquals(TrustGate.Reason.QrUnknown, pinnedOnly.reason)
        assertEquals(TrustGate.Reason.NotTrusted, knownOnly.reason)
        assertEquals(TrustGate.Reason.NotTrusted, neither.reason)
    }

    @Test
    fun `the short code is 12 hex characters and never echoes input`() {
        val valid = TrustGate.evaluate(FP_A, FP_A, TrustGate.Config(true, true), MemoryStores(setOf(FP_A), setOf(FP_A)))
        assertEquals(FP_A.take(12), valid.code)

        val hostile = "SECRET-cert-bytes\n" + "z".repeat(2_000)
        val a = TrustGate.evaluate(hostile, FP_A, TrustGate.Config(), MemoryStores())
        val b = TrustGate.evaluate(hostile, FP_A, TrustGate.Config(), MemoryStores())
        // The extra byte is inside the bounded prefix that is actually hashed.
        val otherInput = "SECRET-cert-bytesX\n" + "z".repeat(2_000)
        val other = TrustGate.evaluate(otherInput, FP_A, TrustGate.Config(), MemoryStores())

        for (code in listOf(a.code, b.code, other.code)) {
            assertEquals(12, code.length)
            assertTrue(code.all { it in "0123456789ABCDEF" }, "code=$code")
            assertFalse(code.contains("SECRET"))
            assertFalse(code.contains("\n"))
        }
        assertEquals(a.code, b.code)
        assertNotEquals(a.code, other.code)
    }

    @Test
    fun `a throwing store fails closed when a gate is on`() {
        val pinConfig = TrustGate.Config(onlyTrusted = true)
        val qrConfig = TrustGate.Config(qrOnly = true)

        assertEquals(
            TrustGate.Reason.NotTrusted,
            TrustGate.evaluate(FP_A, FP_A, pinConfig, ThrowingStores()).reason,
        )
        assertEquals(
            TrustGate.Reason.QrUnknown,
            TrustGate.evaluate(FP_A, FP_A, qrConfig, ThrowingStores()).reason,
        )
    }

    @Test
    fun `rotation is judged by a fresh store not by the old pin`() {
        val config = TrustGate.Config(onlyTrusted = true)
        val oldStore = MemoryStores(setOf(FP_A))

        assertTrue(TrustGate.evaluate(FP_A, FP_A, config, oldStore).accept)
        val rotated = TrustGate.evaluate(FP_B, FP_B, config, oldStore)
        assertFalse(rotated.accept)
        assertEquals(TrustGate.Reason.NotTrusted, rotated.reason)

        val newStore = MemoryStores(setOf(FP_B))
        assertTrue(TrustGate.evaluate(FP_B, FP_B, config, newStore).accept)
        assertFalse(TrustGate.evaluate(FP_A, FP_A, config, newStore).accept)
    }

    @Test
    fun `a restart with an empty store forgets QR-known peers`() {
        val config = TrustGate.Config(qrOnly = true)
        assertTrue(TrustGate.evaluate(FP_A, FP_A, config, MemoryStores(known = setOf(FP_A))).accept)

        val restarted = MemoryStores()
        val forgotten = TrustGate.evaluate(FP_A, FP_A, config, restarted)
        assertFalse(forgotten.accept)
        assertEquals(TrustGate.Reason.QrUnknown, forgotten.reason)

        val nextPeer = MemoryStores(known = setOf(FP_B))
        assertFalse(TrustGate.evaluate(FP_A, FP_A, config, nextPeer).accept)
        assertTrue(TrustGate.evaluate(FP_B, FP_B, config, nextPeer).accept)
    }

    @Test
    fun `no verdict is cached between calls with different stores`() {
        val config = TrustGate.Config(true, true)
        val permissive = MemoryStores(setOf(FP_A), setOf(FP_A))
        val strict = MemoryStores(setOf(FP_B), setOf(FP_B))

        assertTrue(TrustGate.evaluate(FP_A, FP_A, config, permissive).accept)
        assertFalse(TrustGate.evaluate(FP_A, FP_A, config, strict).accept)
        assertTrue(TrustGate.evaluate(FP_A, FP_A, config, permissive).accept)
    }

    private companion object {
        val FP_A: String = "A".repeat(TrustGate.MIN_FINGERPRINT_CHARS)
        val FP_B: String = "B".repeat(TrustGate.MIN_FINGERPRINT_CHARS)
    }
}
