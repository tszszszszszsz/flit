# Quality and confidence assessment

This assessment reads the shipped build's evidence as recorded in `docs/TESTING.md`,
the completion notes under `docs/completions/`, the audit dispositions in
`docs/ideas/PROGRAM.md` and `docs/roadmap/trust-gate-cert-binding.md`, and the
source. It follows the rule in `docs/TESTING.md` §1: where several levels apply
to a claim, the weakest one governs what may be asserted. High confidence here
means a claim is well supported within its stated scope, not that the scope is
wider than the observation. The deferred walk in
`docs/roadmap/device-walk-2026-12-31.md` is a plan, and the passage of time does
not turn it into evidence.

## 1. Scale of assurance

| Level | What it is | What it does not prove |
| --- | --- | --- |
| CODE | Source-level reading of gates, constants, wire shapes and call paths. `docs/ideas/PROGRAM.md` dispositions and the trust-gate design record use this level. | Runtime behavior. A gate visible in source can be reached with different inputs, and constants can be edited. CODE does not show that a path was exercised. |
| PURE | JVM-only tests of wire rules, codecs, reducers, tallies and policy math. The protocol module is JVM-pure; the app unit tests exercise Android-free seams and the harness is an in-process Linux CLI. | Android framework behavior, radio or camera operation, process lifecycle, persistence across a real install, or integration with a second device. Some rules are total only under a stated precondition: `CircleAuthority.foldable` assumes an earlier gate verified the event and reserved its id, so a structural answer from it is not an authenticity claim. |
| SIM-HARN | In-process runs over production endpoints — `ProtocolClient`, `ServerCore`/`LinkServer` and `RelayServer` with real TLS inside a pipe. PNG frames are rendered and decoded on one box. | A radio path, a second physical phone, a camera, or peer-side confirmation outside that box. A CLI-only exercise with no phone observation stays at this level. |
| DEVICE | An observation on physical hardware with logcat, UI capture, state files or a digest read tied to a phone-centred walk. | Anything not captured in that run. The recorded walks use one phone, one build and one session; they do not establish a larger mesh, a second phone, timing, throughput or power beyond the specific figures captured. Absence of an expected line is an observation; absence of a whole step is recorded as not verified. |

The current green figure is 778 tests — 362 protocol, 324 app, 92 harness — from a
fresh, forced re-run of the three test tasks with zero failures, errors and
skipped cases, with the assemblies green in the same run. That figure is PURE
and SIM-HARN evidence for the seams and in-process paths; it is not a device
observation. `docs/TESTING.md` §8 records a stale-output incident in which
UP-TO-DATE tasks reported green against an older class layout, which is why the
counts are read from the XML and cross-checked against source-level test
annotations. Historical totals (302, 588, 714, 769, 773 and others) are
snapshots of earlier layers, not the current inventory.

## 2. Areas with high confidence, and the exact evidence

**v2.2 wire parity and interop.** `DtoParityTest`, `LinkServerParityTest` and the
CLI `bt-sim` run one unchanged v2.2 implementation over TCP/Netty, an in-memory
duplex pipe and a simulated Bluetooth carrier, with no v3 fork. `DtoParityTest`
pins announce/register/prepare DTOs against upstream fixtures;
`LinkServerParityTest` keeps a part across receiver death and resumes
hash-exact. Interop with `localsend-cli` v1.18.2 is recorded both directions in
`interop/README.md`; the day-1 multicast diagnosis was corrected on day 2 after
the app's announce transmit was wired.

**Resume, death and partial-state handling.** `ChunkTrackerTest` (6),
`PartialSidecarTest` (1), `ResumeAndTurboTest` (5), `ProtocolRefusalTest` (4)
and `RelayCoreTest` (11) pin the central contract: a carrier death throws
instead of reporting success, a returned report means the peer spoke, and kept
bytes stay owed. The sidecar exists before the first body byte. Device evidence
in `docs/TESTING.md` §6 backs the Wi-Fi case (a 300 MiB shot cut 0.8 s after
`recv-start` with a 33,980,416-byte part, resumed hash-exact to SHA-256
`fbc4684a30afd…` with no leftover part) and the relay case (a 300 MiB shot cut
by killing the relay, resumed from 31,866,880 bytes, hash-exact with no leftover
part). A late kill near completion took a clean full resend.

**Fingerprint, pinning and replay primitives.** `TlsIdentityTest` (2) and
`PinCacheTest` (6) pin fingerprint derivation as uppercase hex SHA-256 of the
certificate, a bounded LRU pin store, and cert-in-hand verification that never
touches the shared pin store. `CircleCoreTest` (12), `CircleHardeningTest` (14),
`PanicTest` (6) and `FlitCircleHostTest` (22) cover forged authors, bounded seen
sets, replay windows and host governance. The 2026-11-27 partial walk carried a
pinned host fingerprint on the phone's join; the three-node baseline on
2026-11-26 recorded a promoted admin, a kick with rekey, and C refused as a
non-member with `!! sync refused: host refused: not-a-member`.

**Signed JOIN proof and shared authority law.** §4.8 of `docs/TESTING.md`
groups 15 classes. `JoinProofTest` (14) checks an independent golden vector for
the length-prefixed `FLITJOIN1` nonce, per-field binding, the closed `{proof}`
body, the three refusal reasons (`join proof required`, `invalid join proof`,
`join proof replay`), and that a forged author neither verifies nor pins the
victim. `CircleAuthorityTest` (12) checks the full `foldable` truth table: JOIN
never folds, REKEY folds only for a current owner or admin, ordinary verbs fold
only in their own circle, and hostile input stays total. `CircleHardeningTest`
(14) pins the proofless, valid and forged JOIN route branches;
`CircleHarnessTest` (18) adds proof-gate, host-pin and authority cases. A
pre-proof JOIN is refused at admission.

**Per-drop keys, receipts and pad law.** `DropKeyCoreTest` (18),
`DropKeyBodyTest` (14) and `DropKeyCryptoTest` (12) pin key minting, the closed
keyed body, wrap binding to the certificate's fingerprint, and `IV ‖ ciphertext
‖ tag` open with AAD. `ReceiptBodyTest` (12), `ReceiptTallyTest` (6) and
`ReceiptCanonicalTest` (6) pin the `{dropId,sha256,keyId}` body and
`receiptAuthors` as verified current-member authors with at most one receipt
per member. `PadBodyTest` (16), `PadViewTest` (9), `PadMergeTest` (11) and
`PadCanonicalTest` (7) pin the closed pad body, greatest-order-key convergence
and merge idempotence. The parity layers (§4.4–§4.6) add 31, 14 and 23 cases
that drive these rules through app and CLI seams. These are PURE and SIM-HARN
claims; their device runtime is addressed below.

**Install and launch on one physical device.** `docs/completions/2026-11-26-device-install-smoke.md`
records a Redmi `M2101K7BNY`, Android 17 / SDK 37, APK SHA-256
`aeb6dafc…5f7ed`, versionName 0.3.1 / code 4: install succeeded after removing
the 0.2.0 build, `MainActivity` became the focused window with no fatal logcat
line, and the captured screen showed the dock, Circle, QR parcel, Nearby devices
and bottom action bar with readable text. Its own limitation stands: layout and
text only, no interaction semantics or accessibility behavior.

**Ideas audit dispositions.** `docs/ideas/PROGRAM.md` records a review of 20
prototypes and keeps the reason for each disposition. Only `PadSearch`,
`PeerAlias` and `NoiseBudget` were wired; the other 17 were removed. The wiring
is bounded: hits carry offsets into existing text, aliases feed display labels
while full fingerprint equality still governs verification, and the BLE duty
check is an observation-only audit that the carrier arbiter never reads.

## 3. Medium confidence, with the specific gap

**Outbound phone→host Circle fan-out.** `docs/TESTING.md` §6 classifies the
general phone→host fan-out observation as test-evidenced only. The 2026-11-24
walk could not produce a clean peer-side confirmation because one-shot CLI bots
shared CLI state with their `serve` process. The 2026-11-26 walk used distinct
harness homes but still had no listener on the box nodes; a phone-composer event
appeared in the phone feed with `full-log repair to 2 peer(s)` and `refused no
answer` lines but in neither one-shot puller's `circle.state`. The 2026-11-27 partial walk adds a narrower DEVICE observation:
after the phone posted, logcat showed `circle fan-out: 1 new event(s), 1 peer(s), 1 backed off` and
`circle fan-out: ok to C53120B19DFB (1 event(s) offered)`, and the CLI peer's `circle sync` showed the message
under author `6A61F0ED`. The remaining gap is confirmation across
multiple peers and with a box node that runs a listener; no general
device-level fan-out claim is made beyond that recorded flow.

**Pad and poll rendering on the phone.** The partial walk observed a CLI pad
line as `1 line` in the modal pad sheet and `1 hit` after typing `walk`, and a
phone vote appearing in the CLI tally as `[0=1]`. The same note records that the
Circle feed rendered the pad event as `Unsupported circle event / pad` even
though the pad sheet showed the line. That is a display observation, not data
loss, but the feed and pad sheet are separate views. Convergence and merge for
pad stay PURE.

**Keyed drop and receipt runtime on the phone.** The law and CLI/app seams are
green at PURE and SIM-HARN level, including `DropParityTest` and
`ReceiptCliTest`. `docs/TESTING.md` §6 lists the hash-exact Circle file drop as
in-process harness evidence only (8,192 B through production server and client).
No keyed-drop landing, unwrap or receipt minting was observed on the phone in
the partial walk.

**Trust gates.** Both gates are wired in `FlitEngine.onPrepare` at CODE
level. `qrOnly` also has PURE coverage for its persisted default and toggle in
`FlitKnownTest`; `onlyTrusted` has no PURE gate test. No device walk has
toggled either gate and captured the paired trusted/untrusted prepare answers;
Flow 8 of the deferred plan is unperformed.

**BLE policy and duty arithmetic.** `FlitBleTest` (36) and `FlitBluetoothTest`
(14) pin the arbiter, rank rule, permission split, backoff and the 10 s on /
45 s off duty constants; device observations from 2026-10-29/30 cover the
permission sheet, radio flips, hotspot classification and an idle soak. The
`NoiseBudget` check is arithmetic over the schedule and is silent when it
passes. No airtime measurement, peer reachability over BLE or phone-to-phone
RFCOMM is covered by those tests.

## 4. Not established

- **Two-phone RFCOMM transfer.** No second phone was on the rig, and the bench
  box has no Bluetooth adapter (`bluetoothctl list` empty). Only the toggle →
  `bluetooth link: listening` smoke and the pipe/`bt-sim` parity runs exist.
- **A mesh larger than three identities, or two physical phones.** The
  2026-11-26 baseline is DEVICE evidence for exactly three identities — one
  phone and two one-shot CLI bots on one box, gen=0. It does not prove a larger
  mesh. Section 6 of `docs/TESTING.md` keeps the general outbound fan-out row
  at test-evidenced only; the older `docs/circle.md` limitation is superseded
  only for the exact three-identity case.
- **Wi-Fi Aware.** `adb shell pm has-feature android.software.wifi.aware`
  answered `false` on the rig phone; no Aware carrier was built.
- **MIUI battery and autostart behavior.** MIUI can still kill the process. The
  persisted sharing-desired row restores sharing on the next open, not
  autonomously. At the Android 15 `dataSync` 6 h cap, `onTimeout()` drops the
  notification to avoid a system crash while a Flit screen is open; no
  background guarantee after the last surface closes is established.
- **Receipt minting on device.** In the partial walk the only file sent was a
  direct peer send rather than a keyed Circle drop, so `circle receipt` refused
  with `no key id for drop`. No on-device receipt body or confirmation count was
  captured.
- **Officer-only rekey walk.** The partial walk used a plain-member CLI peer and
  a host driven by `serve`, so no officer action was performed from the phone.
  Officer-only fold rules and the same-batch hand-off veto stay PURE and
  SIM-HARN.
- **Courier handoff.** Frames were generated on the box, but there was no second
  screen for the phone camera and no courier bag exchange was run. Bag
  transitions are SIM-HARN evidence from `CourierCliTest` and
  `CourierCarrierHarnessTest`, with the bag moved between directories on one
  box.
- **QR scan on phone.** Encoding happened on the box only. `ParcelCodecTest`
  (21), `ParcelCodecTamperTest` (10), `ParcelScanStateTest` (18) and the CLI PNG
  round-trip in `ParcelCliHarnessTest` (3) are in-process; no camera path was
  exercised.
- **Timing, throughput and power in the partial walks.** The 2026-11-26 smoke
  and 2026-11-27 partial walk took none. The separate on-device ceiling figure
  in `docs/TESTING.md` §6 (512 MiB in about 15.9 s, 32.1 MiB/s, about 270 Mbps)
  is a measurement of that one Wi-Fi link and phone storage, not a framing or
  power claim.

## 5. Known open items

**JOIN proof replay is bounded, not durable.** The proof is valid on a
five-minute bucket grid with a one-bucket tolerance; across further rollovers
the same proof is replay while the invite remains a bearer credential usable
under its holder's own key while valid. `docs/completions/2026-12-22-join-proof.md`
states that a captured proof is single-use only in-process: the id is reserved
once in a process-local bounded seen set at `MAX_SEEN = 4096`, a restart or
another process has no memory of the reservation, and eviction can forget an
older id. There is no shared or durable replay store.

**Trust gates still compare a declared fingerprint on the open default path.**
`MainActivity.kt` initializes `onlyTrusted` to `false` and `FlitKnown.kt`
initializes `known.qrOnly` to `false`. In `FlitEngine.onPrepare`, both gates
compare `request.info.fingerprint` — a caller-declared, unsigned string in
`PrepareUploadRequest.info` — against a persisted pin or Known row. A sender
who knows a trusted fingerprint can pass either gate. The gates are opt-in and
off by default; enabling one changes the decision but does not bind it to the
TLS peer certificate.

**Certificate binding is parked.** `docs/roadmap/trust-gate-cert-binding.md`
evaluated Option A (ask for a client certificate during TLS, derive the DER
digest and require it to equal both the declared fingerprint and a persisted
pin) and Option B (carry a self-signed certificate header at prepare time and
check its digest against the declaration). Neither was shipped in v0.3.1: the
record notes carrier surgery across three transports for Option A and a changed
prepare header set with an opt-in gate for Option B, and states that no concrete
requirement justified either wire change. Option B is named as the first
candidate if the requirement returns. Whichever option is chosen still needs
tests for no certificate, claimed/presented mismatch, plain HTTP, Bluetooth,
relay, restart and pin rotation, with only a short fingerprint code logged.

## 6. What would raise confidence

`docs/roadmap/device-walk-2026-12-31.md` sets out the work. It is a plan with no
walk recorded under it; results belong in a separate
`docs/completions/<actual-date>-full-feature-walk.md` written from the template
at the end of that file. Its rules are the mechanism for raising confidence:
record artifacts and hashes before the flows, keep one log capture through all
flows, use fixed fixtures with digests read in the run, give node B and node C
distinct `FLIT_HARNESS_HOME` values and share neither with a `serve` process,
and assign each row the weakest level that supports it. A DEVICE entry is valid
only with its log or observation excerpt attached; an absent expected line is
recorded with a timestamp, and an unrun step stays not verified.

The ten flows are the means to close the following specific gaps. Each gap
closes only where an observation is actually captured; negative branches and
unshown directions remain at their prior level.

- Flow 1 — founding QR, two signed-proof joins, host lines and a three-member
  roster on the phone; negative proof branches stay SIM-HARN unless a malformed
  or stale proof is emitted on the rig.
- Flow 2 — inbound post, poll and changed votes, plus an explicit check of
  whether a box node's `circle.state` contains the phone-authored event, which
  is the recorded fan-out gap.
- Flow 3 — keyed drop with the exact seven body keys, hash-exact phone landing,
  opened plaintext digest against the announced `sha256`, and C's `drop-open`
  line when its listener is up.
- Flow 4 — receipt body `{dropId,sha256,keyId}` and the confirmation count on
  both the phone card and the CLI, with the count read as signed local-log
  authors, not delivery to every member.
- Flow 5 — pad put/del/show across phone and both box homes, including the
  tombstone and the feed display observation.
- Flow 6 — courier bag rows before and after the directory copy, the opened
  JSON, replay result and whether a phone carry line was actually present.
- Flow 7 — officer-only promotion, kick, forced rotation and departed-member
  refusal with the phone as host; the crafted non-officer REKEY branches stay
  PURE and SIM-HARN unless emitted on the rig.
- Flow 8 — the trusted/untrusted prepare pair with `onlyTrusted` toggled and
  the landed-file difference recorded; the QR-only variant is a separate setting.
- Flow 9 — no-AP arbiter lines, permission dialogs and the presence or absence
  of a duty-audit line, with radio duty still unmeasured.
- Flow 10 — QR parcel encode/decode in both directions with frame captures,
  landed digest and no partial or queued line; a one-box loopback stays
  SIM-HARN and a direction with no display surface in front of the camera stays
  not verified.

The plan explicitly assumes no second physical phone, so phone-to-phone
RFCOMM, a live multi-phone mesh and Wi-Fi Aware remain outside it and need a rig
with that hardware and the Aware feature. MIUI autostart behavior needs a soak
test with the last surface closed, not another app-screen capture. Closing the
JOIN replay and declared-fingerprint gate items needs code changes plus the
tests named in `docs/roadmap/trust-gate-cert-binding.md`, not further walking
of the current build.
