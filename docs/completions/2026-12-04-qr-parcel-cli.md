# 2026-12-04 — QR parcel CLI (Harness) — DRAFT

Layer: 2026-12-04 CLI. The work is the `parcel` subcommand in `flit-cli`: a
one-shot, offline adapter that renders one file as numbered QR PNG frames and
folds a directory of those frames back into bytes. All framing, sequencing,
CRC, digest and crypto stay in the 2026-12-03 `ParcelCodec` and the
`CourierEnvelope` it calls; this layer adds no second crypto path. The adapter
is a single seam in `harness/`, beside `parse-qr`, with no app, route or socket
surface.

Counts are to be taken from a fresh `--rerun-tasks` run. The layer is dated
2026-12-04. No device walk is claimed here; the
air-gap walk stays on 2026-12-08.

## Files in the layer

| File | Content |
| --- | --- |
| `harness/src/main/kotlin/app/flit/share/harness/ParcelCli.kt` | `object ParcelCli`: declared API, encode/decode paths, zxing PNG seam, exit codes and its own `usage()` |
| `harness/src/main/kotlin/app/flit/share/harness/Harness.kt` | `"parcel"` dispatch, one `usage()` line, `parcelUsage()` and `--out` in `ParsedArgs.VALUE_FLAGS`; no other command changed |
| `harness/src/test/kotlin/app/flit/share/harness/ParcelCliTest.kt` | Pure flag parsing, certificate gate and frame naming |
| `harness/src/test/kotlin/app/flit/share/harness/ParcelCliHarnessTest.kt` | Two temp homes, encode → decode round-trip through the production codec |
| `docs/TESTING.md` | New harness classes with counts from the integration run |
| `docs/CONTRACTS.md` | CLI surface of the `parcel` command |
| `docs/SECURITY.md` | Certificate supplied out of band, no pin registry or self-addressed fallback |
| `docs/FAILURE-MODES.md` | Bad certificate, missing frame, missing directory and bad output path |
| `docs/OPERATIONS.md` | `parcel encode` / `parcel decode` command reference in §11 |
| `docs/PERFORMANCE.md` | PNG render and read cost, no invented timings |
| `docs/ARCHITECTURE.md` | One-shot CLI parcel seam over `ParcelCodec` |
| `docs/completions/2026-12-04-qr-parcel-cli.md` | Layer record for 2026-12-04 |
| `docs/roadmap/qr-parcel-cli.md` | API aligned with the surface recorded below |
| `docs/roadmap/qr-parcel-device-walk.md` | Commands aligned; no walk claimed here |
| `docs/roadmap/01-schedule.md`, `docs/roadmap/02-waves.md` | Build-ahead note for the 2026-12-04 layer |
| `README.md` | One CLI bullet, no device claim |

Package `app.flit.share.harness` uses `ParsedArgs` only. There is no second
argument parser, no HTTP route, no radio and no Android import in this layer.

## Public surface

The API declared in `app.flit.share.harness.ParcelCli`:

```
data class EncodeResult(val dir: Path, val frames: Int, val sealedChars: Int, val sha256: String)
data class DecodeResult(val out: Path, val bytes: Int, val sha256: String,
                        val signerFp: String, val recipientFp: String)
internal fun parcelEncode(args: ParsedArgs): EncodeResult
internal fun parcelDecode(args: ParsedArgs): DecodeResult
fun usage(): String
fun run(args: ParsedArgs): Int
```

`parcelEncode` and `parcelDecode` are `internal` so harness tests call them
directly, without spawning a process. `run` returns the process exit code;
`Harness.main` calls it from the `"parcel"` branch and exits with that code.
The adapter loads its identity with `loadOrCreateIdentity()` and roots its
paths in `harnessHome()`, so two `FLIT_HARNESS_HOME` values isolate two nodes
on one box. Field-level meaning of `sealedChars` is fixed by the adapter
implementation in `ParcelCli.kt`; the shape is recorded above.

## Command surface

```
parcel encode <file> --dir DIR --to FP --cert PATH
parcel decode --dir DIR --out PATH
```

`--dir`, `--to` and `--cert` already appear in `ParsedArgs.VALUE_FLAGS`;
`--out` is added to that set. `usage()` prints the two command lines, and
`parcelUsage()` in `Harness.kt` prints `ParcelCli.usage()` for detailed help.

`--cert PATH` holds the Base64 DER of the recipient certificate, not a
fingerprint and not a pin id. No file name travels in a frame, so `decode`
takes an explicit `--out` and the operator names the landed bytes.

## Encode path

1. Read `--file`, `--dir`, `--to` and `--cert` from `ParsedArgs`; a missing
   input file, directory, fingerprint or certificate is a bad-argument exit.
2. Decode the Base64 DER in `--cert` and parse it with `CertificateFactory`
   into an X.509 certificate. Check `TlsIdentity.fingerprintOf(cert)` against
   `--to` after normalisation; a mismatch refuses before any frame is
   rendered.
3. Load the local identity with `loadOrCreateIdentity()` and set
   `signerFp = identity.fingerprint`. Generate a `parcelId` for this encode.
4. Read the file bytes and call
   `ParcelCodec.encodeParcel(payload, parcelId, recipientFp, cert, signerFp,
   identity)`. The certificate object itself is passed to the codec; there is
   no pin lookup and no self-addressed fallback.
5. Render each returned opaque frame string with zxing into
   `frame-%04d.png` (1-based) in the fresh `--dir`, round-tripping each image
   back to the identical string before writing it.
6. Return `EncodeResult(dir, frames, sealedChars, sha256)` and print the
   signer fingerprint, frame count and SHA-256. The adapter computes the
   digest over the source file bytes; `encodeParcel` returns frame strings
   only.

## Decode path

1. Read `--dir` and `--out` from `ParsedArgs`; a missing directory or an
   existing output path is a bad-argument exit.
2. Read every `frame-*.png` with zxing `MultiFormatReader` and keep each QR
   result as an opaque frame string. An unreadable image counts as one
   rejected image and does not stop the remaining images.
3. Call `ParcelCodec.complete(frames, identity.fingerprint, identity)` with
   the local identity. The codec checks frame layout, CRC32, sequence, count,
   whole-parcel digest and recipient before `CourierEnvelope.open`.
4. On a verified `ParcelPayload`, write its `bytes` to `--out`. On any refusal
   there is no output file and the exit is a verification failure.
5. Return `DecodeResult(out, bytes, sha256, signerFp, recipientFp)` and print
   the signer fingerprint, recipient fingerprint, SHA-256 and byte count. No
   second digest or crypto computation is added by the CLI.

## Image seam and constants

The harness depends on `protocol` and zxing, not on `app`, so the image step
is implemented in `ParcelCli.kt` beside `parseQr` rather than importing
`Qr.kt`.

| Constant | Value | Where |
| --- | --- | --- |
| Frame image | `768 × 768` px, PNG | `ParcelCli.kt` |
| Error correction | `ErrorCorrectionLevel.M` | `ParcelCli.kt`; same as `Qr.encode` |
| Character set | `UTF-8` | `ParcelCli.kt`; same as `Qr.encode` |
| Quiet zone | `2` modules | `ParcelCli.kt`; same as `Qr.encode` |
| Frame file name | `frame-%04d.png`, 1-based | `ParcelCli.kt` |
| Frame cap | `ParcelCodec.MAX_FRAMES = 64` | codec; not restated in the CLI |
| Chunk cap | `ParcelCodec.MAX_CHUNK_BYTES = 512` | codec; not restated in the CLI |
| Parcel cap | `ParcelCodec.MAX_AIR_GAP_PARCEL_BYTES = 131072` | codec; not restated in the CLI |

Frame names are zero-padded to four digits, so lexicographic order of
`frame-*.png` equals codec frame order and encode creates no other file in
`--dir`. The CLI declares no second frame, chunk or parcel limit: the caps are
read from `ParcelCodec`. With 64 frames of at most 512 bytes the reachable
framed size is 32768 bytes, below the defensive codec cap. Frame grammar,
`FRAME_MAGIC` and the per-frame CRC32 stay inside the codec; the adapter never
edits or reorders a frame string.

## Exit codes

| Code | Meaning |
| --- | --- |
| `0` | Verified success |
| `2` | Bad arguments, missing certificate, missing `--dir` or missing/invalid `--out` |
| `3` | Verification failure from `ParcelCodec.complete` or a wrong-recipient result |

No other command's exit behaviour changes. Unexpected exceptions still reach
the existing `Harness.main` catch.

## Harness wiring

`Harness.kt` gains a `"parcel"` branch that calls `ParcelCli.run(args)` and
exits with the returned code, one line in `usage()`, `parcelUsage()` that
prints `ParcelCli.usage()`, and `--out` in `ParsedArgs.VALUE_FLAGS`. The
`"circle"`, `"courier"`, `"serve"` and other branches are untouched.

## State ownership

- The sender identity is `identity.p12` under `harnessHome()` and survives
  rebuilds; the signer fingerprint is printed for the receiving side.
- The recipient certificate is read from `--cert` for one invocation, held in
  memory for that invocation only, and never copied into `--dir`. No pin is
  written and no identity registry is built.
- The frame directory is the whole checkpoint: numbered PNGs, no manifest and
  no sidecar. Encode refuses a directory that already holds a `frame-*.png`;
  decode never writes into it.
- Descriptor and completion state are in memory for the one decode call. A
  killed process leaves no output file, no partial bytes and no progress
  record. No delivery status, receipt or gossip row exists at this layer.

## Verification

The rig command from [TESTING.md](../TESTING.md) §3:

```bash
source env.sh && ./gradlew --no-daemon --rerun-tasks \
  :protocol:test :app:testDebugUnitTest :harness:test \
  :app:assembleDebug :app:assembleRelease \
  :harness:installDist :harness:distZip
```

Counts are to be taken from a fresh `--rerun-tasks` XML run, not from a target
figure. The inherited baseline is 158 protocol + 239 app + 51 harness = 448
([TESTING.md](../TESTING.md) §2); this layer adds harness cases only.

| Module | Baseline | Current rig | Task |
| --- | --- | --- | --- |
| `protocol` | 158 | 158 (unchanged) | `:protocol:test` |
| `app` | 239 | 239 (unchanged) | `:app:testDebugUnitTest` |
| `harness` | 39 | 51 | `:harness:test` |
| Total | 436 | 478 | — |

## Tests

Two new harness classes add 12 cases, to be counted from the JUnit XML.

| Class | Cases | What it pins |
| --- | --- | --- |
| `ParcelCliTest` | 9 | Pure flag parsing, the certificate gate against `--to`, frame naming and the result shapes; no sockets |
| `ParcelCliHarnessTest` | 3 | Two temp homes, encode → decode through the production `ParcelCodec`, byte-for-byte equality and the refusal paths |

The harness exercise uses production codec and envelope seams, not a lookalike
cipher. Identities come from `TlsIdentity.generate()`, payloads are seeded and
roots are per test. No protocol or app cases are added by this layer.

## Evidence

| Label | Claimed here | Basis |
| --- | --- | --- |
| CODE | Yes — contract | The `ParcelCli` API above and the `Harness.kt` wiring |
| PURE | TBD | `ParcelCliTest` counts to be taken from the integration run |
| SIM-HARN | TBD | `ParcelCliHarnessTest` counts to be taken from the integration run |
| DEVICE | No | No camera, phone or air-gap observation; the device walk is the 2026-12-08 layer |

No radio, phone, camera or QR observation is claimed on this date.

## Limitations

- Both commands are one-shot and offline. They open no socket, register no
  route and use no `Link`.
- Rendering and PNG reading stay in `harness/`; frame display, camera intake,
  progress and accessibility stay in the app layer on 2026-12-07.
- The certificate arrives out of band. There is no pin lookup, no registry and
  no self-addressed fallback; a certificate whose fingerprint differs from
  `--to` is refused before any frame is rendered.
- No file name is carried in a frame. Decode writes only to the operator's
  `--out`, and only after a verified result.
- No second crypto path: signature, encryption, digest, CRC and sequencing are
  reached only through `ParcelCodec` and the `CourierEnvelope` calls it makes.
- No parcel progress, received ledger row or delivery status is persisted. The
  PNG frames and the identity file are the only on-disk state.
- No device walk, second-phone observation, real capture timing or throughput
  figure is claimed here.

## Verification status

Draft. Counts and the PURE / SIM-HARN evidence rows stay placeholders until the
integration run. No Gradle result is claimed here. No DEVICE observation is
claimed.
