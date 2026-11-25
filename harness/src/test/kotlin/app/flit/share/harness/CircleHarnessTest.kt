package app.flit.share.harness

import app.flit.share.protocol.client.ProtocolClient
import app.flit.share.protocol.client.SendTarget
import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.model.CircleRequest
import app.flit.share.protocol.model.CircleResponse
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

    private fun assertRekey(event: CircleEvent, key: String, gen: Int, members: Set<String>) {
        assertTrue(CircleCore.verify(event), "host's rekey event is not signed by the owner")
        val body = Json.parseToJsonElement(event.body).jsonObject
        assertEquals(gen, (body["gen"] as JsonPrimitive).content.toInt())
        assertEquals(key, (body["keyB64"] as? JsonPrimitive)?.content)
        assertEquals(members, body.getValue("members").jsonArray.map { it.jsonPrimitive.content }.toSet())
    }

    @Test
    fun `two nodes prove owner role appointment, admin kick, poll re-vote, file drop and rekey`() =
        runBlocking {
            val circleId = "circle-walk"
            val invite = "bearer-invite"
            val ownerId = TlsIdentity.generate()
            val memberId = TlsIdentity.generate()
            val thirdId = TlsIdentity.generate()
            val owner = ownerId.fingerprint
            val member = memberId.fingerprint
            val third = thirdId.fingerprint
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
                val memberClient = ProtocolClient(memberId)
                val thirdClient = ProtocolClient(thirdId)
                val ownerClient = ProtocolClient(ownerId)

                // A bearer invite is required: a wrong token is refused before
                // the roster is touched, with the host's own honest reason.
                val badJoin = memberClient.circleJoin(
                    target,
                    CircleRequest(
                        action = CircleRequest.ACTION_JOIN,
                        circleId = circleId,
                        invite = "wrong-invite",
                        memberFp = member,
                    ),
                )
                assertNotNull(badJoin)
                assertFalse(badJoin.ok)
                assertEquals("bad invite", badJoin.reason)
                assertTrue(delegate.circleHost.snapshot().members.none { it == member })

                // ---- join: the key comes back inside the authenticated session ----
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
                assertEquals("MEMBER", delegate.circleHost.snapshot().roles[member])

                // ---- 1. OWNER appoints an ADMIN; only the OWNER may do this ----
                val appoint = CircleCore.create(
                    circleId, owner, ownerId, CircleEvent.ROLE,
                    buildJsonObject {
                        put("targetFp", JsonPrimitive(member))
                        put("role", JsonPrimitive("ADMIN"))
                    }.toString(),
                    lamport = 1L,
                )
                assertTrue(CircleCore.verify(appoint))
                val appointed = ownerClient.sync(target, circleId, owner, listOf(appoint))
                assertNotNull(appointed)
                assertTrue(appointed.ok, "role sync refused: ${appointed.reason}")
                val promoted = delegate.circleHost.snapshot()
                assertEquals("ADMIN", promoted.roles[member], "owner's appointment was not applied")
                assertEquals("OWNER", promoted.roles[owner])
                assertEquals(0, promoted.rekeyGen, "a role change alone must not rekey")
                assertTrue(promoted.events.any { it.id == appoint.id })

                // An ADMIN cannot promote the OWNER: the event is history, not governance.
                val selfPromotion = CircleCore.create(
                    circleId, member, memberId, CircleEvent.ROLE,
                    buildJsonObject {
                        put("targetFp", JsonPrimitive(owner))
                        put("role", JsonPrimitive("ADMIN"))
                    }.toString(),
                    lamport = 2L,
                )
                val inert = memberClient.sync(target, circleId, member, listOf(selfPromotion))
                assertNotNull(inert)
                assertTrue(inert.ok, "the sync succeeds; the unauthorised role is inert")
                assertEquals("OWNER", delegate.circleHost.snapshot().roles[owner])
                assertEquals("ADMIN", delegate.circleHost.snapshot().roles[member])

                // A third device joins so the promoted ADMIN has someone to kick.
                val thirdJoined = thirdClient.circleJoin(
                    target,
                    CircleRequest(
                        action = CircleRequest.ACTION_JOIN,
                        circleId = circleId,
                        invite = invite,
                        memberFp = third,
                    ),
                )
                assertNotNull(thirdJoined)
                assertTrue(thirdJoined.ok, "third join refused: ${thirdJoined.reason}")
                assertEquals(setOf(owner, member, third), thirdJoined.members.toSet())
                assertEquals("MEMBER", delegate.circleHost.snapshot().roles[third])

                // ---- 2. message sync through the wire ----
                val message = CircleCore.create(
                    circleId, member, memberId, CircleEvent.MESSAGE,
                    """{"text":"hello circle"}""", lamport = 3L,
                )
                assertTrue(CircleCore.verify(message))
                val posted = memberClient.sync(target, circleId, member, listOf(message))
                assertNotNull(posted)
                assertTrue(posted.ok)
                assertTrue(posted.events.any { it.id == message.id }, "message event missing from sync answer")
                assertEquals(1, posted.events.count { it.type == CircleEvent.MESSAGE })
                assertEquals(1, delegate.circleHost.snapshot().events.count { it.type == CircleEvent.MESSAGE })

                // A pull with an empty delta is the catch-up path.
                val pulled = memberClient.sync(target, circleId, member)
                assertNotNull(pulled)
                assertTrue(pulled.events.any { it.id == message.id })

                // ---- 3. poll + re-vote: latest vote per member wins, history stays ----
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
                    lamport = 4L,
                )
                val voteYes = CircleCore.create(
                    circleId, member, memberId, CircleEvent.VOTE,
                    """{"pollId":"$pollId","option":0}""", lamport = 5L,
                )
                val voteNo = CircleCore.create(
                    circleId, member, memberId, CircleEvent.VOTE,
                    """{"pollId":"$pollId","option":1}""", lamport = 6L,
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

                // ---- 4. file-drop event: only a reference, hash-exact ----
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
                    lamport = 7L,
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

                // ---- 5. the promoted ADMIN's kick really takes effect ----
                val beforeAdminKick = delegate.circleHost.snapshot()
                val adminKick = CircleCore.create(
                    circleId, member, memberId, CircleEvent.KICK,
                    buildJsonObject {
                        put("kickId", JsonPrimitive("admin-kick-1"))
                        put("targetFp", JsonPrimitive(third))
                        put("approve", JsonPrimitive(true))
                        put("mode", JsonPrimitive("admin"))
                    }.toString(),
                    lamport = 8L,
                )
                assertTrue(CircleCore.verify(adminKick))
                val adminKicked = memberClient.sync(target, circleId, member, listOf(adminKick))
                assertNotNull(adminKicked)
                assertTrue(adminKicked.ok, "admin kick refused: ${adminKicked.reason}")
                val afterAdmin = delegate.circleHost.snapshot()
                assertFalse(afterAdmin.members.contains(third), "promoted admin's kick did not remove the member")
                assertEquals(setOf(owner, member), afterAdmin.members.toSet())
                assertEquals("ADMIN", afterAdmin.roles[member], "kicking must not silently demote the admin")
                assertEquals(1, afterAdmin.rekeyGen)
                assertTrue(beforeAdminKick.key != afterAdmin.key, "admin kick did not rotate the group key")
                val adminRekey = afterAdmin.events.filter { it.type == CircleEvent.REKEY }
                assertEquals(1, adminRekey.size)
                assertRekey(adminRekey.single(), afterAdmin.key, 1, setOf(owner, member))

                // The departed device cannot read a single later event.
                val thirdDenied = thirdClient.sync(target, circleId, third)
                assertNotNull(thirdDenied)
                assertFalse(thirdDenied.ok, "departed member was still served")
                assertEquals("not-a-member", thirdDenied.reason)
                assertTrue(thirdDenied.events.isEmpty())

                // ---- 6. an OWNER kick of an ADMIN also rekeys ----
                val keyBefore = afterAdmin.key
                val kick = CircleCore.create(
                    circleId, owner, ownerId, CircleEvent.KICK,
                    buildJsonObject {
                        put("kickId", JsonPrimitive("kick-1"))
                        put("targetFp", JsonPrimitive(member))
                        put("approve", JsonPrimitive(true))
                    }.toString(),
                    lamport = 9L,
                )
                assertTrue(CircleCore.verify(kick))
                // The host is the owner in this process, so the owner's own
                // kick enters the same production onCircle route as any event.
                val kicked = ownerClient.sync(target, circleId, owner, listOf(kick))
                assertNotNull(kicked)
                assertTrue(kicked.ok)

                val after = delegate.circleHost.snapshot()
                assertFalse(after.members.contains(member), "kicked member still on the roster")
                assertEquals(listOf(owner), after.members)
                assertTrue(keyBefore != after.key, "kick did not rotate the group key")
                assertEquals(2, after.rekeyGen)
                val rekeyEvents = after.events.filter { it.type == CircleEvent.REKEY }
                assertEquals(2, rekeyEvents.size)
                val rekey = rekeyEvents.maxByOrNull { it.lamport }!!
                assertRekey(rekey, after.key, 2, setOf(owner))
                assertEquals(after.events.size, delegate.circleHost.snapshot().events.size)

                val denied = memberClient.sync(target, circleId, member)
                assertNotNull(denied)
                assertFalse(denied.ok, "departed member was still served")
                assertEquals("not-a-member", denied.reason)
                assertTrue(denied.events.isEmpty())

                thirdClient.close()
                memberClient.close()
            } finally {
                server.close()
            }
        }

    @Test
    fun `host ignores a stale signed role event via the replay guard`() {
        val circleId = "stale-walk"
        val invite = "bearer-invite"
        val ownerId = TlsIdentity.generate()
        val memberId = TlsIdentity.generate()
        val owner = ownerId.fingerprint
        val member = memberId.fingerprint
        val host = CircleHostState()
        host.seed(circleId, invite, "group-key-v1", owner, ownerId)

        val joined = host.onCircle(
            CircleRequest(CircleRequest.ACTION_JOIN, circleId, invite = invite, memberFp = member),
        )
        assertTrue(joined.ok)

        // Properly signed, but older than the replay window: history, not governance.
        val stale = CircleCore.create(
            circleId, owner, ownerId, CircleEvent.ROLE,
            """{"targetFp":"$member","role":"ADMIN"}""",
            lamport = 1L,
            createdMs = System.currentTimeMillis() - CircleCore.REPLAY_WINDOW_MS - 10_000L,
        )
        assertTrue(CircleCore.verify(stale))
        val answered = host.onCircle(
            CircleRequest(CircleRequest.ACTION_SYNC, circleId, memberFp = owner, events = listOf(stale)),
        )
        assertTrue(answered.ok)
        assertTrue(answered.events.none { it.id == stale.id }, "stale event was admitted")
        assertEquals("MEMBER", host.snapshot().roles[member])
        assertEquals(0, host.snapshot().rekeyGen)
    }

    @Test
    fun `circle CLI errors say whether the host refused or never answered`() {
        val refused = explainCircleFailure(
            CircleResponse(false, reason = "bad invite"), null, "10.0.0.1:4242",
        )
        assertEquals("host refused: bad invite", refused)
        assertFalse(refused.contains("no answer"))

        val silent = explainCircleFailure(null, null, "10.0.0.1:4242")
        assertTrue(silent.contains("no answer from 10.0.0.1:4242"))
        assertFalse(silent.contains("host refused"))

        val transport = explainCircleFailure(null, IllegalStateException("handshake failed"), "host")
        assertTrue(transport.contains("handshake failed"))
        assertTrue(transport.contains("no answer from host"))
        assertFalse(transport.contains("host refused"))
    }
}
