package app.flit.share

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The spool reaper's rules, pure: age, name, and the send-later queue's own
 * memory — nothing else. Real file mtimes on a temp root; no Robolectric.
 */
class FlitSpoolTest {

    private fun dir(): File = Files.createTempDirectory("flitspool").toFile()

    private fun spool(name: String, ageMs: Long, root: File = dir()): File {
        val f = File(root, name).apply { writeText("x") }
        f.setLastModified(ageMs)
        return f
    }

    private val now = 10_000_000_000L
    private val old = now - FlitSpool.STAGING_WINDOW_MS
    private val young = now - 1_000
    @Test
    fun `killed sessions' copies die — old, spool-named, unremembered`() {
        val f = spool("share-$now-1-notes.txt", old)
        assertEquals(listOf(f), FlitSpool.stale(listOf(f), emptyList(), now))
    }

    @Test
    fun `young copies are never litter — a live staging may precede engine start`() {
        val f = spool("share-$now-1-notes.txt", young)
        assertTrue(FlitSpool.stale(listOf(f), emptyList(), now).isEmpty())
    }

    @Test
    fun `the send-later queue's whitelist wins unconditionally`() {
        val queued = spool("share-$now-1-notes.txt", old)
        assertTrue(FlitSpool.stale(listOf(queued), listOf(queued.absolutePath), now).isEmpty())
        // A URI token never matches a path — it neither saves nor kills others.
        val other = spool("queue-$now-2-report.pdf", old)
        assertEquals(listOf(other), FlitSpool.stale(listOf(other), listOf("content://some/provider"), now))
    }

    @Test
    fun `crush copies are engine litter too — the dir never joined the queue`() {
        val crush = spool("crush-98123-ledger.gflit", old)
        assertEquals(listOf(crush), FlitSpool.stale(listOf(crush), emptyList(), now))
    }

    @Test
    fun `look-alikes and directories never join the sweep`() {
        val root = dir()
        val nested = File(root, "crush-$now-1-nested").apply { mkdirs() }
        val renamed = File(root, "renamed-123-file.txt.hide")
        assertTrue(FlitSpool.stale(listOf(nested, renamed), emptyList(), now).isEmpty())
    }
}
