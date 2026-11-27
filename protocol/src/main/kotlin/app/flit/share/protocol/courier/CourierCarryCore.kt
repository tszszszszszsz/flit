package app.flit.share.protocol.courier

import app.flit.share.protocol.circle.CircleCore
import app.flit.share.protocol.circle.CircleEvent
import app.flit.share.protocol.model.WireJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.LinkedHashMap

/**
 * Largest bounded carrier bag. Trimming drops the earliest parcels by [order].
 */
const val MAX_PARCELS = 256

/** Bounded id reservation shared by [evaluate] and [fresh]. */
const val MAX_SEEN = 4096

/** Ceiling for an undelivered parcel: `expiresMs = createdMs + MAX_PARCEL_TTL_MS`. */
const val MAX_PARCEL_TTL_MS = 24 * 60 * 60 * 1000L

/** Bag-digest domain tag. This is not a second envelope signature. */
private const val CARRY_DOMAIN = "FLITCARRY1"

/** One row of a carrier's bag: a signed `parcel` event plus its derived expiry. */
@Serializable
data class CourierParcel(
    val id: String,
    val toFp: String,
    val fromFp: String,
    val createdMs: Long,
    val expiresMs: Long,
    val event: CircleEvent,
)

/** The caller-owned durable bag. The core owns only the in-process [seen] set. */
@Serializable
data class CarryState(val parcels: List<CourierParcel> = emptyList())

/** Exact precedence: Invalid → OutsideWindow → Expired → Duplicate → Full → Accept. */
sealed interface Decision {
    /** Id reserved; the parcel may enter the bag. */
    data object Accept : Decision

    /** Id already reserved by [evaluate] or [fresh]. Not recorded again. */
    data object Duplicate : Decision

    /** `nowMs >= expiresMs`. Not recorded. */
    data object Expired : Decision

    /** createdMs outside the replay/skew window. Not recorded. */
    data object OutsideWindow : Decision

    /** Shape or body failed. Not recorded. */
    data object Invalid : Decision

    /** Bag at [MAX_PARCELS]. Not recorded; a later offer can succeed. */
    data object Full : Decision
}

/** The one bounded, synchronized reservation set. */
private val seen = LinkedHashMap<String, Long>()

/**
 * Closed-key gate for a `parcel` body: exactly `"toFp"` and `"envelope"`, both
 * non-blank strings, with `toFp` bounded by [CircleCore.MAX_FIELD_CHARS] and the
 * Base64 envelope by `MAX_ENVELOPE_CHARS`. An extra key is refused before any
 * id is reserved.
 */
fun validParcelBody(body: String): Boolean {
    if (body.isBlank() || body.length > CircleCore.MAX_BODY_CHARS) return false
    val obj = runCatching { WireJson.json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        ?: return false
    if (obj.keys != PARCEL_BODY_KEYS) return false
    val toFp = obj["toFp"] as? JsonPrimitive ?: return false
    val envelope = obj["envelope"] as? JsonPrimitive ?: return false
    if (!toFp.isString || !envelope.isString) return false
    val fp = toFp.content.takeIf { it.isNotBlank() } ?: return false
    val blob = envelope.content.takeIf { it.isNotBlank() } ?: return false
    return fp.length <= CircleCore.MAX_FIELD_CHARS && blob.length <= MAX_ENVELOPE_CHARS
}

/**
 * Builds a bag row from a signed `parcel` event, or null if the event is not a
 * valid parcel. `toFp` is normalised to uppercase; `expiresMs` is derived here
 * and nowhere else, so competing expiry values cannot disagree.
 */
fun parcelOf(event: CircleEvent): CourierParcel? {
    if (event.type != PARCEL) return null
    if (!CircleCore.validShape(event)) return null
    if (event.cert.isBlank()) return null
    if (!validParcelBody(event.body)) return null
    if (event.createdMs > Long.MAX_VALUE - MAX_PARCEL_TTL_MS) return null
    val obj = WireJson.json.parseToJsonElement(event.body) as? JsonObject ?: return null
    val toFp = (obj["toFp"] as? JsonPrimitive)?.content?.trim()?.uppercase() ?: return null
    if (toFp.isBlank() || toFp.length > CircleCore.MAX_FIELD_CHARS) return null
    return CourierParcel(
        id = event.id,
        toFp = toFp,
        fromFp = event.authorFp,
        createdMs = event.createdMs,
        expiresMs = event.createdMs + MAX_PARCEL_TTL_MS,
        event = event,
    )
}

/**
 * Deterministic bag digest input: the domain tag and length-prefixed UTF-8
 * fields, then both timestamps and the opaque envelope string. `event.sig` and
 * `event.cert` are excluded. Used for carry-state digests and later receipts;
 * it is not a signature.
 */
fun canonical(p: CourierParcel): ByteArray {
    val bytes = ByteArrayOutputStream()
    val out = DataOutputStream(bytes)
    field(out, CARRY_DOMAIN)
    field(out, p.id)
    field(out, p.toFp)
    field(out, p.fromFp)
    out.writeLong(p.createdMs)
    out.writeLong(p.expiresMs)
    field(out, envelopeOf(p.event))
    out.flush()
    return bytes.toByteArray()
}

private fun envelopeOf(event: CircleEvent): String {
    if (event.type != PARCEL) return ""
    val obj = runCatching { WireJson.json.parseToJsonElement(event.body) as? JsonObject }.getOrNull()
    return (obj?.get("envelope") as? JsonPrimitive)?.content ?: ""
}

private fun field(out: DataOutputStream, value: String) {
    val raw = value.toByteArray(Charsets.UTF_8)
    out.writeInt(raw.size)
    out.write(raw)
}

/**
 * Arrival-order-independent total order: `(createdMs, fromFp, id, …event
 * fields)`. The signed-field tie-break delegates to [CircleCore.order] on the
 * underlying events, so two carriers converge on the same bag.
 */
fun order(parcels: Iterable<CourierParcel>): List<CourierParcel> {
    val primary = compareBy<CourierParcel> { it.createdMs }
        .thenBy { it.fromFp }
        .thenBy { it.id }
    return parcels.sortedWith(Comparator { a, b ->
        val c = primary.compare(a, b)
        when {
            c != 0 -> c
            a.event === b.event || a.event == b.event -> 0
            CircleCore.order(listOf(a.event, b.event))[0] === a.event -> -1
            else -> 1
        }
    })
}

/**
 * Atomic check-and-record over the shared bounded [seen] set. Precedence is
 * exact: Invalid → Expired → OutsideWindow → Duplicate → Full → Accept. Only
 * Accept reserves an id, so a Full or OutsideWindow offer can be retried.
 * Expiry is checked before the replay window so a TTL-elapsed parcel is
 * reported as Expired rather than shadowed by the shorter window.
 *
 * The window compares two positive timestamps by subtraction, never
 * `nowMs ± window`, so an extreme clock cannot overflow into a false Accept.
 * Callers verify with [CircleCore.verify] before calling this.
 */
fun evaluate(parcel: CourierParcel, bagSize: Int, nowMs: Long): Decision {
    if (invalid(parcel)) return Decision.Invalid
    if (nowMs >= parcel.expiresMs) return Decision.Expired
    if (!withinWindow(parcel.createdMs, nowMs)) return Decision.OutsideWindow
    synchronized(seen) {
        if (parcel.id in seen) return Decision.Duplicate
        if (bagSize >= MAX_PARCELS) return Decision.Full
        seen[parcel.id] = nowMs
        while (seen.size > MAX_SEEN) {
            seen.remove(seen.entries.iterator().next().key)
        }
    }
    return Decision.Accept
}

private fun invalid(p: CourierParcel): Boolean {
    if (p.id.isBlank() || p.id.length > CircleCore.MAX_FIELD_CHARS) return true
    if (p.toFp.isBlank() || p.toFp.length > CircleCore.MAX_FIELD_CHARS) return true
    if (p.fromFp.isBlank() || p.fromFp.length > CircleCore.MAX_FIELD_CHARS) return true
    if (p.createdMs <= 0L || p.createdMs > Long.MAX_VALUE - MAX_PARCEL_TTL_MS) return true
    if (p.expiresMs != p.createdMs + MAX_PARCEL_TTL_MS) return true
    if (p.event.type != PARCEL) return true
    if (!CircleCore.validShape(p.event)) return true
    return !validParcelBody(p.event.body)
}

private fun withinWindow(createdMs: Long, nowMs: Long): Boolean =
    nowMs > 0L &&
        nowMs - createdMs <= CircleCore.REPLAY_WINDOW_MS &&
        createdMs - nowMs <= CircleCore.CLOCK_SKEW_MS

/**
 * Id-only reservation on the same set as [evaluate]: true only the first time
 * an id is presented. Use for caller-owned ids that have no signed event yet.
 */
fun fresh(id: String, nowMs: Long): Boolean = synchronized(seen) {
    if (id.isBlank() || id.length > CircleCore.MAX_FIELD_CHARS || id in seen) {
        return@synchronized false
    }
    seen[id] = nowMs
    while (seen.size > MAX_SEEN) {
        seen.remove(seen.entries.iterator().next().key)
    }
    true
}

/** Append when the id is absent, then trim to [MAX_PARCELS] by [order]. */
fun fold(state: CarryState, parcel: CourierParcel): CarryState {
    if (parcel.id.isBlank()) return state
    val next = if (state.parcels.any { it.id == parcel.id }) state.parcels else state.parcels + parcel
    return CarryState(order(next).takeLast(MAX_PARCELS))
}

/** Live parcels addressed to the normalised [toFp], deterministically ordered. */
fun select(
    state: CarryState,
    toFp: String,
    nowMs: Long,
    limit: Int = MAX_PARCELS,
): List<CourierParcel> {
    val target = toFp.trim().uppercase()
    if (target.isBlank() || limit <= 0) return emptyList()
    return order(state.parcels)
        .filter { it.toFp == target && nowMs < it.expiresMs }
        .take(limit)
}

/** Remove by id; unknown ids are inert and the remaining rows are untouched. */
fun ack(state: CarryState, ids: Collection<String>): CarryState {
    if (ids.isEmpty()) return state
    val drop = ids.toSet()
    val kept = state.parcels.filter { it.id !in drop }
    return if (kept.size == state.parcels.size) state else CarryState(kept)
}

/** Drop parcels whose `nowMs >= expiresMs`; the rest are unchanged. */
fun expire(state: CarryState, nowMs: Long): CarryState {
    val kept = state.parcels.filter { nowMs < it.expiresMs }
    return if (kept.size == state.parcels.size) state else CarryState(kept)
}

/**
 * Deterministic, bounded union. Duplicate ids are resolved by [CircleCore.dedup]
 * on the underlying events, so both arrival orders pick the same winner. Live
 * rows are then trimmed by [order]. Idempotent and commutative.
 */
fun merge(a: CarryState, b: CarryState, nowMs: Long): CarryState {
    val all = a.parcels + b.parcels
    if (all.isEmpty()) return CarryState()
    val winners = CircleCore.dedup(all.map { it.event })
    val chosen = LinkedHashMap<String, CourierParcel>()
    for (event in winners) {
        val parcel = all.firstOrNull { it.event === event || it.event == event } ?: continue
        chosen[parcel.id] = parcel
    }
    val live = chosen.values.filter { nowMs < it.expiresMs }
    return CarryState(order(live).takeLast(MAX_PARCELS))
}

/** Test/lifecycle hook: forget every reserved id. */
fun clear() = synchronized(seen) { seen.clear() }

/** Wire form of a durable bag. */
fun encode(state: CarryState): String = WireJson.encode(state)

/**
 * Tolerant, normalising decode. Blank ids/`toFp`, duplicate ids and structurally
 * expired rows are dropped; a malformed expiry is floored to
 * `createdMs + MAX_PARCEL_TTL_MS`; the result is trimmed to [MAX_PARCELS]. A
 * corrupt blob yields an empty state, never an exception. Wall-clock expiry is
 * applied by [expire] at the caller, which supplies `nowMs`.
 */
fun decode(raw: String?): CarryState? {
    if (raw.isNullOrBlank()) return CarryState()
    val parsed = runCatching { WireJson.decode<CarryState>(raw) }.getOrNull() ?: return CarryState()
    val ids = HashSet<String>()
    val rows = ArrayList<CourierParcel>()
    for (p in parsed.parcels) {
        if (p.id.isBlank() || p.id.length > CircleCore.MAX_FIELD_CHARS) continue
        if (p.toFp.isBlank() || p.toFp.length > CircleCore.MAX_FIELD_CHARS) continue
        if (!ids.add(p.id)) continue
        if (p.createdMs <= 0L || p.createdMs > Long.MAX_VALUE - MAX_PARCEL_TTL_MS) continue
        if (p.expiresMs <= p.createdMs) continue // structurally expired row
        val canonicalExpiry = p.createdMs + MAX_PARCEL_TTL_MS
        rows.add(if (p.expiresMs == canonicalExpiry) p else p.copy(expiresMs = canonicalExpiry))
    }
    return CarryState(order(rows).takeLast(MAX_PARCELS))
}

/**
 * Object-style entry points over the same implementation, for callers that
 * name the core explicitly. Both forms share one [seen] set and one codec.
 */
object CourierCarryCore {
    const val MAX_PARCELS: Int = 256
    const val MAX_SEEN: Int = 4096
    const val MAX_PARCEL_TTL_MS: Long = 24L * 60 * 60 * 1000

    fun validParcelBody(body: String): Boolean =
        app.flit.share.protocol.courier.validParcelBody(body)

    fun parcelOf(event: CircleEvent): CourierParcel? =
        app.flit.share.protocol.courier.parcelOf(event)

    fun canonical(p: CourierParcel): ByteArray =
        app.flit.share.protocol.courier.canonical(p)

    fun order(parcels: Iterable<CourierParcel>): List<CourierParcel> =
        app.flit.share.protocol.courier.order(parcels)

    fun evaluate(parcel: CourierParcel, bagSize: Int, nowMs: Long): Decision =
        app.flit.share.protocol.courier.evaluate(parcel, bagSize, nowMs)

    fun fresh(id: String, nowMs: Long): Boolean =
        app.flit.share.protocol.courier.fresh(id, nowMs)

    fun fold(state: CarryState, parcel: CourierParcel): CarryState =
        app.flit.share.protocol.courier.fold(state, parcel)

    fun select(
        state: CarryState,
        toFp: String,
        nowMs: Long,
        limit: Int = MAX_PARCELS,
    ): List<CourierParcel> = app.flit.share.protocol.courier.select(state, toFp, nowMs, limit)

    fun ack(state: CarryState, ids: Collection<String>): CarryState =
        app.flit.share.protocol.courier.ack(state, ids)

    fun expire(state: CarryState, nowMs: Long): CarryState =
        app.flit.share.protocol.courier.expire(state, nowMs)

    fun merge(a: CarryState, b: CarryState, nowMs: Long): CarryState =
        app.flit.share.protocol.courier.merge(a, b, nowMs)

    fun clear() = app.flit.share.protocol.courier.clear()

    fun encode(state: CarryState): String = app.flit.share.protocol.courier.encode(state)

    fun decode(raw: String?): CarryState? = app.flit.share.protocol.courier.decode(raw)
}
