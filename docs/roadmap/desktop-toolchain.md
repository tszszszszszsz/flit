# Desktop toolchain plan

## Status

This document records a build and CI plan for the proposed `:core`, `ui-common`
and `:desktop` modules. No build file was changed. No version catalog entry,
settings file, workflow, source file or test was edited. No module was created,
no task was run, and no test count was measured for any desktop module. The
naming and version choices below are proposals to be checked against resolved
dependency output when the modules are added, not a record of a green build.

The stack target is recorded in
[docs/roadmap/desktop-app.md](desktop-app.md): Compose Multiplatform desktop with
a single `jvm("desktop")` target, Kotlin 2.2.0, CMP 1.9.x and JDK 21. Phase 0 is
extraction, Phase 1 is a Linux MVP, Phase 2 is packaging and parity. `flit-cli`
in `harness/` remains the interop rig and the peer client; the desktop target
does not turn the CLI into an API.

## Build state the plan starts from

The repository has three modules: `:protocol`, `:harness` and `:app`
(`settings.gradle.kts:18-20`). The root build applies the Kotlin JVM,
serialization, Android, Kotlin Android and Compose plugins with `apply false`
(`build.gradle.kts:1-7`). The version catalog pins the Kotlin plugin to the
`kotlin` entry (`gradle/libs.versions.toml:2,36-40`). `:protocol` and `:harness`
use `jvmToolchain(21)`; `:app` compiles against Java 17 and sets
`JvmTarget.JVM_17` (`app/build.gradle.kts:72-75,83-87`). The wrapper is Gradle
8.14.3 (`gradle/wrapper/gradle-wrapper.properties:3`).

CI runs on `ubuntu-latest`, installs Temurin 21 with `actions/setup-java`, sets
up the Android SDK at platform 36, and runs one rig command followed by a CLI
distribution artifact upload (`.github/workflows/rig.yml:15-49`). The command is
`:protocol:test :app:testDebugUnitTest :harness:test :app:assembleDebug
:app:assembleRelease :harness:installDist :harness:distZip`
(`.github/workflows/rig.yml:39-43`; the same set appears in `docs/TESTING.md`
§3). No device, emulator, network service or extra runner is part of that
command.

## Planned module changes

### `:core`

A JVM module under `settings.gradle.kts`, applying `kotlin.jvm` and, if the
shared `CircleState` codec lands there, `kotlin.serialization`. It uses
`jvmToolchain(21)` to match `:protocol` and `:harness`. Dependencies are
`project(":protocol")` plus the Kotlin serialization, coroutines and test
artifacts already in the catalog; the test task mirrors `:protocol:test` with
`useJUnitPlatform()` and the JUnit Platform launcher. `:core` carries no
`android.*` import. BLE radio adapters, the SharedPreferences store, CameraX,
SAF, foreground-service wiring and notification code stay in `app`, and the CLI
state-file location and identity home stay in `harness`. The module is consumed
by `app`, `harness` and `:desktop`.

### `ui-common`

A Kotlin Multiplatform module with an Android target and a `jvm("desktop")`
target. The plugins are expected to include `kotlin.multiplatform`,
`org.jetbrains.compose` and the Compose compiler plugin, with an Android
library target supplied by AGP and `kotlin.android`. The exact plugin set is
not resolved; it depends on the CMP patch chosen below. `ui-common` depends on
`:core` for the shared pure types its helpers consume and on one
Compose/Material 3 artifact set.
`FlitSpring`, `FlitCorner`, the static color scheme and the Android-free
`CircleUi`/`PadUi` helpers move here; `dynamicLightColorScheme`,
`dynamicDarkColorScheme`, `LocalContext` and the API-level check stay in `app`,
and the desktop target supplies its own color actual. An Android import in
`ui-common` is a compile failure, not a review note.

### `:desktop`

A Compose Multiplatform desktop application with the single `jvm("desktop")`
target. Plugins are expected to be `kotlin.multiplatform`,
`org.jetbrains.compose`, the Compose compiler plugin and an application entry
point for `mainClass`; `kotlin.serialization` follows the codec seam if the
desktop settings state serializes the shared types. Dependencies are
`project(":core")`, `project(":ui-common")` and `project(":protocol")`: UDP
discovery, `ProtocolClient`, `ProtocolServer` and `ParcelCodec` are called
directly, so no structured CLI contract between desktop and `flit-cli` is
introduced. Coroutines come from the catalog. JDK 21 is the toolchain. Phase 1
uses a JVM file chooser, paste-only QR intake and no camera library; a webcam
capture backend, zxing rendering and per-OS packaging are Phase 2 questions.
Identity defaults to the CLI home `~/.config/flit/flit-harness/identity.p12`
with the existing `FLIT_HARNESS_HOME` override, with one home per running node.

## Version pins

Known compatible in the shipped build, read from the files named:

| Item | Pin | Source |
| --- | --- | --- |
| Kotlin | 2.2.0 | `gradle/libs.versions.toml:2` |
| Gradle | 8.14.3 | `gradle/wrapper/gradle-wrapper.properties:3` |
| JDK | 21 for JVM modules and CI; Java 17 for `app` | `protocol/build.gradle.kts:6-8`, `harness/build.gradle.kts:6-8`, `app/build.gradle.kts:72-75`, `.github/workflows/rig.yml:20-24` |
| Kotlinx serialization | 1.9.0 | `gradle/libs.versions.toml:3` |
| Coroutines | 1.10.2 | `gradle/libs.versions.toml:4` |
| Ktor | 3.2.2 | `gradle/libs.versions.toml:5` |
| Bouncy Castle | 1.87 | `gradle/libs.versions.toml:8` |
| AGP | 8.13.2 | `gradle/libs.versions.toml:8` |
| Compose BOM (Android) | 2026.06.00 | `gradle/libs.versions.toml:9` |
| zxing | 3.5.3 | `gradle/libs.versions.toml:11` |
| SLF4J | 2.0.17 | `gradle/libs.versions.toml:7` |

Still unresolved:

- The aligned set is Kotlin `2.4.21` (KMP and Compose compiler plugins at the
  same version), CMP `1.12.2` and Compose BOM `2026.09.00`. That BOM resolves
  Compose `1.12.1`, the version CMP `1.12.2` expects; Kotlin `2.4.21` covers
  Gradle `8.14.3` and AGP `8.13.2`. The future `ui-common` Android target
  follows the Kotlin 2.3+ plugin and `android` DSL. The set is conditional on
  changing the repo-wide pins and is closed only by a configuration run.
- One Compose/Material 3 version set for Android and desktop. The Android BOM
  2026.06.00 is pinned for `app`; whether `ui-common` consumes that BOM, the CMP
  published set, or a single aligned set is not established. A mismatch breaks
  rendering rather than one isolated test, so a single resolved set is a
  precondition for writing a screen.
- Compatibility of Gradle 8.14.3 with the chosen CMP patch and KMP plugin set.
  No upgrade is assumed; the wrapper pin stays until a resolved check requires
  a change.
- AGP 8.13.2 with the Kotlin 2.2.0 KMP Android target in `ui-common`, including
  whether `app` and `ui-common` can share one AGP version without a change to
  the shipped Android build.
- JVM target alignment. `app` targets Java 17 and the JVM modules use JDK 21;
  the per-target `jvmTarget` for shared KMP code is not fixed.
- Runtime image and bundle tooling for Phase 2: jlink/JDK 21 runtime targeting,
  Linux package format, macOS signing and notarization, Windows signing.
- The Compose test artifact and task names for `ui-common`, which follow from
  the target layout.

## Module dependency edges

The intended graph is acyclic and points one way:

```
:protocol            JVM-pure wire engine, no project dependencies
    ^
    |
:core                JVM pure seams, depends on :protocol
    ^        ^
    |        |
:ui-common  :app / :harness   (Android and CLI actuals stay here)
    ^        ^
    |        |
:desktop    :harness remains a peer CLI, not a desktop dependency
```

`:core` depends on `:protocol`. `ui-common` depends on `:core`. `:desktop`
depends on `:ui-common`, `:core` and `:protocol`. `app` and `harness` depend on
`:core` after their pure seams move, with `app` additionally depending on
`ui-common` for the Android color actuals and Compose helpers. No edge runs
from `:protocol` to any other module, and no edge runs from `:core`,
`ui-common` or `:desktop` into Android framework code. `desktop-app.md:145`
names the shared JVM test task `:core:test`; this document uses `:core:test`
for the same role and records the label mismatch as an open item to settle
when the modules are added.

## Rig task changes

The current rig command stays as the base. Additions are test and compile
tasks, not device or network steps:

- `:core:test` for every seam moved into `:core`, including the shared
  `CircleState` codec cases that replace the two CLI schemas.
- a JVM-side `ui-common` test task for the pure helpers, named from the target
  layout chosen above.
- `:desktop:test` for identity-home resolution, discovery and file state
  holders, settings state and parcel round-trips, plus Compose UI tests of
  desktop cards and state transitions using Compose test APIs. No device and
  no network are involved.

The test tasks are added to both the local command in `docs/TESTING.md` §3 and
the CI command in `.github/workflows/rig.yml:39-43`. Compilation of the desktop
target is reached through its test task. A desktop packaging task is not added
to the rig in Phase 1; `:desktop:packageDeb` or an equivalent distribution task
is a candidate when the package format is settled. JDK 21 setup, Android SDK
setup, Gradle cache, concurrency grouping and the CLI distribution artifact
step are otherwise unchanged. Desktop-to-`flit-cli` walks remain labelled
`SIM-HARN` and do not become `DEVICE` evidence; the `localsend-cli` interop
checks are kept.

## Re-baseline point

The historical baseline at plan time was 778 tests: 362 protocol + 324 app +
92 harness. The current four-module XML inventory is 972 (443 protocol + 167
core + 254 app + 108 harness) with zero failures, errors and skipped cases
(`docs/TESTING.md:108-113,204-224`; `docs/QUALITY-CONFIDENCE.md:22-30`). It is a
floor for the desktop wave, not a target count.

Re-baselining happens at the first extraction layer that adds `:core` and
`ui-common` and runs the extended rig green. At that point the three original
test tasks are run with `--rerun-tasks`, counts are read from each module's
JUnit XML and cross-checked against source `@Test` annotations, and app,
harness, `assembleDebug`, `assembleRelease`, `installDist` and `distZip` remain
green in the same run. Tests moved out of `app` and `harness` into `:core`
migrate rather than disappear, so the per-module figures shift while the total
stays at or above 778; new cases raise it. No desktop total is asserted before
that measurement.

The re-baseline updates `docs/TESTING.md` §2, §3 and the verification record,
the count comment at `.github/workflows/rig.yml:1-3`, and the scale paragraph in
`docs/QUALITY-CONFIDENCE.md`, alongside the completion note for that layer.
Earlier totals — 302, 588, 714, 769 and 773 — stay labelled historical, and
778 becomes historical once a new green figure replaces it. Until a desktop
module exists and its tests run, this document claims no desktop binary,
desktop test result or device observation.

## Open questions

- CMP patch version and the single Compose/Material 3 set, checked against
  Kotlin 2.2.0 and Gradle 8.14.3 by resolved dependency output.
- `ui-common` target list, Android library plugin, AGP alignment and the exact
  test task names.
- Whether `:core:test` or the `:core:test` label from `desktop-app.md:145`
  is used for the shared JVM tests.
- Per-target JVM levels for shared KMP code given the app's Java 17 target.
- Package format, runtime image and signing for Phase 2 bundles.
