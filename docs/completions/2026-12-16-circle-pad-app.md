# 2026-12-16 — Circle pad in app and CLI (Integration)

Layer: 2026-12-16 Integration. The JVM-pure pad core and the twelfth Circle
event verb landed on 2026-12-15 are wired into the app's Circle surface and the
`flit-cli` Circle commands. A Circle member can put a keyed text line, delete a
key, and read the derived pad from either surface. Both surfaces mint a signed
`pad` event through `PadCore` body builders and publish it on the existing
Circle sync path; both derive the displayed pad from the bounded signed log
through `PadCore.view`. No second pad store is introduced.

The layer adds no route, header, JSON DTO field, QR parameter, persisted store
field, schema field, signature primitive or v2.2 status. A pad event travels in
`CircleRequest.events` on the existing `POST /api/flit/v1/circle/sync`, or inside
a courier/QR parcel payload that already carries signed events.

The layer is built ahead of its scheduled day; the commit stays on 2026-12-16.

Counts below come from the JUnit XML of a fresh `--rerun-tasks` run, as required
by `TESTING.md` §3 and §8. At this layer the app carries 296 cases (281 baseline
plus 8 `PadUiTest` pad cases and 7 added `FlitEngineTest` cases), the harness
carries 75 (67 baseline plus 8 `PadCliTest` cases), and the protocol is unchanged
at 283 from the 2026-12-15 pad layer. No device observation is claimed on this
date: no phone, second phone, radio, carrier or camera walk is recorded by this
document, and the full feature walk belongs to 2026-12-17.

## Files in the layer

| File | Content |
| --- | --- |
| `app/src/main/kotlin/app/flit/share/FlitEngine.kt` | `padView: StateFlow<PadView>` rebuilt from store events through `PadCore.view`; `circlePadPut` / `circlePadDel`; a pad digest added to the `refreshCircle` signature |
| `app/src/main/kotlin/app/flit/share/FlitCircleSync.kt` | `postPadPut` / `postPadDel` using the `PadCore` body builders and the existing signed `post()` fan-out; PAD branch of `circleBodyValid` delegating to `PadCore.validPadBody` |
| `app/src/main/kotlin/app/flit/share/PadUi.kt` | Android-free `object PadUi` with `PadRow` and `rows`, `keyError` and `textError` bounds from `PadCore`; a Compose `PadCard` with edit/delete controls and 48 dp targets |
| `app/src/main/kotlin/app/flit/share/CircleUi.kt` | Additive pad surface entry in `CircleScreen`, passing `padView` and the pad callbacks |
| `app/src/main/kotlin/app/flit/share/MainActivity.kt` | Collects `FlitEngine.padView` and supplies the pad callbacks to the Circle screen |
| `app/src/test/kotlin/app/flit/share/PadUiTest.kt` | `PadUi.rows` derivation and field-bound tests |
| `app/src/test/kotlin/app/flit/share/FlitEngineTest.kt` | Added cases for the `padView` flow, the pad digest, and put/del publication |
| `harness/src/main/kotlin/app/flit/share/harness/Harness.kt` | `circle pad put --key K --text T`, `circle pad del --key K`, `circle pad show` |
| `harness/src/test/kotlin/app/flit/share/harness/PadCliTest.kt` | CLI put/del/show round-trip tests over in-process nodes |
| `docs/TESTING.md` | Three test-class rows with counts from the rig run |
| `docs/CONTRACTS.md` | Pad app and CLI seam contracts |
| `docs/SECURITY.md` | Signed pad body, receiving-route verification and the absence of per-line encryption |
| `docs/FAILURE-MODES.md` | Malformed key/text, stale `del`, evicted-line and carrier-failure refusals |
| `docs/OPERATIONS.md` | Limits of this layer in operational terms |
| `docs/ARCHITECTURE.md` | Pad seams in the app and CLI integration layers |
| `docs/PERFORMANCE.md` | Fold and projection cost; no invented timings |
| `docs/completions/2026-12-16-circle-pad-app.md` | This layer record |
| `docs/roadmap/01-schedule.md`, `docs/roadmap/02-waves.md` | Build-ahead note and integration seam summary |

The app side consumes `CircleEvent`, `CircleCore`, `PadCore`, `PadView` and
`PadLine` from `app.flit.share.protocol.circle`; the CLI consumes the same
protocol core. No Android import enters the protocol module, and no route,
signature primitive or wire shape is added on either surface.

## Public surface

The exact API held by the frozen seams is the 2026-12-15 protocol core, consumed
here and not redefined:

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
  const val MAX_TEXT_CHARS = 4 * 1024
  val PAD_PUT_KEYS: Set<String> = setOf("op", "key", "text")
  val PAD_DEL_KEYS: Set<String> = setOf("op", "key")

  data class PadLine(
    val key: String, val text: String, val lamport: Long = 0L,
    val authorFp: String = "", val id: String = "", val deleted: Boolean = false,
  )
  data class PadView(
    val lines: List<PadLine>,
    val winners: Map<String, PadLine> = emptyMap(),
  )

  fun padPutBody(key: String, text: String): String
  fun padDelBody(key: String): String
  fun validPadBody(body: String): Boolean
  fun view(events: Iterable<CircleEvent>, circleId: String): PadView
  fun merge(views: Iterable<PadView>): PadView
}
```

`PadView.lines` is the lexicographic, tombstone-free live view bounded at
`MAX_LINES`; `winners` also carries tombstones and order keys so `merge` can
re-run the conflict rule. `validPadBody` parses with `WireJson.json` into a
`JsonObject`, requires `obj.keys == PAD_PUT_KEYS` when `op` is `"put"` and
`obj.keys == PAD_DEL_KEYS` when `op` is `"del"`, and requires non-blank JSON
string fields within the key and text caps. A `put` body has a key at most
`MAX_KEY_CHARS` (128) and text at most `MAX_TEXT_CHARS` (4096); a `del` body has
a key at most `MAX_KEY_CHARS`; the whole body stays under
`CircleCore.MAX_BODY_CHARS` (16 KiB). `padPutBody` and `padDelBody` emit exactly
the bodies the gate accepts, so neither surface can mint a body the gate would
refuse.

The integration adds no method to `PadCore`, `CircleCore` or `CircleEvent`. The
app seams are:

```
FlitEngine.padView: StateFlow<PadView>              // rebuilt on every store fold
FlitEngine.circlePadPut(key: String, text: String)
FlitEngine.circlePadDel(key: String)
FlitEngine.refreshCircle()                           // signature includes a pad digest

FlitCircleSync.postPadPut(key: String, text: String)
FlitCircleSync.postPadDel(key: String)
FlitCircleSync.circleBodyValid(type: String, body: String): Boolean   // PAD -> PadCore.validPadBody(body)
FlitCircleSync.post(type: String, body: String)                       // existing signed fan-out, unchanged

object PadUi {                                       // Android-free
  data class PadRow(val key: String, val text: String)
  fun rows(view: PadView): List<PadRow>              // lexicographic by key
  fun keyError(...)                                  // bounds from PadCore.MAX_KEY_CHARS
  fun textError(...)                                 // bounds from PadCore.MAX_TEXT_CHARS
}

@Composable
fun PadCard(view: PadView, onPut: ..., onDel: ..., onClose: ...)
```

`PadUi.keyError` and `PadUi.textError` derive their limits from the `PadCore`
bounds; neither restates them as a second set of caps. `PadUi.rows` maps each
`PadView.lines` entry to a `PadRow(key, text)` with a defensive key sort for hand-built views, so the list is
already lexicographic by key and holds no tombstoned key. `PadCard` renders the
rows as an M3 list with an edit control per row, a delete control per row and a
close control, each with a 48 dp target. The composable holds no store reference
and signs nothing; every action leaves through a callback.

`CircleScreen` gains additive pad parameters with defaults, so call sites
written before the pad surface keep compiling and rendering. `MainActivity`
collects `FlitEngine.padView` and supplies the put, delete and close callbacks.

The CLI seams are:

```
circle pad put --key K --text T
circle pad del --key K
circle pad show
```

`put` and `del` build their bodies with `PadCore.padPutBody` / `PadCore.padDelBody`,
mint the event with the existing `circleEvent` helper as type
`CircleEvent.PAD`, and publish it with `pushCircleEvent`. `show` derives the view
with `PadCore.view(st.events, st.circleId)` and prints one line per entry in
`PadView.lines` as its key and text, in that lexicographic order, and nothing
when the view is empty. No field is added to the CLI's saved circle state.

## Mint gates and inbound admission

Two app-side gates keep a malformed pad body off the wire:

- `FlitCircleSync.circleBodyValid` adds
  `CircleEvent.PAD -> PadCore.validPadBody(body)` beside the other verb branches,
  so a bad key, text or key set is refused before `post()` signs it.
- `FlitCircleSync.postPadPut` and `postPadDel` construct the body only through
  `PadCore.padPutBody` / `PadCore.padDelBody`, then call the existing `post()`,
  which gates the body, calls `CircleCore.create`, verifies, persists the event
  and fans it out through `fanout`. Neither helper opens a socket or writes a
  store directly.

On the receiving side no route or host filter changes are part of this layer.
`PAD` is already in `CircleEvent.TYPES`, so `FlitCircleHost.onSync` admits it
through its existing type and body-cap filters; the member, author, certificate,
id-dedup and `CircleCore.accept` gates are unchanged. `PadCore.view` ignores any
pad event whose body fails `validPadBody`, so a body that slips past the generic
body-cap check cannot change the displayed pad.

`FlitEngine.circlePadPut` and `circlePadDel` launch the publishes on the engine's
IO scope and call `refreshCircle` after each attempt, as the existing Circle
post path does. A carrier failure logs a line and leaves the view as last
folded; it does not fabricate a local line.

## Pad view projection

`FlitEngine.refreshCircle` keeps its existing signature role and adds a digest of
the derived pad view to the signature list it compares against
`lastCircleSignature`. The derivation is `PadCore.view(st.events, st.id)` over
the store snapshot already loaded by that method; no second copy of the pad is
persisted. The digest is appended to the existing signature fields, so:

- a pad edit republishes `_circleState` and `_circleEvents` even when the signed
  log is unchanged, which lets Compose observe the new view;
- an unrelated no-op tick still short-circuits before invalidating Compose;
- `_padView` is set from the same fold, so the flow and the store cannot
  disagree about which events produced the current lines.

`PadUi.rows` is the only presentation mapping between `PadView.lines` and the
list the card renders. The conflict rule stays in `PadCore`: per key, the
operation with the greatest `(lamport, authorFp, id)` wins, a `del` with the
greater key tombstones the line, and an older `del` is inert. An event trimmed
from the bounded log can take its line with it; there is no backfill.

## State and wire ownership

The signed `pad` events in `CircleState.events` remain the only durable pad
state. `PadCore` keeps nothing between calls. The replay id set stays in
`CircleCore`, owned by the receiving route. The derived `PadView` in
`FlitEngine.padView` is process-local and rebuilt on each fold; it is never
written to a file. No per-type reservation is added to
`FlitCircleStore.EVENT_CAP = 500`: pad, receipts and file announcements share
the bounded log, and a busy pad can push an older event out.

The pad reuses `CircleCore.canonical` with the domain tag `FLITCIRCLE1` and the
same seven signed fields, with `sig` and `cert` outside the signed bytes. It
reuses `CircleCore.order`, `dedup`, `evaluate`/`accept` and `fresh`, with
`REPLAY_WINDOW_MS = 6 h`, `CLOCK_SKEW_MS = 5 min`, `MAX_SEEN = 4096`,
`MAX_EVENTS_PER_REQUEST = 1024` and `MAX_REQUEST_BYTES = 8 MiB`, and the
precedence `Invalid → OutsideWindow → Duplicate → Accept`. Verification happens
at the receiving route before the fold, as with every other verb.

## Verification

Fresh rig run, counts read from the JUnit XML:

```
source env.sh && ./gradlew --no-daemon --rerun-tasks \
  :protocol:test :app:testDebugUnitTest :harness:test
```

| Module | Before (last green inventory) | After this layer | Final tree (after 2026-12-21 ideas layer) | Task |
| --- | --- | --- | --- | --- |
| `protocol` | 240 | 283 (unchanged by this layer) | 330 | `:protocol:test` |
| `app` | 281 | 296 | 309 | `:app:testDebugUnitTest` |
| `harness` | 67 | 75 | 75 | `:harness:test` |
| Total | 588 | 654 | 714 | — |

`--rerun-tasks` is required: stale XML has previously reported green against
an older class layout. The "before" figures are the last green inventory in
`TESTING.md` §1 and §2. The 43 protocol cases in the four pad classes recorded
at the 2026-12-15 layer are already included in the 283 shown here; this layer
adds no protocol cases. The 13 cases added after this layer (7 `PadUiTest`
search cases and 6 `FlitBleTest` budget cases) take the final app figure from
296 to 309 and the final tree to 714. All three modules report 0 failures,
0 errors and 0 skipped in the fresh XML.

## Tests

| Class | Cases | What it pins |
| --- | --- | --- |
| `PadUiTest` (new, app) | 8 | `PadUi.rows` maps `PadView.lines` to `PadRow(key, text)` with a defensive key sort for hand-built views and drops nothing from the lexicographic view; `keyError` and `textError` refuse a blank or over-cap key (`MAX_KEY_CHARS = 128`) and a blank or over-cap text (`MAX_TEXT_CHARS = 4096`) using the `PadCore` bounds, with no second cap |
| `FlitEngineTest` (added cases, app) | 7 | `padView` is rebuilt from store events through `PadCore.view` on a fold; the pad digest republishes the flows for a pad-only change and leaves an unrelated no-op tick as a no-op; `circlePadPut` / `circlePadDel` publish signed events through `postPadPut` / `postPadDel` with the exact `PadCore` bodies, and a carrier failure leaves the last folded view in place |
| `PadCliTest` (new, harness) | 8 | `circle pad put` then `circle pad show` prints the key and text in lexicographic order; `circle pad del` tombstones the key and the next show omits it; a blank or over-cap key/text is refused without an event; a replayed id and an older `del` leave the shown view unchanged |

Case counts are the cases added at this layer, read from the JUnit XML of a
clean `--rerun-tasks` run, not from a target figure. Test names are the `@Test`
declarations in each class. In the final tree the full classes carry
`PadUiTest` 15 (8 pad cases here plus 7 search cases added later),
`FlitEngineTest` 30 (7 added here) and `PadCliTest` 8; `FlitBleTest` carries 36
(30 earlier plus 6 budget cases added later). The two-bot pad walk described
for this layer is in-process harness evidence with real endpoints on one box;
it is not a device observation. The 2026-12-15 protocol pad tests are
dependency tests, re-run here and not new work on this date.

## Evidence

| Label | Claimed here | Basis |
| --- | --- | --- |
| CODE | Yes | The app and CLI seams match the API and byte rules recorded above; no new route, header, DTO field, QR parameter, schema field or v2.2 status |
| PURE | Yes | The pad projection and the CLI body derivation are covered by the fresh `--rerun-tasks` XML run: `PadUiTest` 8 and `FlitEngineTest` 7 cases at this layer, 0 failures |
| SIM-HARN | Yes | In-process two-bot pad walks ran in the fresh `--rerun-tasks` XML run: `PadCliTest` 8 cases, 0 failures |
| DEVICE | No | No phone, second phone, radio, carrier or camera observation is made on this date |

The weakest recorded level governs each claim. No device observation is
claimed by this layer. Nothing here records a walk on physical hardware, an
RFCOMM path, a camera decode or a mesh larger than the identities used in the
in-process rig. A pad on this date is test-evidenced only until a separate walk
records what its logs show.

## Limitations

- No device walk is part of this record. The full feature walk belongs to
  2026-12-17; no observation, screenshot or log from a physical device is
  attached here.
- No new route, header, JSON DTO field, QR parameter or persisted store field.
  Pad events use the existing `/circle/join` and `/circle/sync` routes and the
  existing `CircleRequest.events` list.
- No second pad store. The signed log is the only durable pad state; the
  derived `PadView` in the app flow is rebuilt on each fold and never
  persisted.
- `PadCore.view` does not verify signatures or pin certificates. It assumes the
  receiving route has already run verification and the replay guard; a caller
  that feeds it unverified events gets a view of those events, not a statement
  that they are authentic.
- A `del` older than the winning `put` is inert, and an event evicted from the
  bounded log can take its line with it. There is no backfill of evicted lines,
  no rich text, no attachments and no per-line encryption.
- The pad body carries only `op`, `key` and `text`; there is no second time
  field, path, author name, local status string or per-line key.
  `CircleEvent.createdMs` is the event time.
- A busy pad shares the 500-event log with other verbs and can push an older
  event out; there is no per-type reservation. A Circle with three or more
  members remains unverified until it is walked.
- No CLI auto-detection of pad edits: publishing from the box is the explicit
  `circle pad put` / `circle pad del` command. `circle pad show` reads the local
  log and does not pull from a peer.
- Performance figures are not stated; no timings are invented.

## Verification status

Counts for `PadUiTest`, the added `FlitEngineTest` cases and `PadCliTest`, the
app and harness totals and the rig total come from the JUnit XML of a clean
`--rerun-tasks` run. At this layer the app carries 296 cases, the harness 75 and
the protocol 283 unchanged, for 654 in total. After the later 2026-12-21 ideas
layer the final tree carries 330 protocol, 309 app and 75 harness cases — 714
in total with 0 failures, 0 errors and 0 skipped. CODE, PURE and SIM-HARN
evidence are recorded above; no DEVICE evidence exists for the pad on this
date. The route and frame surface is unchanged from the 2026-12-15 circle pad
protocol layer.
