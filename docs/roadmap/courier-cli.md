# Courier CLI carrier

The CLI carrier holds sealed courier parcels on disk and moves them between machines as a directory copy, using the 2026-11-27 envelope and carry layers without opening or forking their wire bytes.

## Date and layer

2026-11-30 — CLI. Commit scope: courier carrier and harness tests.

## Depends on

- 2026-11-25 planning: [`00-plan.md`](00-plan.md) and [`01-schedule.md`](01-schedule.md).
- 2026-11-26 device baseline: [`three-node-walk.md`](three-node-walk.md). The bag handoff uses the same three logical CLI identities that walk established; the carrier adds no fourth identity.
- 2026-11-27 protocol: [`courier-envelope.md`](courier-envelope.md) and [`courier-carry.md`](courier-carry.md). `CourierEnvelope.seal`/`open` and the `FLITCOURIER1` canonical form come from courier-envelope.md. `CourierCarryCore.parcelOf`, `encode`/`decode`, `expire`, the quota constants and `CarryState` come from courier-carry.md. The carrier owns only the storage, transitions and status around them.

The entry points assumed here are `CourierEnvelope.seal` and `CourierEnvelope.open` from the envelope layer, and `CourierCarryCore.parcelOf`, `encode`, `decode`, `expire`, the quota constants and `CarryState` from the carry layer. The envelope carries no expiry field; `CourierCarryCore.parcelOf` derives it as `createdMs + MAX_PARCEL_TTL_MS`. The CLI calls these at one seam; a different name in the 2026-11-27 files changes only this seam, no other CLI code.

## Scope

Add a `courier` command to `flit-cli` that deposits a signed Circle event into a sealed parcel for an absent member, copies parcels between machines in a portable bag directory, opens parcels addressed to the local identity, folds opened events into the CLI's local Circle state, and expires or refuses parcels that cannot be delivered. Parcel bytes, encryption and the `FLITCOURIER1` canonical form belong to courier-envelope.md; parcel encoding, expiry and quota belong to courier-carry.md; the carrier owns only storage, transitions, status output and the CLI test pair.

No new protocol route, no new HTTP header and no change to the v2.2 surface. No app or UI work. No QR, camera or frame work. No per-drop content key, delivery receipt or Circle pad work.

## Design

**Command surface.** `main` gains one `courier` dispatch; the harness's `usage()` gains one line and `courierUsage()` lists the subcommands. `ParsedArgs.VALUE_FLAGS` gains `--bag`, `--to`, `--cert`, `--ttl` (`--text`/`--file` already exist). Every flag and the subcommand are read through the existing `ParsedArgs`; there is no second parser. The command itself is not in `Harness.kt`: `run(args: ParsedArgs): Int` lives in the `CourierCli` object described below. Each invocation is one-shot: load state, do one operation, save state, as the `circle` command does.

```
courier drop   --to FP --text NOTE | --file PATH [--circle ID] [--cert B64] [--ttl MS] [--bag DIR]
courier carry  [--bag DIR]                         # verify the local bag, mark rows carried
courier receive --bag DIR                          # open parcels for this identity, fold events
courier status [--bag DIR]                         # list rows: id, to, state, expires
courier sweep  [--bag DIR]                         # drop expired parcels and enforce quota
```

### `CourierCli` object

`harness/src/main/kotlin/app/flit/share/harness/CourierCli.kt`, package
`app.flit.share.harness`, owns only storage, transitions, status output and this
one-shot command. It has no Android import, no route and no header; encryption
and the parcel bag codec stay in `protocol/`. The harness makes `harnessHome()`,
`loadOrCreateIdentity()` and `saveCircleState()` internal so the object can use
them. The declared API is:

```kotlin
object CourierCli {
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
  fun decodeRows(raw: String?): List<Row>
  fun nextState(row: Row, kind: Kind, nowMs: Long): String
  fun bagDir(args: ParsedArgs): java.nio.file.Path
  fun statePath(args: ParsedArgs): java.nio.file.Path
  fun usage(): String
  fun run(args: ParsedArgs): Int
}
```

`run` implements `drop`, `carry`, `receive`, `status` and `sweep` with the flags
above; `usage()` prints the synopsis. `bagDir` and `statePath` root every path in
`harnessHome()` and apply the `--bag` override. `decodeRows` is tolerant like
`circle.state`.

**Identity and home.** `loadOrCreateIdentity()` supplies the signing/opening identity, so the courier's fingerprint is the one printed by `serve` and used by `circle`. `harnessHome()` roots every courier path; `FLIT_HARNESS_HOME` separates two nodes on one box, the same isolation `CircleHarnessTest` uses for Circle bots.

**On-disk layout, all under `harnessHome()`:**

| Path | Contents |
| --- | --- |
| `courier.state` | Local-only bookkeeping, one JSON line per parcel. |
| `courier-bag/` | One `<envelope-id>.parcel` file per parcel; this directory is the portable handoff unit. |
| `courier-received/` | Opened event JSON written after a successful handoff; evidence of what landed, never re-sent. |
| `courier-rejected/` | Parcels that failed `open`; quarantined, not folded and not re-exported. |

`--bag DIR` overrides the bag path for `carry` and `receive`; `drop`, `status` and `sweep` default to `harnessHome()/courier-bag`. The operator copies `courier-bag/` to the next machine by USB or any file copy; the carrier implements no transport of its own.

**State schema.** `courier.state` mirrors the tolerant `circle.state` codec: a list of lines, `encodeRows` writing one `Row` per line with `WireJson.json` under `ignoreUnknownKeys = true`, and `decodeRows` returning an empty list for an absent, short or corrupt file. The row type is exactly `Row(id, recipientFp, senderFp, circleId, state, createdMs: Long, expiresMs: Long)`. `id` is the envelope id from courier-envelope.md and `expiresMs` comes from `CourierCarryCore.parcelOf` as `createdMs + MAX_PARCEL_TTL_MS`. `state` is one of `pending`, `carried`, `delivered`, `expired`, `refused`. `senderFp` and `recipientFp` are normalised uppercase hex as in `normalized()` in `Harness.kt`. Rows are local-only and are never written into a Circle event.

**Envelope bytes.** `drop` calls `CourierEnvelope.seal` with the recipient's certificate; `seal` carries no expiry field; `parcelOf` derives it after sealing. The envelope goes through `CourierCarryCore.parcelOf`, and `CourierCarryCore.encode(parcel)` is written verbatim to `<id>.parcel`. The CLI never parses, re-signs or rewrites the envelope or the parcel. The recipient certificate comes from the `cert` field of a signed event by that fingerprint in `circle.state`; `--cert B64` supplies it out of band for a member whose event has not arrived. Both paths check `TlsIdentity.fingerprintOf(cert)` against `--to` before sealing, so a parcel cannot be addressed to one fingerprint and encrypted to another.

**Fold path.** `receive` decodes each `<id>.parcel` file with `CourierCarryCore.decode` and opens it with `CourierEnvelope.open`. Events returned by `open` for this identity are folded into `CircleCliState` with `CircleCore.order(CircleCore.dedup(st.events + event))`, the same reducer `mergeCircleAnswer` uses, and saved through `saveCircleState`; a returned refusal maps to `RECEIVE_REFUSED`, while a throw leaves the row pending. Governance and replay remain the protocol layer's job; the CLI member-side fold adds no reducer. A parcel addressed to a different fingerprint stays `pending` and remains in the bag for the next courier: that node is a carrier for it, not its recipient.

**Transitions.** `nextState(row, kind, nowMs)` is the single reducer; `Kind` is exactly `CARRY`, `RECEIVE_OK`, `RECEIVE_REFUSED`, `SWEEP`. `pending → carried` on `CARRY` (after `carry` verifies the bag), `pending|carried → delivered` on `RECEIVE_OK` (a returned `open` with events for this identity), anything → `refused` on `RECEIVE_REFUSED` (a returned refusal), and anything → `expired` on `SWEEP` when `nowMs >= expiresMs`. A thrown `open` is not a conclusion and does not reach `nextState`: the row stays `pending` and the parcel stays in the bag for a later attempt, matching the carrier-death contract used by Circle file delivery. `drop` refuses a new parcel when the current `CarryState` plus the parcel would exceed the quota constants from courier-carry.md.

`carried` is custody, not delivery. A transport OK or an OK sync answer means the peer accepted an offer, not that an envelope was opened or that bytes landed. Row states are local to the node that wrote them and no remote receipt is inferred; `delivered` is the local label for a parcel opened and folded here.

**Constants owned here:** `BAG_DIR = "courier-bag"`, `RECEIVED_DIR = "courier-received"`, `REJECTED_DIR = "courier-rejected"`, `STATE_FILE = "courier.state"`, envelope suffix `.parcel`, `EXIT_BAD_ARGS = 2` for a bad argument or missing bag and `EXIT_OPEN_REFUSED = 3` for a parcel that fails `open`, both returned by `run`. TTL is derived by `CourierCarryCore.parcelOf` as `createdMs + MAX_PARCEL_TTL_MS`; quota constants and maximum parcel bytes come from courier-carry.md. Neither is duplicated in the harness.

**Status output** reuses `${ts()}` and the one-line style of `circle`: one row per parcel with a padded state value, and a closing count by state. No fingerprint beyond the short form is printed, and no parcel content is printed.

## Invariants

1. Every `.parcel` file's id equals a row id in `courier.state`; a file with no row is ignored by `receive` and `sweep`, never folded.
2. `drop` writes no envelope when the recipient certificate's fingerprint differs from `--to` or when the current `CarryState` plus the new parcel would exceed the courier-carry.md quota constants.
3. The CLI stores `CourierCarryCore.encode(CourierCarryCore.parcelOf(envelope))` byte-for-byte; encode→decode→encode of an unopened parcel is identical.
4. `receive` folds an event only after `CourierEnvelope.open` returns it for the local fingerprint; a parcel for another fingerprint stays `pending` in the bag.
5. A duplicate envelope id produces no second fold: `CircleCore.dedup` removes it, and the row moves straight to `delivered`.
6. `open` failure sets `refused` and moves the file to `courier-rejected/`; a throw leaves the row `pending` and the file in the bag.
7. `sweep` deletes an envelope file only when its row is `expired` or `refused`; `delivered` rows (opened and folded here, not a remote delivery proof) keep their file in `courier-received/` as a local record.
8. Every accepted event is folded through `CircleCore.order`/`dedup`; the carrier adds no governance decision and no second signature path.
9. State writes are one-shot and whole-file, as `saveCircleState` does; no load→modify→save races with another CLI process on the same `FLIT_HARNESS_HOME`.
10. No command adds a route, header, DTO field or key parameter to the v2.2 wire.

## Failure modes

| Condition | Conclusion | Keeps bytes | Drops | Evidence |
| --- | --- | --- | --- | --- |
| `--bag` path missing or unreadable | exit `2`, no state change | nothing moved | nothing | CODE |
| Recipient cert fingerprint differs from `--to` | drop refused before seal | nothing written | nothing | CODE / PURE |
| Bag at courier-carry.md quota | drop refused, row not added | existing parcels kept | nothing | CODE / PURE |
| Parcel past TTL at `sweep` | row `expired` | opened records in `courier-received/` | envelope and row on the next sweep | PURE / SIM-HARN |
| Parcel addressed to another fingerprint | stays `pending`, re-exported | envelope kept in `courier-bag/` | nothing | CODE / SIM-HARN |
| `open` returns a refusal (forged or tampered envelope) | row `refused` | nothing in Circle state | envelope moved to `courier-rejected/`, payload dropped | SIM-HARN |
| `open` throws (carrier/IO fault) | stays `pending`, retried | envelope kept | nothing | CODE / SIM-HARN |
| Duplicate envelope id on `receive` | row `delivered` (opened here), no second fold | first folded event | duplicate event | SIM-HARN |
| `courier.state` corrupt or short | decode as empty, report the count | bag files untouched | stale local rows only | CODE |
| Envelope file vanished between `status` and `receive` | skip with a warning, row stays `pending` | remaining envelopes | nothing | CODE |

## Tests

Pure tests first, in `harness/src/test/kotlin/app/flit/share/harness/CourierCliTest.kt` with no sockets and no endpoints: `encodeRows`/`decodeRows` round-trip and tolerant decode of absent, short and corrupt files; the `Kind → nextState` mapping for all four kinds, plus the thrown-`open` path that leaves the row `pending`; `bagDir`/`statePath` path resolution including the `--bag` override; the cert-fingerprint gate against `--to`; the `SWEEP` expiry boundary at `nowMs >= expiresMs` with an injected clock and a stub quota answer; deterministic `<id>.parcel` naming; the state-count line. These test the carrier's own seams, not the envelope crypto or the carry codec, which are covered by the 2026-11-27 protocol tests.

Harness tests, extending the one-JVM production-endpoint pattern of `CircleHarnessTest`: two CLI-state nodes with distinct `FLIT_HARNESS_HOME` values and fresh `Files.createTempDirectory` roots; node A `drop` seals a signed event into a bag, `carry` marks it `carried`, node B `receive --bag` opens it and folds it into `circle.state`; a third-fingerprint parcel stays `pending` and is re-exported; a tampered parcel is refused and quarantined; an expired parcel is swept; a duplicate id does not double-fold; a bad `--to` cert never writes a file. Payloads are seeded, temp roots are per test, and state is asserted by exact row values, not just a count. `decode` goes through `CourierCarryCore` and `open` through `CourierEnvelope` from 2026-11-27, never a lookalike cipher.

Device walk: none at this layer. The carrier handoff is a directory copy on the bench box; the three-node courier walk is specified in [`courier-device-walk.md`](courier-device-walk.md) at 2026-12-02 and reuses these commands.

## Evidence for this day

Written when the layer lands, in `docs/completions/2026-11-30-courier-cli.md`: the `courier` command surface and bag layout, the harness test names and the new harness count, the exact rig command and its green result, and the limitation that no device walk happened — the bag moved between directories on one box only. No radio, no QR and no second-phone claim is recorded.

## Out of scope

App sync worker, file-card states and permissions (2026-12-01); the courier three-node device walk (2026-12-02); QR parcel frame codec, CLI encode/decode, app display and air-gap walk (2026-12-03 through 2026-12-08); per-drop content keys, delivery receipts, Circle pad and their integrations (2026-12-09 through 2026-12-16); multi-member mesh, a desktop GUI, Wi-Fi Aware and `.flitpack` bundles. The `FLITCOURIER1` canonical form, envelope bytes and crypto wrap stay in courier-envelope.md; parcel encoding, expiry and quota values stay in courier-carry.md. The CLI implements no physical transport and no new `LinkProvider`.

## Commit

2026-11-30 — CLI: courier carrier, tests.
