# 2026-12-03 — QR parcel codec (Protocol)

Layer: 2026-12-03 Protocol. The work is a JVM-pure frame codec in
`app.flit.share.protocol.parcel`: one opaque UTF-8 string per QR frame, a
per-frame CRC32, size caps, and a parse/encode/complete seam that turns a
sequence of frame strings back into a signed, recipient-addressed payload. The
payload crypto stays in `CourierEnvelope`; this layer adds no second crypto
path.

Counts are read from the current JUnit XML under each module's
`build/test-results` directory. At this layer the protocol module stood at 158
cases and the rig at 436; those are historical figures for the 2026-12-03
layer. The final inventory is 330 protocol + 309 app + 75 harness = 714 cases
with zero failures, errors or skips, recorded in `docs/TESTING.md`. No device
evidence is claimed here; real-camera decode stays with the 2026-12-08 walk.
The layer is dated 2026-12-03.

## Files in the layer

| File | Content |
| --- | --- |
| `protocol/src/main/kotlin/app/flit/share/protocol/parcel/ParcelCodec.kt` | `object ParcelCodec`: frame grammar, CRC32, caps, `parse`, `encodeParcel`, `complete` |
| `protocol/src/test/kotlin/app/flit/share/protocol/ParcelCodecTest.kt` | Pure round-trip, descriptor, cap and bound tests |
| `protocol/src/test/kotlin/app/flit/share/protocol/ParcelCodecTamperTest.kt` | Pure CRC, digest, sequence, count and recipient refusal tests |
| `docs/TESTING.md` | Final test inventory and evidence levels |
| `docs/CONTRACTS.md` | Frame grammar |
| `docs/SECURITY.md` | Air-gap trust and frame-layer limits |
| `docs/FAILURE-MODES.md` | Frame loss, CRC mismatch, wrong recipient |
| `docs/OPERATIONS.md` | Manual carry procedure |
| `docs/PERFORMANCE.md` | Frame count and byte caps, no invented timings |
| `docs/ARCHITECTURE.md` | Parcel seam |
| `docs/completions/2026-12-03-qr-parcel-codec.md` | Layer record for 2026-12-03 |
| `docs/roadmap/qr-parcel-codec.md` | API aligned with the seams recorded below |
| `docs/roadmap/qr-parcel-cli.md` | CLI aligned to the codec caps |
| `docs/roadmap/qr-parcel-app.md` | App aligned to the codec caps |
| `docs/roadmap/qr-parcel-device-walk.md` | Walk aligned to the caps; no walk claimed here |
| `docs/roadmap/01-schedule.md`, `docs/roadmap/02-waves.md` | Build-ahead note for the 2026-12-03 layer |

The package imports `CourierEnvelope`, `TlsIdentity` and
`java.security.cert.X509Certificate` from the protocol module and the JCA
primitives used by CRC32 and SHA-256. No Android import, zxing class, socket,
file write or clock read is part of this layer.

## Public surface

The exact API held by the corrected seams:

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

`parse` reads the descriptor fields without decoding the carried bytes, so a
caller can show progress and gate on `parcelId` before a parcel is complete.
`encodeParcel` calls `CourierEnvelope.seal` with the caller-supplied
`recipientCert` bound to `recipientFp` and signs with `identity` under
`signerFp`, so the frame layer and the courier layer share one crypto path.
`complete` is the verify/complete call: it checks the frame strings, CRC,
sequence, count, whole-parcel digest and recipient, then opens the payload
through `CourierEnvelope.open` with the caller-supplied recipient `identity`
and checks the opened `recipientFp` against the `recipientFp` argument. It
returns `null` on any refusal, so no partial bytes are released.

Neither call resolves a key from process state. The recipient certificate and
the opening identity are arguments of the call, so the codec holds no in-memory
identity registry, writes no pin of its own and makes no assumption that the
sender and the recipient are the same device.

## Frame grammar

One QR code carries one frame. A frame is a single opaque UTF-8 `String` that
starts with the frame magic and carries the parcel id, a 1-based sequence
index, the frame count, the total byte count, a chunk of the carried bytes and
the CRC32 for that frame. The CRC is computed over the frame fields with the
CRC field excluded from its own input, so a change to any covered field is
detected. `seq == count` marks the final frame. Frames remain opaque to callers:
the CLI and app render and capture the same strings and never edit or reorder
them.

QR rendering is not in this layer. The CLI and app render frame strings at EC
level M with a margin of 2 modules, so both sides produce and read the same
bytes; the protocol module deals only in `String` and `ByteArray`.

The exact encoding of individual fields inside the frame string is held by
`ParcelCodec.kt` and documented in `docs/CONTRACTS.md`; this record covers the
semantic fields and constants only.

## Constants

| Constant | Value | Basis |
| --- | --- | --- |
| `MAX_FRAMES` | `64` | Defensive wire cap and app display cap, so every emitted sequence is one the app will display |
| `MAX_AIR_GAP_PARCEL_BYTES` | `131072` | Defensive wire cap on framed bytes, named apart from `CourierEnvelope.MAX_ENVELOPE_CHARS` |
| `MAX_CHUNK_BYTES` | `512` | Largest raw data chunk in one frame |
| `FRAME_MAGIC` | `"FLITPARCEL1"` | Frame magic / domain tag |

The effective ceiling is the courier envelope's Base64 cap
(`MAX_ENVELOPE_CHARS = 12 * 1024`): the sealed envelope is at most 12288
characters, which at 512 bytes per chunk is about 24 frames and, with the
signature, wrapped key and certificate included, roughly 5 KiB of payload. The
64-frame and 131072-byte wire caps are defensive bounds on malformed input, not
reachable sizes; neither the wire caps nor the envelope cap is restated in the
CLI or app.

## Verification

Counts are read from the current JUnit XML under
`protocol/build/test-results/test/`, `app/build/test-results/testDebugUnitTest/`
and `harness/build/test-results/test/`.

| Module | At this layer (historical) | Current XML |
| --- | --- | --- |
| `protocol` | 158 | 330 |
| `app` | 228 (unchanged by this layer) | 309 |
| `harness` | 32 (unchanged by this layer) | 75 |
| Total | 436 | 714 |

The 138 → 158 protocol and 416 → 436 rig figures describe the state at the
2026-12-03 layer and are historical for that layer. `docs/TESTING.md` holds the
final inventory: 330 + 309 + 75 = 714 cases, zero failures, zero errors and
zero skipped. Stale XML has previously reported green against an older class
layout, so the counts are taken from the current result files.

## Tests

| Class | Cases | What it pins |
| --- | --- | --- |
| `ParcelCodecTest` | 21 | Single- and multi-frame round-trip, descriptor fields with `seq == count` on the final frame, chunks at `MAX_CHUNK_BYTES`, `parse` bounds and caps, and `complete` accepting a full verified frame set |
| `ParcelCodecTamperTest` | 10 | CRC mismatch after a change to a covered field, digest mismatch, missing and conflicting frames, wrong recipient, and refusal returning `null` with no bytes released |

Case counts are read from the current JUnit XML. The 14 and 6 cases recorded at
this layer are historical for the layer; the current result files carry 21 and
10. Test names follow the `@Test` declarations in each class. No harness or app
test is added in this layer; `docs/TESTING.md` holds the final inventory.

## Evidence

| Label | Claimed here | Basis |
| --- | --- | --- |
| CODE | Yes | `ParcelCodec.kt` matches the corrected API, constants and frame grammar |
| PURE | Yes | 21 + 10 cases from the current protocol JUnit XML, zero failures |
| SIM-HARN | No | CLI encode/decode is the 2026-12-04 layer |
| DEVICE | No | No device walk at this layer; camera and air-gap walks belong to later dates |

No radio, phone, camera or QR observation is claimed on this date. Real-camera
decode is not verified by this layer.

## Limitations

- The protocol module has no Android, zxing, socket, file write or wall-clock
  dependency. It produces and consumes strings; rendering, camera intake and
  file landing stay in the CLI and app.
- No CLI command, app surface, route, DTO or v2.2 wire change is part of this
  layer. No parcel progress is persisted.
- The payload crypto is the courier envelope's; this layer does not sign,
  encrypt, re-sign or rewrite it independently.
- No self-addressed delivery and no in-memory identity registry: `encodeParcel`
  takes the recipient certificate and `complete` takes the recipient identity as
  arguments. The caller owns any pinning or certificate lookup; the codec does
  not infer a recipient from its own identity.
- No device walk, second-phone observation or camera decode is claimed here.
- Per-drop content keys, delivery receipts, Circle pad, any radio carrier and
  offline `.flitpack` bundles stay out of scope.

## Verification status

Counts and evidence labels above are read from the current JUnit XML; the
per-layer figures are historical for the 2026-12-03 layer and
`docs/TESTING.md` holds the final inventory of 330 + 309 + 75 = 714 cases with
zero failures. The frame string layout is in `ParcelCodec.kt` and
`docs/CONTRACTS.md` §16. The DEVICE row is not claimed.
