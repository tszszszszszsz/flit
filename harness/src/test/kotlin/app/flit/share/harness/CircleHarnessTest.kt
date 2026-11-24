package app.flit.share.harness

import app.flit.share.protocol.client.ProtocolClient
import app.flit.share.protocol.client.SendTarget
import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.model.CircleRequest
import app.flit.share.protocol.server.ProtocolServer
import app.flit.share.protocol.server.ServerConfig
import app.flit.share.protocol.tls.TlsIdentity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The CLI's two-node walk, in one JVM but over the *production* endpoints: a
 * real TLS [ProtocolServer] hosts the log behind [ConsoleDelegate.circleHost]
 * and a [ProtocolClient] joins and syncs through [ProtocolClient.circleJoin] /
 * [ProtocolClient.circleSync]. Events are minted with [CircleCore.create] and
 * verified with [CircleCore.verify]. Nothing here is a lookalike core.
 */
class CircleHarnessTest {

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

    @Test
    fun `two in-process nodes sync a message, a re-voted poll, a file drop and a kick with rekey`() =
        runBlocking {
            val circleId = "circle-walk"
            val invite = "bearer-invite"
            val ownerId = TlsIdentity.generate()
            val memberId = TlsIdentity.generate()
            val owner = ownerId.fingerprint
            val member = memberId.fingerprint
            val keyV1 = "group-key-v1"

            val delegate = ConsoleDelegate()
            delegate.circleHost.seed(circleId, invite, keyV1, owner, ownerId)
            val server = ProtocolServer(
                ownerId, delegate,
                ServerConfig(
                    alias = "circle-host", port = 0, tls = true,
                    destDir = Files.createTempDirectory("circle-harness"),
                ),
            )
            server.start()
            val target = SendTarget("127.0.0.1", server.port, tls = true)

            try {
                // ---- join: the key comes back inside the authenticated session ----
                val memberClient = ProtocolClient(memberId)
                val joined = memberClient.circleJoin(
                    target,
                    CircleRequest(
                        action = CircleRequest.ACTION_JOIN,
                        circleId = circleId,
                        invite = invite,
                        memberFp = member,
                    ),
                )
                assertNotNull(joined, "join got no answer")
                assertTrue(joined.ok, "join refused: ${joined.reason}")
                assertEquals(keyV1, joined.key)
                assertEquals(setOf(owner, member), joined.members.toSet())
                assertTrue(joined.events.isEmpty())

                // ---- 1. message sync through the wire ----
                val message = CircleCore.create(
                    circleId, member, memberId, CircleEvent.MESSAGE,
                    """{"text":"hello circle"}""", lamport = 1L,
                )
                assertTrue(CircleCore.verify(message))
                val posted = memberClient.sync(target, circleId, member, listOf(message))
                assertNotNull(posted)
                assertTrue(posted.ok)
                assertEquals(listOf(message.id), posted.events.map { it.id })
                assertEquals(1, delegate.circleHost.snapshot().events.size)

                // A pull with an empty delta is the catch-up path.
                val pulled = memberClient.sync(target, circleId, member)
                assertNotNull(pulled)
                assertEquals(listOf(message.id), pulled.events.map { it.id })

                // ---- 2. poll + re-vote: latest vote per member wins, history stays ----
                val pollId = "poll-1"
                val poll = CircleCore.create(
                    circleId, member, memberId, CircleEvent.POLL,
                    buildJsonObject {
                        put("pollId", JsonPrimitive(pollId))
                        put("question", JsonPrimitive("Ship it?"))
                        put("options", buildJsonArray {
                            add(JsonPrimitive("yes")); add(JsonPrimitive("no"))
                        })
                    }.toString(),
                    lamport = 2L,
                )
                val voteYes = CircleCore.create(
                    circleId, member, memberId, CircleEvent.VOTE,
                    """{"pollId":"$pollId","option":0}""", lamport = 3L,
                )
                val voteNo = CircleCore.create(
                    circleId, member, memberId, CircleEvent.VOTE,
                    """{"pollId":"$pollId","option":1}""", lamport = 4L,
                )
                val firstVote = memberClient.sync(target, circleId, member, listOf(poll, voteYes))
                assertNotNull(firstVote)
                assertTrue(firstVote.ok)
                assertEquals(mapOf("0" to 1), CircleCore.tally(firstVote.events, pollId, firstVote.members))

                val changedVote = memberClient.sync(target, circleId, member, listOf(voteNo))
                assertNotNull(changedVote)
                assertTrue(changedVote.ok)
                assertEquals(mapOf("1" to 1), CircleCore.tally(changedVote.events, pollId, changedVote.members))
                assertEquals(
                    2, changedVote.events.count { it.type == CircleEvent.VOTE },
                    "a changed answer appends a new vote; both stay in the log",
                )

                // ---- 3. file-drop event: only a reference, hash-exact ----
                val payload = ByteArray(4096).also { java.util.Random(7).nextBytes(it) }
                val sha = ProtocolClient.sha256Hex { ByteArrayInputStream(payload) }
                val fileDrop = CircleCore.create(
                    circleId, member, memberId, CircleEvent.FILE,
                    buildJsonObject {
                        put("name", JsonPrimitive("drop.bin"))
                        put("size", JsonPrimitive(payload.size.toLong()))
                        put("sha256", JsonPrimitive(sha))
                        put("token", JsonPrimitive("resume-token-1"))
                    }.toString(),
                    lamport = 5L,
                )
                val dropped = memberClient.sync(target, circleId, member, listOf(fileDrop))
                assertNotNull(dropped)
                assertTrue(dropped.ok)
                val landed = dropped.events.single { it.type == CircleEvent.FILE }
                assertTrue(CircleCore.verify(landed))
                val manifest = Json.parseToJsonElement(landed.body).jsonObject
                assertEquals(sha, (manifest["sha256"] as JsonPrimitive).content)
                assertEquals(payload.size.toLong(), (manifest["size"] as JsonPrimitive).content.toLong())
                assertEquals("drop.bin", (manifest["name"] as JsonPrimitive).content)

                // ---- 4. kick + rekey removes the member from the roster ----
                val before = delegate.circleHost.snapshot()
                assertEquals(setOf(owner, member), before.members.toSet())
                val keyBefore = before.key
                val kick = CircleCore.create(
                    circleId, owner, ownerId, CircleEvent.KICK,
                    buildJsonObject {
                        put("kickId", JsonPrimitive("kick-1"))
                        put("targetFp", JsonPrimitive(member))
                        put("approve", JsonPrimitive(true))
                    }.toString(),
                    lamport = 6L,
                )
                assertTrue(CircleCore.verify(kick))
                // The host is the owner in this process, so the owner's own
                // kick enters the same production onCircle route as any event.
                val kicked = memberClient.sync(target, circleId, owner, listOf(kick))
                assertNotNull(kicked)
                assertTrue(kicked.ok)

                val after = delegate.circleHost.snapshot()
                assertFalse(after.members.contains(member), "kicked member still on the roster")
                assertEquals(listOf(owner), after.members)
                assertTrue(keyBefore != after.key, "kick did not rotate the group key")
                assertEquals(1, after.rekeyGen)
                val rekeyEvents = after.events.filter { it.type == CircleEvent.REKEY }
                assertEquals(1, rekeyEvents.size)
                val rekey = rekeyEvents.single()
                assertTrue(CircleCore.verify(rekey))
                val rekeyBody = Json.parseToJsonElement(rekey.body).jsonObject
                assertEquals(1, (rekeyBody["gen"] as JsonPrimitive).content.toInt())
                val rekeyMembers = rekeyBody.getValue("members").jsonArray
                    .map { it.jsonPrimitive.content }
                assertEquals(listOf(owner), rekeyMembers)
                assertEquals(after.events.size, delegate.circleHost.snapshot().events.size)

                // A departed device cannot read a single later event.
                val denied = memberClient.sync(target, circleId, member)
                assertNotNull(denied)
                assertFalse(denied.ok, "departed member was still served")
                assertEquals("not-a-member", denied.reason)
                assertTrue(denied.events.isEmpty())

                memberClient.close()
            } finally {
                server.close()
            }
        }
}
