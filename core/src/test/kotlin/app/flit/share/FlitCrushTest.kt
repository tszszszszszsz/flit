package app.flit.share

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The crush pass's file-intake rules: a bounded decode that never leaves a
 * half-file, a single-component plain name, and the roundtrip those two must
 * not break. Pure JVM on a temp root, no Robolectric.
 */
class FlitCrushTest {

    private fun dir(): File = Files.createTempDirectory("flitcrush").toFile()

    private fun gzip(dst: File, plain: ByteArray) {
        GZIPOutputStream(dst.outputStream(), 64 * 1024).use { it.write(plain) }
    }

    @Test
    fun `the decode ceiling is five hundred twelve mebibytes`() {
        assertEquals(512L * 1024 * 1024, FlitCrush.MAX_UNPACK_BYTES)
    }

    @Test
    fun `a gzip bomb is refused and its partial output removed`() {
        val root = dir()
        val bomb = File(root, "bomb.gflit")
        val cap = 64L * 1024
        val block = ByteArray(cap.toInt())
        GZIPOutputStream(bomb.outputStream(), 64 * 1024).use { gz ->
            repeat(40) { gz.write(block) } // 2.5 MiB decoded against a 64 KiB cap
        }
        val partial = File(root, "bomb.txt")
        assertFailsWith<IOException> { FlitCrush.unpack(bomb, partial, cap) }
        assertFalse(partial.exists(), "partial output survived a refused decode")

        // The refusal is scoped to that decode: an honest wire still unpacks.
        val good = File(root, "good.gflit")
        gzip(good, "hello".toByteArray())
        val out = File(root, "good.txt")
        FlitCrush.unpack(good, out, cap)
        assertEquals("hello", out.readText())
    }

    @Test
    fun `crush then unpack roundtrips into the plain name`() {
        val root = dir()
        val payload = (1..400).joinToString("\n") { "line $it: the quick brown fox" }
        val src = File(root, "notes.txt").apply { writeText(payload) }
        val wire = File(root, "notes.txt.gflit")
        FlitCrush.crushTo(src.inputStream(), wire)
        assertTrue(FlitCrush.isCrushed(wire.name))
        assertEquals("notes.txt", FlitCrush.plainName(wire.name))
        val plain = File(root, FlitCrush.plainName(wire.name))
        FlitCrush.unpack(wire, plain)
        assertEquals(payload, plain.readText())
    }

    @Test
    fun `plain names reject traversal, separators and absolute paths`() {
        assertEquals("notes.txt", FlitCrush.plainName("notes.txt.gflit"))
        assertEquals("notes.txt", FlitCrush.plainName("notes.txt"))
        for (bad in listOf(
            "../etc/passwd.gflit", "..\\evil.gflit", "/tmp/x.gflit",
            "a/b.gflit", "a\\b.gflit", "..", ".", ".gflit", "a\u0000b.gflit",
        )) {
            assertNull(FlitCrush.plainNameOrNull(bad), "accepted $bad")
            assertFailsWith<IllegalArgumentException> { FlitCrush.plainName(bad) }
        }
    }

    @Test
    fun `unpack refuses a symlink output instead of writing through it`() {
        val root = dir()
        val good = File(root, "good.gflit")
        gzip(good, "hello".toByteArray())
        val outside = File(dir(), "secret.txt")
        val link = File(root, "plain.txt")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertFailsWith<IOException> { FlitCrush.unpack(good, link) }
        assertFalse(outside.exists(), "the symlink target was written")
    }
}
