package app.flit.share

import java.util.Locale

/**
 * Android-free copy and state helpers for the Circle file card's courier line.
 *
 * Split out like [SheetUi] so the JVM test suite can pin the states without an
 * Android context. This file never parses a `parcel` body, never reads envelope
 * text and holds no seen set: it only renders a typed [CourierHandoff] produced
 * by the sync layer, and folds the existing three-state Open affordance.
 *
 * A carry line is courier-local information. It never claims that file bytes
 * landed; only the landed-SHA set produces an Open action.
 */
object CourierCardUi {

    /** Local outcome strings published by the sync worker; never signed or gossiped. */
    const val HANDOFF_WAITING = "waiting"
    const val HANDOFF_HANDED = "handed"
    const val HANDOFF_FAILED = "failed"

    enum class CarryOutcome { WAITING, HANDED, FAILED }

    enum class OpenAffordance { RESOLVING, LANDED, ABSENT }

    /**
     * Typed projection of one courier copy, keyed by the SHA-256 of a `file` event.
     * [name], [size] and [fromFp] travel with the entry for callers, but no copy
     * drawn by [courierLine] reads them.
     */
    data class CourierHandoff(
        val sha256: String,
        val name: String,
        val size: Long,
        val toFp: String,
        val courierFp: String,
        val fromFp: String,
        val outcome: String,
    )

    /** Hashes and fingerprints compare after a trim and a root-locale fold. */
    private fun fold(value: String): String = value.trim().lowercase(Locale.ROOT)

    /**
     * Outcomes are taken verbatim: only the exact lowercase values written by
     * the sync layer count. Blank, mixed-case and unknown values read as
     * [CarryOutcome.WAITING], so a throw or a refusal can never produce the
     * "handed off" copy on the strength of an unrecognised value.
     */
    fun carryOutcome(raw: String?): CarryOutcome = when (raw) {
        CourierCardUi.HANDOFF_HANDED -> CarryOutcome.HANDED
        CourierCardUi.HANDOFF_FAILED -> CarryOutcome.FAILED
        else -> CarryOutcome.WAITING
    }

    /**
     * Bounded local label, the same rule `FeedRow` uses for authors: a known
     * member's alias, capped at [FlitCircleStore.MAX_ALIAS_CHARS], otherwise the
     * first eight characters of the fingerprint.
     */
    fun aliasOf(fp: String, members: List<FlitCircleStore.Member>): String {
        val key = fold(fp)
        val alias = members.firstOrNull { fold(it.fp) == key }?.alias
        return if (alias.isNullOrBlank()) key.take(8)
        else alias.take(FlitCircleStore.MAX_ALIAS_CHARS)
    }

    /**
     * One line per handoff, or null when there is no entry or the entry has a
     * blank SHA-256. The courier's local outcome is used only on the courier's
     * own device; a recipient or a third member reads a device-independent line.
     * Only [CourierHandoff.toFp], [CourierHandoff.courierFp] and
     * [CourierHandoff.outcome] reach the copy.
     */
    fun courierLine(
        handoff: CourierHandoff?,
        meFp: String,
        members: List<FlitCircleStore.Member>,
    ): String? {
        if (handoff == null || handoff.sha256.isBlank()) return null
        val me = fold(meFp)
        val courier = fold(handoff.courierFp)
        val to = fold(handoff.toFp)
        val toLabel = aliasOf(handoff.toFp, members)
        return when {
            me.isNotBlank() && me == courier -> when (carryOutcome(handoff.outcome)) {
                CarryOutcome.WAITING -> "Carrying for $toLabel · waiting for a handoff"
                CarryOutcome.FAILED -> "Carrying for $toLabel · handoff will retry"
                CarryOutcome.HANDED -> "Handed off to $toLabel"
            }
            me.isNotBlank() && me == to -> "A courier carried a copy for you"
            else -> "${aliasOf(handoff.courierFp, members)} is carrying a copy for $toLabel"
        }
    }

    /**
     * The three states extracted from `FileCard` without changing them: a
     * non-blank opening hash matching this SHA resolves first, then a landed
     * hash, then absence. Every comparison folds case and surrounding space.
     */
    fun openAffordance(sha: String, landed: Set<String>, opening: String?): OpenAffordance {
        val key = fold(sha)
        val openingKey = opening?.let(::fold)?.takeIf { it.isNotBlank() }
        return when {
            openingKey != null && openingKey == key -> OpenAffordance.RESOLVING
            key.isNotBlank() && landed.any { fold(it) == key } -> OpenAffordance.LANDED
            else -> OpenAffordance.ABSENT
        }
    }
}

// Same-package conveniences for callers that use the bare names. They resolve
// to the object above and introduce no second set of values.
typealias CarryOutcome = CourierCardUi.CarryOutcome
typealias OpenAffordance = CourierCardUi.OpenAffordance
typealias CourierHandoff = CourierCardUi.CourierHandoff

const val HANDOFF_WAITING = CourierCardUi.HANDOFF_WAITING
const val HANDOFF_HANDED = CourierCardUi.HANDOFF_HANDED
const val HANDOFF_FAILED = CourierCardUi.HANDOFF_FAILED

fun carryOutcome(raw: String?): CarryOutcome = CourierCardUi.carryOutcome(raw)

fun courierLine(
    handoff: CourierHandoff?,
    meFp: String,
    members: List<FlitCircleStore.Member>,
): String? = CourierCardUi.courierLine(handoff, meFp, members)

fun openAffordance(sha: String, landed: Set<String>, opening: String?): OpenAffordance =
    CourierCardUi.openAffordance(sha, landed, opening)

fun aliasOf(fp: String, members: List<FlitCircleStore.Member>): String =
    CourierCardUi.aliasOf(fp, members)
