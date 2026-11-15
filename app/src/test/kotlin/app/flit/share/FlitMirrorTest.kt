package app.flit.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The album mirror's device-free truth: the folder-vs-ledger diff. The
 * watcher, the scan, and the flush are device reality; the diff is the law
 * that keeps the mirror from ever re-sending a file.
 */
class FlitMirrorTest {

    private fun shot(name: String, size: Long) =
        FlitMirror.Shot(name, size, "/sdcard/DCIM/Camera/$name")

    @Test
    fun `the diff offers only files the ledger has never marked`() {
        val folder = listOf(
            shot("IMG_1.jpg", 4_000_000),
            shot("IMG_2.jpg", 5_000_000),
            shot("IMG_3.jpg", 6_000_000),
        )
        val ledger = listOf(FlitMirror.ledgerToken("IMG_1.jpg", 4_000_000))
        val plan = FlitMirror.plan(folder, ledger)
        assertEquals(listOf("IMG_2.jpg", "IMG_3.jpg"), plan.map { it.name })
    }

    @Test
    fun `a zero-length file is a write still in progress — never a candidate`() {
        val folder = listOf(shot("IMG_writing.jpg", 0L))
        assertTrue(FlitMirror.plan(folder, emptyList()).isEmpty())
        assertFalse(FlitMirror.notice("IMG_writing.jpg", 0L, emptyList()))
    }

    @Test
    fun `a size change is a different file — the edit mirrors too`() {
        val ledger = listOf(FlitMirror.ledgerToken("IMG_1.jpg", 4_000_000))
        assertTrue(FlitMirror.plan(listOf(shot("IMG_1.jpg", 4_500_000)), ledger).isNotEmpty())
        assertTrue(FlitMirror.notice("IMG_1.jpg", 4_500_000, ledger))
    }

    @Test
    fun `the ledger token survives the round trip`() {
        val token = FlitMirror.ledgerToken("IMG_20261114_101010.jpg", 3_141_592L)
        assertEquals(token, FlitMirror.Shot("IMG_20261114_101010.jpg", 3_141_592L, "/x").ledgerToken)
    }

    @Test
    fun `an already-marked file is quiet on re-notice`() {
        val ledger = listOf(FlitMirror.ledgerToken("IMG_1.jpg", 4_000_000))
        assertFalse(FlitMirror.notice("IMG_1.jpg", 4_000_000L, ledger))
        assertTrue(FlitMirror.notice("IMG_9.jpg", 4_000_000L, ledger))
    }
}
