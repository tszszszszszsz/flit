package app.flit.share.protocol.panic

import app.flit.share.protocol.model.PanicAlert
import app.flit.share.protocol.tls.TlsIdentity
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.LinkedHashMap
import java.util.UUID

/**
 * The pure decision layer of the Flit panic route. No sockets, no Android,
 * no delegate calls: canonical bytes, a replay window, a FIFO dedup set and
 * a hop budget. Sockets/timeouts live in the client; delivery lives in the
 * server's delegate.
 */
object PanicCore {
    /** An alert may cross at most three relays before it stops propagating. */
    const val DEFAULT_MAX_HOPS = 3

    /** Older alerts are stale replay, not news. */
    const val REPLAY_WINDOW_MS = 6 * 60 * 60 * 1000L

    /** Small future tolerance for two phones' unsynchronised clocks. */
    const val CLOCK_SKEW_MS = 5 * 60 * 1000L

    /** Bounded FIFO memory — recall is not infinite, but neither is a panic flood. */
    const val MAX_SEEN = 1024

    sealed interface Decision {
        /** Signature already checked by the caller; safe to deliver. */
        data object Accept : Decision

        /** Same id seen before: answered politely, never delivered twice. */
        data object Duplicate : Decision

        /** Outside the replay window (too old or implausibly in the future). */
        data object OutsideWindow : Decision

        /** Already relayed [DEFAULT_MAX_HOPS] times: drop, do not forward. */
        data object HopLimit : Decision

        /** Structurally unusable: blank id/fingerprint or negative hops. */
        data object Invalid : Decision
    }

    private val seen = LinkedHashMap<String, Long>()

    /**
     * Deterministic signing input: a domain tag plus length-prefixed UTF-8
     * fields, so no delimiter can be smuggled through a note. Covers every
     * immutable field except [PanicAlert.sig] — hops included, so a relay
     * that bumps the count must re-sign with its own identity.
     */
    fun canonical(alert: PanicAlert): ByteArray =
        canonical(alert.id, alert.senderFp, alert.sentMs, alert.text, alert.hops, alert.originFp)

    /** Explicit-field form, handy for forging a test vector. */
    fun canonical(
        id: String,
        senderFp: String,
        sentMs: Long,
        text: String,
        hops: Int,
        originFp: String = "",
    ): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        field(out, "FLITPANIC1")
        field(out, id)
        field(out, senderFp)
        out.writeLong(sentMs)
        field(out, text)
        out.writeInt(hops)
        field(out, originFp)
        out.flush()
        return bytes.toByteArray()
    }

    private fun field(out: DataOutputStream, value: String) {
        val raw = value.toByteArray(Charsets.UTF_8)
        out.writeInt(raw.size)
        out.write(raw)
    }

    /**
     * Builds and signs a fresh alert. The signer's certificate is pinned
     * under its own fingerprint so a local [verify] works immediately; the
     * route additionally re-pins the certificate it receives on the wire.
     */
    fun create(
        senderFp: String,
        text: String,
        identity: TlsIdentity,
        hops: Int = 0,
    ): PanicAlert {
        require(hops >= 0) { "negative hop count" }
        val fp = senderFp.trim().uppercase()
        require(fp.isNotBlank()) { "blank sender fingerprint" }
        TlsIdentity.pin(identity.certificate)
        val alert = PanicAlert(
            id = UUID.randomUUID().toString(),
            senderFp = fp,
            sentMs = System.currentTimeMillis(),
            text = text,
            hops = hops,
            originFp = fp,
        )
        return alert.copy(sig = identity.signPanic(canonical(alert)))
    }

    /**
     * Signature check only — no dedup, no replay window, no hop policy.
     * Trust is the pinned certificate whose SHA-256 DER equals senderFp.
     */
    fun verify(alert: PanicAlert): Boolean =
        TlsIdentity.verifyPanic(alert.senderFp, canonical(alert), alert.sig)

    /** Pure structural / replay / hop decision; reads dedup memory without mutating it. */
    fun evaluate(
        alert: PanicAlert,
        nowMs: Long = System.currentTimeMillis(),
        maxHops: Int = DEFAULT_MAX_HOPS,
    ): Decision = when {
        alert.id.isBlank() || alert.senderFp.isBlank() || alert.hops < 0 || alert.sentMs <= 0L ->
            Decision.Invalid
        alert.hops >= maxHops -> Decision.HopLimit
        alert.sentMs < nowMs - REPLAY_WINDOW_MS || alert.sentMs > nowMs + CLOCK_SKEW_MS ->
            Decision.OutsideWindow
        synchronized(seen) { alert.id in seen } -> Decision.Duplicate
        else -> Decision.Accept
    }

    /**
     * Atomic check-and-record: exactly one racing delivery of an id wins.
     * Only an [Decision.Accept] marks the id seen.
     */
    fun accept(
        alert: PanicAlert,
        nowMs: Long = System.currentTimeMillis(),
        maxHops: Int = DEFAULT_MAX_HOPS,
    ): Decision = synchronized(seen) {
        when (val d = evaluate(alert, nowMs, maxHops)) {
            Decision.Accept -> if (freshLocked(alert.id, nowMs)) Decision.Accept else Decision.Duplicate
            else -> d
        }
    }

    /** Tiny FIFO dedup API: true only the first time an id is presented. */
    fun fresh(id: String, nowMs: Long = System.currentTimeMillis()): Boolean = synchronized(seen) {
        freshLocked(id, nowMs)
    }

    private fun freshLocked(id: String, nowMs: Long): Boolean {
        if (id.isBlank() || id in seen) return false
        seen[id] = nowMs
        while (seen.size > MAX_SEEN) {
            val oldest = seen.entries.iterator().next()
            seen.remove(oldest.key)
        }
        return true
    }

    /**
     * The next hop, or null when the budget is spent. Hops and senderFp are
     * both signed, so the relay becomes this hop's sender and re-signs with
     * its own identity; the original note and id are preserved for dedup.
     */
    fun forward(
        alert: PanicAlert,
        forwarderFp: String,
        identity: TlsIdentity,
        maxHops: Int = DEFAULT_MAX_HOPS,
    ): PanicAlert? {
        val nextHops = alert.hops + 1
        if (nextHops >= maxHops) return null
        val fp = forwarderFp.trim().uppercase()
        require(fp.isNotBlank()) { "blank forwarder fingerprint" }
        TlsIdentity.pin(identity.certificate)
        val next = alert.copy(
            senderFp = fp,
            hops = nextHops,
            sig = "",
            originFp = alert.originFp.ifBlank { alert.senderFp },
        )
        return next.copy(sig = identity.signPanic(canonical(next)))
    }

    /** Test/lifecycle hook: forget every remembered id. */
    fun clear() = synchronized(seen) { seen.clear() }
}
