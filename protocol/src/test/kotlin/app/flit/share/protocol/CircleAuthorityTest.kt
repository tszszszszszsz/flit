package app.flit.share.protocol

import app.flit.share.protocol.circle.CircleAuthority
import app.flit.share.protocol.circle.CircleEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure JVM pins for [CircleAuthority.foldable] and [CircleAuthority.authorityOf].
 * Every event here is a plain data-class value: no signing, certificate
 * pinning, socket, store, Android class or clock is involved, and the gate
 * keeps no state between calls.
 *
 * Coverage: the full truth table (JOIN, REKEY, ROLE, KICK, ordinary verbs,
 * circle match), fingerprint and role normalisation, empty roles, and totality
 * on hostile field values that carry no authority meaning.
 */
class CircleAuthorityTest {
    private fun event(
        type: String,
        circleId: String = CIRCLE,
        authorFp: String = ALICE,
        id: String = "id-1",
        body: String = "{}",
        sig: String = "sig",
        lamport: Long = 1L,
        createdMs: Long = 1_000L,
        cert: String = "cert",
    ): CircleEvent = CircleEvent(id, circleId, authorFp, lamport, type, body, sig, createdMs, cert)

    private fun roles(vararg pairs: Pair<String, String>): Map<String, String> = mapOf(*pairs)

    @Test
    fun joinIsNeverFoldable() {
        assertFalse(CircleAuthority.foldable(event(CircleEvent.JOIN), CIRCLE, roles(ALICE to "OWNER")))
        assertFalse(CircleAuthority.foldable(event(CircleEvent.JOIN), CIRCLE, roles(ALICE to "ADMIN")))
        assertFalse(CircleAuthority.foldable(event(CircleEvent.JOIN), CIRCLE, emptyMap()))
    }

    @Test
    fun rekeyNeedsOwnerOrAdminAuthor() {
        val rekey = event(CircleEvent.REKEY)
        assertTrue(CircleAuthority.foldable(rekey, CIRCLE, roles(ALICE to "OWNER")))
        assertTrue(CircleAuthority.foldable(rekey, CIRCLE, roles(ALICE to "ADMIN")))
        assertFalse(CircleAuthority.foldable(rekey, CIRCLE, roles(ALICE to "MEMBER")))
        assertFalse(CircleAuthority.foldable(rekey, CIRCLE, roles(ALICE to "SERVER")))
        assertFalse(CircleAuthority.foldable(rekey, CIRCLE, roles(BOB to "OWNER")))
    }

    @Test
    fun everyOrdinaryVerbFoldsInsideItsCircle() {
        assertEquals(8, ORDINARY.size)
        ORDINARY.forEach { type ->
            assertTrue(
                CircleAuthority.foldable(event(type), CIRCLE, emptyMap()),
                "$type should fold without role data",
            )
        }
    }

    @Test
    fun foreignCircleRefusesEveryVerb() {
        ORDINARY.forEach { type ->
            assertFalse(
                CircleAuthority.foldable(event(type, circleId = OTHER_CIRCLE), CIRCLE, emptyMap()),
                "$type must not fold into another circle",
            )
        }
        val rekey = event(CircleEvent.REKEY, circleId = OTHER_CIRCLE)
        assertFalse(CircleAuthority.foldable(rekey, CIRCLE, roles(ALICE to "OWNER")))
        assertFalse(
            CircleAuthority.foldable(event(CircleEvent.MESSAGE, circleId = " $CIRCLE "), CIRCLE, emptyMap()),
            "circle ids are matched exactly, not trimmed",
        )
    }

    @Test
    fun unknownOrBlankTypeNeverFolds() {
        listOf("", "   ", "\t\n", "unknown", "message\u0000", " MESSAGE ", "JOIN", "PaD").forEach { type ->
            assertFalse(
                CircleAuthority.foldable(event(type), CIRCLE, roles(ALICE to "OWNER")),
                "type [$type] is not a known CircleEvent verb",
            )
        }
    }

    @Test
    fun blankAuthorNeverFolds() {
        assertFalse(CircleAuthority.foldable(event(CircleEvent.MESSAGE, authorFp = ""), CIRCLE, emptyMap()))
        assertFalse(
            CircleAuthority.foldable(event(CircleEvent.MESSAGE, authorFp = " \t\n "), CIRCLE, emptyMap()),
        )
        assertFalse(
            CircleAuthority.foldable(event(CircleEvent.REKEY, authorFp = ""), CIRCLE, roles("" to "OWNER")),
        )
    }

    @Test
    fun authorFingerprintIsTrimmedAndUppercasedForRoleLookup() {
        val mixed = event(CircleEvent.REKEY, authorFp = "  aaBb  ")
        assertTrue(CircleAuthority.foldable(mixed, CIRCLE, roles("AABB" to "OWNER")))
        assertFalse(CircleAuthority.foldable(mixed, CIRCLE, roles("aabb" to "OWNER")))
        assertFalse(CircleAuthority.foldable(mixed, CIRCLE, roles("  AABB  " to "OWNER")))
        assertTrue(
            CircleAuthority.foldable(event(CircleEvent.MESSAGE, authorFp = "  aAbB "), CIRCLE, emptyMap()),
            "ordinary verbs do not need a role entry",
        )
    }

    @Test
    fun roleValueIsTrimmedAndUppercased() {
        val rekey = event(CircleEvent.REKEY)
        listOf("OWNER", "owner", " owner ", "AdMiN", "\tadmin\n").forEach { role ->
            assertTrue(
                CircleAuthority.foldable(rekey, CIRCLE, roles(ALICE to role)),
                "role [$role] grants the key verb",
            )
        }
        listOf("MEMBER", "member", "superuser", "", "  ").forEach { role ->
            assertFalse(
                CircleAuthority.foldable(rekey, CIRCLE, roles(ALICE to role)),
                "role [$role] does not grant the key verb",
            )
        }
    }

    @Test
    fun emptyRolesRefuseGovernanceButAllowOrdinaryEvents() {
        assertFalse(CircleAuthority.foldable(event(CircleEvent.REKEY), CIRCLE, emptyMap()))
        assertFalse(CircleAuthority.foldable(event(CircleEvent.REKEY), CIRCLE, roles(BOB to "ADMIN")))
        assertFalse(CircleAuthority.foldable(event(CircleEvent.JOIN), CIRCLE, emptyMap()))
        ORDINARY.forEach { type ->
            assertTrue(CircleAuthority.foldable(event(type), CIRCLE, emptyMap()))
        }
    }

    @Test
    fun leaveStaysOrdinaryWhileRoleAndKickNeedAuthority() {
        assertTrue(CircleAuthority.foldable(event(CircleEvent.LEAVE), CIRCLE, emptyMap()))
        listOf(CircleEvent.ROLE, CircleEvent.KICK, CircleEvent.REKEY).forEach { type ->
            assertFalse(
                CircleAuthority.foldable(event(type), CIRCLE, emptyMap()),
                "$type changes the roster or key and cannot fold without roles",
            )
        }
    }

    @Test
    fun `role folds only from the owner on the supplied roles snapshot`() {
        val role = event(CircleEvent.ROLE, body = """{"targetFp":"$BOB","role":"ADMIN"}""")
        assertTrue(CircleAuthority.foldable(role, CIRCLE, roles(ALICE to "OWNER")))
        assertTrue(CircleAuthority.foldable(role, CIRCLE, roles(ALICE to " owner ")))
        assertFalse(CircleAuthority.foldable(role, CIRCLE, roles(ALICE to "ADMIN")))
        assertFalse(CircleAuthority.foldable(role, CIRCLE, roles(ALICE to "MEMBER")))
        assertFalse(CircleAuthority.foldable(role, CIRCLE, roles(BOB to "OWNER")))
        assertFalse(CircleAuthority.foldable(role, CIRCLE, emptyMap()))
        assertEquals(CircleAuthority.Authority.OWNER, CircleAuthority.authorityOf(role))
    }

    @Test
    fun `admin mode kick folds only from an officer and vote kick folds from a member`() {
        val adminKick = event(CircleEvent.KICK, body = """{"targetFp":"$BOB","mode":"admin"}""")
        assertTrue(CircleAuthority.foldable(adminKick, CIRCLE, roles(ALICE to "OWNER")))
        assertTrue(CircleAuthority.foldable(adminKick, CIRCLE, roles(ALICE to "ADMIN")))
        assertFalse(CircleAuthority.foldable(adminKick, CIRCLE, roles(ALICE to "MEMBER")))
        assertFalse(CircleAuthority.foldable(adminKick, CIRCLE, emptyMap()))
        assertEquals(CircleAuthority.Authority.OFFICER, CircleAuthority.authorityOf(adminKick))

        val voteKick = event(CircleEvent.KICK, body = """{"targetFp":"$BOB","mode":"vote"}""")
        assertTrue(CircleAuthority.foldable(voteKick, CIRCLE, roles(ALICE to "MEMBER")))
        assertTrue(CircleAuthority.foldable(voteKick, CIRCLE, roles(ALICE to "ADMIN")))
        assertFalse(CircleAuthority.foldable(voteKick, CIRCLE, emptyMap()))
        assertEquals(CircleAuthority.Authority.MEMBER, CircleAuthority.authorityOf(voteKick))

        // A mode-less CLI kick is still an ordinary member vote-kick.
        val modeLess = event(CircleEvent.KICK, body = """{"targetFp":"$BOB"}""")
        assertTrue(CircleAuthority.foldable(modeLess, CIRCLE, roles(ALICE to "MEMBER")))
        assertEquals(CircleAuthority.Authority.MEMBER, CircleAuthority.authorityOf(modeLess))
    }

    @Test
    fun `privileged verbs are refused when no roles are supplied`() {
        assertEquals(
            setOf(CircleEvent.JOIN, CircleEvent.ROLE, CircleEvent.KICK, CircleEvent.REKEY),
            CircleAuthority.PRIVILEGED,
        )
        CircleAuthority.PRIVILEGED.forEach { type ->
            assertFalse(
                CircleAuthority.foldable(event(type), CIRCLE, emptyMap()),
                "$type must not fold without a roster",
            )
        }
        assertFalse(CircleAuthority.foldable(event(CircleEvent.JOIN), CIRCLE, roles(ALICE to "OWNER")))
        assertTrue(CircleAuthority.foldable(event(CircleEvent.MESSAGE), CIRCLE, emptyMap()))
    }

    @Test
    fun `authorityOf classifies kick by mode and leaves other verbs unchanged`() {
        assertEquals(
            CircleAuthority.Authority.OFFICER,
            CircleAuthority.authorityOf(event(CircleEvent.KICK, body = """{"mode":" ADMIN "}""")),
        )
        assertEquals(
            CircleAuthority.Authority.MEMBER,
            CircleAuthority.authorityOf(event(CircleEvent.KICK, body = """{"mode":"vote"}""")),
        )
        assertEquals(
            CircleAuthority.Authority.MEMBER,
            CircleAuthority.authorityOf(event(CircleEvent.KICK, body = "not json")),
        )
        assertEquals(CircleAuthority.Authority.NONE, CircleAuthority.authorityOf(event(CircleEvent.MESSAGE)))
        assertEquals(CircleAuthority.Authority.NONE, CircleAuthority.authorityOf(event(CircleEvent.JOIN)))
        assertEquals(CircleAuthority.Authority.OWNER, CircleAuthority.authorityOf(event(CircleEvent.ROLE)))
        assertEquals(CircleAuthority.Authority.OFFICER, CircleAuthority.authorityOf(event(CircleEvent.REKEY)))
    }

    @Test
    fun nonAuthorityFieldsDoNotAffectTheDecision() {
        val message = event(CircleEvent.MESSAGE)
        val mangledMessage = event(
            CircleEvent.MESSAGE, id = "", body = "{not json", sig = "",
            lamport = -1L, createdMs = 0L, cert = "",
        )
        assertTrue(CircleAuthority.foldable(message, CIRCLE, emptyMap()))
        assertEquals(
            CircleAuthority.foldable(message, CIRCLE, emptyMap()),
            CircleAuthority.foldable(mangledMessage, CIRCLE, emptyMap()),
        )

        val rekey = event(CircleEvent.REKEY)
        val mangledRekey = event(
            CircleEvent.REKEY, id = "", body = "", sig = "???",
            lamport = Long.MIN_VALUE, createdMs = Long.MAX_VALUE, cert = "broken",
        )
        val owner = roles(ALICE to "OWNER")
        assertEquals(
            CircleAuthority.foldable(rekey, CIRCLE, owner),
            CircleAuthority.foldable(mangledRekey, CIRCLE, owner),
        )
    }

    @Test
    fun hostileInputIsTotal() {
        val huge = "X".repeat(20_000)
        val hostileRoles = mapOf(
            " " to "owner",
            ALICE to "\u0000owner\u0000",
            huge to "admin",
        )
        listOf(
            event(""),
            event("  "),
            event("join", authorFp = ""),
            event(CircleEvent.REKEY, authorFp = " \t "),
            event(CircleEvent.MESSAGE, circleId = OTHER_CIRCLE),
            event(CircleEvent.REKEY, authorFp = "y".repeat(20_000)),
            event("message\u0000"),
        ).forEach { hostile ->
            assertFalse(CircleAuthority.foldable(hostile, CIRCLE, hostileRoles))
        }
        assertTrue(
            CircleAuthority.foldable(event(CircleEvent.REKEY, authorFp = huge), CIRCLE, hostileRoles),
        )
        assertTrue(
            CircleAuthority.foldable(
                event(
                    CircleEvent.MESSAGE, id = huge, body = huge, sig = huge, cert = huge,
                    lamport = Long.MIN_VALUE, createdMs = Long.MIN_VALUE,
                ),
                CIRCLE,
                hostileRoles,
            ),
        )
    }

    private companion object {
        const val CIRCLE = "circle-1"
        const val OTHER_CIRCLE = "circle-2"
        const val ALICE = "AABBCC"
        const val BOB = "DDEEFF"
        val ORDINARY: Set<String> = CircleEvent.TYPES -
            setOf(CircleEvent.JOIN, CircleEvent.REKEY, CircleEvent.ROLE, CircleEvent.KICK)
    }
}
