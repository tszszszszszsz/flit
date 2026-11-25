# Courier file-card states (app UI)

File-card presentation for Circle file drops delivered through a courier, driven by a typed handoff projection from decrypted events rather than by the raw parcel wire body.

## Date and layer

2026-12-01, App. Commit scope from the date table: courier sync plus file-card states, tests. This file owns the file-card states. `courier-sync.md` owns the carrier wiring, envelope open, event fold and handoff projection writes on the same day.

## Depends on

- 2026-11-25 — Planning: the document set and the dated order.
- 2026-11-26 — Device: the three-node baseline walk. It records that the M3 feed and the file card already work with the phone and two CLI identities before a carrier is added.
- 2026-11-27 — Protocol: the courier envelope and carry core. The `parcel` event body is exactly `{"toFp","envelope"}`, gated by `parcelOf`, with `MAX_ENVELOPE_CHARS = 12 * 1024`; the envelope carries signed Circle events, not card fields. This file consumes none of those bytes directly.
- 2026-11-30 — CLI: the courier carrier. It supplies the box-side carry loop used to generate handoffs during development.
- Same-day boundary: `courier-sync.md` opens the envelope at the addressee, folds the decrypted events through `FlitCircleStore.merge` and publishes `FlitEngine.courierHandoffs`. The screen declares its `courierHandoffs` parameter with an empty-map default, so the feed compiles and renders when that flow is not yet supplied.

## Scope

Present courier delivery on the existing Circle file card without changing what a delivery count means or what an Open action proves. Three additions to the current feed:

1. Opaque `parcel` events are filtered out of the rendered feed. Their decrypted `file` events, folded into the signed log by the sync layer, render as ordinary file cards.
2. A carry line on `FileCard`, read from the typed handoff map supplied by `FlitEngine.courierHandoffs`. The UI never parses `parcel` bodies or envelope text.
3. Extraction of the existing three-state Open affordance into an Android-free helper so the states can be pinned by JVM tests.

Nothing here signs, decrypts, encrypts, dials, stores bytes or opens a file. The card keeps reading `circleEvents`, `circleState.deliveries`, `circleLandedShas` and the transient `openingSha256` from `FlitEngine` through `MainActivity`, plus the new typed handoff map.

## Design

**Contract consumed from 2026-11-27.** `parcel` is the tenth Circle verb. Its body is exactly `{"toFp":"<UPPERCASE fingerprint>","envelope":"<Base64 envelope bytes>"}` with `PARCEL_BODY_KEYS = {"toFp","envelope"}` and `MAX_ENVELOPE_CHARS = 12 * 1024`. `parcelOf` is the gate: a row is a parcel only when the type, `CircleCore.validShape`, the closed body key set, a non-blank `cert` and a derived expiry all hold. The envelope carries `CourierParcel(events: List<CircleEvent>)` encrypted to the addressee; the courier holds ciphertext, IV and wrapped key and cannot read the events. All of that stays in `protocol/` and in the sync layer.

The UI does not read `toFp` or `envelope` from a `parcel` body, does not call `parcelOf`, and does not hold envelope text in any state. Its only courier input is the typed projection described below, built by `courier-sync.md` from decrypted events that passed `CircleCore.verify` and from the courier's local handoff outcome.

**Typed projection — new file.** `app/src/main/kotlin/app/flit/share/CourierCardUi.kt`, Android-free, matching the `SheetUi.kt` pattern so `app/src/test` can import it without Robolectric:

```
enum class CarryOutcome { WAITING, HANDED, FAILED }
enum class OpenAffordance { RESOLVING, LANDED, ABSENT }

data class CourierHandoff(
    val sha256: String,      // key into the map; matches the file event's sha256
    val name: String,
    val size: Long,
    val toFp: String,        // addressee, from the opened envelope
    val courierFp: String,   // parcel event authorFp
    val fromFp: String,      // envelope fromFp
    val outcome: String,     // courier-local: waiting | handed | failed
)

fun carryOutcome(raw: String?): CarryOutcome
fun courierLine(handoff: CourierHandoff?, meFp: String, members: List<FlitCircleStore.Member>): String?
fun openAffordance(sha: String, landed: Set<String>, opening: String?): OpenAffordance
fun aliasOf(fp: String, members: List<FlitCircleStore.Member>): String
```

Constants: `HANDOFF_WAITING = "waiting"`, `HANDOFF_HANDED = "handed"`, `HANDOFF_FAILED = "failed"`; blank or unknown values read as `WAITING`. `aliasOf` bounds a label at the existing `FlitCircleStore.MAX_ALIAS_CHARS = 64` and falls back to `fp.take(8)`, the same rule `FeedRow` uses for authors today. No new color, corner, spring or spacing token is introduced: the card keeps `FlitCorner.large`, `surfaceContainerLow`, `typ.labelSmall`, and the `FlitSpring.spatial()` / `FlitSpring.effects()` motion already used by the poll composer.

`openAffordance` extracts the current `FileCard` logic without changing it: a non-blank `opening` matching the SHA-256 gives `RESOLVING`, a SHA-256 present in the normalised landed set gives `LANDED`, everything else gives `ABSENT`. Fingerprints and hashes are compared after `trim().lowercase(Locale.ROOT)`, the same normalisation `CircleScreen` applies at `CircleUi.kt:151-156` today.

**Compose seams — edited file.** `app/src/main/kotlin/app/flit/share/CircleUi.kt`:

- `computeFeedData(events, members, meFp)` filters out events whose type is `parcel` before parsing bodies and tallies. No `CircleCard.PARCEL` value and no `parcel` branch are added to `parseBody`; an envelope is transport metadata, not a chat row. Decrypted `file` events are already in `st.events` through `FlitCircleStore.merge` and keep rendering through the existing `"file"` branch with its exact `{name,size,sha256}` body.
- `FileCard` takes one additive private parameter, `carryLine: String? = null`, rendered above the existing status line. `FeedRow` passes `carryLine = courierLine(handoffs[body.sha256], meFp, members)`. The current local-count line and the three Open states stay as they are.
- `CircleScreen` gains one additive parameter, `courierHandoffs: Map<String, CourierCardUi.CourierHandoff> = emptyMap()`. Old callers keep compiling. `MainActivity.kt:1383-1433` passes `FlitEngine.courierHandoffs.collectAsState()` at the new argument; the existing `landedSha256` and `openingSha256` arguments are untouched.
- `FlitEngine.refreshCircle()` includes the handoff map in the content-diff signature at `FlitEngine.kt:387-395`. Parcel events need no new signature term because they are already part of `events`.

**Copy.** One line, sentence case, no emoji, no claim of delivery. The courier-local outcome is used only on the courier's own device; on every other device it is ignored because it is never gossiped.

| View | Condition | Line |
| --- | --- | --- |
| Courier (`meFp == courierFp`) | `WAITING` | `Carrying for <to> · waiting for a handoff` |
| Courier | `FAILED` | `Carrying for <to> · handoff will retry` |
| Courier | `HANDED` | `Handed off to <to>` |
| Recipient (`meFp == toFp`) | any | `A courier carried a copy for you` |
| Other member | any | `<courier> is carrying a copy for <to>` |

`<to>` and `<courier>` resolve through `aliasOf`. No carry line is drawn for a card with no handoff entry; that card keeps the sender's `local · N sent · M pending · …` line, or the neutral `file drop · open to view if it arrived` when no delivery record exists.

**State ownership.**

| State | Lives in | Persisted | Gossiped | Owner |
| --- | --- | --- | --- | --- |
| Signed `parcel` events, body `{toFp,envelope}` | `FlitCircleStore.CircleState.events`, cap 500 | Yes | Yes, via `/circle/sync` | Sender / courier |
| Envelope bytes and wrapped key | `CourierEnvelope` / `courierBag` on `CircleState` | Yes on the carrier | Envelope only, opaque | Carrier |
| Decrypted signed `file` events | `FlitCircleStore.CircleState.events`, cap 500 | Yes | Only the signed event | Sender |
| SHA-256 → `CourierHandoff` | `FlitEngine.courierHandoffs` StateFlow | No | No | Built by sync from decrypted events; read by the UI |
| Delivery counts | `CircleDelivery.statuses`, keyed by SHA-256 | Yes | No | Sender device |
| Landed SHA-256 set | `FlitEngine._circleLandedShas`, rebuilt at boot | No | No | Recipient device |
| `openingSha256` | `MainActivity` plain `remember` | No | No | Activity |

A leave clears the local handoff map and the signed log; a rekey does not clear it, because parcel copies are addressed to a recipient identity, not to the group key. No spool path, sealed blob, envelope string, content key or recipient key is passed into `CourierCardUi`.

## Invariants

1. The UI never parses a `parcel` body and never reads `envelope` text. `parcelOf`, `MAX_ENVELOPE_CHARS = 12 * 1024` and envelope decryption stay in `protocol/` and in the sync layer.
2. `parcel` rows are filtered from the feed. No "Unsupported circle event · parcel" row and no raw Base64 appears in the conversation.
3. A carry line never claims bytes landed. Only `openAffordance(...) == LANDED` produces `Landed here` plus `Open file`; `RESOLVING` produces the disabled `Opening…` button and `ABSENT` produces `Not here yet · no local copy to open`.
4. Delivery counts come only from `CircleDelivery.statuses`. Carry lines come only from `FlitEngine.courierHandoffs`. Neither count is derived from the other, and a handoff never increments a sent count.
5. The courier-local outcome is read only when `meFp == courierFp`. A recipient or third member never sees `Handed off to …` from another device's outcome.
6. `courierLine` uses `toFp`, `courierFp` and `outcome` only. It never copies `name`, `size`, `fromFp` or any envelope string into the rendered line.
7. Every added public parameter has a default. A `CircleScreen(...)` call written before 2026-12-01 compiles and renders unchanged; `courierHandoffs` is the only new argument at the production call site.
8. `parcel` is additive to the Circle verb set. It adds no route, changes no `ServerCore` status code and leaves the v2.2 wire untouched.
9. The decrypted `file` body stays exactly `{name,size,sha256}`. Handoff outcomes, spool paths, courier fingerprints and envelope fields never enter it.
10. One SHA-256 yields at most one carry line, because the handoff map is keyed by SHA-256. Alias text is bounded at `MAX_ALIAS_CHARS = 64`; the card keeps `maxLines = 2` on the name and one line on the carry status.
11. Replay, signature, cert binding and parcel gate are decided upstream by `parcelOf`, `CourierEnvelope.verify`/`open` and `CircleCore.verify`/`accept`. The UI keeps no seen set and performs no verification.
12. An unknown or blank outcome reads as `WAITING`. Only the exact `HANDOFF_HANDED` string permits `Handed off to <to>`; a throw or a refusal can never produce that copy.

## Failure modes

| Condition | Conclusion | Keeps bytes | Drops | Evidence |
| --- | --- | --- | --- | --- |
| `parcelOf` refuses a body with extra keys or an oversized `envelope` (`MAX_ENVELOPE_CHARS = 12 * 1024`) | No parcel enters the bag; the feed has no row for it | Sender's own events stay in its log | The malformed incoming event | PURE at 2026-11-27; consumed by the UI as absence of a handoff |
| Envelope open fails at the addressee (expired, wrong `toFp`, bad wrapped key) | No decrypted events folded; no handoff entry; no carry line | Held envelope stays in the carrier bag | Nothing | SIM-HAR from 2026-11-30; no UI claim |
| Decrypted event fails `CircleCore.verify` or body gate | Event skipped at fold; file card not created | Envelope and the rest of the parcel | That event | PURE fold gate at 2026-12-01; consumed here |
| Handoff entry has a blank SHA-256 | `courierLine` returns null; no carry line is drawn | Handoff map entry ignored; bytes untouched | Nothing | PURE (`CourierCardUiTest`) |
| Direct sender send to an absent member throws | Recipient's status stays `pending`; a courier may hold a sealed copy | Sender spool kept while any recipient is owed | No file bytes dropped | SIM-HAR from 2026-11-30; DEVICE recorded on 2026-12-02 |
| Courier handoff throws (no route, dead carrier) | Outcome `failed`; line reads `handoff will retry` | Sealed copy stays with the courier | Nothing | PURE (outcome-to-copy mapping) + SIM-HAR (injected carrier throw) |
| Courier handoff answered with a deliberate refusal | Outcome `failed`; no `Handed off to …` copy | Sealed copy stays held for a later encounter | Nothing | SIM-HAR |
| Bytes land before the handoff entry is published | Open affordance comes from `circleLandedShas`; carry line absent until sync publishes it | Landed copy stays in History | Nothing | PURE (affordance precedence) |
| Two handoff entries arrive for one SHA-256 | Map keyed by SHA-256 keeps one entry; one line | No bytes change | Duplicate projection entry | PURE |
| `courierHandoffs` absent (old caller or flow not yet published) | Empty map; no carry lines | Parcel bytes untouched | Nothing | CODE (default argument compiles) |
| Circle left | Log and handoff map cleared; feed returns to the pending state | The device deletes its own sealed copies as part of leave | Local carry state dropped; History files not | PURE (state reset) |

## Tests

**Pure, first — new `app/src/test/kotlin/app/flit/share/CourierCardUiTest.kt`** (JVM, no Android imports, no Robolectric, `kotlin.test` only; it never constructs a `parcel` event or an envelope):

- `carryOutcome` maps `waiting`, `handed` and `failed` exactly, and reads blank, mixed-case and unknown values as `WAITING`.
- `courierLine` returns each of the five exact copy rows: courier waiting, courier failed, courier handed, recipient, and third member.
- A `HANDED` outcome on a non-courier device produces the generic third-member line, not `Handed off to …`; the courier's local outcome is never used for another device.
- `aliasOf` bounds a 200-character alias to `MAX_ALIAS_CHARS = 64` and falls back to `fp.take(8)` for an unknown fingerprint.
- A `CourierHandoff` with a long Base64-like `name` and `fromFp` produces a line containing neither; no envelope or body text reaches the copy.
- `courierLine(null, ...)` and a handoff with a blank SHA-256 yield no carry line.
- `openAffordance` covers all three states, case-folds hashes, and gives `ABSENT` for a blank or unmatched SHA-256.
- Landed precedence: a landed SHA-256 keeps `LANDED` even while a carry line exists; an absent SHA-256 never offers Open.
- A compile check that an existing-style `CircleScreen(...)` call without `courierHandoffs` still resolves (CODE, not a runtime test).

**Harness / sim.** No new harness class is owned by this file. The wire proof for the `{toFp,envelope}` body, `parcelOf`, envelope open and carrier throw lives in the 2026-11-27 and 2026-11-30 specs; the card consumes the typed map those flows produce. Two SIM-HAR outcomes are consumed here: an expired or undecryptable envelope yields no handoff entry, and a carrier throw keeps the sealed copy. Adding a duplicate UI-specific harness bot would repeat the same production endpoints and is not done.

**Device walk.** None on 2026-12-01. The courier three-node walk is dated 2026-12-02 and owned by `courier-device-walk.md`. It supplies the DEVICE observation that a carry line appears on the courier and that the recipient's card shows `Landed here` only after bytes actually arrive. Until that walk runs, every carry observation is test-evidenced only.

## Evidence for this day

Recorded in `docs/completions/2026-12-01.md` when the work is done:

- PURE: the `CourierCardUiTest` case count and the app module count read from the JUnit XML after `--rerun-tasks`; the exact set of states and copy rows pinned by those tests.
- CODE: the new `CourierCardUi.kt` with `CourierHandoff`, `CarryOutcome` and `openAffordance`; the `parcel` filter in `computeFeedData`; the additive `carryLine` seam in `CircleUi.kt`; the additive `courierHandoffs` argument and the `MainActivity` call-site change.
- SIM-HAR: the two consumed outcomes — an envelope that cannot be opened yields no handoff entry, and a carrier throw keeps the sealed copy — named with their source test.
- Limitation: no DEVICE evidence on this date. Carry lines and handoff copy are test-evidenced only until the 2026-12-02 walk. QR parcel frames (2026-12-07), per-drop content keys (2026-12-09), delivery receipts (2026-12-11) and Circle pad (2026-12-15/16) are not part of this day's evidence.

## Out of scope

- QR parcel frame rendering and camera decode, dated 2026-12-07.
- Per-drop content keys, dated 2026-12-09/10, and delivery receipt events, dated 2026-12-11/14. No receipt state is shown on a courier card here.
- Circle pad cards, dated 2026-12-15/16.
- The 2026-12-02 device walk, envelope crypto, `parcelOf`, `CourierCarryCore`, any radio work, and changes to `Link`, `TlsIdentity` or the v2.2 wire.
- Parsing the `parcel` body, reading envelope text in the UI, or adding a `parcel` card to the feed.
- Changing the `file` event body, the meaning of `CircleDelivery.statuses`, or the three-state Open affordance.
- Auto-opening a landed file, cryptographic verification in the UI, new motion or color tokens, and any claim that a carry line means delivery.

## Commit

2026-12-01 Add courier file-card states and pure card projection tests (app)
