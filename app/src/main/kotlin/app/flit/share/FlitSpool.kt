package app.flit.share

import java.io.File

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
internal object FlitSpool {

    /**
     * The staging window. Anything younger than this may be a live intake; the
     * window spans staging through engine start, which in the WKD case is a
     * share-sheet detour of seconds — a day is generous on purpose.
     */
    const val STAGING_WINDOW_MS = 24 * 60 * 60 * 1000L

    /** File-name prefixes [spoolUri] and the crush pass mint; engine-owned copies. */
    val SPOOL_TAGS = setOf("share", "queue", "crush")

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
