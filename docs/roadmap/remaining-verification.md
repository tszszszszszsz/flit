# Remaining verification — executable plan

This file is a plan. It records no walk result, screenshot, log excerpt or landed
hash. The flows below are the ones left unverified by
`docs/completions/2026-11-27-device-walk-partial.md` and by the device-walk
evidence table in `docs/TESTING.md` §6. The step wording, log lines and commands
follow `docs/roadmap/device-walk-2026-12-31.md` and the shipped CLI help surface;
where this file names a level, it names the level attainable if the observation
is actually made, not a level already earned.

Evidence rule carried from the plan: a claim reaches DEVICE only when it is
captured in this run's phone logcat or UI observations, or in CLI output and
state files tied to the same phone-centred walk. A CLI-only exercise with no
phone observation stays SIM-HARN. For every refusal, record the exact reason
line and the exit code. Results go to
`docs/completions/<actual-date>-full-feature-walk.md` using the template at the
end of `docs/roadmap/device-walk-2026-12-31.md`; no value is copied into that
note from an earlier document without naming its source.

## Common preconditions

- Node A is a physical Android phone running `app.flit.share`, with
  `flit-identity.p12` in app `filesDir` and `FlitCircleStore` under prefs `flit`.
  Nodes B and C are box CLI identities in separate `FLIT_HARNESS_HOME` values,
  for example `/tmp/opencode/walk-b` and `/tmp/opencode/walk-c`. No home is
  shared between identities, and no one-shot identity uses a home while a
  `serve` process runs there. Discard any observation made under a shared home.
- Before the first step record the installed artifact and the environment:
  `sha256sum <installed-apk>`, `adb install -r <installed-apk>`,
  `adb shell dumpsys package app.flit.share | grep versionName`,
  `adb getprop ro.product.model`, `adb getprop ro.build.version.release`,
  `adb getprop ro.build.version.sdk`, `adb shell pidof app.flit.share`,
  `sha256sum $(readlink -f "$(command -v flit-cli)")`, the `flit-cli` help
  output, `date -Ins` and `adb shell date`.
- CLI launcher check. The installed `~/.local/bin/flit-cli` help surface lists
  `circle <create|join|post|poll|vote|role|kick|leave|sync|file>` and omits
  `receipt`, `pad`, `courier`, `parcel` and `--host-fp`. The distribution under
  `harness/build/install/flit-cli/bin/flit-cli` lists all of them. Steps 1, 3
  and 4 below require a launcher whose own help output exposes the commands
  used; use that launcher and record its hash. Do not report output from a
  command the selected launcher rejects.
- Seed fixtures on the box and record in-run digests:
  `printf 'full-walk-seed-1\n' > /tmp/opencode/walk-seed.txt`,
  `head -c 8192 /dev/urandom > /tmp/opencode/walk-8k.bin`, and `sha256sum` on
  both. Quote only hashes read from `sha256sum` in this run.
- Start log capture before step 1 and keep it through step 8:
  `adb logcat -v threadtime -s FlitSend:I FlitBleRadio:W FlitOpen:W > walk-logcat.txt`.
- Circle joins pin `--host-fp` to the fingerprint printed by the host. The CLI
  help describes it as the fingerprint pinned for later answers; a rotation
  learned from any other source is not evidence of a host-signed rekey.
- The current argument parser records every occurrence of a repeated value
  flag (`opts()` in `Harness.kt`), and `VALUE_FLAGS` includes `--host-fp`,
  `--sha`, `--drop-id`, `--key`, `--bag`, `--to`, `--cert`, `--ttl`, `--dir`
  and `--out`. The plan's poll step uses one comma-separated `--option` value;
  keep that form and check the observed tally against the printed CLI output
  rather than against an assumed overwrite rule.

## 1. Receipt minting on a keyed Circle drop

**Prerequisites.** Phone A is the Circle host; B and C have joined with
`--host-fp` pinned. B needs a usable certificate for each current member, since
`circle file` wraps to the roster and prints `!! no recipient key material —
nothing posted` when none exists. Wi-Fi is on and the phone answers at
`<phone-ip>:53317`; `Downloads/Flit` on the phone is writable. Start C's
listener under C's own home and keep it through the receipt step, with no
second identity in that home:

```bash
export FLIT_HARNESS_HOME=/tmp/opencode/walk-c
flit-cli serve --dir /tmp/opencode/walk-c-downs

export FLIT_HARNESS_HOME=/tmp/opencode/walk-b
flit-cli circle file --file /tmp/opencode/walk-seed.txt --host <phone-ip>:53317
sha256sum /tmp/opencode/walk-seed.txt
adb shell sha256sum /sdcard/Download/Flit/walk-seed.txt
adb exec-out screencap -p > evidence/keyed-card.png

export FLIT_HARNESS_HOME=/tmp/opencode/walk-c
flit-cli circle receipt --sha <plaintext-sha256> --host <phone-ip>:53317
flit-cli circle sync --host <phone-ip>:53317
export FLIT_HARNESS_HOME=/tmp/opencode/walk-b
flit-cli circle sync --host <phone-ip>:53317
```

Stop C's `serve` after this step. The 2026-11-27 note recorded `circle receipt`
refusing with `no key id for drop` because the only file sent was a direct peer
send; rerunning the command against that legacy event reproduces the refusal,
not a receipt. A keyed drop must land first.

**Observation to capture.** From B: `file <sha12> → … event(s), … member(s),
gen=…` and the status line with `status is local-only, never gossiped`. In B's
stored `circle.state`, the `file` body must show exactly `name`, `size`,
`sha256`, `keyGen`, `cipherSha`, `iv` and `wraps`, with one wrap per current
member and each wrap key equal to a roster fingerprint. From the phone:
`receiving walk-seed.txt` and `→ Downloads/Flit/walk-seed.txt`; the on-device
`sha256sum` equal to the announced plaintext `sha256`; the file card reading
`Landed here` with an enabled `Open file`; and the card's confirmed count. Hash
the sealed `*.flitdrop` copy against `cipherSha` only if that copy is available
in this run. From C's listener: `drop-open <name> <plainSha> (<bytes> bytes)`
and the landed hash under `/tmp/opencode/walk-c-downs`; if the listener did not
run, record no landing rather than inferring one from the announcement.

For the app-minted receipt, read the signed body keys `{dropId,sha256,keyId}`
in the log and record `keyId` as `<file-event-id>:<keyGen>` from the keyed
event, with no invented value. For the explicit CLI receipt capture `receipt
<id8> → … event(s), … member(s), gen=…`, the confirmed counts printed by
`circle sync` on B and C, and the confirmed count on the phone card. Separate
the phone-minted receipt from the CLI-minted receipt by author fingerprint.
Record `!! no key id for drop — receipts are for keyed drops` and its exit code
as a refusal branch when `--sha` names a legacy or direct-send file. A malformed
receipt, a replayed id and a non-member author stay at their prior level unless
produced in this run with their refusal lines captured.

**Evidence label attainable.** DEVICE for the hash-exact phone landing, the
card count, the CLI receipt line and the CLI confirmed count when all are
captured in this phone-centred run. SIM-HARN for a keyed drop and explicit
receipt exercised only between two box homes. CODE and PURE for the closed
receipt body, the `receiptAuthors` tally and canonical bytes
(`ReceiptBodyTest`, `ReceiptTallyTest`, `ReceiptCanonicalTest`), and SIM-HARN
for `ReceiptCliTest` and `DropKeyCliTest`. A confirmed count is evidence of
signed authors in the local log, not a claim about delivery to every member.

**If the prerequisite is unavailable.** Without the phone, the keyed drop and
`circle receipt` stay SIM-HARN and receipt minting on a device remains not
verified. With a stale CLI launcher there is no `circle receipt` command at
all; use a launcher whose help lists it, otherwise leave the step not verified.
Without a usable member certificate, record the `no recipient key material`
refusal and post no keyed event. Without C's listener, record no C landing.

## 2. Officer-only REKEY and departure rotation

**Prerequisites.** Phone A is host; B has been promoted to ADMIN from the
phone's member sheet; C is present as a plain member so the kick has a target.
The CLI launcher must expose `circle role`, `kick`, `leave`, `sync` and
`--host-fp`. The phone holds OWNER authority; `circle role --target FP --role
ADMIN|MEMBER` is documented as OWNER-only and can be used from a box only when
that box holds the owner identity. Three distinct identities are the minimum
for the kick path. The partial walk had a plain-member CLI peer and a `serve`
host, so no officer action was performed there; the 2026-11-26 baseline did
record B promoted to admin kicking C with rekey, but only for exactly three
identities.

```bash
export FLIT_HARNESS_HOME=/tmp/opencode/walk-b
flit-cli circle kick --target <C-fp> --host <phone-ip>:53317
flit-cli circle sync --host <phone-ip>:53317
flit-cli circle leave --host <phone-ip>:53317
export FLIT_HARNESS_HOME=/tmp/opencode/walk-c
flit-cli circle sync --host <phone-ip>:53317
```

**Observation to capture.** On the phone, `circle host: kick <fp12> — N
event(s)` and `circle host: sync <fp12> — N event(s)`; the member sheet showing
the promotion; the roster dropping from three to two after the kick; and the
advance of `rekeyGen` in the stored state. In B's signed log capture the
`kick`, `leave` and `rekey` bodies with their authors and generations. Confirm
that the promoted admin's kick executes immediately rather than opening a
vote-kick. After the leave, capture C's later `circle sync` refused with `!!
sync refused: host refused: not-a-member` while C's earlier local events remain
readable. Record whether a second `OWNER` is appointed, and run `role` and a
direct `kick` as C to capture the exact ordinary-member refusal reason and exit
code.

The officer-only fold rule, the same-batch hand-off veto and the
order-independent pre-batch authority check stay at their prior level unless a
crafted REKEY from a non-officer or a departing officer can be emitted on this
rig and its refusal line captured.

**Evidence label attainable.** DEVICE for the promotion, kick, forced
rotation, roster change and departed-member refusal when phone log, UI and CLI
output exist in this run. CODE and PURE for the authority truth table and the
store/host invariants (`CircleAuthorityTest`, `FlitCircleStoreTest`,
`FlitCircleHostTest`) and SIM-HARN for `CircleHarnessTest`, which already drives
an admin kick and rekey over production routes with two CLI nodes.

**If the prerequisite is unavailable.** With no phone host, a CLI `serve` host
can show a kick and rekey but supplies no phone promotion line or host log; keep
the result at SIM-HARN. With no third identity the kick cannot be observed; use
`leave` to force a rotation and record the kick as not verified. With a stale
launcher lacking `--host-fp`, a joined CLI peer cannot pin host answers, so a
host-signed rotation cannot be confirmed.

## 3. Courier bag handoff

**Prerequisites.** B and C have distinct harness homes, C is absent from
Circle (its listener stopped after step 1), and the phone's sharing surface is
up so the walk stays phone-centred. The launcher must expose all five `courier`
subcommands. C's certificate must be available as the Base64 DER value accepted
by `--cert`, and `--to` must be C's fingerprint; `seal` refuses a certificate
whose fingerprint differs from `--to`. The bag is a copyable directory, and no
new route or header is added by the transfer. A phone carry line exists only if
the app has published a handoff entry for that SHA-256.

```bash
export FLIT_HARNESS_HOME=/tmp/opencode/walk-b
flit-cli courier drop --to <C-fp> --text 'walk-courier-note' \
  --circle <ID> --cert <C-cert-base64> --bag /tmp/opencode/walk-bag
flit-cli courier carry --bag /tmp/opencode/walk-bag
flit-cli courier status --bag /tmp/opencode/walk-bag
# directory copy of the bag into C's harness home is the handoff transport
export FLIT_HARNESS_HOME=/tmp/opencode/walk-c
flit-cli courier receive --bag /tmp/opencode/walk-c/courier-bag
flit-cli courier status --bag /tmp/opencode/walk-c/courier-bag
```

**Observation to capture.** Capture `courier.state` rows on both nodes before
and after the copy, the `<id>.parcel` file names, the opened JSON under
`courier-received/`, and every exit code. The row states should move
`pending → carried` on carry and `→ delivered` on receive. Repeat the same
parcel into C's bag and record one fold and one `delivered` row, keeping the
replay result separate from the first handoff. On the phone look for the exact
lines `courier: holding for <fp>`, `courier: handed off N parcel(s) to <fp>`,
`courier: handoff failed to <fp>`, `courier parcel unreadable — rejected` and
`courier: bag row unreadable, starting empty`. A carry line on the phone card
is process-local and is not copied from the bag; record it only if an entry is
observed, otherwise record that no carry line was present. CLI evidence comes
from `courier status` rows and bag contents, not from a count on the card.
Tampered, expired and multi-carrier chains remain CODE, PURE and SIM-HARN
unless exercised here with their refusal rows captured.

**Evidence label attainable.** DEVICE for the bag rows and handoff when CLI
output is captured in this phone-centred walk and the phone log or card is
recorded alongside it. SIM-HARN for a bag copied between directories on one box
(`CourierCliTest`, `CourierCarrierHarnessTest`); CODE and PURE for the envelope
codec, carry decision and state-transition rules. A phone carry line requires
an observed card entry; without one it is absent, not implied.

**If the prerequisite is unavailable.** With no phone, the CLI bag copy stays
SIM-HARN and the handoff remains not verified on device. With no second home or
no copy transport, there is no handoff to observe. A certificate fingerprint
mismatch refuses `drop` before a parcel is written; record that reason and do
not count a state row as a handoff. Without a phone card entry, record the
carry line as absent.

## 4. QR parcel scan on the phone

**Prerequisites.** A second display surface — a second screen or a second
phone — must be able to show PNG frames full-screen in front of the phone
camera. The phone's camera permission must be granted; if it is denied, frame
strings can be pasted instead, but that is not a camera observation. Radios are
off and no listener is running:

```bash
adb shell settings get global wifi_on                  # 0 expected
adb shell settings get global bluetooth_on             # 0 expected
pgrep -af 'flit-cli (serve|relay)'                     # no output expected
```

The launcher must expose `parcel encode` and `parcel decode`. The phone
certificate is staged on the box as `phone-cert.b64`, and `--to` equals that
certificate's fingerprint. The 8 KiB fixture has a recorded digest. Restore
radio settings after the step. The 2026-11-27 walk encoded 7 PNG frames on the
box with the phone certificate; no phone scan was performed, so that result
stays CLI-only.

```bash
flit-cli parcel encode /tmp/opencode/walk-8k.bin --dir /tmp/opencode/parcel-frames \
  --to <phone-fp> --cert phone-cert.b64
```

**Observation to capture.** Record `EncodeResult.dir`, `frames`, `sealedChars`
and `sha256`, plus the exit code (0 verified, 2 bad args or missing cert, dir
or out, 3 verification failure). On the phone's parcel Scan tab record the
shown signer, SHA-256 and byte count, then
`adb shell sha256sum /sdcard/Download/Flit/parcel-<timestamp>.bin` and compare
it with the encode digest. Capture `parcel → Downloads/Flit/<name> (<size>)`, or
`parcel: could not create Downloads/Flit/<name>` / `parcel: could not write
<name>` on failure, and confirm that no `/partial`, `queued` or `.part` line
appears for this path. Three loops must produce one landed file.

For the phone-to-box direction, stage the box certificate and the seed fixture
on the phone, capture one image per frame index per loop from the phone's Show
screen, and run `flit-cli parcel decode --dir evidence/frames --out
/tmp/opencode/parsed-seed.txt`. Record `out`, `bytes`, `sha256`, `signerFp` and
`recipientFp`, with `recipientFp` equal to the box fingerprint; the fixture and
certificate are not frame inputs. `flit-cli parse-qr` decodes pairing codes and
is not a substitute for `parcel decode`.

**Evidence label attainable.** DEVICE for either direction when frame captures,
decode or landing output, hash comparison and timestamps exist. SIM-HARN for a
one-box CLI encode → decode loopback (`ParcelCliHarnessTest`), kept separate;
CODE and PURE for the frame grammar and tamper seam (`ParcelCodecTest`,
`ParcelCodecTamperTest`) and for the in-process reducer (`ParcelScanStateTest`).
No radio carrier is part of either direction.

**If the prerequisite is unavailable.** With no second screen or monitor that
can be placed before the camera, write the box-to-phone direction as not
verified and keep the phone-to-box direction. With camera permission denied,
record the pasted-frame path as a non-camera observation. With a stale launcher
there is no `parcel` command; if no CLI with `parcel decode` is available, both
directions stay at their prior codec level.

## 5. Bluetooth RFCOMM between two devices

**Prerequisites.** A second physical phone is required, or at minimum a box
with a Bluetooth adapter for one side. The bench box has no adapter
(`bluetoothctl list` empty in `docs/TESTING.md` §6) and the rig has one phone.
Both devices need the Nearby-devices runtime permission (the Android 12+
permission split), Bluetooth radio on, discoverability or pairing, and the
app option "Transfer over Bluetooth" enabled. Wi-Fi is removed to force the
carrier, the system Nearby-devices sheet and radio-enable dialog are accepted
once per device, and the original radio settings are restored at the end.
This is a phone-to-phone transfer; the CLI has no real RFCOMM path.

```bash
adb shell settings get global wifi_on
adb shell settings get global bluetooth_on
adb shell settings put global wifi_on 0
flit-cli bt-sim --bytes 512                 # pipe check only, not a radio walk
```

**Observation to capture.** On both phones, capture `no Wi‑Fi network; direct
discovery on`, `bluetooth link waiting — permission or radio off` if permission
or power is missing, any `hunt: <ip>:<port> answered as <alias>` line, any
`FlitBleRadio` warning (`advertising set failed`, `scan start failed`, `scan
failed: <code>`), and `bluetooth link: listening` where the opt-in is reported.
Then send a seeded file over the carrier and record an accepted offer and a
hash-exact landing on the receiving phone with the on-device `sha256sum`.
After restoring Wi-Fi capture `access point back; direct discovery dark`.
Record whether any `ble duty budget: <reason> — <explanation>` line appeared in
`walk-logcat.txt`; the shipped 55 s / 10 s / 45 s / 2,000 bps schedule is an
arithmetic check and radio duty is not measured here. Backoff doubling, cap and
handshake bounds stay PURE from `FlitBluetoothTest`.

**Evidence label attainable.** DEVICE only if both phones' logs and the
receiving phone's landed hash are captured. On the present rig the flow is
**not verified**: one phone and a box without a Bluetooth adapter cannot form
an RFCOMM pair. `bt-sim` is SIM-HARN and is never reported as a radio walk;
`FlitBleTest` and `FlitBluetoothTest` are PURE; the toggle-to-`listening` smoke
is a single-device observation, not a transfer.

**If the prerequisite is unavailable.** Keep the flow as not verified and name
the missing item — second phone, adapter, or permission — in the limitations.
Do not infer reachability from `bt-sim` or from a single `bluetooth link:
listening` line.

## 6. Mesh larger than three identities

**Prerequisites.** Phone A as host plus at least three further distinct
identities, for example box homes B, C, D and E with separate identities and
separate `FLIT_HARNESS_HOME` values. Each joins with `--host-fp` pinned and
mints its own signed JOIN proof. No home is shared with a `serve` process. A
second physical phone could serve as one member over Wi-Fi, which would not by
itself prove RFCOMM. Roster capacity is not the binding constraint: the host
allows up to 500 members and a keyed drop wraps up to 32; the missing item is
the fourth identity and its observation.

```bash
export FLIT_HARNESS_HOME=/tmp/opencode/walk-d
flit-cli circle join --host <phone-ip>:53317 --circle <ID> --invite <TOKEN> \
  --host-fp <host-fp printed by the app>
flit-cli circle post --text 'mesh-fourth' --host <phone-ip>:53317
flit-cli circle poll --text 'Mesh?' --option yes,no --host <phone-ip>:53317
export FLIT_HARNESS_HOME=/tmp/opencode/walk-e
flit-cli circle vote --option 0 --host <phone-ip>:53317
# repeat joins, syncs, a keyed file and a kick until the roster is >= 4
```

**Observation to capture.** Record each member fingerprint prefix, the roster
count on the phone and in each `joined <ID>: N member(s), M event(s), key=…`
line, and a host line `circle host: join <fp12> — N event(s)` plus `circle
sync: +K event(s) from <fp12>` for each added member. Confirm that a post from
one box renders on the phone and appears in every other joined home after
`circle sync`. Confirm the poll keeps one latest answer per member across four
or more authors. A keyed drop must show one wrap per current member, and a kick
must reduce the roster from four to three while `rekeyGen` advances; record the
departed member's `not-a-member` sync refusal.

**Evidence label attainable.** DEVICE for the roster, host lines, cross-node
events and rotation when the phone log/UI and CLI output are captured in the
same run. SIM-HARN for four or more box identities with no phone observation.
CODE and PURE for Lamport convergence, latest-per-member tallies and the wrap
cap (`CircleCoreTest`, `DropKeyCoreTest`). The 2026-11-26 three-node walk is
on-device evidence only for the exact three-identity case.

**If the prerequisite is unavailable.** With fewer than four identities, keep
the flow not verified; do not extrapolate from the three-node baseline or from
a two-node `CircleHarnessTest`. With no phone, the result is SIM-HARN at most.
Record which identities and which transport were actually present.

## 7. Wi-Fi Aware

**Prerequisites.** Two Aware-capable devices and an implemented Aware carrier.
On the rig phone `adb shell pm has-feature android.software.wifi.aware`
answered `false` on 2026-11-21, and no Aware carrier is built. The CLI help
surface has no Aware command; `relay` is a blind pairer and `bt-sim` is an
in-process pipe, neither of which is an Aware observation.

**Observation to capture.** If capable hardware and a carrier appear, record
the `pm has-feature` result on both devices, the carrier selection line, the
publish/subscribe and discovery events, a file landing hash-exact with its
on-device digest, and any refusal reason. Without those prerequisites there is
no observation to capture for this flow.

**Evidence label attainable.** **Not verified (hardware absent)**, as recorded
in `docs/TESTING.md` §6. No CODE, PURE or SIM-HARN claim is added here, since
there is no Aware pipe to exercise.

**If the prerequisite is unavailable.** Keep the row as not verified and record
the `false` feature line with device model and date. Do not map a relay or BLE
result onto Aware, and do not add a carrier, route, header or status to make the
row pass.

## 8. MIUI autostart and battery behaviour

**Prerequisites.** The MIUI device (Redmi `M2101K7BNY`), MIUI's autostart
setting for `app.flit.share`, battery-saver state, and the Android 15 `dataSync`
foreground-time budget of roughly six hours. Explicit Stop must be off. A box
peer runs `flit-cli send <phone-ip> <fixture>` as the reachability probe, and
logcat is running.

```bash
adb shell am start -n app.flit.share/.MainActivity
adb shell am force-stop app.flit.share
adb shell pidof app.flit.share
```

**Observation to capture.** Record the MIUI autostart and battery settings
values, the PID before and after force-stop, and whether the process returns
without a user opening the app after a timed wait. On the next open capture
`egress pinned…` and `serving https://0.0.0.0:53317…`, then check that a peer
send lands hash-exact. At the `dataSync` cap record the notification state:
`onTimeout()` drops the notification while a Flit screen is open, and there is
no background guarantee after the last surface closes. Record absences with a
timestamp. Keep the persisted intent (sharing comes back on the next open)
separate from an autonomous MIUI restart; the former is a documented behaviour,
the latter is a ROM decision.

**Evidence label attainable.** On-device for the restart-on-next-open and
serving lines already recorded in `docs/open-file-and-sharing-restore.md`
(§ "MIUI / Android limitations" and lines 247-259) and in `docs/TESTING.md` §6.
The autonomous-background guarantee remains a ROM/OS limit and is not verified.
CODE and PURE for the persisted sharing-desired row and restore policy
(`SharingRestoreTest`, `FlitRosterTest`).

**If the prerequisite is unavailable.** Without a MIUI device, keep the ROM/OS
limit as recorded and do not generalise it to other OEMs. A force-stop on a
non-MIUI phone observes that phone's launcher policy only.

## Emulator scope on this box

The box has no `/dev/kvm`, no installed system images and no `emulator`
binary on PATH. A software-emulated AVD therefore cannot be started before a
system image is installed, and without acceleration its boot and UI steps are
slow enough that no timing, throughput or power claim should be attached to it.

What a cheap emulator could observe, once an image is installed: the app
process starting under the Android framework, permission sheets on a stock
image, `MainActivity` becoming focused, `FlitCircleStore` under prefs `flit`
surviving a force-stop and cold open, the `egress pinned…` and
`serving https://0.0.0.0:53317…` log lines, and Circle join, post, poll, vote,
file, receipt and courier bag steps against a box peer over emulator
networking. Several instances with distinct identities could exercise a roster
larger than three, and a virtual camera could feed a test image into the
parcel Scan path. Those observations would close CODE/PURE/SIM-HARN gaps in
framework wiring and would expose crashes, but they are not DEVICE under the
rule in the plan, which requires a run on physical hardware.

What it could not observe: a real RFCOMM connection to a second phone,
successful BLE advertising and scan against physical radios, Wi-Fi Aware on a
feature-poor image, MIUI-specific autostart, battery saver or `dataSync`
behaviour on a stock ROM, a physical camera capture from a second screen, and
device-specific radio, storage or power figures. It also cannot supply the
physical phone's certificate and trust material, so it cannot replace the
phone-side observations in steps 1, 2 and 4.

## Handoff to the evidence note

Write results to `docs/completions/<actual-date>-full-feature-walk.md` with the
fields and flow table from the template in `docs/roadmap/device-walk-2026-12-31.md`.
Each row takes the weakest level that supports its claim. An absent expected
line is an observation with a timestamp; an unrun step is **not verified**.
Nothing in this plan becomes evidence by the passage of time.
