package app.flit.share

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** A trusted ("pinned") peer. Pinning drives roster UI + per-peer destinations. */
@Serializable
data class Pinned(
    /** Peer TlsIdentity fingerprint — stable across restarts, resets on reinstall. */
    val fingerprint: String,
    val alias: String,
    val pinnedMs: Long,
    /** SAF tree to publish received files into for this peer (overrides Downloads/Flit). */
    val destTree: String? = null,
)

/** A send waiting for its peer to reappear (durable across engine restarts). */
@Serializable
data class QueuedSend(
    val fingerprint: String,
    val alias: String,
    /** SAF URIs with persistable grants only — share-sheet streams cannot be kept. */
    val uris: List<String>,
    val names: List<String>,
    val createdMs: Long,
) {
    /**
     * One batch per peer: a second intake JOINS the waiting batch instead of
     * dying as "send failed" with its spooled copies deleted. Tokens union
     * with dedupe (a repeated token carries no new grant or content); names
     * append as spoken — two files may legitimately share one; the batch's
     * age stays its oldest member; the alias follows the peer's latest name.
     */
    fun mergedWith(next: QueuedSend): QueuedSend = copy(
        alias = next.alias.ifBlank { alias },
        uris = (uris + next.uris).distinct(),
        names = names + next.names,
        createdMs = minOf(createdMs, next.createdMs),
    )

    /**
     * The way a sent batch leaves the row: its tokens and spoken names go,
     * anything that merged in while it flew stays owed. Null when the row's
     * story is over. Names leave by first occurrence — two files sharing a
     * name are interchangeable on the glass, and [mergedWith] appends names
     * in merge order, so what remains stays aligned with what remains owed.
     */
    fun without(sentUris: Collection<String>, sentNames: Collection<String>): QueuedSend? {
        val left = uris.filterNot { it in sentUris }
        if (left.isEmpty()) return null
        val spoken = names.toMutableList()
        for (n in sentNames) spoken.remove(n)
        return copy(uris = left, names = spoken)
    }
}

/**
 * Everything F-Droid reviewers like to see: tiny, local, and inspectable.
 * Persisted in the app's own SharedPreferences as JSON — no account, no cloud.
 */
object FlitRoster {

    private const val KEY_ROSTER = "roster"
    private const val KEY_QUEUE = "queue"

    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var ctx: Context

    fun init(context: Context) {
        ctx = context.applicationContext
    }

    private fun prefs() = ctx.getSharedPreferences("flit", Context.MODE_PRIVATE)

    // ==== trusted peers =========================================================

    fun all(): List<Pinned> = prefs().getString(KEY_ROSTER, null)?.let {
        runCatching { json.decodeFromString(ListSerializer(Pinned.serializer()), it) }.getOrDefault(emptyList())
    } ?: emptyList()

    private fun save(list: List<Pinned>) = prefs().edit()
        .putString(KEY_ROSTER, json.encodeToString(ListSerializer(Pinned.serializer()), list))
        .apply()

    fun pinned(fingerprint: String): Boolean = all().any { it.fingerprint == fingerprint }

    /** The alias we best know a trusted device by (locally renamed → wins). */
    fun aliasFor(fingerprint: String): String? =
        all().firstOrNull { it.fingerprint == fingerprint }?.alias?.takeIf { it.isNotBlank() }

    fun alias(fingerprint: String): String? = all().firstOrNull { it.fingerprint == fingerprint }?.alias

    fun pin(fingerprint: String, alias: String) {
        if (fingerprint.startsWith("manual@")) return // stub rows are not pinnable yet
        val list = all().filterNot { it.fingerprint == fingerprint }
        save(list + Pinned(fingerprint, alias, System.currentTimeMillis()))
    }

    fun unpin(fingerprint: String) = save(all().filterNot { it.fingerprint == fingerprint })

    /** A trusted peer renamed itself: the roster follows (shortcuts ride along). */
    fun refreshAlias(fingerprint: String, alias: String) = save(
        all().map { if (it.fingerprint == fingerprint) it.copy(alias = alias) else it },
    )

    fun setDestTree(fingerprint: String, treeUri: String?) {
        save(all().map { if (it.fingerprint == fingerprint) it.copy(destTree = treeUri) else it })
    }

    fun destTree(fingerprint: String): String? = all().firstOrNull { it.fingerprint == fingerprint }?.destTree

    // ==== send-later queue ======================================================

    private fun queueList(): List<QueuedSend> = prefs().getString(KEY_QUEUE, null)?.let {
        runCatching { json.decodeFromString(ListSerializer(QueuedSend.serializer()), it) }.getOrDefault(emptyList())
    } ?: emptyList()

    private fun saveQueue(list: List<QueuedSend>) = prefs().edit()
        .putString(KEY_QUEUE, json.encodeToString(ListSerializer(QueuedSend.serializer()), list))
        .apply()

    fun queued(): List<QueuedSend> = queueList()

    fun enqueue(entry: QueuedSend): Boolean {
        val queue = queueList()
        val waiting = queue.firstOrNull { it.fingerprint == entry.fingerprint }
            ?: run { saveQueue(queue + entry); return true }
        // One row per peer, but nothing the queue was told about may vanish:
        // a second batch joins the waiting one (data loss dressed as "send
        // failed" is the refuse path this replaces).
        saveQueue(
            queue.map { if (it.fingerprint == entry.fingerprint) waiting.mergedWith(entry) else it },
        )
        return true
    }

    fun dequeue(fingerprint: String) = saveQueue(queueList().filterNot { it.fingerprint == fingerprint })

    /**
     * The way a sent batch leaves the queue: precisely its tokens and names —
     * a batch that merged into the same row while this one was flying stays
     * owed, and a row whose last token went is gone. Whole-row [dequeue]
     * stays for cancels.
     */
    fun dequeueTokens(fingerprint: String, sent: QueuedSend) = saveQueue(
        queueList().mapNotNull {
            if (it.fingerprint == fingerprint) it.without(sent.uris, sent.names) else it
        },
    )

    // ==== scanned pairing codes ================================================

    private const val KEY_SCANNED = "scannedCodes"

    /** Fingerprints whose trust came from scanning their device's QR. */
    private fun scannedSet(): Set<String> =
        prefs().getStringSet(KEY_SCANNED, null) ?: emptySet()

    fun markScanned(fingerprint: String) =
        prefs().edit().putStringSet(KEY_SCANNED, scannedSet() + fingerprint).apply()

    fun scannedCode(fingerprint: String): Boolean = fingerprint in scannedSet()

    // ==== manually added addresses =============================================

    private const val KEY_MANUAL = "manualPeers"

    /** "host:port" strings the user added by hand — they survive restarts. */
    fun manual(): List<String> = prefs().getString(KEY_MANUAL, null)?.let {
        runCatching { json.decodeFromString(ListSerializer(String.serializer()), it) }
            .getOrDefault(emptyList())
    } ?: emptyList()

    fun addManual(address: String): Boolean {
        // Loopback is never a neighbor: refuse at the single write choke
        // point (the pre-fix self-ghost bug wrote junk here). Returns false
        // only for refuse-worthy rows; duplicates are already ours → true.
        val host = address.substringBeforeLast(':').trim().lowercase()
        if (host.isBlank() || host.startsWith("127.") || host == "::1" || host == "localhost" || host == "0.0.0.0") {
            return false
        }
        val cur = manual()
        if (cur.contains(address)) return true
        prefs().edit().putString(KEY_MANUAL, json.encodeToString(ListSerializer(String.serializer()), cur + address)).apply()
        return true
    }

    /** Dropping a stale manual row (only from the engine after add proved futile). */
    fun removeManual(address: String) = prefs().edit()
        .putString(KEY_MANUAL, json.encodeToString(ListSerializer(String.serializer()), manual() - address))
        .apply()
}
