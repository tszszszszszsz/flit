# 2026-12-22 — JOIN proof and Circle authority (Protocol)

Layer: 2026-12-22 Protocol. This layer adds a stateless JOIN proof to the Circle
admission path and a shared, fold-time authority gate for Circle events. The
proof is a digest of a domain-tagged nonce carried in a signed JOIN event; the
host recomputes it from the request and a five-minute bucket before the delegate
sees the join. The authority gate reads only a few event fields and a supplied
fingerprint-to-role map, and decides which verbs may enter a fold. Both are
JVM-pure and live in the shared protocol module.

The layer adds no route, header, JSON DTO field, QR parameter, persisted store
field, schema field, signature primitive or v2.2 status. The only wire-visible
change is a refusal: a JOIN without a valid proof is answered with a host-signed
reason and never reaches the delegate. App and CLI enforcement is recorded in
the 2026-12-23 Integration note; the 2026-12-17 device walk remains deferred.

Counts below come from the JUnit XML result files of a fresh JVM run under
`protocol/build/test-results/test/`, `app/build/test-results/testDebugUnitTest/`
and `harness/build/test-results/test/`. No device observation is claimed.

## Files in this layer

| File | Content |
| --- | --- |
| `protocol/src/main/kotlin/app/flit/share/protocol/circle/CircleAuthority.kt` | New JVM-pure object; the `foldable` decision and its role set |
| `protocol/src/main/kotlin/app/flit/share/protocol/circle/CircleCore.kt` | JOIN-proof constants and functions: `JOIN_PROOF_BODY_KEYS`, `JOIN_PROOF_TAG`, `JOIN_PROOF_BUCKET_MS`, `JOIN_PROOF_BYTES`, `joinProofBucket`, `joinProofNonce`, `joinProofDigest`, `joinProofBody`, `joinProofBytes`, `validJoinProofBody`, `joinProofFailure` |
| `protocol/src/main/kotlin/app/flit/share/protocol/server/ServerCore.kt` | `circleRoute` calls `joinProofFailure` on `ACTION_JOIN` and returns a host-signed refusal before the delegate |
| `protocol/src/main/kotlin/app/flit/share/protocol/client/ProtocolClient.kt` | The convenience `circleJoin` overload that took invite fields directly is removed; callers build `CircleRequest` explicitly. No wire shape changes |
| `protocol/src/test/kotlin/app/flit/share/protocol/CircleAuthorityTest.kt` | New pure truth-table and totality tests for `foldable` |
| `protocol/src/test/kotlin/app/flit/share/protocol/JoinProofTest.kt` | New pure nonce-framing, body-gate, failure-gate and replay tests |
| `protocol/src/test/kotlin/app/flit/share/protocol/CircleHardeningTest.kt` | Three added route-level JOIN-proof cases |
| `protocol/src/test/kotlin/app/flit/share/protocol/CircleCoreTest.kt` | Existing cases adjusted to the shared replay entry point |
| `docs/SECURITY.md`, `docs/CONTRACTS.md`, `docs/FAILURE-MODES.md`, `docs/OPERATIONS.md`, `docs/TESTING.md` | Gate descriptions, refusal branches and the test inventory; consolidated in the 2026-12-24 Docs note |
| `docs/completions/2026-12-22-join-proof.md` | This layer record |

No Android import, socket, camera, file write or second signature path enters
the protocol module. `CircleAuthority` uses `Locale.ROOT` for case folding and
holds no mutable state.

## Exact gate rules

`CircleAuthority.foldable(event, circleId, roles)` is total and returns a
boolean:

1. `event.type` must be in `CircleEvent.TYPES`; an unknown or blank type refuses.
2. `authorFp` is trimmed and uppercased with `Locale.ROOT`; a blank author refuses.
3. `event.circleId` must equal the argument exactly; it is not trimmed.
4. `JOIN` is never foldable: it is a handshake, not a log entry.
5. `REKEY` folds only when the lookup `roles[author]`, itself trimmed and
   uppercased, is in `KEY_ROLES = {"OWNER", "ADMIN"}`.
6. Every other known verb folds once the circle matches.

No signature is checked, no clock is read, no store is touched and no memory is
kept between calls. Non-authority fields (`id`, `body`, `sig`, `lamport`,
`createdMs`, `cert`) cannot change the answer.

The JOIN proof gates are:

- `JOIN_PROOF_BODY_KEYS = {"proof"}`. The body holds one string field; a
  missing, extra, renamed, non-string, blank or over-`MAX_FIELD_CHARS` (256)
  value is refused, as is a whole body over `MAX_BODY_CHARS` (16 KiB).
- The value is a 32-byte SHA-256 digest (`JOIN_PROOF_BYTES = 32`) encoded as
  unpadded Base64URL: 43 characters, no `=`, `+` or `/`. The decoder refuses
  the standard alphabet, whitespace, padding and non-canonical trailing bits;
  an encode-decode round trip must return the input string.
- `joinProofNonce` frames a domain tag `FLITJOIN1`, then four length-prefixed
  UTF-8 fields — host fingerprint, circle id, invite, member fingerprint —
  each trimmed and uppercased with `Locale.ROOT`, followed by an eight-byte
  bucket index. The same four-byte length prefix used by `canonical` prevents
  delimiter smuggling; a `FLITCIRCLE1` event signature cannot be replayed here.
- `joinProofBucket(createdMs) = Math.floorDiv(createdMs, JOIN_PROOF_BUCKET_MS)`,
  with `JOIN_PROOF_BUCKET_MS = CLOCK_SKEW_MS = 5 * 60 * 1000L`. `floorDiv`
  keeps the function total on negative input.
- `joinProofFailure(req, hostFp, nowMs)` returns `null` for any action other
  than `ACTION_JOIN`, so SYNC needs no proof. For JOIN the precedence is:
  `join proof required` when no JOIN event is present or none carries a
  `proof` key; `invalid join proof` when no JOIN event has a matching member
  and circle, an admissible body, a passing `verify`, and a positive `nowMs`,
  or when the recomputed digest differs; `join proof replay` when the event
  bucket is outside `[current - 1, current + 1]`, the same proof appears twice
  in the request, or `fresh(proof.id, nowMs)` refuses.
- `verify` binds the embedded certificate's own SHA-256 DER to `authorFp`
  before checking the signature and pins only on success, so a proof naming a
  victim cannot borrow the victim's pin.
- `ServerCore.circleRoute` applies `joinProofFailure` after the shape, size and
  certificate-header checks and before the delegate. A failure is a decodable
  200 `CircleResponse`, signed by the host, that the joiner can pin; the
  delegate is not called.

The shared replay guard still applies to signed events: `REPLAY_WINDOW_MS = 6 h`,
`CLOCK_SKEW_MS = 5 min`, `MAX_SEEN = 4096`, `MAX_EVENTS_PER_REQUEST = 1024`,
`MAX_REQUEST_BYTES = 8 MiB`, with precedence
`Invalid → OutsideWindow → Duplicate → Accept`; only Accept reserves an id.
Callers verify before `evaluate`/`accept`.

## Rig inventory

| Module | Before | After this layer | Task basis |
| --- | --- | --- | --- |
| `protocol` | 330 | 359 | JVM-pure protocol tests |
| `app` | 309 | 309 (unchanged here) | JVM tests of Android-free seams |
| `harness` | 85 | 85 (unchanged here) | In-process CLI and end-point rig |
| Total | 724 | 753 | 0 failures, 0 errors, 0 skipped |

The protocol increase is `12 + 14 + 3 = 29` cases. The harness stands at 85 at
this point with the in-process proof and authority cases of this wave; the
integration layer takes it to 87. All assemblies are green at this inventory.
No timing or hardware result is attached to these counts.

## Tests

| Class | Cases | What it pins |
| --- | --- | --- |
| `CircleAuthorityTest` (new, protocol) | 12 | Full `foldable` truth table: JOIN never folds, REKEY only for OWNER/ADMIN, ordinary verbs fold only in their circle, fingerprint and role normalisation, empty roles, and totality on hostile field values that carry no authority meaning |
| `JoinProofTest` (new, protocol) | 14 | An independent golden vector for the `FLITJOIN1` nonce and deterministic casing/whitespace normalisation; binding of every nonce field and the bucket; the one-key, 32-byte unpadded Base64URL body and its strict gate; minted proof accepted, non-JOIN requests needing none; missing, legacy, malformed and extra-key bodies refused; host, circle, invite and member binding; tampered signature, changed body and swapped certificate refused; stale and future buckets and a proof duplicated in one request refused as replay; forged fingerprint refused without pinning the victim |
| `CircleHardeningTest` (existing, protocol) | +3 (14 total) | Route-level gate: a proof-less JOIN is a signed refusal and never reaches the delegate, a valid signed proof reaches it, and a proof signed by a stranger claiming the member fingerprint is refused with a verifiable signature |

Case counts are read from the JUnit XML of the fresh run, not from a target
figure. `CircleCoreTest` and the other protocol suites were re-run unchanged in
behaviour except for the shared replay entry point. No app test changes belong
to this layer; the app and harness changes are the 2026-12-23 Integration layer.

## Evidence

| Label | Basis |
| --- | --- |
| CODE | `CircleAuthority.kt`, `CircleCore.kt` and `ServerCore.kt` match the gates, constants and precedence recorded above; no new route, header, DTO field, store field, schema field, signature primitive or v2.2 status |
| PURE | The two new protocol classes and the three added route cases ran green with zero failures, errors and skipped cases in the protocol module |
| SIM-HARN | The three route cases exercise production `ServerCore` through in-process exchanges; the harness proof and authority cases of this wave also run with in-process endpoints, not on a device |
| DEVICE | No phone, second phone, radio, carrier or camera observation is made on this date |

The weakest recorded level governs each claim. The 2026-12-17 walk remains
deferred, with rig evidence only.

## Limitations

- A captured proof is single-use only in-process. The id is reserved once in
  the process-local bounded seen set; a restart or another process has no
  memory of that reservation.
- Proof validity is bounded by the bucket grid. The host accepts the current
  bucket with a one-bucket tolerance; across further rollovers the same proof
  is replay and the invite remains a bearer credential that can be used under
  the holder's own key while it is valid.
- The replay set is process-local and bounded at `MAX_SEEN = 4096`. It is not a
  shared or durable replay store, and eviction can forget an older id.
- `foldable` is a structural decision over `circleId`, `type`, `authorFp` and
  the supplied role map. It assumes an earlier gate performed verification and
  id reservation; a caller feeding it unverified events gets a structural
  answer, not an authenticity claim.
- No physical device walk is part of this layer. There is no RFCOMM path, camera
  decode or on-device observation, and the 2026-12-17 walk stays deferred.
- A larger mesh is unverified. The exercises use the small in-process identity
  set; no walk with a Circle larger than the baseline members is recorded.
- `MAX_FIELD_CHARS`, `MAX_BODY_CHARS`, `MAX_REQUEST_BYTES` and the 500-event log
  cap are unchanged; the proof adds no unbounded state and no new cap.
- No performance or timing figure is asserted by this record.
