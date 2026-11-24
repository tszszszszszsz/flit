package app.flit.share

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.flit.share.protocol.circle.CircleEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Circle feed UI. Pure composition: every action leaves through a callback,
 * nothing here reads the store, signs, or decides governance. FlitEngine owns
 * the state; this file only lays it out.
 *
 * Structure echoes a group chat (top bar + count, mine/theirs rows, pinned
 * composer, member sheet) but the visual language is Flit's: M3 Expressive
 * tokens, 12/16/20 corner steps, no bubbles/tails, no emoji, no invented color.
 * Emphasized (bold) type is reserved for counts and actions.
 */

@Composable
fun CircleScreen(
    state: FlitCircleStore.CircleState?,
    events: List<CircleEvent>,
    members: List<FlitCircleStore.Member>,
    meFp: String,
    onPost: (String) -> Unit,
    onFile: () -> Unit,
    onPoll: (String, List<String>) -> Unit,
    onVote: (String, Int) -> Unit,
    onStartKick: (String) -> Unit,
    onVoteKick: (String, Boolean) -> Unit,
    onKick: (String) -> Unit,
    onLeave: () -> Unit,
    onQr: () -> Unit,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val typ = MaterialTheme.typography
    var draft by remember { mutableStateOf("") }
    var showPoll by remember { mutableStateOf(false) }
    var question by remember { mutableStateOf("") }
    var choices by remember { mutableStateOf("") }
    var showMembers by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // ---- top bar: small app bar — title + member count, 1–2 actions ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Circle", style = typ.titleLarge, color = cs.onSurface)
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(members.size.toString())
                        }
                        append(" members")
                    },
                    style = typ.labelMedium,
                    color = cs.onSurfaceVariant,
                )
            }
            IconButton(onClick = { showMembers = true }) {
                Icon(Icons.Default.Person, contentDescription = "Circle members")
            }
        }

        // ---- member chip row: short fingerprint + local label ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (m in members) {
                Surface(
                    shape = RoundedCornerShape(FlitCorner.small),
                    color = cs.surfaceContainerLow,
                    modifier = Modifier.clickable { showMembers = true },
                ) {
                    Text(
                        text = "${m.alias.ifBlank { m.fp.take(6) }} · ${m.fp.take(6)}",
                        style = typ.labelMedium,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
        HorizontalDivider(color = cs.outlineVariant)

        // ---- chronological feed: mine to the end, theirs to the start ----
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(events, key = { it.id }) { event ->
                FeedRow(
                    event = event,
                    mine = event.authorFp == meFp,
                    author = members.firstOrNull { it.fp == event.authorFp }?.alias
                        ?: event.authorFp.take(8),
                    events = events,
                    members = members,
                    meFp = meFp,
                    onVote = onVote,
                    onVoteKick = onVoteKick,
                )
            }
        }

        // ---- poll composer (expand with fluid motion, spring-driven) ----
        AnimatedVisibility(
            visible = showPoll,
            enter = expandVertically(animationSpec = FlitSpring.spatial()) +
                fadeIn(FlitSpring.effects()),
            exit = shrinkVertically(animationSpec = FlitSpring.fastSpatial()) +
                fadeOut(FlitSpring.fastEffects()),
        ) {
            val options = remember(choices) {
                choices.split(',').map { it.trim() }.filter { it.isNotBlank() }
            }
            Surface(
                shape = RoundedCornerShape(FlitCorner.largeIncreased),
                color = cs.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = question,
                        onValueChange = { question = it },
                        label = { Text("Question") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = choices,
                        onValueChange = { choices = it },
                        label = { Text("Options, separated by commas") },
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            onClick = {
                                showPoll = false
                                question = ""
                                choices = ""
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Cancel", fontWeight = FontWeight.Bold) }
                        TextButton(
                            enabled = question.isNotBlank() && options.size >= 2,
                            onClick = {
                                onPoll(question.trim(), options)
                                showPoll = false
                                question = ""
                                choices = ""
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Post poll", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }

        // ---- pinned composer: attach, text, poll, send ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(onClick = onFile, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Add, contentDescription = "Attach a file")
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Message the circle") },
                singleLine = true,
                shape = RoundedCornerShape(FlitCorner.largeIncreased),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showPoll = !showPoll }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Create a poll")
            }
            IconButton(
                enabled = draft.isNotBlank(),
                onClick = {
                    onPost(draft.trim())
                    draft = ""
                },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send message")
            }
        }
    }

    if (showMembers) {
        MemberSheet(
            state = state,
            members = members,
            meFp = meFp,
            onDismiss = { showMembers = false },
            onKick = { fp -> onKick(fp); showMembers = false },
            onStartKick = { fp -> onStartKick(fp); showMembers = false },
            onQr = { onQr(); showMembers = false },
            onLeave = { onLeave(); showMembers = false },
        )
    }
}

// ---- feed row --------------------------------------------------------------

@Composable
private fun FeedRow(
    event: CircleEvent,
    mine: Boolean,
    author: String,
    events: List<CircleEvent>,
    members: List<FlitCircleStore.Member>,
    meFp: String,
    onVote: (String, Int) -> Unit,
    onVoteKick: (String, Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val typ = MaterialTheme.typography
    val body = remember(event.id, event.body, event.type) { parseBody(event) }
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Column(modifier = Modifier.widthIn(max = 340.dp)) {
            Text(
                text = if (mine) "you · ${timeOf(event.createdMs)}"
                else "$author · ${timeOf(event.createdMs)}",
                style = typ.labelSmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(if (mine) Alignment.End else Alignment.Start),
            )
            when (body.kind) {
                CircleCard.MESSAGE -> MessageCard(body.text)
                CircleCard.FILE -> FileCard(body.name, body.size)
                CircleCard.POLL -> PollCard(
                    question = body.text,
                    pollId = body.pollId,
                    options = body.options,
                    events = events,
                    members = members,
                    meFp = meFp,
                    onVote = onVote,
                )
                CircleCard.VOTE -> VoteCard(body)
                CircleCard.KICK -> KickCard(
                    body = body,
                    events = events,
                    members = members,
                    mine = mine,
                    onVoteKick = onVoteKick,
                )
                CircleCard.SYSTEM -> SystemCard(author, body.text)
            }
        }
    }
}

@Composable
private fun MessageCard(text: String) {
    Surface(
        shape = RoundedCornerShape(FlitCorner.large),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun FileCard(name: String, size: Long) {
    val cs = MaterialTheme.colorScheme
    val typ = MaterialTheme.typography
    Surface(
        shape = RoundedCornerShape(FlitCorner.large),
        color = cs.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(name, style = typ.titleSmall, color = cs.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(FlitEngine.fmtBytes(size))
                    }
                    append(" · file drop")
                },
                style = typ.labelMedium,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PollCard(
    question: String,
    pollId: String,
    options: List<String>,
    events: List<CircleEvent>,
    members: List<FlitCircleStore.Member>,
    meFp: String,
    onVote: (String, Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val typ = MaterialTheme.typography
    val tally = remember(events, members, pollId) { pollTally(events, members, pollId) }
    val myVote = remember(events, pollId, meFp) { latestPollVote(events, pollId, meFp) }
    val total = remember(tally) { tally.values.sum() }
    Surface(
        shape = RoundedCornerShape(FlitCorner.large),
        color = cs.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(question, style = typ.titleSmall, color = cs.onSurface)
            options.forEachIndexed { index, option ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = myVote == index,
                        onClick = { onVote(pollId, index) },
                    )
                    Text(
                        text = option,
                        style = typ.bodyMedium,
                        color = cs.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = (tally[index] ?: 0).toString(),
                        style = typ.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = if (myVote == index) cs.primary else cs.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(total.toString()) }
                    append(" votes · tap to change your vote")
                },
                style = typ.labelSmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
        }
    }
}

@Composable
private fun VoteCard(body: CircleBody) {
    val cs = MaterialTheme.colorScheme
    val typ = MaterialTheme.typography
    val label = when {
        body.kickId.isNotBlank() -> "kick vote"
        body.pollId.isNotBlank() ->
            "poll ${body.pollId.take(8)}" + if (body.option >= 0) " · option ${body.option + 1}" else ""
        else -> "vote"
    }
    Surface(
        shape = RoundedCornerShape(FlitCorner.medium),
        color = cs.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            style = typ.labelMedium,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun KickCard(
    body: CircleBody,
    events: List<CircleEvent>,
    members: List<FlitCircleStore.Member>,
    mine: Boolean,
    onVoteKick: (String, Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val typ = MaterialTheme.typography
    val target = members.firstOrNull { it.fp == body.targetFp }
    val tally = remember(events, members, body.kickId) { kickTally(events, members, body.kickId) }
    val (yes, no) = tally
    Surface(
        shape = RoundedCornerShape(FlitCorner.large),
        color = cs.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Kick vote · ${target?.alias?.ifBlank { target.fp.take(8) } ?: body.targetFp.take(8)}",
                style = typ.titleSmall,
                color = cs.onSurface,
            )
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(yes.toString()) }
                    append(" for · ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(no.toString()) }
                    append(" against")
                },
                style = typ.labelMedium,
                color = cs.onSurfaceVariant,
            )
            if (!mine) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onVoteKick(body.kickId, true) }) {
                        Text("Approve", style = typ.labelLarge, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { onVoteKick(body.kickId, false) }) {
                        Text("Reject", style = typ.labelLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SystemCard(author: String, text: String) {
    Surface(
        shape = RoundedCornerShape(FlitCorner.medium),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "$author $text",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

// ---- member sheet ----------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemberSheet(
    state: FlitCircleStore.CircleState?,
    members: List<FlitCircleStore.Member>,
    meFp: String,
    onDismiss: () -> Unit,
    onKick: (String) -> Unit,
    onStartKick: (String) -> Unit,
    onQr: () -> Unit,
    onLeave: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val typ = MaterialTheme.typography
    val canKick = state?.role?.name == "OWNER" || state?.role?.name == "ADMIN"
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Members", style = typ.titleLarge, color = cs.onSurface, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close members")
                }
            }
            Text(
                state?.id?.take(12) ?: "",
                style = typ.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = cs.onSurfaceVariant,
            )
            HorizontalDivider(color = cs.outlineVariant)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (m in members) {
                    val self = m.fp == meFp
                    val owner = m.role.name == "OWNER"
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (self) "${m.alias.ifBlank { m.fp.take(8) }} · you"
                                else "${m.alias.ifBlank { m.fp.take(8) }} · ${m.fp.take(8)}",
                                style = typ.bodyMedium,
                                color = cs.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = m.role.name.lowercase(),
                                style = typ.labelSmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
                        if (!self && !owner) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (canKick) {
                                    TextButton(onClick = { onKick(m.fp) }) {
                                        Text("Kick", style = typ.labelLarge, fontWeight = FontWeight.Bold)
                                    }
                                }
                                TextButton(onClick = { onStartKick(m.fp) }) {
                                    Text("Start vote", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = cs.outlineVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onQr, modifier = Modifier.weight(1f)) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = "Invite by QR",
                        modifier = Modifier.size(18.dp),
                    )
                    Text("Invite by QR", style = typ.labelMedium, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onLeave, modifier = Modifier.weight(1f)) {
                    Text("Leave circle", style = typ.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ---- event body parsing (presentation only, tolerant of unknown fields) ----

private enum class CircleCard { MESSAGE, FILE, POLL, VOTE, KICK, SYSTEM }

private data class CircleBody(
    val kind: CircleCard,
    val text: String = "",
    val name: String = "",
    val size: Long = 0L,
    val pollId: String = "",
    val options: List<String> = emptyList(),
    val kickId: String = "",
    val targetFp: String = "",
    val option: Int = -1,
    val approve: Boolean? = null,
)

private val circleJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun bodyObject(body: String): JsonObject? = runCatching {
    circleJson.parseToJsonElement(body) as? JsonObject
}.getOrNull()

private fun bodyString(body: String): String? = runCatching {
    (circleJson.parseToJsonElement(body) as? JsonPrimitive)?.content
}.getOrNull()

private fun JsonObject.text(key: String, fallback: String = ""): String =
    (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: fallback

private fun JsonObject.integer(key: String, fallback: Int = 0): Int =
    (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: fallback

private fun JsonObject.longNumber(key: String, fallback: Long = 0L): Long =
    (this[key] as? JsonPrimitive)?.content?.toLongOrNull() ?: fallback

private fun JsonObject.flag(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

private fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

private fun parseBody(event: CircleEvent): CircleBody {
    val obj = bodyObject(event.body)
    return when (event.type) {
        "message" -> CircleBody(
            kind = CircleCard.MESSAGE,
            text = bodyString(event.body) ?: obj?.text("text") ?: event.body,
        )
        "file" -> CircleBody(
            kind = CircleCard.FILE,
            name = obj?.text("name") ?: "file",
            size = obj?.longNumber("size") ?: 0L,
        )
        "poll" -> CircleBody(
            kind = CircleCard.POLL,
            text = obj?.text("question", "Poll") ?: "Poll",
            pollId = obj?.text("pollId")?.takeIf { it.isNotBlank() }
                ?: obj?.text("id") ?: event.id,
            options = obj?.strings("options") ?: emptyList(),
        )
        "vote" -> CircleBody(
            kind = CircleCard.VOTE,
            pollId = obj?.text("pollId") ?: obj?.text("poll") ?: "",
            kickId = obj?.text("kickId") ?: "",
            option = obj?.integer("option", -1) ?: -1,
            approve = obj?.flag("approve"),
        )
        "kick" -> CircleBody(
            kind = CircleCard.KICK,
            kickId = obj?.text("kickId")?.takeIf { it.isNotBlank() } ?: event.id,
            targetFp = obj?.text("targetFp") ?: obj?.text("target") ?: "",
            approve = obj?.flag("approve"),
        )
        else -> CircleBody(
            kind = CircleCard.SYSTEM,
            text = when (event.type) {
                "join" -> "joined the circle"
                "leave" -> "left the circle"
                "role" -> "role changed"
                "rekey" -> "key rotated"
                else -> event.type
            },
        )
    }
}

/** Latest vote per author wins — events arrive in CircleCore order. */
private fun latestPollVote(events: List<CircleEvent>, pollId: String, fp: String): Int? {
    var best = Long.MIN_VALUE
    var result: Int? = null
    for (e in events) {
        if (e.type != "vote" || e.authorFp != fp) continue
        val obj = bodyObject(e.body) ?: continue
        if ((obj.text("pollId") ?: obj.text("poll")) != pollId) continue
        val option = obj.integer("option", -1)
        if (option < 0) continue
        if (e.lamport >= best) {
            best = e.lamport
            result = option
        }
    }
    return result
}

private fun pollTally(
    events: List<CircleEvent>,
    members: List<FlitCircleStore.Member>,
    pollId: String,
): Map<Int, Int> {
    val counts = HashMap<Int, Int>()
    for (m in members) {
        val option = latestPollVote(events, pollId, m.fp) ?: continue
        counts[option] = (counts[option] ?: 0) + 1
    }
    return counts
}

private fun kickTally(
    events: List<CircleEvent>,
    members: List<FlitCircleStore.Member>,
    kickId: String,
): Pair<Int, Int> {
    var yes = 0
    var no = 0
    for (m in members) {
        var best = Long.MIN_VALUE
        var vote: Boolean? = null
        for (e in events) {
            if (e.authorFp != m.fp || (e.type != "vote" && e.type != "kick")) continue
            val obj = bodyObject(e.body) ?: continue
            if (obj.text("kickId") != kickId) continue
            // An open/admin kick with no explicit answer is the author's yes.
            val approve = obj.flag("approve") ?: (e.type == "kick")
            if (e.lamport >= best) {
                best = e.lamport
                vote = approve
            }
        }
        if (vote == true) yes++ else if (vote == false) no++
    }
    return yes to no
}

private fun timeOf(atMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(atMs))
