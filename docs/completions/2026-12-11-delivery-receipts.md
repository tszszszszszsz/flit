# 2026-12-11 — Delivery receipts (Protocol) — DRAFT

Layer: 2026-12-11 Protocol. The work is a JVM-pure delivery acknowledgement
core in `app.flit.share.protocol.circle`. A receipt is one signed `CircleEvent`
in which a member acknowledges that a named file drop landed on that member's
device. This layer adds the eleventh event verb, a type-specific body gate and
a pure roll-up of the current members who signed a well-formed receipt for one
drop. It adds no route, header, DTO field, frame, crypto primitive, persisted
store or v2.2 status. The app, CLI, harness and device wiring belong to
2026-12-14.

The layer is built ahead of its scheduled day; the code is written now and the
commit stays on 2026-12-11.

Counts below are placeholders. They are to be read from the JUnit XML of a fresh
`--rerun-tasks` run, as required by `TESTING.md` §3 and §8. No Gradle run was
made for this draft, and no test result is claimed here. No device observation
is claimed on this date; receipts are test-evidenced only.

## Files in the layer

| File | Content |
| --- | --- |
| `protocol/src/main/kotlin/app/flit/share/protocol/circle/CircleEvent.kt` | `RECEIPT = "receipt"` constant and its addition to `CircleEvent.TYPES` as the eleventh verb |
| `protocol/src/main/kotlin/app/flit/share/protocol/circle/CircleCore.kt` | `RECEIPT_BODY_KEYS`, the `validReceiptBody` structural gate and the `receiptAuthors` pure roll-up; canonical bytes unchanged |
| `protocol/src/test/kotlin/app/flit/share/protocol/ReceiptBodyTest.kt` | Body key set, field bounds and verb-tests |
| `protocol/src/test/kotlin/app/flit/share/protocol/ReceiptTallyTest.kt` | Signature-filtered, member-gated, one-per-author roll-up tests |
| `protocol/src/test/kotlin/app/flit/share/protocol/ReceiptCanonicalTest.kt` | Canonical vectors and replay-boundary tests |
| `docs/TESTING.md` | Three new protocol classes with counts from the protocol run |
| `docs/CONTRACTS.md` | Receipt body contract and the absence of a second time field |
| `docs/SECURITY.md` | Receipt proves local landing, not global delivery; signature and pin rules |
| `docs/FAILURE-MODES.md` | Malformed body, forged author, stale or duplicate event and mismatched drop refusals |
| `docs/OPERATIONS.md` | Limits of this layer in operational terms |
| `docs/ARCHITECTURE.md` | Receipt seam in the protocol layer |
| `docs/PERFORMANCE.md` | Fold cost and bounded state; no invented timings |
| `docs/completions/2026-12-11-delivery-receipts.md` | This layer record |
| `docs/roadmap/delivery-receipts.md`, `docs/roadmap/01-schedule.md`, `docs/roadmap/02-waves.md` | Finalized protocol seams and build-ahead note |
| `README.md` | Receipt capability bullet, with no device claim |

The layer is confined to the JVM-pure protocol module. It imports `WireJson`
and `TlsIdentity` and the existing Circle primitives only. No Android import,
socket, file write, clock sleep, second signature path or durable receipt store
is part of this layer.

## Public surface

The exact API held by the frozen seams:

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
primitive that is non-blank and within `MAX_FIELD_CHARS`, and returns false for
a blank, oversized, malformed or extra-key body. It is structural only: it
signs nothing, pins nothing and reads no store.

`receiptAuthors` filters to events whose `type` is `RECEIPT`, whose body passes
`validReceiptBody`, whose `dropId` and `sha256` equal the two arguments, whose
`authorFp` is in the normalised `members` set (trim, uppercase, as in
`CircleCore.tally`) and whose signature passes `CircleCore.verify`. It returns
a deduplicated set of author fingerprints with at most one receipt per member,
the winner chosen by `CircleCore.ORDER` so two peers that received the same
receipts in different orders return the same set. `keyId` is provenance only
and is not a selection key; the caller counts the returned set.

No other method is added. `CircleCore.canonical`, `create`, `sign`, `verify`,
`order`, `dedup`, `evaluate`/`accept`, `fresh`, `validShape`, `tally` and
`kickTally` keep their current signatures and behaviour. `CircleRequest`,
`CircleResponse`, `Link` and `CircleWire.canonical` keep their shapes.

## Event shape and body gate

The signed receipt body has exactly the closed key set:

```
{"dropId":"<id of the file announcement>","sha256":"<landed content digest>","keyId":"<per-drop key identifier>"}
```

`dropId` is the `id` of the `file` event being acknowledged, not the
recipient's local delivery id. `sha256` is the plaintext digest carried by the
`file` event, read after the landing hash gate; `ProtocolClient.sendFiles`
gates on the sealed `cipherSha` instead. `keyId` is provenance from the
2026-12-09/10 per-drop key layer and never invents an identifier. No `path`,
`spoolPath`, `token`, key material, file bytes, local status string or
`landedMs` field is present. `createdMs` on the event is the acknowledgement
time; there is no second time field in the body.

All three values are non-blank strings bounded by `MAX_FIELD_CHARS` (256); the
whole body is bounded by `MAX_BODY_CHARS` (16 KiB). A missing key, an extra key,
a non-string value, a blank value or a value over the cap is refused. The gate
stays structural so a sender's spool path or a local status cannot enter a
signed, fanned-out event through the receipt verb.

## Canonical, signature and replay

Receipts reuse `CircleCore.canonical(e)` unchanged: domain tag `FLITCIRCLE1`,
then length-prefixed `id`, `circleId`, `authorFp`, `writeLong(lamport)`, `type`,
`body`, `writeLong(createdMs)`. The body is covered as one length-prefixed
field, so no delimiter can be smuggled through it. `sig` and `cert` stay
outside the signed bytes. `CircleCore.create` mints a receipt with the
receiver's `TlsIdentity`, `sign` writes `sig`, and `verify` binds the embedded
certificate's own SHA-256 DER to `authorFp` before verifying and pinning. A
sender cannot forge a receiver's receipt, and a failed verify writes no pin.
There is no second signature primitive; this is the same `TlsIdentity.sign` /
`verify` path used by panic, Circle and courier events.

Receipts use `CircleCore.order`, `dedup`, `evaluate`/`accept` and `fresh`
exactly as the other verbs do: `REPLAY_WINDOW_MS` = 6 h, `CLOCK_SKEW_MS` =
5 min, `MAX_SEEN` = 4096, `MAX_EVENTS_PER_REQUEST` = 1024, `MAX_REQUEST_BYTES`
= 8 MiB, `MAX_SIG_CHARS` = 1024 and `MAX_CERT_CHARS` = 8 KiB, with precedence
`Invalid → OutsideWindow → Duplicate → Accept`. Callers verify before
`evaluate`, because `evaluate` reserves the id before crypto. No new cap is
introduced: the roster is already bounded by `FlitCircleHost.MAX_MEMBERS` = 500
and the durable log by `FlitCircleStore.EVENT_CAP` = 500.

## Wire and state ownership

No new frame on `Link` and no new field on `CircleRequest` / `CircleResponse`.
A receipt travels in `CircleRequest.events` on `POST /api/flit/v1/circle/sync`
under the existing 8 MiB body cap, or inside a courier/QR parcel payload that
already carries signed events. The protocol module owns no durable receipt
state; its only mutable memory is the existing bounded `seen` id set in
`CircleCore`. `FlitCircleStore.reduce` already appends unknown verbs as
history, so a folded receipt enters `FlitCircleStore.events` without a reducer
change on this date. Sender-local `CircleDelivery.statuses` (`pending`, `sent`,
`refused`, `failed`) stays local-only and unchanged; a receipt is not a
gossiped status field and does not replace the delivery record. Mapping a
valid receipt to a local status, the `FlitCircleSync.circleBodyValid` branch
for the new verb, the `FlitCircleHost` SYNC body gate, the CLI command and the
file-card display belong to 2026-12-14.

## Verification

Fresh pure run, counts to be recorded from the JUnit XML:

```
source env.sh && ./gradlew --no-daemon --rerun-tasks :protocol:test
```

| Module | Before (last green inventory) | After this layer | Task |
| --- | --- | --- | --- |
| `protocol` | 216 | 240 | `:protocol:test` |
| `app` | 272 | 272 (unchanged by this layer) | `:app:testDebugUnitTest` |
| `harness` | 62 | 62 (unchanged by this layer) | `:harness:test` |
| Total | 550 | 574 | — |

`--rerun-tasks` is required: stale XML has previously reported green against
an older class layout. The "before" figures are the prior green inventory in
`TESTING.md` §2; the "after" figures come from a clean `--rerun-tasks` run.
No Gradle run was made for this draft, so no test result is asserted here.

## Tests

| Class | Cases | What it pins |
| --- | --- | --- |
| `ReceiptBodyTest` (new) | 12 | `RECEIPT_BODY_KEYS` is exactly `{dropId,sha256,keyId}`; `validReceiptBody` accepts only non-blank bounded strings and refuses a partial, extra, mistyped, blank or over-cap field, including any `landedMs` key; `keyId` is provenance only and `createdMs` stays the event time; `CircleEvent.RECEIPT = "receipt"` is the eleventh verb in `TYPES` with existing verb values unchanged |
| `ReceiptTallyTest` (new) | 6 | `receiptAuthors(events, dropId, sha256, members)` filters by exact body `dropId`/`sha256`, verifies the signature, keeps only current members and returns a deduplicated fingerprint set with at most one receipt per member (latest wins); a forged author, a non-member, a mismatched drop or digest and a failed signature contribute nobody; non-receipt events and the existing kick/vote tallies are unaffected |
| `ReceiptCanonicalTest` (new) | 6 | Canonical bytes stay byte-identical for the existing verbs and cover `dropId`, `sha256` and `keyId` for `RECEIPT` with `sig`/`cert` excluded, so changing any covered field changes the bytes; `create`/`sign`/`verify` round-trip, forged-author rejection with no pin write, replay-window edges, duplicate precedence and the bounded `seen` set are unchanged |

Case counts come from a clean `--rerun-tasks` JUnit XML run, not from a target
figure. Test names are the `@Test` declarations in each class. No case count
is claimed in this draft. No app, harness or device test is added on this date.

## Evidence

| Label | Claimed here | Basis |
| --- | --- | --- |
| CODE | Yes | `CircleEvent.kt` and `CircleCore.kt` match the API, byte rules and bounds recorded above; no new route, header, DTO field, frame or v2.2 status |
| PURE | Yes | The three protocol test classes above are planned for a fresh `--rerun-tasks` run; counts not yet read from XML |
| SIM-HARN | No | No CLI command, in-process node or harness walk exercises a receipt on 2026-12-11; that wiring is the 2026-12-14 layer |
| DEVICE | No | No phone, second phone, radio or camera observation is made on this date |

The weakest recorded level governs each claim. No device observation is
claimed by this layer. A receipt on this date is a protocol primitive with pure
test evidence only; it is not an observed delivery acknowledgement between
two devices.

## Limitations

- No app, CLI, harness or device work is part of this layer. The two-node
  receipt exchange, host filter, CLI command, file-card display and the device
  observation belong to 2026-12-14.
- A receipt proves that a named drop landed hash-exact on the signing member's
  device. It does not prove global delivery to every member and is not a
  remote status, command, revocation or deletion primitive.
- No `landedMs` or second time field is added. `createdMs` is the acknowledgement
  time, so a member's clock sets the timestamp within the 5 min skew tolerance.
- `keyId` is provenance only. A later rekey that changes the key identifier
  does not invalidate an earlier receipt for the same `(dropId, sha256)`.
- The protocol module holds no durable receipt state. A folded receipt enters
  the bounded event log as history; `CircleDelivery.statuses` stays local-only.
- No new cap is introduced. The 500-event log cap and 1024-event per-request
  cap still apply, and receipts add no unbounded state.
- Canonical bytes and the replay rules of the existing verbs are unchanged.
  No v2.2 surface changes: no new route, header, QR parameter or JSON DTO
  field with a changed default.
- Performance figures are not stated; no timings are invented.
- No device walk, phone observation, second physical phone or live mesh proof
  is claimed on this date.

## Verification status

Counts for `ReceiptBodyTest`, `ReceiptTallyTest` and `ReceiptCanonicalTest`,
the protocol total and the rig total come from a clean `--rerun-tasks` run once
the code and tests land. CODE and PURE evidence are recorded above as planned;
no SIM-HARN or DEVICE evidence exists for receipts on this date. The route and
frame surface is unchanged from the 2026-12-10 per-drop parity layer. Every
numeric field in this draft is a placeholder pending that run.
