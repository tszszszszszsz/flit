package app.flit.share

import app.flit.share.protocol.circle.CircleEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `file event references only a manifest - never bytes`() {
        val b = obj(circleFileBody("notes.gflit", 4096L, "abc123", "resume-token"))
        assertEquals("notes.gflit", b.getValue("name").jsonPrimitive.content)
        assertEquals(4096L, b.getValue("size").jsonPrimitive.content.toLong())
        assertEquals("abc123", b.getValue("sha256").jsonPrimitive.content)
        assertEquals("resume-token", b.getValue("token").jsonPrimitive.content)
        assertEquals(setOf("name", "size", "sha256", "token"), b.keys)
        assertFalse(b.keys.any { it.contains("data", ignoreCase = true) || it.contains("bytes", ignoreCase = true) })
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
}
