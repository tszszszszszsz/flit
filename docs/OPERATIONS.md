# Operations — CLI, rig setup, permission gate, logs, walkthroughs, cleanup

> Part of the Flit design set. [Transport ladder and index](transport-bluetooth.md) ·
> [README](../README.md).

The historical full-suite count was **778 tests**: 362 protocol + 324 app + 92
harness in the three-module layout, and the same total in the first four-module
projection of 362 protocol + 112 core + 212 app + 92 harness. After the security
closeout wave the last recorded four-module test XML is **972 tests**, split as
443 protocol + 167 core + 254 app + 108 harness, with zero failures, zero errors
and zero skipped cases. The zero-skipped value is an observation with `FLIT_HARNESS_HOME`
unset and POSIX file attributes available; harness `assumeTrue` gates can skip
otherwise. That XML is the current inventory and the projected split
for this layout; the release assembly is green after the R8 keep-rule fix, and
no finished-release claim follows from it. Earlier counts (76,
115, 302, 396, 588, 714, 769, 778) are historical snapshots of the same suite at
earlier layers. The full inventory is in [TESTING.md](TESTING.md).
The v0.2.0 baseline was `72225f8` (Open-file crash + sharing restore), preceded
by `2034fa6` (Circle file delivery + quality pass); later layers are recorded
under `docs/completions/`.

## 1. Rig at a glance

Four modules, four roles ([README](../README.md#repo-layout)):

| Module | Role | Runs on |
| --- | --- | --- |
| `protocol/` | JVM-pure wire engine (JSON DTOs, UDP discovery, TLS+mTLS, HTTP server/client, relay core); 443 tests in the last recorded XML | JVM only; no Android dependency |
| `core/` | JVM module holding the ten Android-free seams moved out of `:app` plus the shared trust and admission reducers; 167 tests in the last recorded XML | JVM only; no Android dependency |
| `app/` | Android shell (Jetpack Compose), package `app.flit.share`, version `0.3.1` (code 4), minSdk 26, compile/targetSdk 36; v0.3.2 is planned and build files are unchanged; 254 tests in the last recorded XML | Phone |
| `harness/` | Desktop companion shipped as `flit-cli`; doubles as the interop and device-walk rig; 108 tests in the last recorded XML | Linux box with JDK 21 |

The `:core` move was a relocation of source and tests, not a behaviour change.
The ten seams are the Android-free models, codecs, reducers and policy helpers
that had no Android dependency; their platform adapters, radio and camera code
and Compose rendering stay in `:app`. The move itself changed no wire behaviour:
the routes, headers, DTO fields and v2.2 statuses stayed the same. The later
security closeout wave added Flit-only prepare headers and new `flit-cli` flags;
the v2.2 wire surface and status codes stayed unchanged. Command names, flags,
help text, output lines and exit codes in §2 describe the current CLI, including
the relay bind default and the prepare gate flags.

Toolchain lives outside the repo in `~/.local/`; `env.sh` pins JDK 21
(`JAVA_HOME="$HOME/.local/jdks/temurin-21"`), and CI instead uses
`actions/setup-java` Temurin 21 (rig.yml:20-24). The reference client used for
interop is the official `localsend-cli` v1.18.2 at `~/.local/opt/localsend-cli`
(interop/).

Dependency pins live in `gradle/libs.versions.toml`: Kotlin 2.2.0,
kotlinx-serialization 1.9.0, coroutines 1.10.2, Ktor 3.2.2, Bouncy Castle 1.87,
Netty 4.2.21.Final, slf4j 2.0.17, AGP 8.13.2, Compose BOM 2026.06.00,
activity-compose 1.13.0, ZXing 3.5.3 and CameraX 1.4.2. All `io.netty` modules
resolve to one patch level with a floor of 4.2.18.Final, and `kotlin-reflect`
is aligned to the Kotlin toolchain version (`build.gradle.kts:11-34`). BC 1.81.1
was affected; 1.87 is the current release for both `bcprov-jdk18on` and
`bcpkix-jdk18on`. A pin set is a point-in-time input, not a claim that the set
is free of advisories.

The pre-commit rig command (README "Dev quickstart", and the same task set in
CI, rig.yml:35-49):

```bash
source env.sh && ./gradlew :protocol:test :core:test :app:testDebugUnitTest :app:assembleDebug \
    :app:assembleRelease :harness:installDist
# CI additionally runs :harness:test and :harness:distZip, with --no-daemon --stacktrace
```

A plain `flit-cli` binary can be reached two ways: from the built distribution
(`harness/build/install/flit-cli/bin/flit-cli`) or installed to `~/.local/bin`
via `install-cli.sh`. The installer also accepts a CI zip:
`./install-cli.sh --zip flit-cli-0.3.1.zip`, and an alternate root with
`--prefix DIR`. It gates both build and launch on Java 21 with one readable line
(`flit-cli needs JDK 21 … set JAVA_HOME or put java 21 first on PATH`) rather
than a JVM class-file stack trace (install-cli.sh:1-70).

## 2. `flit-cli` reference

The command list below is a condensed summary of `flit-cli`; the help text
printed by `usage()` (`Harness.kt:193-217`) and the arg parser decide. The
README carries a separate user-facing summary.

```
flit-cli — the LocalSend v2.2 command line (same stack as the Flit app)

serve [--alias N] [--port P] [--dir D] [--udp-port N] [--http] [--hidden] [--announce-to ip:port,...]
      [--relay host:port --token T] -- serve only inside a blind pairer (milestone E)
discover [--wait S] [--udp-port N] [--http] [--announce-to ip:port,...]
send <ip[:port] | alias> <file...> [--http] [--pin PIN] [--parallel N] [--multi]
info <ip[:port]> [--http]
bt-sim [file...] [--bytes MB] [--no-tls] -- both ends in one process over the pipe carrier
relay [--port P] [--host IP] [--token T] [--park-timeout S] -- blind pairer for unreachable peers (milestone E)
panic <ip[:port]> [--text NOTE] -- send a signed informational panic alert
parse-qr <image...>   pairing codes out of screenshots (png/jpg)
circle <create|join|post|poll|vote|role|kick|leave|sync|file|receipt|pad> [--circle ID] [--invite TOKEN]
       [--text TEXT] [--file PATH] [--target FP] [--role ADMIN|MEMBER] [--option N]
       [--sha SHA] [--drop-id ID] [--host ip[:port]] [--key K] -- accountless group coordination over the production Circle routes
       post --file publishes a signed name/size/sha256 reference only;
       file --file publishes it AND sends the real bytes to every current member via sendFiles
       receipt --sha SHA [--drop-id ID] publishes a signed receipt for a keyed drop
       pad put --key K --text T sets a line; pad del --key K tombstones it;
       pad show prints the local view, one line per live key in key order
courier <drop|carry|receive|status|sweep> [--bag DIR] [--to FP] [--cert B64] [--ttl MS] -- carry sealed events in a copyable bag
parcel <encode|decode> -- render a file as numbered QR PNGs or fold the PNGs back into bytes, offline
```

The `courier` surface is detailed in §2.4 and `parcel` in §11.

Value-taking flags, from `ParsedArgs.VALUE_FLAGS` (Harness.kt:91-97):
`--alias --port --dir --dest --udp-port --wait --pin --parallel --announce-to
--bytes --sender --token --park-timeout --relay --text --circle --invite --file
--target --option --host --host-fp --role --bag --to --cert --ttl --out --sha
--drop-id --key --only-trusted --qr-only --pinned --known`.
Everything else is a presence flag. Defaults come from `Protocol.DEFAULT_PORT
= 53317` and `MULTICAST_PORT = 53317` (protocol/…/Protocol.kt:17,20); the relay
default is `DEFAULT_PORT + 1 = 53318` (Harness.kt:956). `--bag --to --cert
--ttl` belong to `courier` (§2.4), `--out` to `parcel` (§11), `--sha
--drop-id` to `circle receipt`, and `--key` to `circle pad` (§2.1). `--host`
belongs to `relay`; `--only-trusted`, `--qr-only`, `--pinned` and `--known`
belong to `serve` (§2.1). The prepare-gate flags are accepted by the parser but
are not printed in the no-argument help line: a bare occurrence enables a gate,
and `true`, `1` or `yes` also enable it. A relative `--bag` is rooted at
`<identity home>` (`harnessHome()`); an absolute path is used as given. With no
`--bag` the bag is `<identity home>/courier-bag`.

### 2.1 Commands, flags and observable output

**`serve`** (Harness.kt:749-847) — run a production v2.2 receiver.

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
- `--only-trusted` / `--qr-only`: prepare-time gates, both off by default, so an
  unmarked v2.2 sender keeps the auto-accept path. A bare flag enables a gate;
  `true`, `1` or `yes` also enable it. With either gate on, the prepare route
  requires `X-Flit-Prepare-Cert` and `X-Flit-Prepare-Sig`, verifies the signature
  over the exact prepared body and binds the certificate's SHA-256 DER digest to
  the fingerprint declared in the body before calling the delegate. A missing,
  malformed, over-cap, wrongly signed or mismatched binding is a concluded 403
  `Rejected`; Flit-marked senders also receive `X-Flit-Refusal: declined`.
- `--pinned FP,FP,…` / `--known FP,FP,…`: comma-separated fingerprint lists for
  the two gates. `--only-trusted` checks the pinned list and `--qr-only` checks
  the QR-known list; both are read from the caller's persisted rows, never from
  the presented certificate. A delegate refusal log line carries only a
  12-character code, not the raw claim or certificate bytes; a binding that fails
  at the route is answered before the delegate is called.

Startup prints, in order: `HH:mm:ss serving https://0.0.0.0:<port>
dir=<abs path>`, `fingerprint <fp>`, `circle <id> invite=<token> — join with:
flit-cli circle join …`. In hidden mode an extra line explains stand-down. With
a prepare gate on, a `HH:mm:ss prepare gate: onlyTrusted=<bool> qrOnly=<bool>
pinned=<N> known=<N>` line is printed before the serving line.
Ctrl+C runs the shutdown hook (`discovery.stop(); server.close()`) and prints
`bye` (Harness.kt:604-607). In relay-only mode there is no shutdown hook around
the subscribe loop; SIGINT/SIGTERM ends the process.

**`discover`** (Harness.kt:613-652) — announce for `--wait S` seconds (default
8) and print every fingerprint seen. Exits 2 with `no peers found` if the roster
stays empty. Output per peer: `peer <alias> ip=<ip>:<port> tls=<bool>
type=<deviceType> v=<version> fp=<12>…`. The listener binds an ephemeral port
and writes to `flit-tmp-discover/`.

**`send`** (Harness.kt:654-693, 716-750) — resolve the target either as a literal
IPv4 `ip[:port]` (regex `^\d{1,3}(\.\d{1,3}){3}(:\d+)?$`) or as an alias found
by a short discovery rendezvous (`--wait S`, default 5, plus the discovery
flags). Directories passed as file arguments are walked recursively and their
relative paths become wire names (`expandSpecs`). Flags: `--pin PIN` (fingerprint
pin), `--parallel N` (default 4), `--multi` (multi-stream), `--http`. Output:
`sent N/M to ip:port`, one `ok <name>` per landed file, `FAIL <name>: <reason>`
per failure; **exit 3 if any file failed**, exit 1 if no files were found in the
given paths.

**`info`** (Harness.kt:777-790) — print the peer's `PeerInfo` JSON (announce
field removed) or exit 2 with `no answer from ip:port`.

**`bt-sim`** (`BtSim.kt:44-136`) — both ends of an exchange in one process over
`SimLinkProvider`/`PipePair`, the same pipes the protocol parity suite uses. The
receiver is the production `ServerCore` through `LinkServer` (the RFCOMM door),
wrapped in `TlsLink` when TLS is on. It exercises `/info`, `/register`,
`/prepare-upload`, `/upload` with sha receipts and session close over a non-TCP
carrier. With no files, one synthetic random file of `--bytes MB` (default 4) is
generated; `--dir` sets the landing dir (default `flit-sim-received`), `--sender`
the alias, `--pin`, `--parallel`, `--multi` mirror `send`. `--no-tls` is
implemented (`BtSim.kt:49`, `/info` then reports `http`) and listed in the
no-arg help string (`Harness.kt:154`). Exit codes: 1 when
neither files nor `--bytes` are given, 2 if `/info` gets no answer, 3 if any file
fails. This is **harness evidence**, not a radio walk.

**`relay`** (Harness.kt:937-964; RelayServer.kt) — blind pairer.

- `--port P`: default 53318 (`Protocol.DEFAULT_PORT + 1`).
- `--host IP`: bind address; default `127.0.0.1`. Loopback needs no token. A
  non-loopback bind without a strong token exits 2 with `!! refusing non-loopback
  relay bind on <host>: <missing-token|weak-token> token (use a strong --token or
  bind loopback)`; a strong token needs at least
  12 characters, at least four distinct characters and a Shannon estimate of at
  least 48 bits (`RelayCore.isStrongToken`). The same policy is enforced on every
  hello after startup.
- `--token T`: optional shared token gate, compared in constant time by
  `RelayCore`; a wrong token gets `ERR bad-token` on the wire. Three wrong tokens
  from one address open a per-IP backoff (`ERR throttled`); a good hello clears
  that address's record.
- `--park-timeout S`: seconds a subscription waits for a partner. The default is
  300 (5 minutes, `RelayCore.DEFAULT_PARK_TIMEOUT_MS`). The accepted range is
  0..604800 (one week); a malformed or out-of-range value exits 2 with `!! bad
  --park-timeout …`. `0` keeps the old wait-forever option. On timeout the ticket
  is withdrawn from the registry and the subscriber gets `ERR timeout`.

Startup: `relaying on <host>:<port> token=set|none` and `pairing requests and
subscriptions by fingerprint; the pipe inside is opaque`. Pairing logs only
`pair c3 + c4` and `pair c3 + c4 closed` — no fingerprint, no byte count, never
content (milestone-e.md:14-28). A connection that fails its bounded hello gets
`ERR bad-hello`; a timed-out subscription gets `ERR timeout`; a missing partner
gets `ERR partner-gone`. The accept loop, hello parse (`RelayHello.MAX_LINE`
bound), FIFO pairing and ghost-ticket `withdraw` are the same production core
the app dials. Parked tickets are capped globally at 1024
(`RelayCore.DEFAULT_MAX_PARKED`) so a subscription flood cannot grow the registry
without bound. Ctrl+C closes the server and all parked sockets and prints `bye`.
There is no relay-side storage, queue or discovery.

**`panic`** (Harness.kt:249-261) — `flit-cli panic <ip[:port]> [--text NOTE]`;
default note `check in when you can`. It signs an informational alert with the
CLI identity and prints `panic to host:port sent|refused`. Informational only:
no remote action on the recipient ([qr-only-and-panic.md](qr-only-and-panic.md)).

**`parse-qr`** (Harness.kt:210-247) — decode pairing codes from png/jpg captures.
It samples at scales 1, 2, 3 and 4 because ZXing's binarizer has module-size
sweet spots; output is `<path>: <decoded text> (scale 1/N)` or `(no code)`.

**`circle`** (Harness.kt:1065-1265; `circleUsage()` at 169-188) — accountless
group coordination over the production `/circle/join` and `/circle/sync` routes.
CLI state lives at `<identity home>/circle.state` (8 lines: id, invite, key,
host, members, events JSON, rekeyGen and the pinned host fingerprint).
Subcommands:

| Subcommand | Required flags | Effect |
| --- | --- | --- |
| `create` | optional `--circle`, `--invite`, `--host` | mints id/invite/key, prints the matching `serve --circle … --invite …` line |
| `join` | `--host ip[:port]`, `--circle ID`, `--invite TOKEN`, `--host-fp FP` | authenticated TLS join with a signed JOIN proof; stores key, members, log and the host fingerprint; refuses with the host's reason |
| `post` | `--text NOTE` **or** `--file PATH` | signed message, or a file-drop **announcement only** (`{name,size,sha256}`) |
| `file` | `--file PATH` | announcement **and** real bytes to every current member via `sendFiles`; local-only status |
| `poll` | `--text QUESTION`, `--option A,B,…` | signed poll event |
| `vote` | `--option N` | change your answer on the latest poll |
| `role` | `--target FP`, `--role ADMIN\|MEMBER` | OWNER-only appointment; nobody appoints a second OWNER |
| `kick` | `--target FP` | OWNER/ADMIN kick executes now; a MEMBER opens a vote-kick |
| `leave` | — | publishes leave then discards the local key and deletes `circle.state` |
| `sync` | a joined `--host` | pulls the full bounded log, prints feed, poll tally and confirmed count for keyed drops |
| `receipt` | `--sha SHA`; optional `--drop-id ID`, `--host ip[:port]` | publishes a signed receipt for a keyed drop |
| `pad` | `put` needs `--key K --text T`; `del` needs `--key K`; `show` needs neither | `put`/`del` publish a signed pad event on the existing Circle routes; `show` prints the local view |

`join` signs a short-lived proof bound to the host fingerprint, circle id,
invite, joining fingerprint and a five-minute bucket; the host recomputes it
before admission, so an invited peer cannot claim another fingerprint, and a
pre-proof client is refused with `join proof required`, `invalid join proof` or
`join proof replay`. `--host-fp` takes the fingerprint printed by `serve` and is
stored with `circle.state`; every later answer must be signed by that host, and
a rotated key is adopted only from a host-signed REKEY event in the signed log,
installed by generation. This adds no route, header, DTO field or v2.2 status.

`file --file` is deliberately different from `post --file`: a plain post never
moves bytes. The `post --file` announcement is the closed `{name,size,sha256}`
shape; `circle file` announces the signed keyed body
`{name,size,sha256,keyGen,cipherSha,iv,wraps}`, with no spool path, no queue
token and no plaintext key on the wire (`Harness.kt:1389-1394`, `:1403-1413`). Delivery statuses are
`pending → sent|refused|failed`, are local-only and never gossiped; a thrown
carrier leaves the recipient `pending` for retry (Harness.kt:913-1009). Full
semantics and evidence live in [Circle](circle.md#file-delivery-as-built).

`circle receipt` is an explicit publish step, not an automatic landing report.
It needs a joined `circle.state` (`circleId`, `key`, `host`); `--host ip[:port]`
overrides the stored host for this one command. `--sha SHA` is the plaintext
digest of a keyed `file` event in the local log. With no `--drop-id`, the CLI
finds the `file` event whose body `sha256` equals `--sha` and uses that event's
`id` as `dropId`. That `file` body must carry `keyGen` and `wraps`; `keyId` is
derived as `DropKeyCore.keyId(dropId, keyGen)` and is never taken from the
operator. If either lookup is missing the CLI prints `!! no key id for drop —
receipts are for keyed drops` and mints nothing. The body is exactly
`{dropId,sha256,keyId}`; the event is appended to `st.events` and posted with
the existing `circleEvent` / `pushCircleEvent` fan-out, so no route, header,
DTO field or v2.2 status changes. There is no `landedMs`: `createdMs` is the
acknowledgement time, and landings are not auto-detected by the CLI.

`circle pad` has three subcommands and reuses the joined `circle.state`:
`pad put --key K --text T` builds its body with `PadCore.padPutBody` and
publishes a signed `pad` event; `pad del --key K` uses `PadCore.padDelBody` to
publish a signed tombstone; `pad show` prints a `PadView` folded from the local
event log, one line per live key in lexicographic key order, with tombstoned
keys omitted. Both writes go through the existing Circle event fan-out on
`/circle/join` and `/circle/sync`; no route, header, DTO field or v2.2 status is
added. `--key` is shared by `put` and `del`, `--text` is used by `put`, and both
are checked against `PadCore.MAX_KEY_CHARS = 128` and
`PadCore.MAX_TEXT_CHARS = 4096`.

`circle sync` and `circle file` print a confirmed count beside the file entry.
It is the size of `CircleCore.receiptAuthors(st.events, dropId, sha256,
st.members)` over the same candidate drop ids: a set of current-member
fingerprints, one per author, not a per-recipient status list. The existing
`status is local-only, never gossiped` wording stays on the send statuses; the
count is derived from the signed log and does not change them. The limits on
what can be counted are in §10.2.

### 2.2 Identity, environment and exit codes

- Identity: `~/.config/flit/flit-harness/identity.p12`, generated on first use
  and stable across rebuilds and updates (Harness.kt:82-100). Override the whole
  home with `FLIT_HARNESS_HOME`. Keeping this file is what lets previously
  trusting peers keep trusting the box after an installer upgrade.
- `FLIT_MODEL` sets the advertised `deviceModel`; `FLIT_TRACE=1` prints a stack
  trace for the top-level error handler instead of just
  `!! <message> (set FLIT_TRACE=1 for a stack trace)`.
- Exit codes, consolidated: **1** = generic top-level exception / no files found
  / bt-sim needs input / an unexpected `parcel` throwable; **2** = bad `--relay`
  address, a non-loopback relay bind with a missing or weak `--token`, a bad
  `--park-timeout`, `discover` found nobody, `info` got no answer, bt-sim `/info`
  got no answer, bad courier argument or missing `--bag`, or bad `parcel`
  arguments / missing `--cert`, `--dir` or `--out`; **3** = at least one file failed to send,
  a courier parcel failed `open`, or `parcel` verification failed. Normal
  `exitProcess(0)` is implicit. The courier-specific codes are
  `EXIT_BAD_ARGS = 2` and `EXIT_OPEN_REFUSED = 3` (§2.4); `parcel` maps a
  `ParcelCliFailure` to 2 or 3 and lets other throwables reach the top-level
  handler (§11).

### 2.3 Differences from the README summary

1. `bt-sim --no-tls` is implemented (`BtSim.kt:49`) and appears in the `usage()`
   line at `Harness.kt:202`; `/info` then reports `http`. It is a real,
   supported flag.
2. The README list shows a reduced flag set (no `--alias`, `--port`, `--udp-port`,
   `--http`, `--announce-to`, `--dest`, `--sender`, `--circle`/`--invite` on
   `serve`, no `--alias` on `discover`/`send`, no per-subcommand Circle flags),
   and points to `flit-cli` with no arguments for the complete list. `usage()`
   prints the command lines — including `--no-tls`, the `courier` flags and the
   `parcel` line — so the README's reduced set is a summary only.
3. The README documents `relay --port 53318` as if it were a literal default in
   the help text; the code computes it as `Protocol.DEFAULT_PORT + 1` and the
   help string shows `--port P`. The effective default is still 53318.
4. The prepare-gate flags `--only-trusted`, `--qr-only`, `--pinned` and `--known`
   are accepted by `serve` and listed in `ParsedArgs.VALUE_FLAGS`, but they are
   not printed in the no-argument help line. §2.1 is the reference for them.
   The `relay` help line does include the new `--host IP`, whose default is
   loopback.

### 2.4 Courier command reference

`courier` is the CLI carrier for sealed parcels. It holds opaque envelopes on
disk and moves them between machines as a directory copy; it adds no route,
header or wire field to the v2.2 surface and implements no transport of its own.
Encryption and the parcel codec stay in `protocol/`; the CLI owns storage,
state transitions and status output in `CourierCli.kt`. This layer has no device
walk — a bag moves by file copy on one box, and its commands are one-shot like
`circle` (load state, do one operation, save state). The subcommand list comes
from `courierUsage()`.

| Subcommand | Flags | Effect |
| --- | --- | --- |
| `drop` | `--to FP` plus `--text NOTE` **or** `--file PATH`; optional `--circle ID`, `--cert B64`, `--ttl MS`, `--bag DIR` | seal a signed Circle event into an `<id>.parcel` in the bag for an absent member; add a `pending` row |
| `carry` | `[--bag DIR]` | verify the local bag, then mark its rows `carried` |
| `receive` | `--bag DIR` | open parcels addressed to this identity and fold opened events into local Circle state |
| `status` | `[--bag DIR]` | list rows (id, recipient, state, expires) with a closing count by state; no parcel content |
| `sweep` | `[--bag DIR]` | drop expired parcels and enforce the bag quota |

Flags taking a value, added to `ParsedArgs.VALUE_FLAGS`: `--bag`, `--to`,
`--cert`, `--ttl`. `--text`, `--file` and `--circle` are the existing Circle
flags reused by `drop`. `--to FP` is the recipient fingerprint; the sender is
the CLI identity supplied by `loadOrCreateIdentity()`, the same fingerprint
`serve` prints and `circle` uses. `--cert B64` supplies the recipient's Base64
DER out of band when its signed event has not reached `circle.state`; the
certificate fingerprint is checked against `--to` before sealing, so a parcel
cannot be addressed to one fingerprint and encrypted to another. `--ttl MS`
injects the current time (`nowMs`) in milliseconds for sealing and expiry
checks — it is not a lifetime. A positive integer is required; without it the
CLI uses `System.currentTimeMillis()`, and a non-numeric or non-positive value
exits 2. Expiry still comes from the carry layer's bounded `MAX_PARCEL_TTL_MS`
(24 h), derived once as `expiresMs = createdMs + TTL` inside that limit.
`--bag DIR` overrides the bag directory: an absolute path is used as given, a
relative path is rooted at `<identity home>` (`harnessHome()`); with no `--bag`
it is `<identity home>/courier-bag`. `receive` takes the bag copied in from
another machine.

On-disk layout, all under the identity home (`harnessHome()`, overridable with
`FLIT_HARNESS_HOME` to separate nodes on one box):

| Path | Contents |
| --- | --- |
| `courier.state` | local-only bookkeeping, one JSON line per parcel; decoded like `circle.state`, so an absent, short or corrupt file yields an empty list |
| `courier-bag/` | one `<envelope-id>.parcel` file per parcel; the portable handoff unit an operator copies to the next machine |
| `courier-received/` | opened event JSON written after a successful handoff; evidence of what landed, never re-sent |
| `courier-rejected/` | parcels whose `open` returned a refusal; quarantined, not folded and not re-exported |

A state row is `{id, recipientFp, senderFp, circleId, state, createdMs,
expiresMs}`. `id` is the envelope id, fingerprints are normalised uppercase
hex, and `state` is one of `pending`, `carried`, `delivered`, `expired`,
`refused`. Rows are local-only and are never written into a Circle event.
Every `.parcel` file id matches a row in `courier.state`; a file with no row is
ignored by `receive` and `sweep`. The carrier stores
`CourierCarryCore.encode(CourierCarryCore.parcelOf(envelope))` byte-for-byte, so
encode→decode→encode of an unopened parcel is identical.

Transitions (`CourierCli.Kind`):

| Event | From → To |
| --- | --- |
| `CARRY` (after the bag verifies) | `pending` → `carried` |
| `RECEIVE_OK` | `pending` or `carried` → `delivered` |
| `RECEIVE_REFUSED` (`open` returns a refusal) | any state → `refused`; file moved to `courier-rejected/` |
| `SWEEP` when `nowMs >= expiresMs` | any state → `expired` |

A thrown `open` is not a conclusion: the row stays `pending` and the file stays
in the bag for a later attempt, the same carrier-death rule Circle file delivery
uses. A duplicate envelope id folds once through `CircleCore.dedup` and moves
straight to `delivered`. A parcel addressed to another fingerprint stays
`pending` and remains in the bag for the next courier: that node carries it but
is not its recipient. `sweep` deletes a file only when its row is `expired` or
`refused`; a `delivered` row keeps its file in `courier-received/` as a local
record. State writes are one-shot and whole-file, as `saveCircleState` does.

`status` reuses the `circle` one-line style with `${ts()}`: one row per parcel
with a padded state value and a closing count by state. No fingerprint beyond
the short form and no parcel content is printed.

Courier exit codes: `EXIT_BAD_ARGS = 2` for a bad argument or a missing or
unreadable `--bag` (no state change), and `EXIT_OPEN_REFUSED = 3` when a parcel
fails `open` (row `refused`, file moved to `courier-rejected/`). Otherwise the
consolidated codes in §2.2 apply and a normal run exits 0.

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
   first (it binds `127.0.0.1` unless `--host IP` is given), then
   `flit-cli serve --dir … --relay 127.0.0.1:53318 [--token T]`. A non-loopback
   bind needs a strong token; parked subscriptions wait 300 s by default
   (`--park-timeout S` changes that). There is no direct listener and no announce;
   reachability is only through the subscription.
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
pinned; the decision never uses an IP (FlitEngine.kt:1241-1263). Logs are
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
(FlitEngine.kt:3498-3502); when set it logs `egress pin skipped
(debug.flit.nopin)`. It is not a user setting.

## 5. Logs and what they mean

### 5.1 App log (`adb logcat -s FlitSend:I`, in-app last 60 lines)

`FlitEngine.logLine()` prefixes `HH:mm:ss`, writes `Log.i("FlitSend", text)`
and keeps the last 60 lines in memory (FlitEngine.kt:3483-3487). A log line is
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
| pad writes and reads | a pad put/del is one more signed event in the Circle log and shows up in the ordinary sync/host lines; `PadCard` publishes its `PadView` through the content-diff signature, with no separate pad log line |
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
| `HH:mm:ss prepare gate: onlyTrusted=<bool> qrOnly=<bool> pinned=<N> known=<N>` | `serve` started with at least one prepare gate on; both gates off prints no such line |
| `HH:mm:ss offer refused <alias> (<ip>): <reason> code=<12>` | the prepare gate in the delegate refused an offer; the log carries only a short code, never the certificate or raw claim |
| `HH:mm:ss fingerprint <fp>` | this CLI's stable identity; pin this on the phone |
| `HH:mm:ss hidden: not announcing — …` | hidden serve; expect the phone's rescue sweep, not multicast, to find it |
| `HH:mm:ss circle <id> invite=<token> — join with: …` | Circle host seeded; copy the join command |
| `HH:mm:ss relay subscription host:port dir=<abs>` / `relay: <error> — retry in 2s` | relay-only receiver subscribing / reconnecting after a dropped pair |
| `HH:mm:ss seen <alias> (ip:port) type=… proto=… v=…` | a peer registered (or answered an announce); deduped by fingerprint |
| `HH:mm:ss offer from <alias> (ip): N file(s)` + indented `- <id> → <name> (<size> bytes, …)` | prepare; with both gates off the harness **auto-accepts**; with a gate on it accepts only after the cert binding and pinned/known checks pass |
| `recv-start <name> id=<id>` | upload byte stream began |
| `recv-resume <name> from <bytes> bytes` | attach to a kept prefix; the number is the exact `/partial` answer |
| `recv-ok <name> → <path> (<bytes> bytes)` / `recv-ok … streamed` | hash-exact landing (classic two-step / direct-to-storage) |
| `recv-BAD <name> checksum mismatch` / `recv-ERR <name>` | hash gate failed / write error |
| `session <id8> finished|cancelled` | transfer session ended; cancelled is an explicit end, not a crash |
| `HH:mm:ss receipt <id8> → … event(s), … member(s), gen=…` | signed receipt published through the same `pushCircleEvent` line every other Circle verb uses |
| `circle pad put` / `circle pad del` / `circle pad show` | write publishes a signed pad event through the existing Circle fan-out; `show` prints one line per live `PadView` key in key order |
| confirmed count on `circle sync` / `circle file` | confirmed-author set size from `CircleCore.receiptAuthors` for a keyed drop; local send statuses stay unchanged |
| `panic from <fp12> (ip): <text> [hops=N]` | signed alert arrived; `hops` is inside the signed canonical body |
| `relaying on <host>:<port> token=set|none` | relay listening on the bound address (loopback by default); token gate on/off |
| `!! refusing non-loopback relay bind on <host>: <problem> token (use a strong --token or bind loopback)` | relay refused at startup; `<problem>` is `missing-token` or `weak-token` (exit 2) |
| `pair c3 + c4` / `pair c3 + c4 closed` | opaque pairing ticket ids only — deliberately no fingerprint/byte count/content |
| `!! <message>` (+ `set FLIT_TRACE=1 for a stack trace`) | top-level failure; exit code 1 unless a command-specific code applies |
| `bye` | graceful shutdown hook ran (serve, relay) |

The meaning of `recv-resume`, `kept` and the hash gate as *failure conclusions*
is detailed in [FAILURE-MODES.md](FAILURE-MODES.md); the relay's blindness is a
security property in [SECURITY.md](SECURITY.md).

### 5.3 Courier carry lines and handoff outcomes (app)

`parcel` rows are filtered out of the Circle feed; a verified `PARCEL` is folded
on the path `FlitCircleSync.fresh() → persistEvents() → FlitCircleStore.upsert`
and the decrypted `file` event then renders as an ordinary file card — there is
no separate `merge` at this layer. When a handoff entry exists for that SHA-256,
`FileCard` adds one carry line above its status line. The entry is read from
`FlitEngine.courierHandoffs` (process-local, keyed by SHA-256), never parsed from
a `parcel` body or envelope text; a blank SHA-256 draws no line. Outcomes update
on the 30 s courier tick and publish through the content-diff signature without
a log change.

The line depends on the viewer and the courier-local outcome:

| Viewer | Outcome | Line |
| --- | --- | --- |
| Courier (`meFp == courierFp`) | waiting | `Carrying for <to> · waiting for a handoff` |
| Courier | failed | `Carrying for <to> · handoff will retry` |
| Courier | handed | `Handed off to <to>` |
| Recipient (`meFp == toFp`) | any | `A courier carried a copy for you` |
| Other member | any | `<courier> is carrying a copy for <to>` |

`<to>` and `<courier>` resolve to aliases bounded at `MAX_ALIAS_CHARS = 64`.
Blank or unknown outcomes read as `waiting`. Only the exact `handed` string,
read on the courier's own device, produces `Handed off to …`; a refusal or a
thrown carrier produces `failed`, never that line, and no other device sees it
because the outcome is not gossiped.

Scope of the line:

- It reports custody, not delivery. A carry line never means bytes landed.
  The Open affordance is separate and keyed to the landed-SHA set: `LANDED`
  shows `Landed here` with an enabled `Open file`; `RESOLVING` shows a disabled
  `Opening…` button; `ABSENT` shows `Not here yet · no local copy to open`.
  A landed SHA keeps `LANDED` even while a carry line is present.
- It does not move delivery counts. Counts come only from
  `CircleDelivery.statuses`; a handoff never increments a sent count.
- It carries no file name, size, path, envelope text, `fromFp` or recipient key
  — only recipient and courier aliases and the outcome.
- It is process-local: not persisted, not signed and never placed in a
  `CircleRequest`. A restart clears the map; a card with no entry keeps its
  ordinary status line (`local · N sent · M pending · …`, or
  `file drop · open to view if it arrived` with no delivery record).
- A courier that cannot open an envelope has no SHA-256 to key a row with, so it
  publishes nothing and the card reads `waiting`; ciphertext stays opaque to
  the carrier.

App log lines under the `FlitSend` tag that accompany these states:

| Line | Meaning |
| --- | --- |
| `courier drop: no certificate for <fp>` | drop refused; source events stay in the sender's log |
| `courier: invalid parcel body` | body gate refused the event before fold; bag unchanged |
| `courier: parcel signature refused` | verify failed; bag unchanged |
| `courier: parcel expired` | outside window or past TTL; no bag fold |
| `courier: duplicate parcel id` | duplicate envelope id; held copy stays |
| `courier: bag full, 256 parcel(s) held` | quota reached; incoming offer dropped |
| `courier: holding for <fp>` | addressee not met on this pass; held for later |
| `courier: handed off N parcel(s) to <fp>` | OK answer; offered ids acked, outcome `handed` |
| `courier: handoff failed to <fp>` | refusal or carrier throw; bag kept, outcome `failed` |
| `courier parcel unreadable — rejected` | open failed or expired at the addressee; no fold |
| `courier: bag row unreadable, starting empty` | corrupt `courierBag` row decoded as empty |

The three states and their copy are pinned by pure card tests (CODE/PURE only).
No phone-to-phone courier walk is recorded on 2026-12-01; the three-node walk
is dated 2026-12-02.

## 6. Walkthrough recipes

Each recipe states what is proved and what is not. Commands are the ones the
rig used; substitute addresses/ports for a different network.

### 6.1 Green rig before a commit

```bash
source env.sh && ./gradlew :protocol:test :core:test :app:testDebugUnitTest :app:assembleDebug \
    :app:assembleRelease :harness:installDist
```

Projected/last-green inventory: 972 tests (443 protocol + 167 core + 254 app +
108 harness) from the last recorded four-module test XML, with zero failures,
zero errors and zero skipped cases. The earlier four-module projection (362 +
112 + 212 + 92) and the three-module split (362 + 324 + 92) are historical, both
778; the current split adds the security-wave cases. The release assembly is green after the R8 keep-rule fix; no finished-release claim follows from it. CI runs the same task set at
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

`relay` now binds `127.0.0.1` by default; the recorded walk used that address.
The token gate is unchanged on the wire, and a parked subscription now times out
after 300 s unless `--park-timeout` says otherwise.

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
flit-cli circle join --host 127.0.0.1:53317 --circle <id> --invite <token> \
  --host-fp <fingerprint printed by serve>
flit-cli circle post --text "hello from the box"
flit-cli circle poll --text "where?" --option home,away
flit-cli circle vote --option 0
flit-cli circle sync
```

`poll` keeps every `--option` occurrence and also accepts one comma-separated
`--option A,B,…` value (`ParsedArgs.opts`); the older overwrite behavior was
replaced. `vote` takes a single integer `--option N` (Harness.kt:1623-1638).

On the phone, join by pasting a QR v3 code or tapping a `flit://add?v=3&…` link
(the VIEW intent starts sharing and joins). Evidence recorded: inbound sync
`circle sync: +1 event(s) from AFF5EBF981DE` rendered with author and timestamp
in the M3 feed; poll votes changeable with tally flipping 0→1; full leave logs
`circle: left fully — key discarded` and the feed disappears. The phone is a
full host (`FlitCircleHost` behind `onCircleRequest`): it verifies each event,
gates the roster, admits JOIN only with the right invite, rotates the key on
departure and refuses later syncs from departed fingerprints.

The 2026-11-26 three-node baseline walk **is** device evidence for a 3-identity
Circle (phone host plus two box CLI members on separate harness homes): join,
posts, poll/vote, a hash-exact file drop and kick/rekey were recorded from
logcat, UI dumps, CLI output and both `circle.state` files
([three-node baseline](completions/2026-11-26-three-node-baseline.md)).

**Not device-proven:** outbound phone→host fan-out from the app. The behavioural
tests pass with an injected carrier, and in the three-node walk a phone-authored
message reached the phone feed and local log but no one-shot CLI puller saw it,
so the direction remains test-evidenced only
([circle.md](circle.md#known-limitations)). A file drop from the CLI uses
`circle file --file PATH` (bytes via `sendFiles`); `circle post --file PATH`
announces only.

Delivery receipts are not part of this walk. `circle receipt` is published from
the CLI and read back as a confirmed count from the signed log; neither the
command nor the count was observed on the phone, so receipts carry harness
evidence only (§10.2). A confirmed count is not a phone-side landing claim.

### 6.9 Panic alert (on-device evidence)

```bash
flit-cli panic 192.168.100.80:53317 --text "check in"
```

The box logs `panic from <fp12> (ip): <text> [hops=0]`; a phone-sent alert
produced a box log with the sender fingerprint, and a box-sent alert produced a
phone banner with alias, note and safety copy. Acknowledge / Dismiss / Forward
are explicit; Forward re-signs with the forwarder's identity while `originFp`
preserves the author, and `forward` refuses the next hop when `hops + 1 >= 3`
(`DEFAULT_MAX_HOPS = 3`), i.e. returns null at `hops = 2`
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

### 6.12 Circle pad (CLI and app surface, no walk)

The 2026-12-16 integration adds a CLI surface and an additive phone surface for
the pad bounded in §10.3. It carries no device walk: the commands are one-shot
CLI operations, and the phone card is source- and unit-test-evidenced. The
full-feature walk dated 2026-12-17 is not claimed here.

```bash
flit-cli circle pad put --key note --text "first line"
flit-cli circle pad show
flit-cli circle pad del --key note
flit-cli circle pad show
```

`put` and `del` need a joined `circle.state` and post a signed `pad` event on
the existing `/circle/join` and `/circle/sync` routes; the bodies come from
`PadCore.padPutBody` and `PadCore.padDelBody`. `show` folds the local log into a
`PadView`, prints one line per live key in lexicographic order and omits
tombstoned keys. A key is at most 128 characters, text at most 4096, and a view
holds at most 256 live keys; a blank or oversized field is refused before
signing. On the phone the card is an additive entry in the Circle surface: the
engine holds a derived `PadView` in a StateFlow, rebuilt on each store fold, and
`PadUi.rows` lists the same live keys in key order. No phone observation is
part of this recipe.

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
| `!! blank pad key` / `!! pad key too long` / `!! pad text too long` | `PadCore` refuses before minting an event | a pad key must be non-blank and at most 128 chars; put text must be non-blank and at most 4096; shorten and retry |
| `!! pad put body too long` / `!! pad del body too long` | body gate refused the write | a quoted key and text must stay under `CircleCore.MAX_BODY_CHARS = 16 KiB`; no partial line is published |
| Pad line missing from `circle pad show` or the phone card | no error line; the view is rebuilt from the log | the key may be tombstoned by a newer `del`, its event may be for another circle or have a malformed body, or it may have aged out of the 500-event log; retry `show` after a sync |
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
- `bt-sim`, `discover`, `send`, `info`, `circle`, `courier`, `parcel`, `panic`,
  `parse-qr`: one-shot; they exit on their own. `discover` with no peers exits 2
  by design; so does `courier` with a bad argument or missing `--bag`, and
  `courier receive` exits 3 when a parcel fails `open`. `parcel` exits 0 on
  success, 2 on bad arguments or a missing `--cert`, `--dir` or `--out`, and 3
  on verification failure (§11).
- Phone: tap **Stop** in the app (or the Quick Settings tile, which calls
  `stopService` and reaches the same path). This clears `sharingDesired`, so a
  later relaunch stays stopped. `FlitService.onDestroy()` also clears the row
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
| `<identity home>/courier-bag/` | `courier drop` | the portable bag (`<id>.parcel` files); copy it wholesale to hand off, or let `sweep` remove expired/refused files |
| `<identity home>/courier-received/` | `courier receive` | opened event JSON, a local record of what landed; never re-sent |
| `<identity home>/courier-rejected/` | `courier receive` | quarantined parcels that failed `open`; kept locally, not folded or re-exported |
| `<identity home>/courier.state` | `courier` subcommands | local-only row bookkeeping; delete to drop all local rows, leaving bag files untouched |
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
- **Release verification (plan):** a bundle with the signed APK, the CLI zip
  and `SHA256SUMS.txt` is planned, not assembled here. No release zip or
  checksum file exists in the tree. The local APK verifies against the release
  key; CI release builds fall back to debug signing.
- **Provenance:** development was private; the repository is published with its
  full commit history (scaffolding, milestones, regressions and fixes), not as
  a reconstructed snapshot. The latest released tag is `v0.3.1`; `v0.3.0` is an earlier
  release point. The current in-tree version is `0.3.1`, and `v0.3.2` is planned for the
  security closeout wave. Build files still declare `0.3.1`; no `v0.3.2` release
  has been assembled here.
- **Store metadata:** `fastlane/metadata/android/en-US/` holds title,
  short/full description and `changelogs/1.txt` ("First release").
  `RELEASE_NOTES.md` carries a planned v0.3.2 security-closeout section, the
  current v0.3.1 section, the historical v0.3.0 section, the v0.2.0 notes and a dated
  2026-11-25 correction: the original "no new settings/vocabulary" copy was
  superseded by the opt-in **Transfer over Bluetooth** option on 2026-11-24.
- **Version identity:** namespace/applicationId `app.flit.share`, versionName
  `0.3.1`, versionCode 4, minSdk 26, compile/targetSdk 36. The app is
  `allowBackup=false`; identity lives in `filesDir/flit-identity.p12`.
- **Play listing / data safety:** zero collection, no accounts, no cloud, no
  ads, no telemetry, no phone-home SDKs; see
  [play-listing.md](play-listing.md). The clean-room status (protocol
  specification as reference only, no LocalSend source or assets) is stated in
  the [README](../README.md#licensing--provenance-note).

## 10. Known limits

- The `courier` CLI carrier has no device walk at this layer: a bag moves by
  directory copy on one box, and the handoff is covered by harness tests only
  (§2.4).
- The app's courier carry lines (§5.3) have no device walk on 2026-12-01: the
  `waiting`/`handed`/`failed` states and their copy are pinned by pure card
  tests (CODE/PURE). A carry line reports custody, not delivery, and the
  `handed` outcome is read only on the courier's own device. The three-node
  courier walk is dated 2026-12-02.
- Two-phone RFCOMM transfer was not walked — no second phone on the rig, no
  Bluetooth adapter on the box; `bt-sim` covers the exchange over a pipe. The
  2026-11-26 three-node baseline **was** walked as device evidence for a
  3-identity Circle (phone host plus two box CLI members); no live multi-phone
  mesh was available.
- Wi‑Fi Aware is absent on the rig phone; milestone B is parked by a hardware
  gate.
- The phone→host Circle fan-out direction remains test-evidenced only: the
  three-node walk saw a phone-authored message stay in the phone feed and local
  log, with no one-shot CLI puller returning it.
- Grant release on delivered SAF batches is code-verified, not device-walked.
- MIUI autostart/battery behavior and Android 15's ~6 h `dataSync` cap are ROM/OS
  limits outside the app; no background guarantee is claimed.
- Where the README CLI summary and the actual `flit-cli` help/arg parser disagree
  (§2.3), the help text and parser decide.
- Manual air-gap carry by QR frames is an operator procedure with its own size,
  acknowledgement and evidence limits (§11); no device walk is part of it.
- The signed JOIN proof, the officer-only rekey gate and the `--host-fp`
  host-answer pin have no device walk: they are JVM-pure and in-process
  harness-evidenced only ([TESTING.md](TESTING.md#4-test-inventory-by-module-and-class),
  §4.8).

### 10.1 Keyed Circle drops (operator limits)

A file drop can carry a per-drop content key: a fresh 32-byte key and 12-byte IV
seal the file bytes with AES-GCM, and the key is wrapped once per current member
under that member's certificate. The signed `file` body binds the ciphertext
digest, IV and every wrap, so a carrier cannot alter them. The operator bounds
are `MAX_WRAPS = 32` recipients and `MAX_WRAPPED_CHARS = 512` characters per
wrapped value. The limits and refusal lines below are the implemented post-time
and receive-time behaviour (CODE, PURE and SIM-HARN evidence): no device walk was
made for keyed drops on 2026-12-10, and no keyed-drop landing on a phone is
claimed here.

- **A keyed drop needs a current certificate for each member it is meant to
  reach.** Wrapping uses the certificate held for a member at post time
  (`TlsIdentity.pinned(fp)` or the member's certificate in the folded log). A
  member with no usable certificate gets no wrap and no fallback: there is no
  group-key path, no escrow and no later backfill. When no recipient has key
  material, the sender keeps the source spool and posts no event — the app logs
  `circle drop: no recipient key material — not posted` and the CLI prints
  `!! no recipient key material — nothing posted`. A member who joined after the
  drop sees the card but no bytes; a member who left before the drop has no wrap
  in that drop.

- **An over-cap roster is refused, never trimmed.** `MAX_WRAPS` is 32. A member
  list above the cap is refused at post time with no event — the app logs
  `circle drop: N recipients exceeds key wrap cap` and the CLI prints
  `!! <N> recipients exceeds key wrap cap — nothing posted`; the separate
  `!! no recipient key material — nothing posted` line is used only when no
  member has a usable certificate — and the source spool is kept. Wraps
  are not silently dropped to fit.

- **A published drop cannot be revoked on another device.** Leaving the circle,
  a group rekey, or a fresh key generation for another drop does not change an
  already-signed drop body. A member who kept a wrap can still open that old
  drop after leaving; there is no remote revocation, remote delete or wipe, and
  no wrap backfill for a later joiner. Removing the drop locally, or deleting
  `circle.state`, affects only the local copy and the local key material — it
  does not withdraw bytes already landed elsewhere. On the receive side a
  missing wrap, a GCM tag failure or a plaintext digest mismatch yields no ledger
  entry and leaves no plaintext: the app logs
  `circle drop: cannot open <name> (<reason>)` with a local `NO_KEY` status, and
  the CLI prints `drop-refused <name>: no wrap` or
  `drop-refused <name>: open failed`. A successful keyed landing prints
  `drop-open <name> <plainSha> (<bytes> bytes)` on the CLI.

The keyed body shape is in [CONTRACTS.md](CONTRACTS.md) and the refusal
conclusions in [FAILURE-MODES.md](FAILURE-MODES.md).

### 10.2 Delivery receipts (operator limits)

A delivery receipt is one signed Circle event in which a member acknowledges
that a named file drop landed in that member's own receive path. The statement
is local to its author. It is not a delivery guarantee for the drop: a count
of N means N current members each reported the same `(dropId, sha256)`, and it
says nothing about members who did not report. The 2026-12-11 protocol layer
defines the event and the roll-up; the 2026-12-14 integration wires the app's
`FlitCircleSync.circleBodyValid` RECEIPT branch and a `confirmed` value in
`CircleUi.computeFeedData`, mints the receipt after a keyed landing through
`postReceipt`, and adds the `circle receipt` CLI command
([receipts-parity.md](roadmap/receipts-parity.md)). No device walk of receipts
is recorded on either date: the command and the count are CODE, PURE and
harness-evidenced, not a phone observation.

- **A receipt is one member's evidence, not a group result.**
  `CircleCore.receiptAuthors(events, dropId, sha256, members)` returns a set of
  author fingerprints, not a per-recipient status list and not a statement
  that the whole Circle holds the bytes. A missing receipt is not a refusal
  and is not proof that a landing failed elsewhere: the event may still be in
  a courier bag or QR frame sequence, may not have synced yet, may have been
  refused at sync as stale, malformed or forged, or its author may have left
  the current-member set.

- **A receipt takes no remote action.** It moves no bytes, opens no file,
  deletes or revokes no copy, sends no command and pushes no status to another
  member. Its only effect on another member is one more signed event in the
  bounded Circle log. Sender-local `CircleDelivery.statuses`
  (`pending`, `sent`, `refused`, `failed`) stay local-only and never gossiped;
  on 2026-12-14 a confirmed author is folded to `SENT` with the existing
  `setDeliveryStatus` reducer, and no phantom delivery row is created. A hash
  mismatch at the landing mints no receipt, so a failed transfer produces
  silence, not a negative acknowledgement.

- **The count has a narrow, bounded definition.** A receipt counts only when
  its body is exactly `{dropId, sha256, keyId}`, every value a non-blank
  bounded string, its signature verifies with the certificate fingerprint
  bound to `authorFp`, and its author is in the normalised current-member set.
  `dropId` is the `id` of the `file` event and `sha256` its plaintext digest;
  both must match the drop being read. There is no `landedMs`: `createdMs` on
  the event is the acknowledgement time. At most one receipt per author
  counts, chosen by `CircleCore.ORDER` (latest wins), so arrival order cannot
  change the result. `keyId` is provenance only: a later rekey does not
  invalidate an earlier acknowledgement of the same content. No `path`, spool
  path, token, key material, file bytes or local status string is present in
  the body.

- **Confirmation-count limits.** The count is the size of a deduplicated set,
  so it never exceeds the number of current members and cannot be raised by
  repeating a receipt: one author contributes at most one entry, and a
  departed or non-member author contributes none. A receipt for an unknown
  `sha256`, a mismatched `dropId`, a duplicate id or a `createdMs` outside the
  replay window leaves the count unchanged. Each of the three body values is
  bounded by `MAX_FIELD_CHARS = 256` and the whole body by `MAX_BODY_CHARS =
  16 KiB`; the shared bounds are `REPLAY_WINDOW_MS = 6 h`, `CLOCK_SKEW_MS =
  5 min`, `MAX_SEEN = 4096`, `MAX_EVENTS_PER_REQUEST = 1024`,
  `MAX_REQUEST_BYTES = 8 MiB` and the app's `EVENT_CAP = 500`. No new cap is
  introduced. Membership is read at fold time and the log is bounded, so the
  count can fall when a member leaves or an old receipt ages out; it is a
  snapshot of signed events currently in hand, not a durable total.

- **The usual Circle bounds still apply.** Receipts are ordinary events in
  `CircleRequest.events` on the existing `/circle/sync` route, or inside a
  courier or QR parcel that already carries signed events. No new route,
  header, DTO field, frame or v2.2 status is added. They share the replay
  window, skew, seen set, request caps and log cap with every other verb. Old
  receipts can age out of the bounded log, and a receipt still in a bag or
  frame sequence has not been folded yet.

- **Evidence is structural, with harness coverage and no device claim.** The
  body gate, signature path and roll-up are CODE and PURE (protocol tests).
  The 2026-12-14 app gate, card count and CLI `circle receipt` path are covered
  by unit and harness tests; no phone walk, confirmed-count figure from
  hardware, timing or throughput number is claimed here. See
  [CONTRACTS.md](CONTRACTS.md) for the body shape, [SECURITY.md](SECURITY.md)
  for the signature and pinning rules, and
  [FAILURE-MODES.md](FAILURE-MODES.md) for refusals.

### 10.3 Circle pad (operator limits)

A Circle pad is a line-oriented text view rebuilt from signed `pad` events in the
bounded Circle log. `pad` is the twelfth `CircleEvent` verb and the pure fold
lives in `PadCore.kt`. The 2026-12-15 layer defined the verb and its bounds; the
2026-12-16 integration adds the CLI commands and the additive phone surface.
Neither adds a route, header, DTO field, store field or v2.2 shape: pad events
travel over the existing `/circle/join` and `/circle/sync` routes through the
existing fan-out and repair path. No device walk is part of either layer, and no
phone observation of a pad is claimed here. The CLI is covered by harness tests;
the phone surface is CODE and PURE.

- **The view is bounded to `MAX_LINES = 256` live keys.** `PadView.lines` is
  sorted lexicographically by `key`, omits tombstoned keys and holds at most 256
  entries; a pad with more live keys cannot be shown in full by one view. A line
  key is at most `MAX_KEY_CHARS = 128` characters (below
  `CircleCore.MAX_FIELD_CHARS = 256`), and its text at most
  `MAX_TEXT_CHARS = 4096` characters. A put body still fits
  `CircleCore.MAX_BODY_CHARS = 16 KiB`. The body gate accepts
  exactly `{"op":"put","key","text"}` or
  `{"op":"del","key"}`; an extra key, missing key, wrong JSON type, unknown
  `op`, blank value or oversized field is refused before signing and before the
  fold.

- **The CLI writes one line per command and reads only the local log.**
  `circle pad put --key K --text T` and `circle pad del --key K` build their
  bodies with `PadCore.padPutBody` and `PadCore.padDelBody` and publish a signed
  event through the existing Circle fan-out. `circle pad show` prints one line
  per live `PadView` key in key order and omits tombstones; an empty view prints
  no pad lines. There is no bulk import, line-number addressing, remote read or
  host pull, so a member that was offline sees only what its own log holds.

- **The phone surface is derived, bounded and additive.** `FlitEngine.padView`
  is a `StateFlow<PadView>` rebuilt from `FlitCircleStore` events through
  `PadCore.view(events, circleId)` on every store fold; `refreshCircle()` includes
  a pad digest in its signature so a view edit publishes without a log change.
  `PadUi.PadRow(key, text)` and `PadUi.rows(view)` present the same lexicographic
  live lines, and `PadUi.keyError` / `PadUi.textError` apply the `PadCore` bounds.
  `PadCard(view, onPut, onDel, onClose)` renders an M3 list with edit and delete
  controls on 48 dp targets, reached through an additive entry in `CircleUi`;
  `MainActivity` collects the flow and supplies the callbacks. No second pad copy
  is persisted, and no route, header or DTO field is added. Rows and targets are
  CODE and PURE; no card is claimed to have been seen on a phone.

- **Evicted lines have no backfill.** The signed `pad` events in
  `CircleState.events` are the only durable pad state. `PadCore` keeps no state
  between calls and rebuilds every view from the events passed in; the app holds
  a derived `PadView` in a StateFlow but never persists a second copy.
  The log is bounded by `FlitCircleStore.EVENT_CAP = 500`, and pad, receipts and
  file drops share that cap with no per-type reservation, so a busy pad can push
  older events out. A device that was offline receives pad events through the
  courier carry core or the repair tick; a later joiner sees only what remains
  in the bounded log, with no reconstruction of evicted lines, the same limit as
  file drops. There is no archive, escrow or remote source to pull an evicted
  line back.

- **There is no remote delete beyond a signed `del` event.** A line is removed
  from a view only by a `pad` event whose body is the closed `{"op":"del","key"}`
  shape and whose signature verifies at the receiving route. Per key, the
  operation with the greatest `(lamport, authorFp, id)` order key wins: a `del`
  newer than the winning `put` tombstones the key, and a `del` older than it is
  inert, leaving the text in place. There is no remote wipe, bulk delete or
  command to erase a line on another device; removing or clearing a view locally
  changes only that device's copy. Verification and the replay guard
  (`FlitCircleSync.fresh`, then `CircleCore.accept`) run before the fold.
  `PadCore.view` applies `CircleCore.dedup`, so a repeated id counts once, but
  does not call `CircleCore.verify` and pins no certificate.

Pad events otherwise share the usual Circle bounds: `REPLAY_WINDOW_MS = 6 h`,
`CLOCK_SKEW_MS = 5 min`, `MAX_SEEN = 4096`, `MAX_EVENTS_PER_REQUEST = 1024`,
`MAX_REQUEST_BYTES = 8 MiB` and the `EVENT_CAP = 500` log cap. No new cap, route
or persisted field is introduced. The CLI commands are in §2.1 and §6.12 of this
document. See [CONTRACTS.md](CONTRACTS.md) for the body shapes,
[SECURITY.md](SECURITY.md) for verification and storage rules, and
[FAILURE-MODES.md](FAILURE-MODES.md) for refusals.

## 11. Manual air-gap carry (QR parcel)

A parcel moves one small named payload between two identities as an ordered
sequence of QR frames, with no network, Bluetooth, relay or Wi‑Fi Aware carrier
in the path. The frames are the only thing carried; the payload bytes never go
over a socket. The operator uses the CLI `parcel` commands and the app's two
parcel screens: **Show** cycles an outbound payload as QR frames, and **Scan**
reassembles an inbound payload from the camera (or a pasted frame list) and
lands it through the app's received-file path. The frame grammar, per-frame CRC
and size caps belong to the protocol codec (see
[CONTRACTS.md](CONTRACTS.md) for the grammar and
[FAILURE-MODES.md](FAILURE-MODES.md) for refusals).

The CLI surface is exactly two commands:

```
parcel encode <file> --dir DIR --to FP --cert PATH
parcel decode --dir DIR --out PATH
```

`--out` is a value-taking flag on `ParsedArgs`; `--dir`, `--to` and `--cert`
already are. Both commands are one-shot and offline.

### 11.1 Before you start

- Use the built `flit-cli` described in §3 and a phone with the parcel surface
  installed as described in §4. The two `parcel` commands come from the CLI
  layer (`docs/roadmap/qr-parcel-cli.md`) and the app surfaces from the app
  layer (`docs/roadmap/qr-parcel-app.md`); neither adds a build step.
- Have the recipient's certificate as a **Base64 DER file** for `--cert` and
  the recipient's fingerprint for `--to`. The fingerprint is the value the
  receiver prints (`serve` prints `fingerprint <fp>`; the app shows its own
  identity). The CLI parses the certificate, checks
  `TlsIdentity.fingerprintOf(cert) == --to` (normalised) before it renders any
  frame, and passes the certificate object itself to
  `ParcelCodec.encodeParcel`. There is no pin lookup and no self-addressed
  fallback: the file in `--cert` is the only source of the recipient key. The
  certificate is held in memory for that one encode call and is not copied into
  the frame directory.
- Pick a fresh, empty frame directory. `parcel encode` refuses a directory that
  already holds a `frame-*.png`.
- For a true air gap, turn Wi‑Fi and Bluetooth off on the phone and stop any
  `flit-cli serve` or `flit-cli relay` process on the box. ADB over USB is a
  control channel for fixture placement, screen capture and log reads; it never
  carries the payload bytes. Record the radio and process state before starting.
- Record the source hash with `sha256sum <file>`. No file name travels in a
  frame: the operator supplies the output name at decode with `--out`. The
  encoding adapter computes its own SHA‑256 over the file bytes and reports it
  as `sha256`; that is the value to compare at the far end.
- Exit codes: **0** success, **2** bad arguments or a missing `--cert`, `--dir`
  or `--out`, **3** verification failure on decode (no output written). An
  unexpected throwable is not mapped to a `parcel` code: it reaches
  `Harness.main`'s top-level handler and exits 1 (§2.2).

### 11.2 Box → phone (display and scan)

1. Encode on the box:

   ```bash
   flit-cli parcel encode <file> --dir <frames-dir> --to <FP> --cert <cert-path>
   ```

   Flags: `<file>` is the payload; `--dir` is the fresh frame directory; `--to`
   is the recipient fingerprint; `--cert` is the Base64 DER certificate file.
   The command returns `EncodeResult(dir, frames, sealedChars, sha256)` — the
   frame directory, the frame count, the sealed-character count and the
   adapter's SHA‑256 over the file bytes — and writes `frame-0001.png`,
   `frame-0002.png`, … in frame order. The signer is
   `loadOrCreateIdentity().fingerprint`; a fresh `parcelId` is generated for
   this encode and the certificate object itself is passed to
   `ParcelCodec.encodeParcel(payload, parcelId, recipientFp, cert, signerFp,
   identity)`. Each frame is one opaque UTF‑8 string rendered as a 768 × 768
   PNG with zxing `QRCodeWriter` at error correction M and a 2‑module margin;
   the adapter round-trips every frame string through zxing before writing it.
   Exit 0 on success; exit 2 for bad arguments or a missing `--cert`, `--dir`
   or `--to`.
2. Move the PNG directory to the display by physical copy, or show it on the
   rig monitor. No network path is involved.
3. Show the frames in file-name order (frame-0001.png, frame-0002.png, …) and
   open the phone's parcel screen in **Scan** mode. The camera preview feeds
   `Qr.decodeYPlane`; each decoded string is offered to `ParcelScanState`, which
   keeps the first copy of every `(parcelId, seq)` and ignores repeats. The
   progress label reads `frame i of n` from the descriptor's `seq`/`count` (`n`
   is `?` until the first frame is accepted). There is no acknowledgement
   channel, so run several full loops; the receiver deduplicates by frame index
   and finalizes once. If camera permission is denied, paste newline-separated
   frame strings into the paste field instead — they feed the same state
   holder.
4. When every expected frame has been accepted, `ParcelCodec.complete(frames,
   identity.fingerprint, identity)` verifies the whole payload with the phone's
   own `TlsIdentity`. The Scan screen then shows the signer fingerprint,
   SHA-256 and byte count, and writes `ParcelPayload.bytes` through the injected
   `(ByteArray) -> Unit` writer wired in `MainActivity` into the app's received
   bucket via the existing receive path — a normal received file with a ledger
   row under `Downloads/Flit/`. No file name travels in a frame: the local
   receive path supplies the saved name. Read the landed hash with
   `adb shell sha256sum <landed path>` and compare it with the source hash. No
   `/partial`, `queued`, `.part` or resume line belongs to this path; a partial
   scan closed early writes nothing.

### 11.3 Phone → box (capture and decode)

1. Place the fixture on the phone once with `adb push` into its Download folder
   and read its hash without moving bytes:
   `adb shell sha256sum <phone path>`. For the phone's encode step, supply the
   recipient (box) certificate and fingerprint as out-of-band fixture setup as
   in §11.1; neither travels in a frame.
2. Open the phone's parcel screen in **Show** mode and note the frame count.
   Show renders the frames returned by `ParcelCodec.encodeParcel(payload,
   parcelId, recipientFp, recipientCert, signerFp, identity)` as bitmaps through
   `Qr.encode` at error correction `M`, margin `2`, `PARCEL_FRAME_SIZE_PX = 240`.
   It advances one frame every `PARCEL_FRAME_INTERVAL_MS = 400L`, wraps from the
   last frame back to the first, and also offers next/back controls. Capture
   three loops into a fresh directory, one PNG per frame:

   ```bash
   for i in $(seq 1 $((3*N))); do
     adb exec-out screencap -p > <dir>/frame-$i.png
     sleep 0.5
   done
   ```

   `N` is the displayed frame count. The 0.5 s sleep is a capture convention,
   slower than the 400 ms dwell, which is why the walk takes several loops and
   the decoder deduplicates repeated indices; it is not a product display
   cadence.
3. Decode on the box with only the captured PNGs as input:

   ```bash
   flit-cli parcel decode --dir <dir> --out <output>
   ```

   Flags: `--dir` holds the captured PNGs; `--out` is the path for the opened
   bytes. No file name is carried in a frame, so `--out` is the only source of
   the output name. The command reads the PNGs with zxing `MultiFormatReader`,
   keeps the opaque frame strings, calls
   `ParcelCodec.complete(frames, identity.fingerprint, identity)` with the local
   identity, writes `ParcelPayload.bytes` to `--out`, and returns
   `DecodeResult(out, bytes, sha256, signerFp, recipientFp)` — the output path,
   the byte count, the payload hash and the two fingerprints. It prints the
   signer fingerprint, recipient fingerprint, SHA‑256 and byte count. Exit 0
   means verified and written; exit 2 means bad arguments or a missing `--dir`
   or `--out`; exit 3 means verification failed and no output was written.
4. Compare the printed hash with the phone hash, then `cmp` the output against
   the fixture still on the box. The fixture is never an input to the decoder.
   If no monitor or print surface can be placed in front of a camera, this
   direction cannot be run; record it as not verified rather than as a pass.

### 11.4 Limits of this procedure

- One payload per sequence, bounded by 64 frames of at most 512 bytes each —
  32768 framed bytes. The sealed envelope is itself bounded by
  `CourierEnvelope.MAX_ENVELOPE_CHARS` (12*1024 Base64 characters, about 9216
  raw bytes), so the practical ceiling is lower than the frame layer's
  defensive `MAX_AIR_GAP_PARCEL_BYTES = 131072`, which is never reached at these
  settings. An over-cap input is refused before any frame is written.
- Each frame carries a single opaque string (`FRAME_MAGIC = "FLITPARCEL1"`,
  `MAX_FRAMES = 64`, `MAX_CHUNK_BYTES = 512`, CRC32 per frame). The grammar is
  owned by the codec; an operator never edits a frame string, because an edit
  invalidates the CRC and the whole-payload digest.
- There is no acknowledgement, delivery receipt or retry coordination. The
  sender loops blindly and only the receiver knows which indices it has seen.
  A carry is verified by hash equality, not by a returned status, and no
  delivery count moves because of it.
- No partial state is durable. A killed decode writes no `--out` file; a killed
  encode may leave partial numbered PNGs in `--dir` and no progress record; a
  missed frame means starting the scan again. There is no `.part`, queue row,
  resume sidecar or `/partial` exchange in this path.
- Gate failures are refusals, not repairs. A CRC mismatch, missing index,
  digest mismatch, bad envelope signature or a recipient fingerprint other
  than the receiver's yields no output and no landed file; decode exits 3 and
  held frames stay in memory until the caller drops them. See
  [FAILURE-MODES.md](FAILURE-MODES.md).
- The parcel is sealed to one recipient fingerprint. A node that is not that
  recipient cannot open it and the carrier cannot read the contents; the
  certificate must arrive out of band and is not part of the frame directory.
- The CLI and app seams and the frozen codec constants define the carry; no
  camera walk, phone-to-phone air gap, real capture timing or throughput figure
  is part of it. The separate air-gap walk layer
  (`docs/roadmap/qr-parcel-device-walk.md`) records its own dated observation;
  the underlying facets carry CODE, PURE and SIM‑HARN evidence only.
- Frame and capture directories are scratch and may be deleted after hash
  verification; keep the identity files as described in §8.3.
