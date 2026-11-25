# Milestone E — self-hosted relay

> Part of the Flit transport design. [Main ladder and index](transport-bluetooth.md).

## Milestone E design — self-hosted relay (delivered 2026-11-24)

The problem E answers: two peers that can establish *some* outbound
reachability but never each other — different NATs, multicast-blocked
APs, no port forwarding, a phone on cellular. Everything C and A built
survives that world except one sentence: the candidate list runs out of
addresses that answer. E adds one more candidate: a relay both peers can
dial out to, run by whoever wants it (`flit-cli relay`), off by default.

**The shape: a blind pairer.** The relay matches a request for
fingerprint B with B's subscription using the one-line hello, then copies
opaque bytes both ways. It never parses the protocol, never stores a file,
never terminates TLS. The shipped CLI logs only a timestamp and opaque pairing
ticket ids (`pair c3 + c4`, `pair c3 + c4 closed`) — no fingerprint, no byte
counter. The sender dials the relay and opens with a fixed
preamble (magic, target fingerprint, optional shared token); the
receiver, while the engine runs, holds a long-lived subscription to the
same relay ("I am B; pair me"). The relay matches, says OK to both, and
then copies bytes both ways until either side closes. Inside that pipe
the peers run the *unmodified* protocol — TLS with their own identities,
register, prepare, upload, `/partial` probes — so the fingerprint proof,
the trust checks and every resume behave exactly as over TCP. A
malicious relay can refuse, delay or drop a pair; it cannot forge a
file, a fingerprint or a finished hash.

**The seams it touches:**

- **Protocol module gains a pure relay core** (`relay/RelayCore.kt`,
  JVM-only): pairing state machine (register / request / pair / close,
  token gate, pair isolation, both-sides-live-only), unit-tested without
  sockets. The protocol module staying JVM-pure is a hard rule; a
  testing story that needs no Android is exactly why the core lives
  there.
- **`LinkProvider.connect` gains an optional target hint** (default
  null; TCP and sim providers ignore it). A relay route is a dial whose
  `host:port` is the relay and whose hint names the peer; only the
  relay-aware provider reads it. This avoids minting a
  per-send client per route.
- **Protocol module gains two thin pieces alongside the core:** the relay
  dialer / link provider (`RelayLinkProvider`: TCP to relay, preamble, then
  TLS inside the pipe) and the subscriber (`RelaySubscriber`: dial relay as
  self, hand each paired pipe to the server's external door —
  `ProtocolServer.serve`, which rides the exact same `ServerCore` and route
  semantics as the TCP listener, landed with E and shared with B; reconnect
  with the same calm backoff as the keeper). Both stay JVM-pure so they are
  tested without Android. No new wire formats — the preamble is the relay
  core's, already tested. The app module adds only the opt-in preference and
  the engine's start/stop wiring (see Engine/roster below).
- **Harness gains `flit-cli relay --port N [--token T]`** — a thin CLI
  wrapper over the same `RelayCore`, so the thing users self-host is the
  thing the tests exercise.
- **Engine/roster:** one relay address + optional token in Options (off
  by default; empty = E is invisible). When set, the route candidate
  list gains a final relay candidate for any owed fingerprint — tried
  last, after live row, remembered route and manual addresses, so a
  direct answer always wins. The subscription starts with the engine and
  stops with it; nothing new is persisted per peer.

**The surface contract holds.** "Relay" is a preference the user
deliberately configures, like the manual-address field — not a carrier
mechanic leaking into the UI. Sends look and behave exactly like any
other send (progress, queue, hunt, cancel); a relay exchange that dies
mid-body throws `TransferUnreachable` like any carrier, the row stays
owed, and the retry resumes from the receiver's prefix through the
relay — no new states, no new copy.

**Edges, listed:** both peers must be live (no store-and-forward;
an offline receiver's files wait on the sender's queue exactly as today);
the relay is blind: the current CLI logs only timestamps and opaque
pairing ticket ids, not fingerprints or byte counts (content, aliases and
names ride inside TLS anyway); the relay is a single point of failure the
user owns;
there is no relay *discovery* (the address is typed once per device, by
design — a discovery protocol for relays would be a public directory and
that is a different product); throughput is whatever the relay's uplink
is, which is why E stays opt-in and last.

**Test story:** `RelayCore` pairing unit tests (match, reject, wrong
token, both-sides-live-only, isolation between pairs, clean close); one
protocol parity send through the core over loopback pipes — including a
death + resume, since the relay must be transparent to C's machinery;
then the real walk: box runs `flit-cli relay`, phone and second peer
both subscribed, direct route removed, send lands hash-exact through the
relay, and a mid-body relay kill exercises the throw/keep/resume loop.

**Scope cuts, named:** no relay-side storage or queueing, no multi-relay
selection or failover, no relay directory/discovery, no per-peer relay
routing (one relay per device), no auth beyond the optional shared
token. Each is a later ladder item only if a real use demands it.

**Status (2026-11-24):** the app surface is in. `RelayLinkProvider`
(the sender's dial: preamble, then TLS inside the pipe) and
`RelaySubscriber` (the receiver's parked subscription; its socket hook
lets engine shutdown unblock a blocking read) live in the JVM-pure
protocol module alongside the core. `LinkProvider.connect` gained an
optional target hint, threaded through `SendTarget`, `PeerHttpClient`
and every `ProtocolClient` exchange — direct transports ignore it. The
engine dials through the relay only for peers whose last answer came
through one, starts/stops the subscription with the engine, and tries a
relay register only after every direct hunt candidate has failed. The
Options field is empty by default, so E stays invisible until a user
types an address. The harness end-to-end test now runs those production
endpoints with TLS inside the pipe, hash-exact.

**Status (2026-11-18):** the pure core landed exactly as specified —
`RelayHello` (the one-line preamble, strict parse, bounded reads) and
`RelayCore` (FIFO pairing per fingerprint, same-mode never pairs, ghost
tickets retired by `withdraw`, constant-time token gate), 11 tests in
`RelayCoreTest`. The harness surface landed too: `flit-cli relay
--port P [--token T] [--park-timeout S]` runs the accept/pair/blind-copy
loop over the same core, with 5 harness tests proving a real 3 MB
protocol send lands hash-exact through the pairer, a wrong token is
refused on the wire, an unpaired subscription times out cleanly, and a
dead relay dial surfaces as `TransferUnreachable` (never a report).

**Status (2026-11-24):** the app surface and the hardware walk are done.

