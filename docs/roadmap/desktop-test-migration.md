# Desktop test migration — plan

## Status

No test has been moved and no test has been run for this wave. No module, source
set, task or desktop binary has been added. The repository still has three test
locations for JVM code: `protocol/` (362 cases), `app/src/test` (324 cases) and
`harness/` (92 cases). Those numbers are the shipped baseline and stay labelled
historical. `:core` and `ui-common` are planned modules.

This document records where the app's JVM tests would go when the extraction in
`desktop-app.md` lands, which cases remain in `app`, which cases need a new
core-side version rather than a relocation, and the desktop↔`flit-cli` walks to
be written. Every count below that is not the historical baseline is a plan, not
a result.

## Basis for the split

The split follows the seam table in `desktop-app.md`: pure models, codecs and
reducers move into a JVM `:core`; platform actuals, radio adapters, camera
capture, file ports, notification wiring and Compose rendering stay in `app` or
in the planned `ui-common` target. A test moves when the type it pins moves as a
whole and the test source has no platform dependency. A test stays when its
fixture is a platform path: a camera plane, a temp file with a real mtime, a
Wi-Fi sweep, an activity lifecycle. A test needs a new core version when its
subject is an app seam that will be split into extracted islands and injected
ports; those cases are re-authored against the island, and the app originals
remain until the extraction is complete.

Case counts were taken from the `@Test` annotations in the current app test
files. The 20 files add to 324, which matches the historical app total.

## Move to `:core:test`

These 14 files, 242 cases, are relocations. No case is dropped and no new case
is required for the move. Package declarations change from `app.flit.share` to
the core package. Test hooks used by the sync and known-peer cases have to be
part of the core seam API rather than app globals.

| App test file | Cases | Reason |
|---|---:|---|
| `FlitBleTest.kt` | 36 | Record build/parse, alias clipping, stance arbiter, duty cadence and noise-budget validation are pure discovery model and reducers. Radio adapters stay in `app`. |
| `FlitBluetoothTest.kt` | 14 | `FlitBluetoothPolicy` is pure policy over API levels, backoff and log strings. It moves with the seam; the RFCOMM adapter stays in `app`. |
| `FlitCircleStoreTest.kt` | 31 | Codec, defaults, caps and every reducer, including deliveries and courier bag, are pure. The SharedPreferences adapter stays in `app`. |
| `FlitCircleHostTest.kt` | 22 | Host decisions and event folding over signed `CircleCore` events. Notification and lifecycle wiring stay in `app`. |
| `FlitCircleSyncTest.kt` | 56 | Backoff, Lamport sequencing, body gates, watermarks and carrier-seam fan-out use injected hooks and no socket, store or Android context. The hooks move with the seam. |
| `FlitKnownTest.kt` | 8 | Device codec and reducers are written over the `Store` interface; `MemoryStore` moves as the test double. The persisted `Store` actual stays out. |
| `FlitRosterTest.kt` | 12 | Queue merge and `SharingDesired` codec/reducers are pure and ride the same store seam. |
| `FlitRouteTest.kt` | 7 | Route model, candidate order, relay parsing, hunt delay and accept rule are pure transitions. The navigation host stays in `app`. |
| `ParcelScanStateTest.kt` | 18 | Frame holder and reducer driven by real codec frames; no camera, file or socket. CameraX capture stays in `app`. |
| `CourierCardUiTest.kt` | 12 | Card projection: outcome mapping, carry lines, alias rendering, open affordance. No Compose or context in the source. |
| `SheetUiTest.kt` | 3 | Initials, deterministic hue and count phrasing are pure formatting helpers. |
| `PadUiTest.kt` | 15 | Row, bound-check and search-offset projections over `PadView`/`PadSearch`. No Compose in the source; see the destination question below. |
| `FlitSpeedTest.kt` | 4 | Clock-driven estimator with sleeps and wall reads absent. Reused by desktop transfer progress. |
| `FlitSpeedEdgeTest.kt` | 4 | Same estimator, complementary injected-clock edges. |

## Stay in `app`

These six files, 82 cases, keep their current location and their current
fixtures. 242 + 82 = 324, so the relocation plan accounts for every app case.

| App test file | Cases | Reason |
|---|---:|---|
| `FlitQrTest.kt` | 22 | Pins the camera Y-plane with strides, RGB screenshot path and on-screen deployed code string. Those are app capture and render paths. A core version is planned below for the URI and pixel round trips. |
| `FlitEngineTest.kt` | 31 | Drives the engine's own parsed material and reflection-based `foldPad`, plus file and receipt wiring. Core versions are planned below for the pure islands. |
| `FlitSpoolTest.kt` | 5 | Uses real temp files and real mtimes for the reaper. File and clock ports stay platform-side. |
| `FlitMirrorTest.kt` | 5 | Album-folder diff tied to the phone camera folder; the mirror is outside the desktop scope in `desktop-app.md`. |
| `FlitSubnetTest.kt` | 8 | Wi-Fi sweep address math for the app carrier. Phase 1 desktop discovery uses `UdpDiscovery`, not a subnet sweep. |
| `SharingRestoreTest.kt` | 11 | Process-death restore decision belongs to the Android activity lifecycle. A core version is planned below for the codec and old-row cases. |

## New core versions

Three app files need new core-side versions instead of relocation:
`FlitQrTest.kt` (22 cases), `FlitEngineTest.kt` (31) and
`SharingRestoreTest.kt` (11). Their app cases stay. The planned files below are
new cases, not moves, and are counted separately from the 242 relocations.

| Planned core test file | Planned cases | Source and change |
|---|---:|---|
| `DropKeyCoreLandTest` | 11 | The keyed-drop group of `FlitEngineTest`: wrap caps, exact body fields, signature binding, index normalisation and the unwrap/open/digest refusals. Written against the extracted island, not the engine's private helpers. |
| `ReceiptFoldCoreTest` | 7 | Receipt minting refusals and `receiptAuthors` fold from `FlitEngineTest`. No engine instance and no local delivery write. |
| `PadFoldCoreTest` | 2 | View rebuild and published-log fold from `FlitEngineTest`. The publish-through-sync cases stay in `app` until the sync port exists. |
| `PeerFilterCoreTest` | 6 | Self-ghost, null identity, loopback junk, manual stubs, direct-radio label and relay-hunt gate from `FlitEngineTest`. |
| `ReceivedHistoryCoreTest` | 2 | Encode/decode of the received ledger payload from `FlitEngineTest`, without the app's file and content-URI fields. |
| `QrLinkCoreTest` | 20 | URI parse strictness, v1/v2/v3 round trips, URL encoding, deployed-code string, and gray/rotate/RGB decode from `FlitQrTest`. The camera-plane and noise cases stay in `app`. |
| `SharingDesiredCoreTest` | 9 | Codec, old bare-word rows, corrupt rows and idempotence from `SharingRestoreTest`. The two restore-decision cases stay in `app`. |

The planned addition is 57 cases. It is a count of cases to be written, not a
count of cases that exist.

## Re-baselined module counts — plan, not result

The first column is the shipped historical baseline. The second is the result of
relocation only. The third adds the planned new core versions above. No column
after the first has been observed.

| Module | Shipped (historical) | Plan: relocation | Plan: relocation + new core versions |
|---|---:|---:|---:|
| `:protocol` | 362 | 362 | 362 |
| `:app` | 324 | 82 | 82 |
| `:core` | — | 242 | 299 |
| `:harness` | 92 | 92 | 92 |
| Total | 778 | 778 | 835 |

Relocation keeps the total at 778; moving a case is not a reason to drop it.
The 57 new cases raise the planned total to 835. The 778 baseline is a floor,
not a target count. `:desktop` and `ui-common` have no source sets and no case
counts here; their JVM and Compose UI tests are new cases, not a recount of the
existing rig. No green run, no module build and no completion note is claimed by
these numbers.

## Desktop↔`flit-cli` walk tests

These are planned recipes, not walks. Each row pairs one desktop surface with
one `flit-cli` command on the same box. Two running nodes use distinct homes:
the CLI's existing `FLIT_HARNESS_HOME` override stays the way to separate them.
Sharing one home between two running nodes mixes fingerprints and state.

Evidence label for every on-box pair is `SIM-HARN`, the same class as the
other CLI walks. A GUI observation made beside a CLI node is not a phone
observation; none of these rows becomes `DEVICE`. Pure codec and fold results
inside a row carry `PURE`, and constants and gates carry `CODE`. Existing
`localsend-cli` interop checks are kept and are not restated here.

| Planned walk | Surfaces | Recorded result | Label |
|---|---|---|---|
| Identity home continuity | Desktop identity pane; `flit-cli serve`/`info` | One `.p12` at the shared CLI home yields one fingerprint on both surfaces; an override home yields a second node | `SIM-HARN`; not `DEVICE` |
| Discovery | Desktop peer list over `UdpDiscovery`; `flit-cli discover` and `serve` | Each surface lists the other node on the box | `SIM-HARN` |
| File send, desktop→CLI | Desktop file send; `flit-cli serve --dir` | Landed bytes match the announced sha; desktop ledger row written | `SIM-HARN` |
| File send, CLI→desktop | `flit-cli send <ip[:port]> <file>`; desktop received directory | Landed bytes hash-exact; desktop ledger row written | `SIM-HARN` |
| QR invite display and join | Desktop `Qr` invite text; `flit-cli circle create` / `circle join` | Invite pasted across surfaces; circle id and host fingerprint agree; no group key on the code | `SIM-HARN`, `PURE` for codec |
| Parcel frames | `flit-cli parcel encode`; desktop `ParcelScanState`; desktop frames folded by `parcel decode` | Frame count, ordered frames and payload sha agree in both directions; paste only, no camera or webcam | `SIM-HARN`, `PURE` |
| Courier bag | `flit-cli courier drop/carry/receive/status/sweep`; desktop bag folder | Two identities on one box; copied bag yields one handoff and one bag row per parcel | `SIM-HARN` |
| Settings and received ledger | Desktop local state | Fingerprint, node name, receive directory, theme and discovery toggle persist locally; no CLI counterpart | `CODE`, `PURE`; not a walk |
| Circle host and governance (Phase 2) | Desktop host; `flit-cli circle join/post/poll/vote/role/kick/leave/sync` | Signed log, role changes, kick and rekey agree on both surfaces; refusals named | `SIM-HARN` |
| Keyed drops and receipts (Phase 2) | Desktop keyed-drop gate and confirmation count; `flit-cli circle file` / `circle receipt` | Landed plaintext digest gate passes or refuses; one confirmed author per fingerprint | `SIM-HARN`, `PURE` for fold |
| Circle pad (Phase 2) | Desktop pad view; `flit-cli circle pad put/del/show` | Put/del bodies rebuild the same live lines on both surfaces | `SIM-HARN`, `PURE` |

No result, log excerpt, hash or screenshot is attached to any row. A row reaches
an evidence class only when a run records it.

## Open questions

1. `desktop-app.md` §Tests names `:core:test`; this plan uses `:core:test` as
   instructed. One canonical destination name is needed before extraction.
2. `PadUiTest`, `CourierCardUiTest` and `SheetUiTest` follow their pure helpers
   into core in this plan. If those helpers land in the planned `ui-common`
   module instead, the 30 cases move with them and the core/app split changes.
3. `FlitEngine` islands are split between Phase 0 and Phase 2 in
   `desktop-app.md`. The order fixes which of the five core versions above can
   be written first.
4. New core versions and app originals run in parallel until parity. Whether
   the app originals are then removed changes the later app count.
5. `SIM-HARN` covers paired CLI nodes. A desktop-only GUI observation has no
   label in the current set; whether it gets one is unresolved.

## Boundaries

This is a migration plan attached to a design target. It adds no test, no test
result, no module, no source set and no build or repository operation. The
desktop GUI is not built, no desktop test has run, and no device walk is claimed
here.
