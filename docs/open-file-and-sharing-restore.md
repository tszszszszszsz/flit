# "Open file", sharing restoration, and the MIUI limit

> Part of the Flit design set. [Ladder and index](transport-bluetooth.md) ·
> [Circle](circle.md) · [README](../README.md).
> **Status: fix implemented in the working tree, 2026-11-25.**

> **Non-profit constraint (hard, everywhere):** no accounts, no cloud, no ads,
> no telemetry, no SDKs that phone home. This fix adds one local
> SharedPreferences row and one `<paths>` entry; no new permission, no new
> setting, no network call, no SDK. A file opened from a Circle card still goes
> to an external viewer chosen by the user — Flit never opens anything by
> itself, and the viewer learns nothing about Flit except the one file URI.

## The report

> "After tapping Share on Wi‑Fi, opening Circle, tapping **Open file** on a
> message, the app closes fully and sharing is disabled on next launch."

Reproduced from code and device log: the tap itself does not kill the process —
a *stack* of four independent weaknesses lines up on exactly that detour
(share-sheet entry → modal Circle → external chooser → return). Each weakness
was a real fault.

## Root cause (as traced)

1. **Share-sheet auto-finish could fire mid-detour.** `MainActivity` enters
   `shareMode = true` for `ACTION_SEND`/`SEND_MULTIPLE` and closed itself from
   `LaunchedEffect(share) { if (share == null && running) finish() }`. Opening
   Circle does not clear that mode. Returning from a chooser, a configuration
   change, or the intake being cleared for any other reason could therefore
   run `finish()` while the user was still inside the app.
2. **Back/dialog ownership was ambiguous.** The Circle feed is a full-surface
   `Dialog` with its own `onDismissRequest`, while `MainActivity` also had a
   `BackHandler(enabled = showCircle)`. Two handlers could both claim one back
   press, and the gap where neither claimed it fell through to the default
   `Activity.finish()` — the "app closed" report.
3. **`FileProvider` had no root for `Download/Flit/`.** Received files that had
   been copied to the public bucket (or to a SAF tree) can leave a ledger row
   whose path is under `Download/Flit/`. `FileProvider.getUriForFile()` throws
   when no `<paths>` root covers the file, and `digestOf()` read a stale path
   first — so the open path could fail hard instead of degrading to a message. The chooser also carried the read grant on only one of the two
   intents, which OEM resolvers may rebuild.
4. **There was no persisted "sharing is on" intent.** `FlitEngine.state.running`
   is in-memory. The FGS's `onDestroy()` called `FlitEngine.stop()`, the FGS
   type was over-broad (see below), and a killed process forgets `running`
   without the user ever pressing Stop. Next launch therefore came up with
   sharing off. The connected-device FGS type was requested unconditionally on
   API 34+, including Wi‑Fi-only sessions with no Bluetooth grant; a strict ROM
   (MIUI among them) can reject `startForeground()` for that, silently dropping
   the service to "engine-only", which dies as soon as the chooser backgrounds it.

None of these is a Circle bug: they are activity-lifecycle, URI-grant,
foreground-service and state-persistence seams that the share-sheet → Circle →
viewer detour happened to cross all at once.

## The fix, layer by layer

### Activity — a viewer is a detour, not a finish

`MainActivity` now launches the chooser through a lifecycle-bound
`ActivityResultContracts.StartActivityForResult()` launcher (`openExternal`).
Its result is deliberately ignored: a viewer can never finish Flit or tear down
the sharing/Circle state on the way back. `openReceived()` and
`shareReceived()` put the read grant on the **view/send intent, the chooser, and
`ClipData`** — OEM resolvers that rebuild `EXTRA_INTENT` still carry a grant.
`resolveReceived()` prefers the row's own content URI (its declared MIME beats
a filename guess), checks that a path still exists before handing out a
`FileProvider` URI, and uses one authority constant that must equal the
manifest's `android:authorities`.

Everything that can throw on that path is contained: a
`CoroutineExceptionHandler` (`openGuard`) wraps the Circle open coroutine, a
stale digest read returns `null` instead of propagating, and every exit is a
toast ("nothing can open …", "no location stored for …"), never a crash.

UI state that matters across a recreation is now `rememberSaveable` — including
`showCircle` and any in-flight Circle invite (carried by its own URI-based
`Saver`) — so returning from an external viewer restores the feed the user was
in instead of dropping them on a blank home. The Circle `Dialog` sets
`dismissOnBackPress = false` and `MainActivity` is the single owner of back:
one `BackHandler` dismisses the modal, another dismisses an overlay sheet, a
third steps a tab back — none of them finish the activity out of a modal state.
The share-sheet `finish()` still exists for its original purpose (a normal,
completed send), but it is no longer reachable through the Circle detour.

Internal rebinds (rename, hidden-mode toggle, carrier change) call
`stopForRebind()`, which never clears the persisted intent; an explicit Stop is
the only path that does. Rebind timers also check `isDestroyed` before starting
again.

### FileProvider — name only a real, covered file

`app/src/main/res/xml/file_paths.xml` gained an `external-path` root for
`Download/Flit/`. That closes the concrete failure above: a legacy or copied
ledger row pointing at the public receive bucket can now be turned into a valid
content URI, so "Open file" works there too. The provider authority in
`AndroidManifest.xml` (`app.flit.share.fileprovider`) is unchanged and matches
the code's constant. No exported provider, no new path beyond what the ledger
and Circle spool already use.

### Foreground service — a valid FGS is what survives the chooser

`FlitService` now promotes to foreground **first** (within Android's start
window), asks for `connectedDevice` only when the Bluetooth carrier is actually
enabled *and* its runtime grant exists, and otherwise asks for `dataSync`
alone — which is always correct for UDP discovery and TCP/TLS transfers and is
declared in the manifest. If a ROM rejects one of the types with a
`SecurityException`, the service retries with the `dataSync` baseline: losing
the `connectedDevice` label is better than losing the FGS.

The stop path: a refused start `stopSelf(startId)`s **only
that start** instead of poisoning every later Stop; `START_STICKY` is kept for a
live FGS; `onTimeout()` removes the notification promptly (avoiding the fatal
`RemoteServiceException`) while leaving the engine running for as long as a
Flit surface is foreground; and `onDestroy()` calls `FlitEngine.stop()` only
for an instance that actually owned the engine through a live, non-timed-out
FGS. An external chooser taking the foreground is therefore an ordinary
background transition — not a service teardown.

### Persisted intent — `running` is not the sharing intent

`FlitRoster.SharingDesired` is a new local row (`sharingDesired` in the app's
own `SharedPreferences`) recording the last explicit sharing intent (ON).
It is deliberately not the live engine state:

- **Write** on every deliberate start — share-sheet, invite link, or silent
  restore — via `setSharingDesired(true)`.
- **Clear** on an explicit Stop (`setSharingDesired(false)`), and in the service
  when it owned a live foreground service that is stopping (including
  a failed FGS promotion that calls `stopSelf`); internal rebinds (alias, relay,
  Bluetooth, timeout) never clear it. `am force-stop` bypasses `onDestroy`
  entirely, so it preserves the row and the next launch restores silently.
- **Read** on a cold `onCreate()` and on `onResume()` via
  `shouldRestoreSharing(running, starting)`, which restores *silently* — no
  re-prompt on a cold start — and only when nothing is already up or coming up.

The codec is a pure, tolerant reducer: JSON envelope for new rows; bare
`true/1/yes` and `false/0/no` words decoded from older rows; absent, blank,
corrupt and forward-newer rows all decode to "not desired" and never throw.
`with()` and `shouldRestore()` are pure and unit-tested. No account, no cloud —
the row is a few bytes in the app's own preferences.

### Circle card — three states

The file card's open affordance no longer offers a button that can only
dead-end. It derives per-card state from one normalised set of landed SHA-256
values (received ledger plus sender spools, rebuilt off the UI thread after a
fresh process):

| Card state | Visible state | What a tap does |
| --- | --- | --- |
| resolving | **"Opening…"**, disabled button, spinner | nothing; a second tap cannot launch a second chooser |
| landed | **"Landed here"** + **"Open file"** | opens the chooser for that exact hash |
| not landed | **"Not here yet · no local copy to open"** | no button at all |

Hashes are compared case-insensitively, matching the engine's own comparison.
Merged semantics labels keep TalkBack accurate ("Open … landed on this device",
"Opening …, please wait"). The set is released in a `finally`, so no card stays
disabled after an attempt finishes. With no local record the neutral
"file drop · open to view if it arrived" line is kept — delivery status is
sender-local and is never gossiped (see [Circle](circle.md#file-delivery-as-built)).

### Pure seams and tests

The restore decision and the codec are Android-free, so they are tested without
an engine, a context or a device (`SharingRestoreTest`, plus the codec cases in
`FlitRosterTest`): default-off, round-trip, old bare-word rows, corrupt and
forward-newer rows, idempotence, and the rule that restore fires only when the
intent is desired and neither `running` nor `starting`. This is the seam that
must hold when a viewer drags the activity through a configuration change or a
process death.

## Behaviour after the fix

- Tap **Open file** on a Circle card whose bytes are here → a chooser appears;
  pick a viewer; come back → Flit is still there, the Circle feed is still up,
  and sharing is still on. Cancelling the chooser or pressing back is a no-op.
- A card with no local copy shows no Open button; while a hash is being resolved
  the button is disabled; neither state can close the app.
- A failed open (no location, no viewer, a revoked grant, a stale path) ends in
  a toast, not a crash or a vanished app.
- Relaunch after the process was killed: sharing comes back silently — no
  notification prompt on cold start, no duplicate engine. An explicit Stop
  stays stopped; that is the one case where the persisted row is false.
- Internal rebinds keep sharing desired across their brief stop/start.

## Sharing state: what is stored and when

| Event | `sharingDesired` | engine `running` |
| --- | --- | --- |
| Share-sheet / invite / silent restore starts sharing | set `true` | becomes true |
| Explicit Stop | set `false` | false |
| Rename / hidden / carrier rebind | unchanged (`true`) | briefly false, then true |
| External chooser / viewer takes foreground | unchanged (`true`) | stays true while the FGS lives |
| Process killed (MIUI, memory, dataSync cap) | unchanged (`true`) | false until next open |
| Next cold `onCreate`/`onResume` | read | restored silently if desired and not already up |

## MIUI / Android limitations

These are recorded:

- **MIUI can still kill the process.** Battery saver, "autostart" rules and
  memory pressure are outside app control. The persisted intent means the
  *next time the user opens Flit* sharing comes back; it does **not** mean MIUI
  will restart the app on its own. A user who force-stops Flit or revokes autostart
  will still see sharing off until they open the app or start it explicitly.
- **An explicit Stop is honoured.** If the user turned sharing off, the row is
  false and nothing silently turns it back on — by design.
- **Android 15 rations `dataSync` foreground time** (about six hours a day).
  At the cap `onTimeout()` drops the notification to avoid a system crash but
  leaves the engine alive while a Flit screen is open; after the last surface
  closes there is no background guarantee for the rest of that day's budget.
  The next cold open restores sharing from the persisted row.
- **A strict ROM may refuse the FGS start altogether.** The service then runs
  engine-only: it serves peers while Flit is foreground, but background
  sharing is not guaranteed. The user is told with one toast;
  misled.
- **Bluetooth is opt-in on this device.** The engine still
  logs "bluetooth link waiting — permission or radio off" while Wi‑Fi carries
  the roster; that is unrelated to this fix and is documented in
  [the transport ladder](transport-bluetooth.md).
- **A viewer is an external app.** Its ability to render a given MIME type, and
  any restrictions MIUI places on *it*, are not Flit's to promise. Flit only
  guarantees a valid URI with a read grant and a clean return.

## Evidence and verification status

- **Code-traced:** the four root-cause weaknesses above and the corresponding
  fixes, each in the files named in the sections above.
- **Pure-test-covered:** the `SharingDesired` codec and restore decision
  (`SharingRestoreTest`, `FlitRosterTest`) — no device required.
- **Device reproduction:** a capture on the phone shows a single Flit process
  (`app.flit.share`, pid 13483) starting at 21:22:45 and staying up with the
  engine serving (`serving https://0.0.0.0:53317`, periodic `mprober … ok` and
  `circle sync: full-log repair`) through 21:30 with no `stopped` line, no
  `am_crash` and no `RemoteServiceException`. That is consistent with the
  fixed behaviour; the final tap → chooser → return walk and the green-rig
  record are not claimed here.

> **Root-cause scope (2026-11-25):** the "app closes, sharing off" report is a
> stack of lifecycle, dialog, URI-grant, FGS-type and state-persistence faults,
> not a single FileProvider throw; fixing only the URI would have left the
> app-close and the next-launch behaviour intact.

## Device evidence (2026-11-25, final build)

- A fresh 35 B Circle drop (`open-check.txt`) landed hash-exact on the phone
  (`c421969b…`), and its card showed **"Landed here"** with an enabled
  **Open file** button.
- Tapping Open file opened `ChooserActivity`; `pidof app.flit.share` stayed
  alive (15834) and the log had **no** `FATAL`, `am_crash`,
  "crashed too many times" or `Force finishing`.
- Back from the chooser returned to the Circle feed with the dialog intact.
- `am force-stop app.flit.share` → relaunch: new PID 16970 logged
  `egress pinned…` and `serving https://0.0.0.0:53317…` automatically, and the
  Home card showed **Stop** (sharing on). This is the persisted
  sharing-desired restore, not a manual toggle.
