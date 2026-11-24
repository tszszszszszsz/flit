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
2. **Zero new settings — deliberately revised 2026-11-24.** The original contract had
   no Bluetooth setting at all; the user explicitly asked for an option to transfer
   over Bluetooth, so Options now carries **Transfer over Bluetooth** (off by
   default, inert when left off). The rest of the rule holds: power-on stays a
   contextual system ask, once per device, in the system's own voice. Turning the
   option on opens an RFCOMM listener (connectable BLE advertising) and lets the
   hunter dial a peer's Bluetooth address. Wi-Fi still wins whenever it answers:
   Bluetooth is a fallback carrier, not a faster one.
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
- **Bluetooth data carrier landed (2026-11-24)**: BLE was discovery-only until
  now. `FlitBluetooth` adds the real RFCOMM dialer and accept loop on the
  existing `Link`/`LinkProvider` + `LinkServer` + `TlsLink` seams: TLS and the
  whole v2.2 protocol run inside the socket, so trust, resume and the death
  contract are unchanged. BLE advertising becomes connectable only while the
  carrier is on. A new **Transfer over Bluetooth** option (off by default)
  binds it; the hunter carries a route `kind` (TCP/Bluetooth) so a MAC never
  gets dialed as an IP. On-device smoke: toggling the option restarted the
  engine and logged `bluetooth link: listening for direct transfers` with no
  crash. **Not verified:** a real phone-to-phone RFCOMM transfer — there is no
  second phone, and the bench box has no Bluetooth adapter (`bluetoothctl
  list` is empty), so the real socket path cannot be exercised here. Listed,
  not hidden.
- **Speed, measured 2026-11-24**: a 512 MiB file from the reference CLI to the
  phone completed in 15.9 s both single-stream and multi-socket — **32.1 MiB/s
  (~270 Mbps)**. The ceiling is this Wi-Fi link and the phone's storage, not
  Flit's framing; turbo is resume-aware but cannot outrun the radio. Bluetooth
  is a reachability fallback, never a speed upgrade. The in-app readout was
  fixed in the same pass: `FlitSpeed` samples independently of the 350 ms
  notification paint, and multi-socket progress is summed across streams
  instead of four absolute offsets overwriting one ledger slot — the old
  "KB/s then gigabits then done" spike is pinned by `FlitSpeedTest`.
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
- **C — process-death checkpoints** — **done**: the queue row written before
  bytes move (the queue IS the checkpoint), the receiver's token sidecar +
  start sweep make interrupted prefixes survive receiver restarts, fresh
  dispatches probe on their first attempt; killed sender + restarted
  receiver on hardware resumed hash-exact from a 128 MB prefix (evidence
  above). (Prerequisite of A.)
- **D — album mirror** — **done**: the mirror rides the queue machinery (C's
  carry it: one row per peer that merges, auto-flushes, checkpoints through
  death, drops precisely on success); tokens are MediaStore uris behind the
  one media-read ask; walked on hardware end-to-end — a synthetic camera shot
  watched, queued, sent and landed hash-exact in ~200 ms, a restart re-sending
  nothing (dedup proven) (evidence below).
- **A — carrier migration mid-transfer** — **done, walked on hardware
  (2026-11-24)**: the route store, the respect-priority candidates, the hunter
  (2 s tick, calm backoff, fingerprint-matched accept) and the death
  contract (a carrier death throws `TransferUnreachable`; a returned report
  means the receiver spoke; the flush drops its row only after a report)
  are in, sim-proven, rig green. The first walk caught a C-era defect on
  the flush path; the second walk (2026-11-18) caught a two-layer resume
  defect — the receiver parsed only `bytes=` while the wire sends RFC
  `bytes …` (turbo never ran), and a failed chunk claim deleted the kept
  prefix before the plain attach could use it (711 MB tail written as a
  fresh part). Both are pinned by `ResumeAndTurboTest` (5 tests: both
  range syntaxes, restart probe/attach, failed-chunk-then-resume, 409 for
  a missing base, and a real `multi=true` hash-exact landing). **Final
  walk (2026-11-24):** a fresh 300 MiB camera shot was mirrored to the
  box; the box was `kill -9`d 0.8 s after `recv-start` with a 33,980,416 B
  part on disk. The phone logged `ECONNREFUSED … — kept, sends when it
  reappears`, the hunter re-probed on restart, and the very next offer
  logged `recv-resume IMG_20261124_183000.jpg from 33980416 bytes` — the
  exact killed prefix — then `recv-ok`: the landed file's sha256
  `fbc4684a30afd…` matches the phone's byte-for-byte, with no part or
  sidecar left. An earlier, late kill (~90% through an 800 MiB multi-socket
  send) also landed hash-exact but took the clean full-resend path — a
  sparse prefix near completion is not advertised as a resume base; that
  limitation is listed, not hidden.
- **B — Wi-Fi Aware fat pipe** — **parked, hardware absent**: the agreed
  gate `adb shell pm has-feature android.software.wifi.aware` answered
  `false` on the rig phone (2026-11-21). BLE stays discovery; no Aware
  carrier was built for a device that does not have the feature. This is
  a hardware limit, not a silent cut.
- **E — self-hosted relay**: pure core (`RelayHello` + `RelayCore`),
  `flit-cli relay`, and — as of 2026-11-24 — the full app surface landed:
  production relay dial/subscribe endpoints in the protocol module, an
  engine subscription worker riding `ProtocolServer.serve` (the B door),
  relay-last hunt fallback, relay-aware dispatch, and an opt-in Options
  field (empty = E invisible). The harness end-to-end test now uses those
  same production endpoints with TLS inside the blind pipe. Rig green
  (115 tests). **Hardware walk (2026-11-24):** the box ran `flit-cli relay`
  and `flit-cli serve --relay 127.0.0.1:53317` (no direct listener, no
  announces) while the phone typed that relay into Options. A 200 MiB camera
  shot crossed the blind pairer: the phone logged `hunt: relay answered as
  Flit (szszszs)`, the relay logged four opaque pairings, and the receiver
  landed a hash-exact file. Then a 300 MiB shot was cut by `kill -9` on the
  **relay** mid-transfer: the phone kept the row, both ends re-subscribed
  with backoff, the next hunt re-paired through the restarted relay and
  logged `recv-resume … from 31866880 bytes` — the exact killed prefix —
  with a hash-exact landing and no leftover part. The mid-body
  throw/keep/resume loop is proven through the relay on hardware, not only
  in the harness.
- Idea-3 (hotspot assist) parked pending a vocabulary decision; idea-4 items stay
  measure-then-act only.

## Milestone C design — process-death checkpoints (written before code)

Read out of the code as it stands (2026-11-14), then converged. Three facts:

1. **The sender's owe-state already survives death — for queue batches.**
   `dropQueue` fires only after `dispatchTo` *returns* (the far side spoke:
   sent or declined). A batch killed mid-flight keeps its roster row, its
   spooled files, its persistable grants — all on disk — and the next
   `touchPeer` (multicast, keeper, sweep, manual add) re-enters
   `flushQueueFor`, whose per-file `/partial` probe resumes into the
   receiver's prefix. What is missing is the *fresh* dispatch: a send the
   user started right now is not owed anywhere until it fails — a process
   death mid-flight loses it silently and strands its spool copies.
2. **The receiver's progress lives on disk but is unreachable after death.**
   Interrupted parts persist as `.part` files (plus `.pieces` sidecars) beside
   their targets, but the `parts` registry is in-memory: after a restart the
   `/partial` probe answers 404 and the sender re-sends whole — hash-gated and
   correct, but the prefix is squandered. (The start sweeps only *age out*
   parts older than a day; they never repopulate.)
3. **The flush-on-restart path exists.** Queue rows survive reboot;
   any path that lands the peer as a live row auto-flushes. No new machinery
   needed there.

The design, in one sentence each:

- **One truth for owe-state: the queue row, written *before* bytes move.**
  A fresh dispatch converges onto the queue's machinery — enqueue the row at
  dispatch time, send through the same path, `dropQueue` on confirmed success
  exactly as the flush path does today. The enqueue-on-failure dance in the
  fresh path dies; every send the engine attempts is owed somewhere first.
  This is the convergence the milestone names: the queue IS the checkpoint.
- **One truth for progress: the receiver's disk.** No sender-side byte
  counters to persist — the sender re-learns the prefix through `/partial` on
  every re-entry, and the hash gate verifies every landing. A file that fully
  landed before the death re-enters as a zero-byte resume and settles.
- **Receiver durability: the token rides beside the part.** At part-creation
  the receiver writes `resumeToken(file)` into a sidecar next to the `.part`;
  server start sweeps parts (and sidecars) back into the `parts` registry
  with an empty senderIp — which the probe already accepts from anyone.
  The 24-hour aging stays as-is.
- **Honest edges, listed:** non-persistable sources (mixed spool+URI grants)
  cannot be checkpointed and stay best-effort; a resumed-after-death prefix is
  served with an empty senderIp, trusted only because the probe is a read of
  byte count, not of content; the checkpoint covers deaths of *our* process —
  a receiver that died loses nothing (its parts are durable), a sender that
  died keeps its owe and re-enters.

Tests: token sidecar round-trip; a fresh `ServerCore` over a dirty dest answers
`/partial` with the sidecar base; the fresh dispatch owes before bytes (row
visible the moment dispatch starts); the death walk on hardware — transfer
running, `am kill`, restart, same prefix, hash-exact end.

### Milestone C on hardware (2026-11-14) — the walk that proves both truths

Fixture: the box serves the freshly rebuilt `flit-cli` (the C build — verified
by the `.part.token` literal in its installed jar); the phone runs the freshly
installed 0.2.0 release APK (owe-first dispatch + attempt-1 probe), parallel
streams toggled OFF in Options so the walk exercises the plain single-stream
path. The payload: `/tmp/opencode/bigresume.bin`, 400,000,000 bytes,
sha256 `8a4acb100cd4ea4596ce4c495a4080ad19f2fd0b8346823dd225e9dc00a79849`,
pushed to the phone's `Download/`. Then, chronologically:

1. **Owe before bytes.** The send chip on the roster row → SAF picker
   (`OpenMultipleDocuments` — persistable grants, so the batch is
   checkpointable) → `bigresume.bin`. With the C build the queue row is written
   *before* any byte moves; 3–4 s later, mid-body, the sender's process is
   killed outright: `adb shell am force-stop app.flit.share`.
2. **The receiver's disk keeps the prefix — and its own discoverability.**
   The abrupt disconnect surfaces as a checksum mismatch, which is exactly an
   honest prefix: the server kept it as `.bigresume.bin.part` —
   **127,959,040 bytes (32 %)** — and wrote the token sidecar
   `.bigresume.bin.part.token` beside it at keep time
   (`41133d96bf74e2023c12bfa26f953f0d`, the content-bound
   name∥size∥sha256 token the sender probes with). The serve log shows
   `recv-start` → `recv-BAD checksum mismatch` — no special death path, the
   ordinary keep rules did it.
3. **The receiver dies too.** The `flit-cli` serve process is killed and
   restarted — the in-memory parts registry is gone; the only trace of the
   prefix is the sidecar on disk. `sweepOnStart` → `repopulateParts` reads it
   back with an empty senderIp.
4. **The sender comes back owed.** `am start` relaunches the app; sharing
   starts from the dock; the queue row survived the force-stop in prefs. The
   engine's first sweep round re-finds the hidden box on its own
   (`sweep … found 1 (Flit (szszszs)@192.168.100.12)`), and the row's touch
   auto-flushes the owed batch — one log line, no user action:
   `queue: 1 queued for Flit (szszszs) — sending now`.
5. **The fresh sender re-learns the prefix.** The flush's `sendFiles` probes
   `/partial` on its first attempt (the C change: `carried > 0 || attempt == 1`)
   and the restarted receiver answers **from its disk** with 127,959,040 —
   the sender attaches with `X-Flit-Resume` on its own and streams only the
   remaining 272 MB. Seven seconds later:
   `sent 1/1 to Flit (szszszs): bigresume.bin`.
6. **The verdict.** The receiver's log says it plainly —
   `recv-resume bigresume.bin from 127959040 bytes` — and the landing is
   hash-exact: `sha256sum` on the box equals the reference hash above. The
   part and its sidecar are gone from the directory (consumed by the success
   move); the queue row is gone from the phone (precise drop, sent tokens
   out, grants released). Screenshot: `/tmp/opencode/ui-resume-done.png`.

One death was simulated twice over and the transfer still cost only the bytes
that never landed. The queue row was the checkpoint, the receiver's disk was
the progress, and neither side was told anything the other didn't already
speak.

## Milestone D design — album mirror (written before code)

The ladder line: trusted-peer forward-sync of new camera files, deduped,
checkpointed, queue-flush driven. Read against the code, the shape is
already there — **the mirror is a producer into the queue machinery that
milestone C just made bulletproof, not a new pipeline.**

- **One target, chosen and trusted.** Options gains a switch
  ("mirror new camera shots") and, when it is on, a target row that opens a
  picker over the trusted (pinned) peers. One fingerprint, stored. The row
  speaks no transport vocabulary — it names a device, not a mechanism.
- **The watcher.** A `FileObserver` on `DCIM/Camera` (the stock camera's
  folder) notices `CLOSE_WRITE` and `MOVED_TO` — a finished file, not a
  half-written one. Camera shots, videos, everything the stock camera
  saves; screenshots and downloads stay out.
- **The ledger is the dedup.** Keyed `name∥size` (camera names are
  timestamped; size separates an edit), persisted JSON, capped at 2000 with
  FIFO eviction. Seeded at enable time with the folder as it stands — the
  feature syncs *forward*, it does not replay the album's history. Marked
  **only on send success**; between notice and success the queue row (the
  checkpoint) carries the file, so a death loses nothing — the next
  re-notice or scan just merges into the surviving row.
- **Scan on engine start.** The observer covers liveness; a stat-only scan
  of the folder at every engine start covers completeness — anything that
  landed while the app was dead (or that an OEM FUSE quirk swallowed) is
  caught by the diff, then, and nothing is re-hashed or re-sent.
- **The flush is the queue's.** Notice → ledger check → `FlitRoster.enqueue`
  of a `QueuedSend` for the target fingerprint (path tokens, exactly what
  the queue-flush already consumes) → the same row manual queue entries
  ride: merges with anything pending, auto-flushes on the peer's touch
  (sweep, keeper, multicast — at most one keeper beat of latency),
  checkpoints through any death, drops precisely on success. A live peer
  gets a direct flush nudge at enqueue; an absent one catches the batch on
  its next touch.
- **Success marks the ledger** in the same `dropSent` door every sent batch
  leaves through — filtered to tokens that are files inside the camera
  folder, so manual sends never write it.
- **Honest edges, listed:** mirroring runs only while sharing runs (the
  flush needs the engine); the target must stay pinned — unpinning pauses
  the mirror rather than re-pointing it silently; files the user deletes
  from the phone after mirroring are not deleted on the peer (forward-sync
  only, never backwards); a phone whose camera saves elsewhere (pro modes,
  third-party cameras) is not watched.

Tests: the pure diff (plan: existing folder vs ledger → candidates), ledger
seeding and cap, the enqueue-merge with a manual row. The observer, the
scan, and the flush are device truth — walked on hardware: a synthetic shot
pushed into `DCIM/Camera` while the box's row is live, arriving on the box
auto-accepted and hash-exact; a restart with nothing new re-sending nothing.

The walk reshaped three load-bearing choices of this design — raw-path
tokens died of an EACCES, the stat-only scan died of scoped storage, and the
Files collection died of silence. The full story and the amended mechanics
are recorded in the hardware section below; this design stands as written,
as the record of what was believed before the device spoke.

### Milestone D on hardware (2026-11-15) — the walk that reshaped it

Fixture: the box serves the freshly rebuilt `flit-cli` (`--hidden`, downloads
into `/tmp/opencode/flit-cli-downs`); the phone runs the freshly installed
0.2.0 release APK with the box pinned, trusted, and chosen as the mirror
target. The device: the Redmi Note 10S (API 36) as always.

**The first walk caught a real defect — exactly what walks are for.** The
toggle went on, the seed ran (`mirror: baseline seeded — 1 existing camera
file(s) stay put`), the watcher went live, a synthetic 3,000,000-byte shot
(`IMG_20261115_141900.jpg`, sha256 `18c6ac31…`) was pushed into
`DCIM/Camera` — and the queue's flush died on contact:

    mirror: IMG_20261115_141900.jpg (3000000 bytes) — new camera file
    queue: 1 queued for Flit (szszszs) — sending now
    send failed: /storage/emulated/0/DCIM/Camera/IMG_…: open failed: EACCES (Permission denied)

The design's "path tokens, exactly what the flush consumes" was wrong for
camera files: **shared storage is not openable by raw path** — scoped
storage forbids it, and the offer never left the phone. Three catches in
one walk, each fixed before the redo:

1. **Tokens are MediaStore uris now.** A camera shot's token is its
   `content://media/…` uri (the queue's spool step opens it through the
   ContentResolver like any SAF uri). Reading media asks for the photo/video
   read — `READ_MEDIA_IMAGES` + `READ_MEDIA_VIDEO` (plus
   `READ_EXTERNAL_STORAGE` capped at 32) — the mirror's **one system
   dialog**, fired when mirroring is switched on; a refusal leaves
   mirroring off, honestly ("photo access is needed — mirroring stays
   off"). Mirror rows are gated to API 29+ (`RELATIVE_PATH` is the query's
   spine). The ledger marks close through `dropSent` via the batch's
   *mirror duty* and the spooled names/sizes — no path sniffing.
2. **The seed and the scan ride MediaStore, not `stat`.** The enable-time
   seed and the engine-start scan diff the same query the watcher's notice
   polls: `cameraShots()` — names, sizes, uris of everything in
   `DCIM/Camera/` — against the ledger. This also killed a second silent
   no-op the walk caught: the toggle can fire *before the engine ever
   started*, and the original seed crashed inside a `runCatching` and said
   nothing — `mirrorSeed(ctx)` now takes the caller's context.
3. **Never silent, enforced.** An empty query, a failed query, a grant
   refused — each logs an honest line (`camera query answered nothing
   (grant or folder empty?)`), because the walk also proved the app can sit
   there "watching" an album it cannot see, and that must be visible.

Two device quirks the walk pinned down (both are walk-only artifacts; real
use never sees them, but they cost real debugging time and belong in the
record):

- **adb-pushed files land `is_pending=1 owner_package_name=com.android.shell`.**
  The shell sees its own pending rows; the app — correctly — does not see
  other apps' pending rows. `pm grant` of the media permissions, revoking
  the partial `READ_MEDIA_VISUAL_USER_SELECTED` grant, nothing helped until
  the row was finalized with a
  `MEDIA_SCANNER_SCAN_FILE` broadcast (`is_pending=0` after it). Real
  camera shots are written pending by the *camera*, owned by it, and
  finalized by it — never pending to their reader.
- **MIUI had pre-granted the media permissions** (`USER_SET` flags), so the
  system ask never showed on this device; on a stock device the ask is the
  expected first step of the toggle.

The redo, after the fixes — the D moment itself:

1. Toggle on → `mirror: baseline seeded — 1 existing camera file(s) stay
   put` (the first shot had already been mirrored by the pre-fix walk; the
   seed holds it put — the ledger doing its job).
2. Sharing on → `serving https://0.0.0.0:53317` + `mirror: watching
   /storage/emulated/0/DCIM/Camera`; the first sweep round re-finds the box
   on its own (`found 1 (Flit (szszszs)@192.168.100.12)`).
3. A fresh synthetic shot, `IMG_20261115_144400.jpg`, 2,500,000 bytes,
   pushed and finalized. Fourteen seconds later — the whole chain, unprompted:

       mirror: IMG_20261115_144400.jpg (2500000 bytes) — new camera file
       queue: 1 queued for Flit (szszszs) — sending now
       sent 1/1 to Flit (szszszs): IMG_20261115_144400.jpg

   notice → queued → sent in under 200 ms (the flush nudge fired on a live
   peer, as designed).
4. **The verdict on the box:** `recv-ok IMG_20261115_144400.jpg →
   … (2500000 bytes)`, and the landing is hash-exact —
   `3325c7abad2dc3bf163a1e5dd9a053184bf57cc346b759fdacf0e86b13a8874d` on
   the sender side and on the box.
5. **The dedup proof:** force-stop, relaunch, sharing back on. The
   engine-start scan ran over the same folder and said nothing — no
   re-queue, no `scan found` line; the box holds exactly one copy, no
   `(1)` rename. The ledger is the dedup, exactly as designed.

One more MediaStore honesty from the walk: a query of the **Files
collection answered nothing under the very same grant that made the Images
collection answer** — so the shipped `cameraShots()` queries the **Images
and Video collections** (the homes camera JPEG/MP4 rows live in), merged
and token-deduped. The watcher also nudges the scanner and polls the query
for a few beats: a freshly closed file is not instantly indexed, and the
next scan would catch it anyway — nothing lost, nothing re-sent.

Rig at commit time: 86 tests green (23 protocol including the death sim;
63 app including the mirror diff/merge additions), debug + release
assemblies and the CLI dist green. Screenshot of the configured mirror:
`/tmp/opencode/ui-mirror.png`.

What this milestone leaves open, listed: mirroring runs only while sharing
runs (the design's own edge, unchanged); a device whose camera saves
elsewhere is not watched (only `DCIM/Camera` feeds MediaStore's
`RELATIVE_PATH='DCIM/Camera/'` filter — pro modes and third-party cameras
that write elsewhere stay out, and the empty-query log line makes that
visible); deletions are never mirrored backwards; the ledger keys
name∥size, so a same-name same-size re-save is considered already-mirrored
(camera names are timestamped, which makes the collision a curiosity, not
a gap).

## Milestone A design — carrier migration (written before code)

The ladder line: the sender re-probes in priority order and re-attaches a
running transfer onto whichever transport answers. Read against the code
as it stands (2026-11-15), the foundation is C's — a mid-flight death
leaves the owe on disk and the receiver's prefix on its disk — but the
recovery is *passive*: the sender aims every dispatch at exactly one route
(the live row's last-seen address), and after a failure it waits for the
next touch of that fingerprint. The touches exist (announce, keeper, the
manual prober, the retry chip), each with a gap: after a process restart
the last-known address is thrown away and discovery must re-find the peer
from zero; a stale typed row (manual@ rows never age out) suppresses the
sweep forever; a peer that moved addresses leaves every old voice knocking
on a dead door until the slow rounds re-find it. The design, in one
sentence each:

- **One truth for reachability: the route that last answered.** A tiny
  persisted map `fingerprint → host:port(+tls)` in the roster, written on
  every touch that arrived over the wire (a register answer or an announce
  — both carry the peer's real identity and its serving port), and only
  when it changes (announce storms must not churn prefs). This is memory
  of the last good answer, not a discovery mechanism; it survives process
  death by living where the queue rows live. A row with no address-bearing
  socket (the direct-radio rows, port 0) remembers nothing.
- **Candidates, in priority order** (pure, tested): the live row's address
  first (the freshest wire answer), the remembered route second (it
  answered once), typed addresses last — probed for any owed fingerprint
  and accepted **only if the answering register's fingerprint matches the
  owed one**: a register answer carries the full identity, so the
  fingerprint is the trust anchor and a reassigned address can never hand
  the batch to a stranger. Dedup keeps the list honest.
- **The hunter: the sender looks for its owed peer instead of waiting.**
  One worker, ticked every couple of seconds, over the queue's owed
  fingerprints: a fingerprint mid-dispatch is skipped, a due one gets its
  candidates probed on a calm backoff (first beat ~2 s after the failure
  or start, then 5, 15, 30, then one beat per minute for as long as the
  row is owed — a handful of directed dials, the same budget the sweeper's
  knock uses, port-aware), and the **first fingerprint-matched answer goes
  through the ordinary `touchPeer` door** — which already auto-flushes
  owed rows, whose flush already probes `/partial` on its first attempt
  and resumes from the receiver's disk. The hunter owns no sending; C's
  machinery carries the transfer the moment a route answers.
- **Mid-transfer is the walk-out case.** "Re-attaches a running transfer"
  on this architecture means exactly this: the mid-body death leaves the
  owe, the hunter re-attaches within a backoff beat onto whichever
  candidate answers, `/partial` re-learns the receiver's prefix, and only
  the bytes that never landed fly again. The transfer's continuity is the
  owed row plus the receiver's disk, not a living socket — no socket
  hot-swap is designed, and none is needed for the phrase to be true.
- **Honest edges, listed:** the hunter probes only routes it already
  knows — a peer that moved to an address nobody remembers is caught by
  the discovery voices exactly as today, because the hunter never walks
  neighborhoods (that stays the sweep's restrained turf); a fingerprint
  this device never spoke with has no candidates and its queue row waits
  exactly as today; probing means a register, which puts us on the
  answering peer's roster — the same first-contact act any send performs;
  the battery cost is a few directed dials per backoff beat per owed
  fingerprint, capped by the schedule, and the row's own cancellation
  (user or empty) retires the hunt with it.

Tests: the pure seam only — candidate ordering and dedup, the backoff
schedule, the fingerprint-matched accept rule. The route store rides the
same prefs the queue rides (device truth); the loop, the probes, and the
resume are walked on hardware: a transfer killed mid-body, the sender
hunting, the route answering, and the landing resuming hash-exact from
the receiver's prefix — plus the restart case where the remembered route
answers within seconds of engine start, before any discovery round.

### A's first hardware walk caught a C-era defect (2026-11-15) — and the fix is the contract

The design above was written against the code as it stood; the first walk
was supposed to be a formality — hunt answers remembered route on a cold
queue (it did, 15:10:10, `hunt: 192.168.100.12:53317 answered as Flit
(szszszs)` → flush), then `kill -9` the receiver mid-body at ~80% of an
800 MB camera shot and watch the row survive. The row did not survive.
The box kept a 671,678,464-byte `.part`, the phone's queue held nothing,
and the log's last word was a `FAIL … failed to connect … ECONNREFUSED`
with no `kept` after it. C's promise — *the queue IS the checkpoint* —
was only true on the fresh-dispatch path. Three defects, one root:

1. **`dispatchTo` was fire-and-forget** (`scope.launch` inside a `fun`),
   so `flushQueueFor`'s `dropSent(batch, spools)` ran at send-**START**,
   not after the send: the row left the queue before the first byte flew,
   and its catch was dead code. The walk used a fresh dispatch (which
   owes-before-bytes, correct); D's mirror sends always succeeded
   instantly — that is why no earlier walk ever caught it.
2. **`sendFiles` returned transport deaths as reports.** A connect
   refused at the batch prepare threw (that one was honest), but a
   mid-body carrier death after exhausted attempts came back as a
   `SendReport` with a failure reason — and the engine's tail read any
   returned report as "the far side spoke", concluding a story that never
   happened.
3. **The report path cleaned the queue's spools unconditionally.** Row
   gone at send-start (1) + spools deleted on the failure-report (2+3)
   = an 800 MB batch with no row, no spools, and a receiver holding 80%
   of a file nobody would ever resume.

The fix is a contract, not a patch — **`TransferUnreachable`
(extends IOException)**, thrown by the protocol layer whenever the
carrier dies (connect refused, reset mid-body, EOF, exhausted attempts,
re-prepare IO failures — and the batch prepare's first handshake), and
returned reports now mean exactly one thing: **the receiver spoke**
(sent / refused / declined — the story is over, the whole batch
concludes). On that contract:

- `dispatchTo` is `suspend`, runs inline, and returns the receiver's
  `SendReport?` (null only when no exchange could even begin). A throw
  means the carrier died with no answer at all.
- `flushQueueFor` dispatches **synchronously**: `dropSent` + spool
  cleanup run only after a report comes back; a throw leaves the row and
  its spools exactly as they were — the hunt re-enters the flush and the
  receiver's on-disk prefix carries C's resume.
- **One dispatch per peer, fresh or queued** (`dispatching` is
  claimed by `dispatchTo` itself): the owe row exists before bytes
  move, so an announce mid-fresh-send would otherwise re-enter the
  flush and double-fly the same batch. The hunter consults the same
  claim, so a mid-send peer is never probed either. (Found by the same
  post-walk review; the walk's own path was flush-guarded, which is
  why no earlier run had exercised it.)
- `send`/`sendShare` run the same suspend in `scope.launch` — fresh
  dispatches owe-before-bytes as before; their throw path keeps the
  checkpointed owe untouched (logged, story over).
- The mirror ledger's meaning sharpens with it: `dropSent` marks only on
  a concluded story — a carrier death never marks, so the scan re-offers
  the shot and the row flies again when the peer answers.

Sim-side proof (protocol module): `a carrier death throws instead of
reporting` — nobody listening → the prepare throws
`TransferUnreachable`; a receiver that takes half a body and then loses
the carrier for good → attempts exhaust, the sender **throws** (not
reports), and the receiver's part + token sidecar survive the throw
untouched — that on-disk prefix is what the next flush's resume rides.
The receiver-alive half (death → sweep → hash-exact resume) stays
`deathRun`'s, still green. Hardware walk (redo, post-fix) recorded below.

## Milestone E design — self-hosted relay (written before code; parked behind B)

The problem E answers: two peers that can establish *some* outbound
reachability but never each other — different NATs, multicast-blocked
APs, no port forwarding, a phone on cellular. Everything C and A built
survives that world except one sentence: the candidate list runs out of
addresses that answer. E adds one more candidate: a relay both peers can
dial out to, run by whoever wants it (`flit-cli relay`), off by default.

**The shape: a blind pairer, not a server.** The relay knows exactly two
things per pair — that fingerprint A wants fingerprint B, and how many
bytes it shuttled. It never parses the protocol, never stores a file,
never terminates TLS. The sender dials the relay and opens with a fixed
preamble (magic, target fingerprint, optional shared token); the
receiver, while the engine runs, holds a long-lived subscription to the
same relay ("I am B; pair me"). The relay matches, says OK to both, and
then copies bytes both ways until either side closes. Inside that pipe
the peers run the *unmodified* protocol — TLS with their own identities,
register, prepare, upload, `/partial` probes — so the fingerprint proof,
the trust checks and every resume behave exactly as over TCP. A
malicious relay can refuse, delay or drop a pair; it cannot forge a
file, a fingerprint or a finished hash.

**The seams it touches (all tiny, all testable):**

- **Protocol module gains a pure relay core** (`relay/RelayCore.kt`,
  JVM-only): pairing state machine (register / request / pair / close,
  token gate, pair isolation, both-sides-live-only), unit-tested without
  sockets. The protocol module staying JVM-pure is a hard rule; a
  testing story that needs no Android is exactly why the core lives
  there.
- **`LinkProvider.connect` gains an optional target hint** (default
  null; TCP and sim providers ignore it). A relay route is a dial whose
  `host:port` is the relay and whose hint names the peer; only the
  relay-aware provider reads it. This is smaller and truer than minting
  a per-send client per route.
- **App module gains two thin Android-side pieces:** the relay dialer
  (TCP to relay, preamble, wrap in `TlsLink(clientMode = true)`) and the
  subscription worker (engine start → dial relay as self, hand each
  paired pipe to the server's external door — `ProtocolServer.serve`,
  which rides the exact same `ServerCore` and route semantics as the TCP
  listener, landed with E and shared with B; reconnect with the same calm
  backoff as the keeper). No new wire formats on the app side — the
  preamble is the relay core's, already tested.
- **Harness gains `flit-cli relay --port N [--token T]`** — a thin CLI
  wrapper over the same `RelayCore`, so the thing users self-host is the
  thing the tests exercise, not a lookalike.
- **Engine/roster:** one relay address + optional token in Options (off
  by default; empty = E is invisible). When set, the route candidate
  list gains a final relay candidate for any owed fingerprint — tried
  last, after live row, remembered route and manual addresses, so a
  direct answer always wins. The subscription starts with the engine and
  stops with it; nothing new is persisted per peer.

**The surface contract holds.** "Relay" is a preference the user
deliberately configures, like the manual-address field — not a carrier
mechanic leaking into the UI. Sends look and behave exactly like any
other send (progress, queue, hunt, cancel); a relay exchange that dies
mid-body throws `TransferUnreachable` like any carrier, the row stays
owed, and the retry resumes from the receiver's prefix through the
relay — no new states, no new copy.

**Honest edges, listed:** both peers must be live (no store-and-forward;
an offline receiver's files wait on the sender's queue exactly as today);
the relay operator sees metadata — which fingerprints paired, when, and
how many bytes moved (never content, aliases, or names — those ride
inside TLS); the relay is a single point of failure the user owns;
there is no relay *discovery* (the address is typed once per device, by
design — a discovery protocol for relays would be a public directory and
that is a different product); throughput is whatever the relay's uplink
is, which is why E stays opt-in and last.

**Test story:** `RelayCore` pairing unit tests (match, reject, wrong
token, both-sides-live-only, isolation between pairs, clean close); one
protocol parity send through the core over loopback pipes — including a
death + resume, since the relay must be transparent to C's machinery;
then the real walk: box runs `flit-cli relay`, phone and second peer
both subscribed, direct route removed, send lands hash-exact through the
relay, and a mid-body relay kill exercises the throw/keep/resume loop.

**Scope cuts, named:** no relay-side storage or queueing, no multi-relay
selection or failover, no relay directory/discovery, no per-peer relay
routing (one relay per device), no auth beyond the optional shared
token. Each is a later ladder item only if a real use demands it.

**Status (2026-11-24):** the app surface is in. `RelayLinkProvider`
(the sender's dial: preamble, then TLS inside the pipe) and
`RelaySubscriber` (the receiver's parked subscription; its socket hook
lets engine shutdown unblock a blocking read) live in the JVM-pure
protocol module alongside the core. `LinkProvider.connect` gained an
optional target hint, threaded through `SendTarget`, `PeerHttpClient`
and every `ProtocolClient` exchange — direct transports ignore it. The
engine dials through the relay only for peers whose last answer came
through one, starts/stops the subscription with the engine, and tries a
relay register only after every direct hunt candidate has failed. The
Options field is empty by default, so E stays invisible until a user
types an address. The harness end-to-end test now runs those production
endpoints with TLS inside the pipe, hash-exact.

**Status (2026-11-18):** the pure core landed exactly as specified —
`RelayHello` (the one-line preamble, strict parse, bounded reads) and
`RelayCore` (FIFO pairing per fingerprint, same-mode never pairs, ghost
tickets retired by `withdraw`, constant-time token gate), 11 tests in
`RelayCoreTest`. The harness surface landed too: `flit-cli relay
--port P [--token T] [--park-timeout S]` runs the accept/pair/blind-copy
loop over the same core, with 4 harness tests proving a real 3 MB
protocol send lands hash-exact through the pairer, a wrong token is
refused on the wire, an unpaired subscription times out cleanly, and a
dead relay dial surfaces as `TransferUnreachable` (never a report). The
app dialer/subscription workers and the engine/Options surface wait
behind B, per the ladder.


