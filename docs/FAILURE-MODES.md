# Failure modes and the conclusion matrix

> Part of the Flit design set. [Ladder and index](transport-bluetooth.md) ·
> [Wire contracts](CONTRACTS.md) · [Security](SECURITY.md) · [Performance](PERFORMANCE.md) ·
> [Testing](TESTING.md) · [Operations](OPERATIONS.md) ·
> [Circle](circle.md) · [Open file / sharing restore](open-file-and-sharing-restore.md) ·
> milestones [A](milestone-a.md), [C](milestone-c.md), [D](milestone-d.md), [E](milestone-e.md).

| Tag | Meaning |
| --- | --- |
| **CODE** | Read from the source at the `file:line` cited. States what the program does, not what was observed running. |
| **PURE** | Deterministic JVM unit test in `protocol/src/test` or `app/src/test` (no Android, no socket, no device). |
| **SIM/HARN** | Harness test or `bt-sim` run against the production client/server over in-process pipes or loopback TLS. The dated roadmap and CLI tables write this tag as SIM-HARN. |
| **DEVICE** | Observed on the Redmi Note 10S (API 36) and/or the `flit-cli` box on the rig. The dated walks are cited; nothing else is claimed as walked. |

Current rig state: **136 protocol + 228 app + 32 harness = 396 tests, 0
failures** after a green `--rerun-tasks` run. The older 302-test figure was
green at `72225f8` and is historical. The non-profit constraint holds
throughout: no accounts, no cloud, no ads, no telemetry, no phone-home SDKs;
every recovery path below is local.

---

## 0. The one contract that decides every outcome

Almost every "retry or conclude" question reduces to a single rule at the
protocol/app boundary. Milestone A's first hardware walk caught a defect that
deleted a batch (see [milestone A](milestone-a.md#as-first-hardware-walk-caught-a-c-era-defect-2026-11-15)).

> **A returned `SendReport` means exactly one thing: the receiver spoke.**
> A thrown `TransferUnreachable` means no receiver answer exists.

- `TransferUnreachable : IOException` is declared at
  `protocol/.../client/ProtocolClient.kt:70`. It is thrown when the prepare
  handshake cannot reach a receiver (`:255-270`), when per-file attempts exhaust
  on a carrier-shaped failure (`:444-455`), and re-thrown unchanged from the
  per-file wrapper (`:357-358`). Cancellation (`CancellationException`) is
  deliberately *not* converted; it propagates (`:263-264`, `:355-356`,
  `:442-443`).
- `dispatchTo` is `suspend` and runs inline; a throw leaves the app-side owe
  untouched (`app/.../FlitEngine.kt:2582-2600`, `:2727-2748`). Fresh sends
  (`send`/`sendShare`, `:2499-2512`) launch it in `scope`; the queue flush
  (`flushQueueFor`, `:2163-2233`) calls it synchronously and drops tokens only
  after a report returns.
- The receive side mirrors it: a cancelled or failed upload ends the receiver
  session as **cancelled**, never saved
  (`protocol/.../server/ServerCore.kt:628-647`, `:1076-1084`).

### 0.1 Prepare outcomes — a small table that settles most cases

| Receiver answer at `/prepare-upload` | Sender decision | Bytes / row fate | Evidence |
| --- | --- | --- | --- |
| `200` + `PrepareUploadResponse` | continue to uploads | nothing concluded yet | CODE `ProtocolClient.kt:291-295` |
| `204` (no accepted file after path sanitising) | return empty `SendReport` | concluded; no bytes moved | CODE `:271-273` |
| `403` + `X-Flit-Refusal: declined` | return `SendReport(declined = true)` | **concluded batch** — row/spool released | CODE `:274-287`; PURE `ProtocolRefusalTest` (`a marked prepare decline carries the refusal header …`, `sendFiles turns a marked decline into a concluded report not a throw`) |
| bare `403`, `409`, `5xx` | throw `TransferException` (transient, retriable) | carrier did speak but story not concluded; app-side row survives a throw | CODE `:274-290` |
| connect refused / reset / EOF / re-prepare IO failure | throw `TransferUnreachable` | **kept** — the hunt re-enters the flush | CODE `:265-270`, `:444-455`; PURE `ResumeAndTurboTest`; SIM/HARN `RelayServerTest` |

The refusal marker is `X-Flit-Refusal: declined` with status `403`
(`protocol/.../Protocol.kt:74-75`; `ServerCore.kt:311-324`). A QR-unknown or
untrusted sender is declined at prepare by the app delegate
(`FlitEngine.kt:967-989`); a plain v2.2 peer's unmarked `403` is not a
conclusion. See [CONTRACTS.md](CONTRACTS.md) for the route-level
shape.

### 0.2 Exact constants that bound every retry

| Constant | Value | Where | Effect |
| --- | --- | --- | --- |
| `ACCEPT_TIMEOUT_MS` | 30 000 | `ProtocolClient.kt:678` | prepare may wait for a human |
| `UPLOAD_TIMEOUT_MS` | 120 000 | `ProtocolClient.kt:679` | per-exchange body budget |
| sender upload attempts | 3 (`while (attempt < 3)`) | `ProtocolClient.kt:402` | then `TransferUnreachable` |
| `ServerCore.MAX_ATTEMPTS` | 3 | `ServerCore.kt:1103` | receiver token/IP claim budget (`:448-459`) |
| hunt cadence | 2 s, 5 s, 15 s, 30 s, then 60 s | `FlitRoute.kt:49-50` | owed-fingerprint probes |
| Circle sync backoff | 5 s base ×2ⁿ, cap 120 s; real delays 10/20/40/80/120 s | `FlitCircleSync.kt:44-54` | per-peer fan-out retry |
| Circle delivery-worker tick | 30 000; per-sha backoff 20/40/60 s | `FlitEngine.kt:612-629`, `:603-605` | pending/failed drop retries |
| part aging | 24 h | `ServerCore.kt:1206-1223` | stale `.part` / `.part.token` sweep |
| Circle event cap / replay window / skew / seen | 500 / 6 h / 5 min / 4096 | `FlitCircleStore.kt:72`, `CircleCore.kt:23-29` | bounded log and replay gate |
| panic hops / replay / skew / seen | 3 / 6 h / 5 min / 1024 | `PanicCore.kt:18-27` | forward budget and dedup |
| `MAX_ENVELOPE_CHARS` | 12 288 (12 KiB) | `CourierEnvelope.kt` | Base64 envelope cap; the framed `parcel` body stays under `MAX_BODY_CHARS` |
| `MAX_PARCELS` | 256 | `CourierCarryCore.kt` | Bag cap; a Full refusal reserves no id |
| `MAX_PARCEL_TTL_MS` | 86 400 000 (24 h) | `CourierCarryCore.kt` | `expiresMs = createdMs + MAX_PARCEL_TTL_MS`; also the courier replay window's past side — a parcel stays openable for its whole carried lifetime |
| bag `MAX_SEEN` | 4096 | `CourierCarryCore.kt` | Bag id reservation shared by `evaluate` and `fresh`, separate from the Circle set |
| courier `open` window / skew | 24 h / 5 min | `CourierEnvelope.kt`, `CourierCarryCore.kt` | Past side is the parcel TTL (`MAX_PARCEL_TTL_MS`), future side is `CircleCore.CLOCK_SKEW_MS`; compared by subtraction, no second replay set |
| app parcel display / paste caps | `MAX_PARCEL_FRAMES` 64, `PARCEL_PASTE_MAX_CHARS` 16 000, `PARCEL_FRAME_INTERVAL_MS` 400L, `PARCEL_FRAME_SIZE_PX` 240 | `ParcelUi` (roadmap "Constants owned here") | Display cap equals codec `MAX_FRAMES`; cadence and render side are configured values, not measured timing |
| QR parcel frame caps consumed unchanged | `MAX_FRAMES` 64, `MAX_CHUNK_BYTES` 512, `MAX_AIR_GAP_PARCEL_BYTES` 131072 | `ParcelCodec.kt:50`, `:56`, `:53` | 64 frames and 128 KiB are defensive wire caps; the effective ceiling is the 12 KiB sealed-envelope Base64 cap (about 24 frames, roughly 5 KiB payload); the app defines no second grammar |

---

## 1. The master matrix (one-line form)

Read this first; the sections below unfold each row.

| Flow / bad event | Retries? | Concludes? | Keeps bytes? | Drops? | Evidence |
| --- | --- | --- | --- | --- | --- |
| Prepare declined (marked `403`) | no | yes — `declined = true` report | no — spool/row released | spool copies + queue tokens | CODE / PURE |
| Prepare transient (`409`, `5xx`, unmarked `403`) | yes, via next touch/hunt | no | yes — owe row + spool | nothing | CODE |
| Carrier death before prepare | yes | no | yes | nothing | CODE / PURE |
| Mid-body death, plain, attempts left | yes — up to 3 | no | receiver keeps prefix; sender keeps owe | nothing | CODE / PURE / DEVICE |
| Mid-body death, attempts exhausted | yes (outer hunt) | no — `TransferUnreachable` | both sides keep | nothing | CODE / PURE |
| Full-length body, bad digest | no (sender re-sends clean) | yes — `422` mismatch | **drops** the part/sidecar/entry | corrupt bytes | CODE / PURE |
| Resume base no longer held | no | yes for that attach (`409`) | base part untouched | nothing | CODE / PURE |
| Wi‑Fi ↔ Bluetooth switch | clean full resend | per-file report | origin mismatch refuses old part | old prefix ages out | CODE / DEVICE |
| Queue row, no item openable | yes (manual/cancel) | no | row kept, tokens unchanged | transient spool copies only | CODE |
| Queue row, merged batch partially sent | yes | per-token | surgical `without()` leaves the rest | only concluded tokens/grants | CODE / PURE / DEVICE |
| Relay death mid-transfer | yes — both ends re-subscribe | no — `TransferUnreachable` | receiver prefix + sender owe | nothing | CODE / SIM / DEVICE |
| Relay unpaired / wrong token | yes (calm backoff) | refusal only | no bytes moved | ghost ticket withdrawn | CODE / PURE / SIM |
| Circle drop: thrown carrier / no route | yes (~30 s worker) | no — `PENDING` | spool kept | nothing | CODE / SIM |
| Circle drop: returned decline | no | `REFUSED` (terminal) | spool kept until all recipients terminal | nothing yet | CODE / SIM |
| Circle drop: returned `failed` | no in that pass | `FAILED` (terminal per recipient) | spool kept until all recipients terminal | nothing yet | CODE / SIM |
| Circle drop: returned `sent` | no | `SENT` (terminal) | bytes landed hash-exact | spool after all terminal | CODE / SIM / DEVICE |
| Circle drop: spool file gone | no | record dropped | nothing | delivery record + card status | CODE |
| Circle log: replay / wrong circle / forged cert | no | event skipped | log unchanged | event | CODE / PURE / SIM |
| Circle leave/kick | no | membership removed, generation bumped, key rotated | local earlier log stays plaintext | roster entry + old key material | CODE / PURE / SIM / DEVICE |
| Drop key: no usable recipient cert, or no wrap for the reader | no | no wrap entry / `unwrap` null; no plaintext | source or caller's sealed bytes stay | that entry or the plaintext | CODE / PURE |
| Drop key: roster above `MAX_WRAPS` (32), or wrap over `MAX_WRAPPED_CHARS` (512) | no | `wrap` refuses the whole map; body gate false | source bytes stay with the caller | over-cap map / over-cap body | CODE / PURE |
| Drop key: partial, extra or mistyped keyed body | no | `validFileBody` false; `parseFileKeyBody` null | caller keeps the bytes | the malformed body | CODE / PURE |
| Drop key: tampered `wraps` / `iv` / `cipherSha`, or flipped IV / ciphertext / tag | no | `verify` fails before unwrap, or `open` null | event log / caller's sealed bytes stay | modified event or plaintext | CODE / PURE |
| Courier: no recipient cert in log or roster | yes — a later roster answer | no — `seal` refuses | events stay in the sender's own log | nothing | CODE / PURE |
| Courier: bad body keys, oversized envelope, malformed bytes | no | `Invalid` / `decode` null | existing bag unchanged | incoming event or bad row | CODE / PURE |
| Courier: verify fails or cert binding breaks | no | verify false; no pin written | held bytes until `ack` / `expire` | envelope on removal | CODE / PURE |
| Courier: duplicate id or bag at 256 | a Full refusal can be offered again after space frees | `Duplicate` / `Full`; Full reserves nothing | held copy, or all 256 held parcels | incoming copy / offer | CODE / PURE |
| Courier: outside window, expired, wrong `circleId` / `toFp` | no | `OutsideWindow` / `Expired` / `open` refused | existing bag unchanged | incoming or expired parcel | CODE / PURE |
| Courier: pending until encounter, then ack | yes until the addressee is met | Peer accepted the offer at `ack`; no remote proof of opening | parcel until TTL; nothing after ack | acknowledged parcels | CODE / PURE |
| Courier: corrupt bag row or carrier death | yes — persisted bag is re-offered | empty bounded state / no conclusion | unrelated Circle state; persisted `CarryState` | corrupt row / in-memory-only state | CODE / PURE |
| Courier CLI: `--bag` missing or unreadable | no | exit `2`, no state change | nothing moved | nothing | CODE |
| Courier CLI: cert fingerprint differs from `--to`, or bag at quota | no | drop refused, row not added | existing parcels kept | nothing | CODE / PURE |
| Courier CLI: past TTL at `sweep` | no | row `expired` | opened records in `courier-received/` | envelope and row on next sweep | PURE / SIM-HARN |
| Courier CLI: parcel for another fingerprint, or `open` throws | yes — re-export or retry | stays `pending` | envelope kept in `courier-bag/` | nothing | CODE / SIM-HARN |
| Courier CLI: `open` refusal or duplicate id on `receive` | no | row `refused` or `delivered`, no second fold | nothing in Circle state / first folded event | envelope to `courier-rejected/` / duplicate event | SIM-HARN |
| Courier CLI: corrupt `courier.state` or vanished envelope file | no | empty decode with count, or silent skip | bag files / remaining envelopes | stale local rows / nothing | CODE |
| Courier app: no recipient cert for the drop | yes — a later roster answer | no — no `parcel` posted | source events stay in the sender log | nothing | CODE / PURE |
| Courier app: bad `parcel` body or failed signature in `fresh` | no | refused before the bag fold | bag unchanged | incoming event | CODE / PURE |
| Courier app: addressee not met on a sync pass | yes — re-offered via `select` | no — held | bag and signed event stay | nothing | CODE / PURE |
| Courier app: peer accepted the offer | no | `ack`; outcome `handed` = offer accepted, no remote proof | recipient's copy is local and unobserved here | acknowledged bag rows | CODE / PURE |
| Courier app: handoff refused or carrier throws | yes — re-offered on a later pass | outcome `failed`; no `ack` | bag held for retry | nothing | CODE / PURE |
| Courier app: `open` fails at the addressee | held until the core sweep | no fold; no handoff entry or carry line | bag row stays | parsed parcel only | CODE / PURE |
| Courier app: corrupt or absent `courierBag` row | no | empty bag; log line | unrelated Circle state stays | corrupt row | CODE / PURE |
| Courier app: `parcel` row reaches the feed | no | filtered before body parse and tally | signed event stays in the log | feed row only | CODE |
| Courier app: blank SHA/blank outcome, or `handed` read off-device | no | no carry line / generic member line | bytes untouched | nothing | CODE / PURE |
| Courier app: no landed SHA and no matching opening hash behind a carry line | no | `ABSENT`; the carry line never claims delivery | landed copy stays | nothing | CODE / PURE |
| Panic alert | per-device, one attempt per fan-out | `reached/N` confirmed only | banner in memory (max 20) | nothing remote | CODE / PURE / DEVICE |
| Panic forward at hop limit | no | toast, no send | alert stays dismissed locally | forwarded copy | CODE / PURE |
| FGS refused by ROM | no FGS | engine-only while foreground | engine state intact | notification only | CODE |
| `dataSync` 6 h cap (`onTimeout`) | next cold open | notification removed | engine alive while UI open | notification | CODE |
| Process killed (MIUI/memory) | next cold `onCreate`/`onResume` | not concluded | owe row, spools, desired flag | in-memory `running` only | CODE / PURE / DEVICE |

---

## 2. Send / receive failures

### 2.1 Mid-body death, plain upload — what keeps the prefix

**Sender side (CODE).** `uploadAttempts` probes `/partial` on the first attempt
and on every attempt with a confirmed carry (`carried > 0 || attempt == 1`,
`ProtocolClient.kt:411-420`). On a body exception before attempt 3 it polls the
probe up to four times (`:456-463`); any `base` in `1 until total` becomes the
new `carried` and the retry sends only the tail (`:464-469`). Only after attempt
3 does it throw `TransferUnreachable` (`:448-455`). A `422` (full body, bad
digest) sets `carried = total` and retries with the same token (`:477-482`).
A refused claim (session reaped) re-prepares a single-file session and keeps
the on-disk part resumable (`:483-490`).

**Receiver side (CODE).** `ServerCore.uploadRoute` writes the token sidecar
**before the first body byte** (`markPartDiscoverable`, `ServerCore.kt:547-573`,
`:680-693`) so a `SIGKILL` cannot lose discoverability. The keep rules are:

- short body (`bytes < size`) → `keepPart()` puts the token → part → `.part.token`
  sidecar (`:607-613`, `:668-678`);
- cancellation / throw after some bytes → `keepPartAfterAbort()` keeps only when
  the temp is non-empty, otherwise deletes temp + sidecar (`:628-647`,
  `:695-708`);
- full length but digest fail → **drop** temp, sidecar and registry entry, so a
  corrupt base can never be advertised (`:614-624`);
- successful move consumes the part and deletes the sidecar (`:598-606`).

A restarted server repopulates its in-memory `parts` registry from `.part.token`
sidecars with an **empty `senderIp`** (`repopulateParts`, `:1179-1191`). The
probe (`partialRoute`, `:1019-1034`) and the resume attach both call
`resumableFor`/`partsBase`; an empty `senderIp` is accepted from anyone because
the token binds content identity and the final SHA-256 gates the landing
(`:1045-1059`). A **mismatched non-empty** `senderIp` is refused — that is what
forces a clean resend across carriers (`bluetooth-carrier.md#the-one-suspension-of-disbelief`).

**The 409 rule (CODE + PURE).** If an `X-Flit-Resume` header names a base the
receiver no longer holds, the attach answers `409 Resume base no longer held`
and writes nothing (`ServerCore.kt:491-498`, `:511-515`). The 2026-11-18 walk
caught the old behaviour — a claimed part was deleted before the plain attach
could use it, leaving a 711 MB tail written as a fresh file — see
[transport ladder](transport-bluetooth.md). Pinned by
`ResumeAndTurboTest` (`a resume header with no held base is refused with 409 and
writes nothing`).

**Evidence.** PURE: `ResumeAndTurboTest` (5 tests: both RFC `bytes s-e/total` and
`bytes=s-e/total` syntaxes; restart probe/attach; failed-chunk-then-resume;
409-for-missing-base; a real `multi=true` hash-exact landing) and
`PartialSidecarTest` (`the part token sidecar exists before the first body byte`).
DEVICE: 2026-11-14 400 MB force-stop walk — 127,959,040-byte part + sidecar
survived both deaths, restart re-registered it with empty `senderIp`, resume
landed hash-exact and consumed part/sidecar/row
([milestone C](milestone-c.md#milestone-c-on-hardware-2026-11-14)).
DEVICE: 2026-11-24 300 MiB camera shot killed at 33,980,416 B → `recv-resume … from
33980416 bytes` → hash-exact, no leftover part
([transport ladder](transport-bluetooth.md)).

### 2.2 Mid-body death, turbo (multi-socket)

Each range writes at its known offset through `RandomAccessFile`
(`ServerCore.kt:720-779`). `ChunkTracker.add(start, sentInChunk)` counts only
bytes that actually landed, never the declared range length (`:770-774`) — a
short body must not advance the salvage base past real data. A cancellation or
throw mid-chunk marks the partial range covered and calls `keepChunkPart`
(`:780-793`). The salvage base the probe advertises is the **contiguous base
from zero**, never the temp's on-disk length, because the tail may hold
unorderable pieces (`ServerCore.kt:1015-1017`, `:1045-1047`).

On the sender, if the turbo lane is cut short it re-probes `/partial` and falls
back to the plain single-stream resume of the receiver's salvage base
(`ProtocolClient.kt:319-332`). A failed chunk claim must not delete the kept
part — PURE: `ResumeAndTurboTest` (`a failed chunk claim leaves the kept part
intact for a plain resume`). DEVICE: a late kill ~90 % through an 800 MiB
multi-socket send landed hash-exact but took the **clean full-resend** path — a
sparse prefix near completion is not advertised as a resume base
([transport ladder](transport-bluetooth.md)).

### 2.3 Queue rows and spools — the checkpoint that survives process death

**Invariant:** the queue row is written **before** bytes move; it leaves only
after the far side speaks. `QueuedSend` lives in the app's own SharedPreferences
(`FlitRoster.kt:272-306`); a fresh dispatch owes via `queueTokensFor` +
`enqueue` inside `dispatchTo` (`FlitEngine.kt:2638-2660`).

- **Fresh dispatch death:** `dispatchTo`'s catch keeps the owe row and logs
  `<reason> — kept, sends when <peer> reappears` (`:2727-2748`).
- **Flush death:** `flushQueueFor` dispatches synchronously; `dropSent` runs only
  after a report; a throw leaves the row and tokens exactly as they were and
  logs the same "kept" line (`:2185-2222`). `cleanSpools` runs in `finally`
  (`:2221-2226`) because a row keeps **tokens**, never spool paths; the next
  flush re-spools from source.
- **Unopenable items:** if no token can be opened (full disk, revoked grant,
  mid-write camera file) the row is kept with a log entry, not a "send failed"
  message (`:2174-2183`). If some open, `dropSent` removes **only** the opened
  tokens/names; a batch merged in mid-flight stays owed
  (`QueuedSend.without()`, `FlitRoster.kt:49-62`; `dropSent`, `FlitEngine.kt:2792-2806`).
- **Merging:** a second intake joins the waiting batch — tokens union with
  dedup, names append in merge order, oldest timestamp kept
  (`QueuedSend.mergedWith()`, `FlitRoster.kt:33-47`).
- **Grants:** persistable SAF URIs are taken at queue time and released only
  when their tokens leave the row (`releaseGrants`, `FlitEngine.kt:2758-2783`).
  Mixed spool+URI sources cannot be checkpointed and stay best-effort
  (`queueTokensFor`, `:2816-2828`).
- **Start sweep:** killed sessions' spool copies are reclaimed unless a queue
  row remembers them (`FlitSpool.kt:9-48`, 24 h staging window at `:29`).
- **Cancel:** `cancelQueued` ends the row through `dropQueue` → dequeue + grant
  release (`FlitEngine.kt:2779-2783`, `:2832`).

**Evidence.** PURE: `FlitRosterTest` (`a second intake joins the waiting batch`,
`a sent batch leaves its tokens — a batch merged in mid-flight stays owed`,
`dropping the last tokens ends the row`, `display twins leave by first
occurrence`, `the mirror duty rides a merge`) and `FlitSpoolTest`
(`killed sessions' copies die …`, `the send-later queue's whitelist wins
unconditionally`). DEVICE: 2026-10-30 queue walk — serve killed → "kept,
sends when it reappears" → second file merged into one row → serve restarted →
`sent 2/2`, both landed; cancel walked too
([bluetooth carrier](bluetooth-carrier.md)).
The grant-release leg is **code-verified only**: that walk used cache-path
tokens, so no SAF grant was in play (see [README](../README.md#current-capabilities-verified-on-device)).

### 2.4 Hunt, carrier migration and the relay-last rule

The hunter is a 2 s worker over owed fingerprints (`FlitEngine.kt:1612-1641`).
It skips a fingerprint already in `dispatching`, so an announce mid-send cannot
double-fly a batch (`:1637`; the claim itself is in `dispatchTo`, `:2610-2618`).
Candidates are the live row, the remembered route, then typed addresses
(`FlitRoute.candidates`, `FlitRoute.kt:40-41`), deduped. An answer is accepted
**only** when its register fingerprint equals the owed one
(`FlitRoute.accepts`, `:73-74`; engine gate at `FlitEngine.kt:1671-1674`) — a
reassigned address can never inherit a batch. The first accepted answer goes
through the ordinary `touchPeer` door; the flush's first-attempt probe then
resumes from the receiver's disk (`:1682-1687`). Relay is tried **only after**
every direct candidate fails (`:1689-1699`).

**Recovery recipe — carrier dies mid-transfer:** keep the owe (automatic);
wait for the hunter's next beat or any announce/keeper/sweep touch; the hunter
probes candidates in priority order; on a fingerprint match the row auto-flushes;
`/partial` re-learns the prefix; only the tail is sent; SHA-256 gates the landing.
A Wi‑Fi↔Bluetooth switch has no matching origin, so `partsBase` refuses the old
prefix and the file takes a clean full resend — hash-exact either way.

**Evidence.** CODE as cited; PURE: `FlitRouteTest` (`candidates come in priority
order`, `a route listed twice is probed once`, `the hunt stays calm`,
`the fingerprint is the whole accept rule`). DEVICE: 2026-11-15 hunt walk caught
the C-era defect and its redo showed hunt → remembered route → flush → resume
([milestone A](milestone-a.md#as-first-hardware-walk-caught-a-c-era-defect-2026-11-15));
2026-11-24 relay-kill walk below.

### 2.5 Relay death and blind-pairer refusal

The relay is a blind pairer: `RelayCore` matches `req <fp> [token]` with
`sub <fp> [token]`, gates with a constant-time token compare, never pairs a
request with a request, and retires a dead ticket with `withdraw`
(`protocol/.../relay/RelayCore.kt:15-20`, `:81-91`, `:109-145`). The receiver
holds a long-lived subscription, hands each paired pipe to the same
`ProtocolServer.serve` door, and re-subscribes with the keeper's calm backoff
(`FlitEngine.kt:1709-1721`). Within the pipe the protocol is unchanged — TLS,
register, prepare, upload, `/partial` all run end to end
([milestone E](milestone-e.md)).

Failure outcomes:

- **Relay killed mid-transfer** → the dial throws `TransferUnreachable`; the
  sender keeps its row; both ends re-subscribe; the next hunt re-pairs and
  resumes from the receiver's prefix. DEVICE: 2026-11-24 — 300 MiB shot cut by
  `kill -9` on the relay, `recv-resume … from 31866880 bytes`, exact killed
  prefix, hash-exact landing, no leftover part
  ([transport ladder](transport-bluetooth.md)).
- **Unpaired subscription** times out cleanly; **wrong token** is refused on
  the wire; **dead relay dial** surfaces `TransferUnreachable`, never a report.
  SIM/HARN: `RelayServerTest` (`a pair that never comes times out cleanly …`,
  `a wrong token is refused on the wire …`, `a relay dial through ProtocolClient
  reports unreachable, not a send report`, plus a real 3 MB TLS send and a
  carrier-death/resume through the pairer).
- **No store-and-forward** is a deliberate limit: an offline receiver's bytes
  wait on the sender's queue, and a certificate cannot be forged by a malicious
  relay (it only pairs and copies bytes).

---

## 3. Circle failures

### 3.1 File delivery statuses — the local-only conclusion table

A file drop is two separate things: a signed `{name, size, sha256}` announcement
in the event log and a real per-member `sendFiles` delivery from the sender's
private spool — **not** the send-later queue
([Circle](circle.md#file-delivery-as-built)). Status is a local record on the
sender's device; it is never gossiped.

| Outcome of `sendFiles` call | Stored status | Retry? | Spool |
| --- | --- | --- | --- |
| no route (`circleDial` returns null) / thrown carrier | `PENDING` | yes (~30 s worker, per-sha backoff) | kept |
| returned report with `declined` | `REFUSED` | no — terminal | kept until all current recipients terminal |
| returned report whose `failed` contains the name | `FAILED` | no — terminal in that pass | kept until all current recipients terminal |
| returned report whose `sent` contains the name | `SENT` | no — terminal | landing hash-gated |
| spool file no longer exists | record dropped | no | nothing |
| all current recipients terminal | — | no | spool deleted; backoff entry removed |

Source: `FlitEngine.kt:507-511` (terminal set = `SENT`, `REFUSED`, `FAILED`),
`:575-596` (status mapping), `:599-606` (spool delete + backoff), `:612-629`
(worker). The loop sends **only to current members** — a kicked or departed
fingerprint is skipped (`FlitCircleStore` removes it from `members`), and the
worker retries pending/failed rows only after their backoff expires.

**Precision note.** The doc text says "a calm ~30 s worker retries rows with a
pending or failed recipient" ([Circle](circle.md)).
In code the worker tick is 30 s, while the *per-delivery* backoff is
`minOf(60_000, 10_000 shl attempt)` → 20/40/60 s (`FlitEngine.kt:603-605`), and
`deliverCirclePending` skips already-terminal recipients. An all-terminal row
therefore gets one cleanup pass that deletes the spool, not an endless resend.

**Later joiners / no backfill.** Delivery targets are captured at post time. A
device that joins later folds the signed announcement and sees the card but
receives no old bytes; its card shows the neutral "file drop · open to view if it
arrived" line. Status is sender-local, so a recipient's card cannot claim bytes
are missing.

**Open file states** ([open-file doc](open-file-and-sharing-restore.md#circle-card--three-states)):
`Opening…` (disabled), `Landed here` + `Open file` (only when a local SHA-256
match exists), or no button (`Not here yet · no local copy to open`). A failed
open ends in a toast, not a crash. A `FileProvider` root for `Download/Flit/` and
a lifecycle-bound chooser launcher close the original crash path.

**Evidence.** PURE: `CircleHardeningTest` (`file event references only name size
and sha256 — never bytes token or local path`, `file body gate accepts only name
size sha256 and refuses any token or path field`). SIM/HARN:
`CircleHarnessTest` (`circle file lands hash-exact bytes and walks pending to
sent delivery status`, `circle delivery outcome maps a returned report but never
a throw` — the latter uses a deliberately dead port and checks the recipient
stays `PENDING`). DEVICE: 2026-11-25 — a fresh **35 B** drop (`open-check.txt`)
landed hash-exact on the phone (`c421969b…`), card showed "Landed here", tap
opened `ChooserActivity` with the process alive, back returned to the feed,
`am force-stop` + relaunch restored sharing from the persisted flag
([open-file doc](open-file-and-sharing-restore.md#device-evidence-2026-11-25-final-build)).
**Two distinct device drops, both verified:** the earlier `2034fa6` walk landed
an **8 MiB** Circle drop on the phone with matching sha256 and `sent=2`
(commit record); the final Open-file walk used a fresh **35 B** drop
(`open-check.txt`, `c421969b…`) to exercise the chooser path. The harness uses
8192 B (`CircleHarnessTest.kt:405`). `8 MiB` is also the value of
`TURBO_CHUNK_BYTES`, but the device claim comes from the `2034fa6` walk, not
from that constant.

### 3.2 Event log, replay, governance — what is dropped and what is kept

- **Order and dedup:** Lamport per author, deterministic total order
  `(lamport, authorFp, id)`; bounded seen-id set (4096) plus a 6 h replay window
  and 5 min clock skew (`CircleCore.kt:23-29`). The production order is
  `validShape → verify → evaluate → store`, so a forgery cannot reserve a
  legitimate id (`:327-359`).
- **Host gate:** wrong circle, unknown type, oversized body, wrong cert binding,
  member trying to inject JOIN, or a non-owner/owner REKEY are skipped
  (`FlitCircleHost.kt:146-185`); every event is signature-verified and the
  requester's cert must bind to `memberFp`. A departed fingerprint is refused at
  the roster gate (`:147-151`) and a later SYNC cannot fetch new events.
- **Full-log repair:** every SYNC/post/tick carries the bounded log; a lost
  one-shot is repaired by the next post or tick. Posts use per-peer watermarks
  and deltas; the calm tick deliberately ignores watermarks and re-offers the
  bounded full log (`FlitCircleSync.kt:103-126`, `:455-507`). A refused delta
  does not advance the watermark, so it is offered again.
- **Leave / kick / rekey:** `depart()` removes the roster entry and saturates the
  generation (`FlitCircleStore.kt:479-488`); the host mints one fresh random
  key and signs a `rekey` event (`:213-242`). A member-supplied rekey is
  rejected; `applyRekey` only accepts a known signer and a non-decreasing gen
  (`:386-398`). Earlier events already stored on a departed device **stay
  plaintext** — there is no content cipher to erase
  ([Circle](circle.md#threat-model-and-safety-rails)).
- **Vote-kick:** one latest vote per current member; a changed vote can block a
  kick before it lands; strict majority of current members decides
  (`:490-524`). Ties are displayed as ties.

**Evidence.** PURE: `CircleCoreTest`, `CircleHardeningTest`,
`FlitCircleHostTest` (`wrong circle … refusals`, `join requires the
invite`, `sync refuses outsiders`, `a signed certificate cannot claim another
member fingerprint`, `wrong circle unknown type oversized and replayed events
are skipped`, `leave removes the member bumps the generation and host rekeys`,
`admin kick rekeys but the owner cannot be kicked`, `member supplied rekey is
rejected`, `departed fingerprint cannot later sync`), `FlitCircleSyncTest`
(`a refused delta does not advance the watermark`, `calm sync re-offers the full
log after a delta ack`, `sync skips backed-off peers and repairs them on a later
tick`, `fan-out caps concurrent peer dials at four`). SIM/HARN:
`CircleHarnessTest` (`two nodes prove owner role appointment, admin kick, poll
re-vote, file drop and rekey`). DEVICE: join, inbound render, full leave
discarding the key
([Circle](circle.md#build-and-verification-record-2026-11-24)); tap-to-join with
the box's poll and two bot messages (`:302-308`).

### 3.3 Known Circle gaps

- **Outbound phone→host fan-out is test-evidenced only.** The 2026-11-24 device
  walk could not produce a clean peer-side confirmation because the demo bots'
  CLI state and their `serve` process shared identities. The behavioural
  fan-out tests pass with an injected carrier; the device re-walk is the final
  verification item. See
  [Circle](circle.md#known-limitations) and the 2026-11-25
  close (`:357-361`).
- Multi-member mesh, one Circle at a time, no content encryption, bearer invite,
  blind relay requiring a manual Known-device entry with `relayFor` — all listed
  in the same section.

### 3.4 Courier parcels — the store-and-forward conclusion table

A carrier holds opaque `parcel` events in a bounded `CarryState`. The bag and its
reducers are JVM-pure (`CourierCarryCore.kt`); the signed, recipient-encrypted
blob is `CourierEnvelope.kt`. The caller persists the encoded bag — `courierBag`
in the app; the CLI keeps `<id>.parcel` files in `courier-bag/` with local-only
rows in `courier.state` — so process death leaves the signed events on disk and
only the in-memory `seen` set is lost. Every refusal below is logged
by the caller; none is a silent fallback.

| Condition | Retries? | Concludes? | Keeps bytes? | Drops? | Evidence |
| --- | --- | --- | --- | --- | --- |
| Recipient certificate absent from log and roster | yes — a later roster answer | no — `seal` refuses and logs | events stay in the sender's own log | nothing | CODE `CourierEnvelope.seal`; PURE `CourierEnvelopeCryptoTest` |
| Extra parcel body key, blank `toFp`, or `envelope` over `MAX_ENVELOPE_CHARS` | no | `Invalid`; no id reserved | existing bag unchanged | incoming event | CODE `CourierCarryCore.validParcelBody`; PURE `CourierCarryCodecTest` |
| `decode` sees truncated, oversized or unknown-field envelope bytes | no | `decode` returns null | existing bag unchanged | malformed body | CODE `CourierEnvelope.decode`; PURE `CourierEnvelopeCodecTest` |
| Tampered `cipherB64`, `ivB64`, `keyWrapB64`, `alg`, `circleId`, `toFp` or `createdMs` | no | `verify` false | held bytes until explicit `ack` / `expire` | envelope on removal | CODE `CourierEnvelope.verify`; PURE `CourierEnvelopeCryptoTest` |
| `cert` digest is not `fromFp`, or cert differs from the `parcel` signer | no | `verify` false; no pin written | existing pin cache unchanged | envelope | CODE `CourierEnvelope.verify`; PURE `CourierEnvelopeCryptoTest` |
| `createdMs` older than the 24 h parcel TTL (`MAX_PARCEL_TTL_MS`) or more than 5 min ahead | no | `OutsideWindow` / `open` refused | existing bag unchanged | incoming event | CODE `CourierCarryCore.evaluate`, `CourierEnvelope.open`; PURE `CourierCarryDecisionTest`, `CourierEnvelopeCryptoTest` |
| `nowMs >= expiresMs` | no | `Expired`; `expire` removes and logs | existing bag unchanged | incoming or held parcel | CODE `CourierCarryCore.expire`; PURE `CourierCarryDecisionTest` |
| Envelope id already reserved by `evaluate` | no | `Duplicate` | first copy | incoming copy | CODE `CourierCarryCore.evaluate`; PURE `CourierCarryDecisionTest` |
| Bag at `MAX_PARCELS` | yes — Full reserves nothing, so a later offer can succeed | `Full` | all 256 held parcels | incoming offer | CODE `CourierCarryCore.evaluate`; PURE `CourierCarryDecisionTest` |
| Corrupt wrapped key or undecodable plaintext after decrypt | no — any re-offer is a carrier concern | `open` returns null and logs | envelope bytes remain for retry or explicit removal | parsed array only | CODE `CourierEnvelope.open`; PURE `CourierEnvelopeCryptoTest` |
| Event signature invalid after decrypt | no | event skipped by `CircleCore.verify` at fold time | envelope and the rest of the array | that event | CODE `CircleCore.verify`; PURE `CourierIntegrationTest` |
| Parcel pending until the addressee is met | yes — next encounter / handoff | no — Pending | parcel in bag until TTL | nothing | CODE `CourierCarryCore.select`, `CourierCarryCore.expire`; PURE `CourierCarryDecisionTest` |
| Addressee accepted the offer | no | Peer accepted the offer at `ack`; no remote proof of opening | nothing for those parcels | acknowledged parcels | CODE `CourierCarryCore.ack`; PURE `CourierCarryDecisionTest` |
| Carrier restarts before an ack | yes — persisted bag is re-offered | no — addressee dedups by id | persisted `CarryState` | in-memory-only state | CODE `CourierCarryCore.encode`/`decode`; PURE `CourierCarryCodecTest` |
| Corrupt `courier-bag/` or `courierBag` row | no | empty bounded state at decode | unrelated Circle state | corrupt row | CODE `CourierCarryCore.decode`; PURE `CourierCarryCodecTest` |

The rows above are CODE and PURE only.

### 3.5 Courier CLI carrier — the one-shot conclusion table

The 2026-11-30 CLI layer adds a `courier` command with five one-shot
subcommands: `drop`, `carry`, `receive`, `status` and `sweep`. Each invocation
loads `courier.state`, does one operation and saves it. `courier-bag/` is a
portable directory copied between machines by the operator; the CLI adds no
transport, route or header. Envelope bytes and parcel encoding are the
2026-11-27 layers, unchanged.

The conditions below come from the CLI carrier roadmap. Evidence labels are
CODE from `CourierCli.kt`, PURE from `CourierCliTest` and SIM-HARN from
`CourierCarrierHarnessTest`. No row claims DEVICE: the bag moved between
directories on one box, and the three-node courier walk is 2026-12-02. Here
`delivered` is a local `courier.state` row state reached after `open` and a
fold; it is not the app handoff outcome and asserts no remote end-to-end
landing.

| Condition | Conclusion | Keeps bytes | Drops | Evidence |
| --- | --- | --- | --- | --- |
| `--bag` path missing or unreadable | exit `2`, no state change | nothing moved | nothing | CODE `CourierCli.run` |
| Recipient cert fingerprint differs from `--to` | drop refused before seal | nothing written | nothing | CODE / PURE `CourierCliTest` |
| Bag at the courier-carry.md quota | drop refused, row not added | existing parcels kept | nothing | CODE `CourierCli.hasRoom`; PURE `CourierCarryDecisionTest` |
| Parcel past TTL at `sweep` | row `expired` | opened records in `courier-received/` | envelope and row on the next sweep | PURE `CourierCliTest`; SIM-HARN `CourierCarrierHarnessTest` |
| Parcel addressed to another fingerprint | stays `pending`, re-exported | envelope kept in `courier-bag/` | nothing | CODE / SIM-HARN `CourierCarrierHarnessTest` |
| `open` returns a refusal (forged or tampered envelope) | row `refused` | nothing in Circle state | envelope moved to `courier-rejected/`, payload dropped | SIM-HARN `CourierCarrierHarnessTest` |
| `open` throws (carrier / IO fault) | stays `pending`, retried | envelope kept | nothing | CODE / SIM-HARN `CourierCarrierHarnessTest` |
| Duplicate envelope id on `receive` | row `delivered`, no second fold | first folded event | duplicate event | SIM-HARN `CourierCarrierHarnessTest` |
| `courier.state` corrupt or short | decode as empty, report the count | bag files untouched | stale local rows only | CODE `CourierCli.decodeRows` |
| Envelope file vanished between `status` and `receive` | silently skipped, row stays `pending` | remaining envelopes | nothing | CODE `CourierCli.run` |

### 3.6 Courier app layer — sync worker and file-card states

The 2026-12-01 app layer drives the unchanged envelope and carry primitives
from `FlitEngine` and `FlitCircleSync`, and projects the local outcome onto the
existing file card. It adds no route, header or DTO field. `circleCourierDrop`
seals events for an absent member and posts one `PARCEL` event;
`FlitCircleSync.fresh()` gates the body and signature, then `courierReceive`
persists the accepted events and `foldParcel` calls `CourierCarryCore.evaluate`
for the window/TTL/quota/duplicate decisions; the folded state is persisted on
the path `FlitCircleSync.fresh() → persistEvents() → FlitCircleStore.upsert` —
there is no `merge` step. `fanout`/`sync` re-offer `select`ed events and `ack`
only on an OK answer. The encoded bag
lives in the additive `FlitCircleStore.CircleState.courierBag` field (blank on
older rows), written only under the `FlitCircleStore.update` lock.
`FlitEngine.courierHandoffs` is a process-local map keyed by file SHA-256 with
`waiting`, `handed` and `failed`; it is never signed, persisted or put in a
`CircleRequest`. `CourierCardUi.kt` is Android-free and renders one carry line
per SHA from that map; it never parses a `parcel` body or holds envelope text.
An OK answer to `fanout`/`sync` proves only that the peer accepted the offer:
`handed` is that local outcome, never a claim that the addressee opened the
parcel. No remote proof of an end-to-end landing is available at this layer.

| Condition | Retries? | Concludes? | Keeps bytes? | Drops? | Evidence |
| --- | --- | --- | --- | --- | --- |
| Recipient cert absent from the signed log and the host roster | yes — a later roster answer | no — drop refused, no `parcel` posted | source events stay in the sender's own log | nothing | CODE `FlitEngine.circleCourierDrop` |
| `parcel` body with extra keys, blank/oversized `toFp`, or `envelope` over `MAX_ENVELOPE_CHARS` | no | rejected by `circleBodyValid` before `evaluate` | bag unchanged | incoming event | CODE `FlitCircleSync.circleBodyValid/fresh`; PURE `FlitCircleSyncTest` |
| Parcel signature or cert binding fails in `fresh()` | no | refused by `CircleCore.verify` before `evaluate` | bag unchanged | incoming event | CODE `FlitCircleSync.fresh`; PURE `CourierEnvelopeCryptoTest`, `CourierIntegrationTest` |
| Parcel outside the replay window or past TTL | no | `OutsideWindow` / `Expired`; no bag fold | bag unchanged until the `COURIER_TICK_MS` sweep runs `CourierCarryCore.expire` | incoming or expired parcel | CODE `FlitCircleSync.foldParcel` via `CourierCarryCore.evaluate`, `FlitEngine` sweep; PURE `CourierCarryDecisionTest`, `CourierEnvelopeCryptoTest` |
| Duplicate envelope id | no | `Duplicate`; no second fold | held copy stays | repeat copy | CODE `FlitCircleSync.foldParcel` via `CourierCarryCore.evaluate`; PURE `CourierCarryDecisionTest`, `CourierIntegrationTest` |
| Bag at `MAX_PARCELS` | yes — `Full` reserves nothing, a later offer can succeed | `Full`; no id reserved | all 256 held parcels | incoming offer | CODE `FlitCircleSync.foldParcel` via `CourierCarryCore.evaluate`; PURE `CourierCarryDecisionTest` |
| Addressee not met on a fanout/sync pass | yes — next encounter re-offers via `CourierCarryCore.select` | no — held | bag and signed event stay | nothing | CODE `FlitCircleSync.fanout/sync`; PURE `CourierCarryDecisionTest` |
| Handoff answered OK — peer accepted the offer | no | `CourierCarryCore.ack` removes offered ids; outcome `handed` means accepted, not opened | recipient's copy is local and unobserved here | acknowledged bag rows | CODE `FlitCircleSync` OK path, `FlitEngine.courierHandoffs`; PURE `CourierCarryDecisionTest`, `CourierIntegrationTest` |
| Handoff refused or carrier throws | yes — the parcel is re-offered on a later pass | outcome `failed`; no `ack` | bag stays with the courier | nothing | CODE `FlitCircleSync`, `FlitEngine.courierHandoffs` |
| `CourierEnvelope.open` fails at the addressee (expired, wrong `toFp`, bad wrapped key) | yes — held until the core sweep | no events folded; no handoff entry or carry line | bag row stays for expiry | parsed parcel only | CODE `FlitEngine` open path; PURE `CourierEnvelopeCryptoTest`, `CourierIntegrationTest` |
| `courierBag` row corrupt, or absent on an older row | no | decodes as an empty bag with a log line | unrelated Circle state stays | corrupt row | CODE `FlitCircleStore.decode`; PURE `FlitCircleStoreTest` |
| Bag write interleaved with a sync `upsert` | no | atomic decode → fold → encode → persist under `ioLock` | events, roster and deliveries intact | nothing | CODE `FlitCircleStore.update`; PURE `FlitCircleStoreTest` |
| New outcome with no log change | no | `refreshCircle()` includes `courierHandoffs` in the content-diff signature | local map only — never persisted or signed | nothing | CODE `FlitEngine.refreshCircle/courierHandoffs` |
| `parcel` event reaches the feed | no | `computeFeedData` filters it before body parse and tally | signed event stays in the log | feed row only — no `parcel` card or raw Base64 | CODE `CircleUi.computeFeedData` |
| Handoff with blank SHA-256, or blank/unknown outcome | no | `courierLine` returns null; `carryOutcome` reads `WAITING` | map entry ignored; bytes untouched | nothing | CODE `CourierCardUi.courierLine/carryOutcome`; PURE `CourierCardUiTest` |
| `handed` outcome read on a non-courier device | no | third-member line, never `Handed off to …` | bytes untouched | nothing | CODE `CourierCardUi.courierLine`; PURE `CourierCardUiTest` |
| Alias over `MAX_ALIAS_CHARS`, or envelope-like text in a handoff | no | bounded alias / `fp.take(8)`; no `name`, `size`, `fromFp` or envelope text in the line | bytes untouched | nothing | CODE `CourierCardUi.aliasOf/courierLine`; PURE `CourierCardUiTest` |
| Open affordance alongside a carry line | no | `openAffordance` from the landed set and `openingSha256`; a matching non-blank `openingSha256` (`RESOLVING`) wins over a landed hash; blank/unmatched SHA → `ABSENT` | landed copy stays available | nothing | CODE `CourierCardUi.openAffordance`, `CircleUi.FileCard`; PURE `CourierCardUiTest` |
| Two handoff entries for one SHA-256 | no | map keyed by SHA keeps one entry; one carry line | no bytes change | duplicate projection entry | CODE `FlitEngine.courierHandoffs`; PURE `CourierCardUiTest` |
| `courierHandoffs` absent (old caller or flow not yet published) | no | empty-map default; no carry lines | parcel bytes untouched | nothing | CODE `CircleUi.CircleScreen`, `MainActivity`; PURE `CourierCardUiTest` |
| Circle left | no | log and local handoff map cleared | own sealed copies deleted; History files stay | local carry state | CODE `FlitEngine` leave path; PURE (state reset) |

The rows above are CODE and PURE only. No app courier row claims DEVICE or
SIM-HARN evidence; the 2026-12-02 three-node walk is not part of this layer.

### 3.7 QR parcel frames — the air-gap conclusion table

The 2026-12-03 Protocol layer carries one signed, recipient-addressed payload
as an ordered list of opaque UTF-8 QR frame strings. `ParcelCodec` in
`protocol/.../parcel/` is JVM-pure: no camera, Android, socket, clock or file
write, and no second crypto path — it calls `CourierEnvelope.seal` / `open`.
Frozen constants: `FRAME_MAGIC = "FLITPARCEL1"`, `MAX_FRAMES = 64`,
`MAX_CHUNK_BYTES = 512`, `MAX_AIR_GAP_PARCEL_BYTES = 131072`, CRC32 per frame.
The app renders at EC `M` with margin `2`; that is a display choice and changes
no frame field, CRC or digest.

The exact API is:

```
object ParcelCodec {
  data class FrameDescriptor(val parcelId: String, val seq: Int, val count: Int,
                             val totalBytes: Int, val crc: Long)
  fun parse(frame: String): FrameDescriptor?
  fun encodeParcel(payload: ByteArray, parcelId: String, recipientFp: String,
                   signerFp: String, identity: TlsIdentity): List<String>
  fun complete(frames: List<String>, recipientFp: String): ParcelPayload?
  data class ParcelPayload(val parcelId: String, val recipientFp: String,
                           val signerFp: String, val sha256: String,
                           val bytes: ByteArray)
}
```

`parse` rebuilds the canonical bytes from the parsed fields and compares the
CRC, so a changed byte in any field is caught, not just the chunk. `complete`
checks frames, CRC, sequence, count, digest and recipient before it decrypts
through `CourierEnvelope.open`, and returns `ParcelPayload` only for a complete,
verified, correctly addressed set. `MAX_AIR_GAP_PARCEL_BYTES` is the frame-layer
cap, named apart from `CourierEnvelope.MAX_ENVELOPE_CHARS`; the app display cap
and the wire `MAX_FRAMES` are both 64.

| Condition | Conclusion | Keeps bytes | Drops | Evidence |
| --- | --- | --- | --- | --- |
| One frame of a sequence never captured | `complete` returns `null`: every `seq` in `1..count` is required | accepted frames held by caller; no bytes released | nothing | CODE `ParcelCodec.complete` (sequence/count gate); PURE missing-`seq` case |
| Count disagrees with `total`/`sz`, or `seq`/`count` outside `1..MAX_FRAMES` | `parse` returns `null`; nothing routed | nothing added | the offending frame | CODE `ParcelCodec.parse`; PURE cap cases |
| Single-bit change in any canonical field | CRC mismatch, `parse` returns `null` | frames already accepted for that `parcelId` | the corrupted frame | CODE `ParcelCodec.parse` (CRC32 over canonical bytes with `crc` excluded); PURE bit-flip-per-field case |
| Truncated or skewed string decodes to junk | `parse` returns `null`; nothing routed | nothing added | the bad string | CODE `ParcelCodec.parse` (length/base64/shape gates); PURE mutated-string case |
| Same frame captured twice | duplicate ignored; first copy wins | first copy of the chunk | the repeat | CODE `ParcelCodec.complete` (one frame per `(parcelId, seq)`); PURE duplicate case |
| Same `parcelId` with conflicting `count`/`total`/`crc` or bytes at one `seq` | conflict refused; first descriptor stays authoritative | first-seen frames | the conflicting frame | CODE `ParcelCodec.complete`; PURE conflict case |
| All `seq` present but whole-parcel digest differs | `complete` returns `null`; no bytes released | complete chunk set held by caller | nothing | CODE `ParcelCodec.complete` (digest gate); PURE re-CRC'd bad chunk |
| Envelope signature fails or cert no longer binds to the signer | `complete` returns `null`; no `ParcelPayload` | frames held by caller | opened bytes | CODE `CourierEnvelope.verify:215-225`; PURE forged-sig case |
| Opened parcel names another `recipientFp` | `complete` returns `null`; no output written | frames held by caller | opened bytes | CODE `ParcelCodec.complete` (recipient gate before `CourierEnvelope.open`), `CourierEnvelope.open:237`; PURE wrong-identity case |

The rows above are CODE and PURE only. No row claims DEVICE, SIM-HARN or a
camera walk: rendering, capture and the end-to-end air-gap walk are later layers
and are recorded as not verified here.

### 3.8 QR parcel CLI — the one-shot conclusion table

The 2026-12-04 CLI layer adds `parcel encode` and `parcel decode` at the harness
seam. Both commands are one-shot and offline: no socket, route, `Link`, pin
lookup or self-addressed fallback. `--cert PATH` holds the recipient's Base64
DER certificate for one encode invocation; the object is parsed with
`CertificateFactory`, bound to `--to` by `TlsIdentity.fingerprintOf`
(normalised), and handed to `ParcelCodec` directly. Decode keeps the frame
strings opaque and calls `ParcelCodec.complete` with the local fingerprint and
identity, then writes `ParcelPayload.bytes` to `--out` only on success. No file
name travels in a frame; the operator names the output
and the CLI reports signer, recipient, SHA-256 and byte count. Exit codes are
`2` for bad arguments or a missing cert/dir/out, `3` for a verification
failure, and `0` for success.

The exact CLI API is:

```
object ParcelCli {
  data class EncodeResult(val dir: Path, val frames: Int, val sealedChars: Int,
                          val sha256: String)
  data class DecodeResult(val out: Path, val bytes: Int, val sha256: String,
                          val signerFp: String, val recipientFp: String)
  internal fun parcelEncode(args: ParsedArgs): EncodeResult
  internal fun parcelDecode(args: ParsedArgs): DecodeResult
  fun usage(): String
  fun run(args: ParsedArgs): Int
}
```

| Condition | Conclusion | Keeps bytes | Drops | Evidence |
| --- | --- | --- | --- | --- |
| `--cert` missing or unreadable, not Base64 DER, or its `TlsIdentity.fingerprintOf` differs from `--to` | exit `2`; refuse before any PNG is rendered — no pin lookup and no self-addressed fallback | source file; any pre-existing frames in `--dir` | nothing; the certificate is held in memory for this invocation | CODE `ParcelCli.parcelEncode` cert gate; PURE `ParcelCliTest` (cert gate) |
| A `frame-*.png` is absent, or its QR does not decode | `complete` requires every `seq` in `1..count`; verification failure, exit `3`, no output file | all remaining frames on disk | assembled bytes in memory | CODE `ParcelCli.parcelDecode` + `ParcelCodec.complete` sequence gate; PURE missing-`seq` codec case; SIM-HARN `ParcelCliHarnessTest` (one frame removed) |
| Decode with an identity other than the addressed recipient: `complete(frames, identity.fingerprint, identity)` returns null, or the returned `ParcelPayload.recipientFp` is not the local fingerprint | exit `3`; no output file; signer and recipient fingerprints printed | frames unchanged | opened bytes | CODE `ParcelCli.parcelDecode` recipient gate + `ParcelCodec.complete`; PURE wrong-identity codec case; SIM-HARN `ParcelCliHarnessTest` (two temp homes, wrong recipient) |
| `--out` missing, already present, or its parent cannot be written | exit `2`; refuse before writing; frames untouched | existing output file and every frame in `--dir` | nothing | CODE `ParcelCli.run` / `parcelDecode` argument gate; PURE `ParcelCliTest` (flag cases) |

The rows above are CODE from the frozen CLI API and the adapter at the harness
seam, PURE from the inherited codec tests and the flag/cert-gate tests, and
SIM-HARN from the two harness test classes named in the roadmap. No row claims
DEVICE: rendering, capture and the air-gap walk are a later layer dated
2026-12-08 and are not verified here. Test totals are recorded in TESTING.md,
not in this table.

### 3.9 QR parcel app — camera, decode and out-of-cap states

The 2026-12-07 app layer wraps the 2026-12-03 frame codec and the 2026-12-04
CLI in two surfaces: `ParcelShowCard` renders the opaque frame strings returned
by `ParcelCodec.encodeParcel`, and `ParcelScanCard` folds camera or pasted
frames into `ParcelScanState` and hands the verified `ParcelPayload.bytes` to
the existing inbound path. It adds no frame grammar, no crypto path and no
route. `ParcelScanState` is Android-free: it holds opaque frame strings, parses
them with `ParcelCodec.parse`, dedups by `(parcelId, seq)` and closes through
`ParcelCodec.complete(frames, recipientFp, identity)`. The receiving identity's
fingerprint is the recipient gate and the identity supplies the private key for
the codec's `CourierEnvelope.open` call. Frame text is opaque to the UI: it
reads only `parcelId`, `seq` and `count` from the descriptor for gating and
progress, never the payload bytes, CRC or magic. No file name travels in a
frame; the save name is chosen locally.

| Condition | Conclusion | Keeps bytes | Drops | Evidence |
| --- | --- | --- | --- | --- |
| Camera permission denied or still in flight | Paste path stays available; no analyzer is bound and no frame is captured | nothing kept | no file written | CODE `QrUi.QrScanCard:196-209`, `:211-247` (the CAMERA ask and paste fallback reused by `ParcelUi`); PURE paste-parser case in `ParcelScanStateTest` |
| `Qr.decodeYPlane` returns null on a skewed or noisy camera frame | Ignore that frame; the analyzer stays bound and the next frame is tried | previously accepted frames kept | the undecodable frame | CODE `QrUi.CameraQrView:274-307`, `Qr.decodeGray:219-229`; PURE `FlitQrTest` noise case |
| Impossible Y-plane geometry — row/pixel stride shorter than the row stencil, or a short buffer | `decodeYPlane` rejects before decoding; the analyzer's catch leaves scanning running | previously accepted frames kept | the malformed frame | CODE `Qr.decodeYPlane:195-206`, `QrUi.CameraQrView:303-307` |
| Duplicate `(parcelId, seq)` in one scan | Ignore the repeat; the first copy stays authoritative | first copy of the chunk | the duplicate | CODE `ParcelScanState.add` (dedup), `ParcelCodec.complete:203-209`; PURE `ParcelScanStateTest` duplicate case |
| A frame with another `parcelId` arrives mid-scan | Ignore the foreign frame; the current assembly is not replaced | current assembly kept | the foreign frame | CODE `ParcelScanState.add`; PURE `ParcelScanStateTest` mixed-id case |
| `ParcelCodec.parse` returns null — bad magic, version, field shape, CRC, count/size/total disagreement, or out-of-range `seq`/`count` | Ignore the frame; nothing is routed to the assembly | current assembly kept | the offending frame | CODE `ParcelCodec.parse:111-114`, `ParcelCodec.parseFull:242-281`; PURE `ParcelCodecTest`, `ParcelCodecTamperTest` cap and tamper cases |
| A frame pushes the accepted set past `MAX_PARCEL_FRAMES` (64) | Refuse the frame; progress stays at the cap | the 64 accepted frames | the excess frame | CODE `ParcelScanState.add` (cap), `ParcelCodec.MAX_FRAMES:50`; PURE `ParcelScanStateTest` over-cap case |
| Scan dialog closed before every `seq` in `1..count` is present | Conclude incomplete; in-memory frames are dropped and nothing is durable | nothing durable | all in-memory frames | CODE `ParcelScanState` per-dialog lifetime; PURE `ParcelScanStateTest` missing-`seq` case |
| `ParcelCodec.complete` returns null — CRC, digest, signature, cert binding or recipient mismatch | Conclude not from the claimed signer; log the line; no bytes released | frames held for the dialog only, nothing durable | frames and payload on close | CODE `ParcelCodec.complete:183-239`, `CourierEnvelope.verify:215-225`; PURE `ParcelScanStateTest` complete-null case |
| Complete verified payload | Hand `ParcelPayload.bytes` to the inbound callback exactly once; a second `complete()` returns null | landed file or feed row survives | transient frames | CODE `ParcelScanState.complete` (once), `ParcelCodec.complete:232-238`; PURE `ParcelScanStateTest` in-order and double-complete cases |

The rows above are CODE from the frozen seams (`ParcelUi`, `ParcelScanState`,
`Qr`, `ParcelCodec`) and PURE from the JVM-only app and protocol tests. No row
claims DEVICE: a live-camera capture and the two-phone air-gap walk are dated
2026-12-08 and are not claimed here. Test totals are recorded in TESTING.md,
not in this table.

### 3.10 Per-drop content keys — the pure conclusion table

The 2026-12-09 Protocol layer ([per-drop-keys.md](roadmap/per-drop-keys.md))
adds `DropKeyCore`, a JVM-pure object under `protocol/.../circle/`. It mints one
fresh 32-byte key and 12-byte IV per file drop, wraps the key to current
members' certificates with RSA-OAEP/SHA-256, seals the bytes with AES-256-GCM
emitting `IV || ciphertext || tag`, and publishes one wrapped entry per member
in the signed `file` body. The exact API is:

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

AAD is `field("FLITDROP1")` under the `CircleCore.canonical` 4-byte big-endian
UTF-8 field rule. `CircleCore.validFileBody` accepts exactly the legacy
`{name,size,sha256}` set or the keyed `{name,size,sha256,keyGen,cipherSha,iv,wraps}`
set; every other key set is refused. `TlsIdentity` gains one additive instance
method `decryptOaepSha256(cipher: ByteArray): ByteArray?`, backed by the
existing private key, null on any failure, with no pin writes; `unwrap` calls
it and no other method here reads or writes the pin map. The protocol core
holds no key cache, no sealed bytes and no drop state and persists no plaintext
key.

| Condition | Conclusion | Keeps bytes | Drops | Evidence |
| --- | --- | --- | --- | --- |
| No usable certificate for a member at wrap time | That fingerprint gets no `wraps` entry; no group-key fallback and no escrow | Source bytes stay with the caller | That member's wrapped key | CODE `DropKeyCore.wrap`; PURE `DropKeyCryptoTest` (wrap omits the fingerprint with no pinned cert) |
| No wrap for the reader, or `unwrap` under the wrong identity | `unwrap` returns null; no plaintext is released and no pin is written | The caller's sealed bytes stay; the core holds none | No plaintext | CODE `DropKeyCore.unwrap`, `TlsIdentity.decryptOaepSha256`; PURE `DropKeyCryptoTest` (wrong identity and absent fingerprint return null, pin map unchanged) |
| Roster above `MAX_WRAPS = 32` | `wrap` refuses the whole map; it is never silently trimmed and no event is minted | Source bytes stay with the caller | The over-cap map; nothing published | CODE `DropKeyCore.wrap` (`MAX_WRAPS`); PURE `DropKeyCryptoTest` (a map larger than 32 is refused, not trimmed) |
| Wrapped value over `MAX_WRAPPED_CHARS = 512`, or body over `MAX_BODY_CHARS` (16 KiB) | Gate false; `parseFileKeyBody` null; no event minted | The caller keeps the bytes | The over-cap body | CODE `CircleCore.validFileBody`, `DropKeyCore.parseFileKeyBody`; PURE `DropKeyBodyTest` |
| Partial, extra or mistyped keyed body — string `size`, non-positive `keyGen`, `iv` not decoding to 12 bytes, blank or lowercase fingerprint | `validFileBody` false; no event minted or folded | The caller keeps the bytes | The malformed body | CODE `CircleCore.validFileBody`; PURE `DropKeyBodyTest` (exact key sets and value types) |
| Malformed Base64 or bad OAEP padding in a wrap | `decryptOaepSha256` returns null and `unwrap` returns null; no exception reaches the caller and no pin is written | The caller's sealed bytes stay | The malformed wrap; no plaintext | CODE `TlsIdentity.decryptOaepSha256`, `DropKeyCore.unwrap`; PURE `DropKeyCryptoTest` (junk strings return null) |
| Modified `wraps`, `iv` or `cipherSha` on the signed event | `CircleCore.verify` fails before any unwrap; the event is skipped | Event log unchanged | The modified event | CODE `CircleCore.verify`, `CircleCore.canonical`; PURE `DropKeyBodyTest` (signed body then tampered copy) |
| Flipped IV, ciphertext or tag byte, a different key, or a changed `FLITDROP1` AAD | GCM authentication fails; `open` returns null; no plaintext | The caller's sealed bytes stay; the core holds none | The plaintext | CODE `DropKeyCore.open`; PURE `DropKeyCryptoTest` (one-byte flips, wrong key, AAD change) |
| Plaintext digest differs from the announced `sha256` | Drop refused before content is shown; the announced plaintext digest is inside the signed body | The caller's sealed bytes and signed body stay | The substituted plaintext | CODE `CircleCore.canonical` (plaintext `sha256` signed in the body); PURE `DropKeyCryptoTest` (digest gate refuses a rebuilt drop) |
| Duplicate or out-of-window `file` id | `evaluate` returns `Duplicate` / `OutsideWindow`; the drop is not re-accepted | Unchanged | The replayed event | CODE `CircleCore.evaluate`; PURE `DropKeyCoreTest` (injected clock) |
| Departure, group `rekey`, or a `keyGen` bump on another drop | Old drop bodies unchanged; no wrap backfill and no remote revocation | Old drops and wraps stay | Nothing | CODE `CircleCore.canonical`/`verify` (body binding is generation-independent); PURE `DropKeyCoreTest` (fold a `rekey`, old bodies unchanged) |

The rows above are CODE and PURE only. No row claims DEVICE or SIM/HARN: this
layer adds no endpoint, carrier, camera or app state. App and CLI parity,
harness walks and any keyed-drop device observation belong to later layers and
yield no claim here.

---

## 4. Panic fan-out — conclusion and hop accounting

Panic is informational: nothing is wiped or changed remotely
([QR-only / panic](qr-only-and-panic.md)). The flow:

- **Send:** `PanicCore.create` signs canonical bytes that include `hops` and
  `originFp` (`PanicCore.kt:54-65`, `:90-115`). `fanOutPanic` iterates QR-known
  devices, tries their allowed carrier in route-policy order (Bluetooth for
  AUTO/BLUETOOTH_ONLY, relay last and only with `relayFor`, Wi‑Fi for
  AUTO/WIFI_ONLY), and counts a device only when the peer answered `200`
  (`FlitEngine.kt:454-505`). It logs `panic: reached/N known device(s) confirmed
  the alert` — never a claim of more.
- **Receive:** the server verifies the self-signed cert pins to `senderFp` and
  `PanicCore.verify` passes, then `PanicCore.accept` dedups (`ServerCore.kt:925-944`).
  The app additionally gates on `originFp` being QR-known and de-dups the banner
  by id (`FlitEngine.kt:403-423`). A banner is kept in memory (max 20) and the
  user chooses Acknowledge / Dismiss / Forward; **all three are local actions**.
- **Forward:** explicit tap only. `PanicCore.forward` re-signs the original
  alert with the forwarder's identity, preserves `id`, `note` and `originFp`,
  and returns `null` at `hops >= DEFAULT_MAX_HOPS` (3); the UI toasts the hop
  limit (`PanicCore.kt:168-186`; `FlitEngine.kt:434-452`). Because `hops` is
  inside the signed bytes, a bumped count cannot be forged. Looped alerts are
  deduplicated by `id`, not re-shown as new.
- **Replay / invalid:** `evaluate` returns `Invalid`, `HopLimit`,
  `OutsideWindow`, `Duplicate`, or `Accept` in that precedence
  (`PanicCore.kt:118-131`); only `Accept` marks the id seen (`:137-146`).

**Evidence.** PURE: `PanicTest` (`PanicCore` canonical/verify/forward/hop/replay
cases). DEVICE: one phone + CLI box — QR v2 paste pinned the box, phone-sent
alert logged with fingerprint/text on the box, box-sent alert produced the
phone banner with alias/note/safety copy
([QR-only / panic](qr-only-and-panic.md)).
**Not verified:** two-phone mesh hops — listed limitation.

---

## 5. Process death, foreground service and sharing restoration

### 5.1 What dies and what survives

`FlitEngine.state.running` is in-memory; a killed process forgets it. The
durable fact is `FlitRoster.SharingDesired` — a few bytes in the app's own
SharedPreferences (`FlitRoster.kt:65-117`):

- **write `true`** on every deliberate start (share sheet, invite link, silent
  restore) and on a live FGS promotion (`FlitService.kt:71-80`;
  `MainActivity.kt:448`);
- **clear only on explicit Stop** (`MainActivity.kt:461-467`;
  `FlitService.onDestroy` for an instance that actually owned a live,
  non-timed-out FGS, `:175-184`);
- **read** on cold `onCreate`/`onResume` via `shouldRestoreSharing`, restoring
  silently when desired and neither `running` nor `starting`
  (`MainActivity.kt:344`; `SharingDesired.shouldRestore`, `FlitRoster.kt:108-115`).

The codec tolerates bare `true/1/yes` and `false/0/no` old rows, absent, blank,
corrupt and forward-newer rows — all decode to "not desired" without throwing
(`FlitRoster.kt:95-106`). Internal rebinds (rename, hidden, carrier change) use
`stopForRebind()`, which never clears the flag (`MainActivity.kt:472-499`,
`:2005`).

### 5.2 FGS failure ladder (CODE)

`FlitService.onStartCommand` promotes **first**, inside Android's start window
(`:67-81`). On API 34+ it requests `dataSync` always and adds `connectedDevice`
only when **both** the Bluetooth carrier option is on and its runtime grant
exists (`:121-153`). A `SecurityException` from an over-broad type is retried
with `dataSync` alone — losing a label is better than losing the FGS. If the
start request itself is refused, the service stays engine-only, toasts the
limitation, stops only that `startId`, and returns `START_NOT_STICKY`
(`:83-97`). A live FGS returns `START_STICKY`.

`onTimeout` (Android 15 `dataSync` ~6 h/day cap) removes the notification with
`STOP_FOREGROUND_REMOVE` to avoid a fatal `RemoteServiceException`, stops that
start, and leaves the engine alive while a Flit surface is foreground
(`:161-173`). `onDestroy` calls `FlitEngine.stop()` **only** for an instance that
owned the engine through a live, non-timed-out FGS (`:175-184`).

### 5.3 MIUI and OS limits

- MIUI can still kill the process (battery saver, autostart rules, memory
  pressure). The persisted flag means sharing returns **the next time the user
  opens Flit**, not that MIUI restarts us. A force-stop or revoked autostart
  leaves sharing off until the app is opened or started explicitly.
- An explicit Stop is honoured; the row is false and nothing silently re-enables.
- At the `dataSync` cap there is no background guarantee for the rest of that
  day's budget; the next cold open restores from the row.
- A viewer is an external app: its MIME support and any MIUI restrictions on it
  are not Flit's to promise. Flit guarantees a valid URI with a read grant and a
  clean return.

Full root-cause stack of the original "share → Circle → Open file → app closes,
sharing off" report — share-sheet auto-finish, ambiguous back/dialog ownership,
missing `FileProvider` root plus stale ledger digest, and the in-memory
`running` flag with over-broad FGS type — is in
[open-file-and-sharing-restore.md](open-file-and-sharing-restore.md#root-cause-as-traced).

**Evidence.** PURE: `SharingRestoreTest` and the codec cases in `FlitRosterTest`
(`sharing desired survives an encode-decode round trip`, `an absent or old
sharing-desired row decodes safely`, `a corrupt or forward-newer
sharing-desired row never throws`, `restore only when desired and neither
running nor already starting`).
DEVICE: 2026-11-25 final build — 35 B drop landed, chooser opened with pid alive
and no `FATAL`/`am_crash`/`Force finishing`, back returned to the feed,
`am force-stop` → relaunch logged `egress pinned…` and
`serving https://0.0.0.0:53317…` automatically with the Home card showing Stop
([open-file doc](open-file-and-sharing-restore.md#device-evidence-2026-11-25-final-build)).
Earlier capture: 8-minute screen-off idle soak with an empty crash buffer
([bluetooth carrier](bluetooth-carrier.md)).

---

## 6. Recovery recipes (operator-facing)

Each recipe states what is automatic and the manual step. None needs an
account, a server, or a settings change beyond the opt-ins already documented.

1. **A send is killed mid-body (sender or receiver).** Automatic: owe row stays
   on disk (queue IS the checkpoint); the receiver's `.part` + `.part.token`
   survive; a restart repopulates the parts registry. Do not delete the `.part`.
   The next announce, keeper touch, sweep find, hunt beat, or manual "retry now"
   flushes the row; `/partial` re-learns the prefix; the tail is sent; SHA-256
   gates the landing. If the carrier changed (Wi‑Fi ↔ Bluetooth), expect a clean
   full resend — the old part's origin no longer matches.
2. **Prepare declined.** Nothing to retry: the batch is concluded with
   `declined = true`. Fix the cause at the receiver (QR pairing / trusted-senders
   gate), then send again.
3. **Receiver answers `409 Resume base no longer held`.** The kept base was
   trimmed or consumed. The sender falls back to a clean send with the same
   session/token rules; the corrupt tail is not trusted.
4. **Wi‑Fi multicast blocked but peers are on the same LAN.** Automatic: an empty
   roster starts a throttled subnet sweep (≤24 knocks, ≥120 s between rounds,
   one round per silent roster, hidden mode stands down). A dial-only peer is
   kept fresh by a ~15 s re-register keeper; three misses retire it.
5. **No route at all (NATs / cellular).** Configure the self-hosted relay once in
   Options and give the peer a Known-device entry with its relay address/token
   and `relayFor` on. Both peers must be live; the relay is last in priority so a
   direct answer always wins. If the relay dies mid-send, wait for re-subscribe
   and resume — no manual cleanup.
6. **Circle drop shows `pending`/`failed`.** The spool is kept; the ~30 s worker
   retries with per-sha backoff (20/40/60 s) to **current** members only. A
   returned report concludes it; a thrown carrier does not. A later joiner gets
   the card but no old bytes — no backfill by design.
7. **Circle file card has no Open button.** No local copy with that SHA-256 is
   present (or the hash is still resolving). Wait for the landed file, or open it
   from the receiver's own History. A failed open ends in a toast, never a crash.
8. **App was force-stopped / killed by MIUI and sharing is off.** Just open Flit:
   a cold `onCreate`/`onResume` restores silently from `sharingDesired`. If the
   user had pressed Stop, it stays off — that is intended. A strict ROM may leave
   the service engine-only; then sharing works only while Flit is foreground.
9. **At the Android 15 `dataSync` cap.** The notification disappears promptly;
   the engine keeps serving while the app is open. The next cold open restores
   sharing. No background guarantee remains for the rest of that day's budget.
10. **Panic alert loops or hits the hop limit.** Forward is explicit; at
    `hops >= 3` the toast says so and nothing is sent. The `id` is deduped, so a
    looped alert is not re-shown. Acknowledge/Dismiss/Forward change only local
    state.
11. **A courier parcel has no route to its addressee.** Automatic: the parcel
    stays in the bounded bag until `nowMs >= createdMs + MAX_PARCEL_TTL_MS`;
    `select` offers it on a later encounter and `ack` removes acknowledged ids.
    A Full refusal reserves nothing, so the same offer can succeed after space
    frees. A malformed body is Invalid and an `open` refusal leaves the envelope
    in the bag. The encoded `CarryState` survives a carrier restart at the
    app/CLI layer, so the signed event is re-offered and the addressee dedups by
    id. A corrupt bag row decodes to an empty bounded state. The CLI carrier's
    one-shot `drop`/`carry`/`receive`/`status`/`sweep` conclusions are in §3.5;
    that handoff is harness-evidenced at 2026-11-30 and has no DEVICE row. The
    three-node courier walk is 2026-12-02.

---

## 7. Evidence map by flow

| Flow | CODE | PURE | SIM/HARN | DEVICE |
| --- | --- | --- | --- | --- |
| Prepare decline vs carrier death | `ProtocolClient.kt:254-290`, `Protocol.kt:74-75` | `ProtocolRefusalTest` | `bt-sim` parity | declined gate walked via QR/trust policy |
| Mid-body death / resume | `ServerCore.kt:491-647`, `:1019-1059`, `:1179-1191` | `ResumeAndTurboTest`, `PartialSidecarTest` | `deathRun` in sim suite | 400 MB (C) and 300 MiB (A) kill/resume, hash-exact |
| Turbo chunk salvage | `ServerCore.kt:720-793` | `ResumeAndTurboTest` | multi-socket hash-exact test | late ~90 % kill → clean full resend |
| Queue rows / spools | `FlitEngine.kt:2163-2233`, `:2582-2806`; `FlitRoster.kt:23-62`, `:272-306` | `FlitRosterTest`, `FlitSpoolTest` | — | kept → merged → 2/2 → cancel walk |
| Hunt / route policy | `FlitRoute.kt:40-74`, `FlitEngine.kt:1612-1700` | `FlitRouteTest` | — | 2026-11-15 hunt walk |
| Relay death | `RelayCore.kt`, `FlitEngine.kt:1709-1721` | `RelayCoreTest` | `RelayServerTest` (5 tests) | 2026-11-24 relay kill/resume, hash-exact |
| Circle file statuses | `FlitEngine.kt:507-608` | `CircleHardeningTest` | `CircleHarnessTest` | 35 B drop landed + Open-file walk |
| Circle governance / repair | `FlitCircleHost.kt`, `FlitCircleStore.kt:283-524`, `FlitCircleSync.kt` | `CircleCoreTest`, `CircleHardeningTest`, `FlitCircleHostTest`, `FlitCircleSyncTest` | `CircleHarnessTest` | join/post/poll-vote/file/kick/rekey walks; 3-identity Circle at 2026-11-26 |
| Panic fan-out | `FlitEngine.kt:403-505`, `ServerCore.kt:925-944` | `PanicTest` | — | one phone + CLI box banner/hops |
| FGS / sharing restore | `FlitService.kt`, `FlitRoster.kt:65-117`, `MainActivity.kt` | `SharingRestoreTest`, `FlitRosterTest` | — | force-stop → relaunch restore walk |
| MIUI / dataSync cap | `FlitService.kt:161-184` | — | — | idle soak; cap behaviour is **not device-walked** |
| Courier envelope / carry bag | `CourierEnvelope.kt`, `CourierCarryCore.kt` | `CourierEnvelopeCodecTest`, `CourierEnvelopeCryptoTest`, `CourierCarryCodecTest`, `CourierCarryDecisionTest`, `CourierIntegrationTest` | — | — |
| Courier CLI carrier | `CourierCli.kt` | `CourierCliTest` | `CourierCarrierHarnessTest` | — |
| Courier app worker / card projection | `FlitEngine.circleCourierDrop/courierHandoffs`, `FlitCircleSync.postParcel/fresh/fanout/sync`, `FlitCircleStore.update/decode`, `CourierCardUi.kt`, `CircleUi.computeFeedData`, `MainActivity` | `FlitCircleSyncTest`, `FlitCircleStoreTest`, `CourierCardUiTest` | — | — |
| QR parcel frame codec | `ParcelCodec.kt` (`parse`/`encodeParcel`/`complete`, CRC and caps) | `ParcelCodecTest`, `ParcelCodecTamperTest` | — | — |
| QR parcel CLI | `ParcelCli.kt` (`parcelEncode`/`parcelDecode`, cert gate, `--out` gate, exit codes) | `ParcelCodecTest`, `ParcelCodecTamperTest`, `ParcelCliTest` | `ParcelCliHarnessTest` | — |
| QR parcel app camera / decode / caps | `ParcelUi` camera-and-paste seam, `ParcelScanState`, `Qr.decodeYPlane:187-216`, `ParcelCodec.parse/complete` | `ParcelScanStateTest`, `FlitQrTest`, `ParcelCodecTest`, `ParcelCodecTamperTest` | — | — |
| Per-drop content keys (pure core) | `DropKeyCore.wrap/unwrap/seal/open`, `keyedFileBody`, `parseFileKeyBody`; `CircleCore.validFileBody/verify`; `TlsIdentity.decryptOaepSha256` | `DropKeyCoreTest`, `DropKeyBodyTest`, `DropKeyCryptoTest` | — | — |

The CLI carrier handoff has SIM-HARN evidence at 2026-11-30 but no DEVICE
entry: the bag moved between directories on one box. The 2026-12-01 app courier
worker and card projection rows are CODE and PURE only. The three-node courier
walk is dated 2026-12-02 and is not claimed here. The 2026-12-03 QR parcel frame
codec row is CODE and PURE only; camera capture and the air-gap walk are later
layers and are not claimed here. The 2026-12-04 QR parcel CLI row adds CODE,
PURE and SIM-HARN from the one-shot adapter and its two-temp-home harness pair,
with no DEVICE entry; the air-gap walk is 2026-12-08. The 2026-12-07 app camera
row in §3.9 is CODE and PURE only; no live-camera capture or air-gap walk is
claimed for it. The 2026-12-09 per-drop content key row in §3.10 is CODE and
PURE only: the frozen pure protocol seams and their JVM tests. No harness,
camera, CLI or device observation is attached to this layer.

---

## 8. Limitations and inconsistencies

None changes a shipped contract.

1. **Two-phone RFCOMM transfer is not verified.** The rig has one phone and the
   bench box has no Bluetooth adapter (`bluetoothctl list` empty). The carrier
   is CODE- and SIM-evidenced; a real phone-to-phone socket path has not
   been walked, and neither have two-phone panic hops. A 3-identity Circle
   (one phone host plus two CLI nodes) **was** walked on device at 2026-11-26 —
   join, post, poll/vote, file landing and kick/rekey
   ([three-node baseline](completions/2026-11-26-three-node-baseline.md)) — so a
   three-member Circle is DEVICE-evidenced here; what is not walked is a live
   mesh of always-on peers or a second physical phone.
2. **Wi‑Fi Aware is absent on the rig phone** (`pm has-feature
   android.software.wifi.aware` → false, 2026-11-21), so that ladder rung was
   parked on hardware grounds.
3. **Outbound phone→host Circle fan-out remains test-evidenced only** (the
   2026-11-25 close still lists it, and the 2026-11-26 three-node baseline
   records that an app-posted message never reached the one-shot CLI pullers).
   Inbound sync, join, leave and governance are device-evidenced.
4. **The "8 MiB Circle file drop" is not supported by the source docs or
   code.** The documented device drop is 35 B
   (`open-check.txt`), the harness payload is 8192 B, and `8 MiB` is the turbo
   chunk hint. Treated as "not verified" here.
5. **Wording precision, not a contradiction:** [Circle](circle.md)
   calls `failed` terminal and also says the worker retries failed recipients.
   In code (`FlitEngine.kt:507-511`, `:575-596`, `:612-629`) `FAILED` is terminal
   for that recipient in a pass; the worker tick re-enters the row so an
   all-terminal delivery gets one cleanup pass that deletes its spool. Pending
   recipients are the only ones re-sent. The matrix in §3.1 states the code
   reality explicitly.
6. **Two backoff families coexist by design** and are easy to confuse: Circle
   sync uses 5 s base ×2ⁿ cap 120 s (`FlitCircleSync.kt:44-54`); Circle file
   delivery uses 20/40/60 s (`FlitEngine.kt:603-605`); the hunt uses 2/5/15/30/60 s
   (`FlitRoute.kt:49-50`). They govern different loops and are not interchangeable.
7. **Grant-release and `dataSync`-cap behaviour are code-verified, not
   device-walked** where noted in §2.3 and §7. The idle soak shows no crash and
   no restart surprise; it does not exercise the cap itself.
8. **Plain v2.2 peers have no `/partial`** — a probe 404 costs one round trip and
   a resumed base is impossible without the Flit extension. The final SHA-256
   gate verifies every landing regardless. Cross-carrier resume is refused by
   origin binding, producing a clean full resend.
9. **Courier carrier handoff has no DEVICE walk here.** The §3.4 courier rows
   are CODE and PURE; the §3.5 CLI carrier rows add CODE, PURE and SIM-HARN
   from the 2026-11-30 one-shot command and its harness pair. The bag moved
   between directories on the bench box only. The three-node courier walk is
   2026-12-02 and yields no claim here.
10. **The 2026-12-01 app courier layer has no DEVICE walk here.** The §3.6 rows
    are CODE and PURE only: the sync worker, bag persistence, local handoff
    projection and file-card carry line are source- and unit-test-evidenced.
    The three-node courier walk dated 2026-12-02 yields no claim here.
11. **The 2026-12-07 app QR parcel layer has no camera or device walk here.**
    The §3.9 rows are CODE from the frozen seams and PURE from the JVM-only app
    and protocol tests. Live camera capture from a sensor and the two-phone
    air-gap walk are dated 2026-12-08 and yield no claim here.
12. **The 2026-12-09 per-drop content key layer has no device walk here.** The
    §3.10 rows are CODE from the frozen `DropKeyCore`, `CircleCore` and
    `TlsIdentity` seams and PURE from the three JVM-only protocol test classes.
    This layer adds no endpoint, carrier, camera or app state; app and CLI
    parity, harness walks and any keyed-drop device observation belong to later
    layers and yield no claim here.
