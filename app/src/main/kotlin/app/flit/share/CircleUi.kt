package app.flit.share

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import kotlinx.serialization.json.JsonElement
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
 *
 * The feed has three honest presentation states: pending (no circle state
 * yet), empty (no events yet), and the populated list. Unrecognized event
 * bodies degrade to an in-band error card rather than leaking raw JSON.
 * File cards report delivery counts from the device's local record only; a
 * file event with no local record says so instead of pretending bytes landed.
 */

@Composable
fun CircleScreen(
    state: FlitCircleStore.CircleState?,
    events: List<CircleEvent>,
    members: List<FlitCircleStore.Member>,
    meFp: String,
    onPost: (String) -> Unit,
    onFile: () -> Unit,
    onOpenFile: (String) -> Unit, // sha256 from the file event body
    onPoll: (String, List<String>) -> Unit,
    onVote: (String, Int) -> Unit,
    onStartKick: (String) -> Unit,
    onVoteKick: (String, Boolean) -> Unit,
    onKick: (String) -> Unit,
    onRole: (String, FlitCircleStore.Role) -> Unit,
    onLeave: () -> Unit,
    onQr: () -> Unit,
    onBack: () -> Unit,
    // ---- additive open-file robustness params (defaults keep old callers) ----
    /**
     * sha256 values that have a local, openable copy on THIS device ("landed
     * here"): received files plus any still-present sender spool. Cards whose
     * sha is not in this set are announcement-only and get no Open action —
     * an Open button there can only dead-end in a toast. Defaults to empty so
     * the UI never offers a dead action until the caller supplies the truth.
     */
    landedSha256: Set<String> = emptySet(),
    /** sha256 currently being resolved/opened; drives the disabled/loading card state. */
    openingSha256: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    val typ = MaterialTheme.typography
    var draft by remember { mutableStateOf("") }
    var showPoll by remember { mutableStateOf(false) }
    var question by remember { mutableStateOf("") }
    var choices by remember { mutableStateOf("") }
    var showMembers by remember { mutableStateOf(false) }
    // Identity loads asynchronously, so meFp can briefly be blank. The store
    // keeps exactly one OWNER — this device — so that roster entry is a safe
    // fallback for recognising our own events during that window.
    val selfFp = remember(meFp, state) {
        if (meFp.isNotBlank()) meFp
        else if (state?.role == FlitCircleStore.Role.OWNER) {
            state.members.firstOrNull { it.role == FlitCircleStore.Role.OWNER }?.fp.orEmpty()
        } else ""
    }
    // Delivery status is device-local and never gossiped. File cards read the
    // local record by sha; an announcement without one says so explicitly.
    val deliveries = state?.deliveries.orEmpty()
    // Hashes on the wire may be upper- or lower-case hex; normalise once here
    // so membership checks are exact and case-insensitive, matching the
    // engine's own sha comparison. The sets are tiny (received ledger + spools).
    val landedHashes = remember(landedSha256) {
        landedSha256.mapTo(HashSet(landedSha256.size)) { it.trim().lowercase(Locale.ROOT) }
    }
    val openingHash = remember(openingSha256) {
        openingSha256?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
    }
    // Every poll and kick card used to walk the whole log once per member and
    // re-parse each vote body on the main thread. Do the work once here for the
    // whole screen: one JSON parse per event body, then derive every tally from
    // that memoized array. Remembered keys use value equality, so a refresh
    // that republishes an unchanged log does not redo any of this.
    val feedData = remember(events, members, selfFp) {
        computeFeedData(events, members, selfFp)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ---- top bar: small app bar — title + member count, 1–2 actions ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(cs.surface)
                .heightIn(min = 64.dp)
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
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button, onClickLabel = "View circle members") {
                            showMembers = true
                        },
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
        val feedState = rememberLazyListState()
        // Reader position is derived continuously, not sampled inside the
        // effect: layoutInfo can still describe the previous list on the
        // frame a new event arrives, which used to mis-classify a reader
        // who was scrolled up as "at bottom".
        val atBottom by remember {
            derivedStateOf {
                val info = feedState.layoutInfo
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                info.totalItemsCount == 0 || lastVisible >= info.totalItemsCount - 2
            }
        }
        var seenIds by remember { mutableStateOf(emptySet<String>()) }
        LaunchedEffect(state?.id, events) {
            if (events.isEmpty()) {
                seenIds = emptySet()
                return@LaunchedEffect
            }
            // Look at everything newly appended, not just the last event: a
            // batch may place another member's event after our own send.
            val fresh = events.filterNot { it.id in seenIds }
            seenIds = events.mapTo(HashSet(events.size)) { it.id }
            if (fresh.isEmpty()) return@LaunchedEffect
            // Follow our own send unconditionally; follow someone else only
            // when the reader was already at the bottom, so reading history
            // is never yanked away by an incoming message.
            if ((selfFp.isNotBlank() && fresh.any { it.authorFp == selfFp }) || atBottom) {
                feedState.animateScrollToItem(events.lastIndex)
            }
        }
        when {
            state == null -> PendingFeed(Modifier.weight(1f))
            events.isEmpty() -> EmptyFeed(Modifier.weight(1f))
            else -> LazyColumn(
                state = feedState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(events, key = { _, event -> event.id }) { index, event ->
                    val body = feedData.parsed[index].body
                    FeedRow(
                        event = event,
                        body = body,
                        mine = event.authorFp == selfFp && selfFp.isNotBlank(),
                        author = members.firstOrNull { it.fp == event.authorFp }?.alias
                            ?: event.authorFp.take(8),
                        members = members,
                        tallies = feedData.tallies,
                        deliveries = deliveries,
                        // Resolve per card from one normalised set: only a
                        // landed sha gets an actionable Open; a resolving sha
                        // gets the disabled/loading state while it opens.
                        landed = body.sha256.isNotBlank() &&
                            body.sha256.trim().lowercase(Locale.ROOT) in landedHashes,
                        opening = body.sha256.isNotBlank() &&
                            body.sha256.trim().lowercase(Locale.ROOT) == openingHash,
                        onVote = onVote,
                        onVoteKick = onVoteKick,
                        onOpenFile = onOpenFile,
                    )
                }
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
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) { Text("Cancel", fontWeight = FontWeight.Bold) }
                        TextButton(
                            enabled = question.isNotBlank() && options.size >= 2,
                            onClick = {
                                onPoll(question.trim(), options)
                                showPoll = false
                                question = ""
                                choices = ""
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
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
                label = { Text("Message the circle") },
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
            meFp = selfFp,
            onDismiss = { showMembers = false },
            onKick = { fp -> onKick(fp); showMembers = false },
            onRole = onRole,
            onStartKick = { fp -> onStartKick(fp); showMembers = false },
            onQr = { onQr(); showMembers = false },
            onLeave = { onLeave(); showMembers = false },
        )
    }
}

// ---- feed states -----------------------------------------------------------

/** Honest "no circle yet" state. Per M3, a short indeterminate wait of a
 *  few seconds gets an indicator; the label carries the meaning for TalkBack. */
@Composable
private fun PendingFeed(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val typ = MaterialTheme.typography
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(
                color = cs.primary,
                modifier = Modifier.semantics { contentDescription = "Loading circle" },
            )
            Text("Preparing circle…", style = typ.bodyMedium, color = cs.onSurfaceVariant)
        }
    }
}

/** Empty feed: no posts, polls or files yet. The icon is decorative; M3 says
 *  purely decorative graphics must be hidden from screen readers. */
@Composable
private fun EmptyFeed(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val typ = MaterialTheme.typography
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(FlitCorner.largeIncreased),
            color = cs.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.List,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(32.dp),
                )
                Text("No messages yet", style = typ.titleMedium, color = cs.onSurface)
                Text(
                    text = "Posts, polls and file drops appear here.",
                    style = typ.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

// ---- feed row --------------------------------------------------------------

@Composable
private fun FeedRow(
    event: CircleEvent,
    body: CircleBody,
    mine: Boolean,
    author: String,
    members: List<FlitCircleStore.Member>,
    tallies: CircleTallies,
    deliveries: Map<String, FlitCircleStore.CircleDelivery>,
    landed: Boolean,
    opening: Boolean,
    onVote: (String, Int) -> Unit,
    onVoteKick: (String, Boolean) -> Unit,
    onOpenFile: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val typ = MaterialTheme.typography
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
                CircleCard.FILE -> FileCard(
                    name = body.name,
                    size = body.size,
                    sha256 = body.sha256,
                    delivery = body.sha256.takeIf { it.isNotBlank() }?.let { deliveries[it] },
                    landed = landed,
                    opening = opening,
                    onOpenFile = onOpenFile,
                )
                CircleCard.POLL -> PollCard(
                    question = body.text,
                    pollId = body.pollId,
                    options = body.options,
                    tally = tallies.poll(body.pollId),
                    onVote = onVote,
                )
                CircleCard.VOTE -> VoteCard(body)
                CircleCard.KICK -> KickCard(
                    body = body,
                    members = members,
                    tally = tallies.kick(body.kickId),
                    mine = mine,
                    onVoteKick = onVoteKick,
                )
                CircleCard.SYSTEM -> SystemCard(author, body.text)
                CircleCard.UNKNOWN -> UnknownCard(body.text)
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
private fun FileCard(
    name: String,
    size: Long,
    sha256: String,
    delivery: FlitCircleStore.CircleDelivery?,
    /** True only when this device actually holds an openable copy of the file. */
    landed: Boolean,
    /** True while the caller is resolving/opening this sha; suppresses the action. */
    opening: Boolean,
    onOpenFile: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val typ = MaterialTheme.typography
    // Counts come only from this device's delivery record; the announcement
    // alone never implies that any bytes landed. Unknown states are not
    // silently folded into a success bucket.
    val counts = remember(delivery) {
        val buckets = intArrayOf(0, 0, 0, 0)
        delivery?.statuses?.values?.forEach { raw ->
            val index = when (raw.trim().lowercase(Locale.ROOT)) {
                FlitCircleStore.CircleDelivery.PENDING -> 0
                FlitCircleStore.CircleDelivery.SENT -> 1
                FlitCircleStore.CircleDelivery.REFUSED -> 2
                FlitCircleStore.CircleDelivery.FAILED -> 3
                else -> -1
            }
            if (index >= 0) buckets[index]++
        }
        buckets
    }
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
            if (delivery == null) {
                // Delivery status is local to the sender and never gossiped.
                // A recipient has no record even when the bytes already landed
                // in History, so never claim "not delivered" on their behalf.
                Text(
                    text = "file drop · open to view if it arrived",
                    style = typ.labelSmall,
                    color = cs.onSurfaceVariant,
                )
            } else {
                Text(
                    text = buildAnnotatedString {
                        append("local · ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(counts[1].toString()) }
                        append(" sent · ")
                        append(counts[0].toString())
                        append(" pending · ")
                        append(counts[2].toString())
                        append(" refused · ")
                        append(counts[3].toString())
                        append(" failed")
                    },
                    style = typ.labelSmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            // Open affordance — three honest states, no dead button:
            //  - resolving: disabled button + spinner, so a second tap cannot
            //    launch a second chooser while the first is being prepared;
            //  - landed: the only state that actually offers Open;
            //  - otherwise: a plain status line, because an Open here could
            //    only dead-end in a toast ("hasn't landed here yet").
            if (sha256.isNotBlank()) {
                when {
                    opening -> TextButton(
                        enabled = false,
                        onClick = { /* disabled: nothing to do until it resolves */ },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics(mergeDescendants = true) {
                                contentDescription = "Opening $name, please wait"
                            },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // Decorative here: the merged button labels it for TalkBack.
                            CircularProgressIndicator(
                                color = cs.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text("Opening…", style = typ.labelMedium)
                        }
                    }
                    landed -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.semantics(mergeDescendants = true) {
                                contentDescription = "$name landed on this device and is ready to open"
                            },
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null, // the row carries the label
                                tint = cs.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "Landed here",
                                style = typ.labelSmall,
                                color = cs.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        TextButton(
                            onClick = { onOpenFile(sha256) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .semantics(mergeDescendants = true) {
                                    contentDescription = "Open $name, landed on this device"
                                },
                        ) {
                            Text("Open file", style = typ.labelMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                    else -> Text(
                        text = "Not here yet · no local copy to open",
                        style = typ.labelSmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PollCard(
    question: String,
    pollId: String,
    options: List<String>,
    tally: PollTally,
    onVote: (String, Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val typ = MaterialTheme.typography
    // Counts, my vote and the total were computed once for the whole screen.
    val counts = tally.counts
    val myVote = tally.myVote
    val total = tally.total
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(
                            selected = myVote == index,
                            role = Role.RadioButton,
                            onClick = { onVote(pollId, index) },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The whole row is the radio-button target; M3 clears the
                    // inner button's semantics when onClick is null.
                    RadioButton(selected = myVote == index, onClick = null)
                    Text(
                        text = option,
                        style = typ.bodyMedium,
                        color = cs.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = (counts[index] ?: 0).toString(),
                        style = typ.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = if (myVote == index) cs.primary else cs.onSurfaceVariant,
                    )
                }
            }
            if (options.isEmpty()) {
                Text("This poll has no options", style = typ.labelSmall, color = cs.error)
            } else {
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
        shape = RoundedCornerShape(FlitCorner.large),
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
    members: List<FlitCircleStore.Member>,
    tally: Pair<Int, Int>,
    mine: Boolean,
    onVoteKick: (String, Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val typ = MaterialTheme.typography
    val target = members.firstOrNull { it.fp == body.targetFp }
    // Computed once for the whole screen; only the target alias is local here.
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
                    TextButton(
                        onClick = { onVoteKick(body.kickId, true) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Approve", style = typ.labelLarge, fontWeight = FontWeight.Bold)
                    }
                    TextButton(
                        onClick = { onVoteKick(body.kickId, false) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
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
        shape = RoundedCornerShape(FlitCorner.large),
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

/** Bounded, printable type name only — never the event body. */
private fun safeTypeLabel(raw: String): String {
    val cleaned = raw.trim().take(64).map { c ->
        if (c.isLetterOrDigit() || c == '-' || c == '_' || c == '.') c else ' '
    }.joinToString("").trim()
    return cleaned.ifBlank { "unknown" }
}

/** In-band error state: an event of a type this build cannot render is shown
 *  honestly instead of leaking raw JSON into the conversation. */
@Composable
private fun UnknownCard(type: String) {
    val cs = MaterialTheme.colorScheme
    val typ = MaterialTheme.typography
    Surface(
        shape = RoundedCornerShape(FlitCorner.large),
        color = cs.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("Unsupported circle event", style = typ.labelMedium, color = cs.error)
            Text(
                text = safeTypeLabel(type),
                style = typ.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
    onRole: (String, FlitCircleStore.Role) -> Unit,
    onStartKick: (String) -> Unit,
    onQr: () -> Unit,
    onLeave: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val typ = MaterialTheme.typography
    val canKick = state?.role == FlitCircleStore.Role.OWNER ||
        state?.role == FlitCircleStore.Role.ADMIN
    // Only the owner appoints or removes admins; the store refuses every
    // other transition, so those controls never appear for anyone else.
    val canManageRoles = state?.role == FlitCircleStore.Role.OWNER
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
                    val owner = m.role == FlitCircleStore.Role.OWNER
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
                                    TextButton(
                                        onClick = { onKick(m.fp) },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    ) {
                                        Text("Kick", style = typ.labelLarge, fontWeight = FontWeight.Bold)
                                    }
                                }
                                TextButton(
                                    onClick = { onStartKick(m.fp) },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Text("Start vote", fontWeight = FontWeight.Bold)
                                }
                            }
                            if (canManageRoles) {
                                val promote = m.role == FlitCircleStore.Role.MEMBER
                                TextButton(
                                    onClick = {
                                        onRole(
                                            m.fp,
                                            if (promote) FlitCircleStore.Role.ADMIN
                                            else FlitCircleStore.Role.MEMBER,
                                        )
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Text(
                                        if (promote) "Make admin" else "Remove admin",
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = cs.outlineVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onQr,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = null, // adjacent Text labels the button
                        modifier = Modifier.size(18.dp),
                    )
                    Text("Invite by QR", style = typ.labelMedium, fontWeight = FontWeight.Bold)
                }
                TextButton(
                    onClick = onLeave,
                    colors = ButtonDefaults.textButtonColors(contentColor = cs.error),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text("Leave circle", style = typ.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ---- event body parsing (presentation only, tolerant of unknown fields) ----

private enum class CircleCard { MESSAGE, FILE, POLL, VOTE, KICK, SYSTEM, UNKNOWN }

private data class CircleBody(
    val kind: CircleCard,
    val text: String = "",
    val name: String = "",
    val size: Long = 0L,
    val sha256: String = "",
    val pollId: String = "",
    val options: List<String> = emptyList(),
    val kickId: String = "",
    val targetFp: String = "",
    val option: Int = -1,
    val approve: Boolean? = null,
)

private val circleJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** One event body parsed exactly once; the object feeds both its card and every tally. */
private data class ParsedEvent(
    val event: CircleEvent,
    val body: CircleBody,
    val obj: JsonObject?,
)

private data class PollTally(
    val counts: Map<Int, Int>,
    val myVote: Int?,
    val total: Int,
)

private data class CircleTallies(
    val polls: Map<String, PollTally>,
    val kicks: Map<String, Pair<Int, Int>>,
) {
    fun poll(pollId: String): PollTally = polls[pollId] ?: EmptyPollTally
    fun kick(kickId: String): Pair<Int, Int> = kicks[kickId] ?: (0 to 0)
}

private val EmptyPollTally = PollTally(emptyMap(), null, 0)

private data class CircleFeedData(
    val parsed: Array<ParsedEvent>,
    val tallies: CircleTallies,
)

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

private fun parseEvent(event: CircleEvent): ParsedEvent {
    // Exactly one parseToJsonElement per body. The object is shared by the
    // visible card and by the tally pass instead of being re-parsed for every
    // (card, member) pair.
    val element = runCatching { circleJson.parseToJsonElement(event.body) }.getOrNull()
    val obj = element as? JsonObject
    return ParsedEvent(event, parseBody(event, element, obj), obj)
}

private fun parseBody(
    event: CircleEvent,
    element: JsonElement?,
    obj: JsonObject?,
): CircleBody {
    // The store reduces lower-cased types; match that so casing never turns
    // a known card into a spurious error card.
    val type = event.type.trim().lowercase(Locale.ROOT)
    return when (type) {
        "message" -> CircleBody(
            kind = CircleCard.MESSAGE,
            text = (element as? JsonPrimitive)?.content ?: obj?.text("text") ?: event.body,
        )
        "file" -> CircleBody(
            kind = CircleCard.FILE,
            name = obj?.text("name") ?: "file",
            size = obj?.longNumber("size") ?: 0L,
            sha256 = obj?.text("sha256")?.trim() ?: "",
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
        else -> {
            val systemText = when (type) {
                "join" -> "joined the circle"
                "leave" -> "left the circle"
                "role" -> "role changed"
                "rekey" -> "key rotated"
                else -> null
            }
            if (systemText != null) CircleBody(kind = CircleCard.SYSTEM, text = systemText)
            else CircleBody(kind = CircleCard.UNKNOWN, text = event.type.ifBlank { "empty type" })
        }
    }
}

/**
 * Parses each body once, then derives *every* poll and kick tally in one pass.
 * The old path had each card tally independently, which re-walked the log once
 * per member and re-parsed vote bodies on the main thread; a new
 * events list recomposed every card and multiplied that work by the card count.
 *
 * Vote identity rules are preserved exactly: raw (non-lowercased) event types,
 * `lamport >=` tie-breaking so later events win, option >= 0 for polls, and an
 * open/admin kick with no explicit answer counting as the author's yes.
 */
private fun computeFeedData(
    events: List<CircleEvent>,
    members: List<FlitCircleStore.Member>,
    meFp: String,
): CircleFeedData {
    val parsed = Array(events.size) { index -> parseEvent(events[index]) }

    // Latest vote per subject per author. Maps keep insertion order irrelevant:
    // only the highest lamport for an author is kept, ties going to the later
    // event in CircleCore order, exactly as the per-card scans did.
    val pollVotes = HashMap<String, HashMap<String, Pair<Long, Int>>>()
    val kickVotes = HashMap<String, HashMap<String, Pair<Long, Boolean>>>()
    val pollIds = LinkedHashSet<String>()
    val kickIds = LinkedHashSet<String>()

    for (p in parsed) {
        val event = p.event
        val obj = p.obj ?: continue
        when (event.type) {
            "vote" -> {
                val pollId = obj.text("pollId") ?: obj.text("poll")
                val option = obj.integer("option", -1)
                if (option >= 0) {
                    pollIds.add(pollId)
                    val byAuthor = pollVotes.getOrPut(pollId) { HashMap() }
                    val previous = byAuthor[event.authorFp]
                    if (previous == null || event.lamport >= previous.first) {
                        byAuthor[event.authorFp] = event.lamport to option
                    }
                }
                val kickId = obj.text("kickId")
                kickIds.add(kickId)
                val approve = obj.flag("approve") ?: false // a vote needs an explicit answer
                val byAuthor = kickVotes.getOrPut(kickId) { HashMap() }
                val previous = byAuthor[event.authorFp]
                if (previous == null || event.lamport >= previous.first) {
                    byAuthor[event.authorFp] = event.lamport to approve
                }
            }
            "kick" -> {
                val kickId = obj.text("kickId")
                kickIds.add(kickId)
                // An open/admin kick with no explicit answer is the author's yes.
                val approve = obj.flag("approve") ?: true
                val byAuthor = kickVotes.getOrPut(kickId) { HashMap() }
                val previous = byAuthor[event.authorFp]
                if (previous == null || event.lamport >= previous.first) {
                    byAuthor[event.authorFp] = event.lamport to approve
                }
            }
            "poll" -> {
                // Keep zero-vote polls present so their cards show a 0 tally.
                pollIds.add(
                    obj.text("pollId")?.takeIf { it.isNotBlank() }
                        ?: obj.text("id") ?: event.id,
                )
            }
        }
    }

    val pollResults = HashMap<String, PollTally>(pollIds.size)
    for (pollId in pollIds) {
        val counts = HashMap<Int, Int>()
        val byAuthor = pollVotes[pollId]
        for (m in members) {
            val option = byAuthor?.get(m.fp)?.second ?: continue
            counts[option] = (counts[option] ?: 0) + 1
        }
        // "My vote" is not restricted to the roster, so read all authors.
        val myVote = byAuthor?.get(meFp)?.second
        pollResults[pollId] = PollTally(counts, myVote, counts.values.sum())
    }

    val kickResults = HashMap<String, Pair<Int, Int>>(kickIds.size)
    for (kickId in kickIds) {
        var yes = 0
        var no = 0
        val byAuthor = kickVotes[kickId]
        for (m in members) {
            when (byAuthor?.get(m.fp)?.second) {
                true -> yes++
                false -> no++
                null -> Unit
            }
        }
        kickResults[kickId] = yes to no
    }

    return CircleFeedData(parsed, CircleTallies(pollResults, kickResults))
}

private fun timeOf(atMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(atMs))
