package app.flit.share

import app.flit.share.protocol.circle.CircleAuthority
import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.model.CircleRequest
import app.flit.share.protocol.model.CircleResponse
import app.flit.share.protocol.tls.TlsIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import java.security.SecureRandom
import java.util.Base64
import java.util.LinkedHashSet
import java.util.Locale

/**
 * Server-side Circle host: the state machine the app runs when a peer JOINs or
 * SYNCs with it. The same decisions the box harness host makes, but expressed
 * as two pure functions over an explicitly supplied [CircleState] so the engine
 * can keep the durable log in [FlitCircleStore] instead of a private copy.
 *
 * Wiring (orchestrator):
 * ```
 * val host = FlitCircleHost(identity, invite, st.keyB64)
 * val answer = host.onRequest(req, st)
 * _circleState.value = host.accept(answer, st)   // persist on ok
 * return answer
 * ```
 * [accept] must be applied and saved for the gating/rekey decisions to stick.
 *
 * Purity: neither function mutates [CircleState], reads the clock or touches
 * Android. The two deliberate process-level side effects are [CircleCore.verify]'s
 * certificate pinning (the trust anchor) and [CircleCore.accept]'s bounded,
 * synchronized id reservation (the anti-replay guard, called only after an
 * event has passed every structural and signature check). Circle state itself
 * stays the single source of truth: log-id dedup is a second, restart-safe layer.
 * Departure rekeying mints one fresh random key (as `CircleCore.create` mints
 * random ids and `FlitCircleSync.rekey` mints keys), so "deterministic" means the
 * decision for a given input is reproducible; the key material itself is
 * deliberately not derivable by a departed member.
 */
class FlitCircleHost(
    private val identity: TlsIdentity,
    private val invite: String,
    private val keyB64: String,
) {
    companion object {
        /** Maximum roster size; a bearer invite is not a licence to grow it forever. */
        const val MAX_MEMBERS: Int = 500

        /** Defensive body bound, independent of the transport's own frame limit. */
        const val MAX_BODY_CHARS: Int = 8 * 1024
    }

    /**
     * Decide what to answer. [current] is the host's last saved state and is
     * never modified. Refusals are honest, human-readable 200 reasons.
     */
    fun onRequest(req: CircleRequest, current: CircleState): CircleResponse {
        if (req.circleId != current.id) return CircleResponse(false, reason = "wrong circle")
        return when (req.action.trim().lowercase(Locale.ROOT)) {
            CircleRequest.ACTION_JOIN -> onJoin(req, current)
            CircleRequest.ACTION_SYNC -> onSync(req, current)
            else -> CircleResponse(false, reason = "unknown action")
        }
    }

    /**
     * Fold a successful answer back into local state. Used both by the host to
     * persist what it just served and by a joining peer to adopt the host's
     * event log. A refusal changes nothing.
     *
     * [CircleResponse] has no circle id field, so callers must pair an answer
     * with the state it was produced from; [onRequest] already enforces that
     * on the host side.
     */
    fun accept(result: CircleResponse, current: CircleState): CircleState {
        if (!result.ok) return current
        val folded = FlitCircleStore.merge(current, result.events)
        val key = result.key.takeIf { it.isNotBlank() } ?: folded.keyB64
        if (result.members.isEmpty()) return folded.copy(keyB64 = key)

        // The host roster is authoritative. Departures normally arrive as
        // signed events (already folded); the roster is what adds JOIN members,
        // whose own join event cannot be forged by the host. A removal that was
        // not carried by an event still advances the generation, so an old key
        // is never silently reused.
        val hostFps = LinkedHashSet(result.members.mapNotNull { normalized(it) })
        val kept = folded.members.filter { normalized(it.fp) in hostFps }
        val present = kept.mapNotNull { normalized(it.fp) }.toSet()
        val added = (hostFps - present).map { fp ->
            FlitCircleStore.Member(
                fp = fp,
                alias = "",
                role = FlitCircleStore.Role.MEMBER,
                joinedMs = 0L,
            )
        }
        return folded.copy(
            keyB64 = key,
            members = kept + added,
            rekeyGen = if (kept.size != folded.members.size) folded.rekeyGen + 1 else folded.rekeyGen,
        )
    }

    /**
     * Log-hygiene seam for the engine: true for a refusal or an answer that
     * actually changes state (new event, roster, key or generation). A plain
     * sync that only echoes the existing log is false — that is the noisy
     * diagnostic path the engine may stay silent about. Pure: no logging here.
     */
    fun isEvidence(result: CircleResponse, current: CircleState): Boolean {
        if (!result.ok) return true
        return accept(result, current) != current
    }

    // ==== decisions =============================================================

    /** JOIN is the one action exempt from the roster gate, but not from the invite. */
    private fun onJoin(req: CircleRequest, current: CircleState): CircleResponse {
        val presented = req.invite?.trim()
        if (invite.isBlank() || presented.isNullOrBlank() ||
            !invite.equals(presented, ignoreCase = true)
        ) return CircleResponse(false, reason = "bad invite")

        val fp = normalized(req.memberFp)
            ?: return CircleResponse(false, reason = "bad member fingerprint")
        val key = current.keyB64.takeIf { it.isNotBlank() } ?: keyB64
        if (key.isBlank()) return CircleResponse(false, reason = "circle has no key")

        val roster = LinkedHashSet(current.members.mapNotNull { normalized(it.fp) })
        if (fp !in roster && roster.size >= MAX_MEMBERS) {
            return CircleResponse(false, reason = "circle full")
        }
        roster.add(fp)
        // No synthetic join event: only the joining device can sign its own
        // join. Its roster entry travels in `members` and is adopted by accept().
        return CircleResponse(
            ok = true,
            key = key,
            members = roster.toList(),
            events = CircleCore.order(current.events),
        )
    }

    /** SYNC is roster-gated; every accepted event is verified and folded. */
    private fun onSync(req: CircleRequest, current: CircleState): CircleResponse {
        val fp = normalized(req.memberFp)
            ?: return CircleResponse(false, reason = "not-a-member")
        val before = current.members.mapNotNull { normalized(it.fp) }.toSet()
        if (fp !in before) return CircleResponse(false, reason = "not-a-member")

        // Verify before any reducer sees an event. CircleCore.verify pins the
        // presenter's cert under its own fingerprint and requires that
        // fingerprint to equal authorFp, so a claimed identity cannot borrow
        // another member's pin. Wrong-circle, unknown and oversized events are
        // skipped (CircleResponse has one reason slot only). Two dedup layers
        // follow: the current log's ids (restart-safe) and CircleCore's bounded
        // accept-memory, which still remembers an id after it rolls out of the
        // 500-event cap. accept() runs LAST so a forged event cannot reserve a
        // legitimate id before its signature is checked.
        val ids = HashSet(current.events.map { it.id })
        val roleOf = current.members.associate {
            it.fp.trim().uppercase(Locale.ROOT) to it.role.name
        }
        val admitted = req.events.asSequence()
            .filter { it.circleId == current.id }
            .filter { it.type.trim().lowercase(Locale.ROOT) in CircleEvent.TYPES }
            .filter { it.body.length <= MAX_BODY_CHARS }
            .filter { it.type != CircleEvent.FILE || CircleCore.validFileBody(it.body) }
            // A receipt is a signed assertion with an exact, closed body of
            // {dropId, sha256, keyId}. CircleCore owns that key set and its
            // bounds; route the verb there so a malformed receipt cannot enter
            // the log through SYNC. No other gate or verb changes.
            .filter { it.type != CircleEvent.RECEIPT || CircleCore.validReceiptBody(it.body) }
            // The cert header binds the REQUESTER to fp; every event it
            // contributes must be authored by that same fingerprint. A
            // self-signed sockpuppet cannot enter through SYNC.
            .filter { normalized(it.authorFp) == fp }
            // JOIN is a host-routed action, not something a member injects;
            // REKEY needs an OWNER/ADMIN author. Both rules live in the shared
            // CircleAuthority gate so the app and the box host decide alike.
            // This stays before signature verification and id reservation:
            // an unauthorised verb never reaches CircleCore.accept.
            .filter { CircleAuthority.foldable(it, current.id, roleOf) }
            .filter { it.cert.isNotBlank() && CircleCore.verify(it) }
            .filter { ids.add(it.id) }
            .filter { CircleCore.accept(it) == CircleCore.Decision.Accept }
            .toList()
            .let { CircleCore.order(it).take(FlitCircleStore.EVENT_CAP) }

        var next = FlitCircleStore.merge(current, admitted)
        val after = next.members.mapNotNull { normalized(it.fp) }.toSet()
        val departed = before.any { it !in after }
        // A departure invalidates the key no matter what else the batch carries.
        // An admitted REKEY may be a departing officer's hand-off (the store
        // vetoes those, but the host cannot rely on every fold site doing so),
        // or a staying officer's key the leaver already knows. Only a fresh
        // host-minted key is safe.
        if (departed) {
            // A REKEY admitted in this batch may have installed a high gen.
            // The forced rotation must outrank it: peers folding the same
            // answer in another order must not treat our key as stale replay.
            // Hand-off attempts by same-batch leavers are excluded — the store
            // refuses them, so they installed nothing.
            val leavers = admitted.asSequence()
                .filter { it.type.equals(CircleEvent.LEAVE, ignoreCase = true) }
                .mapNotNull { normalized(it.authorFp) }
                .toSet()
            val batchKeyGen = admitted.asSequence()
                .filter { it.type.equals(CircleEvent.REKEY, ignoreCase = true) }
                .filterNot { normalized(it.authorFp) in leavers }
                .mapNotNull { rekeyGenOf(it.body) }
                .maxOrNull() ?: Int.MIN_VALUE
            // Saturate: a corrupt MAX_VALUE gen must not wrap negative.
            val floor = if (batchKeyGen == Int.MAX_VALUE) Int.MAX_VALUE else batchKeyGen + 1
            if (floor > next.rekeyGen) next = next.copy(rekeyGen = floor)
            next = rotateKey(next)
        }
        val key = next.keyB64.takeIf { it.isNotBlank() } ?: keyB64
        if (key.isBlank()) return CircleResponse(false, reason = "circle has no key")

        return CircleResponse(
            ok = true,
            // Only the invite-gated JOIN hands out the group key. A SYNC
            // answer never carries it, even to an already-known member.
            key = "",
            members = next.members.mapNotNull { normalized(it.fp) },
            events = CircleCore.order(next.events),
        )
    }

    // ==== rekey =================================================================

    /** Generation requested by a REKEY body, or null when absent/corrupt. */
    private fun rekeyGenOf(body: String): Int? = runCatching {
        val obj = Json.parseToJsonElement(body) as? JsonObject ?: return@runCatching null
        (obj["gen"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: (obj["rekeyGen"] as? JsonPrimitive)?.content?.toIntOrNull()
    }.getOrNull()

    /**
     * A leave or a realised kick invalidates the old key: bump done by the
     * store reducer, fresh key minted here, signed rekey event appended so the
     * remaining members can follow. The rekey sorts after every folded event.
     */
    private fun rotateKey(state: CircleState): CircleState {
        val signer = normalized(identity.fingerprint) ?: return state
        if (state.members.none { m ->
            normalized(m.fp) == signer &&
                m.role in setOf(FlitCircleStore.Role.OWNER, FlitCircleStore.Role.ADMIN)
        }
        ) return state // no current owner/admin to sign with: store would ignore it anyway

        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val fresh = Base64.getEncoder().encodeToString(bytes)
        val gen = state.rekeyGen
        val body = buildJsonObject {
            put("keyB64", JsonPrimitive(fresh))
            put("gen", JsonPrimitive(gen))
            putJsonArray("members") {
                state.members.mapNotNull { normalized(it.fp) }.forEach { add(JsonPrimitive(it)) }
            }
        }.toString()
        val lamport = (state.events.maxOfOrNull { it.lamport } ?: 0L) + 1L
        val event = CircleCore.create(
            state.id,
            identity.fingerprint.trim().uppercase(Locale.ROOT),
            identity,
            CircleEvent.REKEY,
            body,
            lamport = lamport,
        )
        return FlitCircleStore.reduce(state, event)
    }

    /** One canonical fingerprint spelling for roster comparison and responses. */
    private fun normalized(fp: String): String? =
        fp.trim().uppercase(Locale.ROOT).takeIf { it.isNotBlank() && it.length <= 128 }
}
