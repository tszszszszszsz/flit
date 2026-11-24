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
            """{"targetFp":"fp-member","kickId":"k-1","mode":"vote"}""",
        )
        val s1 = FlitCircleStore.reduce(state(), open)
        assertEquals(3, s1.members.size)

        val s2 = FlitCircleStore.reduce(
            s1, event("v-1", "fp-owner", "vote", """{"kickId":"k-1","approve":true}""", lamport = 2),
        )
        assertEquals(3, s2.members.size) // one yes is not a majority of three

        // Admin says no, then owner changes yes → no: still no kick.
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
        assertEquals(listOf("fp-owner", "fp-admin"), s6.members.map { it.fp })
        assertEquals(1, s6.rekeyGen)

        // Late change of mind cannot resurrect the departed member.
        val s7 = FlitCircleStore.reduce(
            s6, event("v-6", "fp-admin", "vote", """{"kickId":"k-1","approve":false}""", lamport = 5),
        )
        assertEquals(2, s7.members.size)
        assertEquals(1, s7.rekeyGen)
    }

    @Test
    fun `votes from non members are not counted`() {
        val kick = event(
            "k-1", "fp-member", "kick", """{"targetFp":"fp-member","kickId":"k-1","mode":"vote"}""",
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

        val demote = FlitCircleStore.reduce(
            promoted, event("r-4", "fp-owner", "role", """{"targetFp":"fp-admin","role":"MEMBER"}"""),
        )
        assertEquals(FlitCircleStore.Role.MEMBER, demote.members.first { it.fp == "fp-admin" }.role)
        assertEquals(3, demote.members.size)
    }

    @Test
    fun `rekey installs the new key without a second generation bump`() {
        val changed = FlitCircleStore.reduce(
            state(), event("rk-1", "fp-owner", "rekey", """{"keyB64":"key-v2","gen":1}"""),
        )
        assertEquals("key-v2", changed.keyB64)
        assertEquals(1, changed.rekeyGen)

        val stale = FlitCircleStore.reduce(
            changed, event("rk-2", "fp-owner", "rekey", """{"keyB64":"key-v3","gen":0}"""),
        )
        assertEquals("key-v3", stale.keyB64)
        assertEquals(1, stale.rekeyGen) // generation only ever advances
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
    }
}
