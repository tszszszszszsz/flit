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
is named. Line references are to files that existed as of commit `72225f8`;
files added later (the courier and QR-parcel layers) are referenced by their
current line numbers.

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
| `JOIN_PROOF_TAG` / `JOIN_PROOF_BUCKET_MS` | `"FLITJOIN1"` / `5 min` (`= CLOCK_SKEW_MS`, `300_000` ms) | `CircleCore.kt` (§7.8) |
| `JOIN_PROOF_BODY_KEYS` / proof value | `{"proof"}` / 32-byte SHA-256 as unpadded Base64URL (43 chars) | `CircleCore.kt` (§7.8) |
| `CircleAuthority.KEY_ROLES` | `{OWNER, ADMIN}` | `CircleAuthority.kt` (§7.9) |
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
A JOIN-level proof refusal is signed the same way (§7.5, §7.8). The group
`key` is only ever in a successful JOIN answer; a SYNC answer carries
`key = ""` even for an already-known member (`FlitCircleHost.kt:193-203`), and
a later rotation travels in a signed `rekey` event rather than in the answer.
The QR is a bearer invite, never a key; the key travels inside the pinned TLS
session. The CLI pins the host fingerprint with `--host-fp` at join, keeps it
in `circle.state`, and verifies every answer before any merge (§7.10).

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
`type` is one of twelve verbs, including `parcel`, `receipt` and `pad`; `body` is a small JSON object:

| Verb | Body shape (exact keys) |
| --- | --- |
| `message` | `{"text":"..."}` |
| `file` | `{"name":"...","size":<non-negative integer>,"sha256":"..."}` — **exactly** `FILE_BODY_KEYS`, no `token`, `path` or `spoolPath` |
| `poll` | `{"pollId":"...","question":"...","options":["a","b"]}` |
| `vote` | `{"pollId":"...","option":<int>}` or `{"kickId":"...","approve":<bool>}` (app also emits `{"targetFp":"...","approve":<bool>}`) |
| `join` | `{"alias":"..."}` for the ordinary join verb (host-routed; members cannot inject it). |
| `join` (handshake proof) | `{"proof":"<43-char Base64URL SHA-256>"}` — closed `JOIN_PROOF_BODY_KEYS = {"proof"}`; carried as a signed JOIN event in `CircleRequest.events` on a join request, not as a foldable log entry (§7.8). |
| `leave` | `{"memberFp":"..."}` |
| `kick` | `{"kickId":"...","targetFp":"...","mode":"admin"|"vote","approve":<bool>}` (field set varies by phase) |
| `role` | `{"targetFp":"...","role":"OWNER"|"ADMIN"|"MEMBER"}` |
| `rekey` | `{"keyB64":"...","gen":<int>}` (app adds a `members` array; host-authority only) |
| `parcel` | `{"toFp":"...","envelope":"..."}` — **exactly** `PARCEL_BODY_KEYS`, no extra key; closed gate and envelope in §13.3 |
| `receipt` | `{"dropId":"...","sha256":"...","keyId":"..."}` — **exactly** `RECEIPT_BODY_KEYS`, no `landedMs`, path or status; closed gate and roll-up in §21 |
| `pad` | `{"op":"put","key":"...","text":"..."}` or `{"op":"del","key":"..."}` — **exactly** `PAD_PUT_KEYS` or `PAD_DEL_KEYS`; closed gate, caps and deterministic view in §23 |

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
fingerprint; and, for JOIN only, `CircleCore.joinProofFailure` (§7.8). A
rejected shape is `400`, a bad identity `403`, a throwing delegate `500`;
every real decision is a signed `200`. A proof failure is answered *at the
route* with a host-signed `200` refusal whose `reason` is one of `join proof
required`, `invalid join proof` or `join proof replay`; it never reaches the
delegate and never yields a key. The caller verifies that signature with
`CircleResponse.verify` before treating even a refusal as authoritative.

Host `onRequest` first refuses a wrong `circleId`. JOIN is exempt from the
roster gate but not from the invite: a case-insensitive invite match, a valid
fingerprint, a non-blank key and room below `MAX_MEMBERS = 500` are required;
the answer carries the key, the authoritative roster and the ordered seed log,
with no synthetic join event (only the joiner can sign its own join)
(`FlitCircleHost.kt:119-144`). SYNC is roster-gated and filters every incoming
event through `CircleAuthority.foldable` (§7.9): correct circle, known type,
JOIN excluded, REKEY only from an OWNER/ADMIN author, bounded body,
`validFileBody` for files, `authorFp == requester`, a verifiable cert and
signature, then log-id dedup and `CircleCore.accept`. A departure **always**
triggers a fresh 32-byte random key and a signed `rekey` event, even when the
same batch carried an admitted REKEY: the forced generation is placed above
the greatest generation that batch was allowed to install, so a peer folding
the answer in another order cannot treat the fresh key as stale replay. A
signer who also submitted a `leave` in the batch is vetoed as a REKEY author,
so a departing officer cannot hand off its chosen key. A later sync from a
departed fingerprint is refused (`FlitCircleHost.kt:146-242`). Roles are
local, fingerprint-pinned authority labels; an `ADMIN` (or `OWNER`) can kick a
non-owner, a plain member can only open a vote-kick, and nobody can appoint a
second `OWNER`.

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

### 7.8 JOIN proof — the stateless handshake

The QR invite is a bearer token, so a JOIN must also prove that the caller owns
the private key behind `memberFp`. The joiner mints one signed JOIN event in
`CircleRequest.events`; this adds no route, header, DTO field or status code.
Its body has the closed key set `JOIN_PROOF_BODY_KEYS = {"proof"}`: a 32-byte
SHA-256 digest written as unpadded Base64URL, exactly 43 characters, decoding
to 32 bytes. The gate refuses an extra key, padding (`=`), the standard `+`/
`/` alphabet, whitespace, a non-string value and anything over
`MAX_FIELD_CHARS`. The earlier `{"alias":"..."}` body remains the ordinary
join verb body; it is not a proof.

The digest is over a domain-tagged nonce using the same 4-byte big-endian
UTF-8 length-prefixed `field` framing as event canonical bytes, followed by an
8-byte big-endian bucket. The field order is `field("FLITJOIN1")`,
`field(hostFp)`, `field(circleId)`, `field(invite)`, `field(memberFp)`,
`writeLong(bucket)`. Each string is trimmed and uppercased with `Locale.ROOT`
before framing, so casing and whitespace differences between devices cannot
change the bytes, and a `FLITCIRCLE1` event signature can never double as a
proof. The signing domain is separate: `FLITJOIN1`.

`JOIN_PROOF_BUCKET_MS = CLOCK_SKEW_MS = 5 min` (`300_000` ms) and
`joinProofBucket(createdMs) = floorDiv(createdMs, JOIN_PROOF_BUCKET_MS)`. The
host admits a proof whose event bucket is the current bucket or one bucket on
either side (a ±1-bucket tolerance); a proof outside that band is a replay and
stops working once the grid rolls past it. The digest itself is not a secret —
authorship comes from the signed JOIN event, whose embedded certificate is
bound to its own SHA-256 DER and pinned only after `CircleCore.verify` passes.

`CircleCore.joinProofFailure(req, hostFp, nowMs)` is total and stateless apart
from the shared bounded seen-id set. A non-JOIN action returns null (SYNC needs
no proof). For JOIN the refusal precedence is:

| Reason | Meaning |
| --- | --- |
| `join proof required` | No JOIN event in `events`, or no JOIN event carries a `proof` key. |
| `invalid join proof` | Bad body; a JOIN event whose author or circle does not match `memberFp`/`circleId`; a forged or unparseable signature; a digest that does not match this host, circle, invite and member. |
| `join proof replay` | A bucket outside the ±1 tolerance, the same proof presented twice in one request, or an id already reserved by `fresh`/`accept`. |

The route answers each refusal as a host-signed `200` (§7.5), so a joiner can
pin the host before treating a refusal as authoritative. The app and the CLI
mint the proof from their own identity and refuse to dial when the claimed
`memberFp` differs from that identity's certificate fingerprint.

### 7.9 CircleAuthority.foldable — the shared fold-time gate

`CircleAuthority.foldable(event, circleId, roles)` is JVM-pure: it reads only
`circleId`, `type` and `authorFp` plus a caller-supplied fingerprint→role map,
verifies no signature, reads no clock, owns no state and keeps no memory
between calls. Its rules are three: `join` is never foldable (a handshake, not
a log entry); `rekey` is foldable only when the trimmed, uppercased author
holds `OWNER` or `ADMIN` in the map (role values are also trimmed and
uppercased); every other known verb folds when the circle matches. An unknown
or blank type, a blank author or a foreign circle is refused. Non-authority
fields (`id`, `body`, `sig`, `lamport`, `createdMs`, `cert`) do not affect the
decision.

Every fold site reaches the same answer from the same inputs:

| Site | Authority context |
| --- | --- |
| App host (`FlitCircleHost.onRequest`, SYNC filter) | One immutable pre-batch map built from the roster, applied before signature verification and id reservation. A signer who also leaves in the batch is excluded; a departure forces a host-minted rotation above any admitted generation. |
| App fresh (`FlitCircleSync.fresh`) | The roster's fingerprint→role map, applied as a stage of the inbound pipeline. |
| Store reducer (`FlitCircleStore.applyRekey` / `merge`) | One snapshot of the roster as it stood before the batch, so fold order cannot promote a signer or demote one mid-flight. Authors of every `leave` in the batch are vetoed as REKEY signers whether their rekey is folded before or after the leave. |
| CLI host (`Harness.CircleHostState.onCircle`) | The same pre-batch roster snapshot; a REKEY from a same-batch leaver is removed from the admitted set and a departure forces a monotonic rotation. |
| Courier (`FlitCircleSync.courierReceive`, `CourierCli` opened-event gate) | An **empty** role map: no parcel can fold a JOIN or a REKEY even when the sealed signer is an OWNER; membership and signature checks still apply. |

The app's `fresh` pipeline, in code order, is: pure shape and circle gates;
`CircleAuthority.foldable`; type-specific body gates (`validFileBody`,
`validParcelBody`, bounded readable JSON); `CircleCore.verify` — no replay id
is reserved before the signature passes, so a forgery cannot burn a valid
event's id; deterministic `CircleCore.order` and `CircleCore.dedup`; a hash set
of local log ids; the bounded `FlitCircleStore.EVENT_CAP` (500); and finally
`CircleCore.accept` at an injectable `nowMs`. Only events that survive every
stage reserve an id.

A REKEY that fails the gate is still inert log history: it is appended but
installs no key and never raises `rekeyGen`. A successful install requires a
non-negative `gen` no older than the local generation, so a stale rekey cannot
roll the live key back.

### 7.10 CLI host pinning and repeated options

`flit-cli circle join` takes `--host-fp FP` (the fingerprint printed by
`serve`), mints the §7.8 proof for it, and stores the pin in `circle.state`
(eighth line; a legacy seven-line file loads but forces a verified join).
Every later `sync`, `post`, `file`, `receipt`, `leave` and roster refresh calls
`CircleResponse.verify` on the answer with that fingerprint before any merge;
an unsigned or differently signed answer is reported as a host-answer failure
and leaves local state unchanged. Since a SYNC answer carries `key = ""`, the
CLI adopts group-key material only from a verified JOIN answer or from the
newest generation of a verified `rekey` event in the signed log.

`ParsedArgs` keeps both `flags` (last value wins) and `repeated` (every
occurrence in argv order, read through `opts(k)`). `circle poll --option A
--option B --option C` therefore collects all three options and still accepts
the comma form `--option A,B,C` through a flat-map split; single-value callers
continue to read the last occurrence with `opt(k)`.

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
| circle join proof | host-signed `200` | Proof refused at the route before the delegate: `join proof required`, `invalid join proof`, or `join proof replay` (§7.8). |
| relay | `OK` / `ERR <reason>` | Paired / refused (`bad-hello`, `bad-token`, `timeout`, `partner-gone`); then blind copy. |

Full retry/keep/drop conclusions are in [Failure modes](FAILURE-MODES.md);
trust and replay reasoning in [Security](SECURITY.md).

## 10. Evidence ledger

| Claim tier | What is actually established |
| --- | --- |
| **Code reality** | Every constant, route string, header, DTO shape, canonical byte layout, pairing state machine and policy rule listed above. |
| **Pure-test JVM** | Canonical/sign/verify for panic and Circle, forged-author rejection, replay and dedup windows, Lamport convergence, both `Content-Range` spellings, restart probe/attach, failed-chunk-then-resume, relay pairing/isolation/token, DTO and `LinkServer` parity over the in-memory duplex pipe through the real hand-HTTP + TLS stack; JOIN proof framing, bucket tolerance and refusal precedence, the `CircleAuthority.foldable` truth table, and the app receive-side fold gate. |
| **Harness/sim** | `flit-cli relay` pairs a real 3 MB protocol send hash-exact with TLS inside the pipe; wrong token refused; unpaired subscription times out; dead dial surfaces `TransferUnreachable`. Two in-process TLS Circle nodes sync message, poll re-vote, file drop and kick/rekey; `bt-sim` runs the production client over the pipe carrier. The CLI host gate refuses unauthorised REKEY and same-batch hand-off keys and pins the signed answer with `--host-fp`. |
| **On-device** | Panic QR-v2 pairing and one-phone↔box banner exchange; relay dial/subscribe with a 200 MiB camera shot and a 300 MiB mid-body relay kill resuming hash-exact from the exact prefix; Circle two-node join/message/leave and phone-as-host; the 2026-11-26 three-node baseline walk, which is DEVICE evidence for a 3-identity Circle (phone plus two CLI identities); file-drop landing hash-exact; QR v3 tap-to-join. |
| **Not verified** | A real phone-to-phone RFCOMM transfer (no second phone; bench box has no Bluetooth adapter); a Circle mesh larger than three identities; multi-hop panic forwarding on real devices; Wi-Fi Aware (feature absent on the rig phone). Outbound phone→host Circle fan-out is test-evidenced with an injected carrier but was not cleanly device-confirmed. No device walk is attached to the JOIN proof, the fold-time authority gate or the CLI host pin. |

The JUnit XML in the tree records 359 protocol + 323 app + 87 harness = 769
tests with zero failures after the JOIN-proof and authority layers. The earlier
714 figure (330 + 309 + 75) was the 2026-12-18/12-21 inventory and is
historical. These are test counts, not a device claim for the UI or CLI layers.

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
arguments only through `ParsedArgs` from `Harness.kt`, which keeps every
occurrence of a value flag in `repeated` (read with `opts(k)`) as well as the
last-value-wins `flags`/`opt(k)` pair; `circle poll --option A --option B` uses
that repeated form (§7.10). It loads identity through `loadOrCreateIdentity()`,
and roots every path in `harnessHome()`, so `FLIT_HARNESS_HOME` isolates two
nodes on one box as it does for `circle`.

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
| `MAX_AIR_GAP_PAYLOAD_BYTES` | `5120` | The real ceiling on the raw payload accepted by `encodeParcel`: `MAX_ENVELOPE_CHARS * 3 / 4` bounds envelope plaintext at 9216 bytes, and 5120 leaves room for the CircleEvent JSON wrapper, envelope fields, signer certificate and signature. A larger or empty payload yields no frames. |
| `FRAME_MAGIC` | `"FLITPARCEL1"` | Domain tag for the per-frame CRC canonical bytes. |

The effective ceiling comes from `CourierEnvelope.MAX_ENVELOPE_CHARS = 12 * 1024`:
the sealed envelope is at most 12288 Base64 characters, which at 512 bytes per
chunk is about 24 frames and includes the signature, wrapped key and
certificate. The raw payload accepted for encoding is separately and firmly
capped at `MAX_AIR_GAP_PAYLOAD_BYTES = 5120` bytes; an empty or larger payload
returns no frames and the CLI reports it as a bad-argument refusal before any
rendering. The 64-frame and 128 KiB limits above are defensive wire caps that
bound malformed input, not sizes a sender can reach.

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

Before rendering, encode checks the source file against
`ParcelCodec.MAX_AIR_GAP_PAYLOAD_BYTES` (5120 bytes); an empty or larger payload
exits `2` with no PNG written. Each returned frame string is rendered with
zxing `QRCodeWriter` as a 768 × 768 PNG at error correction `M`, margin `2` and
UTF-8, into `frame-0001.png`, `frame-0002.png`, … in codec order (1-based, four
digits, so file-name order is frame order). Every frame is decoded back to a
string to prove the round-trip before any PNG is staged; a failure escalates
the *whole* set to 1024 × 1024, then to 1536 × 1536, and a failure at all three
sizes exits `2` with nothing written. Frame strings are never edited or
re-encoded — only their pixel size changes. Encode writes no other file into
`--dir`.

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
sealed-envelope Base64 cap (about 24 frames) and the codec's hard
`MAX_AIR_GAP_PAYLOAD_BYTES = 5120` payload ceiling. The
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

## 19. Per-drop content key contract — keyed file body (2026-12-09)

### 19.1 Layer and ownership

This is the Protocol layer that gives each Circle `file` drop a fresh symmetric
key. It lives in the JVM-pure package `app.flit.share.protocol.circle`, object
`DropKeyCore`. The only changes outside that new file are one additive instance
method on `TlsIdentity` (§19.2) and an extension of `CircleCore.validFileBody`
(§19.5). The module gains no Android import, socket, file write, preference or
wall-clock read. No route, header, DTO field, status code or v2.2 shape changes:
the key material travels inside the body of the existing signed `file` event on
the existing `POST /api/flit/v1/circle/sync` route (§7.3, §7.5), and a stock
LocalSend v2.2 peer sees the same wire it saw before (§2, §4). An older Flit
build keeps parsing the legacy three-key body unchanged.

### 19.2 Frozen API (`DropKeyCore.kt`)

```
object DropKeyCore {
  const val KEY_BYTES = 32, IV_BYTES = 12, TAG_BYTES = 16
  const val MAX_WRAPS = 32, MAX_WRAPPED_CHARS = 512
  const val DOMAIN = "FLITDROP1"
  data class Key(val bytes: ByteArray, val iv: ByteArray)
  data class KeyedFile(val name: String, val size: Long, val sha256: String,
                       val keyGen: Int, val cipherSha: String, val iv: String,
                       val wraps: Map<String, String>)
  fun mint(): Key
  fun wrap(key: ByteArray, recipients: Map<String, X509Certificate>): Map<String, String>
  fun unwrap(wrapped: Map<String, String>, identity: TlsIdentity): ByteArray?
  fun seal(key: Key, plaintext: ByteArray): ByteArray   // IV || ciphertext || tag
  fun open(key: ByteArray, sealed: ByteArray): ByteArray?
  fun keyedFileBody(v: KeyedFile): String
  fun parseFileKeyBody(body: String): KeyedFile?
  fun keyId(dropId: String, keyGen: Int): String = "$dropId:$keyGen"
}
```

`TlsIdentity` gains exactly one additive instance method,
`decryptOaepSha256(cipher: ByteArray): ByteArray?`, backed by the existing
private key. It returns null on malformed Base64, padding or provider failure
and never writes to the pin map. No new signature algorithm is introduced;
signing and verification stay with `TlsIdentity.sign` / `CircleCore.verify`
(§4.1, §7.4).

### 19.3 Exact key sets

The signed `file` body has one of two closed key sets; anything else is refused
by the gate in §19.5.

| Form | Key set |
| --- | --- |
| Legacy (unchanged) | `FILE_BODY_KEYS = {name, size, sha256}` |
| Keyed | `KEYED_FILE_BODY_KEYS = {name, size, sha256, keyGen, cipherSha, iv, wraps}` |

Keyed body JSON shape:

```
{"name":"...","size":N,"sha256":"<plaintext sha>",
 "keyGen":1,"cipherSha":"<sha256 of sealed bytes>",
 "iv":"<base64 12 bytes>","wraps":{"<UPPERCASE FP>":"<base64 wrapped key>"}}
```

`sha256` is the plaintext digest; `cipherSha` is the digest of the sealed
bytes. `keyGen` is an integer generation counter starting at 1 and bumped when a
fresh key is minted for the same drop. `wraps` is a JSON object keyed by
uppercase recipient fingerprint. `keyedFileBody(v)` builds exactly this map and
never emits a `token`, `path` or `spoolPath` key. The legacy form has no key
material and keeps its byte-identical canonical form.

### 19.4 Bounds

| Constant | Value | Applies to |
| --- | --- | --- |
| `MAX_BODY_CHARS` | `16 * 1024` (16 KiB) | Whole body string (`CircleCore.MAX_BODY_CHARS`, §1.6) |
| `MAX_FIELD_CHARS` | `256` | `name`, `sha256`, `cipherSha`, the `iv` string, and each fingerprint key |
| `MAX_WRAPPED_CHARS` | `512` | Each value in `wraps`; a 2048-bit RSA-OAEP wrap is 256 bytes, about 344 Base64 chars, so `MAX_FIELD_CHARS` would refuse a valid wrap |
| `MAX_WRAPS` | `32` | Entry count in `wraps` |
| `KEY_BYTES` | `32` | Content key length |
| `IV_BYTES` | `12` | IV length; the decoded `iv` must be exactly 12 bytes |
| `TAG_BYTES` | `16` | GCM tag length (128-bit) |

Every string is non-blank and bounded as above. `size` is a non-negative integer
number; the string `"12"` is refused. `keyGen` is a positive integer. `wraps`
holds `1..MAX_WRAPS` entries, each with a non-blank uppercase fingerprint key
and a non-blank Base64 value no longer than `MAX_WRAPPED_CHARS`. With 32 entries
at 344 chars each plus the other fields, the body stays under 16 KiB.

### 19.5 `CircleCore.validFileBody` gate

`validFileBody(body)` accepts a body when and only when its keys equal one of
the two sets in §19.3 and every value in that form passes the bounds in §19.4:

1. The body is non-blank and at most `MAX_BODY_CHARS`.
2. The parsed value is a JSON object whose key set is exactly `FILE_BODY_KEYS`
   or exactly `KEYED_FILE_BODY_KEYS`.
3. Legacy form: `name` and `sha256` are non-blank strings within
   `MAX_FIELD_CHARS`; `size` is a non-negative integer number, not a string.
4. Keyed form: the legacy field checks, plus `keyGen` a positive integer;
   `cipherSha` a non-blank string within `MAX_FIELD_CHARS`; `iv` a non-blank
   string decoding to exactly `IV_BYTES` (12) bytes; and `wraps` a JSON object
   with `1..MAX_WRAPS` non-blank uppercase fingerprint keys, each value a
   non-blank Base64 string within `MAX_WRAPPED_CHARS`.

A partial key set, an extra field, a lowercase or blank fingerprint, a string
`size`, a non-positive `keyGen`, an `iv` that does not decode to 12 bytes and an
empty or over-cap `wraps` map are all refused. `parseFileKeyBody(body)` returns
a typed `KeyedFile` only when this gate passes and the body is the keyed form;
it returns null for every body the gate refuses. `validShape()` stays
type-agnostic bounds (§7.4), so this extension never changes an existing
event's signature or acceptance.

### 19.6 Crypto primitives

`mint()` draws a fresh 32-byte key and 12-byte IV from `SecureRandom`,
independent of the group key, the invite and the file hash. `seal(key, plaintext)`
uses `AES/GCM/NoPadding` with a 128-bit tag and emits
`IV || ciphertext || tag`; the AAD is the length-prefixed domain tag
`field("FLITDROP1")` written with `CircleCore.canonical`'s 4-byte big-endian
UTF-8 field rule. `open` refuses a value shorter than
`IV_BYTES + TAG_BYTES` and returns null on any tag failure. `wrap` encrypts the
key with each recipient certificate's RSA public key using
`RSA/ECB/OAEPWithSHA-256AndMGF1Padding` with an explicit `OAEPParameterSpec`
(`"SHA-256"`, `"MGF1"`, `MGF1ParameterSpec.SHA256`,
`PSource.PSpecified.DEFAULT`), Base64-encodes it, and returns one entry per
uppercase fingerprint with a usable certificate; a roster above `MAX_WRAPS` is
refused, never silently trimmed. `unwrap` returns null for a fingerprint with
no entry, a wrong identity or a malformed blob.
`keyId(dropId, keyGen) = "$dropId:$keyGen"` is the identifier published for the
drop: `dropId` is the signed `file` event id, `keyGen` is the body's counter,
and the value is bounded by `MAX_FIELD_CHARS` and treated as provenance only.

### 19.7 Canonical, wire and state boundary

1. **Canonical bytes unchanged.** `CircleCore.canonical` covers the body as one
   length-prefixed field (§7.4), so the sender's signature binds `cipherSha`,
   `iv` and every wrap. A courier or relay carrying the event cannot alter them;
   `CircleCore.verify` fails on a modified body before any unwrap. No canonical
   field order changes for any existing verb.
2. **No route or v2.2 change.** The keyed body is carried in the existing `file`
   event on `POST /api/flit/v1/circle/sync`. No `PATH_*` constant, Ktor route,
   `LinkProvider`, HTTP header, DTO field or status code is added (§1, §2, §4,
   §7.5). `/info`, `/register`, `/prepare-upload`, `/upload` and `/cancel`, their
   DTO shapes and their status behaviour stay as in §2.
3. **Wire and digest ownership.** The sealed copy is named
   `dropId + DROP_WIRE_SUFFIX` with `DROP_WIRE_SUFFIX = ".flitdrop"`. The sender
   pushes those sealed bytes with the existing `sendFiles` call, so
   `FileInfo.sha256` equals `cipherSha` and the receiver's landing gate checks
   the sealed bytes; `sha256` on the card stays the plaintext digest, checked
   after `open`. The protocol core holds no key cache, no sealed bytes and no
   drop state.
4. **No remote revocation or backfill.** A departure, a group `rekey` or a
   `keyGen` bump on another drop does not change a published keyed body. There
   is no wrap backfill for a later joiner and no remote deletion: a device that
   kept a wrap can still open an old drop. Mint, wrap, unwrap, seal and open
   leave the `TlsIdentity` pin map unchanged (§13.6, §15.4).
5. **Evidence boundary.** CODE establishes the constants, the two key sets, the
   byte layout and the gate; PURE JVM tests cover the existing verbs' canonical
   bytes unchanged, the exact key sets, the bounds, tamper rejection and
   round-trip behavior. This date adds no harness or device observation and
   makes no device claim for this layer. See
   [per-drop-keys.md](roadmap/per-drop-keys.md).

## 20. Per-drop key parity — app and CLI keyed drop seams (2026-12-10)

### 20.1 Layer and ownership

This is the Integration layer over §19 (the JVM-pure per-drop key core). It wires
`DropKeyCore` into the app's Circle file-drop path and the CLI's `circle file`
path, and adds the receive-side digest gate on both surfaces. It lives in
`app/src/main/kotlin/app/flit/share/` (`FlitEngine.kt`, `FlitCircleSync.kt`,
`FlitCircleStore.kt`, `CircleUi.kt`) and
`harness/src/main/kotlin/app/flit/share/harness/Harness.kt`. No protocol
constant, canonical byte layout, key wrap or AES-GCM primitive is redefined:
the two surfaces call `DropKeyCore.mint`, `wrap`, `unwrap`, `seal`, `open`,
`keyedFileBody` and `parseFileKeyBody` from §19.2 and read their bounds from
§19.4. No route, header, DTO field, QR parameter or v2.2 status code changes:
the keyed body stays the body of an existing signed `file` event on
`POST /api/flit/v1/circle/sync` (§7.3, §7.5), and the sealed bytes go through
the existing `sendFiles` call (§2.3). A stock LocalSend v2.2 peer sees the same
wire it saw before (§2, §4); a v0.2.0 peer sees the same `file` body and bytes
as before for a legacy drop.

Both surfaces follow one send rule: mint one fresh `DropKeyCore.Key` per file
drop, wrap it to the current members' certificates (uppercase fingerprints, at
most `MAX_WRAPS = 32`, no group-key fallback or escrow), seal a copy of the
source bytes with AES-GCM, announce the sealed form in a signed keyed `file`
body built by `DropKeyCore.keyedFileBody` (§19.3), and push the sealed bytes
with `sendFiles`. A legacy three-key body (`FILE_BODY_KEYS = {name, size,
sha256}`) stays plaintext and keeps its current behaviour; the CLI's
`circle post --file` path stays legacy.

### 20.2 App send seams

| Seam | Contract |
| --- | --- |
| `FlitCircleSync.postFile(body: String)` | Additive overload that posts `CircleEvent.FILE` with the caller-built body through the same signed `emit`/`fanout` path. The legacy `postFile(name, size, sha)` and `circleFileBody(name, size, sha)` stay for plaintext drops. `circleBodyValid` gates the body before it is signed, so only the exact legacy or keyed key set can be posted. |
| `FlitCircleSync.circleBodyValid(type, body)`, `FILE` branch | Delegates to `CircleCore.validFileBody` (§19.5) and accepts either the exact legacy set or the exact keyed set `KEYED_FILE_BODY_KEYS = {name, size, sha256, keyGen, cipherSha, iv, wraps}`, with each `wraps` value bounded by `MAX_WRAPPED_CHARS`. It stays closed to partial sets, extra fields, string `size`, blank fingerprints and oversized wrap values: a keyed event is otherwise refused by the app's own body gate before any unwrap runs. |
| `FlitEngine.circlePostFile(name, size, sha, spoolPath)` | Gathers current members from `FlitCircleStore.load`, resolves each fingerprint's certificate from `TlsIdentity.pinned(fp)` or the member's `CircleEvent.cert` in the folded log, calls `DropKeyCore.mint` once, wraps to the members with usable certificates, seals a cache copy at `<spool>.flitdrop`, builds the keyed body with `DropKeyCore.keyedFileBody(<KeyedFile>)`, posts it through `FlitCircleSync.postFile(body)`, then starts delivery with the sealed path. No usable certificate, or a roster above `MAX_WRAPS`, logs `circle drop: no recipient key material — not posted` or `circle drop: N recipients exceeds key wrap cap`, posts no event and keeps the source spool; a sealed copy, if one was made, is dropped. |
| `FlitCircleStore.CircleDelivery` | Gains additive fields `cipherSha: String = ""` and `wrappedFor: List<String> = emptyList()`, plus terminal status `NO_KEY = "no-key"`. `decode` keeps its current normalisation: rows with a blank sha are dropped, blank status fingerprints stripped, and old rows decode without the new keys. The delivery record holds only the sealed spool path; no plaintext content key is persisted. |
| `circleDeliveryTerminal` | Includes `NO_KEY`. A fingerprint with no entry in `wraps` gets `NO_KEY` locally and no bytes; it never reaches `sendFiles`. |
| `FlitEngine.startCircleDelivery` / `deliverCirclePending` | Keep the per-sha `Mutex`, the 30 s worker and the 20/40/60 s backoff unchanged. They push the sealed spool with `sendFiles(..., multi = false)`; the declared `FileInfo.sha256` is `cipherSha`, so the receiver's landing gate checks the sealed bytes (§20.4). A returned `SendReport` still concludes as `SENT`, `REFUSED` or `FAILED`; a thrown carrier or a missing route stays `PENDING`. When every current recipient is terminal, the sealed spool is deleted. |
| `CircleUi.kt` | Extends the local-status order with `NO_KEY -> 4` and shows `file drop · no key for this device` for that status. Landed and open-file states are otherwise unchanged; status remains local and is never an event. |

### 20.3 CLI send seams

| Seam | Contract |
| --- | --- |
| `Harness.circleFileAnnouncement` | Gains a keyed overload that mints the same body shape as `DropKeyCore.keyedFileBody` (§19.3). The legacy `{name, size, sha256}` builder stays and is used by `circle post --file`. |
| `Harness.kt` `circle file` | Wraps to the current members in `CircleCliState` using `TlsIdentity.pinned(fp)`, seals a `*.flitdrop` copy of the spool, announces the keyed event through the keyed `circleFileAnnouncement`, and calls the existing `deliverCircleFile` with the sealed path. Above `MAX_WRAPS` or with no usable certificate, the command prints `!! no recipient key material — nothing posted` and exits without an event. There is no second announcement builder for the keyed form. |
| `CircleFileDelivery` | Gains `cipherSha` and `wrappedFor` with defaults; `circleDeliveryOutcome` is unchanged. Statuses stay local to the CLI and are never gossiped. |
| `flit-cli parse-qr`, `Qr.kt` v1/v2/v3, parcel frame codecs | Untouched. Sealed drop bytes cross them as opaque payload; no QR key parameter or frame field is added (§8, §16, §17). |

### 20.4 Receive digest gate

Keyed drops are gated by two distinct SHA-256 values from the signed keyed body
(§19.3): `cipherSha` is the digest of the sealed bytes and is declared as the
`FileInfo.sha256` of the `sendFiles` push, so the existing landing gate (§2.2,
§4.2) accepts only the sealed bytes; `sha256` is the plaintext digest, checked
only after `open`. The receiver never treats a landed digest match as proof of
plaintext.

`FlitEngine` rebuilds an in-memory `cipherSha → DropKeyMaterial` index from the
bounded folded event log at boot. In `onUploadFinish`, an `UploadResult.Saved`
whose landed digest matches an indexed `cipherSha` passes through these steps in
order:

1. `DropKeyCore.unwrap(wraps, identity)` uses the receiving device's own
   identity. A fingerprint absent from `wraps`, a wrong private key or a
   malformed Base64 blob returns null (§19.6) — a refusal, not an exception.
2. `DropKeyCore.open(key, sealed)` is called with the unwrapped key on the
   landed bytes. A GCM tag failure or a short value returns null.
3. The SHA-256 of the opened plaintext is compared with the announced `sha256`.
   A mismatch refuses the drop.
4. On success, the plaintext is written under the announced name,
   `publishReceived` records its plaintext digest so `_circleLandedShas`
   matches the card, and the landed ciphertext temp is deleted.
5. On any failure the engine logs `circle drop: cannot open <name> (<reason>)`,
   drops the landed ciphertext and adds no ledger entry. A landed file with no
   `cipherSha` match takes the existing plaintext path unchanged.

The CLI's `ConsoleDelegate.onUploadFinish` applies the same unwrap → open →
plaintext-sha step against the CLI's own `circle.state` events, writing the
plaintext next to the landed ciphertext. It prints
`drop-open <name> <plainSha> (<bytes> bytes)` on success,
`drop-refused <name>: no wrap` for a non-recipient and
`drop-refused <name>: open failed` for a tag or digest failure; a refusal leaves
no plaintext file. A modified `wraps`, `iv` or `cipherSha` is caught earlier:
`CircleCore.verify` fails on the changed body before any unwrap is attempted
(§19.7, §7.4).

### 20.5 No-new-route and v2.2 boundary

1. **Keyed body rides in the existing `file` event.** Its wire home is
   `POST /api/flit/v1/circle/sync` (`Protocol.PATH_CIRCLE_SYNC`, §1, §7.5). No
   `PATH_*` constant, Ktor route, `LinkProvider`, byte-transfer route, HTTP
   header, `CircleRequest`/`CircleResponse` field or `CircleRequest.body`
   repurposing is added. The key material is text inside the event body.
2. **No status-code change.** The signed `200` decision answer and the `400` /
   `403` / `500` refusals of §7.5 and §9 are unchanged. Delivery conclusions
   are unchanged: a returned report concludes, a thrown carrier or no route
   stays `PENDING`, and `NO_KEY` is terminal locally. A legacy drop lands in
   plaintext with the same status rules.
3. **No second crypto path.** Mint, wrap, unwrap, seal and open come from
   `DropKeyCore` (§19.6). Signing stays with `TlsIdentity.sign` and
   `CircleCore.verify`; the domain-tagged `FLITDROP1` AAD is the only new AAD
   and it is owned by the protocol core. The app and CLI hold no key cache of
   their own beyond the in-memory `DropKeyMaterial` index used for the one
   decrypt pass.
4. **v2.2 is untouched.** `/info`, `/register`, `/prepare-upload`, `/upload`
   and `/cancel`, their DTO shapes and their status behaviour stay as in §2;
   no QR key parameter is added (§8). The earlier courier, relay and parcel
   contracts (§5, §13–§18) keep their byte shapes; a sealed drop is opaque
   payload to them.

### 20.6 State ownership and evidence boundary

Content keys exist only as wrapped ciphertext in the signed event, as the
sender's ephemeral key until the drop finishes, and as one in-memory unwrapped
key for a single decrypt pass. `FlitCircleStore` and `circle.state` persist no
plaintext key; removing the in-memory index or restarting the CLI still leaves
only the wrapped key in the bounded event log. The sealed spool lives in app
cache or the CLI temp path and is removed when all current recipients are
terminal or the drop is refused. The received ledger keeps the plaintext
digest, as before.

CODE establishes the app and CLI seams, the body gate and the two-hash digest
gate. PURE JVM tests pin the keyed/legacy key sets in `DropKeyCore` consumption,
`postFile(body)` emitting a verifiable signed keyed event, the additive store
fields surviving encode/decoding with old rows still decoding, and the receive
seam's mapping of a `cipherSha` match to a plaintext landing with refusals for a
missing wrap, a tag failure and a sha mismatch. SIM-HARN evidence covers two CLI
identities with a hash-exact plaintext landing and a `drop-open` line where the
wire bytes hash to `cipherSha`, a third identity with no wrap landing no
plaintext, a tampered keyed event skipped at the signature gate, a legacy
plaintext drop, and a repeat of identical bytes producing a different
`cipherSha` and a different wrapped key. No device observation is made for this
layer; the receive side is test-evidenced only. See
[per-drop-parity.md](roadmap/per-drop-parity.md).

## 21. Delivery receipt contract — event body, gate and tally (2026-12-11)

### 21.1 Layer and ownership

This is the Protocol layer for delivery receipts. A receipt is one signed
`CircleEvent` in which a member acknowledges that a named file drop landed on
that member's device. It lives in the JVM-pure package
`app.flit.share.protocol.circle`: the eleventh verb in `CircleEvent.kt`, a new
type-specific body gate and a pure roll-up in `CircleCore.kt`. No Android
import, socket, file write, preference or wall-clock read is added. No route,
header, DTO field, QR parameter, status code or v2.2 shape changes: the receipt
is an additive event carried in `CircleRequest.events` on the existing
`POST /api/flit/v1/circle/sync` route (§1, §7.3, §7.5), and a stock LocalSend
v2.2 peer sees the same wire it saw before (§2, §4).

`RECEIPT` is an event, not a delivery status. It does not change
`CircleDelivery.statuses`, the sender's spool path or any reducer on this date.

### 21.2 Frozen API

```
object CircleEvent {
  const val RECEIPT = "receipt"      // eleventh verb
  val TYPES: Set<String> = setOf(..., RECEIPT)
}

object CircleCore {
  val RECEIPT_BODY_KEYS: Set<String> = setOf("dropId", "sha256", "keyId")
  fun validReceiptBody(body: String): Boolean
  fun receiptAuthors(events: Iterable<CircleEvent>, dropId: String,
                     sha256: String, members: Iterable<String>): Set<String>
}
```

`RECEIPT = "receipt"` joins `CircleEvent.TYPES` as the eleventh verb. Adding it
does not change `CircleCore.canonical`, `validShape`, or the validity or
acceptance of any existing verb.

### 21.3 Receipt body

The signed `body` is a JSON object with exactly the closed key set
`RECEIPT_BODY_KEYS = {"dropId","sha256","keyId"}`:

```
receipt  {"dropId":"<id of the file announcement>","sha256":"<landed content digest>","keyId":"<per-drop key identifier>"}
```

| Field | Type | Contract |
| --- | --- | --- |
| `dropId` | String | The `id` of the `file` event being acknowledged, not the recipient's local delivery id. Non-blank, at most `MAX_FIELD_CHARS` (256). |
| `sha256` | String | The plaintext digest carried by the `file` event (§19.3), read after `DropKeyCore.open` and the landing hash gate; `ProtocolClient.sendFiles` gates on the sealed `cipherSha` instead (§20.4). Non-blank, at most `MAX_FIELD_CHARS` (256). |
| `keyId` | String | The per-drop key identifier published by `DropKeyCore.keyId` (§19.6). Provenance only: a receipt never invents one, and it is not a selection key (§21.5). Non-blank, at most `MAX_FIELD_CHARS` (256). |

The whole body is at most `MAX_BODY_CHARS` (16 KiB). There is no second time
field: `createdMs` on the event is the acknowledgement time. The body carries
no `path`, `spoolPath`, `token`, `landedMs`, key material, file bytes or local
status string. A rekey that changes a key identifier does not alter an earlier
receipt for the same landed content.

### 21.4 `CircleCore.validReceiptBody` gate

`validReceiptBody(body)` is the type-specific gate for the receipt verb, in the
shape of `validFileBody` (§19.5). It accepts a body when and only when:

1. The body is non-blank and at most `MAX_BODY_CHARS` (16 KiB).
2. `WireJson.json` parses it into a `JsonObject` whose keys are exactly
   `RECEIPT_BODY_KEYS` — no missing, extra or renamed key.
3. Each value is a JSON string primitive (a number or boolean is refused) that
   is non-blank and within `MAX_FIELD_CHARS` (256).

A blank, oversized, malformed, non-string-valued or extra-key body returns
false. The gate is structural only: it signs nothing, pins nothing and reads no
store. `validShape()` stays type-agnostic bounds (§7.4), so this gate never
changes an existing event's signature or acceptance.

### 21.5 `CircleCore.receiptAuthors` tally

`receiptAuthors(events, dropId, sha256, members)` is a pure roll-up of the
current members who signed a well-formed receipt for one landed drop:

1. Normalise `members` with trim and uppercase, as in `CircleCore.tally` (§7.4).
2. Iterate `CircleCore.order(events)`. Keep an event only when its `type` is
   `receipt`, its author fingerprint is in the normalised member set, its body
   passes `validReceiptBody`, both body `dropId` and `sha256` equal the two
   arguments exactly, and `CircleCore.verify` passes. A departed/non-member, a
   body for another drop, a malformed body or a forgery is ignored.
3. Keep at most one receipt per author. Overwriting while iterating in `ORDER`
   makes the greatest-by-`ORDER` receipt the winner ("latest wins"), so two
   peers that received the same receipts in different orders return the same
   set.
4. Return the deduplicated set of author fingerprints; the caller counts it.

`keyId` is recorded in the signed body but is not a selection key: a later
rekey that changes the key identifier does not invalidate an earlier receipt
for the same `(dropId, sha256)`. The function introduces no new cap and no replay
state; it is a pure fold over the supplied events.

### 21.6 Canonical, replay, wire and state boundary

1. **Canonical bytes unchanged.** `CircleCore.canonical` covers the body as one
   length-prefixed field (§7.4); the receipt reuses it with domain tag
   `FLITCIRCLE1` and the existing `id`, `circleId`, `authorFp`, `lamport`,
   `type`, `body`, `createdMs` field order. No delimiter can be smuggled through
   the body, and no existing verb's bytes change.
2. **Signature unchanged.** `CircleCore.create` mints the receipt with the
   receiver's `TlsIdentity`, `CircleCore.sign` writes `sig`, and
   `CircleCore.verify` binds the embedded certificate's own SHA-256 DER to
   `authorFp` before verifying and pinning (§4.1). A sender cannot forge a
   receiver's receipt: the certificate is checked against the claimed author
   before the pin store is touched. There is no second signature primitive.
3. **Replay and caps unchanged.** Receipts use `CircleCore.order`, `dedup`,
   `evaluate`/`accept` and `fresh` exactly as the other verbs do: `6 h`
   `REPLAY_WINDOW_MS`, `5 min` `CLOCK_SKEW_MS`, a bounded `4096`-id set,
   `MAX_EVENTS_PER_REQUEST = 1024`, `MAX_REQUEST_BYTES = 8 MiB`,
   `MAX_SIG_CHARS = 1024`, `MAX_CERT_CHARS = 8 KiB`, and precedence
   `Invalid → OutsideWindow → Duplicate → Accept`. Callers verify before
   `evaluate`, because `evaluate` reserves the id before crypto. No new cap is
   introduced: the roster is bounded by `FlitCircleHost.MAX_MEMBERS = 500` and
   the durable log by `FlitCircleStore.EVENT_CAP = 500`.
4. **No route or v2.2 change.** No `PATH_*` constant, Ktor route, `LinkProvider`,
   byte-transfer route, HTTP header, `CircleRequest`/`CircleResponse` field,
   status code, QR key parameter or DTO default changes. The receipt rides in
   `CircleRequest.events` on `POST /api/flit/v1/circle/sync` under the existing
   `8 MiB` cap, or inside a courier/QR parcel payload that already carries
   signed events (§13–§18). `/info`, `/register`, `/prepare-upload`, `/upload`
   and `/cancel`, their DTO shapes and their status behaviour stay as in §2.
5. **No new state.** The protocol module owns no durable receipt state; its only
   mutable memory is the existing bounded `seen` id set in `CircleCore`. The
   durable log is `FlitCircleStore.events`; `FlitCircleStore.reduce` already
   appends unknown verbs as history, so a folded receipt enters that log without
   a reducer change on this date. Sender-local `CircleDelivery.statuses`
   (`pending`, `sent`, `refused`, `failed`) stay local-only and unchanged: a
   receipt is not a gossiped status field and does not replace the delivery
   record. The `FlitCircleSync.circleBodyValid` branch for the new verb (its
   current `else -> false` refuses it) and the `FlitCircleHost` SYNC body gate,
   as well as mapping a receipt to a local status, belong to 2026-12-14.

### 21.7 Evidence boundary

CODE establishes the verb, the closed `RECEIPT_BODY_KEYS` set, the
`validReceiptBody` gate and the `receiptAuthors` roll-up. PURE JVM tests cover
the exact key set and bounds, canonical bytes of the existing verbs unchanged,
create/sign/verify round-trip and forged-author rejection, replay and dedup
precedence, and roll-up convergence with wrong-drop, malformed, non-member and
rekey-ignored events. No SIM-HARN or DEVICE evidence exists for receipts on this
date, and no device claim is made: a receipt is one member's signed
acknowledgement of a local, hash-exact landing, not proof of delivery to every
member. See [delivery-receipts.md](roadmap/delivery-receipts.md).

## 22. Delivery receipts parity — app and CLI mint, gates and tally (2026-12-14)

### 22.1 Layer and ownership

This is the Integration layer over §21 (the JVM-pure delivery receipt core). It
wires `CircleCore.validReceiptBody` and `CircleCore.receiptAuthors` into the
app's Circle delivery records and the CLI's Circle commands. It lives in
`app/src/main/kotlin/app/flit/share/` (`FlitEngine.kt`, `FlitCircleSync.kt`,
`FlitCircleHost.kt`, `CircleUi.kt`) and
`harness/src/main/kotlin/app/flit/share/harness/Harness.kt`. No protocol
constant, canonical byte layout, event type or body key set is redefined: both
surfaces call the §21.2 API and read their bounds from §21.3 and §21.4. No route,
header, DTO field, QR parameter or v2.2 status code changes: a receipt is still
the one signed `CircleEvent` of §21 carried in `CircleRequest.events` on
`POST /api/flit/v1/circle/sync` (§7.3, §7.5) or inside a courier/QR parcel that
already carries signed events (§13–§18). A stock LocalSend v2.2 peer sees the
same wire it saw before (§2, §4).

There is no new status value and no new schema field on `CircleDelivery` (app)
or `CircleFileDelivery` (CLI). The per-drop key fields added in §20.2 and §20.3
stay as they are. The community log cap `EVENT_CAP = 500`, the per-request cap
`MAX_EVENTS_PER_REQUEST = 1024` and the request cap `MAX_REQUEST_BYTES = 8 MiB`
still bound where receipts can appear.

### 22.2 App gates

Two app-side admission points that currently refuse the receipt verb are opened
through the protocol core. Neither restates the body rules.

| Gate | Contract |
| --- | --- |
| `FlitCircleSync.circleBodyValid(type, body)`, `RECEIPT` branch | Delegates to `CircleCore.validReceiptBody(body)` (§21.4) beside the `FILE` branch. The exact key set `{"dropId","sha256","keyId"}`, non-blank string values within `MAX_FIELD_CHARS` and a body within `MAX_BODY_CHARS` are accepted; a partial, extra-key, blank, non-string or oversized body is refused before it can be signed. This is also the mint-side gate: `emit` signs, persists and fans out nothing when this gate is false (§7.3). |
| `FlitCircleHost.kt` SYNC admission filter | Adds the matching condition beside `CircleEvent.FILE || CircleCore.validFileBody(...)`: a receipt event is admitted only when `CircleCore.validReceiptBody(it.body)` is true. The `circleId == current.id`, `type in CircleEvent.TYPES`, body length, `normalized(authorFp) == requester`, JOIN/REKEY restrictions, non-blank `cert`, `CircleCore.verify`, local id dedup and `CircleCore.accept == Accept` steps are unchanged. Verify still runs before `accept`, so a forged receipt cannot reserve an id. |

`FlitCircleStore.reduce` already appends unrecognised verbs as history, so an
admitted receipt enters `CircleState.events` without a reducer change and stays
under `EVENT_CAP = 500`. A duplicate id, a `createdMs` outside the replay
window, a bad signature or a certificate whose own fingerprint differs from
`authorFp` is refused by the existing §7.4 / §21.6 path and changes neither the
confirmed count nor any delivery row.

### 22.3 App mint path

`ServerDelegate.onUploadFinish` still fires once per landed file (§3.4). For a
keyed drop it runs after the per-drop key layer has unwrapped and opened the
bytes and checked the plaintext SHA-256 against the announced `file` event
(§20.4). At that point the engine reads the `id` of that `file` event as
`dropId`, derives `keyId` with `DropKeyCore.keyId(dropId, keyGen)` from the
event's `keyGen`, and mints a body that is exactly `{dropId, sha256, keyId}`
using `CircleCore.create(..., CircleEvent.RECEIPT, body)`. The `sha256` is the
plaintext digest carried by the keyed `file` body, not the sealed `cipherSha`
that `sendFiles` gates on, so the sender's read side and the receiver's mint
side name the same content.

A new `postReceipt(dropId, sha, keyId)` helper, beside `FlitCircleSync.postFile`
(§20.2), publishes the event through the existing signed `post()` / `emit` /
fan-out path. It signs nothing that `circleBodyValid` refuses (§22.2) and adds
no route of its own.

Nothing is minted when the plaintext digest gate fails, on `UploadResult.Mismatch`
or `UploadResult.Error`, when `keyGen` or `wraps` is absent from the `file` body
(a legacy plaintext drop), on a wrong identity or `NO_KEY`, or when the carrier
throws. No `landedMs`, path, spool path, token, key material, file bytes or local
status string enters the body; `CircleEvent.createdMs` is the acknowledgement
time. `circle post --file`, `circle file` delivery and
`ConsoleDelegate.onUploadFinish` keep their existing behaviour (§20.3, §20.4).

### 22.4 Tally and UI

A pure confirmation helper takes the folded `CircleState` and one
`CircleDelivery`. It collects the `id`s of `file` events whose body `sha256`
matches the delivery's `sha256`, calls
`CircleCore.receiptAuthors(state.events, dropId, sha, currentMembers)` (§21.5)
for each candidate id, and unions the returned author fingerprints into one set.
The count is the size of that set. `CircleCore.ORDER` picks one entry per author
so arrival order cannot change the count, and the union keeps one entry per
fingerprint across re-announcements of the same content. `keyId` is read only as
signed provenance and never selects a receipt, so a later rekey does not change
the count. The helper has no Compose dependency and is unit-tested directly.

`CircleUi.computeFeedData` folds this `confirmed` count into the file-card data
with `remember` memoization, so the tally is recomputed from the folded events
and member roster rather than stored as a new event field. The status line
reports the sent count and the confirmed count as plain text. No receipt is
rendered as a chat row, no emoji is added and no chat-bubble treatment is
introduced.

A confirmed author may be recorded through the existing `setDeliveryStatus`
reducer as `SENT`, overriding an earlier `REFUSED` or `FAILED` inference, inside
one `FlitCircleStore.update(ctx)` call; no phantom delivery row is created.
`FlitEngine.refreshCircle()` publishes the updated deliveries through
`_circleDeliveries` as it does for the other status writes. Sender-local
`CircleDelivery.statuses` keep their existing vocabulary and remain local-only:
no status string is added and no status is gossiped. Retry-worker and Circle
sync timing constants are unchanged.

### 22.5 CLI receipt contract

`circleUsage()` gains the command `circle receipt --sha SHA [--drop-id ID]
[--host ip[:port]]`. It requires a joined circle (`circleId`, `key`, `host`).
The event is resolved by matching the plaintext `sha256` in the signed `file`
body: with no `--drop-id`, that event's `id` becomes `dropId`; with `--drop-id`,
the supplied id is used. The command then requires `keyGen` and `wraps` in that
`file` body and derives `keyId` with `DropKeyCore.keyId(dropId, keyGen)`. If no
`file` event matches the sha, or the keyed fields are absent (a legacy drop),
it prints `!! no key id for drop — receipts are for keyed drops` and exits
without an event. No value is fabricated.

The minted body is exactly `{dropId, sha256, keyId}`. The event is appended to
`st.events` and sent with `pushCircleEvent` through the existing `circleEvent`
helper, using the joined identity. `circle sync` and `circle file` print a
confirmed count from `CircleCore.receiptAuthors(st.events, dropId, sha,
st.members)` over the same candidate drop ids. `CircleFileDelivery` needs no new
field: the confirmed set is derived from the log. The existing
"status is local-only, never gossiped" wording stays for the other statuses;
the hint to run `circle receipt` lives in the reproduction recipe, not in the
receive log.

### 22.6 No-new-route and v2.2 boundary

1. **Existing wire home only.** A receipt rides in `CircleRequest.events` on
   `POST /api/flit/v1/circle/sync` (`Protocol.PATH_CIRCLE_SYNC`, §1, §7.5) or in
   a courier/QR parcel payload that already carries signed events (§13–§18). No
   `PATH_*` constant, Ktor route, `LinkProvider`, byte-transfer route, HTTP
   header, `CircleRequest`/`CircleResponse` field, status code or QR key
   parameter is added.
2. **v2.2 untouched.** `/info`, `/register`, `/prepare-upload`, `/upload` and
   `/cancel`, their DTO shapes and their status behaviour stay as in §2. The
   earlier courier, relay and parcel contracts (§5, §13–§18) keep their byte
   shapes.
3. **No second crypto or replay path.** Minting uses `CircleCore.create` with
   the existing identity and §21.3 body; the `FLITCIRCLE1` canonical bytes,
   signature, `6 h` replay window, `5 min` skew, dedup and caps of §21.6 are
   reused unchanged. No new status code or response shape is introduced.
4. **No schema change.** No field is added to `CircleDelivery` or
   `CircleFileDelivery`; the 500-event log cap and the 1024-event per-request
   cap still bound receipt state.

### 22.7 Evidence boundary

CODE establishes the app gates, the app and CLI mint seams, the CLI command and
the tally fold. PURE JVM tests pin the `RECEIPT` branch of `circleBodyValid`, the
host SYNC admission of a valid receipt and the skip of a malformed one, the pure
confirmation helper (sha correlation, one author per fingerprint under reversed
order and reversed re-announcements, a changed `keyId` leaving the count intact,
non-member authors ignored), the `setDeliveryStatus` promotion to `SENT` and the
unchanged store row schema. SIM-HARN evidence covers the two-bot keyed-drop walk
in which a receipt is minted after the plaintext digest gate, the next sync
shows one confirmed author, a forged receipt is refused at the host gate and a
replayed id leaves the count at one. No device claim is made in this contract;
any phone↔bot observation belongs in the completion document and records only
what the logs show. See [receipts-parity.md](roadmap/receipts-parity.md).

## 23. Circle pad contract — pad verb, closed bodies and deterministic view (2026-12-15)

### 23.1 Layer and ownership

This is the Protocol layer for a shared, line-oriented text pad in a Circle. A
line is written or removed only by signed `CircleEvent`s; the pad itself is a
derived view that every device folds from the same event log. It lives in the
JVM-pure package `app.flit.share.protocol.circle`: the twelfth verb in
`CircleEvent.kt` and a new object `PadCore.kt`. No Android import, socket, file
write, preference or wall-clock read is added. No route, header, DTO field,
store field, status code or v2.2 shape changes: the `pad` verb is carried in
`CircleRequest.events` on the existing `POST /api/flit/v1/circle/sync` route
(§1, §7.3, §7.5) and in the bounded seed log from `/circle/join`; it travels
through `FlitCircleSync.fanout` and the calm repair pass. A stock LocalSend
v2.2 peer sees the same wire it saw before (§2, §4).

`PAD` is an event, not a second pad store. `PadCore` keeps no mutable state
between calls and persists no view. The signed `pad` events in
`FlitCircleStore.CircleState.events` are the only durable pad state.

### 23.2 Frozen API (`PadCore.kt`)

```
object PadCore {
  const val MAX_LINES = 256, MAX_KEY_CHARS = 128, MAX_TEXT_CHARS = 4096
  val PAD_PUT_KEYS: Set<String> = setOf("op", "key", "text")
  val PAD_DEL_KEYS: Set<String> = setOf("op", "key")
  data class PadLine(val key: String, val text: String, val lamport: Long = 0L,
                     val authorFp: String = "", val id: String = "",
                     val deleted: Boolean = false)
  data class PadView(val lines: List<PadLine>,
                     val winners: Map<String, PadLine> = emptyMap())
  fun padPutBody(key: String, text: String): String
  fun padDelBody(key: String): String
  fun validPadBody(body: String): Boolean
  fun view(events: Iterable<CircleEvent>, circleId: String): PadView
  fun merge(views: Iterable<PadView>): PadView
}
```

`PadLine` carries the winning operation's order key (`lamport`, `authorFp`,
`id`) and a `deleted` tombstone flag beside `key` and `text`; the four added
fields have defaults, so a bare `(key, text)` construction still compiles.
`PadView` has two fields: `lines` holds the live, lexicographically ordered
keys, and `winners` holds one `PadLine` per key, including tombstones and order
keys. Keeping the order keys in the view is what lets `merge` (§23.7) re-apply
the conflict rule without the underlying events.

`CircleEvent.PAD = "pad"` joins `CircleEvent.TYPES` as the twelfth verb. Adding
it does not change `CircleCore.canonical`, `create`, `sign`, `verify`, `order`,
`dedup`, `accept` or `validShape`: a pad event is signed and checked like a
`message` or `file` event. Its canonical bytes cover every field except `sig`
and `cert`, with domain tag `FLITCIRCLE1` and the existing field order (§4.1,
§7.4).

### 23.3 Closed body shapes

The signed `body` is a small JSON object with exactly one of two closed shapes:

| Shape | Body (exact keys) |
| --- | --- |
| `put` | `{"op":"put","key":"<line-key>","text":"<text>"}` — exactly `PAD_PUT_KEYS = {"op","key","text"}` |
| `del` | `{"op":"del","key":"<line-key>"}` — exactly `PAD_DEL_KEYS = {"op","key"}` |

`padPutBody(key, text)` and `padDelBody(key)` build these two strings, so a body
cannot be minted in a shape the gate would refuse: the producers and the gate
stay together in one JVM-pure file.

| Field | Type | Contract |
| --- | --- | --- |
| `op` | String | Exactly `"put"` or `"del"`; any other value is refused. |
| `key` | String | Non-blank line key, at most `MAX_KEY_CHARS` (128). Both shapes carry it. |
| `text` | String | Non-blank line text, at most `MAX_TEXT_CHARS` (4096). Present only in `put`; a `del` body has no `text` key. |

The whole body is at most `CircleCore.MAX_BODY_CHARS` (16 KiB). `validPadBody`
accepts exactly `PAD_PUT_KEYS` when `op == "put"` and exactly `PAD_DEL_KEYS` when
`op == "del"`. An extra key, a missing key, a non-string `op`/`key`/`text`, an
unknown `op`, a blank key or text, or an over-cap value is refused. The body
carries no circle id, author, order key, timestamp, path or local status: those
stay in the signed event fields.

### 23.4 Caps

| Constant | Value | Meaning |
| --- | --- | --- |
| `PadCore.MAX_LINES` | `256` | Most live keys in a `PadView`; headroom under `FlitCircleStore.EVENT_CAP = 500`. |
| `PadCore.MAX_KEY_CHARS` | `128` | Line key bound; below `CircleCore.MAX_FIELD_CHARS` (256). |
| `PadCore.MAX_TEXT_CHARS` | `4096` | Per-line text; a put body with a quoted key and text stays under `CircleCore.MAX_BODY_CHARS` (16 KiB). |
| `PadCore.PAD_PUT_KEYS` / `PAD_DEL_KEYS` | `{"op","key","text"}` / `{"op","key"}` | Closed key sets for the two shapes. |

Pad events reuse the existing Circle bounds unchanged: `MAX_BODY_CHARS = 16
KiB`, `MAX_FIELD_CHARS = 256`, `MAX_SIG_CHARS = 1024`, `MAX_CERT_CHARS = 8 KiB`,
`MAX_EVENTS_PER_REQUEST = 1024`, `MAX_REQUEST_BYTES = 8 MiB`, a `6 h`
`REPLAY_WINDOW_MS` with `5 min` `CLOCK_SKEW_MS`, a bounded `4096`-id `MAX_SEEN`
set, and the `Invalid → OutsideWindow → Duplicate → Accept` precedence of §7.4.
No new cap or replay state is introduced.

### 23.5 `validPadBody` gate and `PadView`

`validPadBody(body)` is the type-specific gate, in the shape of `validFileBody`
(§19.5) and `validReceiptBody` (§21.4). It is structural and total: it parses
the body as a `JsonObject`, checks the closed key set for the declared `op`, and
checks each value's type and bound. A null, blank, malformed, oversized or
non-string-valued body returns false; nothing is thrown at the caller. It signs
nothing, pins nothing and reads no store. `CircleCore.validShape()` stays
type-agnostic bounds (§7.4), so this gate never changes an existing event's
signature or acceptance.

`PadLine` is one resolved line: `key` and `text` plus the order key
(`lamport`, `authorFp`, `id`) of the winning operation and a `deleted` flag. A
line with `deleted = true` is a tombstone: its `text` is empty and it does not
appear in `lines`, but it stays in `winners` so a later operation with a greater
order key can replace it. `PadView` has two fields: `lines` is the live view —
sorted lexicographically by `key`, free of tombstoned keys and capped at
`MAX_LINES` (256) entries — while `winners` maps every key with a surviving
operation to its `PadLine`, including tombstones and order keys and bounded only
by the distinct keys in the folded log. A view is a value, not a persisted row.
The app may hold one in a StateFlow rebuilt on each store fold, but the bounded
event log remains the only durable pad state.

### 23.6 `view` and the conflict rule

`view(events, circleId)` folds a set of events into a `PadView`:

1. Keep only events whose `type` is `pad`, whose `circleId` equals the argument
   and whose body passes `validPadBody`. Other verbs, foreign circles and bad
   bodies are ignored.
2. Apply `CircleCore.dedup` so a repeated id counts once even when a snapshot is
   replayed.
3. Resolve each `key` by the order key `(lamport, authorFp, id)` — the first
   three components of `CircleCore.ORDER` (§7.4). The greatest order key wins:
   `put` sets the line text, a `del` with a greater order key tombstones the key,
   and a `del` older than the winning `put` is inert.
4. Emit the live keys sorted by `key`, capped at `MAX_LINES`.

`view` does **not** call `CircleCore.verify`. Verification and the replay guard
run at the receiving route (`FlitCircleSync.fresh`, then `CircleCore.accept`)
before events reach the fold; a pure fold that re-verified would pin
certificates from a test or a cached snapshot, and `PadCore` owns no pin store.
Fold order does not matter: for any partition and shuffle of one event set the
result is byte-identical, and re-folding an event already present leaves the
`PadView` unchanged.

### 23.7 `merge` and its law

`merge(views)` re-applies the same per-key `(lamport, authorFp, id)` rule to
views already derived from separate event sets. It reads each input's `winners`
map (falling back to `lines` keyed by `key` for a bare view), compares the order
key carried on each `PadLine`, keeps the greatest per key — tombstone included —
and rebuilds both `winners` and `lines`. It is the view-level form of the
conflict rule, so folded views compose without the underlying events:

```
view(a + b) == merge(view(a), view(b))
```

The equality holds for any partition and delivery order of one event set: a
repeated event is resolved once, a tombstone from one view beats an older put in
another, and the merged lines stay sorted by `key` and capped at `MAX_LINES`.
The `lines` cap holds because each input view already has at most `MAX_LINES`
live keys. The `winners` map is bounded by distinct keys with surviving
operations, not by `MAX_LINES`, so a tombstone carried there can beat a put from
another view even when neither line is in `lines`. `merge` is total, never
throws on a malformed view, and keeps no state between calls.

### 23.8 Canonical, replay, wire and state boundary

1. **Canonical bytes unchanged.** The `pad` event reuses `CircleCore.canonical`
   with domain tag `FLITCIRCLE1` and the existing field order; the body is one
   length-prefixed field, so a change to a line key or text breaks the signature
   (§4.1, §7.4). No existing verb's bytes change.
2. **Signature and replay unchanged.** `CircleCore.create` mints the event with
   the author's `TlsIdentity`, `sign` writes `sig`, and `verify` binds the
   certificate to `authorFp` before pinning. The `pad` verb uses the same
   `order`, `dedup`, `evaluate`/`accept`, `fresh` and `6 h` / `5 min` window as
   every other verb (§21.6). Callers verify before `evaluate`, and `view` folds
   only events that have already passed the route gate.
3. **No route or v2.2 change.** No `PATH_*` constant, Ktor route, `LinkProvider`,
   byte-transfer route, HTTP header, `CircleRequest`/`CircleResponse` field,
   status code, QR key parameter or DTO default changes. The pad rides in
   `CircleRequest.events` on `POST /api/flit/v1/circle/sync` (§1, §7.5) and in
   the seed log from `/circle/join`, through `fanout` and the calm repair pass.
   `/info`, `/register`, `/prepare-upload`, `/upload` and `/cancel`, their DTO
   shapes and their status behaviour stay as in §2.
4. **No second persisted state and no second encryption path.** The pad has no
   field of its own in `FlitCircleStore.CircleState`, no reducer change and no
   CLI state row. `FlitCircleStore.reduce` appends the verb as history in the
   bounded event log, as it does for receipts (§21.6). Pad text travels as
   signed JSON inside the existing pinned TLS session; the Circle group key
   stays membership material, not pad encryption. An event trimmed by
   `EVENT_CAP = 500` can remove a line from the rebuilt view; there is no
   backfill, the same limit as file drops (§7.7).
5. **No wire surface for the view.** `PadLine`, its order-key and tombstone
   fields, and `PadView.winners` are JVM-only values; no DTO, header or response
   field carries them. They are rebuilt on demand from the signed log, and the
   `winners` map is never persisted as a second copy of pad state.

### 23.9 Evidence boundary

CODE establishes the twelfth verb, the two closed body shapes, the caps, the
per-key conflict rule and the `merge` law. PURE JVM tests pin the exact key sets
and bounds, the `padPutBody`/`padDelBody` producers feeding `validPadBody`,
create/sign/verify round-trip and forged-author rejection with the existing
verbs' canonical bytes unchanged, `view` ignoring wrong-type, wrong-circle and
bad-body events and deduping repeats, delivery-order convergence, the older-`del`
inert case, the lexicographic order and `MAX_LINES` bound, tombstones and order
keys surviving in `winners`, and `view(a + b) == merge(view(a), view(b))` with
the rule re-applied across views (including a bare view with no `winners` map).
No SIM-HARN or DEVICE evidence exists for the pad on this date, and no device
claim is made: the layer is a pure fold over signed events. No UI, CLI command
or store adapter is wired here. See [circle-pad.md](roadmap/circle-pad.md).

## 24. Circle pad app + CLI contract — pad view flow, PadUi and circle pad commands (2026-12-16)

### 24.1 Layer and ownership

This is the Integration layer over §23 (the JVM-pure pad core). It wires
`PadCore` into the app's Circle surface and the CLI's `circle` command surface.
It lives in `app/src/main/kotlin/app/flit/share/` (`FlitEngine.kt`,
`FlitCircleSync.kt`, `PadUi.kt`, `CircleUi.kt`, `MainActivity.kt`) and
`harness/src/main/kotlin/app/flit/share/harness/Harness.kt`. No protocol
constant, canonical byte layout, event verb, body key set or view type is
redefined: both surfaces call `PadCore.view`, `PadCore.padPutBody`,
`PadCore.padDelBody` and read `MAX_KEY_CHARS` / `MAX_TEXT_CHARS` from §23. No
route, header, DTO field, store field, QR parameter or v2.2 status code changes:
a `pad` event is still the one signed `CircleEvent` of §23 carried in
`CircleRequest.events` on `POST /api/flit/v1/circle/sync` (§1, §7.3, §7.5) and in
the bounded `/circle/join` seed log, fanned out over the existing delta and
calm-repair paths (§7.6). A stock LocalSend v2.2 peer sees the same wire it saw
before (§2, §4).

There is no second pad store. The signed events in
`FlitCircleStore.CircleState.events` remain the only durable pad state (§23.1).
The app holds one derived `PadView` in a StateFlow rebuilt on each store fold;
the CLI derives a view on demand for `show`. Neither writes a pad row, a pad
preference or a persisted copy of `winners`.

### 24.2 App view flow

| Seam | Contract |
| --- | --- |
| `FlitEngine.padView: StateFlow<PadView>` | Backed by a private `MutableStateFlow<PadView>`. On every Circle store fold it is rebuilt as `PadCore.view(events, circleId)` from the current `CircleState.events` and `id`, so live lines, order keys and tombstones are derived from the signed log only. With no Circle state loaded the fold yields an empty view. |
| `FlitEngine.refreshCircle()` | The content-diff signature gains a pad digest computed from the folded view, beside the existing circle id, key, event count / last id, roster, deliveries and courier-handoff outcomes (§15.4). A pad edit therefore publishes a fresh `padView` even when no other tracked field changes, while an unchanged fold stays a no-op and does not invalidate Compose. |

### 24.3 App publish seams

| Seam | Contract |
| --- | --- |
| `FlitEngine.circlePadPut(key: String, text: String)` | Builds the body with `PadCore.padPutBody(key, text)` and delegates to `FlitCircleSync.postPadPut`. No body is assembled in the engine. |
| `FlitEngine.circlePadDel(key: String)` | Builds the body with `PadCore.padDelBody(key)` and delegates to `FlitCircleSync.postPadDel`. |
| `FlitCircleSync.postPadPut` / `postPadDel` | Publish the caller's body as a signed `CircleEvent.PAD` through the existing `post` → `emit` → `fanout` path (§7.6). No new route, header, `CircleRequest` / `CircleResponse` field or event verb is added; the body is exactly one of the two §23.3 shapes. |
| `FlitCircleSync.circleBodyValid(type, body)`, `PAD` branch | Delegates to `PadCore.validPadBody(body)`. Only the exact `PAD_PUT_KEYS` / `PAD_DEL_KEYS` shapes pass; a partial, extra-key, blank or over-cap body is refused before `CircleCore.create`, store upsert or fan-out. This is also the mint-side gate for the two engine methods. |
| Inbound host gate | An inbound pad event passes the existing §7.5 SYNC admission as a known verb with a bounded body; no pad-specific host rule is added in this layer. A body `validPadBody` refuses is ignored by `PadCore.view` when the view is rebuilt (§23.6). |

### 24.4 `PadUi.kt` and the `CircleUi` / `MainActivity` surface

`PadUi.kt` is Android-free. It defines `object PadUi` with `data class PadRow(key:
String, text: String)`, `fun rows(view: PadView): List<PadRow>` — the view's
`lines` are already lexicographic by `key`, so `rows` defensively re-sorts by key for hand-built views and drops
nothing from `lines` — and `fun keyError` / `fun textError`, which report the
blank and over-bound cases using `PadCore.MAX_KEY_CHARS` and
`PadCore.MAX_TEXT_CHARS`. The shipped API also adds `data class PadHitRow(key,
text, start, end, lineIndex)` — one hit with the original, unmodified line text
and an inclusive `start` / exclusive `end` UTF-16 range into it — and `data class
PadHitRows(rows, scannedLines, truncated, queryRejected)`, the result wrapper.
`fun search(view: PadView, query: String, limit: Int = PadSearch.MAX_HITS):
PadHitRows` projects the JVM-pure `PadSearch.search` result into those rows, and
`fun searchRows(view: PadView, query: String, limit: Int = PadSearch.MAX_HITS):
List<PadHitRow>` returns just the rows. Both only read the supplied `PadView`;
they never rewrite or write to it, and a rejected or capped query still yields
a bounded, non-throwing result. The file owns no Compose, socket, store or
preference reference, so those functions are unit-tested directly.

A Compose `PadCard(view: PadView, onPut: (String, String) -> Unit, onDel:
(String) -> Unit, onClose: () -> Unit, query: String? = null)` renders the rows
as an M3 list with edit and delete controls on 48 dp targets, plus a key/text
input for a put. The trailing `query` is optional: when it is null or blank the
full list shows as before; when present the card calls `PadUi.search` and lists
only the hit rows, marking each match with an annotated range built from the
`start` / `end` offsets over the original text, so the `PadView` is never
mutated. A rejected query is reported in the subtitle and a capped result is
marked as capped; every row keeps its 48 dp edit and delete targets. The card
calls `PadUi.rows` for the unfiltered list and `PadUi.keyError` /
`PadUi.textError` before enabling a put. All three callbacks are supplied from
outside: `onPut` and `onDel` reach `FlitEngine.circlePadPut` / `circlePadDel`,
and `onClose` dismisses the surface without writing anything.

`CircleUi` gains one additive pad surface entry beside the existing Circle
cards. It passes the collected `padView` and the put / delete / close callbacks
to `PadCard`; no message, file, poll or vote card changes its shape.
`MainActivity` collects `FlitEngine.padView` and supplies the callbacks that
call into the engine. The pad surface is a local projection only: `PadView`,
`PadLine`, `winners` and order keys stay in memory and never enter a signed
event or a DTO.

### 24.5 CLI pad commands

`Harness.kt` adds three `circle` subcommands and extends
`ParsedArgs.VALUE_FLAGS` with `--key` (`--text` already exists). Each command
uses the joined identity and posts through the existing `circleEvent` /
`pushCircleEvent` helper; no second post path is added.

```
circle pad put --key K --text T
circle pad del --key K
circle pad show
```

| Subcommand | Contract |
| --- | --- |
| `put` | Builds the body with `PadCore.padPutBody(K, T)` and appends one signed `pad` event to `circle.state`, then pushes it with the existing sync exchange. A bad key or text is refused by the builder before an event is minted. |
| `del` | Builds the body with `PadCore.padDelBody(K)` and posts one signed `pad` event the same way. |
| `show` | Runs `PadCore.view(st.events, st.circleId)` and prints the live `PadView.lines` in the view's lexicographic key order (`key` and `text`), omitting tombstoned keys. It prints no order keys, signatures, certificates or local status, and writes no state. |

`pad show` is derived from the same bounded log as the app view; a line evicted
by `EVENT_CAP = 500` is not backfilled (§23.8). No new route, v2.2 shape,
`CircleRequest` field or state row is added.

### 24.6 No-new-route and v2.2 boundary

1. **Existing wire home only.** A pad event rides in `CircleRequest.events` on
   `POST /api/flit/v1/circle/sync`, and in the `/circle/join` seed log, through
   `fanout` and the repair tick (§7.5, §7.6). No `PATH_*` constant, Ktor route,
   `LinkProvider`, byte-transfer route, HTTP header, DTO field or status code is
   added.
2. **Closed body, one builder.** Only `PadCore.padPutBody` / `padDelBody` mint
   pad bodies and only `PadCore.validPadBody` admits them, on both surfaces. No
   view field, order key, tombstone, path or local status enters a body (§23.3).
3. **v2.2 untouched.** `/info`, `/register`, `/prepare-upload`, `/upload` and
   `/cancel`, their DTO shapes and status behaviour stay as in §2. The earlier
   courier, relay, parcel, per-drop-key and receipt contracts (§5, §13–§22) keep
   their byte shapes; a `pad` event is opaque payload to them.
4. **No second state or crypto path.** No `CircleState` field, CLI state row or
   persisted `winners` map is introduced. `PadCore` is the only fold; signing,
   verification, replay and dedup stay with `CircleCore` / `TlsIdentity` (§4.1,
   §7.4, §23.8).

### 24.7 Evidence boundary

CODE establishes the engine view flow and publish seams, the `PadUi` functions,
the Compose card, the `CircleUi` / `MainActivity` entry and the three CLI
commands. PURE JVM tests pin `PadUi.rows` order and empty-view handling, the
`keyError` / `textError` bounds at and beyond `MAX_KEY_CHARS` /
`MAX_TEXT_CHARS`, engine publication of a view from a known event set, and the
CLI body builders feeding `validPadBody` with `show` printing only live
lexicographic lines. Harness coverage drives the put / del / show commands
against the in-process Circle nodes using the same bodies. No device claim is
made in this contract; any observation of the pad on hardware belongs in the
completion document for this date. See [circle-pad.md](roadmap/circle-pad.md).

### 24.8 Identity fallback labels and BLE duty audit

Where a roster alias is blank, the app shows a stable label derived from the
fingerprint by `PeerAlias` instead of a raw fingerprint prefix; an assigned
roster alias stays authoritative and is shown verbatim. `PeerAlias` derives the
same text from the same fingerprint on every JVM and changes neither the roster
nor any wire field.

`FlitBle.auditDutySchedule` checks the duty cycle against a ceiling and writes
any finding to the supplied log sink only. The arbiter and the radio controls
never read that result, so carrier behaviour is unchanged: no route, header,
advertising record or advertise/scan stance is altered by the audit.

## 25. JVM `:core` seam boundary — relocation of Android-free seams (2027-01-11)

### 25.1 Layer and ownership

This section records a module boundary, not a new wire feature. The layer moves
ten Android-free seams — production classes that were already free of platform
APIs — out of `:app` into a JVM-only `:core` module, together with their test
classes. It is a relocation: the declarations, case bodies and package names
are unchanged, and no case is rewritten, merged, retired or replaced. `:protocol`
and `:harness` are untouched. The Android shell in `:app` keeps the code that
needs Android: the service, activities, radio adapters, camera capture,
notification wiring and Compose surfaces. None of that platform code moved into
`:core`.

### 25.2 The ten seams

The relocated seams are `FlitBle` (BLE record codec, arbiter, rank rule, ask
book and duty constants), `Qr` (v2/v3 invite codes, gray decode and frame
helpers), `ParcelScanState` (the Android-free frame-assembly reducer),
`FlitSubnet` (host walks and prefix bounds), `FlitRoute` (route decode,
candidate order and hunt backoff), `FlitMirror` (diff offer and ledger rules),
`FlitSpool` (sweep, queue whitelist and crush-litter rules), `FlitSpeed` (the
rate estimator, with its complementary edge cases), and `SheetUi` (initials,
hue and count phrasing). `FlitCrush`, the gzip crush-and-send helper, moves as a
source file alongside these; it is pinned through the `FlitSpool` sweep cases
rather than a test class of its own. Every one of these was already JVM-pure,
which is why each could leave the Android module without a rewrite.

The test classes move with their subjects: `FlitBleTest`, `FlitQrTest`,
`ParcelScanStateTest`, `FlitSubnetTest`, `FlitRouteTest`, `FlitMirrorTest`,
`FlitSpoolTest`, `FlitSpeedTest`, `FlitSpeedEdgeTest` and `SheetUiTest`. Their
cases total 112. That figure is a projection from the file inventory: it is
counted from the `@Test` annotations in the moved files, not from a JUnit XML.
The historical inventory is 362 protocol + 324 app + 92 harness = 778. After
relocation the projected split is 362 protocol + 112 core + 212 app + 92
harness = 778. Only the module column changes; the total is unchanged because
this is a move rather than an addition or a removal.

### 25.3 Same-package declarations across modules

All ten production declarations keep `package app.flit.share`, and all ten test
classes keep that same package. `:protocol` continues to own
`app.flit.share.protocol` and its subpackages, including the `client`, `model`,
`circle`, `parcel`, `tls`, `transport` and `ideas` types the seams call into. The
result is one package name declared in two modules: `app.flit.share` is declared
in both `:app` and `:core`, while `app.flit.share.protocol.*` is declared only in
`:protocol`. Kotlin and the JVM resolve these from the classpath, so the
unchanged names mean the imports that refer to these classes need no edit. The
relocation renames no package and introduces no new one, and the protocol types
are not duplicated into `:core`.

### 25.4 Wire boundary

No value in §1 changes. No route string, header name, JSON DTO field, QR
parameter, persisted store field, schema field, signature primitive or status
value is added, removed or re-typed by this layer. The seams keep calling the
protocol types that remain in `:protocol`; they do not redefine them in
`:core`. `Qr` still produces the invite text handled in §8, `ParcelScanState`
still consumes the `ParcelCodec` frames of §16–§18, and `FlitBle` still reads
the same `NoiseBudget` rules. `FlitRoute` adds no route and `FlitSubnet` adds no
header. No field is added to `CircleRequest`, `CircleResponse` or any LocalSend
DTO, and `FlitMirror` and `FlitSpool` introduce no new carrier or wire shape. A
stock LocalSend v2.2 peer sees the same bytes it saw before the move (§2, §4).

### 25.5 Evidence boundary

No green claim is made here. The full rig covering all four modules had not
produced a result available to this layer when this section was written, so the
112 core and 212 app figures stay projections from the file inventory. A result
may be published only after the JUnit XML for the completed run is read: confirm
that the core and app case totals match 112 and 212, then record the failures,
errors and skipped values from those XML files. No claim of zero failures, zero
errors or zero skipped is made for any module, and earlier green totals are
historical and are not carried forward by this wave.

CODE covers the source-level facts: the JVM-only module in the build, the ten
production classes and ten test classes under `core/src/main` and `core/src/test`,
the retained `app.flit.share` package, and the absence of those classes from the
`:app` tree. PURE covers the JVM-only character of the moved cases: no Android
runtime, emulator, camera or radio enters them. There is no DEVICE label for
this layer; no phone, radio or camera walk is recorded.
