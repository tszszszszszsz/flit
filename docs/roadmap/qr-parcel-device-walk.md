# Air-gap QR parcel device walk

The walk records, on the rig, that a framed QR sequence moves a small payload
between the CLI box and the phone with no network, Bluetooth or relay carrier in
the path.

## Date and layer

2026-12-08 — Device. Commit scope: air-gap walk and evidence note. No code, no
Gradle change or commit belongs to this layer; the walk writes its observation
into `docs/completions/`.

## Depends on

- 2026-11-26 Device — three-node baseline walk (`docs/roadmap/three-node-walk.md`):
  fixes the phone + CLI identities rig and the evidence-note shape.
- 2026-11-27 Protocol, 2026-11-30 CLI, 2026-12-01 App and 2026-12-02 Device —
  courier envelope, carry core, carrier and three-node walk
  (`docs/roadmap/courier-envelope.md`, `courier-carry.md`, `courier-cli.md`,
  `courier-sync.md`, `courier-device-walk.md`). Air-gap parcels reuse that
  signed envelope instead of a second codec (`00-plan.md:29-30`).
- 2026-12-03 Protocol — QR parcel frame codec, pure JVM, tests
  (`docs/roadmap/qr-parcel-codec.md`): frame shape, sequencing, CRC, size caps
  and reassembly.
- 2026-12-04 CLI — parcel encode and decode, tests
  (`docs/roadmap/qr-parcel-cli.md`): the commands that write and read frame
  images. The existing `parse-qr` image path in `Harness.kt:180-216` is the seam
  that layer extends.
- 2026-12-07 App — parcel display and camera decode, tests
  (`docs/roadmap/qr-parcel-app.md`): the screens built on `QrCanvas` and
  `CameraQrView` (`QrUi.kt:59-82,264-314`).
- Rig facts and commands: `docs/TESTING.md` §2, §3 and §7; evidence tags:
  `docs/FAILURE-MODES.md:9-14`.

## Scope

Two directions are walked on the same day:

1. Phone → box. The phone displays a parcel sequence; the box captures the
   screen over USB/adb and decodes the captured frames with the CLI surface
   built on 2026-12-04. The box reconstructs the payload from frame images
   alone and compares SHA-256 with the fixture generated on the box.
2. Box → phone. The box encodes a fixture into frame images and shows them
   full-screen on the rig monitor; the phone camera scans them through the
   parcel surface built on 2026-12-07. The landed file's SHA-256 is compared
   with the value reported by the CLI encode step.

Radio state is part of the walk: Wi-Fi and Bluetooth are off on the phone and
no `flit-cli serve` or `flit-cli relay` process runs on the box. ADB over USB
is a control channel for fixture placement, screen capture and log reads. It
never carries the payload bytes; in the phone → box direction the only input
to the decoder is the captured PNG sequence.

The full rig is run before the walk. The observed commands, frame counts,
hashes, log lines and limitations are written to
`docs/completions/2026-12-08-air-gap-parcel-walk.md`.

## Design

### Rig and control channel

One Redmi Note 10S (API 36) and one CLI box, the same rig as
`docs/TESTING.md` §2. The CLI is rebuilt with `./gradlew :harness:installDist`
and any running `serve`/`relay` process is stopped, since a live JVM holds the
old jars and a stale receive slot (`docs/TESTING.md:245-249`).

Before each direction, record:

```bash
adb shell settings get global wifi_on          # 0
adb shell settings get global bluetooth_on     # 0
pgrep -af 'flit-cli (serve|relay)'             # no output
adb getprop ro.product.model                   # rig phone identity
```

After the walk, restore the radio settings. A direction whose checks fail is
not reported as an air-gap observation; it is rerun after the state is fixed.

### Frame stream consumed from earlier layers

The frame format is owned by `docs/roadmap/qr-parcel-codec.md`. The walk
asserts its externally visible properties and does not define a second format:
frames arrive in index order, the stream declares a total count and a payload
SHA-256, a final frame closes the sequence, and the envelope binds the sender
fingerprint to the payload. Repeated frames from a second full loop are
deduplicated; the receiver finalizes once. The exact frame fields, CRC and
size caps are read from the 2026-12-03 specification and from the CLI encode
output, not restated here.

Decode on the box uses the CLI surface from `docs/roadmap/qr-parcel-cli.md`.
The encode step prints or writes the frame count and the payload SHA-256;
those two values are the expected values for the direction. Decode takes only
frame images and writes one reconstructed file. Camera decode on the phone
uses the surface from `docs/roadmap/qr-parcel-app.md`, which reads the Y-plane
through `Qr.decodeYPlane` (`Qr.kt:187-216`) rather than adding a second camera
path.

### Walk fixtures and constants

| Item | Value | Purpose |
| --- | --- | --- |
| `marker.txt` | 37 ASCII bytes, a fixed sentence plus a run id | short sequence |
| `gap-1k.bin` | 1024 bytes, Python `random.seed(7)` byte stream | multi-frame reassembly |
| `gap-8k.bin` | 8192 bytes, Python `random.seed(42)` byte stream | ceiling case; reduced only if the 2026-12-03 frame-count cap is smaller, with the reduction recorded |
| Hash | lowercase hex SHA-256, `sha256sum` on the box and `adb shell sha256sum` on the phone | payload comparison |
| Capture | `adb exec-out screencap -p` into one PNG per frame, two captures per second | phone → box |
| Loops | three full frame loops per fixture in both directions | exercise dedup without an acknowledgement channel |
| Evidence | `docs/completions/2026-12-08-air-gap-parcel-walk.md` | dated observation |

Three loops are run because an air gap has no back channel: the sender cannot
wait for an acknowledgement, so the receiver must tolerate repeated frames.
The loop count is a walk constant, not a UI cadence owned by 2026-12-07; the
actual display timing is measured and recorded.

### State ownership

- Box: fixture files, frame PNGs and decoded output live in a fresh temp
  directory per direction. The CLI identity under `FLIT_HARNESS_HOME` is used
  only for signature verification and is left unchanged. Nothing is persisted
  in the app store or the send queue by the CLI path.
- Phone: a completed parcel lands as a normal received file with a ledger row,
  as for any inbound body (`docs/ARCHITECTURE.md` §6). The parcel path creates
  no queue row, no `.part` or `.part.token` sidecar and no resume entry; there
  is no carrier whose death could leave a prefix. Frames in flight are held in
  memory and released when the sequence finalizes or is refused.
- ADB scripts and the full-screen viewer on the box are rig controls, not
  product code. No shipped surface is changed for the walk.

### Direction A — phone to box

1. Place `gap-1k.bin` on the phone once as fixture setup:
   `adb push gap-1k.bin /sdcard/Download/flit-gap/gap-1k.bin`.
2. Read the expected hash without moving bytes:
   `adb shell sha256sum /sdcard/Download/flit-gap/gap-1k.bin`.
3. Open the parcel display surface for that file on the phone. Record the
   start line in `logcat` and the frame count shown by the app.
4. Capture three loops into a fresh directory:
   `for i in $(seq 1 $((3*N))); do adb exec-out screencap -p > frames/gap-1k-$i.png; sleep 0.5; done`
   where `N` is the frame count recorded in step 3.
5. Decode with the 2026-12-04 CLI surface, passing only the captured PNGs.
   Record the reported frame count, sender fingerprint and payload SHA-256.
6. Compare the decoded hash with step 2, then `cmp` the decoded file against
   the fixture still on the box. The fixture is never an input to the decoder.
7. Repeat steps 1-6 for `marker.txt` and `gap-8k.bin`.

### Direction B — box to phone

1. Encode `gap-8k.bin` with the 2026-12-04 CLI surface into a fresh frame
   directory. Record the frame count and payload SHA-256 printed or written
   by the encode step.
2. Show the PNGs full-screen on the rig monitor in manifest order for three
   loops, recording the exact viewer command and the measured loop time.
3. Point the phone camera at the monitor and open the parcel scan surface.
   Let the three loops pass; record the `logcat` lines for frames taken,
   deduplication, finalization and the landed path.
4. Read the landed hash with `adb shell sha256sum <landed path>` and compare
   it with step 1. Check that the parcel card reports the landed result and
   that no `/partial`, `queued` or `.part` line appears in the log.
5. If no monitor can be placed in front of the camera, record direction B as
   `not verified` with that reason. Direction A remains walked.

The box-to-phone direction needs a display surface in front of the camera; the
rig has one phone and a headless box, so the monitor is a required part of this
direction. A printed frame set is the equivalent substitute when a monitor is
unavailable.

## Invariants

1. No carrier is present in either direction: Wi-Fi and Bluetooth read `0` on
   the phone and no `serve`/`relay` process is alive on the box at the start
   and end of each direction. ADB is a control channel only.
2. The reconstructed payload's SHA-256 equals the SHA-256 declared by the
   sender for all three fixtures in both directions.
3. Frame indices are consumed in sequence; a missing index leaves the payload
   undecoded and produces an incomplete-sequence line naming the index.
4. Three loops produce exactly one landed file. Repeated frames are
   deduplicated by index and do not finalize a second time.
5. A frame with a bad CRC, a bad signature or a sender fingerprint other than
   the expected one is refused with a reason line and no landed file.
6. Parcel decode creates no queue row, no resume sidecar and no `/partial`
   exchange; state after finalization is the landed file and its ledger row.
7. Every claim in the completion note carries one label from `CODE`, `PURE`,
   `SIM-HARN` and `DEVICE`, with the weakest applicable label governing the
   claim. No test result is written as a device observation.

## Failure modes

| Condition | Conclusion | Keeps bytes | Drops | Evidence |
| --- | --- | --- | --- | --- |
| Capture skips a frame | Incomplete sequence; no landing | Buffered frames in memory until the wait expires | Buffered frames and the decoded temp | Frame count below the declared count; log names the missing index |
| Same frame taken twice across loops | Dedup by index; finalize once | One reconstructed payload | Duplicate PNGs after decode | Exactly one landed file; duplicate-frame line in the log |
| Frame image tampered with | CRC or signature refusal | Nothing from that sequence | Whole sequence buffer | Reason line with the failing index; no landed file |
| Frames from a different CLI identity | Bad sender fingerprint | Nothing from that sequence | Whole sequence buffer | Refusal naming expected and presented fingerprints |
| Camera permission not granted on the phone | Direction B cannot start | Frame PNGs on the box | Nothing | Permission state in the log; direction B marked `not verified` for that run |
| Wi-Fi, Bluetooth or a CLI carrier found live | Walk run invalid | Captured frames | The air-gap claim for that run | `settings get` and `pgrep` lines; rerun after fixing state |
| Fixture above the codec's frame-count cap | Encode refuses with the cap reason | Fixture file | No frames emitted | CLI error line; fixture reduced to the cap and the run repeated |
| No monitor or print surface for direction B | Direction B not walked | Encoded frames on the box | Nothing | Setup note; direction A recorded as the walked result |

## Tests

Pure tests run first. The full rig command in `docs/TESTING.md` §3 is run after
the earlier layers land; the resulting counts and commit are recorded in the
completion note rather than copied from an earlier snapshot. The parcel codec
tests from 2026-12-03, the CLI encode/decode tests from 2026-12-04 and the app
display/camera tests from 2026-12-07 are the acceptance gate for the surfaces
this walk consumes. No test is added on 2026-12-08.

Harness sanity check before the walk: run the CLI encode step and decode its
own frame images in one process on the box, with no socket and no phone. This
is `SIM-HARN` evidence for the command path and is kept separate from the
device observation.

Device walk: direction A for all three fixtures, then direction B for
`gap-8k.bin`, with the radio and process checks before and after each
direction. Each fixture records the expected hash, the reported hash, frame
count, loop count, decode time and the resulting log excerpt with timestamps.

## Evidence for this day

`docs/completions/2026-12-08-air-gap-parcel-walk.md` records the date, the
commit under which the rig ran, the phone model and API level, the exact
commands for encode, capture, decode and hash reads, the frame and loop counts
per fixture, the two hashes compared in each direction, and the `logcat` and
CLI excerpts with timestamps. Each claim is labelled: `DEVICE` for walked
observations, `SIM-HARN` for the CLI loopback, `PURE` for the test gate and
`CODE` for source facts. Limitations are listed in the same file: no second
phone, no phone-to-phone air gap, no QR throughput measurement, no radio
carrier in the parcel path, and direction B marked `not verified` if the
display surface was absent.

## Out of scope

No code, Gradle, CI or route change. No new frame format, crypto rule or CLI
flag is introduced by this layer; the exact surfaces stay with the
2026-12-03, 2026-12-04 and 2026-12-07 specifications. No Wi-Fi, Bluetooth,
relay or Wi-Fi Aware carrier is used by the parcel path. No second phone, no
large-file transfer and no throughput benchmark. Per-drop content keys,
delivery receipts and Circle pad belong to later dates and are not exercised
here. No commit belongs to this layer.

## Commit

2026-12-08 — Device: air-gap QR parcel walk and evidence note
