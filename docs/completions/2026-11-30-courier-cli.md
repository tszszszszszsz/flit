# 2026-11-30 — Courier CLI carrier

Layer: 2026-11-30 CLI. The work is the `courier` one-shot command in
`flit-cli`: a local-only state row ledger, a portable parcel bag directory, and
the storage, transitions and status around the 2026-11-27 envelope and carry
layers. Encryption stays in `CourierEnvelope`; bag encoding, expiry and quota
stay in `CourierCarryCore`.

Counts below were read from a fresh `--rerun-tasks` run after integration. No
device evidence is claimed at this layer.

## Files in the layer

| File | Content |
| --- | --- |
| `harness/src/main/kotlin/app/flit/share/harness/CourierCli.kt` | `object CourierCli`: state-row codec, transitions, path roots, status output and the one-shot `run` dispatch for the five subcommands |
| `harness/src/main/kotlin/app/flit/share/harness/Harness.kt` | `"courier"` dispatch, one `usage()` line, `courierUsage()`, `--bag`/`--to`/`--cert`/`--ttl` in `ParsedArgs.VALUE_FLAGS`; `harnessHome()`, `loadOrCreateIdentity()` and `saveCircleState()` made `internal`; no existing command changed |
| `harness/src/test/kotlin/app/flit/share/harness/CourierCliTest.kt` | Pure tests of the carrier seams: state-row codec, transition mapping, cert-fingerprint gate, TTL/quota decisions, parcel naming and the status count line |
| `harness/src/test/kotlin/app/flit/share/harness/CourierCarrierHarnessTest.kt` | Two-node harness exercise of `drop` → `carry` → `receive` → `status` → `sweep` over bag directories with distinct homes |
| `docs/OPERATIONS.md` | CLI reference for `courier`, its subcommands and flags |
| `docs/TESTING.md` | New test classes with counts from the integration run |
| `docs/FAILURE-MODES.md` | CLI courier rows |
| `docs/CONTRACTS.md` | CLI surface and bag portability contract |
| `docs/SECURITY.md` | Copied bag is blind; state rows are local-only |
| `docs/PERFORMANCE.md` | Bag-copy and one-shot cost, without invented numbers |
| `docs/ARCHITECTURE.md` | CLI carrier seam paragraph |
| `README.md` | One CLI bullet, no device claim |
| `docs/completions/2026-11-30-courier-cli.md` | Completion record for the 2026-11-30 CLI layer |
| `docs/roadmap/courier-cli.md` | CLI command surface and bag layout |
| `docs/roadmap/courier-device-walk.md` | Expected command strings aligned with the CLI surface |
| `docs/roadmap/01-schedule.md`, `docs/roadmap/02-waves.md` | CLI layer dated 2026-11-30 |

Package `app.flit.share.harness` uses `ParsedArgs` only; there is no second
parser, no route, no header and no Android import.

## Public surface

`object CourierCli`:

```
const val BAG_DIR = "courier-bag"
const val RECEIVED_DIR = "courier-received"
const val REJECTED_DIR = "courier-rejected"
const val STATE_FILE = "courier.state"
const val EXIT_BAD_ARGS = 2
const val EXIT_OPEN_REFUSED = 3

data class Row(val id: String, val recipientFp: String, val senderFp: String,
               val circleId: String, val state: String,
               val createdMs: Long, val expiresMs: Long)

enum class Kind { CARRY, RECEIVE_OK, RECEIVE_REFUSED, SWEEP }

fun encodeRows(rows: List<Row>): String
fun decodeRows(raw: String?): List<Row>      // tolerant, like circle.state
fun nextState(row: Row, kind: Kind, nowMs: Long): String
fun bagDir(args: ParsedArgs): java.nio.file.Path
fun statePath(args: ParsedArgs): java.nio.file.Path
fun usage(): String
fun run(args: ParsedArgs): Int
```

`run` returns an exit code. It loads the identity through
`loadOrCreateIdentity()` and roots every path in `harnessHome()`, so
`FLIT_HARNESS_HOME` isolates two nodes on one box exactly as it does for Circle
bots.

## Command surface

```
courier drop   --to FP --text NOTE | --file PATH [--circle ID] [--cert B64] [--ttl MS] [--bag DIR]
courier carry  [--bag DIR]                         # verify the local bag, mark rows carried
courier receive --bag DIR                          # open parcels for this identity, fold events
courier status [--bag DIR]                         # list rows: id, to, state, expires
courier sweep  [--bag DIR]                         # drop expired parcels and enforce quota
```

`--bag`, `--to`, `--cert` and `--ttl` are added to `ParsedArgs.VALUE_FLAGS`;
`--text` and `--file` already exist. Each invocation is one-shot: load state,
do one operation, save state. `drop`, `status` and `sweep` default to
`harnessHome()/courier-bag`; `carry` and `receive` accept an explicit `--bag`.
Status prints one row per parcel with a padded state value and a closing count
by state; fingerprints appear only in short form and no parcel content is
printed.

## On-disk layout

All paths are under `harnessHome()`:

| Path | Contents |
| --- | --- |
| `courier.state` | Local-only bookkeeping, one JSON line per parcel; tolerant decode as empty on absent, short or corrupt input |
| `courier-bag/` | One `<envelope-id>.parcel` file per parcel; this directory is the portable handoff unit |
| `courier-received/` | Opened event JSON written after a successful handoff; a local record, never re-sent |
| `courier-rejected/` | Parcels that failed `open`; quarantined, not folded and not re-exported |

## Constants

| Constant | Value | Basis |
| --- | --- | --- |
| `BAG_DIR` / `RECEIVED_DIR` / `REJECTED_DIR` | `"courier-bag"` / `"courier-received"` / `"courier-rejected"` | Defined here; `courier-bag/` is the copy unit |
| `STATE_FILE` | `"courier.state"` | Local-only row ledger, never written into a Circle event |
| Envelope suffix | `".parcel"` | Written verbatim from `CourierCarryCore.encode(CourierCarryCore.parcelOf(envelope))` |
| `EXIT_BAD_ARGS` | `2` | Bad argument or missing/unreadable bag, no state change |
| `EXIT_OPEN_REFUSED` | `3` | A parcel whose `open` returns a refusal |
| Row `state` values | `pending`, `carried`, `delivered`, `expired`, `refused` | Closed set; `delivered` is the local row label for a parcel opened and folded here, not a remote delivery proof |

`expiresMs` is derived by `CourierCarryCore.parcelOf` as
`createdMs + MAX_PARCEL_TTL_MS`. Quota constants and maximum parcel bytes come
from the carry layer; the harness declares no second copy of either.

`carried` records custody by a courier, not delivery. A transport OK or an OK
sync answer means the peer accepted an offer; it is not proof that an envelope
was opened or that bytes landed. Every status row is local to the node that
wrote it, and no remote receipt is inferred.

## State transitions

`nextState` applies one `Kind` to a row:

| Kind | Transition |
| --- | --- |
| `CARRY` | `pending → carried` after the bag verifies |
| `RECEIVE_OK` | `pending\|carried → delivered` |
| `RECEIVE_REFUSED` | anything → `refused` |
| `SWEEP` | anything → `expired` when `nowMs >= expiresMs` |

A thrown `open` is not a conclusion: the row stays `pending` and the file stays
in `courier-bag/` for a later attempt, matching the carrier-death contract used
by Circle file delivery. A returned refusal moves the file to
`courier-rejected/` and leaves Circle state untouched. A parcel addressed to a
different fingerprint stays `pending` and is re-exported by the next carrier;
that node is a carrier for it, not its recipient. `sweep` deletes an envelope
file only when its row is `expired` or `refused`.

## Harness visibility changes

`harnessHome()`, `loadOrCreateIdentity()` and `saveCircleState()` become
`internal` so `CourierCli` can share them. No existing command, route or DTO
changes.

## Verification

The rig ran green on a fresh `--rerun-tasks` pass after integration. Command,
the CI-standard task set from [TESTING.md](../TESTING.md):

```bash
source env.sh && ./gradlew --no-daemon --rerun-tasks \
  :protocol:test :app:testDebugUnitTest :harness:test \
  :app:assembleDebug :app:assembleRelease \
  :harness:installDist :harness:distZip
```

| Module | Baseline at this layer | Current rig | Task |
| --- | --- | --- | --- |
| `protocol` | 136 | 136 | `:protocol:test` |
| `app` | 210 | 228 | `:app:testDebugUnitTest` |
| `harness` | 10 | 32 | `:harness:test` |
| Total | 356 | 396 | — |

Result: `BUILD SUCCESSFUL` after `--rerun-tasks`. The 356-test baseline
(136 protocol + 210 app + 10 harness) is historical; the current rig is
396 = 136 protocol + 228 app + 32 harness. The app count rose in later app
work; this CLI layer added no app cases.

## Tests

Two new harness classes add 22 cases, read from the JUnit XML. No new protocol
or app cases are added.

| Class | Cases | What it pins |
| --- | --- | --- |
| `CourierCliTest` | 15 | `courier.state` encode/decode round-trip; tolerant decode of absent, short and corrupt files; the `open` result → state mapping including a throw staying `pending`; the cert-fingerprint gate against `--to`; TTL and quota decisions with an injected `nowMs` and a stub quota answer; deterministic `<id>.parcel` naming; the status count line |
| `CourierCarrierHarnessTest` | 7 | Two CLI-state nodes with distinct `FLIT_HARNESS_HOME` values and per-test temp roots: A `drop` seals a signed event into a bag, `carry` marks it `carried`, B `receive --bag` opens it and folds it into `circle.state`; a third-fingerprint parcel stays `pending` and is re-exported; a tampered parcel is refused and quarantined; an expired parcel is swept; a duplicate id does not double-fold; a bad `--to` cert never writes a file |

The pure class uses no sockets or endpoints. The harness class goes through
`CourierCarryCore.decode` and `CourierEnvelope.open` from 2026-11-27, never a
lookalike cipher, and asserts exact row values rather than only counts.
Identities come from `TlsIdentity.generate()`; payloads are seeded and roots are
per test.

## Evidence

| Label | Claimed here | Basis |
| --- | --- | --- |
| CODE | Yes | The `CourierCli` API in `CourierCli.kt`, the `"courier"` dispatch and value flags in `Harness.kt`, and the bag layout in the roadmap |
| PURE | Yes | `CourierCliTest` run by `:harness:test` |
| SIM-HARN | Yes | `CourierCarrierHarnessTest` run by `:harness:test` with production `CourierCarryCore`/`CourierEnvelope` seams |
| DEVICE | No | No device walk at this layer |

No radio, phone, camera or QR observation is claimed on this date. No
second-phone claim is recorded. The bag moved between directories on one box
only; the three-node courier walk is the 2026-12-02 layer.

## Limitations

- No physical transport is implemented. The operator copies `courier-bag/` by
  USB or any file copy; the carrier has no socket, listener or `LinkProvider`.
- State rows and the received/rejected records are local-only. Rows are never
  written into a Circle event, and no row content is gossiped.
- Encryption, signatures and bag codecs stay in `protocol/`. The CLI does not
  parse, re-sign or rewrite an envelope or a parcel; governance and replay
  remain the protocol layer's job.
- No app, UI, wire route, HTTP header, DTO field or key parameter changed.
- No node ran a listener during this layer; the two harness nodes are separate
  one-shot CLI processes with distinct homes on one box.

## Verification status

Counts and the rig result come from the green `--rerun-tasks` run after the two
test classes landed. The bag moved between directories on one box; no radio, QR
or second-phone evidence is claimed.
