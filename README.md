# Flit (codename)

**Send any file to any nearby device — WiFi-shy, resume-proof, zero setup.**

A no-fluff, native-Kotlin file transfer app that speaks the
[LocalSend protocol v2.2](https://github.com/localsend/protocol) so it interoperates with every
existing LocalSend install (Windows/macOS/Linux/iOS + their CLI), and improves on it with:

| # | Differentiator | Status |
|---|---|---|
| 1 | Chunked **resumable** transfers (their issue #2127, open 20+ months) | **shipped** (Flit↔Flit extension: interrupted uploads keep their prefix on disk and resume after a reconnect, verified hash-exact in both directions) |
| 2 | **Rescue mode**: hotspot fallback when networks block multicast | planned |
| 3 | **Crush & send**: on-device media compression before transfer (Pro) | planned |
| 4 | Native speed: instant cold start, share-sheet-first UX | **shipped** (share intake spool, send-later queue, trusted peers + per-peer folder, progress notifications, QS tile) |
| 5 | **Direct-to-MediaStore receive**: incoming files stream straight into a pending `Downloads/Flit/` row — no temp file, no second copy — with parallel uploads (4 streams) | **shipped** |
| 6 | **Real on-device options**: Material You theme (dynamic color, light/dark/auto), rename from the app, trusted-senders-only gate, hidden mode (listen-only discovery), battery-rationing opt-out helper | **shipped** |
| 7 | **Receive ledger you can act on**: tap a received file to open it, one-tap re-share, clear anything | **shipped** |

Working name. Rename is a 15-minute chore before Play listing (no trademark reuse of "LocalSend").

## Current capabilities (verified on device)

- Share-sheet send: streams are spooled into app cache the moment the intent lands, so grants can
  never expire and every shared file can queue for later (`Send later` row; auto-dispatches when
  the peer reappears).
- Multi-file sends stream concurrently (4 parallel), and a dropped connection mid-file is
  recovered automatically: the receiver keeps the received prefix, the sender probes
  `GET /api/flit/v1/partial`, and the transfer resumes from there (Flit↔Flit only; plain v2.2
  peers get a clean full resend — the final sha256 gate verifies every landing either way).
- Receive: progress notification with live speed; completed transfers land in
  `Downloads/Flit/`, or stream straight into a per-peer folder you pick once for
  trusted peers (no double write). Received files open with one tap and re-share
  from the ledger.
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

commands: `serve`, `discover`, `send <ip[:port]|alias> <file...>`, `info <ip[:port]>`

Interop testing uses the official `localsend-cli` v1.18.2 binary at
`~/.local/opt/localsend-cli` (see `interop/`).

## Licensing / provenance note

This is a **clean-room implementation** of a wire protocol (protocols and file formats are not
copyrightable). No LocalSend source code or assets are copyied; only the protocol specification
documentation is used as reference.
