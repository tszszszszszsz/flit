# Milestone A — carrier migration

> Part of the Flit transport design. [Main ladder and index](transport-bluetooth.md).

## Milestone A design — carrier migration

The ladder line: the sender re-probes in priority order and re-attaches a
running transfer onto whichever transport answers. Read against the code
as it stands (2026-11-15), the foundation is C's — a mid-flight death
leaves the owe on disk and the receiver's prefix on its disk — but the
recovery is *passive*: the sender aims every dispatch at exactly one route
(the live row's last-seen address), and after a failure it waits for the
next touch of that fingerprint. The touches exist (announce, keeper, the
manual prober, the retry chip), each with a gap: after a process restart
the last-known address is thrown away and discovery must re-find the peer
from zero; a stale typed row (manual@ rows never age out) suppresses the
sweep forever; a peer that moved addresses leaves every old voice knocking
on a dead door until the slow rounds re-find it.

- **One truth for reachability: the route that last answered.** A tiny
  persisted map `fingerprint → host:port(+tls)` in the roster, written on
  every touch that arrived over the wire (a register answer or an announce
  — both carry the peer's real identity and its serving port), and only
  when it changes (announce storms must not churn prefs). This is memory
  of the last good answer, not a discovery mechanism; it survives process
  death by living where the queue rows live. A row with no address-bearing
  socket (the direct-radio rows, port 0) remembers nothing.
- **Candidates, in priority order** (pure, tested): the live row's address
  first (the freshest wire answer), the remembered route second (it
  answered once), typed addresses last — probed for any owed fingerprint
  and accepted **only if the answering register's fingerprint matches the
  owed one**: a register answer carries the full identity, so the
  fingerprint is the trust anchor and a reassigned address can never hand
  the batch to a stranger.
- **The hunter: the sender looks for its owed peer instead of waiting.**
  One worker, ticked every couple of seconds, over the queue's owed
  fingerprints: a fingerprint mid-dispatch is skipped, a due one gets its
  candidates probed on a calm backoff (first beat ~2 s after the failure
  or start, then 5, 15, 30, then one beat per minute for as long as the
  row is owed — a handful of directed dials, the same budget the sweeper's
  knock uses, port-aware), and the **first fingerprint-matched answer goes
  through the ordinary `touchPeer` door** — which already auto-flushes
  owed rows, whose flush already probes `/partial` on its first attempt
  and resumes from the receiver's disk. The hunter owns no sending; C's
  machinery carries the transfer the moment a route answers.
- **Mid-transfer is the walk-out case.** "Re-attaches a running transfer"
  on this architecture means exactly this: the mid-body death leaves the
  owe, the hunter re-attaches within a backoff beat onto whichever
  candidate answers, `/partial` re-learns the receiver's prefix, and only
  the bytes that never landed fly again. The transfer's continuity is the
  owed row plus the receiver's disk, not a living socket — no socket
  hot-swap is designed, and none is needed for the phrase to be true.
- **Edges, listed:** the hunter probes only routes it already
  knows — a peer that moved to an address nobody remembers is caught by
  the discovery voices exactly as today, because the hunter never walks
  neighborhoods (that stays the sweep's restrained turf); a fingerprint
  this device never spoke with has no candidates and its queue row waits
  exactly as today; probing means a register, which puts us on the
  answering peer's roster — the same first-contact act any send performs;
  the battery cost is a few directed dials per backoff beat per owed
  fingerprint, capped by the schedule, and the row's own cancellation
  (user or empty) retires the hunt with it.

Tests: the pure seam only — candidate ordering and dedup, the backoff
schedule, the fingerprint-matched accept rule. The route store rides the
same prefs the queue rides (device truth); the loop, the probes, and the
resume are walked on hardware: a transfer killed mid-body, the sender
hunting, the route answering, and the landing resuming hash-exact from
the receiver's prefix — plus the restart case where the remembered route
answers within seconds of engine start, before any discovery round.

### A's first hardware walk caught a C-era defect (2026-11-15)

The design above was written against the code as it stood; the first walk
was supposed to be a formality — hunt answers remembered route on a cold
queue (it did, 15:10:10, `hunt: 192.168.100.12:53317 answered as Flit
(szszszs)` → flush), then `kill -9` the receiver mid-body at ~80% of an
800 MB camera shot and watch the row survive. The row did not survive.
The box kept a 671,678,464-byte `.part`, the phone's queue held nothing,
and the log's last word was a `FAIL … failed to connect … ECONNREFUSED`
with no `kept` after it. The queue-as-checkpoint guarantee held only on
the fresh-dispatch path. Three defects, one root:

1. **`dispatchTo` was fire-and-forget** (`scope.launch` inside a `fun`),
   so `flushQueueFor`'s `dropSent(batch, spools)` ran at send-**START**,
   not after the send: the row left the queue before the first byte flew,
   and its catch was dead code. The walk used a fresh dispatch (which
   owes-before-bytes, correct); D's mirror sends always succeeded
   instantly — that is why no earlier walk ever caught it.
2. **`sendFiles` returned transport deaths as reports.** A connect
   refused at the batch prepare threw, but a mid-body carrier death after
   exhausted attempts came back as a
   `SendReport` with a failure reason — and the engine's tail read any
   returned report as "the far side spoke", concluding a story that never
   happened.
3. **The report path cleaned the queue's spools unconditionally.** Row
   gone at send-start (1) + spools deleted on the failure-report (2+3)
   = an 800 MB batch with no row, no spools, and a receiver holding 80%
   of a file nobody would ever resume.

The fix is a contract — **`TransferUnreachable`
(extends IOException)**, thrown by the protocol layer whenever the
carrier dies (connect refused, reset mid-body, EOF, exhausted attempts,
re-prepare IO failures — and the batch prepare's first handshake), and
returned reports now mean exactly one thing: **the receiver spoke**
(sent / refused / declined — the story is over, the whole batch
concludes). On that contract:

- `dispatchTo` is `suspend`, runs inline, and returns the receiver's
  `SendReport?` (null only when no exchange could even begin). A throw
  means the carrier died with no answer at all.
- `flushQueueFor` dispatches **synchronously**: `dropSent` + spool
  cleanup run only after a report comes back; a throw leaves the row and
  its spools exactly as they were — the hunt re-enters the flush and the
  receiver's on-disk prefix carries C's resume.
- **One dispatch per peer, fresh or queued** (`dispatching` is
  claimed by `dispatchTo` itself): the owe row exists before bytes
  move, so an announce mid-fresh-send would otherwise re-enter the
  flush and double-fly the same batch. The hunter consults the same
  claim, so a mid-send peer is never probed either. (Found by the same
  post-walk review; the walk's own path was flush-guarded, which is
  why no earlier run had exercised it.)
- `send`/`sendShare` run the same suspend in `scope.launch` — fresh
  dispatches owe-before-bytes as before; their throw path keeps the
  checkpointed owe untouched (logged, story over).
- The mirror ledger's meaning sharpens with it: `dropSent` marks only on
  a concluded story — a carrier death never marks, so the scan re-offers
  the shot and the row flies again when the peer answers.

Sim-side proof (protocol module): `a carrier death throws instead of
reporting` — nobody listening → the prepare throws
`TransferUnreachable`; a receiver that takes half a body and then loses
the carrier for good → attempts exhaust, the sender **throws** (not
reports), and the receiver's part + token sidecar survive the throw
untouched — that on-disk prefix is what the next flush's resume rides.
The receiver-alive half (death → sweep → hash-exact resume) stays
`deathRun`'s, still green.

