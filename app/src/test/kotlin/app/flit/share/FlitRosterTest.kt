package app.flit.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Queue truth that must hold without a device: the one-batch-per-peer merge
 * rule. Everything else about the queue (grants, dispatch) lives and dies on
 * the device, but the merge is pure and it guards real files.
 */
class FlitRosterTest {

    private fun batch(
        fp: String = "FP",
        alias: String = "André",
        uris: List<String>,
        names: List<String>,
        createdMs: Long = 1000L,
    ) = QueuedSend(fingerprint = fp, alias = alias, uris = uris, names = names, createdMs = createdMs)

    @Test
    fun `a second intake joins the waiting batch — tokens union, batch keeps its age`() {
        val waiting = batch(uris = listOf("/s/a", "content://x/1"), names = listOf("a.jpg", "1.jpg"), createdMs = 1000)
        val later = batch(alias = "André (2nd)", uris = listOf("/s/b", "content://x/2"), names = listOf("b.jpg", "2.jpg"), createdMs = 5000)
        val joined = waiting.mergedWith(later)
        assertEquals(listOf("/s/a", "content://x/1", "/s/b", "content://x/2"), joined.uris)
        assertEquals("André (2nd)", joined.alias) // latest naming wins
        assertEquals(1000L, joined.createdMs) // batch age stays its oldest member
        assertEquals(listOf("a.jpg", "1.jpg", "b.jpg", "2.jpg"), joined.names)
    }

    @Test
    fun `a repeated token joins without doubling — but repeated names stay`() {
        val waiting = batch(uris = listOf("content://x/1"), names = listOf("photo.jpg"), createdMs = 1000)
        val resent = batch(uris = listOf("content://x/1", "/s/crush"), names = listOf("photo.jpg", "photo.jpg"), createdMs = 9000)
        val joined = waiting.mergedWith(resent)
        // same token = same grant, same content — one occurrence is the whole truth
        assertEquals(listOf("content://x/1", "/s/crush"), joined.uris)
        // two files may legitimately share a name; the queue never collapses spoken rows
        assertEquals(listOf("photo.jpg", "photo.jpg", "photo.jpg"), joined.names)
    }

    @Test
    fun `a blank incoming alias leaves the known one standing`() {
        val waiting = batch(alias = "André", uris = listOf("/s/a"), names = listOf("a"))
        val nameless = batch(alias = "", uris = listOf("/s/b"), names = listOf("b"))
        assertEquals("André", waiting.mergedWith(nameless).alias)
    }

    @Test
    fun `a sent batch leaves its tokens — a batch merged in mid-flight stays owed`() {
        val row = batch(uris = listOf("/s/a", "/s/b"), names = listOf("a.jpg", "b.jpg"))
        val flew = batch(uris = listOf("/s/a"), names = listOf("a.jpg"))
        val left = row.without(flew.uris, flew.names)!!
        assertEquals(listOf("/s/b"), left.uris)
        assertEquals(listOf("b.jpg"), left.names)
    }

    @Test
    fun `dropping the last tokens ends the row`() {
        val row = batch(uris = listOf("/s/a"), names = listOf("a.jpg"))
        assertEquals(null, row.without(listOf("/s/a"), listOf("a.jpg")))
    }

    @Test
    fun `display twins leave by first occurrence — the survivor keeps its name`() {
        val row = batch(uris = listOf("/s/a", "/s/b"), names = listOf("same.jpg", "same.jpg"))
        val left = row.without(listOf("/s/a"), listOf("same.jpg"))!!
        assertEquals(listOf("/s/b"), left.uris)
        assertEquals(listOf("same.jpg"), left.names)
    }

    @Test
    fun `the mirror duty rides a merge — and survives the manual half leaving`() {
        val manual = batch(uris = listOf("/s/a"), names = listOf("a.jpg"))
        val mirror = manual.copy(mirror = true, uris = listOf("content://media/1"), names = listOf("IMG_1.jpg"))
        val joined = manual.mergedWith(mirror)
        assertTrue(joined.mirror, "a merged row keeps the mirror duty")
        val left = joined.without(listOf("/s/a"), listOf("a.jpg"))!!
        assertTrue(left.mirror, "the duty survives the manual half leaving")
        assertEquals(listOf("content://media/1"), left.uris)
    }
}
