# Desktop planning wave — summary

Twenty parallel tasks produced four orchestration plans, six specialist audits,
seven planning documents and four QA audits. No build file, source file, test or
desktop module was changed. This summary records the decisions and the gates
that must be closed before implementation starts.

## Decisions

- **Stack.** Compose Multiplatform desktop with a single `jvm("desktop")`
  target, JDK 21 and AGP-managed Android targets, with Kotlin and Compose pins set by Gate 0. JavaFX and Swing
  discard the existing Compose presentation code; Electron and Tauri add a
  second ecosystem and need a structured CLI contract that does not exist.
- **Modules.** A JVM `:core` shared by app, CLI and desktop; a KMP `ui-common`
  with Android and desktop targets; a `:desktop` application. The current
  three-module rig stays green until an extraction step actually moves tests.
- **State and identity.** One persisted `CircleState` codec replaces the CLI's
  eight-line file; legacy files migrate on first write. `--host-fp` becomes a
  stored field, and the desktop loads the CLI's `identity.p12` rather than
  minting a second key. One home per running node remains a hard rule.
- **Evidence labels.** Desktop-to-CLI walks are SIM-HARN, not DEVICE. No
  desktop build, bundle, test count or device observation is claimed.

## Gate 0 — dependencies before code

Gate 0 cannot close on the shipped pins. BOM `2026.06.00` resolves AndroidX
Compose `1.11.3`, but no published CMP release maps to that version: CMP
`1.11.1` maps to Compose `1.11.2` and CMP `1.12.2` maps to Compose `1.12.1`.
Kotlin `2.2.0` also has no published KMP-plugin envelope for AGP `8.13.2`
(the range ends at `8.10.0`, with Gradle capped at `8.14`). The aligned set is
the conditional upgrade to Kotlin `2.4.21` (KMP and Compose compiler plugins
both `2.4.21`), CMP `1.12.2` and Compose BOM `2026.09.00`, which resolves
Compose `1.12.1` — the version CMP `1.12.2` expects. Kotlin `2.4.21` covers
Gradle `8.14.3` and AGP `8.13.2`. The condition is that the repo-wide Kotlin
and BOM pins change and the future `ui-common` Android target follows the
Kotlin 2.3+ `com.android.kotlin.multiplatform.library` plugin and `android`
DSL; if that upgrade is not taken, Gate 0 stays open rather than adopting a
nearest-match pin. Gate 0 closes only after a configuration run proves the
plugin matrix and a dependency report shows one resolved Compose set. No screen
or module move starts before it. Test tasks are named `:core:test`,
`:ui-common:desktopTest` and `:desktop:desktopTest`; `:common:test` was a label
error and is removed.

## Extraction order

1. Register `:core` with no source moves; keep the 778-test floor.
2. Move leaf pure files with their tests: `FlitRoute`, `SheetUi`, `FlitMirror`,
   `FlitSpeed`, `FlitSpool`, `FlitSubnet`, `FlitCrush`, `FlitBle`.
3. Move the pure halves of `FlitKnown` and `FlitCircleStore`, leaving Android
   `Context` adapters in the app. Add a `FlitRosterStore` seam for roster state.
4. Move `FlitCircleHost`, `CourierCardUi`, `ParcelScanState` and `Qr`.
5. Move `FlitCircleSync` pure helpers and `FlitEngine` pure islands behind
   thin app delegates, keeping the worker objects in the app until Phase 2.
6. Converge CLI persistence onto the shared codec with one-shot legacy
   migration, then add `ui-common` and the desktop target.

Move targets and per-file line ranges are in `desktop-core-contracts.md`,
`desktop-ui-sharing.md` and `desktop-state-convergence.md`. Test disposition
and re-baseline figures are in `desktop-test-migration.md`. Build changes are
in `desktop-toolchain.md`; risks and their verification are in
`desktop-risk-register.md`.

## Divergences found during planning

- `circleNextLamport` in the CLI starts at `0` on an empty log; the app starts
  at `1`. This is the only outright value divergence among the duplicated
  helpers and must be unified before key rotation parity can be claimed.
- The CLI answer-fold helpers omit `validShape` and `CircleCore.accept` and
  lack the PARCEL body gate; they otherwise match the app gates.
- CLI delivery status lacks `NO_KEY` and maps an empty `sent`/`failed` report
  to `REFUSED` where the app uses `report.declined`.
- QR rendering hints differ between `ParcelCli`, `parse-qr` and `Qr.decode*`;
  frame grammar is shared and identical.
- `FlitCircleSync` reaches back into `FlitEngine` and Android `Context` at
  four sites, so its worker object cannot move before ports exist.
- `FlitRoster` has no `Store` seam; it reads SharedPreferences directly.

## Re-baseline rule

The 778 figure (362 protocol + 324 app + 92 harness) is the historical floor.
At the first extraction commit that moves a test-bearing seam, the rig header
and task list change in the same commit. Moved tests keep their assertions and
evidence class; no case may be dropped. New core tests raise the total and make
778 historical. The next implementation wave is gated on Gate 0.

## Out of scope for the planning wave

No desktop source set, no Gradle plugin application, no installer, no webcam
scanner, no BLE on desktop, and no device walk. The phone walk remains partial
as recorded in `docs/completions/2026-11-27-device-walk-partial.md`.
