# Failure analysis

This document records what failed, what stayed incomplete and what was a
deliberate boundary. It separates product gaps from evidence gaps, architecture
gaps, security residuals, packaging gaps and scope boundaries. It does not
repeat the feature list in `docs/STATUS.md`.

The analysis is layered. Orchestrators frame categories and readiness; domain
specialists trace each seam; QA reviewers check whether a claim is supported by
CODE, PURE, SIM-HARN or DEVICE evidence. Findings from completed layers are
recorded below. Later layers are added to the same document when their reports
arrive.

## Categories

| Category | Meaning |
| --- | --- |
| Product gap | A capability needed for normal use does not exist or is not reachable. |
| Evidence gap | A claim remains at CODE, PURE or SIM-HARN when the release needs DEVICE. |
| Architecture gap | A seam, port, module or convergence step is missing for another phase. |
| Security residual | An open risk remains after the landed gates; it may be bounded and documented. |
| UX gap | The behavior is implemented but hard to discover, misleading or unverified in use. |
| Packaging gap | A release artifact, signing step, notice file or CI task is absent. |
| Scope boundary | A capability is intentionally excluded and recorded as such. |

## Ranked release and desktop blockers

| Rank | Failure | Category | Severity | Effect |
| --- | --- | --- | --- | --- |
| 1 | No DEVICE evidence for the security wave; the phone holds a pre-wave build | Evidence | Critical for v0.3.2 | Blocks the closeout claim |
| 2 | Gate 0 pins unresolved for desktop; plugin matrix and one Compose set unproven | Architecture | Critical for desktop | Hard gate before any screen |
| 3 | Trust gates remain off by default and out-of-band provisioning is unproven | Security | High | Blocks security closeout, not v2.2 compatibility |
| 4 | No durable pin source and no restart-safe replay store | Architecture / Security | High | Blocks closeout completeness and long-lived desktop trust |
| 5 | No hostile-input campaign, advisory scan step or dated disposition report | Security / Evidence | High | Supply-chain and adversarial evidence incomplete |
| 6 | General host-side download service absent | Product | High | Files cannot be fetched without an accompanying send flow |
| 7 | CLI/app state convergence and extraction remain incomplete | Architecture | High | Blocks desktop parity and shared UI |
| 8 | `ui-common` and `:desktop` modules and their packaging tasks do not exist | Architecture / Packaging | High | Blocks desktop build |
| 9 | Pad event shown as unsupported in the Circle feed | UX | Medium | Surface inconsistency |
| 10 | Multi-peer fan-out, keyed-drop receipt, courier camera and QR camera flows lack DEVICE rows | Evidence | Medium | Limits confidence outside the one-phone/one-peer walk |

## Evidence failures

The security wave is CODE, PURE and SIM-HARN. No DEVICE row exists for it. The
installed phone build predates the security wave; the current release APK has
not been installed on a device in this run. Every claim that depends on Android
TLS peer-certificate access, real multicast announce, relay sockets, camera
decode or a second physical phone therefore stays unobserved.

The device closeout starts with reinstalling the current release APK and
recording its digest. The walk then covers prepare gates with a trusted and an
untrusted peer, sender-side pin refusal before any body leaves, signed JOIN
admission, keyed file landing and receipt minting, officer-only governance,
courier handoff, QR parcel intake through the camera, and pad convergence. A
missing expected log line is recorded with a timestamp; an unrun step stays not
verified.

Not closable by a walk: durable pin hydration, a restart-safe replay store, the
hostile-input corpus, the advisory scan, second-phone RFCOMM, a larger mesh,
Wi-Fi Aware and MIUI background survival. Each needs its own port, hardware or
report.

## Security residuals

- **Durable trust anchors.** Pins are held in two process-local tiers. Persisted
  roster and Known rows hold fingerprints, not certificate DER. After restart
  or eviction, a bare fingerprint verification fails and the peer must present
  a certificate again. Gate decisions read persisted rows and fail closed; the
  realized effect is an availability and provisioning gap, not a false grant.
- **Restart-safe replay memory.** `CircleCore`, `PanicCore` and courier carry use
  bounded process-local reservation sets. The six-hour window, five-minute JOIN
  bucket and signature verification survive process death, but the id memory
  does not. A captured in-window proof can be spent again after a host restart.
- **Opt-in gates.** With both prepare gates false, the shipped v2.2 behavior
  accepts a caller-declared fingerprint. With a gate on, the route requires a
  certificate and a signature over the exact prepare body, binds the digest to
  the declaration, and passes only the bound fingerprint to the trust gate.
  This is a documented compatibility boundary, not an undiscovered defect.
- **Process-local announce and relay state.** Restarts clear announce
  debounces, register semaphores, parked tickets and per-IP backoff. Stateless
  checks remain: canonical fingerprints, on-link source tests, token strength
  and the configured token.
- **Advisory scan.** Dependency versions were upgraded manually. There is no
  repeatable scan of the resolved runtime set and no dated report with
  upgrade-or-waiver dispositions.

## Missing host-side download service

`downloadApiEnabled` is an advertised boolean with no route, client or
consumer. It feeds `PeerInfo.download`; no `PATH_DOWNLOAD`, handler branch,
binder registration or `ProtocolClient` fetch method exists. Neither the app
nor `flit-cli serve` enables it in production. App intake is send-only: share
intents stage files for a peer, and received-file cards open files already
pushed by a send flow. A fetch without a send flow does not exist.

Three options were analysed:

1. **Opt-in LAN download API with pinned access.** Adds Flit-only list/get
   routes, default-deny authorization, path confinement, response caps and a
   client fetch method. It reuses TLS, `ExpectServerFp`, cert-signature binding
   and persisted pin/known stores. It works over TCP/TLS, Bluetooth pipe and
   relay. It adds a third protocol verb family and an authenticated enumeration
   surface.
2. **CLI-hosted browser download page on localhost/LAN.** Adds `--web`,
   `--web-port`, `--web-host` and `--web-token` to `flit-cli serve`, with a
   read-only listing and filtered GET over HTTP. Loopback is the default; a
   non-loopback bind requires a strong token. No wire route, app release, new
   trust anchor or APK change is needed.
3. **Phone-hosted QR/PIN download surface.** The phone hosts the surface and
   grants access only after QR proximity or a one-time PIN. It reuses QR
   pairing, Known devices and `TrustGate`, but needs the shared route work from
   option 1, a short-lived token, brute-force bounds and a two-phone walk.

The phased decision is option 2 first, then option 1 after product need is
confirmed, then option 3 after device evidence and a surface-contract review.
Option 2 closes the stated failure with the smallest bounded surface: harness
and docs only, no new Flit wire route and no app release. A phone-browser walk
on the rig LAN would supply the first DEVICE observation.

### Phone surface design (option 3, prepared but not started)

The phone would gain a `downloadServe` toggle in Options beside the existing
trusted-senders switch, with a SAF tree under `downloadServeTree`. The Start
hero remains the only engine start path. A Download clients sheet would list
the union of the trusted roster and QR-known peers, marked live from the
current peer list, each with an explicit per-peer grant.

Authorization would be a new JVM-pure `DownloadGate` in `:core`, modelled on
`TrustGate`: config `serveEnabled` / `onlyTrusted` / `qrOnly`, stores for
pinned, known and granted rows, and a result carrying only a 12-character
reason code plus an accept decision. Store callbacks fail closed. The engine
seam sits beside `evaluatePrepareOffer`. Refusals carry no alias, address,
certificate bytes or declaration text.

The received ledger would gain nullable `sourceFp`, `requestId` and `grantId`
fields with tolerant decode, so existing rows load without migration. Revokes
are a separate operation from clearing history: removing a grant ends active
served sessions for that fingerprint and is checked between chunks, while
`clearReceived` stays ledger-only. Status lines for serve on/off, request,
decline, served file and revoke go into the existing 60-line log tail; no new
notification channel is added.

The work depends on the protocol routes from option 1. The gate, ledger fields
and UI copy can be built and pinned on the JVM while those routes are separate.
No DEVICE evidence backs any of it yet.

### Download service security controls

Adding a download route creates a new read surface unless every route is
gated. Two issues are critical and were not in the phased plan:

- **Path traversal.** `sanitizeRelative` bounds inbound upload names, but a
  GET-by-name takes a fresh attacker-controlled value, and `LinkServer` URL
  decodes query parts before validation. One `confinedTarget(destDir, rawName)`
  helper must decode once, sanitise, bound the name, resolve and normalize the
  path, and require it to stay under the normalized root. Apply it per file.
- **Symlink escape.** No current write or read path checks links. A GET that
  opens a resolved target would follow a symlink planted in the served folder.
  Check each path component with `NOFOLLOW_LINKS`, require a regular file, and
  refuse a write target whose parent contains a symlink. The CLI host resolves
  `--dir` once to an absolute normalized root and never serves from a writable
  parent.

High-severity controls follow the same pattern:

- Listing and fetch require a short-lived signed capability whose fingerprint
  is computed at the receiver, then a fresh trust verdict from persisted
  pin/known stores. Default-deny applies to this route even when both prepare
  gates are off. Header lengths use the existing prepare caps.
- Download-all stays behind its own flag with a separate scope, a file-count
  and aggregate-byte cap, and pagination. The capability and confinement
  check run per file, not once per batch.
- Per-identity token buckets and a global concurrent-stream counter bound GET
  abuse; response size and capability lifetime are capped; write timeouts are
  set on both binders.
- Relay reachability needs a separate `downloadViaRelay` opt-in. Relayed or
  empty origins do not authorize a download, and rate keys follow the verified
  fingerprint so relay rotation cannot reset them.
- Capabilities are short-lived and re-checked against persisted stores on
  every request. Unpin and forget clear the in-memory session and capability
  maps and close active streams. After a restart, a missing persisted pin is a
  refusal.
- Listings expose an opaque id and a `safeLandingName`-filtered display name:
  no absolute path, host alias or full fingerprint. Prepare logging applies the
  register alias gate before echoing any alias, and logs the trust code instead
  of raw input.

Everything stays opt-in: route registration behind `downloadApiEnabled`, the
advertised capability only when explicitly enabled, loopback default for the
CLI page with a strong token off-loopback, QR/PIN access only after pairing,
no download-over-relay by default, and no capability that bypasses the
certificate-signature binding.

### CLI host design and test plan

The CLI host would add `flit-cli host`, `list` and `get` commands, reusing the
`serve` identity home, TLS toggle, announce policy, Circle seeding and relay
subscription. `--dir` names the served folder and defaults to `flit-share`;
`shareDir` starts null in `ServerConfig`, so download routes stay 404 until a
folder is set. `list` fetches a manifest and `get` performs one fetch per
chosen file into `--dest`, verifying each sha before promoting a temp file.
Exit codes stay in the existing scheme: 2 for bad PIN, code or argument, 3 for
sha mismatch or partial failure.

The host mints a six-digit PIN when none is supplied, compares it in constant
time, and backs off after five wrong attempts per origin in 30 s. `--qr-out`
writes a `flit://get?` code carrying the LAN address, TLS flag, host
fingerprint and PIN; off-LAN addresses are rejected. Trust gates reuse
`TrustGate` and the certificate-binding shape from prepare, with a warning
when PIN and both gates are off. Progress uses the existing speed smoother at
a 10 Hz cap.

Bounds: 128 manifest entries, single-component 255-char names, 16 concurrent
sessions with a 10-minute idle TTL, 1 GiB per file and 2 GiB per batch, and the
existing 8 MiB control-body cap. Manifest and fetch share the same confinement
helper, so symlinks and escaping paths are refused in both.

The route family is settled for the smallest version: keep
`downloadApiEnabled` and the advertised `PeerInfo.download` bit false, because
that bit names LocalSend's browser-facing `/prepare-download` and `/download`
verbs, and a LocalSend peer would call endpoints Flit does not implement. The
Flit surface would go under `/api/flit/v1` with its own process-local gate
supplier, so the v2.2 wire stays byte-for-byte unchanged and no LocalSend peer
is invited to a missing route. Two additive routes would cover listing and file
fetch; they would ride the same `ServerCore`, so TCP/TLS, Bluetooth pipe and
relay carriers would share one implementation. No such route exists in the tree
today; the gap is recorded in `docs/STATUS.md`. A separate decision would be needed to implement the v2.2
download verbs for LocalSend interoperability.

The test plan starts with pure seams in `core` (PIN gate, listing derivation,
path guard, budget accounting, inventory merge from receive/courier/QR
sources), then protocol refusals and v2.2 byte parity, then app and harness
adapters, then SIM-HARN runs over real TLS and pipe carriers, and finally a
device walk. `DownloadRouteDoorTest` is SIM-HARN even though it lives in
`protocol/`, because it drives production binders. Counts must come from a
fresh forced XML; stale XML can report green against an older class layout.

## Architecture failure chain

`FlitEngine` is one Android singleton of roughly 4,500 lines. It mixes pure
decisions with radio workers, persistence, trust decisions and notifications.
No injected ports exist for files, clock, identity, store or UI dispatch. Size
alone is not the blocker; the mixture prevents a JVM surface from reusing any
of the decisions.

Android `Context` reaches circle logic through `FlitEngine.ctx()` and
`FlitCircleSync`, and every store adapter takes a `Context`. The test hooks
`clockHook`, `dialHook`, `stateHook` and `identityHook` already model the
missing ports as mutable globals, but production still reads the `Context`.

The CLI and app keep two `CircleState` schemas and two folds. The CLI Lamport
counter starts at 0 on an empty log while the app starts at 1. The CLI answer
fold orders and dedups events without the app's `validShape` and `accept`
gates. The CLI delivery vocabulary has no `NO_KEY` state and maps an empty
report to `REFUSED` where the app uses `declined`. QR hints differ even though
the frame grammar matches.

Three identity-generation sites exist and there is no `Identity` port. The
app mints in `FlitEngine` and again in `FlitCircleSync` when the engine context
is unpublished; the CLI mints in `harnessHome()`. A newly minted key
invalidates every persisted peer pin, which is why one home per running node
is a hard rule.

These findings form one dependency chain: move the `CircleState` codec to
`:core` first, converge the CLI and app schemas and folds on it, add `Identity`
and `Store` ports, then remove `Context` from the circle path, then shrink
`FlitEngine`. `ui-common` and `:desktop` have no source or build target yet;
their token extraction can be prepared but not adopted into a screen until
Gate 0 closes. The download catalogue seam is independent of this chain and
can proceed without changing the default-off wire flag.

## LocalSend baseline comparison

`interop/README.md` verifies only the v2.2 baseline: harness-to-harness send
and discovery, real-CLI-to-Flit register/prepare/upload in both directions,
and phone-to-box Wi-Fi discovery and transfer. No row exercises a download or
link-share surface.

Signed Circle events, courier carry, QR parcels, per-drop content keys,
delivery receipts, the shared pad and the security gates are deliberate
additions that leave the v2.2 wire unchanged. They are not failures.

Two true failures come out of the comparison:

- **General host-side download availability is absent.** The capability flag
  exists in DTOs but no route, client or product surface reads it. The v2.2
  download verbs were explicitly left out by the route decision recorded above,
  so the product gap is a missing surface, not a missing boundary decision. Interop coverage missed it because the
  tests exercise the DTO field, not a flow.
- **Pad events render as unsupported in the Circle feed.** The pad sheet shows
  the line, so the event is not lost; the feed path routes it to the unknown
  card. This is a Flit surface defect, not a LocalSend parity issue.

Everything else in the baseline is a recorded boundary or an evidence gap:
desktop GUI, always-on desktop receiver, multi-platform GUI, Wi-Fi Aware,
multi-hop mesh, offline pack format, remote revocation, and phone background
restoration. Simpler UX and a revised vocabulary are deliberate tradeoffs.
Two-phone RFCOMM, camera scan, courier carry on the phone and multi-peer
fan-out weaken confidence but do not show a broken shipped contract.

## Product, UX and packaging failures

- The Circle feed renders a pad event as an unsupported card while the pad
  sheet shows the line. The two surfaces derive from different paths.
- Courier and QR intake are not discoverable from a single place; no device
  walk has exercised camera intake or bag handoff on the phone.
- The security gates are accepted by the CLI as value flags but are not printed
  in `usage()`, making them hard to discover.
- `v0.3.2` is planned but untagged. Source still declares `0.3.1` with code 4.
  The release APK is built and signed locally; no v0.3.2 artifact or tag is
  published.
- CI runs tests and assemblies but no advisory scan and no dated report.
- Git pushes depended on an external token file with Contents and Workflow
  write permissions; that failure was operational and is resolved for the
  current push.

### Additional product gaps

- **Receive stops when the app closes.** Under the `dataSync` cap the service
  is refused, the engine lives only while the UI is open, and the only signal
  is a transient toast. Trust and handoff state are process-local, so an
  opted-in device loses its gates on restart. A persistent status line and one
  explicit receive-while-closed mode are the minimal fixes.
- **Courier is buried and misleading.** "Courier drop" sits three taps deep in
  the members sheet next to Kick and Rekey, carries signed announcements for up
  to four recent files rather than file bytes, and reports `handed` when the
  peer accepts the offer, not when bytes land. A file-card "Carry to …" action,
  explicit label text and persisted handoff status are the minimal fixes.
- **QR parcel entry needs a live session; camera is not a hard dependency.**
  Parcel entry refuses to open without a sharing identity. Both scan cards have
  a clean paste fallback when permission is denied, so the camera is a
  convenience rather than a blocker. Real weaknesses are parcel frame
  auto-advance, which overwrites manual Back/Next steps every 400 ms, and a
  paste format that requires hand-cleaning the CLI decoder's `path: text`
  output. Identity-independent display, a pause control and a plain-text CLI
  frame mode are the minimal fixes. No phone camera walk exists.
- **Circle reliability is silent past three nodes.** Peer dials are capped at
  four, host caps at 500 members and 500 events, and backoff is invisible. A
  per-member synced / not-reached line and one six-to-ten peer walk are the
  minimal fixes.
- **Trust gates have no onboarding path.** The two Options switches describe
  their effect but not their prerequisites. A gate enabled with an empty
  roster or no QR-paired peer silently declines every offer. The `Trust this
  device` control is presented as a receive-folder decision, with no link to
  the send gate. On the CLI, `usage()` omits `--only-trusted`, `--qr-only`,
  `--pinned` and `--known` while a default-off serve gives no signal that a
  gate exists. A confirmation that points to Nearby or QR, gate-state logging
  at startup and the four flags in help are the minimal fixes.
- **Notification content has no lock-screen privacy policy.** Transfer bodies
  carry the file name, panic bodies carry the note and sender prefix, and the
  service notification carries alias and port. No channel sets lock-screen
  visibility or a public version, so privacy falls to the platform default.
  Private visibility, a redacted public version and names omitted from
  progress text are the minimal fixes.
- **MIUI and `dataSync` degradation is graceful in code but not explained in
  product.** A refused foreground start leaves an engine-only process with a
  one-shot toast; at the six-hour `dataSync` cap the notification is removed
  while the engine keeps running. No persistent status tells the user that
  sharing is system-limited or that the notification no longer reflects
  engine state. A stored status line with a link to battery settings and one
  soak with the last surface closed are the minimal fixes.
- **CLI discoverability.** `courier` and `parcel` are in `usage()` but missing
  from the README canonical command block.

### Release-process failures

- `v0.3.2` has no version bump: source still declares `0.3.1` with code 4.
- The documented distribution bundle does not exist: no release zip with a
  signed APK, CLI zip, `SHA256SUMS.txt` and the promised AAB. Only the CLI zip
  is built.
- The release APK carries dependency license files but no project GPL text and
  no NOTICE entry; the CLI notices contain many "no license text in jar" rows.
- Store metadata is stale: `metadata/app.flit.share.yml:9` says `License: MIT`
  against a GPL project, its allowed signing-key digest and version do not
  match the current build, and changelogs stop below code 4 in both `metadata/en-US/changelogs/` and
  `fastlane/metadata/android/en-US/changelogs/`.
- The local APK verifies against the intended release key, but that check is
  not recorded in the release docs, and CI has no signing secret.
- The post-R8 assembly is green and is recorded in `docs/TESTING.md`,
  `RELEASE_NOTES.md` and `.github/workflows/rig.yml`; assembly results are
  outside the JUnit XML.
- Commit dates were assigned to believable weekdays while work was built ahead
  of its commit date; a0fb938 is dated before its parent. This follows the
  dating constraint and is recorded as a history property, not a defect to
  rewrite.

## Repeated failure patterns

These are process failures, not one-off code defects. They recur across waves.

- **Stale XML accepted as green.** The 2026-11-25 incident at
  `docs/TESTING.md:961` recorded `UP-TO-DATE` tasks reporting green against an
  older class layout while the headline count stayed unchanged. The rig makes
  `--rerun-tasks` mandatory for verification, but `.github/workflows/rig.yml`
  runs the test tasks without it and has no step that parses XML for failures,
  errors or skips. A capability inventory cannot be trusted until CI forces a
  fresh run and checks counts against a checked-in manifest.
- **Skipped assumptions counted as green.** Seven `assumeTrue` gates across six
  harness classes skip when `FLIT_HARNESS_HOME` pins a single home; an eighth
  at `CourierCliTest.kt:681` skips when POSIX file attributes are unavailable.
  The walk
  guidance recommends distinct homes for CLI bots, so an operator following it
  can silently skip those cases while completion notes still record zero
  skipped. Zero skipped must be a rig assertion, not an observation.
- **Gates retrofitted with test batches.** Prepare and Circle gates were added
  first and their test classes expanded later: `ProtocolRefusalTest` 4 to 22,
  `RelayServerTest` 6 to 17, `FlitEngineTest` 31 to 49, `JoinProofTest` 14 to
  15. Each new verb then pays for a separate re-proof that canonical bytes did
  not change. One golden vector fixture in `:core`, run automatically on gate
  changes, would replace the repeated layer-by-layer re-proof.
- **Credential handling defined by limitations.** Relay tokens, JOIN invites
  and PIN material are documented as bearer values with process-local replay
  scope. There is no per-token lifecycle row covering issuer, binding,
  lifetime, storage, replay scope, rotation, revocation and default behavior.
- **Design records were not retired in the same layer.** The landing record
  now names Option B as implemented and links to
  `docs/roadmap/trust-gate-cert-binding.md`; that link was added in a later doc
  pass rather than with the code change.
- **Docs drift in repeated passes.** Three consecutive doc-only commits
  (`41734be`, `08f9b0d`, `0c6d562`) reconciled the same rig and assembly
  evidence state; the same reconciliation was needed again during this
  analysis. Build-ahead dating separates verification time from recorded
  commit time. One generated evidence inventory with a verification date, and
  a CI check on quoted counts, versions, paths and links, would replace the
  repeated manual passes.
- **Advertised flag treated as delivery.** `downloadApiEnabled` is the clearest
  instance: a DTO field was treated as the capability, and interop covered the
  field rather than a user-visible flow. Every advertised flag needs a mapped
  product flow or a recorded out-of-scope decision.

## Deliberate boundaries

The following are recorded boundaries, not defects to fix in this pass:
two-phone RFCOMM, a mesh larger than three identities, Wi-Fi Aware, MIUI
background survival, non-Linux desktop bundles, a structured CLI contract,
webcam QR capture, BLE and CameraX in the first desktop scope, and the absence
of an absolute security claim.

No item in this document closes a gap by itself. A gap is closed only by the
source change, test result or observation named for it.
