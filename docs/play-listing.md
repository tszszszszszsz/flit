# Play listing draft (working name "Flit")

## Title (≤30)

Flit — Nearby File Transfer

## Short description (≤80)

Files to nearby devices, no cloud, no cables — over Wi-Fi or Bluetooth.

<!-- verified: 71 chars incl. spaces (limit 80). Title "Flit — Nearby File Transfer"
     is 27 chars (limit 30). Recount exactly before pasting into Play Console. -->

## Full description

Send files to the devices around you — directly, device to device, with no accounts,
no cables, and nothing leaving your network in between.

**How it works**
- Nearby devices speaking the v2.2 protocol appear in one shared list the moment you open
  the app. Tap a device, pick your files, send. Options can narrow that list: **QR-only**
  admits only devices you paired by QR, and **Trusted senders only** admits only devices
  you pinned — both are off by default, so the open list is the default.
- With no Wi-Fi network between the phones at all (mobile data only, a dead access
  point), the roster keeps working: discovery rides Bluetooth at exactly that moment,
  and the transfer does too once you opt in with **Transfer over Bluetooth** (off by
  default) — same list, same speed readout.

**Honest, by design**
- Transfers are end-to-end TLS with per-device identities; every incoming device is
  verified by its fingerprint, not by a name anyone could claim. Trust ("pin") the
  devices you know — by default other nearby v2.2 devices can also send, and
  **Trusted senders only** (or **QR-only**) is the opt-in gate in Options that closes
  the list to devices you already know.
- Send later: a device can stay waiting while you share — the file goes the moment it
  is back. Interrupted transfers resume from where they stopped instead of starting over.
- Received files land straight in your chosen folder (Downloads/Flit by default, or a
  per-device folder), openable with one tap and re-shareable from the received list.
- Text notes ride the same secure wire, from the share sheet or the in-app composer.
- Speaks the open device-to-device protocol (v2.2) used by other open-source transfer
  apps, so it interoperates across Windows, macOS, Linux and iOS — from the share
  sheet in one tap, with a letter-tile visual per peer.

**What we ask, and when**
- Nearby-devices permission and radio-power prompts appear exactly when a transfer needs
  them, as the system's own dialogs, each asked once per device. Nothing asks at launch.
- No tracking, no analytics, no accounts, no ads, no telemetry, no cloud — no
  SDKs that phone home, and nothing uploaded anywhere.

**For the technical**: chunked resumable uploads (hash-exact both ways), up to four
parallel streams per transfer, optional on-the-wire compression for text-like files,
quick settings tile, Material You theming, start-on-boot, hidden discoverability mode.

---

*Checklist before the listing ships:* title/desc character counts (limits: title 30,
short 80, full 4000), 8+ screenshots (roster, share sheet intake, transfer strip,
received ledger, QR pairing, options), 1024×500 feature graphic, category `Tools`,
data-safety form: zero collection/off-device storage, finally the real production name
(no trademark reuse of "LocalSend").
