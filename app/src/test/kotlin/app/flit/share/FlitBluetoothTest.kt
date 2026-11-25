package app.flit.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The RFCOMM carrier's pure policy, tested with no radio and no Android
 * runtime: which permission names are real on each API level, the calm
 * backoff, how an accept() ended, and what the logs are allowed to claim.
 */
class FlitBluetoothTest {

    @Test
    fun `API 26-30 asks for the legacy permissions the manifest declares`() {
        val legacy = listOf(FlitBluetoothPolicy.PERM_LEGACY, FlitBluetoothPolicy.PERM_LEGACY_ADMIN)
        assertEquals(legacy, FlitBluetoothPolicy.requiredPermissions(26))
        assertEquals(legacy, FlitBluetoothPolicy.requiredPermissions(30))
        assertFalse(legacy.contains(FlitBluetoothPolicy.PERM_CONNECT))
    }

    @Test
    fun `permission switch flips at Android 12, not earlier`() {
        assertEquals(
            listOf(FlitBluetoothPolicy.PERM_CONNECT),
            FlitBluetoothPolicy.requiredPermissions(FlitBluetoothPolicy.RUNTIME_PERM_SDK),
        )
        assertEquals(
            listOf(FlitBluetoothPolicy.PERM_CONNECT),
            FlitBluetoothPolicy.requiredPermissions(36),
        )
        assertEquals(31, FlitBluetoothPolicy.RUNTIME_PERM_SDK)
    }

    @Test
    fun `all grants are required and an empty answer is never yes`() {
        val legacy = setOf(FlitBluetoothPolicy.PERM_LEGACY, FlitBluetoothPolicy.PERM_LEGACY_ADMIN)
        assertTrue(FlitBluetoothPolicy.allRequiredGranted(30, legacy))
        assertFalse(FlitBluetoothPolicy.allRequiredGranted(30, setOf(FlitBluetoothPolicy.PERM_LEGACY)))
        assertFalse(FlitBluetoothPolicy.allRequiredGranted(30, emptySet()))
    }

    @Test
    fun `a modern connect-only grant cannot stand in for legacy RFCOMM rights`() {
        // This was the bug: on API 26-30 the carrier looked only at
        // BLUETOOTH_CONNECT — a permission that is neither requested nor
        // granted there — so every dial and listen failed.
        val connectOnly = setOf(FlitBluetoothPolicy.PERM_CONNECT)
        assertFalse(FlitBluetoothPolicy.allRequiredGranted(30, connectOnly))
        assertTrue(FlitBluetoothPolicy.allRequiredGranted(31, connectOnly))
        assertTrue(
            FlitBluetoothPolicy.allRequiredGranted(30, connectOnly + FlitBluetoothPolicy.PERM_LEGACY + FlitBluetoothPolicy.PERM_LEGACY_ADMIN),
        )
    }

    @Test
    fun `backoff doubles and caps at one minute`() {
        var wait = FlitBluetoothPolicy.RETRY_INITIAL_MS
        assertEquals(2_000L, wait)
        val waits = mutableListOf(wait)
        repeat(10) {
            wait = FlitBluetoothPolicy.nextBackoffMs(wait)
            waits.add(wait)
        }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L), waits.take(5))
        assertEquals(60_000L, waits[5])
        assertEquals(60_000L, waits.last(), "capped waits must stay capped")
        assertEquals(60_000L, FlitBluetoothPolicy.RETRY_MAX_MS)
    }

    @Test
    fun `backoff cannot overflow into a negative delay`() {
        assertEquals(2_000L, FlitBluetoothPolicy.nextBackoffMs(0))
        assertEquals(2_000L, FlitBluetoothPolicy.nextBackoffMs(-1))
        assertEquals(60_000L, FlitBluetoothPolicy.nextBackoffMs(Long.MAX_VALUE))
        assertTrue(FlitBluetoothPolicy.nextBackoffMs(59_000L) > 0)
    }

    @Test
    fun `retry log names the reason and the actual delay, not a listening claim`() {
        val line = FlitBluetoothPolicy.retryLog("bluetooth link accept failed: broken pipe", 8_000L)
        assertEquals("bluetooth link accept failed: broken pipe — retry in 8s", line)
        assertFalse(line.contains("listening", ignoreCase = true))
    }

    @Test
    fun `cancellation is a quiet stop, never a failure or a re-listen`() {
        val decision = FlitBluetoothPolicy.acceptExit(active = true, cancelled = true, failure = "socket closed")
        assertEquals(FlitBluetoothPolicy.AcceptAction.STOP, decision.action)
        assertNull(decision.log)
    }

    @Test
    fun `an inactive scope is a stop even when accept threw`() {
        val decision = FlitBluetoothPolicy.acceptExit(active = false, cancelled = false, failure = "socket closed")
        assertEquals(FlitBluetoothPolicy.AcceptAction.STOP, decision.action)
        assertNull(decision.log)
    }

    @Test
    fun `a transient accept failure re-listens and says why`() {
        val decision = FlitBluetoothPolicy.acceptExit(
            active = true, cancelled = false, failure = "read failed, socket might closed",
        )
        assertEquals(FlitBluetoothPolicy.AcceptAction.RELISTEN, decision.action)
        assertEquals("bluetooth link accept failed: read failed, socket might closed", decision.log)
        assertFalse(decision.log!!.contains("listening", ignoreCase = true))
    }

    @Test
    fun `a null socket re-listens with an honest reason`() {
        val decision = FlitBluetoothPolicy.acceptExit(
            active = true, cancelled = false, failure = "no socket returned",
        )
        assertEquals(FlitBluetoothPolicy.AcceptAction.RELISTEN, decision.action)
        assertEquals("bluetooth link accept failed: no socket returned", decision.log)
    }

    @Test
    fun `a clean loop exit while active still re-listens`() {
        val decision = FlitBluetoothPolicy.acceptExit(active = true, cancelled = false, failure = null)
        assertEquals(FlitBluetoothPolicy.AcceptAction.RELISTEN, decision.action)
        assertEquals("bluetooth link accept ended — will listen again", decision.log)
        assertFalse(decision.log!!.contains("listening", ignoreCase = true))
    }

    @Test
    fun `timeout and failure handshake logs are different claims`() {
        val timeout = FlitBluetoothPolicy.handshakeFailureLog("AA:BB", timeout = true, detail = null)
        val failed = FlitBluetoothPolicy.handshakeFailureLog("AA:BB", timeout = false, detail = "bad certificate")
        assertEquals("bluetooth link TLS handshake timed out: AA:BB", timeout)
        assertEquals("bluetooth link TLS handshake failed: bad certificate", failed)
        assertTrue(timeout.contains("timed out"))
        assertTrue(failed.contains("failed"))
    }

    @Test
    fun `the receiver handshake bound is finite and calm`() {
        assertTrue(FlitBluetoothPolicy.HANDSHAKE_TIMEOUT_MS > 0)
        assertTrue(FlitBluetoothPolicy.HANDSHAKE_TIMEOUT_MS <= 60_000L)
    }
}
