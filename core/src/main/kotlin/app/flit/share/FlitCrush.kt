package app.flit.share

import java.io.File
import java.io.InputStream
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

    /** The name the file regains after unpacking. */
    fun plainName(name: String): String =
        if (isCrushed(name)) name.removeSuffix(SUFFIX) else name

    /** Tiny payloads gain nothing; media is already compressed. */
    fun crushable(name: String, mime: String?, size: Long): Boolean {
        if (size < 2 * 1024 || size > 512L * 1024 * 1024) return false
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

    /** Streams a gzip file into its plain bytes (what the receiver keeps). */
    fun unpack(src: File, dst: File) {
        GZIPInputStream(src.inputStream(), 256 * 1024).use { gz ->
            dst.outputStream().use { out ->
                gz.copyTo(out, 256 * 1024)
            }
        }
    }
}
