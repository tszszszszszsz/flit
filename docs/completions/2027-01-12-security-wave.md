# 2027-01-12 — Trust gates, JOIN proof and Circle authority (Protocol/Core)

Layer: 2027-01-12 Protocol/Core. This layer adds JVM-only trust gates in the
protocol and core modules and extends the signed JOIN proof and the shared
Circle fold authority. The prepare gate decides whether an upload offer may
reach a delegate; the announce gates decide whether a decoded discovery
announcement may reach a registry callback. The proof and authority changes add
one pin-preservation case to the JOIN proof suite and a verb-classification
helper to the authority gate. No route, header, DTO field, QR parameter,
persisted store field, schema field, signature primitive or v2.2 status is
added. Evidence labels are CODE, PURE and SIM-HARN only.

Counts below are read from the JUnit XML of the last full four-module run under
`protocol/build/test-results/test/`, `core/build/test-results/test/`,
`app/build/test-results/testDebugUnitTest/` and
`harness/build/test-results/test/`, cross-checked against `@Test` annotations
in source. That run recorded 443 protocol + 167 core + 254 app + 108 harness =
972 cases with zero failures, zero errors and zero skipped cases. The zero-skipped value is an observation with
`FLIT_HARNESS_HOME` unset; one harness gate also depends on POSIX file
attribute availability. These counts
are historical for this layer. The release assembly is green after the R8 keep-rule fix.

## Core trust gates

`core/src/main/kotlin/app/flit/share/TrustGate.kt` is a stateless prepare-offer
decision. `evaluate(declaredFp, presentedFp, config, stores)` compares the
caller-declared fingerprint with the SHA-256 DER digest the protocol route
derived from the certificate that signed the exact request body. With both
runtime flags off the decision accepts every offer and consults nothing; with
`onlyTrusted` or `qrOnly` on, the order is CertAbsent, CertMismatch, NotTrusted
then QrUnknown. Fingerprints are compared as bounded uppercase hex with
surrounding whitespace and colon separators ignored; `MAX_INPUT_CHARS` is 256,
accepted digest lengths are 64 to 128 hex characters, and the only identity
material returned is a 12-character code from `FLITGATE1`-tagged hashing or
the leading 12 characters of a usable declared fingerprint. The `Stores`
interface is read-only, a throwing callback fails closed, and no verdict is
cached between calls. `core/src/test/kotlin/app/flit/share/TrustGateTest.kt`
has 15 cases in the XML, matching 15 source annotations: gates-off behaviour,
each refusal branch, colon and case normalisation, the bounded non-echoing
code, a throwing store, rotation against a fresh store and a restart with an
empty store.

`core/src/main/kotlin/app/flit/share/AnnounceAdmission.kt` is a pure announce
admission function. `admit(info, ownFp, nowMs, seen)` refuses a missing or
malformed fingerprint, a `manual@` placeholder, the device's own fingerprint,
an out-of-range port, an over-long alias, a source that is not plausibly
on-link, a repeat inside 1000 ms and a new peer when the caller's `seen` map is
already at `MAX_PEERS = 256`. On-link is a textual approximation covering RFC
1918 IPv4, 169.254/16, fe80::/10 and fc00::/7; no DNS resolution is performed.
The gate never writes to `seen`, treats a future timestamp as a replay, and
fails closed when the caller map throws.
`core/src/test/kotlin/app/flit/share/AnnounceAdmissionTest.kt` has 27 cases in
the XML: fingerprint and port validity, the full refusal order, IPv4 and IPv6
source ranges, bracket and zone handling, the debounce boundary, the capacity
rule, hostile values and determinism. Together the two core classes account
for 42 of the 167 core cases.

## Protocol trust gate and wiring

`protocol/src/main/kotlin/app/flit/share/protocol/discovery/UdpDiscovery.kt`
carries a separate, stateful `AnnounceAdmission` class. Unlike the core
function it owns a bounded table and a register semaphore. Its `Policy` holds
`minFingerprintIntervalMs = 1000`, `maxFingerprints = 256`,
`maxPerSourceIp = 64`, `fingerprintTtlMs = 60_000` and
`maxConcurrentRegisters = 8`; `decide` returns ACCEPT, INVALID, SELF, TOO_SOON,
SOURCE_MISMATCH, FINGERPRINT_CAP, SOURCE_CAP or REGISTER_BUSY. `dispatchAnnounce`
runs the decision before the peer callback or register job, and releases the
permit when a job is cancelled before launch. `UdpDiscoveryTest` has 15 cases
in the XML, all against the injected-clock decision and in-process dispatch:
flood throttling, table and source caps, stale-entry expiry, self and manual
stub refusal, source mismatch and permit release.

On the prepare path `protocol/src/main/kotlin/app/flit/share/protocol/server/ServerCore.kt`
uses the `requirePrepareCert` supplier; when it is on, `verifyPrepareBinding`
checks the certificate signature over the exact body and passes the bound
digest to the three-argument `onPrepare`. Its `canonicalFingerprint` applies
the same trim, colon removal and bounded-hex rule as TrustGate. The app's
`app/src/main/kotlin/app/flit/share/FlitEngine.kt` exposes `evaluatePrepareOffer`
and `decideAnnounce` / `admitAnnounceForIngest` as testable seams that call the
core gates. The CLI's `harness/src/main/kotlin/app/flit/share/harness/Harness.kt`
builds an equivalent `TrustGate.Config` and read-only store view inside
`ConsoleDelegate`. These are source-level wiring facts; the test counts for
those classes are recorded in the 2027-01-13 note.

## Proof and authority

`protocol/src/main/kotlin/app/flit/share/protocol/circle/CircleCore.kt` holds
the JOIN-proof constants and functions: the five-minute bucket grid, the
`FLITJOIN1` length-prefixed nonce, the one-key `{proof}` body gate and
`joinProofFailure` with its `join proof required`, `invalid join proof` and
`join proof replay` precedence. `ServerCore.circleRoute` applies the failure
gate after shape, size and certificate-header checks and answers with a
host-signed 200 refusal before calling the delegate. `JoinProofTest` has 15
cases in the XML. The historical count for this class was 14; the additional
case pins that a validly signed but refused JOIN proof leaves the pin store
unchanged.

`protocol/src/main/kotlin/app/flit/share/protocol/circle/CircleAuthority.kt`
gains `authorityOf`, which classifies a verb as NONE, MEMBER, OFFICER or OWNER:
`role` requires OWNER, `rekey` requires an officer, an `admin` `kick` requires
an officer while a vote kick needs only membership, and JOIN is never
foldable. `foldable` takes this classification as an optional fourth argument
defaulting to `authorityOf`, so existing three-argument call sites switch
rules at the same instant. `CircleAuthorityTest` has 16 cases in the XML; the
historical count was 12. The four added cases cover owner-only role folds,
officer-only admin kick against a member vote kick, refusal of all privileged
verbs when no roles are supplied, and the classification itself on hostile
bodies. `CircleCoreTest` stays at 12, `CircleHardeningTest` at 14 and
`CourierAuthorizationTest` at 2; they are dependency re-runs, not new work at
this layer. The app and CLI fold sites in `FlitCircleSync.kt`,
`FlitCircleStore.kt`, `FlitCircleHost.kt`, `Harness.kt` and `CourierCli.kt`
already call the shared gate.

## Evidence

- CODE — the gate files, constants, refusal order, delegate bridges and fold
  call sites listed above match the source; no route, header, DTO field, QR
  parameter, store field, schema field, signature primitive or v2.2 status
  changed at this layer.
- PURE — the core gates, the protocol announce decision, the JOIN proof and
  the authority classification are JVM-only assertions with injected clocks,
  caller-owned state and no socket, camera, radio or Android framework.
- SIM-HARN — the route-level proof cases drive production `ServerCore` through
  in-process exchanges, and the announce dispatch cases call the production
  dispatcher without a multicast socket. No hardware endpoint is involved.

There is no DEVICE label. No phone, second phone, radio, carrier or camera
walk was performed for this layer, and the earlier on-device Circle records
are not extended here.

## Limitations

- The core gates own no state. Their verdict is only as good as the caller's
  `seen` map, `nowMs` and persisted trust lists; a restart or a different
  process starts with whatever the caller supplies.
- The protocol announce class owns its table and register permits in one
  process. A separately started process has no memory of them.
- JOIN proof usefulness is bounded by the five-minute bucket grid with a
  one-bucket tolerance, and the replay reservation is process-local. The
  invite remains a bearer credential while it is valid.
- `authorityOf` is a structural classification. It assumes an earlier gate
  performed verification and id reservation; it is not an authenticity claim.
- The TrustGate result code is a log label. It is not a certificate, a pin or
  an identity assertion on its own.
- No larger mesh, physical carrier, camera decode or timing figure is
  attached to these counts. No security proof is asserted by this record.
