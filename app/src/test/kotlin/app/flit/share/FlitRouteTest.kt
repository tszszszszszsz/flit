package app.flit.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Carrier migration's device-free truth: the candidate order, the hunting
 * cadence, and the accept rule. The hunter loop, the probes, and the
 * resume are device reality; this is the law that keeps a reassigned
 * address from ever handing an owed batch to a stranger, and keeps the
 * hunt calm.
 */
class FlitRouteTest {

    private fun route(host: String, port: Int = 53317, tls: Boolean = true) =
        FlitRoute.Route(host, port, tls)

    @Test
    fun `the typed relay is parsed once and off when empty or malformed`() {
        assertEquals("box.example" to 53317, FlitRoute.parseRelay("box.example:53317"))
        assertEquals("192.168.100.12" to 443, FlitRoute.parseRelay("  192.168.100.12:443  "))
        assertEquals(null, FlitRoute.parseRelay(null))
        assertEquals(null, FlitRoute.parseRelay("   "))
        assertEquals(null, FlitRoute.parseRelay("box.example"))
        assertEquals(null, FlitRoute.parseRelay("box.example:0"))
        assertEquals(null, FlitRoute.parseRelay("box.example:65536"))
        assertEquals(null, FlitRoute.parseRelay("box.example:port"))
        assertEquals(null, FlitRoute.parseRelay(":53317"))
    }

    @Test
    fun `candidates come in priority order — live row, remembered route, typed addresses`() {
        val cands = FlitRoute.candidates(
            live = route("192.168.100.66"),
            remembered = route("192.168.100.12"),
            manual = listOf(route("10.0.0.7", 53318)),
        )
        assertEquals(
            listOf("192.168.100.66", "192.168.100.12", "10.0.0.7"),
            cands.map { it.host },
        )
        assertEquals(listOf(53317, 53317, 53318), cands.map { it.port })
    }

    @Test
    fun `a route listed twice is probed once — and nothing is null-born`() {
        val cands = FlitRoute.candidates(
            live = route("192.168.100.12"),
            remembered = route("192.168.100.12"),
            manual = listOf(route("192.168.100.12"), route("10.0.0.7")),
        )
        assertEquals(listOf(route("192.168.100.12"), route("10.0.0.7")), cands)
        assertTrue(FlitRoute.candidates(null, null, emptyList()).isEmpty())
    }

    @Test
    fun `the hunt stays calm — two seconds after a failure, then backing off to one beat a minute`() {
        assertEquals(2_000L, FlitRoute.huntDelayMs(0))
        assertEquals(5_000L, FlitRoute.huntDelayMs(1))
        assertEquals(15_000L, FlitRoute.huntDelayMs(2))
        assertEquals(30_000L, FlitRoute.huntDelayMs(3))
        assertEquals(60_000L, FlitRoute.huntDelayMs(4))
        assertEquals(60_000L, FlitRoute.huntDelayMs(400))
    }

    @Test
    fun `the fingerprint is the whole accept rule — no stranger ever inherits a batch`() {
        assertTrue(FlitRoute.accepts("abc123", "abc123"))
        assertFalse(FlitRoute.accepts("stranger", "abc123"))
        // An answer with no identity at all is not an answer.
        assertFalse(FlitRoute.accepts(null, "abc123"))
    }
}
