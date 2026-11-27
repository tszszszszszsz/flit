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
| **On-device** | Panic QR-v2 pairing and one-phone↔box banner exchange; relay dial/subscribe with a 200 MiB camera shot and a 300 MiB mid-body relay kill resuming hash-exact from the exact prefix; Circle two-node join/message/leave and phone-as-host; file-drop landing hash-exact; QR v3 tap-to-join. |
| **Not verified** | A real phone-to-phone RFCOMM transfer (no second phone; bench box has no Bluetooth adapter); a ≥3-device Circle mesh; multi-hop panic forwarding on real devices; Wi-Fi Aware (feature absent on the rig phone). Outbound phone→host Circle fan-out is test-evidenced with an injected carrier but was not cleanly device-confirmed. |

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

Each constant is declared once in its owning file; no file redeclares another
file's constant. Values below apply to the 2026-11-27 layer.

| Constant | Value | Meaning |
| --- | --- | --- |
| `PARCEL` | `"parcel"` | Tenth Circle event verb, in `CircleEvent.TYPES` |
| `PARCEL_BODY_KEYS` | `{"toFp","envelope"}` | Closed body key set |
| `MAX_ENVELOPE_CHARS` | `12 * 1024` | Base64 cap; framed body stays under `CircleCore.MAX_BODY_CHARS` (16 KiB) |
| `MAX_PARCELS` | `256` | Bounded carrier bag |
| `MAX_SEEN` | `4096` | Bounded id reservation, shared by `evaluate` and `fresh` |
| `REPLAY_WINDOW_MS` | `6 * 60 * 60 * 1000L` (6 h) | Open window for `open` and `evaluate` |
| `CLOCK_SKEW_MS` | `5 * 60 * 1000L` (5 min) | Future tolerance |
| `MAX_PARCEL_TTL_MS` | `24 * 60 * 60 * 1000L` (24 h) | Ceiling; `expiresMs = createdMs + MAX_PARCEL_TTL_MS` |
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

`open(e, identity, nowMs)` decrypts with the recipient's private key, parses
the bounded event array and applies `REPLAY_WINDOW_MS`/`CLOCK_SKEW_MS` to
`createdMs`. It owns no replay set and never calls `CircleCore.evaluate` or
`accept` on an envelope id; authorship is checked by `CircleCore.verify` at
fold time. The envelope carries no `expiresMs`.

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
reserves an id in the bounded `seen` set shared with `fresh`, so a `Full`
refusal can be retried after space frees. The window compares two positive
timestamps by subtraction, and `nowMs >= expiresMs` is never selected.
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
`courier.bag` in the CLI. `TlsIdentity`'s pin cache stays bounded at 512 and
gains the sender's certificate only after `verify` succeeds.

CODE establishes the constants, the two domain tags, the canonical field
order, the closed body keys and the decision precedence. PURE JVM tests cover
canonical bytes, `encode`/`decode` bounds, tamper rejection, `seal`/`open`,
recipient wrapping, certificate binding and bag decisions. Carrier (`SIM-HARN`)
and three-node (`DEVICE`) handoffs are dated 2026-11-30 and 2026-12-02; no such
evidence is claimed for this layer. See
[courier-envelope.md](roadmap/courier-envelope.md) and
[courier-carry.md](roadmap/courier-carry.md).
