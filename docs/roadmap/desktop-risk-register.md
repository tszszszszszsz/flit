# Desktop program — risk register

## Status

This document is a plan. No desktop build exists in this repository, no desktop
test has run, and no desktop binary has been packaged or signed. The recommended
target is Compose Multiplatform desktop with the conditional Gate 0 set
(Kotlin 2.4.21, CMP 1.12.2, BOM 2026.09.00) and JDK 21, declared as one
`jvm("desktop")` target, as set out in `docs/roadmap/desktop-app.md`. `:core`
exists on disk with JVM tests; `ui-common` and `:desktop` remain design targets. Every mitigation and verification
step below is a planned action; none is recorded as complete.

Severity is plan-level judgment: High means the risk can break identity, trust
or transport correctness, or can invalidate an evidence claim; Medium means it
can block packaging, distribution or scope; Low is reserved for items with a
local and easily reversed effect. Historical rig counts (778 tests: 362
protocol, 324 app, 92 harness) stay labelled historical and are a floor, not a
target.

## 1. Compose version alignment

- **Severity:** High.
- **Trigger:** Adding a desktop target resolves Kotlin, CMP and Compose/Material 3
  to more than one version set; a Compose BOM drifts from the CMP release that
  expects it. The failure surfaces as rendering or linkage breakage across the
  shared UI rather than as one isolated test case.
- **Mitigation:** Pin the conditional Gate 0 set (Kotlin 2.4.21, CMP 1.12.2,
  BOM 2026.09.00) and one matching
  Compose/Material 3 set. Confirm a single resolved version set before any
  desktop screen is written, and keep the Android app on the same set so
  `ui-common` compiles against one API surface for both targets.
- **Verification:** A dependency-inspection step on the planned desktop target
  reports one coherent Kotlin/CMP/Compose set and no duplicate versions. A
  trivial shared composable from `ui-common` compiles for both the Android app
  and the desktop target. The historical 778-test baseline was superseded by the current 972-case XML after
  the pin. No desktop build exists to run this check yet.

## 2. Identity continuity

- **Severity:** High.
- **Trigger:** Desktop first launch creates a second `.p12`, or resolves a home
  directory different from the CLI's, so the fingerprint shown on the desktop
  differs from the one established by `flit-cli`. Pinning is by fingerprint, so
  a second key breaks trust already recorded against the first.
- **Mitigation:** Make the desktop read the CLI default
  `~/.config/flit/flit-harness/identity.p12`, resolved the same way as
  `harnessHome()` in `Harness.kt:98-112`, and never mint key material on first
  desktop launch. Preserve the `FLIT_HARNESS_HOME` override for running two
  distinct nodes on one box, and document the one-home-per-running-node rule;
  sharing one home between two running nodes mixes fingerprints and state, as
  recorded in the device-walk notes.
- **Verification:** The planned `:desktop:test` case for identity-home resolution
  loads the CLI file and displays a fingerprint identical to the CLI's, plus a
  case where the override selects a second home. Both are planned cases; no
  desktop test has run.

## 3. Duplicated state

- **Severity:** High.
- **Trigger:** The desktop serializes a private `CircleState` schema, or the two
  existing CLI formats stay separate: `CircleCliState` in `Harness.kt:917` and
  the courier-side state loaded by `CourierCli.kt:626`. A second format means a
  peer written by one surface is not read by the other, and a shared home gives
  two running nodes conflicting state.
- **Mitigation:** Move state onto one shared `CircleState` codec from
  `FlitCircleStore.kt:177-198`. The CLI keeps its own state file location and
  reducer calls but serializes the shared type rather than a second schema.
  Every seam moved into `:core` owns one encode/decode path, so the desktop,
  CLI and app read the same bytes. The one-home rule from risk 2 applies to the
  state file as well as the identity file.
- **Verification:** The planned `:core:test` cases for the shared
  `CircleState` codec replace the two CLI schemas and round-trip state written
  by the CLI and read by the desktop target. These cases are planned; no shared
  codec or desktop state holder exists yet.

## 4. Android-only platform features

- **Severity:** High.
- **Trigger:** An `android.*` import reaches `:core`, `ui-common` or the desktop
  source set, or a desktop path assumes BLE, CameraX, SAF or a foreground
  service is present. Dynamic color and `LocalContext` leaking out of
  `FlitTheme.kt` into shared UI has the same effect.
- **Mitigation:** Keep those features as app-side actuals. Split Android dynamic
  color out of `FlitTheme.kt:30-64` into an Android actual and leave `ui-common`
  with the static scheme, spring coefficients, corner scale and pure composable
  helpers; the desktop target supplies its own color actual. Treat an Android
  import in a shared or desktop module as a build failure, not a review note.
- **Verification:** A build check fails compilation on any `android.*` import in
  `:core`, `ui-common` or the desktop source set. Planned `:core:test` cases
  cover the pure helpers moved into `ui-common`. Neither check exists or has run
  while no desktop build exists.

## 5. Packaging and signing

- **Severity:** Medium.
- **Trigger:** Per-OS bundles, JDK 21 runtime targeting, macOS signing and
  notarization, or Windows signing are treated as available in Phase 1, or a
  bundle is described as release-ready without a recorded signing result.
- **Mitigation:** Phase 1 is a Linux MVP built from existing JVM paths. Bundle
  work for macOS and Windows, runtime targeting, and signing or notarization
  belong to Phase 2. Signing is a release gate, not a Phase 1 claim; any release
  note records which bundles were signed and on which OS.
- **Verification:** A release checklist records the target OS, the JDK runtime
  used, and the signing or notarization result for each bundle before it is
  called shippable. No desktop bundle exists to check.

## 6. Dependency licenses

- **Severity:** Medium.
- **Trigger:** Compose Multiplatform, a bundled JDK runtime, or any new
  third-party library is added without a license inventory, or a distributed
  binary ignores the project's own license terms. `LICENSE` is the full GNU GPL
  v3 text with `SPDX-License-Identifier: GPL-3.0-or-later`, and derivatives that
  are distributed must remain under the same license (`OPERATIONS.md:976-979`).
- **Mitigation:** Record each new dependency and its license before it is
  introduced, keep an inventory alongside any bundle, and confirm each bundled
  dependency is compatible with distribution under the project's license. Do
  not add a dependency without a concrete need.
- **Verification:** A license inventory is reviewed at packaging time, and a
  check confirms no dependency with incompatible redistribution terms is
  bundled. No desktop dependency inventory exists yet, because no desktop
  target has been added.

## 7. No camera or webcam scanner

- **Severity:** Medium.
- **Trigger:** Phase 1 is expected to capture QR frames from a camera, or the
  desktop is assumed to match the app's CameraX intake. Adding a camera or
  webcam dependency to `:core` or `ui-common` would also violate risk 4.
- **Mitigation:** Phase 1 invite intake and parcel frame intake are paste-only
  through text fields; QR display is screen-only. Webcam capture feeding frames
  to `Qr.decodeGray` is an optional, separate Phase 2 path and does not become
  the default intake. CameraX stays in the app; no camera dependency enters the
  shared or desktop modules in Phase 1.
- **Verification:** Planned `:desktop:test` cases round-trip a parcel from
  pasted frames using `ParcelCodec` and the `ParcelScanState` reducer, with no
  capture device. A scope check confirms Phase 1 lists no camera path. No such
  test has run and no webcam path is implemented.

## 8. Relay and opt-in semantics

- **Severity:** High.
- **Trigger:** A desktop path dials a relay before a live direct route, enables
  relay by default, or lets a received event forward bytes automatically. This
  breaks the established rule that relay is a typed address with a per-device
  opt-in switch off by default (`PRODUCT-DECISIONS.md:36`), that direct Wi-Fi
  always wins and relay is strictly last (`PRODUCT-DECISIONS.md:95-97`), and
  that no automatic action is taken on another device
  (`PRODUCT-DECISIONS.md:25`).
- **Mitigation:** Phase 1 desktop scope uses only `UdpDiscovery` and
  `ProtocolClient.sendFiles` / `ProtocolServer`; no relay carrier is introduced.
  Where relay is later added, `relayFor` gates dispatch, Circle dialing and
  panic fan-out, the switch is off by default, and a refusal is logged or shown
  rather than silently falling back. Wi-Fi and Bluetooth remain first-class
  carriers; relay stays opt-in and last.
- **Verification:** Planned tests confirm that a default-off `relayFor` blocks
  relay dispatch and that a direct route wins over relay when both exist. A
  scope check confirms the Phase 1 desktop paths are direct-only. These are
  planned checks; no desktop transport code exists to run them.

## 9. Evidence-label discipline

- **Severity:** High.
- **Trigger:** A desktop-to-`flit-cli` walk is tagged `DEVICE`, or a passing
  desktop test is reported as a device walk. The defined labels are CODE, PURE,
  SIM-HARN and DEVICE; where several apply, the weakest governs what may be
  asserted, and a device claim requires a log and an observation, not a passing
  test (`docs/roadmap/03-quality-gates.md:18-21`).
- **Mitigation:** Label desktop-to-`flit-cli` walks as `SIM-HARN`, the same
  evidence class as the other on-box walks; they do not become `DEVICE`
  evidence. Keep desktop JVM tests at CODE or PURE by construction, with no
  device and no network in Compose UI tests. Do not upgrade an evidence class
  without a log and an observation.
- **Verification:** Each desktop test and walk record carries one of the four
  labels, and a review confirms no desktop artifact is tagged `DEVICE` on the
  strength of a test alone. No desktop test or walk has been recorded, so there
  is no evidence to label yet.

## Cross-cutting note

All nine desktop items remain open. The local `:core` extraction is not a
desktop module and does not mitigate them. The historical rig
state and the partially walked phone remain as recorded elsewhere in the
repository; nothing in this register adds a desktop claim.
