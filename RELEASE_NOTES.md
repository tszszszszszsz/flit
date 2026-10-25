# Flit v0.1.0 — release notes (GitHub Releases / Play "What's new")

## Highlights

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
