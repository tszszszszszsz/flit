# QR parcel display and camera decode

The app shows a signed air-gap parcel as a sequence of QR frames and folds frames captured by the camera back into one verified payload.

## Date and layer

2026-12-07, App. The work covers the on-screen frame sequence, the camera and paste intake, the progress states around both, and JVM tests of the app-side seams.

## Depends on

- 2026-11-27 Protocol — courier envelope and carry core, for the signed payload and per-recipient wrap a parcel carries.
- 2026-12-01 App — courier sync and file-card states, for the inbound path a verified parcel joins.
- 2026-12-03 Protocol — QR parcel frame codec, for the frame text grammar and the `parse`/`complete` entry points.
- 2026-12-04 CLI — parcel encode and decode, for the same frame round-trip driven from the box.

## Scope

A parcel is a small signed payload that travels as an ordered list of QR frame strings with no radio link. The sender's screen cycles those frames; the receiver's camera reads them and the app puts the frames back in order until the codec can verify the whole payload. This piece adds only the display and intake surfaces, the app-side bookkeeping between a scanned frame and the codec, and the injected write of verified bytes into the existing received bucket. No Circle or transfer behavior changes. The frame grammar, the signature, the payload wrap and the frame caps (`MAX_FRAMES`, `MAX_AIR_GAP_PARCEL_BYTES`, `MAX_CHUNK_BYTES`) belong to the protocol layer of 2026-12-03 and are consumed here unchanged; the app restates none of them.

## Design

Three additive files land under `app/src/main/kotlin/app/flit/share/`; the protocol, wire and engine state are unchanged:

- `ParcelUi.kt` — the parcel screen with two modes, `ParcelShowCard` and `ParcelScanCard`, plus a private camera view that follows the `CameraQrView` analyzer shape in `QrUi.kt:265-314`.
- `ParcelScanState.kt` — an Android-free holder with one small pure reducer; `add` accepts one opaque frame string at a time, progress reads from `seq`/`count`, and `complete` closes through the codec.
- `MainActivity.kt` — an additive entry point to the screen and the injected `(ByteArray) -> Unit` writer that lands verified bytes in the app's received bucket.

`ParcelShowCard` takes the immutable ordered `List<String>` returned by `ParcelCodec.encodeParcel(payload, parcelId, recipientFp, recipientCert, signerFp, identity)` and renders each string with `Qr.encode(frame, PARCEL_FRAME_SIZE_PX)`, which already fixes EC level `M`, UTF-8 and margin `2` in `Qr.kt:143-150`. The recipient certificate is supplied out of band, as in the CLI's `--cert`; no frame carries a file name or a certificate. The card advances one frame every `PARCEL_FRAME_INTERVAL_MS = 400L`, wraps from the last frame to index zero, and offers next/back controls.

`ParcelScanCard` feeds each decoded or pasted string to `ParcelScanState.add`, shows progress and, on a verified result, the signer fingerprint, SHA-256 and byte count before writing `ParcelPayload.bytes` through the injected `(ByteArray) -> Unit` callback. `MainActivity` supplies that writer and saves under a locally chosen name through the existing received-file path (`FlitEngine.publishReceived`); the save name never travels in a frame. Reassembly closes with `ParcelCodec.complete(frames, recipientFp, identity): ParcelPayload?`, where `identity` is the receiving app's `TlsIdentity`: the recipient fingerprint gates the parcel and the identity supplies the private key the codec uses for `CourierEnvelope.open`. The camera path reuses the existing `Manifest.permission.CAMERA` ask, `LifecycleCameraController`, the ~8 fps throttle, the Y-plane copy with row/pixel strides and `Qr.decodeYPlane`. A denied camera leaves a paste field, capped by `PARCEL_PASTE_MAX_CHARS`, that feeds the same state holder.

Frame text is opaque to the UI. The codec exposes `ParcelCodec.parse(frame): FrameDescriptor?`; the descriptor carries `parcelId`, `seq`, `count`, `totalBytes` and `crc`, and the app reads only `parcelId`, `seq` and `count` for gating and progress. The original opaque frame strings are stored; payload bytes, CRC and magic are never interpreted or rewritten by the UI. Assembly closes through `ParcelCodec.complete(frames, recipientFp, identity): ParcelPayload?`, passing the app's own `TlsIdentity` beside the recipient fingerprint so the codec can open the envelope; it folds CRC, sequence, count, digest, envelope signature and recipient check into one call, so there is no separate verify entry point. A `ParcelPayload` carries `parcelId`, `recipientFp`, `signerFp`, `sha256` and `bytes`. Progress comes from the descriptor's `seq`/`count`; the scan card shows it as `frame i of n`, with `n` the first accepted frame's `count` and `?` before any frame is accepted.

### Constants owned here

| Constant | Value | Meaning |
| --- | --- | --- |
| `PARCEL_FRAME_INTERVAL_MS` | `400L` | Dwell per frame on the sender's screen before advancing and wrapping. |
| `MAX_PARCEL_FRAMES` | `64` | App display/assembly cap, equal to `ParcelCodec.MAX_FRAMES`; the state holder reads the codec cap rather than defining a second limit. |
| `PARCEL_FRAME_SIZE_PX` | `240` | `Qr.encode` side for a rendered parcel frame. |
| `PARCEL_PASTE_MAX_CHARS` | `16_000` | Cap on newline-separated frame text pasted into the scan field when camera permission is denied; a UI input cap, not a codec cap. |

The frame grammar and its caps are owned by the 2026-12-03 codec and are not redefined here. `ParcelScanState` reads `MAX_FRAMES = 64`, `MAX_AIR_GAP_PARCEL_BYTES = 131072` and `MAX_CHUNK_BYTES = 512` from `ParcelCodec`; `MAX_PARCEL_FRAMES` above names the same 64 for the display and is not a second grammar cap, so the app cannot be handed a sequence it refuses to display. With 512-byte chunks and 64 frames the largest reachable payload is 32 KiB, inside the codec's 128 KiB defensive cap. `FRAME_MAGIC = "FLITPARCEL1"` and per-frame CRC32 stay inside the codec; the app never reads or rewrites them.

### State ownership

- Display index lives in a Compose `remember` inside `ParcelShowCard`; it is discarded on close and is never written to `FlitCircleStore` or prefs.
- Intake frames live in `ParcelScanState`, created per scan dialog and dropped on close or completion. A partial parcel is not persisted: a missed frame means starting the scan again. Only a verified payload becomes durable, through the injected writer `MainActivity` supplies to the existing received-file path.
- The camera analyzer and its throttle are local to the dialog, matching `QrScanCard`; no engine field is added.

## Invariants

1. `ParcelShowCard` advances frames in list order at `PARCEL_FRAME_INTERVAL_MS` and wraps from the last frame back to index zero.
2. `ParcelScanState.add` stores at most one frame per `(parcelId, seq)` pair; a repeat is ignored and the first copy wins.
3. A frame whose `count` disagrees with the first accepted frame for the same `parcelId` is ignored.
4. Stored frames never exceed `MAX_PARCEL_FRAMES`; an excess frame is ignored and progress stays at the cap.
5. A frame carrying a different `parcelId` while another parcel is in progress is ignored rather than replacing the current assembly.
6. `ParcelScanState.complete()` returns a `ParcelPayload` only when the accepted frames cover every `seq` in `1..count` exactly once and `ParcelCodec.complete(frames, recipientFp, identity)` verifies CRC, digest, envelope signature and recipient and returns a payload; otherwise it returns null and frames stay for another scan attempt.
7. A verified payload's `bytes` are handed to the injected `(ByteArray) -> Unit` writer exactly once; a second `complete()` call returns null.
8. Closing the scan dialog drops every in-memory frame and writes no file, ledger row or delivery record.

## Failure modes

| Condition | Conclusion | Keeps bytes | Drops | Evidence |
| --- | --- | --- | --- | --- |
| Camera permission denied | Paste path stays available; no frame captured | Nothing kept | No file written | `PURE` paste-parser test; camera state not claimed on this date |
| `Qr.decodeYPlane` returns null on a skewed or noisy frame | Ignore that camera frame and keep scanning | Previously accepted frames kept | The undecodable frame | `PURE` test; existing `FlitQrTest` noise case |
| Duplicate `(parcelId, seq)` | Ignore the repeat | First copy kept | The duplicate | `PURE` test |
| Mixed `parcelId` in one scan | Ignore the foreign frame | Current assembly kept | The foreign frame | `PURE` test |
| Count mismatch or sequence past `MAX_PARCEL_FRAMES` | Refuse the malformed sequence | Current assembly kept | The offending frame | `PURE` test |
| User closes with an incomplete sequence | Conclude incomplete | Nothing durable | All in-memory frames | `PURE` test |
| `ParcelCodec.complete` returns null (CRC, digest, signature or recipient mismatch) | Conclude not from a claimed signer; log the line | Nothing durable | All frames and payload | `PURE` test |
| Complete verified payload | Hand `bytes` to the injected writer once | Landed file in the received bucket survives | Transient frames | `PURE` test; `DEVICE` walk belongs to 2026-12-08 |

## Tests

Pure tests first, in `app/src/test/kotlin/app/flit/share/`, JVM-only and Android-free as the app suite requires:

- `ParcelScanStateTest` — in-order, out-of-order, duplicate, missing, mixed-id, count-mismatch, over-cap, `complete`-null (CRC/digest/signature/recipient) and double-complete paths, driving the pure `add`/`complete` reducer with no Compose or Android import. Each case asserts the exact accepted frame set and whether `complete()` yields a `ParcelPayload`. These tests drive the real `ParcelCodec.complete(frames, recipientFp, identity)` with a recipient fingerprint and the receiving `TlsIdentity`; the codec's own CRC, digest and signature cases stay in the 2026-12-03 protocol suite and are not duplicated here.
- A rendered-frame case alongside `FlitQrTest` that takes a codec-produced frame at `PARCEL_FRAME_SIZE_PX`, renders it through `Qr.encode`/`Qr.gray` at the codec's EC level `M` and margin `2`, and decodes it back through `Qr.decodeGray`, and decodes the same frame from a simulated CameraX Y plane with row/pixel strides and one rotation step. This is the pixel proof that a frame fits a QR at the size the UI draws.
- A small UI-state test for the display index wrap at the last frame and the progress label format. No Android framework import, no Robolectric, no engine start.

Harness: none in this layer. The 2026-12-04 CLI piece owns the box-side encode/decode round-trip; this piece's decode path is exercised on the same `Qr` seam the camera uses, in pure JVM tests.

Device walk: none on 2026-12-07. Capturing a real multi-frame sequence from a second phone's screen is the 2026-12-08 air-gap walk. Until then camera capture and end-to-end frame delivery are recorded as not verified, not as a green device result.

After the layer lands, the rig is run with `source env.sh && ./gradlew --no-daemon --rerun-tasks :protocol:test :app:testDebugUnitTest :harness:test` and the new app test count is written into the completion note and cross-linked count tables.

## Evidence for this day

A completion note `docs/completions/2026-12-07-qr-parcel-app.md` records the new app test count and result under the `PURE` label, the three files changed (`ParcelScanState.kt`, `ParcelUi.kt`, `MainActivity.kt`), the exact codec calls and screen constants, and the limitation that no camera walk happened on this date (`DEVICE` not claimed). The note names the 2026-12-03 codec and 2026-12-04 CLI layers as the source of the frame grammar used here.

## Out of scope

- No device walk or camera capture on hardware; that is 2026-12-08.
- No change to the frame grammar, signature, encryption or courier envelope; those are 2026-12-03 and 2026-11-27.
- No CLI commands or box-side status output; that is 2026-12-04.
- No per-drop content keys (2026-12-09), delivery receipts (2026-12-11) or Circle pad (2026-12-15).
- No persistence of partial parcels, no new route on the v2.2 wire and no offline `.flitpack` bundle.

## Commit

2026-12-07 App: QR parcel frame display and camera reassembly with pure tests
