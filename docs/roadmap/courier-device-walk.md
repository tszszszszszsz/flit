# Courier three-node device walk

Record the Courier handoff across three Circle nodes — the phone and two CLI identities — over the existing Circle sync route and file-drop path.

## Date and layer

2026-12-02, Device. The day produces a device walk and its evidence note. No code, build change or release artifact lands on this date.

## Depends on

- 2026-11-26, Device — three-node baseline walk (`docs/roadmap/three-node-walk.md`): the same phone plus two CLI identities, Circle founding, joins and node naming.
- 2026-11-27, Protocol — Courier envelope and carry core (`docs/roadmap/courier-envelope.md`, `docs/roadmap/courier-carry.md`): the signed carry event and the accept/carry/handoff state machine.
- 2026-11-30, CLI — CLI carrier (`docs/roadmap/courier-cli.md`): the `flit-cli courier drop`, `carry`, `receive`, `status` and `sweep` commands, the `courier.state` rows and the log lines the two box nodes emit.
- 2026-12-01, App — Courier sync and file-card states (`docs/roadmap/courier-sync.md`, `docs/roadmap/courier-ui.md`): the phone's carried-parcel handling and the card states read during the walk.

## Scope

Three nodes are online at the start: A is the phone running the Flit app, B and C are two `flit-cli` processes on the box with separate harness homes. C goes offline before a drop is posted. B accepts the carried envelope while C is absent, holds it without delivering, and hands it to C on a later encounter. The walk covers the offline hold, the handoff, replay of the same carry id, backoff while C is unreachable, and refusal to a departed member. It records observations at DEVICE level and re-runs the PURE and SIM-HARN layers that the earlier dates added.

## Design

### Nodes and identity

| Node | Surface | Identity and state |
|---|---|---|
| A | phone, Flit app | `flit-identity.p12` in app `filesDir`, loaded by `FlitCircleSync.identity()`; Circle state in `FlitCircleStore`, atomic under `FlitCircleStore.update` |
| B | box, `flit-cli serve` + CLI carrier | one `FLIT_HARNESS_HOME`, holding `identity.p12`, `circle.state`, `courier.state` and a `courier-bag/`; live file bytes land in the `--dir` passed to `serve` |
| C | box, `flit-cli circle join` + CLI carrier | a second `FLIT_HARNESS_HOME`, holding its own `identity.p12`, `circle.state`, `courier.state` and `courier-bag/`; live file bytes land in its own `--dir` |

`harnessHome()` in `harness/src/main/kotlin/app/flit/share/harness/Harness.kt` reads `FLIT_HARNESS_HOME` and defaults to `~/.config/flit/flit-harness`. Sharing one home between B and C mixes fingerprints, `circle.state`, `courier.state` and the `courier-bag/`, which makes peer-side confirmation ambiguous (`docs/TESTING.md` §8). Each node in this walk gets its own home. The phone keeps the keystore identity the QR advertised; no new key material is minted for the walk.

### Event and frame shapes

The carried envelope is a signed `CircleEvent`; its type and exact body keys are defined by the 2026-11-27 protocol layer and are not restated here. Handoff adds no protocol route: the envelope travels in `CircleRequest.ACTION_SYNC` / `CircleResponse`, the same exchange used by `FlitCircleSync.fanout`, `FlitCircleSync.sync` and `CircleHostState.onCircle`, and the file bytes travel with `ProtocolClient.sendFiles`. A file drop stays a reference body of exactly `{"name":...,"size":...,"sha256":...}` (`CircleCore.FILE_BODY_KEYS`, enforced by `CircleCore.validFileBody` and `circleBodyValid`); the sender's local spool path and queue token never enter a signed event.

Every receiving node applies the existing pipeline in this order: `validShape → CircleCore.verify → CircleCore.evaluate`/`accept → store`. Incoming bodies must parse as one bounded JSON object through `circleBodyReadable`. `evaluate` keeps the decision precedence Invalid → OutsideWindow → Duplicate → Accept, and only Accept reserves the id in the bounded `CircleCore` seen set. For courier parcels the carry core applies `Invalid → Expired → OutsideWindow → Duplicate → Full → Accept` over the 24 h courier replay window (`MAX_PARCEL_TTL_MS`), not `CircleCore.REPLAY_WINDOW_MS` (6 h). Delta selection uses `circleDelta` with `CircleWatermark(lamport, id)`; the repair pass uses `circleRepairBatch`. The walk asserts the resulting accept, duplicate or refusal through phone and CLI logs rather than new wire bytes.

### Constants held by the walk

| Constant | Value | Role in the walk |
|---|---|---|
| Courier replay window (`MAX_PARCEL_TTL_MS`) | `24 * 60 * 60 * 1000L` | A carry event older than the 24 h courier replay window is OutsideWindow, not news; `CircleCore.REPLAY_WINDOW_MS` (6 h) does not bound courier carry |
| `CircleCore.CLOCK_SKEW_MS` | `5 * 60 * 1000L` | Future tolerance for the three nodes' clocks |
| `CircleCore.MAX_BODY_CHARS` | `16 * 1024` | Upper bound on a carry envelope body |
| `CircleCore.MAX_EVENTS_PER_REQUEST` | `1024` | Cap on one sync batch and repair batch |
| `CircleCore.MAX_REQUEST_BYTES` | `8 * 1024 * 1024` | Bounded Circle request body |
| `CircleCore.MAX_SEEN` | `4096` | Bounded replay id set shared by `evaluate` and `accept` |
| `FlitCircleSync.CIRCLE_SYNC_MIN_INTERVAL_MS` | `30_000L` | Minimum spacing between repair ticks |
| `FlitCircleSync.CIRCLE_BACKOFF_BASE_MS` / `CIRCLE_BACKOFF_MAX_MS` | `5_000L` / `120_000L` | `circleBackoffMs` yields 10s, 20s, 40s, 80s, 120s for attempts 1–5 |
| `FlitCircleSync.CIRCLE_PEER_DIALS` | `4` | Ceiling on concurrent dials, also for a three-node roster |

### State ownership

Circle id, key, roster, event log and rekey generation live in `FlitCircleStore` on the phone and in the per-node `circle.state` written by `Harness.kt` on the box. Carried parcels and their local status stay with the holder; nothing about status is gossiped. The CLI carrier keeps one `<id>.parcel` per sealed event in `courier-bag/` and one local-only row per parcel in `courier.state`, with opened event JSON in `courier-received/` and failed-open parcels in `courier-rejected/`; none of these enters a Circle event. The spool stays in app cache on the sender until the delivery concludes, and a thrown carrier leaves it pending. File bytes from `sendFiles` land in the node's `serve --dir`, and the landed hash is the correlation between a file card and the bytes. Circle file-delivery statuses use the local-only vocabulary `pending`, `sent`, `refused`, `failed` (`CircleDeliveryStatus` in `Harness.kt`, mirrored in `FlitCircleStore.CircleDelivery`); the courier's own row states are `pending`, `carried`, `delivered`, `expired`, `refused` and are read with `flit-cli courier status`.

### Observations read from the nodes

- Phone log lines from `FlitCircleSync`: `circle fan-out: N new event(s), M peer(s)[, K backed off]`, `circle sync: full-log repair to M peer(s)`, `circle sync: +K event(s) from <fp>`, and `recv-ok <name> → <path> (<bytes> bytes)`.
- CLI log lines from `ConsoleDelegate`: `seen …`, `offer from …`, `recv-start …`, `recv-resume … from <prefix> bytes`, `recv-ok …`, `recv-BAD …`, `recv-ERR …`. Carrier evidence comes from `flit-cli courier drop`, `carry`, `receive` and `status`: one row per parcel in `courier.state`, a file in `courier-bag/` or `courier-received/`, and the subcommand syntax in `courier-cli.md` (§2.4 of `docs/OPERATIONS.md`).
- File-card states on the phone come from `courier-ui.md`. Sender-side confirmation is not claimed: delivery receipts land on 2026-12-11, so the handoff is confirmed on C's log and landed hash, not by a remote status on A's card. An OK answer means C accepted the offer; a transport OK alone is never recorded as end-to-end delivery.

### Walk steps

1. Found the Circle on A and join B and C with `flit-cli serve --circle <id> --invite <token>` and `flit-cli circle join --host ip:port --circle <id> --invite <token>`. Record the three fingerprint prefixes, the circle id and each node's `FLIT_HARNESS_HOME`. Confirm all three see the same roster with `flit-cli circle sync` on the box nodes and a repair tick on the phone.
2. Stop C's CLI process. Post a fresh seeded file from A as a file drop, or from B with `flit-cli circle file --file <path>`. Record the announcement's sha256 and size. On B, seal the signed event for absent C with `flit-cli courier drop --to <C-fp> --text <note> --circle <id> [--cert <B64>] [--ttl <ms>] [--bag <dir>]`; confirm one `pending` row in `courier.state` and one `<id>.parcel` in `courier-bag/`. Confirm B accepts and carries the envelope for absent C, and that C has no `recv-start` line.
3. Keep C down through one `CIRCLE_SYNC_MIN_INTERVAL_MS` (30 s) repair tick and at least the first `circleBackoffMs(1) = 10s` retry window. Confirm the phone logs a backed-off peer, C logs nothing, and no full-log flood appears.
4. Restart C and trigger the encounter through the CLI carrier: run `flit-cli courier carry [--bag <dir>]` on B so the row moves `pending → carried`, copy B's `courier-bag/` into C's harness home (or pass `--bag <dir>`), then run `flit-cli courier receive --bag <dir>` on C so the row moves to `delivered` and the opened event JSON is recorded in `courier-received/`. Confirm C accepts the handoff envelope, the file bytes land hash-exact with `recv-ok` in C's `serve --dir`, and the landed sha256 matches the announcement. Record the file card state visible on the phone, the `courier.state` rows shown by `flit-cli courier status` on B and C, and B's Circle file-delivery status.
5. Replay the same carry id to C: copy the same `<id>.parcel` into C's bag again and run `flit-cli courier carry` then `flit-cli courier receive --bag <dir>`. Confirm a Duplicate or OutsideWindow decision in `circle.state`, no second fold, one `delivered` row in `courier.state` under `flit-cli courier status`, no second `recv-ok`, one landed file and no leftover `.part` or `.part.token` sidecars in C's `serve --dir`.
6. In a separate pass, make C leave with `flit-cli circle leave` before a handoff and confirm B does not seal a new parcel to the departed fingerprint and keeps any existing parcel locally in `courier-bag/` with its row at `pending` in `courier.state`; no bytes go to C and the key rotation makes the stale parcel fail `open` on C, which `flit-cli courier receive` records as `refused` with the file moved to `courier-rejected/`. Run `flit-cli courier sweep [--bag <dir>]` to confirm expired rows move to `expired` and their envelopes are dropped.

## Invariants

1. Handoff adds no route: the carry event uses `CircleRequest.ACTION_SYNC` / `CircleResponse` and the bytes use `ProtocolClient.sendFiles`.
2. Every carried envelope passes `validShape → verify → evaluate → store` in that order; a forgery reserves no id and lands no bytes.
3. A carry event outside `[createdMs - 24h, createdMs + 5min]` is OutsideWindow and is not stored.
4. One carry id is accepted once; a replay is Duplicate and produces no second delivery.
5. A file announcement body stays exactly `{name,size,sha256}`; no spool path or queue token appears on the signed wire.
6. Landed bytes are hash-exact; a mismatch reports `recv-BAD` and drops the temp and its sidecars.
7. A throw from the carrier leaves the parcel and spool pending for retry; only a returned report concludes a status.
8. An unreachable member is skipped until `circleBackoffMs(attempt)` expires: 10s, 20s, 40s, 80s, 120s, capped at 120s.
9. Repair ticks are at least `CIRCLE_SYNC_MIN_INTERVAL_MS = 30_000L` apart.
10. Concurrent dials never exceed `CIRCLE_PEER_DIALS = 4`, even with three nodes.
11. B and C use different `FLIT_HARNESS_HOME` paths; identities and `circle.state` are never shared.
12. Delivery status stays local-only: Circle file statuses in `circle.state` and courier rows in `courier.state` are never gossiped or written into a signed event. No observation claims sender-side confirmation before the 2026-12-11 receipts layer.
13. A departure rotates the key; a stale carry from a departed fingerprint is refused and its bytes are not delivered.

## Failure modes

| Condition | Conclusion | Keeps bytes | Drops | Evidence |
|---|---|---|---|---|
| C offline when the drop is posted | Pending for C; B seals a `courier drop` parcel and marks it `carried` | Spool on A, `<id>.parcel` in B's `courier-bag/` | Nothing | Phone `circle fan-out … backed off`; no `recv-start` on C; `courier.state` row `pending` then `carried` |
| C unreachable across ticks | Retry delayed by `circleBackoffMs`, repair every ≥30 s | Spool and envelope | Nothing | Backoff gap in phone log, no `recv-ok` on C |
| Carrier throws during handoff | Pending; no conclusion from a throw | Spool and envelope | Nothing | No `recv-ok` on C, carrier error line, `flit-cli courier status` still `pending` |
| Forged or unsigned carry envelope | Refused at `verify`; no id reserved | Holder's own copy | The forged envelope | No card on C, refusal line in CLI log, exit `3` and file in `courier-rejected/` on a failed `open` |
| Carry id replayed after acceptance | Duplicate; no second delivery | Landed file on C | The duplicate event | One `recv-ok`, one `delivered` row in `courier.state`, no new `.part`/`.token` |
| Carry older than 24 h or >5 min ahead | OutsideWindow | Holder's copy | The stale event | No card on C, no `recv-ok`; `flit-cli courier sweep` moves the row to `expired` |
| Body hash mismatch on landing | `recv-BAD` | Nothing at receiver | Temp file and sidecars | `recv-BAD <name>`, empty receive directory |
| C left before handoff | Refused after departure; no new parcel to C | Parcel stays local in B's `courier-bag/` | Remote delivery to C | No `recv-start` on C; on a stale parcel C's `open` refusal sets `refused` and moves the file to `courier-rejected/` |

## Tests

PURE — re-run the earlier JVM layers with the rig: `CircleCoreTest` and `CircleHardeningTest` for shape, signature, order, dedup and replay decisions; `FlitCircleSyncTest` for `circleDelta`, `CircleWatermark`, `circleBackoffMs`, `circleFanoutTargets` and repair batching; `FlitCircleStoreTest` for atomic updates and local-only delivery rows; the carry-core tests added on 2026-11-27 and the file-card state tests added on 2026-12-01. Constants and refusal reasons are asserted exactly, with `createdMs` injected for window edges.

SIM-HARN — re-run `CircleHarnessTest` and `CourierCarrierHarnessTest` from 2026-11-30 with a distinct harness home per node: `courier drop` for offline C, `courier carry` on B, bag copy, `courier receive --bag` on C after restart, duplicate replay, and hash-exact landing through the production `ProtocolClient` / `ProtocolServer` and `CircleCore` seams. No lookalike core.

DEVICE — the six walk steps above on the phone plus two box identities. The walk is recorded only if it is actually run; until then the three-node handoff is labelled test-evidenced only.

## Evidence for this day

`docs/completions/2026-12-02-courier-walk.md`, written when the walk runs, holds the date, the node table with fingerprint prefixes and harness homes, the circle id, the payload sha256 and size, the exact `flit-cli courier drop/carry/receive/status/sweep` and `flit-cli circle` commands, `courier.state` row snapshots for B and C, timestamped log excerpts for each step, C's landed path and hash, the phone's card state and B's local status, the observed backoff gaps, and the conclusions labelled DEVICE. Limitations recorded with it: a courier chain with more than one carrier is not verified; sender-side confirmation is test-evidenced only until receipts land on 2026-12-11; three members are the rig ceiling, with a larger mesh not verified; Wi-Fi Aware stays parked with the hardware absent.

## Out of scope

Air-gap QR parcels (2026-12-03 onward), per-drop content keys (2026-12-09 onward), delivery receipts (2026-12-11 onward) and the Circle pad (2026-12-15 onward) are not exercised on this day. So are multi-hop courier chains, more than three nodes, a desktop GUI, Wi-Fi Aware and `.flitpack` bundles. No new protocol route, code change or Gradle change is part of this layer, and no completion note is written before the walk happens.

## Commit

2026-12-02 Device — Courier three-node walk and evidence note
