# Deferred full feature device walk — 2026-12-31 (plan)

This file is a plan. No walk has been performed under it, and no observation,
screenshot, log excerpt or landed hash is recorded here. DEVICE evidence is
claimed only when logs and observations exist from a run on physical hardware;
the label is applied in a separate evidence note written after that run, not in
this document. Step descriptions below name what can be observed and what level
a step can reach if the observation is actually made. Where an observation is
absent, the step remains at the level already recorded in `docs/TESTING.md`
(CODE, PURE or SIM-HARN) or is written as **not verified**.

The 2026-12-17 Device layer in `docs/roadmap/01-schedule.md` was deferred
because no physical walk was available. The date in this file name is the date
of the plan, not a claim that the walk ran. When hardware is available, run the
steps on the actual date and write the results to
`docs/completions/<actual-date>-full-feature-walk.md` using the template at the
end of this file. Nothing here is changed into an evidence note by the passage
of time.

The JVM rig is green at the inventory recorded in `docs/TESTING.md` §2 and §3
before any hardware work. That result is PURE and SIM-HARN evidence for the
seams and in-process paths; it is not a device observation and is not restated
as one.

## Rig and preconditions

| Node | Surface | Identity and state |
| --- | --- | --- |
| A | physical Android phone, Flit app | `flit-identity.p12` in app `filesDir`; `FlitCircleStore` under prefs `flit` |
| B | box, one-shot `flit-cli` | `FLIT_HARNESS_HOME=/tmp/opencode/walk-b`, holding `identity.p12`, `circle.state`, `courier.state`, `courier-bag/` |
| C | box, one-shot `flit-cli` | `FLIT_HARNESS_HOME=/tmp/opencode/walk-c`, a separate home and identity |

B and C must never share a harness home, and neither may share one with a
`serve` process. A shared home is the failure recorded in `docs/TESTING.md` §8
that made an earlier peer-side confirmation ambiguous; discard any observation
made under that condition and rerun the step.

Before the first flow, record the installed artifact and the environment:

```bash
sha256sum <installed-apk>                              # build hash of the APK
adb install -r <installed-apk>
adb shell dumpsys package app.flit.share | grep versionName
adb getprop ro.product.model
adb getprop ro.build.version.release
adb getprop ro.build.version.sdk
adb shell pidof app.flit.share
sha256sum $(readlink -f "$(command -v flit-cli)")      # CLI launcher under test
flit-cli                                               # help/version line
date -Ins                                              # host clock and time zone
adb shell date                                         # device clock
```

Start the log capture before the first flow and keep it running through the
last one:

```bash
adb logcat -v threadtime -s FlitSend:I FlitBleRadio:W FlitOpen:W > walk-logcat.txt
```

Seed fixture files on the box and record their byte counts and digests before
they are sent:

```bash
printf 'full-walk-seed-1\n' > /tmp/opencode/walk-seed.txt
head -c 8192 /dev/urandom > /tmp/opencode/walk-8k.bin   # record its sha256sum
sha256sum /tmp/opencode/walk-seed.txt /tmp/opencode/walk-8k.bin
```

Use fixed fixtures with recorded hashes; do not quote a hash that was not read
from `sha256sum` in this run.

A step reaches DEVICE only when its result is captured in this run's phone
logcat/UI observations or in CLI output and state files tied to the same
phone-centred walk. A CLI-only exercise with no phone observation stays
SIM-HARN. For every refusal branch, record the exact reason line and exit code;
do not turn a refusal into a success by inference.

## Flow 1 — Circle founding and join with signed proof

Found the Circle from the app. Open the Circle surface from Home, start a
Circle, and record the circle id, invite token, host fingerprint and the exact
v3 QR URI shown on screen. Confirm the URI has no `key` parameter.

Surfaces and commands:

```bash
# phone: Home → Circle → start; record the QR and its decoded text
adb exec-out screencap -p > evidence/circle-invite.png
flit-cli parse-qr evidence/circle-invite.png

# box, each home in its own shell
export FLIT_HARNESS_HOME=/tmp/opencode/walk-b
flit-cli circle join --host <phone-ip>:53317 --circle <ID> --invite <TOKEN> \
  --host-fp <host-fp printed by the app>
export FLIT_HARNESS_HOME=/tmp/opencode/walk-c
flit-cli circle join --host <phone-ip>:53317 --circle <ID> --invite <TOKEN> \
  --host-fp <host-fp printed by the app>
```

The CLI mints the signed JOIN proof from its own identity; the phone host
recomputes it from host fingerprint, circle id, invite, joining fingerprint and
the five-minute bucket before admitting the member. Capture the phone lines
`circle host: join <fp12> — N event(s)` and `circle sync: +K event(s) from
<fp12>`, the CLI line `joined <ID>: N member(s), M event(s), key=…`, the three
fingerprint prefixes, and the roster count on each node after both joins.
Record the host fingerprint used for `--host-fp` and confirm that a later CLI
answer signed by another fingerprint is not accepted; if no such answer can be
emitted on this rig, that branch stays SIM-HARN from `CircleHarnessTest`.

Evidence level: DEVICE for the founding QR, both successful joins, the host
lines and the three-member roster when the phone log and CLI output exist.
The negative proof branches (`join proof required`, `invalid join proof`,
`join proof replay`) are CODE, PURE and SIM-HARN only unless a client on this
rig can emit the malformed or stale proof; record them as not verified on
device otherwise.

## Flow 2 — post, poll and vote

Commands and surfaces:

```bash
export FLIT_HARNESS_HOME=/tmp/opencode/walk-b
flit-cli circle post --text 'walk-post-b' --host <phone-ip>:53317
flit-cli circle poll --text 'Ship?' --option yes,no --host <phone-ip>:53317
export FLIT_HARNESS_HOME=/tmp/opencode/walk-c
flit-cli circle vote --option 0 --host <phone-ip>:53317
flit-cli circle sync --host <phone-ip>:53317
flit-cli circle vote --option 1 --host <phone-ip>:53317
flit-cli circle sync --host <phone-ip>:53317
```

The parser records every `--option` value, so repeated flags and one
comma-separated value both produce the full option list; see `ParsedArgs.opts`
and `docs/OPERATIONS.md` §6.8.

On the phone, confirm the post renders in the Circle feed with author and
timestamp, and tap the rendered `no` option. Then post from the phone composer
and record the exact `circle fan-out:` line, any `no route to` or backed-off
peer lines, and whether either box node's `circle.state` later contains the
event. Confirm the poll tally keeps both C votes and the phone vote and counts
the latest answer per member.

Capture: `circle sync: +N event(s) from <fp12>`, `circle fan-out: N new
event(s), M peer(s)[, K backed off]`, `circle sync: full-log repair to M
peer(s)`, the CLI feed lines from both `circle sync` runs, and the stored poll
and vote bodies from the CLI state files. Note whether either box node ran a
listener while the phone post was made.

Evidence level: DEVICE for inbound CLI posts, the poll, the changed votes and
the phone-side feed when logcat and UI observations exist. Outbound
phone→host fan-out reaches DEVICE only if a box node's state file or output
shows the phone-authored event in this run. If it does not, repeat the wording
from `docs/TESTING.md` §6: test-evidenced only, with the full-log repair and
`refused no answer` lines recorded as the limitation.

## Flow 3 — keyed file drop with hash-exact landing

Send from B to the joined circle with the phone and C present. C needs a
listener under its own harness home to receive the bytes; a one-shot CLI
identity has no listener and no mailbox, so it would see the announcement only
on a later `circle sync`. No other identity uses C's home while the listener
runs:

```bash
export FLIT_HARNESS_HOME=/tmp/opencode/walk-c
flit-cli serve --dir /tmp/opencode/walk-c-downs    # keep running through flow 4

export FLIT_HARNESS_HOME=/tmp/opencode/walk-b
flit-cli circle file --file /tmp/opencode/walk-seed.txt --host <phone-ip>:53317
sha256sum /tmp/opencode/walk-seed.txt    # plaintext digest announced in the event
```

On the phone, confirm the file card reads `Landed here` with an enabled `Open
file`, and read the landed digest:

```bash
adb shell sha256sum /sdcard/Download/Flit/walk-seed.txt
adb exec-out screencap -p > evidence/keyed-card.png
```

Capture the CLI `file <sha12> → … event(s), … member(s), gen=…` line and its
local status line with `status is local-only, never gossiped`; the phone lines
`receiving walk-seed.txt` and `→ Downloads/Flit/walk-seed.txt`; the landed path
and digest; and the stored `file` body keys from B's `circle.state`. The keyed
body must show exactly `name`, `size`, `sha256`, `keyGen`, `cipherSha`, `iv`
and `wraps`, with one wrap per current member. Record the opened plaintext
digest against the announced `sha256`; record the sealed copy's digest against
`cipherSha` only if the sender's `*.flitdrop` file is available to hash in this
run, and leave it unobserved otherwise. With C's listener up, record its
`drop-open <name> <plainSha> (<bytes> bytes)` line and the landed hash under
`/tmp/opencode/walk-c-downs`.
If the listener is not up, record no landing on C and do not infer one from the
signed announcement.

Evidence level: DEVICE for the announcement, keyed body, hash-exact phone
landing and local-only status when the CLI output, phone log and digest read
exist. The unwrap, tag and plaintext-digest refusal branches stay PURE and
SIM-HARN unless a deliberately tampered drop is landed in this run and its
`circle drop: cannot open …` or `drop-refused … open failed` line is captured.

## Flow 4 — receipt confirmation

After the keyed landing, read the confirmation count from B's signed log and
from the phone card. Publish an explicit receipt from C for the same plaintext
digest:

```bash
export FLIT_HARNESS_HOME=/tmp/opencode/walk-c
flit-cli circle receipt --sha <plaintext-sha256> --host <phone-ip>:53317
flit-cli circle sync --host <phone-ip>:53317
export FLIT_HARNESS_HOME=/tmp/opencode/walk-b
flit-cli circle sync --host <phone-ip>:53317
```

The app mints a receipt when a keyed drop lands hash-exact on the phone; the
CLI publishes one only through the explicit command above and does not detect
landings by itself. Capture the CLI line `receipt <id8> → … event(s), …
member(s), gen=…`, the signed body keys `{dropId, sha256, keyId}` in the stored
log, the confirmed count printed by `circle sync` on B and C, and the confirmed
count rendered on the phone's file card. Record `keyId` as
`<file-event-id>:<keyGen>` from the keyed event; no value is invented.

Evidence level: DEVICE for the phone card count and the CLI receipt and count
when both are captured in this run. A confirmed count is evidence of signed
authors in the local log, not a claim about delivery to every member. A
malformed receipt, a replayed id and a non-member author remain PURE and
SIM-HARN unless produced on this rig with their refusal lines captured. Stop C's
`serve` after this flow so C is absent for the courier handoff.

## Flow 5 — pad put, del and show

Use the phone pad card inside the Circle surface and the CLI pad commands from
two box homes:

```bash
export FLIT_HARNESS_HOME=/tmp/opencode/walk-b
flit-cli circle pad put --key alpha --text 'first line' --host <phone-ip>:53317
flit-cli circle pad show
export FLIT_HARNESS_HOME=/tmp/opencode/walk-c
flit-cli circle pad put --key beta --text 'second line' --host <phone-ip>:53317
flit-cli circle pad show
export FLIT_HARNESS_HOME=/tmp/opencode/walk-b
flit-cli circle pad del --key alpha --host <phone-ip>:53317
flit-cli circle pad show
```

On the phone, enter a line through the pad card's key and text fields, then
delete a line with its row control. Confirm the card lists live keys in
lexicographic order and that a tombstoned key disappears. Capture the exact
`circle pad show` output from each node after each write, the phone's ordinary
`circle sync: +N event(s) from <fp12>` and `circle host: sync <fp12> — N
event(s)` lines, and the stored `pad` event bodies `{op,key,text}` for put and
`{op,key}` for del. There is no separate pad log line; do not claim one. A
blank or over-cap key or text must be refused without an event, which stays
CODE and PURE unless the refusal is observed here.

Evidence level: DEVICE for the card view and the CLI put/del/show sequence when
phone UI and CLI output exist. Convergence and merge order stay PURE.

## Flow 6 — courier handoff

Make C absent, seal a parcel on B for C, then move the bag as a directory copy:

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

Capture the `courier.state` rows on both nodes before and after the copy, the
`<id>.parcel` file names, the opened JSON in `courier-received/`, and the exit
codes. Repeat the same parcel into C's bag to confirm one fold and one
`delivered` row; keep the replay result separate from the first handoff. If a
pad or file card is present on the phone and the app has published a handoff
entry for that SHA-256, record the carry line shown on the card; otherwise
record that no carry line was present. The carry line is process-local and is
not copied from the bag.

App log lines to look for, with their exact wording: `courier: holding for
<fp>`, `courier: handed off N parcel(s) to <fp>`, `courier: handoff failed to
<fp>`, `courier parcel unreadable — rejected`, `courier: bag row unreadable,
starting empty`. CLI evidence comes from `courier status` rows and the bag
contents, not from a count on the phone card.

Evidence level: DEVICE for the bag rows and handoff when CLI output is captured
in this phone-centred walk and the phone log or card is recorded alongside it.
A phone carry line requires an observed phone card entry; without one it is
absent, not implied. Tampered, expired and multi-carrier chains stay PURE and
SIM-HARN unless exercised here with their refusal rows captured.

## Flow 7 — officer-only rekey and departure rotation

Use the phone as host and B as a promoted admin. Promote B from the phone's
member sheet, then drive the kick and the later leave from the CLI:

```bash
export FLIT_HARNESS_HOME=/tmp/opencode/walk-b
flit-cli circle kick --target <C-fp> --host <phone-ip>:53317
flit-cli circle sync --host <phone-ip>:53317
flit-cli circle leave --host <phone-ip>:53317
export FLIT_HARNESS_HOME=/tmp/opencode/walk-c
flit-cli circle sync --host <phone-ip>:53317
```

Capture the phone's `circle host: kick <fp12> — N event(s)` and `circle host:
sync <fp12> — N event(s)` lines, the roster drop from three to two members, the
advance of `rekeyGen`, and the signed `kick`, `leave` and `rekey` bodies in B's
log. Confirm that the promoted admin's kick executes immediately and that the
departure forces a fresh host rotation. Confirm C's later `circle sync` is
refused with `!! sync refused: host refused: not-a-member`, while C's earlier
local events remain readable. Confirm that no second `OWNER` is appointed and
that an ordinary member cannot appoint an admin or issue a direct kick; use the
member sheet state and the CLI refusal output where the command is available.

Evidence level: DEVICE for the promotion, kick, forced rotation, roster change
and departed-member refusal when phone log, UI and CLI output exist. The
officer-only fold rule, the same-batch hand-off veto and the order-independent
pre-batch authority check stay PURE and SIM-HARN unless a crafted REKEY from a
non-officer or a departing officer can be emitted on this rig and its refusal
line captured.

## Flow 8 — trusted-senders gate

Keep Wi-Fi on and the phone's sharing surface up. Pin B on the phone as a Known
device through the QR-paired Known devices flow, and leave C unpinned. Record
`scan: pinned <alias> (code verified)` when B is trusted. Then send one seeded
file from each box identity to the phone:

```bash
export FLIT_HARNESS_HOME=/tmp/opencode/walk-b
flit-cli send <phone-ip>:53317 /tmp/opencode/walk-seed.txt --pin <phone-fp>
export FLIT_HARNESS_HOME=/tmp/opencode/walk-c
flit-cli send <phone-ip>:53317 /tmp/opencode/walk-8k.bin
```

Toggle Options → "Only trusted devices can send me files" on. Capture the
phone line `offer from <alias> (<ip>): N file(s)` for the trusted sender and
`declined offer from <alias> (<ip>) — device not trusted` for the untrusted
sender. Check the receive directory and ledger: the trusted file lands
hash-exact and the untrusted file has no landing row. Confirm the gate is a
prepare-time decision by fingerprint and not by IP; no trusted-senders gate
exists on the CLI receiver.

Evidence level: DEVICE for the toggle, both prepare answers and the difference
in landed files when logcat and the receive directory are recorded. The
QR-only variant of the gate (`declined QR-unknown device …`) is a separate
setting and remains CODE and PURE unless it is toggled and observed here.

## Flow 9 — BLE carrier reachability and duty stance

Record the starting radio state, then remove the access point and enable the
carrier from Options → "Transfer over Bluetooth":

```bash
adb shell settings get global wifi_on
adb shell settings get global bluetooth_on
adb shell settings put global wifi_on 0
adb shell settings get global wifi_on
```

Accept the system Nearby-devices sheet and the radio enable dialog if they are
raised; they are asked once per device. Capture the arbiter lines `no Wi‑Fi
network; direct discovery on`, `bluetooth link waiting — permission or radio
off` if permission or power is missing, any `hunt: <ip>:<port> answered as
<alias>` or `hunt: ... answered as <alias>` line, the `FlitBleRadio` warnings
`advertising set failed`, `scan start failed` and `scan failed: <code>` if they
occur, and the carrier line `bluetooth link: listening` where the opt-in is
reported. Restore Wi-Fi afterwards and capture `access point back; direct
discovery dark`. Restore the original radio settings at the end.

Duty stance is read at engine start. `FlitBle.auditDutySchedule` logs one
`ble duty budget: <reason> — <explanation>` line per finding and is silent when
the shipped schedule passes. The shipped inputs are a 55 s cycle, 10 s on and
45 s off, with a 2,000 bps ceiling; the check is arithmetic, not an airtime
measurement, and it never steers the carrier. Record whether any such line
appeared in `walk-logcat.txt`, and state that radio duty was not measured.
Run the transport-agnostic pipe check separately:

```bash
flit-cli bt-sim --bytes 512
```

Evidence level: DEVICE for the no-AP arbiter lines, permission and power
dialogs, and the presence or absence of a duty-audit line in this run's log.
The schedule arithmetic is CODE and PURE. Peer reachability over BLE and a real
phone-to-phone RFCOMM transfer are **not verified** on a rig with one phone and
a box without a Bluetooth adapter; `bt-sim` is SIM-HARN and is never reported
as a radio walk.

## Flow 10 — QR parcel encode and decode

Do this flow with radios off and no CLI listener, and restore settings after it:

```bash
adb shell settings get global wifi_on                  # 0 expected
adb shell settings get global bluetooth_on             # 0 expected
pgrep -af 'flit-cli (serve|relay)'                     # no output expected
```

Phone → box. Stage the box certificate and a fixture as out-of-band public
material, then use the phone's parcel screen:

```bash
adb push box-cert.b64 /sdcard/Download/flit-gap/box-cert.b64
adb push /tmp/opencode/walk-seed.txt /sdcard/Download/flit-gap/walk-seed.txt
adb shell sha256sum /sdcard/Download/flit-gap/walk-seed.txt
```

On the phone open Home → "QR parcel" → Show. Enter the box recipient
fingerprint, pick the certificate and the file, tap "Make frames", and record
the frame count. Step with Next and capture three complete loops:

```bash
adb exec-out screencap -p > evidence/frames/phone-loop1-0001.png
# one capture per frame index per loop; Next between captures
flit-cli parcel decode --dir evidence/frames --out /tmp/opencode/parsed-seed.txt
sha256sum /tmp/opencode/parsed-seed.txt
```

Record the decode fields `out`, `bytes`, `sha256`, `signerFp` and `recipientFp`;
`recipientFp` must equal the box fingerprint. The fixture and certificate are
not frame inputs.

Box → phone. Stage the phone certificate on the box, encode, and show the PNGs
full-screen in front of the camera:

```bash
flit-cli parcel encode /tmp/opencode/walk-8k.bin --dir /tmp/opencode/parcel-frames \
  --to <phone-fp> --cert phone-cert.b64
```

Record `EncodeResult.dir`, `frames`, `sealedChars` and `sha256`. On the phone
switch to the parcel screen's Scan tab. The camera preview feeds
`ParcelScanState`; if camera permission is denied, paste the frame strings
instead. On completion record the shown signer, SHA-256 and byte count, then:

```bash
adb shell sha256sum /sdcard/Download/Flit/parcel-<timestamp>.bin
```

Capture the app line `parcel → Downloads/Flit/<name> (<size>)`; on failure
`parcel: could not create Downloads/Flit/<name>` or `parcel: could not write
<name>`. Compare the landed digest with the encode digest and confirm no
`/partial`, `queued` or `.part` line appears for this path. Three loops must
produce one landed file. If no display surface can be placed before the camera,
write direction two as **not verified** and keep the phone → box direction.

Evidence level: DEVICE for either direction when the frame captures, decode or
landing output, hash comparison and timestamps exist. A one-box CLI encode →
decode loopback is SIM-HARN and is kept separate. No radio carrier is part of
either direction.

## Evidence note template

Write the completed note to `docs/completions/<actual-date>-full-feature-walk.md`.
The fields below are mandatory; leave a value as **not observed** rather than
filling it from an earlier document.

```markdown
# Full feature walk — <actual date>

## Device and build
- Device model: <adb getprop ro.product.model>
- OS: <adb getprop ro.build.version.release>, API <adb getprop ro.build.version.sdk>
- Installed app version: <dumpsys versionName>
- Build hash: <sha256sum of installed APK recorded before adb install>
- CLI launcher hash: <sha256sum of flit-cli used>
- Host/device clock and time zone: <date -Ins / adb shell date>
- Node table: A phone fingerprint; B and C harness homes and fingerprints
- Circle id, invite token, host fingerprint, QR URI
- Rig state before each radio-sensitive flow: wifi_on, bluetooth_on, pgrep result

## Flow entries

| # | Flow | Timestamp | Command / app surface | Observed result | Source of observation | Evidence level | Limitations |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | join with signed proof | <time> | <exact command and screen> | <what was seen, including refusal reasons> | logcat / UI dump / CLI stdout / state file | CODE / PURE / SIM-HARN / DEVICE | <what was not observed> |
| 2 | post, poll, vote | <time> | <exact command and screen> | <what was seen> | <source> | <level> | <limit> |
| 3 | keyed file drop | <time> | <exact command and screen> | <size, plaintext sha256, cipherSha, landed path> | <source> | <level> | <limit> |
| 4 | receipt confirmation | <time> | <exact command and screen> | <dropId, sha256, keyId, confirmed count> | <source> | <level> | <limit> |
| 5 | pad put/del/show | <time> | <exact command and screen> | <keys, row order, tombstone> | <source> | <level> | <limit> |
| 6 | courier handoff | <time> | <exact command and screen> | <rows before/after, file ids, replay result> | <source> | <level> | <limit> |
| 7 | rekey and departure | <time> | <exact command and screen> | <roster, rekeyGen, refusal reason> | <source> | <level> | <limit> |
| 8 | trusted-senders gate | <time> | <exact command and screen> | <offer lines and landed-file difference> | <source> | <level> | <limit> |
| 9 | BLE reachability and duty | <time> | <exact command and screen> | <arbiter lines, dialogs, duty-audit line or its absence> | <source> | <level> | <limit> |
| 10 | QR parcel encode/decode | <time> | <exact command and screen> | <frame count, signer, sha256, byte count, landed path> | <source> | <level> | <limit> |

## Log and file excerpts

<timestamped logcat lines, CLI stdout, `courier.state` / `circle.state` rows,
frame counts, hashes and UI text, attached per flow. Every excerpt names its
source file and the flow number.>

## Limitations

- <branches that stayed CODE, PURE or SIM-HARN>
- <directions that were not verified, with the reason>
- <radio, camera, second-phone and mesh constraints observed on this rig>
```

Each row's evidence level is the weakest level that supports its claim. A
DEVICE entry is valid only where the corresponding log or observation excerpt
is attached. Absence of an expected line is recorded as an observation with its
timestamp; absence of a whole step is recorded as **not verified**.

## What remains outside this plan

No second physical phone is assumed, so phone-to-phone RFCOMM and a live
multi-phone mesh stay **not verified**. Wi-Fi Aware is absent on the rig phone
as recorded in `docs/TESTING.md` §6. A relay-only carrier, a multi-hop courier
chain, a desktop GUI and `.flitpack` bundles are not added by this walk. No new
route, header, DTO field, QR parameter or event verb is introduced; the walk
only observes the surfaces already specified in the linked design and
completion documents.
