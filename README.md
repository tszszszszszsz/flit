# Flit (codename)

**Send any file to nearby v2.2 devices — over Wi‑Fi when there is one, Bluetooth when
there isn't (opt-in). Resumable within a carrier, no accounts, no cloud.**

A no-fluff, native-Kotlin file transfer app that speaks the
[LocalSend protocol v2.2](https://github.com/localsend/protocol) so it interoperates with every
existing LocalSend install (Windows/macOS/Linux/iOS + their CLI), and improves on it with:

> **Non-profit, by construction.** Flit has **no accounts, no cloud, no ads, no telemetry,
> and no SDKs that phone home**. There is no server to sign up with, nothing to pay for,
> and no data collected about you — devices talk directly to each other, on your network
> or over Bluetooth. This is a hard constraint on every feature, not a setting.

| # | Capability | Status |
|---|---|---|
| 1 | Chunked **resumable** transfers (their issue #2127, open 20+ months) | **shipped** (Flit↔Flit extension: interrupted uploads keep their prefix on disk and resume after a reconnect, verified hash-exact in both directions) |
| 2 | **Rescue mode**: direct transport when no network path exists | **shipped — Bluetooth direct-discovery (row 9)**; joining a phone's own hotspot works by-address; the multicast-blocking-network case now **sweeps**: an empty roster on Wi-Fi knocks on the whole subnet for a running v2.2 server (throttled, bounded, hidden-mode-respecting) |
| 3 | **Crush & send**: text, notes, logs, JSON and code are gzip'd on the wire, Flit receivers unpack automatically (any other client still gets a valid `.gflit` gzip archive it can open) | **shipped** (free, Options → "Crush files when sending") |
| 4 | Native speed: instant cold start, share-sheet-first UX | **shipped** (share intake spool, send-later queue, trusted peers + per-peer folder, progress notifications, QS tile) |
| 5 | **Direct-to-storage receive**: on API 29+ for non-crushed wires, incoming files stream straight into a pending `Downloads/Flit/` row or a chosen SAF folder — no temp file, no second copy (old devices, crushed wires or a storage refusal take the classic two-step path) — with parallel uploads (4 streams) | **shipped** |
| 6 | **Real on-device options**: Material You theme (dynamic color, light/dark/auto), rename from the app, trusted-senders-only gate, hidden mode (listen-only discovery), start-on-boot, default receive folder, battery-rationing opt-out helper | **shipped** |
| 7 | **Receive ledger you can act on**: tap a received file to open it, one-tap re-share, clear anything | **shipped** |
| 8 | **In-app note composer**: send a plain-text note to a peer straight from the app — no share sheet detour | **shipped** |
| 9 | **Bluetooth direct-discovery**: when no Wi‑Fi network exists between you (mobile-data-only phones, dead access points), the roster just keeps working — the engine flips LE discovery on at the exact no-AP moment and the same v2.2 exchange rides a Bluetooth pipe; asks are the OS's own dialogs, once per device | **shipped** (engine + asks walked on device; full two-phone transfer awaits a second phone — see the transport doc's walkthrough record) |

Working name. Rename is a 15-minute chore before Play listing (no trademark reuse of "LocalSend").

## Current capabilities

- **No Wi‑Fi network? The roster keeps working.** The engine flips Bluetooth LE
  discovery on at the exact no-AP moment (mobile data only, dead access point) and the
  same v2.2 exchange rides the Bluetooth pipe; peers arrive in the same list. The radio
  stays silent until the user opts in with **Transfer over Bluetooth**; the only prompts
  are the OS's own dialogs, each asked once per device. Direct transport is exercised end
  to end in the harness (`bt-sim`) against the production server and client, and the
  arbiter's no-AP / hotspot / radio-off states are walked on hardware. Design, walks and
  limitations live in [the ladder and design index](docs/transport-bluetooth.md),
  [the Bluetooth carrier](docs/bluetooth-carrier.md) and
  [QR-only / panic](docs/qr-only-and-panic.md); a two-phone radio transfer remains the
  listed pending item.
- Share-sheet send: streams are spooled into app cache the moment the intent lands, so grants can
  never expire and every shared file can queue for later (`Send later` row; auto-dispatches when
  the peer reappears). The queue's promise is kept: a second intake joins the waiting batch
  instead of dying as "send failed", a flush broken by the peer leaving keeps its row, and
  delivered batches release their storage grants — everything keeps its sentence. (Walked
  on device against a flit-cli server: kept → merged → auto-sent 2/2 → cancel, all live.
  The grant-release path is **code-verified**, not device-walked: picker files are spooled
  at intake, so that walk had cache-path tokens and no SAF grants in play.)
- Multi-file sends stream concurrently (4 parallel), and a dropped connection mid-file is
  recovered automatically **within the same carrier**: the receiver keeps the received
  prefix, the sender probes `GET /api/flit/v1/partial`, and the transfer resumes from there
  (Flit↔Flit only; plain v2.2 peers, or a Wi‑Fi↔Bluetooth switch whose origin no longer
  matches the kept part, get a clean full resend — the final sha256 gate verifies every
  landing either way).
- Receive: progress notification with live speed; completed transfers land in
  `Downloads/Flit/`, in your default folder (Options), or straight into a
  per-peer folder for trusted peers — no double write either way. Received files
  open with one tap and re-share from the ledger.
- Dock-first redesigned UI (Material You expressive cards, floating bottom dock, animated
  screen transitions): live transfer telemetry — a "↑ Sending 48.0 MiB/s" hero strip and a
  progress ring around the dock's Send button while bytes are in flight. Tabs double as Home
  buttons, manual by-address peers persist across restarts, and files picked straight from
  the dock's Send stay ready under the "Ready to send" banner.
- **Circle — accountless group coordination:** a temporary, fingerprint-pinned group with one
  signed local event log (messages, file drops and re-votable polls) — authenticated by
  fingerprint-pinned signatures and TLS-protected in transit, **not encrypted at rest** — founded
  by a QR invite and synced over the same Wi‑Fi/Bluetooth/relay carriers. A file drop is
  a signed `{name,size,sha256}` announcement plus a real per-member `sendFiles`
  delivery from the sender's local spool (not the send-later queue); bytes land hash-exact
  in History, delivery status is tracked only on the sender's own device (never gossiped),
  and a device that joins later sees the card but not the old bytes. The founder is
  owner and can appoint admins; leave or kick rotates the key and the host refuses the
  departed device's later sync. No accounts, no server, no remote actions.
  The 2026-11-26 device walk covered a three-identity Circle — one phone host and
  two CLI bots — with join, post, poll/vote, a hash-exact file landing and
  kick/rekey. A larger mesh is the unverified case; phone→host fan-out stays
  test-evidenced only — see [the Circle design and evidence](docs/circle.md).
- **"Open file" is a detour, not an exit:** a Circle file card offers Open only
  when a local copy with that SHA-256 is present; the chooser carries a valid
  FileProvider grant, and returning from an external viewer leaves the feed and
  sharing intact. Sharing "desired" is persisted locally, so a cold launch
  restores it silently; only an explicit Stop turns it off. MIUI can still kill
  the process — restoration happens on the next open, not by MIUI restarting
  us. Root cause, per-layer fix, and verification status live in
  [Open file, sharing restoration and the MIUI limit](docs/open-file-and-sharing-restore.md).
- Quick Settings tile + foreground service. Note: Android 15 rations `dataSync` background hours;
  when the budget is spent Flit runs engine-only while the app is open instead of crashing.
- Phone QR parcel screen: a signed air-gap parcel can be shown on screen as a sequence of QR
  frames and read back with the camera (or pasted as frame text when camera access is denied).
  The app keeps one frame per id and sequence, folds the frames back through the codec into one
  verified payload, and hands the bytes to the existing receive path. No file name travels in a
  frame; the save name is local. This is code and JVM-tested only, with no camera or device walk
  claimed.
- **Signed Circle admission and officer-only rekey:** a JOIN carries a short-lived
  signed proof bound to the host, circle, invite and the joining fingerprint, so an
  invited party cannot impersonate another device at admission; a rekey folds only
  from a current owner or admin, never from a departing officer. No new route or
  wire field, and no device walk is claimed.
- **Signed delivery receipts:** a Circle member can acknowledge a named file
  drop with a signed `receipt` event whose body is the closed set `{dropId, sha256, keyId}` —
  the acknowledged drop's id, the landed plaintext digest, and the per-drop key identifier
  (provenance only, with no second time field or local status). The protocol module gates that
  body and rolls up the current members who signed a well-formed receipt for one drop, keeping
  at most one per member by canonical order. A receipt proves that the bytes landed hash-exact
  on the signing member's own side; it says nothing about delivery to other members and does
  not replace the sender-local delivery record. It rides the existing signed Circle log with no
  new route, header or DTO field. The 2026-12-14 layer wired it into the app and `flit-cli`:
  the engine mints and tallies a receipt on a hash-exact landing, and the CLI exposes
  `circle receipt`, both on that same signed log. No device walk is claimed.
- **Shared Circle pad (protocol layer):** members can keep one shared, line-oriented text pad as
  signed `pad` events in the Circle's existing event log — each event is a `put` or `del` of a line
  key with a closed body shape and a strict gate. The protocol module folds those events into one
  deterministic view: per key the operation with the greatest `(lamport, authorFp, id)` wins, a
  `put` sets the text and a `del` tombstones the key, so every member resolves the same log to the
  same view regardless of delivery order, and views derived from separate event sets merge to that
  same result. The view is bounded (256 lines, keys to 128 characters, text to 4096) and rides the
  existing `/circle/join` and `/circle/sync` routes; the signed events stay the only pad state, and
  verification and pinning stay at the receiving route. It adds no route, header, DTO field or
  second persisted copy. A phone pad surface and `flit-cli circle pad put|del|show` use the same
  core; the view is rebuilt from the signed log, never a second stored copy. No device walk is
  claimed.
- **Small surface additions:** the pad card can search its lines against the same bounded view
  without changing it, fingerprint fallback labels use a derived short display code instead of
  a truncated fingerprint, and the shipped BLE duty schedule is checked at start-up as an
  observation only — findings are logged and the carrier arbiter never reads them, so carrier
  behavior is unchanged. These are code and JVM-tested additions; no device walk is claimed.

## Documentation

Milestone status is in the [design index](docs/transport-bluetooth.md). Engineering context:

- [Architecture](docs/ARCHITECTURE.md) — module map, transport seam, state ownership, lifecycle, concurrency.
- [Contracts](docs/CONTRACTS.md) — v2.2 wire, additive Flit routes, Link/ServerDelegate APIs, canonical signatures.
- [Failure modes](docs/FAILURE-MODES.md) — what retries, concludes, keeps bytes, or drops, across every flow.
- [Security](docs/SECURITY.md) — threat model, trust anchors, replay, invites, relay, panic, limits.
- [Performance](docs/PERFORMANCE.md) — measured throughput, energy design, duty cycles, watermarks, caches.
- [Testing](docs/TESTING.md) — rig command, evidence levels, device walks, reproduction recipes.
- [Operations](docs/OPERATIONS.md) — CLI reference, box/phone setup, MIUI notes, logs, troubleshooting.
- [Product decisions](docs/PRODUCT-DECISIONS.md) — non-profit constraint, surface contract revision, scope cuts, postmortems.
- [Build roadmap](docs/roadmap/00-plan.md) — dated plan, agent waves and quality gates for the next features.
- Focused designs: [Circle](docs/circle.md), [Bluetooth carrier](docs/bluetooth-carrier.md), [QR-only & panic](docs/qr-only-and-panic.md), [Open file & sharing restore](docs/open-file-and-sharing-restore.md), milestones [A](docs/milestone-a.md), [C](docs/milestone-c.md), [D](docs/milestone-d.md), [E](docs/milestone-e.md), and [store listing copy](docs/play-listing.md).

## Repo layout

```
protocol/   wire protocol engine (pure Kotlin; JSON DTOs, UDP discovery, TLS+mTLS, HTTP server/client)
app/        Android shell (Jetpack Compose) — the phone app
harness/    the desktop companion, shipped as flit-cli (doubles as the rig that verifies interop with official LocalSend)
```

## Dev quickstart

Toolchain (JDK 21 + Gradle + reference CLI) lives in `~/.local/`:

```bash
source env.sh                 # puts JDK 21 first on PATH
./gradlew :harness:installDist
cd harness/build/install/flit-cli/bin && ./flit-cli
```

The full command list with every flag is in the [canonical `flit-cli` list](#desktop-companion-flit-cli)
below — there is one list, not two. Of those, `bt-sim` runs a complete v2.2 exchange
(info → register → files) both ends in one process: the receiver is the production server,
the sender is the production protocol client riding the simulated Bluetooth pipe
(`--bytes MB` for synthetic bodies, `--no-tls` for the plain carrier). The rig standard
used before every commit:

```bash
source env.sh && ./gradlew :protocol:test :app:testDebugUnitTest :harness:test \
    :app:assembleDebug :app:assembleRelease :harness:installDist :harness:distZip
```

Interop testing uses the official `localsend-cli` v1.18.2 binary at
`~/.local/opt/localsend-cli` (see `interop/`).

## Desktop companion (`flit-cli`)

The app's protocol stack also runs on the desktop: the same v2.2 client and server
(TLS with mutual certificate auth, UDP register discovery, sha256 receipts, resumable
uploads) behind a command line — the same binary that serves as this repo's interop
rig, shipped under a name that doesn't wear test-bench vocabulary.

```bash
./install-cli.sh                            # build from source → ~/.local/bin/flit-cli
./install-cli.sh --zip flit-cli-0.3.1.zip   # or install a CI artifact directly
```

Common `flit-cli` commands (run `flit-cli` with no arguments for the complete
flag list; `docs/OPERATIONS.md` is the canonical reference):

```bash
flit-cli serve --dir ~/Public [--relay host:port --token T]   # receive; announces on the LAN like the app
flit-cli serve --dir ~/Public --hidden                      # receive; stays off other devices' rosters entirely
flit-cli discover --wait 5                                  # who else is speaking v2.2 here?
flit-cli send <ip[:port]|alias> <file...> [--parallel N] [--pin PIN] [--multi]
flit-cli info <ip[:port]>
flit-cli bt-sim [file...] [--bytes MB] [--no-tls]           # both ends in one process over the pipe carrier
flit-cli relay --port 53318 [--token T] [--park-timeout S]  # blind pairer for peers that can't reach each other
flit-cli panic <ip[:port]> [--text NOTE]                    # signed informational alert
flit-cli parse-qr <image...>                                # decode pairing codes from screenshots
flit-cli circle <create|join|post|poll|vote|role|kick|leave|sync|file>   # accountless group coordination
```

- `courier` moves signed Circle events between machines as sealed parcels in a portable bag directory: `drop` seals an event for a recipient fingerprint, `carry` verifies the bag and marks parcels carried, `receive` opens parcels addressed to the local identity and folds them into local Circle state, and `status`/`sweep` report rows and expire or refuse undeliverable parcels. The bag is moved by directory copy (USB or any file copy); the CLI adds no transport of its own and no route to the v2.2 wire.
- `parcel` folds one file into numbered QR frames and back, entirely offline: `encode <file> --dir DIR --to FP --cert PATH` seals the bytes for a recipient certificate supplied out of band (its fingerprint checked against `--to`) and writes `frame-0001.png`, `frame-0002.png`, … into `--dir` through the parcel codec; `decode --dir DIR --out PATH` reads those PNGs, verifies them through the same codec and writes the opened bytes to the explicitly named `--out`. No file name travels in a frame, and neither command opens a socket or uses a radio carrier.

Needs java 21 at run time — the installed launcher says so in one line if the
ambient `java` is older (`JAVA_HOME` or PATH wins). Identity lives at
`~/.config/flit/flit-harness` — stable across rebuilds and updates, so peers that
trusted this machine keep trusting it. Edges: `serve`
auto-accepts incoming offers (the app's trusted-senders gate has no CLI counterpart
yet), and a machine hidden with `--hidden` is invisible to multicast discovery —
which is exactly the peer the phone app's rescue sweep exists to find. `relay` is
the milestone-E pairer for peers that cannot open a route to each other: it pairs a
sender's `req <fingerprint>` with a receiver's long-lived `sub <fingerprint>` and
copies bytes without ever parsing the protocol — TLS and the fingerprint proof run
end-to-end between the peers. The current CLI logs only timestamps and opaque
pairing ticket ids — no fingerprint, no byte count, never content. Both peers must be
live; there is no relay-side
queueing, and the address is configured by hand (no relay discovery, by design). On the
phone the relay carrier is gated by the per-device **relayFor** switch in Known devices:
a device opted out never has its traffic routed through the relay, and Circle dialing
requires that switch on (with a relay address and token) to reach a peer that way.

## Licensing / provenance note

Flit is free software under the **GNU GPL v3 or later** ([`LICENSE`](LICENSE)). Copyleft is
deliberate: you may use, modify and redistribute it, but anything derived from it and
distributed must carry the same license. There are no separate commercial or closed-source
terms, and no dual-license escape hatch.

This is a **clean-room implementation** of a wire protocol (protocols and file formats are not
copyrightable). No LocalSend source code or assets are copied; only the protocol specification
documentation is used as reference.

Development happened privately and is published here with its **full commit history** — the
scaffolding, the milestones, the regressions and their fixes are all in it. This is not an
imported or rewritten repository, and more work is planned.
