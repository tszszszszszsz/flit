package app.flit.share

import app.flit.share.protocol.circle.CircleEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Circle store is exercised on the JVM only: the codec and every reducer
 * are pure, and the SharedPreferences half is a thin adapter nobody calls here.
 */
class FlitCircleStoreTest {

    private val owner = FlitCircleStore.Member("fp-owner", "Owner", FlitCircleStore.Role.OWNER, 1)
    private val admin = FlitCircleStore.Member("fp-admin", "Admin", FlitCircleStore.Role.ADMIN, 2)
    private val member = FlitCircleStore.Member("fp-member", "Member", FlitCircleStore.Role.MEMBER, 3)
    private val roster = listOf(owner, admin, member)

    private fun event(
        id: String,
        author: String,
        type: String,
        body: String = "{}",
        lamport: Long = 1,
        created: Long = 1_000L,
    ) = CircleEvent(
        id = id,
        circleId = "circle-1",
        authorFp = author,
        lamport = lamport,
        type = type,
        body = body,
        sig = "sig-$id",
        createdMs = created,
    )

    private fun state(
        members: List<FlitCircleStore.Member> = roster,
        events: List<CircleEvent> = emptyList(),
        rekeyGen: Int = 0,
        key: String = "key-v1",
    ) = FlitCircleStore.CircleState(
        id = "circle-1",
        keyB64 = key,
        role = FlitCircleStore.Role.OWNER,
        members = members,
        events = events,
        rekeyGen = rekeyGen,
    )

    @Test
    fun `codec roundtrip keeps every field`() {
        val source = state(
            events = listOf(event("e-1", "fp-owner", "message", """{"text":"hello"}""")),
            rekeyGen = 3,
        )
        val raw = encode(source)
        assertEquals(source, decodeCircle(raw))
        assertEquals(source, FlitCircleStore.decode(FlitCircleStore.encode(source)))
        assertEquals(source, decodeCircle(raw))
    }

    @Test
    fun `old rows pick up defaults and corrupt rows decode to null`() {
        val old = decodeCircle(
            """{"id":"circle-1","keyB64":"k","role":"OWNER","members":""" +
                """[{"fp":"fp-owner","alias":"Owner","role":"OWNER"}],"unknown":"x"}""",
        )
        assertEquals(FlitCircleStore.Role.OWNER, old?.role)
        assertEquals(0, old?.rekeyGen)
        assertEquals(emptyList(), old?.events)
        assertEquals(0L, old?.members?.first()?.joinedMs)
        assertNull(decodeCircle(null))
        assertNull(decodeCircle("   "))
        assertNull(decodeCircle("{not json"))
        assertNull(decodeCircle("[]"))
    }

    @Test
    fun `decode normalizes duplicates, trims oversized logs and floors negative generation`() {
        val dirty = state(
            members = listOf(owner, owner.copy(alias = "dup"), member, admin.copy(role = FlitCircleStore.Role.OWNER)),
            events = listOf(
                event("e-1", "fp-owner", "message"),
                event("e-1", "fp-owner", "message"),
                event("", "fp-owner", "message"),
            ),
            rekeyGen = -4,
        )
        val clean = decodeCircle(FlitCircleStore.encode(dirty))!!
        assertEquals(listOf("fp-owner", "fp-member", "fp-admin"), clean.members.map { it.fp })
        assertEquals(1, clean.members.count { it.role == FlitCircleStore.Role.OWNER })
        assertEquals(FlitCircleStore.Role.MEMBER, clean.members.first { it.fp == "fp-admin" }.role)
        assertEquals(listOf("e-1"), clean.events.map { it.id })
        assertEquals(0, clean.rekeyGen)
    }

    @Test
    fun `defaults on construction are empty events and generation zero`() {
        val s = FlitCircleStore.CircleState(
            id = "c", keyB64 = "k", role = FlitCircleStore.Role.MEMBER, members = emptyList(),
        )
        assertEquals(emptyList(), s.events)
        assertEquals(0, s.rekeyGen)
        assertEquals(FlitCircleStore.Role.MEMBER, FlitCircleStore.Member("a", "A").role)
        assertEquals(0L, FlitCircleStore.Member("a", "A").joinedMs)
    }

    @Test
    fun `blank id or blank author events are ignored`() {
        val s = state()
        assertEquals(s, FlitCircleStore.reduce(s, event("", "fp-owner", "message")))
        assertEquals(s, FlitCircleStore.reduce(s, event("e-x", "", "message")))
        assertEquals(s, FlitCircleStore.reduce(s, event("e-x", "   ", "join")))
    }

    @Test
    fun `join adds a member once and a repeat join only refreshes the alias`() {
        val join = event("j-1", "fp-new", "join", """{"alias":"New phone","future":true}""")
        val joined = FlitCircleStore.reduce(state(), join)
        assertEquals(4, joined.members.size)
        val added = joined.members.first { it.fp == "fp-new" }
        assertEquals("New phone", added.alias)
        assertEquals(FlitCircleStore.Role.MEMBER, added.role)
        assertEquals(1_000L, added.joinedMs)
        assertEquals(0, joined.rekeyGen) // joining does not rekey; departures do

        val again = FlitCircleStore.reduce(
            joined,
            event("j-2", "fp-new", "join", """{"alias":"Renamed"}""", created = 2_000L),
        )
        assertEquals(4, again.members.size)
        assertEquals("Renamed", again.members.first { it.fp == "fp-new" }.alias)
        assertEquals(FlitCircleStore.Role.MEMBER, again.members.first { it.fp == "fp-new" }.role)
        assertEquals(2, again.events.size)
    }

    @Test
    fun `join aliases are bounded`() {
        val long = "X".repeat(FlitCircleStore.MAX_ALIAS_CHARS + 32)
        val joined = FlitCircleStore.reduce(
            state(), event("j-long", "fp-new", "join", """{"alias":"$long"}"""),
        )
        assertEquals(FlitCircleStore.MAX_ALIAS_CHARS, joined.members.first { it.fp == "fp-new" }.alias.length)
    }

    @Test
    fun `leave removes the author from the roster and bumps the generation`() {
        val left = FlitCircleStore.reduce(state(), event("l-1", "fp-member", "leave"))
        assertEquals(listOf("fp-owner", "fp-admin"), left.members.map { it.fp })
        assertEquals(1, left.rekeyGen)
        assertEquals(listOf("l-1"), left.events.map { it.id })

        val repeat = FlitCircleStore.reduce(left, event("l-1", "fp-member", "leave"))
        assertEquals(left, repeat) // dedup: no second removal or bump

        val again = FlitCircleStore.reduce(left, event("l-2", "fp-member", "leave"))
        assertEquals(1, again.rekeyGen)
        assertEquals(2, again.members.size)
    }

    @Test
    fun `generation saturates instead of wrapping`() {
        val left = FlitCircleStore.reduce(
            state(rekeyGen = Int.MAX_VALUE), event("l-max", "fp-member", "leave"),
        )
        assertEquals(Int.MAX_VALUE, left.rekeyGen)
        assertEquals(2, left.members.size)
    }

    @Test
    fun `admin kick removes a member but never the owner`() {
        val kicked = FlitCircleStore.reduce(
            state(),
            event("k-1", "fp-admin", "kick", """{"targetFp":"fp-member"}"""),
        )
        assertEquals(listOf("fp-owner", "fp-admin"), kicked.members.map { it.fp })
        assertEquals(1, kicked.rekeyGen)

        val ownerSafe = FlitCircleStore.reduce(
            state(),
            event("k-2", "fp-admin", "kick", """{"targetFp":"fp-owner"}"""),
        )
        assertEquals(3, ownerSafe.members.size)
        assertEquals(0, ownerSafe.rekeyGen)
        assertTrue(ownerSafe.members.any { it.fp == "fp-owner" })

        val ownerKicks = FlitCircleStore.reduce(
            state(),
            event("k-3", "fp-owner", "kick", """{"targetFp":"fp-admin"}"""),
        )
        assertEquals(listOf("fp-owner", "fp-member"), ownerKicks.members.map { it.fp })
        assertEquals(1, ownerKicks.rekeyGen)
    }

    @Test
    fun `self kick and explicitly denied kick are inert`() {
        val self = FlitCircleStore.reduce(
            state(), event("k-self", "fp-admin", "kick", """{"targetFp":"fp-admin"}"""),
        )
        assertEquals(3, self.members.size)
        assertEquals(0, self.rekeyGen)

        val denied = FlitCircleStore.reduce(
            state(), event("k-no", "fp-admin", "kick", """{"targetFp":"fp-member","approve":false}"""),
        )
        assertEquals(3, denied.members.size)
        assertEquals(0, denied.rekeyGen)
    }

    @Test
    fun `a plain member kick is only a vote-kick, not an immediate removal`() {
        val opened = FlitCircleStore.reduce(
            state(),
            event("k-1", "fp-member", "kick", """{"targetFp":"fp-admin","mode":"vote"}"""),
        )
        assertEquals(3, opened.members.size)
        assertEquals(0, opened.rekeyGen)
        assertEquals(listOf("k-1"), opened.events.map { it.id })
    }

    @Test
    fun `vote kick needs a majority of current members and the latest vote wins`() {
        val open = event(
            "k-1", "fp-member", "kick",
            """{"targetFp":"fp-admin","kickId":"k-1","mode":"vote"}""",
        )
        val s1 = FlitCircleStore.reduce(state(), open)
        assertEquals(3, s1.members.size)

        val s2 = FlitCircleStore.reduce(
            s1, event("v-1", "fp-owner", "vote", """{"kickId":"k-1","approve":true}""", lamport = 2),
        )
        assertEquals(3, s2.members.size) // one yes is not a majority of three

        // Admin (the target) says no, then owner changes yes → no: still no kick.
        val s3 = FlitCircleStore.reduce(
            s2, event("v-2", "fp-admin", "vote", """{"kickId":"k-1","approve":false}""", lamport = 2),
        )
        val s4 = FlitCircleStore.reduce(
            s3, event("v-3", "fp-owner", "vote", """{"kickId":"k-1","approve":false}""", lamport = 3),
        )
        assertEquals(3, s4.members.size)
        assertEquals(0, s4.rekeyGen)

        // Admin changes no → yes: one latest yes still falls short.
        val s5 = FlitCircleStore.reduce(
            s4, event("v-4", "fp-admin", "vote", """{"kickId":"k-1","approve":true}""", lamport = 3),
        )
        assertEquals(3, s5.members.size)

        // Owner changes no → yes: two latest yes votes are a majority.
        val s6 = FlitCircleStore.reduce(
            s5, event("v-5", "fp-owner", "vote", """{"kickId":"k-1","approve":true}""", lamport = 4),
        )
        assertEquals(listOf("fp-owner", "fp-member"), s6.members.map { it.fp })
        assertEquals(1, s6.rekeyGen)

        // Late change of mind from the departed admin cannot resurrect them.
        val s7 = FlitCircleStore.reduce(
            s6, event("v-6", "fp-admin", "vote", """{"kickId":"k-1","approve":false}""", lamport = 5),
        )
        assertEquals(2, s7.members.size)
        assertEquals(1, s7.rekeyGen)
    }

    @Test
    fun `votes from non members are not counted`() {
        val kick = event(
            "k-1", "fp-member", "kick", """{"targetFp":"fp-admin","kickId":"k-1","mode":"vote"}""",
        )
        val pollVote = event(
            "p-1", "fp-outsider", "vote", """{"kickId":"k-1","approve":true}""", lamport = 2,
        )
        val s = FlitCircleStore.merge(state(), listOf(kick, pollVote))
        assertEquals(3, s.members.size)
        assertEquals(0, s.rekeyGen)
    }

    @Test
    fun `only the owner appoints or removes admins`() {
        val appoint = event("r-1", "fp-owner", "role", """{"targetFp":"fp-member","role":"ADMIN"}""")
        val promoted = FlitCircleStore.reduce(state(), appoint)
        assertEquals(
            FlitCircleStore.Role.ADMIN,
            promoted.members.first { it.fp == "fp-member" }.role,
        )
        assertEquals(0, promoted.rekeyGen)

        val byAdmin = FlitCircleStore.reduce(
            state(), event("r-2", "fp-admin", "role", """{"targetFp":"fp-member","role":"ADMIN"}"""),
        )
        assertEquals(FlitCircleStore.Role.MEMBER, byAdmin.members.first { it.fp == "fp-member" }.role)

        val newOwner = FlitCircleStore.reduce(
            state(), event("r-3", "fp-owner", "role", """{"targetFp":"fp-member","role":"OWNER"}"""),
        )
        assertEquals(FlitCircleStore.Role.MEMBER, newOwner.members.first { it.fp == "fp-member" }.role)

        val selfDemote = FlitCircleStore.reduce(
            state(), event("r-4", "fp-owner", "role", """{"targetFp":"fp-owner","role":"MEMBER"}"""),
        )
        assertEquals(FlitCircleStore.Role.OWNER, selfDemote.members.first { it.fp == "fp-owner" }.role)

        val demote = FlitCircleStore.reduce(
            promoted, event("r-5", "fp-owner", "role", """{"targetFp":"fp-admin","role":"MEMBER"}"""),
        )
        assertEquals(FlitCircleStore.Role.MEMBER, demote.members.first { it.fp == "fp-admin" }.role)
        assertEquals(3, demote.members.size)

        // Public reducer stays callable directly by the engine/UI wiring.
        assertEquals(
            FlitCircleStore.Role.ADMIN,
            FlitCircleStore.applyRole(
                state(), event("r-6", "fp-owner", "role", """{"targetFp":"fp-member","role":"ADMIN"}"""),
            ).members.first { it.fp == "fp-member" }.role,
        )
    }

    @Test
    fun `a role change targeting self keeps the local role in step`() {
        val mine = state().copy(selfFp = "fp-member")
        val promoted = FlitCircleStore.reduce(
            mine, event("r-up", "fp-owner", "role", """{"targetFp":"fp-member","role":"ADMIN"}"""),
        )
        assertEquals(FlitCircleStore.Role.ADMIN, promoted.role)
        assertEquals(
            FlitCircleStore.Role.ADMIN,
            promoted.members.first { it.fp == "fp-member" }.role,
        )

        // A promotion of somebody else must not silently promote this device.
        val other = state().copy(selfFp = "fp-admin")
        val promotedOther = FlitCircleStore.reduce(
            other, event("r-other", "fp-owner", "role", """{"targetFp":"fp-member","role":"ADMIN"}"""),
        )
        assertEquals(FlitCircleStore.Role.OWNER, other.role)
        assertEquals(FlitCircleStore.Role.OWNER, promotedOther.role)

        val demoted = FlitCircleStore.reduce(
            promoted, event("r-down", "fp-owner", "role", """{"targetFp":"fp-member","role":"MEMBER"}"""),
        )
        assertEquals(FlitCircleStore.Role.MEMBER, demoted.role)
        assertEquals(FlitCircleStore.Role.OWNER, mine.role) // input untouched
    }

    @Test
    fun `legacy rows infer self only when the local role is unique`() {
        val solo = state(
            members = listOf(owner, member),
            key = "key-v1",
        ).copy(role = FlitCircleStore.Role.MEMBER)
        val promoted = FlitCircleStore.reduce(
            solo, event("r-legacy", "fp-owner", "role", """{"targetFp":"fp-member","role":"ADMIN"}"""),
        )
        assertEquals(FlitCircleStore.Role.ADMIN, promoted.role)

        val ambiguous = state(
            members = listOf(owner, member, FlitCircleStore.Member("fp-other", "Other", FlitCircleStore.Role.MEMBER)),
        ).copy(role = FlitCircleStore.Role.MEMBER)
        val noChange = FlitCircleStore.reduce(
            ambiguous, event("r-ambig", "fp-owner", "role", """{"targetFp":"fp-member","role":"ADMIN"}"""),
        )
        assertEquals(FlitCircleStore.Role.MEMBER, noChange.role)
    }

    @Test
    fun `rekey installs the new key without a second generation bump`() {
        val changed = FlitCircleStore.reduce(
            state(), event("rk-1", "fp-owner", "rekey", """{"keyB64":"key-v2","gen":1}"""),
        )
        assertEquals("key-v2", changed.keyB64)
        assertEquals(1, changed.rekeyGen)

        // A stale generation is replay: it cannot roll the live key back.
        val stale = FlitCircleStore.reduce(
            changed, event("rk-2", "fp-owner", "rekey", """{"keyB64":"key-v3","gen":0}"""),
        )
        assertEquals("key-v2", stale.keyB64)
        assertEquals(1, stale.rekeyGen)

        val newer = FlitCircleStore.reduce(
            changed, event("rk-3", "fp-owner", "rekey", """{"keyB64":"key-v4","gen":2}"""),
        )
        assertEquals("key-v4", newer.keyB64)
        assertEquals(2, newer.rekeyGen)
    }

    @Test
    fun `only known participants may rekey and payloads are bounded`() {
        // A stranger with no roster entry and no signed history cannot rotate.
        val outsider = FlitCircleStore.reduce(
            state(), event("rk-out", "fp-outsider", "rekey", """{"keyB64":"evil","gen":1}"""),
        )
        assertEquals("key-v1", outsider.keyB64)
        assertEquals(0, outsider.rekeyGen)

        // An officer on the roster may rotate.
        val officer = FlitCircleStore.reduce(
            state(), event("rk-admin", "fp-admin", "rekey", """{"keyB64":"key-v2","gen":1}"""),
        )
        assertEquals("key-v2", officer.keyB64)
        assertEquals(1, officer.rekeyGen)

        // A departing device can hand off a key in the same batch: its signed
        // leave is already in the log, so the author is a known participant.
        val afterLeave = FlitCircleStore.reduce(state(), event("l-1", "fp-member", "leave"))
        val handoff = FlitCircleStore.reduce(
            afterLeave, event("rk-handoff", "fp-member", "rekey", """{"keyB64":"peer-key","gen":1}"""),
        )
        assertEquals("peer-key", handoff.keyB64)
        assertEquals(1, handoff.rekeyGen)

        val negative = FlitCircleStore.reduce(
            state(), event("rk-neg", "fp-owner", "rekey", """{"keyB64":"evil","gen":-1}"""),
        )
        assertEquals("key-v1", negative.keyB64)
        assertEquals(0, negative.rekeyGen)

        val bigKey = "A".repeat(FlitCircleStore.MAX_KEY_CHARS + 1)
        val oversized = FlitCircleStore.reduce(
            state(), event("rk-big", "fp-owner", "rekey", """{"keyB64":"$bigKey","gen":1}"""),
        )
        assertEquals("key-v1", oversized.keyB64)
        assertEquals(0, oversized.rekeyGen)
    }

    @Test
    fun `wrong circle events are ignored and the log is bounded`() {
        val alien = event("x-1", "fp-owner", "message").copy(circleId = "other")
        assertEquals(state(), FlitCircleStore.reduce(state(), alien))

        val many = (1..FlitCircleStore.EVENT_CAP + 7).map {
            event("m-$it", "fp-owner", "message", lamport = it.toLong())
        }
        val merged = FlitCircleStore.merge(state(), many + many.take(3))
        assertEquals(FlitCircleStore.EVENT_CAP, merged.events.size)
        assertEquals(FlitCircleStore.EVENT_CAP, merged.events.map { it.id }.distinct().size)
        assertNotEquals(many.first().id, merged.events.first().id)

        // A corrupt-but-parseable old row cannot smuggle in an unbounded log.
        val trimmed = decodeCircle(FlitCircleStore.encode(state(events = many)))!!
        assertEquals(FlitCircleStore.EVENT_CAP, trimmed.events.size)
    }
}
