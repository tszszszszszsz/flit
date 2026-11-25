# QR parcel frame codec

Defines the frame bytes, per-frame CRC, size caps and the verify/complete seam that let one small payload cross an air gap as a sequence of QR codes.

## Date and layer

Date: 2026-12-03. Layer: Protocol. Commit scope: QR parcel frame codec, pure JVM, tests.

## Depends on

- 2026-11-25 — the plan fixes the no-radio boundary and keeps the QR frame codec separate from pairing codes.
- 2026-11-27 — the courier envelope codec seals, signs, verifies and opens the payload. This layer frames its encoded bytes and calls those entry points; it adds no second crypto path.

## Scope

A new JVM-pure package `protocol/src/main/kotlin/app/flit/share/protocol/parcel/` with two files: `ParcelCodec.kt` (frame layout, CRC, digest, caps, parse/encode) and `ParcelReassembly.kt` (per-parcel assembly and bounded live set). It turns an opaque `ByteArray` plus a display name into an ordered `List<String>` of QR payload strings, and turns captured strings back into the same bytes.

Owned here: the wire string, the canonical bytes covered by the CRC, the whole-parcel digest, the `seq`/`count` sequencing, the frame and parcel size caps, duplicate and conflict handling, and an optional CLI-side assembly helper. Not owned here: QR rendering (stays in `app/src/main/kotlin/app/flit/share/Qr.kt`), camera and screenshot capture (stays in the app camera path and `flit-cli parse-qr`), CLI commands, app UI, file landing, encryption, receipts and any radio carrier. The protocol module gains no Android, zxing, socket or clock dependency; this layer deals only in `String` and `ByteArray`.

## Design

Frame wire string. One code carries one frame. The string is a `flit://parcel` URI with a query whose parameter order is not significant:

```
flit://parcel?v=<int>&id=<b64url(16)>&sz=<int>&i=<int>&n=<int>&total=<long>&name=<urlencoded>&sha=<b64url(32)>&data=<b64url(chunk)>&crc=<8 hex>
```

`b64url` is Base64 with the URL-safe alphabet (`A-Z a-z 0-9 - _`), no line breaks, padding optional. `name` uses the same `URLEncoder`/`URLDecoder` UTF-8 convention as `QrAdd.uri()` and may be blank. `crc` is eight lowercase hex digits. The scheme is distinct from `flit://add`/`flit://pair`: a pairing code never parses as a parcel and a parcel string never parses as a pairing code.

Canonical frame bytes. The CRC covers a domain-tagged, length-prefixed byte stream written with `DataOutputStream`, the same device as `CircleCore.canonical`. `field` writes a 4-byte big-endian byte length followed by the raw bytes; no delimiter can be smuggled through a field.

```
field("FLITPAR1")
field(id)               // 16 raw bytes
writeInt(version)       // 1
writeInt(sz)
writeInt(seq)           // 1-based frame index
writeInt(count)
writeLong(totalBytes)
field(nameUtf8)
field(sha)              // 32 raw bytes
field(chunk)
```

CRC32 is computed over those bytes and stored only in `crc`; it is excluded from its own input, like a signature is excluded from canonical bytes. `parse` rebuilds the canonical bytes from the parsed fields and compares, so a changed byte in any field — not just `data` — is caught.

Whole-parcel digest and id:

```
digest(name, payload) = SHA-256( field("FLITPARD1"), field(nameUtf8), field(payload) )
id                    = digest[0..15]
```

The display name is bound to the bytes. All frames of one parcel repeat the same `id` and full 32-byte `sha`, so no single lost frame removes the metadata needed to verify the result. Two sends of the same name and bytes regenerate the same frames.

Constants in `ParcelCodec`:

| Constant | Value | Meaning |
| --- | --- | --- |
| `SCHEME` | `"flit://parcel"` | Recognizable prefix; separate from pairing codes. |
| `MAGIC` | `"FLITPAR1"` | Frame canonical domain tag. |
| `DIGEST_TAG` | `"FLITPARD1"` | Whole-payload digest domain tag. |
| `VERSION` | `1` | Only accepted `v`; later versions are additive. |
| `ID_BYTES` | `16` | Parcel id, first 16 bytes of the digest. |
| `HASH_BYTES` | `32` | SHA-256 stored in `sha`. |
| `MAX_CHUNK_BYTES` | `512` | Largest raw `data` bytes in one frame; base64 stays scannable. |
| `MAX_FRAMES` | `64` | Largest `n`; `seq` space 1..64. Matches the app's `MAX_PARCEL_FRAMES = 64`, so the CLI cannot emit a sequence the app refuses to display. |
| `MAX_AIR_GAP_PARCEL_BYTES` | `131072` | Defensive frame-layer cap (128 KiB), named apart from `CourierEnvelope.MAX_PARCEL_BYTES` (8 MiB). With `MAX_CHUNK_BYTES = 512` and 64 frames the reachable maximum is 32768 bytes. |
| `MAX_NAME_CHARS` | `120` | Decoded UTF-8 name cap. |
| `MAX_URI_CHARS` | `1200` | Parse cap before any base64 decode. |
| `MAX_LIVE_PARCELS` | `8` | Bounded reassembly set; oldest touched assembly evicted. |

Functions:

- `fun digest(name: String, payload: ByteArray): ByteArray` — 32 bytes as above.
- `fun frames(id: ByteArray, name: String, payload: ByteArray, sz: Int = MAX_CHUNK_BYTES): List<String>` — explicit-id form used by tests; requires `id.size == ID_BYTES`, `sz` in `1..MAX_CHUNK_BYTES`, name and payload within caps. Emits `n = ceil(total / sz)` frames with `seq` values `1..n`.
- `fun frames(name: String, payload: ByteArray, sz: Int = MAX_CHUNK_BYTES): List<String>` — derives `id` from `digest`; the production entry point.
- `data class FrameDescriptor(val parcelId: String, val seq: Int, val count: Int, val sz: Int, val total: Int, val name: String, val sha: ByteArray, val chunk: ByteArray, val crc: Int)` — the parsed view. `parcelId` is the base64url id string, `seq` is the 1-based frame index and `count` is `n`; `seq == count` marks the final frame. Frame strings remain opaque `String` values to callers; neither app nor CLI edits a frame string.
- `fun parse(uri: String): FrameDescriptor?` — trims, refuses a URI over `MAX_URI_CHARS`, accepts the scheme case-insensitively, reads any parameter order, last duplicate key wins, unknown keys are ignored, and returns `null` on any failed check.
- `fun canonical(d: FrameDescriptor): ByteArray`, `fun crc(d: FrameDescriptor): Int` — the CRC seam shared by encoder and parser.
- `fun snapshot(frames: List<String>): ParcelSnapshot` — pure view over frame strings: `total` is the declared `count`, `seen` the distinct `(parcelId, seq)` pairs, `missing` the sorted absent `seq` values, `duplicate` the repeat count, `signerFp` and `declaredHash` the envelope signer and frame digest when available. It writes nothing.
- `fun complete(frames: List<String>, recipient: TlsIdentity): OpenedParcel?` — the verify/complete call. Parses every string, keeps one frame per `(parcelId, seq)`, requires one common `count` and every `seq` in `1..count`, checks the frame digest, then calls `CourierEnvelope.verify` and `open` with `recipient`. Returns `null` unless the opened parcel's recipient fingerprint equals `recipient.fingerprint`. `OpenedParcel` carries `name`, `bytes`, `sha256`, `signerFp` and `recipientFp`.

Parse checks, in order: scheme and length; required keys present (`v`, `id`, `sz`, `i`, `n`, `total`, `sha`, `data`, `crc`), `name` defaults to `""`; `v == VERSION`; decoded `id`/`sha` lengths; `sz` in `1..MAX_CHUNK_BYTES`; `count` in `1..MAX_FRAMES`; `total` in `1..MAX_AIR_GAP_PARCEL_BYTES`; `count == (total + sz - 1) / sz`; `seq` in `1..count`; decoded `name` within cap; decoded chunk length `== min(sz, total - (seq - 1) * sz)` and `== sz` for every non-final frame; `crc` matches. Any failure is `null`, never an exception from the caller's view.

Reassembly ownership. The app's `ParcelScanState` owns scan assembly: it stores opaque frame strings, reads `parcelId`/`seq`/`count` from `parse`, ignores repeats and foreign ids, and calls `complete(frames, recipient)` when the set is full. The codec holds no scan state for that path. The CLI on 2026-12-04 uses the same pure functions through an optional `ParcelReassembler(recipient, maxLive = MAX_LIVE_PARCELS)` helper in `ParcelReassembly.kt`: one frame string at a time, `add(uri): AddResult` with `Accepted | Duplicate | Conflict`, `snapshot()` and `complete()`, with the least recently touched id evicted past the cap. Both paths reach the same `parse`, CRC, digest, `verify` and `open`. No state is persisted and no clock is used.

App-owned constants are listed here so the boundary stays explicit: `PARCEL_FRAME_INTERVAL_MS = 400L`, `MAX_PARCEL_FRAMES = 64`, `PARCEL_FRAME_SIZE_PX = 240`, `PARCEL_PASTE_MAX_CHARS = 16_000`. The app display cap and this codec's wire cap are both 64 frames, so every sequence the CLI emits is one the app can display. The air-gap fixtures stay below both. None of the app constants changes a frame field, CRC or digest.

Envelope seam to the 2026-11-27 courier codec. The frame layer never forks crypto; it calls `CourierEnvelope.seal`, `encode`, `verify` and `open` in `app.flit.share.protocol.courier`. `fun encodeParcel(name: String, payload: ByteArray, identity: TlsIdentity, recipientCert: X509Certificate, sz: Int = MAX_CHUNK_BYTES): ParcelEncode` seals a `CourierParcel` to `recipientCert`, writes the envelope with `CourierEnvelope.encode`, frames those bytes with `frames(name, envelopeBytes, sz)`, and returns `ParcelEncode(frames, frameCount, signerFp = identity.fingerprint, sha256 = lowercase hex SHA-256 of payload)`. That `sha256` is the value compared by `sha256sum` in the device walk; the frame `sha` field is the digest of the framed envelope bytes. The CLI prints the signer fingerprint and payload hash from this result and never handles key material.

## Invariants

1. Every emitted string starts with `flit://parcel?` and carries each required key once; `name` may be blank, no other key may be blank or absent.
2. `parse` returns `null` for: wrong scheme, body over `MAX_URI_CHARS`, `v != 1`, bad base64url, a decoded id other than 16 bytes, a decoded sha other than 32 bytes, out-of-range `sz`/`i`/`n`/`total`/`name`, a `count` that disagrees with `total` and `sz`, a chunk length that disagrees with its `seq`, or a CRC mismatch.
3. `frames` emits exactly `n = ceil(total / sz)` frames with `seq` `1..n`; non-final chunks are `sz` bytes, the final chunk is `1..sz` bytes, and concatenating chunks by `seq` reproduces the framed bytes byte-for-byte.
4. CRC32 is taken over canonical bytes with `crc` excluded; flipping one byte in `id`, `sz`, `i`, `n`, `total`, `name`, `sha` or `data` makes `parse` return `null`.
5. `flit://add` and `flit://pair` strings never parse as parcels, and parcel strings carry no `key` parameter and no address fields.
6. `complete` accepts frames in any order, keeps one per `(parcelId, seq)`, refuses a frame whose metadata disagrees with the first-seen descriptor for that `parcelId`, and releases bytes only when every `seq` in `1..count` is present, the frame digest matches, `CourierEnvelope.verify` passes and `open` yields a parcel addressed to `recipient.fingerprint`.
7. The optional CLI reassembler holds at most `MAX_LIVE_PARCELS` ids and at most `MAX_AIR_GAP_PARCEL_BYTES` per id; adding a ninth id evicts the least recently touched assembly. The app path holds no codec-side scan state.
8. `protocol/` gains no Android import, zxing class, socket, file write or wall-clock read; the module stays JVM-pure and the test suite runs under `:protocol:test` with the existing JDK 21 toolchain and JUnit Platform setup.
9. A digest mismatch, a failed envelope signature or a recipient mismatch never returns an `OpenedParcel`; no partial file is written and held frames stay until the caller drops them.
10. `parse` returns a `FrameDescriptor` whose `parcelId`, `seq` and `count` are readable without decoding payload bytes; the app uses those three fields for progress and id gates and stores the original opaque strings.
11. The frame-layer `MAX_AIR_GAP_PARCEL_BYTES = 131072` cap is named apart from the envelope's `MAX_ENVELOPE_CHARS = 12 * 1024`; the app's `MAX_PARCEL_FRAMES = 64` and the wire `MAX_FRAMES = 64` are the same cap, so no emitted sequence exceeds what the app will display. Air-gap fixtures stay inside both.

## Failure modes

| Condition | Conclusion | Keeps bytes | Drops | Evidence |
| --- | --- | --- | --- | --- |
| Truncated or skewed capture decodes to junk | `parse` returns `null`; nothing is routed | No frames are added | The bad string | PURE (mutated string); camera not verified until 2026-12-08 |
| Single-bit change in any canonical field | CRC mismatch, `parse` returns `null` | Frames already accepted for that `parcelId` | The corrupted frame | PURE (bit-flip per field) |
| One frame of a sequence never scans | `complete` returns `null`; `missing` names the `seq` | Every accepted frame of that `parcelId` | Nothing | PURE (missing-`seq` case) |
| Same frame scanned twice | `Duplicate`, no state change | First copy of the chunk | The repeat | PURE |
| Same `parcelId` with different `count`/`total`/`sha`/`name`, or different bytes at one `seq` | `Conflict`; first descriptor stays authoritative | First-seen frames | The conflicting frame | PURE |
| All `seq` present but whole digest differs | `complete` returns `null`; no bytes released | Complete chunk set held by the caller | Nothing | PURE (re-CRC'd bad chunk) |
| Envelope signature fails or opened parcel names another recipient | `complete` returns `null`; no output written | Frames held by the caller | Opened bytes | PURE (wrong identity, forged `sig`) |
| Name, payload, chunk or URI over cap | Encode refuses or `parse` returns `null` | Nothing for that frame | The oversize input | CODE + PURE |
| A ninth live `parcelId` arrives in the CLI helper | Oldest touched assembly evicted | Newest eight assemblies | Oldest assembly | PURE |
| A pairing code is fed to the parser | `null`; no cross-protocol state | Nothing | The pairing string | PURE |

## Tests

Pure JVM tests first, in `protocol/src/test/kotlin/app/flit/share/protocol/`, using `kotlin.test` and the JUnit Platform already configured in `protocol/build.gradle.kts`:

- `ParcelCodecTest` — single-frame round-trip; multi-frame round-trip with scrambled order; non-final chunks exactly `sz` bytes and final remainder; `parse` yields `parcelId`/`seq`/`count` with `seq == count` on the final frame; `snapshot` reports exact missing and duplicate counts; CRC detects a single-bit flip in each canonical field; tolerant parameter order, duplicate key last-wins and ignored unknown key; `flit://add` literal refused; blank name and arbitrary binary payload including `0x00` round-trip; every cap and bad-base64/bad-sha-length case returns `null`.
- `ParcelReassemblyTest` — `complete` with in-order, out-of-order, duplicate, missing, mixed-`parcelId` and count-mismatched frame sets; a tampered envelope signature and a wrong recipient identity both return `null`; `encodeParcel`/`complete` round-trip returns the exact payload, payload `sha256`, `signerFp` and `recipientFp`; the optional CLI helper evicts the least recently touched id at `MAX_LIVE_PARCELS` and `forget`/`clear` release state. No clock or file access is needed.

The completion note records the exact new `@Test` count and the resulting protocol total; the recorded baseline before this day is 82 protocol tests (`docs/TESTING.md`). Verification command: `source env.sh && ./gradlew --no-daemon --rerun-tasks :protocol:test`. `--rerun-tasks` is required because stale XML has previously reported green against an older class layout.

Harness tests: none in this layer. `flit-cli parse-qr` is the capture seam used by the CLI encode/decode layer on 2026-12-04; no harness code or test is added on 2026-12-03.

Device walk: none in this layer. Camera rendering and scanning belong to 2026-12-07 and the air-gap walk to 2026-12-08. Real-camera decode is **not verified** by this commit.

## Evidence for this day

A completion note under `docs/completions/` records the two new protocol files and package, the exact per-class pure test counts and the new protocol total, the verification command, and the CRC/digest/cap constants held by the tests. Evidence labels are CODE for the frame layout and constants and PURE for the round-trip, CRC, bounds and eviction cases. No SIM-HARN or DEVICE claim is made; camera scanning stays marked not verified until the later air-gap walk.

## Out of scope

QR rendering and zxing, camera and screenshot intake, CLI `parcel` commands, app display and progress states, file landing, the courier envelope itself, content encryption and per-drop keys (2026-12-09), delivery receipts (2026-12-11), Circle pad (2026-12-15), any radio carrier, relay or Bluetooth handoff, v2.2 wire changes, and offline `.flitpack` bundles. The codec does not assign names to landed files, does not persist partial parcels and does not decide trust.

## Commit

2026-12-03 Protocol: QR parcel frame codec and bounded reassembly, pure JVM tests (CODE/PURE).
