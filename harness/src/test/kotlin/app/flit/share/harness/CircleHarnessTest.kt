package app.flit.share.harness

import app.flit.share.protocol.client.ProtocolClient
import app.flit.share.protocol.client.SendError
import app.flit.share.protocol.client.SendReport
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
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    /** The signed proof-of-possession the production JOIN route now requires. */
    private fun joinProof(
        hostFp: String,
        circleId: String,
        invite: String,
        identity: TlsIdentity,
        nowMs: Long = System.currentTimeMillis(),
    ): CircleEvent = CircleCore.create(
        circleId, identity.fingerprint, identity, CircleEvent.JOIN,
        CircleCore.joinProofBody(
            hostFp, circleId, invite, identity.fingerprint, CircleCore.joinProofBucket(nowMs),
        ),
        lamport = 0L, createdMs = nowMs,
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
                        // The proof binds the presented invite, so it is valid
                        // for this request and still reaches the host's gate.
                        events = listOf(joinProof(owner, circleId, "wrong-invite", memberId)),
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
                        events = listOf(joinProof(owner, circleId, invite, memberId)),
                    ),
                )
                assertNotNull(joined, "join got no answer")
                assertTrue(joined.ok, "join refused: ${joined.reason}")
                assertTrue(CircleResponse.verify(joined, owner), "join answer was not signed by the host")
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
                        events = listOf(joinProof(owner, circleId, invite, thirdId)),
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
                val payload = ByteArray(4096).also { Random(7).nextBytes(it) }
                val sha = ProtocolClient.sha256Hex { ByteArrayInputStream(payload) }
                val announcement = circleFileAnnouncement("drop.bin", payload.size.toLong(), sha)
                assertTrue(CircleCore.validFileBody(announcement))
                val fileDrop = CircleCore.create(
                    circleId, member, memberId, CircleEvent.FILE, announcement, lamport = 7L,
                )
                val dropped = memberClient.sync(target, circleId, member, listOf(fileDrop))
                assertNotNull(dropped)
                assertTrue(dropped.ok)
                val landed = dropped.events.single { it.type == CircleEvent.FILE }
                assertTrue(CircleCore.verify(landed))
                assertTrue(CircleCore.validFileBody(landed.body))
                val manifest = Json.parseToJsonElement(landed.body).jsonObject
                assertEquals(setOf("name", "size", "sha256"), manifest.keys)
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
                assertEquals("", adminKicked.key, "a SYNC answer never hands out the group key")
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
                assertEquals("", kicked.key, "a SYNC answer never hands out the group key")

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
            CircleRequest(
                CircleRequest.ACTION_JOIN, circleId, invite = invite, memberFp = member,
                events = listOf(joinProof(ownerId.fingerprint, circleId, invite, memberId)),
            ),
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

    @Test
    fun `circle file lands hash-exact bytes and walks pending to sent delivery status`() = runBlocking {
        val circleId = "circle-file-walk"
        val invite = "bearer-invite"
        val senderId = TlsIdentity.generate()
        val receiverId = TlsIdentity.generate()
        val sender = senderId.fingerprint
        val receiver = receiverId.fingerprint
        val dest = Files.createTempDirectory("circle-file-landing")

        val delegate = ConsoleDelegate()
        delegate.circleHost.seed(circleId, invite, "group-key-v1", receiver, receiverId)
        val server = ProtocolServer(
            receiverId, delegate,
            ServerConfig(alias = "circle-file-host", port = 0, tls = true, destDir = dest),
        )
        server.start()
        val target = SendTarget("127.0.0.1", server.port, tls = true)
        // A port nobody listens on: the carrier throws, so this recipient must
        // stay pending (spool kept for retry), not be concluded as failed.
        val deadPort = java.net.ServerSocket(0).use { it.localPort }
        val dead = "DEAD-ROUTE-FP"

        try {
            val memberClient = ProtocolClient(senderId)
            val joined = memberClient.circleJoin(
                target,
                CircleRequest(
                    CircleRequest.ACTION_JOIN, circleId, invite = invite, memberFp = sender,
                    events = listOf(joinProof(receiver, circleId, invite, senderId)),
                ),
            )
            assertNotNull(joined, "join got no answer")
            assertTrue(joined.ok, "join refused: ${joined.reason}")
            assertEquals(setOf(receiver, sender), joined.members.toSet())

            // Real bytes in a real spool file — the CLI drops a path, not an in-memory buffer.
            val payload = ByteArray(8192).also { Random(11).nextBytes(it) }
            val spool = Files.createTempFile("circle-drop", ".bin")
            Files.write(spool, payload)
            val sha = ProtocolClient.sha256Hex { java.io.FileInputStream(spool.toFile()) }
            val name = spool.fileName.toString()

            // The announcement is name/size/sha256 only: no local path, no token.
            val announcement = circleFileAnnouncement(name, payload.size.toLong(), sha)
            assertTrue(CircleCore.validFileBody(announcement))
            val ev = CircleCore.create(circleId, sender, senderId, CircleEvent.FILE, announcement, lamport = 1L)
            val posted = memberClient.sync(target, circleId, sender, listOf(ev))
            assertNotNull(posted)
            assertTrue(posted.ok, "file event refused: ${posted.reason}")
            val landed = posted.events.single { it.type == CircleEvent.FILE }
            assertTrue(CircleCore.verify(landed))
            assertTrue(CircleCore.validFileBody(landed.body))
            assertEquals(announcement, landed.body)
            val manifest = Json.parseToJsonElement(landed.body).jsonObject
            assertEquals(setOf("name", "size", "sha256"), manifest.keys)
            assertFalse(landed.body.contains(spool.toString()), "sender's local spool path went on the wire")
            assertFalse(manifest.containsKey("token"), "a queue token went on the wire")

            // Production sendFiles to every current member (host here + a dead route).
            val transitions = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
            val delivery = deliverCircleFile(
                identity = senderId,
                spoolPath = spool,
                recipients = listOf(
                    CircleRecipient(receiver, target),
                    CircleRecipient(dead, SendTarget("127.0.0.1", deadPort, tls = true)),
                ),
                onStatus = { fp, status -> transitions += fp to status },
            )

            // Status transitions are local-only and observable: reachable member
            // goes pending → sent; a thrown carrier stays pending for retry.
            assertEquals(
                listOf(receiver to CircleDeliveryStatus.PENDING, dead to CircleDeliveryStatus.PENDING,
                    receiver to CircleDeliveryStatus.SENT),
                transitions,
            )
            assertEquals(CircleDeliveryStatus.SENT, delivery.statuses[receiver])
            assertEquals(CircleDeliveryStatus.PENDING, delivery.statuses[dead])
            assertEquals(sha, delivery.sha256)
            assertEquals(name, delivery.name)
            assertEquals(payload.size.toLong(), delivery.size)
            assertEquals(
                listOf(announcement),
                delegate.circleHost.snapshot().events.filter { it.type == CircleEvent.FILE }.map { it.body },
                "the host log holds only the announcement — delivery status is local-only",
            )

            // Hash-exact landing on the receiver's disk, via the normal upload route.
            val landedFiles = Files.list(dest).use { stream ->
                stream.filter { Files.isRegularFile(it) }.toList()
            }
            assertEquals(1, landedFiles.size, "expected exactly one landed file")
            assertContentEquals(payload, Files.readAllBytes(landedFiles.single()))
            assertEquals(sha, ProtocolClient.sha256Hex { Files.newInputStream(landedFiles.single()) })

            memberClient.close()
        } finally {
            server.close()
        }
    }

    @Test
    fun `circle delivery outcome maps a returned report but never a throw`() {
        val name = "drop.bin"
        assertEquals(
            CircleDeliveryStatus.SENT,
            circleDeliveryOutcome(SendReport(listOf(name), emptyList(), "s1"), name),
        )
        assertEquals(
            CircleDeliveryStatus.FAILED,
            circleDeliveryOutcome(SendReport(emptyList(), listOf(SendError(name, "checksum mismatch")), "s1"), name),
        )
        assertEquals(
            CircleDeliveryStatus.REFUSED,
            circleDeliveryOutcome(SendReport(emptyList(), emptyList(), null), name),
        )
        assertEquals(CircleDeliveryStatus.PENDING, circleDeliveryOutcome(null, name))
    }

    @Test
    fun `a member cannot inject another member's signed event through SYNC`() {
        val circleId = "author-gate"
        val invite = "bearer-invite"
        val ownerId = TlsIdentity.generate()
        val memberId = TlsIdentity.generate()
        val owner = ownerId.fingerprint
        val member = memberId.fingerprint
        val host = CircleHostState()
        host.seed(circleId, invite, "group-key-v1", owner, ownerId)
        assertTrue(
            host.onCircle(
                CircleRequest(
                    CircleRequest.ACTION_JOIN, circleId, invite = invite, memberFp = member,
                    events = listOf(joinProof(owner, circleId, invite, memberId)),
                ),
            ).ok,
        )

        // Properly signed by the owner, but submitted by a plain member: the
        // request cert binds only that member, so the event is not admissible.
        val borrowed = CircleCore.create(
            circleId, owner, ownerId, CircleEvent.MESSAGE,
            """{"text":"borrowed"}""", lamport = 1L,
        )
        val injected = host.onCircle(
            CircleRequest(CircleRequest.ACTION_SYNC, circleId, memberFp = member, events = listOf(borrowed)),
        )
        assertTrue(injected.ok)
        assertTrue(injected.events.none { it.id == borrowed.id }, "a borrowed author entered the log")

        // The refusal must not burn the id: the real author may still post it.
        val resubmitted = host.onCircle(
            CircleRequest(CircleRequest.ACTION_SYNC, circleId, memberFp = owner, events = listOf(borrowed)),
        )
        assertTrue(resubmitted.ok)
        assertTrue(resubmitted.events.any { it.id == borrowed.id }, "the author's id was consumed by a forgery")
    }

    @Test
    fun `a signed JOIN injected through SYNC neither enters the log nor changes the roster`() {
        val circleId = "join-injection"
        val invite = "bearer-invite"
        val ownerId = TlsIdentity.generate()
        val memberId = TlsIdentity.generate()
        val owner = ownerId.fingerprint
        val member = memberId.fingerprint
        val host = CircleHostState()
        host.seed(circleId, invite, "group-key-v1", owner, ownerId)
        host.onCircle(
            CircleRequest(
                CircleRequest.ACTION_JOIN, circleId, invite = invite, memberFp = member,
                events = listOf(joinProof(owner, circleId, invite, memberId)),
            ),
        )

        // A member may know a perfectly valid JOIN proof — it is still a
        // host-routed handshake, never a log entry contributed via SYNC.
        val injected = joinProof(owner, circleId, invite, memberId)
        val answered = host.onCircle(
            CircleRequest(CircleRequest.ACTION_SYNC, circleId, memberFp = member, events = listOf(injected)),
        )
        assertTrue(answered.ok)
        assertTrue(answered.events.none { it.id == injected.id }, "JOIN entered the signed log via SYNC")
        assertEquals(setOf(owner, member), host.snapshot().members.toSet())
    }

    @Test
    fun `only an officer REKEY installs a key and a lower generation never rolls it back`() {
        val circleId = "rekey-governance"
        val invite = "bearer-invite"
        val ownerId = TlsIdentity.generate()
        val memberId = TlsIdentity.generate()
        val owner = ownerId.fingerprint
        val member = memberId.fingerprint
        val host = CircleHostState()
        host.seed(circleId, invite, "group-key-v1", owner, ownerId)
        host.onCircle(
            CircleRequest(
                CircleRequest.ACTION_JOIN, circleId, invite = invite, memberFp = member,
                events = listOf(joinProof(owner, circleId, invite, memberId)),
            ),
        )

        fun rekeyBody(key: String, gen: Int) = buildJsonObject {
            put("keyB64", JsonPrimitive(key))
            put("gen", JsonPrimitive(gen))
            putJsonArray("members") { add(JsonPrimitive(owner)); add(JsonPrimitive(member)) }
        }.toString()

        // A MEMBER-signed rekey is history, even if its signature is perfect.
        val memberRekey = CircleCore.create(
            circleId, member, memberId, CircleEvent.REKEY, rekeyBody("member-key", 1), lamport = 1L,
        )
        val skipped = host.onCircle(
            CircleRequest(CircleRequest.ACTION_SYNC, circleId, memberFp = member, events = listOf(memberRekey)),
        )
        assertTrue(skipped.ok)
        assertEquals("group-key-v1", host.snapshot().key)
        assertTrue(host.snapshot().events.none { it.id == memberRekey.id })

        // The owner appoints an ADMIN; now that ADMIN may rotate the key.
        val role = CircleCore.create(
            circleId, owner, ownerId, CircleEvent.ROLE,
            buildJsonObject {
                put("targetFp", JsonPrimitive(member))
                put("role", JsonPrimitive("ADMIN"))
            }.toString(),
            lamport = 2L,
        )
        assertTrue(host.onCircle(CircleRequest(CircleRequest.ACTION_SYNC, circleId, memberFp = owner, events = listOf(role))).ok)
        assertEquals("ADMIN", host.snapshot().roles[member])

        val ownerRekey = CircleCore.create(
            circleId, owner, ownerId, CircleEvent.REKEY, rekeyBody("owner-key-v2", 1), lamport = 3L,
        )
        assertTrue(host.onCircle(CircleRequest(CircleRequest.ACTION_SYNC, circleId, memberFp = owner, events = listOf(ownerRekey))).ok)
        assertEquals("owner-key-v2", host.snapshot().key)
        assertEquals(1, host.snapshot().rekeyGen)

        // Same officer, older generation: the key must never roll back.
        val stale = CircleCore.create(
            circleId, member, memberId, CircleEvent.REKEY, rekeyBody("old-key-v0", 0), lamport = 4L,
        )
        assertTrue(host.onCircle(CircleRequest(CircleRequest.ACTION_SYNC, circleId, memberFp = member, events = listOf(stale))).ok)
        assertEquals("owner-key-v2", host.snapshot().key, "a stale rekey rolled the key back")
        assertEquals(1, host.snapshot().rekeyGen)
    }

    @Test
    fun `a departing owner or admin cannot hand off the group key in either fold order`() {
        val circleId = "departure-handoff"
        val invite = "bearer-invite"
        val ownerId = TlsIdentity.generate()
        val adminId = TlsIdentity.generate()
        val memberId = TlsIdentity.generate()
        val owner = ownerId.fingerprint
        val admin = adminId.fingerprint
        val member = memberId.fingerprint
        val originalKey = "group-key-v1"
        val attackerKey = "attacker-key"

        /** One batch from [officer], with the two events folded in either order. */
        fun handoff(officer: TlsIdentity, rekeyFirst: Boolean) {
            val host = CircleHostState()
            host.seed(circleId, invite, originalKey, owner, ownerId)
            assertTrue(
                host.onCircle(
                    CircleRequest(
                        CircleRequest.ACTION_JOIN, circleId, invite = invite, memberFp = member,
                        events = listOf(joinProof(owner, circleId, invite, memberId)),
                    ),
                ).ok,
            )
            if (officer.fingerprint != owner) {
                assertTrue(
                    host.onCircle(
                        CircleRequest(
                            CircleRequest.ACTION_JOIN, circleId, invite = invite, memberFp = admin,
                            events = listOf(joinProof(owner, circleId, invite, adminId)),
                        ),
                    ).ok,
                )
                val appoint = CircleCore.create(
                    circleId, owner, ownerId, CircleEvent.ROLE,
                    """{"targetFp":"$admin","role":"ADMIN"}""", lamport = 1L,
                )
                assertTrue(
                    host.onCircle(
                        CircleRequest(CircleRequest.ACTION_SYNC, circleId, memberFp = owner, events = listOf(appoint)),
                    ).ok,
                )
                assertEquals("ADMIN", host.snapshot().roles[admin])
            }

            val officerFp = officer.fingerprint
            val genBefore = host.snapshot().rekeyGen
            val keyBefore = host.snapshot().key
            val handoffBody = buildJsonObject {
                put("keyB64", JsonPrimitive(attackerKey))
                put("gen", JsonPrimitive(genBefore + 1))
            }.toString()
            // CircleCore.order sorts by lamport: these two lamports choose the
            // fold order. Submit in the OPPOSITE order so arrival cannot matter.
            val rekey = CircleCore.create(
                circleId, officerFp, officer, CircleEvent.REKEY, handoffBody,
                lamport = if (rekeyFirst) 10L else 20L,
            )
            val leave = CircleCore.create(
                circleId, officerFp, officer, CircleEvent.LEAVE, "{}",
                lamport = if (rekeyFirst) 20L else 10L,
            )
            val batch = if (rekeyFirst) listOf(leave, rekey) else listOf(rekey, leave)
            val answered = host.onCircle(
                CircleRequest(CircleRequest.ACTION_SYNC, circleId, memberFp = officerFp, events = batch),
            )
            assertTrue(answered.ok, "sync refused: ${answered.reason}")

            val after = host.snapshot()
            assertFalse(after.members.contains(officerFp), "the officer did not depart")
            assertFalse(after.key == attackerKey, "the attacker's key was installed")
            assertFalse(after.key == keyBefore, "departure did not rotate the key")
            assertTrue(after.key.isNotBlank())
            assertTrue(after.events.none { it.id == rekey.id }, "the hand-off REKEY entered the signed log")
            assertTrue(answered.events.none { it.id == rekey.id })
            assertEquals(genBefore + 1, after.rekeyGen, "only the departure bumped the generation")
            val rotations = after.events.filter { it.type == CircleEvent.REKEY }
            assertEquals(1, rotations.size)
            assertRekey(rotations.single(), after.key, after.rekeyGen, after.members.toSet())

            val denied = host.onCircle(CircleRequest(CircleRequest.ACTION_SYNC, circleId, memberFp = officerFp))
            assertFalse(denied.ok, "the departed officer was still served")
            assertEquals("not-a-member", denied.reason)
        }

        handoff(adminId, rekeyFirst = true)
        handoff(adminId, rekeyFirst = false)
        handoff(ownerId, rekeyFirst = true)
        handoff(ownerId, rekeyFirst = false)
    }

    @Test
    fun `a forced rotation outranks a rekey admitted in the same batch`() {
        val circleId = "rekey-departure-gen"
        val invite = "bearer-invite"
        val ownerId = TlsIdentity.generate()
        val memberId = TlsIdentity.generate()
        val owner = ownerId.fingerprint
        val member = memberId.fingerprint
        val officerKey = "officer-key-v7"

        fun run(rekeyFirst: Boolean) {
            val host = CircleHostState()
            host.seed(circleId, invite, "group-key-v1", owner, ownerId)
            assertTrue(
                host.onCircle(
                    CircleRequest(
                        CircleRequest.ACTION_JOIN, circleId, invite = invite, memberFp = member,
                        events = listOf(joinProof(owner, circleId, invite, memberId)),
                    ),
                ).ok,
            )
            val before = host.snapshot()
            val requestedGen = before.rekeyGen + 7
            val rekey = CircleCore.create(
                circleId, owner, ownerId, CircleEvent.REKEY,
                buildJsonObject {
                    put("keyB64", JsonPrimitive(officerKey))
                    put("gen", JsonPrimitive(requestedGen))
                }.toString(),
                lamport = if (rekeyFirst) 1L else 2L,
            )
            val kick = CircleCore.create(
                circleId, owner, ownerId, CircleEvent.KICK,
                buildJsonObject {
                    put("kickId", JsonPrimitive("kick-$rekeyFirst"))
                    put("targetFp", JsonPrimitive(member))
                    put("approve", JsonPrimitive(true))
                    put("mode", JsonPrimitive("admin"))
                }.toString(),
                lamport = if (rekeyFirst) 2L else 1L,
            )
            val batch = if (rekeyFirst) listOf(rekey, kick) else listOf(kick, rekey)
            val answered = host.onCircle(
                CircleRequest(CircleRequest.ACTION_SYNC, circleId, memberFp = owner, events = batch),
            )
            assertTrue(answered.ok, "sync refused: ${answered.reason}")
            val after = host.snapshot()
            assertFalse(after.members.contains(member))
            assertTrue(
                after.key != before.key && after.key != officerKey,
                "the fresh host key must replace an admitted rekey on departure",
            )
            assertEquals(requestedGen + 1, after.rekeyGen, "generation must stay monotonic")
            val hostRekey = after.events.filter { it.type == CircleEvent.REKEY }
                .maxByOrNull { e ->
                    (Json.parseToJsonElement(e.body).jsonObject["gen"] as JsonPrimitive).content.toInt()
                }!!
            assertRekey(hostRekey, after.key, after.rekeyGen, setOf(owner))
            // A peer folding the signed answer lands on the host's fresh key.
            val st = CircleCliState(circleId = circleId, key = before.key, hostFp = owner)
            mergeCircleAnswer(st, answered)
            assertEquals(after.key, st.key)
            assertEquals(after.rekeyGen, st.rekeyGen)
        }

        run(rekeyFirst = true)
        run(rekeyFirst = false)
    }

    @Test
    fun `the JOIN proof gate refuses an invited attacker without a signed proof`() = runBlocking {
        val circleId = "join-proof-walk"
        val invite = "bearer-invite"
        val ownerId = TlsIdentity.generate()
        val attackerId = TlsIdentity.generate()
        val victimId = TlsIdentity.generate()
        val owner = ownerId.fingerprint
        val attacker = attackerId.fingerprint
        val victim = victimId.fingerprint
        val delegate = ConsoleDelegate()
        delegate.circleHost.seed(circleId, invite, "group-key-v1", owner, ownerId)
        val server = ProtocolServer(
            ownerId, delegate,
            ServerConfig(
                alias = "join-proof-host", port = 0, tls = true,
                destDir = Files.createTempDirectory("join-proof-harness"),
            ),
        )
        server.start()
        val target = SendTarget("127.0.0.1", server.port, tls = true)
        try {
            val attackerClient = ProtocolClient(attackerId)
            val victimClient = ProtocolClient(victimId)

            // The invite is a bearer token, but an attacker who knows it and
            // presents their OWN certificate still has no proof of possession.
            val noProof = attackerClient.circleJoin(
                target, CircleRequest(CircleRequest.ACTION_JOIN, circleId, invite = invite, memberFp = attacker),
            )
            assertNotNull(noProof)
            assertFalse(noProof.ok)
            assertEquals("join proof required", noProof.reason)
            assertTrue(CircleResponse.verify(noProof, owner), "a signed refusal must still pin to the host")
            assertFalse(delegate.circleHost.snapshot().members.contains(attacker))

            // A validly signed proof naming a different host does not pass.
            val wrongHost = joinProof(attacker, circleId, invite, attackerId)
            val forged = attackerClient.circleJoin(
                target,
                CircleRequest(
                    CircleRequest.ACTION_JOIN, circleId, invite = invite,
                    memberFp = attacker, events = listOf(wrongHost),
                ),
            )
            assertNotNull(forged)
            assertFalse(forged.ok)
            assertEquals("invalid join proof", forged.reason)
            assertFalse(delegate.circleHost.snapshot().members.contains(attacker))

            // The rightful member's own signed proof succeeds.
            val joined = victimClient.circleJoin(
                target,
                CircleRequest(
                    CircleRequest.ACTION_JOIN, circleId, invite = invite, memberFp = victim,
                    events = listOf(joinProof(owner, circleId, invite, victimId)),
                ),
            )
            assertNotNull(joined)
            assertTrue(joined.ok, "honest join refused: ${joined.reason}")
            assertTrue(delegate.circleHost.snapshot().members.contains(victim))
            assertNull(delegate.circleHost.snapshot().roles[attacker])

            attackerClient.close()
            victimClient.close()
        } finally {
            server.close()
        }
    }

    @Test
    fun `circleAnswerSigned pins an answer to the host fingerprint`() {
        val hostId = TlsIdentity.generate()
        val otherId = TlsIdentity.generate()
        val answer = CircleResponse.sign(CircleResponse(true, key = "group-key"), hostId)
        assertTrue(circleAnswerSigned(answer, hostId.fingerprint))
        assertTrue(circleAnswerSigned(answer, "  ${hostId.fingerprint.lowercase()}  "), "pin is normalised")
        assertFalse(circleAnswerSigned(answer, otherId.fingerprint), "another host's answer is refused")
        assertFalse(circleAnswerSigned(answer, null), "without a pin the answer is not trusted")
        assertFalse(circleAnswerSigned(answer.copy(sig = ""), hostId.fingerprint))
        assertFalse(circleAnswerSigned(null, hostId.fingerprint))
        assertTrue(
            explainCircleFailure(answer.copy(sig = ""), null, "host", hostId.fingerprint)
                .contains("not signed by"),
        )
    }

    @Test
    fun `mergeCircleAnswer learns only host-signed rekeys and never a sync key`() {
        val circleId = "merge-rekey"
        val hostId = TlsIdentity.generate()
        val host = hostId.fingerprint
        fun rekeyBody(key: String, gen: Int) = buildJsonObject {
            put("keyB64", JsonPrimitive(key))
            put("gen", JsonPrimitive(gen))
            putJsonArray("members") { add(JsonPrimitive(host)) }
        }.toString()
        fun answer(events: List<CircleEvent>, key: String = "") = CircleResponse.sign(
            CircleResponse(true, key = key, members = listOf(host), events = events), hostId,
        )

        val st = CircleCliState(circleId = circleId, key = "group-key-v1", hostFp = host)
        val rotated = CircleCore.create(
            circleId, host, hostId, CircleEvent.REKEY, rekeyBody("group-key-v2", 1), lamport = 1L,
        )
        mergeCircleAnswer(st, answer(listOf(rotated)))
        assertEquals("group-key-v2", st.key, "the rotated key is learned from the host-signed rekey")
        assertEquals(1, st.rekeyGen)

        // A lower generation in the same signed log is history.
        val stale = CircleCore.create(
            circleId, host, hostId, CircleEvent.REKEY, rekeyBody("group-key-v0", 0), lamport = 2L,
        )
        mergeCircleAnswer(st, answer(listOf(stale)))
        assertEquals("group-key-v2", st.key)
        assertEquals(1, st.rekeyGen)

        // Even a signed SYNC answer carrying a key must not replace it.
        mergeCircleAnswer(st, answer(emptyList(), key = "rogue-key"))
        assertEquals("group-key-v2", st.key, "a sync answer never hands out a key")

        // Only an explicit JOIN-fold adopts the key field.
        val joining = CircleCliState(circleId = circleId, key = "", hostFp = host)
        mergeCircleAnswer(joining, answer(emptyList(), key = "join-key"), fromJoin = true)
        assertEquals("join-key", joining.key)
    }

    @Test
    fun `circleFreshEvents drops an event whose certificate does not match its author`() {
        val circleId = "fresh-gate"
        val victim = TlsIdentity.generate()
        val attacker = TlsIdentity.generate()
        val forged = CircleCore.create(
            circleId, victim.fingerprint, attacker, CircleEvent.MESSAGE,
            """{"text":"I am the victim"}""", lamport = 1L,
        )
        val honest = CircleCore.create(
            circleId, attacker.fingerprint, attacker, CircleEvent.MESSAGE,
            """{"text":"I am myself"}""", lamport = 2L,
        )
        val folded = circleFreshEvents(CircleCliState(circleId = circleId), listOf(forged, honest))
        assertEquals(listOf(honest.id), folded.map { it.id })
        assertFalse(CircleCore.verify(forged))
        assertTrue(CircleCore.verify(honest))
    }

    @Test
    fun `a CLI peer ignores a departed officer hand-off above the host rotation`() {
        val circleId = "cli-departed-officer"
        val hostId = TlsIdentity.generate()
        val adminId = TlsIdentity.generate()
        val memberId = TlsIdentity.generate()
        val host = hostId.fingerprint
        val admin = adminId.fingerprint
        val member = memberId.fingerprint

        fun rekeyBody(key: String, gen: Int) = buildJsonObject {
            put("keyB64", JsonPrimitive(key))
            put("gen", JsonPrimitive(gen))
        }.toString()

        // The phone host keeps the officer's vetoed REKEY in its signed answer
        // as inert history, while its forced rotation carries a LOWER gen.
        // Folding the greatest generation blindly installs the attacker key.
        val appoint = CircleCore.create(
            circleId, host, hostId, CircleEvent.ROLE,
            """{"targetFp":"$admin","role":"ADMIN"}""", lamport = 1L,
        )
        val leave = CircleCore.create(circleId, admin, adminId, CircleEvent.LEAVE, "{}", lamport = 2L)
        val handoff = CircleCore.create(
            circleId, admin, adminId, CircleEvent.REKEY, rekeyBody("attacker-key", 99), lamport = 3L,
        )
        val hostRekey = CircleCore.create(
            circleId, host, hostId, CircleEvent.REKEY, rekeyBody("host-fresh-key", 2), lamport = 4L,
        )
        val answer = CircleResponse.sign(
            CircleResponse(
                ok = true,
                key = "",
                members = listOf(host, member),
                events = listOf(appoint, leave, handoff, hostRekey),
            ),
            hostId,
        )
        assertTrue(circleAnswerSigned(answer, host))
        val st = CircleCliState(
            circleId = circleId,
            key = "group-key-v1",
            members = mutableListOf(host, admin, member),
            // The appointment predates this answer, so the officer really is
            // an ADMIN on the CLI's pre-batch snapshot before it leaves.
            events = mutableListOf(appoint),
            hostFp = host,
        )
        mergeCircleAnswer(st, answer)
        assertEquals("host-fresh-key", st.key, "the departed officer's high-gen key won")
        assertEquals(2, st.rekeyGen, "the vetoed generation must not advance the CLI")
        assertTrue(st.events.any { it.id == handoff.id }, "the vetoed rekey stays inert history")
    }

    @Test
    fun `circleFreshEvents drops a host-answer JOIN and member REKEY but folds an ordinary event`() {
        val circleId = "fresh-authority"
        val hostId = TlsIdentity.generate()
        val memberId = TlsIdentity.generate()
        val host = hostId.fingerprint
        val member = memberId.fingerprint
        val st = CircleCliState(circleId = circleId, members = mutableListOf(host, member), hostFp = host)
        val join = joinProof(host, circleId, "invite", memberId)
        val memberRekey = CircleCore.create(
            circleId, member, memberId, CircleEvent.REKEY,
            """{"keyB64":"member-key","gen":1}""", lamport = 1L,
        )
        val message = CircleCore.create(
            circleId, member, memberId, CircleEvent.MESSAGE,
            """{"text":"hello"}""", lamport = 2L,
        )
        val answer = CircleResponse.sign(
            CircleResponse(
                ok = true, key = "", members = listOf(host, member),
                events = listOf(join, memberRekey, message),
            ),
            hostId,
        )
        assertTrue(circleAnswerSigned(answer, host), "host-signed answer expected")
        assertEquals(listOf(message.id), circleFreshEvents(st, answer.events).map { it.id })
    }

    @Test
    fun `a stale pin from another circle does not vouch for a join to a new circle`() {
        val st = CircleCliState(circleId = "circle-a", hostFp = "HOST-A", key = "old-key")
        val different = ParsedArgs(arrayOf("circle", "join", "--circle", "circle-b", "--host-fp", "host-b"))
        assertEquals("HOST-B", circleExpectedHostFp(different, st))
        val sameCircle = ParsedArgs(arrayOf("circle", "join", "--circle", "circle-a", "--host-fp", "host-b"))
        assertNull(circleExpectedHostFp(sameCircle, st), "a conflicting pin for the same circle is refused")
        val noPin = ParsedArgs(arrayOf("circle", "join", "--circle", "circle-b"))
        assertNull(circleExpectedHostFp(noPin, st), "a different circle needs a fresh pin")
    }

    @Test
    fun `circle join clears a stale pin before it rewrites the circle id`() {
        assumeTrue(
            System.getenv("FLIT_HARNESS_HOME").isNullOrBlank(),
            "the CLI home is pinned by FLIT_HARNESS_HOME and cannot be isolated",
        )
        val root = Files.createTempDirectory("circle-pin-join")
        val previousHome = System.getProperty("user.home")
        System.setProperty("user.home", root.toString())
        val hostId = TlsIdentity.generate()
        val circleId = "circle-b"
        val invite = "invite-b"
        val key = "host-b-key"
        val delegate = ConsoleDelegate()
        delegate.circleHost.seed(circleId, invite, key, hostId.fingerprint, hostId)
        val server = ProtocolServer(
            hostId, delegate,
            ServerConfig(
                alias = "pin-host", port = 0, tls = true,
                destDir = Files.createTempDirectory("pin-host"),
            ),
        )
        server.start()
        try {
            saveCircleState(CircleCliState(circleId = "circle-a", hostFp = "STALE-HOST-A", key = "old-key"))
            main(
                arrayOf(
                    "circle", "join",
                    "--host", "127.0.0.1:${server.port}",
                    "--circle", circleId, "--invite", invite,
                    "--host-fp", hostId.fingerprint,
                ),
            )
            val lines = Files.readAllLines(harnessHome().resolve("circle.state"))
            assertTrue(lines.size >= 8, "join did not persist a pinned state")
            assertEquals(circleId, lines[0], "join must move to the requested circle")
            assertEquals(key, lines[2], "the new host's key must be adopted")
            assertEquals(hostId.fingerprint, lines[7], "the stale pin must not survive")
        } finally {
            server.close()
            if (previousHome == null) System.clearProperty("user.home")
            else System.setProperty("user.home", previousHome)
        }
    }
}
