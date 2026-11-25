# Delivery receipts — protocol core

A receipt is a signed Circle event in which one member acknowledges that a named file drop landed on that member's device; this piece defines the event verb, the body gate and the roll-up in the JVM-pure protocol module.

## Date and layer

2026-12-11 — Protocol. Changes are confined to
`protocol/src/main/kotlin/app/flit/share/protocol/circle/` and
`protocol/src/test/kotlin/app/flit/share/protocol/`. No app, CLI, harness or
device work is part of this date.

## Depends on

- 2026-11-25 — the planning document set in `docs/roadmap/`.
- 2026-11-26 — three-node baseline walk. Phone plus two CLI identities is the
  rig that later extends to receipt observation.
- 2026-11-27, 2026-11-30, 2026-12-01, 2026-12-02 — courier envelope and carry
  core, CLI carrier, app sync and three-node walk. A receipt is an ordinary
  signed `CircleEvent`, so a courier that carries the log carries receipts with
  no new frame.
- 2026-12-03, 2026-12-04, 2026-12-07, 2026-12-08 — QR parcel codec, CLI,
  app display/decode and air-gap walk, for the same reason.
- 2026-12-09, 2026-12-10 — per-drop content keys in protocol and in app/CLI.
  The `keyId` value a receipt names is the identifier those layers publish for
  the acknowledged drop; a receipt never invents one.

## Scope

Three additive protocol-layer items:

1. `CircleEvent.RECEIPT = "receipt"` added to the companion object and to
   `CircleEvent.TYPES` (`CircleEvent.kt:53-65`).
2. `CircleCore.RECEIPT_BODY_KEYS` and `CircleCore.validReceiptBody(body)`, a
   type-specific gate in the shape of `validFileBody`
   (`CircleCore.kt:304-325`).
3. `CircleCore.receiptAuthors(events, dropId, sha256, members)`, a pure roll-up
   of the current members who signed a well-formed receipt for one drop.

No new route, header, DTO field, frame, crypto path or persisted store is
introduced. `CircleRequest`, `CircleResponse`, `Link` and `CircleWire.canonical`
keep their current shapes. The existing nine event verbs keep byte-identical
canonical forms.

## Design

**Event shape.** A receipt is one immutable `CircleEvent`. Its `body` is a
bounded JSON object with exactly the closed key set
`RECEIPT_BODY_KEYS = setOf("dropId", "sha256", "keyId")`:

```
receipt  {"dropId":"<id of the file announcement>","sha256":"<landed content digest>","keyId":"<per-drop key identifier>"}
```

`dropId` is the `id` of the `file` event being acknowledged, not the
recipient's local delivery id. `sha256` is the plaintext digest carried by the
`file` event, read after `DropKeyCore.open` and the landing hash gate;
`ProtocolClient.sendFiles` gates on the sealed `cipherSha` instead. `keyId` is
provenance from the 2026-12-09/10 per-drop key layer. No `path`, `spoolPath`, `token`, key material, file bytes or local status
string is present. `createdMs` on the event is the acknowledgement time; no
second time field is added to the body. All three values are non-blank strings
bounded by `CircleCore.MAX_FIELD_CHARS` (256); the body is bounded by
`MAX_BODY_CHARS` (16 KiB).

`validReceiptBody(body)` parses with `WireJson.json` into a `JsonObject`,
requires `obj.keys == RECEIPT_BODY_KEYS`, requires each value to be a JSON
string primitive that is non-blank and within `MAX_FIELD_CHARS`, and returns
false for a blank, oversized, malformed or extra-key body. It is structural
only: it signs nothing, pins nothing and reads no store.

**Canonical and signature.** Receipts reuse `CircleCore.canonical(e)` unchanged:
domain tag `FLITCIRCLE1`, then length-prefixed `id`, `circleId`, `authorFp`,
`writeLong(lamport)`, `type`, `body`, `writeLong(createdMs)`. The body is
covered as one length-prefixed field, so no delimiter can be smuggled through
it. `CircleCore.create` mints the receipt with the receiver's `TlsIdentity`,
`CircleCore.sign` writes `sig`, and `CircleCore.verify` binds the embedded
certificate's own SHA-256 DER to `authorFp` before verifying and pinning
(`CircleCore.kt:79-111,119-187`). A sender cannot forge a receiver's receipt:
the certificate is checked against the claimed author before the pin store is
touched. There is no second signature primitive; this is the same
`TlsIdentity.sign` / `verify` path used by panic, Circle and courier events.

**Order, dedup, replay.** Receipts use `CircleCore.order`, `dedup`,
`evaluate`/`accept` and `fresh` exactly as the other verbs do:
`REPLAY_WINDOW_MS = 6 h`, `CLOCK_SKEW_MS = 5 min`, `MAX_SEEN = 4096`,
`MAX_EVENTS_PER_REQUEST = 1024`, `MAX_REQUEST_BYTES = 8 MiB`, `MAX_SIG_CHARS =
1024`, `MAX_CERT_CHARS = 8 KiB`, with precedence
`Invalid → OutsideWindow → Duplicate → Accept`. Callers verify before
`evaluate`, because `evaluate` reserves the id before crypto. No new cap is
introduced: a roster is already bounded by `FlitCircleHost.MAX_MEMBERS = 500`
and the durable log by `FlitCircleStore.EVENT_CAP = 500`.

**Roll-up.** `receiptAuthors(events, dropId, sha256, members)` filters to
events whose `type` is `receipt`, whose body passes `validReceiptBody`, whose
`dropId` and `sha256` equal the two arguments, and whose `authorFp` is in the
normalised `members` set (trim, uppercase, as in `CircleCore.tally`). One
author contributes at most one entry, chosen by `CircleCore.ORDER`, so two peers
that received the same receipts in different orders return the same set.
`keyId` is recorded in the signed body but is not a selection key: a later
rekey that changes a key identifier does not invalidate an earlier receipt for
the same landed content. The caller counts the returned set.

**Wire and state ownership.** No new frame on `Link`, no new field on
`CircleRequest`/`CircleResponse`. A receipt travels in `CircleRequest.events`
on `POST /api/flit/v1/circle/sync` under the existing `8 MiB` body cap, or
inside a courier/QR parcel payload that already carries signed events. The
protocol module owns no durable receipt state; its only mutable memory is the
existing bounded `seen` id set in `CircleCore`. The durable event log is
`FlitCircleStore.events`. `FlitCircleStore.reduce` already appends unknown
verbs as history, so a folded receipt enters that log without a reducer change
on this date. Sender-local `CircleDelivery.statuses` (`pending`, `sent`,
`refused`, `failed`) stays local-only and unchanged here; a receipt is not a
gossiped status field and does not replace the delivery record. Mapping a valid
receipt to a local status belongs to the 2026-12-14 integration day, as does
the `FlitCircleSync.circleBodyValid` branch for the new verb (its current
`else -> false` at `FlitCircleSync.kt:166` refuses it) and the
`FlitCircleHost` SYNC body gate.

## Invariants

1. Adding `RECEIPT` to `TYPES` leaves the canonical bytes, validity and
   acceptance of all nine existing verbs unchanged; `validShape` stays
   type-agnostic bounds only.
2. A receipt body has exactly `RECEIPT_BODY_KEYS`; `validReceiptBody` refuses a
   missing key, an extra key, a non-string value, a blank value and a value
   over `MAX_FIELD_CHARS`.
3. A receipt body carries no file bytes, local path, queue token, key material
   or delivery status.
4. Signature bytes cover every event field except `sig` and `cert`; `verify`
   checks a first-seen cert against `authorFp` before pinning it.
5. A receipt counts only when both `dropId` and `sha256` match the drop being
   read and its author is in the supplied current-member set.
6. At most one receipt per author per drop contributes to the roll-up; the
   winner is chosen by `CircleCore.ORDER`, independent of arrival order.
7. Receipts obey the 6 h replay window, 5 min clock skew, bounded 4096-id set
   and `Invalid → OutsideWindow → Duplicate → Accept` precedence; `verify`
   precedes `evaluate`.
8. `keyId` is provenance only. A rekey that changes the key id does not
   invalidate an earlier receipt for the same `(dropId, sha256)`.
9. Receipts are events, not delivery status. No `CircleDelivery` reducer,
   spool path or local status is read or written on this date.
10. No v2.2 surface changes: no new route, header, QR parameter or JSON DTO
    field with a changed default.
11. Receipts add no unbounded state; the 500-event log cap and 1024-event
    per-request cap still apply.

## Failure modes

| Condition | Conclusion | Keeps bytes | Drops | Evidence |
| --- | --- | --- | --- | --- |
| Receipt body missing a key, with an extra key, or oversized | `validReceiptBody` false; never signed, folded or counted | yes — the drop bytes are untouched | the malformed body only | CODE `CircleCore.kt:304-325`; PURE test on 2026-12-11 |
| Bad signature, blank `sig`, or cert fingerprint not equal to `authorFp` | `verify` false; no pin is written and the receipt is not counted | yes | the forged receipt | CODE `CircleCore.kt:165-187`; PURE test on 2026-12-11 |
| `createdMs` older than 6 h or more than 5 min ahead | `Decision.OutsideWindow`; id not reserved | yes | the stale receipt from the tally | CODE `CircleCore.kt:342-360`; PURE test on 2026-12-11 |
| Same id seen again | `Decision.Duplicate`; quiet answer, counted once | yes | the second copy | CODE `CircleCore.kt:342-370`; PURE test on 2026-12-11 |
| `dropId` or `sha256` does not match the drop read | ignored by `receiptAuthors` | yes | the mismatched receipt from the tally | CODE, new roll-up; PURE test on 2026-12-11 |
| Author not in the current-member set | ignored by `receiptAuthors` | yes | the departed/outsider receipt from the tally | CODE, same member gate as `CircleCore.tally`; PURE test on 2026-12-11 |
| Bytes did not land hash-exact at the receiver | no receipt is minted; sender-local delivery status is unchanged | yes — the part/sidecar rules from `ServerCore` still decide | nothing | CODE `ServerCore.kt:594-596,607-625` |
| Carrier dies while a receipt is in a courier or QR parcel | the receipt stays in the bounded signed log and is re-offered by fan-out delta or calm repair | yes | nothing | CODE `FlitCircleSync.kt:461-515,673-716`; earlier courier/QR layers |

## Tests

Pure tests come first, in a new `ReceiptCoreTest` under
`protocol/src/test/kotlin/app/flit/share/protocol/`, using
`TlsIdentity.generate()` and explicit `createdMs`. No socket, Android class or
clock sleep:

- exact body key set and bounds, including non-string `size`-style values and
  oversized fields;
- create/sign/verify round-trip, forged-author rejection through a substituted
  cert, and no pin written by a failed verify;
- replay-window edges (`now - REPLAY_WINDOW_MS - margin`, `now + CLOCK_SKEW_MS +
  margin`) with the id reservation left untouched outside the window;
- duplicate id precedence and `accept`/`fresh` sharing one bounded set;
- one receipt per author chosen by `CircleCore.ORDER` under two different
  delivery orders;
- roll-up ignores wrong `dropId`, wrong `sha256`, malformed bodies and
  non-members, and ignores `keyId` changes for the same content;
- canonical vectors for all nine existing verbs are unchanged after `RECEIPT`
  joins `TYPES`.

Harness: none on 2026-12-11. The two-node receipt exchange, host filter and
CLI command are wired on 2026-12-14; that day adds the harness test with the
production `ProtocolClient`/`ProtocolServer` and `CircleCore`.

Device walk: none on 2026-12-11. The 2026-12-14 day records the observation in
`docs/completions/`; nothing is claimed as walked here.

## Evidence for this day

When the layer lands, `docs/completions/2026-12-11-delivery-receipts.md`
records CODE references to `CircleEvent.kt` and `CircleCore.kt`, the pure test
class and the `:protocol:test` result from a fresh run:

```bash
source env.sh && ./gradlew --no-daemon --rerun-tasks :protocol:test
```

The note carries the CODE / PURE labels used in `docs/FAILURE-MODES.md`, the
new module test count, and the limitation that no SIM/HARN or DEVICE evidence
exists for receipts on this date. Cross-linked counts in `docs/TESTING.md` and
the other docs are updated in the same commit.

## Out of scope

App and CLI wiring, `FlitCircleSync.circleBodyValid`, `FlitCircleHost` SYNC
filtering, file-card receipt display, CLI receipt commands, harness tests and
the device walk belong to 2026-12-14. Also out of scope: changing
`FILE_BODY_KEYS` or the existing `{name,size,sha256}` file announcement,
encrypting receipt bodies, altering `CircleDelivery.statuses`, changing a
receipt into a remote command or revocation, modifying courier/QR framing, and
any change to the LocalSend v2.2 wire surface.

## Commit

2026-12-11 — Protocol: signed Circle delivery receipts core, pure JVM, tests
