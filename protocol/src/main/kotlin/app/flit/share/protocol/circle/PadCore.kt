package app.flit.share.protocol.circle

import app.flit.share.protocol.model.WireJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

// The bare names stay usable in this package; the declarations are nested in
// PadCore so callers can also spell them PadCore.PadLine / PadCore.PadView.
typealias PadLine = PadCore.PadLine
typealias PadView = PadCore.PadView

/**
 * Pure JVM fold for the Circle pad. The durable state is the signed `pad`
 * events in the Circle log; this object keeps nothing between calls. It checks
 * bodies, builds the two body shapes, and resolves one line per key into a
 * derived [PadView]. It does not verify signatures or pin certificates: the
 * receiving route owns verification and the replay guard before events reach
 * the fold, so no test or cached snapshot can pin a certificate here.
 */
object PadCore {
    /** Most live lines in a derived view. */
    const val MAX_LINES = 256

    /** Longest accepted line key. */
    const val MAX_KEY_CHARS = 128

    /** Longest accepted line text. */
    const val MAX_TEXT_CHARS = 4 * 1024

    /** Exact key set of a `put` body. */
    val PAD_PUT_KEYS: Set<String> = setOf("op", "key", "text")

    /** Exact key set of a `del` body. */
    val PAD_DEL_KEYS: Set<String> = setOf("op", "key")

    /** One line in a pad view, with the order key of its winning operation. */
    data class PadLine(
        val key: String, val text: String, val lamport: Long = 0L,
        val authorFp: String = "", val id: String = "", val deleted: Boolean = false,
    )

    /**
     * Derived pad state: [lines] are the live, lexicographically ordered keys;
     * [winners] also carries tombstones and order keys so [merge] can re-run the
     * conflict rule and honour view(a + b) == merge(view(a), view(b)).
     */
    data class PadView(val lines: List<PadLine>, val winners: Map<String, PadLine> = emptyMap())

    /** One surviving operation, carrying the first three `CircleCore.ORDER` fields. */
    private data class Op(
        val lamport: Long,
        val authorFp: String,
        val id: String,
        val deleted: Boolean,
        val text: String,
    )

    private val OP_ORDER = compareBy<Op> { it.lamport }
        .thenBy { it.authorFp }
        .thenBy { it.id }

    private val LINE_ORDER = compareBy<PadLine> { it.lamport }
        .thenBy { it.authorFp }
        .thenBy { it.id }

    /** Builds the exact body accepted by [validPadBody] for a `put`. */
    fun padPutBody(key: String, text: String): String {
        require(key.isNotBlank()) { "blank pad key" }
        require(key.length <= MAX_KEY_CHARS) { "pad key too long" }
        require(text.isNotBlank()) { "blank pad text" }
        require(text.length <= MAX_TEXT_CHARS) { "pad text too long" }
        val body = buildJsonObject {
            put("op", JsonPrimitive("put"))
            put("key", JsonPrimitive(key))
            put("text", JsonPrimitive(text))
        }.toString()
        require(body.length <= CircleCore.MAX_BODY_CHARS) { "pad put body too long" }
        return body
    }

    /** Builds the exact body accepted by [validPadBody] for a `del`. */
    fun padDelBody(key: String): String {
        require(key.isNotBlank()) { "blank pad key" }
        require(key.length <= MAX_KEY_CHARS) { "pad key too long" }
        val body = buildJsonObject {
            put("op", JsonPrimitive("del"))
            put("key", JsonPrimitive(key))
        }.toString()
        require(body.length <= CircleCore.MAX_BODY_CHARS) { "pad del body too long" }
        return body
    }

    /**
     * Structural gate for a pad body: a bounded JSON object whose keys are
     * exactly [PAD_PUT_KEYS] when `op` is `"put"`, or exactly [PAD_DEL_KEYS]
     * when `op` is `"del"`. `op`, `key` and `text` must be non-blank JSON
     * strings within [MAX_KEY_CHARS] / [MAX_TEXT_CHARS]; the whole body stays
     * within [CircleCore.MAX_BODY_CHARS]. Extra, missing or renamed keys,
     * non-string values, blank fields and unknown verbs are refused. The gate
     * parses and compares only; it never throws on malformed input.
     */
    fun validPadBody(body: String): Boolean {
        if (body.isBlank() || body.length > CircleCore.MAX_BODY_CHARS) return false
        val obj = bodyObject(body) ?: return false
        return when (string(obj, "op")) {
            "put" -> {
                if (obj.keys != PAD_PUT_KEYS) return false
                val key = string(obj, "key") ?: return false
                val text = string(obj, "text") ?: return false
                key.length <= MAX_KEY_CHARS && text.length <= MAX_TEXT_CHARS
            }
            "del" -> {
                if (obj.keys != PAD_DEL_KEYS) return false
                val key = string(obj, "key") ?: return false
                key.length <= MAX_KEY_CHARS
            }
            else -> false
        }
    }

    /**
     * Folds the supplied events into the pad view for [circleId]. Events of
     * another verb, another circle or with a body [validPadBody] refuses are
     * ignored; [CircleCore.dedup] keeps one event per id, so a replayed id
     * counts once. No signature check, no pin and no replay-window test run
     * here. For each key the operation with the greatest
     * `(lamport, authorFp, id)` wins: `put` installs its text, `del` tombstones
     * the key, and a `del` older than the winning `put` is inert. The result
     * lists live keys in lexicographic order, capped at [MAX_LINES] (the first
     * keys in that order).
     */
    fun view(events: Iterable<CircleEvent>, circleId: String): PadView {
        val relevant = events.filter {
            it.type == CircleEvent.PAD && it.circleId == circleId && validPadBody(it.body)
        }
        val ops = HashMap<String, Op>()
        for (e in CircleCore.dedup(relevant)) {
            val obj = bodyObject(e.body) ?: continue
            val key = string(obj, "key") ?: continue
            val deleted = string(obj, "op") == "del"
            val text = if (deleted) "" else string(obj, "text") ?: continue
            val op = Op(e.lamport, e.authorFp, e.id, deleted, text)
            val previous = ops[key]
            if (previous == null || OP_ORDER.compare(op, previous) > 0) ops[key] = op
        }
        val winners = ops.entries.associate { (key, op) ->
            key to PadLine(key, if (op.deleted) "" else op.text, op.lamport, op.authorFp, op.id, op.deleted)
        }
        val lines = winners.values.asSequence()
            .filterNot { it.deleted }
            .sortedBy { it.key }
            .take(MAX_LINES)
            .toList()
        return PadView(lines, winners)
    }

    /**
     * Combines views already derived from separate event sets. Each view keeps
     * the winning operation per key (including tombstones and order keys), so
     * the same greatest-order rule is re-applied across views and
     * view(a + b) == merge(view(a), view(b)) for any partition or shuffle.
     * Empty input yields an empty view; malformed input never throws.
     */
    fun merge(views: Iterable<PadView>): PadView {
        val winners = HashMap<String, PadLine>()
        for (view in views) {
            val source = view.winners.ifEmpty { view.lines.associateBy { it.key } }
            for ((key, line) in source) {
                val previous = winners[key]
                if (previous == null || LINE_ORDER.compare(line, previous) > 0) winners[key] = line
            }
        }
        val lines = winners.values.asSequence()
            .filterNot { it.deleted }
            .sortedBy { it.key }
            .take(MAX_LINES)
            .toList()
        return PadView(lines, winners)
    }

    private fun bodyObject(body: String): JsonObject? =
        runCatching { WireJson.json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    private fun string(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
}
