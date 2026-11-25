# Bluetooth carrier — design and hardware walks

> Part of the Flit transport design. [Main ladder and index](transport-bluetooth.md).

# Phase B — Bluetooth as a fallback transport

The **original contract** was **the UI never learns Bluetooth exists; the engine never
forgets** — no settings, no toggle, no new vocabulary. That contract was **superseded
on 2026-11-24** by an explicit opt-in **Transfer over Bluetooth** option (off
by default); see the dated correction in `RELEASE_NOTES.md`. Outside that one option the
surfaces are still unchanged: if a Wi-Fi access point exists between two devices, Flit
behaves exactly as it does today. When it can't exist (mobile data only, hotspot dead,
airplane mode with radio toggled), a peer simply shows up in the same list and files
stream the same way — slower, labeled by the existing speed readout.

The depth is spent underneath. Most of it is in the engine, not the surface — but the engine
*does* have explicit carrier branches (see "Transport arbitration" below); only the
protocol wire itself is transport-blind.

## The one suspension of disbelief

The wire protocol (v2: `/info` `/register` `/prepare-upload` `/upload` `/cancel`, the
flit `/partial` resume extension) is HTTP/1.1 over TLS, spoken by a hand-rolled client
(`PeerHttpClient` — raw `Socket`, one exchange per connection, fingerprint-pinned, no
hostname verification by design) and served by Ktor/Netty over TCP.

Bluetooth offers a reliable byte stream (RFCOMM — the built carrier; there is no LE L2CAP
CoC path in the code). TLS runs fine on any reliable stream. Therefore: **the same
protocol semantics ride an unchanged wire brain over a second pipe.** No protocol fork
and no v3: the client (`Link`/`LinkProvider`) and server (`LinkServer`) seams are shared.
That claim is about the *protocol*, not the whole app — the engine has explicit carrier
branches (TCP, relay, Bluetooth; see "Transport arbitration" below). Resume tokens
(`/partial`, ChunkTracker) behave the same **within one carrier**, not across carriers:
the kept prefix is bound to the requester's origin (an IP over Wi-Fi, a MAC over
Bluetooth), and `ServerCore.partsBase` refuses a part whose `senderIp` differs from that
origin, so a Wi-Fi↔Bluetooth switch takes a clean full resend. A **receiver restart**
is the remaining exception to "drop reconnects and continues": the in-memory session
is gone, and resume works only because the `.token` / `.pieces` sidecars are swept
back into the parts registry at start (`repopulateParts`), which re-binds the kept
prefix to the new requester's origin.

## Surface contract (hard rules)

1. **Zero new strings on the happy path — with a short exception list.** The roster
   row, transfer strip and snackbars are unchanged. If the engine ever must explain
   itself it borrows the existing voice ("no Wi‑Fi between us — trying the direct way"),
   never the word Bluetooth. The exceptions are (a) the opt-in **Transfer over Bluetooth**
   row in Options, (b) the per-device route-policy labels **Auto / Wi‑Fi / Bluetooth /
   Relay** in Known devices, and (c) the carrier log lines described below. None
   of them renames a roster row or a transfer strip.
2. **Original "zero new settings" contract — revised 2026-11-24.** The
   original contract had no Bluetooth setting at all; the option exists to reach
   peers when no AP is available, so Options now carries **Transfer over Bluetooth**
   (off by default, inert when left off). The rest of the rule holds: power-on stays a
   contextual system ask, once per device, in the system's own voice. Turning the option
   on opens an RFCOMM listener and lets the hunter dial a peer's Bluetooth address;
   **advertising is connectable only while that carrier is on** (`connectable =
   btClient != null` in the engine) — discovery-only advertising stays non-connectable.
   A permission or radio-power consent answered after the toggle rebinds the carrier
   once, so a late grant still brings the listener up. Wi-Fi still wins whenever it
   answers: Bluetooth is a fallback carrier, not a faster one.
3. **Permissions are contextual too.** BLUETOOTH_ADVERTISE + BLUETOOTH_SCAN +
   BLUETOOTH_CONNECT (API 31+; the Redmi test device is API 36, dynamic color stock, so
   this is the primary path) are all three asked together as the system's
   Nearby-devices sheet at the precise moment a no-AP transfer needs them, with a
   one-line explanation. Never on app launch, and never bundled with unrelated
   permissions (the three radio permissions are one OS-defined group).
4. **The roster stays one list.** A dual-transport peer is one row. "2 online" counts
   peers, not transports (this is also where the pre-existing loopback self-peer bug
   gets fixed — B3's natural test case).
5. **Trust semantics unchanged.** Fingerprints pin over BLE discovery exactly as they
   pin over mDNS today; QR codes already carry them; the trust toggle, destination
   trees and only-trusted gate apply to both transports verbatim. Trust is this QR /
   `TlsIdentity` fingerprint pinning — **not** OS bond state; see "First-contact cost".

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
duplex stream in server/client-mode TLS with the same TlsIdentity). Framework code
in crypto terms: ~150 lines, well-trodden, unit-testable against an in-memory pipe.

**2. Discovery = BLE advertise + scan.** Own clean-room service UUID flips on only
with the engine. The announce rides **service data** under that 128-bit UUID (via
`addServiceData`, not manufacturer data) and carries a binary fingerprint plus alias
on extended advertising; the legacy record carries only an 8-byte fingerprint prefix
and no alias. There is **no model field** in the BLE payload (the UDP announce has one;
the radio does not). Scan builds roster entries attributed `source: ble`. Dormancy
rule: while a direct AP is up, the radio stays dark — Wi-Fi discovery owns the list.
The arbiter flips to BLE only when the AP criterion fails; flipping back is automatic.

**3. Transport arbitration.** Rank: Wi-Fi (any sane AP) → Bluetooth, with the opt-in
relay as a last resort. The engine has explicit carrier branches here: it builds a TCP
client, a `RelayLinkProvider` client and a `FlitBluetooth.LinkProvider` client and the
hunter carries a route `kind` (TCP/Bluetooth) so a MAC is never dialed as an IP. Each
dial is pinned to the carrier that chose it. A mid-transfer drop **resumes only on the
same origin**: Wi-Fi parts key on the peer's IP, Bluetooth parts on its MAC, and
`ServerCore.partsBase` refuses a part whose `senderIp` differs from the requester's
origin. A Wi-Fi↔Bluetooth switch therefore takes a clean full resend, not a
cross-carrier continuation. Carrier enablement is opt-in; there is no per-transfer
manual carrier switch.

**4. First-contact cost.** The OS may insert itself: incoming RFCOMM
from an unpaired device can raise the system's own pairing/bond consent sheet. **Flit has
no bond-state listener** — accepting that system dialog does *not* pin a peer in Flit's
roster. Trust is always QR-invite fingerprint pinning (`TlsIdentity`), exactly as over
Wi-Fi; the OS dialog is a platform step around the carrier, not a Flit trust decision.
Flit cannot remove the sheet, only trigger it.

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
keeper and the row ages out. Empty rounds say so once, then stay quiet
while the rounds keep running. Edges: the sweep
knocks only the default port (custom-port servers stay reachable by address or
multicast, not by sweep), it rides Wi-Fi subnets only (cellular or wired networks are
not walked), a peer found by sweep is found *because it serves* — a powered-off or
firewalled machine is invisible, same as for any connect-based discovery — and the
probe's register budget (400 ms) can be outrun by a cold or slow TLS server: a peer
whose handshake is slower than the budget stays invisible to the sweep while
remaining visible to multicast and manual address.

## Verification reality (one Android test device)

**As-built RFCOMM listener, retries and logs.** The accept loop acquires a
listener only while `BLUETOOTH_CONNECT` is granted and the radio is on; while either
is missing it logs `bluetooth link waiting — permission or radio off — retry in Ns`
and retries on a calm 2 → 4 → 8 … → 60 s backoff, so a consent answered *after* the
option was enabled still brings the listener up. A `listen()` failure is logged as
`bluetooth link listen failed: … — retry in Ns` and retried likewise.
`bluetooth link: listening` is written **only after** a socket was actually
published, and `bluetooth link: stopped listening` only when that publication
ends. A transient `accept()` failure closes that listener and re-listens behind
the same backoff (`bluetooth link accept failed: … — retry in Ns`); cancellation
is the only real stop. Each accepted socket is TLS-wrapped with a timeout and fed
to `LinkServer`; socket and handshake failures get their own
`bluetooth link socket:` / `bluetooth link TLS handshake …` lines rather than a
success claim. All of this is JVM-testable policy, but a real two-phone RFCOMM
handshake has no hardware here (see the walkthrough record).

- `PeerHttpClient`'s socket construction and the Bluetooth link both sit behind the
  `Link`/`LinkProvider` seam (the server side feeds `LinkServer`), injectable — the
  entire protocol-parity suite runs the **same tests over an in-memory duplex pipe
  through the real hand-HTTP + TLS shim stack**.
- Harness `bt-sim` (B4, shipped): both ends of a run in one process — the receiver
  is the same `ServerCore` the TCP binder feeds, spoken to through the same
  `LinkServer` the RFCOMM binder rides, wrapped in the same `TlsLink`; the sender is
  the production `ProtocolClient` over `SimLinkProvider`. Green runs (TLS/plain, pin,
  multi-file, synthetic worst-case bodies) print the send rig's usual report with the
  same exit codes; `--no-tls` runs label `/info` `http`. The pipe carrier
  lives in `protocol.transport`, shared verbatim with the parity suite.
- On-device checklist (bluetooth radio real): permission-sheet wording, radio-off
  flow and the arbiter's hotspot moments — all walked 2026-10-29/30 (record below).
  The advertisement seen by *another* BT source stays parked with the two-device
  contact. True two-phone transfers wait for a second phone — against the
  harness-rig standard, that is a listed limitation, not a silent gap.

## Walkthrough record (2026-10-29, Redmi Note 10S / API 36)

- **B2 smoke**: radio-off first observed as silence (no flip line, no surface
  noise); enabling → flip line once; stack's own advertiser map shows the service UUID
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
  the surface. Flip-back walked after the fix: with the radio dimmed under a
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
  churn — no ask raised, ≤5 s of advertising, self-healing. Deliberately not
  smoothed with hysteresis: the arbiter evaluates facts each tick, the flutter is
  invisible to asks and to the surface, and the flipper reflects whatever instant
  it surveys. The `FlitEngine` comment's coarse corner resolves: on this
  build, coarse is accurate.
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
  left them (Bluetooth off), and the only log lines the mprober's refusals
  against the serve-down box.
- **Interoperated on hardware (same walk)**: the harness `send` — the production
  protocol client — discovering the phone and delivering a file (`from-box.bin`) that
  landed in `Downloads/Flit/` with ledger row "_received_ from Flit-sender". Reference
  CLI, the engine, real radio.
- **Bluetooth data carrier landed (2026-11-24)**: BLE was discovery-only until
  now. `FlitBluetooth` adds the real RFCOMM dialer and accept loop on the
  existing `Link`/`LinkProvider` + `LinkServer` + `TlsLink` seams: TLS and the
  whole v2.2 protocol run inside the socket, so trust, resume and the death
  contract are unchanged. BLE advertising becomes connectable only while the
  carrier is on. A new **Transfer over Bluetooth** option (off by default)
  binds it; the hunter carries a route `kind` (TCP/Bluetooth) so a MAC never
  gets dialed as an IP. On-device smoke: toggling the option restarted the
  engine and logged `bluetooth link: listening` with no
  crash. **Not verified:** a real phone-to-phone RFCOMM transfer — there is no
  second phone, and the bench box has no Bluetooth adapter (`bluetoothctl
  list` is empty), so the real socket path cannot be exercised here. Listed.
- **Speed, measured 2026-11-24**: a 512 MiB file from the reference CLI to the
  phone completed in 15.9 s both single-stream and multi-socket — **32.1 MiB/s
  (~270 Mbps)**. The ceiling is this Wi-Fi link and the phone's storage, not
  Flit's framing; turbo is resume-aware but cannot outrun the radio. Bluetooth
  is a reachability fallback, never a speed upgrade. The in-app readout was
  also fixed: `FlitSpeed` samples independently of the 350 ms
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
    consecutive misses retire it and the row ages out. After the fix the
    row held at 2.5 min ("1 online") with exactly one found line in the log — the
    walk stood down once the roster was fed.
  - *A working peer, not a ghost*: the send chip on the sweep-born row opened the
    picker; the picked file went through — `sent 1/1 to Flit (szszszs):
    batch-b.bin`, received on the box's disk. No multicast, no typed address, no
    QR anywhere in the story.
  - *Retire*: serve killed → the row pruned itself (~25 s, the roster's
    TTL expiring for a peer that stopped serving) → the next walk ran
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
    the 400 ms register budget. Now listed: a slow or cold v2.2
    server is invisible to the sweep; it still arrives by multicast when it
    announces, by manual address, and answers a probe once its handshake is
    fast (a warmed desktop JVM).
- **Sweep restraint matrix, all on device (same walk)**: rounds only when the
  roster holds no live row (log silence throughout every live-row stretch, firing
  within one tick of the pruner emptying it); ≥120 s between walks; ≤24 knocks in
  flight; empty rounds logged once; found rows kept fresh and retired;
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
  disk (`batch-b (1).bin`, dedup against the earlier arrival) → CLI→phone
  send landed in the phone's `Downloads/Flit/` (`sent 1/1 … ok cli-hello.txt`).
  Identity home stays `~/.config/flit/flit-harness`, so fingerprints survived the
  rename: every peer that trusted the rig keeps trusting the product.

