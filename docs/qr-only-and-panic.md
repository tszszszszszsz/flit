# QR-only mode, known devices and panic

> Part of the Flit transport design. [Main ladder and index](transport-bluetooth.md).
> Hard constraint: no accounts, no cloud, no ads, no telemetry, no SDKs that phone home.

## QR-only, known devices and panic (2026-11-24)

The first batch of the "free will" plan landed: **QR-only mode** (no ambient
announce, scan, manual prober or Wi‑Fi sweep; only QR-paired devices can send),
a **Known devices** store (fingerprint, alias, MAC/relay hints, per-device route
policy: Auto / Wi‑Fi / Bluetooth / Relay), and **panic alerts** — a signed,
hop-counted Flit-only route (`/api/flit/v1/panic`) with a self-signed cert pinned
to the sender fingerprint. A QR v2 code carries the fingerprint plus optional
MAC, relay and token; v1 codes still parse byte-for-byte. Panic is informational:
a recipient sees a banner with fingerprint/alias/time/note and chooses
 Acknowledge / Dismiss / Forward. Nothing is wiped or changed remotely.

**Forward handoff and hop limit (as built).** Forward is an explicit tap only —
nothing auto-forwards. `PanicCore.forward` re-signs the **original** alert with the
forwarder's own identity: `senderFp` becomes this hop's sender, while `originFp`
preserves the original author as the trust anchor (a receiver gates on `originFp`,
so a known intermediary cannot smuggle in an unknown author), and `id` plus the
note survive for dedup. Hops are inside the signed canonical bytes, including the
handoff, so a bumped count cannot be forged; `forward` returns null once
`hops >= DEFAULT_MAX_HOPS` (3), and the UI toasts "alert reached its hop limit — it
cannot be forwarded further" instead of sending.

**Hardware evidence (one phone + the CLI box):** the box was added through the
QR v2 paste path (`scan: pinned Flit (szszszs) (code verified)`), the Known
devices sheet showed its fingerprint with route/forget actions, QR-only logged
`qr-only: no ambient discovery` with the engine rebound, a phone-sent alert was
logged by the box with the sender fingerprint and text, and a box-sent alert
produced the phone banner with alias, note and the safety copy. Two-phone mesh
hops remain a listed limitation.

