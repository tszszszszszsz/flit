# Flit architecture — the engineering map

Module map, transport seam, state ownership, invariants, lifecycle and concurrency. Milestone status and focused evidence are in the [design index](transport-bluetooth.md).

## 1. What Flit is

Flit is a clean-room LocalSend v2.2 file-transfer implementation with a native Android app and a Linux CLI built from the same protocol engine. It has no accounts, no cloud, no ads, no telemetry and no SDK that phones home. Discovery, transfer, resume, relay, group coordination and panic alerts all run between the devices involved.

Three hard boundaries shape every design decision:

1. **The protocol module stays JVM-pure.** No Android imports in `protocol/`. This is what makes the entire wire engine testable on the JVM and reusable by the CLI.
2. **The app owns the Android surface** — UI, permissions, radios, foreground service, storage. It never forks the protocol.
3. **v2.2 is untouched.** Every Flit-only feature is additive: new routes, new headers, new event types. A stock LocalSend v2.2 peer sees the same info/register/prepare/upload/cancel routes it always saw.

## 2. Module map

```
protocol/   JVM-pure wire engine
  client/     PeerHttpClient (raw HTTP/1.1 over Link), ProtocolClient (v2.2 operations),
              Link/LinkProvider/TcpLinkProvider/TlsLink, PathOutgoing, SendReport.
  server/     ServerCore (transport-neutral routes), ProtocolServer (TCP/Netty binder),
              LinkServer (hand-parsed HTTP/1.1 over any Link), HttpExchange, PartRegistry.
  transport/  In-memory pipe pair for parity tests and the bt-sim rig.
  relay/      RelayHello, RelayCore (pure pairing), RelayLinkProvider, RelaySubscriber.
  panic/      PanicCore — signed, replay-windowed alert canonicalisation.
  circle/     CircleCore — signed event log, ordering, dedup, tallies, shape gates.
  tls/        StreamTls (SSLEngine over any two streams), TlsIdentity, bounded pin cache.
  discovery/  UdpDiscovery — multicast/unicast announce and register probing.
  model/      Wire DTOs, WireJson, Relay/Panic/Circle request-response envelopes.

app/        Android shell
  FlitEngine      single in-process coordinator: server + clients + queue + hunter +
                  mirror + relay + Circle + panic + sharing-desired restore.
  FlitService     foreground service wrapper; FGS types/timeouts and process liveness.
  MainActivity    Compose UI, intents, file open/chooser, dialog state, permission asks.
  FlitRoster      persistent routing/trust/queue/mirror/Circle-local state.
  FlitRoute       pure candidate ordering and relay address parsing.
  FlitBluetooth   RFCOMM dialer + accept loop on the Link seam.
  FlitBle*        pure announce wire + duty-cycle stance + Android BLE radio adapter.
  FlitSpool       grant-proof cache copies for share-sheet intake.
  CircleUi        M3 activity feed; FileCard, PollCard, member sheet; tallies memoized.
  FlitNotify      transfer and panic notifications.

harness/    flit-cli: the same protocol engine as a Linux tool and interop rig.
  serve/send/discover/info/relay/panic/parse-qr/circle/bt-sim.
```

## 3. The transport seam: one brain, many pipes

Every carrier becomes a `Link`: a stable pair of `InputStream`/`OutputStream` plus an optional `origin` label ([`Link.kt`](../protocol/src/main/kotlin/app/flit/share/protocol/client/Link.kt)).

- `TcpLinkProvider` — ordinary TCP, TLS via JDK socket.
- `FlitBluetooth.LinkProvider` — RFCOMM dial; `origin` is the peer MAC.
- `RelayLinkProvider` — dials a blind pairer, writes a one-line hello, then TLS inside the pipe.
- `SimLinkProvider` — in-memory duplex pipe used by the JVM parity suites and `bt-sim`.

`PeerHttpClient` has exactly one dial site. It writes hand-built HTTP/1.1 with `Connection: close`, then parses the response. `ProtocolClient` layers v2.2 operations on top. Nothing above the seam knows whether bytes crossed Wi‑Fi, Bluetooth, a relay or a test pipe.

On the receive side, `ServerCore` owns route semantics. `ProtocolServer` serves it over a TCP listener; `LinkServer` serves the *same* core over any `Link` (one exchange per connection, streams taken once). `ProtocolServer.serve(link)` is the door the BLE/RFCOMM accept loop and the relay subscriber walk through.

**Invariant:** adding a carrier must not add a protocol route. If it does, the design is wrong.

`origin` is deliberately transport-specific: an IP on TCP, a MAC on RFCOMM, empty over a relay. It drives session binding and the interrupted-parts registry. This is why a live Wi‑Fi→Bluetooth switch cannot resume the same part: the receiver's `partsBase()` rejects a different `senderIp` and the sender falls back to a clean full resend. A restarted receiver can still resume its own sidecar-backed part because repopulated entries have an empty `senderIp` ([`FAILURE-MODES.md`](FAILURE-MODES.md)).

## 4. Protocol operations and additive extensions

### v2.2 core
`/info`, `/register`, `/prepare-upload`, `/upload`, `/cancel` — unchanged, with the same response shapes and status behaviour. `localsend-cli` interoperability and the exact-key-set DTO tests guard this.

### Flit extensions
| Extension | Route / seam | Purpose |
|---|---|---|
| Resume | `/partial` + `X-Flit-Resume`, `X-Flit-Base`, `Content-Range` | Salvage a prefix after a carrier death; turbo chunks seed the ledger from the advertised base. |
| Refusal marker | `X-Flit-Refusal: declined` on a 403 | Distinguish a human/peer refusal (concluded) from a carrier death (throw, keep the row). |
| Relay | `/relay/join`, `/relay/sync` | Blind pairing by fingerprint; TLS and protocol inside the pipe. |
| Panic | `/panic` | Signed alert with hop count and original author; never auto-acts on a recipient. |
| Circle | `/circle/join`, `/circle/sync` | Signed group event log, host-signed answers, rekey on departure. |

The exact field sets, canonical byte rules and compatibility contract live in [`CONTRACTS.md`](CONTRACTS.md).

**Invariant:** a Flit-only header or route must be ignorable by a v2.2 peer. If a v2.2 client receives a Flit-only response, it should see a normal 404 or an unknown field, never a broken v2.2 exchange.

## 5. State ownership

State is deliberately split. Confusing these stores is the source of most historical bugs.

| State | Lives in | Persisted? | Owner |
|---|---|---|---|
| Engine `running/starting/error`, port, identity | `FlitEngine._state` | No — process-local | engine |
| "Sharing desired" intent | `FlitRoster` prefs | **Yes** | NAV writes on explicit start/stop; service/engine never clear on internal churn |
| Peers roster, trust, routes, manual addresses, queue rows, mirror ledger | `FlitRoster` prefs | Yes | engine |
| Interrupted parts + `.part.token`/`.pieces` sidecars | receiver filesystem | Yes | `ServerCore` / start sweep |
| Received ledger (last 20) + digest cache | `FlitEngine` + prefs JSON | Ledger yes, digest no (rebuilt at boot) | engine |
| Circle id/key/roster/events/rekey generation | `FlitCircleStore` prefs | Yes | host + members |
| Circle file deliveries (spool path, per-member status) | `CircleState.deliveries` | Yes — local only, never gossiped | sender |
| Panic banner events + signed alerts | `FlitEngine` in-memory | No | engine |
| Circle landed-SHA set | `_circleLandedShas` | No — rebuilt at boot | engine |
| Queue spools / circle spools | app cache | No — checkpointed by row/delivery record | engine |

**Invariant:** a persisted row is a promise. `dropSent` removes exactly the tokens that reached the wire; a partial spool failure leaves the rest owed. A Circle delivery record keeps the spool until every current recipient concludes.

## 6. File send and receive

### Outbound
1. Intake (share sheet, picker, mirror, Circle drop) is spooled into app cache before any selection UI, so persistable grants cannot expire mid-send.
2. A fresh dispatch writes a `QueuedSend` row **before bytes move**. The row *is* the checkpoint.
3. `dispatchTo` claims one-dispatch-per-peer, chooses a carrier from the known-device policy, and calls `sendFiles`.
4. A returned `SendReport` means the receiver spoke: the story is over, the row's concluded tokens leave, spools are cleaned. A throw (`TransferUnreachable`/IO) means the carrier died: row and spools stay.
5. The hunter probes owed fingerprints on a calm backoff and re-attaches; the receiver's `/partial` answer drives the resume base.

### Inbound
1. `ServerCore` claims a session, writes `.part` + `.part.token` **before the first body byte**, then streams into the temp (or a caller-provided `IncomingSink` for direct MediaStore/SAF).
2. A short body is a genuine prefix and is kept; a full-length checksum mismatch is dropped with its sidecars. A cancel/throw keeps a salvageable prefix.
3. On success the temp moves to its final name, the token sidecar is deleted, and the received ledger gains a row.
4. After copying to the public Downloads bucket or a SAF tree, the ledger row is rewritten to the surviving URI; the deleted staging path is never handed to a viewer.

Turbo (`multi = true`) splits the remaining range across up to four sockets, each declaring `X-Flit-Base`; the server seeds its `ChunkTracker` with that base and refuses a view that starts past it. Progress is summed across streams, not four absolute offsets overwriting one slot.

## 7. Circle — group coordination

A Circle is founded from a QR that carries host fingerprint, circle id and invite token, but **no group key**. The joiner receives the key in a host-signed response over TLS; a rogue relay or on-path peer cannot substitute it. Membership changes (leave/admin kick/successful vote-kick) rotate the key and are refused to the departed fingerprint. Status is local-only and never gossiped.

```
founder: startCircle() → QR v3 → onCircleRequest(JOIN) → key + signed answer
member:  join(code) → verify host signature → fold seed events → persist
sync:    full bounded log pushed; receiver verifies cert-in-event + dedups + orders
file:    signed {name,size,sha256} announcement → sender-push sendFiles per member
poll:    signed vote events; latest per member wins; changing answer appends a new vote
kick:    OWNER/ADMIN direct, member opens vote-kick; majority of current members
```

Host authority lives in `FlitCircleHost`; the app host and the box CLI host share the same semantics. Replay protection (`CircleCore.accept`) and log-id dedup are separate layers. Rekey events are host-authority only; a member cannot rotate the group key.

## 8. Panic, relay and the offline path

**Panic** is an informational signed alert. A forward re-signs the original alert, preserving id, text and `originFp`; the recipient gates trust on the original author and shows "via <forwarder>". There is no remote wipe, no mute, no auto-action.

**Relay** is a blind pairer. It sees a hello (request/subscribe by fingerprint), pairs tickets, and copies bytes. The shipped CLI logs only timestamps and opaque ticket IDs — no fingerprint, no byte counter. Per-device `relayFor` is the explicit switch; the direct route always wins.

**Offline bundle** is not implemented; `.flitpack` is a parked option, not a feature.

## 9. Lifecycle, FGS and sharing restore

`FlitEngine.start()` is asynchronous and seq-guarded: a stop/restart invalidates every worker. `FlitService` wraps it as a foreground service. On API 34+ `connectedDevice` is requested only when the Bluetooth option is on and a nearby-device permission is granted; a `SecurityException` falls back to `dataSync`. A dataSync timeout stops the wrapper but leaves the engine alive. A genuine wrapper stop clears the "sharing desired" flag; internal rebinds (alias/relay/Bluetooth) never do. A cold launch calls `restoreSharingIfDesired()` silently.

MIUI can still kill the process or refuse autostart; that cannot be fixed in code. We restore on next open and say so.

## 10. Concurrency model

- Engine scope: `SupervisorJob + Dispatchers.IO`; worker loops are seq-guarded.
- One dispatch per peer via a `dispatching` set, claimed before spooling.
- Circle file delivery serialises per SHA with a `Mutex` map, with capped exponential backoff.
- Relay/Circle dials use shared bounded concurrency (`Semaphore(4)`) in the sync worker.
- Store writes are atomic under one lock (`FlitCircleStore.update`); callers never do load→modify→save outside it.
- Compose state is content-diffed before publication so a no-op worker tick does not invalidate the UI.

## 11. UI and surface contract

The original Phase B contract said the UI would never learn Bluetooth exists. The current surface adds an explicit **Transfer over Bluetooth** option, Known-devices route policies, QR-only mode and a Circle feed. It still never shows protocol mechanics, TLS state, relay tickets or carrier handshakes; it reports observable outcomes: sent, waiting, refused, landed here, not here yet.

Circle is an M3 activity feed with WhatsApp-like structure (chronological rows, member bar, pinned composer) but no emoji, decorative backgrounds or chat-bubble tails. Poll and kick tallies are computed once per snapshot, not per card.

## 12. Evidence and limitations

- Full rig command and counts: [`TESTING.md`](TESTING.md).
- Device walks (resume, relay, Circle file, open-file): [`TESTING.md`](TESTING.md) and the focused docs.
- Threat model and what is *not* guaranteed: [`SECURITY.md`](SECURITY.md).
- Failure/conclusion matrix: [`FAILURE-MODES.md`](FAILURE-MODES.md).
- Measured throughput and energy design: [`PERFORMANCE.md`](PERFORMANCE.md).
- Product choices, scope cuts and postmortems: [`PRODUCT-DECISIONS.md`](PRODUCT-DECISIONS.md).

Two-phone RFCOMM, multi-member mesh, Wi‑Fi Aware (feature absent on the rig) and a GUI desktop app are not verified or not built. They are listed, never implied.
