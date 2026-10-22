# Flit (codename)

**Send any file to any nearby device — WiFi-shy, resume-proof, zero setup.**

A no-fluff, native-Kotlin file transfer app that speaks the
[LocalSend protocol v2.2](https://github.com/localsend/protocol) so it interoperates with every
existing LocalSend install (Windows/macOS/Linux/iOS + their CLI), and improves on it with:

| # | Differentiator | Status |
|---|---|---|
| 1 | Chunked **resumable** transfers (their issue #2127, open 20+ months) | planned |
| 2 | **Rescue mode**: hotspot fallback when networks block multicast | planned |
| 3 | **Crush & send**: on-device media compression before transfer (Pro) | planned |
| 4 | Native speed: instant cold start, share-sheet-first UX | planned |

Working name. Rename is a 15-minute chore before Play listing (no trademark reuse of "LocalSend").

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
