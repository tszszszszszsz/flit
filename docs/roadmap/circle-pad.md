# Circle pad CRDT core

A shared text pad for a Circle: signed `pad` events fold into one deterministic view on every device without a server.

## Date and layer

2026-12-15 — Protocol. The work is JVM-pure and lives in `protocol/`; it adds no app surface, CLI command, persistence adapter or device walk.

## Depends on

- 2026-11-27 (Protocol) — courier envelope and carry core. Pad events for absent members travel the same carry path and repair tick; the core itself opens no socket.
- 2026-12-09 (Protocol) — per-drop content keys, and 2026-12-11 (Protocol) — delivery receipts. Each adds a new `CircleEvent` verb with a strict body gate; the `pad` verb and gate merge with those sets rather than replacing them.
- No dated dependency for the fold itself. It uses only `CircleCore`, `CircleEvent` and `FlitCircleStore`, which already exist.

## Scope

Add one event verb, one strict body gate and a pure fold that turns the signed event log into a shared line-oriented pad. Define the closed body shapes, the conflict rule, the bounds and the derived view type. Wire nothing: no UI, no CLI command, no store adapter and no new route. The pad rides the existing `/circle/join` and `/circle/sync` routes through `FlitCircleSync.fanout` and the calm repair pass.

## Design

### Event verb and body shapes

`CircleEvent.PAD = "pad"` is added to `CircleEvent.TYPES`. `CircleCore.canonical`, `create`, `sign`, `verify`, `order`, `dedup` and `accept` are unchanged. A pad event is signed and checked like a `message` or `file` event, and its canonical bytes cover the same seven fields except `sig` and `cert`.

`body` is a small JSON object with two closed shapes:

```
put: {"op":"put","key":"<line-key>","text":"<text>"}
del: {"op":"del","key":"<line-key>"}
```

`PadCore.PAD_PUT_KEYS = setOf("op", "key", "text")` and `PadCore.PAD_DEL_KEYS = setOf("op", "key")`. `PadCore.padPutBody(key: String, text: String): String` and `PadCore.padDelBody(key: String): String` build these strings. The gate and its producers stay in one JVM-pure file so a body cannot be minted in a shape the gate would refuse.

### Pure core surface

All of the following live in `protocol/src/main/kotlin/app/flit/share/protocol/circle/PadCore.kt`, an `object` with no mutable state between calls. This is the finalized surface:

- `const val MAX_LINES = 256`, `const val MAX_KEY_CHARS = 128`, `const val MAX_TEXT_CHARS = 4096` — the pad bounds.
- `val PAD_PUT_KEYS = setOf("op", "key", "text")`, `val PAD_DEL_KEYS = setOf("op", "key")` — the two closed body key sets.
- `data class PadLine(val key: String, val text: String, val lamport: Long = 0L, val authorFp: String = "", val id: String = "", val deleted: Boolean = false)` — one line plus the order-key metadata (`lamport`, `authorFp`, `id`) of its winning operation and a `deleted` tombstone flag.
- `data class PadView(val lines: List<PadLine>, val winners: Map<String, PadLine> = emptyMap())` — the derived view; `lines` is lexicographic by `key` and holds no tombstoned key, while `winners` also carries tombstones and their order keys so `merge` can re-run the per-key conflict rule after the events are gone.
- `fun padPutBody(key: String, text: String): String` — builds a `put` body.
- `fun padDelBody(key: String): String` — builds a `del` body.
- `fun validPadBody(body: String): Boolean` — structural gate, modelled on `CircleCore.validFileBody`.
- `fun view(events: Iterable<CircleEvent>, circleId: String): PadView` — keeps only `pad` events for that circle with a readable body, applies `CircleCore.dedup`, then resolves conflicts per key.
- `fun merge(views: Iterable<PadView>): PadView` — applies the same per-key rule to views already derived from separate event sets, so `view(a + b)` equals `merge(view(a), view(b))`.

`view` does not call `CircleCore.verify`. Verification and the replay guard run at the receiving route (`FlitCircleSync.fresh`, then `CircleCore.accept`) before events reach the fold. Re-verifying here would pin certificates from a pure test or a cached snapshot, and `PadCore` owns no pin store. `view` does apply `CircleCore.dedup` because dedup is pure and a repeated id must count once even when a snapshot is replayed.

### Conflict rule

Each surviving operation carries the order key `(lamport, authorFp, id)` — the first three components of `CircleCore.ORDER`. For one `key`, the operation with the greatest order key wins: `put` sets the line text, `del` tombstones the key. A `del` older than the winning `put` is inert. `view` resolves that winner for every key and lists live keys in lexicographic order, omitting tombstoned keys. `merge` applies the same per-key rule across views already derived from separate event sets: for each key it keeps the line whose backing event has the greatest order key, so a tombstone wins over an older put but loses to a greater put. That winning `PadLine`, with its order key and `deleted` flag, is retained in `PadView.winners` so a later merge can re-run the rule. Order keys come from signed fields and `id` is unique, so two peers resolve the same event set to the same view regardless of delivery order, folding the same event twice changes nothing, and `view(a + b) == merge(view(a), view(b))` for any partition or shuffle of one event set.

### Constants

| Constant | Value | Note |
| --- | --- | --- |
| `PadCore.MAX_LINES` | `256` | Most live keys in a `PadView`; leaves headroom under `FlitCircleStore.EVENT_CAP = 500`. |
| `PadCore.MAX_KEY_CHARS` | `128` | Line key bound; below `CircleCore.MAX_FIELD_CHARS = 256`. |
| `PadCore.MAX_TEXT_CHARS` | `4096` | Per-line text; a put body with a quoted key and text stays under `CircleCore.MAX_BODY_CHARS = 16 * 1024`. |
| `PadCore.PAD_PUT_KEYS` / `PAD_DEL_KEYS` | `setOf("op", "key", "text")` / `setOf("op", "key")` | Closed key sets for the two verbs. |
| `CircleCore.MAX_EVENTS_PER_REQUEST` | `1024` | Reused bound on events in one sync batch. |
| `CircleCore.REPLAY_WINDOW_MS` / `CLOCK_SKEW_MS` | `6 h` / `5 min` | Reused replay window; older pad events are refused at the route. |
| `CircleCore.MAX_SEEN` | `4096` | Reused bounded id set for `accept`/`fresh`. |

### State ownership

The signed `pad` events in `CircleState.events` are the only durable pad state. `PadCore` keeps no state between calls; every `view` rebuilds from the events passed in. The replay id set stays in `CircleCore`, owned by the receiving route. The app layer may hold a derived `PadView` in a StateFlow, rebuilt on each store fold, but never persists a second copy. A device that was offline receives pad events through the courier carry core or the repair tick; a later joiner sees the pad from the bounded log only, with no backfill of evicted lines — the same limit as file drops.

### Seam summary

| Seam | Use |
| --- | --- |
| `CircleEvent.PAD`, `CircleEvent.TYPES` | New signed verb; adds no route. |
| `CircleCore.canonical` / `create` / `verify` / `order` / `dedup` / `accept` | Unchanged primitives for the pad event. |
| `PadCore.validPadBody`, `padPutBody`, `padDelBody` | Closed body gate and its pure builders. |
| `PadCore.view`, `PadCore.merge` | Deterministic fold and view merge. |
| `FlitCircleStore.CircleState.events`, `FlitCircleStore.update` | Durable log and single-lock fold; no new persisted field. |
| `FlitCircleSync.fanout`, calm repair pass, `Link` | Delta handoff and bounded full-log repair on the existing transport. |

## Invariants

1. `validPadBody` accepts exactly `PAD_PUT_KEYS` when `op == "put"` and `PAD_DEL_KEYS` when `op == "del"`. Any extra key, missing key, wrong JSON type or unknown `op` is refused.
2. A `put` body has a non-blank `key` of at most `MAX_KEY_CHARS` and non-blank `text` of at most `MAX_TEXT_CHARS`; a `del` body has a non-blank `key` of at most `MAX_KEY_CHARS`. Every accepted body is at most `CircleCore.MAX_BODY_CHARS`.
3. A `pad` event minted by `CircleCore.create` verifies with `CircleCore.verify`; its canonical bytes cover every field except `sig` and `cert`, unchanged from the existing verbs — `pad` is the twelfth verb in `CircleEvent.TYPES`.
4. `view(events, circleId)` ignores events whose `type` is not `pad`, whose `circleId` differs, or whose body fails `validPadBody`; it applies `CircleCore.dedup` so a repeated id counts once.
5. Fold order does not matter: for any partition and shuffle of one event set, `view(a + b) == merge(view(a), view(b))` and the result is byte-identical.
6. Re-folding an event already present in the log leaves the `PadView` unchanged.
7. Per `key`, the operation with the greatest `(lamport, authorFp, id)` wins. A `put` sets the text; a `del` with a greater order key tombstones the key; a `del` with a smaller order key is inert.
8. `PadView.lines` is sorted lexicographically by `key`, contains no tombstoned key and holds at most `MAX_LINES` entries.
9. `validPadBody`, `view` and `merge` are total: malformed JSON, blanks, oversized fields and non-string values are refused or ignored, never thrown.
10. No pad event creates a new route, a new persisted field or a new encryption path. Pad text travels as signed JSON over the existing pinned TLS session; the Circle group key stays membership material, not encryption.

## Failure modes

| Condition | Conclusion | Keeps bytes | Drops | Evidence |
| --- | --- | --- | --- | --- |
| Body has extra/missing keys, wrong types or unknown `op` | `validPadBody` refuses; event not folded | Previous view unchanged | The malformed body | CODE `PadCore.kt`; PURE |
| `text` over `MAX_TEXT_CHARS` or body over `CircleCore.MAX_BODY_CHARS` | Gate refuses before signing or folding | Previous view unchanged | The oversized event | CODE; PURE |
| Event `circleId` differs from the local circle | `view` ignores it | Current view unchanged | The foreign event | CODE `PadCore.view`; PURE |
| Repeated event id | `CircleCore.dedup` keeps one | First occurrence | The repeat | CODE; PURE |
| `del` older than the winning `put` | Inert; line keeps its text | Winning `put` | The stale `del` | CODE; PURE |
| `createdMs` outside the replay window | `CircleCore.accept` returns `OutsideWindow`; id not reserved | Current view and seen set unchanged | The event | CODE `CircleCore.kt:342-360`; PURE |
| Same id already reserved at the route | `CircleCore.accept` returns `Duplicate`; no second delivery | Seen set unchanged | The duplicate | CODE; PURE |
| Cert cannot be bound to `authorFp` | `CircleCore.verify` false; route refuses | Pin store untouched | The event | CODE `CircleCore.kt:172-187`; PURE |
| Event trimmed by `FlitCircleStore.EVENT_CAP` | View rebuilt from remaining events; an evicted line can vanish | Remaining 500 events | The oldest events | CODE `FlitCircleStore.kt:473-477` |
| Member offline during the pad write | No live update until the courier carry or repair tick folds the event | Local view as last folded | Nothing | CODE; 2026-11-27 dependency |

## Tests

### Pure tests (this layer)

Four JVM-only classes under `protocol/src/test/kotlin/app/flit/share/protocol/`, using `kotlin.test` and `runBlocking` where a suspend path is needed:

- `PadBodyTest.kt` — `validPadBody` accepts the two exact shapes and refuses an extra key, a missing key, a non-string `op`/`key`/`text`, an unknown `op`, blank `key`/`text`, and fields over `MAX_KEY_CHARS` / `MAX_TEXT_CHARS`; `padPutBody` / `padDelBody` produce bodies that `validPadBody` accepts, and a put at `MAX_TEXT_CHARS` still fits `CircleCore.MAX_BODY_CHARS`; malformed JSON, blank fields and oversized fields never throw.
- `PadViewTest.kt` — `view` keeps only in-circle `pad` events with a valid body; other verbs, other circles and bad bodies are ignored. Two opposite delivery orders of the same put/del set give byte-identical `PadView`s, and re-folding the same event leaves the view unchanged. A `del` with a greater order key tombstones a key; an older `del` leaves the text in place. `PadView.lines` is sorted by key, omits tombstoned keys and never exceeds `MAX_LINES`.
- `PadMergeTest.kt` — `view(a + b) == merge(view(a), view(b))` for disjoint event sets; the per-key winner with its order key and tombstone flag survives in `PadView.winners`, so a tombstone in one view beats an older put in the other and re-merging is idempotent.
- `PadCanonicalTest.kt` — `CircleCore.create` with `PAD` verifies, and a forged `authorFp` or a changed body fails `verify`; canonical bytes cover the same seven fields with `sig`/`cert` excluded, and the existing verbs stay byte-identical.

Existing protocol suites (`CircleCoreTest`, `CircleHardeningTest`, `LinkServerParityTest`) are re-run unchanged; a `pad` verb must not alter their canonical bytes, refusals or v2.2 status behaviour. Counts and the exact rig command are recorded with the layer result, not asserted here.

### Harness

No harness test is added in this layer. Pad events over the production `ProtocolClient` / `ProtocolServer` routes are exercised when the app and CLI wiring lands on 2026-12-16, using the same in-process Circle nodes as `CircleHarnessTest`.

### Device walk

No device walk in this layer. The full feature walk is 2026-12-17; any pad observation on hardware belongs to that date and is labelled DEVICE only when actually run.

## Evidence for this day

`docs/completions/2026-12-15-circle-pad.md` records the protocol test-task result from a fresh `--rerun-tasks` run, the added test names and what each pins, the exact `PadCore` constants and file, and the post-layer protocol test count updated from the then-current 240 (a historical figure for that layer; the rig at that layer was 714 = 330 protocol + 309 app + 75 harness (historical; current inventory is 972), with zero failures). It records limitations at the CODE and PURE levels only: no UI, no CLI command, no persisted pad field and no device observation in this layer. Harness and device claims stay unmarked until their own dates.

## Out of scope

- No new route or header. Pad events use `/circle/join` and `/circle/sync` unchanged.
- No app UI, StateFlow, Compose card, CLI command or store adapter; those belong to 2026-12-16.
- No device walk or hardware claim; that belongs to 2026-12-17.
- No backfill of lines evicted from the bounded log, no rich text, attachments or per-line encryption.
- No change to `CircleCore` canonical bytes, replay order, dedup or the `v2.2` wire surface.
- No per-type reservation in `FlitCircleStore.EVENT_CAP` — pad, receipts and file announcements share the same 500-event log, and a busy pad can push an older event out.
- No multi-member mesh claim. A three-or-more-member Circle remains unverified until it is walked.

## Commit

2026-12-15 — Protocol: Circle pad CRDT core, pure JVM, tests
