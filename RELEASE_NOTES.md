# Flit — release notes

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
