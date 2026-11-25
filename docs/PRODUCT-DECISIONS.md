# Product decisions and postmortems

Design rationale, scope cuts and postmortems. Status and focused evidence are in the [design index](transport-bluetooth.md); the system map is in [ARCHITECTURE.md](ARCHITECTURE.md).

## 1. The product constraint: non-profit by construction

Flit has no accounts, no cloud, no ads, no telemetry and no SDK that phones home. This is not a settings toggle or a business-model slide; it is an architectural constraint:

- Discovery is local (UDP/BLE); there is no directory service.
- Trust is a fingerprint pinned by QR or by wire identity, never a server account.
- Relays are user-run and blind. They pair tickets and copy bytes; they do not store files or maintain a social graph.
- Circle state lives on the devices in the circle. Status is local-only and never gossiped.
- The CLI is a local tool, not a phone-home daemon.

Tradeoffs: no push notifications when friends are offline, no cross-device sync of preferences, no central revocation, no analytics dashboard, no "free tier" upgrade path.

## 2. Engineering standard

The standard is quality over quantity, expressed as a set of rules:

1. **Every documented claim needs a code path.** If docs say "bytes land", a harness test or device walk must show bytes landing.
2. **Every carrier claim needs evidence.** Sim parity and a two-phone walk are labelled separately.
3. **A feature is finished when its failure story is finished.** A file drop that only announces is not finished; a relay that only pairs but cannot recover from death is not finished.
4. **No silent degradation.** If FGS promotion is refused, MIUI blocks autostart, or a relay is unreachable, the log/UI says so.
5. **No automatic action on another device.** Panic, kick and relay pairing all require explicit user choices; a received alert never wipes, mutes or forwards anything by itself.

Existing surfaces get fixed and re-verified rather than new ones added. The work after Circle and after the Open-file crash closed gaps, not feature lists.

## 3. Surface contract — and its deliberate revision

The original Phase B design had a hard rule: "the UI never learns Bluetooth exists; zero new settings, no new vocabulary." That held while Bluetooth was only a fallback carrier. It was revised on 2026-11-24 when Bluetooth became a reachability carrier for peer pairs with no shared access point:

- **Transfer over Bluetooth** (off by default) is now a visible option.
- **Known devices** carries per-device route policy: Auto / Wi‑Fi only / Bluetooth only / Relay only.
- **QR-only mode** makes the device invisible except to QR-paired peers.
- **Relay** is a typed address with a per-device opt-in switch, off by default.

Everything else still holds: no protocol mechanics in the UI, no TLS state, no relay tickets, no carrier handshakes. The UI reports outcomes in human terms: sent, waiting, refused, landed here, not here yet. The original contract is kept in `docs/bluetooth-carrier.md` marked as superseded rather than rewritten as if it had always been false.

The revision is recorded in the docs and the earlier wording is kept as history.

## 4. Why each big feature exists

| Feature | Why it earns its place |
|---|---|
| Subnet sweep (1) | Multicast-blocked APs are common; a calm connect-based walk finds peers that announces cannot. |
| CLI (2) | A Linux peer that uses the same engine, plus the rig that proves interop and runs relays/bots. |
| Process-death checkpoints (C) | Phones kill apps. The queue row written before bytes is the only checkpoint. |
| Album mirror (D) | Camera shots are the real high-volume workflow; mirroring through the queue reuses every guarantee. |
| Carrier migration (A) | Wi‑Fi drops mid-transfer; a hunter and a receiver-side prefix must carry the story over. |
| Relay (E) | Some peer pairs can never reach each other directly; a user-run blind pairer is the last route. |
| Bluetooth carrier | No AP at all; BLE discovery plus RFCOMM bytes keep the app useful. |
| Circle | People who exchange files also need to coordinate: messages, decisions, file drops, inside the same trust graph. |
| Panic | A one-way, explicit informational alert for situations where coordination matters more than transfer. |
| QR-only + known devices | Owner control: the device decides who can see and send to it, not the network. |

## 5. Scope cuts, named

- **No GUI desktop app.** `flit-cli` is the Linux companion. A GUI would be a new product surface, not a quality fix.
- **No Wi‑Fi Aware carrier.** The rig phone reports `pm has-feature android.software.wifi.aware = false`. Building against absent hardware would be theatre.
- **No multi-hop mesh.** A three-or-more-device relay/mesh walk needs hardware not on this rig. The seams exist; the walk does not.
- **No bonded simultaneous Wi‑Fi+Bluetooth transfer.** Both are first-class routes chosen per peer; aggregation is a later, device-gated change.
- **No offline `.flitpack` bundle.** Discussed and designed in principle; not built, not claimed.
- **No backup restoration of sharing.** A ROM kill is followed by silent restore on next open, not by a guaranteed background restart. MIUI autostart is user-controlled.
- **No encrypted Circle log.** Events are signed and TLS-protected in transit, but stored bodies are plaintext JSON. The docs say so; do not put secrets in Circle messages.

## 6. Postmortems

### Turbo never worked
The client sent RFC `Content-Range: bytes 0-…/total`; the server parsed only `bytes=…`. Every chunk was answered 400 and the lane silently fell back to single-stream. Worse, a failed chunk claim left an empty tracker and the plain retry trimmed the kept part to zero, so a 711 MB tail was written as a fresh file. Fix: accept both header forms, never let a claim touch the part before the attach decides, seed the ledger from the advertised base, refuse a disagreeing view with 409. Pinned by `ResumeAndTurboTest`.

The mismatch is pinned by `ResumeAndTurboTest`, which exercises the multi-stream path rather than the inferred outcome.

### Open-file killed the app
The receive ledger could point at a staging file that was deleted after copying to Downloads. `digestOf()` read that path outside any guard; `circleReceivedFor()` re-hashed every ledger row on tap; the exception escaped into the main-thread lifecycle job → FATAL → MIUI refused the sticky FGS restart → sharing off on next launch. The FileProvider had no `Download/Flit/` root, which was a second latent crash. Fix: guarded digest reads, ledger rewritten to the surviving URI after copy, FileProvider root added, landed-SHA cache rebuilt at boot, dead Open buttons removed, persisted sharing-desired restore.

The single "app closed" symptom covered three separate failures; the dropbox trace identified the stale-path read as the trigger.

### Partial spool failures vanished
`mapNotNull` dropped any queue token that could not be opened; the successful send then removed the whole row and all grants. Fix: pair tokens with spools by index, drop only concluded tokens, leave unopenable ones owed.

Pairing tokens with spools by index keeps every unopenable token owed.

### "Refused" was treated as a carrier death
A prepare-level Decline answered a plain 403; the client turned it into `TransferException`, the engine kept the row and hunted a peer that had actively refused. Fix: a Flit-only refusal header maps 403 to a concluded report; 409/5xx still throw.

A bare 403 covered both a peer refusal and an unreachable peer; the Flit refusal header separates the two.

### Aborted receives reported success
Four abort/catch paths in `ServerCore` ended sessions with `cancelled = false`, so a killed transfer produced a success notification. Fix: aborted endings are `cancelled = true`.

Aborted endings are now `cancelled = true`, so a killed transfer cannot produce a success notification.

### Relay switch was display-only
The Known-devices "use relay for this device" switch had no production reader, and Circle dialing tried the relay before a live direct route. Fix: `relayFor` now gates direct dispatch, Circle dialing and panic fan-out; direct Wi‑Fi always wins; relay is strictly last.

`relayFor` now gates direct dispatch, Circle dialing and panic fan-out.

### File drops were announcements only
The event body was correct, the card rendered, but no bytes were ever pushed. Fix: sender-push through `sendFiles` per member, local status, retry, spool cleanup, device-proven hash-exact landing.

File drops push bytes through `sendFiles`; the signed announcement alone did not deliver anything.

## 7. Roadmap status

| Item | Status |
|---|---|
| 1 subnet sweep, 2 CLI, C checkpoints, D mirror, A migration, E relay | done, with device evidence where radios/networks were involved |
| Bluetooth carrier | built and smoke-tested; two-phone RFCOMM transfer not hardware-verified |
| Circle v1 | built: groups, QR founding, signed log, polls, file drops, governance, host/rekey; two-node device walks |
| Open-file / sharing restore | fixed and device-verified on the final build |
| Wi‑Fi Aware | parked: hardware feature absent |
| Multi-hop mesh, bonded carriers, GUI desktop, offline bundle | parked or not built, named above |

## 8. Working practice

- Large changes are specified before implementation: ownership, APIs, invariants and success criteria.
- Changes stay on disjoint surfaces; reviews cite `file:line`; one integration pass compiles and walks the device.
- One commit follows a green rig and, where warranted, a device walk.
- Evidence is labelled: code-traced, pure-test, harness/sim, device. The weakest label present wins in the docs.
