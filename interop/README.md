# Interop verification log — Flit vs real LocalSend CLI v1.18.2

Date: 2026-10-22 · Environment: Linux box, JVM Temurin 21, loopback.
Peer under test: `~/.local/opt/localsend-cli` (rustls-based, protocol v2.2).
Our stack: `:protocol` module (Kotlin), Netty server, own `PeerHttpClient` (JDK TLS).

| # | Scenario | Result |
|---|----------|--------|
| T0-s | Harness↔harness send (3 files, parallel 2, mTLS, dedup `payload (3).txt`) | PASS (3/3, exit 0) |
| T0-d | Discovery harness↔harness | PASS via **unicast fallback announce** (`--announce-to`) |
| T1-r | Real CLI → us: CLI registers + sends payload, we auto-accept | PASS (`recv-ok`, CLI TUI `Sent 1 file (32 B)`) |
| T1-s | Us → real CLI: prepare-upload + upload to the rustls server, `Y` in CLI TUI | PASS (`sent 1/1`, CLI TUI `Received 1 file (30 B)`, byte-identical) |
| T0-m | Multicast 224.0.0.0/4 announce | **FAIL — kernel-level broken here** (`ping 224.0.0.1` 100 % loss; receiver socket joined on iface gets nothing; no firewall rules). Mitigation: unicast announce fallback. |

## Root causes diagnosed this session (clean-room, from observed wire behavior)

1. **Ktor CIO TLS client unusable for LocalSend** — threw `Invalid TLS record type code: 116`
   against rustls servers. Retired for the client side; **replaced transport with our
   minimal HTTP/1.1 client over JDK `SSLSocket`** (`PeerHttpClient`): one exchange per
   connection, content-length bodies, minimal chunked decode, no hostname verification
   (peers are IP-addressed; authentication is fingerprint-based per protocol spec).
   `java.net.http.HttpClient` was rejected: hostname verification cannot be disabled
   cleanly (undocumented kernel property) and it does not exist on Android.

2. **rustls alert 116 = `certificate_required`** — both curl-without-cert and our stack got
   this alert. Traced with `javax.net.debug`: the CLI's `CertificateRequest` carries
   `certificate_authorities: [CN=LocalSend User]` (its own self-signed subject). JDK
   `SunX509` filters client-cert aliases against that list (`chooseClientAlias(..., issuers)`
   → `null` when issuer mismatch), swings to an **empty client certificate flight**, and
   rustls kills the handshake. Peer-to-peer mTLS has no CA hierarchy, so
   `TlsIdentity.keyManagers()` now wraps the stock KeyManager with an issuer-agnostic
   `X509ExtendedKeyManager`: selection matches by key algorithm only, CA list ignored.

3. **Strict JSON on prepare-upload** — upsteam `RegisterDtoV2` (serde) declares `port` and
   `protocol` with **no defaults**; our earlier `encodeDefaults=false` omitted
   `protocol:"https"` → CLI answers `400 {"message":"Invalid JSON body"}`. Wire JSON now
   always carries default-valued fields (`WireJson` `encodeDefaults = true`); serde readers
   ignore extra fields on responses, so the changed response shape stays compatible.

4. Carried context: password `CharArray` shared with netty/ktor gets zero-filled →
   `TlsIdentity` holds an immutable secret and hands out fresh `secretChars()` per call.
   CLI quirks: UDP announce socket always 53317 regardless of `LOCALSEND_PORT`; device
   list opens with `D`, Enter sends, `Y/N/P` accept incoming.

## Incident notes
- A timed-out CLI prepare leaves its TUI dialog behind; dismiss with `N` before retrying.
- Rebuild ritual: restart `flit-cli serve` after every `installDist` (JVM holds the old
  jars; a stale session also blocks the single slot until `POST …/cancel?sessionId=…`).
  Bin renamed 2026-11-14: the same code ships as `flit-cli` — earlier records here
  say `harness serve`; those are history, the lines above are how to run it now.

## Repro bootstraps
- Node A: `flit-cli serve --dir received --port 53318 --udp-port 53319 --alias FlitAlpha --announce-to 127.0.0.1:53317`
- Node B send: `flit-cli send --to 127.0.0.1:53318 <files>` (T0), `--to 127.0.0.1:53216` (T1 reverse).
- CLI receive driving: `mkfifo cli-stdin`, `exec 3<> cli-stdin`, launch `script -qec "$HOME/.local/opt/localsend-cli" /dev/null <&3 > cli-tui.log 2>&1 &`,
  then `printf 'Y' >&3` when the offer dialog shows (same shell invocation; fd3 dies with the shell).

## Day 2 (2026-10-23) — wireless mesh: phone (M2101K7BNY, MIUI) ↔ Linux box

Environment: real Wi-Fi LAN "Bojan i Stefan 5g"; phone 192.168.100.80 (Flit app),
box 192.168.100.12 (harness `serve --port 53318`). The final verification pass ran with
**no USB tunnels** (`adb forward`/`reverse` removed). The box's firewalld REJECTs by
default; runtime-opened with
`sudo firewall-cmd --add-port=53317-53320/tcp --add-port=53317/udp` (nothing persisted).

| # | Scenario | Result |
|---|----------|--------|
| W0 | Engine boot: UDP discovery sockets unmarked → egress pinned to Wi-Fi → serving `https://0.0.0.0:53317` | PASS |
| W1 | Box→phone multicast announce → nearby-device row auto-appears with the real address (192.168.100.12:53318) | PASS |
| W2 | Phone→box register-back over Wi-Fi | PASS — A logs `seen Flit · M2101K7BNY (192.168.100.80:53317)` ≤ 12 s after engine start |
| W3 | Phone→box transfer (row tap → SAF pick) | PASS — round-trip `day2-rev.txt` (29 B), `offer` + `recv-ok`, sha held |
| W4 | Box→phone transfer (`harness send 192.168.100.80:53317 day2-final.txt`) | PASS — `sent 1/1`, 27 B byte-identical on phone (`Downloads/Flit/`) |
| W5 | Phone→box multicast announce from the **app process** | **PASS after this day's wiring** — 3/3 deliveries to a fresh listener (14:41:51 / :57 / 14:42:03) |

Also re-verified the harness after the discovery refactor: `harness discover` lists the
phone and the box peer with correct fps/addresses; the phone's row survives engine
restarts by re-discovery alone (no manual injection anywhere).

Corrects day-1 T0-m: the box's kernel multicast path is **not** broken —
phone↔box multicast delivers both directions once announce TX is wired properly.

### Fixes / findings this day

1. **Ktor netty peer reverse-DNS stall (the day-1 "TLS wedge")** — ktor 3.2.2 netty
   `NettyConnectionPoint` (http1) builds `request.origin` via
   `InetSocketAddress.getHostName()`: a **blocking reverse-DNS** on literal LAN IPs
   (`getent hosts 192.168.100.80` on the box: 4.98 s, fails; ~10 s negative cache, so the
   wedge recurs in cycles). Every handler touching `request.origin`
   (register/prepare/upload/cancel) stalled past any client timeout; loopback peers
   resolve instantly from /etc/hosts, which is why tunnel flows worked and direct ones
   hung; `/info` never calls it, which is why health curls stayed 200 throughout.
   **Fix**: an `ApplicationCallPipeline.Setup` intercept (runs before routing wraps the
   call in `RoutingCall`, which hides the channel) captures the raw channel
   `InetSocketAddress` into a call attribute; `peerIp()` returns
   `attributes.getOrNull(peerAddrAttr)?.address?.hostAddress` — pure string, no DNS.
   **Rule: on the server never read `request.origin`.**

2. **Announcement TX wiring on Android**:
   - a `runCatching` wrapping **both** `s.networkInterface = ni` and `s.send()` silently
     skipped the send whenever `setNetworkInterface` threw — which it does for
     netd-marked sockets on this device. **This is the proven killer**: with the
     day-1 pin active the announce socket was marked, every announce threw-and-skipped,
     silently (W2 worked because it is TCP, not UDP).   - supporting observation (not cleanly isolated): a raw `nc -u` datagram sent as the
     app's own UID (`run-as app.flit.share nc -u -p 53317 224.0.0.167 53317`) always
     delivered; identical in-app sends of that era appeared dead — but every in-app
     check then ran on a listener whose seen-map already held the phone's fingerprint
     (dedupe mask), so marked-vs-unmarked multicast could not be separated. The final
     wiring defensively keeps UDP sockets **created before the pin** regardless.
   **Fix**: UdpDiscovery split into `create()` (bind + per-iface `joinGroup` RX + aux TX
   socket) and `start()` (loops); engine order
   `srv.start()` → `cli` → multicast lock → `disc.create()` → `bindProcessToNetwork`
   pin → `disc.start()`; per-interface `setNetworkInterface` TX removed entirely
   (only RX membership anchoring kept); sends use the plain aux `DatagramSocket`
   pre-`connect()`-ed to the group. After that, W5 delivers.
   Also dismissed as causes: UID (run-as passes), fixed source port (`-p 53317`
   passes), multicast lock, loopback mode, main-table route emptiness (per-netid
   tables hold the multicast route), socket type (plain vs MulticastSocket both fine
   once the send actually runs).

3. **firewalld**: phone→box TCP was icmp-admin-prohibited (`connect: No route to host`
   → `EHOSTUNREACH` in the phone's register log), UDP silently dropped. Runtime-open
   only.

### Tooling / observation notes
- Harness `ConsoleDelegate` dedupes **every** `seen` print (UDP announce *and* HTTP
  register) by fingerprint. After a peer's first register, its announces are logged
  nowhere; **use a fresh listener (`flit-cli discover --wait 3`) for clean announce
  observations**. Multiple "phone multicasts never arrive" debug readings were this
  mask — the register-based `seen` line made every later check a false negative.
- UDP probes over adb: `cmd | nc -u …` never exits and wedges the session — always
  `nohup … >/dev/null 2>&1 &`.
- `adb shell setprop debug.flit.nopin 1` (read via SystemProperties reflection) skips
  egress pinning for that start — escape hatch for pin-related experiments.
- Android main route table is empty; routes live in per-netid tables (e.g. Wi-Fi =
  `table 1000000033`, holding `224.0.0.0/24 dev wlan0`).
- Session-restart ritual stays: restart `flit-cli serve` after every `installDist`.

## Day 3 (2026-10-23) — release build (R8) shipped to real Wi-Fi, all green

Built `:app:assembleRelease` with R8 minify + resource shrink + local release
keystore, installed on the phone, and re-ran the wireless matrix. Results vs
the debug build: identical (all PASS).

| Check (release R8 APK) | Result |
| --- | --- |
| W0 egress pin to Wi-Fi | PASS (`egress pinned to Wi-Fi network` at 15:03:40) |
| W1 box → phone multicast discovery | PASS (FlitAlpha row discovered on phone) |
| W2 phone → box register | PASS (`seen Flit · M2101K7BNY … port=53317 v=2.2`) |
| W3/W4 phone → box transfer | PASS (`recv-ok day2-final.txt 27 bytes` 15:04:50, byte-identical) |
| W5 box → phone transfer | PASS (`sent 1/1` 15:05:18 → `saved rel-day2-w5.txt (272 B)` → Downloads/Flit/, byte-identical) |

### Release config

- `app/build.gradle.kts`: `release { isMinifyEnabled = true; isShrinkResources = true }`,
  signing from root `keystore.properties` (`rootProject.file()` resolve — module-relative
  path broke signing validation), falling back to the debug key when the properties
  file is absent so source builds stay reproducible everywhere.
- Local release key: `keystore/` + `keystore.properties` (gitignored).
- APK: 5.35 MB.

### R8 findings (the debugging story, worth keeping)

1) **`start failed: Class a does not have a public non-arg constructor`** —
   netty's `ReflectiveChannelFactory` does `getConstructor().newInstance()` on
   `NioServerSocketChannel` (mapping: `NioServerSocketChannel → c6.a`,
   `ReflectiveChannelFactory → y5.x0`); R8's rewrite had killed the no-arg ctor
   lookup. Fixed by keeping netty (below) — a targeted ctor keep alone was NOT
   enough because the failure then moved one level deeper:
2) **`VerifyError: AdaptiveByteBufAllocator sub-types erroneous
   AbstractByteBufAllocator`** — ART rejects R8's rewritten netty buffer code at
   verify time. Persisted even with `android.enableR8.fullMode=false`; the
   `AbstractByteBufAllocator` dex entry looked structurally sane with all
   referenced types resolvable, i.e. plain R8 bytecode rewriting trips this
   device's verifier, not (only) full-mode class merging.
   **Final rule set: keep `io.netty.**` / `io.ktor.**` verbatim, shrink only app
   code** (+ kotlinx-serialization serializer keeps per runtime docs). APK 5.35 MB —
   acceptable; correctness verified on-device was the winning criterion.
   `fullMode=false` stays as a defensive default (closest to the tested debug config).
3) Keeping netty bodies surfaced its optional compression backends (brotli4j,
   zstd-jni, lz4, jzlib, ning-lzf, oracle-svm, pkitesting) as missing classes —
   all `-dontwarn` (guarded at runtime; we use JDK gzip/deflate).
- Diag tooling: `Log.e("FlitEngine", "engine start failed", e)` in the engine's
  catch → `adb logcat -d | grep -a FlitEngine` (obfuscated stack) +
  `mapping.txt` deobfuscation of `c6.a`/`y5.x0` to identify the reflective ctor
  consumer; dexdump of the R8-built APK for the buffer-class audit.
