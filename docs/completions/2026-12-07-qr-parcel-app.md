# 2026-12-07 — QR parcel app (App) — DRAFT

Layer: 2026-12-07 App. The work is the on-screen parcel frame display and the
camera frame intake in `app.flit.share`: a pure state holder that folds one
opaque frame string at a time into a verified payload, a two-mode Compose
screen that renders frames and reads them back, and an additive entry point in
`MainActivity` with an injected writer that lands verified bytes through the
existing receive path. Frame grammar, sequencing, CRC, digest, envelope
signature and recipient check stay in the 2026-12-03 `ParcelCodec` and the
`CourierEnvelope` it calls; this layer adds no second crypto path and restates
no codec cap.

Counts are placeholders and are to be taken from a fresh `--rerun-tasks` run.
The layer is dated 2026-12-07. No device walk is claimed here; the air-gap,
second-phone camera walk stays on 2026-12-08.

## Files in the layer

| File | Content |
| --- | --- |
| `app/src/main/kotlin/app/flit/share/ParcelScanState.kt` | Android-free state holder: opaque frame strings, `ParcelCodec.parse`, `(parcelId, seq)` dedup, `count`, pure reducer, `ParcelCodec.complete` seam |
| `app/src/main/kotlin/app/flit/share/ParcelUi.kt` | Compose screen with two modes, Show and Scan; `Qr.encode` render, camera/paste intake, progress, injected writer |
| `app/src/main/kotlin/app/flit/share/MainActivity.kt` | Additive parcel-screen entry point and injected writer into the existing received bucket; Circle and transfer behavior unchanged |
| `app/src/test/kotlin/app/flit/share/ParcelScanStateTest.kt` | JVM-only reducer tests over the real `ParcelCodec.complete`; no Compose, no Android framework, no engine start |
| `docs/TESTING.md` | New app classes with counts from the integration run (TBD) |
| `docs/CONTRACTS.md` | App seam over `ParcelCodec` and the injected writer |
| `docs/SECURITY.md` | Camera frames stay local; no file name in a frame |
| `docs/FAILURE-MODES.md` | Camera, decode, duplicate, mixed-id and verification-refusal states |
| `docs/OPERATIONS.md` | Phone procedure for showing and scanning a parcel |
| `docs/PERFORMANCE.md` | Frame cadence and dwell; no invented timing |
| `docs/ARCHITECTURE.md` | UI seam over the codec and receive path |
| `docs/completions/2026-12-07-qr-parcel-app.md` | Layer record for 2026-12-07 |
| `docs/roadmap/qr-parcel-app.md` | API aligned with the seams recorded below |
| `docs/roadmap/qr-parcel-device-walk.md` | Commands aligned; no walk claimed here |
| `docs/roadmap/01-schedule.md`, `docs/roadmap/02-waves.md` | Build-ahead note for the 2026-12-07 layer |
| `README.md` | One capability bullet, no device claim |

Package `app.flit.share` uses `ParcelCodec`, `Qr`, `TlsIdentity` and the existing
receive path only. There is no second argument parser, no HTTP route, no radio
surface and no new wire field in this layer.

## Public surface

The API declared at the seams below. The codec and QR declarations are quoted
from the files already on disk; the app-side declarations are the contract,
with the concrete Kotlin signatures declared by the files in the table above.

Codec API consumed from `app.flit.share.protocol.parcel.ParcelCodec` (unchanged
by this layer):

```
object ParcelCodec {
  const val MAX_FRAMES = 64
  const val MAX_AIR_GAP_PARCEL_BYTES = 131072
  const val MAX_CHUNK_BYTES = 512
  const val FRAME_MAGIC = "FLITPARCEL1"
  data class FrameDescriptor(val parcelId: String, val seq: Int, val count: Int,
                             val totalBytes: Int, val crc: Long)
  data class ParcelPayload(val parcelId: String, val recipientFp: String,
                           val signerFp: String, val sha256: String,
                           val bytes: ByteArray)
  fun parse(frame: String): FrameDescriptor?
  fun encodeParcel(payload: ByteArray, parcelId: String, recipientFp: String,
                   recipientCert: X509Certificate, signerFp: String,
                   identity: TlsIdentity): List<String>
  fun complete(frames: List<String>, recipientFp: String,
               identity: TlsIdentity): ParcelPayload?
}
```

QR API consumed from `app.flit.share.Qr` (unchanged by this layer):

```
object Qr {
  fun encode(text: String, size: Int): BitMatrix
  fun decodeYPlane(y: ByteArray, width: Int, height: Int, rowStride: Int,
                   pixelStride: Int, rotationTimes: Int): String?
}
```

App seams declared in the files above:

- `ParcelScanState.kt` — an Android-free state holder that accepts one opaque
  frame string at a time through a small pure reducer, holds the strings, parses
  each with `ParcelCodec.parse(frame)`, deduplicates by `(parcelId, seq)`,
  tracks `count` from the first accepted descriptor, and completes through
  `ParcelCodec.complete(frames, recipientFp, identity): ParcelPayload?`, where
  `identity` is the receiving app's `TlsIdentity`. The concrete class and method
  names are owned by `ParcelScanState.kt`; the reducer is pure so
  `ParcelScanStateTest` needs no Compose, Android framework or engine start.
- `ParcelUi.kt` — one Compose screen with two modes, Show and Scan. Show takes
  the immutable `List<String>` returned by `ParcelCodec.encodeParcel` and
  renders each string as a bitmap through `Qr.encode`; Scan feeds camera frames
  to `Qr.decodeYPlane`, offers each decoded string to `ParcelScanState`, reads
  progress from `FrameDescriptor.seq`/`count`, and on completion shows the
  signer fingerprint, SHA-256 and byte count and writes `bytes` through an
  injected `(ByteArray) -> Unit` callback. The exact composable signatures are
  owned by `ParcelUi.kt`.
- `MainActivity.kt` — an additive entry point to the parcel screen and an
  injected writer that saves the verified bytes to the app's received bucket
  through the existing receive path. No Circle or transfer behavior is changed.

## Show path

1. The caller supplies the ordered `List<String>` produced by
   `ParcelCodec.encodeParcel(payload, parcelId, recipientFp, recipientCert,
   signerFp, identity)`; the UI never builds, edits or reorders a frame string.
2. Each string is rendered through `Qr.encode` at error-correction level `M`,
   margin `2`, side `PARCEL_FRAME_SIZE_PX = 240` px, into a bitmap for display.
3. The display index advances one frame every `PARCEL_FRAME_INTERVAL_MS = 400L`
   and wraps from the last frame back to the first. Next/back controls move the
   index explicitly. The index is local UI state and is discarded on close.

## Scan path

1. A camera preview feeds an analyzer built on the same shape as
   `QrUi.kt`'s `CameraQrView`: `LifecycleCameraController`, a throttle, a Y-plane
   copy with row and pixel strides and one rotation step, then `Qr.decodeYPlane`.
   A null decode is an undecodable frame and is ignored; the scan continues.
2. Each decoded string is offered to `ParcelScanState`. The state holder parses
   it with `ParcelCodec.parse`, keeps the opaque string, and gates on
   `(parcelId, seq)` and `count` before storing it.
3. Progress is `frame i of n`, where `n` is the first accepted descriptor's
   `count` and `i` is the number of distinct sequences accepted so far; before
   the first frame, `n` is unknown.
4. When accepted frames cover `1..count`, the holder calls
   `ParcelCodec.complete(frames, recipientFp, identity)`. A verified
   `ParcelPayload` is handed to the injected `(ByteArray) -> Unit` writer exactly
   once; the screen then shows the signer fingerprint, the SHA-256 and the byte
   count. Any refusal returns null and no bytes are written.
5. No file name travels in a frame. The save name is local to the receiving app
   and is chosen by the existing receive path, not by the sender.
6. A denied camera leaves a paste field that accepts newline-separated frame
   strings and feeds the same state holder. The paste cap is
   `PARCEL_PASTE_MAX_CHARS = 16_000` from the roadmap design.

## Constants

| Constant | Value | Basis |
| --- | --- | --- |
| `PARCEL_FRAME_SIZE_PX` | `240` | `Qr.encode` side for a rendered parcel frame |
| `PARCEL_FRAME_INTERVAL_MS` | `400L` | Dwell per frame before advancing and wrapping |
| Error correction | `M` | `Qr.encode`; same level the codec and CLI use |
| Quiet zone / margin | `2` modules | `Qr.encode` |
| `MAX_PARCEL_FRAMES` | `64` | App display/assembly cap, set to the codec's `MAX_FRAMES` |
| `PARCEL_PASTE_MAX_CHARS` | `16_000` | Cap on paste-field frame text (roadmap design) |
| `MAX_FRAMES` | `64` | Consumed from `ParcelCodec`; not restated |
| `MAX_AIR_GAP_PARCEL_BYTES` | `131072` | Consumed from `ParcelCodec`; not restated |
| `MAX_CHUNK_BYTES` | `512` | Consumed from `ParcelCodec`; not restated |

With 512-byte chunks and 64 frames the reachable framed payload is 32 KiB,
inside the codec's 128 KiB defensive cap. `FRAME_MAGIC = "FLITPARCEL1"`, the
per-frame CRC32 and the field layout stay inside the codec; the app never reads
or rewrites them.

## State ownership

- Display index lives in Compose `remember` inside the Show mode; it is
  discarded on close and never written to `FlitCircleStore` or preferences.
- Intake frames live in `ParcelScanState`, created per scan and dropped on close
  or completion. A partial parcel is not persisted: a missed frame means
  starting the scan again. Only a verified payload becomes durable, through the
  existing received-file or feed path.
- The camera analyzer and its throttle are local to the screen, matching
  `QrScanCard`; no engine field is added.
- The injected writer writes bytes only after `ParcelCodec.complete` returns a
  payload. Closing before completion writes no file, ledger row or delivery
  record.

## Invariants

1. Show advances frames in list order at `PARCEL_FRAME_INTERVAL_MS` and wraps
   from the last frame back to index zero.
2. `ParcelScanState` stores at most one frame per `(parcelId, seq)` pair; a
   repeat is ignored and the first copy wins.
3. A frame whose `count` disagrees with the first accepted frame for the same
   `parcelId` is ignored.
4. Stored frames never exceed `MAX_PARCEL_FRAMES`; an excess frame is ignored.
5. A frame carrying a different `parcelId` while another parcel is in progress
   is ignored rather than replacing the current assembly.
6. Completion yields a `ParcelPayload` only when the accepted frames cover
   every `seq` in `1..count` exactly once and `ParcelCodec.complete` verifies
   CRC, digest, envelope signature and recipient; otherwise it returns null and
   frames stay for another attempt.
7. A verified payload is handed to the inbound callback exactly once; a second
   completion call returns null.
8. Closing the scan drops every in-memory frame and writes no file, ledger row
   or delivery record.

## Verification

The rig command from [TESTING.md](../TESTING.md) §3:

```bash
source env.sh && ./gradlew --no-daemon --rerun-tasks \
  :protocol:test :app:testDebugUnitTest :harness:test
```

Counts are TBD and must come from a fresh `--rerun-tasks` XML run, not from a
target figure. The inherited baseline after the 2026-12-04 CLI layer is 158
protocol + 257 app + 52 harness = 478 ([TESTING.md](../TESTING.md) §2); this
layer adds app cases only.

| Module | Baseline | Current rig | Task |
| --- | --- | --- | --- |
| `protocol` | 158 | 158 (unchanged) | `:protocol:test` |
| `app` | 239 | TBD | `:app:testDebugUnitTest` |
| `harness` | 51 | 51 (unchanged) | `:harness:test` |
| Total | 478 | TBD | — |

No Gradle result is claimed here.

## Tests

`ParcelScanStateTest` is the owned app test class. It is JVM-only and
Android-free: in-order, out-of-order, duplicate, missing, mixed-id,
count-mismatch, over-cap, completion-null (CRC/digest/signature/recipient) and
double-completion paths, asserting the exact accepted frame set and whether
completion yields a `ParcelPayload`. It drives the real `ParcelCodec.complete`
with a recipient fingerprint and the receiving `TlsIdentity`; the codec's own
CRC, digest and signature cases stay in the 2026-12-03 protocol suite and are
not duplicated here. The roadmap also describes a rendered-frame case alongside
`FlitQrTest` and a small UI-state case for index wrap and progress label; those
are planned app cases, not separate files in this layer.

| Class | Cases | What it pins |
| --- | --- | --- |
| `ParcelScanStateTest` | TBD | Pure reducer over the real codec: dedup, cap, mixed-id, count mismatch, completion and refusal paths |
| Additional app cases (roadmap plan) | TBD | Rendered-frame pixel proof and display-index/progress UI state |

Case counts are to come from the JUnit XML of a fresh run, not from a target figure.
No protocol or harness test is added by this layer.

## Evidence

| Label | Claimed here | Basis |
| --- | --- | --- |
| CODE | Yes — contract | The seams above and the codec/QR APIs already on disk |
| PURE | TBD | `ParcelScanStateTest` counts from the integration run |
| SIM-HARN | No | The box-side encode/decode round-trip is the 2026-12-04 CLI layer |
| DEVICE | No | No camera, phone or air-gap observation; the walk is 2026-12-08 |

No radio, phone, camera or QR observation is claimed on this date.

## Limitations

- Frame rendering, camera intake, progress and the local save name are app
  surfaces only; the box-side round-trip stays with the 2026-12-04 CLI.
- The frame grammar, signature, encryption, digest, CRC and sequencing are the
  codec's; this layer adds no second crypto path and restates no codec cap.
- No file name is carried in a frame. The name is local to the receiving app and
  is chosen by the existing receive path.
- No partial parcel is persisted; a missed frame requires a new scan. No new
  delivery status, receipt, ledger row or gossip row is added.
- No change to Circle, transfer behavior, the v2.2 wire or any radio carrier.
- No device walk, second-phone observation, real capture timing or throughput
  figure is claimed here.

## Placeholders

The following items are open for the integration pass:

- `app` current-rig count and the rig total in the Verification table (TBD).
- `ParcelScanStateTest` case count and the additional planned app-case count in
  the Tests section (TBD).
- The PURE evidence row (TBD) and the exact `@Test` names inside
  `ParcelScanStateTest`.
- The concrete Kotlin declarations and member names of `ParcelScanState`,
  `ParcelUi` and the `MainActivity` entry point/writer (declared by those
  files; only the contract shape is recorded here).
- The rig command and cross-linked count tables in
  [TESTING.md](../TESTING.md) after the new app count is known.

## Verification status

Draft. Counts and the PURE evidence row stay placeholders until the integration
run. No Gradle result is claimed here. No DEVICE observation is claimed.
