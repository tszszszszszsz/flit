package app.flit.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.flit.share.protocol.circle.PadCore
import app.flit.share.protocol.circle.PadView
import app.flit.share.protocol.ideas.padsearch.PadSearch

/**
 * Android-free projection and input bounds for the Circle pad.
 *
 * Split out like [CourierCardUi] so the JVM suite can pin the rows and the two
 * field checks without an Android context or a Compose test rule. This object
 * never parses a `pad` body, never signs or posts and holds no state: [rows]
 * only projects a derived [PadView], and the two error helpers report the same
 * bounds [PadCore] applies at the gate, so the card cannot offer an edit the
 * core would refuse. [PadCard] below is the Compose consumer of these helpers.
 */
object PadUi {

    /** One line as the card shows it: the signed key and the current text. */
    data class PadRow(val key: String, val text: String)

    /**
     * Derive the card's rows from a folded view. `PadCore.view` already lists
     * live keys lexicographically; the sort here is a no-op for a real view and
     * keeps the projection lexicographic even for a hand-built one. No key is
     * tombstoned here — a deleted key is absent from [PadView.lines] already.
     */
    fun rows(view: PadView): List<PadRow> =
        view.lines.sortedBy { it.key }.map { PadRow(it.key, it.text) }

    /** Null when the key can be sent; otherwise the message to show under it. */
    fun keyError(key: String): String? = when {
        key.isBlank() -> "Key is required"
        key.length > PadCore.MAX_KEY_CHARS ->
            "Key must be at most ${PadCore.MAX_KEY_CHARS} characters"
        else -> null
    }

    /** Null when the text can be sent; otherwise the message to show under it. */
    fun textError(text: String): String? = when {
        text.isBlank() -> "Text is required"
        text.length > PadCore.MAX_TEXT_CHARS ->
            "Text must be at most ${PadCore.MAX_TEXT_CHARS} characters"
        else -> null
    }

    /**
     * One search hit as the card shows it. [text] is the original, unmodified
     * `PadLine.text`; [start] (inclusive) and [end] (exclusive) are UTF-16
     * offsets into that text, so a caller can mark the match without rewriting
     * the view. No highlighted text or markup is stored here.
     */
    data class PadHitRow(
        val key: String,
        val text: String,
        val start: Int,
        val end: Int,
        val lineIndex: Int = 0,
    ) {
        /** The matched slice of the original text, read only through offsets. */
        val matched: String
            get() {
                val from = start.coerceIn(0, text.length)
                return text.substring(from, end.coerceIn(from, text.length))
            }
    }

    /**
     * Projected result of one search: display rows plus the same status flags
     * [PadSearch.Result] reports. A rejected or capped query still yields a
     * bounded, non-throwing value.
     */
    data class PadHitRows(
        val rows: List<PadHitRow>,
        val scannedLines: Int,
        val truncated: Boolean,
        val queryRejected: Boolean,
    )

    /**
     * Pure projection of [PadSearch.search] into rows the card can draw. Every
     * hit is sliced from the supplied view by offset; the view is only read,
     * never rewritten or written to. Totality matches [PadSearch.search]: a
     * hostile [PadSearch.Hit.lineIndex] is skipped and offsets are clamped to
     * the line text rather than throwing. No Compose state or Android type is
     * held here.
     */
    fun search(view: PadView, query: String, limit: Int = PadSearch.MAX_HITS): PadHitRows {
        val result = PadSearch.search(view, query, limit)
        val rows = result.hits.mapNotNull { hit ->
            val text = view.lines.getOrNull(hit.lineIndex)?.text ?: return@mapNotNull null
            val start = hit.start.coerceIn(0, text.length)
            val end = hit.end.coerceIn(start, text.length)
            PadHitRow(hit.key, text, start, end, hit.lineIndex)
        }
        return PadHitRows(rows, result.scannedLines, result.truncated, result.queryRejected)
    }

    /** Just the rows of [search], for callers that do not need status flags. */
    fun searchRows(
        view: PadView,
        query: String,
        limit: Int = PadSearch.MAX_HITS,
    ): List<PadHitRow> = search(view, query, limit).rows
}

// Same-package conveniences, mirroring CourierCardUi: bare names resolve to the
// object above and introduce no second set of values.
typealias PadRow = PadUi.PadRow
typealias PadHitRow = PadUi.PadHitRow
typealias PadHitRows = PadUi.PadHitRows

fun rows(view: PadView): List<PadRow> = PadUi.rows(view)

fun keyError(key: String): String? = PadUi.keyError(key)

fun textError(text: String): String? = PadUi.textError(text)

fun search(view: PadView, query: String, limit: Int = PadSearch.MAX_HITS): PadHitRows =
    PadUi.search(view, query, limit)

fun searchRows(
    view: PadView,
    query: String,
    limit: Int = PadSearch.MAX_HITS,
): List<PadHitRow> = PadUi.searchRows(view, query, limit)

/**
 * The Circle pad surface: an M3 list of live lines with an editor above it.
 *
 * Pure composition. Every action leaves through a callback — [onPut] receives
 * `(key, text)`, [onDel] receives a key, and [onClose] dismisses the surface —
 * nothing here signs, posts or reads the store. Tapping a row loads its key and
 * text into the editor; the key is then fixed so an edit is one `put` for the
 * same line rather than an orphaning put/delete pair. Each interactive target
 * is at least 48 dp: the close icon, both row actions, the editor buttons and
 * the rows themselves. Validation is the Android-free [PadUi] check, so an
 * over-bound key or text is shown and the put button stays disabled.
 *
 * [query] is an optional filter: when it is null or blank the full list shows
 * as before; when present the card runs the Android-free [PadUi.search] and
 * lists only the matching lines, marking each match from the UTF-16 offsets
 * [PadSearch] reports. The view is never mutated and no search state is kept
 * in [PadUi] — the one derived result is remembered beside the editor state.
 */
@Composable
fun PadCard(
    view: PadView,
    onPut: (String, String) -> Unit,
    onDel: (String) -> Unit,
    onClose: () -> Unit,
    query: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    val typ = MaterialTheme.typography
    var key by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var editingKey by remember { mutableStateOf<String?>(null) }
    val padRows = remember(view) { PadUi.rows(view) }
    val activeQuery = query?.takeIf { it.isNotBlank() }
    val hits = remember(view, activeQuery) { activeQuery?.let { PadUi.search(view, it) } }
    val subtitle = when {
        hits == null -> "${padRows.size} line${if (padRows.size == 1) "" else "s"}"
        hits.queryRejected -> "Search query rejected"
        else -> buildString {
            append("${hits.rows.size} hit${if (hits.rows.size == 1) "" else "s"}")
            if (hits.truncated) append(" · capped")
        }
    }
    val keyProblem = PadUi.keyError(key)
    val textProblem = PadUi.textError(text)

    fun clearEditor() {
        key = ""
        text = ""
        editingKey = null
    }

    fun save() {
        if (PadUi.keyError(key) != null || PadUi.textError(text) != null) return
        onPut(key, text)
        clearEditor()
    }

    fun delete(rowKey: String) {
        onDel(rowKey)
        if (editingKey == rowKey) clearEditor()
    }

    Surface(
        shape = RoundedCornerShape(FlitCorner.largeIncreased),
        color = cs.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Circle pad", style = typ.titleMedium, color = cs.onSurface)
                    Text(
                        text = subtitle,
                        style = typ.labelSmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = "Close pad" },
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
            HorizontalDivider(color = cs.outlineVariant)

            OutlinedTextField(
                value = key,
                onValueChange = { if (editingKey == null) key = it },
                enabled = editingKey == null,
                label = { Text("Line key") },
                isError = keyProblem != null,
                supportingText = if (keyProblem != null) {
                    { Text(keyProblem, color = cs.error, style = typ.labelSmall) }
                } else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Line text") },
                isError = textProblem != null,
                supportingText = if (textProblem != null) {
                    { Text(textProblem, color = cs.error, style = typ.labelSmall) }
                } else null,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    enabled = keyProblem == null && textProblem == null,
                    onClick = { save() },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text(
                        if (editingKey == null) "Add line" else "Save line",
                        style = typ.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (editingKey != null) {
                    TextButton(
                        onClick = { clearEditor() },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Cancel edit", style = typ.labelLarge) }
                }
            }
            HorizontalDivider(color = cs.outlineVariant)

            when {
                hits != null && hits.queryRejected -> Text(
                    text = "Search query is empty or longer than ${PadSearch.MAX_QUERY_CHARS} characters.",
                    style = typ.bodyMedium,
                    color = cs.error,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                hits != null && hits.rows.isEmpty() -> Text(
                    text = "No lines match this search.",
                    style = typ.bodyMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                padRows.isEmpty() -> Text(
                    text = "No lines yet. Add a key and text above.",
                    style = typ.bodyMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (hits != null) {
                        // Two hits can share a key at different offsets, so the
                        // key must include the offset to stay unique in the list.
                        items(hits.rows, key = { "${it.lineIndex}:${it.start}:${it.key}" }) { hit ->
                            PadLineRow(
                                rowKey = hit.key,
                                rowText = hit.text,
                                matchStart = hit.start,
                                matchEnd = hit.end,
                                onEdit = {
                                    editingKey = hit.key
                                    key = hit.key
                                    text = hit.text
                                },
                                onDelete = { delete(hit.key) },
                            )
                        }
                    } else {
                        items(padRows, key = { it.key }) { row ->
                            PadLineRow(
                                rowKey = row.key,
                                rowText = row.text,
                                matchStart = null,
                                matchEnd = null,
                                onEdit = {
                                    editingKey = row.key
                                    key = row.key
                                    text = row.text
                                },
                                onDelete = { delete(row.key) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One pad line, optionally with a matched range marked from [PadSearch]
 * offsets. The original [rowText] is sliced only for display; the supplied
 * view is never rewritten. Both actions keep the 48 dp target contract.
 */
@Composable
private fun PadLineRow(
    rowKey: String,
    rowText: String,
    matchStart: Int?,
    matchEnd: Int?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val typ = MaterialTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = rowKey,
                style = typ.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { contentDescription = "Line key $rowKey" },
            )
            if (matchStart != null && matchEnd != null) {
                val start = matchStart.coerceIn(0, rowText.length)
                val end = matchEnd.coerceIn(start, rowText.length)
                val marked = buildAnnotatedString {
                    append(rowText.substring(0, start))
                    withStyle(
                        SpanStyle(
                            background = cs.primaryContainer,
                            color = cs.onPrimaryContainer,
                            fontWeight = FontWeight.Bold,
                        ),
                    ) {
                        append(rowText.substring(start, end))
                    }
                    append(rowText.substring(end))
                }
                Text(
                    text = marked,
                    style = typ.bodyMedium,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics {
                        contentDescription = rowText
                    },
                )
            } else {
                Text(
                    text = rowText,
                    style = typ.bodyMedium,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TextButton(
            onClick = onEdit,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text("Edit", style = typ.labelLarge) }
        TextButton(
            onClick = onDelete,
            colors = ButtonDefaults.textButtonColors(contentColor = cs.error),
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text("Delete", style = typ.labelLarge) }
    }
}
