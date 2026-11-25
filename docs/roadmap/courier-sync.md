# Courier sync and file-card states

Folds sealed courier parcels into the app's Circle sync worker and publishes local handoff outcomes for the existing file card, using the protocol envelope and carry core unchanged.

## Date and layer

2026-12-01, App. Commit scope: courier sync and file-card states, tests.

## Depends on

- 2026-11-27, Protocol — [`courier-envelope.md`](courier-envelope.md) and [`courier-carry.md`](courier-carry.md). They define the only new wire kind, `PARCEL`, with the closed body `{toFp, envelope}`; the envelope carries signed Circle events and owns its id and TTL. The app calls `CourierEnvelope.seal`, `verify` and `open` for the bytes, and `CourierCarry.validParcelBody`, `parcelOf`, `evaluate`, `fold`, `select`, `ack`, `expire`, `encode`, `decode` and `expired` for the bag. Nothing here redefines those shapes or constants.
- 2026-11-30, CLI — [`courier-cli.md`](courier-cli.md). Its carrier supplies the harness observations this layer consumes; no harness class is duplicated here.
- Existing seams: `FlitCircleSync` (`emit`, `fanout`, `fresh`, `sync`, hooks), `FlitCircleStore` (`update`, codec, reducers), `FlitEngine` (`refreshCircle`, `circleDial`, `circleState`), `CircleCore` (verify, order, dedup), `CircleRequest.events` on `/circle/sync`.
- Same-day boundary: `courier-ui.md` owns the Compose rendering and reads `FlitEngine.courierHandoffs`; this file owns the worker, the bag writes and that flow's contents.

## Scope

A sender seals the signed events for an absent member with `CourierEnvelope.seal`, publishes the envelope in a `parcel` event over the existing `/circle/sync` route, and present members hold the opaque bytes in a bounded local bag. When a carrier meets the addressee, it re-offers the held signed event in that peer's sync request; removal follows an OK answer. The addressee opens the envelope, verifies and folds the inner events, and the file card's Open action stays gated by the landed-SHA set. Handoff outcomes are local, keyed by SHA-256, and never enter an event.

## Design

### Wire event — one kind, one closed body

`PARCEL = "parcel"` is the tenth Circle verb on 2026-11-27. Its body is exactly:

```
{"toFp":"<uppercase fingerprint>","envelope":"<Base64 CourierEnvelope.encode bytes>"}
```

`validParcelBody` from the carry core is the single gate; any extra key, a blank or oversized `toFp`, or a blank or oversized `envelope` is refused. `MAX_ENVELOPE_CHARS = 12 * 1024` keeps the body under `CircleCore.MAX_BODY_CHARS = 16 KiB`. There is no `handoff` event, no phase field and no `parcelId`, `sha256`, `name`, `size`, `path` or queue token in the body. The inner Circle events — including any `file` event with its exact `{name,size,sha256}` body — are encrypted inside the envelope. A carrier cannot read them.

`FlitCircleSync.postParcel(toFp, envelopeB64)` builds that body, calls `circleBodyValid` (one `PARCEL` case delegating to `validParcelBody`), then `emit` and `fanout`. `fresh()` applies `validParcelBody` before `CircleCore.verify`, in the same position where `validFileBody` guards a `file` event. Verification still binds the event's cert to `authorFp` by the cert's own SHA-256 DER.

### Bag state and persistence

`FlitCircleStore.CircleState` gains one additive, defaulted field:

```
courierBag: String = ""   // CourierCarry.encode(CarryState)
```

A blank string is an empty bag, so old rows and existing named-argument constructors keep working. `decode()` calls `CourierCarry.decode` and tolerates a corrupt row as an empty bag; no exception reaches a caller. The bag holds at most `MAX_PARCELS = 256` rows, trimmed by `CourierCarry.order` rather than arrival order.

Every bag write goes through `FlitCircleStore.update`, which holds `ioLock` for the whole decode → fold → encode → persist sequence. A bag write cannot interleave with a sync `upsert` or clobber the event log, roster or delivery statuses. The reducers are the protocol ones: `fold` on acceptance, `ack` on a concluded handoff, `expire` on a sweep, `merge` when two local views are combined. Expiry is decided by `CourierCarry.expired` against the envelope's own `expiresMs`; the app never extends a TTL.

### Sync and handoff path

1. `FlitEngine.circleCourierDrop(events, toFp)` obtains the recipient certificate from a signed event by that fingerprint or the host roster answer, calls `CourierEnvelope.seal`, then posts the returned envelope with `FlitCircleSync.postParcel`. Without that certificate the drop is refused and the source events stay in the sender's own log.
2. A member folding a `parcel` event runs `CircleCore.verify` first, then `CourierCarry.parcelOf` and `evaluate`. Only `Accept` calls `fold` on the bag and persists it. `Invalid`, `OutsideWindow`, `Expired`, `Duplicate` and `Full` leave the bag unchanged and are logged with the reason.
3. On each `fanout` or calm `sync` pass, the app calls `CourierCarry.select(bag, fp, nowMs)` for that peer and appends the selected signed events to `CircleRequest.events`, deduplicated by event id and capped at `CircleCore.MAX_EVENTS_PER_REQUEST = 1024`. A held parcel is re-offered through the same route even when the carrier already has the event in its own bounded log.
4. An OK answer concludes the offered ids with `CourierCarry.ack`; the handoff outcome becomes `handed`. A refusal or a thrown carrier leaves the bag untouched and the outcome `failed`; the parcel is re-offered on a later pass.
5. When `toFp` is this device, `CourierEnvelope.open` decrypts the envelope with the local `TlsIdentity`. Each returned event passes `CircleCore.verify` and enters `FlitCircleStore.merge`. No new byte route and no second signature path are introduced. A failed or expired open folds nothing and keeps the bag row for the core's expiry sweep.

### Local handoff projection

`FlitEngine.courierHandoffs: StateFlow<Map<String, String>>` is keyed by the SHA-256 of a file event the app learned by sealing or opening an envelope. Values are the three local outcomes consumed by the card: `waiting`, `handed`, `failed`; an unknown or blank value reads as `waiting`. The map is process-local: it is not persisted, not signed and not gossiped. `refreshCircle()` includes it in the content-diff signature so a new outcome publishes without a log change. `MainActivity` reads it with `collectAsState()` and passes it to `CircleScreen`; the UI owns the copy and the Open affordance.

A carrier that cannot open an envelope has no SHA-256 to key a row with, so it publishes nothing for that parcel and the card reads `waiting`. This keeps ciphertext-only custody intact: the app does not invent a SHA key from envelope bytes.

### Constants and ownership

Protocol-owned, read but not redefined: `PARCEL`, `PARCEL_BODY_KEYS = {toFp, envelope}`, `MAX_ENVELOPE_CHARS = 12 * 1024`, `MAX_PARCELS = 256`, `MAX_SEEN = 4096`, `MAX_PARCEL_EVENTS = 1024`, `MAX_PARCEL_TTL_MS = 24 h`, `CLOCK_SKEW_MS = 5 min`, `ALG = "AESGCM+RSAOAEP256"`. App-owned: `COURIER_TICK_MS = 30_000L`, matching `CIRCLE_SYNC_MIN_INTERVAL_MS = 30_000L`; dial concurrency stays at `CIRCLE_PEER_DIALS = 4` and backoff at `CIRCLE_BACKOFF_BASE_MS = 5_000L` / `CIRCLE_BACKOFF_MAX_MS = 120_000L`.

State ownership follows `ARCHITECTURE.md`: circle id/key/roster/events in `FlitCircleStore`, the encoded bag in the same persisted row (local only), the handoff map in `FlitEngine` (process-local), the landed-SHA set rebuilt at boot. No spool path, content key or parcel plaintext is written by this layer.

## Invariants

1. `PARCEL` is the only new wire kind; its body has exactly `toFp` and `envelope`.
2. The envelope is `CourierEnvelope.encode` output stored verbatim in that body; the app never rewrites or re-signs it.
3. Verification precedes `evaluate`, and only `Accept` calls `fold` and persists a bag row.
4. All bag writes use `FlitCircleStore.update` under `ioLock`; no load → modify → save happens outside it.
5. Only `CourierCarry.select(bag, fp, nowMs)` parcels addressed to that peer enter its sync request, deduped by id and capped at 1024 events.
6. `ack` runs only after an OK answer. A refusal or a throw keeps the bag and sets `failed`.
7. `CourierEnvelope.open` runs only when `toFp` matches this device; inner events are verified by `CircleCore` and folded once by `FlitCircleStore.merge`.
8. `courierHandoffs` is local only: never signed, never persisted, never placed in a `CircleRequest`.
9. Expiry comes from `CourierCarry.expired` on the envelope's own `expiresMs`; the app does not extend it.
10. The added verb is additive to a Flit-only route. No v2.2 route, DTO shape or status code changes.

## Failure modes

| Condition | Conclusion | Keeps bytes | Drops | Evidence |
| --- | --- | --- | --- | --- |
| Recipient certificate unavailable | Drop refused; no `parcel` event posted | Source events stay in the sender log | Nothing | `courier drop: no certificate for <fp>` |
| `parcel` body has extra keys or oversized fields | Rejected before `evaluate` | Bag unchanged | Incoming event | `courier: invalid parcel body` |
| Event signature or cert binding fails | Refused before `evaluate` | Bag unchanged | Incoming event | `courier: parcel signature refused` |
| Parcel outside window or past TTL | `OutsideWindow` / `Expired`; no fold | Bag unchanged | Incoming event | `courier: parcel expired` |
| Duplicate envelope id | `Duplicate`; no second fold | Held copy stays | Repeat copy | `courier: duplicate parcel id` |
| Bag at `MAX_PARCELS` | `Full`; no id reserved | All 256 held parcels | Incoming offer | `courier: bag full, 256 parcel(s) held` |
| Addressee not met on a pass | Held for a later encounter | Bag and signed event stay | Nothing | `courier: holding for <fp>` |
| Handoff answered OK | `ack` removes offered ids; outcome `handed` | Opened copy on the recipient | Acknowledged bag rows | `courier: handed off N parcel(s) to <fp>` |
| Handoff refused or carrier throws | Outcome `failed`; held for retry | Bag stays | Nothing | `courier: handoff failed to <fp>` |
| `open` fails or envelope expired at recipient | No events folded; outcome `failed` | Bag stays for the core sweep | Parsed parcel only | `courier parcel unreadable — rejected` |
| `courierBag` row corrupt | Decodes as empty bag; log line written | Unrelated Circle state | Corrupt row | `courier: bag row unreadable, starting empty` |

## Tests

Pure tests come first, in the app module's JVM tests of Android-free seams. `FlitCircleSyncTest` adds: one `PARCEL` case in `circleBodyValid`; `fresh()` folding a verified parcel, refusing an unverified one and refusing extra body keys; `postParcel` emitting the two-key body; `fanout` and `sync` appending `select`ed events with id dedup and the 1024 cap; `ack` only on OK and a retained bag on a throw; `resetForTest()` clearing new hooks. `FlitCircleStoreTest` adds: `courierBag` absent on old rows, roundtrip through `encode`/`decode`, tolerant decode of a corrupt row, and a bag write leaving events, roster and deliveries intact. A new `FlitCourierHandoffTest` pins the pure outcome mapping — `waiting` default, `handed` only on OK, `failed` on refusal or throw — without an Android context.

Harness observations are consumed from the 2026-11-30 carrier: a parcel held across passes, a handoff removed after an OK answer, and a throw that keeps the bag. They carry SIM-HARN evidence; no duplicate harness bot is added here.

The device walk is not part of this day. The tests above are the precondition for the three-node record on 2026-12-02.

## Evidence for this day

`docs/completions/2026-12-01-courier-sync.md`, written when the layer lands, records the rig command, the green result for `:protocol:test`, `:app:testDebugUnitTest` and `:harness:test`, the new test names, and the `courier:` log lines observed in the harness. The baseline inventory is 302 = 82 + 210 + 10; the new total is written down at commit time. Evidence labels are CODE, PURE and SIM-HARN. No DEVICE claim is made on this day. Limitations recorded there: no phone-to-phone handoff walk, no live multi-member mesh, and no delivery receipt or decryption proof beyond the harness.

## Out of scope

The Compose rendering and copy (`courier-ui.md`), the three-node walk (2026-12-02), QR parcel frames (2026-12-03 onward), per-drop content keys (2026-12-09), delivery receipts (2026-12-11) and Circle pad (2026-12-15). File bytes never enter a parcel; this layer adds no byte-transfer route, no physical transport and no new `LinkProvider`. Multi-hop relay routing, Wi-Fi Aware, offline `.flitpack` bundles and remote revocation stay parked. The v2.2 wire is untouched.

## Commit

2026-12-01 App: courier sync and file-card states, pure and harness tests
