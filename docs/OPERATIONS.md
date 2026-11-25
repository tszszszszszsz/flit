# Operations — CLI, rig setup, permission gate, logs, walkthroughs, cleanup

> Part of the Flit design set. [Transport ladder and index](transport-bluetooth.md) ·
> [README](../README.md).

The rig's last green standard is **302 tests, 0 failures** (82 protocol + 210 app
+ 10 harness). Earlier design docs quote smaller counts (76, 115) and the CI
workflow comment quotes 36/67/4; those are historical snapshots of the same
suite at earlier commits, not the current inventory. The current inventory is in
[TESTING.md](TESTING.md).
The last commit is `72225f8` (Open-file crash + sharing restore), preceded by
`2034fa6` (Circle file delivery + quality pass).

## 1. Rig at a glance

Three modules, three roles ([README](../README.md#repo-layout)):

| Module | Role | Runs on |
| --- | --- | --- |
| `protocol/` | JVM-pure wire engine (JSON DTOs, UDP discovery, TLS+mTLS, HTTP server/client, relay core) | JVM only; no Android dependency |
| `app/` | Android shell (Jetpack Compose), package `app.flit.share`, version `0.2.0` (code 2), minSdk 26, compile/targetSdk 36 | Phone |
| `harness/` | Desktop companion shipped as `flit-cli`; doubles as the interop and device-walk rig | Linux box with JDK 21 |

Toolchain lives outside the repo in `~/.local/`; `env.sh` pins JDK 21
(`JAVA_HOME="$HOME/.local/jdks/temurin-21"`), and CI instead uses
`actions/setup-java` Temurin 21 (rig.yml:20-24). The reference client used for
interop is the official `localsend-cli` v1.18.2 at `~/.local/opt/localsend-cli`
(interop/).

The pre-commit rig command (README "Dev quickstart", and the same task set in
CI, rig.yml:35-49):

```bash
source env.sh && ./gradlew :protocol:test :app:testDebugUnitTest :app:assembleDebug \
    :app:assembleRelease :harness:installDist
# CI additionally runs :harness:test and :harness:distZip, with --no-daemon --stacktrace
```

A plain `flit-cli` binary can be reached two ways: from the built distribution
(`harness/build/install/flit-cli/bin/flit-cli`) or installed to `~/.local/bin`
via `install-cli.sh`. The installer also accepts a CI zip:
`./install-cli.sh --zip flit-cli-0.2.0.zip`, and an alternate root with
`--prefix DIR`. It gates both build and launch on Java 21 with one readable line
(`flit-cli needs JDK 21 … set JAVA_HOME or put java 21 first on PATH`) rather
than a JVM class-file stack trace (install-cli.sh:1-70).

## 2. `flit-cli` reference

The canonical command list is the no-argument output of `flit-cli` itself,
defined at `Harness.kt:136-156`. The README's shorter list is a user-facing
summary; where the two differ, the help text and the arg parser decide
and the differences are recorded at the end of this section.

```
flit-cli — the LocalSend v2.2 command line (same stack as the Flit app)

serve [--alias N] [--port P] [--dir D] [--udp-port N] [--http] [--hidden] [--announce-to ip:port,...]
      [--relay host:port --token T] -- serve only inside a blind pairer (milestone E)
discover [--wait S] [--udp-port N] [--http] [--announce-to ip:port,...]
send <ip[:port] | alias> <file...> [--http] [--pin PIN] [--parallel N] [--multi]
info <ip[:port]> [--http]
bt-sim [file...] [--bytes MB] -- both ends in one process over the pipe carrier
relay [--port P] [--token T] [--park-timeout S] -- blind pairer for unreachable peers (milestone E)
panic <ip[:port]> [--text NOTE] -- send a signed informational panic alert
parse-qr <image...>   pairing codes out of screenshots (png/jpg)
circle <create|join|post|poll|vote|role|kick|leave|sync|file> [--circle ID] [--invite TOKEN]
       [--text TEXT] [--file PATH] [--target FP] [--role ADMIN|MEMBER] [--option N]
       [--host ip[:port]] -- accountless group coordination over the production Circle routes
       post --file publishes a signed name/size/sha256 reference only;
       file --file publishes it AND sends the real bytes to every current member via sendFiles
```

Value-taking flags, from `ParsedArgs.VALUE_FLAGS` (Harness.kt:72-78):
`--alias --port --dir --dest --udp-port --wait --pin --parallel --announce-to
--bytes --sender --token --park-timeout --relay --text --circle --invite --file
--target --option --host --role`. Everything else is a presence flag. Defaults
come from `Protocol.DEFAULT_PORT = 53317` and `MULTICAST_PORT = 53317`
(protocol/…/Protocol.kt:17,20); the relay default is `DEFAULT_PORT + 1 = 53318`
(Harness.kt:675).

### 2.1 Commands, flags and observable output

**`serve`** (Harness.kt:499-580) — run a production v2.2 receiver.

- `--alias N`: advertised name; default `Flit (<USER>)` or `Flit (dev)`.
- `--port P`: TCP/TLS listener; default 53317.
- `--dir D` / `--dest D`: receive directory (both names mean the same thing;
  `--dest` is used by scripts); default `flit-downloads` relative to the working
  directory.
- `--udp-port N`: discovery multicast port; default 53317. Dial-only operation
  uses a port nobody listens on, e.g. `--udp-port 9999`.
- `--http`: plain HTTP instead of TLS; `/info` reports `http`.
- `--hidden`: never announce, never register back; reachable only by address,
  by a peer's rescue sweep, or when it sends first.
- `--announce-to ip:port,…`: unicast announce copies for networks that strip
  multicast.
- `--circle ID --invite TOKEN`: seed the host-side Circle state; the startup
  line prints a ready-to-paste `circle join` command.
- `--relay host:port --token T`: **relay-only mode** — no TCP listener and no
  announces, a long-lived `RelaySubscriber` subscription that reconnects with a
  2 s backoff. TLS runs *inside* the pipe. A malformed address exits 2 with
  `!! bad --relay … (expected host:port)`.

Startup prints, in order: `HH:mm:ss serving https://0.0.0.0:<port>
dir=<abs path>`, `fingerprint <fp>`, `circle <id> invite=<token> — join with:
flit-cli circle join …`. In hidden mode an extra line explains stand-down.
Ctrl+C runs the shutdown hook (`discovery.stop(); server.close()`) and prints
`bye` (Harness.kt:573-576). In relay-only mode there is no shutdown hook around
the subscribe loop; SIGINT/SIGTERM ends the process.

**`discover`** (Harness.kt:582-621) — announce for `--wait S` seconds (default
8) and print every fingerprint seen. Exits 2 with `no peers found` if the roster
stays empty. Output per peer: `peer <alias> ip=<ip>:<port> tls=<bool>
type=<deviceType> v=<version> fp=<12>…`. The listener binds an ephemeral port
and writes to `flit-tmp-discover/`.

**`send`** (Harness.kt:623-662, 685-744) — resolve the target either as a literal
IPv4 `ip[:port]` (regex `^\d{1,3}(\.\d{1,3}){3}(:\d+)?$`) or as an alias found
by a short discovery rendezvous (`--wait S`, default 5, plus the discovery
flags). Directories passed as file arguments are walked recursively and their
relative paths become wire names (`expandSpecs`). Flags: `--pin PIN` (fingerprint
pin), `--parallel N` (default 4), `--multi` (multi-stream), `--http`. Output:
`sent N/M to ip:port`, one `ok <name>` per landed file, `FAIL <name>: <reason>`
per failure; **exit 3 if any file failed**, exit 1 if no files were found in the
given paths.

**`info`** (Harness.kt:746-759) — print the peer's `PeerInfo` JSON (announce
field removed) or exit 2 with `no answer from ip:port`.

**`bt-sim`** (`BtSim.kt:44-136`) — both ends of an exchange in one process over
`SimLinkProvider`/`PipePair`, the same pipes the protocol parity suite uses. The
receiver is the production `ServerCore` through `LinkServer` (the RFCOMM door),
wrapped in `TlsLink` when TLS is on. It exercises `/info`, `/register`,
`/prepare-upload`, `/upload` with sha receipts and session close over a non-TCP
carrier. With no files, one synthetic random file of `--bytes MB` (default 4) is
generated; `--dir` sets the landing dir (default `flit-sim-received`), `--sender`
the alias, `--pin`, `--parallel`, `--multi` mirror `send`. `--no-tls` is
implemented (`BtSim.kt:49`, `/info` then reports `http`) but omitted
from the no-arg help string — see the discrepancy note. Exit codes: 1 when
neither files nor `--bytes` are given, 2 if `/info` gets no answer, 3 if any file
fails. This is **harness evidence**, not a radio walk.

**`relay`** (Harness.kt:670-683; RelayServer.kt) — blind pairer.

- `--port P`: default 53318.
- `--token T`: optional shared token gate, compared in constant time by
  `RelayCore`; a wrong token is refused on the wire.
- `--park-timeout S`: seconds a subscription waits for a partner; 0 (default)
  means wait forever.

Startup: `relaying on 0.0.0.0:<port> token=set|none` and `pairing requests and
subscriptions by fingerprint; the pipe inside is opaque`. Pairing logs only
`pair c3 + c4` and `pair c3 + c4 closed` — no fingerprint, no byte count, never
content (milestone-e.md:14-28). A connection that fails its bounded hello gets
`ERR bad-hello`; a timed-out subscription gets `ERR timeout`; a missing partner
gets `ERR partner-gone`. The accept loop, hello parse (`RelayHello.MAX_LINE`
bound), FIFO pairing and ghost-ticket `withdraw` are the same production core
the app dials. Ctrl+C closes the server and all parked sockets and prints `bye`.
There is no relay-side storage, queue or discovery.

**`panic`** (Harness.kt:218-230) — `flit-cli panic <ip[:port]> [--text NOTE]`;
default note `check in when you can`. It signs an informational alert with the
CLI identity and prints `panic to host:port sent|refused`. Informational only:
no remote action on the recipient ([qr-only-and-panic.md](qr-only-and-panic.md)).

**`parse-qr`** (Harness.kt:179-216) — decode pairing codes from png/jpg captures.
It samples at scales 1, 2, 3 and 4 because ZXing's binarizer has module-size
sweet spots; output is `<path>: <decoded text> (scale 1/N)` or `(no code)`.

**`circle`** (Harness.kt:1034-1264; `circleUsage()` at 158-177) — accountless
group coordination over the production `/circle/join` and `/circle/sync` routes.
CLI state lives at `<identity home>/circle.state` (7 lines: id, invite, key,
host, members, events JSON, rekeyGen). Subcommands:

| Subcommand | Required flags | Effect |
| --- | --- | --- |
| `create` | optional `--circle`, `--invite`, `--host` | mints id/invite/key, prints the matching `serve --circle … --invite …` line |
| `join` | `--host ip[:port]`, `--circle ID`, `--invite TOKEN` | authenticated TLS join; stores key, members, log; refuses with the host's reason |
| `post` | `--text NOTE` **or** `--file PATH` | signed message, or a file-drop **announcement only** (`{name,size,sha256}`) |
| `file` | `--file PATH` | announcement **and** real bytes to every current member via `sendFiles`; local-only status |
| `poll` | `--text QUESTION`, `--option A,B,…` | signed poll event |
| `vote` | `--option N` | change your answer on the latest poll |
| `role` | `--target FP`, `--role ADMIN\|MEMBER` | OWNER-only appointment; nobody appoints a second OWNER |
| `kick` | `--target FP` | OWNER/ADMIN kick executes now; a MEMBER opens a vote-kick |
| `leave` | — | publishes leave then discards the local key and deletes `circle.state` |
| `sync` | a joined `--host` | pulls the full bounded log, prints feed and poll tally |

`file --file` is deliberately different from `post --file`: a plain post never
moves bytes. The announcement body is the closed `{name,size,sha256}` shape with
no spool path and no queue token (Harness.kt:875-880). Delivery statuses are
`pending → sent|refused|failed`, are local-only and never gossiped; a thrown
carrier leaves the recipient `pending` for retry (Harness.kt:882-978). Full
semantics and evidence live in [Circle](circle.md#file-delivery-as-built).

### 2.2 Identity, environment and exit codes

- Identity: `~/.config/flit/flit-harness/identity.p12`, generated on first use
  and stable across rebuilds and updates (Harness.kt:81-99). Override the whole
  home with `FLIT_HARNESS_HOME`. Keeping this file is what lets previously
  trusting peers keep trusting the box after an installer upgrade.
- `FLIT_MODEL` sets the advertised `deviceModel`; `FLIT_TRACE=1` prints a stack
  trace for the top-level error handler instead of just
  `!! <message> (set FLIT_TRACE=1 for a stack trace)`.
- Exit codes, consolidated: **1** = generic top-level exception / no files found
  / bt-sim needs input; **2** = bad `--relay` address, `discover` found nobody,
  `info` got no answer, bt-sim `/info` got no answer; **3** = at least one file
  failed to send. Normal `exitProcess(0)` is implicit.

### 2.3 Differences from the README summary

1. The README's "canonical list" line for `bt-sim` includes `--no-tls`; the
   `usage()` string at Harness.kt:145 omits it while `BtSim.kt:49` implements it.
   Treat it as a real, supported flag.
2. The README list shows a reduced flag set (no `--alias`, `--port`, `--udp-port`,
   `--http`, `--announce-to`, `--dest`, `--sender`, `--circle`/`--invite` on
   `serve`, no `--alias` on `discover`/`send`, no per-subcommand Circle flags),
   yet claims to be "every command and flag, from `flit-cli` with no arguments".
   The actual `usage()` output above is the complete list.
3. The README documents `relay --port 53318` as if it were a literal default in
   the help text; the code computes it as `Protocol.DEFAULT_PORT + 1` and the
   help string shows `--port P`. The effective default is still 53318.

## 3. Box setup

1. `source env.sh` (or export a Java 21 `JAVA_HOME` yourself). Confirm
   `java -version` reports 21 — `install-cli.sh` checks this for you.
2. Build and install: `./install-cli.sh` (equivalent to
   `./gradlew :harness:installDist :harness:distZip` followed by the guarded
   launcher install). Check with `flit-cli | head -2`; if `~/.local/bin` is not
   on PATH the installer says so.
3. Decide the receive directory. `serve --dir ~/Public` writes there; without
   `--dir`, the working directory's `flit-downloads/` is used. The same
   directory can be pointed at from multiple walks, which is why duplicate
   names arrive as `batch-b (1).bin` (bluetooth-carrier.md:240-256).
4. Keep the identity file. Reinstalling over `~/.local/lib/flit-cli` does not
   touch `~/.config/flit/flit-harness`, so fingerprints survive. This matters
   for QR-pinned peers and the Circle host roster.
5. For a relay-only receiver, start `flit-cli relay --port 53318 [--token T]`
   first, then `flit-cli serve --dir … --relay 127.0.0.1:53318 [--token T]`.
   There is no direct listener and no announce; reachability is only through
   the subscription.
6. For interop against a reference client, `~/.local/opt/localsend-cli`
   (v1.18.2) is used by the `interop/` rig; the app and CLI are clean-room
   implementations of the v2.2 wire protocol, not copies of LocalSend source.

## 4. Phone setup and the permission gate

### 4.1 Device facts on this rig

The hardware walk device is a Redmi Note 10S on API 36
(bluetooth-carrier.md:192). Install a fresh build with
`adb install -r <release-apk>`; the rig's release key is stable across rebuilds
so updates land in place. One earlier debug-signed build needed a single
uninstall before the signed update took (bluetooth-carrier.md:288-291) — if a
signature mismatch appears, uninstall once and reinstall. Wi‑Fi Aware is
**absent**: `adb shell pm has-feature android.software.wifi.aware` answered
`false` on 2026-11-21, which is why milestone B (fat pipe) is parked by a
hardware gate (transport-bluetooth.md:96-100).

### 4.2 Runtime permissions and when they are asked

The manifest declares `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`,
`CHANGE_WIFI_MULTICAST_STATE`, `FOREGROUND_SERVICE` +
`FOREGROUND_SERVICE_DATA_SYNC` + `FOREGROUND_SERVICE_CONNECTED_DEVICE`,
`POST_NOTIFICATIONS`, `BLUETOOTH_ADVERTISE` / `SCAN` (`neverForLocation`) /
`CONNECT` plus the legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` pair capped at API 31,
`CAMERA`, `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO` (legacy
`READ_EXTERNAL_STORAGE` capped at API 32) and `RECEIVE_BOOT_COMPLETED`
(AndroidManifest.xml). None is requested at install; the asks are:

| Gate | When it is raised | What happens if refused |
| --- | --- | --- |
| Notifications | first start of sharing | progress notifications unavailable; engine still runs |
| Nearby devices (BT advertise/scan/connect) | only at the exact no-AP moment and only on a real ask, once per device; the dialog wording is Android's | LE stays dark; Wi‑Fi roster unaffected; log says `bluetooth link waiting — permission or radio off` |
| Bluetooth radio power | after permission grant if the radio is still off | radio stays off; arbiter keeps telling the AP truth |
| Camera | QR scan | pairing by code unavailable; manual address still works |
| Media images/video | only when album mirror is switched on in Options | mirror stays off, logged `photo access is needed — mirroring stays off` |
| Battery-optimization exemption | Options → "Background battery" → system settings (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`) | MIUI may kill the engine; persisted sharing intent restores it on next open, not by MIUI restarting us |

**The opt-in carrier gate is separate from the permission gate.** Bluetooth
radio use is off until **Transfer over Bluetooth** is enabled in Options
(pref `bluetoothTransfer`, default false); discovery-only LE surfaces nothing
on its own. The connectedDevice FGS type is requested only when that option is
on *and* `FlitBluetooth.hasPermission()` is true (FlitService.kt:127-153);
otherwise `dataSync` alone is used. If a strict ROM throws `SecurityException`
for the requested flags, the service retries with the `dataSync` baseline —
losing the label is preferred to losing the FGS. A refused start logs
`system refused foreground service (<class>) — running engine-only until you
open Flit next` and toasts `background sharing limited by system; works while
Flit is open` (FlitService.kt:83-96).

**The receive gate is a prepare-time decision, not a discovery filter.**
`onPrepare` declines when QR-only is on and the fingerprint is not in the Known
devices store, or when "trusted senders only" is on and the fingerprint is not
pinned; the decision never uses an IP (FlitEngine.kt:967-989). Logs are
`declined QR-unknown device <alias> (<fp12>)` and `declined offer from <alias>
(<ip>) — device not trusted`. A decline is a receiver answer that concludes the
attempt; it is different from a carrier death, which throws and keeps the row
([FAILURE-MODES.md](FAILURE-MODES.md)).

**Known devices / relay gate.** Each Known device row has a route policy
(Auto / Wi‑Fi / Bluetooth / Relay). A `relayFor` switch gates relay dialing per
device: an opted-out device is never routed through the relay, and Circle
dialing through one needs the address + token and `relayFor` on
([README](../README.md#desktop-companion-flit-cli),
[circle.md](circle.md#transport-and-battery)).

**MIUI and OS limits, recorded:** battery saver, autostart rules and memory
pressure can kill the process; Android 15 rations `dataSync` foreground time to
about six hours a day, after which `onTimeout()` removes the notification
(avoiding a fatal `RemoteServiceException`) but leaves the engine alive while a
Flit surface is foreground; an explicit Stop is the one path that persists
"sharing off" ([open-file-and-sharing-restore.md](open-file-and-sharing-restore.md)).

### 4.3 Handy device commands

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell pidof app.flit.share                       # is the process alive?
adb shell am force-stop app.flit.share               # hard stop (does not clear sharing intent)
adb shell pm has-feature android.software.wifi.aware # Wi-Fi Aware gate (false on this rig)
adb shell setprop debug.flit.nopin 1                 # debug escape hatch before engine start
adb logcat -s FlitSend:I                             # the app's engine log tag
```

`debug.flit.nopin` is a developer escape hatch read by `FlitEngine.debugProp`
(FlitEngine.kt:3220-3224); when set it logs `egress pin skipped
(debug.flit.nopin)`. It is not a user setting.

## 5. Logs and what they mean

### 5.1 App log (`adb logcat -s FlitSend:I`, in-app last 60 lines)

`FlitEngine.logLine()` prefixes `HH:mm:ss`, writes `Log.i("FlitSend", text)`
and keeps the last 60 lines in memory (FlitEngine.kt:3204-3209). A log line is
an observation, not a promise: it says what the engine saw at that instant.

| Line (or family) | Meaning |
| --- | --- |
| `serving https://0.0.0.0:53317 as “<alias>”` | TCP/TLS listener up; the port in the line is the settled port (the FGS reposts after start) |
| `egress pinned to Wi‑Fi network` / `could not pin egress; using default network` / `no Wi‑Fi network; using default network` | which `Network` the sender bound to; the mobile-data-only case is the third |
| `egress pin skipped (debug.flit.nopin)` | debug property set; pinning deliberately disabled |
| `qr-only: no ambient discovery` | QR-only mode rebound the engine; no scan, no prober, no sweep |
| `hidden mode: not announcing — others find this device by address, or when you send first` | hidden mode; a register would be an announcement, so none is sent |
| `relay: subscribed at host:port as <fp12>` | relay subscription started with the engine |
| `mprober ip:port ok` / `mprober ip:port: no answer` | targeted keeper probe of a known peer; used to keep rows fresh |
| `register to ip:port failed` / `register to ip:port: <error>` | first-contact register failed; no row minted from that attempt |
| `sweep <cidr>: N hosts, found M (…) in Xms` / `nothing found in Xms — rounds continue, quiet until a find` | subnet rescue round; only fires when the roster has no live row, ≥120 s apart, ≤24 knocks in flight; empty rounds print once |
| `sweep keeper: <error>` / `sweep: ip quiet — its row retires` | keeper failure / three consecutive misses retiring a dial-only peer |
| `hunt: ip:port answered as <alias>` / `hunt: relay answered as <alias>` | the hunter's fingerprint-matched answer; the flush follows through the ordinary door |
| `hunt: <error>` | a probe failed; calm backoff continues while the row is owed |
| `offer from <alias> (<ip>): N file(s)` | a prepare arrived and passed both gates |
| `declined QR-unknown device …` / `declined offer from … — device not trusted` | prepare-time refusal by QR-only / trusted-senders gate |
| `resuming <file> at <size>` / `receiving <file>` | a fresh attach from the receiver's prefix / a full receive |
| `rx <done>/<total> pace X.X MiB/s`, `saved <file> (<size>)`, `→ Downloads/Flit/<name>` / `→ <name> (chosen folder)` | live receive progress, the landing, and where it landed |
| `BAD <file>: checksum mismatch` / `error writing <file>` | hash gate failed / storage write failed; both are terminal conclusions, not retries |
| `queued N file(s) for <peer> — sending now`, `<reason> — kept, sends when <peer> reappears`, `queued send cancelled` | send-later queue auto-flush, owed row kept after a carrier death, user cancellation |
| `system refused foreground service (<class>) — running engine-only until you open Flit next` | FGS type rejected by the ROM; background sharing is not guaranteed |
| `boot receiver fired (<why>)`, `boot: start-on-boot off — staying quiet`, `boot: FGS rationed — starting engine-only` | boot path; Android 15 bans `dataSync` FGS from BOOT, lifting at first unlock |
| `stopped` | the engine actually stopped; a process kill by MIUI does **not** produce this line |
| `panic: <fp12> — <text>`, `panic: ignored unverified or QR-unknown origin <fp12>`, `panic: reached its hop limit …` | alert received, refused at the origin gate, or forward refused at 3 hops |
| `circle sync: +N event(s) from <fp12>`, `circle host: <action> <fp12> — N event(s)`, `circle: left fully — key discarded` | inbound sync, host-side request, and full leave with key discard |
| `scan: pinned <alias> (code verified)`, `scan: <host>:<port> answers as <real>, code said <code> — NOT trusted` | QR pairing succeeded / fingerprint mismatch refused |
| `no Wi‑Fi network; direct discovery on` / `access point back; direct discovery dark` / `… waiting on the system` | arbiter state at a no-AP moment, AP restored, or permission ask pending |

Other tags: `FlitSend` also carries `Log.i` for share intake (`ingest
action=…`, `share-in: direct target …`, `direct-share dispatch to …`);
`FlitOpen` logs `open-file path failed` with a throwable when an Open detour
fails; the BLE radio logs warnings under its own `FlitBleRadio` tag
(`advertising set failed`, `scan start failed`, `scan failed: <code>`); it
otherwise emits no log lines.

### 5.2 CLI log (`flit-cli`, stdout/stderr)

| Line | Meaning |
| --- | --- |
| `HH:mm:ss serving <scheme>://0.0.0.0:<port> dir=<abs>` | server bound; scheme is `https` unless `--http` |
| `HH:mm:ss fingerprint <fp>` | this CLI's stable identity; pin this on the phone |
| `HH:mm:ss hidden: not announcing — …` | hidden serve; expect the phone's rescue sweep, not multicast, to find it |
| `HH:mm:ss circle <id> invite=<token> — join with: …` | Circle host seeded; copy the join command |
| `HH:mm:ss relay subscription host:port dir=<abs>` / `relay: <error> — retry in 2s` | relay-only receiver subscribing / reconnecting after a dropped pair |
| `HH:mm:ss seen <alias> (ip:port) type=… proto=… v=…` | a peer registered (or answered an announce); deduped by fingerprint |
| `HH:mm:ss offer from <alias> (ip): N file(s)` + indented `- <id> → <name> (<size> bytes, …)` | prepare; the harness **auto-accepts** — there is no trusted-senders gate on the CLI |
| `recv-start <name> id=<id>` | upload byte stream began |
| `recv-resume <name> from <bytes> bytes` | attach to a kept prefix; the number is the exact `/partial` answer |
| `recv-ok <name> → <path> (<bytes> bytes)` / `recv-ok … streamed` | hash-exact landing (classic two-step / direct-to-storage) |
| `recv-BAD <name> checksum mismatch` / `recv-ERR <name>` | hash gate failed / write error |
| `session <id8> finished|cancelled` | transfer session ended; cancelled is an explicit end, not a crash |
| `panic from <fp12> (ip): <text> [hops=N]` | signed alert arrived; `hops` is inside the signed canonical body |
| `relaying on 0.0.0.0:<port> token=set|none` | relay listening; token gate on/off |
| `pair c3 + c4` / `pair c3 + c4 closed` | opaque pairing ticket ids only — deliberately no fingerprint/byte count/content |
| `!! <message>` (+ `set FLIT_TRACE=1 for a stack trace`) | top-level failure; exit code 1 unless a command-specific code applies |
| `bye` | graceful shutdown hook ran (serve, relay) |

The meaning of `recv-resume`, `kept` and the hash gate as *failure conclusions*
is detailed in [FAILURE-MODES.md](FAILURE-MODES.md); the relay's blindness is a
security property in [SECURITY.md](SECURITY.md).

## 6. Walkthrough recipes

Each recipe states what is proved and what is not. Commands are the ones the
rig used; substitute addresses/ports for a different network.

### 6.1 Green rig before a commit

```bash
source env.sh && ./gradlew :protocol:test :app:testDebugUnitTest :app:assembleDebug \
    :app:assembleRelease :harness:installDist
```

Expected: 302 tests, 0 failures at the last green standard; debug and release
APKs plus the CLI distribution produced. CI runs the superset at
.github/workflows/rig.yml:35-49 and uploads the CLI zip as artifact `flit-cli`.

### 6.2 Box serve + discover + send (interop baseline, harness evidence)

```bash
flit-cli serve --dir ~/Public                       # terminal A
flit-cli discover --wait 5                          # terminal B
flit-cli info 192.168.100.12:53317
flit-cli send 192.168.100.12:53317 ./notes.txt --parallel 4
```

`discover` prints the peer with its fingerprint; `info` confirms TLS and
version; `send` prints `sent 1/1 … ok notes.txt` and the receiver prints
`offer → recv-start → recv-ok`. `--pin <fp>` adds the fingerprint check;
`--multi` exercises parallel streams. Exit 3 means at least one file failed.

A single-process carrier check that needs no network:

```bash
flit-cli bt-sim --bytes 512                         # TLS pipe, synthetic 512 MiB
flit-cli bt-sim --bytes 512 --no-tls                # plain pipe, /info labels http
```

This proves the v2 exchange over the simulated pipe, **not** a real radio link.
The measured Wi‑Fi number from the rig is 512 MiB in ~15.9 s ≈ 32.1 MiB/s
(~270 Mbps), identical single- and multi-stream; Bluetooth is a reachability
fallback, never a speed upgrade
([bluetooth-carrier.md](bluetooth-carrier.md) "Speed, measured 2026-11-24").

### 6.3 Phone ↔ box over Wi‑Fi (on-device evidence)

1. `flit-cli serve --dir ~/Public` on the box; phone starts sharing from the
   dock or share sheet.
2. The box appears in the phone roster by multicast/register (or by the rescue
   sweep if multicast is stripped); add a manual address if needed.
3. Phone → box: pick a file from the dock/share sheet; box logs
   `offer … recv-start … recv-ok`.
4. Box → phone: `flit-cli send <phone-ip>:53317 <file>`; the file lands in the
   phone's `Downloads/Flit/` with a ledger row. This exact direction was walked
   (`from-box.bin`, "_received_ from Flit-sender", bluetooth-carrier.md:262-265).

### 6.4 Resume after kill (milestone C/A, on-device evidence)

Fixture: box serves the C/A build, phone runs the 0.2.0 release APK, parallel
streams toggled **off** in Options to exercise single-stream. Payload a large
file pushed to the phone's `Download/`.

1. Send from the phone; mid-body, kill the sender with
   `adb shell am force-stop app.flit.share` (or kill the receiver with `kill -9`).
2. On the box inspect the receive dir: a `.part` and its `.part.token` sidecar
   remain (the C walk: 127,959,040 bytes of a 400 MB file, plus the
   name∥size∥sha token). The server logs `recv-start` then `recv-BAD checksum
   mismatch` — no special death path.
3. Kill and restart `flit-cli serve`; `sweepOnStart`/`repopulateParts` reads the
   sidecar back with an empty senderIp.
4. Relaunch the app (`am start` / open it); the queue row survived in prefs. The
   next touch auto-flushes: `queue: 1 queued for <peer> — sending now`. The
   first attempt probes `/partial`; the receiver answers from disk; the sender
   attaches with `X-Flit-Resume`.
5. Box logs `recv-resume <name> from <prefix> bytes`, landing is
   hash-exact, part and sidecar are gone, phone's row is gone. The A-era final
   walk used a 300 MiB camera shot killed 0.8 s after `recv-start` with a
   33,980,416 B part; a late kill near completion instead took a clean full
   resend (a sparse prefix is not advertised as a resume base)
   ([transport-bluetooth.md](transport-bluetooth.md#post-ship-ladder-2026-10-30)).

### 6.5 Relay-only receiver and relay kill (milestone E, on-device evidence)

```bash
flit-cli relay --port 53318 --token T                          # terminal A
flit-cli serve --dir ~/Public --relay 127.0.0.1:53318 --token T # terminal B
```

1. On the phone type the relay address into Options (empty by default, so E is
   invisible until configured); the Known device for the box must have
   `relayFor` on.
2. Send a file. Direct candidates are skipped (no listener, no announces); the
   phone logs `hunt: relay answered as <alias>`, the relay logs four opaque
   pairings, the receiver lands a hash-exact file (200 MiB walk).
3. Kill the **relay** mid-body. The phone keeps the row and logs a retry with
   backoff; both ends re-subscribe; the next hunt re-pairs through the restarted
   relay and logs `recv-resume … from 31866880 bytes` — the exact killed prefix —
   with a hash-exact landing and no leftover part (300 MiB walk). This is the
   throw/keep/resume loop proven through the blind pipe on hardware, not just
   in the harness ([milestone-e.md](milestone-e.md),
   [transport-bluetooth.md](transport-bluetooth.md#post-ship-ladder-2026-10-30)).

### 6.6 Send-later queue (on-device evidence)

1. With `serve` down, pick a file in the phone's share sheet and tap the peer
   (or aim at a manual address). Log: `<reason> — kept, sends when <peer>
   reappears`; the "Send later" row appears.
2. Pick a second file into the same dead peer; it **merges** into the one row
   ("2 file(s) waiting") instead of dying as "send failed".
3. Restart `serve`; the mprober notices within a beat, logs
   `queue: 2 queued … — sending now` → `sent 2/2`; both files land (a duplicate
   name gets a `(1)` suffix).
4. Cancellation: queue a third file, press the row's X → `queued send
   cancelled`, row gone. Grant release on delivered SAF batches is
   **code-verified, not device-walked**: picker files are spooled at intake, so
   that walk carried cache paths, no SAF grants.

### 6.7 Hidden mode and the rescue sweep (on-device evidence)

1. Start `flit-cli serve --hidden` (or toggle hidden on the phone, which
   rebinds the engine itself). Confirm the "hidden: not announcing" line and no
   multicast announce.
2. On a phone with an empty roster and Wi‑Fi up, the next throttled walk finds
   it: `sweep 192.168.100.0/24: 253 hosts, found 1 (Flit (szszszs)@
   192.168.100.12) in 2773ms`. With hidden ON, zero sweep lines are produced —
   a register would reveal the device.
3. Kill `serve`; the row ages out (~30 s TTL), the keeper's third miss logs
   `sweep: 192.168.100.12 quiet — its row retires`, and the next throttled walk
   logs `nothing found`. Restoring `serve` re-finds it. A workaround for
   multicast-blocked APs is `--announce-to ip:port,…` unicast announce copies.
   A slow/cold v2.2 server can miss the sweep's 400 ms register budget and still
   arrive by multicast, manual address or once warmed.

### 6.8 Circle two-node walk (on-device evidence)

```bash
flit-cli serve --dir ~/Public --circle <id> --invite <token>   # box hosts (also prints join line)
flit-cli circle join --host 127.0.0.1:53317 --circle <id> --invite <token>
flit-cli circle post --text "hello from the box"
flit-cli circle poll --text "where?" --option home --option away
flit-cli circle vote --option 0
flit-cli circle sync
```

On the phone, join by pasting a QR v3 code or tapping a `flit://add?v=3&…` link
(the VIEW intent starts sharing and joins). Evidence recorded: inbound sync
`circle sync: +1 event(s) from AFF5EBF981DE` rendered with author and timestamp
in the M3 feed; poll votes changeable with tally flipping 0→1; full leave logs
`circle: left fully — key discarded` and the feed disappears. The phone is a
full host (`FlitCircleHost` behind `onCircleRequest`): it verifies each event,
gates the roster, admits JOIN only with the right invite, rotates the key on
departure and refuses later syncs from departed fingerprints.

**Not device-proven:** outbound phone→host fan-out from the app. The behavioural
tests pass with an injected carrier, but the device walk could not get a clean
peer-side confirmation because the demo bots' CLI state and their `serve`
process shared identities ([circle.md](circle.md#known-limitations)).
A genuine three-or-more-device mesh, and phone→host fan-out, stay listed as
limitations. A file drop from the CLI uses `circle file --file PATH` (bytes via
`sendFiles`); `circle post --file PATH` announces only.

### 6.9 Panic alert (on-device evidence)

```bash
flit-cli panic 192.168.100.80:53317 --text "check in"
```

The box logs `panic from <fp12> (ip): <text> [hops=0]`; a phone-sent alert
produced a box log with the sender fingerprint, and a box-sent alert produced a
phone banner with alias, note and safety copy. Acknowledge / Dismiss / Forward
are explicit; Forward re-signs with the forwarder's identity while `originFp`
preserves the author, and `forward` returns null at `DEFAULT_MAX_HOPS = 3`
([qr-only-and-panic.md](qr-only-and-panic.md)).

### 6.10 Bluetooth opt-in and permission gate (single-phone walk only)

1. On a phone with Wi‑Fi up, leave **Transfer over Bluetooth** off: BLE stays
   dark, roster works over Wi‑Fi, log may say `bluetooth link waiting —
   permission or radio off`.
2. Force the no-AP moment (mobile data only / AP down) with grants revoked: the
   OS's own "find, connect to nearby devices" sheet is presented once; grant →
   possibly the radio-power dialog → radio flips on and the arbiter logs
   `no Wi‑Fi network; direct discovery on`. Ask-flags are written per-launcher.
3. Toggle **Transfer over Bluetooth** on: engine rebinds, logs `bluetooth link:
   listening`; connectedDevice FGS type is requested only with the live grant.
4. Restore the AP: log `access point back; direct discovery dark`; the option
   stays on but LE rests while Wi‑Fi owns the roster.

**Not verified:** a real phone-to-phone RFCOMM transfer — there is no second
phone, and the bench box has no Bluetooth adapter (`bluetoothctl list` is
empty). The full v2 exchange over the pipe is harness-proven (`bt-sim`), and the
arbiter/ask lifecycle is walked on hardware, but the real socket path is not
([bluetooth-carrier.md](bluetooth-carrier.md)).

### 6.11 Open-file detour and sharing restore (on-device evidence)

1. Put a small file on the box and drop it into Circle with
   `flit-cli circle file --file <path>`; confirm hash-exact landing on the phone
   and a card showing **"Landed here"** with an enabled **Open file** button.
2. Tap Open file: `ChooserActivity` appears; `adb shell pidof app.flit.share`
   stays alive; log has no `FATAL`, `am_crash`, "crashed too many times" or
   "Force finishing". Back from the chooser returns to the Circle feed with the
   dialog intact.
3. `adb shell am force-stop app.flit.share` → relaunch: new PID logs `egress
   pinned…` and `serving https://0.0.0.0:53317…` automatically; Home shows
   **Stop** (sharing on). This is the persisted `sharingDesired` restore, not a
   manual toggle.
4. A card with no local copy shows no Open button; a failed open ends in a
   toast ("nothing can open …", "no location stored for …"), never a crash.

Root cause, per-layer fix and the MIUI/Android 15 caveats are in
[open-file-and-sharing-restore.md](open-file-and-sharing-restore.md).

## 7. Troubleshooting

| Symptom | What the log says | What it means / what to do |
| --- | --- | --- |
| "app closed / sharing off after opening a file" | no `stopped`, no `am_crash`, no `RemoteServiceException`; possibly `system refused foreground service (…)` | four stacked seams (activity auto-finish, ambiguous back ownership, missing FileProvider root, memory-only `running`). Fixed in the working tree; relaunch restores from `sharingDesired`. An explicit Stop is honoured. See [open-file-and-sharing-restore.md](open-file-and-sharing-restore.md) |
| MIUI kills sharing in background | process disappears with no `stopped` line | battery/autostart/memory outside app control; exclude from battery optimization in Options, grant autostart, and rely on the next-open restore |
| `dataSync` FGS times out (~6 h/day on Android 15) | notification removed, engine still serving while a Flit screen is open | `onTimeout()` deliberately drops the notification to avoid a fatal `RemoteServiceException`; background is not guaranteed for the rest of the budget; next cold open restores |
| Peer invisible on roster | `mprober ip:port: no answer`, or `hidden: not announcing`, or `declined QR-unknown device …` | hidden peer is found by address/sweep/send-first; QR-only declines unknown fingerprints at prepare; check Known devices and route policy |
| Multicast stripped by the AP | no `seen … (multi …)` lines; empty roster | start the phone's rescue sweep, add a manual address, or run `serve --announce-to ip:port,…` for unicast announce copies |
| Sweep finds a peer then it blinks | `sweep … found 1` then row pruned ~30 s later | dial-only peer with no announce; the keeper re-registers every ~15 s and retires after three misses; fixed, update the build |
| Send fails after peer leaves mid-file | `<reason> — kept, sends when <peer> reappears` | carrier death threw `TransferUnreachable`, the row and spools stay; the hunter re-probes and the flush resumes from the receiver's prefix; do not delete the row |
| Receiver has a `.part` but no resume | `recv-BAD` then no `recv-resume` after restart | sidecar missing or origin changed (Wi‑Fi IP vs BT MAC): clean full resend, still hash-gated; the final hash decides, not the resume |
| `flit-cli` refuses to start | `flit-cli needs JDK 21 …` | `source env.sh` or set `JAVA_HOME`; the gate is intentional |
| Relay pairing never happens | relay log silent; `relay: … — retry in 2s` on receiver | both peers must be live (no store-and-forward); token must match; address is hand-configured; `relayFor` on for the peer; check `pair cN + cM` lines |
| Circle refuses a join/sync | `!! join refused: host refused: bad invite\|not-a-member\|wrong circle` | exact host reason is surfaced; re-scan/re-paste the v3 invite; a departed member is refused after rekey |
| Circle file shows no Open button | card says "Not here yet · no local copy to open" | that SHA-256 is not present locally (a later joiner sees the card but no old bytes); delivery status is sender-local and never gossiped |
| `!! <message>` from the CLI | top-level handler; exit code 1 (or 2/3) | set `FLIT_TRACE=1` for a stack trace; check the command's positional args and flags |
| FGS start refused from boot | `boot: FGS rationed — starting engine-only` | Android 15 bans `dataSync` FGS at BOOT; engine runs until a surface opens, then restore; not a crash |

Two cross-cutting rules when debugging: a **returned report means the receiver
spoke** (sent/refused/declined), while a **throw means the carrier died with no
answer** (keep the row and bytes). Every landing is verified by sha256
([FAILURE-MODES.md](FAILURE-MODES.md),
[CONTRACTS.md](CONTRACTS.md)).

## 8. Cleanup and stop commands

### 8.1 Graceful stop (preferred)

- `flit-cli serve` / `flit-cli relay`: press Ctrl+C (SIGINT). The shutdown hook
  stops discovery, closes the server / parked sockets and prints `bye`. A
  `kill -9` skips this hook and leaves any in-flight `.part` + `.part.token` on
  disk; that is deliberate for the death walks, because the restart sweep
  repopulates the prefix from the sidecar. For normal operation, use Ctrl+C.
- `bt-sim`, `discover`, `send`, `info`, `circle`, `panic`, `parse-qr`: one-shot;
  they exit on their own. `discover` with no peers exits 2 by design.
- Phone: tap **Stop** in the app (or the Quick Settings tile, which calls
  `stopService` and reaches the same path). The documented intent is that this
  is the only path that clears `sharingDesired`, so a later relaunch stays
  stopped. Code-traced nuance: `FlitService.onDestroy()` also clears the row
  whenever that instance owned a live, non-timed-out FGS
  (FlitService.kt:175-184) — including an FGS-start error path that calls
  `stopSelf(startId)` (FlitService.kt:99-116). A plain
  `adb shell am force-stop app.flit.share` does **not** reach `onDestroy`, so it
  leaves the persisted intent set and the next cold open restores sharing.

### 8.2 Stopping a stuck process

```bash
adb shell pidof app.flit.share                 # confirm it is alive
adb shell am force-stop app.flit.share         # Android hard stop
# on the box, identify the CLI process then:
kill -TERM <pid>                               # graceful (shutdown hook runs)
kill -9 <pid>                                  # only for a deliberate death walk
```

### 8.3 Files created during walks, and what is safe to remove

| Path | Created by | Safe cleanup |
| --- | --- | --- |
| `flit-downloads/` (cwd, default `serve --dir`) | CLI receive | contains received files (and `.part`/`.part.token` after a kill); remove the ones you no longer need; gitignored |
| `<--dir>` custom path | CLI receive / `bt-sim` (`flit-sim-received/`) | same; `bt-sim` synthetic bodies are random test data |
| `flit-tmp-discover/`, `flit-tmp-circle-file/` | `discover`/alias resolution, Circle route sweep | scratch receive dirs, safe to delete when no process is running |
| `~/.config/flit/flit-harness/identity.p12` | first CLI run | **keep**: deleting it mints a new fingerprint, and peers that pinned the old one stop trusting the box |
| `~/.config/flit/flit-harness/circle.state` | `circle create/join` | deleting it is a local leave (key discarded); the host still lists you until a leave event is accepted |
| build dirs (`build/`, `.gradle/`, `.kotlin/`) | Gradle | generated; `git clean` or targeted `rm -rf` when rebuilding from scratch |
| phone `Downloads/Flit/` and chosen SAF folders | app receive | managed from the app's ledger ("clear anything"); deleting outside the app removes the file but leaves the ledger row pointing at a stale path — "Open file" then degrades to a toast, not a crash |
| phone `cacheDir/circle/…` spools | Circle file drop | removed by the sender once every recipient is terminal; a killed process may leave a stale spool until the next start sweep |

Do not delete `.part` / `.part.token` sidecars while a resume walk is in
progress: the prefix is the only progress the sender re-learns via `/partial`.

## 9. Release artifacts and provenance

- **APKs:** `app/build/outputs/apk/debug/app-debug.apk` and
  `app/build/outputs/apk/release/app-release.apk`. Signing uses
  `keystore.properties` + `keystore/flit-release.jks` when present (both
  gitignored); without them release falls back to the **debug key** so a source
  build still assembles (app/build.gradle.kts:11-17,31-56). The published
  release key has subject `CN=Flit, OU=Flit contributors`; verify with
  `apksigner verify --print-certs app-release.apk` (build-tools 36). `adb
  install -r` only works when the new APK has the **same signing identity** as
  the installed one. Moving from the debug-signed development build to the
  release-signed one therefore needs an uninstall first, which clears local
  app data (history, Circle membership, shared-desired flag); plan for that
  rather than expecting an in-place update.
- **CLI zip:** `harness/build/distributions/flit-cli-<version>.zip`, produced by
  `:harness:distZip`; CI uploads it as artifact named `flit-cli` (rig.yml:45-50).
  `install-cli.sh --zip <file>` installs that artifact to
  `~/.local/lib/flit-cli` with a launcher at `~/.local/bin/flit-cli`.
- **License:** `LICENSE` is the full GNU GPL v3 text with
  `SPDX-License-Identifier: GPL-3.0-or-later`. The earlier permissive MIT
  license was replaced deliberately; derivatives that are distributed must
  remain under the same license.
- **Release verification:** the published bundle carries the signed APK, the
  CLI zip and `SHA256SUMS.txt`. Check with `sha256sum -c SHA256SUMS.txt` and
  re-check the APK signer before sideloading.
- **Provenance:** development was private; the repository is published with its
  full commit history (scaffolding, milestones, regressions and fixes), not as
  a reconstructed snapshot. The tag is `v0.2.0`.
- **Store metadata:** `fastlane/metadata/android/en-US/` holds title,
  short/full description and `changelogs/1.txt` ("First release").
  `RELEASE_NOTES.md` carries v0.2.0 notes plus a dated 2026-11-25 correction:
  the original "no new settings/vocabulary" copy was superseded by the opt-in
  **Transfer over Bluetooth** option on 2026-11-24.
- **Version identity:** namespace/applicationId `app.flit.share`, versionName
  `0.2.0`, versionCode 2, minSdk 26, compile/targetSdk 36. The app is
  `allowBackup=false`; identity lives in `filesDir/flit-identity.p12`.
- **Play listing / data safety:** zero collection, no accounts, no cloud, no
  ads, no telemetry, no phone-home SDKs; see
  [play-listing.md](play-listing.md). The clean-room status (protocol
  specification as reference only, no LocalSend source or assets) is stated in
  the [README](../README.md#licensing--provenance-note).

## 10. Known limits

- Two-phone RFCOMM transfer and a multi-member Circle mesh were not walked —
  no second phone on the rig, no Bluetooth adapter on the box. `bt-sim` and the
  harness two-node tests cover those paths in process only.
- Wi‑Fi Aware is absent on the rig phone; milestone B is parked by a hardware
  gate.
- The phone→host Circle fan-out direction remains test-evidenced only.
- Grant release on delivered SAF batches is code-verified, not device-walked.
- MIUI autostart/battery behavior and Android 15's ~6 h `dataSync` cap are ROM/OS
  limits outside the app; no background guarantee is claimed.
- Where the README CLI summary and the actual `flit-cli` help/arg parser disagree
  (§2.3), the help text and parser decide.
