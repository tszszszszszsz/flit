# Delivery receipts — app and CLI parity

Wires the signed receipt event core into the app's Circle delivery records and the CLI's Circle commands, then walks the confirm path between a phone and a CLI bot.

## Date and layer

2026-12-14, Integration. Commit scope: receipts in app and CLI, device walk, doc.

This layer is built ahead of its scheduled day. The delivery receipts code in
the app and CLI and its tests are written now, but the commit stays on 2026-12-14;
no earlier date carries it.

## Depends on

- 2026-12-11 Protocol — `docs/roadmap/delivery-receipts.md`: `CircleEvent.RECEIPT = "receipt"`, `CircleCore.RECEIPT_BODY_KEYS = setOf("dropId","sha256","keyId")`, `validReceiptBody`, `receiptAuthors(events, dropId, sha256, members)` and the existing `CircleCore.canonical` domain tag `FLITCIRCLE1`. Those artifacts are consumed here, not redefined.
- 2026-12-09 Protocol and 2026-12-10 Integration — `docs/roadmap/per-drop-keys.md` and `per-drop-parity.md`: `DropKeyCore.keyId(dropId, keyGen)` is `"$dropId:$keyGen"`, derived from the keyed `file` event's body (`keyGen` and `wraps`); no separate `keywrap` event exists. A receipt never invents a `keyId`.
- 2026-11-30 CLI and 2026-12-01 App — courier sync paths carry the receipt event with no new frame. 2026-12-04 CLI and 2026-12-07 App — parcel display and decode sit beside the confirmation line.

Correlation contract carried through both surfaces: a receipt is addressed by the `sha256` of the drop's `file` event, and at most one receipt per author fingerprint is counted for a drop. `keyId` is provenance only and is never a selection key, so a later rekey does not invalidate an earlier receipt.

## Scope

Three surfaces adopt the 2026-12-11 core:

1. `FlitEngine` and `FlitCircleStore` fold and gate the new verb, then map a valid receipt to the sender's local `CircleDelivery.statuses` for that recipient. The store schema is unchanged.
2. `FlitEngine` mints a receipt after a keyed drop lands hash-exact and the plaintext digest gate passes, then publishes it through the existing Circle log.
3. `Harness.kt` adds `circle receipt` for publishing an assertion and prints confirmed counts from `CircleCore.receiptAuthors` in `circle sync` and `circle file`. A device walk records the phone↔bot confirm path.

Nothing new is sent over the v2.2 wire. A receipt travels in `CircleRequest.events` on `POST /api/flit/v1/circle/sync`, or inside a courier/QR parcel that already carries signed events.

## Design

**Receipt event.** The event shape is owned by the protocol layer:

```
type  = CircleEvent.RECEIPT = "receipt"
body  = {"dropId":"<id of the file event>","sha256":"<landed content digest>","keyId":"<32 lowercase hex>"}
```

`dropId` is the `id` of the `file` event being acknowledged, not the recipient's local delivery id. All three body values are non-blank strings bounded by `CircleCore.MAX_FIELD_CHARS = 256`; the body is bounded by `MAX_BODY_CHARS = 16 KiB`. There is no `landedMs` field: `CircleEvent.createdMs` is the acknowledgement time. No path, spool path, token, key material, file bytes or local status string enters the body. The canonical stream is the existing `CircleCore.canonical` — domain tag `FLITCIRCLE1`, then length-prefixed `id`, `circleId`, `authorFp`, `lamport`, `type`, `body`, `createdMs` — with `sig` and `cert` excluded from the signed bytes and the cert bound to `authorFp` by its own SHA-256 DER in `CircleCore.verify`. Replay uses `6 h`, `5 min`, `MAX_SEEN = 4096`, `MAX_EVENTS_PER_REQUEST = 1024`, `MAX_REQUEST_BYTES = 8 MiB`, with `verify` before `evaluate`.

For a keyed drop the receipt `sha256` is the value in the `file` event body — the plaintext digest the card is keyed by — obtained after `DropKeyCore.open` and the plaintext hash gate. It is not the sealed stream's `cipherSha` that `sendFiles` gates on. That makes the sender's read side and the receiver's mint side name the same `sha256`. Legacy plaintext `file` events have no `keyGen` or `wraps` and therefore no `keyId`; they keep the existing send-report status and get no receipt.

**App gates and fold.** Two app-side gates currently refuse the new verb and must accept it through `CircleCore.validReceiptBody`:

- `FlitCircleSync.circleBodyValid` (`FlitCircleSync.kt:143-167`) ends in `else -> false`; add `CircleEvent.RECEIPT -> CircleCore.validReceiptBody(body)` beside the `FILE` branch.
- `FlitCircleHost` SYNC filter (`FlitCircleHost.kt:166-175`) already checks `CircleEvent.TYPES` and has a `validFileBody` condition for files; add the matching `RECEIPT || validReceiptBody` condition. The existing `authorFp == requester` gate and host signature checks are unchanged.

`FlitCircleStore.reduce` appends unknown verbs as history, so an accepted receipt enters `CircleState.events` without a schema change and stays under `EVENT_CAP = 500`.

**App state mapping.** No new `CircleDelivery` field is introduced. A pure app helper reads `CircleState`:

1. Collect the `id`s of `file` events whose body sha matches a delivery's `sha256`.
2. Call `CircleCore.receiptAuthors(state.events, dropId, sha, currentMembers)` for each candidate id and union the returned author fingerprints into one set. The core picks one entry per author by `CircleCore.ORDER`, so arrival order cannot change the result, and the union keeps one entry per fingerprint across re-announcements of the same content.
3. Under one `FlitCircleStore.update(ctx)` call, apply the existing `setDeliveryStatus` reducer for each confirmed author so `statuses[author] = SENT`. A receipt is a stronger statement than a send report, so it may override an earlier `REFUSED` or `FAILED` inference. No phantom delivery row is created; `keyId` is read only as signed provenance and never selects a receipt.

`FlitEngine.refreshCircle()` publishes the updated deliveries through `_circleDeliveries` as it already does for status writes. Retry-worker constants are unchanged: `30 s` tick and `10 s → 60 s` backoff; Circle sync stays on its `30 s` gate.

**Mint path (app).** `ServerDelegate.onUploadFinish` already fires once per landed file. On a keyed drop, after the per-drop key layer has opened the bytes and checked the plaintext digest against the `file` event, the engine reads that event's `id` and derives `keyId` from its `keyGen` and mints a `receipt` body with `CircleCore.create(..., CircleEvent.RECEIPT, body)`. A `postReceipt(dropId, sha, keyId)` helper beside `FlitCircleSync.postFile` publishes it through the same fan-out path. A missing `keyGen` or `wraps` field, a digest mismatch, `UploadResult.Mismatch` or `Error`, and a thrown carrier mint nothing.

**UI.** `CircleUi` file-card counts gain a `confirmed` value: the size of the author set returned by the pure helper above for that card's delivery. The status line reports sent and confirmed counts as plain text. No new event is rendered as a chat row, no emoji is added and no chat-bubble treatment is introduced. The count helper takes `CircleState` and a `CircleDelivery`, so it is unit-tested without Compose.

**CLI.** `harness/.../Harness.kt`:

- `circleUsage()` adds `receipt --sha SHA [--drop-id ID] --host ip[:port]`. The command requires a joined circle (`circleId`, `key`, `host`). With no `--drop-id`, it finds the `file` event in `st.events` whose body sha equals `--sha` and takes that event's `id`. It then requires `keyGen` and `wraps` in that `file` event's body and derives `keyId(dropId, keyGen)`. With either lookup missing, it prints `!! no key id for drop — receipts are for keyed drops` and exits without an event. No value is fabricated. The minted body is exactly `{dropId,sha256,keyId}`; the event is appended to `st.events` and sent with `pushCircleEvent`, using the existing `circleEvent` helper.
- `circle sync` and `circle file` print a confirmed count obtained from `CircleCore.receiptAuthors(st.events, dropId, sha, st.members)` over the same candidate drop ids. `CircleFileDelivery` needs no new field: the per-drop key layer already added `cipherSha` and `wrappedFor`, and the confirmed set is derived from the log. The existing `status is local-only, never gossiped` wording stays on the other statuses.
- `circle post --file`, `circle file` delivery and `ConsoleDelegate.onUploadFinish` are unchanged. The hint to run `circle receipt` lives in the reproduction recipe, not in the receive log.

## Invariants

1. A receipt is counted only when `CircleCore.verify` returns true and the cert's own fingerprint equals `authorFp`.
2. A receipt body has exactly `dropId`, `sha256` and `keyId`; each is a non-blank string within `MAX_FIELD_CHARS`. No `landedMs`, path, token, key material or status string is present.
3. `keyId` comes from `keyGen` in the keyed `file` body and is provenance only; a rekey that changes the key id does not invalidate a receipt for the same `(dropId, sha256)`.
4. Correlation is by the `file` event's `sha256`; at most one author entry per fingerprint is counted for a drop, chosen by `CircleCore.ORDER` and unaffected by arrival order.
5. A confirmed author is recorded by `setDeliveryStatus` as `SENT` inside one `FlitCircleStore.update` call; no phantom delivery row is created.
6. A receipt for an unknown sha, a departed fingerprint or a non-member author produces no confirmation.
7. A duplicate id or a `createdMs` outside the replay window leaves the confirmed count and the delivery row unchanged.
8. No receipt is minted for a digest mismatch, `Error`, a carrier throw or a plaintext drop without `keyGen` or `wraps`.
9. The app's `circleBodyValid` branch and the host's SYNC filter both route receipts through `CircleCore.validReceiptBody`; the existing verbs keep their bodies and canonical bytes.
10. Receipts use only `/api/flit/v1/circle/sync` or an existing parcel/courier payload; the v2.2 route set and response shapes stay byte-for-byte unchanged.
11. No schema field is added to `CircleDelivery`; the 500-event log cap and 1024-event per-request cap still bound receipt state.

## Failure modes

| Condition | Conclusion | Keeps bytes | Drops | Evidence |
|---|---|---|---|---|
| Body has a missing key, an extra key, a non-string value, a blank value or a value over `MAX_FIELD_CHARS` | `validReceiptBody` false at both app gates; never minted, folded or counted | Drop bytes untouched | The malformed body only | CODE `CircleCore.kt`; PURE per 2026-12-11 |
| Bad signature, blank `sig`, or cert fingerprint different from `authorFp` | `verify` false; no pin written, no status change | Drop bytes untouched | The forged receipt from the confirm set | CODE `CircleCore.kt`; PURE, SIM-HARN |
| `createdMs` older than 6 h or more than 5 min ahead | `Decision.OutsideWindow`; id not reserved | Nothing | Stale receipt from the confirm set | CODE, PURE |
| Same receipt id re-offered by repair sync | `Decision.Duplicate`; confirmed count unchanged | Nothing | Second copy | CODE, PURE, SIM-HARN |
| `dropId` or `sha256` does not match a local `file` event and delivery | Ignored by `receiptAuthors`; no status change | Nothing | Mismatched receipt from the confirm set | CODE, PURE |
| Author not in the current member roster | Ignored by `receiptAuthors` | Nothing | Departed/outsider receipt from the confirm set | CODE, PURE |
| No `keyGen`/`wraps` in the `file` body, or a plaintext legacy drop | CLI exits before minting; app mints nothing; status stays as the send report left it | Sealed spool until existing terminal logic concludes it | Nothing | CODE, PURE |
| `DropKeyCore.open` fails or the plaintext digest differs from the `file` event | No receipt minted; card stays on its local status | Sealed spool and landed bytes follow the per-drop key layer's rules | Nothing | CODE, SIM-HARN |
| Carrier dies while a receipt is in the log, a courier parcel or a QR parcel | Receipt stays in the bounded signed log and is re-offered by fan-out delta or calm repair | Landed file bytes | Nothing | SIM-HARN |
| Older peer without `RECEIPT` in `CircleEvent.TYPES` | Event refused by `validShape`; other events fold normally | Nothing | Only the unknown-type event at that peer | CODE, SIM-HARN |

## Tests

Pure tests first, then harness, then the device walk.

- `protocol/` (`ReceiptCoreTest`, landed on 2026-12-11, re-run here): exact body keys, canonical/sign/verify, forged-author rejection, replay and dedup, and `receiptAuthors` under two delivery orders. These are dependency tests, not new work on 2026-12-14.
- `app/` JVM tests: `FlitCircleSyncTest` gains the `RECEIPT -> validReceiptBody` branch cases (exact keys accepted; extra/blank/non-string keys refused); `FlitCircleHostTest` gains the SYNC filter case where a malformed receipt is skipped and a valid one folds; the pure app confirmation-helper test pins sha correlation, one author per fingerprint under reversed order and reversed re-announcements, `keyId` changes leaving the count intact, non-member authors ignored, and `setDeliveryStatus` promoting a row to `SENT`. `FlitCircleStoreTest` confirms the row schema is unchanged. No Android framework class, no Robolectric and no engine start.
- `harness/` tests: `CircleHarnessTest` extends the two-node keyed-drop walk — bot B posts a receipt after the plaintext digest gate, bot A's next `circle sync` shows one confirmed author, a forged receipt is refused by the host gate, and a replayed id leaves the count at one. Distinct `FLIT_HARNESS_HOME` values and ephemeral ports follow the flaky-pattern rules in `docs/TESTING.md`.
- Device walk (outside the automated rig): phone posts a keyed `circle file`, bot lands it hash-exact and runs `circle receipt --sha …`, phone's next sync shows the confirmed count on the card. Record only what the logs show. If peer-side confirmation cannot be disambiguated, mark it test-evidenced only and name the ambiguity.

## Evidence for this day

`docs/completions/2026-12-14.md` is written when the work is done, not now. It records:

- The exact rig command and test counts from a fresh run, with module counts updated across `docs/TESTING.md` and the other docs that quote them.
- Tier labels: CODE for the event shape, `FLITCIRCLE1` canonical stream, constants and gates; PURE for `receiptAuthors` use, the app confirmation helper and the CLI body derivation; SIM-HARN for the two-bot confirm walk; DEVICE for the phone↔bot observation.
- The observed log lines — `recv-ok`, `circle receipt`, `sync … event(s)` and the card's confirmed count — plus the `dropId`, `sha256` and `keyId` used to correlate them.
- Limitations: receipts apply to keyed drops only; there is no phone-to-phone RFCOMM claim, no three-or-more-member mesh, no backfill for drops made before this layer, and any confirmation that stays test-evidenced only is named.

## Out of scope

- No new HTTP route, header, QR parameter or JSON DTO field; no change to the `file` event body or to `FILE_BODY_KEYS`.
- No receipt for a plaintext drop with no `keyGen`/`wraps`, no fabricated `keyId`, and no backfill for drops made before this layer.
- No encryption of the receipt body: it is a signed, verifiable assertion. Per-drop content keys stay on the delivery path.
- No auto-detection of landings by the CLI; publishing from the box is the explicit `circle receipt` command.
- No schema change to `CircleDelivery`, no new persisted receipt store and no change to `statuses` vocabulary beyond mapping a confirmed author to `SENT`.
- No remote revocation, no third-party verifier outside the Circle and no multi-hop forwarding of receipts.
- No claim for phone-to-phone RFCOMM, a ≥3-member mesh or Wi-Fi Aware.

## Commit

2026-12-14 — Delivery receipts in app and CLI; device walk and completion doc.
