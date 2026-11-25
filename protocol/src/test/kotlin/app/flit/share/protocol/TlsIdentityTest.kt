package app.flit.share.protocol

import app.flit.share.protocol.tls.PinCache
import app.flit.share.protocol.tls.TlsIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * QA-SEC P2: the shared pin store must stay bounded under a flood of first-seen
 * Circle/panic certificates, without changing any pin/verify helper semantics.
 */
class PinCacheTest {

    @Test
    fun `pin cache is bounded and evicts the least recently accessed entry`() {
        val cache = PinCache(3)
        val first = TlsIdentity.generate()
        val second = TlsIdentity.generate()
        val third = TlsIdentity.generate()
        val overflow = TlsIdentity.generate()

        listOf(first, second, third).forEach { cache.put(it.fingerprint, it.certificate) }
        assertEquals(3, cache.size())

        cache.put(overflow.fingerprint, overflow.certificate)
        assertEquals(3, cache.size(), "cache must never exceed its cap")
        assertNull(cache.get(first.fingerprint), "least recently accessed pin evicted")
        assertNotNull(cache.get(second.fingerprint))
        assertNotNull(cache.get(third.fingerprint))
        assertNotNull(cache.get(overflow.fingerprint))
    }

    @Test
    fun `a read refreshes recency so actively verified pins survive`() {
        val cache = PinCache(3)
        val a = TlsIdentity.generate()
        val b = TlsIdentity.generate()
        val c = TlsIdentity.generate()
        val d = TlsIdentity.generate()
        listOf(a, b, c).forEach { cache.put(it.fingerprint, it.certificate) }

        // Reading B makes A the least recently accessed entry.
        assertNotNull(cache.get(b.fingerprint))
        cache.put(d.fingerprint, d.certificate)

        assertEquals(3, cache.size())
        assertNull(cache.get(a.fingerprint))
        assertNotNull(cache.get(b.fingerprint))
        assertNotNull(cache.get(c.fingerprint))
        assertNotNull(cache.get(d.fingerprint))
    }

    @Test
    fun `fingerprint keys are normalized and forget and clear remove pins`() {
        val cache = PinCache(2)
        val id = TlsIdentity.generate()
        cache.put(id.fingerprint, id.certificate)

        assertNotNull(cache.get("  ${id.fingerprint.lowercase()}  "))
        cache.remove(id.fingerprint.lowercase())
        assertNull(cache.get(id.fingerprint))

        cache.put(id.fingerprint, id.certificate)
        val other = TlsIdentity.generate()
        cache.put(other.fingerprint, other.certificate)
        assertEquals(2, cache.size())
        cache.clear()
        assertEquals(0, cache.size())
    }

    @Test
    fun `pinning preserves verification through all entry points`() {
        val id = TlsIdentity.generate()
        val canonical = "canonical bytes".toByteArray()
        val sig = id.sign(canonical)
        val fp = TlsIdentity.pin(id.certificate)
        try {
            assertEquals(id.fingerprint, fp)
            assertNotNull(TlsIdentity.pinned("  ${fp.lowercase()}  "))
            assertTrue(TlsIdentity.verify(fp, canonical, sig))
            assertTrue(TlsIdentity.verify(fp.lowercase(), canonical, sig), "caller case is normalized")
            assertTrue(id.verify(fp, canonical, sig), "instance helper")
            assertTrue(TlsIdentity.verifyPanic(fp, canonical, sig), "panic helper")
            assertTrue(id.verifyPanic(fp, canonical, sig), "panic instance helper")
            assertFalse(TlsIdentity.verify(fp, canonical + "!".toByteArray(), sig), "tampered bytes")

            TlsIdentity.forgetPin(fp)
            assertNull(TlsIdentity.pinned(fp))
            assertFalse(TlsIdentity.verify(fp, canonical, sig), "forgotten pin no longer verifies")
        } finally {
            TlsIdentity.forgetPin(fp)
        }
    }

    @Test
    fun `re-pinning the same certificate is idempotent and does not grow the store`() {
        val cache = PinCache(2)
        val id = TlsIdentity.generate()
        cache.put(id.fingerprint, id.certificate)
        cache.put(id.fingerprint, id.certificate)
        assertEquals(1, cache.size())
        assertNotNull(cache.get(id.fingerprint))
    }

    @Test
    fun `cert-in-hand verification never touches the shared pin store`() {
        val id = TlsIdentity.generate()
        val canonical = "embedded cert".toByteArray()
        val sig = id.sign(canonical)
        assertTrue(TlsIdentity.verifyWithCertificate(id.certificate, canonical, sig))
        assertNull(TlsIdentity.pinned(id.fingerprint))
        assertFalse(TlsIdentity.verifyWithCertificate(id.certificate, canonical + "!".toByteArray(), sig))
    }
}
