# QR parcel frame codec

Defines the frame bytes, per-frame CRC, size caps and the verify/complete seam that let one small payload cross an air gap as a sequence of QR codes.

## Date and layer

Date: 2026-12-03. Layer: Protocol. Commit scope: QR parcel frame codec, pure JVM, tests.

## Depends on

- 2026-11-25 — the plan fixes the no-radio boundary and keeps the QR frame codec separate from pairing codes.
- 2026-11-27 — the courier envelope codec seals, signs, verifies and opens the payload. This layer frames its encoded bytes and calls those entry points; it adds no second crypto path.

## Scope

A new JVM-pure package `app.flit.share.protocol.parcel` with one file, `ParcelCodec.kt`. It turns a payload, a caller-supplied parcel id and recipient fingerprint, the recipient certificate and the sender identity into an ordered `List<String>` of opaque QR frame strings, and turns captured strings back into the same payload when the caller supplies the recipient identity.

Owned here: the wire string, the canonical bytes covered by the CRC, the whole-parcel digest, the `seq`/`count` sequencing, the frame and parcel size caps, and the parse/encode/complete entry points. Not owned here: QR rendering (stays in `app/src/main/kotlin/app/flit/share/Qr.kt`), camera and screenshot capture (stays in the app camera path and `flit-cli parse-qr`), CLI commands, app UI, file landing, encryption, receipts and any radio carrier. The protocol module gains no Android, zxing, socket or clock dependency; this layer deals only in `String` and `ByteArray`. It has no camera, no Android, no socket and no persistence.

## Design

Frame wire string. One code carries one frame. A frame is a single opaque UTF-8 `String`; callers never edit it. It starts with the domain tag `FLITPARCEL1` and carries a fixed, length-prefixed set of fields: the parcel id, the 1-based `seq`, the `count`, the total framed byte count, the chunk bytes and a CRC32. There is no URI scheme and no query-parameter list; a pairing code never parses as a parcel and a parcel string never parses as a pairing code. The CLI and app render the same string bytes at error-correction level M with a two-module quiet zone and UTF-8.

Canonical frame bytes. The CRC covers a domain-tagged, length-prefixed byte stream written with `DataOutputStream`, the same device as `CircleCore.canonical`: a 4-byte big-endian byte length followed by the raw bytes, so no delimiter can be smuggled through a field. CRC32 is stored in the frame and excluded from its own input, like a signature is excluded from canonical bytes. `parse` rebuilds the canonical bytes and compares, so a change to any field — not just the chunk — is caught.

Whole-parcel digest and id. The `parcelId` is supplied by the caller to `encodeParcel` and repeated in every frame, so no single lost frame removes the identity metadata. `seq` values run `1..count` and `count` is the frame count. The payload hash returned by `complete` is the lowercase hex SHA-256 of the opened payload bytes; `complete` checks the declared digest before it decrypts. Two sends of the same parcel id and bytes regenerate the same frames.

Constants in `ParcelCodec`:

| Constant | Value | Meaning |
| --- | --- | --- |
| `FRAME_MAGIC` | `"FLITPARCEL1"` | Domain tag at the head of every frame and the CRC domain tag. |
| `MAX_CHUNK_BYTES` | `512` | Largest raw chunk bytes in one frame; the UTF-8 string stays scannable. |
| `MAX_FRAMES` | `64` | Defensive wire cap on `count`; `seq` space `1..64`. Matches the app's `MAX_PARCEL_FRAMES = 64`, so the CLI cannot emit a sequence the app refuses to display. |
| `MAX_AIR_GAP_PARCEL_BYTES` | `131072` | Defensive wire cap on framed bytes (128 KiB), named apart from `CourierEnvelope.MAX_ENVELOPE_CHARS` (`12 * 1024`); it bounds malformed input, not a reachable size. |

The effective ceiling is set by `CourierEnvelope.MAX_ENVELOPE_CHARS = 12 * 1024`:
the sealed envelope is at most 12288 Base64 characters, which at 512 bytes per
chunk is about 24 frames and, with the signature, wrapped key and certificate
included, roughly 5 KiB of payload. The 64-frame and 128 KiB wire caps above are
defensive bounds on malformed input, not reachable sizes.

API in `ParcelCodec.kt`:

```
object ParcelCodec {
  data class FrameDescriptor(val parcelId: String, val seq: Int, val count: Int,
                             val totalBytes: Int, val crc: Long)
  fun parse(frame: String): FrameDescriptor?
  fun encodeParcel(payload: ByteArray, parcelId: String, recipientFp: String,
                   recipientCert: X509Certificate, signerFp: String,
                   identity: TlsIdentity): List<String>
  fun complete(frames: List<String>, recipientFp: String,
               identity: TlsIdentity): ParcelPayload?
  data class ParcelPayload(val parcelId: String, val recipientFp: String,
                           val signerFp: String, val sha256: String,
                           val bytes: ByteArray)
}
```

`parse` returns a descriptor with `parcelId`, `seq`, `count`, `totalBytes` and the frame `crc` without decoding payload bytes; the app uses `parcelId`, `seq` and `count` for progress and id gates and stores the original opaque strings. `encodeParcel` uses `CourierEnvelope.seal` for the signed, recipient-addressed payload so there is one crypto path: `recipientCert` is the recipient's X.509 certificate, `identity` is the sender's signing identity, and `signerFp` must match `identity.fingerprint`. The encoded bytes are then framed in order. `complete` verifies frames, CRC, sequence, count and digest, then decrypts through `CourierEnvelope.open` with the caller-supplied recipient `identity`; it requires `identity.fingerprint` to equal `recipientFp` and returns a `ParcelPayload` only when every check passes and the opened parcel is addressed to `recipientFp`. Neither entry point consults a process-local identity registry, and neither assumes the caller is the recipient implicitly — the recipient identity is an explicit argument to `complete`.

Parse checks, in order: scheme/magic and length; the required fixed fields present; `FRAME_MAGIC`; `seq` and `count` in `1..MAX_FRAMES`; `totalBytes` in `1..MAX_AIR_GAP_PARCEL_BYTES`; `count == ceil(totalBytes / chunk size)`; chunk length `== min(MAX_CHUNK_BYTES, totalBytes - (seq - 1) * MAX_CHUNK_BYTES)` and `== MAX_CHUNK_BYTES` for every non-final frame; CRC match. Any failure is `null`, never an exception from the caller's view.

State ownership. The codec holds no scan state and no identity registry. The app's `ParcelScanState` stores opaque frame strings, reads `parcelId`/`seq`/`count` from `parse`, ignores repeats and foreign ids, and calls `complete(frames, recipientFp, identity)` with the recipient identity it holds when the set is full. The CLI on 2026-12-04 folds numbered frame strings through the same `parse`/`complete` seam and passes its own `identity` as the recipient identity. Both paths reach the same CRC, digest, `CourierEnvelope.verify` and `open`. No state is persisted and no clock is read in this layer.

App-owned constants are listed here so the boundary stays explicit: `PARCEL_FRAME_INTERVAL_MS = 400L`, `MAX_PARCEL_FRAMES = 64`, `PARCEL_FRAME_SIZE_PX = 240`, `PARCEL_PASTE_MAX_CHARS = 16_000`. The app display cap and this codec's `MAX_FRAMES` are both 64, so every sequence the CLI emits is one the app can display. The air-gap fixtures stay below both. None of the app constants changes a frame field, CRC or digest.

Envelope seam to the 2026-11-27 courier codec. The frame layer never forks crypto; it calls `CourierEnvelope.seal`, `verify` and `open` in `app.flit.share.protocol.courier`. `encodeParcel` seals the payload to `recipientFp` using `recipientCert` — refusing a certificate whose own fingerprint differs from `recipientFp` — signs with the sender's `identity` as `signerFp`, frames those bytes, and returns the ordered strings; `complete` opens with the caller-supplied recipient `identity`, after requiring its fingerprint to equal `recipientFp`. No identity is looked up from a process-local registry and no self-addressed default is inferred from the caller. The frame CRC and digest cover the framed envelope bytes; the `sha256` carried in `ParcelPayload` is the payload hash compared by `sha256sum` in the device walk. The CLI prints the signer fingerprint and payload hash from the result; the caller owns key material and hands it to the codec only through `identity`.

## Invariants

1. Every emitted string is an opaque UTF-8 frame beginning with `FRAME_MAGIC` and carrying each required field once; no caller edits a frame string.
2. `parse` returns `null` for: a malformed or oversized string, a missing `FRAME_MAGIC`, an out-of-range `seq`/`count`/`totalBytes`, a `count` that disagrees with `totalBytes` and the chunk size, a chunk length that disagrees with its `seq`, or a CRC mismatch.
3. `encodeParcel` emits exactly `count = ceil(totalBytes / chunk)` frames with `seq` `1..count`; non-final chunks are `MAX_CHUNK_BYTES` bytes, the final chunk is `1..MAX_CHUNK_BYTES` bytes, and concatenating chunks by `seq` reproduces the framed bytes byte-for-byte.
4. CRC32 is taken over canonical bytes with the stored CRC excluded; flipping one byte in any canonical field makes `parse` return `null`.
5. A pairing code never parses as a parcel, and a parcel string carries no pairing fields.
6. `complete(frames, recipientFp, identity)` accepts frames in any order, keeps one per `(parcelId, seq)`, refuses metadata that disagrees with the first-seen frame for that `parcelId`, and releases a `ParcelPayload` only when every `seq` in `1..count` is present, the CRC and digest match, `identity.fingerprint` equals `recipientFp`, `CourierEnvelope.verify` passes and `open` with the caller-supplied `identity` yields a parcel addressed to `recipientFp`. No identity is resolved from a registry.
7. Frame count never exceeds `MAX_FRAMES` and framed bytes never exceed `MAX_AIR_GAP_PARCEL_BYTES`; the app's `MAX_PARCEL_FRAMES = 64` is the same cap, so no emitted sequence exceeds what the app will display. Air-gap fixtures stay inside both.
8. `protocol/` gains no Android import, zxing class, socket, file write or wall-clock read; the module stays JVM-pure and the test suite runs under `:protocol:test` with the existing JDK 21 toolchain and JUnit Platform setup.
9. A digest mismatch, a failed envelope signature or a recipient mismatch never returns a `ParcelPayload`; no partial file is written and held frames stay until the caller drops them.
10. `parse` returns a `FrameDescriptor` whose `parcelId`, `seq` and `count` are readable without decoding payload bytes; the app uses those three fields for progress and id gates and stores the original opaque strings.
11. The frame-layer `MAX_AIR_GAP_PARCEL_BYTES = 131072` cap is named apart from the envelope's `MAX_ENVELOPE_CHARS = 12 * 1024`; the app's `MAX_PARCEL_FRAMES = 64` and the wire `MAX_FRAMES = 64` are the same cap.

## Failure modes

| Condition | Conclusion | Keeps bytes | Drops | Evidence |
| --- | --- | --- | --- | --- |
| Truncated or skewed capture decodes to junk | `parse` returns `null`; nothing is routed | No frames are added | The bad string | PURE (mutated string); camera not verified until 2026-12-08 |
| Single-bit change in any canonical field | CRC mismatch, `parse` returns `null` | Frames already accepted for that `parcelId` | The corrupted frame | PURE (bit-flip per field) |
| One frame of a sequence never scans | `complete` returns `null` | Every accepted frame of that `parcelId` | Nothing | PURE (missing-`seq` case) |
| Same frame scanned twice | Repeat ignored, no state change | First copy of the chunk | The repeat | PURE |
| Same `parcelId` with different `count`/`totalBytes` or different bytes at one `seq` | Conflict; first descriptor stays authoritative | First-seen frames | The conflicting frame | PURE |
| All `seq` present but whole digest differs | `complete` returns `null`; no bytes released | Complete chunk set held by the caller | Nothing | PURE (re-CRC'd bad chunk) |
| Envelope signature fails, opened parcel names another recipient, or `identity.fingerprint` differs from `recipientFp` | `complete` returns `null`; no output written | Frames held by the caller | Opened bytes | PURE (wrong identity, forged `sig`) |
| `recipientCert` fingerprint differs from `recipientFp` at encode | `encodeParcel` refuses before framing | Source payload | Nothing written | PURE |
| Payload, chunk or frame over cap | Encode refuses or `parse` returns `null` | Nothing for that frame | The oversize input | CODE + PURE |
| A pairing code is fed to the parser | `null`; no cross-protocol state | Nothing | The pairing string | PURE |

## Tests

Pure JVM tests first, in `protocol/src/test/kotlin/app/flit/share/protocol/`, using `kotlin.test` and the JUnit Platform already configured in `protocol/build.gradle.kts`:

- `ParcelCodecTest` — single-frame round-trip; multi-frame round-trip with scrambled order; non-final chunks exactly `MAX_CHUNK_BYTES` bytes and final remainder; `parse` yields `parcelId`/`seq`/`count`/`totalBytes`/`crc` with `seq == count` on the final frame; CRC detects a single-bit flip in each canonical field; a pairing-code literal is refused; arbitrary binary payload including `0x00` round-trips; every cap and malformed-frame case returns `null`.
- `ParcelCodecTamperTest` — `complete` with in-order, out-of-order, duplicate, missing, mixed-`parcelId` and count-mismatched frame sets; a tampered envelope signature, a wrong recipient fingerprint and a recipient `identity` whose fingerprint differs from `recipientFp` all return `null`; `encodeParcel` refuses a `recipientCert` that does not match `recipientFp`; `encodeParcel`/`complete` round-trip returns the exact payload, payload `sha256`, `signerFp` and `recipientFp`. No clock or file access is needed.

The completion note records the exact new `@Test` count and the resulting protocol total; the recorded baseline before this day is 136 protocol tests (`docs/TESTING.md`), with the rig at that layer at 714 = 330 protocol + 309 app + 75 harness (historical); the current inventory is 972 = 443 protocol + 167 core + 254 app + 108 harness; the 396 inventory is historical. Verification command: `source env.sh && ./gradlew --no-daemon --rerun-tasks :protocol:test`. `--rerun-tasks` is required because stale XML has previously reported green against an older class layout.

Harness tests: none in this layer. `flit-cli parse-qr` is the capture seam used by the CLI encode/decode layer on 2026-12-04; no harness code or test is added on 2026-12-03.

Device walk: none in this layer. Camera rendering and scanning belong to 2026-12-07 and the air-gap walk to 2026-12-08. Real-camera decode is **not verified** by this commit.

## Evidence for this day

A completion note under `docs/completions/` records the new package and `ParcelCodec.kt`, the exact per-class pure test counts and the new protocol total, the verification command, and the CRC/digest/cap constants held by the tests. Evidence labels are CODE for the frame layout and constants and PURE for the round-trip, CRC, bounds and tamper cases. No SIM-HARN or DEVICE claim is made; camera scanning stays marked not verified until the later air-gap walk.

## Out of scope

QR rendering and zxing, camera and screenshot intake, CLI `parcel` commands, app display and progress states, file landing, the courier envelope itself, content encryption and per-drop keys (2026-12-09), delivery receipts (2026-12-11), Circle pad (2026-12-15), any radio carrier, relay or Bluetooth handoff, v2.2 wire changes, and offline `.flitpack` bundles. The codec does not assign names to landed files, does not persist partial parcels and does not decide trust.

## Commit

2026-12-03 Protocol: QR parcel frame codec and bounded reassembly, pure JVM tests (CODE/PURE).
