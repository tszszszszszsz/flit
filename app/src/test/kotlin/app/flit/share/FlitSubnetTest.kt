package app.flit.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sweep's subnet math, pure: each answer here decides which doors the
 * phone knocks on. Wrong bounds mean a broadcast hit or a /16 crawl — both
 * rude on someone else's network, so the armor lives these tests first.
 */
class FlitSubnetTest {

    @Test
    fun `a slash-24 walks usable hosts ascending — network, broadcast and self never touched`() {
        val hosts = FlitSubnet.hosts("192.168.100.80", 24)
        assertEquals(253, hosts.size)
        assertEquals("192.168.100.1", hosts.first())
        assertEquals("192.168.100.254", hosts.last())
        assertFalse(hosts.contains("192.168.100.0"))
        assertFalse(hosts.contains("192.168.100.255"))
        assertFalse(hosts.contains("192.168.100.80")) // our own door is not a knock
        // ascending, by numeric tail
        val tails = hosts.map { it.substringAfterLast('.').toInt() }
        assertEquals(tails.sorted(), tails)
        // first/second/last checked — spot the 10th too, so lexicographic slips show
        assertEquals("192.168.100.10", hosts[9])
    }

    @Test
    fun `a slash-30 walks its two usable hosts and self is filtered separately`() {
        // self on the network address (never a knock target anyway): the whole walk stands
        assertEquals(listOf("10.0.0.5", "10.0.0.6"), FlitSubnet.hosts("10.0.0.4", 30))
        // 10.0.0.5 is a member of this walk: filtered, leaving the one neighbor
        assertEquals(listOf("10.0.0.6"), FlitSubnet.hosts("10.0.0.5", 30))
    }

    @Test
    fun `a slash-31 and a slash-32 are no neighborhoods — empty, no walk`() {
        assertEquals(emptyList(), FlitSubnet.hosts("192.168.100.0", 31))
        assertEquals(emptyList(), FlitSubnet.hosts("192.168.100.1", 31))
        assertEquals(emptyList(), FlitSubnet.hosts("10.9.8.7", 32))
    }

    @Test
    fun `IPv6 is not walked — link-local sweeping is another animal`() {
        assertEquals(emptyList(), FlitSubnet.hosts("fe80::1234", 64))
        assertEquals(emptyList(), FlitSubnet.hosts("::1", 128))
    }

    @Test
    fun `prefixes that are not 8 through 32 walk nothing`() {
        assertEquals(emptyList(), FlitSubnet.hosts("192.168.100.80", 0))
        assertEquals(emptyList(), FlitSubnet.hosts("192.168.100.80", 7))
        assertEquals(emptyList(), FlitSubnet.hosts("192.168.100.80", 33))
        assertEquals(emptyList(), FlitSubnet.hosts("192.168.100.80", -1))
    }

    @Test
    fun `a slash-16 is not a neighborhood — past the cap the walk is refused`() {
        assertTrue(FlitSubnet.hosts("172.16.30.4", 16).isEmpty())
        assertTrue(FlitSubnet.hosts("10.9.8.7", 8).isEmpty())
    }

    @Test
    fun `the cap itself is a boundary — a slash-24 fits whole at 253, dies at 252`() {
        assertEquals(253, FlitSubnet.hosts("10.0.0.1", 24, cap = 253).size)
        assertTrue(FlitSubnet.hosts("10.0.0.1", 24, cap = 252).isEmpty())
    }

    @Test
    fun `label names the subnet, and never invents one for garbage`() {
        assertEquals("192.168.100.0/24", FlitSubnet.label("192.168.100.80", 24))
        assertEquals("10.0.0.4/30", FlitSubnet.label("10.0.0.5", 30))
        assertEquals("(not-an-ip/24)", FlitSubnet.label("not-an-ip", 24))
        assertEquals("(fe80::1234/64)", FlitSubnet.label("fe80::1234", 64))
    }
}
