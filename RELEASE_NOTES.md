# Flit — release notes

## v0.3.0

* **Circle pad.** A Circle can keep one shared, line-oriented text pad in the
  signed event log. Members put a keyed line, delete a key, and read a derived
  view on the phone or with `flit-cli circle pad put|del|show`. The view is
  rebuilt from the log on every fold; there is no second stored copy and no new
  route or wire field. Per key, the operation with the greatest
  `(lamport, authorFp, id)` wins, so members resolve the same log to the same
  view regardless of delivery order.
* **Signed delivery receipts.** Sender and receiver record a receipt for each
  delivered drop; the app and CLI show the same author tally. Receipts ride the
  existing Circle event path; no new route or status code was added.
* **Per-drop content keys.** Each Circle file drop carries its own content key,
  wrapped to member certificates. A member who leaves no longer unwraps later
  drops; earlier drops keep their bytes.
* **QR air-gap parcels.** A parcel can be encoded into PNG frames and decoded
  from a camera feed without a network between devices. The frame codec and the
  courier carry path stay inside the existing v2.2 wire rules.
* **Courier carry.** An offline member's signed events can travel through a
  second device. Carry is opt-in and bounded by the existing replay window.
* **Small surface additions.** Fingerprint fallback labels use a derived short
  code, pad search runs on the pad card, and the shipped BLE duty cycle is
  checked at start-up as an observation only; a violation is logged and does
  not change carrier behavior.
* Wire protocol remains v2.2. A v0.2.0 peer sees the same routes, headers, DTO
  fields and status codes as before. No account, cloud, advertisement,
  telemetry or phone-home dependency was added.

Counts, commands and the evidence table are in `docs/TESTING.md`. The dated
layer records are under `docs/completions/`.

## v0.2.0

* **Works when Wi‑Fi doesn't.** With no network between the phones (mobile data only,
  dead access point) the roster keeps working: the engine lights Bluetooth discovery at
  exactly that moment, and the same secure v2.2 exchange rides the Bluetooth carrier once
  you turn on **Transfer over Bluetooth** (off by default). The prompts you see are the
  OS's own (nearby-devices permission, radio power), each asked once per device.
* **The "send later" queue keeps its promise end to end:** a second share to an
  already-waiting peer joins that batch instead of failing; a flush broken by the peer
  walking out keeps its row and retries when they're back; completed batches release
  their storage grants.
* **Cache hygiene:** a start-time sweep reclaims disk from staged copies a previous
  process left behind (queue-referenced copies are remembered and kept).
* Harness: `bt-sim` runs a full protocol exchange (both ends one process) through the
  simulated Bluetooth pipe against the production server and client — the
  transport-agnostic regression gate for everything above.
* Same wire protocol v2.2, byte-identical on the Bluetooth pipe; TLS and resume behave
  exactly as over Wi‑Fi — **within one carrier**: the kept prefix is bound to the
  requester's origin (IP over Wi‑Fi, MAC over Bluetooth), so switching carriers takes a
  clean full resend rather than a cross-transport continuation.

> **Dated correction (2026-11-25):** the original v0.2.0 copy said "No new settings, no
> new vocabulary — the prompts you see are the OS's own." That contract was superseded
> on **2026-11-24** by the opt-in **Transfer over Bluetooth** option in Options (off,
> inert by default). It is deliberately a new setting and a new string; discovery-only
> LE still surfaces nothing. Bluetooth is a reachability fallback, not a speed upgrade.

---

## v0.1.0 — (GitHub Releases / Play "What's new")

### Highlights

* **Device-to-device over your own Wi-Fi.** No accounts, no cloud, no cables —
  everything, including discovery metadata, rides mutual TLS with pinned
  fingerprints.
* **Resumable transfers.** An interrupted upload keeps its prefix and continues
  from there when the device is back — hash-exact both ways.
* **Direct-to-storage receive.** Incoming files stream into a pending
  Downloads/Flit row, your chosen default folder, or a per-device folder for
  trusted devices — no temp files, no double copies.
* **Send later.** Send to an offline device and the queue completes it the
  moment it reappears.
* **Text notes** ride the same secure wire as files — from the share sheet or
  the in-app composer.
* **Options that matter:** Material You theme (light/dark/auto), rename your
  device, trusted-senders-only gate, hidden mode, start-on-boot, battery
  opt-out helper.
* **Received ledger:** tap to open, one-tap re-share.
* Quick settings tile, progress notifications with live speed, cross-platform
  via the open protocol v2.2 (compatible with other protocol v2.2 clients).

## Install

- `app-release.apk` — sideload, signed build
- `app-release.aab` — Play upload bundle

Verification: v2/v3-signed with a pinned key (SHA-256 fingerprint in the
F-Droid metadata draft).
