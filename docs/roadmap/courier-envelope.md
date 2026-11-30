# Courier envelope and carry core

Defines the signed, recipient-encrypted envelope that a Circle member seals for an absent peer and the wire shape that lets a carrier hold the bytes in a bounded bag until a later encounter.

## Date and layer

2026-11-27 — Protocol. Pure JVM wire work in `protocol/`: the envelope codec, the `parcel` event body it rides in, and tests. No carrier loop, no app state, no device walk.

## Depends on

2026-11-26 (Device) — the three-node baseline walk supplies the phone plus two CLI identities the bag serves. The codec reuses `CircleCore`, `CircleEvent`, `TlsIdentity` and `WireJson`, all already in `protocol/`. The bounded bag and its decision rules are the same-day carry core; no later date is required.

## Scope

A member who cannot reach an absent peer seals the events into a `CourierEnvelope`: the events are encrypted to the recipient's device certificate, the envelope is signed by the sender, and a carrier holds the opaque bytes until it meets the recipient. The bytes travel as the `envelope` field of a signed `parcel` event over `/circle/sync`; the carrier never decrypts them.

This piece defines `PARCEL = "parcel"` as a tenth Circle event verb, its closed body keys, the `MAX_ENVELOPE_CHARS` cap, and the envelope DTO, byte codec, canonical signature, verification and open path. The bag, its `evaluate` precedence, `MAX_PARCEL_TTL_MS`, `MAX_PARCELS`, `parcelOf`, `validParcelBody`, `CourierParcel`, `CarryState` and `select`/`ack`/`fold`/`merge` belong to the same-day carry core and are consumed unchanged.

## Design

New package `app.flit.share.protocol.courier`, JVM-pure like `circle/` and `panic/`, with one file, `CourierEnvelope.kt`. The carry core lives in `CourierCarryCore.kt` alongside it.

Event verb and body, defined here and enforced by `validParcelBody` and `parcelOf` in the carry core:

```
PARCEL = "parcel"
PARCEL_BODY_KEYS = setOf("toFp", "envelope")
MAX_ENVELOPE_CHARS = 12 * 1024
```

The body is exactly `{"toFp":"<UPPERCASE fingerprint>","envelope":"<Base64 envelope bytes>"}`. `toFp` is bounded by `CircleCore.MAX_FIELD_CHARS`; the Base64 envelope is bounded by `MAX_ENVELOPE_CHARS`, about `9216` raw bytes, so the whole body stays under `CircleCore.MAX_BODY_CHARS` (`16 KiB`) after JSON framing. An extra key is refused before any id is reserved. `PARCEL` is additive: `CircleCore.validShape` is type-agnostic bounds, so no existing event's signature or acceptance changes.

Envelope DTO:

```
CourierEnvelope(
    id, circleId, fromFp, toFp,
    createdMs: Long,
    alg, ivB64, cipherB64, keyWrapB64,
    sig = "", cert = "")
```

`id` is a UUID minted by `seal`. `circleId` and `toFp` bind the blob to one circle and one recipient so a carrier cannot redirect it. `cert` is the sender's Base64 DER and is the same signer named by the `parcel` event's `authorFp`; no second trust anchor is introduced. The envelope carries no `expiresMs`: expiry is derived once by the carry core as `expiresMs = createdMs + MAX_PARCEL_TTL_MS` in `parcelOf`, and a competing expiry inside the blob would let two copies disagree.

Plaintext is a bounded JSON array of `CircleEvent` serialized with `WireJson` and encrypted with AES-256-GCM. `open` decrypts with the recipient's private key, parses the array and returns `List<CircleEvent>`; no second parcel type is defined, so the name `CourierParcel` stays the carry core's bag row. Events keep their author signatures and embedded certs. Authorship is checked by `CircleCore.verify` at fold time, never by the carrier.

Canonical bytes use the domain tag `FLITCOURIER1` and the same `field` rule as `CircleCore.canonical`: a 4-byte big-endian UTF-8 byte length followed by the bytes. Order is `field(tag)`, `field(id)`, `field(circleId)`, `field(fromFp)`, `field(toFp)`, `writeLong(createdMs)`, `field(alg)`, `field(ivB64)`, `field(cipherB64)`, `field(keyWrapB64)`. `encode` writes those fields followed by `field(sig)` and `field(cert)`; `decode` reverses it and returns `null` on any malformed, missing or oversized field. The Base64 string placed in the body is bounded by `MAX_ENVELOPE_CHARS`. `FLITCARRY1` is the carry core's bag-digest tag and is not an envelope signature; the two tags are distinct by design.

Crypto wrap. `seal` generates a fresh 32-byte content key with `SecureRandom`, encrypts the event array under it, wraps the key with RSA-OAEP/SHA-256 to the recipient certificate's public key, then signs the canonical bytes with `TlsIdentity.sign`. The key exists only inside `seal` and the wrapped blob. `verify` binds `cert`'s own SHA-256 DER to `fromFp` and checks the signature against that certificate before pinning, exactly as `CircleCore.verify` does, so a substituted certificate cannot poison the pin cache. `ALG = "AESGCM+RSAOAEP256"` is the only accepted value; another value is Invalid. The recipient certificate comes from the signed event log or the host roster answer; without it, `seal` refuses.

Constants:

| Constant | Value | Basis |
| --- | --- | --- |
| `PARCEL` / `PARCEL_BODY_KEYS` | `"parcel"` / `{"toFp","envelope"}` | Defined here; consumed by the carry core |
| `MAX_ENVELOPE_CHARS` | `12 * 1024` | Base64 cap; body stays under `16 KiB` |
| `MAX_FIELD_CHARS` / `MAX_SIG_CHARS` / `MAX_CERT_CHARS` | `256` / `1024` / `8 * 1024` | Reused from `CircleCore` |
| `REPLAY_WINDOW_MS` / `CLOCK_SKEW_MS` | `6 h` / `5 min` | Reused from `CircleCore` |
| `MAX_SEEN` / `MAX_PARCELS` / `MAX_PARCEL_TTL_MS` | `4096` / `256` / `24 h` | Carry core bounds; not redefined here |
| `KEY_BYTES` / `IV_BYTES` | `32` / `12` | AES-256-GCM |
| `ALG` | `"AESGCM+RSAOAEP256"` | Closed value set |

Window and replay. `open` refuses `createdMs` older than `REPLAY_WINDOW_MS` or more than `CLOCK_SKEW_MS` ahead. Id dedup on the Circle path is the carry core's `evaluate` over its bounded `seen` set after `parcelOf` and `CircleCore.verify`; the envelope codec adds no second set and never calls `CircleCore.evaluate`/`accept` on an envelope id.

State ownership. The sender owns the content key during `seal` only. The carrier holds the opaque bytes in `CarryState`, whose durable row is owned by the caller (`courierBag` on `CircleState` in the app, `courier-bag/` in the CLI). `TlsIdentity`'s pin cache, bounded at `MAX_PINS = 512`, gains the sender's certificate only after `verify` succeeds. The envelope codec is stateless; the carry core owns the bounded `seen` set, as `CircleCore` owns its replay set.

Seams to later layers: the CLI carrier on 2026-11-30 drives `select`/`ack` over `Link` and persists the bag; the app on 2026-12-01 folds decrypted events through `FlitCircleStore.merge` and drives file-card states; the QR codec on 2026-12-03 frames `encode` bytes unchanged, so there is one envelope codec; per-drop content keys on 2026-12-09 change file-drop semantics but not this transport wrap.

## Invariants

1. Canonical bytes carry the `FLITCOURIER1` domain tag and length-prefixed fields; a change to any covered field changes them; `sig` and `cert` are excluded.
2. `seal` wraps a fresh 32-byte key to the recipient certificate; the carrier holds only ciphertext, IV and wrapped key and cannot decrypt.
3. `verify` checks the certificate's own SHA-256 DER against `fromFp` and verifies the signature against that certificate before pinning; a substituted certificate is refused.
4. `verify` returns false for a tampered `cipherB64`, `ivB64`, `keyWrapB64`, `alg`, `circleId`, `toFp` or `createdMs`, and for a blank or malformed `sig` or `cert`.
5. A parcel body's key set is exactly `{"toFp","envelope"}`; an extra key is refused before any id is reserved.
6. The Base64 envelope is at most `MAX_ENVELOPE_CHARS`; the framed body stays under `CircleCore.MAX_BODY_CHARS`.
7. The envelope has no `expiresMs`; `MAX_PARCEL_TTL_MS` derived by `parcelOf` is the only expiry.
8. `open` applies `REPLAY_WINDOW_MS` and `CLOCK_SKEW_MS` to `createdMs` and owns no replay set; `evaluate` is the carry core's id guard.
9. Decrypted events pass `CircleCore.validShape` and have a type in `CircleEvent.TYPES`; the envelope layer does not assert authorship, which is `CircleCore.verify` at fold time.
10. The envelope `cert` is the same signer as the `parcel` event's `authorFp`; no second trust anchor is created.
11. No plaintext event, content key or recipient private material is written to disk or logged by this layer; logs carry counts, ids and fingerprints only.

## Failure modes

| Condition | Conclusion | Keeps bytes | Drops | Evidence |
| --- | --- | --- | --- | --- |
| Recipient certificate absent from the log and roster | `seal` refuses and logs; no envelope created | Events stay in the sender's own log | Nothing | CODE; PURE `CourierEnvelopeTest` |
| Extra body key, blank `toFp` or Base64 over `MAX_ENVELOPE_CHARS` | `Invalid` at `validParcelBody` and `decode`; no id reserved | Nothing new is held | The malformed body | CODE; PURE `CourierEnvelopeTest` |
| Tampered ciphertext, IV, wrapped key or algorithm tag | `verify` false; carrier logs before explicit `ack`/`expire` | Held bytes until removal | Envelope on removal | CODE; PURE `CourierEnvelopeTest` |
| Certificate does not hash to `fromFp` or differs from `authorFp` | `verify` false, no pin written | Nothing | Envelope | CODE; PURE `CourierEnvelopeTest` |
| `circleId` or `toFp` differs from the recipient's values | `open` refused, no events folded | Envelope stays in the bag | Nothing | CODE; PURE `CourierEnvelopeTest` |
| `createdMs` older than `REPLAY_WINDOW_MS` or future beyond `CLOCK_SKEW_MS` | `open` refused | Existing bag unchanged | Envelope on `expire` | CODE; PURE `CourierEnvelopeTest` |
| Envelope id already reserved by `evaluate` | `Duplicate`; not held again | The first copy | The repeat | CODE; PURE (carry core test) |
| Bag at `MAX_PARCELS` | `Full`; no id reserved | All held parcels | The new offer | CODE; PURE (carry core test) |
| `nowMs >= expiresMs` from `parcelOf` | `Expired`; `expire` removes and logs | Existing bag unchanged | Expired parcel | CODE; PURE (carry core test) |
| Corrupt wrapped key or undecodable plaintext after decrypt | `open` returns null, logged | Envelope bytes remain for retry or explicit removal | Parsed array only | CODE; PURE `CourierEnvelopeTest` |
| Event signature invalid after decrypt | Event skipped by the later fold gate | Envelope and the rest of the array | That event | CODE; PURE (fold gate exercised at app layer) |
| Carrier process dies before handoff | Bag row is owned by the app or CLI layer | Persisted `CarryState` survives there | In-memory only state | CODE (limitation; persistence is the 2026-11-30 layer) |

Harness and device evidence for courier handoff are recorded by the 2026-11-30 and 2026-12-02 layers, not here.

## Tests

Pure tests first, in `protocol/src/test/kotlin/app/flit/share/protocol/CourierEnvelopeTest.kt`, with no Android, socket or device:

- Canonical bytes are domain-tagged and length-prefixed; a change to each covered field changes them; `sig` and `cert` are excluded.
- `seal`/`open` round-trips a signed event array through AES-GCM and RSA wrap; the returned events are byte-equal to the input.
- `verify` rejects a substituted certificate, a certificate whose digest is not `fromFp`, a certificate that differs from the event's `authorFp`, a forged signature, a tampered ciphertext/IV/wrapped key, a wrong `alg`, a wrong `circleId`, a wrong `toFp` and a `createdMs` outside the window; rejections never write a pin.
- `encode` output Base64 fits `MAX_ENVELOPE_CHARS` and is accepted by `validParcelBody` with keys exactly `{toFp, envelope}`; `decode` returns null on truncated, oversized or unknown-field bytes.
- `open` refuses a wrapped key that cannot be unwrapped and a plaintext array that is not bounded JSON; no envelope id is reserved by this layer.

Harness tests (2026-11-30): the CLI carrier moves `parcel` events between three logical identities over production `/circle/sync`, persists `CarryState` across a restart and logs the handoff. Device walk (2026-12-02): three-node courier handoff with a log and an observation note under `docs/completions/`. Neither is claimed on this date.

## Evidence for this day

`docs/completions/2026-11-27-courier-envelope.md` is written when the layer lands. It records the fresh pure-test run, the protocol test count moving from the 82 baseline by the number of added `CourierEnvelopeTest` cases, CODE references to `CourierEnvelope.kt`, and that only PURE evidence is claimed. Harness and device rows stay unclaimed; the three-node mesh remains unverified until the 2026-12-02 walk.

## Out of scope

The bounded bag, `parcelOf`, `validParcelBody`, `evaluate`, `CarryState` and `select`/`ack`/`fold`/`merge` (same-day carry core); the carrier loop, `Link` handoff and `courier-bag/` persistence; app state, `FlitCircleStore` folding and file-card states; QR framing and camera decode; per-drop content keys, delivery receipts and Circle pad events; encryption of events already in the shared Circle log; any change to the v2.2 wire, routes or status codes; multi-hop relay routing, Wi-Fi Aware and offline `.flitpack` bundles.

## Commit

2026-11-27 Protocol: courier envelope codec and parcel wire shape with pure JVM tests.
