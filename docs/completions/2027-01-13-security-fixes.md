# 2027-01-13 — QA fixes: pins, panic origin, register, relay, app and CLI (Integration)

Layer: 2027-01-13 Integration. This wave closes six QA gaps found around the
trust gates. The pin store now separates verified identities from
unauthenticated route pins; panic alerts carry an origin signature that
survives relay forwarding; the direct register route applies the same
boundary checks as the announce gate; the relay refuses unsafe binds and
bounds its registry and failure state; the app wires the pure gates into its
seams; and the CLI state loader drops unsigned or forged rows. No v2.2 route,
header, DTO field, QR parameter, persisted store field, schema field,
signature primitive or status code changes. The panic origin fields are
additive to the Flit-only alert type and carry defaults that older alerts omit.
Evidence labels are CODE, PURE and SIM-HARN only; there is no DEVICE
observation.

Counts come from the JUnit XML of the last full four-module run, cross-checked
with source `@Test` annotations. That run recorded 443 protocol + 167 core +
254 app + 108 harness = 972 cases with zero failures, zero errors and zero
skipped cases. The figures are historical for this layer because the release
assembly rerun after the R8 keep-rule fix has not produced a result yet.

## Pin flooding

`protocol/src/main/kotlin/app/flit/share/protocol/tls/TlsIdentity.kt` now uses
a two-tier `PinCache`. Verified pins live in a protected LRU capped at
`MAX_PINS = 512`; bare `pin` calls land in an unverified LRU capped at
`UNVERIFIED_PIN_QUOTA = 16` and can neither evict nor downgrade a verified
entry. `pinVerified` installs directly into the protected tier. A successful
`verifyWithCertificate` marks the certificate digest in a separate
access-ordered ring bounded at `MAX_PINS`; the next `pin` of that same digest
within a five-second grace window promotes it. Oldest-accessed markers are
dropped unconditionally, so a verification flood cannot grow the ring. The one
crypto check shared by both paths enforces certificate validity, the digital
signature key usage bit and canonical Base64 before a signature is accepted.

`PinCacheTest` has 6 cases in the XML, unchanged from its historical count,
covering eviction at cap, recency refresh, normalised keys and forget/clear.
`PeerIdentityPinTest` has 12 cases, including that verified pins survive an
unauthenticated pin flood, that recent verification markers stay hard bounded,
and that non-canonical Base64, expired certificates and certificates without
digital-signature usage are refused. `ReplayBoundaryTest` has 5 cases: a flood
of live ids does not evict an in-window replay record, expired reservations are
reclaimed only past the retain deadline, a future-skew id stays reserved, and
a re-signed or captured JOIN proof stays replay-refused after a live-id flood.
`TlsIdentityTest` stays at 2.

## Panic origin

`protocol/src/main/kotlin/app/flit/share/protocol/model/PanicAlert.kt` adds
`originFp`, `originSig` and `originCert` as additive fields under the existing
Flit route. `protocol/src/main/kotlin/app/flit/share/protocol/panic/PanicCore.kt`
signs the hop-zero canonical at creation and copies the origin signature and
certificate unchanged through `forward`; only the current-hop `sig` is
re-signed by a relay. `verifyOrigin` binds the embedded certificate's own
SHA-256 DER to `originFp` and checks `originSig` over the rebuilt hop-zero
bytes, so a relay that names a victim while signing as itself is refused.
`validShape` caps all three new fields, and a blank `originFp` falls back to
the verified sender for older alerts.

The panic route in `ServerCore` checks the sender certificate binding, the
current-hop signature and `verifyOrigin` before calling the delegate; a quiet
refusal for duplicate, stale or hop-spent alerts touches neither pin tier, and
the id is recorded only after delivery. `PanicTest` has 12 cases in the XML;
the historical count was 6. The six added cases pin hop-zero origin signing
and certificate carriage, rejection of an attacker-signed victim-origin alert,
preservation of the origin pair across a forward, refusal of a hop reset with a
foreign origin signature, the legacy blank-origin fallback and oversized-field
refusals. `ProtocolRefusalTest` also checks that refused panic and Circle
requests leave the pin store unchanged.

## Register gate

`ServerCore.validRegisterOffer` applies a boundary check to every direct
`POST /register` before `onRegister` sees the peer. `canonicalRegisterFingerprint`
requires bounded 64-hex input, `validRegisterOrigin` accepts loopback,
link-local, site-local, private and unique-local IP origins plus non-IP carrier
labels, and refuses global unicast origins. This closes the direct-post path
against offers the announce gate would never have admitted.

`ProtocolRefusalTest` has 22 cases in the XML; the historical count was 4. The
added cases cover prepare binding with the gate on and off, a replayed
certificate without a body signature, a declared fingerprint that differs from
the certificate digest, a foreign signing key, a self-consistent attacker
still refused by the delegate, colon-form declaration binding, oversized
control bodies answered 413, an upload body longer than its declared size,
spoofed and malformed register offers refused, and a legitimate register
fingerprint still reaching the delegate. `ProtocolServerDoorTest` has 1 case,
showing that an externally served `Link` answers with the listener's exact
route semantics.

## Relay

`protocol/src/main/kotlin/app/flit/share/protocol/relay/RelayCore.kt` adds a
strong-token policy: `isStrongToken` estimates entropy, `bindTokenProblem`
reports a missing or weak token for a non-loopback bind, and `requireStrongToken`
gates every hello when that policy is on. Wrong-token failures back off per
source IP with the failure map bounded at 4096 addresses; parked tickets share
one cap of 1024 and age out on a finite TTL. `RelayServer.kt` refuses a
non-loopback bind with a missing or weak token before listening and applies
the same policy inside the pairing core. `RelayEndpoints.readReply` reads the
OK line one byte at a time under `RelayHello.MAX_LINE`, leaving any TLS bytes
written after the newline readable on the paired stream.

`RelayCoreTest` has 20 cases in the XML; the historical count was 11. The added
cases pin unknown-ticket withdrawal, re-offer supersession, control characters
in hellos, the global cap under a subscription flood, per-IP throttling, a
good hello clearing backoff state, bounded failure state across many IPs,
expired park retirement, weak-token refusal and the non-loopback bind policy.
`RelayServerTest` has 17 cases; the historical count was 6. It exercises a real
3 MiB TLS send and hash-exact death/resume, the wire token gate and throttle,
the byte-wise OK reply, an unreachable dial reporting a throw, gated prepare
over both relay and direct TLS paths, `qrOnly` against a known unpinned sender,
legacy auto-accept with both gates off, landing names that cannot escape the
destination directory, forged unsigned Circle state dropped on load, a weak
token refused before listen, an oversized park timeout rejected, CLI
`circleFreshEvents` dropping member ROLE and admin KICK, and owner-only
permissions on keyed-drop plaintext.

## App wiring

`app/src/main/kotlin/app/flit/share/FlitEngine.kt` wires the pure gates into
`evaluatePrepareOffer`, `decideAnnounce` and `admitAnnounceForIngest`, and adds
`expectedPinFor` / `targetForPeer` so a TLS pin comes only from persisted trust
rows, never from a `PeerInfo` claim. Panic handling in the app trusts an origin
only after its own signature verifies. `FlitEngineTest` has 49 cases in the
XML; the historical count was 31. The added cases pin both prepare flags, a
throwing store, announce decisions against self stubs, off-link ports and
replays, an injected announce clock, panic origin verification, an explicit
`relayFor` opt-in for dispatch, the TLS leaf pin on dispatch targets, a spoofed
`PeerInfo` that cannot move the pin, intent preselection, bounded share extras
and landing names confined to the destination.

`FlitCircleSyncTest` has 63 cases; the historical count was 56. Added cases
refuse role, kick, rekey and JOIN from a non-member, drop a member-signed admin
kick while admitting an officer admin kick and a member vote kick, and check
that fan-out and sync refuse a forged answer or an answer signed by someone
other than the host dialed. `FlitCircleHostTest` has 23 cases and
`FlitCircleStoreTest` 35, carrying the shared authority decision into host
filtering and store folds. `FlitKnownTest` has 11 and `MainActivityUiTest` 9.

## CLI state

`harness/src/main/kotlin/app/flit/share/harness/CourierCli.kt` and
`Harness.kt` load one-shot state and apply the same fold and pin rules as the
app. `CourierCliTest` has 26 cases in the XML; the historical count was 21.
The added cases pin that receive refuses a forged outer signature and a
non-member outer author, that a stale inner event is refused inside a fresh
envelope while a fresh inner event still folds, that a forged unsigned event
is dropped from Circle state on load, and that courier state writes are
private to the local user. `CircleHarnessTest` stays at 18, `CourierCarrierHarnessTest`
at 10, `ParcelCliTest` at 11, `ParcelCliHarnessTest` at 3, `PadCliTest` at 8,
`DropKeyCliTest` at 10 and `ReceiptCliTest` at 5. All of these run in-process
on one box.

## Evidence

- CODE — the two-tier pin store, origin fields and verification, the register
  boundary checks, the relay bind policy and the app/CLI seam wiring match the
  source; no new v2.2 wire surface was introduced, and the panic fields are
  additive with defaults.
- PURE — pin bookkeeping, panic canonicalisation and origin verification,
  register fingerprint and IP checks, relay pairing arithmetic and the app/CLI
  pure seams run in JVM tests with injected clocks and no Android framework.
- SIM-HARN — route, relay and CLI cases drive production `ServerCore`,
  `RelayServer`, `ProtocolClient` and CLI state files in one process. No
  endpoint is a phone, camera or radio.

There is no DEVICE label. No phone, second phone, carrier, camera or RFCOMM
walk was performed for this wave.

## Limitations

- Pin and replay state remain process-local and bounded. A restart or another
  process has no memory of verified promotions, unverified pins or reserved
  ids; the five-second promotion grace and the JOIN bucket grid still bound
  usefulness.
- The relay token is the only access control the relay has. A loopback bind is
  deliberately allowed without one; off-box binds require a high-entropy
  token, but the token is still bearer material.
- Panic origin binding covers the declared origin, not the human meaning of a
  message. Receiving an alert never acts on the device; any visible or
  forwarded action stays in the app layer.
- The register gate is a textual origin approximation. It does not resolve
  names and it deliberately lets non-IP pipe carriers through.
- No larger mesh, physical transfer, camera decode or timing figure is
  attached to these counts. No security proof is asserted by this record.
