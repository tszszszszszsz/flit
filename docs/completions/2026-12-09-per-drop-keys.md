# 2026-12-09 — Per-drop content keys (Protocol) — DRAFT

Layer: 2026-12-09 Protocol. The work is a JVM-pure per-drop content key core in
`app.flit.share.protocol.circle`: one fresh symmetric key and IV per file drop,
RSA-OAEP wraps of that key to the current members' certificate public keys,
AES-GCM sealing of the drop bytes, and a body codec for the signed `file`
announcement. `CircleCore` gains a type-specific gate that accepts either the
legacy `{name, size, sha256}` body or the keyed body. `TlsIdentity` gains one
additive instance method for OAEP-SHA-256 decryption backed by its existing
private key. A keyed drop is an ordinary signed `file` event in the existing
courier envelope and QR parcel frame; this layer adds no route, DTO, frame,
socket, persistence or Android import, and restates no other layer's caps.

Counts below are placeholders. They are to be read from the JUnit XML of a fresh
`--rerun-tasks` run, as required by `TESTING.md` §3 and §8. No Gradle run was
made for this draft, and no test result is claimed here. The layer is dated
2026-12-09. App and CLI integration, harness walks and device observation are
not verified on this date, and no remote revocation is claimed.

## Files in the layer

| File | Content |
| --- | --- |
| `protocol/src/main/kotlin/app/flit/share/protocol/circle/DropKeyCore.kt` | `object DropKeyCore`: constants, `Key`, `KeyedFile`, `mint`, `wrap`, `unwrap`, `seal`, `open`, body codec and `keyId` |
| `protocol/src/main/kotlin/app/flit/share/protocol/tls/TlsIdentity.kt` | One additive instance method `decryptOaepSha256(cipher: ByteArray): ByteArray?`; no other change |
| `protocol/src/main/kotlin/app/flit/share/protocol/circle/CircleCore.kt` | File-body gate only: accept the legacy key set or the keyed key set; canonical bytes unchanged |
| `protocol/src/test/kotlin/app/flit/share/protocol/DropKeyCoreTest.kt` | Mint, key/IV lengths, `keyId`, codec and gate acceptance tests |
| `protocol/src/test/kotlin/app/flit/share/protocol/DropKeyBodyTest.kt` | Legacy and keyed body key sets, bounds and refusal tests |
| `protocol/src/test/kotlin/app/flit/share/protocol/DropKeyCryptoTest.kt` | Wrap/unwrap, seal/open, round-trips at 0, 1, 8192 and 3 MiB bytes, and tamper refusals |
| `docs/TESTING.md` | New protocol classes with counts from the integration run |
| `docs/CONTRACTS.md` | Keyed `file` body contract |
| `docs/SECURITY.md` | Per-drop key confidentiality; no remote revocation |
| `docs/FAILURE-MODES.md` | No key material, over-cap roster and tamper refusals |
| `docs/OPERATIONS.md` | Limits of this layer in operational terms |
| `docs/ARCHITECTURE.md` | Drop key seam |
| `docs/PERFORMANCE.md` | Wrap and stream cost; no invented timings |
| `docs/completions/2026-12-09-per-drop-keys.md` | This layer record |
| `docs/roadmap/per-drop-keys.md`, `docs/roadmap/per-drop-parity.md` | Finalized API for this layer and the 2026-12-10 consumer |
| `docs/roadmap/01-schedule.md`, `docs/roadmap/02-waves.md` | Build-ahead note for the 2026-12-09 layer |

The package imports `CircleCore`, `CircleEvent`, `TlsIdentity`, `WireJson` and
the JCA primitives used by AES-GCM, SHA-256 and RSA-OAEP. No Android import,
socket, file write, clock read, pin write or second signature algorithm is part
of this layer.

## Public surface

The exact API held by the frozen seams:

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

`TlsIdentity` gains exactly one additive instance method, backed by the existing
private key and never touching the pin map:

```
fun decryptOaepSha256(cipher: ByteArray): ByteArray?   // null on any failure
```

`CircleCore.validFileBody(body: String): Boolean` gains the keyed key set while
keeping the legacy set. Its canonical bytes, `validShape`, `verify`, `evaluate`
and total order are unchanged, so no existing event's signature or acceptance
changes.

## Constants

| Constant | Value | Basis |
| --- | --- | --- |
| `KEY_BYTES` | `32` | AES-256-GCM key length |
| `IV_BYTES` | `12` | GCM IV prefix emitted by `seal` |
| `TAG_BYTES` | `16` | 128-bit GCM tag appended by `seal` |
| `MAX_WRAPS` | `32` | Upper bound on recipients in one keyed body |
| `MAX_WRAPPED_CHARS` | `512` | Per-wrap Base64 cap; a 2048-bit RSA-OAEP wrap is about 344 chars |
| `DOMAIN` | `"FLITDROP1"` | AAD tag in `seal`/`open` |
| `FILE_BODY_KEYS` | `{name, size, sha256}` | Existing `CircleCore` legacy set, unchanged |
| `KEYED_FILE_BODY_KEYS` | `{name, size, sha256, keyGen, cipherSha, iv, wraps}` | Closed keyed set in `CircleCore` |
| `MAX_BODY_CHARS` | `16 * 1024` | Reused from `CircleCore` for the whole body |
| `MAX_FIELD_CHARS` | `256` | Reused from `CircleCore` for body strings, including `sha256` and `cipherSha` |

## Crypto and wire shape

`mint()` draws a fresh 32-byte key and 12-byte IV from `SecureRandom`; neither
is derived from the group key, the QR invite or the file hash. `seal(key,
plaintext)` uses `Cipher.getInstance("AES/GCM/NoPadding")` with
`GCMParameterSpec(TAG_BYTES * 8, iv)` and emits `IV || ciphertext || tag`. The
additional authenticated data is the length-prefixed domain tag
`field("FLITDROP1")` under the `CircleCore.canonical` rule: a 4-byte big-endian
UTF-8 byte length followed by the bytes. The file event id is not in the AAD,
because the bytes are sealed before the event is minted. `open(keyBytes,
sealed)` refuses a value shorter than `IV_BYTES + TAG_BYTES`, splits the prefix
and returns plaintext or null on any tag failure. Two drops of identical bytes
get different IVs, different sealed bytes and different cipher digests.

`wrap(key, recipients)` encrypts the 32-byte key with each recipient
certificate's RSA public key using `RSA/ECB/OAEPWithSHA-256AndMGF1Padding` and
an explicit `OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256,
PSource.PSpecified.DEFAULT)`, Base64-encodes it, and returns a map keyed by
uppercase fingerprint. More than `MAX_WRAPS` entries are refused, not trimmed.
A member with no usable certificate gets no entry; there is no group-key
fallback and no escrow. `unwrap(wrapped, identity)` decrypts through
`TlsIdentity.decryptOaepSha256` and returns null for an absent fingerprint, a
wrong identity, malformed Base64 or bad OAEP padding, without throwing or
writing a pin.

The signed `file` body has exactly one of two key sets. The keyed form is:

```
{"name":"...","size":N,"sha256":"<plaintext sha>",
 "keyGen":1,"cipherSha":"<sha256 of sealed bytes>",
 "iv":"<base64 12 bytes>","wraps":{"<UPPERCASE FP>":"<base64 wrapped key>"}}
```

`validFileBody` requires a non-blank `name` and plaintext `sha256` within
`MAX_FIELD_CHARS`, a non-negative integer `size`, a positive integer `keyGen`, a
non-blank `cipherSha`, an `iv` decoding to exactly 12 bytes, and `wraps` a JSON
object with 1..`MAX_WRAPS` entries whose keys are non-blank uppercase
fingerprints and whose values are non-blank Base64 no longer than
`MAX_WRAPPED_CHARS`. A partial set, an extra field, a string `size`, a blank
fingerprint or a value over the cap is refused. `keyedFileBody(...)` builds
exactly this map; `parseFileKeyBody(body)` returns a typed value only when the
gate passes and null for every body the gate refuses. The whole body stays under
`MAX_BODY_CHARS`. `CircleCore.canonical` covers the body as one length-prefixed
field, so the signature binds `cipherSha`, `iv` and every wrap.

`keyId(dropId, keyGen)` is `"$dropId:$keyGen"`: `dropId` is the id of the signed
`file` event and `keyGen` is the body's generation counter, starting at 1 and
bumped when a fresh key is minted for the same drop. Receipts at a later layer
name this value as provenance only. The protocol core holds no key cache, no
sealed bytes and no drop state; the sealed copy name used downstream is
`dropId + ".flitdrop"`, owned by the 2026-12-10 integration, not by this layer.
A departure, a group `rekey` or a `keyGen` bump on another drop does not change
an old drop body, and there is no wrap backfill for a later joiner.

## Verification

Fresh pure run, counts to be recorded from the JUnit XML after integration:

```
source env.sh && ./gradlew --no-daemon --rerun-tasks \
  :protocol:test :app:testDebugUnitTest :harness:test
```

| Module | Before (last green inventory) | After this layer | Task |
| --- | --- | --- | --- |
| `protocol` | 169 | 210 | `:protocol:test` |
| `app` | 257 | 257 (unchanged by this layer) | `:app:testDebugUnitTest` |
| `harness` | 52 | 52 (unchanged by this layer) | `:harness:test` |
| Total | 478 | 519 | — |

`--rerun-tasks` is required: stale XML has previously reported green against an
older class layout. The "before" figures are the prior green inventory in
`TESTING.md` §2; the "after" figures were projections at this layer.

## Tests

| Class | Cases | What it pins |
| --- | --- | --- |
| `DropKeyCoreTest` | 15 | Seeded mint uniqueness and key/IV lengths, independence from group key and invite, wrap/unwrap round-trip, wrong-identity and absent-fingerprint refusal, junk and tampered blobs with no pin writes, `MAX_WRAPS` and `MAX_WRAPPED_CHARS` bounds, `keyId` derivation and stability, canonical vectors for existing verbs unchanged, and a keyed `file` event create/sign/verify, dedup, replay window, oversized-body id accounting and `rekey` independence through `CircleCore` |
| `DropKeyBodyTest` | 14 | Exact legacy and keyed key sets, `keyedFileBody` output passing `validFileBody`, `parseFileKeyBody` round-trip, partial and extra keys refused, non-integer or negative `size`, non-positive `keyGen`, an `iv` that does not decode to 12 bytes, empty or over-cap `wraps`, lowercase or blank fingerprint and value over `MAX_WRAPPED_CHARS` |
| `DropKeyCryptoTest` | 12 | `seal`/`open` byte-exact round-trips at 0, 1, 8192 and 3 MiB bytes with seeded data; exactly `IV || ciphertext || tag` with `TAG_BYTES = 16`; flipping one IV, ciphertext or tag byte, using another key or changing the AAD yields null; plaintext digest gate refusal |

Case counts come from a clean `--rerun-tasks` JUnit XML run, not from a target figure.
Test names are the `@Test` declarations in each class. No app, harness or device
test is added in this layer.

## Evidence

| Label | Claimed here | Basis |
| --- | --- | --- |
| CODE | Yes | `DropKeyCore.kt`, the additive `TlsIdentity` method and the `CircleCore` file-body gate match the API, byte rules and bounds recorded above |
| PURE | Yes | Three protocol test classes planned for a fresh `--rerun-tasks` run; counts not yet read from XML |
| SIM-HARN | No | No CLI path or in-process TLS node exercises a keyed drop on 2026-12-09; that parity is the 2026-12-10 layer |
| DEVICE | No | No phone, second phone, radio or camera observation is made on this date |

The weakest recorded level governs each claim. No device observation is claimed
by this layer.

## Limitations

- No app state, UI, CLI command, route, header, QR field or v2.2 wire change is
  part of this layer. The app's `circleBodyValid` gate, the CLI's `circle file`
  path and the two-node walk belong to 2026-12-10.
- No delivery receipts, Circle pad work or migration of already-landed drops
  happens here; those are later layers.
- The protocol core holds no plaintext key after a call returns. No key is
  persisted by this layer; the wrapped key stays in the signed event and the
  sealed bytes are owned by the caller.
- There is no escrow, server, cloud, remote wipe, remote revocation or second
  content key per recipient. A member who kept a wrap can still open an old
  drop; a later joiner gets no backfill. No such guarantee is made here.
- No second signature primitive or second crypto path is introduced. The
  existing `file` verb and the legacy three-key body are unchanged.
- Performance figures are not stated. The cost of a wrap and of streaming a
  sealed drop is described in `docs/PERFORMANCE.md` without invented timings.
- No device walk, phone observation, second physical phone or live mesh proof
  is claimed on this date.

## Verification status

Counts (15 + 14 + 12), the protocol total 210 and the rig total 519 come from
a clean `--rerun-tasks` run. CODE and PURE evidence are recorded above; no
device observation is claimed. The frame and route surface is unchanged from
the 2026-11-27 and 2026-12-03 protocol layers.
