# Flit transport — ladder and design index

Brief (user, 2026-10-27): "make it simplest and most complex at the same time."
The original Bluetooth contract was **the UI never learns Bluetooth exists**.
That contract was deliberately revised on 2026-11-24: an explicit opt-in
**Transfer over Bluetooth** option now exists, and **QR-only mode** lets a user
refuse every device that did not enter their graph by QR. Everything else still
holds: no accounts, no cloud, no telemetry, no silent carrier switches.

> **Non-profit constraint (hard, everywhere):** no accounts, no cloud, no ads, no
> telemetry, and no SDKs that phone home. Every design below is judged against it —
> there is no server to register with and nothing to collect; peers connect directly.

## Focused design documents

- [Bluetooth carrier — design and hardware walks](bluetooth-carrier.md) —
  the Link/LinkProvider seam, TLS over any pipe, BLE discovery/arbiter, RFCOMM
  carrier, the one-phone walks, and the honest two-phone limitation.
- [QR-only mode, known devices and panic](qr-only-and-panic.md) —
  the free-will base: QR v2 pairing, per-device route policy, signed alerts,
  and the safety rule that nothing ever auto-acts on a recipient's device.
- [Circle — accountless group coordination](circle.md) — **v1 built**:
  QR-founded groups with a signed (TLS-protected in transit, plaintext at rest)
  local event log, file drops and re-votable polls; host/role enforcement,
  threat model, scope cuts, evidence and honest limitations.
- [Milestone C — process-death checkpoints](milestone-c.md) —
  queue rows before bytes, receiver sidecars, and the hardware walk.
- [Milestone D — album mirror](milestone-d.md) —
  New camera shots ride the queue to one trusted peer.
- [Milestone A — carrier migration](milestone-a.md) —
  hunter, death contract, the 2026-11-18 defect and the passing redo walk.
- [Milestone E — self-hosted relay](milestone-e.md) —
  blind pairer, `flit-cli relay`, app surface, and the relay-kill walk.
- [Play listing draft](play-listing.md) — store copy and the data-safety form
  (zero collection), kept honest against the constraint above.

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

