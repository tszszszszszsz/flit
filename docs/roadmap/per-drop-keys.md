# Per-drop content keys

Defines the JVM-pure core that gives each Circle file drop a fresh symmetric key, seals the bytes, and publishes one wrapped key per current member in the signed file announcement.

## Date and layer

2026-12-09, Protocol. Commit scope: per-drop content keys, pure JVM, tests.

## Depends on

- 2026-11-27, Protocol — courier envelope and carry core (`courier-envelope.md`). A keyed `file` event is an ordinary signed log entry inside the envelope; no new route, frame or carrier is added.
- 2026-12-03, Protocol — QR parcel frame codec (`qr-parcel-codec.md`). Sealed drop bytes are opaque payload to the frame codec, so no second framing path is introduced.

Both are earlier protocol layers. The core uses only shipped seams: `CircleCore`, `CircleEvent`, `TlsIdentity` and the existing `file` verb. No device evidence, app wiring or CLI command is required before this date. The 2026-12-10 integration consumes this core unchanged.

## Scope

`DropKeyCore`, a JVM-pure object under `protocol/src/main/kotlin/app/flit/share/protocol/circle/`, with key minting, RSA-OAEP wrapping to member certificates, AES-GCM sealing and opening, and a body codec for the keyed `file` announcement. `CircleCore` gains a type-specific gate that accepts either the legacy three-key `file` body or the keyed body. A `keyId` is published so delivery receipts at 2026-12-11 can name the key generation. The core has no socket, Android import or persistence.

## Design

**Object and constants.** `DropKeyCore` declares `KEY_BYTES = 32`, `IV_BYTES = 12`, `TAG_BYTES = 16`, `MAX_WRAPS = 32`, `MAX_WRAPPED_CHARS = 512` and the domain tag `FLITDROP1`. Its API is `mint()`, `wrap(key, recipients: Map<String, X509Certificate>)`, `unwrap(wrapped, identity)`, `seal(key, plaintext)`, `open(keyBytes, sealed)` and the codec `keyedFileBody(...) / parseFileKeyBody(body)`. No new signature algorithm is used. `unwrap` needs one additive instance method on `TlsIdentity`, `decryptOaepSha256(cipher: ByteArray): ByteArray?`, backed by the existing private key accessor; it returns null on malformed Base64, padding or provider failure and never writes to the pin map.

**Mint, seal, open.** `mint()` draws a fresh 32-byte key and 12-byte IV from `SecureRandom`; neither is derived from the group key, the QR invite or the file hash. `seal(key, plaintext)` uses `Cipher.getInstance("AES/GCM/NoPadding")` with `GCMParameterSpec(TAG_BYTES * 8, iv)` and emits `IV || ciphertext || tag` — the 12-byte IV prefix, the AES-GCM body and the 16-byte tag. Additional authenticated data is the length-prefixed domain tag `field("FLITDROP1")` using the `CircleCore.canonical` rule (4-byte big-endian UTF-8 byte length, then the bytes); the file event id is not in the AAD because the bytes are sealed before the event is minted. `open(keyBytes, sealed)` refuses a value shorter than `IV_BYTES + TAG_BYTES`, splits the prefix and returns plaintext or null on any tag failure. Two drops of identical bytes get different IVs, different sealed bytes and different cipher digests. The pure API takes byte arrays; integration streams through the same JCE cipher so large drops are not held whole in memory.

**Wrap, unwrap, key identifier.** `wrap(key, recipients)` encrypts the 32-byte key with each recipient certificate's RSA public key using `RSA/ECB/OAEPWithSHA-256AndMGF1Padding` and explicit `OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)`, Base64-encodes it and returns a map keyed by uppercase fingerprint. At most `MAX_WRAPS` entries; a larger roster is refused, never silently trimmed. A member with no pinned certificate gets no entry; there is no group-key fallback and no escrow. `unwrap(wrapped, identity)` decrypts with the local private key and returns null for a fingerprint with no entry, a wrong identity or a malformed blob. `keyId(dropId, keyGen) = "$dropId:$keyGen"` is the identifier published for the drop. `dropId` is the `id` of the signed `file` event and `keyGen` is the body's integer generation counter, starting at 1 and bumped when a fresh key is minted for the same drop. A receipt at 2026-12-11 names this value as provenance and never invents one.

**Keyed file body.** The signed `file` event keeps the legacy `FILE_BODY_KEYS = {name, size, sha256}` exactly. A keyed body has exactly `KEYED_FILE_BODY_KEYS = {name, size, sha256, keyGen, cipherSha, iv, wraps}`:

```
{"name":"...","size":N,"sha256":"<plaintext sha>",
 "keyGen":1,"cipherSha":"<sha256 of sealed bytes>",
 "iv":"<base64 12 bytes>","wraps":{"<UPPERCASE FP>":"<base64 wrapped key>"}}
```

`CircleCore.validFileBody` accepts a body whose keys equal either set. For the keyed form it requires non-blank `name` and plaintext `sha256` within `MAX_FIELD_CHARS`, a non-negative integer `size`, a positive integer `keyGen`, a non-blank `cipherSha`, an `iv` decoding to exactly 12 bytes, and `wraps` a JSON object with 1..`MAX_WRAPS` entries whose keys are non-blank uppercase fingerprints and whose values are non-blank Base64 no longer than `MAX_WRAPPED_CHARS`. `MAX_WRAPPED_CHARS = 512` covers a 2048-bit RSA OAEP wrap (256 bytes, 344 Base64 chars); wrapped values use that cap, not `MAX_FIELD_CHARS = 256`. `keyedFileBody(...)` builds exactly this map and `parseFileKeyBody(body)` returns a typed value only when the gate passes, otherwise null. A partial key set, an extra field, a string `size` or a blank fingerprint is refused by the same gate. The whole body stays under `MAX_BODY_CHARS` (16 KiB). `CircleCore.canonical` covers the body as one length-prefixed field, so the signature binds `cipherSha`, `iv` and every wrap; a courier or relay that carries the event cannot alter them.

**Wire and state ownership.** The sealed copy is named `dropId + DROP_WIRE_SUFFIX` with `DROP_WIRE_SUFFIX = ".flitdrop"`. `sendFiles` computes `FileInfo.sha256` from the sealed bytes, so `ServerCore` gates the landing on `cipherSha`; `sha256` on the card stays the plaintext digest, checked after `open`. The protocol core holds no key cache, no sealed bytes and no drop state. The app and CLI own the sealed spool and the in-memory unwrapped key at 2026-12-10. A departure or `rekey` does not change a published drop body; there is no wrap backfill for a later joiner and no remote revocation — a device that kept a wrap can still open an old drop. The `TlsIdentity` pin map is unchanged by mint, wrap, unwrap, seal and open.

## Invariants

1. The gate accepts exactly `FILE_BODY_KEYS` or `KEYED_FILE_BODY_KEYS`; the nine existing event verbs keep byte-identical canonical bytes and signatures, and no v2.2 route or DTO changes.
2. `mint()` produces a fresh 32-byte key and 12-byte IV; two mints differ, and neither value derives from the group key, invite or file hash.
3. `seal`/`open` round-trip byte-exactly for empty, one-byte, 8 KiB and multi-megabyte inputs; the output is exactly `IV || ciphertext || tag` with `TAG_BYTES = 16`; flipping one IV, ciphertext or tag byte, using another key or changing the AAD yields null.
4. `wrap` produces one Base64 entry per uppercase fingerprint with a usable certificate, at most `MAX_WRAPS`; each value is within `MAX_WRAPPED_CHARS` and opens only under that member's identity.
5. A wrong identity, a missing fingerprint, malformed Base64 or bad OAEP padding makes `unwrap` return null without throwing, writing a pin or touching the pin map.
6. `validFileBody` refuses a partial key set, an extra field, a non-integer or negative `size`, a non-positive `keyGen`, an `iv` that does not decode to 12 bytes, an empty or over-cap `wraps` map, a lowercase or blank fingerprint and a value over `MAX_WRAPPED_CHARS`.
7. `keyedFileBody(...)` output passes `validFileBody` and `parseFileKeyBody` returns the same fields; `parseFileKeyBody` returns null for every body the gate refuses.
8. `keyId(dropId, keyGen)` is `"$dropId:$keyGen"`, bounded by `MAX_FIELD_CHARS`, stable for one signed event and changed by a new key generation; receipts treat it as provenance only.
9. Bumping `keyGen`, folding a `rekey` or leaving the circle does not change old drop bodies, and there is no wrap backfill for a later joiner.
10. The protocol core holds no plaintext key; no key material is persisted by this layer and no second signature primitive is introduced.

## Failure modes

| Condition | Conclusion | Keeps bytes | Drops | Evidence |
| --- | --- | --- | --- | --- |
| No usable certificate for a member | That fingerprint gets no `wraps` entry | Source bytes remain with the sender | That member's wrapped key | Pure test: `wrap` omits the fingerprint with no pinned cert |
| Roster above `MAX_WRAPS` | `wrap` refuses; integration posts no event | Source bytes stay | The over-cap map | Pure test asserts a map larger than 32 is refused, not trimmed |
| Unwrap with the wrong identity or a missing fingerprint | No plaintext; the drop is unreadable | Sealed bytes stay until integration cleanup | No pin, no key, no card content | Pure test: wrong identity and absent fingerprint return null and pin map is unchanged |
| Tampered ciphertext, IV or tag | GCM authentication fails; `open` returns null | Sealed bytes stay until integration cleanup | The plaintext | Pure test flips one byte in each field |
| Plaintext digest differs from the card | Drop refused before content is shown | Sealed bytes and card stay for inspection | The substituted plaintext | Pure test asserts the digest gate refuses a rebuilt drop |
| Partial, extra or mistyped body field | `validFileBody` false; no event minted or folded | Bytes stay with the caller | The malformed body | Pure test on the exact key sets and value types |
| Modified `wraps`, `iv` or `cipherSha` on the wire | `CircleCore.verify` fails before any unwrap | Event log unchanged | The modified event | Pure test verifies a signed body, then a tampered copy |
| Duplicate or out-of-window `file` id | No second key installed | Unchanged | The replayed event | Pure test with an injected clock asserts `Duplicate` and `OutsideWindow` |
| Departure, group `rekey`, `keyGen` bump on another drop | Old drop bodies unchanged; no backfill | Old drops and wraps stay | Nothing | Pure test folds a `rekey` and asserts old bodies unchanged |
| Malformed Base64 or OAEP padding | `unwrap` returns null; no exception reaches the caller | Sealed bytes stay | The malformed wrap | Pure test passes junk strings and asserts null |

## Tests

Pure tests come first and are the only tests added on this date. `DropKeyCoreTest.kt` in `protocol/src/test/kotlin/app/flit/share/protocol/` covers: seeded mint uniqueness, key and IV lengths, and independence from group key and invite; wrap/unwrap round-trip, wrong-identity failure, absent fingerprint, junk and tampered blobs, with no pin writes; `MAX_WRAPS` and `MAX_WRAPPED_CHARS` bounds; `seal`/`open` byte-exact round-trips at 0, 1, 8192 and 3 MiB with seeded data; IV, ciphertext, tag and AAD tampering; plaintext digest gate; `keyedFileBody` output, exact keyed and legacy key sets, partial and extra keys refused, and `parseFileKeyBody` null on every refused body; `keyId` derivation and stability; canonical vectors for the nine existing event verbs unchanged; and a keyed `file` event create/sign/verify, dedup, replay window and oversized-body id accounting through `CircleCore`, plus `rekey` independence. Figure counts are read from the JUnit XML after `--rerun-tasks`, as required by `TESTING.md` §3 and §8.

Harness coverage is not added on this date. The protocol layer adds no production endpoint, so no `flit-cli` path or in-process TLS node exercises a keyed drop on 2026-12-09. The two-node harness walk is part of the 2026-12-10 integration.

No device walk is run on this date. The air-gap and full-feature walks at 2026-12-08 and 2026-12-17 are the hardware observations; this layer produces `PURE` evidence only.

## Evidence for this day

`docs/completions/2026-12-09-per-drop-keys.md` is written when the work is done. It records the new test class and its count, the protocol-module total before and after, the exact `--rerun-tasks` rig command and its result, and the constants listed above. Evidence labels are `CODE` for the object, byte rules and gates, and `PURE` for the tests. It states that app and CLI integration, harness walks and device observation are not verified on this date, and that no remote revocation is claimed.

## Out of scope

No app state, UI, CLI command or wire route; those land at 2026-12-10. No new event verb: key material lives in the existing signed `file` body and the legacy `{name,size,sha256}` form is unchanged. No delivery receipts, which are a separate event shape at 2026-12-11 and 2026-12-14. No Circle pad work at 2026-12-15 and 2026-12-16. No change to the courier envelope, QR frame codec, v2.2 surface or group-key rule. No migration of already-landed drops; that is integration work at 2026-12-10. No escrow, server, cloud, remote wipe, second content key per recipient or second signature algorithm.

## Commit

2026-12-09 — Protocol: per-drop content keys, pure JVM core and tests (PURE evidence)
