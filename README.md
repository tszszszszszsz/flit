# Flit (codename)

**Send any file to any nearby device — over Wi‑Fi when there is one, Bluetooth when
there isn't. Resume-proof, zero setup.**

A no-fluff, native-Kotlin file transfer app that speaks the
[LocalSend protocol v2.2](https://github.com/localsend/protocol) so it interoperates with every
existing LocalSend install (Windows/macOS/Linux/iOS + their CLI), and improves on it with:

| # | Differentiator | Status |
|---|---|---|
| 1 | Chunked **resumable** transfers (their issue #2127, open 20+ months) | **shipped** (Flit↔Flit extension: interrupted uploads keep their prefix on disk and resume after a reconnect, verified hash-exact in both directions) |
| 2 | **Rescue mode**: direct transport when no network path exists | **shipped — Bluetooth direct-discovery (row 9)**; joining a phone's own hotspot works by-address, the multicast-blocking-network case stays planned |
| 3 | **Crush & send**: text, notes, logs, JSON and code are gzip'd on the wire, Flit receivers unpack automatically (any other client still gets a valid `.gflit` gzip archive it can open) | **shipped** (free, Options → "Crush files when sending") |
| 4 | Native speed: instant cold start, share-sheet-first UX | **shipped** (share intake spool, send-later queue, trusted peers + per-peer folder, progress notifications, QS tile) |
| 5 | **Direct-to-MediaStore receive**: incoming files stream straight into a pending `Downloads/Flit/` row — no temp file, no second copy — with parallel uploads (4 streams) | **shipped** |
| 6 | **Real on-device options**: Material You theme (dynamic color, light/dark/auto), rename from the app, trusted-senders-only gate, hidden mode (listen-only discovery), start-on-boot, default receive folder, battery-rationing opt-out helper | **shipped** |
| 7 | **Receive ledger you can act on**: tap a received file to open it, one-tap re-share, clear anything | **shipped** |
| 8 | **In-app note composer**: send a plain-text note to a peer straight from the app — no share sheet detour | **shipped** |
| 9 | **Bluetooth direct-discovery**: when no Wi‑Fi network exists between you (mobile-data-only phones, dead access points), the roster just keeps working — the engine flips LE discovery on at the exact no-AP moment and the same v2.2 exchange rides a Bluetooth pipe; asks are the OS's own dialogs, once per device | **shipped** (engine + asks walked on device; full two-phone transfer awaits a second phone — see the transport doc's walkthrough record) |

Working name. Rename is a 15-minute chore before Play listing (no trademark reuse of "LocalSend").

## Current capabilities (verified on device)

- **No Wi‑Fi network? The roster keeps working.** The engine flips Bluetooth LE
  discovery on at the exact no-AP moment (mobile data only, dead access point) and the
  same v2.2 exchange rides the Bluetooth pipe; peers arrive in the same list. The surface
  never learns the radio exists — the only prompts are the OS's own dialogs, each asked
  once per device, at the moment they matter. Direct transport is exercised end to end in
  the harness (`bt-sim`) against the production server and client, and the arbiter's
  no-AP / hotspot / radio-off states are walked on hardware (see
  `docs/transport-bluetooth.md`); a two-phone transfer over the radio remains the listed
  pending item there.
- Share-sheet send: streams are spooled into app cache the moment the intent lands, so grants can
  never expire and every shared file can queue for later (`Send later` row; auto-dispatches when
  the peer reappears). The queue's promise is kept: a second intake joins the waiting batch
  instead of dying as "send failed", a flush broken by the peer leaving keeps its row, and
  delivered batches release their storage grants — everything keeps its sentence. (Walked
  on device against a harness server: kept → merged → auto-sent 2/2 → cancel, all live.)
- Multi-file sends stream concurrently (4 parallel), and a dropped connection mid-file is
  recovered automatically: the receiver keeps the received prefix, the sender probes
  `GET /api/flit/v1/partial`, and the transfer resumes from there (Flit↔Flit only; plain v2.2
  peers get a clean full resend — the final sha256 gate verifies every landing either way).
- Receive: progress notification with live speed; completed transfers land in
  `Downloads/Flit/`, in your default folder (Options), or straight into a
  per-peer folder for trusted peers — no double write either way. Received files
  open with one tap and re-share from the ledger.
- Dock-first redesigned UI (Material You expressive cards, floating bottom dock, animated
  screen transitions): live transfer telemetry — a "↑ Sending 48.0 MiB/s" hero strip and a
  progress ring around the dock's Send button while bytes are in flight. Tabs double as Home
  buttons, manual by-address peers persist across restarts, and files picked straight from
  the dock's Send stay ready under the "Ready to send" banner.
- Quick Settings tile + foreground service. Note: Android 15 rations `dataSync` background hours;
  when the budget is spent Flit runs engine-only while the app is open instead of crashing.

## Repo layout

```
protocol/   wire protocol engine (pure Kotlin; JSON DTOs, UDP discovery, TLS+mTLS, HTTP server/client)
harness/    headless CLI used to verify interop with the official LocalSend CLI
app/        Android shell (Jetpack Compose) — added once an Android SDK is available
```

## Dev quickstart

Toolchain (JDK 21 + Gradle + reference CLI) lives in `~/.local/`:

```bash
source env.sh                 # puts JDK 21 first on PATH
./gradlew :harness:installDist
cd harness/build/install/harness/bin && ./harness
```

commands: `serve`, `discover`, `send <ip[:port]|alias> <file...>`, `info <ip[:port]>`,
`bt-sim` — runs a complete v2.2 exchange (info → register → files) both ends in one
process: the receiver is the production server, the sender is the production protocol
client riding the simulated Bluetooth pipe (`--bytes MB` for synthetic bodies,
`--no-tls` for the plain carrier). The rig standard used before every commit:

```bash
source env.sh && ./gradlew :protocol:test :app:testDebugUnitTest :app:assembleDebug \
    :app:assembleRelease :harness:installDist
```

Interop testing uses the official `localsend-cli` v1.18.2 binary at
`~/.local/opt/localsend-cli` (see `interop/`).

## Licensing / provenance note

This is a **clean-room implementation** of a wire protocol (protocols and file formats are not
copyrightable). No LocalSend source code or assets are copyied; only the protocol specification
documentation is used as reference.
