# 2026-12-24 — Security and documentation record (Docs)

Layer: 2026-12-24 Docs. This layer records the JOIN proof, the shared Circle
authority gate and the restored app ingress replay guard in the security
disclosure, the seam contracts, the failure modes, the operational limits and
the test inventory. It also places the dated completion records for the
2026-12-22 and 2026-12-23 layers alongside the earlier ones. No test, route,
header, DTO field, store field, schema field, signature primitive or v2.2
status is added by this layer; the rig inventory is unchanged.

Counts come from the JUnit XML result files of the fresh JVM run under
`protocol/build/test-results/test/`, `app/build/test-results/testDebugUnitTest/`
and `harness/build/test-results/test/`. No device observation is claimed, and
the 2026-12-17 device walk remains deferred.

## Files in this layer

| File | Content |
| --- | --- |
| `docs/SECURITY.md` | Threat table and "Signatures and cert-in-event" section updated: a JOIN must carry one signed proof, the stateless verifier and its precedence, the host-signed refusal before the delegate, the in-process single-use reservation, and the pure and in-process test references |
| `docs/CONTRACTS.md` | Seam contracts for the JOIN-proof body, the `foldable` decision, the `fresh` gate, the pre-batch authority snapshot and the CLI host pin |
| `docs/FAILURE-MODES.md` | Refusal branches: missing, legacy, malformed, extra-key, tampered, stale and future proofs; a proof used twice in one request; a forged fingerprint; unauthorised REKEY; same-batch hand-off; a forced rotation outranking a same-batch key |
| `docs/OPERATIONS.md` | Operational limits of the proof and authority layers, including the process-local reservation and the bucket bound |
| `docs/TESTING.md` | Module totals 359 / 323 / 87 / 769, the new protocol classes and the changed app and harness classes, and the clarification that the harness file-delivery case is in-process rather than on-device |
| `docs/completions/2026-12-18-release.md` | Historical figures kept at their v0.3.0 point; the 714 tree identified as the post-2026-12-21 state, not the current inventory |
| `docs/completions/2026-12-22-join-proof.md`, `docs/completions/2026-12-23-circle-authority.md`, `docs/completions/2026-12-24-security-docs.md` | The dated layer records for this wave |
| `docs/roadmap/01-schedule.md`, `docs/roadmap/02-waves.md`, `docs/roadmap/03-quality-gates.md` | Build-ahead note, dated layer rows and the release-gate wording |
| `README.md` | Capability wording aligned with the admission proof and the shared fold rule |
| `docs/completions/2026-12-24-security-docs.md` | This layer record |

Earlier per-layer inventories are kept as snapshots of their layers; the
figures below are the inventory at this documentation point, not a rewrite of
those records.

## Gate rules recorded

The documented gates are those already implemented and tested in the two
preceding layers:

- `CircleAuthority.foldable`: known type, exact circle match, non-blank and
  normalised author; JOIN never folds; REKEY needs OWNER or ADMIN; all other
  known verbs fold. No signature, clock, store or state.
- JOIN proof: one-key `{"proof":"…"}` body, 32-byte SHA-256 over a
  `FLITJOIN1`-tagged nonce of four length-prefixed normalised fields and a
  five-minute bucket, unpadded Base64URL. `joinProofFailure` returns `null` for
  non-JOIN actions and otherwise `join proof required`, `invalid join proof` or
  `join proof replay` in that precedence; the bucket tolerance is one, and a
  duplicate in one request or a failed `fresh` id reservation is replay.
- Route gate: `ServerCore.circleRoute` answers a failed proof with a host-signed
  200 refusal before the delegate; proof-less old clients are refused.
- App ingress: `fresh` runs shape, circle, authority, body, verify, order, dedup,
  local-log filter, cap and `accept` in that order, so a forgery cannot burn an
  valid event's id.
- Store and host: authority is read from one pre-batch roster snapshot, same-batch
  leavers are vetoed as REKEY signers, and a departure forces a host rotation
  above any non-leaver REKEY generation admitted in the batch.
- CLI: `--host-fp` pins the host at join; later answers must be signed by that
  pin; keys are learned only from host-signed REKEY events by generation.

These rules add no wire surface. The only behaviour change visible to an old
client is that a pre-proof JOIN is refused with an exact reason.

## Rig inventory

| Module | Tests | Basis |
| --- | --- | --- |
| `protocol` | 359 | JVM-pure wire, Circle, courier and parcel cores |
| `app` | 323 | JVM tests of Android-free seams; no emulator or device |
| `harness` | 87 | In-process CLI and end-point rig on one box |
| Total | 769 | 0 failures, 0 errors, 0 skipped; all assemblies green |

No tests are added by this documentation layer. The 2026-12-28 Release layer
also adds none, so 769 is the final green rig figure for this tree. No timing
or hardware measurement is attached to these counts.

## Test classes named by the inventory

| Class | Cases | Where |
| --- | --- | --- |
| `CircleAuthorityTest` | 12 | protocol, new on 2026-12-22 |
| `JoinProofTest` | 14 | protocol, new on 2026-12-22 |
| `CircleHardeningTest` | 14 | protocol, three route cases added on 2026-12-22 |
| `FlitCircleSyncTest` | 56 | app, twelve cases added on 2026-12-23 |
| `FlitCircleHostTest` | 22 | app, two cases added on 2026-12-23 |
| `FlitCircleStoreTest` | 31 | app, one existing case restated on 2026-12-23 |
| `CircleHarnessTest` | 14 | harness, in-process proof and authority walks |
| CLI suites | `CourierCliTest` 21, `PadCliTest` 8, `ParcelCliTest` 11 | harness, in-process |

Counts are read from the JUnit XML of the fresh run. The whole rig is JVM-pure
or in-process; none of these classes exercises a physical device.

## Evidence

| Label | Basis |
| --- | --- |
| CODE | The documentation describes the implemented seams and gates without changing a route, header, DTO field, store field, schema field or v2.2 status |
| PURE | The protocol and app cases in the inventory ran in JVM modules with zero failures, errors and skipped cases |
| SIM-HARN | The harness cases named above ran with in-process endpoints; the documented CLI and Circle walks are that rig, not a device walk |
| DEVICE | No phone, second phone, radio, carrier or camera observation is claimed or documented here |

The weakest recorded level governs each claim. Documentation of on-device
evidence from earlier layers keeps its original label; nothing in this layer
upgrades a rig walk to a device observation.

## Limitations

- A captured proof is single-use only in-process. The id is reserved in a
  process-local bounded set; a restart or another process has no memory of the
  reservation.
- Proof usefulness is bounded by the five-minute bucket grid: the host accepts
  the current bucket with a one-bucket tolerance and treats further rollovers
  as replay. The invite stays a bearer credential while it is valid.
- The replay set is process-local and bounded at `MAX_SEEN = 4096`; it is not a
  shared or durable replay store.
- No physical device walk is recorded for this wave. The 2026-12-17 walk stays
  deferred and no placeholder observation is added for it.
- A larger mesh is unverified: the recorded walks use the small in-process
  identity set, with no Circle larger than the baseline members.
- The documentation edits do not run a feature walk or add a test; they rest on
  the fresh JVM rig whose XML counts are named above.
- No performance or timing figure is asserted by this record.
