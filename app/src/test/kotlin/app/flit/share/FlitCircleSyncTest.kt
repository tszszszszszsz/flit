package app.flit.share

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.circle.DropKeyCore
import app.flit.share.protocol.courier.CarryState
import app.flit.share.protocol.courier.CourierCarryCore
import app.flit.share.protocol.courier.CourierEnvelope
import app.flit.share.protocol.courier.CourierParcel
import app.flit.share.protocol.courier.MAX_ENVELOPE_CHARS
import app.flit.share.protocol.courier.PARCEL
import app.flit.share.protocol.courier.PARCEL_BODY_KEYS
import app.flit.share.protocol.model.CircleRequest
import app.flit.share.protocol.model.CircleResponse
import app.flit.share.protocol.tls.TlsIdentity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.util.Base64
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

    // ==== courier parcel body: closed key set and the body postParcel mints =====

    @Test
    fun `parcel body gate accepts only the closed toFp envelope key set`() {
        val good = """{"toFp":"AABB","envelope":"ZW52ZWxvcGU="}"""
        assertTrue(circleBodyValid(PARCEL, good))
        assertTrue(circleBodyValid(PARCEL, """{"toFp":"aabb","envelope":"AA=="}"""))

        // The carrier sees only the two keys. Anything that would let a sender
        // smuggle card fields, a queue token or a parcel phase into a signed
        // event is refused before an id is reserved.
        listOf(
            """"token":"spool"""",
            """"path":"/cache/circle/a"""",
            """"spoolPath":"/cache/circle/a"""",
            """"sha256":"sha"""",
            """"parcelId":"p"""",
            """"phase":"carried"""",
            """"name":"a.gflit","size":12""",
            """"data":"AA==","bytes":12""",
        ).forEach { extra ->
            assertFalse(
                circleBodyValid(PARCEL, """{"toFp":"AABB","envelope":"AA==",$extra}"""),
                "extra parcel body key(s) must be refused: $extra",
            )
        }
        assertFalse(circleBodyValid(PARCEL, """{"toFp":"AABB"}"""), "envelope is required")
        assertFalse(circleBodyValid(PARCEL, """{"envelope":"AA=="}"""), "toFp is required")
        assertFalse(
            circleBodyValid("unknown", good),
            "the gate is per verb; a generic object is not a parcel",
        )
    }

    @Test
    fun `parcel body gate refuses blank oversized and wrong typed fields`() {
        assertFalse(circleBodyValid(PARCEL, ""))
        assertFalse(circleBodyValid(PARCEL, "not json"))
        assertFalse(circleBodyValid(PARCEL, """["toFp","envelope"]"""))
        assertFalse(circleBodyValid(PARCEL, "x".repeat(CircleCore.MAX_BODY_CHARS + 1)))
        assertFalse(circleBodyValid(PARCEL, """{"toFp":"","envelope":"AA=="}"""))
        assertFalse(circleBodyValid(PARCEL, """{"toFp":"AABB","envelope":""}"""))
        assertFalse(
            circleBodyValid(
                PARCEL,
                """{"toFp":"${"x".repeat(CircleCore.MAX_FIELD_CHARS + 1)}","envelope":"AA=="}""",
            ),
            "toFp is bounded by the shared field cap",
        )
        assertFalse(
            circleBodyValid(
                PARCEL,
                """{"toFp":"AABB","envelope":"${"x".repeat(MAX_ENVELOPE_CHARS + 1)}"}""",
            ),
            "the envelope is bounded so the framed body stays under MAX_BODY_CHARS",
        )
        assertFalse(circleBodyValid(PARCEL, """{"toFp":42,"envelope":"AA=="}"""))
        assertFalse(circleBodyValid(PARCEL, """{"toFp":"AABB","envelope":42}"""))
    }

    @Test
    fun `postParcel mints a two-key body that carries the envelope verbatim`() {
        val envelope = "AAECAw==" // opaque to the carrier: never parsed, re-signed or rewritten
        val body = circleParcelBody("AABB", envelope)
        val b = obj(body)

        assertEquals(PARCEL_BODY_KEYS, b.keys)
        assertEquals("AABB", b.getValue("toFp").jsonPrimitive.content)
        assertEquals(envelope, b.getValue("envelope").jsonPrimitive.content)
        assertTrue(circleBodyValid(PARCEL, body), "the body we mint must pass the parcel gate")
        assertFalse(b.keys.any { it.contains("sha", ignoreCase = true) }, "no file digest on the wire")
        assertFalse(
            b.keys.any { it.contains("path", ignoreCase = true) || it.contains("token", ignoreCase = true) },
            "no local spool path or queue token on the wire",
        )
        assertFalse(b.keys.any { it.contains("name", ignoreCase = true) || it.contains("size", ignoreCase = true) })
    }

    // ==== courier path over the same pure seams =================================

    private companion object {
        const val COURIER_NOW = 1_800_000_000_000L
    }

    private fun courierFileEvent(
        sender: TlsIdentity,
        sha: String,
        name: String = "carry.gflit",
        size: Long = 12L,
        lamport: Long = 1L,
    ): CircleEvent = CircleCore.create(
        "c1", sender.fingerprint, sender, CircleEvent.FILE,
        circleFileBody(name, size, sha), lamport, COURIER_NOW,
    )

    private fun courierMessageEvent(sender: TlsIdentity, text: String = "hello"): CircleEvent =
        CircleCore.create(
            "c1", sender.fingerprint, sender, CircleEvent.MESSAGE,
            """{"text":"$text"}""", 2L, COURIER_NOW,
        )

    /** A real sealed envelope so the envelope-id lookup in concludeHandoff works. */
    private fun sealedParcel(
        sender: TlsIdentity,
        recipient: TlsIdentity,
        inner: List<CircleEvent>,
        toFp: String = recipient.fingerprint,
        lamport: Long = 0L,
    ): Pair<CircleEvent, CourierEnvelope> {
        val envelope = CourierEnvelope.seal(
            inner, "c1", sender.fingerprint, toFp, recipient.certificate, sender, COURIER_NOW,
        ) ?: error("seal refused a well-formed recipient certificate")
        val event = CircleCore.create(
            "c1", sender.fingerprint, sender, CircleEvent.PARCEL,
            circleParcelBody(toFp, CourierEnvelope.encode(envelope)), lamport, COURIER_NOW,
        )
        return event to envelope
    }

    private fun courierState(
        me: String,
        peer: String,
        vararg parcels: CourierParcel,
        events: List<CircleEvent> = emptyList(),
    ): CircleState = state(me, peer).copy(
        events = events,
        courierBag = CourierCarryCore.encode(CarryState(parcels.toList())),
    )

    @Test
    fun `postParcel signs a closed parcel body and offers the emitted event to peers`() = runBlocking {
        val id = TlsIdentity.generate()
        val envelope = "AAECAw=="
        var current = state("ME", "AABB").copy(events = listOf(event("old", 1L)))
        val upserted = mutableListOf<CircleEvent>()
        val sent = mutableListOf<CircleRequest>()
        installSeam(current, dial = { fp ->
            route(fp) { req -> sent += req; CircleResponse(true) }
        })
        FlitCircleSync.identityHook = { id }
        FlitCircleSync.upsertHook = { evs ->
            upserted += evs
            current = current.copy(events = current.events + evs)
        }
        FlitCircleSync.stateHook = { current }
        try {
            val emitted = FlitCircleSync.postParcel("aabb", envelope)
            val parcelEvent = assertNotNull(emitted, "postParcel must return the signed event")
            assertEquals(CircleEvent.PARCEL, parcelEvent.type)
            assertEquals("c1", parcelEvent.circleId)
            assertTrue(parcelEvent.authorFp.equals(id.fingerprint, ignoreCase = true))
            assertTrue(CircleCore.verify(parcelEvent), "the emitted parcel verifies against its own cert")
            val body = obj(parcelEvent.body)
            assertEquals(PARCEL_BODY_KEYS, body.keys)
            assertEquals("AABB", body.getValue("toFp").jsonPrimitive.content)
            assertEquals(envelope, body.getValue("envelope").jsonPrimitive.content)
            assertEquals(listOf(parcelEvent), upserted, "the parcel is committed before fan-out")
            assertEquals(1, sent.size)
            assertTrue(parcelEvent in sent.first().events, "the peer is offered the newly emitted parcel")
        } finally {
            FlitCircleSync.resetForTest()
        }
        Unit
    }

    @Test
    fun `postParcel refuses an invalid envelope without signing or fanning out`() = runBlocking {
        val id = TlsIdentity.generate()
        val upserted = mutableListOf<CircleEvent>()
        val sent = mutableListOf<CircleRequest>()
        val logs = mutableListOf<String>()
        installSeam(state("ME", "AABB"), logs = logs, dial = { fp ->
            route(fp) { req -> sent += req; CircleResponse(true) }
        })
        FlitCircleSync.identityHook = { id }
        FlitCircleSync.upsertHook = { upserted += it }
        try {
            assertNull(FlitCircleSync.postParcel("AABB", ""))
            assertNull(FlitCircleSync.postParcel("AABB", "x".repeat(MAX_ENVELOPE_CHARS + 1)))
            assertTrue(upserted.isEmpty(), "no id is reserved and no event is signed")
            assertTrue(sent.isEmpty(), "a refused parcel never reaches a peer")
            assertTrue(logs.any { it.contains("invalid parcel body") }, logs.toString())
        } finally {
            FlitCircleSync.resetForTest()
        }
        Unit
    }

    @Test
    fun `courierTrack keys normalised shas by the emitted parcel event id and a handoff publishes each once`() = runBlocking {
        CourierCarryCore.clear()
        val sender = TlsIdentity.generate()
        val recipient = TlsIdentity.generate()
        val inner = courierFileEvent(sender, "tracked-sha")
        val (parcelEvent, _) = sealedParcel(sender, recipient, listOf(inner), toFp = recipient.fingerprint)
        val parcel = CourierCarryCore.parcelOf(parcelEvent)!!
        var clock = COURIER_NOW
        var current = courierState("ME", recipient.fingerprint, parcel)
        val outcomes = mutableListOf<Pair<String, String>>()
        val bagWrites = mutableListOf<String>()
        installSeam(current, now = { clock }, dial = { fp ->
            route(fp) { CircleResponse(true) }
        })
        FlitCircleSync.stateHook = { current }
        FlitCircleSync.handoffHook = { sha, outcome -> outcomes += sha to outcome }
        FlitCircleSync.bagPersistHook = { raw ->
            bagWrites += raw
            current = current.copy(courierBag = raw)
        }
        FlitCircleSync.courierTrack(" ${parcelEvent.id} ", listOf(" AbC123 ", "abc123", "", "DEF456"))
        try {
            FlitCircleSync.sync()

            assertEquals(
                listOf("abc123" to "handed", "def456" to "handed"),
                outcomes,
                "shas are trimmed, lowercased and deduplicated; each is published once",
            )
            assertTrue(bagWrites.isNotEmpty(), "an answered handoff must ack the bag")
            assertTrue(
                CourierCarryCore.decode(bagWrites.last())?.parcels.orEmpty().isEmpty(),
                "the handed parcel is removed from the bag",
            )

            clock += CIRCLE_SYNC_MIN_INTERVAL_MS
            outcomes.clear()
            FlitCircleSync.sync()
            assertTrue(outcomes.isEmpty(), "a second tick must not republish a cleared handoff")
        } finally {
            FlitCircleSync.resetForTest()
        }
        Unit
    }

    @Test
    fun `concludeHandoff publishes failed on refusal or throw and keeps the sha for a later handoff`() = runBlocking {
        CourierCarryCore.clear()
        val sender = TlsIdentity.generate()
        val recipient = TlsIdentity.generate()
        val inner = courierFileEvent(sender, "retry-sha")
        val (parcelEvent, _) = sealedParcel(sender, recipient, listOf(inner), toFp = recipient.fingerprint)
        val parcel = CourierCarryCore.parcelOf(parcelEvent)!!
        var clock = COURIER_NOW
        var current = courierState("ME", recipient.fingerprint, parcel)
        val outcomes = mutableListOf<Pair<String, String>>()
        val bagWrites = mutableListOf<String>()
        var answer = 0
        installSeam(current, now = { clock }, dial = { fp ->
            route(fp) {
                answer += 1
                when (answer) {
                    1 -> CircleResponse(false, reason = "offline")
                    2 -> throw IllegalStateException("socket closed")
                    else -> CircleResponse(true)
                }
            }
        })
        FlitCircleSync.stateHook = { current }
        FlitCircleSync.handoffHook = { sha, outcome -> outcomes += sha to outcome }
        FlitCircleSync.bagPersistHook = { raw ->
            bagWrites += raw
            current = current.copy(courierBag = raw)
        }
        FlitCircleSync.courierTrack(parcelEvent.id, listOf("retry-sha"))
        try {
            FlitCircleSync.sync()
            assertEquals(listOf("retry-sha" to "failed"), outcomes)

            clock += 40_000L
            FlitCircleSync.sync()
            assertEquals(
                listOf("retry-sha" to "failed", "retry-sha" to "failed"),
                outcomes,
                "a thrown answer is also a failed handoff",
            )
            assertTrue(bagWrites.isEmpty(), "a refusal never acks the bag")

            clock += 40_000L
            FlitCircleSync.sync()
            assertEquals(
                listOf("retry-sha" to "failed", "retry-sha" to "failed", "retry-sha" to "handed"),
                outcomes,
                "the retained sha is published as handed once the peer answers",
            )
            assertEquals(1, bagWrites.size)
            assertTrue(CourierCarryCore.decode(bagWrites.last())?.parcels.orEmpty().isEmpty())
        } finally {
            FlitCircleSync.resetForTest()
        }
        Unit
    }

    @Test
    fun `courierTrack ignores blank ids and blank shas and an unopened parcel publishes nothing`() = runBlocking {
        CourierCarryCore.clear()
        val sender = TlsIdentity.generate()
        val recipient = TlsIdentity.generate()
        val meFp = recipient.fingerprint
        val inner = courierFileEvent(sender, "unopened-sha")
        val (first, _) = sealedParcel(sender, recipient, listOf(inner), toFp = meFp, lamport = 0L)
        val (second, _) = sealedParcel(sender, recipient, listOf(inner), toFp = meFp, lamport = 1L)
        var current = courierState(
            meFp, "AABB",
            CourierCarryCore.parcelOf(first)!!,
            CourierCarryCore.parcelOf(second)!!,
        )
        val outcomes = mutableListOf<Pair<String, String>>()
        installSeam(
            current, now = { COURIER_NOW }, me = meFp,
            dial = { fp -> route(fp) { CircleResponse(true) } },
        )
        FlitCircleSync.stateHook = { current }
        FlitCircleSync.handoffHook = { sha, outcome -> outcomes += sha to outcome }
        FlitCircleSync.courierTrack("  ", listOf("sha"))
        FlitCircleSync.courierTrack(first.id, listOf("  ", "", " "))
        try {
            FlitCircleSync.sync()
            assertTrue(outcomes.isEmpty(), "only a carrier that opened an envelope knows a sha to publish")
        } finally {
            FlitCircleSync.resetForTest()
        }
        Unit
    }

    @Test
    fun `courierReceive folds a verified parcel already present in the local log into the bag`() = runBlocking {
        CourierCarryCore.clear()
        val sender = TlsIdentity.generate()
        val recipient = TlsIdentity.generate()
        val meFp = recipient.fingerprint
        val inner = courierFileEvent(sender, "replay-sha")
        val (parcelEvent, _) = sealedParcel(sender, recipient, listOf(inner), toFp = meFp)
        val parcel = CourierCarryCore.parcelOf(parcelEvent)!!
        // The parcel event was persisted on an earlier pass. Its log id must not
        // stop the carry core from folding the row — carry.evaluate dedups ids.
        var current = courierState(meFp, "AABB", events = listOf(parcelEvent)).copy(
            members = listOf(
                Member(meFp, "", Role.MEMBER),
                Member(sender.fingerprint, "", Role.MEMBER),
            ),
        )
        val bagWrites = mutableListOf<String>()
        val published = mutableListOf<CourierCardUi.CourierHandoff>()
        FlitCircleSync.resetForTest()
        FlitCircleSync.stateHook = { current }
        FlitCircleSync.meHook = { meFp }
        FlitCircleSync.clockHook = { COURIER_NOW }
        FlitCircleSync.logHook = { }
        FlitCircleSync.bagPersistHook = { raw ->
            bagWrites += raw
            current = current.copy(courierBag = raw)
        }
        FlitCircleSync.handoffPublishHook = { published += it }
        try {
            FlitCircleSync.courierReceive(current, listOf(parcelEvent))
            assertTrue(bagWrites.isNotEmpty(), "a parcel already in the log must still be folded")
            assertEquals(
                listOf(parcel.id),
                CourierCarryCore.decode(bagWrites.last())?.parcels.orEmpty().map { it.id },
            )
            assertTrue(published.isEmpty(), "with no identity hook the opened copy is refused; only the bag row is kept")
        } finally {
            FlitCircleSync.resetForTest()
        }
        Unit
    }

    @Test
    fun `courierReceive opens a parcel addressed to me and publishes a typed waiting handoff`() = runBlocking {
        CourierCarryCore.clear()
        val sender = TlsIdentity.generate()
        val recipient = TlsIdentity.generate()
        val inner = courierFileEvent(sender, "landed-sha", name = "carry.gflit", size = 42L)
        val (parcelEvent, _) = sealedParcel(sender, recipient, listOf(inner))
        val parcel = CourierCarryCore.parcelOf(parcelEvent)!!
        // The opened file event is authored by sender, so sender must be a
        // current member for openAtMe to fold it into the signed log.
        var current = courierState(recipient.fingerprint, "AABB").copy(
            members = listOf(
                Member(recipient.fingerprint, "", Role.MEMBER),
                Member("AABB", "", Role.MEMBER),
                Member(sender.fingerprint, "", Role.MEMBER),
            ),
        )
        val bagWrites = mutableListOf<String>()
        val upserted = mutableListOf<CircleEvent>()
        val published = mutableListOf<CourierCardUi.CourierHandoff>()
        val logs = mutableListOf<String>()
        FlitCircleSync.resetForTest()
        FlitCircleSync.stateHook = { current }
        FlitCircleSync.meHook = { recipient.fingerprint }
        FlitCircleSync.identityHook = { recipient }
        FlitCircleSync.clockHook = { COURIER_NOW }
        FlitCircleSync.logHook = { logs += it }
        FlitCircleSync.bagPersistHook = { raw ->
            bagWrites += raw
            current = current.copy(courierBag = raw)
        }
        FlitCircleSync.upsertHook = { upserted += it }
        FlitCircleSync.handoffPublishHook = { published += it }
        try {
            FlitCircleSync.courierReceive(current, listOf(parcelEvent))

            assertTrue(bagWrites.isNotEmpty())
            assertEquals(
                listOf(parcel.id),
                CourierCarryCore.decode(bagWrites.last())?.parcels.orEmpty().map { it.id },
            )
            assertEquals(listOf(inner), upserted, "the decrypted file event enters the signed log")
            assertEquals(1, published.size)
            val handoff = published.single()
            assertEquals("landed-sha", handoff.sha256)
            assertEquals("carry.gflit", handoff.name)
            assertEquals(42L, handoff.size)
            assertTrue(handoff.toFp.equals(recipient.fingerprint, ignoreCase = true))
            assertTrue(handoff.courierFp.equals(sender.fingerprint, ignoreCase = true))
            assertTrue(handoff.fromFp.equals(sender.fingerprint, ignoreCase = true))
            assertEquals(CourierCardUi.HANDOFF_WAITING, handoff.outcome)
            assertTrue(logs.any { it.contains("opened 1 event(s)") }, logs.toString())
        } finally {
            FlitCircleSync.resetForTest()
        }
        Unit
    }

    @Test
    fun `courierReceive refuses a forged parcel but still folds ordinary verified events`() = runBlocking {
        CourierCarryCore.clear()
        val sender = TlsIdentity.generate()
        val recipient = TlsIdentity.generate()
        val meFp = recipient.fingerprint
        val inner = courierFileEvent(sender, "forged-sha")
        val (parcelEvent, _) = sealedParcel(sender, recipient, listOf(inner), toFp = meFp)
        val forged = parcelEvent.copy(sig = "not-a-signature")
        val message = courierMessageEvent(sender)
        var current = courierState(meFp, "AABB")
        val bagWrites = mutableListOf<String>()
        val upserted = mutableListOf<CircleEvent>()
        val published = mutableListOf<CourierCardUi.CourierHandoff>()
        FlitCircleSync.resetForTest()
        FlitCircleSync.stateHook = { current }
        FlitCircleSync.meHook = { meFp }
        FlitCircleSync.clockHook = { COURIER_NOW }
        FlitCircleSync.logHook = { }
        FlitCircleSync.bagPersistHook = { raw ->
            bagWrites += raw
            current = current.copy(courierBag = raw)
        }
        FlitCircleSync.upsertHook = { upserted += it }
        FlitCircleSync.handoffPublishHook = { published += it }
        try {
            FlitCircleSync.courierReceive(current, listOf(forged, message))
            assertTrue(bagWrites.isEmpty(), "a forged signature must not reserve a bag row")
            assertTrue(published.isEmpty())
            assertEquals(listOf(message), upserted, "ordinary events keep the fresh() → persistEvents path")
        } finally {
            FlitCircleSync.resetForTest()
        }
        Unit
    }

    @Test
    fun `courierReceive rejects opened events from a non-member author or a privileged verb`() = runBlocking {
        CourierCarryCore.clear()
        val sender = TlsIdentity.generate()
        val recipient = TlsIdentity.generate()
        val file = courierFileEvent(sender, "foreign-sha")
        val join = CircleCore.create(
            "c1", sender.fingerprint, sender, CircleEvent.JOIN,
            """{"alias":"ghost"}""", 2L, COURIER_NOW,
        )
        val rekey = CircleCore.create(
            "c1", sender.fingerprint, sender, CircleEvent.REKEY,
            """{"keyB64":"newkey","gen":9}""", 3L, COURIER_NOW,
        )
        val (parcelEvent, _) = sealedParcel(sender, recipient, listOf(file, join, rekey))
        // The roster deliberately omits sender. A validly sealed and signed
        // parcel still may not rewrite our roster or group key once opened.
        var current = courierState(recipient.fingerprint, "AABB")
        val bagWrites = mutableListOf<String>()
        val upserted = mutableListOf<CircleEvent>()
        val published = mutableListOf<CourierCardUi.CourierHandoff>()
        val logs = mutableListOf<String>()
        FlitCircleSync.resetForTest()
        FlitCircleSync.stateHook = { current }
        FlitCircleSync.meHook = { recipient.fingerprint }
        FlitCircleSync.identityHook = { recipient }
        FlitCircleSync.clockHook = { COURIER_NOW }
        FlitCircleSync.logHook = { logs += it }
        FlitCircleSync.bagPersistHook = { raw ->
            bagWrites += raw
            current = current.copy(courierBag = raw)
        }
        FlitCircleSync.upsertHook = { upserted += it }
        FlitCircleSync.handoffPublishHook = { published += it }
        try {
            FlitCircleSync.courierReceive(current, listOf(parcelEvent))

            assertTrue(
                logs.any { it.contains("refused parcel from non-member") },
                logs.toString(),
            )
            assertTrue(upserted.isEmpty(), "no event from a non-member parcel author may be folded")
            assertTrue(published.isEmpty(), "a refused parcel publishes no Open affordance")
            assertTrue(bagWrites.isEmpty(), "a non-member parcel never enters the bag")
        } finally {
            FlitCircleSync.resetForTest()
        }
        Unit
    }

    @Test
    fun `rejoin keeps the courier bag alongside locally recorded delivery rows`() = runBlocking {
        val sender = TlsIdentity.generate()
        val recipient = TlsIdentity.generate()
        val inner = courierFileEvent(sender, "bag-sha")
        val (parcelEvent, _) = sealedParcel(sender, recipient, listOf(inner), toFp = recipient.fingerprint)
        val bagRaw = CourierCarryCore.encode(CarryState(listOf(CourierCarryCore.parcelOf(parcelEvent)!!)))
        val delivery = CircleDelivery(
            id = "d-bag", name = "carry.gflit", size = 12L, sha256 = "bag-sha",
            spoolPath = "/cache/circle/carry.gflit",
            statuses = mapOf("HOSTFP" to CircleDelivery.SENT),
            createdMs = 5L,
        )
        val prior = state("ME", "HOSTFP").copy(
            keyB64 = "old-key",
            events = listOf(event("old-event")),
            deliveries = mapOf(delivery.sha256 to delivery),
            courierBag = bagRaw,
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
                    CircleResponse(true, key = "new-key", members = listOf("hostfp", "ME"))
                },
            )
        }
        FlitCircleSync.joinPersistHook = { persisted = it }
        try {
            FlitCircleSync.join(QrAdd(fingerprint = "hostfp", circle = "c1", invite = "invite"))
            val joined = assertNotNull(persisted, "join must persist a joined state")
            assertEquals(bagRaw, joined.courierBag, "rejoining must not wipe the carrier's local bag")
            assertEquals(mapOf(delivery.sha256 to delivery), joined.deliveries)
            assertEquals("new-key", joined.keyB64)
        } finally {
            FlitCircleSync.resetForTest()
        }
        Unit
    }

    // ==== per-drop keyed file announcement ======================================

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun validIv() = Base64.getEncoder().encodeToString(ByteArray(DropKeyCore.IV_BYTES))
    private fun validWrap() = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })

    private data class KeyedAnnouncement(
        val body: String,
        val plainSha: String,
        val cipherSha: String,
    )

    /** A real mint/wrap/seal triple, so the body carries genuine key material. */
    private fun keyedAnnouncement(recipient: TlsIdentity): KeyedAnnouncement {
        val plaintext = "sealed drop bytes".toByteArray(Charsets.UTF_8)
        val key = DropKeyCore.mint()
        val sealed = DropKeyCore.seal(key, plaintext)
        val wraps = DropKeyCore.wrap(key.bytes, mapOf(recipient.fingerprint to recipient.certificate))
        val body = DropKeyCore.keyedFileBody(
            DropKeyCore.KeyedFile(
                name = "sealed.gflit",
                size = plaintext.size.toLong(),
                sha256 = sha256Hex(plaintext),
                keyGen = 1,
                cipherSha = sha256Hex(sealed),
                iv = Base64.getEncoder().encodeToString(key.iv),
                wraps = wraps,
            ),
        )
        return KeyedAnnouncement(body, sha256Hex(plaintext), sha256Hex(sealed))
    }

    private fun keyedBodyOf(
        name: String = "a.gflit",
        size: Long = 12L,
        sha: String = "plainsha",
        keyGen: Int = 1,
        cipherSha: String = "ciphersha",
        iv: String = validIv(),
        wraps: Map<String, String> = mapOf("AABB" to validWrap()),
    ): String = DropKeyCore.keyedFileBody(
        DropKeyCore.KeyedFile(name, size, sha, keyGen, cipherSha, iv, wraps),
    )

    /** Derive a body with one field removed or replaced by a string primitive. */
    private fun mutatedKeyedBody(
        body: String,
        drop: String? = null,
        set: Map<String, String> = emptyMap(),
    ): String {
        val fields = obj(body).toMutableMap()
        drop?.let { fields.remove(it) }
        set.forEach { (key, value) -> fields[key] = JsonPrimitive(value) }
        return JsonObject(fields).toString()
    }

    @Test
    fun `postFile with a caller-built keyed body signs and fans out a verified file event`() = runBlocking {
        val id = TlsIdentity.generate()
        val announcement = keyedAnnouncement(id)
        val upserted = mutableListOf<CircleEvent>()
        val sent = mutableListOf<CircleRequest>()
        val base = state("ME", "AABB")
        installSeam(base, dial = { fp ->
            route(fp) { req -> sent += req; CircleResponse(true) }
        })
        FlitCircleSync.identityHook = { id }
        FlitCircleSync.upsertHook = { upserted += it }
        FlitCircleSync.stateHook = { base.copy(events = base.events + upserted) }
        try {
            FlitCircleSync.postFile(announcement.body)

            val emitted = upserted.single()
            assertEquals(CircleEvent.FILE, emitted.type)
            assertEquals("c1", emitted.circleId)
            assertTrue(emitted.authorFp.equals(id.fingerprint, ignoreCase = true))
            assertTrue(CircleCore.verify(emitted), "the keyed event verifies against its own cert")
            assertEquals(announcement.body, emitted.body, "the caller-built body is posted verbatim")

            val body = obj(emitted.body)
            assertEquals(CircleCore.KEYED_FILE_BODY_KEYS, body.keys)
            assertTrue(circleBodyValid(CircleEvent.FILE, emitted.body))
            assertEquals(announcement.plainSha, body.getValue("sha256").jsonPrimitive.content)
            assertEquals(announcement.cipherSha, body.getValue("cipherSha").jsonPrimitive.content)
            assertEquals(1, body.getValue("keyGen").jsonPrimitive.content.toInt())
            assertTrue(body.getValue("wraps").jsonObject.keys.single().equals(id.fingerprint, ignoreCase = true))

            assertEquals(1, sent.size, "one peer receives the new event")
            assertTrue(emitted in sent.first().events, "the keyed event is offered on the sync route")
        } finally {
            FlitCircleSync.resetForTest()
        }
        Unit
    }

    @Test
    fun `postFile refuses a malformed keyed body without signing or fanning out`() = runBlocking {
        val id = TlsIdentity.generate()
        val upserted = mutableListOf<CircleEvent>()
        val sent = mutableListOf<CircleRequest>()
        val logs = mutableListOf<String>()
        installSeam(state("ME", "AABB"), logs = logs, dial = { fp ->
            route(fp) { req -> sent += req; CircleResponse(true) }
        })
        FlitCircleSync.identityHook = { id }
        FlitCircleSync.upsertHook = { upserted += it }
        try {
            FlitCircleSync.postFile(mutatedKeyedBody(keyedBodyOf(), drop = "cipherSha"))
            FlitCircleSync.postFile("""{"name":"a.gflit","size":12,"sha256":"sha","token":"spool"}""")

            assertTrue(upserted.isEmpty(), "an invalid body must never be signed or committed")
            assertTrue(sent.isEmpty(), "an invalid body must never reach a peer")
        } finally {
            FlitCircleSync.resetForTest()
        }
        Unit
    }

    @Test
    fun `file body gate accepts exact legacy and keyed sets but refuses partial or extra keys`() {
        val legacy = circleFileBody("a.gflit", 12L, "sha")
        assertEquals(CircleCore.FILE_BODY_KEYS, obj(legacy).keys)
        assertTrue(circleBodyValid(CircleEvent.FILE, legacy), "the legacy three-key body stays valid")

        val keyed = keyedBodyOf()
        assertEquals(CircleCore.KEYED_FILE_BODY_KEYS, obj(keyed).keys)
        assertTrue(circleBodyValid(CircleEvent.FILE, keyed), "the keyed seven-key body is valid")
        assertTrue(CircleCore.validFileBody(keyed))

        // Every subset of either closed set is refused, including a legacy body
        // with a single keyed field grafted on.
        CircleCore.FILE_BODY_KEYS.forEach { missing ->
            assertFalse(
                circleBodyValid(CircleEvent.FILE, mutatedKeyedBody(legacy, drop = missing)),
                "legacy body missing $missing must be refused",
            )
        }
        CircleCore.KEYED_FILE_BODY_KEYS.forEach { missing ->
            assertFalse(
                circleBodyValid(CircleEvent.FILE, mutatedKeyedBody(keyed, drop = missing)),
                "keyed body missing $missing must be refused",
            )
        }
        listOf("token", "path", "spoolPath", "data", "bytes", "key", "wrappedFor").forEach { extra ->
            assertFalse(
                circleBodyValid(CircleEvent.FILE, mutatedKeyedBody(legacy, set = mapOf(extra to "x"))),
                "extra legacy key $extra must be refused",
            )
            assertFalse(
                circleBodyValid(CircleEvent.FILE, mutatedKeyedBody(keyed, set = mapOf(extra to "x"))),
                "extra keyed key $extra must be refused",
            )
        }
        assertFalse(
            circleBodyValid(CircleEvent.FILE, mutatedKeyedBody(legacy, set = mapOf("keyGen" to "1"))),
            "a partial key set is not a keyed body",
        )
    }

    @Test
    fun `file body gate bounds the keyed fields and refuses wrong-typed values`() {
        val keyed = keyedBodyOf()
        assertTrue(circleBodyValid(CircleEvent.FILE, keyed))

        // Types: size and keyGen are integers, never quoted strings.
        assertFalse(circleBodyValid(CircleEvent.FILE, mutatedKeyedBody(keyed, set = mapOf("size" to "12"))))
        assertFalse(circleBodyValid(CircleEvent.FILE, mutatedKeyedBody(keyed, set = mapOf("keyGen" to "1"))))
        assertFalse(circleBodyValid(CircleEvent.FILE, mutatedKeyedBody(keyed, set = mapOf("wraps" to "AAEC"))))

        // Field bounds: the iv is exactly 12 decoded bytes, a wrap value is
        // bounded by MAX_WRAPPED_CHARS, and a fingerprint is a non-blank
        // uppercase key.
        assertFalse(
            circleBodyValid(
                CircleEvent.FILE,
                keyedBodyOf(iv = Base64.getEncoder().encodeToString(ByteArray(DropKeyCore.IV_BYTES - 1))),
            ),
        )
        assertFalse(
            circleBodyValid(
                CircleEvent.FILE,
                keyedBodyOf(cipherSha = "x".repeat(CircleCore.MAX_FIELD_CHARS + 1)),
            ),
        )
        assertFalse(
            circleBodyValid(
                CircleEvent.FILE,
                keyedBodyOf(wraps = mapOf("AABB" to "x".repeat(DropKeyCore.MAX_WRAPPED_CHARS + 1))),
            ),
        )
        assertFalse(circleBodyValid(CircleEvent.FILE, keyedBodyOf(wraps = mapOf("aabb" to validWrap()))))
        assertFalse(circleBodyValid(CircleEvent.FILE, keyedBodyOf(wraps = mapOf(" " to validWrap()))))
        assertFalse(circleBodyValid(CircleEvent.FILE, keyedBodyOf(wraps = emptyMap())))
        assertFalse(
            circleBodyValid(
                CircleEvent.FILE,
                keyedBodyOf(wraps = (1..(DropKeyCore.MAX_WRAPS + 1)).associate { "FP$it" to validWrap() }),
            ),
            "a roster above the wrap cap must be refused, never trimmed",
        )
    }
}
