# Phase B — Bluetooth as an invisible transport

Brief (user, 2026-10-27): "make it simplest and most complex at the same time."

That is the contract: **the UI never learns Bluetooth exists; the engine never forgets.**
No settings, no toggle, no new vocabulary anywhere in the strings. If a Wi-Fi access
point exists between two devices, Flit behaves exactly as it does today. When it can't
exist (mobile data only, hotspot dead, airplane mode with radio toggled), a peer simply
shows up in the same list and files stream the same way — slower, honestly labeled by
the existing speed readout, never explained by the app.

The depth is spent entirely underneath to buy that silence. Everything below is engine
truth; none of it is surface.

## The one suspension of disbelief

Our wire protocol (v2: `/info` `/register` `/prepare-upload` `/upload` `/cancel`, the
flit `/partial` resume extension) is HTTP/1.1 over TLS, spoken by a hand-rolled client
(`PeerHttpClient` — raw `Socket`, one exchange per connection, fingerprint-pinned, no
hostname verification by design) and served by Ktor/Netty over TCP.

Bluetooth offers a reliable byte stream (RFCOMM, or LE L2CAP CoC). TLS runs fine on any
reliable stream. Therefore: **the same protocol semantics ride an unchanged brain over
a second pipe.** No protocol fork, no v3, no transport-aware code paths beyond the two
binders described below. Resume tokens (`/partial`, ChunkTracker) work identically —
a mid-transfer drop reconnects and continues, transport-independent.

## Surface contract (hard rules)

1. **Zero new strings on the happy path.** The roster row, transfer strip, snackbars:
   unchanged. If the engine ever must explain itself, it borrows the existing voice
   ("no Wi‑Fi between us — trying the direct way"), never the word Bluetooth.
2. **Zero new settings.** No "enable Bluetooth transfers" checkbox, ever. Power on the
   radio is a *contextual* system ask: on the first no-AP start with the radio off,
   one system prompt / one sheet, once per device. After that, silence.
3. **Permissions are contextual too.** BLUETOOTH_SCAN + BLUETOOTH_CONNECT (API 31+;
   the Redmi test device is API 36, dynamic color stock, so this is the primary path)
   are requested at the precise moment a no-AP transfer needs them, with a one-line
   honest explanation. Never on app launch, never bundled with other permissions.
4. **The roster stays one list.** A dual-transport peer is one row. "2 online" counts
   peers, not transports (this is also where the pre-existing loopback self-peer bug
   gets fixed — B3's natural test case).
5. **Trust semantics unchanged.** Fingerprints pin over BLE discovery exactly as they
   pin over mDNS today; QR codes already carry them; the trust toggle, destination
   trees and only-trusted gate apply to both transports verbatim.

## Engine architecture

```
        surface (unchanged list / strip / snackbars)
                     │
                   arbiter  ← the only new "intelligence"
                     │         ranks transports silently:
        ┌────────────┼───────────────┐
   Wi-Fi (BSD)   BLE advertise     Bluetooth
   v2 server     + scan only      RFCOMM server+client
   (today's      when no AP,      hand-parsed HTTP/1.1
    ktor)        else dormant     → same ServerCore routes
                     │
              TlsIdentity / pinning / receipts (single brain)
```

**1. ServerCore refactor.** ProtocolServer's route handlers (prepare/upload/cancel/
partial/info) lift into a transport-neutral core consumed by two binders: Ktor keeps
the TCP/HTTPS binder as-is; RFCOMM binder accepts `BluetoothSocket`s and feeds each
connection through a small hand-parsed HTTP/1.1 *request* reader (~200 lines, the
mirror of the response parser `PeerHttpClient` already owns) into the same routes.
TLS over RFCOMM rides an `SSLEngine`-based stream shim (JDK `SSLSocketFactory`
wrapping needs a `java.net.Socket`; BluetoothSocket isn't one — the shim wraps any
duplex stream in server/client-mode TLS with our same TlsIdentity). Framework code
in crypto terms: ~150 lines, well-trodden, unit-testable against an in-memory pipe.

**2. Discovery = BLE advertise + scan.** Own clean-room service UUID flips on only
with the engine. Advertisement carries the same identity fields the UDP announce
carries (alias, model, fingerprint) in the manufacturer-data payload. Scan builds
roster entries attributed `source: ble`. Dormancy rule: while a direct AP is up,
the radio stays dark — Wi-Fi discovery owns the list. The arbiter flips to BLE only
when the AP criterion fails; flipping back is automatic.

**3. Transport arbitration.** Rank: Wi-Fi (any sane AP) → Bluetooth. A transport is
pinned per transfer; if BT drops, resume over whichever transport rises (the resume
token is peer+file-keyed, not transport-keyed — already true). No user choice, ever.

**4. First-contact cost (recorded honestly).** The OS inserts itself once per peer:
incoming RFCOMM from an unpaired device raises the system pairing consent sheet. Flit
maps that moment onto its existing roster-trust flow (accept once → trusted → roster
remembers). This is one dialog per new device, once — it can't be removed, only placed.

## Verification reality (one Android test device)

- `PeerHttpClient`'s socket construction and the RFCOMM binder both sit behind a
  `ConnectionProvider` seam, injectable — the entire protocol-parity suite runs the
  **same tests over an in-memory duplex pipe through the real hand-HTTP + TLS shim
  stack** (that's the "most complex" made testable without a radio).
- Harness `bt-sim` (B4, shipped): both ends of a run in one process — the receiver
  is the same `ServerCore` the TCP binder feeds, spoken to through the same
  `LinkServer` the RFCOMM binder rides, wrapped in the same `TlsLink`; the sender is
  the production `ProtocolClient` over `SimLinkProvider`. Green runs (TLS/plain, pin,
  multi-file, synthetic worst-case bodies) print the send rig's usual report with the
  same exit codes; `--no-tls` runs label `/info` `http` honestly. The pipe carrier
  lives in `protocol.transport`, shared verbatim with the parity suite.
- On-device checklist (bluetooth radio real): permission-sheet wording, radio-off
  flow and the arbiter's hotspot moments — all walked 2026-10-29/30 (record below).
  Our advertisement seen by *another* BT source stays parked with the two-device
  contact. True two-phone transfers wait for a second phone — against the
  harness-rig standard, that is a listed limitation, not a silent gap.

## Walkthrough record (2026-10-29, Redmi Note 10S / API 36)

- **B2 smoke**: radio-off first observed as honest silence (no flip line, no surface
  noise); enabling → flip line once; stack's own advertiser map shows our service UUID
  with a 52-byte extended payload (2 magic+ver + 32 fp + 18 alias — the arithmetic
  closes to the byte), legacy path unnecessary on this chip, connectable/scannable
  down, device name withheld; dump knob heard 7 nearby radios (RPA phones + an IoT
  neighbor), each address once; flip-back line once, map empty again. Both ask kinds:
  granted via adb mid-walk — engine tracked facts, never reasoned about them.
- **B3 walk**: grants revoked + no-AP forced → permissions ask raised once ("direct
  discovery waiting on the system", borrowed voice only) and the system sheet
  presented (`GrantPermissionsActivity`, result wired to the launcher) — the sheet's
  wording is Android's, zero strings ours. adb grants → ask advanced to radio power
  (radio still dark) → the enable dialog concluded itself ~45 s in with no user touch
  (MIUI quirk: the stack's enable log records nothing for it) → radio-on flip line
  once → flip-back line once. Ask-flags written per-launcher as designed. Device left
  in pre-walk state (grants restored, radio off, ask-flags clear) so the real
  first no-AP moment still earns its sheet.
- **B3 UI walk + hotspot classification (2026-10-30, phone unlocked)**: the asks
  consumed their real, once-per-device lifetime on hardware — grants revoked, no-AP
  forced, "Share on Wi-Fi" tapped like a user → the system's permission sheet
  presented ("Allow Flit to find, connect to… nearby devices?", Android's own
  wording), Allow tapped → radio flipped live, and the *power* book stayed shut
  (radio already on; `directAskPermsDone` alone written, exactly the one
  question/one answer design). Radio deliberately dropped → the system's enable
  dialog presented ("Flit wants to turn on Bluetooth"), Allow → radio on, flip line
  once. Ask-flags are now legitimately set and stay set as the shipped lifecycle. The
  roster was screencapped as visual evidence: zero new strings/vocabulary anywhere on
  the surface. Flip-back walked on the honesty fix: with the radio dimmed under a
  pending ask, the dark line tells the AP story only when the AP is the story.
- **Hotspot walkthrough, same day**: classifier proven on hardware, in three parts.
  ① Station + hotspot coexist on this build (status-bar fan intact, `wlan0` agent
  validated throughout) → the arbiter goes dark **in silence** — a user starting a
  hotspot is not a no-AP moment; the device still owns a Wi‑Fi network *and becomes
  one* (SoftAP hands out `10.29.15.0/24` on `ap0`, and the server binds all
  interfaces, so peers that join the hotspot reach it by IP while BLE rests). ② The
  system-level fact is structural, not incidental: the SoftAP rides `allNetworks` as
  its own network (interface `ap0`, `TRANSPORT_WIFI`, `NET_CAPABILITY_LOCAL_NETWORK`,
  not VPN) — exactly what `wifiNetwork()` matches; hotspot-as-access-point is true
  even with the station gone. ③ Induced churn (`svc wifi disable` under a live
  hotspot) made the SoftAP's agent vanish from the app's view for under a tick: the
  arbiter flipped ON once (`no Wi‑Fi network; direct discovery on`), then re-locked
  dark within one tick as the agents re-entered. The engine mirrored the system's
  churn honestly — no ask raised, ≤5 s of advertising, self-healing. Deliberately not
  smoothed with hysteresis: facts-by-tick IS the design, the flutter is invisible to
  asks and to the surface, and the flipper must keep telling the truth about whatever
  instant it surveys. The `FlitEngine` comment's coarse corner resolves: on this
  build, coarse is honest.
- **Pending user-gated**: the real two-device contact (BLE end-to-end and hotspot
  transfers with an actual peer need a second phone — listed limitation, not a
  silent gap).

## Milestone ladder

- **B0** — this doc + `ConnectionProvider` seam introduced *without behavior change*
  (TCP still default; all 30 tests + parity green).
- **B1** — ServerCore lift: ktor binder consumes it; RFCOMM request-reader + stream
  TLS shim land behind the seam with in-memory tests mirroring the v2 parity suite.
- **B2** — BLE advertise/scan service + arbiter + dual-transport roster entries
  (fixes self-peer counting along the way). Device smoke with real radio.
- **B3** — no-AP start flow: contextual radio-power ask, permission sheet, silent
  flip-back; on-device walkthroughs with hotspot on/off.
- **B4** — harness `bt-sim` + docs; two-device end-to-end gets verified whenever a
  second phone is available.

Each milestone: full suite green, assembleRelease, device evidence where a radio is
involved. Same discipline as the look rewrite.
