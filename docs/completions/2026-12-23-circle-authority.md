# 2026-12-23 — Circle authority in app and CLI (Integration)

Layer: 2026-12-23 Integration. The JOIN proof and the fold-time authority gate
landed in the protocol layer on 2026-12-22 are enforced in the app and the
`flit-cli` Circle surface. Joining mints a signed proof from the same identity
the device records as itself. Inbound events pass one fold gate on the app and
the box host: a JOIN is never folded out of an answer, a REKEY folds only from
a current owner or admin, and the replay guard verifies before reserving an id.
The CLI pins the host fingerprint given at join and verifies later answers
against it.

The layer adds no route, header, JSON DTO field, QR parameter, persisted store
field, schema field, signature primitive or v2.2 status. It consumes the
protocol seams recorded on 2026-12-22 and does not redefine them. Counts come
from the JUnit XML of a fresh JVM run under `protocol/build/test-results/test/`,
`app/build/test-results/testDebugUnitTest/` and `harness/build/test-results/test/`.
No device observation is claimed.

## Files in this layer

| File | Content |
| --- | --- |
| `app/src/main/kotlin/app/flit/share/FlitCircleSync.kt` | `join` mints a signed JOIN proof; `fresh` is the single eight-stage fold gate; opened courier events use `CircleAuthority.foldable` with no role data; reset clears the process-local replay set |
| `app/src/main/kotlin/app/flit/share/FlitCircleStore.kt` | `merge` snapshots the pre-batch roster and the set of same-batch leave authors; `reduce` and `applyRekey` take that authority context; the two-argument forms keep their signatures |
| `app/src/main/kotlin/app/flit/share/FlitCircleHost.kt` | The SYNC admission filter delegates JOIN/REKEY decisions to `CircleAuthority.foldable`; a departure forces a host rotation whose generation outranks a same-batch admission |
| `app/src/main/kotlin/app/flit/share/CircleUi.kt` | Additive UI surface following the changed authority flow |
| `app/src/test/kotlin/app/flit/share/FlitCircleSyncTest.kt` | Added cases for proof minting, the `fresh` stages and the courier fold |
| `app/src/test/kotlin/app/flit/share/FlitCircleHostTest.kt` | Added cases for same-batch authority and forced rotation |
| `app/src/test/kotlin/app/flit/share/FlitCircleStoreTest.kt` | An existing rekey case restated for the officer-only rule; no case added |
| `harness/src/main/kotlin/app/flit/share/harness/Harness.kt` | `--host-fp` pin, host-answer verification, proof minting at `join`, shared authority fold, host-key learning from signed REKEY events only |
| `harness/src/main/kotlin/app/flit/share/harness/CourierCli.kt` | Opened parcel events must pass the shared fold gate with no role data |
| `harness/src/main/kotlin/app/flit/share/harness/ParcelCli.kt` | Encode cap check before rendering |
| `harness/src/test/kotlin/app/flit/share/harness/CircleHarnessTest.kt` | In-process proof, authority, key-pin and host-answer cases |
| `harness/src/test/kotlin/app/flit/share/harness/CourierCliTest.kt`, `harness/src/test/kotlin/app/flit/share/harness/PadCliTest.kt`, `harness/src/test/kotlin/app/flit/share/harness/ParcelCliTest.kt` | In-process CLI cases in the suites carrying this wave |
| `docs/SECURITY.md`, `docs/CONTRACTS.md`, `docs/FAILURE-MODES.md`, `docs/OPERATIONS.md`, `docs/TESTING.md` | Seam contracts and refusal branches; consolidated in the 2026-12-24 Docs note |
| `docs/completions/2026-12-23-circle-authority.md` | This layer record |

## Exact gate rules

`FlitCircleSync.fresh(state, events, nowMs)` is the single fold gate for peer
and parcel events, in this order:

1. `CircleCore.validShape` and an exact circle-id match;
2. `CircleAuthority.foldable(event, state.id, roles)` with the roster's
   fingerprint-to-role map — JOIN never folds, REKEY needs OWNER or ADMIN;
3. type-specific body gates for FILE and PARCEL, then one bounded JSON object;
4. `CircleCore.verify`, before any id is reserved;
5. deterministic `CircleCore.order` and `CircleCore.dedup`;
6. a hash set of local log ids, so an already-stored event is not news;
7. the bounded `FlitCircleStore.EVENT_CAP = 500`;
8. `CircleCore.accept(event, nowMs)`, the shared clock and replay guard.

Only survivors reserve an id. `nowMs` is injectable so the window decision is
testable without sleeping.

`FlitCircleStore.merge` snapshots `rolesOf(state.members)` and
`leavingAuthors(events)` once at entry; the four-argument `reduce` and
`applyRekey` use that immutable context. A REKEY installs a key only when
`CircleAuthority.foldable` admits it against the pre-batch roster and the
author is not a same-batch leaver. Fold order alone cannot promote a signer or
let one hand off a key while leaving. `gen` is mandatory, non-negative and at
least the local generation, so an old rekey cannot roll the key back.

`FlitCircleHost` applies the same `foldable` decision inside its SYNC filter,
before verification and id reservation, and excludes a JOIN contribution. On a
departure it forces a host rotation: the floor generation is one above the
highest REKEY generation admitted from a non-leaver in the batch, saturated so
a corrupt `MAX_VALUE` cannot wrap negative, and the local generation is raised
to that floor before rotation. A same-batch leaver's hand-off installs nothing.

Opened courier events use `CircleAuthority.foldable(event, state.id, emptyMap())`
plus the current-member check: with no role data neither privileged verb can
enter from a parcel, even for an officer.

The client JOIN uses `CircleCore.joinProofBody(host, circleId, invite, me,
CircleCore.joinProofBucket(now))` and signs it with the same identity recorded
as `selfFp`; a claimed fingerprint that is not the signing identity refuses
before a dial. The host answer is verified against the QR fingerprint before
anything is persisted, including a refusal. The CLI follows the same rule:
`--host-fp` pins the fingerprint printed by `serve` at join, every later answer
must be signed by that pin, and rotated keys are learned only from host-signed
REKEY events in the signed log, installed by generation. A key carried in an
unverified answer or a sync response is never adopted.

## Rig inventory

| Module | Before | After this layer | Basis |
| --- | --- | --- | --- |
| `protocol` | 359 | 359 (unchanged here) | JVM-pure protocol tests |
| `app` | 309 | 323 | JVM tests of Android-free seams |
| `harness` | 85 | 87 | In-process CLI and end-point rig |
| Total | 753 | 769 | 0 failures, 0 errors, 0 skipped |

The app increase is 14 cases; the harness increase is 2. All assemblies are
green at this inventory. No timing or hardware result is attached to these
counts. The 2026-12-24 Docs and 2026-12-28 Release layers add no tests, so 769
is the final green rig figure at this layer (historical); the current inventory is in `docs/TESTING.md` §1.

## Tests

| Class | Cases | What it pins |
| --- | --- | --- |
| `FlitCircleSyncTest` (added cases, app) | +12 (56 total) | An admin-signed REKEY admitted through sync; same-batch hand-off and pre-batch authority order independence; `fresh` accepting a verified event once and refusing an id replay, refusing events outside the window on either side, verifying before reserving so a forgery cannot burn a valid id, dedup by id in canonical order, local-log filtering and batch cap, role-aware REKEY with no folded JOIN; JOIN minting a proof bound to host, circle, invite, member and bucket; a claimed fingerprint that is not the signer refused; courier receive refusing officer-signed governance even from a roster officer |
| `FlitCircleHostTest` (added cases, app) | +2 (22 total) | A departing officer cannot smuggle its REKEY past the host in either fold order; a departure forces a host rotation above a REKEY admitted in the same batch |
| `FlitCircleStoreTest` (existing case restated, app) | 31 total | The rekey case now names the current owner/admin rule and bounded payloads; no new case at this layer |
| `CircleHarnessTest` (in-process, harness) | 14 total | Proof and authority gates through production endpoints, JOIN injected through SYNC refused, officer REKEY and generation ordering, host-answer pinning, and host-signed key learning; two new cases at this layer |
| CLI suites (in-process, harness) | `CourierCliTest` 21, `PadCliTest` 8, `ParcelCliTest` 11 | Courier, pad and parcel commands under the shared fold and cap rules; no new case at this layer |

Case counts are read from the JUnit XML of the fresh run. The in-process walks
use real endpoints on one box; they are not device observations. The 2026-12-22
protocol classes are dependency tests, re-run here and not new work on this date.

## Evidence

| Label | Basis |
| --- | --- |
| CODE | The app and CLI seams match the gates and file list above; no new route, header, DTO field, QR parameter, schema field or v2.2 status |
| PURE | The added app cases run in JVM tests of Android-free seams with no emulator or framework import; zero failures, errors and skipped cases |
| SIM-HARN | The harness additions run in-process with real endpoints: JOIN proof refusal, authority decisions, host-answer pinning and CLI parity |
| DEVICE | No phone, second phone, radio, carrier or camera observation is made on this date |

The weakest recorded level governs each claim. No device walk is claimed by
this layer; the 2026-12-17 walk remains deferred.

## Limitations

- A captured proof is single-use only in-process. The id reservation lives in
  the process-local replay set; a restart or another process has no memory of
  it, and the proof's usefulness ends with its bucket grid.
- The replay set is process-local and bounded at `MAX_SEEN = 4096`. It is not a
  shared or durable replay store; the app also filters against the local log
  and the 500-event log cap.
- No physical device walk is part of this layer. The in-process harness uses
  real endpoints on one box; there is no RFCOMM link, camera decode, phone
  observation or timing figure.
- A larger mesh is unverified. The exercises use the small in-process identity
  set; no Circle with more members than the baseline walk is recorded.
- The authority gate reads only circle id, type, author fingerprint and the
  supplied role map. It does not verify signatures and owns no store; the
  `fresh` order is what keeps an unauthorised or forged event from being
  admitted or burning an id.
- The CLI pin is explicit: `--host-fp` is required at join, and a key from an
  unverified answer is never adopted. Rotated keys come only from host-signed
  REKEY events in the bounded signed log.
- `join` is a host-routed handshake. No peer can contribute a folded JOIN from
  a sync answer or an opened parcel, and a member cannot inject another
  member's signed event.
- No performance or timing figure is asserted by this record.
