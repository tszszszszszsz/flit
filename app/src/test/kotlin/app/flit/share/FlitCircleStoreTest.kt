package app.flit.share

import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.courier.CarryState
import app.flit.share.protocol.courier.CourierCarryCore
import app.flit.share.protocol.courier.CourierParcel
import app.flit.share.protocol.courier.MAX_PARCEL_TTL_MS
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
        deliveries: Map<String, FlitCircleStore.CircleDelivery> = emptyMap(),
    ) = FlitCircleStore.CircleState(
        id = "circle-1",
        keyB64 = key,
        role = FlitCircleStore.Role.OWNER,
        members = members,
        events = events,
        rekeyGen = rekeyGen,
        deliveries = deliveries,
    )

    private fun delivery(
        id: String = "d-1",
        name: String = "photo.jpg",
        size: Long = 1_234L,
        sha: String = "sha-aabb",
        spoolPath: String = "/cache/circle/photo.jpg",
        statuses: Map<String, String> = emptyMap(),
        created: Long = 5_000L,
    ) = FlitCircleStore.CircleDelivery(
        id = id, name = name, size = size, sha256 = sha,
        spoolPath = spoolPath, statuses = statuses, createdMs = created,
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
        assertEquals(emptyMap(), old?.deliveries) // additive field: old rows get an empty map
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
        assertEquals(emptyMap(), s.deliveries)
        assertEquals(0, s.rekeyGen)
        assertEquals(FlitCircleStore.Role.MEMBER, FlitCircleStore.Member("a", "A").role)
        assertEquals(0L, FlitCircleStore.Member("a", "A").joinedMs)
        // Delivery fields are additive too: statuses and createdMs have defaults.
        val bare = FlitCircleStore.CircleDelivery("d", "f.txt", 1L, "sha", "/cache/f")
        assertEquals(emptyMap(), bare.statuses)
        assertEquals(0L, bare.createdMs)
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
    fun `only a current owner or admin may rekey and payloads are bounded`() {
        // A stranger with no roster entry and no signed history cannot rotate.
        val outsider = FlitCircleStore.reduce(
            state(), event("rk-out", "fp-outsider", "rekey", """{"keyB64":"evil","gen":1}"""),
        )
        assertEquals("key-v1", outsider.keyB64)
        assertEquals(0, outsider.rekeyGen)

        // An OWNER and an ADMIN on the roster may rotate.
        val ownerKey = FlitCircleStore.reduce(
            state(), event("rk-owner", "fp-owner", "rekey", """{"keyB64":"owner-key","gen":1}"""),
        )
        assertEquals("owner-key", ownerKey.keyB64)
        assertEquals(1, ownerKey.rekeyGen)

        val adminKey = FlitCircleStore.reduce(
            state(), event("rk-admin", "fp-admin", "rekey", """{"keyB64":"key-v2","gen":1}"""),
        )
        assertEquals("key-v2", adminKey.keyB64)
        assertEquals(1, adminKey.rekeyGen)

        // A MEMBER on the roster has no key authority either.
        val plainMember = FlitCircleStore.reduce(
            state(), event("rk-member", "fp-member", "rekey", """{"keyB64":"member-key","gen":1}"""),
        )
        assertEquals("key-v1", plainMember.keyB64)
        assertEquals(0, plainMember.rekeyGen)

        // A departing MEMBER can no longer hand off a key in the same batch:
        // its leave is in the log, but log authorship is not rekey authority.
        val afterLeave = FlitCircleStore.reduce(state(), event("l-1", "fp-member", "leave"))
        val handoff = FlitCircleStore.reduce(
            afterLeave, event("rk-handoff", "fp-member", "rekey", """{"keyB64":"peer-key","gen":1}"""),
        )
        assertEquals("key-v1", handoff.keyB64)
        assertEquals(1, handoff.rekeyGen) // only the leave bumped it

        // Being an author in the log alone never grants the verb, even when
        // the rekey carries a fresh, plausible key and generation.
        val withLogAuthor = FlitCircleStore.reduce(
            state(), event("m-former", "fp-former", "message", """{"text":"old"}"""),
        )
        val loggedAuthor = FlitCircleStore.reduce(
            withLogAuthor, event("rk-log-author", "fp-former", "rekey", """{"keyB64":"log-key","gen":1}"""),
        )
        assertEquals("key-v1", loggedAuthor.keyB64)
        assertEquals(0, loggedAuthor.rekeyGen)
        assertEquals(
            listOf("m-former", "rk-log-author"),
            loggedAuthor.events.map { it.id }, // refused rekeys are still inert log history
        )

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
    fun `an officer cannot hand off a key in the same batch as its leave`() {
        val evil = "officer-key"
        for (author in listOf("fp-owner", "fp-admin")) {
            val rekey = event(
                "rk-handoff-$author", author, "rekey",
                """{"keyB64":"$evil","gen":1}""", lamport = 1,
            )
            val leave = event("l-officer-$author", author, "leave", lamport = 2)

            for (batch in listOf(listOf(rekey, leave), listOf(leave, rekey))) {
                val merged = FlitCircleStore.merge(state(), batch)
                val label = "$author / ${batch.map { it.type }}"
                assertEquals("key-v1", merged.keyB64, label)
                assertEquals(1, merged.rekeyGen, label) // only the leave bumped it
                assertTrue(merged.members.none { it.fp == author }, label)
                // The attempted hand-off is inert history either way.
                assertEquals(batch.map { it.id }, merged.events.map { it.id }, label)
            }
        }
    }

    @Test
    fun `pre batch authority is order independent and only the leaver is vetoed`() {
        val leave = event("l-member", "fp-member", "leave", lamport = 1)
        val rekey = event(
            "rk-staying", "fp-admin", "rekey", """{"keyB64":"admin-key","gen":1}""", lamport = 2,
        )
        for (batch in listOf(listOf(rekey, leave), listOf(leave, rekey))) {
            // A staying officer keeps its authority; somebody else's leave in
            // the batch must not veto its rekey, in either fold order.
            val merged = FlitCircleStore.merge(state(), batch)
            assertEquals("admin-key", merged.keyB64)
            assertTrue(merged.rekeyGen >= 1)
            assertTrue(merged.members.none { it.fp == "fp-member" })
            assertTrue(merged.members.any { it.fp == "fp-admin" })
        }

        // A departed officer cannot rotate in a later batch either: the
        // snapshot for that batch no longer grants it authority.
        val departed = FlitCircleStore.merge(
            state(), listOf(event("l-admin", "fp-admin", "leave")),
        )
        val late = FlitCircleStore.reduce(
            departed,
            event("rk-late", "fp-admin", "rekey", """{"keyB64":"late-key","gen":1}"""),
        )
        assertEquals("key-v1", late.keyB64)
        assertEquals(1, late.rekeyGen)
    }

    @Test
    fun `a same batch promotion cannot turn a member kick into an admin kick in either order`() {
        val promote = event(
            "r-promote", "fp-owner", "role", """{"targetFp":"fp-member","role":"ADMIN"}""",
        )
        val kick = event(
            "k-exec", "fp-member", "kick",
            """{"kickId":"k-exec","targetFp":"fp-admin","mode":"admin"}""", lamport = 2,
        )
        for (batch in listOf(listOf(promote, kick), listOf(kick, promote))) {
            val merged = FlitCircleStore.merge(state(), batch)
            assertEquals(
                FlitCircleStore.Role.ADMIN,
                merged.members.first { it.fp == "fp-member" }.role,
            )
            assertEquals(3, merged.members.size, "the member's admin kick must not execute")
            assertEquals(0, merged.rekeyGen)
            assertEquals(batch.map { it.id }, merged.events.map { it.id })
        }
    }

    @Test
    fun `role authority comes from the pre batch roster not the folded roster`() {
        val newcomer = FlitCircleStore.Member("fp-new", "New", FlitCircleStore.Role.MEMBER, 4)
        val start = state(members = roster + newcomer)
        val promote = event(
            "r-promote", "fp-owner", "role", """{"targetFp":"fp-member","role":"ADMIN"}""",
        )
        val selfServe = event(
            "r-self", "fp-member", "role", """{"targetFp":"fp-new","role":"ADMIN"}""", lamport = 2,
        )
        for (batch in listOf(listOf(promote, selfServe), listOf(selfServe, promote))) {
            val merged = FlitCircleStore.merge(start, batch)
            assertEquals(
                FlitCircleStore.Role.MEMBER,
                merged.members.first { it.fp == "fp-new" }.role,
                "a same-batch promotion cannot vouch for the promoted member's own role event",
            )
            assertEquals(
                FlitCircleStore.Role.ADMIN,
                merged.members.first { it.fp == "fp-member" }.role,
            )
            assertEquals(4, merged.members.size)
        }
    }

    @Test
    fun `a same batch demotion does not strip an officer kick authority`() {
        val demote = event(
            "r-demote", "fp-owner", "role", """{"targetFp":"fp-admin","role":"MEMBER"}""",
        )
        val kick = event(
            "k-officer", "fp-admin", "kick",
            """{"targetFp":"fp-member","mode":"admin"}""", lamport = 2,
        )
        for (batch in listOf(listOf(demote, kick), listOf(kick, demote))) {
            val merged = FlitCircleStore.merge(state(), batch)
            assertEquals(
                listOf("fp-owner", "fp-admin"),
                merged.members.map { it.fp },
                "the officer's executive kick survives a same-batch demotion",
            )
            assertEquals(1, merged.rekeyGen)
        }
    }

    @Test
    fun `two argument applyRole and applyKick still use the state roster`() {
        val promoted = FlitCircleStore.reduce(
            state(), event("r-1", "fp-owner", "role", """{"targetFp":"fp-member","role":"ADMIN"}"""),
        )
        val directKick = FlitCircleStore.applyKick(
            promoted, event("k-1", "fp-member", "kick", """{"targetFp":"fp-admin","mode":"admin"}"""),
        )
        assertEquals(listOf("fp-owner", "fp-member"), directKick.members.map { it.fp })
        assertEquals(1, directKick.rekeyGen)

        val byAdmin = FlitCircleStore.applyRole(
            state(), event("r-2", "fp-admin", "role", """{"targetFp":"fp-member","role":"ADMIN"}"""),
        )
        assertEquals(FlitCircleStore.Role.MEMBER, byAdmin.members.first { it.fp == "fp-member" }.role)

        // The explicit snapshot wins over what the folded state currently says.
        val stale = FlitCircleStore.applyRole(
            promoted,
            event("r-3", "fp-member", "role", """{"targetFp":"fp-admin","role":"MEMBER"}"""),
            mapOf("FP-MEMBER" to "MEMBER"),
        )
        assertEquals(FlitCircleStore.Role.ADMIN, stale.members.first { it.fp == "fp-admin" }.role)
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

    @Test
    fun `codec roundtrip keeps deliveries and normalises corrupt delivery rows`() {
        val d = delivery(statuses = mapOf("fp-admin" to CircleDelivery.SENT))
        val source = state(deliveries = mapOf(d.sha256 to d))
        assertEquals(source, decodeCircle(encode(source)))

        val dirty = state(
            deliveries = linkedMapOf(
                "  " to delivery(id = "blank-key", sha = "sha-ignored"),
                "sha-aabb" to delivery(statuses = mapOf("" to "sent", "fp-owner" to "sent")),
                "no-sha" to delivery(id = "no-sha", sha = "   "),
            ),
        )
        val clean = decodeCircle(encode(dirty))!!
        assertEquals(listOf("sha-aabb"), clean.deliveries.keys.toList())
        assertEquals(mapOf("fp-owner" to "sent"), clean.deliveries["sha-aabb"]!!.statuses)
    }

    @Test
    fun `upsertDelivery adds then replaces by sha and keeps statuses on retry`() {
        val s0 = state()
        val d = delivery(statuses = mapOf("fp-admin" to CircleDelivery.PENDING))
        val s1 = FlitCircleStore.upsertDelivery(s0, d)
        assertEquals(mapOf(d.sha256 to d), s1.deliveries)
        assertEquals(emptyMap(), s0.deliveries) // pure: input untouched

        val s2 = FlitCircleStore.setDeliveryStatus(s1, d.sha256, "fp-admin", CircleDelivery.SENT)
        val s3 = FlitCircleStore.upsertDelivery(s2, delivery(id = "d-1-retry", name = "photo-v2.jpg"))
        // A retry that re-announces the same sha with no statuses keeps what we learned.
        assertEquals(mapOf("fp-admin" to CircleDelivery.SENT), s3.deliveries[d.sha256]!!.statuses)
        assertEquals("photo-v2.jpg", s3.deliveries[d.sha256]!!.name)

        val explicit = delivery(statuses = mapOf("fp-member" to CircleDelivery.REFUSED))
        val s4 = FlitCircleStore.upsertDelivery(s3, explicit)
        assertEquals(mapOf("fp-member" to CircleDelivery.REFUSED), s4.deliveries[d.sha256]!!.statuses)
        assertEquals(1, s4.deliveries.size) // no duplicate row for the same sha

        val two = FlitCircleStore.upsertDelivery(s4, delivery(id = "d-2", sha = "sha-cc"))
        assertEquals(2, two.deliveries.size)
        assertEquals(s4, FlitCircleStore.upsertDelivery(s4, delivery(sha = "  "))) // blank sha inert
    }

    @Test
    fun `setDeliveryStatus is per fingerprint, latest wins and unknown targets are inert`() {
        val d = delivery()
        val s0 = state(deliveries = mapOf(d.sha256 to d))
        val s1 = FlitCircleStore.setDeliveryStatus(s0, d.sha256, "fp-admin", CircleDelivery.SENT)
        val s2 = FlitCircleStore.setDeliveryStatus(s1, d.sha256, "fp-member", CircleDelivery.PENDING)
        assertEquals(
            mapOf("fp-admin" to "sent", "fp-member" to "pending"),
            s2.deliveries[d.sha256]!!.statuses,
        )
        val s3 = FlitCircleStore.setDeliveryStatus(s2, d.sha256, "fp-admin", CircleDelivery.REFUSED)
        assertEquals("refused", s3.deliveries[d.sha256]!!.statuses["fp-admin"])
        assertEquals(emptyMap(), s0.deliveries[d.sha256]!!.statuses) // input untouched

        assertEquals(s3, FlitCircleStore.setDeliveryStatus(s3, "sha-unknown", "fp-owner", "sent"))
        assertEquals(s3, FlitCircleStore.setDeliveryStatus(s3, d.sha256, " ", "sent"))
        assertEquals(s3, FlitCircleStore.setDeliveryStatus(s3, d.sha256, "fp-owner", ""))

        // A delivery id or a case variant also resolves to the same sha row.
        assertEquals(
            CircleDelivery.FAILED,
            FlitCircleStore.setDeliveryStatus(s3, d.id, "fp-owner", CircleDelivery.FAILED)
                .deliveries[d.sha256]!!.statuses["fp-owner"],
        )
        assertEquals(
            CircleDelivery.SENT,
            FlitCircleStore.setDeliveryStatus(s3, d.sha256.uppercase(), "fp-owner", CircleDelivery.SENT)
                .deliveries[d.sha256]!!.statuses["fp-owner"],
        )
    }

    @Test
    fun `removeDelivery forgets one record and ignores unknown shas`() {
        val a = delivery(sha = "sha-a")
        val b = delivery(id = "d-2", sha = "sha-b")
        val s = FlitCircleStore.upsertDelivery(FlitCircleStore.upsertDelivery(state(), a), b)
        val removed = FlitCircleStore.removeDelivery(s, a.sha256)
        assertEquals(listOf("sha-b"), removed.deliveries.keys.toList())
        assertEquals(s, FlitCircleStore.removeDelivery(s, "sha-unknown"))
        assertEquals(emptyMap(), FlitCircleStore.removeDelivery(removed, b.sha256).deliveries)
        assertEquals(s, FlitCircleStore.removeDelivery(s, "  "))
    }

    @Test
    fun `event folds leave local delivery records untouched`() {
        val d = delivery(statuses = mapOf("fp-admin" to CircleDelivery.SENT))
        val s = state(deliveries = mapOf(d.sha256 to d))
        val folded = FlitCircleStore.merge(s, listOf(
            event("e-1", "fp-owner", "message", """{"text":"hi"}"""),
            event("j-1", "fp-new", "join"),
        ))
        assertEquals(s.deliveries, folded.deliveries)
        assertEquals(2, folded.events.size)
        assertEquals(4, folded.members.size)
    }

    // ==== courier bag (additive persisted string, local only) ==================

    private fun parcel(
        id: String = "p-1",
        toFp: String = "FP-OTHER",
        fromFp: String = "fp-owner",
        created: Long = 1_000L,
    ) = CourierParcel(
        id = id, toFp = toFp, fromFp = fromFp,
        createdMs = created, expiresMs = created + MAX_PARCEL_TTL_MS,
        event = event(
            id, fromFp, "parcel",
            """{"toFp":"$toFp","envelope":"ENVELOPE-$id"}""",
            created = created,
        ),
    )

    @Test
    fun `courierBag defaults to a blank string and old rows read as an empty bag`() {
        // Additive field: every named-argument constructor above predates it.
        assertEquals("", state().courierBag)
        assertEquals(emptyList(), CourierCarryCore.decode(state().courierBag)?.parcels)

        // A row written before the courier layer has no courierBag key at all.
        val old = decodeCircle(
            """{"id":"circle-1","keyB64":"k","role":"OWNER","members":""" +
                """[{"fp":"fp-owner","alias":"Owner","role":"OWNER"}],"unknown":"x"}""",
        )!!
        assertEquals("k", old.keyB64)
        assertEquals(1, old.members.size)
        assertEquals(emptyList(), CourierCarryCore.decode(old.courierBag)?.parcels)
    }

    @Test
    fun `courierBag roundtrips through encode and decode`() {
        val bag = CarryState(listOf(parcel()))
        val rawBag = CourierCarryCore.encode(bag)
        val source = state(
            events = listOf(event("e-1", "fp-owner", "message", """{"text":"hello"}""")),
            deliveries = mapOf("sha-aabb" to delivery()),
        ).copy(courierBag = rawBag)

        val decoded = decodeCircle(encode(source))!!
        assertEquals(rawBag, decoded.courierBag)
        assertEquals(bag, CourierCarryCore.decode(decoded.courierBag))
        assertEquals(source, decoded)
    }

    @Test
    fun `courierBag corrupt row decodes tolerantly to an empty bag`() {
        // The string is a valid JSON value but not a CarryState; the whole row
        // must still decode and its other fields survive unchanged.
        val s = decodeCircle(
            """{"id":"circle-1","keyB64":"k","role":"OWNER","members":""" +
                """[{"fp":"fp-owner","alias":"Owner","role":"OWNER"}],"courierBag":"{not json"}""",
        )!!
        assertEquals(emptyList(), CourierCarryCore.decode(s.courierBag)?.parcels)
        assertEquals("k", s.keyB64)
        assertEquals(1, s.members.size)
        assertEquals(0, s.rekeyGen)
    }

    @Test
    fun `a bag write leaves events roster and deliveries intact`() {
        val d = delivery(statuses = mapOf("fp-admin" to CircleDelivery.SENT))
        val before = state(
            events = listOf(event("e-1", "fp-owner", "message")),
            deliveries = mapOf(d.sha256 to d),
        )
        val bag = CarryState(listOf(parcel(id = "p-2")))
        val after = decodeCircle(encode(before.copy(courierBag = CourierCarryCore.encode(bag))))!!

        assertEquals(before.events, after.events)
        assertEquals(before.members, after.members)
        assertEquals(before.deliveries, after.deliveries)
        assertEquals(before.keyB64, after.keyB64)
        assertEquals(before.rekeyGen, after.rekeyGen)
        assertEquals(bag, CourierCarryCore.decode(after.courierBag))
        assertEquals(before, after.copy(courierBag = before.courierBag))
    }
}
