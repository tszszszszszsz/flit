package app.flit.share

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    @Test
    fun `the spool allowlist admits spool-shaped direct children of cacheDir`() {
        val cache = dir()
        val good = File(cache, "share-$now-7-notes.txt").apply { writeText("x") }
        assertEquals(good.absoluteFile, FlitSpool.spoolPath(good.absolutePath, cache))
        assertTrue(FlitSpool.validSpoolToken(good.absolutePath, cache))
        // Shape, not existence: a token whose copy has gone still names a spool.
        val missing = File(cache, "queue-$now-11-await.txt")
        assertEquals(missing.absoluteFile, FlitSpool.spoolPath(missing.absolutePath, cache))
    }

    @Test
    fun `the spool allowlist rejects arbitrary absolute paths and URIs`() {
        val cache = dir()
        val elsewhere = File(dir(), "share-$now-8-notes.txt").apply { writeText("x") }
        for (bad in listOf(
            "/etc/passwd",
            elsewhere.absolutePath,
            "content://some/provider",
            "share-$now-9-notes.txt", // relative — resolves outside cacheDir
            File(cache, "notes.txt").absolutePath, // real file, wrong shape
            File(cache, "share-$now-x-notes.txt").absolutePath, // non-numeric parts
        )) {
            assertNull(FlitSpool.spoolPath(bad, cache), "accepted $bad")
            assertFalse(FlitSpool.validSpoolToken(bad, cache), "accepted $bad")
        }
    }

    @Test
    fun `the spool allowlist rejects symlink targets and symlinked parents`() {
        val cache = dir()
        val outside = File(dir(), "secret.txt").apply { writeText("secret") }
        val link = File(cache, "queue-$now-9-notes.txt")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertNull(FlitSpool.spoolPath(link.absolutePath, cache))

        val alias = File(cache, "share-$now-10-alias")
        Files.createSymbolicLink(alias.toPath(), outside.parentFile.toPath())
        val nested = File(alias, "queue-$now-11-notes.txt").apply { writeText("x") }
        assertNull(FlitSpool.spoolPath(nested.absolutePath, cache))
        assertEquals("secret", outside.readText())
    }
}
