# Interop verification log — Flit vs real LocalSend CLI v1.18.2

Date: 2026-10-22 · Environment: Linux box, JVM Temurin 21, loopback.
Peer under test: `~/.local/opt/localsend-cli` (rustls-based, protocol v2.2).
Our stack: `:protocol` module (Kotlin), Netty server, own `PeerHttpClient` (JDK TLS).

| # | Scenario | Result |
|---|----------|--------|
| T0-s | Harness↔harness send (3 files, parallel 2, mTLS, dedup `payload (3).txt`) | PASS (3/3, exit 0) |
| T0-d | Discovery harness↔harness | PASS via **unicast fallback announce** (`--announce-to`) |
| T1-r | Real CLI → us: CLI registers + sends payload, we auto-accept | PASS (`recv-ok`, CLI TUI `Sent 1 file (32 B)`) |
| T1-s | Us → real CLI: prepare-upload + upload to the rustls server, `Y` in CLI TUI | PASS (`sent 1/1`, CLI TUI `Received 1 file (30 B)`, byte-identical) |
| T0-m | Multicast 224.0.0.0/4 announce | **FAIL — kernel-level broken here** (`ping 224.0.0.1` 100 % loss; receiver socket joined on iface gets nothing; no firewall rules). Mitigation: unicast announce fallback. |

## Root causes diagnosed this session (clean-room, from observed wire behavior)

1. **Ktor CIO TLS client unusable for LocalSend** — threw `Invalid TLS record type code: 116`
   against rustls servers. Retired for the client side; **replaced transport with our
   minimal HTTP/1.1 client over JDK `SSLSocket`** (`PeerHttpClient`): one exchange per
   connection, content-length bodies, minimal chunked decode, no hostname verification
   (peers are IP-addressed; authentication is fingerprint-based per protocol spec).
   `java.net.http.HttpClient` was rejected: hostname verification cannot be disabled
   cleanly (undocumented kernel property) and it does not exist on Android.

2. **rustls alert 116 = `certificate_required`** — both curl-without-cert and our stack got
   this alert. Traced with `javax.net.debug`: the CLI's `CertificateRequest` carries
   `certificate_authorities: [CN=LocalSend User]` (its own self-signed subject). JDK
   `SunX509` filters client-cert aliases against that list (`chooseClientAlias(..., issuers)`
   → `null` when issuer mismatch), swings to an **empty client certificate flight**, and
   rustls kills the handshake. Peer-to-peer mTLS has no CA hierarchy, so
   `TlsIdentity.keyManagers()` now wraps the stock KeyManager with an issuer-agnostic
   `X509ExtendedKeyManager`: selection matches by key algorithm only, CA list ignored.

3. **Strict JSON on prepare-upload** — upsteam `RegisterDtoV2` (serde) declares `port` and
   `protocol` with **no defaults**; our earlier `encodeDefaults=false` omitted
   `protocol:"https"` → CLI answers `400 {"message":"Invalid JSON body"}`. Wire JSON now
   always carries default-valued fields (`WireJson` `encodeDefaults = true`); serde readers
   ignore extra fields on responses, so the changed response shape stays compatible.

4. Carried context: password `CharArray` shared with netty/ktor gets zero-filled →
   `TlsIdentity` holds an immutable secret and hands out fresh `secretChars()` per call.
   CLI quirks: UDP announce socket always 53317 regardless of `LOCALSEND_PORT`; device
   list opens with `D`, Enter sends, `Y/N/P` accept incoming.

## Incident notes
- A timed-out CLI prepare leaves its TUI dialog behind; dismiss with `N` before retrying.
- Rebuild ritual: restart `harness serve` after every `installDist` (JVM holds the old
  jars; a stale session also blocks the single slot until `POST …/cancel?sessionId=…`).

## Repro bootstraps
- Node A: `harness serve --dir received --port 53318 --udp-port 53319 --alias FlitAlpha --announce-to 127.0.0.1:53317`
- Node B send: `harness send --to 127.0.0.1:53318 <files>` (T0), `--to 127.0.0.1:53216` (T1 reverse).
- CLI receive driving: `mkfifo cli-stdin`, `exec 3<> cli-stdin`, launch `script -qec "$HOME/.local/opt/localsend-cli" /dev/null <&3 > cli-tui.log 2>&1 &`,
  then `printf 'Y' >&3` when the offer dialog shows (same shell invocation; fd3 dies with the shell).
