# QR parcel CLI — encode and decode air-gap frames

`flit-cli` renders a named byte payload as a sequence of scannable QR images and folds a set of those images back into the payload, with all framing, sequencing and verification done by the protocol parcel codec.

## Date and layer

2026-12-04 — CLI. Scope: parcel encode and decode, tests. Frames are written and read on the box; no app surface, radio carrier or device walk is part of this layer.

## Depends on

- 2026-11-27 — courier envelope: the signed, recipient-addressed payload the frames carry.
- 2026-11-30 — `flit-cli` command conventions: `ParsedArgs`, `harnessHome()` / `loadOrCreateIdentity()`, one-shot commands and exit codes in `Harness.kt`.
- 2026-12-03 — QR parcel frame codec: frame strings, frame order, size caps, reassembly and the hash gate.

## Scope

Two commands are added to the `parcel` subcommand:

```
flit-cli parcel encode <file> --dir DIR --to FP --cert PATH
flit-cli parcel decode --dir DIR [--out PATH]
```

`encode` reads one file, seals it for the recipient named by `--to`, asks the codec for ordered frame strings, and writes them as `frame-0001.png`, `frame-0002.png`, … into an empty `--dir`. It prints the signer fingerprint, the payload name, size and SHA-256, and the frame count.

`decode` reads every `frame-*.png` in `--dir`, turns each image into a frame string, feeds the strings to the codec assembler, and writes the opened payload to `--out` or to `DIR/<name>` only when the codec returns a hash-verified payload addressed to this CLI's own fingerprint. It prints seen, duplicate and rejected counts, missing indices, the signer fingerprint and the payload hash.

Both commands are one-shot and offline. They open no socket, register no route and use no `Link`.

## Design

### One adapter at the harness seam

All codec and image work is confined to `harness/src/main/kotlin/app/flit/share/harness/ParcelCli.kt`, called from `Harness.main` by `"parcel" -> parcel(args)` beside the existing `circle` and `parse-qr` branches (`Harness.kt:112-135`). `VALUE_FLAGS` gains `--out`, `--to` and `--cert`; `--dir` already exists (`Harness.kt:73-77`). The usage text gains the two command lines above.

The adapter exposes two functions that the harness tests call directly, without spawning a process:

```
data class ParcelEncodeResult(
    val name: String, val size: Long, val sha256: String,
    val frameCount: Int, val framesDir: Path, val signerFp: String,
)

data class ParcelDecodeResult(
    val total: Int, val seen: Int, val duplicate: Int, val rejected: Int,
    val missing: List<Int>, val signerFp: String, val sha256: String,
    val name: String, val size: Long, val output: Path?,
)

fun parcelEncode(file: Path, dir: Path, to: String, cert: Path,
                 identity: TlsIdentity): ParcelEncodeResult
fun parcelDecode(dir: Path, identity: TlsIdentity, out: Path? = null): ParcelDecodeResult
```

`output` is `null` for every incomplete, unverified or wrongly addressed parcel; it is the only signal a caller needs before writing bytes.

### Codec boundary

The adapter imports the protocol parcel codec and the courier envelope entry points named in the 2026-12-03 and 2026-11-27 specifications; those names and the frame grammar are not redefined here. The boundary is exact in shape:

- encode passes the file name, the bytes, the sender identity from `loadOrCreateIdentity()` (`Harness.kt:93-99`) and the recipient certificate to the protocol, and receives an ordered list of opaque frame strings plus the payload hash.
- decode passes one opaque frame string at a time to the assembler and reads back a snapshot (total, seen indices, missing indices, duplicates, signer fingerprint, declared hash) and, only on completion, the opened payload with its name, bytes, hash and recipient fingerprint.
- The CLI never parses, edits or reorders a frame string. Every byte of a frame comes from the codec; every frame string handed to the codec comes from a QR decode.

CLI decode is a batch fold over numbered PNGs for the round-trip test and box-side capture. Camera frame intake, display cadence and reassembly progress stay in the app layer on 2026-12-07 and are not implemented here.

`--cert` is parsed as an X.509 certificate with `CertificateFactory`, pinned in memory with `TlsIdentity.pin` (`TlsIdentity.kt:179-186`) and bound to `--to` by `TlsIdentity.fingerprintOf` (`TlsIdentity.kt:192`). Pins are process-local and are not written to disk. Decode checks the opened parcel's recipient fingerprint against `identity.fingerprint` before any output is written. Signature verification, cert-in-hand checks and the SHA-256 gate stay inside the protocol codec; the CLI has no second crypto path.

### Image seam and constants

`harness/build.gradle.kts:11` gives the harness a dependency on `protocol` and zxing but not on `app`, so the adapter implements the image step beside `parseQr` (`Harness.kt:179-216`) instead of importing `Qr.kt`.

| Constant | Value | Where |
| --- | --- | --- |
| Frame image | `768 × 768` px, PNG | `ParcelCli.kt` |
| Error correction | `ErrorCorrectionLevel.M` | `ParcelCli.kt`; same as `Qr.encode` (`Qr.kt:142-150`) |
| Character set | `UTF-8` | `ParcelCli.kt`; same as `Qr.encode` |
| Quiet zone | `2` modules | `ParcelCli.kt`; same as `Qr.encode` |
| Frame file name | `frame-%04d.png`, 1-based | `ParcelCli.kt` |
| Exit codes | `0` verified, `2` incomplete or unverified, `1` usage or I/O | `ParcelCli.kt` and `Harness.kt:128-133` |

The render size, error correction and frame names above are CLI-side values; no app display or intake constant is declared or imported here. The frame count follows the codec cap, with no additional CLI-side frame limit.

The per-frame payload cap and the maximum parcel size come from the codec constants; the adapter imports them and refuses a parcel that exceeds them rather than restating a second limit. Every frame string is rendered in memory and decoded back to a string before the first PNG is written. On decode, images are read as RGB int arrays through `RGBLuminanceSource` and `HybridBinarizer`, the same shape `parseQr` uses; zxing failure counts as one rejected image and does not abort the batch.

### State ownership

- Sender identity lives in `identity.p12` under `harnessHome()` and survives rebuilds (`Harness.kt:81-99`). The signer fingerprint is printed so an operator can pair it on the receiving side.
- The recipient certificate is read from `--cert` for one invocation and pinned in memory only. No certificate file is copied into `--dir`.
- The frame directory is the whole checkpoint: numbered PNGs, no manifest and no sidecar. Encode refuses a directory that already holds a `frame-*.png`; decode never writes into it.
- Assembler state, pins and opened bytes are in memory for the one decode invocation. A killed process leaves no output file, no partial bytes and no progress record.
- No delivery status, receipt or gossip state exists at this layer.

## Invariants

1. The CLI contains no frame grammar, CRC, signature, encryption, hash or sequencing code; those operations are reached only through the protocol codec.
2. Every PNG is written only after its own QR round-trip returns the identical frame string at `768 × 768`, EC `M`, margin `2` and UTF-8.
3. Frame names are 1-based and zero-padded to four digits; lexicographic order of `frame-*.png` equals codec frame order, and encode creates no other file in `--dir`.
4. Decode considers `frame-*.png` only. An image with no readable QR increments `rejected`; a frame the codec refuses is counted and never aborts the remaining images.
5. Decode writes an output file only when the assembler returns a hash-verified payload whose recipient fingerprint equals `identity.fingerprint`; otherwise `output` is `null` and the exit code is `2`.
6. A recipient certificate whose own SHA-256 DER differs from `--to` is refused before any frame is rendered.
7. Two decode runs over an unchanged frame directory produce the same counts, hash and output bytes.
8. No socket, route, `Link`, app module, v2.2 DTO or status code is added or changed by this layer.
9. Missing `--dir`, a missing input file or an existing output path ends with a `!!` reason and a non-zero exit, not a zero-frame success or a stack trace.

## Failure modes

| Condition | Conclusion | Keeps bytes | Drops | Evidence |
| --- | --- | --- | --- | --- |
| `--cert` fingerprint differs from `--to` | Refuse before rendering; exit `1` | Source file; any pre-existing frames | Nothing written | CODE; SIM-HARN `ParcelCliTest` |
| Frame string exceeds QR capacity at EC `M` | Refuse encode; exit `1` | Source file; no PNGs | Nothing written | CODE; SIM-HARN |
| `--dir` already holds `frame-*.png` | Refuse encode; exit `1` | Source file and old frames | Nothing written | CODE; SIM-HARN |
| One PNG has no readable QR | Count `rejected`, continue; result unverified if a frame is missing | All frames on disk | That frame's contribution | CODE; SIM-HARN |
| A frame index is missing | `missing` lists indices; `output == null`, exit `2` | All frames; no output file | Assembled bytes in memory | PURE (codec); SIM-HARN |
| A frame is repeated | Assembler dedups; count `duplicate`; verified result still writes | All frames and output | Repeated frame | PURE (codec); SIM-HARN |
| Declared hash does not match opened bytes | `output == null`, exit `2` | Frames; no output file | Unverified bytes | PURE (codec); SIM-HARN |
| Parcel addressed to another fingerprint | `output == null`, exit `2`; print both fingerprints | Frames; no output file | Opened bytes | CODE; SIM-HARN |
| Output path already exists | Refuse before writing; exit `1` | Existing file and frames | Nothing | CODE; SIM-HARN |
| Process dies during encode | Partial numbered PNGs may remain | Source file; written PNGs | Frames not yet written | CODE |
| Process dies during decode | No output file; frames unchanged | Frames | Assembler state and pins | CODE |

## Tests

Pure tests come first and are owned by the 2026-12-03 codec layer: frame string round-trip, order and dedup, declared caps, and the SHA-256 gate. The 2026-12-04 layer does not duplicate them; its harness tests drive the same production codec through the CLI adapter.

`harness/src/test/kotlin/app/flit/share/harness/ParcelCliTest.kt` is added to the existing 10 harness tests. Tests use seeded random bytes, a generated `TlsIdentity` for sender and recipient, and a fresh temp directory per case:

1. Encode a seeded multi-frame payload; assert frame count, exact `frame-%04d.png` names, that every PNG re-decodes to its original frame string, and that decode returns `output` with byte-for-byte equal content, matching `sha256`, `missing` empty and `rejected == 0`.
2. Delete one frame; assert `output == null`, `missing` names that index, and no file at the output path.
3. Add a second copy of a frame under another `frame-*.png` name; assert `duplicate == 1` and the verified result still writes.
4. Add a solid-color PNG; assert `rejected == 1` and the verified result still writes.
5. Decode with an identity other than the recipient; assert `output == null` and that the printed reason names both fingerprints.
6. Encode with a certificate that does not match `--to`; assert the refusal happens before any PNG exists.
7. Encode into a directory that already holds a frame; assert the refusal leaves the old frames untouched.

The green run uses `source env.sh && ./gradlew --no-daemon --rerun-tasks :protocol:test :app:testDebugUnitTest :harness:test`, followed by the full rig command in `docs/TESTING.md` §3. The harness count in `docs/TESTING.md` §2, §4 and §5 moves from 10 to 10 plus the number of `@Test` functions added; counts are taken from `--rerun-tasks` XML, not from a target figure.

Device walk: scheduled for 2026-12-08, not claimed here. The recipe is to render frames on the box with `parcel encode`, step them on the box screen for the phone camera built on 2026-12-07, and in the other direction capture phone-displayed frames with `adb screencap` and fold them on the box with `parcel decode`. The walk records frame count, hash equality and the point where scanning stopped; it is labelled DEVICE only when actually run.

## Evidence for this day

`docs/completions/2026-12-04-parcel-cli.md` records CODE for the adapter functions, flags, image constants and exit codes; PURE for the inherited codec tests; and SIM-HARN for `ParcelCliTest` results with the exact harness count, one byte-for-byte round-trip with its hash and frame count, and the missing-frame, cert-mismatch and wrong-recipient refusals. DEVICE is not recorded on this date; the air-gap walk is scheduled for 2026-12-08.

## Out of scope

- Frame display, camera intake, progress states and accessibility in the app (2026-12-07).
- The air-gap device walk and its evidence note (2026-12-08).
- Per-drop content keys (2026-12-09), delivery receipts (2026-12-11) and Circle pad (2026-12-15).
- New HTTP routes, a radio carrier, `Link` providers, relay pairing and any change to the v2.2 surface.
- Persistence of parcel progress, a received ledger row or a delivery status; the frame PNGs and the identity file are the only on-disk state.
- Batch parcels, file names other than the source file's, image compression tuning beyond the zxing hints above, and forwarding or re-signing a received parcel.

## Commit

2026-12-04 — CLI: add `flit-cli parcel encode/decode` over the protocol codec with harness round-trip tests
