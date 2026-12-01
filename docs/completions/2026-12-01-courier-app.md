# 2026-12-01 — Courier app (App)

Layer: 2026-12-01 App. The work folds sealed courier parcels into the existing
Circle sync worker, persists a bounded carrier bag in
`FlitCircleStore.CircleState`, publishes process-local handoff outcomes from
`FlitEngine`, and renders a carry line on the Circle file card from a typed,
Android-free projection. The only new wire kind is the `PARCEL` verb with a
closed body `{toFp, envelope}`. Envelope sealing, verification, opening and the
carry reducers stay in `protocol/` and are called, not redefined. The code is
built ahead of the scheduled commit date; the commit stays on 2026-12-01.

Counts below come from a green `--rerun-tasks` run of the CI-standard rig. No
device evidence is claimed for the courier UI at this layer: the three-node
baseline walk is device evidence for a 3-identity Circle, while outbound
phone→host fan-out remains test-evidenced only.

## Files in the layer

| File | Content |
| --- | --- |
| `app/src/main/kotlin/app/flit/share/FlitCircleStore.kt` | Additive `CircleState.courierBag` field with a default; tolerant codec; bag writes through `update` under `ioLock` |
| `app/src/main/kotlin/app/flit/share/FlitCircleSync.kt` | `postParcel`; `PARCEL` case in `circleBodyValid`; bag fold through `fresh()` → `persistEvents()` → `FlitCircleStore.upsert`; worker `select` and handoff path; `ack` on an OK answer |
| `app/src/main/kotlin/app/flit/share/FlitEngine.kt` | `courierHandoffs` StateFlow; `circleCourierDrop`; 30 s expiry sweep; extra term in the `refreshCircle` content-diff signature |
| `app/src/main/kotlin/app/flit/share/MainActivity.kt` | Collect `courierHandoffs`; pass the typed handoff map at the new `CircleScreen` argument |
| `app/src/main/kotlin/app/flit/share/CircleUi.kt` | `parcel` filter in `computeFeedData`; additive `carryLine` on `FileCard`; additive `courierHandoffs` on `CircleScreen` |
| `app/src/main/kotlin/app/flit/share/CourierCardUi.kt` (new) | Android-free `CourierHandoff`, `CarryOutcome`, `OpenAffordance`, `carryOutcome`, `courierLine`, `openAffordance`, `aliasOf` |
| `app/src/test/kotlin/app/flit/share/CourierCardUiTest.kt` (new) | Pure outcome-to-copy mapping, alias bounds, open affordance, hash case folding |
| `app/src/test/kotlin/app/flit/share/FlitCircleStoreTest.kt` (added cases only) | `courierBag` absent on old rows, encode/decode round-trip, tolerant corrupt decode, bag write leaving log/roster/deliveries intact |
| `app/src/test/kotlin/app/flit/share/FlitCircleSyncTest.kt` (added cases only) | `PARCEL` body gate, verify-before-fold, two-key `postParcel` body, `select` fanout with id dedup and cap, `ack` on OK, `resetForTest` |
| `docs/TESTING.md` | Updated test counts (396 total) |
| `docs/FAILURE-MODES.md` | App courier rows, CODE and PURE only |
| `docs/SECURITY.md` | Local-only handoff map, no gossip, no envelope text in the UI |
| `docs/CONTRACTS.md` | `postParcel` and the `CircleState.courierBag` field |
| `docs/ARCHITECTURE.md` | App worker and UI projection seam |
| `docs/OPERATIONS.md` | User-visible carry lines and their limits |
| `docs/completions/2026-12-01-courier-app.md` | Completion note for the layer |

`CourierCardUi.kt` carries no Android import and follows the `SheetUi.kt`
pattern, so `app/src/test` can import it without Robolectric. No envelope,
content key, spool path or parcel body text enters that file.

## Public surface

`FlitCircleStore.CircleState` adds one additive field; existing named-argument
constructors and older rows keep working.

```
courierBag: String = ""   // CourierCarryCore.encode(CarryState)
```

`encode`, `decode` and `update` remain atomic under `ioLock`. A blank string is
an empty bag; a corrupt row decodes as an empty bag with a log line and no
exception to the caller.

`FlitCircleSync` adds, without changing existing parameters or routes:

```
postParcel(toFp, envelopeB64)
```

`circleBodyValid` gains one `PARCEL` case that delegates to the protocol body
gate, accepting only the closed key set `{toFp, envelope}`. `fresh()` applies
that gate before `CircleCore.verify`; accepted parcels are folded through
`persistEvents()` → `FlitCircleStore.upsert`. The worker path appends selected
signed events to `CircleRequest.events`, deduplicated by id and capped at
`CircleCore.MAX_EVENTS_PER_REQUEST`, and calls `ack` only on an OK answer.

`FlitEngine` adds process-local state and one drop entry point:

```
val courierHandoffs: StateFlow<Map<String, CourierCardUi.CourierHandoff>>   // sha256 → waiting | handed | failed
fun circleCourierDrop(events: List<CircleEvent>, toFp: String)
```

Without a recipient certificate the drop is refused and the source events stay
in the sender's own log. A 30 s sweep applies `CourierCarryCore.expire` and
publishes outcomes into the map. `refreshCircle()` includes the map in its content-diff
signature so a new outcome republishes without a log change. The map is never
signed, persisted, gossiped or placed in a `CircleRequest`.

`CourierCardUi.kt` (new, Android-free):

```
enum class CarryOutcome { WAITING, HANDED, FAILED }
enum class OpenAffordance { RESOLVING, LANDED, ABSENT }

data class CourierHandoff(
    val sha256: String,
    val name: String,
    val size: Long,
    val toFp: String,
    val courierFp: String,
    val fromFp: String,
    val outcome: String,
)

fun carryOutcome(raw: String?): CarryOutcome
fun courierLine(handoff: CourierHandoff?, meFp: String, members: List<FlitCircleStore.Member>): String?
fun openAffordance(sha: String, landed: Set<String>, opening: String?): OpenAffordance
fun aliasOf(fp: String, members: List<FlitCircleStore.Member>): String
```

Hashes and fingerprints are compared after `trim().lowercase(Locale.ROOT)`.
`aliasOf` bounds a label at `FlitCircleStore.MAX_ALIAS_CHARS = 64` and falls back
to `fp.take(8)`. A blank or unknown outcome reads as `WAITING`.

`CircleUi.kt` adds one parameter at each seam; all existing parameters and the
three-state Open affordance are unchanged.

```
fun CircleScreen(..., courierHandoffs: Map<String, CourierCardUi.CourierHandoff> = emptyMap())
```

`FileCard` takes `carryLine: String? = null`, rendered above the status line.
`computeFeedData` filters `parcel` events before body parsing and tallies; no
`CircleCard.PARCEL` value and no `parcel` branch are added to `parseBody`.
Decrypted `file` events keep rendering through the existing `"file"` branch.

`MainActivity.kt` collects `FlitEngine.courierHandoffs` and passes the projected
handoff map at the new `CircleScreen` argument. The existing `landedSha256` and
`openingSha256` arguments are untouched.

## Constants

| Constant | Value | Basis |
| --- | --- | --- |
| `COURIER_TICK_MS` | `30_000L` | App-owned expiry sweep; matches `CIRCLE_SYNC_MIN_INTERVAL_MS` |
| `HANDOFF_WAITING` / `HANDOFF_HANDED` / `HANDOFF_FAILED` | `"waiting"` / `"handed"` / `"failed"` | App-owned local outcomes; blank or unknown reads as waiting |
| `CIRCLE_PEER_DIALS` | `4` | Reused, not redefined |
| `CIRCLE_BACKOFF_BASE_MS` / `CIRCLE_BACKOFF_MAX_MS` | `5_000L` / `120_000L` | Reused, not redefined |
| `MAX_ALIAS_CHARS` | `64` | Reused from `FlitCircleStore` for `aliasOf` |
| `PARCEL` / `PARCEL_BODY_KEYS` | `"parcel"` / `{"toFp","envelope"}` | Protocol-owned, read only |
| `MAX_ENVELOPE_CHARS` | `12 * 1024` | Protocol-owned, read only |
| `MAX_PARCELS` / `MAX_SEEN` / `MAX_PARCEL_EVENTS` | `256` / `4096` / `1024` | Protocol-owned, read only |
| `MAX_PARCEL_TTL_MS` / `CLOCK_SKEW_MS` | `24 h` / `5 min` | Protocol-owned, read only |
| `ALG` | `"AESGCM+RSAOAEP256"` | Protocol-owned, read only |

No protocol constant is redefined in the app layer. Expiry comes only from the
envelope's own `expiresMs`; the app does not extend a TTL.

## State ownership

| State | Lives in | Persisted | Gossiped |
| --- | --- | --- | --- |
| Signed `parcel` events, body `{toFp,envelope}` | `FlitCircleStore.CircleState.events` | Yes | Yes, via `/circle/sync` |
| Encoded carrier bag | `FlitCircleStore.CircleState.courierBag` | Yes, on the carrier | Envelope only, opaque |
| Decrypted signed `file` events | `FlitCircleStore.CircleState.events` | Yes | Only the signed event |
| SHA-256 → outcome | `FlitEngine.courierHandoffs` | No | No |
| SHA-256 → `CourierHandoff` | `CircleScreen` parameter | No | No |
| Delivery counts | `FlitCircleStore.CircleDelivery.statuses` | Yes | No |
| Landed SHA-256 set | `FlitEngine._circleLandedShas`, rebuilt at boot | No | No |
| `openingSha256` | `MainActivity` plain `remember` | No | No |

A leave clears the signed log and the local handoff map; a rekey does not clear
the map, because a parcel copy is addressed to a recipient identity, not to the
group key.

## Carry copy

One line, sentence case, no emoji and no claim of delivery. The courier-local
outcome is read only when `meFp == courierFp`; on every other device it is
ignored, because it is never gossiped.

| View | Condition | Line |
| --- | --- | --- |
| Courier (`meFp == courierFp`) | `WAITING` | `Carrying for <to> · waiting for a handoff` |
| Courier | `FAILED` | `Carrying for <to> · handoff will retry` |
| Courier | `HANDED` | `Handed off to <to>` |
| Recipient (`meFp == toFp`) | any | `A courier carried a copy for you` |
| Other member | any | `<courier> is carrying a copy for <to>` |

`<to>` and `<courier>` resolve through `aliasOf`. No carry line is drawn for a
card with no handoff entry. A carry line never claims bytes landed: only
`openAffordance(...) == LANDED` offers `Open file`; `RESOLVING` gives the
disabled `Opening…` button and `ABSENT` gives
`Not here yet · no local copy to open`. Delivery counts come only from
`CircleDelivery.statuses`; a handoff never increments a sent count.

## Verification

Rig command, the CI-standard task set from
[TESTING.md](../TESTING.md):

```bash
source env.sh && ./gradlew --no-daemon --rerun-tasks \
  :protocol:test :app:testDebugUnitTest :harness:test \
  :app:assembleDebug :app:assembleRelease \
  :harness:installDist :harness:distZip
```

Result: green. Module counts from the `--rerun-tasks` JUnit XML:

| Module | Baseline | With this layer | Task |
| --- | --- | --- | --- |
| `protocol` | 136 | 136 | `:protocol:test` |
| `app` | 210 | 228 | `:app:testDebugUnitTest` |
| `harness` | 32 | 32 | `:harness:test` |
| Total | 378 | 396 | — |

The 302-test inventory (82 + 210 + 10) is a historical snapshot. The 378
baseline (136 + 210 + 32) stands after the 2026-11-27 and 2026-11-30 layers;
this layer adds 18 app cases for a 396 total.

## Tests

| Class | Cases | What it pins |
| --- | --- | --- |
| `CourierCardUiTest` (new) | 11 | `carryOutcome` exact and default mapping; all five copy rows; a `HANDED` outcome on a non-courier device yielding the third-member line; `aliasOf` bound and fallback; no envelope or body text in copy; null and blank-SHA lines absent; all three `openAffordance` states, case folding and landed precedence |
| `FlitCircleStoreTest` (added cases) | 4 | `courierBag` absent on old rows, `encode`/`decode` round-trip, tolerant decode of a corrupt row, bag write leaving events, roster and deliveries intact |
| `FlitCircleSyncTest` (added cases) | 3 | `PARCEL` in `circleBodyValid`, `fresh` folding a verified parcel and refusing an unverified or extra-key one, two-key `postParcel` body, `select` fanout with id dedup and 1024 cap, `ack` only on OK and bag retained on a throw, `resetForTest` clearing new hooks |

Test names follow the `@Test` declarations in each class. Two SIM-HARN outcomes
are consumed from the 2026-11-30 carrier — an envelope that cannot be opened
yields no handoff entry, and a carrier throw keeps the sealed copy. No duplicate
harness bot is added.

## Evidence

| Label | Claimed here | Basis |
| --- | --- | --- |
| CODE | Yes | Additive seams in `FlitCircleStore`, `FlitCircleSync`, `FlitEngine`, `CircleUi`, `MainActivity`; the new Android-free `CourierCardUi.kt`; unchanged route, DTO and signed canonical bytes |
| PURE | Yes | Green `--rerun-tasks` run: `CourierCardUiTest` (11), `FlitCircleStoreTest` (+4), `FlitCircleSyncTest` (+3); 228 app tests total |
| SIM-HARN | Yes, consumed | The two 2026-11-30 carrier outcomes above |
| DEVICE | No | This is a UI layer; no device claim. The three-node baseline walk is device evidence for a 3-identity Circle, while outbound phone→host fan-out remains test-evidenced only |

No radio, phone, camera or QR observation is claimed for the courier UI on this
date. Carry lines and handoff copy are test-evidenced only; a later device walk
would have to record a carry line on the courier and `Landed here` on the
recipient after bytes arrive.

## Limitations

- No device walk for the courier UI, no phone-to-phone handoff and no live
  multi-member mesh at this layer. No delivery receipt or decryption proof
  beyond the harness.
- The handoff map is process-local and is lost on restart. A carrier that cannot
  open an envelope has no SHA-256 to key a row with, publishes nothing for that
  parcel and the card reads `waiting`.
- A carry line never stands for delivery. The Open affordance stays gated by
  the landed-SHA set, and `parcel` rows are filtered out of the rendered feed.
- The app calls the protocol seam for envelope bytes and carry decisions; it
  does not parse a `parcel` body, read envelope text, dial a new route or add a
  new `LinkProvider`.
- QR parcel frames, per-drop content keys, delivery receipts and Circle pad are
  dated later. The v2.2 wire and the closed `file` event body `{name,size,sha256}`
  are unchanged.

## Verification status

All counts are from a green `--rerun-tasks` run of the CI-standard rig after all
sixteen files in the layer were present: 396 total (136 protocol + 228 app +
32 harness). No placeholder counts remain. The DEVICE row stays unclaimed for
the courier UI on this date.
