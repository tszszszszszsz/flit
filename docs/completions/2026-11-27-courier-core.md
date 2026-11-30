# 2026-11-27 — Courier core (Protocol)

Layer: 2026-11-27 Protocol. The work is the signed, recipient-encrypted
`CourierEnvelope` codec and the bounded `CourierCarryCore` parcel bag, both
JVM-pure in `protocol/`, with pure tests only. The code is built ahead of the
scheduled commit date; the commit stays on 2026-11-27.

## Files in the layer

| File | Content |
| --- | --- |
| `protocol/src/main/kotlin/app/flit/share/protocol/courier/CourierEnvelope.kt` | `CourierEnvelope` DTO, `seal`, canonical bytes, `encode`/`decode`, `verify`, `open` |
| `protocol/src/main/kotlin/app/flit/share/protocol/courier/CourierCarryCore.kt` | `CourierParcel`, `CarryState`, `Decision`, body gate, reducers, `encode`/`decode` |
| `protocol/src/main/kotlin/app/flit/share/protocol/circle/CircleEvent.kt` | `PARCEL` verb added to `TYPES`; existing verb values and DTO fields unchanged |
| `protocol/src/test/kotlin/app/flit/share/protocol/CourierEnvelopeCodecTest.kt` | Canonical bytes, codec, bounds, tamper |
| `protocol/src/test/kotlin/app/flit/share/protocol/CourierEnvelopeCryptoTest.kt` | `seal`/`open`, key wrap, wrong certificate, replay window |
| `protocol/src/test/kotlin/app/flit/share/protocol/CourierCarryCodecTest.kt` | `CarryState` codec, tolerant repair, trim |
| `protocol/src/test/kotlin/app/flit/share/protocol/CourierCarryDecisionTest.kt` | Precedence, quota, TTL, `ack`, `select`, `expire`, `merge` |
| `protocol/src/test/kotlin/app/flit/share/protocol/CourierIntegrationTest.kt` | `seal` → parcel → carry → `open` → `CircleCore.verify`; `TYPES` count |

Package `app.flit.share.protocol.courier` imports `CircleCore`, `CircleEvent`,
`WireJson` and the JCA crypto primitives only. No Android import, socket, file
or preference persistence is present in this layer.

## Public surface

`CourierEnvelope`:

```
CourierEnvelope(id, circleId, fromFp, toFp, createdMs,
                alg, ivB64, cipherB64, keyWrapB64, sig = "", cert = "")
seal(events, circleId, fromFp, toFp, recipientCert, identity, nowMs)
encode(e): String
decode(raw): CourierEnvelope?
verify(e): Boolean
open(e, identity, nowMs): List<CircleEvent>?
```

`CourierCarryCore`:

```
CourierParcel(id, toFp, fromFp, createdMs, expiresMs, event)
CarryState(parcels)
Decision = Accept | Duplicate | Expired | OutsideWindow | Invalid | Full

validParcelBody  parcelOf  canonical  order  evaluate  fresh
fold             select    ack        expire merge     clear
encode           decode
```

`CircleEvent.PARCEL = "parcel"` is the tenth verb.

## Constants

| Constant | Value | Basis |
| --- | --- | --- |
| `PARCEL` / `PARCEL_BODY_KEYS` | `"parcel"` / `{"toFp","envelope"}` | Defined here; consumed by the carry core |
| `MAX_ENVELOPE_CHARS` | `12 * 1024` | Base64 cap; framed body stays under `CircleCore.MAX_BODY_CHARS` |
| `MAX_PARCELS` | `256` | Bounded carrier bag |
| `MAX_SEEN` | `4096` | Bounded id reservation |
| `REPLAY_WINDOW_MS` | `6 * 60 * 60 * 1000L` | Reused from `CircleCore` by reference |
| `CLOCK_SKEW_MS` | `5 * 60 * 1000L` | Reused from `CircleCore` by reference |
| `MAX_FIELD_CHARS` | `256` | Reused from `CircleCore` for `toFp` and id bounds |
| `MAX_PARCEL_TTL_MS` | `24 * 60 * 60 * 1000L` | Sole expiry source; `expiresMs = createdMs + MAX_PARCEL_TTL_MS` |
| `KEY_BYTES` / `IV_BYTES` | `32` / `12` | AES-256-GCM |
| `ALG` | `"AESGCM+RSAOAEP256"` | Closed value set; any other value is Invalid |

Domain tag `FLITCOURIER1` covers the envelope canonical bytes with local
4-byte length-prefixed `field` values; `sig` and `cert` are excluded and `verify`
binds the certificate's own SHA-256 DER to `fromFp` before pinning. Domain tag
`FLITCARRY1` is a bag digest, not a second envelope signature.

## CircleCore

No additive shared change was needed in `CircleCore`. Its canonical bytes,
`validShape`, `verify`, `evaluate` and acceptance rules are unchanged; `PARCEL`
is additive because `validShape` is type-agnostic bounds.

## Verification

Fresh pure run, counts recorded at integration:

```
source env.sh && ./gradlew --no-daemon --rerun-tasks :protocol:test
```

Result: `BUILD SUCCESSFUL`. The protocol module moves from the 82-test baseline
to 136 tests (82 + 54 added cases). The full rig — `protocol:test`,
`app:testDebugUnitTest`, `harness:test`, `assembleDebug`, `assembleRelease`,
`installDist`, `distZip` — is green after `--rerun-tasks`.

The baseline inventory stays 302 (82 protocol + 210 app + 10 harness) as a
historical snapshot; the current total is 356 (136 + 210 + 10).

## Tests

The five classes add 54 cases and are not part of the 82-protocol / 302-total
baseline. Case counts come from the JUnit XML; test names are the `@Test`
method declarations in each class.

| Class | Cases | Test names | What it pins |
| --- | --- | --- | --- |
| `CourierEnvelopeCodecTest` | 11 | canonical field order, caps, truncations, trailing fields, tamper | Domain-tagged, length-prefixed canonical bytes with `sig`/`cert` excluded and changed by each covered field; `encode`/`decode` round-trip and refuse truncated, oversized or unknown-field bytes; `MAX_ENVELOPE_CHARS`; tamper refusals |
| `CourierEnvelopeCryptoTest` | 13 | seal/open, wrap, wrong cert, forged signature, window edges | `seal`/`open` byte-exact round-trip through AES-GCM and RSA wrap; wrong certificate, cert digest not equal to `fromFp`, forged signature and tampered ciphertext/IV/wrapped key fail `verify` without a pin write; `createdMs` outside the replay/skew window refused by `open` |
| `CourierCarryCodecTest` | 10 | encode/decode, corrupt blob, duplicates, expiry repair, trim | `CarryState` round-trip; tolerant repair of corrupt, default-valued and absent fields; oversized bag trimmed to `MAX_PARCELS` |
| `CourierCarryDecisionTest` | 14 | precedence, quota, TTL, ack, select, expire, merge | Six-way `evaluate` precedence, bounded `MAX_PARCELS` quota, TTL derived only from `createdMs + MAX_PARCEL_TTL_MS`, and `fold`, `select`, `ack`, `expire`, `merge`, `clear` on `CarryState` |
| `CourierIntegrationTest` | 6 | seal → parcel → carry → open → verify; TYPES count | `seal` → `parcel` → carry fold → `open` → `CircleCore.verify`; `PARCEL` present in `CircleEvent.TYPES` with existing verb values and DTO fields unchanged |

Identities in tests come from `TlsIdentity.generate()`. Tests are pure JVM
tests and use no Robolectric.

## Evidence

| Label | Claimed here | Basis |
| --- | --- | --- |
| CODE | Yes | Wire shapes, domain tags, constants, decision precedence and bounds in the two courier files and the `PARCEL` verb in `CircleEvent.kt` |
| PURE | Yes | The five test classes run by `:protocol:test` |
| SIM-HARN | No | CLI carrier handoff is the 2026-11-30 layer |
| DEVICE | No | Three-node courier walk is the 2026-12-02 layer |

No radio, phone, camera or QR observation is claimed on this date. Harness and
device rows stay unclaimed until their dated walks run.

## Limitations

- No carrier loop, `Link` handoff or persisted bag (`courier-bag/`, `courierBag`)
  is part of this layer; `CarryState` durability belongs to the caller.
- The content key exists only inside `seal` and the wrapped blob. No plaintext
  event, content key or private material is written to disk or logged; logs
  carry counts, ids and fingerprints only.
- `open` applies the replay window and skew to `createdMs` and owns no replay
  set; id dedup is the carry core's bounded `evaluate` guard.
- Authorship of decrypted events is checked by `CircleCore.verify` at fold
  time, never by the carrier. The envelope's `cert` is the same signer named by
  the `parcel` event's `authorFp`; no second trust anchor is introduced.
- Multi-hop relay, Wi-Fi Aware, QR framing, per-drop keys, delivery receipts,
  Circle pad and any v2.2 wire change stay out of scope.

## Verification status

All counts and the rig result were recorded from the fresh `--rerun-tasks` run
after the nested type alias and decision-precedence fixes. No placeholder
remains. Carrier (`SIM-HARN`) and three-node (`DEVICE`) evidence stay with the
2026-11-30 and 2026-12-02 layers.
