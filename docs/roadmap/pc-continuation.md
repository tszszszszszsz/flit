# PC/desktop continuation after the security wave

## Status

This document records how desktop work resumes after the security hardening
wave. It changes no build file, version catalog, source file, test, workflow or
module. It adds no desktop source set, binary, bundle, screen or release step.
No Gradle task, git operation or commit was run for it. The statements below are
a continuation plan and a record of what is already present on disk, not a
claim that a desktop build, test or walk has happened.

Before the security wave, `docs/QUALITY-CONFIDENCE.md` named six findings: the
receive gates read a caller-declared fingerprint; senders trusted all server
certificates; JOIN proof single-use was process-local; replay state was
process-local; the pin cache was memory-only; and no adversarial campaign or
dependency scan was recorded. The 2027-01-12/13 wave landed the prepare
certificate and signature binding (Option B in
`docs/roadmap/trust-gate-cert-binding.md`), the sender-side TLS peer pin, the
signed JOIN proof with digest-keyed single-use, replay retention and the pin
tiers. Option A is superseded. What remains is the durable pin source, the
durable replay store, the hostile-input corpus, the advisory scan and the
unperformed device walk. A passing test is not a security proof. Closing a gap
means a tested change with evidence at the appropriate level, not a note that a
design exists.

## Gate 0 — version pins

Gate 0 is the dependency decision that must close before any desktop screen is
written. The shipped build pins Kotlin `2.2.0` and Android Compose BOM
`2026.06.00`; that BOM resolves Compose `1.11.3`, and no published CMP release
maps to it (CMP `1.11.1` maps to Compose `1.11.2`, CMP `1.12.2` maps to Compose
`1.12.1`). Kotlin `2.2.0` also has no published KMP-plugin envelope for AGP
`8.13.2`.

The aligned set recorded for Gate 0 is:

| Item | Pin | Role |
| --- | --- | --- |
| Kotlin | `2.4.21` | KMP plugin and Compose compiler plugin at the same version |
| Compose Multiplatform | `1.12.2` | desktop plugin and published artifact set |
| Compose BOM (Android) | `2026.09.00` | resolves the Compose artifacts used by `ui-common` and `app` |
| Compose | `1.12.1` | resolved by BOM `2026.09.00`; the version CMP `1.12.2` expects |

Kotlin `2.4.21` covers the shipped Gradle `8.14.3` and AGP `8.13.2`. The set is
conditional, not adopted: it requires the repo-wide Kotlin and BOM pins to
change, and the future `ui-common` Android target must follow the Kotlin 2.3+
`com.android.kotlin.multiplatform.library` plugin and `android` DSL. If that
repo-wide upgrade is not taken, Gate 0 stays open rather than taking a
nearest-match pin. Gate 0 closes only after a configuration run proves the
plugin matrix and a dependency report shows one resolved Compose set across
Android and desktop; a mismatch there breaks rendering rather than one
isolated test. The full pin table and the unresolved items are in
`docs/roadmap/desktop-toolchain.md`, and the decision with its rationale is in
`docs/roadmap/desktop-wave-summary.md`.

## What has landed locally

The first extraction layer is present on disk. `settings.gradle.kts` includes
`:core`, and `core/build.gradle.kts` applies `kotlin("jvm")` and the
serialization plugin with a JDK 21 toolchain, depending on `:protocol` and
`libs.zxing.core`. The source directory
`core/src/main/kotlin/app/flit/share/` holds the leaf pure seams `FlitRoute`,
`SheetUi`, `FlitMirror`, `FlitSpeed`, `FlitSpool`, `FlitSubnet`, `FlitCrush`,
`FlitBle`, `ParcelScanState` and `Qr`, with `GraySource` as a supporting type.
The matching test directory holds ten test classes, including `FlitQrTest`,
`ParcelScanStateTest` and `FlitSpeedEdgeTest`; local JUnit XML under
`core/build/test-results/test/` accounts for 112 cases.

The old floor was 778, split as 362 protocol + 112 core + 212 app + 92
harness. That split was a projection before the extended rig was green. The
current four-module JUnit XML records 443 protocol + 167 core + 254 app + 108
harness = 972 tests with zero failures, errors and skipped cases; 778 is
historical, and moved tests kept their assertions and evidence level. Re-baseline
rules are in `docs/roadmap/03-quality-gates.md` and
`docs/roadmap/desktop-test-migration.md`.

## Remaining extraction steps

Extraction continues in the order set in `docs/roadmap/desktop-wave-summary.md`,
from the point the local `:core` layer reached:

1. Move the pure halves of `FlitKnown` and `FlitCircleStore` into `:core`,
   leaving the Android `Context`/SharedPreferences adapters in `app`, and add a
   `FlitRosterStore` seam for roster state so `FlitRoster` stops reading
   SharedPreferences directly.
2. Move `FlitCircleHost` pure decisions and event folding, and
   `CourierCardUi` pure card state and formatting, keeping Android notification
   and lifecycle wiring in `app`. `ParcelScanState` and `Qr` are already in
   `:core`, so only their app-side platform adapters remain behind.
3. Move `FlitCircleSync` pure helpers and the pure islands of `FlitEngine`
   behind thin app delegates, leaving workers, I/O, file, permission and
   notification ports in `app` until the later phase with injected ports.
4. Converge CLI persistence onto the shared `CircleState` codec, replacing the
   CLI's separate eight-line schema with one-shot legacy migration on first
   write, and unify the duplicated-helper divergences found during planning
   before parity can be claimed. The clearest one is `circleNextLamport`,
   which starts at `0` on an empty log in the CLI and at `1` in the app; the
   CLI answer-fold helpers also omit `validShape` and `CircleCore.accept` and
   lack the PARCEL body gate, and CLI delivery status lacks `NO_KEY` and maps
   an empty `sent`/`failed` report to `REFUSED` where the app uses
   `report.declined`. QR rendering hints differ across `ParcelCli`,
   `parse-qr` and `Qr.decode*` although the frame grammar matches.

Per-file move targets and line ranges are in
`docs/roadmap/desktop-core-contracts.md`; state convergence is in
`docs/roadmap/desktop-state-convergence.md`; UI helper moves are in
`docs/roadmap/desktop-ui-sharing.md`; test disposition is in
`docs/roadmap/desktop-test-migration.md`. No step in this list is recorded as
done beyond the local `:core` layer described above.

## ui-common and desktop modules

Neither module exists yet; both are planned, and neither is created by this
document. `ui-common` is a Kotlin Multiplatform module with an Android target
and a single `jvm("desktop")` target. It is planned to hold `FlitSpring`,
`FlitCorner`, the static color scheme and the Android-free `CircleUi`/`PadUi`
helpers. `dynamicLightColorScheme`, `dynamicDarkColorScheme`, `LocalContext`
and the API-level check stay in `app`, and the desktop target supplies its own
color actual. An `android.*` import in `ui-common` is a compile failure, not a
review note.

`:desktop` is a Compose Multiplatform desktop application with one
`jvm("desktop")` target and a JDK 21 toolchain, depending on `:ui-common`,
`:core` and `:protocol`. It calls `UdpDiscovery`, `ProtocolClient`,
`ProtocolServer` and `ParcelCodec` directly; no structured contract with
`flit-cli` is introduced, and `flit-cli` remains a peer client and the interop
rig. Identity defaults to the CLI home `~/.config/flit/flit-harness/identity.p12`
with the `FLIT_HARNESS_HOME` override, and one home per running node remains a
hard rule so two running nodes do not mix fingerprints and state. The intended
dependency graph is acyclic and one-way: `:protocol` has no project
dependencies, `:core` depends on `:protocol`, `ui-common` depends on `:core`,
and `:desktop` depends on `ui-common`, `:core` and `:protocol`. The module
layout, plugin sets and rig task names are in
`docs/roadmap/desktop-toolchain.md`; the target design is in
`docs/roadmap/desktop-app.md`.

## Scope: Linux/GNOME first, Windows later

The first desktop scope is Linux, with GNOME as the first desktop environment
to target. It covers the paths that already have JVM code and that can be
exercised without a phone or a camera: identity load/display from the CLI home,
UDP discovery, file send and receive with the existing `:protocol` endpoints,
locally stored settings, paste-only QR invite intake with screen-only display,
parcel encode/decode through `ParcelCodec` with a JVM file chooser, a local
courier bag folder, and a local received ledger. BLE, CameraX, SAF, foreground
services, multi-hop relay, Circle UI parity, webcam capture and non-Linux
bundles are out of this first scope.

Windows comes later, together with macOS, in the portability phase. That later
phase is where the rest of `FlitEngine` moves behind injected ports for files,
clock, identity, store, notifications and UI dispatch, enabling Circle host and
governance, keyed drops, receipts, the pad and the courier worker at parity,
plus tray notifications. macOS signing/notarization and Windows signing belong
to that phase as release gates, not to the first Linux scope. No Windows bundle
or signed artifact is part of the current continuation.

Packaging takes AppImage as the primary format for the Linux desktop. This
sets the Linux package-format question that
`docs/roadmap/desktop-toolchain.md` left open, where `:desktop:packageDeb` was
named only as a candidate. AppImage being primary is a packaging decision, not
a record of a built image: no bundle, runtime image, signing or packaging task
exists or has been run here. JDK 21 runtime targeting and distribution tasks
remain part of the packaging phase. Risks and mitigations are in
`docs/roadmap/desktop-risk-register.md`.

## Evidence walks

Desktop-to-`flit-cli` walks are labelled SIM-HARN, the class for in-process
runs over production client/server endpoints with real TLS inside a pipe, PNG
render/decode on one box, or CLI loopback. They never become DEVICE evidence:
there is no radio path, second physical phone or camera observation in them.
The existing `localsend-cli` interop checks are kept. The rig additions for
this continuation are test and compile tasks only — `:core:test`,
`ui-common:desktopTest` and `:desktop:desktopTest` — with no device and no
network step, and packaging tasks are not added to the rig in the first scope.
Labels and their limits are defined in `docs/QUALITY-CONFIDENCE.md` and
`docs/FAILURE-MODES.md`, and the desktop evidence rule is restated in
`docs/roadmap/desktop-app.md`.

## Hard gate before any desktop screen

No desktop screen is built before the security gaps listed above are closed
and Gate 0 passes. The order is: close and verify the security findings with
tests and recorded evidence; carry the remaining extraction steps and the
`ui-common`/`:desktop` module work forward; take the conditional Gate 0 pin
set only if the repo-wide upgrade is made; prove the plugin matrix with a
configuration run and a single resolved Compose set; then, and only then, start
a desktop screen. A parked design, a local `:core` extraction or a projected
test count does not satisfy either condition. This document does not claim a
security proof, a green extended rig, a built desktop binary, a device walk or
a release date.

## Related documents

- `docs/roadmap/desktop-app.md` — stack decision, extraction table, Phase 1
  Linux MVP, Phase 2 portability, tests, risks and boundaries.
- `docs/roadmap/desktop-wave-summary.md` — decisions, Gate 0 condition,
  extraction order, re-baseline rule and planning-wave scope.
- `docs/roadmap/desktop-toolchain.md` — module build plans, shipped and
  conditional pins, module edges, rig task changes and re-baseline point.
- `docs/roadmap/desktop-core-contracts.md` — per-seam move targets.
- `docs/roadmap/desktop-ui-sharing.md` — shared UI helper moves.
- `docs/roadmap/desktop-state-convergence.md` — CLI persistence convergence.
- `docs/roadmap/desktop-test-migration.md` — test disposition and counts.
- `docs/roadmap/desktop-risk-register.md` — risks and verification.
- `docs/roadmap/trust-gate-cert-binding.md` — design record; Option B landed as
  the prepare certificate/signature binding (see landing records).
- `docs/roadmap/03-quality-gates.md` — completeness, re-baseline and evidence
  rules.
- `docs/QUALITY-CONFIDENCE.md` and `docs/FAILURE-MODES.md` — security findings
  and evidence labels.
