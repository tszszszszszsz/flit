# Flit wire and API contracts

> Part of the Flit transport design. [Main ladder and index](transport-bluetooth.md).

## 0. Sources

Sources, in order of authority for a contract question:

1. The JVM-pure `protocol` module (`protocol/src/main/kotlin/app/flit/share/protocol/`).
   This module has no Android dependency, so its bytes are the wire truth.
2. The app's wiring of those pieces (`app/src/main/kotlin/app/flit/share/`,
   chiefly `FlitEngine.kt`, `FlitRoute.kt`, `FlitKnown.kt`, `Qr.kt`,
   `FlitCircleHost.kt`, `FlitCircleSync.kt`).
3. The focused docs already in `docs/`: `transport-bluetooth.md`,
   `bluetooth-carrier.md`, `circle.md`, `qr-only-and-panic.md`, `milestone-e.md`,
   `milestone-{a,c,d}.md`, `open-file-and-sharing-restore.md`, `README.md`.

## 1. Exact constants and route strings

All values below are declared in `protocol/.../Protocol.kt` unless another file
is named. Line references are to the files as of commit `72225f8`.

| Contract | Value | Source |
| --- | --- | --- |
| Wire version | `"2.2"` (`Protocol.VERSION`) | `Protocol.kt:14` |
| Default TCP port | `53317` (`Protocol.DEFAULT_PORT`) | `Protocol.kt:17` |
| Default UDP multicast port | `= DEFAULT_PORT` (`53317`) | `Protocol.kt:20` |
| Multicast group | `"224.0.0.167"` | `Protocol.kt:23` |
| LocalSend API root | `"/api/localsend/v2"` | `Protocol.kt:25` |
| Info route | `GET /api/localsend/v2/info` | `Protocol.kt:27` |
| Register route | `GET` and `POST /api/localsend/v2/register` | `Protocol.kt:28` |
| Prepare route | `POST /api/localsend/v2/prepare-upload` | `Protocol.kt:29` |
| Upload route | `POST /api/localsend/v2/upload` | `Protocol.kt:30` |
| Cancel route | `POST /api/localsend/v2/cancel` | `Protocol.kt:31` |
| Flit API root | `"/api/flit/v1"` | `Protocol.kt:35` |
| Partial (resume probe) | `GET /api/flit/v1/partial` | `Protocol.kt:36` |
| Panic | `POST /api/flit/v1/panic` | `Protocol.kt:39` |
| Circle join | `POST /api/flit/v1/circle/join` | `Protocol.kt:42` |
| Circle sync | `POST /api/flit/v1/circle/sync` | `Protocol.kt:43` |
| Panic cert header | `X-Flit-Panic-Cert` | `Protocol.kt:51` |
| Circle cert header | `X-Flit-Circle-Cert` | `Protocol.kt:58` |
| Flit client marker | `X-Flit-Client: 1` | `Protocol.kt:65-66` |
| Flit refusal marker | `X-Flit-Refusal: declined` with status `403` | `Protocol.kt:74-75` |
| Multi-socket marker | `X-Flit-Multi: 1` (client) / `multipart` response field | `ProtocolClient.kt:692,699-702` |
| Resume header | `X-Flit-Resume: <contentToken>` | `ProtocolClient.kt:429`, `ServerCore.kt:491` |
| Chunk range header | `Content-Range: bytes <start>-<last>/<total>` | `ProtocolClient.kt:567`, `ServerCore.kt:1124` |
| Chunk base header | `X-Flit-Base: <from>` | `ProtocolClient.kt:568`, `ServerCore.kt:430` |

### 1.1 Client-side numbers (`ProtocolClient.kt:676-702`)

| Constant | Value | Meaning |
| --- | --- | --- |
| `ACCEPT_TIMEOUT_MS` | `30_000` | Prepare may wait for a human to accept. |
| `UPLOAD_TIMEOUT_MS` | `120_000` | Per-body socket timeout. |
| `TURBO_MIN_BYTES` | `16 MiB` (`16L shl 20`) | Below this, no multi-socket lane. |
| `TURBO_MAX_STREAMS` | `4` | Sender cap of concurrent range POSTs. |
| `SPLIT_TARGET_BYTES` | `16 MiB` | Rough per-stream split target. |
| `PROGRESS_STEP` | `512 KiB` | Coarse per-stream progress callback step. |

### 1.2 Receiver-side numbers (`ServerCore.kt`)

| Constant | Value | Source / meaning |
| --- | --- | --- |
| `MAX_ATTEMPTS` | `3` | Plain upload attempts per file (mirrors upstream); `ServerCore.kt:1103`. |
| `TURBO_CHUNK_BYTES` | `8 MiB` | Receiver's `multipart` hint; `ServerCore.kt:1106`. |
| `MAX_TURBO_STREAMS` | `6` | Receiver-side claim headroom over the sender's 4; `ServerCore.kt:1113`. |
| `STALE_SESSION_MS` | `10_000` | A session whose last byte is older than this is replaceable; `ServerCore.kt:211`. |
| `PartRegistry.CAP` | `8` | Kept parts; oldest (and its bytes) evicted past cap; `ServerCore.kt:167`. |
| `PartRegistry.TTL_MS` | `6 h` | Kept part expires; `ServerCore.kt:168`. |
| Part sidecar | `<temp>.token` | `tokenSidecar()` at `ServerCore.kt:1168`. |
| Piece sidecar | `<temp>.pieces` | `piecesSidecar()` at `ServerCore.kt:884`. |
| Part/piece sweep age | `24 h` | `sweepOldParts` / `sweepPieceSidecars` at `ServerCore.kt:1158,1219`. |
| Progress emit cap | `~10 Hz` (`100_000_000 ns`) | `ServerCore.kt:585,757`. |
| Chunk buffer | `256 KiB` | `ServerCore.kt:576,748`. |

### 1.3 Discovery numbers (`UdpDiscovery.kt`)

| Constant | Value | Source |
| --- | --- | --- |
| Announce interval | `3_000 ms` | `UdpDiscovery.kt:157` |
| Start burst delay | `400 ms` | `UdpDiscovery.kt:158` |
| Datagram receive buffer | `65507 B` | `UdpDiscovery.kt:135` |

### 1.4 Relay numbers

| Constant | Value | Source |
| --- | --- | --- |
| Hello magic | `FLITRLY1` | `RelayCore.kt:41` |
| Max hello line | `512 B` | `RelayCore.kt:44` |
| Max fingerprint / token | `128` chars each | `RelayCore.kt:47,50` |
| Relay CLI default port | `53318` (`DEFAULT_PORT + 1`) | `harness/.../Harness.kt:675` |
| Relay handshake timeout | `10_000 ms` | `harness/.../RelayServer.kt:40` |
| Relay park timeout | `0` = wait forever | `RelaySubscriber.kt:70`, `RelayServer.kt:33` |
| Relay retry backoff | `2 s → 4 s … → 60 s` | `FlitEngine.kt:1720-1733` |

### 1.5 Panic numbers (`PanicCore.kt`)

| Constant | Value | Source |
| --- | --- | --- |
| `DEFAULT_MAX_HOPS` | `3` | `PanicCore.kt:18` |
| `REPLAY_WINDOW_MS` | `6 h` | `PanicCore.kt:21` |
| `CLOCK_SKEW_MS` | `5 min` | `PanicCore.kt:24` |
| `MAX_SEEN` | `1024` (FIFO) | `PanicCore.kt:27` |
| App text cap | `240` chars | `FlitEngine.kt:457` |
| App retained alerts | `20` UI events | `FlitEngine.kt:420` |

### 1.6 Circle numbers

| Constant | Value | Source |
| --- | --- | --- |
| `REPLAY_WINDOW_MS` / `CLOCK_SKEW_MS` | `6 h` / `5 min` | `CircleCore.kt:23,26` |
| `MAX_SEEN` | `4096` (LRU-ish FIFO) | `CircleCore.kt:29` |
| `MAX_BODY_CHARS` | `16 KiB` | `CircleCore.kt:32` |
| `MAX_FIELD_CHARS` | `256` | `CircleCore.kt:38` |
| `MAX_SIG_CHARS` / `MAX_CERT_CHARS` | `1024` / `8 KiB` | `CircleCore.kt:41,44` |
| `MAX_EVENTS_PER_REQUEST` | `1024` | `CircleCore.kt:47` |
| `MAX_REQUEST_BYTES` | `8 MiB` | `CircleCore.kt:50` |
| `FlitCircleStore.EVENT_CAP` | `500` | `FlitCircleStore.kt:72` |
| `FlitCircleHost.MAX_MEMBERS` | `500` | `FlitCircleHost.kt:49` |
| `FlitCircleHost.MAX_BODY_CHARS` | `8 KiB` (defensive) | `FlitCircleHost.kt:52` |
| Sync minimum interval | `30 s` | `FlitCircleSync.kt:41` |
| Sync backoff | `5 s` base, `120 s` cap (`10,20,40,80,120 s` for attempts 1–5) | `FlitCircleSync.kt:48-54` |
| Concurrent peer dials | `4` | `FlitCircleSync.kt:89` |
| Identity pin cache | `512`, access-ordered LRU | `TlsIdentity.kt:165,274-301` |

## 2. LocalSend v2.2 surface — untouched shapes

The v2.2 wire is HTTP/1.1 (never HTTP/2 — `enableHttp2 = false`,
`ProtocolServer.kt:164`) over TLS, served by Ktor/Netty and spoken by a
hand-rolled client (`PeerHttpClient`, one exchange per connection,
fingerprint-pinned, no hostname verification by design). The same route
semantics are hosted by `ServerCore` for both the TCP binder and the pipe binder
(`LinkServer`), and by the relay's `ProtocolServer.serve` door
(`ProtocolServer.kt:195`).

### 2.1 DTOs (`protocol/.../model/Model.kt`, `WireJson.kt`)

Wire JSON config is shared by client, server and discovery:
`ignoreUnknownKeys = true`, `encodeDefaults = true`, `explicitNulls = false`
(`WireJson.kt:10-16`). The consequence is concrete: **fields with defaults are
still written on the wire** (so `port` and `protocol` appear in register/info
answers as well as in announcements), while nulls are omitted. This is the
safe side for the rust `RegisterDtoV2`, which requires `port` and `protocol`
with no serde defaults. The longer comment on `PeerInfo` (`Model.kt:6-12`) says
the opposite — it is stale; see §12.

- `PeerInfo(alias, version, deviceModel?, deviceType?, fingerprint,
  port = 53317, protocol = "https", download = false, announce? = null)`.
  `sansAnnounce()` clears only `announce`. The UDP announcement uses
  `AnnounceMessage`, the same fields with concrete `port`/`protocol` and no
  `announce` key at all.
- `FileInfo(id, fileName, size, fileType, sha256?, preview?, metadata?)`,
  where `FileMetadata(modified?, accessed?)` uses ISO-8601 strings.
- `PrepareUploadRequest(info: PeerInfo, files: Map<String, FileInfo>)`.
- `PrepareUploadResponse(sessionId, files: Map<String, String>, multipart: Long?)`.
  `files` maps file id → per-file transfer token and contains only accepted
  files. `multipart` is the Flit-only chunk-size hint (§4.2).
- `PartialInfo(size: Long)` — the one field of a `GET /partial` answer.

The legacy v2.0 `announce` boolean is tolerated on read, never written
(`Model.kt:11,25`). Discovery answers an announcement with a `POST /register`
back to the announcer (`UdpDiscovery.kt:145-152`); hidden mode listens but never
registers back.

### 2.2 Route semantics (all implemented once in `ServerCore.kt:265-279`)

| Route | Method | Shape / result |
| --- | --- | --- |
| `/info` | GET | `200` + `selfInfo().sansAnnounce()` JSON (`ServerCore.kt:283-285`). |
| `/register` | GET, POST | Optional body decoded as `PeerInfo`; a non-blank fingerprint calls `delegate.onRegister(info, origin)`; `200` + self info either way (`ServerCore.kt:287-297`). A GET never decodes a body. |
| `/prepare-upload` | POST | `400 Invalid body` on empty/invalid body or zero files; delegate decides; `403 Rejected` on decline; `204` when no accepted file survives path sanitising; `200` + `PrepareUploadResponse` with a fresh UUID session id and per-file UUID tokens (`ServerCore.kt:299-379`). |
| `/upload` | POST | Query `sessionId`, `fileId`, `token` all required or `400 Missing parameters`. Claim checks session id + sender origin + token + `settled`; bad claim `403 Invalid token or IP address`; resumed base no longer held `409 Resume base no longer held`. Digest mismatch `422`, success `200`, other error `500` (`ServerCore.kt:393-666`). |
| `/cancel` | POST | Query `sessionId`; if it matches the live session for this origin, end it as cancelled; always `200` (`ServerCore.kt:1088-1100`). |
| `/partial` | GET | Query `token`; `200 PartialInfo(size)` when the sender holds a resumable prefix, otherwise `404` with `{}` (`ServerCore.kt:1019-1034`). |

A receiver finalises an upload only when the SHA-256 of the assembled body
matches `FileInfo.sha256`; an empty or absent declared hash is treated as "no
claim" (`ServerCore.kt:594-596,830-831`). Duplicate landing names are resolved by
`uniqueTarget()` as `base (1).ext`, `base (2).ext`, … (`ServerCore.kt:1226-1237`).
Incoming file names pass `sanitizeRelative()`: absolute paths, any `..` segment
and the characters `\\:*?"<>|\r\n` are rejected or replaced with `_`
(`ServerCore.kt:1194-1204`).

### 2.3 Sender contract (`ProtocolClient.kt`)

`sendFiles()` hashes every `OutgoingFile` from a freshly opened stream to get
`FileInfo.sha256`, assigns an `f<8 hex>` id, prepares with
`PrepareUploadRequest(self.sansAnnounce(), files)`, then uploads accepted files
through a `Semaphore(parallelism = 4)` and reports
`SendReport(sent, failed, sessionId, declined = false)`. It re-prepares a
single-file session on a refused claim so a resumed part can be re-claimed
(`ProtocolClient.kt:223-382,333-354`). `register()` answers discovery;
`info()` and `cancelTransfer()` are the thin diagnostics.

The **carrier-death contract** is the key API distinction: a returned
`SendReport` means the far side spoke (sent, refused or declined — the batch's
story is over); `TransferUnreachable` means no receiver answer exists and the
batch stays owed for the engine's hunt. Prepare-level `403` with
`X-Flit-Refusal: declined` yields a concluded `declined = true` report; a bare
`403`, a `409` or a `5xx` stays a `TransferException` (transient, retriable)
(`ProtocolClient.kt:63-73,271-290`).

## 3. Transport seams — `Link`, `LinkProvider`, `SendTarget`, `ServerDelegate`

### 3.1 `Link` / `LinkProvider` (`protocol/.../client/Link.kt`)

`Link` is a live byte pipe (`input`, `output`) with an optional `origin` label:
an IP on TCP, a device address over Bluetooth; `null` when the carrier cannot
tell. `LinkProvider.connect(host, port, tls, timeoutMs)` hands back one link per
exchange (always `Connection: close`). An additive overload
`connect(host, port, tls, timeoutMs, relayFor: String?)` defaults to ignoring the
hint, so TCP, Bluetooth and the sim providers are unchanged. Only
`RelayLinkProvider` reads `relayFor` (`Link.kt:34-45`).

`TcpLinkProvider` is the native transport; `TlsLink` upgrades *any* `Link` to
mutual TLS across its streams using an `SSLEngine` shim (`StreamTls`), which is
how RFCOMM and relay pipes speak the same wire TLS without a `java.net.Socket`.
`TlsLink` preserves the raw pipe's `origin`; TLS never changes the carrier label.

### 3.2 `SendTarget` (`ProtocolClient.kt:31-44`)

```kotlin
data class SendTarget(val ip: String, val port: Int, val tls: Boolean,
                      val relayFor: String? = null)
fun baseUrl(): String = "${if (tls) "https" else "http"}://$ip:$port"
```

`relayFor` is milestone E's one extra field. When set, `host:port` names a
relay and the hint names the peer fingerprint to pair with. Direct routes leave
it `null` and are byte-for-byte unchanged. It is threaded through every
`ProtocolClient` exchange (`info`, `register`, `prepare`, `upload`, `partial`,
`cancel`, `sendPanic`, `circleJoin`, `circleSync`).

### 3.3 Route kinds and relay policy (app side)

`FlitRoute.Kind { TCP, BLUETOOTH }` with `TCP` the default so stored prefs stay
valid; `FlitRoute.bluetooth(host) = Route(host, 0, tls = true, BLUETOOTH)` — a MAC
is never dialed as an IP, and BT routes carry port `0`
(`FlitRoute.kt:14-30`). `FlitRoute.candidates(live, remembered, manual)` returns
`(live + remembered + manual).distinct()`; `huntDelayMs` is
`2 s, 5 s, 15 s, 30 s`, then `60 s` forever; `accepts(answerFp, owedFp)` requires
an exact fingerprint match (`FlitRoute.kt:40-74`). `parseRelay("host:port")`
returns `null` for any malformed or out-of-range value — a bad typed relay is
invisible, never a crash (`FlitRoute.kt:57-64`).

Known-device policy lives in `FlitKnown.Device`
(`FlitKnown.kt:28-52`): `RoutePolicy { AUTO, WIFI_ONLY, BLUETOOTH_ONLY,
RELAY_ONLY }` and fields `fingerprint`, `alias`, `label`, `mac`, `relay`, `token`,
`routePolicy`, `relayFor`, `panicForward`, `scannedMs`. `relayFor` is the
per-device opt-in that gates all relay routing; a device with `relayFor = false`
is never sent through a relay. Storage is pure JSON with `ignoreUnknownKeys` and
a `decode()` that returns an empty list on a broken blob, so future additive
fields are safe (`FlitKnown.kt:58-73`).

Applied policy (all in `FlitEngine.kt`):

- **Normal dispatch** (`:2667-2689`): `BLUETOOTH_ONLY`, or a BT-kind peer under
  `AUTO`, uses `btClient` and `SendTarget(mac, 0, true)`. Relay is used when
  `known?.relayFor != false` and either a relay route exists or policy is
  `RELAY_ONLY`, producing `SendTarget(host, port, true, relayFor = fp)`; the
  address is `known.relay` → remembered relay route → global relay config.
  Otherwise direct TCP/Wi-Fi.
- **Circle dial** (`circleDial`, `:796-832`): remembered Wi-Fi route first,
  then a Bluetooth MAC, then relay only when `relayFor == true` and policy is
  `AUTO`/`RELAY_ONLY`. A `SYNC` alone remembers no route; a JOIN with an IP
  remembers `ip:53317` TLS TCP (`:845-852`, `:1946-1948`). BT MACs and relay
  pairings are not IP routes, so a member that joined over either needs a
  Known-device entry to be dialed back.
- **Panic fan-out** (`fanOutPanic`, `:462-505`): Bluetooth for
  `AUTO`/`BLUETOOTH_ONLY`; relay last for `AUTO`/`RELAY_ONLY` and only with
  per-device `relayFor`; direct Wi-Fi for `AUTO`/`WIFI_ONLY`. The count is of
  devices that actually answered `200`; no success is claimed for silent ones.

### 3.4 `ServerDelegate` (`ServerCore.kt:47-84`)

Application hooks, with defaults chosen so every existing delegate stays
source-compatible:

| Hook | Default | Contract |
| --- | --- | --- |
| `onRegister(info, ip)` | no-op | Called only for a non-blank fingerprint body. |
| `onPrepare(request, peerIp): PrepareDecision` | **required** | `Decline` or `Accept(ids)`. App declines QR-unknown devices and untrusted fingerprints (`FlitEngine.kt:967-989`). |
| `onUploadStart(sessionId, fileId, file)` | no-op | Once per file per session. |
| `onResumeStart(sessionId, fileId, file, prefixBytes)` | no-op | Only on an actual resumed prefix. |
| `onUploadProgress(..., bytes, totalBytes)` | no-op | At most ~10×/s per file. |
| `onUploadFinish(..., result: UploadResult)` | no-op | `Saved(path,bytes)`, `Streamed(bytes)`, `Mismatch` or `Error`. |
| `onSessionEnd(sessionId, cancelled)` | no-op | Once all files terminal. |
| `onPanic(alert, ip)` | no-op | Delivered only after signature, replay, dedup and hop checks. Never acts. |
| `onCircle(request, ip): CircleResponse` | `ok=false, reason="Circle support not enabled"` | The app owns governance and the log; the protocol layer only checks shape, size and cert binding. |

`ServerConfig` (`ServerCore.kt:86-110`) is likewise additive: alias `"Flit"`,
port `53317`, host `0.0.0.0`, `tls = true`, `destDir = flit-downloads`,
`downloadApiEnabled = false`, `resume = true`, and an optional `incomingSink`
that streams bodies into caller-provided streams (`IncomingSink.done(ok)` must
be safe to call exactly once).

## 4. Additive-only compatibility discipline

Every Flit-only feature is layered so a stock LocalSend v2.2 peer sees the v2.2
wire unchanged and an older Flit build keeps compiling and parsing. The rules,
with the code that enforces each:

1. **New routes never enter the v2.2 root.** `{partial, panic, circle/join,
   circle/sync}` live only under `/api/flit/v1` (`Protocol.kt:33-43`). The Ktor
   binder registers all of them, but a v2.2 sender simply never calls them
   (`ProtocolServer.kt:130-141`).
2. **New request headers are sender-side only.** `X-Flit-Client: 1` is sent on
   every prepare so a Flit receiver can mark its refusal; `X-Flit-Multi: 1` is
   sent only for turbo; `X-Flit-Resume` and `X-Flit-Base` are sent only on a
   resume/chunk attempt. A v2.2 receiver ignores all four (`ProtocolClient.kt:699-702`).
3. **New response fields are nullable and omitted when absent.**
   `PrepareUploadResponse.multipart` is populated only when the sender signalled
   multi and the receiver is a Flit peer; `explicitNulls = false` removes the
   key otherwise, so a v2.2 answer is byte-compatible (`ProtocolClient.kt:291-295`,
   `ServerCore.kt:370-378`).
4. **A deliberate refusal is distinguishable only for a marked sender.**
   `Decline` answers `403 Rejected` to a plain v2.2 peer; a Flit-marked sender
   additionally gets `X-Flit-Refusal: declined`, and only that header turns a
   `403` into a concluded `declined = true` report (`ServerCore.kt:311-324`,
   `ProtocolClient.kt:274-287`).
5. **New data-class fields have defaults and new methods have default bodies.**
   `SendTarget.relayFor = null`, `SendReport.declined = false`,
   `PanicAlert.hops = 0` / `originFp = ""`, `CircleRequest.body = null`,
   `CircleEvent.cert = ""`, all `LinkProvider` hint overloads, all new
   `ServerDelegate` methods: old callers and old stored JSON stay valid.
6. **Parsers are tolerant.** `WireJson.ignoreUnknownKeys = true`, `PeerInfo`
   tolerates the legacy `announce` field, `FlitKnown.decode()` treats a broken
   blob as empty, `parseContentRange()` accepts both RFC `bytes s-e/total` and
   the `bytes=s-e/total` spelling (the 2026-11-18 fix that had silently
   disabled turbo), and `QR.parseAddCode()` keeps v1 byte shape while reading
   v2/v3 hints (`WireJson.kt:10-16`, `ServerCore.kt:1117-1148`, `Qr.kt:58-137`).
7. **Validation is additive, never retroactive.** `CircleCore.validShape()` is
   type-agnostic bounds only, so adding a verb never changes an existing
   event's signature. `validFileBody()` is an extra type-specific gate used
   only for the `file` verb (`CircleCore.kt:292-324`).
8. **QR versions are supersets.** `v=1` writes address + optional `tls=1` only;
   `v=2` adds `mac`, `relay`, `token` and always writes `tls`; `v=3` adds
   `circle` and `invite`, omits blank hints, and never emits a key parameter
   (`Qr.kt:58-81,95-137`). A v1/v2 code still parses byte-for-byte.

### 4.1 Canonical bytes and signature algorithm

All signatures are Base64 of `SHA256withRSA` (or `SHA256withECDSA` for loaded EC
keys) over a domain-tagged, length-prefixed byte stream. The fingerprint is the
uppercase-hex SHA-256 of the certificate's DER (`TlsIdentity.kt:303-311`). The
same `TlsIdentity.sign` / `verify` primitive serves TLS identity, panic and
Circle; the `signPanic`/`verifyPanic` names are compatibility wrappers, not a
second crypto path (`TlsIdentity.kt:59-76,194-210`).

Canonical-field rule: **every field except the signature and the embedded
certificate is covered**; the certificate is bound to its claimed fingerprint
by its own SHA-256 DER *before* signature verification and pinning. This makes
a substituted cert unforgeable and prevents a forged first-contact event from
poisoning the shared pin store (`TlsIdentity.kt:198-206`, `CircleCore.kt:165-187`).

### 4.2 Multi-socket and resume extensions (Flit↔Flit)

- Preparation: sender adds `X-Flit-Multi: 1`; a Flit receiver sets
  `session.turbo` and answers `multipart = 8 MiB` (`ServerCore.kt:336,373-377`).
- Chunk claim: each stream POSTs to the normal upload route with
  `Content-Range: bytes <start>-<last>/<total>` and `X-Flit-Base: <prefix>`.
  The receiver keeps a `ChunkTracker` coverage ledger, seeds it with the resume
  base, and refuses a claim whose base disagrees with its own (`409 Resume base
  changed — start over`) or that arrives on a non-turbo session (`403`)
  (`ServerCore.kt:411-444`, `ProtocolClient.kt:523-612`).
- Coverage completion hashes the assembled temp and gates on the declared
  SHA-256 exactly like a plain upload; only one racing claim finalizes
  (`pf.finalizeLock`, `ServerCore.kt:805-861`).
- Resume probe: `GET /partial?token=<resumeToken>` answers the **contiguous
  base from zero**, never the on-disk length of a sparse temp
  (`ServerCore.kt:1019-1059`). `Protocol.resumeToken(info)` is the first 32 hex
  chars of `SHA256(fileName || 0x00 || size || 0x00 || sha256)`
  (`Protocol.kt:83-92`). A part is bound to the requester's origin;
  `partsBase()` refuses one with a different `senderIp`, so a Wi-Fi↔Bluetooth
  switch takes a clean full resend; an entry repopulated from a `.token`
  sidecar carries an empty `senderIp` and is claimable until the first re-attach
  re-binds it (`ServerCore.kt:1051-1059,1179-1191`).
- A **short** body with a failed digest is kept as a genuine prefix; a
  full-length body with a failed digest is deleted with its sidecars, so a
  corrupt base is never advertised (`ServerCore.kt:607-625,844-859`).

## 5. Relay contract — hello, core, endpoints, pairing

### 5.1 Hello line (`protocol/.../relay/RelayCore.kt`)

One ASCII line, space-separated, newline-terminated, bounded at `512` bytes:

```
FLITRLY1 req <targetFp> [<token>]      # sender: pair me with this fingerprint
FLITRLY1 sub <selfFp>   [<token>]      # receiver: I am this fingerprint
```

`parse()` accepts 3 or 4 parts only; fingerprint and token are non-blank and
bounded at `128` chars; any other verb is rejected (`RelayCore.kt:52-66`). A
token is optional on the wire: a relay configured with one requires an exact
match (constant-time compare, `RelayCore.kt:139-152`); a relay without one
ignores whatever arrived.

### 5.2 Pairing core (`RelayCore.kt:70-153`)

`RelayCore.offer(hello, ticket)` is a pure, synchronized state machine:

- FIFO parking per fingerprint, one partner per side; the arriving counterpart
  pairs with the oldest parked ticket (`Paired`) or parks (`Parked`).
- Same-mode hellos never pair (two senders or two receivers are not a story).
- A token mismatch yields `Refused("bad-token")` before any parking.
- `withdraw(ticket)` retires a dead parked connection so a ghost can never pair.
- The ticket is caller-chosen; the core has no sockets, clocks or timeouts.

### 5.3 Socket endpoints (`RelayEndpoints.kt`, `harness/.../RelayServer.kt`)

- `RelayLinkProvider.connect(..., relayFor)` dials the relay, writes the `req`
  line, requires a one-line `OK` reply, then clears the handshake timeout
  (`soTimeout = 0`, `keepAlive = true`) and wraps the pipe in client-mode
  `TlsLink`. Everything after the preamble is opaque end-to-end TLS
  (`RelayEndpoints.kt:35-59`).
- `RelaySubscriber.subscribe(host, port, selfFp, token, parkTimeoutMs)` writes
  the `sub` line, waits for `OK`, clears the read deadline and returns a raw
  `SocketLink`; the engine hands it to `ProtocolServer.serve(TlsLink(...))`
  server-mode (`RelayEndpoints.kt:74-109`, `FlitEngine.kt:1723-1727`). One
  subscription serves one exchange; prepare, `/partial` and each chunk arrive
  on their own re-subscribed pipes.
- `flit-cli relay [--port P] [--token T] [--park-timeout S]` is a thin wrapper
  over the same core. It assigns tickets `c1`, `c2`, …, writes `OK` to both
  sides on a pair and `ERR <reason>` on rejection, then blind-copies bytes in
  both directions with 64 KiB buffers. Logs contain only timestamps and opaque
  ticket ids (`pair c3 + c4`, `pair c3 + c4 closed`) — no fingerprint, byte
  count or content (`RelayServer.kt:75-198`). `parkTimeoutMs = 0` waits
  forever; a bounded value retires an unpaired slot with `ERR timeout`.

**Blindness invariant:** a malicious relay can refuse, delay, drop or pair
connections; it cannot forge a fingerprint, a file or a finished hash, because
identity proof and the digest gate run inside the TLS pipe. There is no
store-and-forward: both peers must be live, and the relay stores nothing.

## 6. Panic contract — alert, canonical, signature, hops, origin

### 6.1 Alert shape (`model/PanicAlert.kt`)

```
PanicAlert(id, senderFp, sentMs, text, hops = 0, sig = "", originFp = "")
```

`id` is a UUID; `senderFp` is uppercase hex; `originFp` is the original author
and is preserved across re-signed forwards. The alert is an additive wire type
under `/api/flit/v1`; receiving it never acts on the device — it is delivered
to `delegate.onPanic` and the app decides what a human sees.

### 6.2 Canonical bytes (`PanicCore.kt:48-83`)

Domain tag `FLITPANIC1`, then length-prefixed UTF-8 fields in this exact order:

```
field("FLITPANIC1"), field(id), field(senderFp),
writeLong(sentMs), field(text), writeInt(hops), field(originFp)
```

`field` writes a 4-byte big-endian UTF-8 byte length followed by the bytes, so
no delimiter can be smuggled through a note. **Hops are inside the signed
bytes**: a relay that bumps the count changes the signature and must re-sign
with its own identity; a bumped count with the original signature fails
verification. `sig` is excluded (it is the signature itself).

### 6.3 Create, verify, forward (`PanicCore.kt:85-190`)

- `create(senderFp, text, identity, hops=0)` rejects blank/negative fields,
  pins the identity's cert, sets `originFp = senderFp`, and signs the canonical
  bytes. The app trims text to `240` chars (`FlitEngine.kt:457`).
- `verify()` is a pure signature check against the cert pinned under
  `senderFp` (`PanicCore.kt:115-116`).
- `evaluate()`/`accept()` precedence is exactly
  `Invalid → HopLimit → OutsideWindow → Duplicate → Accept`: blank id/fp,
  negative hops or non-positive `sentMs` invalid; `hops >= 3` stops propagation;
  older than `6 h` or more than `5 min` in the future is outside the window; a
  duplicate id is answered politely but never delivered twice. `accept()` is
  an atomic check-and-record over a bounded `1024`-id FIFO; only `Accept`
  reserves the id (`PanicCore.kt:118-161`).
- `forward(alert, forwarderFp, identity, maxHops=3)` returns `null` when
  `hops + 1 >= 3`. Otherwise it replaces `senderFp` with the forwarder, sets
  `originFp = alert.originFp.ifBlank { alert.senderFp }`, preserves `id` and
  `text`, clears `sig` and re-signs with the forwarder's key
  (`PanicCore.kt:163-186`). The UI toasts
  "alert reached its hop limit — it cannot be forwarded further" when `null`.

### 6.4 Route and app gate (`ServerCore.kt:903-945`, `FlitEngine.kt:402-505`)

The route decodes the JSON alert, rejects blank `id`/`senderFp`/`sig` or
negative hops with `400`, requires a decodable `X-Flit-Panic-Cert` (else `403`),
pins that cert and requires its own fingerprint to equal `senderFp` **and**
`PanicCore.verify` to pass (else `403 Forged panic alert`). Then
`PanicCore.accept()` decides; only `Accept` calls `delegate.onPanic`. **Every
valid alert, including a duplicate or a stale one, gets a quiet `200`** so a
sender never retries a delivered alert forever.

The app's `onPanicReceived` gates trust on `originFp` (not `senderFp`) being a
QR-known fingerprint, dedups by id, renders alias/note/time in a banner, and
offers Acknowledge / Dismiss / Forward. Forward is an explicit tap and re-signs
the stored original, so a looped alert is deduplicated rather than shown as
new. Nothing is wiped, changed or auto-forwarded on any device. Two-phone mesh
hops remain **unverified** on this rig.

## 7. Circle contract — request, response, events, host answers

### 7.1 Request and response (`model/CircleWire.kt`)

```kotlin
CircleRequest(action, circleId, invite ?: null, memberFp = "",
              events = [], body ?: null)        // action ∈ {"join","sync"}
CircleResponse(ok, key = "", members = [], events = [],
               reason = "", serverCert = "", sig = "")
```

A refused join/sync is a well-formed **`200`** with `ok = false` and a human
`reason` — a caller can tell "not enabled / bad invite" from a dead carrier.
The group `key` is only ever in a successful JOIN answer; a SYNC answer carries
`key = ""` even for an already-known member (`FlitCircleHost.kt:193-203`). The
QR is a bearer invite, never a key; the key travels inside the pinned TLS
session.

### 7.2 Host-signed answers (`CircleWire.kt:60-108`)

`CircleResponse.canonical` writes domain tag `FLITCIRCLE1`, then `ok`, `key`,
`members` (count + each), `events` (count + each event's `id`, `circleId`,
`authorFp`, `lamport`, `type`, `body`, `createdMs` — **not** an event's `sig` or
`cert`), then `reason`. `sign()` puts the host's Base64 DER cert in
`serverCert` and signs the canonical bytes of the answer with `serverCert` and
`sig` blanked. `verify(r, expectedHostFp)` parses that cert, compares its own
SHA-256 DER to the expected host fingerprint, verifies the signature **against
the cert in the answer before touching the global pin map**, and only then pins
it. A rogue relay or on-path peer cannot substitute the key. The joiner calls
this verification before persisting the invite (`FlitCircleSync.kt:390-403`).

### 7.3 Event kinds and closed body shapes (`circle/CircleEvent.kt`, `FlitCircleSync.kt:175-234`)

`CircleEvent(id, circleId, authorFp, lamport, type, body, sig, createdMs, cert)`.
`type` is one of ten verbs, including `parcel`; `body` is a small JSON object:

| Verb | Body shape (exact keys) |
| --- | --- |
| `message` | `{"text":"..."}` |
| `file` | `{"name":"...","size":<non-negative integer>,"sha256":"..."}` — **exactly** `FILE_BODY_KEYS`, no `token`, `path` or `spoolPath` |
| `poll` | `{"pollId":"...","question":"...","options":["a","b"]}` |
| `vote` | `{"pollId":"...","option":<int>}` or `{"kickId":"...","approve":<bool>}` (app also emits `{"targetFp":"...","approve":<bool>}`) |
| `join` | `{"alias":"..."}` (host-routed; members cannot inject it) |
| `leave` | `{"memberFp":"..."}` |
| `kick` | `{"kickId":"...","targetFp":"...","mode":"admin"|"vote","approve":<bool>}` (field set varies by phase) |
| `role` | `{"targetFp":"...","role":"OWNER"|"ADMIN"|"MEMBER"}` |
| `rekey` | `{"keyB64":"...","gen":<int>}` (app adds a `members` array; host-authority only) |
| `parcel` | `{"toFp":"...","envelope":"..."}` — **exactly** `PARCEL_BODY_KEYS`, no extra key; closed gate and envelope in §13.3 |

`validFileBody()` enforces the exact key set and types: `name`/`sha256` non-blank
bounded strings, `size` a JSON number (the string `"12"` is refused). That is
what keeps the sender's local spool path off the wire: recipients resolve bytes
by `sha256`, never by a published path. The bytes themselves go by a direct
per-member `sendFiles` call from the sender's private spool — **not** the
send-later/bulk queue — and are accepted only on a hash-exact landing.

### 7.4 Event canonical, ordering, dedup, replay (`CircleCore.kt`)

Canonical bytes: domain tag `FLITCIRCLE1`, then id, circleId, authorFp,
`writeLong(lamport)`, type, body, `writeLong(createdMs)` — every field except
`sig` and `cert`. `verify()` binds a first-seen cert's own SHA-256 DER to
`authorFp` and verifies against that cert before pinning; an event without a
cert falls back to an already-pinned author (relayed senders).

Ordering is deterministic: `(lamport, authorFp, id)`, then type, body,
`createdMs`, `sig`, `cert` to break exact ties (`CircleCore.kt:195-205`), so all
members converge without a server. `dedup()` keeps one event per id and chooses
the winner by that order, not by arrival order (`:212-219`). `evaluate()`/`accept()`
use the shared bounded `4096`-id set with precedence
`Invalid → OutsideWindow → Duplicate → Accept`; callers **must verify first**
because `evaluate()` reserves ids before crypto (`:326-387`). `tally()` and
`kickTally()` count only each current member's latest signed vote, so changing
an answer appends a history event without double-counting (`:221-289`).

### 7.5 Route and host decisions (`ServerCore.kt:947-1007`, `FlitCircleHost.kt`)

Route checks, in order: body capped at `8 MiB`; `action` in `{join, sync}`;
`circleId` non-blank; `events.size <= 1024` and every event `validShape`;
`X-Flit-Circle-Cert` present and decodable; `memberFp` equal to that cert's own
fingerprint. A rejected shape is `400`, a bad identity `403`, a throwing
delegate `500`; every real decision is a signed `200`.

Host `onRequest` first refuses a wrong `circleId`. JOIN is exempt from the
roster gate but not from the invite: a case-insensitive invite match, a valid
fingerprint, a non-blank key and room below `MAX_MEMBERS = 500` are required;
the answer carries the key, the authoritative roster and the ordered seed log,
with no synthetic join event (only the joiner can sign its own join)
(`FlitCircleHost.kt:119-144`). SYNC is roster-gated and filters every incoming
event: correct circle, known type, bounded body, `validFileBody` for files,
`authorFp == requester`, no injected JOIN, REKEY only from owner/admin, a
verifiable cert and signature, then log-id dedup and `CircleCore.accept`.
A departure without a rekey event triggers a fresh 32-byte random key and a
signed `rekey` event; a later sync from a departed fingerprint is refused
(`FlitCircleHost.kt:146-242`). Roles are local, fingerprint-pinned authority
labels; an `ADMIN` (or `OWNER`) can kick a non-owner, a plain member can only
open a vote-kick, and nobody can appoint a second `OWNER`.

### 7.6 Sync wire shape: delta vs full log

The route itself does not distinguish a delta from a full log — `events` is just
a bounded list. The app uses two modes (`FlitCircleSync.kt`):

- **Fan-out** (`fanout`, `:673-716`) sends a **per-peer delta** since the last
  acknowledged event, keyed by a `(lamport, id)` watermark
  (`circleDelta`, `:108-119`). An unseen peer, or one whose watermark event was
  trimmed out of the bounded log, gets the full bounded log instead. A small
  probe is still sent for an empty delta.
- **Calm repair** (`sync`, `:461-515`) ignores watermarks and re-offers the
  bounded full-log batch (`circleRepairBatch`, `:121-123`) to every reachable
  member; this is what heals a missed delta. Sync passes are at least `30 s`
  apart, backed off per peer, and capped at `4` concurrent dials.

> **Doc simplification:** `docs/circle.md` ("Event log" section) says *every*
> SYNC posts the full bounded log. In the code, only the repair tick does;
> fan-out is delta-based. The wire shape (`CircleRequest.events`) is unchanged
> either way, so this is a behaviour detail, not a contract break.

### 7.7 File-drop announcement

`circlePostFile` publishes a `file` event whose body is exactly
`{name,size,sha256}` and creates a sender-local delivery record keyed by that
SHA. The engine drives a calm per-member `sendFiles` delivery from the private
spool (not the send-later queue). Per recipient, the local status is one of
`pending`, `sent`, `refused`, `failed`; `sent`/`refused`/`failed` are terminal,
`pending` is owed, and a ~30 s worker retries owed rows to current members.
Status lives only in the sender's `CircleDelivery` record — it is never an
event and never gossiped. A later joiner folds the announcement and sees the
card but receives no old bytes (no backfill); its card says
"file drop · open to view if it arrived".

## 8. QR pairing codes (`Qr.kt`)

`flit://add?...` (also tolerates `flit://pair`), parsed with any parameter
order. Fields: `v`, `ip`, `port`, `tls` (`1`/`0`), `fp`, `alias`, and from v2
`mac`, `relay` (`host:port`), `token`; from v3 `circle`, `invite`. No `key`
parameter exists or is accepted. Rules:

- `v=1` requires `ip` plus a valid port; later hints are ignored; `tls` is
  written only when true, preserving deployed byte shape.
- `v=2`/`v=3` may drop `ip` when a well-formed relay is present, always write
  `tls`, and range-check the port only when an ip is present.
- `v=3` additionally requires a non-blank `circle` and `invite`; an invite code
  missing either is refused, never silently downgraded to a pairing.
- The fingerprint is uppercased; the scanner marks the code `scanned` and pins
  it, satisfying the "only trusted" gate. Trust is this QR/`TlsIdentity`
  fingerprint pinning, **not** OS Bluetooth bond state.

A v3 tap-to-join link (`flit://add?v=3&…&circle=…&invite=…`) is handled by a
`VIEW` intent filter and joins without pasting.

## 9. Response-code and decision quick matrix

| Route | Code | Meaning |
| --- | --- | --- |
| prepare | `403` + `X-Flit-Refusal: declined` | A live Flit peer deliberately declined — concluded batch. |
| prepare | bare `403`, `409`, `5xx` | Transient — `TransferException`, retriable; not a conclusion. |
| prepare | `204` | No accepted file survived (or all names invalid). |
| upload | `200` / `422` / `403` / `409` / `500` | Saved / digest mismatch / bad claim / resume base lost / error. |
| partial | `200 PartialInfo` / `404 {}` | Prefix held / start over. |
| panic | `400` / `403` / `200` | Bad wire / bad identity or forgery / accepted (also duplicates and stale). |
| circle | `400` / `403` / `500` / `200` | Bad shape or size / cert mismatch / delegate error / signed decision (including refusal). |
| relay | `OK` / `ERR <reason>` | Paired / refused (`bad-hello`, `bad-token`, `timeout`, `partner-gone`); then blind copy. |

Full retry/keep/drop conclusions are in [Failure modes](FAILURE-MODES.md);
trust and replay reasoning in [Security](SECURITY.md).

## 10. Evidence ledger

| Claim tier | What is actually established |
| --- | --- |
| **Code reality** | Every constant, route string, header, DTO shape, canonical byte layout, pairing state machine and policy rule listed above. |
| **Pure-test JVM** | Canonical/sign/verify for panic and Circle, forged-author rejection, replay and dedup windows, Lamport convergence, both `Content-Range` spellings, restart probe/attach, failed-chunk-then-resume, relay pairing/isolation/token, DTO and `LinkServer` parity over the in-memory duplex pipe through the real hand-HTTP + TLS stack. |
| **Harness/sim** | `flit-cli relay` pairs a real 3 MB protocol send hash-exact with TLS inside the pipe; wrong token refused; unpaired subscription times out; dead dial surfaces `TransferUnreachable`. Two in-process TLS Circle nodes sync message, poll re-vote, file drop and kick/rekey; `bt-sim` runs the production client over the pipe carrier. |
| **On-device** | Panic QR-v2 pairing and one-phone↔box banner exchange; relay dial/subscribe with a 200 MiB camera shot and a 300 MiB mid-body relay kill resuming hash-exact from the exact prefix; Circle two-node join/message/leave and phone-as-host; the 2026-11-26 three-node baseline walk, which is DEVICE evidence for a 3-identity Circle (phone plus two CLI identities); file-drop landing hash-exact; QR v3 tap-to-join. |
| **Not verified** | A real phone-to-phone RFCOMM transfer (no second phone; bench box has no Bluetooth adapter); a Circle mesh larger than three identities; multi-hop panic forwarding on real devices; Wi-Fi Aware (feature absent on the rig phone). Outbound phone→host Circle fan-out is test-evidenced with an injected carrier but was not cleanly device-confirmed. |

The current rig baseline is 396 tests = 136 protocol + 228 app + 32 harness,
read from a green `--rerun-tasks` run of `:protocol:test`,
`:app:testDebugUnitTest` and `:harness:test`. That is a test count, not a
device claim for the UI or CLI layers.

## 11. Cross-links

- Transport ladder and the dated contract revisions:
  [transport-bluetooth.md](transport-bluetooth.md).
- Carrier seam, RFCOMM listener and the sweep: [bluetooth-carrier.md](bluetooth-carrier.md).
- QR-only, known devices and panic behaviour: [qr-only-and-panic.md](qr-only-and-panic.md).
- Relay design and evidence: [milestone-e.md](milestone-e.md).
- Circle design, governance and limitations: [circle.md](circle.md).
- Process-death checkpoints and sidecars: [milestone-c.md](milestone-c.md).
- Carrier migration: [milestone-a.md](milestone-a.md).
- Open-file action and FileProvider roots: [open-file-and-sharing-restore.md](open-file-and-sharing-restore.md).

## 12. Known simplifications in the existing docs

- `docs/circle.md` says every SYNC posts the full bounded log; code uses
  per-peer deltas on fan-out and the full log only on the repair tick (§7.6).
- `Model.kt:6-12` claims default-valued `port`/`protocol` are omitted from
  register/info answers; the shared `WireJson` config sets
  `encodeDefaults = true`, so they are present (§2.1).
- A few code comments (`CircleEvent.kt:28-29`, `CircleCore.kt:310-312`) call
  `sendFiles` the "bulk transfer path/queue"; the corrected doc vocabulary is
  a direct per-member `sendFiles` delivery, explicitly **not** the app's
  send-later/bulk queue (§7.3, §7.7).
- `docs/qr-only-and-panic.md` says `forward` returns null once
  `hops >= DEFAULT_MAX_HOPS` (3). The code refuses the *next* hop when
  `hops + 1 >= 3`, i.e. at `hops = 2` (`PanicCore.kt:174-175`), and `PanicTest`
  pins that (`forward(hops = 2) == null`). Separately, `evaluate` flags
  `hops >= 3` as `HopLimit` and the route drops it without delivery. The
  precise rule is in §6.3.

## 13. Courier contract — parcel, envelope and carry bag (2026-11-27)

### 13.1 Files and layer

The courier layer lives in `protocol/.../courier/` (`CourierEnvelope.kt`,
`CourierCarryCore.kt`), package `app.flit.share.protocol.courier`. It is
JVM-pure: no Android import, no socket, no file or preference persistence. The
tenth Circle verb is added in `circle/CircleEvent.kt`; any shared change in
`circle/CircleCore.kt` is additive only and leaves existing canonical bytes,
validity and acceptance rules unchanged.

A parcel travels as a `parcel` event inside `CircleRequest.events` over the
existing `/circle/sync` route. There is no new route, header or request field.
The carrier handles the `envelope` string as opaque bytes; the sender encrypts
the events to the recipient's device certificate. No file bytes enter a parcel.

### 13.2 Closed constants

Protocol-owned constants are read from `CircleEvent` / `CircleCore`; the courier
object facade repeats values for callers. `PARCEL` is declared in
`CircleEvent.TYPES`, while `CLOCK_SKEW_MS`, `MAX_FIELD_CHARS`, `MAX_SIG_CHARS` and
`MAX_CERT_CHARS` are reused from `CircleCore`, not declared in the courier
package. The courier replay window is the full parcel TTL (`MAX_PARCEL_TTL_MS`,
24 h), **not** `CircleCore.REPLAY_WINDOW_MS` (6 h, the Circle-event window); only
the future-side skew stays `CircleCore.CLOCK_SKEW_MS`. The `CourierCarryCore`
object repeats the top-level `MAX_PARCELS`, `MAX_SEEN` and `MAX_PARCEL_TTL_MS`
values for callers that name the core explicitly — a facade, not a second source
of truth. Values below apply to the 2026-11-27 layer.

| Constant | Value | Meaning |
| --- | --- | --- |
| `PARCEL` | `"parcel"` | Tenth Circle event verb, in `CircleEvent.TYPES` |
| `PARCEL_BODY_KEYS` | `{"toFp","envelope"}` | Closed body key set |
| `MAX_ENVELOPE_CHARS` | `12 * 1024` | Base64 cap; framed body stays under `CircleCore.MAX_BODY_CHARS` (16 KiB) |
| `MAX_PARCELS` | `256` | Bounded carrier bag |
| `MAX_SEEN` | `4096` | Bounded id reservation, shared by `evaluate` and `fresh` |
| `CLOCK_SKEW_MS` | `5 * 60 * 1000L` (5 min) | Future tolerance, reused from `CircleCore` |
| `MAX_PARCEL_TTL_MS` | `24 * 60 * 60 * 1000L` (24 h) | Parcel lifetime ceiling **and** the courier replay window for `open` and `evaluate`; `expiresMs = createdMs + MAX_PARCEL_TTL_MS` |
| `MAX_FIELD_CHARS` | `256` | Reused from `CircleCore` for bounded string fields |
| `MAX_SIG_CHARS` / `MAX_CERT_CHARS` | `1024` / `8 * 1024` | Reused from `CircleCore` for `sig`/`cert` |
| `KEY_BYTES` / `IV_BYTES` | `32` / `12` | AES-256-GCM content key and IV |
| `ALG` | `"AESGCM+RSAOAEP256"` | Only accepted algorithm value |
| `FLITCOURIER1` | domain tag | Envelope signature canonical bytes |
| `FLITCARRY1` | domain tag | Carry-state bag digest, not an envelope signature |

`expiresMs` is never stored in the envelope: `parcelOf` derives it once as
`createdMs + MAX_PARCEL_TTL_MS`, so two copies of one parcel cannot carry
competing expiries. The only accepted `alg` value is `"AESGCM+RSAOAEP256"`;
a different value is Invalid at `verify` and `open`.

### 13.3 Parcel body

`PARCEL = "parcel"` joins `CircleEvent.TYPES` as the tenth verb. Its body is
exactly `{"toFp":"<UPPERCASE fingerprint>","envelope":"<Base64 envelope bytes>"}`:
the key set is the closed `PARCEL_BODY_KEYS = {"toFp","envelope"}`.
`validParcelBody` refuses an extra key (`token`, `path`, `spoolPath`, …), a
blank or over-cap `toFp`, and a blank or over-cap `envelope` before any id is
reserved. `parcelOf` uppercases `toFp` and also requires non-blank `cert`,
`CircleCore.validShape` and an expiry inside the TTL bound. The Base64 cap of
`MAX_ENVELOPE_CHARS` (about 9216 raw bytes) keeps the framed body under
`CircleCore.MAX_BODY_CHARS` (16 KiB).

`CourierParcel(id, toFp, fromFp, createdMs, expiresMs, event)` holds the signed
event; `CarryState(parcels)` is the bag. The `Decision` sealed interface has
exactly `Accept`, `Duplicate`, `Expired`, `OutsideWindow`, `Invalid` and
`Full`.

### 13.4 Envelope canonical bytes and verification

```
CourierEnvelope(
    id, circleId, fromFp, toFp, createdMs: Long,
    alg, ivB64, cipherB64, keyWrapB64, sig = "", cert = "")
```

`seal(events, circleId, fromFp, toFp, recipientCert, identity, nowMs)` mints a
UUID id, generates a fresh 32-byte content key with `SecureRandom`, encrypts a
`WireJson` array of `CircleEvent` with AES-256-GCM, wraps the key with
RSA-OAEP/SHA-256 to the recipient certificate, and signs the canonical bytes
with `TlsIdentity.sign`. The content key exists only inside `seal` and the
wrapped blob. Without a recipient certificate, `seal` refuses.

Canonical bytes use domain tag `FLITCOURIER1` and the local `field` helper: a
4-byte big-endian UTF-8 byte length followed by the bytes. The order is
`field(tag)`, `field(id)`, `field(circleId)`, `field(fromFp)`, `field(toFp)`,
`writeLong(createdMs)`, `field(alg)`, `field(ivB64)`, `field(cipherB64)`,
`field(keyWrapB64)`. `sig` and `cert` are excluded. `encode` appends
`field(sig)` and `field(cert)`; `decode` reverses it and returns `null` on any
malformed, missing or oversized field. The Base64 string placed in the parcel
body is bounded by `MAX_ENVELOPE_CHARS`.

`verify(e)` parses `cert`, compares the certificate's own SHA-256 DER to
`fromFp`, checks the signature against that certificate before pinning, and
pins only after the check, as `CircleCore.verify` does; a substituted
certificate cannot poison the pin cache. It returns false for a tampered
`cipherB64`, `ivB64`, `keyWrapB64`, `alg`, `circleId`, `toFp` or `createdMs`,
and for a blank or malformed `sig` or `cert`.

`open(e, identity, nowMs)` verifies the envelope signature and recipient first,
then applies a replay window to `createdMs`: the past side is the full parcel
TTL (`MAX_PARCEL_TTL_MS`, 24 h), the future side `CircleCore.CLOCK_SKEW_MS`
(5 min). That 24 h courier replay window is deliberately longer than Circle's
6 h `REPLAY_WINDOW_MS`, so a courier can still open a parcel throughout its
carried lifetime. It then decrypts with the recipient's private key and parses
the bounded event array. It owns no replay set and never calls
`CircleCore.evaluate` or `accept` on an envelope id; authorship is checked by
`CircleCore.verify` at fold time. The envelope carries no `expiresMs`.

### 13.5 Carry digest tag and decisions

`canonical(p)` uses the separate domain tag `FLITCARRY1`: tag, length-prefixed
`id`, `toFp`, `fromFp`, `writeLong(createdMs)`, `writeLong(expiresMs)`, then the
envelope string, with `event.sig` and `event.cert` excluded. It is a bag digest
for carry state and later receipts, not an envelope signature; `FLITCARRY1` and
`FLITCOURIER1` are distinct by design.

The carry core exposes `validParcelBody`, `parcelOf`, `canonical`, `order`,
`evaluate`, `fresh`, `fold`, `select`, `ack`, `expire`, `merge`, `clear` and
`encode`/`decode` for `CarryState`. `evaluate` follows exactly
`Invalid → Expired → OutsideWindow → Duplicate → Full → Accept`; only `Accept`
reserves an id in the bounded `seen` set shared with `fresh`, so a `Full` or
`OutsideWindow` refusal can be retried. Expiry is decided first, so a
TTL-elapsed parcel is always reported `Expired`; the window's remaining role is
to reject a parcel dated further than `CircleCore.CLOCK_SKEW_MS` in the future.
The courier replay window is the full `MAX_PARCEL_TTL_MS` (24 h), matching
`open`, not Circle's 6 h `REPLAY_WINDOW_MS`. It compares two positive timestamps
by subtraction, never `nowMs ± window`, so an extreme clock cannot overflow into
a false `Accept`. `nowMs >= expiresMs` is never selected.
`order` delegates to `CircleCore.order` on the underlying events; `fold` and
`merge` dedup by id and trim to `MAX_PARCELS` by `order`, so arrival order does
not change the bag. `select` returns live parcels for the normalised `toFp`,
ordered and capped; `ack` and `expire` are inert on unknown ids. `decode` is
total: a null, blank, truncated, wrongly typed or otherwise corrupt blob
decodes to an empty bounded state. A parsed blob keeps the first row for a
duplicate id, drops rows with blank or oversized id/`toFp` or non-positive
`createdMs`, and repairs a numeric expiry that disagrees with `createdMs +
MAX_PARCEL_TTL_MS`. Decode has no clock; `expire(state, nowMs)` and `select`
apply wall-clock expiry.

### 13.6 Compatibility and evidence boundary

The addition is confined to a new verb, new JVM-pure files and the caller-owned
bag. `CircleCore.validShape` stays type-agnostic bounds, so no existing event's
signature or acceptance changes; `validParcelBody` is the type-specific gate,
as `validFileBody` is for `file`. No v2.2 route, DTO, header or status code
changes. The `/circle/sync` route keeps its signed `200` answers; a malformed
parcel body is refused before an id is reserved. An older Flit build keeps
parsing its existing events; the parcel bytes are framed by the single
envelope codec that the later QR layer reuses unchanged. The durable bag is
owned outside the protocol module: `courierBag` on `CircleState` in the app and
`courier-bag/` in the CLI. `TlsIdentity`'s pin cache stays bounded at 512 and
gains the sender's certificate only after `verify` succeeds.

CODE establishes the constants, the two domain tags, the canonical field
order, the closed body keys and the decision precedence. PURE JVM tests cover
canonical bytes, `encode`/`decode` bounds, tamper rejection, `seal`/`open`,
recipient wrapping, certificate binding and bag decisions. Carrier (`SIM-HARN`)
and three-node (`DEVICE`) handoffs are dated 2026-11-30 and 2026-12-02; no such
evidence is claimed for this layer. A courier "handoff" means a peer accepted an
offered parcel (a transport OK), never that the parcel was opened or that file
bytes landed on the recipient. See
[courier-envelope.md](roadmap/courier-envelope.md) and
[courier-carry.md](roadmap/courier-carry.md).

## 14. CLI courier contract — bag directory and local state (2026-11-30)

### 14.1 Files and layer

The CLI carrier is `harness/src/main/kotlin/app/flit/share/harness/CourierCli.kt`,
object `CourierCli` in package `app.flit.share.harness`. It owns only storage,
state transitions, status output and the one-shot command. Encryption stays in
`protocol/.../courier/CourierEnvelope.kt`; the parcel codec, expiry and quota
stay in `protocol/.../courier/CourierCarryCore.kt` (§13). The CLI imports no
Android class and adds no route, header, DTO field or status code. It parses its
arguments only through `ParsedArgs` from `Harness.kt`, loads identity through
`loadOrCreateIdentity()`, and roots every path in `harnessHome()`, so
`FLIT_HARNESS_HOME` isolates two nodes on one box as it does for `circle`.

`run(args: ParsedArgs): Int` does one operation — load state, act, save state —
and returns a process code: `0` on success, `EXIT_BAD_ARGS = 2` for a bad
argument or a missing bag, `EXIT_OPEN_REFUSED = 3` when a parcel fails `open`.
`usage(): String` is the single source of the `courier` help text; `Harness.kt`
adds one dispatch line and extends `ParsedArgs.VALUE_FLAGS` with `--bag`, `--to`,
`--cert` and `--ttl` (`--text`, `--file` and `--circle` already exist). There is
no second parser.

### 14.2 Command surface

```
courier drop   --to FP --text NOTE | --file PATH [--circle ID] [--cert B64] [--ttl MS] [--bag DIR]
courier carry  [--bag DIR]
courier receive --bag DIR
courier status [--bag DIR]
courier sweep  [--bag DIR]
```

| Subcommand | Operation |
| --- | --- |
| `drop` | Seal a signed Circle event into a new parcel for `--to` and write `<id>.parcel` into the bag. Refuses before writing when the certificate fingerprint differs from `--to` or when the bag is at the courier-carry.md quota. |
| `carry` | Verify the local bag and move verified rows `pending → carried`; it does not open or forward anything. |
| `receive` | Open each parcel addressed to the local identity, fold a returned event into `circle.state` through `CircleCore.order`/`dedup`, and move the row to `delivered`. Parcels for another fingerprint keep their prior state in the bag. |
| `status` | Print one line per row with a padded state value and a closing count by state. No parcel content and no fingerprint beyond the short form is printed. |
| `sweep` | Drop parcels whose `nowMs >= expiresMs`, enforce quota, and remove envelope files whose row is `expired` or `refused`. |

`drop` takes the recipient certificate from the `cert` field of a signed event by
that fingerprint in `circle.state`, or from `--cert B64` for a member whose event
has not arrived. Both paths check `TlsIdentity.fingerprintOf(cert)` against
`--to` before sealing, so a parcel cannot be addressed to one fingerprint and
encrypted to another. `--ttl` injects `nowMs` for sealing and expiry checks; it
is not a new TTL ceiling. The stored expiry is still derived by
`CourierCarryCore.parcelOf` as `createdMs + MAX_PARCEL_TTL_MS`, never read from
the envelope.

### 14.3 On-disk layout

All paths are relative to `harnessHome()`; `--bag` overrides only the bag path.

| Path | Contents |
| --- | --- |
| `courier.state` (`STATE_FILE`) | Local-only bookkeeping, one JSON row per parcel. Never written into a Circle event. |
| `courier-bag/` (`BAG_DIR`) | One `<envelope-id>.parcel` file per parcel. This directory is the portable handoff unit. |
| `courier-received/` (`RECEIVED_DIR`) | Opened event JSON after a successful handoff; a local record, never re-sent. |
| `courier-rejected/` (`REJECTED_DIR`) | Parcels that failed `open`; quarantined, not folded and not re-exported. |

`bagDir(args: ParsedArgs)` resolves `--bag` when present and otherwise
`harnessHome()/courier-bag`; `statePath(args: ParsedArgs)` is
`harnessHome()/courier.state`. State writes are one-shot and whole-file, as
`saveCircleState` is; no command holds a load→modify→save transaction open
across another CLI process on the same home.

### 14.4 State row schema and transitions

`Row` is `encodeRows(rows)` / `decodeRows(raw)` JSON, one object per line,
decoded with `WireJson.json` under `ignoreUnknownKeys = true`. A null, blank,
short or corrupt file decodes to an empty list, the same tolerance `circle.state`
uses for a broken blob. Each row is:

| Field | Type | Meaning |
| --- | --- | --- |
| `id` | String | Envelope id from `CourierEnvelope.seal`; also the `<id>.parcel` file stem. |
| `recipientFp` | String | Normalised uppercase-hex addressee (`--to`). |
| `senderFp` | String | Normalised uppercase-hex signer (the local identity). |
| `circleId` | String | Circle the envelope is bound to (`--circle`). |
| `state` | String | One of `pending`, `carried`, `delivered`, `expired`, `refused`. |
| `createdMs` | Long | Seal time. |
| `expiresMs` | Long | `createdMs + MAX_PARCEL_TTL_MS` (24 h), derived once by `parcelOf`; the envelope carries no expiry. |

Fingerprints are normalised uppercase hex as in `normalized()` in `Harness.kt`.
`nextState(row, kind, nowMs)` applies exactly these transitions:

| `Kind` | From | To | Condition |
| --- | --- | --- | --- |
| `CARRY` | `pending` | `carried` | Bag verified by `carry`. |
| `RECEIVE_OK` | `pending`, `carried` | `delivered` | `open` returned the event for the local identity. |
| `RECEIVE_REFUSED` | any | `refused` | `open` reported a refusal. |
| `SWEEP` | any | `expired` | `nowMs >= expiresMs`. |

A thrown `open` is not a conclusion: `receive` records no transition, leaving
the row at its prior state (`pending` or `carried`) and the envelope file in
`courier-bag/` for a later attempt.
This matches the carrier-death rule used for Circle file delivery. A duplicate
envelope id produces no second fold: `CircleCore.dedup` drops it and the row
moves straight to `delivered`. `delivered` is a local-only status: this device
opened the parcel and folded its event. No acknowledgement travels back to the
sender, so no row claims remote proof of delivery. It is distinct from the app's
`handed` handoff outcome (§15.4), which means only that a peer accepted an
offer.

### 14.5 Bag directory as portable unit

`drop` stores `CourierCarryCore.encode(CourierCarryCore.parcelOf(envelope))` byte-for-byte
in `<id>.parcel`; `carry` and `receive` do decode parcels (`CourierEnvelope.decode`,
`CourierCarryCore.parcelOf`) to verify, open and fold them, but the CLI never
rewrites or re-signs sealed bytes, so encode→decode→encode of an unopened parcel
is identical (§13.5). One `.parcel` file corresponds to one row id in
`courier.state`; a file with no row is ignored by `receive` and `sweep`, never
folded.

The operator copies `courier-bag/` to the next machine by USB or any file copy;
the carrier implements no transport of its own and adds no `LinkProvider`. A
node that is not the addressee holds a parcel for the next courier and re-exports
it unchanged. `receive` folds a file only after `CourierEnvelope.open` returns
its event for the local fingerprint; governance and replay stay with the
protocol layer, and the CLI fold adds no reducer and no second signature path.
`open` failure moves the file to `courier-rejected/` and sets `refused`; a throw
leaves the file in the bag.

### 14.6 Wire boundary

Every `.parcel` byte is produced and consumed by the 2026-11-27 codecs in §13:
the `parcel` verb, its closed `{"toFp","envelope"}` body, the `FLITCOURIER1`
envelope signature and the `FLITCARRY1` bag digest. The CLI adds no v2.2 route,
header, request field, status code or QR key parameter. Rows in `courier.state`
are local-only; no fingerprint, row, state value or parcel content is extracted
from the bag into a Circle event, and no parcel content is printed or logged.
This layer claims no device evidence: the bag moves between directories on one
box only. See [courier-cli.md](roadmap/courier-cli.md).

## 15. App courier contract — postParcel, courierBag and the route boundary (2026-12-01)

### 15.1 Layer and ownership

This is the App layer over §13 (protocol) and after §14 (CLI). It lives in
`app/src/main/kotlin/app/flit/share/`: `FlitCircleSync.kt` owns the wire post and
the worker handoff path, `FlitCircleStore.kt` owns the durable bag field,
`FlitEngine.kt` owns `courierHandoffs`, `circleCourierDrop` and the sweep, and
`CourierCardUi.kt` / `CircleUi.kt` / `MainActivity.kt` own the read-only
projection. No protocol constant, canonical byte layout or envelope codec is
redefined here; the app calls `CourierEnvelope.seal` / `verify` / `open` and the
`CourierCarryCore` reducers with the current bag and current time.

### 15.2 `postParcel`, pre-fanout sha registration and the closed body gate

`FlitCircleSync.postParcel(toFp, envelopeB64, shas)` publishes one `parcel`
event on the existing `post` path. It writes a body with exactly two keys —
`toFp` normalised by `circleFp()` (uppercase) and `envelope` set to the Base64
`CourierEnvelope.encode` text — then signs and upserts through `emit`. It
registers the caller's file `shas` with `courierTrack`, keyed by the emitted
event id, **immediately after `emit` and before `fanout`**, then fans out. The
order is a contract: a peer can answer synchronously inside `fanout`, so the sha
keys must already be registered for an OK to publish `handed`. The engine passes
`shas` to `postParcel` and no longer calls `courierTrack` itself.
`PARCEL = "parcel"` is the tenth verb already in `CircleEvent.TYPES` (§13.3);
there is no new verb table, phase field or body builder outside this path.

| Gate | Contract |
| --- | --- |
| Mint check | `circleBodyValid(CircleEvent.PARCEL, body)` gains one `PARCEL` case that delegates to `CourierCarryCore.validParcelBody(body)`; it does not reimplement the key set. |
| Closed keys | `PARCEL_BODY_KEYS = {"toFp","envelope"}` only. An extra key (`token`, `path`, `spoolPath`, `parcelId`, `sha256`, `name`, `size`, phase or any other) is refused. No handoff event and no `parcelId` body key exist; the parcel id used for sha registration is the signed event id. |
| Bounds | Body non-blank and at most `CircleCore.MAX_BODY_CHARS` (16 KiB); `toFp` a non-blank string at most `MAX_FIELD_CHARS` (256); `envelope` a non-blank string at most `MAX_ENVELOPE_CHARS` (12 * 1024). The cap keeps the framed body under the Circle body limit. |
| Refusal | A failed gate returns before `CircleCore.create`, `FlitCircleStore.upsert`, sha registration and `fanout`: no signed event, no wire byte, no bag row. The caller's source events are unchanged. |
| Sha registration | `shas` are trimmed and lowercased, then stored process-locally by the emitted event id through `courierTrack` between `emit` and `fanout`. A carrier holding only ciphertext learns no sha and publishes no handoff. |
| Inbound outer gate | A `parcel` enters the bag only when its `circleId` matches, its body passes `circleBodyValid`/`validParcelBody`, `CircleCore.verify` passes, **and its `authorFp` is a current roster member after `circleFp()` normalisation**. `CourierCarryCore.evaluate` runs only after those gates; only `Accept` calls `fold` and persists a bag row. `Invalid`, `Expired`, `OutsideWindow`, `Duplicate` and `Full` leave the bag unchanged. |
| Inbound inner gate | Events opened from an envelope keep the existing gate: same circle, a current-member author after normalisation, no injected `join` or `rekey`, and `fresh()` verifies every signature before anything is persisted. |

`FlitEngine.circleCourierDrop(events, toFp)` obtains the recipient certificate
from a signed event by that fingerprint or the host roster answer, calls
`CourierEnvelope.seal`, then posts the returned envelope through `postParcel`
together with the file shas of the signed events. With no certificate the drop
is refused before any post and the source events stay in the sender's own log.

### 15.3 `CircleState.courierBag`

`FlitCircleStore.CircleState` gains one additive, defaulted field:

```
courierBag: String = ""   // CourierCarryCore.encode(CarryState)
```

| Aspect | Contract |
| --- | --- |
| Default | `""` is an empty bag. Older rows and existing named-argument constructors decode without the key (`WireJson.ignoreUnknownKeys = true`); no migration is required. |
| Codec | `FlitCircleStore.encode` keeps the string verbatim; `decode` runs `CourierCarryCore.decode` and normalises it. A null, blank, truncated or otherwise corrupt blob becomes an empty bounded bag and no exception reaches a caller. |
| Atomicity | Every bag write goes through `FlitCircleStore.update(ctx) { ... }`, which holds `ioLock` across the whole decode → fold → encode → persist sequence. A bag write cannot interleave with a sync `upsert`, a delivery write or another bag write, and cannot clobber the event log, roster or delivery statuses. |
| Reducers | Protocol ones only: `fold` on `Accept`, `ack` after a peer's OK answer to the offer (offer accepted, **not** proof the parcel was opened), `expire` on the sweep. The app has no `merge` step: verified incoming events reach the signed log through `FlitCircleSync.fresh() → persistEvents() → FlitCircleStore.upsert`, which dedups by id. `CourierCarryCore.fold` dedups by id and trims to `MAX_PARCELS = 256` by `CourierCarryCore.order`, not arrival order. |
| Selection and TTL | `CourierCarryCore.select(bag, fp, nowMs)` returns live parcels for the normalised addressee, deduped by id and capped at `CircleCore.MAX_EVENTS_PER_REQUEST` (1024). `expiresMs` is derived only as `createdMs + MAX_PARCEL_TTL_MS` (24 h) by `parcelOf`; the app never extends a TTL. |
| Sweep | `COURIER_TICK_MS = 30_000L` drives `CourierCarryCore.expire` and the publication of local outcomes. A refusal or a thrown carrier leaves the bag intact and re-offers the parcel on a later pass. |

### 15.4 No-new-route boundary

1. **`PARCEL` rides in `CircleRequest.events`.** Its wire home is the existing
   `POST /api/flit/v1/circle/sync` (`Protocol.PATH_CIRCLE_SYNC`, §1, §7.5). No
   `PATH_*` constant, Ktor route, `LinkProvider`, byte-transfer route or
   `CircleRequest.body` repurposing is added. The envelope is text inside the
   event's `body`; it is not a new request field.
2. **No header, DTO or status-code change.** `CircleRequest` keeps exactly
   `action`, `circleId`, `invite`, `memberFp`, `events`, `body`; `CircleResponse`
   keeps its seven fields. There is no `ParcelDto`, no courier header and no
   response field. The signed `200` decision answer and the `400` / `403` /
   `500` refusal codes of §7.5 and §9 are unchanged.
3. **No second signature path.** `CourierEnvelope` uses `TlsIdentity.sign` and
   `FLITCOURIER1`; event authorship stays a `CircleCore.verify` check and the
   `FLITCARRY1` digest is a bag tag, not an envelope signature (§13.4, §13.5).
4. **Handoff state never goes on the wire.** `FlitEngine.courierHandoffs` is a
   process-local `StateFlow<Map<String, CourierCardUi.CourierHandoff>>` keyed by
   a normalised SHA-256. Each value is a typed `CourierCardUi.CourierHandoff`
   (`sha256`, `name`, `size`, `toFp`, `courierFp`, `fromFp`, `outcome`), published
   by `publishCourierHandoff` and updated by `setCourierHandoffOutcome`; its
   `outcome` is one of `waiting` / `handed` / `failed`, and no fourth value is
   ever written. `handed` means **the peer accepted the offer** (a transport OK
   on the `parcel` offer), not that the file opened on the recipient and not
   end-to-end delivery: there is no remote proof. Only a locally landed SHA
   produces an Open affordance (§18.5), and UI copy says the offer was handed
   off/accepted, never that bytes were delivered. It is never signed, never
   persisted and never placed in a `CircleRequest` or an event; a carrier cannot
   name a SHA key from an envelope it cannot open. `refreshCircle()` includes the
   map in its content-diff signature so a new outcome publishes without a log
   change.
5. **Numbers reuse existing seams.** `COURIER_TICK_MS = 30_000L` is app-owned;
   dial concurrency stays at `CIRCLE_PEER_DIALS = 4` and backoff at
   `CIRCLE_BACKOFF_BASE_MS = 5_000L` / `CIRCLE_BACKOFF_MAX_MS = 120_000L`. All
   protocol constants (§13.2) are read, never redefined.
6. **v2.2 is untouched.** `/info`, `/register`, `/prepare-upload`, `/upload`
   and `/cancel`, their DTO shapes and their status behaviour stay as in §2. A
   stock LocalSend v2.2 peer never calls the Flit-only route (§4).

### 15.5 Compatibility and evidence boundary

The additions are one already-defined verb used by one new app method, one
defaulted store field and one local flow. No existing parameter, route, DTO
shape or signed canonical byte changes. CODE establishes the `postParcel` gate,
the pre-fanout sha registration, the outer roster-member gate, the `courierBag`
field and the route boundary; PURE JVM tests pin the closed body keys, tolerant
bag decode and the three local outcome values; the 2026-11-30 carrier supplies
the SIM-HARN observations of a held parcel, an ack after a peer's OK (offer
accepted) and a throw that keeps the bag. None of this is evidence that file
bytes landed on the recipient. No DEVICE claim is made for this layer.
See [courier-sync.md](roadmap/courier-sync.md) and
[courier-ui.md](roadmap/courier-ui.md).

## 16. QR parcel frame contract — frame grammar and closed caps (2026-12-03)

### 16.1 Layer and ownership

This is the Protocol layer over §13 (courier envelope). It lives in the
JVM-pure package `app.flit.share.protocol.parcel`, object `ParcelCodec`. The
module gains no Android import, zxing class, camera, socket, file write or
wall-clock read; it deals only in `String` and `ByteArray`. A frame string is
opaque to its callers: the CLI renders it and the app displays or scans it, but
neither edits, reorders or reinterprets its bytes. The one crypto path is
`CourierEnvelope`: `encodeParcel` seals through `CourierEnvelope.seal` and
`complete` verifies and decrypts through `CourierEnvelope.verify` / `open`. No
second signature, key wrap or digest path is introduced.

### 16.2 Frame grammar

One QR code carries one frame. The wire item is a single opaque UTF-8 `String`
per frame; the CLI and the app render that same string at error-correction
level `M` with a quiet zone of `2` modules, so both sides encode identical
bytes. The string carries the frame descriptor — `parcelId`, `seq`, `count`,
`totalBytes` — and one CRC32 value. `seq` is the 1-based frame index and `count`
the number of frames; `seq == count` marks the final frame. All frames of one
parcel repeat the same `parcelId`, so a single lost frame removes no metadata.

The CRC covers canonical bytes: a domain-tagged, length-prefixed stream keyed by
`FRAME_MAGIC = "FLITPARCEL1"`, written with `DataOutputStream` as in
`CircleCore.canonical`. A field is a 4-byte big-endian UTF-8 byte length followed
by the bytes, so no delimiter can be smuggled through a field. CRC32 is computed
over those bytes and stored only in `crc`; it is excluded from its own input.
`parse` rebuilds the canonical bytes from the parsed fields and compares, so a
changed byte in any field — not just the payload — is caught. The exact field
order and string encoding are defined by `ParcelCodec.kt`, the wire truth for
this layer (§0); this contract fixes the grammar's shape, not a second copy of
the byte layout.

`complete` accepts frames in any order, keeps one frame per `(parcelId, seq)`,
requires a single common `count` and every `seq` in `1..count`, checks the frame
CRC and the whole-parcel digest, then requires `CourierEnvelope.verify` to pass
and `CourierEnvelope.open` — using the `identity` passed to `complete` — to yield
a payload whose recipient fingerprint equals `recipientFp` on that same identity.
Only then does it release bytes. A digest mismatch, a failed envelope signature
or a recipient mismatch releases no bytes and writes no file.

### 16.3 Closed caps

These are fixed, closed values in `ParcelCodec`; a caller cannot raise them.
The frame-layer parcel cap is named apart from `CourierEnvelope.MAX_ENVELOPE_CHARS`
so the two limits are never confused.

| Constant | Value | Meaning |
| --- | --- | --- |
| `MAX_FRAMES` | `64` | Defensive wire cap on `count`; `seq` space is `1..64`. Same as the app's `MAX_PARCEL_FRAMES = 64`, so the codec emits no sequence the app refuses to display. |
| `MAX_CHUNK_BYTES` | `512` | Largest raw chunk bytes in one frame. |
| `MAX_AIR_GAP_PARCEL_BYTES` | `131072` (128 KiB) | Defensive wire cap on framed bytes, named apart from `CourierEnvelope.MAX_ENVELOPE_CHARS` (12 * 1024). It bounds malformed input; it is not a reachable size. |
| `FRAME_MAGIC` | `"FLITPARCEL1"` | Domain tag for the per-frame CRC canonical bytes. |

The effective ceiling comes from `CourierEnvelope.MAX_ENVELOPE_CHARS = 12 * 1024`:
the sealed envelope is at most 12288 Base64 characters, which at 512 bytes per
chunk is about 24 frames and, with the signature, wrapped key and certificate
included, roughly 5 KiB of payload. The 64-frame and 128 KiB limits above are
defensive wire caps that bound malformed input, not sizes a sender can reach.

### 16.4 API (`ParcelCodec.kt`)

```
object ParcelCodec {
  data class FrameDescriptor(val parcelId: String, val seq: Int, val count: Int,
                             val totalBytes: Int, val crc: Long)
  fun parse(frame: String): FrameDescriptor?
  fun encodeParcel(payload: ByteArray, parcelId: String, recipientFp: String,
                   recipientCert: X509Certificate, signerFp: String,
                   identity: TlsIdentity): List<String>
  fun complete(frames: List<String>, recipientFp: String,
               identity: TlsIdentity): ParcelPayload?
  data class ParcelPayload(val parcelId: String, val recipientFp: String,
                           val signerFp: String, val sha256: String,
                           val bytes: ByteArray)
}
```

`parse` returns `null` on any failed check and never throws at the caller.
`encodeParcel` seals the payload through `CourierEnvelope.seal`, which binds the
wrapping key to the certificate's own fingerprint and refuses a `recipientCert`
whose fingerprint differs from `recipientFp`. Recipient privacy is therefore
enforced inside `seal` by that cert-fingerprint binding, not by the frame layer;
only after sealing does the codec frame the encoded bytes. `complete` verifies
frames, CRC, sequence, count, digest and recipient, then decrypts through
`CourierEnvelope.open` with the `identity` passed to it. Decoding thus needs the
recipient's identity on the decoding device: a captured frame set cannot be
opened without that private key, and the `recipientFp` argument alone never
releases bytes.

### 16.5 No-new-route and v2.2 boundary

1. The parcel is an air-gap sequence of QR strings, not a network exchange. No
   `PATH_*` constant, Ktor route, `LinkProvider`, socket, HTTP header, DTO field
   or status code is added (§1, §2, §3).
2. `/info`, `/register`, `/prepare-upload`, `/upload` and `/cancel`, their DTO
   shapes and their status behaviour stay as in §2. The v2.2 surface and the
   Flit-only `/api/flit/v1` routes are unchanged; a stock LocalSend v2.2 peer is
   unaffected (§4).
3. No signing or encryption path is forked. `encodeParcel` and `complete` call
   the §13 courier codec; the frame layer owns only framing, CRC, sequencing,
   caps and digest/recipient gates (§13.4, §16.2).
4. A parcel frame is a distinct string from a `flit://add` / `flit://pair` code;
   no pairing key or address field crosses this seam (§8).

## 17. QR parcel CLI contract — encode and decode at the harness seam (2026-12-04)

### 17.1 Layer and ownership

This is the CLI layer over §16 (frame codec) and §13 (courier envelope). It lives
in `harness/src/main/kotlin/app/flit/share/harness/ParcelCli.kt`, object
`ParcelCli` in package `app.flit.share.harness`. Both commands are one-shot and
offline: no socket, route, `Link`, worker or background process. The adapter
imports `ParcelCodec` from the JVM-pure protocol module and owns only argument
reading, the zxing image step, file I/O, the printed summary and the process
code. Frame layout, CRC, sequence, caps, digest and crypto are not redefined
here; they stay in `ParcelCodec` and the `CourierEnvelope` calls it makes.

`Harness.kt` gains one `"parcel"` dispatch beside `courier`, one line in the
top-level `usage()` and `parcelUsage()`, which prints `ParcelCli.usage()`.
`--out` is added to `ParsedArgs.VALUE_FLAGS`; `--dir`, `--to` and `--cert`
already exist. No other command's arguments, dispatch or output change.

### 17.2 Command surface and flags

```
parcel encode <file> --dir DIR --to FP --cert PATH
parcel decode --dir DIR --out PATH
```

| Input | Command | Contract |
| --- | --- | --- |
| `<file>` | encode | One source file; its bytes are the payload. No file name travels in a frame. |
| `--dir DIR` | encode | Output directory for the numbered PNGs. It must be empty of existing frames; encode refuses to overwrite. |
| `--dir DIR` | decode | Input directory; only the numbered frame PNGs are read. Decode never writes into it. |
| `--to FP` | encode | Recipient fingerprint, normalised before comparison with the certificate's own fingerprint. |
| `--cert PATH` | encode | Path to a file holding Base64 DER of the recipient X.509 certificate. Parsed for this invocation, checked against `--to`, then the certificate object itself is passed to `encodeParcel`. |
| `--out PATH` | decode | Path for the verified payload bytes. The operator names the landed file; no name is derived from a frame or from the payload. |

All flags are read through `ParsedArgs`. Encode does no recipient pin lookup and
has no self-addressed fallback: the only certificate used is `--cert`, held in
memory for that call and never copied into `--dir` or into a frame.

### 17.3 Frozen API (`ParcelCli.kt`)

```
object ParcelCli {
  data class EncodeResult(val dir: Path, val frames: Int,
                          val sealedChars: Int, val sha256: String)
  data class DecodeResult(val out: Path, val bytes: Int, val sha256: String,
                          val signerFp: String, val recipientFp: String)
  internal fun parcelEncode(args: ParsedArgs): EncodeResult
  internal fun parcelDecode(args: ParsedArgs): DecodeResult
  fun usage(): String
  fun run(args: ParsedArgs): Int
}
```

| Result field | Meaning |
| --- | --- |
| `EncodeResult.dir` | Frame directory the PNGs were written to. |
| `EncodeResult.frames` | Number of ordered frame strings rendered from `encodeParcel`. |
| `EncodeResult.sealedChars` | Character count of the sealed envelope carried by those frames. |
| `EncodeResult.sha256` | SHA-256 of the source file bytes. |
| `DecodeResult.out` | Path the verified bytes were written to (`--out`). |
| `DecodeResult.bytes` | Byte count of `ParcelPayload.bytes`. |
| `DecodeResult.sha256` | `ParcelPayload.sha256`, the digest of the opened bytes. |
| `DecodeResult.signerFp` | `ParcelPayload.signerFp`, printed by decode. |
| `DecodeResult.recipientFp` | `ParcelPayload.recipientFp`, printed by decode and checked against the local identity. |

`parcelEncode` and `parcelDecode` are `internal` so harness tests drive the same
functions the command uses without spawning a process. `usage()` is the single
source of the detailed `parcel` help text; `run(args)` returns the process code.

### 17.4 Encode path

Encode reads one file, calls `loadOrCreateIdentity()` and sets
`signerFp = identity.fingerprint`. It generates a fresh `parcelId` per encode,
parses `--cert` as an X.509 certificate, and requires
`TlsIdentity.fingerprintOf(cert) == --to` after normalisation. A mismatch or an
unparseable certificate is refused before any frame is rendered. The checked
certificate object is then passed directly as
`ParcelCodec.encodeParcel(payload, parcelId, recipientFp, cert, signerFp,
identity)`; the codec's own cert-fingerprint binding in `CourierEnvelope.seal`
applies unchanged.

Each returned frame string is rendered with zxing `QRCodeWriter` as a
768 × 768 PNG at error correction `M`, margin `2` and UTF-8, into
`frame-0001.png`, `frame-0002.png`, … in codec order (1-based, four digits, so
file-name order is frame order). Encode writes no other file into `--dir`.

### 17.5 Decode path

Decode reads the `frame-*.png` files, decodes each with zxing
`MultiFormatReader`, and keeps the returned frame strings opaque. It calls
`ParcelCodec.complete(frames, identity.fingerprint, identity)` with the local
identity from `loadOrCreateIdentity()`, and writes `ParcelPayload.bytes` to
`--out` only on a verified result for that identity. Verification — frame
layout, CRC, sequence, count, whole-parcel digest, envelope signature and
recipient — stays in `ParcelCodec`; the CLI adds no second crypto or digest
path. Decode prints the signer fingerprint, the recipient fingerprint, the
SHA-256 and the byte count. No file name travels in a frame, so `--out` is the
only source of the output name.

### 17.6 Process codes

| Code | Meaning |
| --- | --- |
| `0` | Success: frames written, or bytes written to `--out`. |
| `2` | Bad arguments, or a missing certificate, frame directory or output path; also a mismatch between `--cert` and `--to`. |
| `3` | Verification failure: an incomplete, unverified or wrongly addressed parcel, including a failed `complete`. |

`run(args)` returns the code; `Harness.main` calls `exitProcess(code)` when it
is non-zero. These three codes are the frozen surface for this layer; earlier
draft wording in the roadmap used `1` for usage/I/O and `2` for verification
refusal.

### 17.7 Wire and state boundary

No v2.2 route, DTO, header, request field or status code changes. No QR key
parameter, `LinkProvider`, socket or network carrier is added. The grammar,
`FRAME_MAGIC`, per-frame CRC32 and the caps in §16.3 are read from
`ParcelCodec`, not restated or raised by the CLI. The only durable state is the
identity file under `harnessHome()` and the frame PNGs in `--dir`; certificate
and assembled bytes live in memory for one invocation. A failed gate leaves no
output file. This layer is CODE plus harness round-trip evidence on the box;
no device walk is claimed here.

## 18. QR parcel app contract — show/scan seam and injected writer (2026-12-07)

### 18.1 Layer and ownership

This is the App layer over §16 (frame codec) and §13 (courier envelope), after
§17 (CLI). It lives in `app/src/main/kotlin/app/flit/share/`:
`ParcelScanState.kt` owns the Android-free, Compose-free reassembly state;
`ParcelUi.kt` owns one Compose screen with two modes, Show and Scan;
`MainActivity.kt` owns one additive entry point to that screen and the injected
writer that lands verified bytes in the app's received bucket. No protocol
constant, frame grammar, CRC, digest, signature or route is redefined here; the
codec remains the only place frame bytes are interpreted. The state holder
imports no zxing, camera, Android, socket or file class.

The roadmap names the two views `ParcelShowCard` and `ParcelScanCard`; the
frozen seam is the single `ParcelUi.kt` screen with Show and Scan modes and the
state/writer boundaries below. Show and Scan are two modes of one screen, not
two navigation routes, and no Circle or transfer behavior changes.

### 18.2 Constants owned here

| Constant | Value | Meaning |
| --- | --- | --- |
| `PARCEL_FRAME_INTERVAL_MS` | `400L` | Dwell per frame before advancing and wrapping. |
| `PARCEL_FRAME_SIZE_PX` | `240` | Side of the square bitmap drawn from one frame string. |
| `MAX_PARCEL_FRAMES` | `64` | Hard cap on displayed and assembled frames. |
| `PARCEL_PASTE_MAX_CHARS` | `16_000` | Cap on the paste field's newline-separated frame text. |

`MAX_PARCEL_FRAMES` is set to `ParcelCodec.MAX_FRAMES = 64`, so the app can
display every sequence the codec emits. `MAX_AIR_GAP_PARCEL_BYTES` (131072 /
128 KiB) and `MAX_CHUNK_BYTES` (512) are read from §16.3, never restated; both
are defensive wire caps, while the effective parcel size is set by the 12 KiB
sealed-envelope Base64 cap (about 24 frames, roughly 5 KiB of payload). The
~8 fps analyzer throttle is the reused
`QrUi.kt` behavior (a 120 ms gate), not a new constant here.

### 18.3 Show mode

Show takes the immutable `List<String>` returned by
`ParcelCodec.encodeParcel(payload, parcelId, recipientFp, recipientCert,
signerFp, identity)`. Each string is rendered through
`Qr.encode(text, PARCEL_FRAME_SIZE_PX)` — zxing `QRCodeWriter`, error correction
`M`, `MARGIN = 2`, UTF-8 — and `Qr.gray` into a square bitmap; that is the same
gray layout `Qr.decodeGray` and the camera path's `Qr.decodeYPlane` consume.
Frames are drawn in codec order, advancing every `PARCEL_FRAME_INTERVAL_MS` and
wrapping from the last frame back to index zero; next/back controls move one
frame at a time. The display index lives in a Compose `remember`, is discarded
on close and is never written to prefs, `FlitCircleStore` or the ledger. The app
never parses, reorders or rewrites a frame string.

### 18.4 Scan mode and `ParcelScanState`

The state holder is a small pure reducer; the Kotlin file is authoritative for
the names of its read-only accessors.

```
class ParcelScanState(recipientFp: String, identity: TlsIdentity) {
  fun add(frame: String): Boolean                    // accepted, or ignored
  fun complete(): ParcelCodec.ParcelPayload?
  // read-only progress view: parcelId, count, accepted-frame count,
  // and the "frame i of n" label
}
```

`add(frame)` calls `ParcelCodec.parse(frame): FrameDescriptor?`; an unparseable
frame is ignored. Only `parcelId`, `seq` and `count` are read for gating and
progress; `totalBytes` and `crc` stay inside the codec. At most one frame is
kept per `(parcelId, seq)` pair and the first copy wins. A frame with a
different `parcelId` while another parcel is in progress, a frame whose `count`
disagrees with the first accepted frame, a `seq` past `MAX_PARCEL_FRAMES` and a
frame beyond the 64-frame cap are all ignored; the current assembly stays.
Progress is `frame i of n`, where `n` is the descriptor's `count` once known and
`?` before the first accepted frame.

`complete()` calls `ParcelCodec.complete(frames, recipientFp, identity)`, where
`identity` is the receiving app's `TlsIdentity`, not just its fingerprint, so
the codec can open the envelope with the recipient's private key. It returns a
`ParcelCodec.ParcelPayload` (`parcelId`, `recipientFp`, `signerFp`, `sha256`,
`bytes`) only when the accepted frames cover every `seq` in `1..count` exactly
once and every codec gate — CRC, sequence, count, digest, envelope signature
and recipient — passes. Otherwise it returns null and the frames stay for
another attempt. One verified payload is handed out once: a second `complete()`
returns null.

The camera path reuses the `QrUi.kt` `CameraQrView` shape: the existing
`Manifest.permission.CAMERA` ask, `LifecycleCameraController`, the ~8 fps
analyzer throttle, a stride-aware copy of the Y plane (row/pixel strides) and
`Qr.decodeYPlane`. Each decoded string is offered to `add`; a null decode is
ignored and previously accepted frames are kept. A denied camera leaves a paste
field of newline-separated frame strings capped at `PARCEL_PASTE_MAX_CHARS`,
feeding the same state holder. Frames live per scan dialog and are dropped on
close or completion; a partial parcel is never persisted, and closing with an
incomplete sequence writes no file, ledger row or delivery record.

The UI seam is therefore:

| Seam | Shape | Contract |
| --- | --- | --- |
| Show input | `List<String>` | Immutable `encodeParcel` output, codec order, opaque. |
| Scan input | `(String) -> Unit` → `add` | One decoded frame string per call; a failed decode sends nothing. |
| Progress | `String` from state | `frame i of n`, with `?` before the first accepted frame. |
| Result | `ParcelCodec.ParcelPayload?` | Shown as signer fingerprint, SHA-256 and byte count only after `complete` passes. |
| Writer | `(ByteArray) -> Unit` | Injected from `MainActivity`; receives `ParcelPayload.bytes` once. |
| Close | `() -> Unit` | Drops all in-memory frames without writing anything. |

### 18.5 Injected writer

`MainActivity.kt` supplies the `(ByteArray) -> Unit` callback. It saves the
delivered bytes to the app's received bucket through the existing receive path
(the one that ends in `FlitEngine.publishReceived`: received ledger and digest
entry, then per-peer folder → global default → `Downloads/Flit`). `ParcelUi.kt`
holds no `File`, `ContentResolver`, engine or preference reference; the callback
is the only bridge from a verified payload to persistence, so a pure test can
pass a collector instead. No new receive path, route or file format is added.

No file name travels in a frame. The save name is local: the writer chooses it
on the receiving device, and nothing from the frame text or the payload is
published as a name. The callback runs once per verified payload; a
double-`complete`, a failed verification or a closed dialog never calls it a
second time.

### 18.6 No wire change and compatibility boundary

1. A parcel is an air-gap sequence of QR strings. No `PATH_*` constant, Ktor
   route, `LinkProvider`, socket, HTTP header, DTO field or status code is
   added (§1, §2, §3).
2. `/info`, `/register`, `/prepare-upload`, `/upload` and `/cancel`, their DTO
   shapes and their status behaviour stay as in §2. The Flit-only
   `/api/flit/v1` routes stay as in §7 and §15. Nothing in Circle, courier sync
   or send/transfer behavior changes.
3. The frame grammar, `FRAME_MAGIC`, per-frame CRC32 and the caps in §16.3 are
   read from `ParcelCodec`. The app adds no cap of its own above
   `MAX_PARCEL_FRAMES`, which mirrors `MAX_FRAMES`, and it never re-signs or
   re-encrypts anything. A parcel frame is not a `flit://add` / `flit://pair`
   code and carries no key or address field (§8, §16.5).
4. There is no second verification or reassembly path. `ParcelCodec.complete`
   is the only gate that releases bytes, and the injected writer is the only
   place they land.
5. All new state is process-local: the display index in Compose `remember`, the
   frames in a per-dialog holder, the camera analyzer and its throttle in the
   dialog. No partial parcel, frame string, signer fingerprint or displayed
   payload is written to prefs, `FlitCircleStore`, a Circle event, the courier
   bag or the ledger; only the writer's verified bytes become durable.

### 18.7 Evidence boundary

CODE establishes the show/scan seam, the reassembly gates and the injected
writer. PURE JVM tests (no Compose, no Robolectric, no engine start) drive
`add`/`complete` for in-order, out-of-order, duplicate, missing, mixed-id,
count-mismatch, over-cap and double-complete cases, plus a rendered-frame
round-trip at `PARCEL_FRAME_SIZE_PX` through `Qr.encode`/`Qr.gray`/
`Qr.decodeGray` and a simulated CameraX Y plane with row/pixel strides and one
rotation step. No DEVICE evidence is claimed for this layer: capturing a real
multi-frame sequence from a second phone's screen is the 2026-12-08 air-gap
walk. See [qr-parcel-app.md](roadmap/qr-parcel-app.md), and §16–§17 for the
codec and CLI that produce the frames used here.
