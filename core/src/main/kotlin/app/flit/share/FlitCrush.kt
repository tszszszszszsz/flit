package app.flit.share

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Crush & send, free: gzip compressible payloads before they hit the wire as
 * "name.ext.gflit", and unpack them back on arrival.
 *
 * - The wire file is a plain gzip archive: a client that has never seen the
 *   extension still receives a valid, self-describing file it can open with
 *   any unzip tool.
 * - Flit receivers stream the unpacked bytes into the chosen folder while the
 *   file keeps its original name.
 * - sha/size/resume all describe the wire bytes, so a crushed transfer
 *   resumes like any other (identical chunk math; assembly is verified
 *   before the one-shot decode).
 */
object FlitCrush {

    /** Marker tail on the wire; stripped back off when unpacking. */
    const val SUFFIX = ".gflit"

    /**
     * Ceiling on decoded bytes from one gzip wire. [crushable] refuses inputs
     * above it, so a well-formed peer cannot legitimately decode to more than
     * this; anything past the ceiling is a decompression bomb.
     */
    const val MAX_UNPACK_BYTES = 512L * 1024 * 1024

    private val TEXT_EXT = setOf(
        "txt", "md", "rtf", "log", "csv", "tsv", "json", "ndjson", "yml", "yaml",
        "ini", "cfg", "conf", "properties", "env", "sql", "html", "htm", "xhtml",
        "css", "scss", "less", "js", "mjs", "cjs", "ts", "jsx", "tsx", "vue", "svelte",
        "py", "rb", "rs", "go", "c", "h", "cpp", "hpp", "cs", "java", "kt", "kts",
        "swift", "sh", "bash", "zsh", "fish", "ps1", "bat", "cmd", "gradle",
        "dart", "php", "lua", "pl", "toml", "xml", "svg", "srt", "vtt", "lrc",
        "makefile",
    )

    fun isCrushed(name: String): Boolean = name.endsWith(SUFFIX)

    /**
     * The name the file regains after unpacking. Always a single component:
     * a name carrying a separator, `..` or an absolute path is refused instead
     * of turning the decode into a write outside the receive folder.
     */
    fun plainName(name: String): String =
        plainNameOrNull(name) ?: throw IllegalArgumentException("unsafe plain name: ${name.take(120)}")

    /** Null when [name] does not resolve to a single safe file name. */
    fun plainNameOrNull(name: String): String? {
        val base = if (isCrushed(name)) name.removeSuffix(SUFFIX) else name
        if (base.isBlank() || base.length > 255) return null
        if (base == "." || base == "..") return null
        if (base.any { it == '/' || it == '\\' || it == '\u0000' }) return null
        if (File(base).isAbsolute) return null
        return base
    }

    /** Tiny payloads gain nothing; media is already compressed. */
    fun crushable(name: String, mime: String?, size: Long): Boolean {
        if (size < 2 * 1024 || size > MAX_UNPACK_BYTES) return false
        if (isCrushed(name)) return false
        if (TEXT_EXT.contains(name.substringAfterLast('.', "").lowercase())) return true
        val m = (mime ?: "").lowercase()
        return m.startsWith("text/") || m == "application/json" || m == "application/xml" ||
            m == "application/yaml" || m == "application/x-yaml" ||
            m == "application/javascript" || m == "application/x-javascript" ||
            m == "application/rss+xml" || m == "image/svg+xml"
    }

    /** Streams [src] into a gzip file at [dst]; caller owns deleting it. */
    fun crushTo(src: InputStream, dst: File) {
        src.use { ins ->
            GZIPOutputStream(dst.outputStream(), 256 * 1024).use { gz ->
                ins.copyTo(gz, 256 * 1024)
            }
        }
    }

    /**
     * Streams a gzip file into its plain bytes (what the receiver keeps).
     *
     * Decoded bytes are capped at [maxBytes] (the public cap by default). The
     * count is taken before each write, so an oversized member never reaches
     * disk; on any failure the partial output is removed instead of being left
     * for the receive ledger to publish as a finished file.
     */
    fun unpack(src: File, dst: File, maxBytes: Long = MAX_UNPACK_BYTES) {
        require(maxBytes >= 0) { "negative unpack cap: $maxBytes" }
        if (Files.isSymbolicLink(dst.toPath())) {
            throw IOException("refusing to unpack through a symlink: ${dst.name}")
        }
        var decoded = 0L
        var complete = false
        try {
            GZIPInputStream(src.inputStream(), 256 * 1024).use { gz ->
                dst.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = gz.read(buf)
                        if (n < 0) break
                        decoded += n
                        if (decoded > maxBytes) {
                            throw IOException(
                                "unpacked payload exceeds $maxBytes bytes (got at least $decoded)"
                            )
                        }
                        out.write(buf, 0, n)
                    }
                    out.flush()
                }
            }
            complete = true
        } finally {
            if (!complete) runCatching { dst.delete() }
        }
    }
}
