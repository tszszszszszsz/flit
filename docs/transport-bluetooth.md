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

**5. The rescue sweep (milestone 1).** A multicast-blocked access point (hotel Wi-Fi,
corporate VLANs, some routers) swallows discovery announces while both machines sit on
the same LAN — addressable, and running the very same v2.2 server. The sweeper
(`FlitEngine.startSweeper`, pure math in `FlitSubnet`) walks the device's own Wi-Fi
subnets (station and SoftAP alike, VPNs excluded) and knocks on the serving port
(250 ms dial filter, then `register` TLS-first with a plain fallback); an answered
register lands as a row through the same `touchPeer` path a manual add uses — no new
surface, no asks, no vocabulary. Restraints, each load-bearing: only while discovery
is silent (no live non-manual row), never in hidden mode (its promise is to stay off
other devices' rosters entirely, and a register is an announcement), one round per
~120 s, twenty-four knocks in flight at most, ranges beyond 1024 knocks refused (a /16
is not a neighborhood), typed `manual@` addresses left to the mprober. Found peers
stay fresh between walks by a cheap targeted re-register every ~15 s (the keeper:
the roster's TTL is shorter than the walk's cadence, and a dial-only peer announces
nothing to refresh itself); [SWEEP_KEEP_MAX_MISSES] consecutive misses retire the
keeper and the row ages out honestly. Empty rounds say so once, then stay quiet
while the rounds keep running. Honest edges: the sweep
knocks only the default port (custom-port servers stay reachable by address or
multicast, not by sweep), it rides Wi-Fi subnets only (cellular or wired networks are
not walked), a peer found by sweep is found *because it serves* — a powered-off or
firewalled machine is invisible, same as for any connect-based discovery — and the
probe's register budget (400 ms) can be outrun by a cold or slow TLS server: a peer
whose handshake is slower than the budget stays invisible to the sweep while
remaining visible to multicast and manual address.

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
- **Queue on hardware (2026-10-30 D1 walk)**: with the harness `serve` running on
  the author's box (192.168.100.12 — the manual row's own address), the manual peer row
  lit ("mprober … ok") and the phone sent a text note and a picked file for real —
  phone → box, TLS, received on disk. Then the full queue story, all on device: serve
  killed → picked file dispatched into the refusal → `kept, sends when it reappears`
  and the "Send later" row ("1 file(s) waiting for it to reconnect"); a second picked
  file into the same dead peer **merged** into that one row ("2 file(s) waiting") —
  the pre-fix behavior would have been a "send failed" lie and a deleted copy; serve
  restarted → the probe loop noticed within a beat → `queue: 2 queued … — sending
  now` → `sent 2/2` and both files landed on the box (the receiver's duplicate naming
  — `batch-a (1).bin` — held). Cancel walked too: a third intake queued, the row's
  X pressed, `queued send cancelled`, row gone. Persistable-grant observation: picker
  files are spooled at intake, so queue tokens are cache paths and no grants are in
  play for this flow — the release path is structure, exercised by any SAF-token
  batch. One transient noted without a charge: the peer row briefly presented the
  address-as-name presentation ("11" tile) while offers raced a restart, then
  returned to the named state by itself.
- **Idle soak, 0.2.0 on hardware (2026-10-30, D1 close)**: 8 minutes screen-off with
  the engine serving — empty crash buffer, process alive, radios in the state the user
  left them (Bluetooth off), and the only log lines the mprober's honest refusals
  against the serve-down box. Silence = evidence: the arbiter and rationing earned
  their rest without a surprise.
- **Interoperated on hardware (same walk)**: the harness `send` — the production
  protocol client — discovering the phone and delivering a file (`from-box.bin`) that
  landed in `Downloads/Flit/` with ledger row "_received_ from Flit-sender". Reference
  CLI, our engine, real radio.
- **Still parked, still listed**: the two-phone *Bluetooth* end-to-end — today's box
  contacts prove the protocol and queue on the Wi-Fi carrier; the BLE carrier's full
  phone-to-phone transfer needs an actual second phone with the app.
- **Rescue sweep on hardware (2026-11-14, milestone 1)**: fresh release install
  (0.2.0 + sweep; the rig's release key proved stable across rebuilds — one earlier
  debug-signed device build needed a single uninstall before the signed update took,
  after which `adb install -r` updates in place). The phone (192.168.100.80) and the
  box (192.168.100.12) share "Bojan i Stefan 5g". Walk, all on device:
  - *Empty rounds*: engine start → first round within ~8 s — `sweep
    192.168.100.0/24: 253 hosts, nothing found in 2774ms — rounds continue, quiet
    until a find` (serve down). A second empty round ~120 s later printed nothing
    (by design, the empty line says once) — the throttle's spacing read from the
    timestamps of the lines that do print.
  - *The find, unassisted*: the box's serve restarted **dial-only** (`--udp-port
    9999` sends announces to a port nobody listens on — multicast out of the
    picture), and the install was fresh (no manual rows, no QR trust): the next
    walk knocked, the register answered, and the round line named it — `sweep …
    found 1 (Flit (szszszs)@192.168.100.12) in 2773ms` — row on the glass, born
    from the sweep alone.
  - *A real defect caught by the walk*: the walk's cadence (~120 s) outlives the
    roster's freshness (30 s TTL), so a dial-only peer (announces nothing, not a
    manual row) **blinked** — found, pruned ~30 s later, re-found two minutes
    later. Fixed with the keeper: found peers get a cheap targeted re-register
    every ~15 s (the mprober's trick, pointed at discovered peers); three
    consecutive misses retire it and the row ages out honestly. After the fix the
    row held at 2.5 min ("1 online") with exactly one found line in the log — the
    walk stood down once the roster was fed.
  - *A working peer, not a ghost*: the send chip on the sweep-born row opened the
    picker; the picked file went through — `sent 1/1 to Flit (szszszs):
    batch-b.bin`, received on the box's disk. No multicast, no typed address, no
    QR anywhere in the story.
  - *Retire honesty*: serve killed → the row pruned itself (~25 s, the roster's
    TTL telling the truth about a peer that stopped serving) → the next walk ran
    and said `nothing found` → the keeper's third miss printed `sweep:
    192.168.100.12 quiet — its row retires` → serve returned → the throttled next
    walk re-found it (`found 1`, 17:44:21) → row back, keeper re-engaged.
  - *Hidden stand-down*: hidden ON (the toggle restarts the engine itself) → 26 s
    with a fresh throttle, an empty roster and Wi-Fi up produced **zero** sweep
    lines (a round would have fired within ~10 s) — the sweep keeps hidden mode's
    promise of staying off other devices' rosters, because a register IS an
    announcement. Hidden OFF → the walk resumed within 7 s (`nothing found` —
    serve still down) → serve restored → `found 1`, end state "1 online".
  - Solved mid-walk: a second host answering 53317 dials with a Flit-subject
    certificate (192.168.100.66) never answered the sweep's register inside
    400 ms — it turned out to be **this very phone**, whose Wi-Fi address
    drifted .80 → .66 during the day (DHCP). The phone dialing itself pays its
    own server's cold TLS handshake on every probe, and that handshake outruns
    the 400 ms register budget. Honest edge, now listed: a slow or cold v2.2
    server is invisible to the sweep; it still arrives by multicast when it
    announces, by manual address, and answers a probe once its handshake is
    fast (a warmed desktop JVM). The walk reported what it saw; the wrong
    story in the first draft got the correction it deserved.
- **Sweep restraint matrix, all on device (same walk)**: rounds only when the
  roster holds no live row (log silence throughout every live-row stretch, firing
  within one tick of the pruner emptying it); ≥120 s between walks; ≤24 knocks in
  flight; empty rounds logged once; found rows kept fresh and retired honestly;
  hidden mode stand-down absolute.
- **flit-cli ships (2026-11-14, milestone 2)**: the desktop companion is the same
  v2.2 stack under a bin that doesn't wear test-bench vocabulary. Shipped pieces:
  `install-cli.sh` (installs from a build or a CI zip; a java-21 gate guards both
  the build and the installed launcher — a bare shell holding java 8 gets one
  readable line, not the JVM's class-version stack trace), a README section, and
  a CI artifact (`flit-cli-0.2.0.zip` from every rig run). Artifact walked on the
  box: installer → `~/.local/bin/flit-cli serve --hidden` (never announces, never
  registers back — the app's hidden semantics, now a real flag instead of the
  dial-only `--udp-port` trick) → the phone's rescue sweep found it on the next
  throttled round (`found 1 … 192.168.100.12`) → phone→CLI transfer landed on
  disk (`batch-b (1).bin`, dedup honest against the earlier arrival) → CLI→phone
  send landed in the phone's `Downloads/Flit/` (`sent 1/1 … ok cli-hello.txt`).
  Identity home stays `~/.config/flit/flit-harness`, so fingerprints survived the
  rename: every peer that trusted the rig keeps trusting the product.

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

## Post-ship ladder (agreed 2026-10-30)

- **1 — subnet sweep** (rescue mode for multicast-blocked APs) — **done**: pure
  math + sweeper + keeper, rig green (76 tests), full hardware walk recorded above.
- **2 — CLI companion** — **done**: the desktop client ships as `flit-cli`
  (installer with a guarded launcher, README section, CI dist artifact);
  evidence below.
- **C — process-death checkpoints**: every send persisted before bytes move; crash,
  OOM, reboot resume into the same prefix. (Prerequisite of A.)
- **D — album mirror**: trusted-peer forward-sync of new camera files, deduped,
  checkpointed, queue-flush driven.
- **A — carrier migration mid-transfer**: the sender re-probes in priority order and
  re-attaches a running transfer onto whichever transport answers.
- **B — Wi-Fi Aware fat pipe** (hardware-gated: check `pm has-feature` on the rig
  before committing; BLE stays discovery, Aware becomes the data road).
- **E — self-hosted relay**: parked last, opt-in only, largest scope.
- Idea-3 (hotspot assist) parked pending a vocabulary decision; idea-4 items stay
  measure-then-act only.
