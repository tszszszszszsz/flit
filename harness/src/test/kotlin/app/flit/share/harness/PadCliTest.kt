package app.flit.share.harness

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.circle.PadCore
import app.flit.share.protocol.client.ProtocolClient
import app.flit.share.protocol.client.SendTarget
import app.flit.share.protocol.model.CircleRequest
import app.flit.share.protocol.server.ProtocolServer
import app.flit.share.protocol.server.ServerConfig
import app.flit.share.protocol.tls.TlsIdentity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The CLI's Circle pad seams plus a two-node wire walk. The pure cases drive
 * the same [PadCore] builders, closed-body gate and [CircleCore] signing the
 * `circle pad put|del|show` commands use, over [CircleCliState] as the CLI's
 * on-disk log; the private command helpers are mirrored here the way
 * `ReceiptCliTest` mirrors `Harness.circleEvent`. The convergence case uses a
 * real TLS [ProtocolServer] behind [ConsoleDelegate.circleHost] and two
 * [ProtocolClient] nodes over the production `/circle/sync` route, with no new
 * route, header or device claim.
 */
class PadCliTest {
    private val circleId = "cli-pad-circle"

    @BeforeTest
    fun resetReplayMemory() = CircleCore.clear()

    /** Mirrors the CLI's mint-and-append: next lamport, closed body, signed event. */
    private fun postPad(
        st: CircleCliState,
        signer: TlsIdentity,
        type: String,
        body: String,
    ): CircleEvent {
        val ev = CircleCore.create(
            circleId = st.circleId.ifBlank { circleId },
            authorFp = signer.fingerprint,
            identity = signer,
            type = type,
            body = body,
            lamport = (st.events.maxOfOrNull { it.lamport } ?: -1L) + 1L,
        )
        st.events.add(ev)
        return ev
    }

    private fun put(st: CircleCliState, signer: TlsIdentity, key: String, text: String): CircleEvent =
        postPad(st, signer, CircleEvent.PAD, PadCore.padPutBody(key, text))

    private fun del(st: CircleCliState, signer: TlsIdentity, key: String): CircleEvent =
        postPad(st, signer, CircleEvent.PAD, PadCore.padDelBody(key))

    /** The source of truth `circle pad show` prints: the view folded from the CLI log. */
    private fun shownLines(st: CircleCliState): List<Pair<String, String>> =
        PadCore.view(st.events, st.circleId.ifBlank { circleId })
            .lines.map { it.key to it.text }

    private fun bodyObject(body: String): JsonObject? =
        runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()

    private fun text(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    private fun padEvent(
        signer: TlsIdentity,
        authorFp: String,
        body: String,
        lamport: Long,
        circleId: String,
    ): CircleEvent = CircleCore.create(
        circleId = circleId,
        authorFp = authorFp,
        identity = signer,
        type = CircleEvent.PAD,
        body = body,
        lamport = lamport,
    )

    private suspend fun ProtocolClient.sync(
        target: SendTarget,
        circleId: String,
        fp: String,
        events: List<CircleEvent> = emptyList(),
    ) = circleSync(
        target,
        CircleRequest(
            action = CircleRequest.ACTION_SYNC,
            circleId = circleId,
            memberFp = fp,
            events = events,
        ),
    )

    private fun mergedLog(vararg lists: List<CircleEvent>): List<CircleEvent> =
        CircleCore.order(CircleCore.dedup(lists.flatMap { it }))

    // ---- put / del: closed bodies -------------------------------------------

    @Test
    fun `circle pad put mints a signed closed put body with op key and text only`() {
        val author = TlsIdentity.generate()
        val st = CircleCliState(circleId = circleId)

        val ev = put(st, author, "title", "hello pad")

        assertEquals(1, st.events.size, "the put is appended to the CLI log")
        assertEquals(CircleEvent.PAD, ev.type)
        assertEquals(circleId, ev.circleId)
        assertEquals(author.fingerprint, ev.authorFp)
        assertTrue(CircleCore.verify(ev), "the put is signed by its author")
        assertTrue(PadCore.validPadBody(ev.body))
        val fields = bodyObject(ev.body)!!
        assertEquals(PadCore.PAD_PUT_KEYS, fields.keys)
        assertEquals("put", text(fields, "op"))
        assertEquals("title", text(fields, "key"))
        assertEquals("hello pad", text(fields, "text"))
        assertFalse(fields.containsKey("value"), "no alias field for text")
    }

    @Test
    fun `circle pad del mints a signed closed del body with no text field`() {
        val author = TlsIdentity.generate()
        val st = CircleCliState(circleId = circleId)
        put(st, author, "title", "hello pad")

        val ev = del(st, author, "title")

        assertEquals(2, st.events.size)
        assertEquals(CircleEvent.PAD, ev.type)
        assertTrue(CircleCore.verify(ev))
        assertTrue(PadCore.validPadBody(ev.body))
        val fields = bodyObject(ev.body)!!
        assertEquals(PadCore.PAD_DEL_KEYS, fields.keys)
        assertEquals("del", text(fields, "op"))
        assertEquals("title", text(fields, "key"))
        assertFalse(fields.containsKey("text"), "a del body carries no text")
    }

    @Test
    fun `a pad body changed after signing fails verification`() {
        val author = TlsIdentity.generate()
        val st = CircleCliState(circleId = circleId)
        val ev = put(st, author, "title", "hello pad")
        assertTrue(CircleCore.verify(ev))

        val fields = bodyObject(ev.body)!!.toMap().toMutableMap()
        fields["extra"] = JsonPrimitive("x")
        val tampered = ev.copy(body = buildJsonObject { fields.forEach { (k, v) -> put(k, v) } }.toString())

        assertFalse(CircleCore.verify(tampered), "the signature covers every body field")
        assertFalse(PadCore.validPadBody(tampered.body), "an extra field breaks the closed shape")
    }

    // ---- show ---------------------------------------------------------------

    @Test
    fun `circle pad show lists live lines in lexicographic key order and omits a tombstoned key`() {
        val author = TlsIdentity.generate()
        val st = CircleCliState(circleId = circleId)
        put(st, author, "zebra", "last")
        put(st, author, "alpha", "first")
        put(st, author, "middle", "second")

        assertEquals(
            listOf("alpha" to "first", "middle" to "second", "zebra" to "last"),
            shownLines(st),
        )

        del(st, author, "middle")
        assertEquals(
            listOf("alpha" to "first", "zebra" to "last"),
            shownLines(st),
            "a tombstoned key is not printed",
        )

        put(st, author, "middle", "new")
        assertEquals(
            listOf("alpha" to "first", "middle" to "new", "zebra" to "last"),
            shownLines(st),
            "a later put brings the key back",
        )
    }

    // ---- closed gate and bounds --------------------------------------------

    @Test
    fun `pad body gate refuses extra missing and wrongly typed fields`() {
        assertFalse(PadCore.validPadBody("""{"op":"put","key":"k","text":"v","extra":"x"}"""))
        assertFalse(PadCore.validPadBody("""{"op":"put","key":"k"}"""))
        assertFalse(PadCore.validPadBody("""{"op":"put","text":"v"}"""))
        assertFalse(PadCore.validPadBody("""{"op":"del","key":"k","text":"v"}"""))
        assertFalse(PadCore.validPadBody("""{"op":1,"key":"k","text":"v"}"""))
        assertFalse(PadCore.validPadBody("""{"op":"put","key":7,"text":"v"}"""))
        assertFalse(PadCore.validPadBody("""{"op":"put","key":"k","text":true}"""))
        assertFalse(PadCore.validPadBody("""{"op":"unknown","key":"k","text":"v"}"""))
        assertFalse(PadCore.validPadBody("""{"op":"put","key":" ","text":"v"}"""))
        assertFalse(PadCore.validPadBody("""{"op":"put","key":"k","text":"  "}"""))
        assertFalse(PadCore.validPadBody("not json"))
        assertFalse(PadCore.validPadBody(""))
        assertTrue(PadCore.validPadBody(PadCore.padPutBody("k", "v")))
        assertTrue(PadCore.validPadBody(PadCore.padDelBody("k")))
    }

    @Test
    fun `pad bounds refuse blank oversized key or text before a put or del is minted`() {
        val author = TlsIdentity.generate()
        val st = CircleCliState(circleId = circleId)
        val before = st.events.size

        assertFailsWith<IllegalArgumentException> { PadCore.padPutBody("", "text") }
        assertFailsWith<IllegalArgumentException> { PadCore.padPutBody("k", "") }
        assertFailsWith<IllegalArgumentException> { PadCore.padPutBody("k", "  ") }
        assertFailsWith<IllegalArgumentException> {
            PadCore.padPutBody("x".repeat(PadCore.MAX_KEY_CHARS + 1), "v")
        }
        assertFailsWith<IllegalArgumentException> {
            PadCore.padPutBody("k", "v".repeat(PadCore.MAX_TEXT_CHARS + 1))
        }
        assertFailsWith<IllegalArgumentException> { PadCore.padDelBody("") }
        assertFailsWith<IllegalArgumentException> {
            PadCore.padDelBody("x".repeat(PadCore.MAX_KEY_CHARS + 1))
        }

        // A body minted outside the builders still fails the closed gate.
        val overKey = buildJsonObject {
            put("op", JsonPrimitive("put"))
            put("key", JsonPrimitive("x".repeat(PadCore.MAX_KEY_CHARS + 1)))
            put("text", JsonPrimitive("v"))
        }.toString()
        val overText = buildJsonObject {
            put("op", JsonPrimitive("put"))
            put("key", JsonPrimitive("k"))
            put("text", JsonPrimitive("v".repeat(PadCore.MAX_TEXT_CHARS + 1)))
        }.toString()
        assertFalse(PadCore.validPadBody(overKey))
        assertFalse(PadCore.validPadBody(overText))
        assertEquals(before, st.events.size, "a refused bound mints no CLI event")

        // The bounds are inclusive: a put at both maxima is accepted and verifies.
        val max = put(st, author, "k".repeat(PadCore.MAX_KEY_CHARS), "v".repeat(PadCore.MAX_TEXT_CHARS))
        assertTrue(PadCore.validPadBody(max.body))
        assertTrue(CircleCore.verify(max))
        assertEquals(1, shownLines(st).size)
    }

    @Test
    fun `an oversized pad body is refused by the route body bound`() {
        val body = "v".repeat(CircleCore.MAX_BODY_CHARS + 1)
        assertFailsWith<IllegalArgumentException> {
            CircleCore.create(
                circleId, "AUTHOR", TlsIdentity.generate(), CircleEvent.PAD, body, lamport = 1L,
            )
        }
        assertFalse(PadCore.validPadBody(body))
    }

    // ---- convergence across two nodes --------------------------------------

    @Test
    fun `two nodes converge on one pad view through the production circle sync route`() = runBlocking {
        val circleId = "pad-two-node"
        val invite = "pad-invite"
        val ownerId = TlsIdentity.generate()
        val memberId = TlsIdentity.generate()
        val owner = ownerId.fingerprint
        val member = memberId.fingerprint

        val delegate = ConsoleDelegate()
        delegate.circleHost.seed(circleId, invite, "group-key-v1", owner, ownerId)
        val server = ProtocolServer(
            ownerId, delegate,
            ServerConfig(
                alias = "pad-host", port = 0, tls = true,
                destDir = Files.createTempDirectory("pad-cli-harness"),
            ),
        )
        server.start()
        val target = SendTarget("127.0.0.1", server.port, tls = true)

        try {
            val ownerClient = ProtocolClient(ownerId)
            val memberClient = ProtocolClient(memberId)
            val now = System.currentTimeMillis()
            val proof = CircleCore.create(
                circleId, member, memberId, CircleEvent.JOIN,
                CircleCore.joinProofBody(
                    owner, circleId, invite, member, CircleCore.joinProofBucket(now),
                ),
                0L, now,
            )
            val joined = memberClient.circleJoin(
                target,
                CircleRequest(
                    action = CircleRequest.ACTION_JOIN,
                    circleId = circleId,
                    invite = invite,
                    memberFp = member,
                    events = listOf(proof),
                ),
            )
            assertNotNull(joined)
            assertTrue(joined.ok, "join refused: ${joined.reason}")
            assertEquals(setOf(owner, member), joined.members.toSet())

            val putTitle = padEvent(ownerId, owner, PadCore.padPutBody("title", "hello"), 1L, circleId)
            val putNote = padEvent(ownerId, owner, PadCore.padPutBody("note", "alpha"), 2L, circleId)
            val putStatus = padEvent(memberId, member, PadCore.padPutBody("status", "ready"), 3L, circleId)
            assertTrue(
                listOf(putTitle, putNote, putStatus).all {
                    CircleCore.verify(it) && PadCore.validPadBody(it.body)
                },
            )

            // Each node posts through the same route, then pulls to catch up.
            val ownerPost = ownerClient.sync(target, circleId, owner, listOf(putTitle, putNote))
            val memberPost = memberClient.sync(target, circleId, member, listOf(putStatus))
            val ownerPull = ownerClient.sync(target, circleId, owner)
            val memberPull = memberClient.sync(target, circleId, member)
            assertNotNull(ownerPost)
            assertNotNull(memberPost)
            assertNotNull(ownerPull)
            assertNotNull(memberPull)
            assertTrue(ownerPost.ok, "owner post refused: ${ownerPost.reason}")
            assertTrue(memberPost.ok, "member post refused: ${memberPost.reason}")

            val logA = mergedLog(ownerPost.events, memberPost.events, ownerPull.events)
            val logB = mergedLog(memberPost.events, ownerPost.events, memberPull.events)
            assertEquals(logA.map { it.id }, logB.map { it.id }, "both nodes hold the same signed pad events")
            assertTrue(logA.all { it.type == CircleEvent.PAD })

            val viewA = PadCore.view(logA, circleId)
            val viewB = PadCore.view(logB, circleId)
            assertEquals(viewA, viewB, "the two nodes fold to one PadView")
            assertEquals(
                listOf("note" to "alpha", "status" to "ready", "title" to "hello"),
                viewA.lines.map { it.key to it.text },
            )

            // A newer del tombstones one key; an older del on another key is inert.
            val delTitle = padEvent(memberId, member, PadCore.padDelBody("title"), 4L, circleId)
            val staleDelStatus = padEvent(ownerId, owner, PadCore.padDelBody("status"), 0L, circleId)
            val ownerDel = ownerClient.sync(target, circleId, owner, listOf(staleDelStatus))
            val memberDel = memberClient.sync(target, circleId, member, listOf(delTitle))
            val ownerCatch = ownerClient.sync(target, circleId, owner)
            val memberCatch = memberClient.sync(target, circleId, member)
            assertNotNull(ownerDel)
            assertNotNull(memberDel)
            assertNotNull(ownerCatch)
            assertNotNull(memberCatch)
            assertTrue(ownerDel.ok, "owner del refused: ${ownerDel.reason}")
            assertTrue(memberDel.ok, "member del refused: ${memberDel.reason}")

            val finalA = mergedLog(ownerDel.events, memberDel.events, ownerCatch.events)
            val finalB = mergedLog(memberDel.events, ownerDel.events, memberCatch.events)
            assertEquals(finalA.map { it.id }, finalB.map { it.id })
            val finalViewA = PadCore.view(finalA, circleId)
            assertEquals(finalViewA, PadCore.view(finalB, circleId))
            assertEquals(
                listOf("note" to "alpha", "status" to "ready"),
                finalViewA.lines.map { it.key to it.text },
                "the newer del wins; the older del does not remove status",
            )
            assertEquals(finalViewA, PadCore.view(finalA.reversed(), circleId), "delivery order cannot change the view")

            ownerClient.close()
            memberClient.close()
        } finally {
            server.close()
        }
    }
}
