# Per-drop content keys — app and CLI parity

Wire the protocol-layer per-drop content keys into the app and the CLI so a sealed file drop is announced, delivered, unwrapped and landed by the same rules on both sides.

## Date and layer

2026-12-10 — Integration. Per-drop keys in app and CLI, tests.

## Depends on

2026-12-09 — Protocol: the pure per-drop key core (`per-drop-keys.md`). The integration binds to its key mint, wrap, unwrap, seal and open functions and to its keyed `file` body shape; no key material is implemented here. The three-node baseline (2026-11-26), courier carry core (2026-11-27) and air-gap parcels (2026-12-08) are already landed and keep their `Link` and frame shapes unchanged — a sealed drop is opaque payload to them.

## Scope

The app's Circle drop path and the CLI's `circle file` path each mint one fresh content key per drop, wrap it to the current members' identity public keys, seal the file bytes with AES-GCM, announce the sealed drop in a signed `file` event, and push the sealed bytes with the existing `sendFiles` call. The receive side in both surfaces unwraps with the receiving identity, opens the sealed bytes, checks the plaintext digest, and lands the plaintext through the normal received-file path. Old three-key `file` events stay plaintext and keep their current behaviour. The app's `circleBodyValid` FILE branch is part of this layer: it must accept both the legacy and the keyed `file` body sets. No route, header, QR field or v2.2 shape changes.

## Design

### Pure core consumed from 2026-12-09

`DropKeyCore` in `protocol/src/main/kotlin/app/flit/share/protocol/circle/` exposes this exact API:

```
object DropKeyCore {
  const val KEY_BYTES = 32, IV_BYTES = 12, TAG_BYTES = 16
  const val MAX_WRAPS = 32, MAX_WRAPPED_CHARS = 512
  const val DOMAIN = "FLITDROP1"
  data class Key(val bytes: ByteArray, val iv: ByteArray)
  data class KeyedFile(val name: String, val size: Long, val sha256: String,
                       val keyGen: Int, val cipherSha: String, val iv: String,
                       val wraps: Map<String, String>)
  fun mint(): Key
  fun wrap(key: ByteArray, recipients: Map<String, X509Certificate>): Map<String, String>
  fun unwrap(wrapped: Map<String, String>, identity: TlsIdentity): ByteArray?
  fun seal(key: Key, plaintext: ByteArray): ByteArray   // IV || ciphertext || tag
  fun open(key: ByteArray, sealed: ByteArray): ByteArray?
  fun keyedFileBody(v: KeyedFile): String
  fun parseFileKeyBody(body: String): KeyedFile?
  fun keyId(dropId: String, keyGen: Int): String = "$dropId:$keyGen"
}
```

`mint()` returns a fresh `Key(bytes, iv)`; `seal` takes that `Key`, while `open` and `wrap` take the bare key byte array. `wrap` uses RSA-OAEP with SHA-256 and an explicit `OAEPParameterSpec`, Base64, one entry per uppercase fingerprint; `unwrap` uses the receiving identity's own private key. `keyId(dropId, keyGen)` names the key generation for receipts. `CircleCore` accepts either the legacy `FILE_BODY_KEYS = {name, size, sha256}` or the keyed set below. All bounds live in the protocol core; the app and CLI call them rather than redefining them.

### Event shape

Signed `file` body, keyed form — exact key set:

```
{"name":"...","size":N,"sha256":"<plaintext sha>",
 "keyGen":1,"cipherSha":"<sha256 of sealed bytes>",
 "iv":"<base64 12 bytes>","wraps":{"<UPPERCASE FP>":"<base64 wrapped key>"}}
```

`KEYED_FILE_BODY_KEYS = {name, size, sha256, keyGen, cipherSha, iv, wraps}`. `CircleCore.canonical` already covers the whole body, so the sender's signature binds `cipherSha`, `iv` and every wrap; a courier or relay that carries the bytes cannot alter them. A legacy body has exactly `FILE_BODY_KEYS` and no key material. A body with a partial key set (a `wraps` map without `cipherSha`, an extra field, a non-integer `size`, a blank fingerprint) is refused by the same gate. The body stays under `MAX_BODY_CHARS` (16 KiB). Every body string stays under `MAX_FIELD_CHARS` (256) except each value in `wraps`, bounded by `MAX_WRAPPED_CHARS = 512` because a 2048-bit RSA-OAEP wrap is about 344 Base64 characters; `MAX_FIELD_CHARS` would refuse a valid wrap. With `MAX_WRAPS = 32` and 344 chars per wrap, the map fits. A member list above `MAX_WRAPS` is refused with a log line, never silently trimmed.

### App seams

- `FlitEngine.circlePostFile` (`FlitEngine.kt:725-734`) gathers current members from `FlitCircleStore.load`, resolves each fingerprint's certificate from `TlsIdentity.pinned(fp)` or the member's `CircleEvent.cert` in the folded log, wraps to those with a usable cert, seals a cache copy at `<spool>.flitdrop`, then posts through `FlitCircleSync.postFile(body)` and starts delivery with the sealed path. No usable cert, or a member count above `MAX_WRAPS`, logs `circle drop: no recipient key material — not posted` / `circle drop: N recipients exceeds key wrap cap` and posts nothing; the source spool is kept.
- `FlitCircleSync.postFile` (`FlitCircleSync.kt:545-547`) gains a body-taking overload that posts `CircleEvent.FILE` with the caller-built body. The legacy three-argument form and `circleFileBody` remain for old drops.
- `FlitCircleSync.circleBodyValid` (`FlitCircleSync.kt:135-152`) is in this layer's scope. Its `FILE` branch currently requires `obj.keys == setOf("name", "size", "sha256")` at `:150-152`; it must accept either that exact legacy set or the keyed set, with each wrap value bounded by `MAX_WRAPPED_CHARS`. Otherwise keyed events are refused by the app's own body gate before any unwrap is attempted. The gate stays closed to partial, extra or oversized key sets.
- `FlitCircleStore.CircleDelivery` (`FlitCircleStore.kt:104-120`) gains additive fields `cipherSha: String = ""` and `wrappedFor: List<String> = emptyList()`, plus terminal status `NO_KEY = "no-key"`. `decode` keeps its current normalisation: rows with a blank sha are dropped, blank status fingerprints stripped, old rows decode. `circleDeliveryTerminal` in `FlitEngine.kt:507-511` includes `NO_KEY`; a fingerprint with no wrap gets `NO_KEY` locally and no bytes. The delivery record still holds only the sealed spool path; no key is persisted in plaintext.
- `FlitEngine.startCircleDelivery` / `deliverCirclePending` (`FlitEngine.kt:514-608`) keep the per-sha `Mutex`, the 30 s worker and the 20/40/60 s backoff unchanged. They push the sealed spool with `sendFiles(..., multi = false)`; the declared `FileInfo.sha256` is the ciphertext hash, so the receiver's landing gate checks the sealed bytes. A returned report still concludes (sent/refused/failed), a throw or missing route stays `PENDING`. When every current recipient is terminal, the sealed spool is deleted.
- Receive side: `FlitEngine` rebuilds an in-memory `cipherSha → DropKeyMaterial` index from the bounded folded event log at boot. In `onUploadFinish` (`FlitEngine.kt:1020-1032`), an `UploadResult.Saved` whose landed digest matches an indexed `cipherSha` is unwrapped with the local `identity`, opened and checked against the announced plaintext `sha256`. On success the plaintext is written under the announced name, `publishReceived` records its digest so `_circleLandedShas` (`FlitEngine.kt:213-217`) matches the card, and the ciphertext temp is deleted. A missing wrap, a tag failure or a plaintext digest mismatch logs `circle drop: cannot open <name> (<reason>)`, drops the landed ciphertext and adds no ledger entry. A landed file with no `cipherSha` match takes the existing plaintext path unchanged.
- `CircleUi.kt:565-568` extends the local-status order with `NO_KEY -> 4` and shows `file drop · no key for this device` for that status. The landed/open-file states are otherwise unchanged.

### CLI seams

- `Harness.circleFileAnnouncement` (`Harness.kt:875-880`) gains a keyed overload that mints the same body shape; the legacy `{name,size,sha256}` builder stays for `circle post --file`.
- `circle file` (`Harness.kt:1107-1136`) wraps to the current members in `CircleCliState` using `TlsIdentity.pinned(fp)`, seals a `*.flitdrop` copy of the spool, announces the keyed event, and calls the existing `deliverCircleFile` with the sealed path. `CircleFileDelivery` (`Harness.kt:896-902`) gains `cipherSha` and `wrappedFor` with defaults; `circleDeliveryOutcome` (`:916-921`) is unchanged. Above the wrap cap or with no usable cert the command prints `!! no recipient key material — nothing posted` and exits without an event.
- `ConsoleDelegate.onUploadFinish` (`Harness.kt:483-490`) applies the same unwrap/open/plaintext-sha step against the CLI's own `circle.state` events, writing the plaintext next to the landed ciphertext and printing `drop-open <name> <plainSha> (<bytes> bytes)`. A non-recipient prints `drop-refused <name>: no wrap`; a tag or digest failure prints `drop-refused <name>: open failed` and leaves no plaintext.
- `flit-cli parse-qr`, `Qr.kt` v1/v2/v3 and the parcel frame codecs are untouched. Sealed bytes cross them as opaque payload.

### State ownership

Content keys exist only as wrapped ciphertext in the signed event, as the sender's ephemeral key until the drop finishes, and as an in-memory unwrapped key for one decrypt pass. `FlitCircleStore` and `circle.state` persist no plaintext key. The sealed spool lives in app cache / the CLI temp path and is removed when all current recipients are terminal or the drop is refused. The received ledger keeps the plaintext digest, as today.

## Invariants

1. A keyed `file` body has exactly `KEYED_FILE_BODY_KEYS`; a legacy body has exactly `FILE_BODY_KEYS`. Both pass the host gate; any other key set is refused.
2. Each drop has one fresh 32-byte key and one fresh 12-byte IV. Two drops of identical bytes have different `cipherSha` values and different sealed bytes.
3. `wraps` contains one entry per current member with a usable certificate, at most `MAX_WRAPS`, keyed by uppercase fingerprint. A member who left before the drop has no wrap in that drop. Over-cap or zero usable keys refuses the drop with a log line and posts no event.
4. The bytes pushed by `sendFiles` are the sealed bytes; their `FileInfo.sha256` equals `cipherSha`. The receiver gates the landing on that hash, then gates the plaintext on the announced `sha256`.
5. Unwrap uses the receiving identity's own private key. A fingerprint absent from `wraps`, a wrong key, a GCM tag failure or a plaintext digest mismatch yields no ledger entry and a logged refusal.
6. The signed body binds `wraps`, `iv` and `cipherSha`. A modified keyed event is skipped by signature verification before any unwrap is attempted.
7. No plaintext content key is persisted. Removing the app's in-memory index or restarting the CLI still leaves only the wrapped key in the bounded event log.
8. Delivery conclusions are unchanged: a returned report concludes, a thrown carrier or no route stays `PENDING`, `NO_KEY` is terminal locally, and all-terminal deletes the sealed spool. Legacy drops land in plaintext with the same status rules.
9. No new v2.2 route, header, DTO field, QR parameter or status code appears. A v0.2.0 peer sees the same `file` event body and bytes it saw before.

## Failure modes

| Condition | Conclusion | Keeps bytes | Drops | Evidence |
| --- | --- | --- | --- | --- |
| No usable recipient cert at post time | Refuse drop, log `no recipient key material`, no event | Source spool | Sealed copy if made | CODE / PURE |
| Member count above `MAX_WRAPS` | Refuse drop, log cap reason, no event | Source spool | Sealed copy if made | CODE / PURE |
| Announce post fails | Drop not started, log | Source spool | Sealed copy | CODE / PURE |
| Carrier throw or no route | `PENDING`, retried by 30 s worker at 20/40/60 s | Sealed spool | Nothing | CODE / PURE / SIM-HARN |
| Returned decline / `failed` / `sent` | `REFUSED` / `FAILED` / `SENT`, terminal | Sealed spool until all terminal; landed ciphertext | Sealed spool when all terminal | CODE / SIM-HARN |
| Fingerprint absent from `wraps` | `NO_KEY`, `drop-refused`; no ledger entry | Landed ciphertext until cleanup | Ciphertext temp | CODE / PURE / SIM-HARN |
| GCM tag or plaintext sha mismatch | `drop-refused`, no ledger entry | Nothing | Landed ciphertext and temp | PURE / SIM-HARN |
| Modified `wraps` / `iv` / `cipherSha` | Event skipped at signature gate | Event log unchanged | Event | CODE / PURE / SIM-HARN |
| Partial or extra body key | Refused by file-body gate | Event log unchanged | Event | CODE / PURE |
| Legacy three-key drop | Plaintext landing, existing statuses | Landed file | Nothing | CODE / PURE / SIM-HARN |
| Process death mid-delivery | Owed row and sealed spool survive; key index rebuilt from log | Row + sealed spool | In-memory unwrapped key | CODE / PURE |

## Tests

### Pure

- `DropKeyCore` consumption in `protocol/src/test` and `app/src/test`: mint uniqueness, wrap round-trip to an identity's public key, unwrap with the wrong identity returns null, seal/open byte-exact, a flipped tag or byte returns null, `MAX_WRAPS` and `MAX_WRAPPED_CHARS` bounds, keyed/legacy body codec and exact key sets, partial and extra keys refused.
- `FlitCircleStoreTest`: additive `cipherSha` / `wrappedFor` survive encode-decode; old rows decode; blank sha rows dropped; blank status fingerprints stripped; `NO_KEY` is terminal.
- `FlitCircleSyncTest`: `postFile` with a caller-built keyed body emits a `file` event whose signature verifies and whose body passes the keyed gate.
- `FlitEngineTest`: the receive seam maps a `cipherSha` match to a plaintext landing, refuses a missing wrap, a tag failure and a sha mismatch, and leaves a legacy landing unchanged.

### Harness

- Extend `CircleHarnessTest` with two CLI identities: post a keyed drop, assert the receiver lands plaintext hash-exact to the announced `sha256` and prints `drop-open`, and assert the sealed bytes on the wire hash to `cipherSha`.
- A third identity with no wrap cannot open the drop and lands no plaintext.
- A tampered keyed event is skipped by the signature gate; a legacy `circle file` drop still lands plaintext with `pending → sent`.
- Repeat the drop with identical bytes and assert a different `cipherSha` and a different wrapped key.

### Device walk

No device walk on this date. The receive side is test-evidenced only until the full feature walk on 2026-12-17 records a keyed drop landing on the phone. Two-phone RFCOMM and a live multi-member mesh stay unverified.

## Evidence for this day

`docs/completions/2026-12-10-per-drop-keys.md`, written when the work lands, records the rig command and the before/after module counts, the pure test names, the harness test names, the seeded payload sizes and their announced/plain/cipher digests, and the limitation that no device observation was made on this date. The evidence levels used are CODE, PURE and SIM-HARN; the weakest level governs each claim.

## Out of scope

Delivery receipts (2026-12-11 and 2026-12-14), Circle pad (2026-12-15 and 2026-12-16), a device walk on this date, remote revocation or deletion of a drop already landed on another device, key escrow or recovery, a second content key per recipient, encryption of message/poll/vote events, changes to `Qr.kt` byte shape or the parcel frame codec, new wire routes or headers, v2.2 surface changes, and a live multi-member mesh proof.

## Commit

2026-12-10 Wire per-drop content keys into app and CLI with pure and harness parity tests
