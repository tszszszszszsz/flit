package app.flit.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure JVM pins for the Android/Compose-free security decisions behind the
 * share activity. No Android context, no Compose, no engine: only the helpers
 * that decide (a) which deep links may reach a confirmation, (b) what that
 * confirmation is allowed to show, and (c) whether a caller-declared shortcut
 * target is more than a preselection.
 *
 * The structural parts of the hardening (no addFromScan/joinCircle/engine start
 * before the confirm button, no auto-send from a SEND intent, FLAG_SECURE on
 * secret surfaces) live in MainActivity and are reviewed there, not here.
 */
class MainActivityUiTest {

    // ---- flit://add authority gate ------------------------------------------

    @Test
    fun `only the exact add authority is accepted`() {
        assertTrue(isFlitAddAuthority("add"))
        assertTrue(isFlitAddAuthority("ADD"))
        assertTrue(isFlitAddAuthority("Add"))
    }

    @Test
    fun `lookalike, retired and absent authorities are refused`() {
        assertFalse(isFlitAddAuthority(null))
        assertFalse(isFlitAddAuthority(""))
        assertFalse(isFlitAddAuthority("add.evil"))
        assertFalse(isFlitAddAuthority("add:443"))
        assertFalse(isFlitAddAuthority(" add"))
        assertFalse(isFlitAddAuthority("pair"))
        assertFalse(isFlitAddAuthority("pair.evil"))
    }

    // ---- confirmation content -----------------------------------------------

    @Test
    fun `pairing prompt shows host and prefix, never the full fingerprint or a token`() {
        val fp = "A1B2C3D4E5F60718293A4B5C6D7E8F90"
        val code = QrAdd(
            ip = "192.168.1.42",
            port = 53317,
            fingerprint = fp,
            alias = "André",
        )
        val p = describeDeepLink(code)

        assertEquals("192.168.1.42:53317", p.host)
        assertEquals(fp.take(12), p.fingerprintPrefix)
        assertNull(p.circleId)
        assertFalse(p.isInvite)
        // A prompt is an eyeball check, not a second copy of the identity.
        assertTrue(p.fingerprintPrefix.length < fp.length)
    }

    @Test
    fun `v3 invite prompt carries the circle id but never the invite token`() {
        val token = "secret-invite-token-DO-NOT-SHOW"
        val code = QrAdd(
            relay = "relay.example:7443",
            fingerprint = "DEADBEEFCAFEDEADBEEFCAFEDEADBEEF",
            circle = "circle-42",
            invite = token,
            v = 3,
        )
        val p = describeDeepLink(code)

        assertEquals("relay.example:7443", p.host)
        assertEquals("DEADBEEFCAFE", p.fingerprintPrefix)
        assertEquals("circle-42", p.circleId)
        assertTrue(p.isInvite)
        // The invite token must not leak into anything shown on screen.
        assertFalse(listOf(p.host, p.fingerprintPrefix, p.circleId).any { it?.contains(token) == true })
    }

    @Test
    fun `missing fp and route degrade to explicit placeholders`() {
        val p = describeDeepLink(QrAdd(v = 2))

        assertEquals("unknown host", p.host)
        assertEquals("not provided", p.fingerprintPrefix)
        assertNull(p.circleId)
        assertFalse(p.isInvite)
    }

    @Test
    fun `blank circle and invite are not treated as an invite`() {
        val p = describeDeepLink(
            QrAdd(ip = "10.0.0.2", port = 53317, circle = "  ", invite = null, v = 3),
        )
        assertNull(p.circleId)
        assertFalse(p.isInvite)
    }

    // ---- shortcut extras are preselection only ------------------------------

    @Test
    fun `preselection accepts only an already pinned trimmed fingerprint`() {
        val fp = "A1B2C3D4E5F60718293A4B5C6D7E8F90"
        val pinned = setOf(fp)

        assertEquals(fp, preselectPeerFp("  $fp  ") { it in pinned })
        assertNull(preselectPeerFp("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB") { it in pinned })
        assertNull(preselectPeerFp("   ") { it in pinned })
        assertNull(preselectPeerFp(null) { it in pinned })
    }

    @Test
    fun `preselection does not normalise a lookalike into a pinned identity`() {
        val fp = "A1B2C3D4E5F60718293A4B5C6D7E8F90"
        // No case-folding or other rewriting: a caller cannot turn a different
        // string into a pinned peer's identity just by changing its casing.
        assertNull(preselectPeerFp(fp.lowercase()) { it == fp })
        assertNull(preselectPeerFp("a1b2c3d4e5f6") { it == fp })
    }

    @Test
    fun `shortcut-id-only send is ignored but the peer-fp extra still preselects`() {
        val pinned = "A1B2C3D4E5F60718293A4B5C6D7E8F90"
        val shortcutOnly = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"

        // A sheet (e.g. MIUI) that supplies only the standard shortcut id must
        // not create a preselection: FlitEngine ignores that extra, so the
        // activity would otherwise disagree with the engine about the target.
        val shortcutIdOnly = ShareTargetExtras(peerFp = null, shortcutId = pinned)
        assertNull(shortcutIdOnly.preselection())
        assertNull(preselectPeerFp(shortcutIdOnly.preselection()) { it == pinned })

        // Both extras present: the shortcut id still never becomes the source.
        val both = ShareTargetExtras(peerFp = null, shortcutId = shortcutOnly)
        assertNull(preselectPeerFp(both.preselection()) { it == shortcutOnly })

        // The pinned EXTRA_PEER_FP remains the only preselection source.
        assertEquals(pinned, ShareTargetExtras(peerFp = pinned, shortcutId = shortcutOnly).preselection())
        assertEquals(
            pinned,
            preselectPeerFp(
                ShareTargetExtras(peerFp = pinned, shortcutId = shortcutOnly).preselection(),
            ) { it == pinned },
        )
    }
}
