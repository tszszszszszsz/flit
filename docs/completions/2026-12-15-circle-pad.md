# 2026-12-15 — Circle pad protocol core (Protocol)

Layer: 2026-12-15 Protocol. The work is a JVM-pure shared-pad core in
`app.flit.share.protocol.circle`. A pad is a line-oriented set of keyed entries
in a Circle. This layer adds the twelfth event verb, a strict body gate and a
pure fold that turns signed `pad` events into one derived view. It adds no app
surface, CLI command, route, header, DTO field, persisted store field, schema
field, signature primitive or v2.2 status. The app StateFlow, Compose card and
CLI command belong to 2026-12-16; the device walk belongs to 2026-12-17.

The layer is built ahead of its scheduled day; the commit stays on 2026-12-15.

Counts below come from the JUnit XML of a fresh `--rerun-tasks` run, as required
by `TESTING.md` §3 and §8. No device observation is claimed on this date; the
pad is test-evidenced only.

## Files in the layer

| File | Content |
| --- | --- |
| `protocol/src/main/kotlin/app/flit/share/protocol/circle/CircleEvent.kt` | `PAD = "pad"` constant and its addition to `CircleEvent.TYPES` as the twelfth verb, with its two body shapes in the KDoc |
| `protocol/src/main/kotlin/app/flit/share/protocol/circle/PadCore.kt` | JVM-pure object with no mutable state: body bounds, key sets, body builders, structural gate, `PadLine`/`PadView`, `view` fold and `merge` |
| `protocol/src/test/kotlin/app/flit/share/protocol/PadBodyTest.kt` | Body key set, field bounds and body-builder tests |
| `protocol/src/test/kotlin/app/flit/share/protocol/PadViewTest.kt` | Filter, dedup, conflict rule, ordering and cap tests for `view` |
| `protocol/src/test/kotlin/app/flit/share/protocol/PadMergeTest.kt` | View-merge and fold-merge equivalence tests |
| `protocol/src/test/kotlin/app/flit/share/protocol/PadCanonicalTest.kt` | Canonical vectors, sign/verify round-trip and existing-verb invariance tests |
| `docs/TESTING.md` | Four new protocol classes with counts from the protocol run |
| `docs/CONTRACTS.md` | Pad body contract, view type and conflict rule |
| `docs/SECURITY.md` | Signed-body gate and the receiving route's ownership of verification and pinning |
| `docs/FAILURE-MODES.md` | Malformed body, wrong circle, stale `del`, replayed id and evicted-line refusals |
| `docs/OPERATIONS.md` | Limits of this layer in operational terms |
| `docs/ARCHITECTURE.md` | Pad seam in the protocol layer |
| `docs/PERFORMANCE.md` | Fold cost and bounded state; no invented timings |
| `docs/completions/2026-12-15-circle-pad.md` | This layer record |
| `docs/roadmap/circle-pad.md`, `docs/roadmap/01-schedule.md`, `docs/roadmap/02-waves.md` | Finalized protocol seams and build-ahead note |
| `README.md` | Pad capability bullet, with no device claim |

The layer is confined to the JVM-pure protocol module. It imports `WireJson` and
the existing Circle primitives only. No Android import, socket, file write,
clock sleep, second signature path, pin store or durable pad store is part of
this layer.

## Public surface

The exact API held by the frozen seams:

```
object CircleEvent {
  const val PAD = "pad"
  val TYPES: Set<String> = setOf(
    MESSAGE, FILE, POLL, VOTE, JOIN, LEAVE, KICK, ROLE, REKEY, PARCEL, RECEIPT, PAD,
  )                                   // twelve verbs
}

object PadCore {
  const val MAX_LINES = 256
  const val MAX_KEY_CHARS = 128
  const val MAX_TEXT_CHARS = 4096       // 4 * 1024
  val PAD_PUT_KEYS: Set<String> = setOf("op", "key", "text")
  val PAD_DEL_KEYS: Set<String> = setOf("op", "key")

  data class PadLine(
    val key: String, val text: String, val lamport: Long = 0L,
    val authorFp: String = "", val id: String = "", val deleted: Boolean = false,
  )
  data class PadView(
    val lines: List<PadLine>, val winners: Map<String, PadLine> = emptyMap(),
  )   // lines lexicographic by key; winners also holds tombstones for merge

  fun padPutBody(key: String, text: String): String
  fun padDelBody(key: String): String
  fun validPadBody(body: String): Boolean
  fun view(events: Iterable<CircleEvent>, circleId: String): PadView
  fun merge(views: Iterable<PadView>): PadView
}
```

`validPadBody` parses with `WireJson.json` into a `JsonObject`, requires
`obj.keys == PAD_PUT_KEYS` when `op` is `"put"` and `obj.keys == PAD_DEL_KEYS`
when `op` is `"del"`, and requires `op`, `key` and `text` to be non-blank JSON
string primitives. A `put` key is at most `MAX_KEY_CHARS` (128) and its text at
most `MAX_TEXT_CHARS` (4096); a `del` key is at most `MAX_KEY_CHARS`; the whole
body stays within `CircleCore.MAX_BODY_CHARS` (16 KiB). An extra, missing or
renamed key, a non-string value, a blank field or an unknown `op` is refused.
The gate is structural only: it parses and compares, never throws, signs,
pins or reads a store. `padPutBody` and `padDelBody` emit the exact bodies the
gate accepts, so a body cannot be minted in a shape the gate would refuse.

No other method is added. `CircleCore.canonical`, `create`, `sign`, `verify`,
`order`, `dedup`, `accept`/`evaluate`, `fresh`, `validShape`, `tally` and
`kickTally` keep their current signatures and behaviour. `CircleRequest`,
`CircleResponse`, `Link` and `CircleWire.canonical` keep their shapes.

## Event shape and body gate

The signed pad body has one of exactly two closed shapes:

```
put: {"op":"put","key":"<line-key>","text":"<text>"}
del: {"op":"del","key":"<line-key>"}
```

`key` is the line key and `text` is the line content. There is no second time
field, path, author name, per-line key, local status string or any other key in
the body; `CircleEvent.createdMs` is the event time. A `put` whose text is blank
or over the cap and a `del` with any key beyond the gate are refused before
signing. The closed key sets keep an extra field from entering a signed,
fanned-out event through the pad verb.

## Conflict rule and fold

`view(events, circleId)` keeps only events whose `type` is `PAD`, whose
`circleId` equals the argument and whose body passes `validPadBody`. It applies
`CircleCore.dedup` so a repeated event id counts once even when a snapshot is
replayed. It does not call `CircleCore.verify`: verification and the replay
guard run at the receiving route (`FlitCircleSync.fresh`, then
`CircleCore.accept`) before events reach the fold, so a pure test or a cached
snapshot cannot pin a certificate here and `PadCore` owns no pin store.

For one `key`, the operation with the greatest order key
`(lamport, authorFp, id)` wins. A `put` sets the line text; a `del` with the
greater order key tombstones the key; a `del` older than the winning `put` is
inert. `PadView.lines` lists live keys in lexicographic order, omits tombstoned
keys and holds at most `MAX_LINES` (256) entries. The order key comes from
signed fields and `id` is unique, so two devices resolve the same event set to
the same view regardless of delivery order, and folding an event already in the
log changes nothing. `merge` applies the same per-key rule to views already
derived from separate event sets, so `view(a + b) == merge(view(a), view(b))`.
`validPadBody`, `view` and `merge` are total: malformed JSON, blanks, oversized
fields and non-string values are refused or ignored, never thrown.

## Canonical, signature and replay

Pad events reuse `CircleCore.canonical(e)` unchanged: domain tag `FLITCIRCLE1`,
then length-prefixed `id`, `circleId`, `authorFp`, `writeLong(lamport)`, `type`,
`body`, `writeLong(createdMs)`. The body is covered as one length-prefixed
field, so no delimiter can be smuggled through it. `sig` and `cert` stay outside
the signed bytes. `CircleCore.create` mints a pad event with the writer's
`TlsIdentity`, `sign` writes `sig`, and `verify` binds the embedded
certificate's own SHA-256 DER to `authorFp` before verifying and pinning. There
is no second signature primitive; this is the same `TlsIdentity.sign` /
`verify` path used by the other Circle verbs.

Pad events use `CircleCore.order`, `dedup`, `evaluate`/`accept` and `fresh`
exactly as the other verbs do: `REPLAY_WINDOW_MS` = 6 h, `CLOCK_SKEW_MS` =
5 min, `MAX_SEEN` = 4096, `MAX_EVENTS_PER_REQUEST` = 1024, `MAX_REQUEST_BYTES` =
8 MiB, `MAX_SIG_CHARS` = 1024 and `MAX_CERT_CHARS` = 8 KiB, with precedence
`Invalid → OutsideWindow → Duplicate → Accept`. Callers verify before
`evaluate`, because `evaluate` reserves the id before crypto. No new cap is
introduced: the durable log is already bounded by `FlitCircleStore.EVENT_CAP` =
500.

## Wire and state ownership

No new route, header, DTO field or QR parameter is added. A pad event travels
in `CircleRequest.events` on the existing `POST /api/flit/v1/circle/sync`, or
inside a courier/QR parcel payload that already carries signed events, through
`FlitCircleSync.fanout` and the calm repair pass. The v2.2 route set and
response shapes stay unchanged.

The signed `pad` events in `CircleState.events` are the only durable pad state.
`PadCore` keeps no state between calls; every `view` rebuilds from the events
passed in. The replay id set stays in `CircleCore`, owned by the receiving
route. There is no second persisted pad store and no new store field. The app
may hold a derived `PadView` in a StateFlow, rebuilt on each store fold, but
never persists a second copy. A member offline during a pad write receives the
event through the courier carry core or the repair tick; a later joiner sees
the pad from the bounded log only, with no backfill of evicted lines. There is
no per-type reservation in `EVENT_CAP`: pad, receipts and file announcements
share the same 500-event log.

## Verification

Fresh pure run, counts read from the JUnit XML:

```
source env.sh && ./gradlew --no-daemon --rerun-tasks \
  :protocol:test :app:testDebugUnitTest :harness:test
```

| Module | Before (last green inventory) | After this layer | Task |
| --- | --- | --- | --- |
| `protocol` | 240 | 283 | `:protocol:test` |
| `app` | 296 | 296 (unchanged by this layer) | `:app:testDebugUnitTest` |
| `harness` | 75 | 75 (unchanged by this layer) | `:harness:test` |
| Total | 611 | 654 | — |

`--rerun-tasks` is required: stale XML has previously reported green against
an older class layout. The "before" figures are the inventory before this
layer; the four pad classes add 16 + 9 + 11 + 7 = 43 cases to the protocol
module, taking it from 240 to 283. The fresh run reported 0 failures, 0 errors
and 0 skipped cases in all three modules; the app and harness totals are
unchanged by this layer.

## Tests

| Class | Cases | What it pins |
| --- | --- | --- |
| `PadBodyTest` (new) | 16 | `validPadBody` accepts exactly `PAD_PUT_KEYS = {op,key,text}` when `op == "put"` and `PAD_DEL_KEYS = {op,key}` when `op == "del"`, refusing an extra or missing key, a wrong-typed `op`/`key`/`text`, an unknown `op`, a blank or over-cap `key` (`MAX_KEY_CHARS = 128`) and a blank or over-cap `text` (`MAX_TEXT_CHARS = 4096`), with every accepted body at or below `CircleCore.MAX_BODY_CHARS`; `padPutBody`/`padDelBody` emit bodies the gate accepts, including a put at the text cap, and malformed JSON, blanks and oversized fields are refused rather than thrown |
| `PadViewTest` (new) | 9 | `view(events, circleId)` keeps only `pad` events for the given circle with a body the gate accepts, ignoring other verbs, other circles and malformed bodies, and applies `CircleCore.dedup` so a repeated id counts once without calling `CircleCore.verify`; per `key` the operation with the greatest `(lamport, authorFp, id)` wins — a `put` sets the text, a greater-key `del` tombstones the key and an older `del` is inert — with `PadView.lines` sorted lexicographically, tombstoned keys omitted and at most `MAX_LINES = 256` entries; opposite delivery orders and a re-fold of the same events give byte-identical views |
| `PadMergeTest` (new) | 11 | `merge` applies the same per-key greatest-order-key rule to views already derived from separate event sets, so `view(a + b) == merge(view(a), view(b))` for disjoint and overlapping sets and for either merge order; a tombstone in one view wins over an older put in the other, re-merging a view is idempotent and a merged view never grows past `MAX_LINES` |
| `PadCanonicalTest` (new) | 7 | `CircleEvent.PAD = "pad"` is the twelfth verb in `TYPES` with the existing verb values unchanged; `CircleCore.canonical` covers the same seven fields except `sig`/`cert` for `pad`, so a `pad` event minted by `CircleCore.create` verifies and a forged `authorFp` or a changed covered field fails `verify`, while canonical bytes, replay order, `dedup` and `accept` for the existing verbs stay byte-identical and no route, header, DTO field or v2.2 status is added |

Case counts come from the clean `--rerun-tasks` JUnit XML run, not from a
target figure: 16 + 9 + 11 + 7 = 43 cases, 0 failures, 0 errors and 0 skipped.
Test names are the `@Test` declarations in each class. The existing protocol
suites (`CircleCoreTest`, `CircleHardeningTest`, `LinkServerParityTest`) were
re-run unchanged, with their canonical bytes, refusals and v2.2 status
behaviour intact. No app, CLI, harness or device test is added on this date.

## Evidence

| Label | Claimed here | Basis |
| --- | --- | --- |
| CODE | Yes | `CircleEvent.kt` and `PadCore.kt` match the API, byte rules and bounds recorded above; no new route, header, DTO field, store field or v2.2 status |
| PURE | Yes | The four protocol test classes above ran in a fresh `--rerun-tasks` run: 16 + 9 + 11 + 7 = 43 cases, 0 failures, 0 errors and 0 skipped |
| SIM-HARN | No | No CLI command, in-process node or harness walk exercises a pad on 2026-12-15; that wiring is a later layer |
| DEVICE | No | No phone, second phone, radio, carrier or camera observation is made on this date |

The weakest recorded level governs each claim. No device observation is
claimed by this layer. A pad on this date is a protocol primitive with pure
test evidence only; it is not an observed shared-pad exchange between devices.

## Limitations

- No app, CLI, harness or device work is part of this layer. The StateFlow,
  Compose card, CLI command, host filter and any device observation belong to
  2026-12-16 and 2026-12-17.
- A `del` older than the winning `put` is inert, and an evicted event can take a
  line with it. There is no backfill of lines evicted from the bounded log, no
  rich text, no attachments and no per-line encryption.
- `view` does not verify signatures or pin certificates. It assumes the
  receiving route has already run verification and the replay guard; a caller
  that feeds it unverified events gets a view of those events, not a statement
  that they are authentic.
- The protocol module holds no durable pad state. The signed events in the
  bounded log are the only state, and a derived view is rebuilt on each fold.
- No new cap is introduced. The 500-event log cap, the 1024-event per-request
  cap and the bounded `seen` set still apply; pad events add no unbounded state.
- Canonical bytes and the replay rules of the existing verbs are unchanged.
  No v2.2 surface changes: no new route, header, QR parameter or JSON DTO field
  with a changed default.
- A busy pad shares the 500-event log with other verbs and can push an older
  event out; there is no per-type reservation. A three-or-more-member Circle
  remains unverified until it is walked.
- Performance figures are not stated; no timings are invented.
- No device walk, phone observation, second physical phone or live mesh proof
  is claimed on this date.

## Verification status

Counts for `PadBodyTest`, `PadViewTest`, `PadMergeTest` and `PadCanonicalTest`
(16 + 9 + 11 + 7 = 43 cases), the protocol total of 283 and the three-module
total of 654 come from a clean `--rerun-tasks` run with 0 failures, 0 errors and
0 skipped cases. CODE and PURE evidence are recorded above from that run; no
SIM-HARN or DEVICE evidence exists for the pad on this date. The route and frame
surface is unchanged from the 2026-12-14 receipts parity layer. Later layers
took the tree to protocol 330, app 309, harness 75, total 714; the figures in
this record are the state at this layer and are historical against that tree.
