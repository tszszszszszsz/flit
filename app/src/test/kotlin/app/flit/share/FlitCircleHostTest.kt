package app.flit.share

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.model.CircleRequest
import app.flit.share.protocol.model.CircleResponse
import app.flit.share.protocol.tls.TlsIdentity
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * JVM-only: the host is a pure decision layer over CircleCore + FlitCircleStore.
 * Every event here is really signed by its author's identity, so the cert
 * pinning path is exercised rather than mocked.
 */
class FlitCircleHostTest {

    private val ownerId: TlsIdentity by lazy { TlsIdentity.generate() }
    private val memberId: TlsIdentity by lazy { TlsIdentity.generate() }
    private val adminId: TlsIdentity by lazy { TlsIdentity.generate() }
    private val newcomerId: TlsIdentity by lazy { TlsIdentity.generate() }
    private val attackerId: TlsIdentity by lazy { TlsIdentity.generate() }

    private val ownerFp get() = ownerId.fingerprint
    private val memberFp get() = memberId.fingerprint
    private val adminFp get() = adminId.fingerprint
    private val newcomerFp get() = newcomerId.fingerprint
    private val attackerFp get() = attackerId.fingerprint

    private val ownerMember get() = FlitCircleStore.Member(ownerFp, "Owner", FlitCircleStore.Role.OWNER, 1L)
    private val memberMember get() = FlitCircleStore.Member(memberFp, "Member", FlitCircleStore.Role.MEMBER, 2L)
    private val adminMember get() = FlitCircleStore.Member(adminFp, "Admin", FlitCircleStore.Role.ADMIN, 3L)
    private val newcomerMember get() = FlitCircleStore.Member(newcomerFp, "Newcomer", FlitCircleStore.Role.MEMBER, 4L)

    private fun host(invite: String = "invite-1", key: String = "key-v1") =
        FlitCircleHost(ownerId, invite, key)

    private fun state(
        members: List<FlitCircleStore.Member> = listOf(ownerMember),
        events: List<CircleEvent> = emptyList(),
        key: String = "key-v1",
        gen: Int = 0,
    ) = FlitCircleStore.CircleState(
        id = "circle-1", keyB64 = key, role = FlitCircleStore.Role.OWNER,
        members = members, events = events, rekeyGen = gen,
    )

    private fun signed(
        identity: TlsIdentity,
        type: String,
        body: String,
        lamport: Long = 1L,
    ) = CircleCore.create("circle-1", identity.fingerprint, identity, type, body, lamport = lamport)

    private fun join(fp: String = newcomerFp, invite: String? = "invite-1") = CircleRequest(
        action = CircleRequest.ACTION_JOIN, circleId = "circle-1", invite = invite, memberFp = fp,
    )

    private fun sync(fp: String, events: List<CircleEvent> = emptyList()) = CircleRequest(
        action = CircleRequest.ACTION_SYNC, circleId = "circle-1", memberFp = fp, events = events,
    )

    private fun roleOf(st: FlitCircleStore.CircleState, fp: String) =
        st.members.first { it.fp == fp }.role

    @Test
    fun `wrong circle and unknown action get honest refusals`() {
        val h = host()
        val st = state()
        val wrong = h.onRequest(sync(memberFp).copy(circleId = "other"), st)
        assertFalse(wrong.ok)
        assertEquals("wrong circle", wrong.reason)
        val unknown = h.onRequest(CircleRequest(action = "nope", circleId = "circle-1"), st)
        assertEquals("unknown action", unknown.reason)
        assertEquals(listOf(ownerFp), st.members.map { it.fp }) // input untouched
    }

    @Test
    fun `join requires the invite and a usable fingerprint`() {
        val h = host(invite = "SECRET")
        val st = state()
        assertEquals("bad invite", h.onRequest(join(invite = "guess"), st).reason)
        assertEquals("bad invite", h.onRequest(join(invite = null), st).reason)
        assertEquals("bad invite", host(invite = "").onRequest(join(invite = ""), st).reason)
        assertEquals("bad member fingerprint", h.onRequest(join(fp = "   ", invite = "SECRET"), st).reason)
        assertEquals(
            "bad member fingerprint",
            h.onRequest(join(fp = "X".repeat(129), invite = "SECRET"), st).reason,
        )
    }

    @Test
    fun `join into a signed non empty log returns that log and accept keeps it`() {
        val h = host()
        val seed = signed(memberId, CircleEvent.MESSAGE, """{"text":"before join"}""")
        val st = state(members = listOf(ownerMember, memberMember), events = listOf(seed))
        val req = join(fp = "  ${newcomerFp.lowercase()}  ")

        val result = h.onRequest(req, st)
        assertTrue(result.ok)
        assertEquals("", result.reason)
        assertEquals("key-v1", result.key)
        assertEquals(listOf(ownerFp, memberFp, newcomerFp), result.members)
        assertEquals(listOf(seed.id), result.events.map { it.id }) // non-empty log is handed out
        assertEquals(result, h.onRequest(req, st)) // pure: same input, same answer
        assertTrue(h.isEvidence(result, st))

        val next = h.accept(result, st)
        assertEquals(listOf(ownerFp, memberFp, newcomerFp), next.members.map { it.fp })
        assertEquals(FlitCircleStore.Role.MEMBER, roleOf(next, newcomerFp))
        assertEquals(listOf(seed.id), next.events.map { it.id })
        assertEquals(0, next.rekeyGen)
        assertEquals("key-v1", next.keyB64)
        assertEquals(listOf(ownerFp, memberFp), st.members.map { it.fp }) // input untouched
    }

    @Test
    fun `sync refuses outsiders and blank fingerprints`() {
        val h = host()
        val st = state(members = listOf(ownerMember, memberMember))
        val outsider = h.onRequest(sync(attackerFp), st)
        assertFalse(outsider.ok)
        assertEquals("not-a-member", outsider.reason)
        assertEquals("not-a-member", h.onRequest(sync("  "), st).reason)
    }

    @Test
    fun `sync verifies signatures before folding valid events`() {
        val h = host()
        val st = state(members = listOf(ownerMember, memberMember))
        val good = signed(memberId, CircleEvent.MESSAGE, """{"text":"hi"}""")
        val unsigned = good.copy(id = "unsigned", sig = "", cert = "")
        val badSig = good.copy(id = "bad-sig", sig = "not-a-signature")

        val result = h.onRequest(sync(memberFp, listOf(good, unsigned, badSig)), st)
        assertTrue(result.ok)
        assertEquals(listOf(good.id), result.events.map { it.id })
        val next = h.accept(result, st)
        assertEquals(listOf(good.id), next.events.map { it.id })
        assertTrue(st.events.isEmpty()) // input untouched
    }

    @Test
    fun `a signed certificate cannot claim another member fingerprint`() {
        val h = host()
        val st = state(members = listOf(ownerMember, memberMember))
        val base = CircleEvent(
            id = "impostor", circleId = "circle-1", authorFp = memberFp, lamport = 1L,
            type = CircleEvent.MESSAGE, body = """{"text":"impostor"}""", sig = "",
            createdMs = System.currentTimeMillis(),
            cert = Base64.getEncoder().encodeToString(attackerId.certificate.encoded),
        )
        val forged = base.copy(sig = attackerId.sign(CircleCore.canonical(base)))
        assertFalse(CircleCore.verify(forged)) // pin lands under attacker fp, not memberFp

        val result = h.onRequest(sync(memberFp, listOf(forged)), st)
        assertTrue(result.ok)
        assertTrue(result.events.isEmpty())
        assertEquals(st, h.accept(result, st))
    }

    @Test
    fun `wrong circle unknown type oversized and replayed events are skipped`() {
        val h = host()
        val good = signed(memberId, CircleEvent.MESSAGE, """{"text":"hi"}""")
        val st = state(members = listOf(ownerMember, memberMember), events = listOf(good))
        val alien = good.copy(id = "alien", circleId = "another-circle")
        val unknown = good.copy(id = "unknown", type = "explode")
        val oversized = good.copy(id = "oversized", body = "x".repeat(FlitCircleHost.MAX_BODY_CHARS + 1))
        val replay = good.copy(id = good.id, lamport = 9L)

        val result = h.onRequest(sync(memberFp, listOf(alien, unknown, oversized, replay)), st)
        assertTrue(result.ok)
        assertEquals(listOf(good.id), result.events.map { it.id })
        assertEquals(1, result.events.size)
    }

    @Test
    fun `leave removes the member bumps the generation and host rekeys`() {
        val h = host()
        val st = state(members = listOf(ownerMember, memberMember))
        val leave = signed(memberId, CircleEvent.LEAVE, """{"memberFp":"$memberFp"}""")

        val result = h.onRequest(sync(memberFp, listOf(leave)), st)
        assertTrue(result.ok)
        assertEquals(listOf(ownerFp), result.members)
        assertNotEquals("key-v1", result.key)
        val rekey = result.events.single { it.type == CircleEvent.REKEY }
        assertEquals(ownerFp, rekey.authorFp)
        assertTrue(CircleCore.verify(rekey)) // host-signed, cert pinned
        assertTrue(rekey.lamport > leave.lamport) // sorts after the departure

        val next = h.accept(result, st)
        assertEquals(listOf(ownerFp), next.members.map { it.fp })
        assertEquals(1, next.rekeyGen)
        assertEquals(result.key, next.keyB64)
        assertEquals(listOf(ownerFp, memberFp), st.members.map { it.fp }) // input untouched

        val later = h.onRequest(sync(memberFp), next)
        assertFalse(later.ok)
        assertEquals("not-a-member", later.reason)
    }

    @Test
    fun `admin kick rekeys but the owner cannot be kicked`() {
        val h = host()
        val st = state(members = listOf(ownerMember, adminMember, memberMember))
        val kick = signed(
            adminId, CircleEvent.KICK,
            """{"kickId":"k1","targetFp":"$memberFp","mode":"admin"}""",
        )
        val result = h.onRequest(sync(adminFp, listOf(kick)), st)
        assertEquals(listOf(ownerFp, adminFp), result.members)
        assertNotEquals("key-v1", result.key)
        assertEquals(1, result.events.count { it.type == CircleEvent.REKEY })
        assertEquals(1, h.accept(result, st).rekeyGen)

        val ownerKick = signed(
            adminId, CircleEvent.KICK,
            """{"kickId":"k2","targetFp":"$ownerFp","mode":"admin"}""",
            lamport = 2L,
        )
        val intact = h.onRequest(sync(adminFp, listOf(ownerKick)), st)
        assertEquals(3, intact.members.size)
        assertEquals("key-v1", intact.key)
        assertEquals(0, intact.events.count { it.type == CircleEvent.REKEY })
    }

    @Test
    fun `member supplied rekey is applied without a second host rekey`() {
        val h = host()
        val st = state(members = listOf(ownerMember, memberMember))
        val leave = signed(memberId, CircleEvent.LEAVE, """{"memberFp":"$memberFp"}""")
        val rekey = signed(memberId, CircleEvent.REKEY, """{"keyB64":"peer-key","gen":1}""", lamport = 2L)

        val result = h.onRequest(sync(memberFp, listOf(leave, rekey)), st)
        assertEquals("peer-key", result.key)
        assertEquals(1, result.events.count { it.type == CircleEvent.REKEY })
        assertEquals(rekey.id, result.events.first { it.type == CircleEvent.REKEY }.id)
        val next = h.accept(result, st)
        assertEquals("peer-key", next.keyB64)
        assertEquals(1, next.rekeyGen)
    }

    @Test
    fun `owner signed role event promotes a member through request and accept`() {
        val h = host()
        val st = state(members = listOf(ownerMember, memberMember))
        val promote = signed(ownerId, CircleEvent.ROLE, """{"targetFp":"$memberFp","role":"ADMIN"}""")

        val result = h.onRequest(sync(ownerFp, listOf(promote)), st)
        assertTrue(result.ok)
        assertTrue(h.isEvidence(result, st))
        assertEquals(listOf(promote.id), result.events.map { it.id })
        val next = h.accept(result, st)
        assertEquals(FlitCircleStore.Role.ADMIN, roleOf(next, memberFp))
        assertEquals(FlitCircleStore.Role.OWNER, roleOf(next, ownerFp))
        assertEquals(FlitCircleStore.Role.MEMBER, roleOf(st, memberFp)) // input untouched
    }

    @Test
    fun `member signed role event is history only and never changes the owner`() {
        val h = host()
        val st = state(members = listOf(ownerMember, memberMember))
        val claim = signed(memberId, CircleEvent.ROLE, """{"targetFp":"$ownerFp","role":"ADMIN"}""")

        val result = h.onRequest(sync(memberFp, listOf(claim)), st)
        assertTrue(result.ok)
        assertEquals(listOf(claim.id), result.events.map { it.id }) // recorded as history
        val next = h.accept(result, st)
        assertEquals(FlitCircleStore.Role.OWNER, roleOf(next, ownerFp))
        assertEquals(FlitCircleStore.Role.MEMBER, roleOf(next, memberFp))
        assertEquals(0, next.rekeyGen)
        assertEquals("key-v1", next.keyB64)
    }

    @Test
    fun `promoted admin kicks and the owner can demote that admin`() {
        val h = host()
        val st = state(members = listOf(ownerMember, memberMember, newcomerMember))

        val promote = signed(ownerId, CircleEvent.ROLE, """{"targetFp":"$memberFp","role":"ADMIN"}""")
        val promoted = h.accept(h.onRequest(sync(ownerFp, listOf(promote)), st), st)
        assertEquals(FlitCircleStore.Role.ADMIN, roleOf(promoted, memberFp))

        val kick = signed(
            memberId, CircleEvent.KICK,
            """{"kickId":"k9","targetFp":"$newcomerFp","mode":"admin"}""",
            lamport = 2L,
        )
        val answer = h.onRequest(sync(memberFp, listOf(kick)), promoted)
        assertEquals(listOf(ownerFp, memberFp), answer.members)
        assertNotEquals(promoted.keyB64, answer.key)
        val kicked = h.accept(answer, promoted)
        assertEquals(1, kicked.rekeyGen)
        assertEquals("not-a-member", h.onRequest(sync(newcomerFp), kicked).reason)

        val demote = signed(ownerId, CircleEvent.ROLE, """{"targetFp":"$memberFp","role":"MEMBER"}""", lamport = 3L)
        val removed = h.accept(h.onRequest(sync(ownerFp, listOf(demote)), kicked), kicked)
        assertEquals(FlitCircleStore.Role.MEMBER, roleOf(removed, memberFp))
        assertEquals(1, removed.rekeyGen) // demotion alone never rekeys
    }

    @Test
    fun `departed fingerprint cannot later sync or contribute another event`() {
        val h = host()
        val sneak = signed(memberId, CircleEvent.MESSAGE, """{"text":"sneak"}""", lamport = 2L)

        val st = state(members = listOf(ownerMember, memberMember))
        val leave = signed(memberId, CircleEvent.LEAVE, """{"memberFp":"$memberFp"}""")
        val left = h.accept(h.onRequest(sync(memberFp, listOf(leave)), st), st)
        val afterLeave = h.onRequest(sync(memberFp, listOf(sneak)), left)
        assertFalse(afterLeave.ok)
        assertEquals("not-a-member", afterLeave.reason)
        assertTrue(afterLeave.events.isEmpty())
        assertEquals(left, h.accept(afterLeave, left)) // no event ingested

        val kickedState = state(members = listOf(ownerMember, adminMember, memberMember))
        val kick = signed(ownerId, CircleEvent.KICK, """{"targetFp":"$memberFp","mode":"admin"}""")
        val kicked = h.accept(h.onRequest(sync(ownerFp, listOf(kick)), kickedState), kickedState)
        assertFalse(kicked.members.any { it.fp == memberFp })
        val afterKick = h.onRequest(sync(memberFp, listOf(sneak)), kicked)
        assertEquals("not-a-member", afterKick.reason)
        assertEquals(kicked, h.accept(afterKick, kicked))
    }

    @Test
    fun `a signed replay refused after it rolls out of the capped log`() {
        val h = host()
        val st = state(members = listOf(ownerMember, memberMember))
        val old = signed(memberId, CircleEvent.MESSAGE, """{"text":"old"}""")

        val first = h.onRequest(sync(memberFp, listOf(old)), st)
        assertTrue(first.ok)
        assertEquals(listOf(old.id), first.events.map { it.id })
        val withOld = h.accept(first, st)

        // Push the id out of the 500-event log without going through the host,
        // so only CircleCore's bounded accept-memory can still recognise it.
        val filler = (1..FlitCircleStore.EVENT_CAP).map { n ->
            CircleEvent(
                id = "filler-$n", circleId = "circle-1", authorFp = memberFp,
                lamport = n.toLong(), type = CircleEvent.MESSAGE,
                body = """{"text":"filler"}""", sig = "", createdMs = old.createdMs,
            )
        }
        val rolled = withOld.copy(events = (withOld.events + filler).takeLast(FlitCircleStore.EVENT_CAP))
        assertFalse(rolled.events.any { it.id == old.id })

        val replay = h.onRequest(sync(memberFp, listOf(old)), rolled)
        assertTrue(replay.ok)
        assertFalse(replay.events.any { it.id == old.id }) // replay guard, not just log dedup
        assertEquals(rolled, h.accept(replay, rolled)) // nothing ingested
    }

    @Test
    fun `a correctly signed event outside the replay window is refused`() {
        val h = host()
        val st = state(members = listOf(ownerMember, memberMember))
        val stale = CircleCore.create(
            "circle-1", memberFp, memberId, CircleEvent.MESSAGE, """{"text":"stale"}""",
            lamport = 1L,
            createdMs = System.currentTimeMillis() - CircleCore.REPLAY_WINDOW_MS - 60_000L,
        )
        assertTrue(CircleCore.verify(stale)) // signature is fine; the clock is not

        val result = h.onRequest(sync(memberFp, listOf(stale)), st)
        assertTrue(result.ok)
        assertTrue(result.events.isEmpty())
        assertEquals(st, h.accept(result, st))
    }

    @Test
    fun `noop sync carries no evidence while refusals and changes do`() {
        val h = host()
        val seed = signed(memberId, CircleEvent.MESSAGE, """{"text":"hi"}""")
        val st = state(members = listOf(ownerMember, memberMember), events = listOf(seed))

        val noop = h.onRequest(sync(memberFp), st)
        assertTrue(noop.ok)
        assertEquals(listOf(seed.id), noop.events.map { it.id })
        assertFalse(h.isEvidence(noop, st))
        assertEquals(st, h.accept(noop, st))

        assertTrue(h.isEvidence(h.onRequest(sync(attackerFp), st), st)) // refusal is a real signal
        val change = signed(memberId, CircleEvent.MESSAGE, """{"text":"new"}""", lamport = 2L)
        assertTrue(h.isEvidence(h.onRequest(sync(memberFp, listOf(change)), st), st))
    }

    @Test
    fun `accept is a no-op for refusals and repeatable for answers`() {
        val h = host()
        val st = state(members = listOf(ownerMember, memberMember))
        assertEquals(st, h.accept(CircleResponse.refused("bad invite"), st))
        assertTrue(h.isEvidence(CircleResponse.refused("bad invite"), st)) // refusals are evidence

        val message = signed(memberId, CircleEvent.MESSAGE, """{"text":"hi"}""")
        val ok = CircleResponse(
            ok = true, key = "key-v1", members = listOf(ownerFp, memberFp), events = listOf(message),
        )
        assertEquals(h.accept(ok, st), h.accept(ok, st))
        assertTrue(st.events.isEmpty())
    }

    @Test
    fun `event cap and roster cap keep the host bounded`() {
        val h = host()
        val st = state(members = listOf(ownerMember, memberMember))
        val many = (1..FlitCircleStore.EVENT_CAP + 5).map {
            signed(memberId, CircleEvent.MESSAGE, """{"text":"m$it"}""", lamport = it.toLong())
        }
        val result = h.onRequest(sync(memberFp, many), st)
        assertTrue(result.ok)
        assertEquals(FlitCircleStore.EVENT_CAP, result.events.size)
        assertEquals(FlitCircleStore.EVENT_CAP, h.accept(result, st).events.size)

        val full = state(
            members = listOf(ownerMember) + (1 until FlitCircleHost.MAX_MEMBERS).map {
                FlitCircleStore.Member("FP-$it", "", FlitCircleStore.Role.MEMBER)
            },
        )
        assertEquals("circle full", h.onRequest(join(fp = "NEW-FP"), full).reason)
    }
}
