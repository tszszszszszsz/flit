package app.flit.share

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.model.CircleRequest
import app.flit.share.protocol.model.CircleResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure parts of the Circle sync worker: backoff, Lamport sequencing, the
 * wire-body schemas and kick-id resolution. Nothing here touches an Android
 * context, the store or a socket — those need the engine/protocol runtime.
 */
class FlitCircleSyncTest {

    private fun obj(body: String) = Json.parseToJsonElement(body).jsonObject

    @Test
    fun `backoff escalates then caps - repeated failures never spin`() {
        assertEquals(5_000L, circleBackoffMs(0))
        assertEquals(10_000L, circleBackoffMs(1))
        assertEquals(20_000L, circleBackoffMs(2))
        assertEquals(40_000L, circleBackoffMs(3))
        assertEquals(80_000L, circleBackoffMs(4))
        assertEquals(120_000L, circleBackoffMs(5))
        assertEquals(120_000L, circleBackoffMs(6))
        assertEquals(5_000L, circleBackoffMs(-3)) // nonsense attempts clamp, never zero
    }

    @Test
    fun `next lamport is one past the highest seen, and starts at one`() {
        assertEquals(1L, circleNextLamport(emptyList()))
        assertEquals(4L, circleNextLamport(listOf(0L, 3L, 2L)))
        assertEquals(11L, circleNextLamport(listOf(10L, -1L, 7L)))
    }

    @Test
    fun `vote body carries poll id and option`() {
        val b = obj(circleVoteBody("poll-42", 2))
        assertEquals("poll-42", b.getValue("pollId").jsonPrimitive.content)
        assertEquals(2, b.getValue("option").jsonPrimitive.content.toInt())
        assertFalse(b.containsKey("fp")) // author identity is the signed envelope, not the body
    }

    @Test
    fun `poll body carries poll id question and every option in order`() {
        val b = obj(circlePollBody("poll-42", "Which file?", listOf("a.gflit", "b.gflit")))
        assertEquals("poll-42", b.getValue("pollId").jsonPrimitive.content)
        assertEquals("Which file?", b.getValue("question").jsonPrimitive.content)
        assertEquals(
            listOf("a.gflit", "b.gflit"),
            b.getValue("options").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `file event references only name size and sha256 - never bytes token or local path`() {
        val b = obj(circleFileBody("notes.gflit", 4096L, "abc123"))
        assertEquals("notes.gflit", b.getValue("name").jsonPrimitive.content)
        assertEquals(4096L, b.getValue("size").jsonPrimitive.content.toLong())
        assertEquals("abc123", b.getValue("sha256").jsonPrimitive.content)
        assertEquals(setOf("name", "size", "sha256"), b.keys)
        assertFalse(b.containsKey("token"), "no spool token on the wire")
        assertFalse(b.keys.any { it.contains("path", ignoreCase = true) }, "no local path on the wire")
        assertFalse(b.keys.any { it.contains("data", ignoreCase = true) || it.contains("bytes", ignoreCase = true) })
    }

    @Test
    fun `file body gate accepts only name size sha256 and refuses any token or path field`() {
        assertTrue(circleBodyValid(CircleEvent.FILE, circleFileBody("a.gflit", 12L, "sha")))
        assertFalse(
            circleBodyValid(CircleEvent.FILE, """{"name":"a.gflit","size":12,"sha256":"sha","token":"spool"}"""),
            "the local resume token must never be minted into a signed file event",
        )
        assertFalse(
            circleBodyValid(CircleEvent.FILE, """{"name":"a.gflit","size":12,"sha256":"sha","path":"/cache/circle/a"}"""),
        )
        assertFalse(
            circleBodyValid(CircleEvent.FILE, """{"name":"a.gflit","size":12,"sha256":"sha","spoolPath":"/cache/circle/a"}"""),
        )
        assertFalse(
            circleBodyValid(CircleEvent.FILE, """{"name":"a.gflit","size":12,"token":"spool"}"""),
            "a missing sha256 is not a reference",
        )
    }

    @Test
    fun `kick bodies distinguish opening voting and executing`() {
        val open = obj(circleKickOpenBody("k-1", "AABB"))
        assertEquals("k-1", open.getValue("kickId").jsonPrimitive.content)
        assertEquals("AABB", open.getValue("targetFp").jsonPrimitive.content)
        assertEquals("true", open.getValue("approve").jsonPrimitive.content)
        assertEquals("vote", open.getValue("mode").jsonPrimitive.content)

        val vote = obj(circleKickVoteBody("k-1", "AABB", false))
        assertEquals("k-1", vote.getValue("kickId").jsonPrimitive.content)
        assertEquals("false", vote.getValue("approve").jsonPrimitive.content)

        val mirrored = obj(circleKickVoteEventBody("k-1", "AABB", true))
        assertEquals("vote", mirrored.getValue("mode").jsonPrimitive.content)
        assertEquals("AABB", mirrored.getValue("targetFp").jsonPrimitive.content)

        val execute = obj(circleKickExecuteBody("k-1", "AABB"))
        assertEquals("AABB", execute.getValue("targetFp").jsonPrimitive.content)
        assertEquals("admin", execute.getValue("mode").jsonPrimitive.content)
    }

    @Test
    fun `rekey and leave bodies carry only the minimum`() {
        val rekey = obj(circleRekeyBody("base64key", 3, listOf("AA", "BB")))
        assertEquals("base64key", rekey.getValue("keyB64").jsonPrimitive.content)
        assertEquals(3, rekey.getValue("gen").jsonPrimitive.content.toInt())
        assertEquals(
            listOf("AA", "BB"),
            rekey.getValue("members").jsonArray.map { it.jsonPrimitive.content },
        )
        val leave = obj(circleLeaveBody("AABB"))
        assertEquals("AABB", leave.getValue("memberFp").jsonPrimitive.content)
        assertEquals(setOf("memberFp"), leave.keys)
    }

    @Test
    fun `kick target resolves by kick id or by the kick event id`() {
        val open = CircleEvent(
            id = "event-9",
            circleId = "c1",
            authorFp = "AA",
            lamport = 1L,
            type = CircleEvent.KICK,
            body = circleKickOpenBody("k-1", "AABB"),
            sig = "sig",
            createdMs = 1L,
        )
        assertEquals("AABB", circleKickTarget(listOf(open), "k-1"))
        // A UI that only kept the event id still resolves the target: the open
        // body's id equals the event's kickId here, so both routes agree.
        assertEquals("AABB", circleKickTarget(listOf(open), "event-9"))
        assertNull(circleKickTarget(listOf(open), "k-2"))
        assertNull(
            circleKickTarget(
                listOf(open.copy(type = CircleEvent.MESSAGE)),
                "k-1",
            ),
            "only kick events may resolve a vote-kick target",
        )
        assertTrue(circleKickTarget(listOf(open), "k-1")!!.isNotBlank())
    }

    @Test
    fun `outbound bodies we mint all pass the strict gate`() {
        assertTrue(circleBodyValid(CircleEvent.MESSAGE, """{"text":"hello"}"""))
        assertTrue(circleBodyValid(CircleEvent.FILE, circleFileBody("a.gflit", 12L, "sha")))
        assertTrue(circleBodyValid(CircleEvent.POLL, circlePollBody("p", "q", listOf("a", "b"))))
        assertTrue(circleBodyValid(CircleEvent.VOTE, circleVoteBody("p", 1)))
        assertTrue(circleBodyValid(CircleEvent.VOTE, circleKickVoteBody("k", "AABB", false)))
        assertTrue(circleBodyValid(CircleEvent.JOIN, """{"alias":"Ann"}"""))
        assertTrue(circleBodyValid(CircleEvent.LEAVE, circleLeaveBody("AABB")))
        assertTrue(circleBodyValid(CircleEvent.KICK, circleKickOpenBody("k", "AABB")))
        assertTrue(circleBodyValid(CircleEvent.KICK, circleKickExecuteBody("k", "AABB")))
        assertTrue(circleBodyValid(CircleEvent.ROLE, circleRoleBody("AABB", FlitCircleStore.Role.ADMIN)))
        assertTrue(circleBodyValid(CircleEvent.REKEY, circleRekeyBody("key", 3, listOf("AABB"))))
    }

    @Test
    fun `malformed and unusable bodies never pass the outbound gate`() {
        assertFalse(circleBodyValid(CircleEvent.MESSAGE, ""))
        assertFalse(circleBodyValid(CircleEvent.MESSAGE, "not json"))
        assertFalse(circleBodyValid(CircleEvent.MESSAGE, """["text"]"""))
        assertFalse(circleBodyValid(CircleEvent.MESSAGE, """{"text":""}"""))
        assertFalse(circleBodyValid(CircleEvent.MESSAGE, "x".repeat(CircleCore.MAX_BODY_CHARS + 1)))
        assertFalse(circleBodyValid(CircleEvent.FILE, """{"name":"a","size":"oops","sha256":"s"}"""))
        assertFalse(circleBodyValid(CircleEvent.FILE, """{"name":"a","size":12}"""))
        assertFalse(circleBodyValid(CircleEvent.POLL, """{"pollId":"p","question":"q","options":[]}"""))
        assertFalse(circleBodyValid(CircleEvent.VOTE, """{"pollId":"p"}"""))
        assertFalse(circleBodyValid(CircleEvent.KICK, """{"kickId":"k"}"""))
        assertFalse(circleBodyValid(CircleEvent.ROLE, """{"targetFp":"AABB"}"""))
        assertFalse(circleBodyValid(CircleEvent.REKEY, """{"gen":2}"""))
        assertFalse(circleBodyValid("unknown", """{"text":"hello"}"""))
    }

    @Test
    fun `inbound gate is bounded and tolerant of inert history`() {
        assertTrue(circleBodyReadable("{}")) // leave and host-minted rekey shapes
        assertTrue(circleBodyReadable("""{"gen":2,"members":["AABB"]}"""))
        assertFalse(circleBodyReadable(""))
        assertFalse(circleBodyReadable("not json"))
        assertFalse(circleBodyReadable("x".repeat(CircleCore.MAX_BODY_CHARS + 1)))
    }

    private fun state(vararg fps: String) = CircleState(
        id = "c1",
        keyB64 = "key",
        role = Role.OWNER,
        members = fps.map { Member(it, "", Role.MEMBER) },
    )

    @Test
    fun `fan-out peers are normalised deduplicated and never self`() {
        val st = state(" aabb ", "AABB", "ccdd", "ME")
        assertEquals(listOf("AABB", "CCDD"), circlePeers(st, "me"))
        assertTrue(circlePeers(state("ME"), " me ").isEmpty())
    }

    @Test
    fun `fan-out skips backed-off peers but never busy-loops`() {
        val st = state("AABB", "CCDD", "EEFF")
        val retryAt = mapOf("AABB" to 10_000L, "CCDD" to 5_000L)
        assertEquals(listOf("CCDD", "EEFF"), circleFanoutTargets(st, "ME", 9_999L, retryAt))
        assertEquals(
            listOf("AABB", "CCDD", "EEFF"),
            circleFanoutTargets(st, "ME", 10_000L, retryAt),
        )
        assertEquals(5_000L, circleBackoffMs(0)) // first refusal = one spaced retry
    }

    @Test
    fun `role body carries a canonical target and an assignable role`() {
        val b = obj(circleRoleBody(" aabb ", FlitCircleStore.Role.ADMIN))
        assertEquals("AABB", b.getValue("targetFp").jsonPrimitive.content)
        assertEquals("ADMIN", b.getValue("role").jsonPrimitive.content)
        assertEquals(setOf("targetFp", "role"), b.keys)
    }

    // ==== behavioural fan-out tests over the injectable carrier seam ============

    private fun event(id: String = "e1", lamport: Long = 1L) = CircleEvent(
        id = id,
        circleId = "c1",
        authorFp = "ME",
        lamport = lamport,
        type = CircleEvent.MESSAGE,
        body = """{"text":"hello"}""",
        sig = "sig",
        createdMs = 1_000L,
    )

    private fun installSeam(
        st: CircleState,
        dial: suspend (String) -> FlitCircleSync.Route?,
        now: () -> Long = { 1_000L },
        logs: MutableList<String> = mutableListOf(),
        me: String = "ME",
    ) {
        FlitCircleSync.resetForTest()
        FlitCircleSync.stateHook = { st }
        FlitCircleSync.meHook = { me }
        FlitCircleSync.clockHook = now
        FlitCircleSync.logHook = { logs.add(it) }
        FlitCircleSync.dialHook = dial
    }

    private fun route(
        fp: String,
        sync: suspend (CircleRequest) -> CircleResponse = { CircleResponse(true) },
    ) = FlitCircleSync.Route(fp, sync = sync, join = { CircleResponse(false, reason = "unused") })

    @Test
    fun `rejoin keeps existing delivery rows and replaces only host-owned fields`() = runBlocking {
        val delivery = CircleDelivery(
            id = "d-1",
            name = "notes.gflit",
            size = 12L,
            sha256 = "sha-rejoin",
            spoolPath = "/cache/circle/notes.gflit", // local-only, never on the wire
            statuses = mapOf("AABB" to CircleDelivery.SENT),
            createdMs = 5L,
        )
        val prior = state("ME", "HOSTFP").copy(
            keyB64 = "old-key",
            events = listOf(event("old-event")),
            deliveries = mapOf(delivery.sha256 to delivery),
        )
        var persisted: CircleState? = null
        FlitCircleSync.resetForTest()
        FlitCircleSync.stateHook = { prior }
        FlitCircleSync.meHook = { "ME" }
        FlitCircleSync.dialHook = { fp ->
            FlitCircleSync.Route(
                peer = fp,
                sync = { CircleResponse(false, reason = "unused") },
                join = { req ->
                    assertEquals("c1", req.circleId)
                    assertEquals("ME", req.memberFp)
                    // No seed events: verification of fabricated signatures is
                    // not what this test is about; the local rows are.
                    CircleResponse(true, key = "new-key", members = listOf("hostfp", "ME"))
                },
            )
        }
        FlitCircleSync.joinPersistHook = { persisted = it }
        try {
            FlitCircleSync.join(QrAdd(fingerprint = "hostfp", circle = "c1", invite = "invite"))

            val joined = assertNotNull(persisted, "join must persist a joined state")
            assertEquals(
                mapOf(delivery.sha256 to delivery),
                joined.deliveries,
                "rejoining must not wipe locally recorded delivery rows",
            )
            assertEquals("new-key", joined.keyB64, "host key replaces the stale local one")
            assertEquals("ME", joined.selfFp, "selfFp tracks this device exactly")
            assertEquals(emptyList(), joined.events, "stale local log is not carried over")
            assertEquals(
                setOf("HOSTFP", "ME"),
                joined.members.map { it.fp }.toSet(),
                "host roster is authoritative",
            )
        } finally {
            FlitCircleSync.resetForTest()
        }
        Unit
    }

    @Test
    fun `fanout posts one delta per non-self peer and always logs evidence`() = runBlocking {
        val ev = event()
        val st = state("ME", "hostfp", "AABB").copy(events = listOf(ev))
        val dialed = mutableListOf<String>()
        val sent = mutableListOf<CircleRequest>()
        val logs = mutableListOf<String>()
        installSeam(st, logs = logs, dial = { fp ->
            dialed += fp
            route(fp) { req -> sent += req; CircleResponse(true) }
        })
        try {
            FlitCircleSync.fanout(st, listOf(ev))

            assertEquals(listOf("HOSTFP", "AABB"), dialed)
            assertEquals(2, sent.size)
            assertTrue(sent.all {
                it.action == CircleRequest.ACTION_SYNC && it.circleId == st.id &&
                    it.memberFp == "ME" && it.events == listOf(ev)
            }, sent.toString())
            assertTrue(
                logs.first().startsWith("circle fan-out: 1 new event(s), 2 peer(s)"),
                logs.toString(),
            )
            assertEquals(2, logs.count { it.startsWith("circle fan-out: ok to ") })
        } finally {
            FlitCircleSync.resetForTest()
        }
        Unit
    }

    @Test
    fun `fanout escalates backoff after refusals and resets after an answer`() = runBlocking {
        var clock = 10_000L
        val st = state("ME", "HOSTFP").copy(events = listOf(event()))
        val dialed = mutableListOf<String>()
        val logs = mutableListOf<String>()
        var answers = 0
        installSeam(st, now = { clock }, logs = logs, dial = { fp ->
            dialed += fp
            route(fp) {
                answers += 1
                if (answers <= 2) CircleResponse(false, reason = "offline") else CircleResponse(true)
            }
        })
        try {
            FlitCircleSync.fanout(st, listOf(event())) // fail #1 → 10 s backoff
            assertEquals(1, dialed.size, "first attempt")

            clock += 1_000L
            FlitCircleSync.fanout(st, listOf(event())) // still backed off
            assertEquals(1, dialed.size, "backed off after #1")

            clock = 20_000L
            FlitCircleSync.fanout(st, listOf(event())) // fail #2 → 20 s backoff
            assertEquals(2, dialed.size, "retry after 10 s")

            clock += 1_000L
            FlitCircleSync.fanout(st, listOf(event())) // escalated, still skipped
            assertEquals(2, dialed.size, "backed off after #2")

            clock = 40_000L
            FlitCircleSync.fanout(st, listOf(event())) // answer → clear backoff
            assertEquals(3, dialed.size, "retry after 20 s")

            clock += 1_000L
            FlitCircleSync.fanout(st, listOf(event())) // no backoff left
            assertEquals(4, dialed.size, "backoff cleared")
            assertTrue(logs.count { it.contains("backed off") } >= 2, logs.toString())
        } finally {
            FlitCircleSync.resetForTest()
        }
        Unit
    }

    @Test
    fun `second sync inside the calm interval makes zero dials`() = runBlocking {
        var clock = 1_000L
        val st = state("ME", "HOSTFP")
        val dialed = mutableListOf<String>()
        installSeam(st, now = { clock }, dial = { fp ->
            dialed += fp
            route(fp) { CircleResponse(true) }
        })
        try {
            FlitCircleSync.sync()
            assertEquals(1, dialed.size) // self excluded, host dialled once

            clock += CIRCLE_SYNC_MIN_INTERVAL_MS - 1
            FlitCircleSync.sync()
            assertEquals(1, dialed.size) // calm interval refuses the second pass

            clock += 1
            FlitCircleSync.sync()
            assertEquals(2, dialed.size) // and resumes on the next beat
        } finally {
            FlitCircleSync.resetForTest()
        }
        Unit
    }

    // ==== watermarked delta fan-out and bounded repair =========================

    @Test
    fun `delta selection offers only events after the acked watermark`() {
        val log = listOf(event("e1", 1L), event("e2", 2L), event("e3", 3L))
        assertEquals(log, circleDelta(log, null), "a new peer gets the whole bounded log")
        assertEquals(
            listOf(log[1], log[2]),
            circleDelta(log, CircleWatermark(1L, "e1")),
        )
        assertEquals(listOf(log[2]), circleDelta(log, CircleWatermark(2L, "e2")))
        assertTrue(circleDelta(log, CircleWatermark(3L, "e3")).isEmpty())
        assertEquals(
            listOf(log[2]),
            circleDelta(log, CircleWatermark(2L, "e2"), limit = 1),
            "the delta is capped too",
        )
        assertEquals(
            log.takeLast(2),
            circleDelta(log, CircleWatermark(0L, "trimmed-away"), limit = 2),
            "an ack whose event left the bounded log falls back to a full repair",
        )
        assertEquals(CircleWatermark(3L, "e3"), circleAckOf(log))
        assertNull(circleAckOf(emptyList()))
    }

    @Test
    fun `fanout advances a per-peer watermark and sends only the delta on the next post`() = runBlocking {
        val e1 = event("e1", 1L)
        val e2 = event("e2", 2L)
        val st = state("ME", "AABB", "CCDD").copy(events = listOf(e1))
        val sentByPeer = mutableMapOf<String, MutableList<CircleRequest>>()
        installSeam(st, dial = { fp ->
            route(fp) { req ->
                sentByPeer.getOrPut(fp) { mutableListOf() } += req
                CircleResponse(true)
            }
        })
        try {
            FlitCircleSync.fanout(st, listOf(e1))
            val next = st.copy(events = listOf(e1, e2))
            FlitCircleSync.stateHook = { next }
            FlitCircleSync.fanout(next, listOf(e2))

            listOf("AABB", "CCDD").forEach { fp ->
                assertEquals(
                    listOf(listOf(e1), listOf(e2)),
                    sentByPeer.getValue(fp).map { it.events },
                    "$fp must see a full log once, then only the new event",
                )
            }
        } finally {
            FlitCircleSync.resetForTest()
        }
        Unit
    }

    @Test
    fun `a refused delta does not advance the watermark and is offered again`() = runBlocking {
        var clock = 1_000L
        val e1 = event("e1", 1L)
        val e2 = event("e2", 2L)
        val st = state("ME", "AABB").copy(events = listOf(e1))
        val sent = mutableListOf<CircleRequest>()
        var refuseOnce = true
        installSeam(st, now = { clock }, dial = { fp ->
            route(fp) { req ->
                sent += req
                if (refuseOnce) {
                    refuseOnce = false
                    CircleResponse(false, reason = "offline")
                } else CircleResponse(true)
            }
        })
        try {
            FlitCircleSync.fanout(st, listOf(e1))
            val next = st.copy(events = listOf(e1, e2))
            FlitCircleSync.stateHook = { next }
            clock = 11_000L // backoff from the refusal has expired
            FlitCircleSync.fanout(next, listOf(e2))

            assertEquals(
                listOf(listOf(e1), listOf(e1, e2)),
                sent.map { it.events },
                "a peer that did not ack must get the whole unacked log again",
            )
        } finally {
            FlitCircleSync.resetForTest()
        }
        Unit
    }

    @Test
    fun `calm sync re-offers the full log after a delta ack and resets the watermark`() = runBlocking {
        val e1 = event("e1", 1L)
        val e2 = event("e2", 2L)
        val e3 = event("e3", 3L)
        val st = state("ME", "AABB").copy(events = listOf(e1, e2))
        val sent = mutableListOf<CircleRequest>()
        installSeam(st, dial = { fp ->
            route(fp) { req -> sent += req; CircleResponse(true) }
        })
        try {
            FlitCircleSync.fanout(st, listOf(e2))
            val withThird = st.copy(events = listOf(e1, e2, e3))
            FlitCircleSync.stateHook = { withThird }
            FlitCircleSync.fanout(withThird, listOf(e3))
            assertEquals(listOf(listOf(e1, e2), listOf(e3)), sent.map { it.events })

            sent.clear()
            FlitCircleSync.sync()
            assertEquals(
                listOf(listOf(e1, e2, e3)),
                sent.map { it.events },
                "the calm tick repairs with the full log even though the watermark is current",
            )

            val e4 = event("e4", 4L)
            FlitCircleSync.stateHook = { withThird.copy(events = listOf(e1, e2, e3, e4)) }
            sent.clear()
            FlitCircleSync.fanout(withThird.copy(events = listOf(e1, e2, e3, e4)), listOf(e4))
            assertEquals(
                listOf(listOf(e4)),
                sent.map { it.events },
                "after the repair the next post is delta-only again",
            )
        } finally {
            FlitCircleSync.resetForTest()
        }
        Unit
    }

    @Test
    fun `repair pass caps the offered log at the store event cap`() = runBlocking {
        val many = (1..FlitCircleStore.EVENT_CAP + 5).map { event("e$it", it.toLong()) }
        assertEquals(
            FlitCircleStore.EVENT_CAP,
            circleRepairBatch(many).size,
        )
        val sent = mutableListOf<CircleRequest>()
        installSeam(state("ME", "AABB").copy(events = many), dial = { fp ->
            route(fp) { req -> sent += req; CircleResponse(true) }
        })
        try {
            FlitCircleSync.sync()
            assertEquals(1, sent.size)
            assertEquals(
                many.takeLast(FlitCircleStore.EVENT_CAP),
                sent.first().events,
                "a hand-edited oversized state must not put more than the bounded log on the wire",
            )
        } finally {
            FlitCircleSync.resetForTest()
        }
        Unit
    }

    @Test
    fun `sync skips backed-off peers and repairs them on a later tick`() = runBlocking {
        var clock = 1_000L
        val logs = mutableListOf<String>()
        val st = state("ME", "AABB", "CCDD").copy(events = listOf(event()))
        val dialed = mutableListOf<String>()
        var aabbRefusals = 0
        installSeam(st, now = { clock }, logs = logs, dial = { fp ->
            dialed += fp
            route(fp) {
                if (fp == "AABB" && aabbRefusals < 3) {
                    aabbRefusals++
                    CircleResponse(false, reason = "offline")
                } else CircleResponse(true)
            }
        })
        try {
            // Escalate AABB to a 40 s backoff (three failures, last at t=31s).
            listOf(1_000L, 11_000L, 31_000L).forEach { at ->
                clock = at
                dialed.clear()
                FlitCircleSync.fanout(st, listOf(event()))
                assertTrue("AABB" in dialed, "AABB should be tried at $at")
            }
            assertEquals(3, aabbRefusals)

            clock = 32_000L
            dialed.clear()
            FlitCircleSync.sync()
            assertEquals(listOf("CCDD"), dialed, "backed-off peer must not be dialled on the tick")
            assertTrue(
                logs.any { it.startsWith("circle sync:") && it.contains("backed off") },
                logs.toString(),
            )

            clock = 62_000L
            dialed.clear()
            FlitCircleSync.sync()
            assertEquals(listOf("CCDD"), dialed, "still inside the backoff at the next tick")

            clock = 92_000L
            dialed.clear()
            FlitCircleSync.sync()
            assertEquals(listOf("AABB", "CCDD"), dialed, "repair resumes once the backoff expires")
            assertEquals(3, aabbRefusals, "the resumed repair was answered")
        } finally {
            FlitCircleSync.resetForTest()
        }
        Unit
    }

    private fun concurrencyProbe(
        peerCount: Int,
        action: suspend () -> Unit,
    ): Pair<Int, Int> {
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val st = state("ME", *((1..peerCount).map { "P$it" }).toTypedArray())
            .copy(events = listOf(event()))
        installSeam(st, dial = { fp ->
            val seen = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, seen) }
            delay(40L)
            active.decrementAndGet()
            route(fp)
        })
        return runBlocking {
            try {
                action()
            } finally {
                FlitCircleSync.resetForTest()
            }
            peak.get() to active.get()
        }
    }

    @Test
    fun `fanout caps concurrent peer dials at four`() {
        val (peak, stillActive) = concurrencyProbe(10) {
            val st = state("ME", *((1..10).map { "P$it" }).toTypedArray())
                .copy(events = listOf(event()))
            FlitCircleSync.fanout(st, listOf(event()))
        }
        assertEquals(0, stillActive, "every permit must be released")
        assertTrue(peak <= CIRCLE_PEER_DIALS, "peak $peak exceeded the dial cap")
        assertEquals(CIRCLE_PEER_DIALS, peak, "the cap must actually admit four peers")
    }

    @Test
    fun `sync caps concurrent peer dials at four`() {
        val (peak, stillActive) = concurrencyProbe(10) {
            FlitCircleSync.sync()
        }
        assertEquals(0, stillActive, "every permit must be released")
        assertTrue(peak <= CIRCLE_PEER_DIALS, "peak $peak exceeded the dial cap")
        assertEquals(CIRCLE_PEER_DIALS, peak, "the repair tick must also stay bounded")
    }
}
