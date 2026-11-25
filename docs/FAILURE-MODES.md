# Failure modes and the conclusion matrix

> Part of the Flit design set. [Ladder and index](transport-bluetooth.md) ·
> [Wire contracts](CONTRACTS.md) · [Security](SECURITY.md) · [Performance](PERFORMANCE.md) ·
> [Testing](TESTING.md) · [Operations](OPERATIONS.md) ·
> [Circle](circle.md) · [Open file / sharing restore](open-file-and-sharing-restore.md) ·
> milestones [A](milestone-a.md), [C](milestone-c.md), [D](milestone-d.md), [E](milestone-e.md).

This document answers one question for every flow in Flit: **when something goes
wrong, does it retry, conclude, keep bytes, or drop them?** It records the
contract that makes that decision, the on-disk state that survives each kind of
death, and the concrete recovery path. It deliberately says "not verified"
where the rig had no hardware to walk a case.

## Evidence legend

Every claim below is tagged with the strongest evidence that actually exists for
it. The four levels are never mixed silently:

| Tag | Meaning |
| --- | --- |
| **CODE** | Read from the source at the `file:line` cited. States what the program does, not what was observed running. |
| **PURE** | Deterministic JVM unit test in `protocol/src/test` or `app/src/test` (no Android, no socket, no device). |
| **SIM/HARN** | Harness test or `bt-sim` run against the production client/server over in-process pipes or loopback TLS. |
| **DEVICE** | Observed on the Redmi Note 10S (API 36) and/or the `flit-cli` box on the rig. The dated walks are cited; nothing else is claimed as walked. |

Rig at the last green commit `72225f8`: **82 protocol + 210 app + 10 harness =
302 tests, 0 failures**. Commit before it: `2034fa6`. The non-profit constraint
holds throughout: no accounts, no cloud, no ads, no telemetry, no phone-home
SDKs; every recovery path below is local.

---

## 0. The one contract that decides every outcome

Almost every "retry or conclude" question reduces to a single rule at the
protocol/app boundary. It was written after milestone A's first hardware walk
caught a defect that deleted a batch (see [milestone A](milestone-a.md#as-first-hardware-walk-caught-a-c-era-defect-2026-11-15--and-the-fix-is-the-contract)).

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
[transport ladder](transport-bluetooth.md#c--process-death-checkpoints). Pinned by
`ResumeAndTurboTest` (`a resume header with no held base is refused with 409 and
writes nothing`).

**Evidence.** PURE: `ResumeAndTurboTest` (5 tests: both RFC `bytes s-e/total` and
`bytes=s-e/total` syntaxes; restart probe/attach; failed-chunk-then-resume;
409-for-missing-base; a real `multi=true` hash-exact landing) and
`PartialSidecarTest` (`the part token sidecar exists before the first body byte`).
DEVICE: 2026-11-14 400 MB force-stop walk — 127,959,040-byte part + sidecar
survived both deaths, restart re-registered it with empty `senderIp`, resume
landed hash-exact and consumed part/sidecar/row
([milestone C](milestone-c.md#milestone-c-on-hardware-2026-11-14--the-walk-that-proves-both-truths)).
DEVICE: 2026-11-24 300 MiB camera shot killed at 33,980,416 B → `recv-resume … from
33980416 bytes` → hash-exact, no leftover part
([transport ladder](transport-bluetooth.md#c--process-death-checkpoints)).

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
sparse prefix near completion is not advertised as a resume base. This is a
listed limitation, not a hidden gap
([transport ladder](transport-bluetooth.md#c--process-death-checkpoints)).

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
  mid-write camera file) the row is kept with an honest log, not a "send failed"
  lie (`:2174-2183`). If some open, `dropSent` removes **only** the opened
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
([bluetooth carrier](bluetooth-carrier.md#queue-on-hardware-2026-10-30-d1-walk)).
The grant-release leg is **code-verified only**: that walk used cache-path
tokens, so no SAF grant was in play (see [README](README.md#current-capabilities-verified-on-device)).

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
([milestone A](milestone-a.md#as-first-hardware-walk-caught-a-c-era-defect-2026-11-15--and-the-fix-is-the-contract));
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
([milestone E](milestone-e.md#the-shape-a-blind-pairer-not-a-server)).

Failure outcomes:

- **Relay killed mid-transfer** → the dial throws `TransferUnreachable`; the
  sender keeps its row; both ends re-subscribe; the next hunt re-pairs and
  resumes from the receiver's prefix. DEVICE: 2026-11-24 — 300 MiB shot cut by
  `kill -9` on the relay, `recv-resume … from 31866880 bytes`, exact killed
  prefix, hash-exact landing, no leftover part
  ([transport ladder](transport-bluetooth.md#e--self-hosted-relay)).
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
pending or failed recipient" ([Circle](circle.md#status-semantics-app-as-built)).
In code the worker tick is 30 s, while the *per-delivery* backoff is
`minOf(60_000, 10_000 shl attempt)` → 20/40/60 s (`FlitEngine.kt:603-605`), and
`deliverCirclePending` skips already-terminal recipients. An all-terminal row
therefore gets one cleanup pass that deletes the spool, not an endless resend.

**Later joiners / no backfill.** Delivery targets are captured at post time. A
device that joins later folds the signed announcement and sees the card but
receives no old bytes; its card shows the neutral "file drop · open to view if it
arrived" line. Status is sender-local, so a recipient's card cannot claim bytes
are missing.

**Open file states** ([open-file doc](open-file-and-sharing-restore.md#circle-card--three-honest-states)):
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
  plaintext** — there is no content cipher to erase. This is recorded, not
  hidden ([Circle](circle.md#threat-model-and-safety-rails)).
- **Vote-kick:** one latest vote per current member; a changed vote can block a
  kick before it lands; strict majority of current members decides
  (`:490-524`). Ties are displayed as ties.

**Evidence.** PURE: `CircleCoreTest`, `CircleHardeningTest`,
`FlitCircleHostTest` (team's `wrong circle … refusals`, `join requires the
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
  verification item. Recorded explicitly in
  [Circle](circle.md#known-limitations-recorded-not-hidden) and still open in
  the 2026-11-25 close (`:357-361`).
- Multi-member mesh, one Circle at a time, no content encryption, bearer invite,
  blind relay requiring a manual Known-device entry with `relayFor` — all listed
  in the same section. None is contradicted here.

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
([QR-only / panic](qr-only-and-panic.md#hardware-evidence-one-phone--the-cli-box)).
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

### 5.3 MIUI and OS limits — honest boundaries

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
([bluetooth carrier](bluetooth-carrier.md#idle-soak-020-on-hardware-2026-10-30-d1-close)).

---

## 6. Recovery recipes (operator-facing)

Each recipe states what is automatic and what the user must do. None needs an
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
   kept fresh by a ~15 s re-register keeper; three misses retire it honestly.
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
| Circle governance / repair | `FlitCircleHost.kt`, `FlitCircleStore.kt:283-524`, `FlitCircleSync.kt` | `CircleCoreTest`, `CircleHardeningTest`, `FlitCircleHostTest`, `FlitCircleSyncTest` | `CircleHarnessTest` | join/post/leave/rekey two-node walk |
| Panic fan-out | `FlitEngine.kt:403-505`, `ServerCore.kt:925-944` | `PanicTest` | — | one phone + CLI box banner/hops |
| FGS / sharing restore | `FlitService.kt`, `FlitRoster.kt:65-117`, `MainActivity.kt` | `SharingRestoreTest`, `FlitRosterTest` | — | force-stop → relaunch restore walk |
| MIUI / dataSync cap | `FlitService.kt:161-184` | — | — | idle soak; cap behaviour is **not device-walked** |

---

## 8. Limitations and inconsistencies found while writing

These are recorded rather than smoothed over. None changes a shipped contract.

1. **Two-phone RFCOMM transfer is not verified.** The rig has one phone and the
   bench box has no Bluetooth adapter (`bluetoothctl list` empty). The carrier
   is code-complete and sim-proven; a real phone-to-phone socket path has not
   been walked. Likewise a genuine multi-member Circle mesh and two-phone panic
   hops.
2. **Wi‑Fi Aware is absent on the rig phone** (`pm has-feature
   android.software.wifi.aware` → false, 2026-11-21), so that ladder rung was
   parked on hardware grounds, not silently cut.
3. **Outbound phone→host Circle fan-out remains test-evidenced only** (the
   2026-11-25 close still lists it). Inbound sync, join, leave and governance are
   device-evidenced.
4. **The task brief's "8 MiB Circle file drop" is not supported by the source
   docs or code I read.** The documented device drop is 35 B
   (`open-check.txt`), the harness payload is 8192 B, and `8 MiB` is the turbo
   chunk hint. Treated as "not verified" here; no claim was built on it.
5. **Wording precision, not a contradiction:** [Circle](circle.md#status-semantics-app-as-built)
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
