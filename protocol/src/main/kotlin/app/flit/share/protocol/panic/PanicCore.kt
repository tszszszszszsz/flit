package app.flit.share.protocol.panic

import app.flit.share.protocol.model.PanicAlert
import app.flit.share.protocol.tls.TlsIdentity
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
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

    /** Largest accepted note; matches the send-side trim in the app. */
    const val MAX_TEXT_CHARS = 240

    /** Largest accepted id. */
    const val MAX_ID_CHARS = 256

    /** Largest accepted sender/origin fingerprint. */
    const val MAX_FP_CHARS = 256

    /** Base64 signature cap (a 2048-bit RSA signature is ~344 chars). */
    const val MAX_SIG_CHARS = 1024

    /** Base64 DER cap (a self-signed 2048-bit cert is ~1.2 KiB). */
    const val MAX_CERT_CHARS = 8 * 1024

    sealed interface Decision {
        /** Signature already checked by the caller; safe to deliver. */
        data object Accept : Decision

        /** Same id seen before: answered politely, never delivered twice. */
        data object Duplicate : Decision

        /** Outside the replay window (too old or implausibly in the future). */
        data object OutsideWindow : Decision

        /** Already relayed [DEFAULT_MAX_HOPS] times: drop, do not forward. */
        data object HopLimit : Decision

        /** Structurally unusable: blank/oversized fields or non-positive clock. */
        data object Invalid : Decision
    }

    private val seen = LinkedHashMap<String, Long>()

    /**
     * Deterministic signing input: a domain tag plus length-prefixed UTF-8
     * fields, so no delimiter can be smuggled through a note. Covers every
     * immutable field except the signature/certificate fields ([PanicAlert.sig],
     * [PanicAlert.originSig], [PanicAlert.originCert]) — hops included, so a
     * relay that bumps the count must re-sign with its own identity.
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
     * Builds and signs a fresh alert. At hop zero the sender is also the
     * origin, so [PanicAlert.sig] and [PanicAlert.originSig] are the same
     * signature over the same canonical bytes and [PanicAlert.originCert] is
     * the origin's DER certificate. Relays re-sign only [PanicAlert.sig] and
     * carry the origin pair through unchanged. The signer's certificate is
     * pinned under its own fingerprint so a local [verify] works immediately;
     * the route additionally re-pins the certificate it receives on the wire.
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
        require(fp.length <= MAX_FP_CHARS) { "sender fingerprint too long" }
        TlsIdentity.pin(identity.certificate)
        val alert = PanicAlert(
            id = UUID.randomUUID().toString(),
            senderFp = fp,
            sentMs = System.currentTimeMillis(),
            text = text,
            hops = hops,
            originFp = fp,
        )
        val signed = alert.copy(sig = identity.signPanic(canonical(alert)))
        return signed.copy(
            originSig = signed.sig,
            originCert = Base64.getEncoder().encodeToString(identity.certificate.encoded),
        )
    }

    /**
     * Signature check only — no dedup, no replay window, no hop policy.
     * Trust is the pinned certificate whose SHA-256 DER equals senderFp.
     */
    fun verify(alert: PanicAlert): Boolean {
        if (!validShape(alert) || alert.sig.isBlank() || alert.sig.length > MAX_SIG_CHARS) return false
        return TlsIdentity.verifyPanic(alert.senderFp, canonical(alert), alert.sig)
    }

    /**
     * Pure structural gate shared by the route, [verify], [verifyOrigin] and
     * [evaluate]. No replay state and no crypto: only the bounds a well-formed
     * alert obeys. It deliberately does not require originSig/originCert to be
     * present — those are additive fields and older alerts may omit them — but
     * it caps them so an oversized value can never reach a parser or a UI.
     */
    fun validShape(alert: PanicAlert): Boolean =
        alert.id.isNotBlank() && alert.id.length <= MAX_ID_CHARS &&
            alert.senderFp.isNotBlank() && alert.senderFp.length <= MAX_FP_CHARS &&
            alert.originFp.length <= MAX_FP_CHARS &&
            alert.sentMs > 0L && alert.hops >= 0 &&
            alert.text.length <= MAX_TEXT_CHARS &&
            alert.sig.length <= MAX_SIG_CHARS &&
            alert.originSig.length <= MAX_SIG_CHARS &&
            alert.originCert.length <= MAX_CERT_CHARS

    /**
     * Binds [PanicAlert.originFp] to the key that signed the ORIGINAL hop-zero
     * alert. The certificate travels inside the alert, so no prior pin is
     * needed: its OWN SHA-256 DER must equal originFp, and originSig must
     * verify with that certificate over exactly the hop-zero canonical
     * (senderFp = originFp, hops = 0). Relays copy both fields unchanged while
     * re-signing only [PanicAlert.sig], so forwarded alerts still verify here;
     * a relay that rewrites hops or re-signs its own origin claim is refused.
     *
     * A legacy alert with a blank originFp has no origin binding: the
     * documented fallback is the verified sender (the route and [forward]
     * treat senderFp as the origin), so it is accepted only when [verify]
     * passes. Total: never throws on bad input.
     */
    fun verifyOrigin(alert: PanicAlert): Boolean {
        if (alert.originFp.isBlank()) return verify(alert)
        if (alert.originSig.isBlank() || alert.originCert.isBlank()) return false
        if (alert.originFp.length > MAX_FP_CHARS ||
            alert.originSig.length > MAX_SIG_CHARS ||
            alert.originCert.length > MAX_CERT_CHARS
        ) return false
        val cert = runCatching {
            CertificateFactory.getInstance("X.509").generateCertificate(
                Base64.getDecoder().decode(alert.originCert.trim()).inputStream(),
            ) as? X509Certificate
        }.getOrNull() ?: return false
        val fp = runCatching { TlsIdentity.fingerprintOf(cert) }.getOrNull() ?: return false
        if (!fp.equals(alert.originFp.trim(), ignoreCase = true)) return false

        // The origin signed this exact byte string before any relay moved a
        // hop. Rebuilding it from the origin fields keeps it stable across
        // forwards; no other hop count is an origin signature.
        val hopZero = canonical(alert.id, alert.originFp, alert.sentMs, alert.text, 0, alert.originFp)
        return runCatching { TlsIdentity.verifyWithCertificate(cert, hopZero, alert.originSig) }
            .getOrElse { false }
    }

    /** Pure structural / replay / hop decision; reads dedup memory without mutating it. */
    fun evaluate(
        alert: PanicAlert,
        nowMs: Long = System.currentTimeMillis(),
        maxHops: Int = DEFAULT_MAX_HOPS,
    ): Decision = when {
        !validShape(alert) -> Decision.Invalid
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
     * its own identity; the original note and id are preserved for dedup, as
     * are the origin's signature and certificate so [verifyOrigin] still works.
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
            // Signed by the origin, not by this relay: carried through verbatim.
            originSig = alert.originSig,
            originCert = alert.originCert,
        )
        return next.copy(sig = identity.signPanic(canonical(next)))
    }

    /** Test/lifecycle hook: forget every remembered id. */
    fun clear() = synchronized(seen) { seen.clear() }
}
