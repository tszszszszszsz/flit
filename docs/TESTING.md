# Testing and evidence

> Part of the Flit engineering docs. [Main ladder](transport-bluetooth.md) ·
> [Architecture](ARCHITECTURE.md) · [Operations](OPERATIONS.md) ·
> [Contracts](CONTRACTS.md) · [Failure modes](FAILURE-MODES.md) ·
> [Performance](PERFORMANCE.md) · [Security](SECURITY.md).

## 1. Evidence levels

Where several levels apply to a claim, the **weakest** one governs what may be asserted.

Rules applied here:

- No device walk is claimed that did not happen. A direction proved only by a
  test is written "test-evidenced only" and named in the limitations list.
- Where hardware was absent, the text says **not verified** rather than
  implying a green rig covers it. Current examples: phone-to-phone RFCOMM,
  a Circle mesh beyond the three-identity baseline (two physical phones or
  more than three members), Wi-Fi Aware (feature absent on the rig phone),
  and outbound phone→host Circle fan-out as a device observation. The
  2026-11-26 three-node walk **is** on-device evidence for a three-identity
  Circle; it does not prove a larger mesh (§6).
- Historical counts (30, 76, 86, 115, 121, 126, 145, 183, 231, 286, 302, 356,
  378, 396, 769, 773) are snapshots of earlier layers, not the current inventory:
  356 after the 2026-11-27 protocol layer, 378 after the 2026-11-30 CLI layer
  and 396 after the 2026-12-01 App layer, 416 after the app QA fixes, 436 after
  the QR codec layer, 448 after the QR CLI layer, 714 at the pre-JOIN-proof
  layer, 769 as the snapshot before the relay-hunt and `wrapVerified` cases and
  773 after those cases. The current green figure is 778 (362 protocol + 324
  app + 92 harness) after a clean `--rerun-tasks` run with zero failures, zero
  errors and zero skipped tests; all assemblies were green in the same run.
- The 2026-12-01 App layer added 18 courier cases in `app/src/test` (11 in
  `CourierCardUiTest`, 4 added to `FlitCircleStoreTest`, 3 added to
  `FlitCircleSyncTest`), recorded from a green `--rerun-tasks` run (§4.2).
  The counts come from the JUnit XML, not from a target figure.
- The app QA fixes after the 2026-12-01 App layer added 20 cases on top of that
  396 inventory: 2 in the new `CourierAuthorizationTest` (protocol), 11 in the
  app (10 more in `FlitCircleSyncTest`, 1 more in `CourierCardUiTest`) and 7 in
  the harness (5 more in `CourierCliTest`, 2 more in
  `CourierCarrierHarnessTest`). They took the rig from 396 to 416. The two QR parcel protocol classes in §4.1
  added 20 more cases (14 + 6), taking the rig to 436; the QR CLI layer added 12
  harness cases (9 + 3), taking it to 448. All counts come from the JUnit XML.
- The 2026-12-04 QR parcel CLI layer added two harness classes, `ParcelCliTest`
  (9 cases) and `ParcelCliHarnessTest` (3 cases), recorded from a fresh
  `--rerun-tasks` run. Both are in-process, on one box, with no
  device evidence.
- The QR parcel App layer added `ParcelScanStateTest` (18 cases) and the courier
  hardening added further cases; all are included in the green 588 from a
  clean `--rerun-tasks` run. No device or camera claim is attached to them.
- The 2026-12-10 per-drop parity Integration layer adds four test classes
  (`DropParityTest`, `FlitCircleSyncTest`, `FlitEngineTest`, `DropKeyCliTest`)
  with 6 + 4 + 11 + 10 cases, included in the green 588 from a clean
  `--rerun-tasks` run (§4.4). No device walk is claimed on this date:
  the receive side is test-evidenced only.
- The 2026-12-11 delivery receipts protocol layer adds three JVM-pure protocol
  test classes (`ReceiptBodyTest`, `ReceiptTallyTest`, `ReceiptCanonicalTest`,
  §4.1) with 12 + 6 + 6 cases, included in the green 588 from a clean
  `--rerun-tasks` run. No device walk, new route, header, DTO field or v2.2 status
  is claimed.
- The 2026-12-14 delivery receipts parity Integration layer adds three
  test-class rows (`FlitCircleSyncTest` and `FlitEngineTest` in `app/`,
  `ReceiptCliTest` in `harness/`, §4.5) with 3 + 6 + 5 cases, included in the
  green 588 from a clean `--rerun-tasks` run. No device walk, new route, header,
  DTO field, QR parameter or v2.2 status is attached to this layer.
- The 2026-12-15 circle pad protocol layer adds four JVM-pure protocol test
  classes (`PadBodyTest`, `PadViewTest`, `PadMergeTest`, `PadCanonicalTest`,
  §4.1) with 16 + 9 + 11 + 7 = 43 cases, recorded from the JUnit XML, taking
  the protocol module from 240 to 283 at that layer. The module totals at that
  layer were 330 + 309 + 75 = 714; the 359 + 323 + 87 = 769 and 362 + 324 +
  87 = 773 snapshots and the current 362 + 324 + 92 = 778 totals are recorded
  in §2. No app, CLI, harness, device walk, new route, header, DTO field, store
  field or v2.2 status is attached to this layer.
- The 2026-12-16 circle pad app + CLI Integration layer adds two test classes:
  `PadUiTest` (app, Android-free JVM, 15 cases) and `PadCliTest` (harness,
  in-process, 8 cases), recorded from the JUnit XML. At that layer the app
  module stood at 296 cases and the harness at 75; the module totals at that
  layer were 330 + 309 + 75 = 714, against the 359 + 323 + 87 = 769 and
  362 + 324 + 87 = 773 snapshots and the current 362 + 324 + 92 = 778 (§2). No
  device walk, new route, header, DTO field, store field or v2.2 status is
  attached to this layer.
- The signed JOIN proof / shared Circle authority wave adds two JVM-pure protocol
  classes (`JoinProofTest` 14, `CircleAuthorityTest` 12) and changes seven more
  across all three modules (§4.8), from the JUnit XML of the final green
  `--rerun-tasks` run. All are JVM-pure or in-process, with no device walk, no
  new route, header, DTO field or v2.2 status; pre-proof JOIN clients are
  refused.
- A later, smaller layer adds three `wrapVerified` cases to `DropKeyCoreTest`
  and one relay-hunt case to `FlitEngineTest` (§4.8), taking the inventory from
  the 769 snapshot to the historical 773 snapshot. Both classes stay JVM-pure,
  with no device walk, new route, header, DTO field or v2.2 status.
- The 2027-01-05 CLI parity layer adds four cases to `CircleHarnessTest` and one
  to `RelayServerTest` (§4.8), taking the rig from the historical 773 snapshot to
  778. The four cases pin
  the CLI `circleFreshEvents` gate, the ignored departed-officer hand-off, the
  stale `--host-fp` guard across circles and at join, and a byte-wise read of the
  relay OK reply; `ParcelCliTest` stays at 11 and `ParcelCliHarnessTest` at 3. The
  QR reader in `ParcelCliHarnessTest` was hardened with decode hints and integer
  scales without adding a case. No device walk is claimed; the layer is
  CODE/PURE/SIM-HARN only.
- The protocol module is JVM-pure by hard rule. The app module's unit tests are
  JVM tests of Android-free seams; they import no Android framework class and
  use no Robolectric (the note at `app/src/test/kotlin/app/flit/share/FlitSpoolTest.kt:11`
  says so explicitly). The harness is a Linux CLI (`flit-cli`), which doubles as
  the interop rig.

## 2. The rig at a glance

| Module | Role | Test count | Test task | Where it runs |
| --- | --- | --- | --- | --- |
| `protocol/` | Wire engine: JSON DTOs, UDP discovery, TLS/mTLS, HTTP server/client, relay core, Circle core, courier codec | **362** | `:protocol:test` | JVM only (JDK 21 toolchain, `protocol/build.gradle.kts:6-8`) |
| `app/` | Android shell (Compose), `app.flit.share`, version 0.3.1, minSdk 26, compile/targetSdk 36 | **324** | `:app:testDebugUnitTest` | JVM unit tests of pure seams; no emulator, no device |
| `harness/` | Desktop companion `flit-cli`; the rig for interop, relays, Circle bots and the courier carrier | **92** | `:harness:test` | JVM, in-process with real endpoints |
| **Total** | Three modules, three roles | **778** | see §3 | 0 failures, 0 errors, 0 skipped at last green; all assemblies green |

The 2026-12-04 CLI layer's two harness classes added 12 cases, moving harness
from 39 to 51 and the total from 436 to 448 at that layer's green
`--rerun-tasks` run. The 2026-12-07 QR parcel App layer added
`ParcelScanStateTest` with 18 cases, first counted from source `@Test`
annotations and later confirmed by a fresh JUnit XML run. The table above is
the current inventory from the final green `--rerun-tasks` run (§3), not an
earlier per-layer figure.

The 2026-12-09 per-drop content key protocol layer added three JVM-pure classes
(`DropKeyCoreTest` 15, `DropKeyBodyTest` 14, `DropKeyCryptoTest` 12), included
in the green 588 from a clean `--rerun-tasks` run (§4.1).

The 2026-12-10 per-drop parity Integration layer added four test classes (§4.4)
at that layer's green run, which then stood at 240 + 281 + 67 = 588. Two classes
were new (`DropParityTest` in `protocol/`, `DropKeyCliTest` in `harness/`) and
two added cases to existing app classes (`FlitCircleSyncTest`, `FlitEngineTest`).
No device observation is attached to these rows.

The 2026-12-11 delivery receipts protocol layer adds three JVM-pure protocol
classes (§4.1), included in the green 588. No device observation or new wire
surface is attached to them.

The 2026-12-14 delivery receipts parity Integration layer adds three test-class
rows (§4.5), included in the green 588. No device observation is attached to
them.

The 2026-12-15 circle pad protocol layer added four JVM-pure protocol classes
(§4.1) with 16 + 9 + 11 + 7 = 43 cases on top of the then-green 588. The
240 + 281 + 67 = 588 figure is that layer's snapshot; the module table above
records the current inventory from the final green run. No app, CLI, harness or
device claim is attached to these rows.

The JOIN-proof/authority layer adds `JoinProofTest` (14) and
`CircleAuthorityTest` (12) in `protocol/`, with the changed classes listed in
§4.8, and accounts for the move from the earlier 714 inventory to 769, a
historical snapshot. The later `wrapVerified` and relay-hunt cases add four
cases to reach the historical 773 snapshot. The 2027-01-05 CLI parity layer
adds four `CircleHarnessTest` cases and one `RelayServerTest` case to reach the
current 778; the QR decoder helper change adds no case. None of these claims a
device walk or adds a route, header, DTO field or v2.2 status; a pre-proof JOIN
is refused at admission.

Each module declares `testImplementation(kotlin("test"))` plus the JUnit
Platform launcher (`protocol/build.gradle.kts:23-24`,
`harness/build.gradle.kts:16-17`, `app/build.gradle.kts:108`) and configures
`useJUnitPlatform()` (`protocol/build.gradle.kts:27-29`,
`harness/build.gradle.kts:20-22`). The app has no separate release-unit-test
task in the rig; debug and release are only *assembled*.

Toolchain: JDK 21 lives at `~/.local/jdks/temurin-21` and `env.sh` puts it first
on PATH; Gradle 8.14.3 is pinned outside the repo. CI uses
`actions/setup-java` with Temurin 21 instead of `env.sh` (`.github/workflows/rig.yml:20-24`).
The reference peer for interop is the official `localsend-cli` v1.18.2 at
`~/.local/opt/localsend-cli` (README:133-134, `interop/README.md`).

## 3. Exact rig command

**Local pre-commit standard** — README "Dev quickstart" (README:164-167):

```bash
source env.sh && ./gradlew :protocol:test :app:testDebugUnitTest :harness:test \
    :app:assembleDebug :app:assembleRelease :harness:installDist :harness:distZip
```

**CI standard** — the same task set with `--no-daemon --stacktrace`
(`.github/workflows/rig.yml:35-43`):

```bash
./gradlew --no-daemon --stacktrace \
  :protocol:test :app:testDebugUnitTest :harness:test \
  :app:assembleDebug :app:assembleRelease \
  :harness:installDist :harness:distZip
```

Two notes about these commands:

1. Both commands now run the same test set: the README line includes
   `:harness:test` and the CI command adds `--no-daemon --stacktrace`, so each
   ran 778 tests (362 + 324 + 92) at the last green run; 773 (362 + 324 + 87)
   and 769 (359 + 323 + 87) are earlier snapshots. The older 302-test
   figure (which included the 10 harness tests) came from the CI command, or
   from adding `:harness:test` locally when the README line omitted it; that
   baseline's README line ran 292 tests (82 + 210). Earlier per-layer figures,
   including 588, are historical snapshots.
2. `assembleRelease` falls back to the debug key when the local-only, gitignored
   `keystore.properties` is absent (`.github/workflows/rig.yml:36-38`,
   `app/build.gradle.kts:15-33`). That is a build-integrity check, not a
   release-signing verification.

**Verification record.** The three test tasks were run fresh with
`--rerun-tasks` in `/home/szszszs/flit` (JDK 21.0.13, Gradle 8.14.3):

```bash
source env.sh && ./gradlew --no-daemon --rerun-tasks \
  :protocol:test :app:testDebugUnitTest :harness:test
```

Result: `BUILD SUCCESSFUL`, **362 protocol + 324 app + 92 harness = 778 tests,
0 failures, 0 errors, 0 skipped**, read from the XML result files under each
module's `build/test-results/test/`. Debug and release assemblies and the CLI
distribution were green in the same rig run (§2). Provenance: the 2026-11-25
date belongs to the earlier 302-test baseline and the stale-XML incident in §8,
not to the courier counts. Earlier fresh runs recorded 136 + 210 + 32 = 378
after the 2026-11-30 CLI layer, 396 after the 2026-12-01 App layer and 588
after the courier/QR hardening; the JOIN-proof/authority layer raised the
totals to the 359 + 323 + 87 = 769 snapshot (§4.8), and the `wrapVerified` and
relay-hunt cases raised them to the historical 362 + 324 + 87 = 773 snapshot.
The 2027-01-05 CLI parity cases raised them to the current 362 + 324 + 92 =
778. The `--rerun-tasks` flag was deliberate: an earlier invocation reported all test
tasks `UP-TO-DATE` and left XML from an older class layout in place (see §8).
The 302-test baseline was green at `72225f8`; the added cases are listed in
§4.1, §4.2, §4.3 and §4.8. The earlier `wrapVerified` layer added three cases
to `DropKeyCoreTest` (now 18) and one to `FlitEngineTest` (now 31), counted from
the same XML. The latest layer adds four cases to `CircleHarnessTest` (now 18)
and one to `RelayServerTest` (now 6); `ParcelCliTest` stays at 11 and
`ParcelCliHarnessTest` at 3. The 2026-12-14 receipts parity layer's three
test-class rows (§4.5) are part of the current inventory.

## 4. Test inventory by module and class

Counts are from the JUnit XML reports after the final fresh run; they agree with
a source-level `@Test` count. Layer notes that quote 588, 52 harness, 302, 769
or 773 are snapshots at those layers, not the current inventory (§2). The
JOIN-proof / authority classes, the later `wrapVerified` / relay-hunt cases and
the 2027-01-05 CLI parity cases are grouped in §4.8. File names and class names
differ in two places, so the report is grouped by **class**: `DtoParityTest.kt` contains both
`DtoParityTest` and `TlsIdentityTest` (`protocol/src/test/kotlin/app/flit/share/protocol/DtoParityTest.kt:22,142`),
and `TlsIdentityTest.kt` contains the class `PinCacheTest`
(`.../TlsIdentityTest.kt:16`). Do not "fix" these by renaming without updating
this table.

### 4.1 Protocol — 362 tests in the final green XML

| Class | # | What it pins |
| --- | --- | --- |
| `ChunkTrackerTest` | 6 | Out-of-order pieces never fake completeness; overlapping retry merges; base is the prefix only; tidy/scrambled/random shuffles assemble exactly once. |
| `CircleCoreTest` | 12 | Create/sign/verify, forged-author rejection, fresh-id replay window, bounded seen set, file body `{name,size,sha256}` only, Lamport convergence under scrambled delivery, latest vote per member, kick tally, circle routes answer while v2 paths stay 404, default-refusing delegate and join/sync through `LinkServer`. |
| `CircleHardeningTest` | 14 | Declared bounds enforced, one bounded replay set with exact accept/fresh precedence, oversized fields never consume an id, cert bound to `authorFp`, extreme clocks cannot overflow the window, tallies ignore malformed JSON, strict file-body gate, delivery-order-independent dedup and kick-target resolution, exact status/reason for every refusal branch, v2.2 status behavior unchanged. The three added cases cover the JOIN-proof route: a proofless JOIN is a signed refusal that never reaches the delegate, a valid signed proof reaches it, and a forged proof is refused with a verifiable signature (§4.8). |
| `DtoParityTest` | 8 | Announce/register/prepare DTOs match upstream fixtures and v2.2 shapes; multi-socket hint decodes; older receivers tolerate the hint; `WireJson` carries default-valued fields (`encodeDefaults = true`). |
| `TlsIdentityTest` | 2 | Fingerprint is uppercase hex SHA-256 of the certificate; identity survives a keystore round-trip. (Class lives in `DtoParityTest.kt`.) |
| `PinCacheTest` | 6 | LRU eviction at cap, read refreshes recency, normalized fingerprint keys and forget/clear, pinning through every entry point, re-pin idempotence, cert-in-hand verification never touches the shared pin store. (Class lives in `TlsIdentityTest.kt`.) |
| `LinkServerParityTest` | 4 | Full v2.2 flow over in-memory pipes in both TLS modes; a kept part survives receiver death and resumes hash-exact; a carrier death throws instead of reporting. |
| `PanicTest` | 6 | Created alerts verify, forged sender rejected, fresh id delivered once, hop limit stops forwarding, replay window rejects stale, v2 path stays 404, `sendPanic` reaches the delegate. |
| `PartialSidecarTest` | 1 | The `.part.token` sidecar exists **before** the first body byte — the restart-proof claim, tested with a blocked body stream. |
| `ProtocolRefusalTest` | 4 | Marked prepare decline carries the refusal header and concludes as a report; transient statuses still throw `TransferException`; aborted upload ends the receiver session as cancelled, not saved. |
| `ProtocolServerDoorTest` | 1 | An externally served `Link` answers with the listener's exact route semantics (the door relay and BLE share). |
| `RelayCoreTest` | 11 | Hello line round-trip, garbage refused, both pairing directions park, FIFO per-fingerprint isolation, same-mode never pairs, `withdraw` prevents ghost pairing, re-offer supersedes old park, optional constant-time token gate vs open relay. |
| `ResumeAndTurboTest` | 5 | Both `Content-Range` spellings (`bytes s-e/total` and `bytes=s-e/total`), restart probe/attach from a dead run's part, failed chunk claim leaves the part intact for plain resume, 409 for a resume header with no held base, `multi=true` hash-exact landing. |
| `StreamTlsTest` | 4 | `SSLEngine` shim handshake closes the loop both directions, multi-megabyte duplex round-trip byte-exact, half-open close yields a clean end, idempotent close and single bytes. |
| `TurboProgressTest` | 1 | Multi-socket progress is non-decreasing, bounded by total and ends exactly at total. |

The two JOIN-proof/authority protocol classes added by that layer,
`JoinProofTest` and `CircleAuthorityTest`, are listed with the rest of the wave
in §4.8, together with the later `wrapVerified` cases.

#### Courier additions — counts recorded at integration

The five classes below add 54 cases in
`protocol/src/test/kotlin/app/flit/share/protocol/`. They are not part of the
82-protocol / 302-total baseline in §2. Counts come from a fresh `--rerun-tasks`
run. These classes carry no device evidence.

| Class | # | What it pins |
| --- | --- | --- |
| `CourierEnvelopeCodecTest` | 11 | Canonical bytes domain-tagged and length-prefixed with `sig`/`cert` excluded, and changed by a change to each covered field; `encode`/`decode` round-trip and refuse truncated, oversized or unknown-field bytes; `MAX_ENVELOPE_CHARS` bound; tampering detected. |
| `CourierEnvelopeCryptoTest` | 13 | `seal`/`open` round-trip a signed event array through AES-GCM and RSA wrap byte-exact; wrong certificate, cert digest not equal to `fromFp`, forged signature and tampered ciphertext/IV/wrapped key fail `verify` without writing a pin; `createdMs` outside the replay/skew window refused by `open`. |
| `CourierCarryCodecTest` | 10 | `CarryState` encode/decode round-trip; a corrupt blob decodes to an empty state; numeric expiry repaired; duplicate ids keep the first row; oversized bag trimmed to `MAX_PARCELS`. |
| `CourierCarryDecisionTest` | 14 | `evaluate` precedence and bounded `MAX_PARCELS` quota, TTL derived only from `createdMs + MAX_PARCEL_TTL_MS`, and `ack`, `select`, `expire`, `merge` and `clear` on `CarryState`. |
| `CourierIntegrationTest` | 6 | `seal` → `parcel` → carry fold → `open` → `CircleCore.verify` chain; `PARCEL` listed in `CircleEvent.TYPES` with existing verb values and DTO fields unchanged. |

#### Protocol QA addition — 2 cases

The case below is part of the 436 baseline, not of the 82-protocol / 302-total
baseline. It comes from the same fresh `--rerun-tasks` run as the app and harness
QA additions below. It carries no device evidence.

| Class | # | What it pins |
| --- | --- | --- |
| `CourierAuthorizationTest` (new) | 2 | `seal` refuses a recipient certificate whose fingerprint differs from `toFp`; `open` yields signature-valid non-member JOIN and REKEY events that the app and CLI gates must refuse. |

#### QR parcel additions — 31 cases

The two classes below belong to the 2026-12-03 QR parcel codec layer. Both are
JVM-pure, live in `app.flit.share.protocol.parcel` and carry no device evidence.
Counts come from a fresh `--rerun-tasks` run: 21 in `ParcelCodecTest` and 10 in
`ParcelCodecTamperTest`, moving protocol from 138 to 169. No camera, Android,
socket or persistence claim is made here.

| Class | # | What it pins |
| --- | --- | --- |
| `ParcelCodecTest` (new) | 21 | Frame grammar of one opaque UTF-8 `String` per QR frame with `FRAME_MAGIC = "FLITPARCEL1"`, CRC32 per frame and caps `MAX_FRAMES = 64`, `MAX_CHUNK_BYTES = 512`, `MAX_AIR_GAP_PARCEL_BYTES = 131072`; `parse` yields a `FrameDescriptor(parcelId, seq, count, totalBytes, crc)` on framed input and `null` on malformed input; `encodeParcel` uses `CourierEnvelope.seal`, `complete` verifies frames/CRC/sequence/count/digest/recipient and opens through `CourierEnvelope.open`, returning the exact `ParcelPayload(parcelId, recipientFp, signerFp, sha256, bytes)`; round-trips over single-frame and multi-frame payloads with scrambled frame order. |
| `ParcelCodecTamperTest` (new) | 10 | Refusals at the frame seam: a changed byte under the per-frame CRC, bad magic or scheme, out-of-range `seq`/`count`/chunk and byte caps, a missing frame, a whole-parcel digest mismatch, a forged envelope signature and a wrong recipient fingerprint all return `null` from `parse`/`complete` with no bytes released; a pairing-code string is never accepted as a parcel. |

#### Per-drop key additions — 44 cases in the final XML

The three classes below are the 2026-12-09 per-drop content key protocol layer.
They are JVM-pure, live in `protocol/src/test/kotlin/app/flit/share/protocol/`
and drive the real `DropKeyCore`, `CircleCore` and `TlsIdentity` seams. Their
final XML case counts are 18 + 14 + 12; the three added `wrapVerified` cases in
`DropKeyCoreTest` are named in §4.8. At the 2026-12-09 layer the counts stood at
15 + 14 + 12 = 41. No app, CLI, harness, camera or device claim is attached to
these cases.

| Class | # | What it pins |
| --- | --- | --- |
| `DropKeyCoreTest` (new) | 18 | Seeded `mint()` yields distinct 32-byte keys and 12-byte IVs independent of the group key, invite and file hash; `keyId(dropId, keyGen)` is `"$dropId:$keyGen"`, stable for one signed event and changed by a new generation; canonical bytes for the existing event verbs stay byte-identical; a keyed `file` event create/sign/verify, duplicate and out-of-window id refusal with an injected clock, oversized-body id accounting, and a `rekey` fold leaving old drop bodies unchanged. The three added cases pin `wrapVerified` fingerprint/certificate binding and are named in §4.8. |
| `DropKeyBodyTest` (new) | 14 | `CircleCore.validFileBody` accepts exactly the legacy `{name,size,sha256}` set or the keyed `{name,size,sha256,keyGen,cipherSha,iv,wraps}` set and refuses a partial, extra or mistyped field, a non-integer or negative `size`, a non-positive `keyGen`, an `iv` not decoding to 12 bytes, an empty or over-cap `wraps` map, a lowercase or blank fingerprint, a value over `MAX_WRAPPED_CHARS = 512` and a body over 16 KiB; `keyedFileBody(...)` emits a body that passes the gate, `parseFileKeyBody` returns the same typed fields and returns null for every body the gate refuses; on a signed keyed body, changing `cipherSha`, `iv` or any wrap fails `CircleCore.verify` before unwrap. |
| `DropKeyCryptoTest` (new) | 12 | `wrap` emits one Base64 entry per uppercase fingerprint with a usable certificate, at most `MAX_WRAPS = 32`, each within `MAX_WRAPPED_CHARS`, and refuses a larger roster rather than trimming it, with no entry for a member lacking a usable certificate; `unwrap` returns the 32-byte key under the matching `TlsIdentity` and returns null without throwing or writing a pin for a wrong identity, an absent fingerprint, malformed Base64 or bad OAEP padding; `seal` emits exactly `IV || ciphertext || tag` and `open` round-trips byte-exactly for seeded 0, 1, 8192-byte and 3 MiB inputs; flipping an IV, ciphertext or tag byte, using another key or changing the `FLITDROP1` AAD returns null; a rebuilt drop whose plaintext digest differs is refused. |

#### Delivery receipt additions — 24 cases

The three classes below are the 2026-12-11 delivery receipts protocol layer.
They are JVM-pure, live in `protocol/src/test/kotlin/app/flit/share/protocol/`
and drive the real `CircleCore` and `CircleEvent` seams. Their case counts
(12 + 6 + 6) come from a clean `--rerun-tasks` run and are included in the
green 588. Receipt is an
additive Circle event carried in `CircleRequest.events`; no route, header, DTO
field or v2.2 status changes, and no device claim is attached to these cases.

| Class | # | What it pins |
| --- | --- | --- |
| `ReceiptBodyTest` (new) | 12 | `CircleCore.RECEIPT_BODY_KEYS` is exactly the closed set `{dropId,sha256,keyId}`; `validReceiptBody` accepts only non-blank bounded strings and refuses a partial, extra, mistyped, blank or over-cap field, including any `landedMs` key; `keyId` is provenance only and `createdMs` remains the event time; `CircleEvent.RECEIPT = "receipt"` is the eleventh verb in `TYPES` with the existing verb values unchanged. |
| `ReceiptTallyTest` (new) | 6 | `CircleCore.receiptAuthors(events, dropId, sha256, members)` filters receipt events by exact body `dropId`/`sha256`, verifies the signature, keeps only current members and returns a deduplicated set of author fingerprints with at most one receipt per member (latest wins); a forged author, a non-member, a mismatched drop or digest and a failed signature contribute nobody; non-receipt events and the existing kick/vote tallies are unaffected. |
| `ReceiptCanonicalTest` (new) | 6 | Canonical bytes stay byte-identical for the existing verbs and cover `dropId`, `sha256` and `keyId` for `RECEIPT` with `sig`/`cert` excluded, so changing any covered field changes the bytes; replay windows and the bounded seen set are unchanged, and the additive receipt event adds no route, header, DTO field or v2.2 status. |

#### Circle pad additions — four classes, 43 cases

The four classes below are the 2026-12-15 circle pad protocol layer. All are
JVM-pure, live in `protocol/src/test/kotlin/app/flit/share/protocol/` and drive
the real `PadCore`, `CircleCore` and `CircleEvent` seams. Their 16 + 9 + 11 + 7
= 43 cases are recorded from the JUnit XML and added on top of the green 588;
the §2 module table and the 240 + 281 + 67 = 588 figure stay at the last green
run until a fresh `--rerun-tasks` run records the post-layer figure. No app,
CLI, harness, device walk, new route, header, DTO field, store field or v2.2
status is attached to these rows.

| Class | # | What it pins |
| --- | --- | --- |
| `PadBodyTest` (new) | 16 | `PadCore.validPadBody` accepts exactly `PAD_PUT_KEYS = {op,key,text}` when `op == "put"` and `PAD_DEL_KEYS = {op,key}` when `op == "del"`, refusing an extra or missing key, a wrong-typed `op`/`key`/`text`, an unknown `op`, a blank or over-cap `key` (`MAX_KEY_CHARS = 128`) and a blank or over-cap `text` (`MAX_TEXT_CHARS = 4096`), with every accepted body at or below `CircleCore.MAX_BODY_CHARS`; `padPutBody`/`padDelBody` emit bodies the gate accepts, including a put at the text cap, and malformed JSON, blanks and oversized fields are refused rather than thrown. |
| `PadViewTest` (new) | 9 | `PadCore.view(events, circleId)` keeps only `pad` events for the given circle with a body the gate accepts, ignoring other verbs, other circles and malformed bodies, and applies `CircleCore.dedup` so a repeated id counts once without calling `CircleCore.verify`; per `key` the operation with the greatest `(lamport, authorFp, id)` wins — a `put` sets the text, a greater-key `del` tombstones the key and an older `del` is inert — with `PadView.lines` sorted lexicographically, tombstoned keys omitted and at most `MAX_LINES = 256` entries; opposite delivery orders and a re-fold of the same events give byte-identical views. |
| `PadMergeTest` (new) | 11 | `PadCore.merge` applies the same per-key greatest-order-key rule to views already derived from separate event sets, so `view(a + b) == merge(view(a), view(b))` for disjoint and overlapping sets and for either merge order; a tombstone in one view wins over an older put in the other, re-merging a view is idempotent and a merged view never grows past `MAX_LINES`. |
| `PadCanonicalTest` (new) | 7 | `CircleEvent.PAD = "pad"` is the twelfth verb in `TYPES` with the existing verb values unchanged; `CircleCore.canonical` covers the same seven fields except `sig`/`cert` for `pad`, so a `pad` event minted by `CircleCore.create` verifies and a forged `authorFp` or a changed covered field fails `verify`, while canonical bytes, replay order, `dedup` and `accept` for the existing verbs stay byte-identical and no route, header, DTO field or v2.2 status is added. |

### 4.2 App — 324 tests in the final green XML

All are JVM tests of Android-free seams: codecs, reducers, pure policy math and
rendered QR pixels. They never start the Android framework, an engine or a
radio.

| Class | # | What it pins |
| --- | --- | --- |
| `FlitBleTest` | 36 | BLE record codec (extended/legacy, fingerprint+alias, codepoint-safe clipping, garbage refused, both self-ghost kinds), arbiter (AP up dark, no-AP on, hidden listen-only, missing perm/dead radio off), rank rule, contextual ask book, 10-on/45-off duty constants and injectable phase, live-peer stop rule. |
| `FlitBluetoothTest` | 14 | Android 12 permission split and legacy API 26-30 set, all-grants rule, RFCOMM backoff doubling/cap/no overflow, retry logs, quiet cancellation, transient accept failure re-listens, bounded handshake. |
| `FlitCircleHostTest` | 22 | Wrong circle/unknown action refusals, invite + fingerprint gate on JOIN, signed-log return, outsider/blank SYNC refusal, verify-before-fold, cert cannot claim another fingerprint, wrong type/oversized/replayed skip, leave/kick/role governance and rekey invariants, departed member refused, replay roll-off, bounded log and roster, repeatable answers. The three added cases pin a departing officer unable to smuggle a rekey past the host in either fold order, a forced rotation outranking a same-batch rekey, and an admin-signed rekey via sync admitted without a host rotation (§4.8). |
| `FlitCircleStoreTest` | 31 | Store codec and tolerant decode (defaults, corrupt, duplicate, oversized trim, negative generation), roster folds (join/leave/kick/vote/role/rekey), generation saturation, per-fingerprint delivery records (upsert/status/remove) untouched by event folds. The six added cases pin officer-only rekey with bounded payloads, an officer unable to hand off a key in the same batch as its leave, and order-independent pre-batch authority that vetoes only the leaver (§4.8). |
| `FlitCircleSyncTest` | 56 | Backoff escalation/cap, Lamport next, canonical message/poll/vote/file/kick/role/rekey bodies, strict outbound and inbound body gates, fan-out peer normalization and concurrency cap (4), per-peer watermarks, delta-only posts, refused delta not acked, calm full-log repair after ack, backed-off repair. The 29 added cases pin the restored ingress replay guard (verify before id reservation, canonical dedup, window and batch bounds), a JOIN proof minted for the host/circle/invite/member/clock with a claimed fingerprint that is not the signer refused, roster roles applied to rekey with JOIN never folded, and courier receive refusing officer-signed governance (§4.8). |
| `FlitEngineTest` | 31 | History payload round-trip and corrupt decode, self-ghost dedup (with/without identity), loopback manual junk dropped, direct radio row labels without phantom port, manual stub hidden by real device. This is the final XML count, including the parity cases listed in §4.4 and §4.5 and the one added relay-hunt case named in §4.8. |
| `FlitKnownTest` | 8 | Known-device codec round-trip, tolerant decode, QR-only default/toggle, latest-put route policy, forget, keys under the `flit` namespace. |
| `FlitMirrorTest` | 5 | Diff offers only ledger-unmarked files, zero-length write-in-progress excluded, size change mirrors again, token survives round-trip, re-notice quiet. |
| `FlitQrTest` | 22 | v2/v3 URI round-trips (including relay-only codes and v3 with no key on the wire), order/dupe resolution, junk refused, on-screen and camera-shaped frames decoded (canvas-gray, RGB, y-plane with strides, rotation), skewed non-code stays calm, unicode alias. |
| `FlitRosterTest` | 12 | Batch merge semantics (token union, age kept, aliases, mid-flight merge, last-token removal, first-occurrence twins), sharing-desired codec and reducer, restore only when desired and neither running nor starting. |
| `FlitRouteTest` | 7 | Bluetooth MAC route distinct from TCP twin, legacy TCP route decode, typed relay parse, candidate priority order and dedup, calm hunt backoff, fingerprint-only accept rule. |
| `FlitSpeedEdgeTest` | 4 | First huge sample only seeds baseline, irregular intervals, decreasing/repeated counts never negative, 100× jump limited. |
| `FlitSpeedTest` | 4 | Repeated samples in one paint window do not inflate rate, no negative/gigabit spike, reset forgets prior baseline. |
| `FlitSpoolTest` | 5 | Start sweep reclaims killed-session copies only; young staging kept; queue whitelist wins; crush copies are litter; look-alikes/directories ignored. Real mtimes on a temp root, no Robolectric. |
| `FlitSubnetTest` | 8 | /24 and /30 host walks, self/network/broadcast excluded, /31-/32 and out-of-range prefixes refused, IPv6 not walked, /16 over the 1024-knock cap refused, boundary at 253/252, labels. |
| `SharingRestoreTest` | 11 | Fresh row not desired, absent/blank/corrupt decode safe, `with` flips and stamps, envelope can grow, idempotent encode/decode, old bare-word rows, restore only after process death and not when already up/starting. |
| `SheetUiTest` | 3 | Initials from first two words, stable in-range hue per fingerprint, count phrasing rule of one. |

#### Courier app additions — 18 cases

The three app test files below belong to the 2026-12-01 App layer. Per-class
counts come from the JUnit XML after a green `--rerun-tasks` run: 11 + 4 + 3 =
18 cases, taking the app module from 210 to 228 and the rig from 378 to 396 in
§2. No row below carries device evidence.

| Class | # | What it pins |
| --- | --- | --- |
| `CourierCardUiTest` (new) | 11 | Android-free JVM class: `carryOutcome` maps `waiting`/`handed`/`failed` exactly and reads blank, mixed-case and unknown values as `WAITING`; `courierLine` returns the five exact copy rows (courier waiting, courier failed, courier handed, recipient, third member) and never applies a non-courier device's `HANDED` outcome; `aliasOf` bounds a label at `MAX_ALIAS_CHARS = 64` with an `fp.take(8)` fallback; a long Base64-like `name` and `fromFp` stay out of the copy; `courierLine(null, …)` and a blank SHA-256 draw no line; `openAffordance` covers `RESOLVING`/`LANDED`/`ABSENT`, case-folds hashes and gives `ABSENT` for a blank or unmatched SHA; landed precedence holds while a carry line exists. No Android imports, no Robolectric, no `parcel` event or envelope constructed. |
| `FlitCircleStoreTest` (added cases) | 4 | `courierBag` absent on old rows via the blank default, `encode`/`decode` roundtrip, tolerant decode of a corrupt bag row to an empty bag, and a bag write through `update` leaving events, roster and deliveries intact. |
| `FlitCircleSyncTest` (added cases) | 3 | `circleBodyValid` accepts only the closed `{"toFp","envelope"}` key set for `PARCEL` and refuses blank, oversized and wrong-typed fields; `circleParcelBody` mints a two-key body that passes that gate and carries the envelope verbatim, with no `sha256`, path or token key. |

These three sync cases pin the gate and the minted body; they do not claim a
runtime fold. The app fold path is `FlitCircleSync.fresh() → persistEvents() →
FlitCircleStore.upsert`, not `merge`.

The additive `CircleScreen(...)` `courierHandoffs` parameter is a CODE check, not
a counted runtime case: an old-style call without it must still resolve.

#### App QA additions — 11 cases

The cases below are part of the current 257 app / 588 rig baseline, added after
the 2026-12-01 App layer. They are counted from the JUnit XML, not from a target
figure, and carry no device evidence.

| Class | # | What it pins |
| --- | --- | --- |
| `FlitCircleSyncTest` (added cases) | +10 | `postParcel` signs a closed two-key body and offers the emitted event to peers; an invalid envelope is refused without signing or fanning out; `courierTrack` keys normalised shas by the emitted parcel event id and publishes each handoff once; `concludeHandoff` publishes `failed` on refusal or throw and keeps the sha for a later handoff; blank ids/shas publish nothing; `courierReceive` folds a parcel already in the local log, opens one addressed to me into a `waiting` handoff, refuses a forged parcel while folding ordinary verified events and rejects opened events from a non-member author or privileged verb; rejoin keeps the courier bag alongside delivery rows. |
| `CourierCardUiTest` (added cases) | +1 | Courier copy rows match fingerprints case-insensitively after trimming. |

#### QR parcel app additions — 18 cases (source-counted)

The class below belongs to the 2026-12-07 QR parcel App layer. It is a new
Android-free JVM class in `app/src/test/kotlin/app/flit/share/`, driving the
real `ParcelCodec.complete(frames, recipientFp, identity)` with a recipient
fingerprint and the receiving `TlsIdentity`. Its 18 cases are included in the
green 588 (257 app) from a clean `--rerun-tasks` run. The codec's own CRC,
digest, signature and recipient cases stay in §4.1; no camera, Android
framework, Robolectric or device claim is made here.

| Class | # | What it pins |
| --- | --- | --- |
| `ParcelScanStateTest` (new) | 18 | Android-free JVM reducer cases in `app/src/test/kotlin/app/flit/share/`: in-order and out-of-order assembly, duplicate `(parcelId, seq)` ignored with the first copy kept, a missing frame leaving `complete()` null, a mixed `parcelId` frame ignored, count mismatch and sequence past `MAX_PARCEL_FRAMES = 64` refused, and a second `complete()` returning null after a verified payload. Each case asserts the exact accepted frame set and whether `complete()` yields a `ParcelPayload`; CRC/digest/signature/recipient failures stay null through the real `ParcelCodec.complete(frames, recipientFp, identity)` seam, with the codec's own tamper cases left in §4.1. No Android import, no Robolectric, no camera or device evidence. |

### 4.3 Harness — 92 tests in the final green XML

The two baseline classes run the **production** endpoints in one JVM. `CircleHarnessTest`
hosts through `ConsoleDelegate.circleHost` behind a real TLS `ProtocolServer`
and dials with a real `ProtocolClient` (`harness/src/test/kotlin/app/flit/share/harness/CircleHarnessTest.kt:32-38`);
`RelayServerTest` uses `RelayLinkProvider` and `RelaySubscriber` against the
same `RelayServer` the CLI runs, with TLS inside the blind pipe
(`.../RelayServerTest.kt:36-42`).

| Class | # | What it pins |
| --- | --- | --- |
| `CircleHarnessTest` | 18 | Two-node walk: bad invite refused, JOIN returns key, owner appoints admin, admin kick removes third member and rekeys, departed member refused, poll re-vote keeps both votes, file announcement is name/size/sha256 only, file bytes land hash-exact with local status moving pending→sent. Plus: stale signed ROLE event refused by replay guard, CLI errors distinguish host-refused from no-answer, and delivery outcome maps a returned report but never a throw (a thrown carrier stays pending). The nine earlier added cases pin a JOIN-proof gate that refuses an invited attacker without a signed proof, host-answer signing pinned to the host fingerprint, rotation learned only from host-signed REKEY events and never from a sync key, a fresh gate dropping a certificate/author mismatch, SYNC unable to inject JOIN or another member's signed event, officer-only rekey, a departing officer unable to hand off the key in either fold order, and a forced rotation outranking a same-batch rekey (§4.8). Four cases added on 2027-01-05 pin the CLI `circleFreshEvents` gate (a host-answer JOIN and a member REKEY are dropped while an ordinary verified event folds), a departed officer's high-generation hand-off left inert while the host's lower-generation rotation installs the key, the stale `--host-fp` guard (a pin from another circle does not vouch for a join to a new circle and a conflicting pin for the same circle is refused), and a join that resolves the pin before rewriting the circle id so the stale pin does not survive. |
| `RelayServerTest` | 6 | Real 3 MiB TLS send lands hash-exact through the pairer; an 8 MiB carrier death throws `TransferUnreachable`, keeps the receiver prefix and resumes hash-exact after the stale window; wrong token refused on the wire; unpaired subscription times out cleanly; dead relay dial reports unreachable, not a send report. The added case writes `OK\n` and a plausible TLS record prefix in one packet, then reads the reply a byte at a time so the bytes after the newline stay readable on the paired stream instead of being swallowed by a buffering reader. |

#### Courier CLI additions — 22 cases

The two classes below cover the 2026-11-30 courier CLI layer. They are not part
of the 10-test harness baseline above. Counts come from a fresh `--rerun-tasks`
run. Neither class carries device evidence — the bag moves between directories
on one box.

| Class | # | What it pins |
| --- | --- | --- |
| `CourierCliTest` | 21 | Pure seams in `harness/src/test/kotlin/app/flit/share/harness/CourierCliTest.kt`: `courier.state` encode/decode round-trip and tolerant decode of absent, short and corrupt files; the `nextState` transition map (`pending → carried` on CARRY, `pending|carried → delivered` on RECEIVE_OK, anything → `refused` on RECEIVE_REFUSED, expiry on SWEEP at `nowMs >= expiresMs`) including a thrown `open` leaving the row `pending`; the recipient-cert fingerprint gate against `--to`; TTL and quota decisions with an injected `nowMs`; deterministic `<id>.parcel` naming and the state-count line. The six added cases pin a deterministic one-line-per-row codec, tolerant decode of unknown keys, usage naming every subcommand, bad arguments before touching state, status and refused-open exit codes, and that opening a parcel never folds a JOIN or a REKEY into Circle state (§4.8). No sockets and no endpoints. |
| `CourierCarrierHarnessTest` | 7 | In-process CLI handoff with two CLI-state nodes on distinct `FLIT_HARNESS_HOME` values and fresh temp roots: `drop` seals a signed event into a bag, `carry` marks rows carried, `receive --bag` opens the parcel and folds the event into `circle.state`; a third-fingerprint parcel stays `pending` and is re-exported; a tampered parcel is refused and quarantined; an expired parcel is swept; a duplicate id does not double-fold; a bad `--to` cert writes no file. Decoding goes through `CourierCarryCore` and opening through `CourierEnvelope`, never a lookalike cipher; payloads are seeded and rows asserted by exact value. |

#### Harness QA additions — 7 cases

The cases below are part of the current 52 harness / 588 rig baseline, added
after the 2026-11-30 CLI layer. They are counted from the JUnit XML and carry no
device evidence — the bag moves between directories on one box.

| Class | # | What it pins |
| --- | --- | --- |
| `CourierCliTest` (added cases) | +5 | Courier constants pin the on-disk and exit-code contract; bag and state paths root at `FLIT_HARNESS_HOME` with the fixed names; an explicit `--bag` overrides only the bag path, a relative one roots under the harness home and an absolute one is kept; the parcel file is the envelope id plus the fixed suffix under the bag. |
| `CourierCarrierHarnessTest` (added cases) | +2 | An unopened parcel survives production encode → decode → encode byte-for-byte; a parcel whose open cannot conclude stays `pending` and returns success. |

#### QR parcel CLI additions — 12 cases

The two classes below belong to the 2026-12-04 QR parcel CLI layer. Both are
in-process harness tests in `app.flit.share.harness`, driving the `ParcelCli`
adapter through the protocol `ParcelCodec` with PNG frames rendered and read
on one box. The 9 + 3 cases are included in the current 52 harness / 588 rig
count from a `--rerun-tasks` run. No socket, app module or device walk is
involved.

| Class | # | What it pins |
| --- | --- | --- |
| `ParcelCliTest` | 11 | Pure seams of `ParcelCli` and the harness wiring: `usage()` carries one `parcel` line covering `encode`/`decode` and `ParsedArgs.VALUE_FLAGS` gains `--out` beside `--dir`, `--to` and `--cert`; bad args and a missing cert, dir, out or input file end with the exit-2 reason and no frames; `--cert` holds Base64 DER whose `TlsIdentity.fingerprintOf` must equal normalised `--to`, and the cert object goes to `ParcelCodec.encodeParcel` with no pin lookup or self-addressed fallback, a mismatch refusing before any PNG is rendered; frame names are deterministic 1-based `frame-%04d.png` under `--dir` and no file name travels in a frame. The two added cases pin repeated value flags kept for poll options and verification failure for a parcel addressed elsewhere; an over-cap payload is refused before any frame is rendered (§4.8). |
| `ParcelCliHarnessTest` (new) | 3 | Two distinct `FLIT_HARNESS_HOME` values and fresh temp roots: `parcel encode <file> --dir D --to FP --cert PATH` renders opaque frame strings to zxing PNGs (768×768, EC `M`, margin 2, UTF-8) and returns `EncodeResult(dir, frames, sealedChars, sha256)`; `parcel decode --dir D --out PATH` in the other home reads them with `MultiFormatReader`, keeps the frame strings, calls `ParcelCodec.complete(frames, identity.fingerprint, identity)` and writes `ParcelPayload.bytes` to `--out`, returning `DecodeResult(out, bytes, sha256, signerFp, recipientFp)`; the round-trip lands byte-for-byte with equal sha256, a missing or tampered frame gives verification failure (exit 3) with no output file, and a bad `--out` or missing dir gives exit 2. |

### 4.4 2026-12-10 per-drop parity integration additions — 31 cases

The four classes below are the 2026-12-10 Integration layer that wires the
2026-12-09 per-drop content keys into the app and the CLI. Two are new JVM-pure
classes and two add cases to existing app classes. Counts come from a clean
`--rerun-tasks` run and are included in the green 588. The receive side is
test-evidenced only and no device walk is claimed on this date.

| Class | # | What it pins |
| --- | --- | --- |
| `DropParityTest` (new, protocol) | 6 | JVM-pure parity of the 2026-12-09 key core with the keyed wire shape: `mint()` yields a fresh 32-byte key and 12-byte IV per drop, and identical plaintext bytes give different sealed bytes and `cipherSha`; `keyedFileBody`/`parseFileKeyBody` round-trip the exact `KEYED_FILE_BODY_KEYS = {name,size,sha256,keyGen,cipherSha,iv,wraps}` set while the legacy `FILE_BODY_KEYS` set stays valid and partial, extra or mistyped sets are refused; `CircleCore.verify` binds `iv`, `cipherSha` and each uppercase-fingerprint wrap so a changed wrap, IV or cipher digest is rejected before unwrap; `wrap` caps at `MAX_WRAPS = 32` with `MAX_WRAPPED_CHARS = 512` values, and `unwrap` under a wrong identity or a missing fingerprint returns null; `seal`/`open` are byte-exact and a flipped IV, ciphertext or tag byte is refused. No app, CLI, socket or device claim. |
| `FlitCircleSyncTest` (added cases, app) | 4 | JVM app gate and event seams for the keyed drop: `postFile(body)` posts a signed `CircleEvent.FILE` carrying the caller-built keyed body verbatim and the event passes `CircleCore.verify`; `circleBodyValid`'s FILE branch accepts exactly the legacy `{name,size,sha256}` or keyed `{name,size,sha256,keyGen,cipherSha,iv,wraps}` key sets and refuses partial, extra, blank, mistyped and oversized bodies, with each wrap value bounded by `MAX_WRAPPED_CHARS`; the legacy three-argument `postFile` and `circleFileBody` stay unchanged, so an old drop still passes the same gate. No Android import, no Robolectric, no runtime fold claim. |
| `FlitEngineTest` (added cases, app) | 11 | JVM app delivery and receive seams: `circlePostFile` gathers current members and their certs, refuses with the `no recipient key material` / over-cap log line and posts nothing when no usable cert exists or the roster exceeds `MAX_WRAPS = 32`, and keeps the source spool; a posted keyed drop pushes the sealed `*.flitdrop` path with `sendFiles(..., multi = false)` and a declared `FileInfo.sha256` equal to `cipherSha`, with a returned report concluding and a throw or no route staying `PENDING`; on receive, a landed digest matching `cipherSha` is unwrapped with the local identity, opened and checked against the announced plaintext `sha256` before the plaintext is written and recorded, while a missing wrap, a tag failure or a digest mismatch logs `circle drop: cannot open` and adds no ledger entry; a landed file with no `cipherSha` match takes the unchanged legacy plaintext path; `NO_KEY` is terminal and all-terminal deletes the sealed spool. No radio, camera or device claim. |
| `DropKeyCliTest` (new, harness) | 10 | In-process CLI parity in `app.flit.share.harness` driving `circle file` through `Harness.circleFileAnnouncement`'s keyed overload: two CLI identities with distinct `FLIT_HARNESS_HOME` values and fresh temp roots; the sender wraps to current members, seals a `*.flitdrop` copy, posts the signed keyed body and delivers the sealed bytes with `FileInfo.sha256 == cipherSha`, and the receiver prints `drop-open <name> <plainSha> (<bytes> bytes)` and lands plaintext hash-exact to the announced `sha256`; a third identity with no wrap prints `drop-refused ... no wrap` and lands no plaintext, a tag or digest failure prints `drop-refused ... open failed` with no plaintext left, and a tampered keyed event is skipped at the signature gate; repeating identical bytes yields a different `cipherSha` and wrapped key; `circle post --file` stays a legacy plaintext three-key body. No new route, header, DTO field or status code; no device walk. |

### 4.5 2026-12-14 delivery receipts parity integration additions — 14 cases

The three rows below belong to the 2026-12-14 Integration layer that wires the
2026-12-11 receipt core into the app and the CLI. Two add cases to existing app
classes and one is a new harness class. The 3 + 6 + 5 cases come from a clean
`--rerun-tasks` run and are included in the green 588. All are in-process JVM
tests with no device
walk, new route, header, DTO field, QR parameter or v2.2 status.

| Class | # | What it pins |
| --- | --- | --- |
| `FlitCircleSyncTest` (added cases, app) | 3 | JVM app gate and publish seams for the receipt verb: `circleBodyValid`'s RECEIPT branch delegates to `CircleCore.validReceiptBody`, accepting a body of exactly `{dropId,sha256,keyId}` and refusing a partial, extra, mistyped, blank or over-cap field and any `landedMs` key; `postReceipt` signs a closed three-key body from the landed keyed `file` event and offers the event through the existing `post()` fan-out path; the existing verbs and the legacy file-body gate stay unchanged. No Android import, no Robolectric, no device claim. |
| `FlitEngineTest` (added cases, app) | 6 | JVM mint-and-fold seams: after a keyed drop lands hash-exact and the plaintext digest matches the announced `file` event `sha256`, the engine reads that event's `id` and its `keyGen`/`wraps`, mints via `CircleCore.create(..., RECEIPT, {dropId,sha256,keyId})` and publishes through `post()`; a digest mismatch, wrong identity, `NO_KEY`, a carrier throw or a legacy plaintext drop mints nothing; `computeFeedData` folds `CircleCore.receiptAuthors` into the confirmation count with `remember` memoization while sender-local delivery statuses stay unchanged; `keyId` is provenance only and never selects a receipt. No radio, camera or device claim. |
| `ReceiptCliTest` (new, harness) | 5 | In-process CLI parity in `app.flit.share.harness`: `circle receipt --sha SHA [--drop-id ID] [--host ip[:port]]` requires a joined circle, resolves the `file` event by plaintext `sha256` (or the given `--drop-id`), derives `keyId` from that signed keyed body and posts a signed receipt with body exactly `{dropId,sha256,keyId}`, refusing with no event when the drop or key id is absent; `circle sync`/`console` prints the confirmation count from `CircleCore.receiptAuthors`; a malformed receipt is refused by the host gate and a replayed id leaves the count at one; no value is fabricated. No new route, header, DTO field, QR parameter or v2.2 status; no device walk. |

### 4.6 2026-12-16 circle pad app + CLI integration additions — 23 cases

The two rows below belong to the 2026-12-16 Integration layer that wires the
2026-12-15 pad protocol into the app surface and the CLI. One is a new
Android-free JVM class in `app/src/test` and one is a new in-process harness
class in `harness/src/test`. Their counts from the JUnit XML are 15 and 8;
the module totals at that layer were 330 + 309 + 75 = 714, against the
359 + 323 + 87 = 769 and 362 + 324 + 87 = 773 snapshots and the current
362 + 324 + 92 = 778 (§2). No device walk, new route, header, DTO field, store
field or v2.2 status is attached to these rows.

| Class | # | What it pins |
| --- | --- | --- |
| `PadUiTest` (new, app) | 15 | Android-free JVM tests of the `PadUi` seam: `rows(view)` yields lexicographically ordered `PadRow(key, text)` pairs from a `PadView`, with tombstoned keys omitted; `keyError` and `textError` reject blanks and enforce the `PadCore` bounds `MAX_KEY_CHARS = 128` and `MAX_TEXT_CHARS = 4096`. No Android import, no Robolectric, no device claim. |
| `PadCliTest` (new, harness) | 8 | In-process CLI parity in `app.flit.share.harness` for `circle pad put --key K --text T`, `circle pad del --key K` and `circle pad show`: put and del bodies come from `PadCore.padPutBody`/`padDelBody`, `show` prints the `PadView` lines, and bad arguments or blank/over-cap fields are refused with no event posted. No new route, header, DTO field or v2.2 shape; no socket or device claim. |

### 4.7 Idea and wiring classes — final XML counts

The 2026-12-21 Integration layer wired the three WIRE prototype cores listed
in `docs/ideas/PROGRAM.md` into existing surfaces. Their classes and the app
wiring cases are counted from the JUnit XML:

| Class | # | Module |
| --- | --- | --- |
| `PadSearchTest` | 17 | `protocol/` |
| `PeerAliasTest` | 12 | `protocol/` |
| `NoiseBudgetTest` | 18 | `protocol/` |
| `PadUiTest` (search cases) | 7 | `app/` (included in the 15 above) |
| `FlitBleTest` (budget cases) | 6 | `app/` (included in the 36 above) |

The four pad protocol classes in §4.1 keep their recorded counts:
`PadBodyTest` 16, `PadViewTest` 9, `PadMergeTest` 11 and `PadCanonicalTest` 7.
No device walk, new route, header, DTO field, store field or v2.2 status is
attached to these classes.

### 4.8 Signed JOIN proof, shared Circle authority and later gates — 15 classes

The JOIN-proof wave adds one stateless admission proof and one shared fold gate, then
drives both through the app, the host and the CLI. The proof is a SHA-256 digest
of a length-prefixed `FLITJOIN1` nonce over the normalized host fingerprint,
circle id, invite, member fingerprint and a five-minute bucket, carried as a
43-character unpadded Base64URL value in the closed `{proof}` body of a signed
JOIN event. The host recomputes it from the request and answers with one of three
exact refusals: `join proof required`, `invalid join proof` or `join proof replay`.
`CircleAuthority.foldable` is the single rule used by the app, the CLI
and the host: JOIN never folds out of an answer, REKEY folds only for a current
owner or admin, ordinary verbs fold only inside their own circle, and the rule
is total on hostile input. The app ingress replay guard verifies before
reserving an id, and the CLI pins host answers to `--host-fp` and learns
rotations only from host-signed REKEY events. All cases are JVM-pure or
in-process from the final green `--rerun-tasks` run. No device walk, new route,
header, DTO field or v2.2 status is claimed, and a pre-proof JOIN is refused at
admission.

A later, smaller layer adds three `wrapVerified` cases in `protocol/` and one
relay-hunt case in `app/`, moving the inventory from the 769 snapshot to the
historical 773 snapshot. The 2027-01-05 CLI parity layer adds four cases in
`CircleHarnessTest` and one in `RelayServerTest`, and hardens the QR reader in
`ParcelCliHarnessTest` without adding a case, moving the inventory to the
current 778. Evidence here is CODE/PURE/SIM-HARN only: CODE for source-level
gate wiring, PURE for JVM-only rules, SIM-HARN for in-process runs over
production endpoints. No DEVICE observation is claimed. The last rows below
name them.

| Class | # | Module | What it pins |
| --- | --- | --- | --- |
| `JoinProofTest` (new) | 14 | `protocol/` | The bucket grid equals the five-minute clock skew and is total on hostile input; an independent golden vector and per-field binding for the length-prefixed `FLITJOIN1` nonce with no delimiter smuggling; a closed `{proof}` body holding one 32-byte unpadded Base64URL SHA-256 digest; gate refusals for malformed, padded, short, standard-alphabet, extra-key and over-cap bodies; exact host/circle/invite/member binding; a tampered signature, changed body or swapped certificate refused; stale and future buckets plus same-request duplicates are `join proof replay`; a forged author fingerprint neither verifies nor poisons the pin store. |
| `CircleAuthorityTest` (new) | 12 | `protocol/` | The full `CircleAuthority.foldable` truth table: JOIN never folds, REKEY needs a current OWNER or ADMIN author, all ten ordinary verbs fold only in their own circle, foreign circles and blank or unknown types refuse, fingerprint and role values are trimmed and uppercased, empty roles allow ordinary verbs but no governance, and hostile or oversized values stay total with non-authority fields unable to change the decision. |
| `CircleCoreTest` | 12 | `protocol/` | Unchanged at 12: create/sign/verify, forged author, replay window, bounded seen set, canonical bodies, convergence, tallies, route answers and join/sync through `LinkServer` stay byte-identical while the JOIN proof reuses the same signed event envelope. |
| `CircleHardeningTest` | 14 | `protocol/` | Three added route cases: a JOIN without a proof is a signed refusal that never reaches the delegate, a valid signed proof reaches it, and a forged proof is refused with a verifiable signature; all earlier bounds and the v2.2 status behavior stay unchanged. |
| `FlitCircleSyncTest` | 56 | `app/` | Twenty-nine added cases: `fresh` restores the ingress replay guard by verifying before an id is reserved, dedups in canonical order and keeps window, local-id and batch caps; `join` mints a signed proof bound to host, circle, invite, member and clock and refuses a claimed fingerprint that is not the signer; roster roles apply to rekey with JOIN never folded; courier receive refuses officer-signed governance even from a roster officer. |
| `FlitCircleStoreTest` | 31 | `app/` | Six added cases: only a current owner or admin may rekey with bounded payloads, an officer cannot hand off a key in the same batch as its leave, and pre-batch authority is order-independent and vetoes only the leaver. |
| `FlitCircleHostTest` | 22 | `app/` | Three added cases: a departing officer cannot smuggle its rekey past the host in either fold order, a departure forces a rotation above a rekey admitted in the same batch, and an admin-signed rekey via sync is admitted without a host rotation. |
| `CircleHarnessTest` | 18 | `harness/` | Nine earlier added cases over production routes: the JOIN proof gate refuses an invited attacker without a signed proof, `circleAnswerSigned` pins an answer to the host fingerprint, `mergeCircleAnswer` learns only host-signed rekeys and never a sync key, `circleFreshEvents` drops a certificate/author mismatch, a member cannot inject another member's or a JOIN event through SYNC, only an officer REKEY installs a key, a departing officer cannot hand off the key in either fold order, and a forced rotation outranks a same-batch rekey. Four cases added on 2027-01-05 pin the CLI `circleFreshEvents` gate against a host-answer JOIN and a member REKEY while an ordinary event folds, a departed officer's generation-99 hand-off left as inert history while the host's lower-generation rotation installs the key, the stale `--host-fp` guard (a pin from another circle does not vouch for a new circle and a conflicting pin for the same circle is refused), and `circle join` resolving the pin before rewriting the circle id so the stale pin is cleared. |
| `ParcelCliTest` | 11 | `harness/` | Two added cases: repeated value flags keep every occurrence for poll options and a parcel addressed elsewhere fails verification with no output; an over-cap payload is refused before any frame is rendered, and the earlier usage, bad-argument, certificate-gate and zero-padded frame-name cases stay intact. |
| `ParcelCliHarnessTest` | 3 | `harness/` | Unchanged at 3: in-process PNG encode/decode round-trip lands byte-for-byte, a missing or tampered frame fails verification with no output, and bad paths exit 2. Its PNG reader was hardened to use `TRY_HARDER` and QR-only decode hints and to retry integer scales 1..6 before declaring a frame unreadable; no case was added, and the production 768/1024/1536 render escalation is already recorded in §8. |
| `RelayServerTest` (added case) | +1 | `harness/` | The relay's OK reply is read a byte at a time up to the newline and bounded by the hello limit; a plausible TLS record prefix written in the same packet as `OK\n` remains readable on the paired stream, so a buffering reader cannot swallow the first handshake bytes (now 6 cases in §4.3). |
| `CourierCliTest` | 21 | `harness/` | Six added cases: deterministic one-line-per-row codec output, tolerant decode of unknown keys, usage naming every subcommand, bad arguments before touching state, status and refused-open exit codes, and receive folding neither JOIN nor REKEY out of an opened parcel. |
| `PadCliTest` | 8 | `harness/` | Unchanged at 8: `circle pad put|del|show` bodies come from `PadCore`, `show` prints the folded view, and blank or over-cap fields are refused with no event posted. |
| `DropKeyCoreTest` / `FlitEngineTest` (added cases) | +3 / +1 | `protocol/` / `app/` | `wrapVerified` binds every wrap to the fingerprint of the certificate it holds, refuses a fingerprint misbound to a foreign certificate, keeps only correctly bound entries in a mixed roster and refuses an over-cap roster; the relay hunt is a last resort needing an explicit `relayFor` opt-in with `AUTO` or `RELAY_ONLY`, and fails closed for a missing Known entry, an opted-out device or a `WIFI_ONLY`/`BLUETOOTH_ONLY` policy. |

## 5. What the suites establish, by flow

The per-class tables in §4 are the authority for counts.

- **Wire parity.** `DtoParityTest`, `LinkServerParityTest` and the CLI `bt-sim`
  run one unchanged v2.2 brain over three pipes: TCP/Netty, an in-memory duplex
  pipe, and the simulated Bluetooth carrier. The server side is `ServerCore`
  and `LinkServer` in all three; the client is the hand-rolled `PeerHttpClient`
  / `ProtocolClient`. No v3, no protocol fork.
- **Trust and crypto.** `TlsIdentityTest` / `PinCacheTest` cover fingerprint
  derivation, the bounded LRU pin store and cert-in-hand verification;
  `CircleCoreTest`, `CircleHardeningTest`, `FlitCircleHostTest` and `PanicTest`
  cover signatures, forged authors, replay windows and bounded sets. Host
  answers are pinned to the QR fingerprint; the group key is membership
  material, not encryption.
- **Resume and death.** `ChunkTrackerTest`, `PartialSidecarTest`,
  `ResumeAndTurboTest`, `ProtocolRefusalTest` and `LinkServerParityTest` prove
  prefix assembly, the sidecar written before the first body byte, both
  `Content-Range` spellings, the 409 no-base refusal, the failed-chunk-then-
  plain-resume path, hash-exact multi-socket landing, and the central death
  contract: a carrier death throws `TransferUnreachable` (never a send report),
  a returned report means the peer spoke, and kept bytes stay owed.
- **Relay.** `RelayCoreTest` is the pure state machine; `RelayServerTest` is the
  wire proof through the same code the CLI serves, including relay death and
  resume. The relay parses no protocol byte; TLS and the fingerprint proof
  run end-to-end inside the pipe.
- **Circle.** `CircleCoreTest`/`CircleHardeningTest` (pure event law),
  `FlitCircleStoreTest`/`FlitCircleSyncTest`/`FlitCircleHostTest` (app state,
  fan-out and host governance), `CircleHarnessTest` (two nodes over production
  routes), and `JoinProofTest` / `CircleAuthorityTest` (signed admission proof
  and the shared fold gate). JOIN never folds out of a host answer and REKEY
  folds only for a current officer (§4.8). The file-drop event body is exactly
  `{name,size,sha256}` — no path, no queue token, no bytes — and delivery status
  is sender-local, never gossiped. A `receipt` event carries
  `{dropId,sha256,keyId}` and is tallied by `receiptAuthors` as verified
  current-member authors, with no `landedMs` field and no new route.
- **Discovery and routing.** `FlitQrTest`, `FlitKnownTest`, `FlitRouteTest`,
  `FlitBleTest`, `FlitSubnetTest` and `FlitEngineTest` cover QR codecs, QR-only
  and per-device route policy, BLE advertising/arbiter/duty cycle, the subnet
  rescue sweep math and roster dedup. The radio-facing policy is JVM-testable;
  the actual RFCOMM socket path is not exercised on this bench.
- **Lifecycle and hygiene.** `SharingRestoreTest` and `FlitRosterTest` pin the
  persisted sharing-desired restore; `FlitSpoolTest`, `FlitSpeedTest` and
  `FlitSpeedEdgeTest` pin litter cleanup and progress; `SheetUiTest`
  pins small render rules.

## 6. Device-walk evidence table

Every row below is an observation recorded in a focused doc or commit body;
the level column is the strongest level actually available. A row marked
*not verified* is a limit.

A full-feature walk is planned at
[`docs/roadmap/device-walk-2026-12-31.md`](roadmap/device-walk-2026-12-31.md).
That file is a plan only: no walk has been performed under it, and it holds no
observation, log excerpt or landed hash. The date in the file name is the date
of the plan, not a walk date; no row below is sourced from it.

| Claim | Level | Exact observation | Source |
| --- | --- | --- | --- |
| Mid-body carrier death keeps the prefix and resumes hash-exact (Wi-Fi) | **On-device** | Fresh 300 MiB camera shot; box `kill -9` 0.8 s after `recv-start` with a 33,980,416 B part; phone logged `kept, sends when it reappears`, hunter re-probed, next offer `recv-resume … from 33980416 bytes`, then `recv-ok`; sha256 `fbc4684a30afd…` matches byte-for-byte, no part/sidecar left. Late kill (~90% of 800 MiB multi) took a clean full resend. | `docs/transport-bluetooth.md:85-95` |
| Receiver restart keeps an interrupted prefix | **On-device** | Killed sender + restarted receiver resumed hash-exact from a 128 MB prefix. | `docs/transport-bluetooth.md:60-65` |
| Blind relay pairs two peers and survives relay death | **On-device** | Box ran `flit-cli relay` and `flit-cli serve --relay 127.0.0.1:53317`; 200 MiB shot crossed the pairer (`hunt: relay answered as Flit (szszszs)`), then a 300 MiB shot was cut by `kill -9` on the relay; both ends re-subscribed, next hunt logged `recv-resume … from 31866880 bytes`, hash-exact with no leftover part. | `docs/transport-bluetooth.md:108-119` |
| Wi-Fi throughput ceiling | **On-device** | 512 MiB from the reference CLI to the phone in ~15.9 s, identical single-stream and multi-socket — 32.1 MiB/s (~270 Mbps). Ceiling is the link and phone storage, not framing. | `docs/bluetooth-carrier.md:279-287` |
| Circle file drop delivers real bytes hash-exact | **In-process harness** | 8,192 B landed hash-exact with matching sha256 and local delivery status moving pending→sent through the production server and client; no on-device drop is recorded for this case. | `CircleHarnessTest.kt:409-467` |
| Circle two-node coordination on hardware | **On-device** | Joined by v3 QR paste and by `flit://add` tap; CLI posted message rendered in the M3 feed with author/timestamp; phone as host logged `circle host: join` and `sync … event(s)`; changeable poll and two bot messages in the feed; full leave logged `circle: left fully — key discarded`. | `docs/circle.md:290-308,354-356` |
| Open file does not crash the app; sharing survives force-stop | **On-device** | Fresh 35 B drop landed (`c421969b…`) with "Landed here"; tap opened `ChooserActivity`, `pidof app.flit.share` stayed 15834, no `FATAL`/`am_crash`/`Force finishing`; back returned to the feed; `am force-stop` → new PID 16970 logged `egress pinned…` and `serving https://0.0.0.0:53317` with Home showing Stop. | `docs/open-file-and-sharing-restore.md:247-259` |
| Album mirror and dedup | **On-device** | Synthetic camera shot queued, sent and landed hash-exact in ~200 ms; a restart re-sent nothing. | `docs/transport-bluetooth.md:66-71` |
| Subnet rescue sweep finds a dial-only peer and keeps it fresh | **On-device** | 2026-11-14: `sweep 192.168.100.0/24: 253 hosts … found 1 (Flit (szszszs)@192.168.100.12)` with multicast out and no manual/QR trust; keeper held the row 2.5 min; hidden mode produced zero sweep lines. | `docs/bluetooth-carrier.md:288-341` |
| Interop with a real v2.2 peer | **Harness/sim** (loopback CLI) + **on-device** (wireless day 2) | `localsend-cli` v1.18.2 both directions: our receive `recv-ok`, our send `sent 1/1` byte-identical. Day 1's multicast row was later corrected: day 2 proved phone↔box multicast both directions once the app's announce TX was wired; it was not a permanent kernel failure. | `interop/README.md` T0/T1, W0-W5 and "Corrects day-1 T0-m" |
| BLE permission/radio/hotspot UX on one phone | **On-device** | 2026-10-29/30 walks: system permission sheet and enable dialog presented once each, radio flip lines, hotspot classifier in three parts, 8-minute idle soak with empty crash buffer. | `docs/bluetooth-carrier.md:192-262` |
| Phone-to-phone RFCOMM transfer | **Not verified** | No second phone; bench box has no Bluetooth adapter (`bluetoothctl list` empty). Only the toggle → `bluetooth link: listening` smoke and the pipe parity/`bt-sim` sim were run. | `docs/bluetooth-carrier.md:266-278` |
| Three-identity Circle coordination on hardware | **On-device** | 2026-11-26 baseline walk: phone host A (`992AD6C9…`) plus CLI bots B (`2A61BF69…`) and C (`49182145…`) in one circle, 3 members, gen=0; B's message reached C, C and the phone voted on a poll with latest-per-member tally, a 25-byte file drop from B landed hash-exact on the phone with `Landed here`, and B was promoted admin then kicked C with rekey and C refused as a non-member. | `docs/completions/2026-11-26-three-node-baseline.md` |
| Mesh beyond three identities / two physical phones | **Not verified** | The baseline has three identities — one phone and two one-shot CLI bots on one box. No second physical phone, no phone-to-phone RFCOMM and no larger mesh were on the rig; the older `docs/circle.md` limitation is superseded only for the exact three-identity case. | `docs/completions/2026-11-26-three-node-baseline.md:79-90` |
| Outbound phone→host Circle fan-out as a device observation | **Test-evidenced only** | Behavioural fan-out tests pass with an injected carrier; the 2026-11-24 walk could not produce a clean peer-side confirmation because demo bots' CLI state and their `serve` process shared identities. The 2026-11-26 walk repeated the gap: a phone-composer event rendered in the phone feed and logged `full-log repair to 2 peer(s)` with two `refused no answer` lines, but neither one-shot CLI puller's `circle.state` showed it. | `docs/circle.md:312-319,357-361`; `docs/completions/2026-11-26-three-node-baseline.md:35-41` |
| Wi-Fi Aware carrier | **Not verified (hardware absent)** | `adb shell pm has-feature android.software.wifi.aware` answered `false` on the rig phone (2026-11-21); no Aware carrier built. | `docs/transport-bluetooth.md:96-100` |
| MIUI autostart/battery and Android 15 `dataSync` 6 h cap | **ROM/OS limit** | MIUI can still kill the process; the persisted row restores sharing on the *next* open, not autonomously. At the `dataSync` cap `onTimeout()` drops the notification to avoid a system crash while a Flit screen is open; no background guarantee after the last surface closes. | `docs/open-file-and-sharing-restore.md:198-217`, README:86-87 |

## 7. Reproduction recipes

Commands are from the shipped CLI usage (`harness/src/main/kotlin/app/flit/share/harness/Harness.kt:136-176`). Build first:

```bash
cd /home/szszszs/flit
source env.sh
./gradlew :harness:installDist
# binary: harness/build/install/flit-cli/bin/flit-cli
# or: ./install-cli.sh --zip flit-cli-0.3.1.zip  → ~/.local/bin/flit-cli
```

The CLI identity lives at `~/.config/flit/flit-harness/identity.p12` (override
with `FLIT_HARNESS_HOME`), so fingerprints survive rebuilds and reinstalls
(`Harness.kt:81-99`). Restart `serve` after every `installDist`: a running JVM
holds the old jars and a stale session can occupy the single receive slot until
`POST …/cancel?sessionId=…` (`interop/README.md`, "Incident notes").

### 7.1 Box receive (direct or hidden)

```bash
flit-cli serve --dir ~/Public                        # receive, announces on the LAN
flit-cli serve --dir ~/Public --hidden               # never announces or registers back
flit-cli serve --dir ~/Public --port 53318 --udp-port 53319 \
    --alias FlitAlpha --announce-to 127.0.0.1:53317  # unicast announce where multicast is stripped
flit-cli discover --wait 5                           # who is speaking v2.2
flit-cli info 192.168.100.12:53317                   # /info by address
flit-cli send 192.168.100.12 <file...> [--parallel N] [--pin PIN] [--multi]
```

`serve` auto-accepts incoming offers; the app's trusted-senders gate has no CLI
counterpart (README:166-168). A hidden server announces nothing, which is exactly
the case the app's rescue sweep covers: the shipped-CLI walk used
`serve --hidden` and the phone's sweep found it on the next throttled round
(`docs/bluetooth-carrier.md:342-353`). The stand-down rule applies to the *phone's*
hidden mode — a register is an announcement, so a hidden phone runs no sweep.

### 7.2 Blind relay pairer

```bash
flit-cli relay --port 53318 [--token T] [--park-timeout S]
flit-cli serve --dir ~/Public --relay 127.0.0.1:53318 --token T   # no direct listener, no announces
```

On the phone, type the relay `host:port` (and token) into Options and enable the
per-device **relayFor** switch for any peer that must use it; Circle dialing
needs the same switch. The relay pairs `req <fp>` with `sub <fp>`, logs only
timestamps and opaque ticket ids, stores nothing and has no discovery. Both
peers must be live (`docs/milestone-e.md:71-80`, README:169-179).

### 7.3 Simulated Bluetooth pipe (`bt-sim`)

```bash
flit-cli bt-sim [file...] [--bytes MB] [--no-tls]
```

One process, two ends: receiver is the production `ServerCore` behind
`LinkServer`, sender is the production `ProtocolClient` over `SimLinkProvider`;
`--bytes` mints a synthetic random body when no file is given; `--no-tls` makes
`/info` say `http` (`BtSim.kt:27-43,44-49,96-101`). A green run prints
`info: …`, `sent N/N`, exit 0; a failed file exits 3. This is the transport-
agnostic regression gate, not a radio proof.

### 7.4 Circle with two bots

Two bots are two CLI processes with distinct `FLIT_HARNESS_HOME` values, so
their identities and local Circle state cannot collide. Bot A first mints the
circle; `circle create` prints the exact `serve --circle ID --invite TOKEN`
command to run in a second terminal (`Harness.kt:1041-1052`). `serve` seeds a
host from those values and prints them at startup (`Harness.kt:512-516,569-570`).

```bash
# terminal A
export FLIT_HARNESS_HOME=/tmp/opencode/bot-a
flit-cli circle create                              # prints circle ID + invite TOKEN
flit-cli serve --dir /tmp/opencode/bot-a-downs \
    --circle ID --invite TOKEN                      # the host bot (blocks)

# terminal B
export FLIT_HARNESS_HOME=/tmp/opencode/bot-b
flit-cli circle join --host 127.0.0.1:53317 --circle ID --invite TOKEN \
    --host-fp FP                              # fingerprint printed by the host's serve
flit-cli circle post --text 'hello' --host 127.0.0.1:53317
flit-cli circle poll --text 'Ship?' --option yes --option no --host 127.0.0.1:53317
flit-cli circle vote --option 0 --host 127.0.0.1:53317
flit-cli circle sync --host 127.0.0.1:53317          # feed + poll tally
flit-cli circle role --target FP --role ADMIN --host 127.0.0.1:53317
flit-cli circle kick --target FP --host 127.0.0.1:53317
flit-cli circle file --file big.bin --host 127.0.0.1:53317   # announce + real bytes
flit-cli circle leave --host 127.0.0.1:53317
```

`circle post --file` publishes only a `{name,size,sha256}` reference;
`circle file --file` also pushes real bytes with `sendFiles` and records local
status `pending → sent/refused/failed` (`Harness.kt:149-174`). The harness test
uses the same command paths in-process; use distinct `FLIT_HARNESS_HOME` values
when driving two bots, since a shared home with a `serve` process is what made
the device fan-out observation ambiguous (`docs/circle.md:357-361`). Each CLI
command is one-shot: it loads the state file, performs one request and saves it
(`Harness.kt:1035-1039`).

### 7.5 Device walk recipes

These reproduce the recorded evidence; they are **not** part of the automated
rig and must be reported as device walks only when actually run.

- **Wi-Fi kill/resume:** start a large pick from the phone to a box `serve`;
  `kill -9` the box process just after `recv-start`; confirm the phone log says
  `kept, sends when it reappears`; restart `serve`; expect `recv-resume … from
  <exact prefix>` then `recv-ok`; compare sha256 and assert no `.part`/`.token`
  left. The part must be well short of completion — a near-complete sparse
  prefix is not advertised as a resume base.
- **Relay kill/resume:** run `flit-cli relay` plus `flit-cli serve --relay …`,
  configure the phone, send, `kill -9` the relay mid-body, and watch both ends
  re-subscribe on backoff before the next hunt re-pairs.
- **Circle file drop:** post `circle file --file` to a joined phone; confirm the
  M3 card becomes "Landed here", hash matches, and status moves to `sent`.
- **Open-file / sharing restore:** tap Open file, pick a viewer, return; check
  `pidof app.flit.share` and the log for `am_crash`/`FATAL`; then
  `am force-stop app.flit.share`, relaunch and confirm sharing comes back from
  the persisted row without a fresh prompt.

### 7.6 Interop with a real v2.2 peer

The official CLI is at `~/.local/opt/localsend-cli`. Drive its TUI with a FIFO
and an `Y` at the offer prompt; the reproducible bootstrap pairs are recorded
in `interop/README.md` ("Repro bootstraps" and "Day 2"). This check is manual and
sits outside the rig. Day 1 used a unicast announce fallback because multicast
appeared broken; day 2 (2026-10-23) corrected that diagnosis — multicast worked
both ways on the real Wi-Fi LAN once the app's announce TX was wired properly,
and the earlier `T0-m` conclusion is kept only as history.

## 8. Flaky patterns and fixes

These are the timing and state hazards observed while running the suite, with
the mitigation each test uses.

| Pattern | Where | Symptom | Mitigation |
| --- | --- | --- | --- |
| Global mutable test knob `PeerHttpClient.testAbortBytes` | `LinkServerParityTest.kt:199,212,361,373,527,531`; `RelayServerTest.kt:165,171,201` | A forced mid-body abort can leak into the next test and break unrelated sends. | Set only for the abort window, reset to `0L` in `finally`; keep those tests in a single JVM (Gradle's default fork behavior). A new abort test must follow the same pattern. |
| Wall-clock sleep for the stale-window gap | `RelayServerTest.kt:187` (`delay(11_000)`) | The receiver's stale session must retire before a new sender replaces it; a shorter sleep reintroduces the two-layer resume defect. | Sleep past the window, then assert the prefix was kept and the resume is hash-exact. Slow, but deterministic; do not trim it without a clock-injection seam. |
| Filesystem polling with deadlines | `RelayServerTest.kt:112-114,179-181,194-196`; `PartialSidecarTest.kt:109-110` | An async landing or sidecar appears a few milliseconds late; a bare existence check races the writer. | Poll at 50 ms with a bounded 5-20 s deadline, then assert content/hash. Always fail with the directory in the message. |
| Hard hang instead of slow failure | `LinkServerParityTest.kt:103-125` | A duplex pipe can deadlock; Gradle would hang until a human intervenes. | A daemon watchdog dumps all thread stacks and `halt(97)` after 60 s; the actual run is bounded by `withTimeoutOrNull(90_000)`. Healthy runs interrupt the watchdog in `finally`. Copy this shape for any new duplex test. |
| Fixed port collisions | All network-ish tests use `port = 0` or `ServerSocket(0)` | A hardcoded 53317/53318 collides with a running `serve` or another fork. | Bind an ephemeral port and pass the assigned value through `SendTarget`. |
| Shared temp directories | Parity, relay and Circle tests | A previous run's landed file makes "exactly one file" or hash checks pass for the wrong reason. | `Files.createTempDirectory(...)` per test; assert exact file count and bytes; close servers/clients in `finally`. |
| QR round-trip under suite load | `ParcelCliTest`, `ParcelCliHarnessTest` | A dense frame can fail its own decode check at 768 px in a full suite while the same frame passes in a targeted run. | `ParcelCli.renderFrames` verifies every frame and escalates the whole set through 768, 1024 and 1536 px before staging any PNG; frame strings stay byte-identical. The `ParcelCliHarnessTest.frameString` reader uses the same hinted decode (`TRY_HARDER`, QR_CODE as the only possible format) and retries integer scales 1..6 before treating a PNG as unreadable, so a dense frame is not mistaken for a codec defect; no case count changed. |
| Random data without a seed | `Random(7)`, `Random(42)`, `Random(99)` in parity/relay/Circle tests | Non-reproducible payloads make a rare framing bug impossible to replay. | Seed every generator; keep the seed in the test source. |
| Wall-clock replay windows | `CircleCoreTest.kt:108,256`; `PanicTest.kt:166`; `FlitCircleHostTest.kt:154,370`; `CircleHarnessTest.kt:345` | An event created "now" can drift out of the window on a slow machine, or a stale one can drift in. | Pass `createdMs` explicitly for edge cases (`now − REPLAY_WINDOW_MS − margin`) and use defaults only for fresh events. |
| Stale Gradle outputs and XML | Observed on 2026-11-25: XML listed `PinCacheTest` and `TlsIdentityTest: 2` although the current source has `PinCacheTest` in `TlsIdentityTest.kt` and `TlsIdentityTest` in `DtoParityTest.kt` | `UP-TO-DATE` test tasks can report green against an older class layout; per-class counts in this report would drift. | Use `--rerun-tasks` (or `clean`) when verifying counts after test-file edits; count `@Test` in source as a cross-check. Totals stayed 302 because tests moved between files, so the stale XML was not obvious from the headline number. |
| Identity/state sharing between CLI bots | `Harness.kt:81-99`; `docs/circle.md:357-361` | Two bots with one `~/.config/flit/flit-harness` home, or a bot sharing state with `serve`, blur peer-side confirmation. | Give each bot its own `FLIT_HARNESS_HOME`; record the ambiguity when a device walk cannot disambiguate. |
| Concurrent CI runs on one branch | `.github/workflows/rig.yml:10-12` | Two pushes could race on build outputs/artifacts. | `concurrency.group = rig-${{ github.ref }}` with `cancel-in-progress: true`. |
| Inherited `FLIT_TRACE` / stale `serve` session | `Harness.kt:128-133`; `interop/README.md` | A CLI failure prints only `!! message` without a stack, or a stale receive slot blocks a new run. | Set `FLIT_TRACE=1` for diagnosis; restart `serve` after `installDist`, cancel stale sessions with `POST …/cancel?sessionId=…`. |

## 9. How to add tests

1. **Pick the right module for the claim.** A new pure rule (codec, reducer,
   policy math, canonical body, tally) goes in `protocol/` if it is wire-level
   and Android-free, or in `app/` if it is app state. A claim about two peers,
   a relay or a real production endpoint goes in `harness/` using the real
   `ProtocolClient`/`ProtocolServer`/`RelayCore`, never a lookalike core. A
   claim about a radio or a UI gesture can only be a device walk; write the
   pure seam test as well and label the walk separately.
2. **Keep the test JVM-pure.** No Android framework imports in `app/src/test`,
   no Robolectric, no emulator. Use `kotlin.test` (`@Test`, `assertEquals`,
   `assertContentEquals`, …), `runBlocking` where a suspend path is needed, and
   the JUnit Platform already configured in each module's build file. Do not
   add a new test dependency without a concrete need.
3. **Exercise the production seam.** Use `ServerCore`/`LinkServer`,
   `ProtocolClient`, `CircleCore.create`/`verify`, `TlsIdentity` and the same
   `SendTarget` the app builds. If a test needs a fake, keep it at the transport
   boundary (`PipePair`, `SimLinkProvider`, an injected clock or carrier), not
   at the protocol rule it claims to prove.
4. **Make inputs deterministic.** Seed random payloads; inject `createdMs`
   for replay-window edges; bind ephemeral ports; use fresh temp directories;
   assert exact bytes/hash and exact refusal reason, not just "ok".
5. **Clean up.** Close clients/servers/scopes in `finally`; reset global knobs
   such as `PeerHttpClient.testAbortBytes` to `0L`; delete nothing that a
   queue whitelist should keep. If the test can hang, add the watchdog/timeout
   shape from `LinkServerParityTest`.
6. **Verify names and counts.** A file may hold more than one test class (see
   §4), so verify the report by class name, not by file name. After adding a
   test, re-run with `--rerun-tasks` and update the count in §2, §4 and the
   cross-linked docs (`OPERATIONS.md`, `PERFORMANCE.md`, `SECURITY.md`,
   `FAILURE-MODES.md`, `ARCHITECTURE.md` and the CI comment all quote counts).
   The commit message records the new module counts, as `72225f8` and `2034fa6`
   do.
7. **Run the rig before claiming green.** Local test subset first:
   `source env.sh && ./gradlew :protocol:test :app:testDebugUnitTest :harness:test`,
   then the full local or CI command from §3 including both assemblies and
   `installDist`. Only then call it green, and only at the level the tests
   actually cover (§1). If the behavior needs hardware, add the reproduction
   recipe from §7.5 and mark the observation "not verified" until walked.

## 10. Cross-links and historical note

- Module boundaries and route/relay policy: [Architecture](ARCHITECTURE.md),
  [Contracts](CONTRACTS.md).
- What retries, what concludes, what keeps bytes: [Failure modes](FAILURE-MODES.md).
- CLI flags, box/phone setup, logs and troubleshooting: [Operations](OPERATIONS.md).
- Measured throughput and the design tradeoffs behind the tests: [Performance](PERFORMANCE.md).
- Trust anchors and threat model behind the signature/pin tests: [Security](SECURITY.md).
- Device-walk narrative per feature: [transport ladder](transport-bluetooth.md),
  [Bluetooth carrier](bluetooth-carrier.md), [Circle](circle.md),
  [Open-file and sharing restore](open-file-and-sharing-restore.md),
  and the [milestone A/C/D/E docs](milestone-a.md) ([C](milestone-c.md),
  [D](milestone-d.md), [E](milestone-e.md)).

Historical counts in those docs describe the suite at the time they were
written: 30 at B0, 76 at milestone 1, 86 at milestone D, 115 at milestone E,
then 121, 126, 145, 183, 231, 286 and 302 at `72225f8`, 356 after the
2026-11-27 protocol layer (136 + 210 + 10), 378 after the 2026-11-30 CLI layer
(136 + 210 + 32), 396 after the 2026-12-01 App layer (136 + 228 + 32), 416
after the app QA fixes (138 + 239 + 39), 436 after the 2026-12-03 QR codec
layer (240 + 272 + 39), 588 after the receipts and pad layers, 714 at the
pre-JOIN-proof layer (330 + 309 + 75), 769 (359 + 323 + 87) as the snapshot
before the relay-hunt and `wrapVerified` cases and 773 (362 + 324 + 87) after
them. The CI workflow header (`.github/workflows/rig.yml:1-3`) now quotes the
current 362 + 324 + 92. The
2026-12-07 QR parcel App layer added 18 cases in `ParcelScanStateTest`, later
confirmed by JUnit XML. The 2026-12-09 per-drop content key protocol layer
added three classes in §4.1 (`DropKeyCoreTest`, `DropKeyBodyTest`,
`DropKeyCryptoTest`), included in the green 588 at that layer. The 2026-12-10
per-drop parity Integration layer added four classes in §4.4 with 31 cases at
that layer; no device observation is asserted. The 2026-12-11 delivery receipts
protocol layer added three classes in §4.1 with 24 cases at that layer; no
device observation is asserted for them. The 2026-12-15 circle pad protocol
layer added four classes in §4.1 (`PadBodyTest`, `PadViewTest`, `PadMergeTest`,
`PadCanonicalTest`) with 16 + 9 + 11 + 7 = 43 cases recorded from the JUnit
XML; no device observation is asserted for them. The JOIN-proof/authority layer
is recorded in §4.8 and brought the inventory to the 769 snapshot
(359 + 323 + 87), with no device walk claimed. The relay-hunt and
`wrapVerified` cases in §4.8 add four cases to reach the historical
362 + 324 + 87 = 773. The 2027-01-05 CLI parity layer adds four
`CircleHarnessTest` cases and one `RelayServerTest` case to reach the current
362 + 324 + 92 = 778; its QR decoder hardening adds no case. Neither layer
claims a device walk. The layer record is
`docs/completions/2027-01-05-cli-parity.md`.
