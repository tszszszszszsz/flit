# Testing and evidence

> Part of the Flit engineering docs. [Main ladder](transport-bluetooth.md) ·
> [Architecture](ARCHITECTURE.md) · [Operations](OPERATIONS.md) ·
> [Contracts](CONTRACTS.md) · [Failure modes](FAILURE-MODES.md) ·
> [Performance](PERFORMANCE.md) · [Security](SECURITY.md).

## 1. Evidence levels

Where several levels apply to a claim, the **weakest** one governs what may be asserted.

Rules applied here:

- No device walk is claimed that did not happen. A direction proved only by a
  test is written "test-evidenced only" and named in the limitations list.
- Where hardware was absent, the text says **not verified** rather than
  implying a green rig covers it. Current examples: phone-to-phone RFCOMM,
  a three-or-more-member Circle mesh, Wi-Fi Aware (feature absent on the rig
  phone), and outbound phone→host Circle fan-out as a device observation.
- Historical counts (30, 76, 86, 115, 121, 126, 145, 183, 231, 286) are
  snapshots of earlier commits, not the current inventory. The current figure
  is 302, last green at commit `72225f8`; the commit before it is `2034fa6`.
- The protocol module is JVM-pure by hard rule. The app module's unit tests are
  JVM tests of Android-free seams; they import no Android framework class and
  use no Robolectric (the note at `app/src/test/kotlin/app/flit/share/FlitSpoolTest.kt:11`
  says so explicitly). The harness is a Linux CLI (`flit-cli`), which doubles as
  the interop rig.

## 2. The rig at a glance

| Module | Role | Test count | Test task | Where it runs |
| --- | --- | --- | --- | --- |
| `protocol/` | Wire engine: JSON DTOs, UDP discovery, TLS/mTLS, HTTP server/client, relay core, Circle core | **82** | `:protocol:test` | JVM only (JDK 21 toolchain, `protocol/build.gradle.kts:6-8`) |
| `app/` | Android shell (Compose), `app.flit.share`, version 0.2.0, minSdk 26, compile/targetSdk 36 | **210** | `:app:testDebugUnitTest` | JVM unit tests of pure seams; no emulator, no device |
| `harness/` | Desktop companion `flit-cli`; the rig for interop, relays and Circle bots | **10** | `:harness:test` | JVM, in-process with real endpoints |
| **Total** | Three modules, three roles | **302** | see §3 | 0 failures, 0 errors, 0 skipped at last green |

Each module declares `testImplementation(kotlin("test"))` plus the JUnit
Platform launcher (`protocol/build.gradle.kts:23-24`,
`harness/build.gradle.kts:16-17`, `app/build.gradle.kts:108`) and configures
`useJUnitPlatform()` (`protocol/build.gradle.kts:27-29`,
`harness/build.gradle.kts:20-22`). The app has no separate release-unit-test
task in the rig; debug and release are only *assembled*.

Toolchain: JDK 21 lives at `~/.local/jdks/temurin-21` and `env.sh` puts it first
on PATH; Gradle 8.14.3 is pinned outside the repo. CI uses
`actions/setup-java` with Temurin 21 instead of `env.sh` (`.github/workflows/rig.yml:20-24`).
The reference peer for interop is the official `localsend-cli` v1.18.2 at
`~/.local/opt/localsend-cli` (README:133-134, `interop/README.md`).

## 3. Exact rig command

**Local pre-commit standard** — README "Dev quickstart" (README:125-131):

```bash
source env.sh && ./gradlew :protocol:test :app:testDebugUnitTest :app:assembleDebug \
    :app:assembleRelease :harness:installDist
```

**CI standard** — the same task set plus harness tests and the distribution
zip, with `--no-daemon --stacktrace` (`.github/workflows/rig.yml:35-43`):

```bash
./gradlew --no-daemon --stacktrace \
  :protocol:test :app:testDebugUnitTest :harness:test \
  :app:assembleDebug :app:assembleRelease \
  :harness:installDist :harness:distZip
```

Two notes about these commands:

1. The README command does **not** include `:harness:test`. The 302-test figure
   (which includes the 10 harness tests) is realized by the CI command, or by
   adding `:harness:test` locally. A local run of exactly the README line
   executes 292 tests (82 + 210) and still assembles the CLI.
2. `assembleRelease` falls back to the debug key when the local-only, gitignored
   `keystore.properties` is absent (`.github/workflows/rig.yml:36-38`,
   `app/build.gradle.kts:15-33`). That is a build-integrity check, not a
   release-signing verification.

**Verification record.** The three test tasks were run fresh
on 2026-11-25 in `/home/szszszs/flit` (JDK 21.0.13, Gradle 8.14.3):

```bash
source env.sh && ./gradlew --no-daemon --rerun-tasks \
  :protocol:test :app:testDebugUnitTest :harness:test
```

Result: `BUILD SUCCESSFUL`, **82 protocol + 210 app + 10 harness = 302 tests,
0 failures, 0 errors, 0 skipped**, read from the XML result files under each
module's `build/test-results/test/`. The `--rerun-tasks` flag was deliberate:
an earlier invocation reported all test tasks `UP-TO-DATE` and left XML from an
older class layout in place (see §8). The last green commit recorded in git is
`72225f8` ("Fix Circle Open file crash and sharing restore (302 tests green)").

## 4. Test inventory by module and class

Counts are from the JUnit XML reports after the fresh run; they agree with a
source-level `@Test` count. File names and class names differ in two places, so
the report is grouped by **class**: `DtoParityTest.kt` contains both
`DtoParityTest` and `TlsIdentityTest` (`protocol/src/test/kotlin/app/flit/share/protocol/DtoParityTest.kt:22,142`),
and `TlsIdentityTest.kt` contains the class `PinCacheTest`
(`.../TlsIdentityTest.kt:16`). Do not "fix" these by renaming without updating
this table.

### 4.1 Protocol — 82 tests

| Class | # | What it pins |
| --- | --- | --- |
| `ChunkTrackerTest` | 6 | Out-of-order pieces never fake completeness; overlapping retry merges; base is the prefix only; tidy/scrambled/random shuffles assemble exactly once. |
| `CircleCoreTest` | 12 | Create/sign/verify, forged-author rejection, fresh-id replay window, bounded seen set, file body `{name,size,sha256}` only, Lamport convergence under scrambled delivery, latest vote per member, kick tally, circle routes answer while v2 paths stay 404, default-refusing delegate and join/sync through `LinkServer`. |
| `CircleHardeningTest` | 11 | Declared bounds enforced, one bounded replay set with exact accept/fresh precedence, oversized fields never consume an id, cert bound to `authorFp`, extreme clocks cannot overflow the window, tallies ignore malformed JSON, strict file-body gate, delivery-order-independent dedup and kick-target resolution, exact status/reason for every refusal branch, v2.2 status behavior unchanged. |
| `DtoParityTest` | 8 | Announce/register/prepare DTOs match upstream fixtures and v2.2 shapes; multi-socket hint decodes; older receivers tolerate the hint; `WireJson` carries default-valued fields (`encodeDefaults = true`). |
| `TlsIdentityTest` | 2 | Fingerprint is uppercase hex SHA-256 of the certificate; identity survives a keystore round-trip. (Class lives in `DtoParityTest.kt`.) |
| `PinCacheTest` | 6 | LRU eviction at cap, read refreshes recency, normalized fingerprint keys and forget/clear, pinning through every entry point, re-pin idempotence, cert-in-hand verification never touches the shared pin store. (Class lives in `TlsIdentityTest.kt`.) |
| `LinkServerParityTest` | 4 | Full v2.2 flow over in-memory pipes in both TLS modes; a kept part survives receiver death and resumes hash-exact; a carrier death throws instead of reporting. |
| `PanicTest` | 6 | Created alerts verify, forged sender rejected, fresh id delivered once, hop limit stops forwarding, replay window rejects stale, v2 path stays 404, `sendPanic` reaches the delegate. |
| `PartialSidecarTest` | 1 | The `.part.token` sidecar exists **before** the first body byte — the restart-proof claim, tested with a blocked body stream. |
| `ProtocolRefusalTest` | 4 | Marked prepare decline carries the refusal header and concludes as a report; transient statuses still throw `TransferException`; aborted upload ends the receiver session as cancelled, not saved. |
| `ProtocolServerDoorTest` | 1 | An externally served `Link` answers with the listener's exact route semantics (the door relay and BLE share). |
| `RelayCoreTest` | 11 | Hello line round-trip, garbage refused, both pairing directions park, FIFO per-fingerprint isolation, same-mode never pairs, `withdraw` prevents ghost pairing, re-offer supersedes old park, optional constant-time token gate vs open relay. |
| `ResumeAndTurboTest` | 5 | Both `Content-Range` spellings (`bytes s-e/total` and `bytes=s-e/total`), restart probe/attach from a dead run's part, failed chunk claim leaves the part intact for plain resume, 409 for a resume header with no held base, `multi=true` hash-exact landing. |
| `StreamTlsTest` | 4 | `SSLEngine` shim handshake closes the loop both directions, multi-megabyte duplex round-trip byte-exact, half-open close yields a clean end, idempotent close and single bytes. |
| `TurboProgressTest` | 1 | Multi-socket progress is non-decreasing, bounded by total and ends exactly at total. |

### 4.2 App — 210 tests

All are JVM tests of Android-free seams: codecs, reducers, pure policy math and
rendered QR pixels. They never start the Android framework, an engine or a
radio.

| Class | # | What it pins |
| --- | --- | --- |
| `FlitBleTest` | 29 | BLE record codec (extended/legacy, fingerprint+alias, codepoint-safe clipping, garbage refused, both self-ghost kinds), arbiter (AP up dark, no-AP on, hidden listen-only, missing perm/dead radio off), rank rule, contextual ask book, 10-on/45-off duty constants and injectable phase, live-peer stop rule. |
| `FlitBluetoothTest` | 14 | Android 12 permission split and legacy API 26-30 set, all-grants rule, RFCOMM backoff doubling/cap/no overflow, retry logs, quiet cancellation, transient accept failure re-listens, bounded handshake. |
| `FlitCircleHostTest` | 19 | Wrong circle/unknown action refusals, invite + fingerprint gate on JOIN, signed-log return, outsider/blank SYNC refusal, verify-before-fold, cert cannot claim another fingerprint, wrong type/oversized/replayed skip, leave/kick/role governance and rekey invariants, departed member refused, replay roll-off, bounded log and roster, repeatable answers. |
| `FlitCircleStoreTest` | 25 | Store codec and tolerant decode (defaults, corrupt, duplicate, oversized trim, negative generation), roster folds (join/leave/kick/vote/role/rekey), generation saturation, per-fingerprint delivery records (upsert/status/remove) untouched by event folds. |
| `FlitCircleSyncTest` | 27 | Backoff escalation/cap, Lamport next, canonical message/poll/vote/file/kick/role/rekey bodies, strict outbound and inbound body gates, fan-out peer normalization and concurrency cap (4), per-peer watermarks, delta-only posts, refused delta not acked, calm full-log repair after ack, backed-off repair. |
| `FlitEngineTest` | 7 | History payload round-trip and corrupt decode, self-ghost dedup (with/without identity), loopback manual junk dropped, direct radio row labels without phantom port, manual stub hidden by real device. |
| `FlitKnownTest` | 8 | Known-device codec round-trip, tolerant decode, QR-only default/toggle, latest-put route policy, forget, keys under the `flit` namespace. |
| `FlitMirrorTest` | 5 | Diff offers only ledger-unmarked files, zero-length write-in-progress excluded, size change mirrors again, token survives round-trip, re-notice quiet. |
| `FlitQrTest` | 22 | v2/v3 URI round-trips (including relay-only codes and v3 with no key on the wire), order/dupe resolution, junk refused, on-screen and camera-shaped frames decoded (canvas-gray, RGB, y-plane with strides, rotation), skewed non-code stays calm, unicode alias. |
| `FlitRosterTest` | 12 | Batch merge semantics (token union, age kept, aliases, mid-flight merge, last-token removal, first-occurrence twins), sharing-desired codec and reducer, restore only when desired and neither running nor starting. |
| `FlitRouteTest` | 7 | Bluetooth MAC route distinct from TCP twin, legacy TCP route decode, typed relay parse, candidate priority order and dedup, calm hunt backoff, fingerprint-only accept rule. |
| `FlitSpeedEdgeTest` | 4 | First huge sample only seeds baseline, irregular intervals, decreasing/repeated counts never negative, 100× jump limited. |
| `FlitSpeedTest` | 4 | Repeated samples in one paint window do not inflate rate, no negative/gigabit spike, reset forgets prior baseline. |
| `FlitSpoolTest` | 5 | Start sweep reclaims killed-session copies only; young staging kept; queue whitelist wins; crush copies are litter; look-alikes/directories ignored. Real mtimes on a temp root, no Robolectric. |
| `FlitSubnetTest` | 8 | /24 and /30 host walks, self/network/broadcast excluded, /31-/32 and out-of-range prefixes refused, IPv6 not walked, /16 over the 1024-knock cap refused, boundary at 253/252, labels. |
| `SharingRestoreTest` | 11 | Fresh row not desired, absent/blank/corrupt decode safe, `with` flips and stamps, envelope can grow, idempotent encode/decode, old bare-word rows, restore only after process death and not when already up/starting. |
| `SheetUiTest` | 3 | Initials from first two words, stable in-range hue per fingerprint, count phrasing rule of one. |

### 4.3 Harness — 10 tests

Both classes run the **production** endpoints in one JVM. `CircleHarnessTest`
hosts through `ConsoleDelegate.circleHost` behind a real TLS `ProtocolServer`
and dials with a real `ProtocolClient` (`harness/src/test/kotlin/app/flit/share/harness/CircleHarnessTest.kt:32-38`);
`RelayServerTest` uses `RelayLinkProvider` and `RelaySubscriber` against the
same `RelayServer` the CLI runs, with TLS inside the blind pipe
(`.../RelayServerTest.kt:36-42`).

| Class | # | What it pins |
| --- | --- | --- |
| `CircleHarnessTest` | 5 | Two-node walk: bad invite refused, JOIN returns key, owner appoints admin, admin kick removes third member and rekeys, departed member refused, poll re-vote keeps both votes, file announcement is name/size/sha256 only, file bytes land hash-exact with local status moving pending→sent. Plus: stale signed ROLE event refused by replay guard, CLI errors distinguish host-refused from no-answer, and delivery outcome maps a returned report but never a throw (a thrown carrier stays pending). |
| `RelayServerTest` | 5 | Real 3 MiB TLS send lands hash-exact through the pairer; an 8 MiB carrier death throws `TransferUnreachable`, keeps the receiver prefix and resumes hash-exact after the stale window; wrong token refused on the wire; unpaired subscription times out cleanly; dead relay dial reports unreachable, not a send report. |

## 5. What the suites establish, by flow

The per-class tables in §4 are the authority for counts.

- **Wire parity.** `DtoParityTest`, `LinkServerParityTest` and the CLI `bt-sim`
  run one unchanged v2.2 brain over three pipes: TCP/Netty, an in-memory duplex
  pipe, and the simulated Bluetooth carrier. The server side is `ServerCore`
  and `LinkServer` in all three; the client is the hand-rolled `PeerHttpClient`
  / `ProtocolClient`. No v3, no protocol fork.
- **Trust and crypto.** `TlsIdentityTest` / `PinCacheTest` cover fingerprint
  derivation, the bounded LRU pin store and cert-in-hand verification;
  `CircleCoreTest`, `CircleHardeningTest`, `FlitCircleHostTest` and `PanicTest`
  cover signatures, forged authors, replay windows and bounded sets. Host
  answers are pinned to the QR fingerprint; the group key is membership
  material, not encryption.
- **Resume and death.** `ChunkTrackerTest`, `PartialSidecarTest`,
  `ResumeAndTurboTest`, `ProtocolRefusalTest` and `LinkServerParityTest` prove
  prefix assembly, the sidecar written before the first body byte, both
  `Content-Range` spellings, the 409 no-base refusal, the failed-chunk-then-
  plain-resume path, hash-exact multi-socket landing, and the central death
  contract: a carrier death throws `TransferUnreachable` (never a send report),
  a returned report means the peer spoke, and kept bytes stay owed.
- **Relay.** `RelayCoreTest` is the pure state machine; `RelayServerTest` is the
  wire proof through the same code the CLI serves, including relay death and
  resume. The relay parses no protocol byte; TLS and the fingerprint proof
  run end-to-end inside the pipe.
- **Circle.** `CircleCoreTest`/`CircleHardeningTest` (pure event law),
  `FlitCircleStoreTest`/`FlitCircleSyncTest`/`FlitCircleHostTest` (app state,
  fan-out and host governance), `CircleHarnessTest` (two nodes over production
  routes). The file-drop event body is exactly `{name,size,sha256}` — no path,
  no queue token, no bytes — and delivery status is sender-local, never
  gossiped.
- **Discovery and routing.** `FlitQrTest`, `FlitKnownTest`, `FlitRouteTest`,
  `FlitBleTest`, `FlitSubnetTest` and `FlitEngineTest` cover QR codecs, QR-only
  and per-device route policy, BLE advertising/arbiter/duty cycle, the subnet
  rescue sweep math and roster dedup. The radio-facing policy is JVM-testable;
  the actual RFCOMM socket path is not exercised on this bench.
- **Lifecycle and hygiene.** `SharingRestoreTest` and `FlitRosterTest` pin the
  persisted sharing-desired restore; `FlitSpoolTest`, `FlitSpeedTest` and
  `FlitSpeedEdgeTest` pin litter cleanup and progress; `SheetUiTest`
  pins small render rules.

## 6. Device-walk evidence table

Every row below is an observation recorded in a focused doc or commit body;
the level column is the strongest level actually available. A row marked
*not verified* is a limit.

| Claim | Level | Exact observation | Source |
| --- | --- | --- | --- |
| Mid-body carrier death keeps the prefix and resumes hash-exact (Wi-Fi) | **On-device** | Fresh 300 MiB camera shot; box `kill -9` 0.8 s after `recv-start` with a 33,980,416 B part; phone logged `kept, sends when it reappears`, hunter re-probed, next offer `recv-resume … from 33980416 bytes`, then `recv-ok`; sha256 `fbc4684a30afd…` matches byte-for-byte, no part/sidecar left. Late kill (~90% of 800 MiB multi) took a clean full resend. | `docs/transport-bluetooth.md:85-95` |
| Receiver restart keeps an interrupted prefix | **On-device** | Killed sender + restarted receiver resumed hash-exact from a 128 MB prefix. | `docs/transport-bluetooth.md:60-65` |
| Blind relay pairs two peers and survives relay death | **On-device** | Box ran `flit-cli relay` and `flit-cli serve --relay 127.0.0.1:53317`; 200 MiB shot crossed the pairer (`hunt: relay answered as Flit (szszszs)`), then a 300 MiB shot was cut by `kill -9` on the relay; both ends re-subscribed, next hunt logged `recv-resume … from 31866880 bytes`, hash-exact with no leftover part. | `docs/transport-bluetooth.md:108-119` |
| Wi-Fi throughput ceiling | **On-device** | 512 MiB from the reference CLI to the phone in ~15.9 s, identical single-stream and multi-socket — 32.1 MiB/s (~270 Mbps). Ceiling is the link and phone storage, not framing. | `docs/bluetooth-carrier.md:279-287` |
| Circle file drop delivers real bytes hash-exact to the phone | **On-device** | 8 MiB landed on the phone with matching sha256 and delivery status `sent=2` (commit record); the harness version uses 8,192 B with the same hash-exact assertion. | `git log 2034fa6` body; `CircleHarnessTest.kt:409-467` |
| Circle two-node coordination on hardware | **On-device** | Joined by v3 QR paste and by `flit://add` tap; CLI posted message rendered in the M3 feed with author/timestamp; phone as host logged `circle host: join` and `sync … event(s)`; changeable poll and two bot messages in the feed; full leave logged `circle: left fully — key discarded`. | `docs/circle.md:290-308,354-356` |
| Open file does not crash the app; sharing survives force-stop | **On-device** | Fresh 35 B drop landed (`c421969b…`) with "Landed here"; tap opened `ChooserActivity`, `pidof app.flit.share` stayed 15834, no `FATAL`/`am_crash`/`Force finishing`; back returned to the feed; `am force-stop` → new PID 16970 logged `egress pinned…` and `serving https://0.0.0.0:53317` with Home showing Stop. | `docs/open-file-and-sharing-restore.md:247-259` |
| Album mirror and dedup | **On-device** | Synthetic camera shot queued, sent and landed hash-exact in ~200 ms; a restart re-sent nothing. | `docs/transport-bluetooth.md:66-71` |
| Subnet rescue sweep finds a dial-only peer and keeps it fresh | **On-device** | 2026-11-14: `sweep 192.168.100.0/24: 253 hosts … found 1 (Flit (szszszs)@192.168.100.12)` with multicast out and no manual/QR trust; keeper held the row 2.5 min; hidden mode produced zero sweep lines. | `docs/bluetooth-carrier.md:288-341` |
| Interop with a real v2.2 peer | **Harness/sim** (loopback CLI) + **on-device** (wireless day 2) | `localsend-cli` v1.18.2 both directions: our receive `recv-ok`, our send `sent 1/1` byte-identical. Day 1's multicast row was later corrected: day 2 proved phone↔box multicast both directions once the app's announce TX was wired; it was not a permanent kernel failure. | `interop/README.md` T0/T1, W0-W5 and "Corrects day-1 T0-m" |
| BLE permission/radio/hotspot UX on one phone | **On-device** | 2026-10-29/30 walks: system permission sheet and enable dialog presented once each, radio flip lines, hotspot classifier in three parts, 8-minute idle soak with empty crash buffer. | `docs/bluetooth-carrier.md:192-262` |
| Phone-to-phone RFCOMM transfer | **Not verified** | No second phone; bench box has no Bluetooth adapter (`bluetoothctl list` empty). Only the toggle → `bluetooth link: listening` smoke and the pipe parity/`bt-sim` sim were run. | `docs/bluetooth-carrier.md:266-278` |
| Three-or-more-member Circle mesh | **Not verified** | Only the two-node phone+CLI walk exists; a real multi-member mesh needs hardware not on this rig. | `docs/circle.md:326-327,360-361` |
| Outbound phone→host Circle fan-out as a device observation | **Test-evidenced only** | Behavioural fan-out tests pass with an injected carrier; device walk could not produce a clean peer-side confirmation because demo bots' CLI state and their `serve` process shared identities. | `docs/circle.md:312-319,357-361` |
| Wi-Fi Aware carrier | **Not verified (hardware absent)** | `adb shell pm has-feature android.software.wifi.aware` answered `false` on the rig phone (2026-11-21); no Aware carrier built. | `docs/transport-bluetooth.md:96-100` |
| MIUI autostart/battery and Android 15 `dataSync` 6 h cap | **ROM/OS limit** | MIUI can still kill the process; the persisted row restores sharing on the *next* open, not autonomously. At the `dataSync` cap `onTimeout()` drops the notification to avoid a system crash while a Flit screen is open; no background guarantee after the last surface closes. | `docs/open-file-and-sharing-restore.md:198-217`, README:86-87 |

## 7. Reproduction recipes

Commands are from the shipped CLI usage (`harness/src/main/kotlin/app/flit/share/harness/Harness.kt:136-176`). Build first:

```bash
cd /home/szszszs/flit
source env.sh
./gradlew :harness:installDist
# binary: harness/build/install/flit-cli/bin/flit-cli
# or: ./install-cli.sh --zip flit-cli-0.2.0.zip  → ~/.local/bin/flit-cli
```

The CLI identity lives at `~/.config/flit/flit-harness/identity.p12` (override
with `FLIT_HARNESS_HOME`), so fingerprints survive rebuilds and reinstalls
(`Harness.kt:81-99`). Restart `serve` after every `installDist`: a running JVM
holds the old jars and a stale session can occupy the single receive slot until
`POST …/cancel?sessionId=…` (`interop/README.md`, "Incident notes").

### 7.1 Box receive (direct or hidden)

```bash
flit-cli serve --dir ~/Public                        # receive, announces on the LAN
flit-cli serve --dir ~/Public --hidden               # never announces or registers back
flit-cli serve --dir ~/Public --port 53318 --udp-port 53319 \
    --alias FlitAlpha --announce-to 127.0.0.1:53317  # unicast announce where multicast is stripped
flit-cli discover --wait 5                           # who is speaking v2.2
flit-cli info 192.168.100.12:53317                   # /info by address
flit-cli send 192.168.100.12 <file...> [--parallel N] [--pin PIN] [--multi]
```

`serve` auto-accepts incoming offers; the app's trusted-senders gate has no CLI
counterpart (README:166-168). A hidden server announces nothing, which is exactly
the case the app's rescue sweep covers: the shipped-CLI walk used
`serve --hidden` and the phone's sweep found it on the next throttled round
(`docs/bluetooth-carrier.md:342-353`). The stand-down rule applies to the *phone's*
hidden mode — a register is an announcement, so a hidden phone runs no sweep.

### 7.2 Blind relay pairer

```bash
flit-cli relay --port 53318 [--token T] [--park-timeout S]
flit-cli serve --dir ~/Public --relay 127.0.0.1:53318 --token T   # no direct listener, no announces
```

On the phone, type the relay `host:port` (and token) into Options and enable the
per-device **relayFor** switch for any peer that must use it; Circle dialing
needs the same switch. The relay pairs `req <fp>` with `sub <fp>`, logs only
timestamps and opaque ticket ids, stores nothing and has no discovery. Both
peers must be live (`docs/milestone-e.md:71-80`, README:169-179).

### 7.3 Simulated Bluetooth pipe (`bt-sim`)

```bash
flit-cli bt-sim [file...] [--bytes MB] [--no-tls]
```

One process, two ends: receiver is the production `ServerCore` behind
`LinkServer`, sender is the production `ProtocolClient` over `SimLinkProvider`;
`--bytes` mints a synthetic random body when no file is given; `--no-tls` makes
`/info` say `http` (`BtSim.kt:27-43,44-49,96-101`). A green run prints
`info: …`, `sent N/N`, exit 0; a failed file exits 3. This is the transport-
agnostic regression gate, not a radio proof.

### 7.4 Circle with two bots

Two bots are two CLI processes with distinct `FLIT_HARNESS_HOME` values, so
their identities and local Circle state cannot collide. Bot A first mints the
circle; `circle create` prints the exact `serve --circle ID --invite TOKEN`
command to run in a second terminal (`Harness.kt:1041-1052`). `serve` seeds a
host from those values and prints them at startup (`Harness.kt:512-516,569-570`).

```bash
# terminal A
export FLIT_HARNESS_HOME=/tmp/opencode/bot-a
flit-cli circle create                              # prints circle ID + invite TOKEN
flit-cli serve --dir /tmp/opencode/bot-a-downs \
    --circle ID --invite TOKEN                      # the host bot (blocks)

# terminal B
export FLIT_HARNESS_HOME=/tmp/opencode/bot-b
flit-cli circle join --host 127.0.0.1:53317 --circle ID --invite TOKEN
flit-cli circle post --text 'hello' --host 127.0.0.1:53317
flit-cli circle poll --text 'Ship?' --option yes --option no --host 127.0.0.1:53317
flit-cli circle vote --option 0 --host 127.0.0.1:53317
flit-cli circle sync --host 127.0.0.1:53317          # feed + poll tally
flit-cli circle role --target FP --role ADMIN --host 127.0.0.1:53317
flit-cli circle kick --target FP --host 127.0.0.1:53317
flit-cli circle file --file big.bin --host 127.0.0.1:53317   # announce + real bytes
flit-cli circle leave --host 127.0.0.1:53317
```

`circle post --file` publishes only a `{name,size,sha256}` reference;
`circle file --file` also pushes real bytes with `sendFiles` and records local
status `pending → sent/refused/failed` (`Harness.kt:149-174`). The harness test
uses the same command paths in-process; use distinct `FLIT_HARNESS_HOME` values
when driving two bots, since a shared home with a `serve` process is what made
the device fan-out observation ambiguous (`docs/circle.md:357-361`). Each CLI
command is one-shot: it loads the state file, performs one request and saves it
(`Harness.kt:1035-1039`).

### 7.5 Device walk recipes

These reproduce the recorded evidence; they are **not** part of the automated
rig and must be reported as device walks only when actually run.

- **Wi-Fi kill/resume:** start a large pick from the phone to a box `serve`;
  `kill -9` the box process just after `recv-start`; confirm the phone log says
  `kept, sends when it reappears`; restart `serve`; expect `recv-resume … from
  <exact prefix>` then `recv-ok`; compare sha256 and assert no `.part`/`.token`
  left. The part must be well short of completion — a near-complete sparse
  prefix is not advertised as a resume base.
- **Relay kill/resume:** run `flit-cli relay` plus `flit-cli serve --relay …`,
  configure the phone, send, `kill -9` the relay mid-body, and watch both ends
  re-subscribe on backoff before the next hunt re-pairs.
- **Circle file drop:** post `circle file --file` to a joined phone; confirm the
  M3 card becomes "Landed here", hash matches, and status moves to `sent`.
- **Open-file / sharing restore:** tap Open file, pick a viewer, return; check
  `pidof app.flit.share` and the log for `am_crash`/`FATAL`; then
  `am force-stop app.flit.share`, relaunch and confirm sharing comes back from
  the persisted row without a fresh prompt.

### 7.6 Interop with a real v2.2 peer

The official CLI is at `~/.local/opt/localsend-cli`. Drive its TUI with a FIFO
and an `Y` at the offer prompt; the reproducible bootstrap pairs are recorded
in `interop/README.md` ("Repro bootstraps" and "Day 2"). This check is manual and
sits outside the rig. Day 1 used a unicast announce fallback because multicast
appeared broken; day 2 (2026-10-23) corrected that diagnosis — multicast worked
both ways on the real Wi-Fi LAN once the app's announce TX was wired properly,
and the earlier `T0-m` conclusion is kept only as history.

## 8. Flaky patterns and fixes

These are the timing and state hazards observed while running the suite, with
the mitigation each test uses.

| Pattern | Where | Symptom | Mitigation |
| --- | --- | --- | --- |
| Global mutable test knob `PeerHttpClient.testAbortBytes` | `LinkServerParityTest.kt:199,212,361,373,527,531`; `RelayServerTest.kt:165,171,201` | A forced mid-body abort can leak into the next test and break unrelated sends. | Set only for the abort window, reset to `0L` in `finally`; keep those tests in a single JVM (Gradle's default fork behavior). A new abort test must follow the same pattern. |
| Wall-clock sleep for the stale-window gap | `RelayServerTest.kt:187` (`delay(11_000)`) | The receiver's stale session must retire before a new sender replaces it; a shorter sleep reintroduces the two-layer resume defect. | Sleep past the window, then assert the prefix was kept and the resume is hash-exact. Slow, but deterministic; do not trim it without a clock-injection seam. |
| Filesystem polling with deadlines | `RelayServerTest.kt:112-114,179-181,194-196`; `PartialSidecarTest.kt:109-110` | An async landing or sidecar appears a few milliseconds late; a bare existence check races the writer. | Poll at 50 ms with a bounded 5-20 s deadline, then assert content/hash. Always fail with the directory in the message. |
| Hard hang instead of slow failure | `LinkServerParityTest.kt:103-125` | A duplex pipe can deadlock; Gradle would hang until a human intervenes. | A daemon watchdog dumps all thread stacks and `halt(97)` after 60 s; the actual run is bounded by `withTimeoutOrNull(90_000)`. Healthy runs interrupt the watchdog in `finally`. Copy this shape for any new duplex test. |
| Fixed port collisions | All network-ish tests use `port = 0` or `ServerSocket(0)` | A hardcoded 53317/53318 collides with a running `serve` or another fork. | Bind an ephemeral port and pass the assigned value through `SendTarget`. |
| Shared temp directories | Parity, relay and Circle tests | A previous run's landed file makes "exactly one file" or hash checks pass for the wrong reason. | `Files.createTempDirectory(...)` per test; assert exact file count and bytes; close servers/clients in `finally`. |
| Random data without a seed | `Random(7)`, `Random(42)`, `Random(99)` in parity/relay/Circle tests | Non-reproducible payloads make a rare framing bug impossible to replay. | Seed every generator; keep the seed in the test source. |
| Wall-clock replay windows | `CircleCoreTest.kt:108,256`; `PanicTest.kt:166`; `FlitCircleHostTest.kt:154,370`; `CircleHarnessTest.kt:345` | An event created "now" can drift out of the window on a slow machine, or a stale one can drift in. | Pass `createdMs` explicitly for edge cases (`now − REPLAY_WINDOW_MS − margin`) and use defaults only for fresh events. |
| Stale Gradle outputs and XML | Observed on 2026-11-25: XML listed `PinCacheTest` and `TlsIdentityTest: 2` although the current source has `PinCacheTest` in `TlsIdentityTest.kt` and `TlsIdentityTest` in `DtoParityTest.kt` | `UP-TO-DATE` test tasks can report green against an older class layout; per-class counts in this doc would drift. | Use `--rerun-tasks` (or `clean`) when verifying counts after test-file edits; count `@Test` in source as a cross-check. Totals stayed 302 because tests moved between files, so the stale XML was not obvious from the headline number. |
| Identity/state sharing between CLI bots | `Harness.kt:81-99`; `docs/circle.md:357-361` | Two bots with one `~/.config/flit/flit-harness` home, or a bot sharing state with `serve`, blur peer-side confirmation. | Give each bot its own `FLIT_HARNESS_HOME`; record the ambiguity when a device walk cannot disambiguate. |
| Concurrent CI runs on one branch | `.github/workflows/rig.yml:10-12` | Two pushes could race on build outputs/artifacts. | `concurrency.group = rig-${{ github.ref }}` with `cancel-in-progress: true`. |
| Inherited `FLIT_TRACE` / stale `serve` session | `Harness.kt:128-133`; `interop/README.md` | A CLI failure prints only `!! message` without a stack, or a stale receive slot blocks a new run. | Set `FLIT_TRACE=1` for diagnosis; restart `serve` after `installDist`, cancel stale sessions with `POST …/cancel?sessionId=…`. |

## 9. How to add tests

1. **Pick the right module for the claim.** A new pure rule (codec, reducer,
   policy math, canonical body, tally) goes in `protocol/` if it is wire-level
   and Android-free, or in `app/` if it is app state. A claim about two peers,
   a relay or a real production endpoint goes in `harness/` using the real
   `ProtocolClient`/`ProtocolServer`/`RelayCore`, never a lookalike core. A
   claim about a radio or a UI gesture can only be a device walk; write the
   pure seam test as well and label the walk separately.
2. **Keep the test JVM-pure.** No Android framework imports in `app/src/test`,
   no Robolectric, no emulator. Use `kotlin.test` (`@Test`, `assertEquals`,
   `assertContentEquals`, …), `runBlocking` where a suspend path is needed, and
   the JUnit Platform already configured in each module's build file. Do not
   add a new test dependency without a concrete need.
3. **Exercise the production seam.** Use `ServerCore`/`LinkServer`,
   `ProtocolClient`, `CircleCore.create`/`verify`, `TlsIdentity` and the same
   `SendTarget` the app builds. If a test needs a fake, keep it at the transport
   boundary (`PipePair`, `SimLinkProvider`, an injected clock or carrier), not
   at the protocol rule it claims to prove.
4. **Make inputs deterministic.** Seed random payloads; inject `createdMs`
   for replay-window edges; bind ephemeral ports; use fresh temp directories;
   assert exact bytes/hash and exact refusal reason, not just "ok".
5. **Clean up.** Close clients/servers/scopes in `finally`; reset global knobs
   such as `PeerHttpClient.testAbortBytes` to `0L`; delete nothing that a
   queue whitelist should keep. If the test can hang, add the watchdog/timeout
   shape from `LinkServerParityTest`.
6. **Verify names and counts.** A file may hold more than one test class (see
   §4), so verify the report by class name, not by file name. After adding a
   test, re-run with `--rerun-tasks` and update the count in §2, §4 and the
   cross-linked docs (`OPERATIONS.md`, `PERFORMANCE.md`, `SECURITY.md`,
   `FAILURE-MODES.md`, `ARCHITECTURE.md` and the CI comment all quote counts).
   The commit message records the new module counts, as `72225f8` and `2034fa6`
   do.
7. **Run the rig before claiming green.** Local test subset first:
   `source env.sh && ./gradlew :protocol:test :app:testDebugUnitTest :harness:test`,
   then the full local or CI command from §3 including both assemblies and
   `installDist`. Only then call it green, and only at the level the tests
   actually cover (§1). If the behavior needs hardware, add the reproduction
   recipe from §7.5 and mark the observation "not verified" until walked.

## 10. Cross-links and historical note

- Module boundaries and route/relay policy: [Architecture](ARCHITECTURE.md),
  [Contracts](CONTRACTS.md).
- What retries, what concludes, what keeps bytes: [Failure modes](FAILURE-MODES.md).
- CLI flags, box/phone setup, logs and troubleshooting: [Operations](OPERATIONS.md).
- Measured throughput and the design tradeoffs behind the tests: [Performance](PERFORMANCE.md).
- Trust anchors and threat model behind the signature/pin tests: [Security](SECURITY.md).
- Device-walk narrative per feature: [transport ladder](transport-bluetooth.md),
  [Bluetooth carrier](bluetooth-carrier.md), [Circle](circle.md),
  [Open-file and sharing restore](open-file-and-sharing-restore.md),
  and the [milestone A/C/D/E docs](milestone-a.md) ([C](milestone-c.md),
  [D](milestone-d.md), [E](milestone-e.md)).

Historical counts in those docs describe the suite at the time they were
written: 30 at B0, 76 at milestone 1, 86 at milestone D, 115 at milestone E,
then 121, 126, 145, 183, 231, 286 and 302 at `72225f8`. The CI workflow header
(`.github/workflows/rig.yml:1-3`) still quotes 36/67/4; that line is stale.
