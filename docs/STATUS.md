# Current state

Remote `master` is `0c6d562`. This state record and the failure analysis are
new additions to the tree. The latest tag is `v0.3.1`; source still declares
`0.3.1` with code `4`, and `v0.3.2` is planned in the docs but not tagged or
released.

The full rig is green: 443 protocol, 167 core, 254 app and 108 harness tests,
972 total, with zero failures, errors and skipped cases. Debug APK, release APK,
CLI install tree and CLI zip all build successfully.

## Shipped capability

The phone app and `flit-cli` cover direct LAN transfer, BLE policy and carrier
decisions, discovery, resumable upload, per-drop content keys, delivery receipts,
Circle messages, polls, votes, roles, kicks, keyed file drops, the shared line
pad, courier handoff and QR air-gap parcels. The protocol module is JVM-pure and
holds the canonical bytes, body gates, authority rules, replay primitives,
envelope codecs and relay core.

The security wave landed prepare certificate and signature binding, sender-side
TLS peer pins, panic origin signatures, shared Circle authority, digest-keyed
JOIN proof single-use, expiry-aware replay retention, register admission gates,
relay policy, UDP announce admission, keyed-drop landing confinement, intake
caps and dependency upgrades. Final source review found no P0 or P1 remaining
in those audited areas.

## Evidence boundary

Evidence is CODE, PURE and SIM-HARN. No DEVICE evidence exists for the security
wave. The last on-device walk covered install, launch and render, plus a partial
feature walk over one phone and one CLI peer: join, messages, file landing,
pad search, poll and vote. The phone currently installed holds an earlier
`v0.3.1` build from before the security wave.

No security proof is claimed. Trust gates remain opt-in and off by default for
v2.2 compatibility. Pin and replay state are process-local; there is no durable
pin store or durable replay ledger. No desktop GUI exists.

## Known gaps under analysis

A general host-side download service is not available. The app can serve an
active sharing session, but there is no stable PC- or phone-hosted download
surface that lets another device fetch files without an accompanying send flow.
The existing download API is disabled by default and is not a product feature.

Other open items are recorded in `docs/QUALITY-CONFIDENCE.md` and
`docs/roadmap/security-hardening.md`: durable trust anchors, restart-safe replay
memory, adversarial campaign and advisory scan, and a device walk for the
security wave.

Desktop work remains parked behind Gate 0. The planned pin set is Kotlin
`2.4.21`, Compose Multiplatform `1.12.2` and Android Compose BOM `2026.09.00`,
subject to a configuration run and a single resolved Compose set. `flit-cli`
remains the Linux companion.

## Next sequence

1. Analyse failures with layered review waves, including the missing host-side
   download service.
2. Decide which failures are product gaps, which are evidence gaps and which are
   deliberate scope boundaries.
3. Implement only changes with bounded surface and clear tests.
4. Install the current release APK on the phone and walk the security-sensitive
   flows with a CLI peer.
5. Decide whether durable pin and replay state is required before calling the
   security closeout complete.
6. Publish `v0.3.2` only after the rig and device evidence agree.
