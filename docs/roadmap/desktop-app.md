# Desktop application

## Status

No desktop GUI exists in this repository. `flit-cli` in `harness/` is the only
companion on a box, and the JVM modules are `protocol`, `harness`, `app` and `core`, with
the Android application in `app/src/main`. The earlier GUI-desktop scope cut
is already superseded in `docs/PRODUCT-DECISIONS.md` §5 and
`docs/roadmap/00-plan.md`, which point to this document; `docs/ARCHITECTURE.md`
lists a GUI desktop app as not built. This document is that design target. It
does not add a desktop source set,
a module, a build task or a release step, and it does not change the v2.2 wire
surface: no new route, header, DTO field or Circle event verb is proposed.

## Stack decision

The recommended stack is Compose Multiplatform desktop with the conditional
Gate 0 set (Kotlin 2.4.21, CMP 1.12.2, BOM 2026.09.00) and JDK 21, declared as
a single `jvm("desktop")` target. The target list
stays at one entry for now; no web, iOS or shared JVM target is implied by the
word "Multiplatform" here. Material 3 in Compose supplies the existing
expressive presentation vocabulary, and a desktop window can reuse the Compose
layout code that has no Android imports rather than re-express it.

The reasons are code reuse and seam shape, not novelty:

- `:protocol` is already JVM-pure. `ProtocolClient`, `ProtocolServer`,
  `TlsIdentity`, `UdpDiscovery`, `ParcelCodec`, the Circle cores and the courier
  cores run on the box today inside `harness/`.
- `app/` already separates Android-free Kotlin seams from platform actuals. The
  pure reducers, codecs and state holders under
  `app/src/main/kotlin/app/flit/share/` have JVM tests in
  `app/src/test/kotlin/app/flit/share/`, so the logic a desktop build needs is
  already written against no Android class in most cases.
- The presentation code is Compose. `CircleUi.kt`, `PadUi.kt`, `CourierCardUi.kt`,
  `SheetUi.kt`, `QrUi.kt`, `ParcelUi.kt` and the motion and shape constants in
  `FlitTheme.kt:30-64` are the largest single block of reusable UI work. Reusing
  them preserves the M3 Expressive scheme already chosen for the app.

JavaFX and Swing are rejected because they discard that Compose UI investment:
the screens, the `FlitSpring` spring coefficients and the `FlitCorner` scale
would be rewritten in a different rendering model with no shared test surface.
Electron and Tauri are rejected because they add a second ecosystem (JS/TS or
Rust) next to an existing Kotlin/JVM codebase, and because `flit-cli` emits
human text for a terminal. A desktop built on either would need a new structured
CLI contract before it could talk to the harness; the Kotlin option talks to
`:protocol` directly and keeps `flit-cli` as a peer rather than as an API to be
redesigned.

## Extraction before UI work

No desktop screen should be written against a copy of app logic. A JVM `:core`
module is added first, or `:protocol` is extended to hold the same seams if a
second module is not wanted. The module is shared by `app`, `harness` and the
new desktop target, and it carries no `android.*` import. The following pure
seams move into it, with platform adapters left where they are:

| Seam | Moves | Stays behind |
|---|---|---|
| `FlitBle.kt` | Pure discovery model and reducers | BLE radio adapters in `FlitBleRadio.kt` / `FlitBluetooth.kt`, app-side |
| `FlitCircleStore.kt` | Pure `CircleState` models, `encode`/`decode` and state reducers | SharedPreferences store adapter, app-side |
| `FlitCircleHost.kt` | Pure host decisions and event folding | Android notification and lifecycle wiring |
| `FlitRoute.kt` | Route model and pure transitions | Platform navigation host |
| `FlitKnown.kt` | Known-peer logic written over its `Store` interface | The `Store` actual that persists on each platform |
| `FlitRoster.kt` | Pure roster models and reducers | A planned `FlitRosterStore` seam and its Android adapter |
| `ParcelScanState.kt`, `Qr.kt` | Frame holders, reducers, encode/decode | CameraX Y-plane capture in the app |
| `CourierCardUi.kt`, `SheetUi.kt` | Pure card state and formatting helpers | Compose rendering where it depends on platform UI |
| `FlitEngine.kt` | Pure islands only, identified case by case | The engine's I/O, file, permission and notification ports |

`FlitEngine` is deliberately not moved whole. Its pure islands go to `:core`
ahead of the desktop UI; the remaining engine extraction is Phase 2 work with
injected ports.

A KMP `ui-common` module is added alongside `:core` for the shared `CircleUi`
and `PadUi` helpers that are free of Android imports, plus `FlitSpring` and
`FlitCorner`, which today live in `app/.../FlitTheme.kt:30-64`. Android dynamic
color is split out of that file into an actual in `app`: `dynamicLightColorScheme`,
`dynamicDarkColorScheme`, `LocalContext` and the API-level check belong to the
Android side. `ui-common` keeps the static color scheme, the spring coefficients,
the corner scale and the composable helpers; the desktop target supplies its own
color actual. An Android import appearing in `ui-common` or the desktop source
set is a build failure, not a review note.

CLI state converges at the same time. `CircleCliState` in
`harness/.../Harness.kt:917` and the courier-side state loaded by
`CourierCli.kt:626` move onto the shared `CircleState` codec from
`FlitCircleStore.kt:177-198`. The CLI keeps its own state file location and
reducer calls, but it serializes the shared type rather than a second schema.
The identity home is the CLI's existing
`~/.config/flit/flit-harness/identity.p12`, resolved by `harnessHome()` in
`Harness.kt:98-112`. The desktop target reads that same default file instead of
minting new key material, so a fingerprint established by `flit-cli` stays the
fingerprint shown on the desktop. The existing `FLIT_HARNESS_HOME` override
remains the way to run two distinct nodes on one box; sharing one home between
two running nodes mixes fingerprints and state, as recorded in the device-walk
notes.

## Phase 1 — Linux MVP

Phase 1 targets Linux only. It is scoped to the paths that already have JVM
code and to surfaces that can be built without a phone or a camera:

1. **Identity.** Load or create the `.p12` at the shared CLI home, display the
   fingerprint, and keep one home per running node.
2. **UDP discovery.** Use `UdpDiscovery` from `:protocol` to list local peers.
3. **File send and receive.** Use `ProtocolClient.sendFiles` and
   `ProtocolServer` with a configured received directory; no new transport is
   introduced.
4. **Settings.** Identity fingerprint, node name, receive directory, theme and
   discovery on/off, all stored locally.
5. **QR invite display and join.** Render invite text with `Qr.kt`, and accept a
   pasted invite string or link through a text field. Display is screen-only;
   intake is paste-only in this phase.
6. **Parcel encode and decode.** Use `ParcelCodec` from `:protocol` with the
   `ParcelScanState` reducer: show ordered frames, paste frames back in, and
   write a verified payload once to the received bucket.
7. **Courier bag folder.** A local folder holds pending parcels, driven by the
   courier cores already in `:protocol`; no mobile carrier is involved.
8. **Received ledger.** Local rows for landed files and verified parcels, with
   the same meaning as the app's received path and no cloud or account.

Out of scope for Phase 1: BLE, CameraX, SAF (the desktop uses a JVM file
chooser), foreground services (a desktop process has no FGS), multi-hop relay,
Circle UI parity, macOS and Windows bundles, and webcam QR capture.

## Phase 2 — parity and portability

Phase 2 extracts the rest of `FlitEngine` behind injected ports for files,
clock, identity and store, notifications, and UI dispatch. The Android app and
the desktop target each supply their own actuals. On that base the desktop
reaches Circle host and governance, keyed drops, delivery receipts, the Circle
pad and the courier worker at parity with the app and `flit-cli`, including the
fold paths that the CLI already exercises.

Further Phase 2 scope: tray notifications in place of the app's notification
channel, macOS and Windows packaging with signing, and an optional webcam QR
capture path that feeds frames to `Qr.decodeGray`. Webcam capture stays
optional and separate from the Phase 1 paste path.

## Tests

The historical 778 baseline was superseded by the four-module XML of 972
(443 + 167 + 254 + 108); a moved case keeps its assertion. Moving
a seam is not a reason to drop a case. New cases raise the total, so the
baseline is a floor and not a target count.

- `:core:test` holds JVM tests for every seam moved into `:core` and the pure
  helpers in `ui-common`, including the shared `CircleState` codec cases that
  replace the two CLI schemas.
- `:desktop:test` holds JVM tests for identity-home resolution, discovery and
  file state holders, settings state and parcel round-trips.
- Compose UI tests cover the desktop cards and their state transitions using the
  Compose test APIs, with no device and no network.
- Desktop-to-`flit-cli` walks are labelled SIM-HARN, the evidence class used
  for other on-box walks. They do not become `DEVICE` evidence.
- The existing `localsend-cli` interop checks are kept.

## Risks

| Risk | Mitigation |
|---|---|
| Compose BOM and CMP version alignment | Gate 0 resolves this before any screen is built. No published CMP release maps to the Compose `1.11.3` resolved by the current BOM `2026.06.00`. The aligned set is Kotlin `2.4.21` with matching KMP and Compose compiler plugins, CMP `1.12.2` and BOM `2026.09.00` (Compose `1.12.1`); Kotlin `2.4.21` covers the shipped Gradle `8.14.3` and AGP `8.13.2`. The future `ui-common` Android target follows the Kotlin 2.3+ plugin and `android` DSL. If that upgrade is not taken, Gate 0 stays open. A configuration run must prove the plugin matrix and one resolved Compose set with `:app:dependencies`. A mismatch breaks rendering rather than one isolated test. |
| Identity continuity | Keep the CLI home as the desktop default and never mint a second key on first desktop launch; document the one-home-per-node rule and the override. |
| Android-only platform features | Keep `android.*` out of `:core`, `ui-common` and the desktop source set; BLE, CameraX, SAF and FGS remain app actuals, enforced by compile failure. |
| Packaging and signing | Treat JDK 21 runtime targeting and per-OS bundles as Phase 2 work; macOS signing/notarization and Windows signing are a release gate, not a Phase 1 claim. |

## Boundaries

The desktop target is a proposal with a stated order of work, not an implemented
feature and not a promised date. No GUI desktop binary exists, no desktop test
has run, and no claim is made here about a physical device walk. This document
adds no build task, repository operation or release step.
