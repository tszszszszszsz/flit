# 2026-12-14 — Delivery receipts parity (Integration) — DRAFT

Layer: 2026-12-14 Integration. The JVM-pure receipt core landed on 2026-12-11 is
wired into the app's Circle delivery path and the CLI's Circle commands. Both
surfaces now mint one signed `receipt` event after a keyed drop lands
hash-exact, and both fold the verified receipts in the bounded signed log into
a confirmed-author set for a drop. A receipt is a statement by one member that
a named drop landed on that member's device; it is not a gossiped delivery
status and not a claim about every member.

The layer adds no route, header, JSON DTO field, QR parameter, persisted store,
schema field, signature primitive or v2.2 status. A receipt travels in
`CircleRequest.events` on the existing `POST /api/flit/v1/circle/sync`, or
inside a courier/QR parcel payload that already carries signed events.

Counts below are placeholders. They are to be read from the JUnit XML of a
fresh `--rerun-tasks` run, as required by `TESTING.md` §3 and §8. No Gradle run
was made for this draft, and no test result is claimed here. No device
observation is claimed on this date: there is no phone, second phone, radio,
carrier or camera walk recorded by this document, and any phone-to-bot receipt
walk stays a reproduction recipe rather than an observation.

## Files in the layer

| File | Content |
| --- | --- |
| `app/src/main/kotlin/app/flit/share/FlitEngine.kt` | `onUploadFinish` mints a receipt after a keyed drop lands hash-exact; `postReceipt` fan-out and confirmation fold over `CircleCore.receiptAuthors`; `refreshCircle` republishes deliveries |
| `app/src/main/kotlin/app/flit/share/FlitCircleSync.kt` | `circleBodyValid` RECEIPT branch delegates to `CircleCore.validReceiptBody`; `postReceipt(dropId, sha, keyId)` beside `postFile` publishes through `post()` |
| `app/src/main/kotlin/app/flit/share/FlitCircleHost.kt` | SYNC filter admits a RECEIPT event whose body passes `CircleCore.validReceiptBody`; author and signature gates unchanged |
| `app/src/main/kotlin/app/flit/share/CircleUi.kt` | `computeFeedData` folds `CircleCore.receiptAuthors` into the file card's confirmed count with `remember` memoization; no new chat row |
| `harness/src/main/kotlin/app/flit/share/harness/Harness.kt` | `circle receipt --sha SHA [--drop-id ID] [--host ip[:port]]`, and confirmed-count output in `circle sync` / `circle file` from the local signed log |
| `app/src/test/kotlin/app/flit/share/FlitCircleSyncTest.kt` | RECEIPT branch of `circleBodyValid` (exact keys accepted, malformed bodies refused) |
| `app/src/test/kotlin/app/flit/share/FlitEngineTest.kt` | Mint gate, confirmation fold, sha correlation and `setDeliveryStatus` promotion |
| `harness/src/test/kotlin/app/flit/share/harness/ReceiptCliTest.kt` | CLI receipt publishing, missing-key refusal and confirmed-count output |
| `docs/TESTING.md` | Three new test-class rows with 3 + 6 + 5 cases in the green 588 |
| `docs/CONTRACTS.md` | Receipt body contract on the app and CLI seams |
| `docs/SECURITY.md` | Receipt as a signed landing assertion; no remote status or revocation |
| `docs/FAILURE-MODES.md` | Malformed body, forged author, stale/duplicate event and keyed-drop refusals |
| `docs/OPERATIONS.md` | Limits of this layer in operational terms |
| `docs/ARCHITECTURE.md` | Integration seam for the receipt core |
| `docs/completions/2026-12-14-receipts-parity.md` | This layer record |
| `docs/roadmap/receipts-parity.md`, `docs/roadmap/01-schedule.md`, `docs/roadmap/02-waves.md` | Finalized integration seams and build-ahead note |

The app side consumes `CircleCore` and `CircleEvent` from
`app.flit.share.protocol`; the CLI consumes the same protocol core. No Android
import enters the protocol module, and no route, signature primitive or wire
shape is added on either surface.

## Public surface

The exact API held by the frozen seams is the 2026-12-11 protocol core,
consumed here and not redefined:

```
object CircleEvent {
  const val RECEIPT = "receipt"
  val TYPES: Set<String> = setOf(
    MESSAGE, FILE, POLL, VOTE, JOIN, LEAVE, KICK, ROLE, REKEY, PARCEL, RECEIPT,
  )                                   // eleven verbs
}

object CircleCore {
  val RECEIPT_BODY_KEYS: Set<String> = setOf("dropId", "sha256", "keyId")
  fun validReceiptBody(body: String): Boolean
  fun receiptAuthors(
    events: Iterable<CircleEvent>,
    dropId: String,
    sha256: String,
    members: Iterable<String>,
  ): Set<String>
}
```

`validReceiptBody` parses with `WireJson.json` into a `JsonObject`, requires
`obj.keys == RECEIPT_BODY_KEYS`, requires every value to be a JSON string
primitive that is non-blank and within `MAX_FIELD_CHARS` (256), and refuses a
blank, oversized, malformed, partial, extra-key or non-string body. It is
structural only: it signs nothing, pins nothing and reads no store.
`receiptAuthors` filters to events whose `type` is `RECEIPT`, whose body passes
`validReceiptBody`, whose `dropId` and `sha256` match the two arguments, whose
`authorFp` is in the normalised `members` set (trim, uppercase), and whose
signature passes `verify`. It returns a deduplicated set of author fingerprints
with at most one entry per author, the winner chosen by `CircleCore.ORDER` so
two peers that received the same receipts in different orders return the same
set. `keyId` is provenance only and is not a selection key.

The integration adds no new method to `CircleCore` or `CircleEvent`. The app
and CLI seams are:

```
FlitCircleSync.circleBodyValid(type: String, body: String): Boolean  // RECEIPT -> CircleCore.validReceiptBody(body)
FlitCircleSync.postReceipt(dropId: String, sha256: String, keyId: String)  // beside postFile(body)
FlitCircleSync.post(type: String, body: String)                      // existing fan-out, unchanged
FlitEngine.onUploadFinish(sessionId: String, fileId: String, file: FileInfo, result: UploadResult)
FlitEngine.refreshCircle()                                           // publishes _circleDeliveries
FlitCircleStore.setDeliveryStatus(state: CircleState, sha: String, fp: String, status: String): CircleState
CircleUi.computeFeedData(events, members, meFp): CircleFeedData       // confirmed fold, remember-memoised at call site
Harness.circleEvent(st, identity, type, body): CircleEvent            // unchanged
Harness.pushCircleEvent(st, identity, ev)                             // unchanged
```

`circle receipt --sha SHA [--drop-id ID] [--host ip[:port]]` resolves the
`file` event by plaintext sha and derives `keyId` from that event's `keyGen`
and `wraps`; it uses the existing `circleEvent` helper to mint and the existing
`pushCircleEvent` to publish. `FlitCircleStore.CircleDelivery` gains no field:
`cipherSha` and `wrappedFor` come from the per-drop key layer, and the
confirmed set is derived from the log. The CLI's `CircleFileDelivery` gains no
field either. `CircleCore.validFileBody`, `canonical`, `create`, `sign`,
`verify`, `order`, `dedup`, `evaluate`/`accept`, `fresh`, `tally`, `kickTally`,
and the `Link` / `CircleRequest` / `CircleResponse` shapes keep their current
signatures and behaviour.

## Event shape and mint gates

The signed receipt body has exactly the closed key set:

```
{"dropId":"<id of the file event>","sha256":"<landed plaintext digest>","keyId":"<dropId:keyGen>"}
```

`dropId` is the `id` of the keyed `file` event being acknowledged, not the
recipient's local delivery id. `sha256` is the plaintext digest carried in the
keyed `file` body, read after the per-drop key layer opens the bytes and checks
that digest; it is not the sealed stream's `cipherSha` that `sendFiles` gates
on. That makes the sender's read side and the receiver's mint side name the
same `sha256`. `keyId` is `DropKeyCore.keyId(dropId, keyGen)` — `"$dropId:$keyGen"`
— from the landed keyed `file` body; no identifier is invented. All three
values are non-blank strings bounded by `MAX_FIELD_CHARS`; the whole body stays
under `MAX_BODY_CHARS` (16 KiB). There is no `landedMs`, path, spool path,
token, key material, file byte count or local status string in the body:
`CircleEvent.createdMs` is the acknowledgement time.

Nothing is minted on a digest mismatch, `UploadResult.Mismatch`,
`UploadResult.Error`, a thrown carrier, a missing `keyGen` or `wraps` field,
`NO_KEY`, or a legacy plaintext drop that has no per-drop key. The CLI refuses
to mint when the lookup fails; it prints
`!! no key id for drop — receipts are for keyed drops` and exits without an
event. No value is fabricated to satisfy the command.

## App gates and fold

Two app-side gates route the new verb through `CircleCore.validReceiptBody`:

- `FlitCircleSync.circleBodyValid` adds
  `CircleEvent.RECEIPT -> CircleCore.validReceiptBody(body)` beside the `FILE`
  branch, so a malformed body is never signed or fanned out.
- `FlitCircleHost.onSync` adds the matching
  `it.type != CircleEvent.RECEIPT || CircleCore.validReceiptBody(it.body)`
  condition beside the `validFileBody` filter. The member-roster gate, the
  `authorFp == requester` gate, the cert-bound `CircleCore.verify` call, the
  id-set dedup and `CircleCore.accept` ordering are unchanged.

`FlitCircleStore.reduce` appends unknown verbs as history, so an accepted
receipt enters `CircleState.events` without a schema change and stays under
`EVENT_CAP = 500`.

The confirmation fold is pure and takes `CircleState` plus a delivery:

1. Collect the `id`s of `file` events whose body `sha256` matches a delivery's
   `sha256`.
2. Call `CircleCore.receiptAuthors(events, dropId, sha, members)` for each
   candidate id and union the returned author fingerprints into one set. The
   core picks one entry per author by `CircleCore.ORDER`, so arrival order
   cannot change the result, and the union keeps one entry per fingerprint
   across re-announcements of the same content.
3. Under one `FlitCircleStore.update(ctx)` call, apply the existing
   `setDeliveryStatus` reducer for each confirmed author so `statuses[author]`
   is `SENT`. A receipt is a stronger statement than a send report, so it may
   override an earlier `REFUSED` or `FAILED` inference. No phantom delivery row
   is created, and `keyId` is read only as signed provenance and never selects
   a receipt.

`FlitEngine.refreshCircle()` publishes the updated deliveries through
`_circleDeliveries` as it already does for status writes. Retry-worker
constants are unchanged: a `30 s` tick and `10 s → 60 s` backoff; Circle sync
stays on its `30 s` gate. `CircleUi.computeFeedData` folds the confirmed-author
set into the file card's `confirmed` count with `remember` memoization at the
call site, and the status line reports sent and confirmed counts as plain
text. No receipt is rendered as a chat row, no emoji is added and no chat-bubble
treatment is introduced. Sender-local delivery statuses stay local-only and
unchanged.

## CLI

`circleUsage()` gains `receipt --sha SHA [--drop-id ID] [--host ip[:port]]`.
The command requires a joined circle (`circleId`, `key`, `host`). With no
`--drop-id`, it finds the `file` event in `st.events` whose body `sha256`
equals `--sha` and takes that event's `id`. It then requires `keyGen` and
`wraps` in that `file` event's body and derives `keyId(dropId, keyGen)`. With
either lookup missing it prints the no-key-id line above and exits without an
event. The minted body is exactly `{dropId,sha256,keyId}`; the event is appended
to `st.events` and sent with `pushCircleEvent`, using the existing `circleEvent`
helper.

`circle sync` and `circle file` print a confirmed count obtained from
`CircleCore.receiptAuthors(st.events, dropId, sha, st.members)` over the same
candidate drop ids. The `status is local-only, never gossiped` wording stays on
the other statuses. `circle post --file`, `circle file` delivery and
`ConsoleDelegate.onUploadFinish` are unchanged; the hint to run `circle
receipt` lives in the reproduction recipe, not in the receive log.

## State and wire ownership

No schema field is added to `CircleDelivery` or `CircleFileDelivery`. Receipt
state is the signed event in the bounded log: at most `EVENT_CAP = 500` events
per store and `MAX_EVENTS_PER_REQUEST = 1024` events per sync delta, under the
`MAX_REQUEST_BYTES = 8 MiB` body cap. Receipts reuse the `FLITCIRCLE1` canonical
stream and the existing replay rules: `REPLAY_WINDOW_MS = 6 h`,
`CLOCK_SKEW_MS = 5 min`, `MAX_SEEN = 4096`, with `verify` before `evaluate`.
A duplicate id or an event outside the window leaves the confirmed count and
the delivery row unchanged. No new persisted receipt store is introduced.

No receipt uses a new route, header, DTO field or QR parameter. The v2.2 route
set and response shapes stay byte-for-byte unchanged. A receipt for an unknown
sha, a departed fingerprint or a non-member author produces no confirmation.
Keyed drops only: a later rekey that changes `keyId` does not invalidate an
earlier receipt for the same `(dropId, sha256)`.

## Verification

Fresh rig run, counts to be recorded from the JUnit XML:

```
source env.sh && ./gradlew --no-daemon --rerun-tasks \
  :protocol:test :app:testDebugUnitTest :harness:test
```

| Module | Before (last green inventory) | After this layer | Task |
| --- | --- | --- | --- |
| `protocol` | 240 | 240 (unchanged) | `:protocol:test` |
| `app` | 272 | 281 | `:app:testDebugUnitTest` |
| `harness` | 62 | 67 | `:harness:test` |
| Total | 574 | 588 | — |

`--rerun-tasks` is required: stale XML has previously reported green against
an older class layout. The "before" figures are the last green inventory in
`TESTING.md` §2; the "after" figures and every per-class count below are
placeholders pending that run. No Gradle run was made for this draft, so no
test result is asserted here.

## Tests

| Class | Cases | What it pins |
| --- | --- | --- |
| `FlitCircleSyncTest` (added cases, app) | 3 | `circleBodyValid` RECEIPT branch accepts the exact `{dropId,sha256,keyId}` key set and refuses a partial, extra, blank, mistyped or over-cap body; the existing verbs keep their bodies and gates |
| `FlitEngineTest` (added cases, app) | 6 | Receipt mint after a keyed landing and no mint on a digest mismatch, `Error`, a carrier throw or a plaintext drop; confirmation fold by sha, one author per fingerprint under reversed order and reversed re-announcements, `keyId` changes leaving the count intact, non-member authors ignored, and `setDeliveryStatus` promoting a row to `SENT` |
| `ReceiptCliTest` (new, harness) | 5 | `circle receipt` resolves the keyed `file` event by sha, derives `keyId`, posts a signed receipt and prints one confirmed author on the next `circle sync`; a missing event or missing key material prints the no-key-id line and exits without an event; a forged receipt is refused at the host gate and a replayed id leaves the count at one |

Case counts come from a clean `--rerun-tasks` JUnit XML run, not from a target
figure. Test names are the `@Test` declarations in each class. The two-bot
keyed-drop confirm walk described in the roadmap is harness evidence under
this layer; no device or extra walk is claimed. The 2026-12-11 protocol
receipt tests are dependency tests, re-run here and not new work on this date.

## Evidence

| Label | Claimed here | Basis |
| --- | --- | --- |
| CODE | Yes | The app and CLI seams match the API and byte rules recorded above; no new route, header, DTO field, QR parameter, schema field or v2.2 status |
| PURE | Yes | The confirmation fold and the CLI body derivation are planned for a fresh `--rerun-tasks` run; counts not yet read from XML |
| SIM-HARN | Yes | In-process two-bot receipt walks are planned for this layer; counts not yet read from XML |
| DEVICE | No | No phone, second phone, radio, carrier or camera observation is made on this date |

The weakest recorded level governs each claim. No device observation is
claimed by this layer. Nothing here records a phone-to-bot receipt landing, a
phone-to-phone RFCOMM path, a three-or-more-member mesh or Wi-Fi Aware. A
receipt on this date is test-evidenced only until a separate walk records what
its logs show.

## Limitations

- No device walk is part of this record. The phone↔bot confirm path remains a
  reproduction recipe; no observation, screenshot or log from a physical device
  is attached here. If a peer-side confirmation cannot be disambiguated in a
  later walk, it must be named as test-evidenced only.
- Receipts apply to keyed drops only. There is no receipt for a plaintext drop
  without `keyGen`/`wraps`, no fabricated `keyId` and no backfill for drops made
  before this layer.
- A receipt proves that a named drop landed hash-exact on the signing member's
  device. It does not prove global delivery to every member and is not a
  remote status, command, revocation or deletion primitive.
- `keyId` is provenance only. A later rekey that changes the key identifier
  does not invalidate an earlier receipt for the same `(dropId, sha256)`.
- No new cap is introduced. The 500-event log cap, the 1024-event per-request
  cap and the bounded `seen` set still apply; receipts add no unbounded state.
- No schema field is added to `CircleDelivery`, no new persisted receipt store
  is introduced, and the `statuses` vocabulary changes only in that a
  confirmed author is mapped to `SENT`.
- The v2.2 surface is unchanged: no new route, header, QR parameter or JSON DTO
  field with a changed default. The existing verbs keep their bodies and
  canonical bytes.
- No `landedMs` or second time field is added; `createdMs` is the
  acknowledgement time within the 5 min skew tolerance.
- No CLI auto-detection of landings: publishing from the box is the explicit
  `circle receipt` command.
- Performance figures are not stated; no timings are invented.

## Verification status

Counts for `FlitCircleSyncTest`, `FlitEngineTest` and `ReceiptCliTest`, the
module totals and the rig total come from a clean `--rerun-tasks` run once the
code and tests land. CODE, PURE and SIM-HARN evidence are recorded above as
planned; no DEVICE evidence exists for receipts on this date. The route and
frame surface is unchanged from the 2026-12-11 protocol layer. Every numeric
field in this draft is a placeholder pending that run, and no Gradle run or
test result is claimed here.
