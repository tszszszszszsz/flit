package app.flit.share

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Phase C: the spool reaper's rules — cache copies with no future.
 *
 * Spooled copies share a life with their session: a session that ends sends
 * them on or deletes them ([cleanSpools]); a session the OS killed mid-flight
 * leaves them behind with nobody to come back. The reaper's one sweep at
 * engine start collects that litter — but nothing else:
 *
 *  - the send-later queue can reference a spool by absolute path and
 *    outlives restarts; anything the queue remembers is immortal until
 *    the queue itself forgets (the whitelist wins unconditionally),
 *  - a copy staged in the last staging window may belong to a live
 *    staging flow that hasn't started the engine yet — age derived from
 *    file mtime is the only safe opinion here.
 *
 * Pure JVM: the caller lists candidates; this decides.
 */
public object FlitSpool {

    /**
     * The staging window. Anything younger than this may be a live intake; the
     * window spans staging through engine start, which in the WKD case is a
     * share-sheet detour of seconds — a day is generous on purpose.
     */
    const val STAGING_WINDOW_MS = 24 * 60 * 60 * 1000L

    /** File-name prefixes `spoolUri` and the crush pass mint; engine-owned copies. */
    val SPOOL_TAGS = setOf("share", "queue", "crush")

    /**
     * Shape minted by the share intake: `<tag>-<millis>-<sequence>-<safe>`.
     * A queue token that does not match it is not an engine-owned spool, even
     * if it happens to point at a readable file.
     */
    private val SPOOL_NAME = Regex(
        "^(?:${SPOOL_TAGS.sorted().joinToString("|")})-[0-9]+-[0-9]+-.*$"
    )

    /**
     * The one gate for a persisted queue token that claims to name a spool.
     * [token] is accepted only when it is a direct child of [cacheDir], has a
     * spool-minted name, and no symlink (the file itself, its parent, or the
     * path it resolves to) can move the read or write outside the cache.
     * Returns the validated file, or null for every other token — URIs,
     * absolute paths elsewhere on disk, traversal, and symlink targets alike.
     */
    fun spoolPath(token: String, cacheDir: File): File? {
        if (token.isBlank() || token.length > 4096) return null
        val base = runCatching {
            cacheDir.toPath().toAbsolutePath().normalize()
        }.getOrNull() ?: return null
        val baseReal = runCatching { base.toRealPath() }.getOrNull() ?: return null
        val target = runCatching {
            File(token).toPath().toAbsolutePath().normalize()
        }.getOrNull() ?: return null
        val name = target.fileName?.toString() ?: return null
        if (!SPOOL_NAME.matches(name)) return null
        if (name.any { it == '/' || it == '\\' || it == '\u0000' }) return null
        // One component below the cache directory and nowhere else: a nested
        // token or an absolute path from another tree stops here.
        if (target.parent != base) return null
        if (Files.isSymbolicLink(target)) return null
        val parentReal = runCatching { target.parent.toRealPath() }.getOrNull() ?: return null
        if (parentReal != baseReal) return null
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            val real = runCatching { target.toRealPath(LinkOption.NOFOLLOW_LINKS) }.getOrNull()
                ?: return null
            if (!real.startsWith(baseReal) || real.parent != baseReal) return null
        }
        return target.toFile()
    }

    /** Verdict form of [spoolPath] for callers that only need an allow/deny. */
    fun validSpoolToken(token: String, cacheDir: File): Boolean =
        spoolPath(token, cacheDir) != null

    /**
     * The files among [candidates] whose session died: spool-named, past the
     * staging window, and not remembered by any `send later` token.
     * Queue paths ride absolute; tokens the queue stores as URIs never match
     * a file and are ignored naturally.
     */
    fun stale(
        candidates: Iterable<File>,
        queueTokens: Collection<String>,
        now: Long,
    ): List<File> {
        val kept = queueTokens.toHashSet()
        return candidates.filter { f ->
            f.isFile &&
                f.name.substringBefore('-') in SPOOL_TAGS &&
                now - f.lastModified() >= STAGING_WINDOW_MS &&
                f.absolutePath !in kept
        }
    }
}
