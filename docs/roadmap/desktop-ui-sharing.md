# Shared UI for the desktop target

## Status

No UI module exists in this repository. The declared modules are `:protocol`,
:harness`, `:app` and `:core`. Every UI file named below lives under
`app/src/main/kotlin/app/flit/share` and is compiled into the Android
application. There is no `ui-common` module, no desktop source set, no desktop
binary and no desktop test run. `flit-cli` in `harness/` is the only companion
program that runs on a box today.

This document records the planned `ui-common` KMP module and the boundary it
must keep. It is a plan: it adds no source set, no build task and no wire
change. The v2.2 wire surface — routes, headers, DTO fields and Circle event
verbs — stays as shipped. The JVM `:core` module exists as of 2027-01-11; the pure
seams that the UI files import need a shared home before those files can move.

The 778-test figure (362 protocol + 324 app + 92 harness) is a historical count
of the shipped state. It is a floor for the extraction, not a target count.

## What ui-common is for

`ui-common` holds the Compose presentation code that has no Android platform
dependency: the Circle feed, the pad card, the courier card helpers, the share
sheet helpers and the motion, shape and static-color tokens. It is shared by
the Android app and the single planned `jvm("desktop")` target. It owns no
state, no store, no signing, no file I/O and no carrier. Every action leaves
through a callback supplied by the host.

An `android.*` import in `ui-common` or in the desktop source set is a compile
failure, not a review note. The same rule applies to Android `Context`,
`Intent`, content `Uri`, SAF grants and permission launchers. Code that needs
one of those belongs behind an expect/actual split or stays in the app.

Extraction order follows `docs/roadmap/desktop-app.md`: the pure models and
reducers go to `:core` first (Phase 0), then the UI files move to `ui-common`,
then the Linux desktop surfaces are built (Phase 1) and parity work follows
(Phase 2). No desktop screen is written against a copy of app logic.

## Symbols to share

### CircleUi.kt

Move the public composable `CircleScreen` (`CircleUi.kt:131`) and the
presentation functions it owns: `PendingFeed`, `EmptyFeed`, `FeedRow`,
`MessageCard`, `FileCard`, `PollCard`, `VoteCard`, `KickCard`, `SystemCard`,
`UnknownCard`, `MemberSheet` (`CircleUi.kt:1014`) and `PadSheet`
(`CircleUi.kt:1167`). The private helpers travel with them: `peerLabel`,
`peerCaption`, `safeTypeLabel`, `timeOf`, the `JsonObject` extension readers,
`parseEvent`, `parseBody`, `isParcel` and `computeFeedData`, together with the
presentation-only types `CircleCard`, `CircleBody`, `ParsedEvent`, `PollTally`,
`CircleTallies`, `CircleFeedData` and `circleJson` (`CircleUi.kt:1206-1472`).
That parsing and tally layer is presentation logic: it parses each event body
once, derives poll and kick tallies, and folds verified receipts into the same
pass. It stays with the screen so both hosts render the same cards from the
same inputs.

`CircleScreen` takes `FlitCircleStore.CircleState`, `Member`, `Role` and
`CircleDelivery`, `CircleEvent`, `PadView` and `CourierHandoff`, and calls
`CircleCore.receiptAuthors`, `PeerAlias` and `FlitEngine.fmtBytes`. Those
types and pure functions must come from `:core` before this file can compile
in `ui-common`; none of them is a platform adapter. `timeOf`
(`CircleUi.kt:1471`) uses `java.text.SimpleDateFormat`, which is valid for the
single JVM desktop companion target and is not a reason to add another target.
The insets modifiers and `ModalBottomSheet` used by the sheets are Compose
APIs rather than Android platform calls; their desktop rendering is a check to
make during the version gate, not an existing result.

### PadUi.kt

Move the object `PadUi` (`PadUi.kt:54`) and its Android-free projection and
input-bound helpers: `PadRow`, `rows`, `keyError`, `textError`, `PadHitRow`,
`PadHitRows`, `search` and `searchRows`, plus the same-package typealiases and
convenience functions (`PadUi.kt:146-163`). Move the composable `PadCard`
(`PadUi.kt:184`) and its private `PadLineRow`. The helpers only project a
folded `PadView` and report the bounds that `PadCore` and `PadSearch` apply;
they hold no state and never sign or post. `PadCard` routes put, delete and
close through callbacks. `PadCore`, `PadView`, `PadSearch` and their limits must
be reachable from `:core`.

### CourierCardUi.kt

Move the whole object `CourierCardUi` (`CourierCardUi.kt:16`): constants
`HANDOFF_WAITING`, `HANDOFF_HANDED` and `HANDOFF_FAILED`; enums `CarryOutcome`
and `OpenAffordance`; `CourierHandoff`; and the functions `carryOutcome`,
`aliasOf`, `courierLine` and `openAffordance`. The typealiases and top-level
forwarders at `CourierCardUi.kt:115-135` move with it so existing call sites
keep their bare names. This object is pure JVM code today apart from one
`java.util.Locale` fold. It reads `FlitCircleStore.Member` and
`MAX_ALIAS_CHARS`, so it waits on the same `:core` extraction as `CircleUi`.

### SheetUi.kt

Move the object `SheetUi` (`SheetUi.kt:7`) with `initials`, `hueFor` and
`countSummary`. It has no imports and no dependencies beyond its arguments, so
it is the lowest-risk first move and the natural place to prove the shared test
setup.

### FlitTheme.kt

Move `FlitSpring` (`FlitTheme.kt:30-49`) with its six spring factories and
their coefficients, `FlitCorner` (`FlitTheme.kt:52-64`) with the ten Dp steps
and `full`, the `ThemeShapes` mapping (`FlitTheme.kt:67-73`) and the static
`GoogleBlueLight` / `GoogleBlueDark` schemes (`FlitTheme.kt:77-89`). These are
Compose and JVM values with no platform call. Keep `FlitTheme`
(`FlitTheme.kt:108`) in common as the wrapper that calls a shared color-scheme
function. Split `FlitThemeColors` (`FlitTheme.kt:93-105`) at the platform seam
described below.

## expect/actual splits

- **Color scheme.** Declare one expect composable that returns a `ColorScheme`
  for a theme override. The common side keeps the static schemes, the shapes
  and the dark/light choice from the override plus `isSystemInDarkTheme`. The
  Android actual keeps `Build.VERSION.SDK_INT`, `LocalContext`,
  `dynamicLightColorScheme` and `dynamicDarkColorScheme` in `app`. The desktop
  actual returns the static scheme and calls no dynamic-color API and no
  `Context`. This is the split named in `docs/roadmap/desktop-app.md`: Android
  dynamic color leaves `ui-common`; the desktop supplies its own color actual.
- **File picking.** Declare shared calls for picking files to send, one file
  to attach or decode, and a directory for received output. The Android actual
  uses `ActivityResultContracts.OpenMultipleDocuments`, `OpenDocument` and
  `OpenDocumentTree` with SAF URIs and persistable grants. The desktop actual
  uses a JVM file chooser that returns plain paths; there are no content URIs
  and no grants to persist. The shared cards know only callbacks and path or
  name strings. SAF stays app-side and is out of scope for Phase 1.
- **QR input and display.** Keep QR rendering and the text-entry field in
  shared code where they have no Android import. The Android actual for
  capture is the CameraX `PreviewView` and Y-plane analyzer in `QrUi.kt` and
  `ParcelUi.kt`, including the permission launcher. The desktop actual has no
  camera: Phase 1 invites are pasted as text or a link into a field, and parcel
  frames are pasted back in and decoded through the shared `Qr` and
  `ParcelScanState` seams. A desktop adapter that decodes a picked QR image
  file is a later variation on the same shared decoder, not a CameraX path.
  Optional webcam capture stays a separate Phase 2 item.
- **Notifications and tray.** Declare a notifier port. The Android actual is
  the notification channel, `NotificationCompat` builder and foreground
  service wiring in `FlitNotify.kt`, `FlitService.kt` and the boot, tile and
  shortcut receivers. The desktop actual is a system-tray icon and tray
  notification. This is Phase 2 parity work; Phase 1 has no FGS and no
  notification channel.
- **BLE radio.** The pure discovery model and reducers are shared through
  `:core`. The Android actual is `FlitBleRadio.kt` / `FlitBluetooth.kt` with
  their scan, advertise and permission wiring. The desktop actual is a no-op:
  no radio, no scan, no advertise, no discovered BLE peers and no permission
  prompts. Wi-Fi/UDP through `UdpDiscovery` and `ProtocolClient` /
  `ProtocolServer` is the desktop carrier in Phase 1; relay stays opt-in and
  last.
- **Stores.** The `Store` interfaces behind known-peer and roster logic stay
  shared; the Android actual persists through SharedPreferences and the
  desktop actual writes a local file under the CLI home. Identity reads the
  existing `~/.config/flit/flit-harness/identity.p12` rather than minting a
  second key, with `FLIT_HARNESS_HOME` as the two-node override.

## Android-only screens and adapters that stay

`MainActivity.kt` stays in `app`: it is the activity host for SAF pickers,
permission launchers, intent handling and the share sheet. The camera scanner
composables in `QrUi.kt` and `ParcelUi.kt` stay there with `PreviewView` and
the CameraX analyzer. The foreground service and notification stack stays in
`FlitService.kt`, `FlitNotify.kt`, `BootReceiver.kt`, `FlitTile.kt` and
`FlitShortcuts.kt`. The BLE radio adapters stay in `FlitBleRadio.kt` and
`FlitBluetooth.kt`. The platform navigation host, the SharedPreferences
actuals and the Android dynamic-color actual stay in `app`. None of these is
ported by copying its body into a desktop file; the desktop supplies an
actual or uses the shared callback-driven composable.

## Desktop-only adapters

The desktop target supplies the JVM file chooser, the paste-based QR and parcel
input path, the system-tray notifier and the no-op BLE radio described above.
It also supplies the color actual and the local-store actual. Phase 1 scope on
that base is identity from the shared CLI home, UDP discovery, file send and
receive through the existing protocol seams, local settings, QR invite display
and paste intake, parcel encode/decode with frames pasted back in, a local
courier bag folder and a local received ledger. No new transport is
introduced. Phase 2 adds the rest of the engine behind injected ports, Circle
host and governance at parity, tray notifications, macOS and Windows bundles
and an optional webcam QR path.

## Version gate

Before any UI file moves or any desktop screen is built, pin Kotlin 2.2.0
against CMP 1.9.x and JDK 21, keep the target list at the single
`jvm("desktop")` entry, and confirm one resolved Compose and Material 3
version set. A mismatch there breaks rendering across the shared cards rather
than one isolated test. The gate also covers the Compose insets and modal sheet
behavior on a desktop window, since the Circle sheets depend on them. `:core`
and `ui-common` must resolve with no Android dependency in the graph; a failed
compile on an `android.*` import is the intended enforcement. `flit-cli`
remains the interop rig and its human text output is not turned into a new
structured API for the desktop.

## Verification boundary

Moving a seam does not drop a test case. The shared pure helpers keep JVM
tests; the desktop target gets its own state-holder and round-trip tests;
Compose tests cover card state transitions without a device or a network.
Desktop-to-`flit-cli` walks are labelled `SIM-HARN`, the same evidence class
as other on-box walks, and do not become `DEVICE` evidence. No desktop test has
run and no physical device walk is claimed here.

## Open questions

- Which of `MemberSheet` and `PadSheet` should use a desktop window or dialog
  actual, and which render unchanged as Material 3 modal sheets, is settled
  during the version gate rather than assumed now.
- Whether the planned file-based QR image adapter lands in Phase 1 or after
  the paste path is proven is left open; paste is the Phase 1 input as scoped.
- The exact path and format of the desktop store under the CLI home is fixed
  when the store actual is written, with the one-home-per-node rule preserved.
- Tray notification scope for macOS and Windows follows the packaging work;
  it is not part of the Linux MVP claim.
