# Security — threat model, trust anchors, and limits

> Part of the Flit design set. [Ladder and index](transport-bluetooth.md) ·
> [Circle](circle.md) · [QR-only, known devices and panic](qr-only-and-panic.md) ·
> [Open file and sharing restoration](open-file-and-sharing-restore.md) ·
> [README](../README.md).
>
> **Non-profit constraint (hard, everywhere):** no accounts, no cloud, no ads, no
> telemetry, no SDKs that phone home. Every mechanism below is local or
> peer-to-peer; there is no identity server, no key escrow and no data collector
> to audit.

- **Code reality** — traced to specific files and lines; no execution implied.
- **Pure-test evidence** — JVM tests in `protocol/src/test`, `app/src/test`.
- **Harness/sim evidence** — `flit-cli` and in-process endpoints over
  `Link`/`LinkProvider`, `bt-sim`, relay over loopback.
- **On-device evidence** — the rig phone (Redmi Note 10S / API 36) and the CLI
  box, as recorded in the focused docs. A walk is claimed only where those docs
  record one.

Rig status: 443 protocol + 167 core + 254 app + 108 harness = 972 tests, 0
failures, 0 errors, 0 skipped in the last full green test XML (2026-11-28,
`docs/TESTING.md` §1). That result covers the four test tasks only; the release
assembly is green after the R8 keep-rule change
written, so no green claim is made for it. The 714 figure (330 + 309 + 75), the
769 figure (359 + 323 + 87) and the four-module 778 projection are historical.
Properties asserted only by a code comment or an unrun path are marked "not
verified".

## Assets and trust boundaries

Assets worth protecting: the device's TLS private key (`TlsIdentity`), the
fingerprint that names it, received file bytes, the sender's local spool, Circle
event bodies and group key, per-drop content keys and their wraps, the invite
token, the relay token, and the QR codes that carry all of the above indirectly.

Boundaries where untrusted input crosses into a decision:

| Boundary | Ingress | Who may be hostile |
| --- | --- | --- |
| Wi-Fi / LAN | UDP announce, TCP+TLS register, prepare, upload, `/partial`, panic and Circle routes | any device on the network, including an on-path peer |
| Bluetooth RFCOMM | the same v2.2 wire over a socket | any nearby radio; the OS pairing sheet is not a Flit trust decision |
| Relay pipe | one hello line, then opaque bytes | the relay operator and anyone who can reach it |
| QR scan / paste / `flit://` link | a URI string from a camera, clipboard or saved invite | whoever crafted the code or photo |
| Local storage | SharedPreferences, spool, ledger, event log | someone with device or backup access |
| External viewer | a `FileProvider` content URI with a read grant | the chosen viewer is a separate app |
| Courier parcel on `/circle/sync` | a `parcel` event body whose `envelope` field holds Base64 envelope bytes, offered by a carrier | any carrier that holds and forwards the bytes; it cannot decrypt them |
| QR parcel frames (air gap) | a sequence of opaque frame strings shown, photographed or pasted by a person | whoever crafted, altered or photographed a frame; the carrier holds every frame but no key |

## Threat model

The table answers "what does this design hold against". It is
deliberately symmetric: every row states both the guarantee and the gap.

| Adversary | What holds | What does not hold |
| --- | --- | --- |
| Passive LAN observer | file bytes, Circle events and the group key travel inside TLS; authorship is proven by signature over canonical bytes | IPs, MACs, timing and byte counts are visible; Circle event bodies are **plaintext JSON at rest** on each device, not encrypted |
| On-path / rogue relay | TLS runs end-to-end inside the pipe; the relay never sees a protocol byte, a fingerprint in the log, or a finished hash; a signed host answer binds the key to the QR host | a relay can refuse, delay, drop or partition a pair; it is a single point of failure the user owns |
| Malicious courier carrier | holds only AES-GCM ciphertext, an IV and an RSA-wrapped key; cannot decrypt, forge the sender signature, or redirect a blob to a different `toFp`/`circleId` without breaking the cipher or the signature | can refuse, delay, drop, duplicate or partition parcels; a held parcel stays a stored copy until the 24 h TTL expiry; sees addressee fingerprint, timing and size |
| Air-gap frame carrier | a sealed parcel is released only when `complete` checks the recipient and the frames, CRC, sequence, count and digest; the bytes are AES-GCM ciphertext behind an RSA-OAEP-wrapped key, signed with the sender's cert-bound key | can copy, delay, reorder, drop, duplicate or photograph frames and can alter one, but a bad frame fails CRC and a bad parcel fails signature, digest or recipient check; sees frame metadata and ciphertext, not plaintext |
| Forged fingerprint | an attacker certificate lands under the attacker's own SHA-256 DER fingerprint; a **signed** event naming a victim's `senderFp`/`authorFp` fails without that victim's private key, so signed events cannot be forged | the member certificate DER is public in Circle events and in `X-Flit-Circle-Cert`; the header alone remains a name claim. Possession of the key behind `memberFp` is now checked by the signed JOIN proof described below |
| Stolen QR photo (pairing) | pinning for an IP-bearing code happens only after a live `register` answers with the exact fingerprint in the code | the photo is a bearer hint: whoever shows it to a device that answers correctly gets trusted. A relay-only/MAC-only code has no probe and pins directly from the code — the human eyeball is the only check |
| Stolen Circle invite (v3 QR) | the code carries no group key; the key arrives only inside a TLS session to the host named in the QR, and the answer is signed by that host. A JOIN also needs one signed JOIN event carrying a proof minted with the private key for the claimed `memberFp`, so the photo alone cannot claim another member's identity | the invite token remains a bearer credential: whoever holds it can join under their own key while it is valid. A captured proof is a bearer token until its bucket rolls over; the host reserves its id once in-process, but a restart or another process has no memory of that reservation. Retiring a suspicious code means starting a new Circle |
| Compromised circle member | cannot forge another member's **signed events** (signature + cert pin), cannot mint a proof for another fingerprint, cannot inject `JOIN` or `REKEY` through `SYNC` or a parcel, cannot inject a `parcel` into another device's bag unless it is a current roster member, cannot read a key the host never sends in a sync answer | can read the whole plaintext log and can emit signed events as itself; with the invite and its own key it can join under its own fingerprint and have that route remembered; earlier events already stored on a departed device cannot be erased |
| Malicious panic sender | must own the private key for the claimed `senderFp`; hops and author are signed; recipient gates on the original `originFp` being QR-known; dedup stops replays | any QR-known device can send a loud banner; panic is informational and never wipes, locks or acts on the recipient |
| Local attacker with device access | no cloud copy to pull, no account to reset, `android:allowBackup="false"` | can read the app's plaintext state, preferences, spool and ledger; there is no content cipher and no screen lock of its own |
| Malicious external viewer | Flit hands one content URI with a scoped read grant, never the private key or the whole spool | whatever the user chooses to do with that file is outside Flit's control |
| Prepare sender claiming another fingerprint | when either receive gate is on, `X-Flit-Prepare-Cert` plus `X-Flit-Prepare-Sig` bind a signature over the exact body to a certificate whose own digest must equal the declared fingerprint; a bare replayed DER is refused | both gates are off by default; the header proves possession of a key pair, not that the certificate was provisioned out of band |
| Rogue TLS receiver on a paired send | a configured `expectServerFp` is compared with the handshake peer's leaf certificate before any request byte is written, on TCP and on piped TLS | a null pin keeps the legacy unauthenticated TLS path; a peer holding the pinned private key is indistinguishable from the real device |

## Trust anchor: fingerprint and certificate pinning

The one root of trust in Flit is a device's own self-signed X.509 certificate.
`TlsIdentity.fingerprint` is the **uppercase-hex SHA-256 of the certificate's
DER encoding** (`protocol/.../tls/TlsIdentity.kt:41-43`, `:56-57`, `:303-307`).
That same value is announced as `fingerprint` on the wire and carried in QR
codes, roster rows, Circle events and panic alerts. There is no CA hierarchy and
no hostname to validate.

- `TlsIdentity.generate()` mints a 2048-bit RSA key, a self-signed
  `SHA256withRSA` certificate valid for 30 years, with `serverAuth` and
  `clientAuth` EKUs and SANs for `localhost` / `127.0.0.1`
  (`TlsIdentity.kt:212-251`). `DEFAULT_ALIAS` is `flit-device`,
  `DEFAULT_PASSWORD` is `flit`. Loaded identities re-check certificate validity
  (`TlsIdentity.kt:253-263`).
- The TLS context uses a trust-all manager and presents its own client
  certificate (`TlsIdentity.kt:142-145`, `:331-336`). The hand-rolled
  `PeerHttpClient` deliberately omits hostname verification: peers are reached
  by IP and authenticated by the announced fingerprint instead
  (`protocol/.../client/PeerHttpClient.kt:19-21`). The key manager ignores the
  peer's `certificate_authorities` list so stock SunX509 still selects the
  self-signed client cert (`TlsIdentity.kt:81-140`).
- TLS provides confidentiality and transport integrity; it does **not** by
  itself establish *which* device is on the other end. For a TLS session,
  identity rests on the announced fingerprint matching one obtained out of band
  (QR scan) or from a pinned roster row; for a signed event, the signature must
  verify under the pinned certificate, so a peer that cannot present the private
  key for a fingerprint cannot sign as it. A Circle `JOIN` uses the same
  primitive: it carries one signed JOIN event whose embedded certificate is
  bound to that fingerprint by its own digest, so an invite holder without the
  private key cannot mint a valid proof (see "Signatures and cert-in-event").
- OS Bluetooth bond state is **not** a Flit trust anchor. The system pairing
  sheet may appear on an incoming RFCOMM, but accepting it does not pin anything
  in Flit; trust is still QR-invite fingerprint pinning
  (`docs/bluetooth-carrier.md`, "First-contact cost", `:124-129`).

### The pin cache

`TlsIdentity` holds a single in-process certificate pin map, keyed by the
certificate's own fingerprint:

- `pin(cert)` stores under `certificateFingerprint(cert)` and returns that
  fingerprint; `pinned(fp)` looks it up; `forgetPin(fp)` removes it
  (`TlsIdentity.kt:179-190`).
- `MAX_PINS = 512`. `PinCache` is an access-ordered `LinkedHashMap` with LRU
  eviction at capacity (`TlsIdentity.kt:160-177`, `:267-301`). Keys are
  `trim().uppercase()`-normalized on put/get/remove; every `pinned()` lookup
  promotes the entry, so actively verified pins survive a flood of first-seen
  certificates and stale ones are evicted first.
- `verifySignature(fp, bytes, sig)` resolves the pin, **re-checks that the
  stored certificate's own digest equals `fp`**, then verifies the signature
  (`TlsIdentity.kt:314-319`). A claimed fingerprint can never borrow another
  certificate's signature.
- `verifyWithCertificate(cert, bytes, sig)` verifies against a certificate held
  in hand and never reads or writes the shared map
  (`TlsIdentity.kt:198-206`, `:321-329`). This is the primitive that makes
  pin-after-verify possible on first contact.
- **Two tiers in memory.** `PinCache` now holds a protected verified tier
  (`MAX_PINS = 512`) and a small unverified tier (`UNVERIFIED_PIN_QUOTA = 16`).
  `pinVerified` writes only the protected tier; a bare `pin` writes the small
  tier and cannot downgrade or evict a protected pin (`TlsIdentity.kt:191-209`,
  `:235-252`, `:383-449`). A successful `verifyWithCertificate` marks the digest
  in a bounded, access-ordered marker ring capped at `MAX_PINS` for a 5 s grace
  window, so the caller's normal verify-then-pin order promotes that pin to the
  protected tier without a new API (`:211-227`, `:254-264`, `:292-297`).
- **Sender-side TLS peer pin.** `TlsIdentity.checkPeerPin` compares the
  handshake peer's leaf certificate digest with an expected fingerprint after
  the handshake and before the first request byte; absent certificate and
  mismatch both fail closed (`TlsIdentity.kt:309-322`). It is driven by
  `SendTarget.expectServerFp` on every client exchange and by the link provider
  (§ "Security closeout wave").
- **Failure mode:** the cache is memory-only and bounded. After a restart or
  after eviction, a bare `verify(fp, …)` with no certificate in hand returns
  false. Every production ingress therefore carries the certificate in the
  event or header and re-pins after verifying; the fallback-to-pinned path is
  for already-known relayed senders only (`CircleCore.kt:172-187`). A durable
  pin source is **not implemented**: `FlitRoster.Pinned` and
  `FlitKnown.Device` persist fingerprints, not certificate DER
  (`FlitRoster.kt:12-14`, `FlitKnown.kt:34`).

Pure-test evidence: `TlsIdentityTest.kt:16-118` (6 `PinCacheTest` tests) covers
LRU eviction, recency promotion, key normalization and forget/clear, all verify
entry points, tampered bytes, idempotent re-pinning, and that cert-in-hand
verification leaves the global map untouched.

## Signatures and cert-in-event

There is exactly one crypto path for Flit's signed types. `TlsIdentity.sign`
produces Base64 of `SHA256withRSA` (or `SHA256withECDSA` for loaded EC keys);
`verify` and `verifyPanic` are wrappers over the same static check
(`TlsIdentity.kt:59-79`, `:309-329`). Panic and Circle share
`sign(bytes)` / `verify(fp, bytes, sig)`; the panic route is a thin wrapper, not
a second implementation.

Canonical bytes are a domain tag plus length-prefixed UTF-8 fields, so no
delimiter can be smuggled through a note or body:

- `PanicCore.canonical`: `FLITPANIC1`, then `id`, `senderFp`, `sentMs` (long),
  `text`, `hops` (int), `originFp` (`protocol/.../panic/PanicCore.kt:73-96`).
  Every immutable field including `hops` is signed; `sig`, `originSig` and
  `originCert` are not. `originSig` is the origin's own signature over the
  **hop-zero** canonical (`senderFp = originFp`, `hops = 0`), rebuilt by
  `verifyOrigin` from fields that survive re-signing (`:165-200`).
- `CircleCore.canonical`: `FLITCIRCLE1`, then `id`, `circleId`, `authorFp`,
  `lamport`, `type`, `body`, `createdMs` (`protocol/.../circle/CircleCore.kt:73-111`).
  Every field except `sig` **and `cert`** is signed.
- `CircleResponse.canonical`: `FLITCIRCLE1` with the answer's `ok`, `key`,
  members, events and reason; `serverCert` and `sig` are excluded
  (`protocol/.../model/CircleWire.kt:60-90`).

The one crypto check `verifySignatureWithCertificate` now enforces three
layers before any signature is trusted: the certificate is currently valid, it
is allowed to sign when a `KeyUsage` extension is present, and the Base64
signature is canonical (decode, re-encode and compare to the trimmed input),
so unpadded or trailing-bit variants cannot be smuggled past a decoder that
would otherwise accept them (`TlsIdentity.kt:472-490`).

**Cert-in-event, pin-after-verify.** A Circle event carries the signer's DER
certificate as Base64 in `cert`. A receiver parses it, requires its own SHA-256
DER to equal `authorFp`, verifies the signature against that certificate *before*
pinning it, and only then pins (`CircleCore.kt:172-187`). An event without a
certificate falls back to an already-pinned author. This is why a first-seen
author can be verified without poisoning the pin store: a forged event whose
`authorFp` names a victim fails the digest comparison and is never pinned.
`CircleCore.sign` refuses to re-sign an already-signed event, so the same
canonical bytes cannot be signed twice by accident (`CircleCore.kt:152-163`).

The panic route uses the same rule with the certificate in a header rather than
in the body: `ServerCore.panicRoute` decodes `X-Panic-Cert`, pins it under its
own digest, requires that digest to equal `alert.senderFp`, and verifies the
signature (`protocol/.../server/ServerCore.kt:902-945`). Circle routes require
`X-Flit-Circle-Cert` and compare its digest to `memberFp`
(`ServerCore.kt:971-997`). That header check remains only a name claim: the
certificate DER is public in every signed Circle event. Proof of the private
key comes from the JOIN event in the request's existing `events` field. A JOIN
must carry one signed JOIN event whose body is exactly `{"proof":"…"}`. The
`proof` value is the unpadded Base64URL SHA-256 of a `FLITJOIN1`-domain nonce:
length-prefixed UTF-8 fields for host fingerprint, circle id, invite, member
fingerprint, followed by the index of a five-minute bucket
(`CircleCore.joinProofNonce`, `CircleCore.kt:485-502`; `joinProofBody`, `:514-526`).
The host recomputes that digest from its own fingerprint and the request; only
the holder of the private key behind `memberFp` can sign a JOIN carrying a
matching proof (`CircleCore.joinProofFailure`, `:566-595`). `CircleCore.verify`
binds the embedded certificate to its own SHA-256 DER digest before checking the
signature and pins it only on success, so a JOIN naming a victim cannot borrow
the victim's pin (`:196-211`). The route refuses a proof-less, malformed or
stale JOIN with a host-signed 200 before the delegate sees it, so old clients
that send only the invite cannot join (`ServerCore.kt:1001-1016`). The host
accepts the current bucket with a one-bucket tolerance. Single-use is keyed on
the **domain-tagged proof digest**, not the carrying event id: a re-signed JOIN
with the same digest but a fresh id is still refused (`CircleCore.kt:649-685`).
The reservation lives in the bounded in-process seen set, is retained for
`REPLAY_WINDOW_MS + CLOCK_SKEW_MS` (6 h 5 min, `CircleCore.kt:32-37`) and fails
closed when that set is full of live entries. The five-minute bucket grid is the
stateless backstop. That reservation is process memory: a restart forgets it; a
durable replay store is not implemented.

The host signs every Circle answer; a joiner verifies it against the host
fingerprint from the QR via `CircleResponse.verify(response, host)` before
persisting anything (`CircleWire.kt:92-108`, `app/.../FlitCircleSync.kt:390-403`).
A rogue relay or on-path peer cannot substitute the key.

Pure-test evidence: `PanicTest.kt:103-133` (any edit or forged `senderFp`
breaks verification and the route answers 403), `CircleHardeningTest.kt:180-219`
(embedded cert bound to `authorFp`, rejected signature never pins the stranger),
`CircleCoreTest.kt:145` and `FlitCircleHostTest.kt` (real signed identities,
not mocks), `JoinProofTest.kt` (golden-vector nonce framing; a minted proof that
verifies and passes the host gate; refusals for a missing or legacy body, a
changed host/circle/invite/member/bucket, a tampered signature or swapped
certificate, and a proof replayed in a later bucket or twice in one request),
`CircleAuthorityTest.kt` (JOIN never folds, REKEY only from OWNER/ADMIN), and
`FlitCircleSyncTest.kt` (the client mints a proof the host's stateless verifier
admits). Harness evidence: 403 refusal branches for missing, malformed and
mismatched cert headers are exercised through `ServerCore` in
`CircleHardeningTest.kt:362-441` (pure route, production code), and an
in-process TLS walk refuses a proof-less JOIN and a proof for another host while
admitting the rightful member's signed proof (`CircleHarnessTest.kt`).

## Replay guards

Both signed types use a bounded seen-id set and a clock window. The constants
are exact and shared in spirit, not in a single class:

| Constant | Panic (`PanicCore`) | Circle (`CircleCore`) |
| --- | --- | --- |
| Replay window | `REPLAY_WINDOW_MS = 6 h` (`:21`) | `REPLAY_WINDOW_MS = 6 h` (`:23`) |
| Future tolerance | `CLOCK_SKEW_MS = 5 min` (`:24`) | `CLOCK_SKEW_MS = 5 min` (`:26`) |
| Bounded memory | `MAX_SEEN = 1024` (`:27`) | `MAX_SEEN = 4096` (`:29`) |
| Other bounds | `DEFAULT_MAX_HOPS = 3` (`:18`) | `MAX_BODY_CHARS = 16 KiB`, `MAX_FIELD_CHARS = 256`, `MAX_SIG_CHARS = 1024`, `MAX_CERT_CHARS = 8 KiB`, `MAX_EVENTS_PER_REQUEST = 1024`, `MAX_REQUEST_BYTES = 8 MiB` (`:31-50`) |

Decision precedence and atomicity:

- `PanicCore.evaluate` checks, in order: structural `Invalid` → `HopLimit` →
  `OutsideWindow` → `Duplicate` → `Accept` (`PanicCore.kt:118-131`). `accept`
  wraps `evaluate` in the same synchronized block so exactly one racing
  delivery of an id wins and only `Accept` records it (`:133-146`). `fresh` is a
  tiny FIFO that evicts the oldest id past `MAX_SEEN` (`:148-161`).
- `CircleCore.evaluate` checks `Invalid` (shape) → `OutsideWindow` →
  `Duplicate` → `Full` → `Accept` and reserves the id atomically; `accept` is an
  alias of `evaluate`, and `fresh` shares the same set (`CircleCore.kt:87-147`,
  `:708-743`). Reservations carry a deadline of `now + REPLAY_WINDOW_MS +
  CLOCK_SKEW_MS` (saturating at `Long.MAX_VALUE`); only expired entries are
  purged, so a flood of fresh ids never evicts an in-window record. When every
  slot is still live the newcomer gets `Full` and no id is reserved. The
  production order is `validShape → verify → evaluate → store`: reserve **after**
  crypto, so a forgery cannot burn the id of a legitimate event.
  `ReplayBoundaryTest` pins both the flood case and the saturation refusal.
  The app peer fold follows that order in `FlitCircleSync.fresh`
  (`:992-1031`): shape, circle and `CircleAuthority.foldable`, type-specific
  body gates, then `CircleCore.verify` before any id is reserved. Two dedup
  layers follow — the local log's id set (restart-safe) and `CircleCore`'s
  bounded accept-memory — and the last stage is `CircleCore.accept(nowMs)` on
  the survivors. The app host's `SYNC` path runs the same ordering with `accept`
  last (`FlitCircleHost.kt:156-192`).
- The Circle window compares two positive timestamps by subtraction, never
  `now ± window`, so an extreme clock cannot overflow into a false Accept
  (`CircleCore.kt:358-359`).
- Deterministic total order is
  `(lamport, authorFp, id, type, body, createdMs, sig, cert)`; `dedup` picks the
  winner by that order, not by arrival order, so two peers converge without a
  server (`CircleCore.kt:189-219`).
- Bounded store-and-forward: every `SYNC` posts the full bounded local log, so a
  lost one-shot is repaired by the next post or tick. The relay is never given
  storage (`docs/circle.md:176-184`).
- Courier deliberately uses a longer, bounded replay window: the **24 h parcel
  TTL** (`MAX_PARCEL_TTL_MS`), not `CircleCore.REPLAY_WINDOW_MS` (6 h). Both
  `CourierEnvelope.open` and `CourierCarryCore.evaluate` apply it to `createdMs`
  with the same five-minute future skew; see "Courier: recipient-cert encryption
  and carrier blindness".

**Failure modes and limits.** The seen sets are process memory, not durable
state; a restart forgets them, and the six-hour window (plus the five-minute
JOIN bucket) is the backstop. The JOIN proof reserves its domain-tagged digest
in that same set, so its single-use property is process-local: the same proof
is refused a second time in this process even under a new event id, but a fresh
process has no memory of it. A saturated set refuses a newcomer as `Full` or
`join proof replay` rather than dropping a live reservation. Bounded memory is
established; absence of replay after the retention window is not. A legitimate
event delayed more than six hours is stale replay and is refused. A forwarded panic that has already been delivered once returns 200
on the wire but is not delivered again to the delegate
(`ServerCore.kt:941-944`) — the quiet
200 is deliberate so senders do not retry forever. A spent-hop alert likewise
gets 200 but is dropped before the delegate.

Pure-test evidence: `CircleHardeningTest.kt:116-141` (shared set, exact
precedence), `:143-178` (oversized events never consume an id), `:221-239`
(overflow-safe window with `Long.MAX_VALUE`/`MIN_VALUE`),
`:304-358` (delivery-order-independent dedup and kick tally);
`PanicTest.kt:135-194` (once-per-id delivery, hop limit, stale window);
`CircleCoreTest.kt` (Lamport convergence, replay window).

## QR codes: pairing authority and invite bearer semantics

### What a code can and cannot carry

`QrAdd` is a `flit://add` (or `flit://pair`) URI (`app/.../Qr.kt:15-81`):

- **v1** — address only: `ip`, `port`, `tls`, optional `fp` and `alias`.
  Re-encoded byte-for-byte for deployed codes.
- **v2** — adds carrier hints: Bluetooth `mac`, relay `host:port` plus optional
  `token`; may be relay-only or MAC-only with no IP.
- **v3** — a Circle invite: adds `circle` (id) and `invite` (token) alongside the
  host fingerprint and v2 hints. **No group key is ever emitted** (`Qr.kt:31-35`,
  `:76-79`). A code that tried to smuggle a `key`/`secret` parameter cannot feed
  it into the store: `parseAddCode` has no such field and deliberately rejects
  v3 codes missing `circle` or `invite` rather than downgrading them to a
  pairing (`Qr.kt:119-137`).

Strict parsing is pure and JVM-tested: unknown versions, bad ports, malformed
relays, other schemes, empty IP and incomplete v3 codes all return `null`
(`Qr.kt:95-137`; `app/src/test/.../FlitQrTest.kt:40-50`, `:125-135`, `:188-199`).
Tests also lock that rendered/scanned/rotated pixels decode back to the exact
URI (`:209-302`) — that is a format-integrity property, not a security proof.

### QR authority check (pairing)

A scanned or pasted code is untrusted input. `FlitEngine.addFromScan`
(`FlitEngine.kt:2366-2437`) enforces:

1. Parse strictly; a non-code is refused with a toast, not added.
2. A code with a blank fingerprint is refused outright — "that code has no
   device identity" (`:2377-2383`).
3. **IP-bearing codes pin only after a live `register` answers.** The engine
   dials the code's address, performs a real `register`, and pins only if the
   answering peer's fingerprint equals the code's fingerprint; a mismatch is
   logged and toasted as "NOT trusted" and nothing is pinned
   (`:2403-2436`). Trust attaches to the device that actually answers, not to a
   round-trip-free copy of the code. Only then are the known-device row and the
   roster pin written.
4. **Relay-only / MAC-only codes have no address to probe.** The QR itself is
   the human-eyeball verification, so they pin directly and are labelled
   `QR verified` (`:2392-2401`). This is the weaker branch and is stated as such.

`QR-only` mode is a carrier gate, not merely a discovery preference: it stops
ambient announce, scan and sweep (`FlitEngine.kt:1278-1294`) **and** makes
`onPrepare` decline any sender whose fingerprint is not in Known devices
(`:967-973`). It is stored at `known.qrOnly`, default false
(`FlitKnown.kt:54-56`, `:98-100`), as is the separate *Trusted senders only*
gate (`FlitEngine.kt:974-982`). Both are off by default; the open list is the
default and the gates are opt-in.

### Invite bearer semantics and persistence timing

A v3 QR is a **bearer invite**: whoever holds the photo can present the token.
It is not the group key and it does not reveal the key without a TLS session to
the host named in the code. The UI says this plainly and a suspicious code is
retired by starting a new Circle (`docs/circle.md:106-133`, `:359-367`).

The timing of persistence is load-bearing:

- The host checks the presented invite against its own (case-insensitive) and
  the `X-Flit-Circle-Cert` digest against `memberFp`, then requires the signed
  JOIN proof before any state changes. `ServerCore.circleRoute` answers with a
  host-signed refusal when `CircleCore.joinProofFailure` reports a missing,
  invalid or replayed proof (`ServerCore.kt:1001-1016`, `CircleCore.kt:566-595`);
  proof-less old clients do not reach the delegate. The proof binds host,
  circle, invite, member and bucket, and the JOIN event's signature proves the
  private key behind `memberFp`. Only then does the host add the member and
  remember the requester's IP as that fingerprint's route
  (`FlitCircleHost.kt:122-146`, `FlitEngine.kt:1402-1428`). Roster growth is
  capped at `MAX_MEMBERS = 500` (`FlitCircleHost.kt:48`, `:131-134`).
- Only a successful JOIN answer carries the group key; a SYNC answer never does,
  even to an already-known member (`FlitCircleHost.kt:196-203`).
- The joiner verifies the answer's signature against the host fingerprint from
  the QR **before** calling `circleInviteAccepted`
  (`FlitCircleSync.kt:390-403`). That function is the only writer of the
  persisted `circleInvite` preference (`FlitEngine.kt:772-777`), and
  `refreshCircle` loads it on every rebind (`:381-399`). Pure JVM tests use a
  `joinPersistHook` and therefore skip both the on-device signature check and
  the write — a deliberate seam, not a claim about device behavior.
- A 2026-11-25 fix addressed a real bug here: the invite token had been
  memory-only, so a host could not admit members after a restart; it is now
  persisted (`docs/circle.md:369-378`).

**Failure modes:** a wrong invite gets `ok=false, reason="bad invite"`; a
departed or never-joined fingerprint gets `"not-a-member"`; a wrong-circle id
gets `"wrong circle"`; all are well-formed 200 responses so a caller can tell
refusal from a dead carrier (`ServerCore.kt:947-1007`,
`FlitCircleHost.kt:59-66`).

## Circle wire authority, roles and rekey

Fold-time authority is one shared, JVM-pure decision in
`CircleAuthority.foldable` (`protocol/.../circle/CircleAuthority.kt:33-43`). It
reads only circle id, event type, author fingerprint and a caller-supplied
fingerprint-to-role map; it verifies no signature, reads no clock and keeps no
state. The rules:

- `JOIN` is never foldable. It is a host-routed handshake admitted by the invite
  and proof gate, not a log entry a peer can contribute.
- `CircleAuthority.authorityOf` classifies the privileged verbs before any role
  lookup: `ROLE` requires `OWNER`, `REKEY` requires `OWNER` or `ADMIN`, a `KICK`
  with `"mode":"admin"` requires an officer, and a mode-less or `"vote"` kick
  requires only membership (the majority rule stays in the reducer).
  `PRIVILEGED` is `{JOIN, ROLE, KICK, REKEY}`. `foldable`'s fourth argument
  defaults to `authorityOf`, so every site reaches the same decision
  (`CircleAuthority.kt:34-89`). With no roles — the courier and CLI folds pass an
  empty map — no privileged verb can enter from a parcel or a member-supplied
  batch. Any other known verb folds once the circle matches; a blank author,
  unknown or blank type, or foreign circle is refused. Author fingerprints and
  role values are trimmed and uppercased before lookup; a malformed kick body
  degrades to an ordinary vote-kick, not to an officer bypass.

The gate is applied at every fold site rather than only the host: the app host's
`SYNC` admission (`FlitCircleHost.kt:169-192`), the app peer fold
(`FlitCircleSync.fresh`, `:1011-1030`), the store reducer
(`FlitCircleStore.applyRekey`, `:444-477`), the CLI/harness host fold
(`Harness.kt:382`) and the courier fold (`CourierCli.kt:441-447`,
`FlitCircleSync.kt:924-937`). Author fingerprints and role values are trimmed
and uppercased before lookup, so casing does not change the decision. `SYNC` is
roster-gated and rejects events not authored by the requester's own fingerprint;
oversized, out-of-circle and malformed bodies are filtered before verification.

A rekey changes the group key, so two further rules bound it. The store takes an
immutable roster snapshot when a batch starts and checks authority against that
snapshot, so folding a `leave` before or after the rekey cannot change the
decision; a signer whose fingerprint is in the batch's leavers is refused even
when the snapshot grants it a role, so an officer cannot hand off a chosen key
on the way out (`FlitCircleStore.kt:444-477`). Independently, any departure in a
batch forces the host to rotate to a fresh host-minted key with a generation
above any key admitted in that batch, so neither a hand-off attempt nor a
staying officer's key already known to the leaver is reused
(`FlitCircleHost.kt:195-222`). A later `SYNC` from the departed fingerprint is
refused at the roster gate. Roles remain local authority labels pinned to a
fingerprint, not accounts: only OWNER appoints/removes ADMIN, an ADMIN (or
OWNER) kicks a non-owner, and nobody appoints a second OWNER
(`docs/circle.md:58-104`).

The group key is **membership/rekey material, not a content cipher**. There is
no encryption path for event bodies. Confidentiality on the wire is TLS;
integrity and authorship are signatures plus the cert pin
(`docs/circle.md:144-150`). Leaving a Circle deletes the local key and hides
the feed; it cannot wipe or change another device, and earlier events on the
departed device stay plaintext (`docs/circle.md:49-56`, `:152-154`).

## Courier: recipient-cert encryption and carrier blindness

The courier protocol adds a store-and-forward path for an absent Circle
member. A member who cannot reach a peer seals events into a `CourierEnvelope`;
a carrier holds the opaque bytes in a bounded bag until a later encounter. The
wire layer is pure JVM code in `protocol/.../courier/CourierEnvelope.kt` and
`CourierCarryCore.kt`. The carrier loop, CLI bag and app fold are separate
surfaces described below and add no new trust anchor.

A courier **handoff** means only that a peer accepted an offered parcel (an OK
answer). It is not end-to-end delivery: there is no
remote proof that the addressee opened the parcel or that any file byte landed.
The app's local `handed` value denotes that acceptance, nothing more.

### What the carrier holds

A parcel is one `parcel` event on `/circle/sync` whose body is exactly
`{"toFp":"<fingerprint>","envelope":"<Base64 bytes>"}`. The bytes hold
AES-256-GCM ciphertext, an IV, an RSA-wrapped content key, and the sender's
signature and certificate. The carrier forwards them without decrypting. The
blindness is structural: the only private key that unwraps the content key is
the addressee's, and it never reaches the carrier. This matches the relay rule
in "Relay: blindness and per-device opt-in" — the carrier carries opaque bytes
and cannot forge content or a fingerprint, but can refuse, delay, drop or
partition parcels.

`circleId` and `toFp` are inside the signed envelope, so redirecting a blob to
another circle or recipient breaks the signature. `expiresMs` is deliberately
absent from the envelope: the carry core derives it once as
`createdMs + MAX_PARCEL_TTL_MS`, so two carrier copies cannot disagree on
expiry. The clear `toFp` in the body is what lets a carrier select parcels for a
peer it meets; the carrier therefore sees the addressee fingerprint and message
size even though it cannot read the events.

### Recipient-cert encryption and no escrow

`seal` generates a fresh 32-byte content key with `SecureRandom`, encrypts the
bounded `CircleEvent` array under AES-256-GCM with a 12-byte IV, wraps the key
with RSA-OAEP/SHA-256 to the recipient certificate's public key, and signs the
canonical envelope bytes with `TlsIdentity.sign`. The content key exists only
inside `seal` and in the wrapped blob; no copy is written, logged or handed to a
third party. There is no key escrow, no key server and no certificate authority:
the recipient certificate comes from the signed Circle event log or a host
roster answer, and `seal` refuses when it cannot find one. The only accepted
algorithm tag is `ALG = "AESGCM+RSAOAEP256"`; another value is invalid. `open`
decrypts with the recipient's own private key and returns the event array only
when decryption, parsing and the `circleId`/`toFp` match pass; it refuses an
unwrappable key or undecodable plaintext.

### Sender signature and cert bind

Canonical bytes carry the domain tag `FLITCOURIER1` and the same 4-byte
big-endian length-prefixed UTF-8 field rule as `CircleCore.canonical`, over
`id`, `circleId`, `fromFp`, `toFp`, `createdMs` (long), `alg`, `ivB64`,
`cipherB64` and `keyWrapB64`. `sig` and `cert` are excluded; changing any covered
field changes the signature input.

`verify` binds the certificate's own SHA-256 DER to `fromFp` and checks the
signature against that certificate *before* pinning it in `TlsIdentity`'s
cache — the same pin-after-verify rule as "Signatures and cert-in-event". A
certificate whose digest is not `fromFp` is refused and never pins. The envelope
`cert` is the same signer named by the `parcel` event's `authorFp`, so no second
trust anchor is introduced. Decrypted events keep their own author signatures
and certs; courier does not assert their authorship, which is `CircleCore.verify`
at fold time. A forged event is skipped there, not by the carrier.

### Outer `parcel` gate at fold time

The signed outer `parcel` event is not trusted just because it verifies. A
carrier can forward a parcel minted by a member who has since left, so folding
requires all of: `CircleCore.verify(event)`, `event.circleId` equal to the local
circle id, the closed `{toFp, envelope}` body, and an `authorFp` that is a
**current** roster member after the same `trim().uppercase()` normalisation the
roster uses. A departed or never-member is refused even with a valid signature,
the right circle and a well-formed body. Only then does `parcelOf` derive the
expiry and `evaluate` reserve an id.

Decrypted inner events keep a separate gate: each must come from a current
member of the local roster, belong to the local circle, and never be `JOIN` or
`REKEY`. Those two verbs are governance material and are excluded even when a
current member sealed them; a sealed `REKEY` cannot rewrite the group key and a
sealed `JOIN` cannot grow the roster. `fresh()` verifies every signature before
anything is persisted.

### Limits and failure modes

| Constant | Value | Role |
| --- | --- | --- |
| `MAX_ENVELOPE_CHARS` | `12 * 1024` | Base64 cap; body stays under `CircleCore.MAX_BODY_CHARS` (16 KiB) |
| `KEY_BYTES` / `IV_BYTES` | `32` / `12` | AES-256-GCM content key and IV |
| `ALG` | `"AESGCM+RSAOAEP256"` | Closed algorithm value |
| `MAX_PARCELS` | `256` | Bounded carrier bag |
| `MAX_SEEN` | `4096` | Bounded id reservation in the carry core |
| `MAX_PARCEL_TTL_MS` / `CLOCK_SKEW_MS` | `24 h` / `5 min` | The one replay window used by both `CourierEnvelope.open` and `CourierCarryCore.evaluate` on `createdMs`; it is the parcel TTL, not `CircleCore.REPLAY_WINDOW_MS` (6 h) |
| `MAX_PARCEL_TTL_MS` | `24 h` | `expiresMs = createdMs + MAX_PARCEL_TTL_MS` |
| `MAX_FIELD_CHARS` / `MAX_SIG_CHARS` / `MAX_CERT_CHARS` | `256` / `1024` / `8 KiB` | Reused from `CircleCore` |

- A body with any key besides `{toFp, envelope}` is Invalid and reserves no id.
  `evaluate` follows Invalid → Expired → OutsideWindow → Duplicate → Full →
  Accept; only Accept reserves an id, so a Full bag refuses the offer without
  burning the id and can accept it once space frees.
- `open` applies the 24-hour parcel-TTL window (`MAX_PARCEL_TTL_MS`) and the
  five-minute future skew to `createdMs` and owns no replay set; id dedup is the
  carry core's bounded `seen`. Both are process memory, so a restart forgets
  them; the 24 h window and the TTL are the same bound and are the backstop.
  `evaluate` uses that same 24 h window, with expiry checked first so a
  TTL-elapsed parcel is always `Expired`; the window's remaining role is to
  reject a parcel dated more than `CLOCK_SKEW_MS` in the future. The window
  compares two positive timestamps by subtraction, so an extreme clock cannot
  overflow into a false Accept.
- `select` returns only live parcels addressed to the normalized `toFp` in
  deterministic order; `ack` and `expire` remove by id or TTL. `merge` dedups and
  trims deterministically, so two carriers holding the same parcels converge.
- There is no revocation beyond expiry: a held parcel, or a copy an addressee
  already decrypted, can be reopened after the sender leaves the Circle or a
  rekey happens. Courier adds no content cipher for events already in the shared
  Circle log; those stay plaintext JSON under the at-rest rule above.
- The durable bag row is owned by the caller (`courierBag` on `CircleState` in
  the app, `courier-bag/` in the CLI), not by the wire layer. A carrier process
  death before an ack therefore depends on that row being persisted by the app
  or CLI; the wire layer holds no durable carrier state.

Evidence: CODE for the codec and carry core and PURE for the courier tests.
Harness evidence belongs to the CLI carrier described below. A walk from a
carrier to an absent member is not device-verified, and a peer's acceptance of
an offer is not a delivery proof.

### CLI carrier storage (2026-11-30)

The CLI carrier adds a durable, portable bag on disk but introduces no new key
material and no new wire surface. Every path is rooted in `harnessHome()`, and
each invocation is one-shot: load state, do one operation, save state. The
carry-core paragraph above names `courier-bag/` as the caller-owned CLI row; the
CLI layer implements that storage as `courier-bag/` plus `courier.state`.

- **The copied bag is blind.** `courier-bag/` holds one `<envelope-id>.parcel`
  file per parcel. Each file is `CourierCarryCore.encode` of a `parcel` event whose
  body is exactly `{toFp, envelope}`: AES-256-GCM ciphertext, an IV, an
  RSA-OAEP-wrapped content key and the sender's signature and certificate. No
  private key, content key or plaintext event is written into the bag, and the
  CLI never parses, re-signs or rewrites the bytes. Copying the directory to
  another machine by USB or any file copy hands over ciphertext only — the
  receiver cannot decrypt, forge or redirect a parcel. The carrier still sees
  the clear `toFp`, message size and timing, and can refuse, delay, drop or
  partition parcels.
- **State rows stay local.** `courier.state` holds one JSON line per parcel
  `{id, recipientFp, senderFp, circleId, state, createdMs, expiresMs}` and is
  read with the same tolerant codec as `circle.state`: a missing, short or
  corrupt file decodes as empty. Rows are local bookkeeping only; they are never
  written into a Circle event, never published on `/circle/sync` and never
  copied with the bag. A bag file with no matching row is ignored by `receive`
  and `sweep`, never folded.
- **A parcel for another fingerprint stays pending.** `receive` opens only
  parcels addressed to the local identity from `loadOrCreateIdentity()`. A
  parcel whose recipient fingerprint differs stays `pending` and remains in
  `courier-bag/` for the next courier: that node is a carrier for it, not its
  addressee. The row moves to `delivered` — the CLI's local *opened and folded*
  state — only after `CourierEnvelope.open` succeeds and the allowed events are
  folded into local `circle.state`. That is local receipt by the addressee's
  CLI, not proof that any app on another device opened the parcel. A thrown
  `open` is not a conclusion and leaves the row `pending` for a later attempt.
- **A quarantined parcel is refused.** An `open` that returns a refusal — a
  forged or tampered envelope, a certificate whose digest is not `fromFp`, a
  wrong `circleId`/`toFp` or a `createdMs` outside the window — moves the row to
  `refused`, moves the file to `courier-rejected/`, and folds nothing into
  Circle state. A refused parcel is not re-exported as a live offer. `sweep`
  deletes files whose rows are `expired` or `refused`; a `delivered` file is
  kept in `courier-received/` as a local record and is never re-sent.
- **Fold-time gates match the app's.** Before an opened event reaches
  `circle.state`, `receive` requires `CircleCore.verify(event)` and
  `event.circleId == st.circleId`, applies the closed body gate for the event
  type, and excludes `JOIN` and `REKEY` plus events whose normalised author is
  not a current local member. No unverified or out-of-circle event is folded and
  no refused event becomes a state row. The fold, the archive move and the row
  update are one commit: a failed `saveRows` rolls back the archive and leaves
  the parcel unmarked, so no partial fold is reported.

The courier CLI implements no physical transport, adds no route, header, DTO
field or key parameter to the v2.2 wire. A handoff here is a directory copy on
the bench box; a peer's OK to an offered parcel means the offer was accepted,
not that bytes were delivered end to end. Event authorship remains a fold-time
`CircleCore.verify` decision, not a decision of the carrier.

### App handoff projection (2026-12-01)

The app layer adds a local-only projection for the file card. It introduces no
new wire surface and no new authority. `FlitEngine.courierHandoffs` is a
`StateFlow<Map<String, CourierCardUi.CourierHandoff>>` keyed by the SHA-256 of a file event learned by
sealing or opening an envelope, with the local values `waiting`, `handed` and
`failed`; an unknown or blank value reads as `waiting`. Here `handed` means the
peer accepted the offered parcel — it is not end-to-end delivery and carries no
remote proof that the recipient opened it or that bytes landed. A 30 s sweep
applies `CourierCarryCore.expire` and publishes outcomes, and `refreshCircle()`
folds the map into its content-diff signature so a new outcome republishes
without a log change. `MainActivity` collects the flow and passes it to `CircleScreen` as the
additive `courierHandoffs: Map<String, CourierCardUi.CourierHandoff> = emptyMap()`
argument. Sealing stays `circleCourierDrop(events, toFp)`: the recipient
certificate is taken from a signed event or the host roster, and with no
certificate the drop is refused and the source events stay in the sender's own
log. `FlitCircleSync.postParcel` posts the envelope as the single `PARCEL` kind
with the closed `{toFp, envelope}` body; `circleBodyValid` accepts no other key
set. The file shas learned while sealing are registered against the emitted
event id immediately after `emit` and before fanout, so a synchronous OK can
publish the accepted outcome. Opened inner events take the same app fold path as
any accepted sync event — `FlitCircleSync.fresh() → persistEvents() →
FlitCircleStore.upsert` — not the pure `FlitCircleStore.merge` reducer.

The handoff map is process-local. It is never signed — no canonical byte or
Circle event covers it — never persisted, and never gossiped: it never enters a
`CircleRequest`, so no peer can read another device's outcome. A restart clears
it; unlike the encoded `courierBag` on `CircleState`, there is no durable row to
restore. The bag and the signed `parcel` events remain the only durable courier
state. The projection is built from decrypted events that passed
`CircleCore.verify` plus the courier's local outcome; it carries no `envelope`
field and is not a trust anchor or an end-to-end delivery proof. A carrier that
cannot open an envelope has no SHA-256 to key a row with and publishes nothing,
so the card reads `waiting` rather than inventing a key from ciphertext.

No envelope text reaches the UI. `CourierCardUi.kt` is Android-free and holds a
typed `CourierHandoff(sha256, name, size, toFp, courierFp, fromFp, outcome)`; it
never parses a `parcel` body, never reads `envelope`, and never calls
`parcelOf`. `courierLine(...)` renders from `toFp`, `courierFp` and `outcome`
only: no `name`, `size`, `fromFp` or Base64 envelope string is copied into carry
copy. Hashes and fingerprints are compared after
`trim().lowercase(Locale.ROOT)`, the rule `CircleScreen` already applies.
`computeFeedData` filters `parcel` rows out of the feed, so no raw envelope
Base64 appears in the conversation; the decrypted `file` event with its exact
`{name,size,sha256}` body renders as an ordinary card. The Open affordance is
unchanged: `openAffordance(...)` yields `RESOLVING`, `LANDED` or `ABSENT` from
the landed-SHA set, and a carry line never claims bytes landed. The courier's
local outcome is consumed only when `meFp == courierFp`; a recipient or third
member sees a generic line, never another device's `handed` (offer accepted)
claim.

Evidence: CODE and PURE — `CourierCardUi.kt`, the `parcel` filter, the additive
card/screen seams and `postParcel` are traced in code, and `CourierCardUiTest`
pins the outcome-to-copy mapping and the absence of body/envelope text in
rendered copy. A parcel offered through a carrier to an absent member has no
device-verified end-to-end walk; a peer's `handed` answer is acceptance of the
offer, not delivery to that member's app.

## Air gap: QR parcel frames

The 2026-12-03 protocol layer adds a manual, no-radio path for one small
payload. `ParcelCodec` in the new JVM-pure `app.flit.share.protocol.parcel`
package turns a sealed payload into an ordered `List<String>` of QR frames and
turns captured strings back into the same bytes. A person carries the frames
across an air gap by showing, photographing or pasting them. The parcel package
has no socket, no radio carrier, no camera, no Android and no persistence; its
surface is `parse`, `encodeParcel` and `complete`.

### Signed and encrypted through one crypto path

`encodeParcel(payload, parcelId, recipientFp, recipientCert, signerFp,
identity)` does not invent a second cipher. Both the recipient fingerprint and
the recipient's X.509 certificate are explicit arguments: `recipientFp` is
required, and the certificate's own SHA-256 DER fingerprint must match it, so
a certificate for a different device cannot be substituted to redirect a
parcel. The content key is wrapped to that certificate's public key. It passes
the payload through `CourierEnvelope.seal`
(`protocol/.../courier/CourierEnvelope.kt:149-207`), so the bytes framed into
QRs are the signed envelope: AES-256-GCM ciphertext under a fresh 32-byte
content key, that key wrapped with RSA-OAEP/SHA-256 to the recipient
certificate, and a `SHA256withRSA` signature over the canonical envelope bytes
with the signer certificate's digest bound to `signerFp`. `complete(frames,
recipientFp, identity)` takes the decoding device's own `TlsIdentity`: it
checks the frames, each frame's CRC32, the sequence and count, the whole-parcel
digest and the recipient against the explicit `recipientFp`, then decrypts
through `CourierEnvelope.verify` and `open` (`CourierEnvelope.kt:215-261`) with
`identity`, whose own fingerprint must equal `recipientFp`. Opening needs that
device's private key to unwrap the content key; frames plus a fingerprint
string cannot decode a parcel. One failure returns `null` and no bytes.
Frame-layer caps are `MAX_FRAMES = 64`, `MAX_AIR_GAP_PARCEL_BYTES = 131072`,
`MAX_CHUNK_BYTES = 512` and the domain tag `FRAME_MAGIC = "FLITPARCEL1"`; the
byte cap is named apart from `CourierEnvelope.MAX_ENVELOPE_CHARS` so the two
limits are not confused.

Decode is not tied to the encoding process. The receiving device passes its own
`TlsIdentity` directly to `complete` along with `recipientFp`; there is no
in-memory identity registry and no requirement that encode and decode share a
process or that an encode in this process ran first. Any device holding the
identity whose fingerprint is `recipientFp` — and therefore its private key —
can open the parcel, so cross-device decode is supported.

### No key on a frame

A frame carries encrypted material only: a chunk of the sealed envelope's
encoded bytes plus the metadata needed to order and check it. No private key,
recipient certificate, content key, plaintext payload or shared secret is
emitted on any QR frame, and the frame grammar has no `key` parameter. A
`ParcelPayload` is produced only by `complete` after verification, using the
decoding device's private key supplied in its `identity` argument and matched
against `recipientFp`; nothing on the carrying side can turn a frame into key
material. The frame format is separate from the `flit://add` and
`flit://pair` pairing codes in "QR codes: pairing authority and invite bearer
semantics": a pairing code is not a parcel frame and a frame is not a pairing
credential, so carrying a parcel never pins a network peer and never supplies
an address to contact.

### The carrier sees frames

The carrier holds the frame strings and can read what `parse` exposes without
decoding the payload — `parcelId`, `seq`, `count`, `totalBytes` and `crc` — and
can see the opaque envelope bytes inside them. It can copy, delay, reorder,
drop, duplicate or photograph every frame, and it knows how many frames exist
and how large the declared parcel is. It cannot decrypt the payload, forge the
sender signature, unwrap the content key or redirect a blob to another
recipient: those need the recipient's private key or the sender's private key,
neither of which is on a frame. This is the same blindness rule as the courier
carrier above, moved to a human-carried medium. The guarantee is
confidentiality and integrity of the bytes, not availability of the carry.

### Wrong recipient refused

`complete(frames, recipientFp, identity)` releases bytes only when the checked
recipient fingerprint matches the parcel addressed by the frames and equals
both the explicit `recipientFp` and the decoding device's own
`identity.fingerprint`. It verifies frames, CRC, sequence, count and digest
first, then decrypts through `CourierEnvelope.open` with that identity, which
independently requires the envelope's `toFp` to equal the opening identity's
own fingerprint (`CourierEnvelope.kt:235-261`) and uses its private key to
unwrap the content key. A parcel addressed to a different
fingerprint is refused even with intact frames, a passing CRC and a valid
signature; opened bytes naming another recipient are never returned as
`ParcelPayload`. A failed digest, a failed signature, a recipient mismatch or
an identity without the matching private key returns `null`, writes no file
and releases no partial bytes; held frames stay with the caller until it drops
them.

### No remote action

None of these operations acts at a distance. There is no camera capture, no QR
rendering, no socket, no Android component, no file write and no persistence in
the parcel package; the codec only maps `String` to `ByteArray` and back.
Capturing or scanning a frame is a local verification step, not a command: it
does not dial a peer, join a Circle, open a file, acknowledge delivery or change
another device. The signer pin written by `CourierEnvelope.verify` is the same
bounded, in-memory pin described in "Signatures and cert-in-event"; it is local
state, not a trust decision about a network peer. Whether a scanned frame lands
a file or triggers other app behavior is a separate app concern, not a property
of the frame codec.

Evidence: CODE for the frame layout, constants and crypto seam, and PURE for
round-trip, CRC, cap and refusal tests. Real-camera decode, a carrier walk and
device verification are separate CLI and app concerns.

## Air-gap CLI: certificate in hand, identity on decode (2026-12-04)

The 2026-12-04 CLI layer adds two one-shot, offline commands over the
2026-12-03 parcel codec: `flit-cli parcel encode <file> --dir DIR --to FP
--cert PATH` and `flit-cli parcel decode --dir DIR --out PATH`. It moves no
trust anchor into the frame directory and adds no second crypto path.

### Recipient certificate travels out of band

`--cert PATH` is an operator-supplied file holding Base64 DER of the
recipient's X.509 certificate. The CLI reads it for one encode invocation,
parses it with `CertificateFactory`, and holds the certificate object in memory
only for that call. No certificate is copied into `--dir`, written into a frame
or fetched from a peer. Frames carry encrypted material only — no private key,
recipient certificate, content key, plaintext or shared secret ("No key on a
frame" above). Whoever copies the PNG directory therefore gets ciphertext and
frame metadata, not the certificate needed to address or redirect a parcel.

### Fingerprint checked against `--to`

Encode normalizes `--to` and requires `TlsIdentity.fingerprintOf(cert)` — the
uppercase-hex SHA-256 of the certificate's DER encoding, the same root of trust
described in "Trust anchor: fingerprint and certificate pinning" — to equal the
normalized `--to`. A certificate whose own digest differs from `--to` is refused
before any PNG is rendered; no frame is emitted for a mismatched pair. The
checked certificate object is then passed directly to `ParcelCodec.encodeParcel`
as `recipientCert`, so `CourierEnvelope.seal` wraps the content key to its public
key and the codec's own cert-bind and signature rules apply.

### No pin registry or self-addressed fallback

Encode does no recipient pin lookup. The CLI does not consult a stored
fingerprint-to-certificate map, a roster or a key server to find the addressee,
and it has no self-addressed fallback that silently redirects the parcel to the
sender's own identity. The one certificate used is the one supplied by `--cert`
and checked against `--to` for that invocation. The bounded in-memory pin cache
described in "The pin cache" is a verification aid for signed network events;
the parcel CLI's encode gate does not depend on it, and nothing is persisted
between invocations.

### Decode needs the local identity

`parcel decode` calls `loadOrCreateIdentity()` and passes both
`identity.fingerprint` and the `TlsIdentity` object to `ParcelCodec.complete`
as `complete(frames, identity.fingerprint, identity)`. Verifying frames, CRC,
sequence, count, digest and recipient is only the first half: `complete` opens
the envelope through `CourierEnvelope.open` with that identity's private key,
and the RSA-OAEP-wrapped content key can be unwrapped only by the identity whose
fingerprint is `recipientFp`. A fingerprint string plus frames cannot produce
bytes. No in-memory identity registry is consulted, and decode is not tied to
the encode process: any box holding the identity named by `recipientFp` can
decode, including a different machine from the one that encoded; a box holding
a different identity gets no output. An output file is written only when
`complete` returns a `ParcelPayload` whose `recipientFp` equals the local
`identity.fingerprint`. No file name travels in a frame — `--out` supplies it.

Evidence: CODE for the CLI adapter, flags and the cert gate, and SIM-HARN for
the round-trip and refusal cases in `ParcelCliTest`; the codec crypto and digest
checks stay PURE tests. The parcel CLI adds no camera capture, radio carrier or
device walk.

## Air-gap app: camera frames stay local (2026-12-07)

The 2026-12-07 app layer adds the display and camera intake surfaces over the
2026-12-03 parcel codec. It adds no wire surface, no route, no carrier and no
new authority: a person shows frames on one screen and photographs or pastes
them into another device, and the existing codec verifies and opens them on
that device. The camera, the Compose cards and the one-frame-at-a-time state
holder are local code; the codec's frame grammar, caps and crypto stay
unchanged and are consumed, not redefined.

### Camera frames stay local

A captured frame goes from the camera analyzer into the scan state holder and
no further until it is verified. The parcel camera follows the `CameraQrView`
analyzer shape in `QrUi.kt:265-314`: it copies only the Y (luminance) plane with
its row and pixel strides, throttles to about 8 fps and passes the decoded
string to a local callback (`Qr.decodeYPlane` in `Qr.kt:187-216`). The scan card
feeds that string to `ParcelScanState.add`; the state holder keeps the opaque
frame strings in memory, deduplicated by `(parcelId, seq)`, and reads only
`parcelId`, `seq` and `count` from `ParcelCodec.parse` for gating and progress.
No frame, decoded text or partial payload is written to a network socket, a
broadcast, a Circle event, a relay hello, a panic route or any peer. Nothing is
persisted on an incomplete scan: closing the card drops every held frame and
writes no file, ledger row or delivery record. The paste path feeds the same
state holder, so a pasted frame has the same local-only fate as a photographed
one.

### Verification needs the local identity

Reassembly closes through `ParcelCodec.complete(frames, recipientFp,
identity)` (`ParcelCodec.kt:183-239`). The app passes its own `TlsIdentity`
beside the recipient fingerprint: the fingerprint gates the recipient and the
identity's private key unwraps the RSA-OAEP content key in
`CourierEnvelope.open`. Frames and a fingerprint string alone cannot produce
bytes. The check folds frame CRC, sequence, count, whole-parcel digest,
envelope signature and recipient into one call, so there is no separate verify
entry point the UI can skip. A wrong recipient, a failed digest or signature,
or an identity without the matching private key returns null and releases no
payload. The app writes bytes only through the existing received-file or feed
path after a payload is returned, under a save name chosen locally; no file
name travels in a frame.

### No remote action

Scanning or showing a frame does not act at a distance. It does not dial a
peer, pin a network peer, join a Circle, acknowledge a delivery, write to a
remote route or change another device. The signer pin written by
`CourierEnvelope.verify` is the same bounded, in-memory pin described in
"Signatures and cert-in-event"; it is local verification state, not a trust
decision about a network peer. A parcel frame is not a `flit://add` or
`flit://pair` code: showing or scanning one supplies no address, pins no
device and carries no pairing authority. The only durable effect is the
verified bytes handed to the local receive path; everything else in the scan
is transient.

### No key on a frame

The app renders and reads only the opaque frame strings returned by
`ParcelCodec.encodeParcel`; it never interprets, rewrites or emits key
material. As stated in "No key on a frame" above, a frame carries encrypted
material and the metadata needed to order and check it only — no private key,
recipient certificate, content key, plaintext payload or shared secret — and
the frame grammar has no `key` parameter. The display card renders those
strings through `Qr.encode` at EC level M, margin 2 and
`PARCEL_FRAME_SIZE_PX = 240`, advancing every
`PARCEL_FRAME_INTERVAL_MS = 400L`; the scan card stores the same strings
unchanged. The receiving identity's private key lives only in the app's
`TlsIdentity`, never in a frame or a QR image rendered from one.

Evidence: CODE for the camera, UI and state seams and PURE for the app-side
reassembly and progress tests. Real-camera multi-frame capture and a device walk
are not verified.

## Panic: authorization, hops, and informational-only delivery

Panic is a signed, hop-counted Flit-only route (`/api/flit/v1/panic`); it adds no
v2.2 shape and no auto-action (`docs/qr-only-and-panic.md:6-16`).

- **Create:** `PanicCore.create` pins the sender's own certificate, mints a
  random UUID `id`, sets `originFp = senderFp`, and signs the canonical bytes
  (`PanicCore.kt:85-109`).
- **Verify:** `PanicCore.verify` resolves the pinned certificate by `senderFp`
  and verifies the signature (`:111-116`). The route pins the header cert under
  its own digest and requires it to equal `senderFp` before delivering
  (`ServerCore.kt:920-939`).
- **Hops:** `hops` is inside the signed bytes. `forward` re-signs the **original**
  alert with the forwarder's identity: `senderFp` becomes this hop's sender,
  `originFp` preserves the original author, `id` and `text` survive for dedup,
  and the count is bumped. It returns `null` when `alert.hops + 1 >= maxHops`
  (`PanicCore.kt:163-186`). A bumped count with the original signature fails
  verification. The receiver gates on `originFp` (defaulting to `senderFp`)
  *and* requires that origin to be QR-known, so a known intermediary cannot
  smuggle in an unknown author (`FlitEngine.kt:402-423`;
  `docs/qr-only-and-panic.md:18-26`).
- **Fan-out is explicit and gated.** `sendPanic`/`forwardPanic` target only Known
  devices, over each device's route policy. Bluetooth is tried first, then the
  relay, then the remembered Wi-Fi route; the relay is used only when that
  device's `relayFor` switch is on and the policy permits it
  (`FlitEngine.kt:461-499`). There is no broadcast to unknown peers and no
  automatic forwarding.
- **UI is advisory:** banner with fingerprint/alias/time/note, and
  Acknowledge / Dismiss / Forward. Nothing is wiped, locked or changed remotely
  (`docs/qr-only-and-panic.md:14-16`).

**Failure modes:** unverified or unknown-origin alerts are logged and dropped
before any banner; duplicate ids are skipped locally as well as at the server;
a `null` from `forward` toasts "alert reached its hop limit"; UI state is
capped at 20 events (`FlitEngine.kt:411-420`, `:446-451`). Note the exact hop
condition in code: forwarding stops when the *next* count would be `>= 3`, i.e.
at most two re-signed forwards from the origin; an alert received at `hops >= 3`
is `HopLimit` and is not delivered.

Pure-test evidence: `PanicTest.kt:103-247` covers edit/forgery rejection, 403 on
a forged `senderFp`, once-per-id delivery, hop limit and stale window, the
unchanged v2.2 404/200 shape, and an end-to-end signed send through `LinkServer`.
On-device evidence (`docs/qr-only-and-panic.md:28-34`): box added through QR v2
paste (`scan: pinned Flit (szszszs) (code verified)`), a phone-sent alert logged
by the box with sender fingerprint and text, and a box-sent alert produced a
phone banner with alias, note and safety copy. Two-phone mesh hops remain
unverified.

## Relay: blindness and per-device opt-in

The relay is a self-hosted blind pairer, off by default (`docs/milestone-e.md`).
Its entire wire vocabulary is one bounded line:

```
FLITRLY1 req <targetFp> [<token>]
FLITRLY1 sub <selfFp>   [<token>]
```

`RelayHello.MAX_LINE = 512`, `MAX_FINGERPRINT = 128`, `MAX_TOKEN = 128`; parse
is strict (`protocol/.../relay/RelayCore.kt:40-66`). `RelayCore` pairs FIFO per
fingerprint, never pairs two senders or two receivers, gates every hello on an
optional shared token with a **constant-time compare**, and `withdraw`s parked
tickets whose connection died so a ghost cannot pair (`:70-152`). After the
`OK`, `RelayLinkProvider` and `RelaySubscriber` run the unmodified protocol with
TLS inside the pipe (`protocol/.../relay/RelayEndpoints.kt:25-109`).

- **What blindness means:** the relay copies opaque bytes. The shipped CLI logs
  only a timestamp and ticket ids (`pair c3 + c4`, `pair c3 + c4 closed`) — no
  fingerprint, no byte count, never content (`harness/.../RelayServer.kt:84-122`,
  `:140`, `:211`). A malicious relay can refuse, delay, drop or kill a pair; it
  cannot forge a file, a fingerprint or a finished hash
  (`docs/milestone-e.md:14-28`).
- **Per-device opt-in governs a device's traffic, not the relay's door.** A Known
  devices entry needs an address/token and `relayFor` on before Flit will route
  that device's sends, Circle dials or panic fan-out through a relay
  (`FlitEngine.kt:477-491`, `:813-830`, `:796-832`; `README.md:163-165`). An
  opted-out device is never sent that way. The relay's own access control is
  the separate optional shared token in Options/CLI — one token per relay, not
  a per-peer ACL (`docs/milestone-e.md:30-61`, `:90-93`).
- **Failure mode:** a relay death mid-body is a carrier death —
  `TransferUnreachable` is thrown, the sender keeps its queue row and spool,
  and the next hunt re-pairs through the restarted relay and resumes from the
  receiver's on-disk prefix. No relay-side storage exists; both peers must be
  live (`docs/milestone-e.md:67-81`, `docs/transport-bluetooth.md:108-119`).

Pure-test evidence: `RelayCoreTest` (11 tests: match, wrong token, both-sides-live,
isolation, clean close) and harness `RelayServerTest` (3 MB production send
hash-exact, wrong token refused on the wire, unpaired subscription times out,
dead relay dial throws). On-device evidence: the 2026-11-24 relay-kill walk
recorded in `docs/transport-bluetooth.md:108-119` — a 200 MiB shot crossed the
blind pairer, and a 300 MiB shot cut by `kill -9` on the relay resumed from the
exact killed prefix.

## File announcements: the no-path rule and local-only status

A Circle file drop is two separate things and the announcement proves nothing
was delivered (`docs/circle.md:189-193`):

- The published event body is **exactly** `{name, size, sha256}` — no bytes, no
  spool path, no queue token. `CircleCore.FILE_BODY_KEYS` is the closed set
  `{name, size, sha256}`, and `validFileBody` refuses any extra key (`token`,
  `path`, `spoolPath`, `extra`), a string `size`, a negative size, blank
  strings, and oversized metadata (`CircleCore.kt:34-35`, `:304-324`). That is
  the enforcement of "a sender's local path never appears in a signed,
  fanned-out event".
- The bytes go by a direct per-member `sendFiles` call from the sender's private
  spool (`cacheDir/circle/...`), never on the send-later/bulk queue, and the
  landing is accepted only when its hash matches the announced SHA-256
  (`docs/circle.md:189-207`, `:233-235`).
- Delivery status is **sender-local only**. One record per member holds
  `pending`, `sent`, `refused` or `failed`; `sent`/`refused`/`failed` are
  terminal and `pending` is the only owed state. Status is not an event, is not
  gossiped, and a recipient's card cannot claim the bytes are missing
  (`docs/circle.md:208-231`). A later joiner folds the signed announcement but
  gets no backfill of old bytes.
- The Open affordance has three states: disabled "Opening…" while the
  hash resolves, "Landed here" + "Open file" only when a local copy with that
  SHA-256 exists, and no button at all otherwise. Hashes are compared
  case-insensitively, matching the engine (`docs/open-file-and-sharing-restore.md:143-161`).

Pure-test evidence: `CircleCoreTest.kt:231-244` and
`CircleHardeningTest.kt:273-302` reject every extra-key/wrong-type/oversized
file body. Harness evidence: `CircleHarnessTest.kt:233`, `:242`, `:417`, `:424`
assert the live announcement and landed event both pass `validFileBody`.
On-device evidence: the 2026-11-25 Open-file walk — a 35 B drop landed
hash-exact, the card offered Open, the chooser opened without a crash, back
returned to the feed, and `am force-stop` + relaunch restored sharing silently
from the persisted desired flag (`docs/open-file-and-sharing-restore.md:247-259`).

The keyed form added at 2026-12-09 keeps the signed `file` verb but carries
wrapped key material instead of content bytes; see "Per-drop content keys:
confidentiality for file drops" and its app-and-CLI integration in "Keyed drops
in app and CLI: confidentiality and receive gate (2026-12-10)". The no-path rule,
the hash gate on the bytes and the sender-local status rule above apply unchanged
to both the legacy and the keyed form.

## Per-drop content keys: confidentiality for file drops (2026-12-09)

The 2026-12-09 protocol layer gives each Circle file drop a fresh symmetric key.
It is a JVM-pure object at
`protocol/src/main/kotlin/app/flit/share/protocol/circle/DropKeyCore.kt`, with no
socket, no Android import and no persistence. A drop's bytes are sealed before
they are announced and sent, and the key travels only as one RSA-wrapped entry
per current member inside the signed `file` announcement. The exact API is:

```
object DropKeyCore {
  const val KEY_BYTES = 32, IV_BYTES = 12, TAG_BYTES = 16
  const val MAX_WRAPS = 32, MAX_WRAPPED_CHARS = 512
  const val DOMAIN = "FLITDROP1"
  data class Key(val bytes: ByteArray, val iv: ByteArray)
  data class KeyedFile(val name: String, val size: Long, val sha256: String,
                       val keyGen: Int, val cipherSha: String, val iv: String,
                       val wraps: Map<String, String>)
  fun mint(): Key
  fun wrap(key: ByteArray, recipients: Map<String, X509Certificate>): Map<String, String>
  fun unwrap(wrapped: Map<String, String>, identity: TlsIdentity): ByteArray?
  fun seal(key: Key, plaintext: ByteArray): ByteArray   // IV || ciphertext || tag
  fun open(key: ByteArray, sealed: ByteArray): ByteArray?
  fun keyedFileBody(v: KeyedFile): String
  fun parseFileKeyBody(body: String): KeyedFile?
  fun keyId(dropId: String, keyGen: Int): String = "$dropId:$keyGen"
}
```

### A fresh key per drop, sealed before announcement

`mint()` draws a 32-byte key and a 12-byte IV from `SecureRandom`; neither value
is derived from the Circle group key, the invite token or the file hash. Two
drops of identical bytes therefore carry different IVs, different sealed bytes
and different `cipherSha` digests. `seal(key, plaintext)` uses
`Cipher.getInstance("AES/GCM/NoPadding")` with
`GCMParameterSpec(TAG_BYTES * 8, iv)` and emits `IV || ciphertext || tag`: the
12-byte IV prefix, the AES-GCM body and a 128-bit tag. The additional
authenticated data is the length-prefixed domain tag `field("FLITDROP1")`, using
the same 4-byte big-endian UTF-8 field rule as `CircleCore.canonical`. The file
event id is not in the AAD because the bytes are sealed before the event is
minted. `open(keyBytes, sealed)` refuses a value shorter than
`IV_BYTES + TAG_BYTES`, splits the prefix and returns plaintext or null on any
tag failure. Flipping one IV, ciphertext or tag byte, using another key or
changing the AAD yields null.

### Wraps go to current members, with no fallback and no escrow

`wrap(key, recipients)` encrypts the 32-byte key with each recipient
certificate's RSA public key using `RSA/ECB/OAEPWithSHA-256AndMGF1Padding` and an
explicit `OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256,
PSource.PSpecified.DEFAULT)`, Base64-encodes the result and returns a map keyed
by uppercase fingerprint. There is one entry per current member with a usable
certificate, capped at `MAX_WRAPS = 32`; an over-cap roster is refused, never
silently trimmed. A member with no usable certificate gets no entry, and there
is no group-key fallback and no escrow: no copy of the content key is written,
logged or handed to a third party. The group key stays membership/rekey material
and is not a content cipher, as stated in "Circle wire authority, roles and
rekey". `unwrap(wrapped, identity)` decrypts with the local private key through
the one additive instance method on `TlsIdentity`,
`decryptOaepSha256(cipher: ByteArray): ByteArray?`, which returns null on
malformed Base64, padding or provider failure and never reads or writes the pin
map. A fingerprint absent from `wraps`, a wrong identity or a malformed blob
yields no key.

### The keyed file body and its gate

A keyed `file` body has exactly `KEYED_FILE_BODY_KEYS = {name, size, sha256,
keyGen, cipherSha, iv, wraps}`; the legacy `{name, size, sha256}` set is
unchanged and stays valid for old drops. `CircleCore.validFileBody` accepts
either set and refuses a partial or extra key set, a string or negative `size`,
a non-positive `keyGen`, an `iv` that does not decode to exactly 12 bytes, a
blank or lowercase fingerprint key, an empty or over-cap `wraps` map, and a wrap
value over `MAX_WRAPPED_CHARS = 512` (a 2048-bit RSA-OAEP wrap is about 344
Base64 chars, so `MAX_FIELD_CHARS = 256` would refuse a valid wrap). Every other
string stays within `MAX_FIELD_CHARS` and the body within `MAX_BODY_CHARS`
(16 KiB). `keyedFileBody(...)` builds exactly this map and `parseFileKeyBody(body)`
returns a typed `KeyedFile` only when the gate passes, otherwise null. Canonical
bytes and signatures are unchanged: `CircleCore.canonical` covers the body as
one length-prefixed field, so the signature binds `cipherSha`, `iv` and every
wrap; a courier or relay carrying the event cannot alter them.
`keyId(dropId, keyGen) = "$dropId:$keyGen"` is the identifier a future delivery
receipt can name as provenance, bounded by `MAX_FIELD_CHARS`.

### No remote revocation of a past drop

A departure, a group `rekey`, or a bumped `keyGen` on another drop does not change
a published drop body. There is no wrap backfill for a later joiner and no remote
revocation: a device that kept a wrap can still open an old drop, and removing a
member from the roster cannot unwrap or delete a copy another device already
holds. The protocol core holds no key cache, no sealed bytes and no drop state;
the unwrapped key is an in-memory value owned by the caller for one decrypt pass.
This matches the courier rule in "Courier: recipient-cert encryption and carrier
blindness" — expiry ends a held parcel, not one an addressee already decrypted —
and the at-post-time delivery rule in "File announcements".

Evidence: CODE for the object, byte rules, gate and constants, and PURE for the
protocol tests (`DropKeyCoreTest.kt`, `DropKeyBodyTest.kt`,
`DropKeyCryptoTest.kt`). No harness walk and no device observation are made for
this layer; the sealed drop is opaque payload to the courier and QR frame codecs,
and the app and CLI receive path is a later integration.

## Keyed drops in app and CLI: confidentiality and receive gate (2026-12-10)

The 2026-12-10 integration wires `DropKeyCore` into the app's Circle drop path
and the CLI's `circle file` path. Both surfaces mint one fresh key per file drop,
wrap it to the current members, seal the file bytes with AES-GCM, announce the
sealed drop in a signed `file` body — through `FlitCircleSync.postFile(body)` in
the app and the keyed `Harness.circleFileAnnouncement` overload in the CLI — and
push the sealed bytes with `sendFiles`. The wire shape is unchanged: the same
`/circle/sync` route, the same `file` verb, no new route, header, DTO field, QR
parameter or v2.2 status code. Legacy three-key bodies stay plaintext and keep
their current path.

### Only current members can read the bytes

A keyed drop's confidentiality rests on the wrap map, not on the transport. The
sender wraps the one content key to each current member's certificate, keyed by
uppercase fingerprint, with `MAX_WRAPS = 32` and no fallback
(`DropKeyCore.wrap`). With no usable certificate, or a roster above the cap, the
drop is refused with a log line and no event is posted; the source spool is kept.
The bytes pushed by `sendFiles` are the AES-GCM ciphertext and the declared
`FileInfo.sha256` is the ciphertext digest `cipherSha`, so a carrier, relay,
passive LAN observer or a device with no wrap holds only ciphertext, IV, wraps,
addressee fingerprints, timing and size. A non-member, a member who left before
the drop, and a later joiner get no wrap and no backfill; none can derive the
content key from the sealed bytes, the group key or the public event log. The
group key stays membership/rekey material and is never a content cipher, as
stated in "Circle wire authority, roles and rekey". TLS keeps the body and bytes
off the wire in cleartext, but the property that limits plaintext to members is
the wrap, not TLS. The keyed `file` body is still plaintext JSON in the shared
log under the at-rest rule in "Storage and permissions"; it carries no content
plaintext, only the wrapped key, the ciphertext digest, the IV and the wrap map,
so a member who can read the log still cannot read the file bytes without a wrap.

### No escrow

The content key has three forms only: the wrapped entries inside the signed
`file` body, the sender's ephemeral `DropKeyCore.Key` until the drop finishes,
and an in-memory unwrapped key held by the receiver for one decrypt pass through
`DropKeyCore.unwrap`. `FlitCircleStore` and the CLI's `circle.state` persist no
plaintext key; the sealed spool lives in app cache or the CLI temp path and is
removed when every recipient is terminal or the drop is refused. There is no key
server, no certificate authority and no key escrow to audit, the same rule as the
courier envelope in "Courier: recipient-cert encryption and carrier blindness".
A wrap is addressed with the recipient's public certificate and can be opened
only by the identity whose private key matches that fingerprint.

### No remote revocation

A departure, a group `rekey`, or a bumped `keyGen` on another drop does not change
a published body. `cipherSha`, `iv` and every wrap are bound by the sender's
signature through `CircleCore.canonical`, so a third party cannot rewrite them;
removing a member from the roster provides no way to unwrap or delete a copy that
device already holds. There is no remote action that invalidates, deletes or
recalls a landed drop, no wrap backfill for a later joiner and no second content
key per recipient. This repeats the protocol-layer rule in "No remote revocation
of a past drop" and the courier TTL rule: expiry or removal ends a held copy, not
one an addressee already opened.

### The receive digest gate refuses tampered plaintext

Both receive surfaces apply the same gates in order before any plaintext is
written. On the app, `FlitEngine.onUploadFinish` matches a landed
`UploadResult.Saved` digest against the in-memory `cipherSha` index rebuilt from
the bounded folded event log; on the CLI, `ConsoleDelegate.onUploadFinish` does
the same against `circle.state` events. The receiver then calls
`DropKeyCore.unwrap(wrapped, identity)` with its own `TlsIdentity`,
`DropKeyCore.open(key, sealed)` for the AES-GCM tag check, and compares the
opened bytes' SHA-256 with the plaintext `sha256` announced in the signed body.
Plaintext is written under the announced name and recorded in the ledger only
when all of those pass. A fingerprint absent from `wraps`, a wrong identity, a
short or tampered sealed value, a GCM tag failure or a plaintext digest mismatch
refuses the drop: the app logs `circle drop: cannot open <name> (<reason>)`, the
CLI prints `drop-refused <name>: no wrap` or `drop-refused <name>: open failed`,
no ledger entry is added and the landed ciphertext is dropped. The app's
`FlitCircleSync.circleBodyValid` FILE branch accepts only the exact legacy or
keyed key sets, each wrap value bounded by `MAX_WRAPPED_CHARS = 512`, so a
partial, extra or oversized body is refused before any unwrap is attempted. A
modified `wraps`, `iv`, `cipherSha` or `sha256` fails signature verification
first, because `CircleCore.canonical` covers the whole body. A landed file with
no `cipherSha` match takes the legacy plaintext path unchanged.

Evidence: CODE for the app and CLI seams and the receive gate, PURE for the core
crypto and body tests, and SIM-HARN for the CLI keyed-drop round-trip and refusal
cases recorded in the integration completion. No device walk is claimed for this
layer; a keyed drop landing on the phone belongs to the later feature walk.

## Delivery receipts: local-landing acknowledgement, not global delivery proof (2026-12-11)

A receipt is an ordinary signed `CircleEvent` — the new `CircleEvent.RECEIPT =
"receipt"` verb — in which one member acknowledges that a named file drop landed
on that member's device. The statement is a local fact about the signing device.
It is **not global delivery proof**: it says nothing about whether any other
member landed the bytes, and no peer can read a receipt as evidence that the
whole Circle received the drop. It does not replace the sender-local
`CircleDelivery.statuses` (`pending`, `sent`, `refused`, `failed`) or the file
announcement; those rules in "File announcements" stay unchanged
(`docs/roadmap/delivery-receipts.md`).

### What the signed body carries

A receipt body is a bounded JSON object with exactly the closed key set
`CircleCore.RECEIPT_BODY_KEYS = {dropId, sha256, keyId}`:

```
receipt  {"dropId":"<id of the file event>","sha256":"<landed plaintext digest>","keyId":"<per-drop key identifier>"}
```

`dropId` is the `id` of the `file` event being acknowledged, not a local delivery
id. `sha256` is the plaintext digest read after `DropKeyCore.open` and the
landing hash gate. `keyId` is provenance copied from the per-drop key layer: it
is an identifier, **not key material**. The body carries no private key, content
key, wrapped key, file bytes, `path`, `spoolPath`, `token` or local status string,
and there is no second time field — `createdMs` on the event is the
acknowledgement time. `validReceiptBody` parses with `WireJson.json`, requires
the exact key set, and requires every value to be a non-blank string within
`MAX_FIELD_CHARS` (256) with the body within `MAX_BODY_CHARS` (16 KiB). It signs
nothing, pins nothing and reads no store.

### Signed, verified and counted like other Circle events

Receipts reuse `CircleCore.canonical` unchanged: domain tag `FLITCIRCLE1`, then
length-prefixed `id`, `circleId`, `authorFp`, `lamport`, `type`, `body` and
`createdMs`. The body is one length-prefixed field, so no delimiter can be
smuggled through it. `CircleCore.sign` writes `sig`, and `CircleCore.verify`
binds the embedded certificate's own SHA-256 DER to `authorFp` before verifying
and pinning — the pin-after-verify rule in "Signatures and cert-in-event". A
sender therefore cannot mint a receipt for another member. This is the same
`TlsIdentity.sign` / `verify` path used by panic, Circle and courier events; no
second crypto primitive is introduced.

Replay and membership use the shared gates: `REPLAY_WINDOW_MS = 6 h`,
`CLOCK_SKEW_MS = 5 min`, `MAX_SEEN = 4096`, `MAX_EVENTS_PER_REQUEST = 1024`,
`MAX_REQUEST_BYTES = 8 MiB`, with precedence
`Invalid → OutsideWindow → Duplicate → Accept` and verify before `evaluate`.
`CircleCore.receiptAuthors(events, dropId, sha256, members)` counts a receipt
only when its body is well formed, both `dropId` and `sha256` match the drop
being read, its signature verifies and its author is in the supplied normalised
current-member set. At most one receipt per author counts, chosen by
`CircleCore.ORDER` independent of arrival order. `keyId` is not a selection key:
a later rekey that changes the identifier does not invalidate an earlier
acknowledgement of the same landed content.

### No new wire surface or durable receipt state

No new route, header, DTO field, frame, QR parameter or v2.2 status is added. A
receipt travels in `CircleRequest.events` on the existing
`POST /api/flit/v1/circle/sync` under the `8 MiB` cap, or inside a courier or QR
parcel that already carries signed events. The protocol module holds no durable receipt
state; `FlitCircleStore.reduce` appends the new verb as history under the
existing 500-event log cap. Receipts are signed events, not gossiped status: the
process-local courier `handed` outcome in "App handoff projection" and the
sender-only delivery record in "File announcements" are unchanged. App minting,
file-card display and CLI receipt commands belong to the 2026-12-14 integration,
not this layer.

Evidence: CODE for the verb, body gate and roll-up, and PURE for the protocol
tests on 2026-12-11. No harness or device observation of receipts exists on this
date.

## Delivery receipts in app and CLI: local landing, no remote action (2026-12-14)

The 2026-12-14 integration puts the 2026-12-11 receipt verb on both surfaces.
The app mints a receipt in `FlitEngine.onUploadFinish` after a keyed drop's
plaintext digest matches the signed `file` event, and publishes it through
`FlitCircleSync.postReceipt` on the existing fan-out path. The CLI mints one with
`circle receipt --sha SHA [--drop-id ID] [--host ip[:port]]`, resolving the
`file` event by its plaintext sha and deriving `keyId` from that event's signed
`keyGen`. Both surfaces consume `CircleCore.receiptAuthors` for the confirmed
count. The trust properties below are properties of that one signed event; the
integration adds no new trust anchor.

### A receipt is a local landing acknowledgement

A receipt states one fact about the signing device: a named drop landed there
hash-exact. It is not a claim about any other member and not global delivery
proof. The app mints nothing on a digest mismatch, an `Error`, a carrier throw,
a drop without `keyGen` and `wraps`, or a legacy plaintext drop; the CLI exits
without an event when the sha lookup or the keyed body is missing. Neither
surface fabricates a `dropId` or a `keyId`. A receipt does not replace the
sender-local `CircleDelivery.statuses` and does not create a delivery row that
never existed; it only maps a confirmed author to `SENT` through the existing
`setDeliveryStatus` reducer inside one `FlitCircleStore.update` call. The
sender-local status rules in "File announcements" still apply.

### No remote action

Minting or publishing a receipt does not act at a distance. It does not dial a
peer, open a file, set a status on another device, join a Circle, wipe or lock
anything, or command a recipient. The event travels only in
`CircleRequest.events` on `POST /api/flit/v1/circle/sync` or inside a courier or
QR parcel that already carries signed events; no route, header, DTO field, QR
parameter or v2.2 status is added. A confirmed count is a local fold over the
bounded signed log, not a message sent to anyone. This is the same "no remote
action" rule as the parcel surfaces above: the durable effect is a signed event
in the local log and a local status write, and no peer can be made to change by
receiving it.

### No key material

The body is exactly `CircleCore.RECEIPT_BODY_KEYS = {dropId, sha256, keyId}`.
`keyId` is provenance derived from `keyGen`, an identifier, not a private key,
content key, wrapped key or shared secret. There is no per-recipient wrap, no
file bytes, no `path` or `spoolPath`, no token, no status string and no extra
time field — `CircleEvent.createdMs` is the acknowledgement time. The closed
body gate `CircleCore.validReceiptBody` accepts only three non-blank strings
within `MAX_FIELD_CHARS` (256) in a body within `MAX_BODY_CHARS` (16 KiB), so
extra, blank, non-string or oversized fields are refused at both app gates
(`FlitCircleSync.circleBodyValid` and the `FlitCircleHost` SYNC filter). The
whole body is one length-prefixed field in `CircleCore.canonical`, so the sender
signature binds every value and a carrier cannot rewrite the `sha256` or the
`keyId` without failing verification. Per-drop content keys stay on the drop
path described in "Per-drop content keys" and "Keyed drops in app and CLI"; no
key is added to the receipt.

### The tally is member-gated

`CircleCore.receiptAuthors(events, dropId, sha256, members)` counts a receipt
only when its author fingerprint is in the supplied **normalized current-member
set**, its body is well formed, both `dropId` and `sha256` match the drop being
read, and `CircleCore.verify` passes with the cert bound to `authorFp`. A
departed member, a non-member, a forged receipt and a body for another drop
produce no confirmation. At most one entry per author counts, chosen by
`CircleCore.ORDER` independent of arrival order; `keyId` is not a selection key,
so a later rekey does not invalidate an earlier acknowledgement. Entries enter
the local log through the roster-gated SYNC filter, and replay uses the shared
`6 h` window, `5 min` skew, `MAX_SEEN = 4096` and verify-before-evaluate order,
so a replayed or stale id leaves the count unchanged. The confirmed set is
folded by the app's pure helper and by the CLI's `circle sync` / `circle file`
display, never gossiped as a status, and a non-member author never reaches a
card or a delivery row.

Evidence: CODE for the app mint/publish seams, the CLI command, the two app
gates and the member-gated roll-up; PURE for the body, tally and canonical tests
and the app confirmation helper; SIM-HARN for the two-bot confirm walk and its
forgery/refusal cases. No device receipt walk is claimed.

## Circle pad: signed events, route verification, plaintext bodies (2026-12-15)

The 2026-12-15 protocol layer adds one `CircleEvent` verb, `PAD = "pad"`, and a
JVM-pure fold in `protocol/src/main/kotlin/app/flit/share/protocol/circle/PadCore.kt`.
The pad is a shared, line-oriented text view rebuilt from the signed event log;
it adds no route, header, DTO field, persisted field or device walk. It rides the
existing `/circle/join` and `/circle/sync` routes through the existing fan-out
and repair path (`docs/roadmap/circle-pad.md`). The properties below belong to
that one signed event and its pure fold; no new trust anchor is introduced.

### Pad events are signed

A `pad` event is signed and checked like a `message` or `file` event.
`CircleCore.canonical` covers the same seven fields — `id`, `circleId`,
`authorFp`, `lamport`, `type`, `body`, `createdMs` — as a domain-tagged,
length-prefixed byte sequence, excluding only `sig` and `cert`. The two closed
body shapes are `{"op":"put","key":"…","text":"…"}` and `{"op":"del","key":"…"}`,
gated by `PadCore.PAD_PUT_KEYS` and `PAD_DEL_KEYS`. `PadCore` builds them with
`padPutBody` and `padDelBody`, so a body cannot be minted in a shape the gate
would refuse. Signing uses the same `TlsIdentity.sign` path as every other Flit
signed type; `CircleCore.verify` binds the embedded certificate's own SHA-256 DER
to `authorFp` before verifying and pinning, the pin-after-verify rule in
"Signatures and cert-in-event". A forged `authorFp` or an altered body fails
verification. The gate is structural only: it signs nothing, pins nothing and
reads no store.

### Verification and replay stay at the receiving route

`PadCore.view` does not call `CircleCore.verify`. Verification and the replay
guard run at the receiving route before events reach the fold:
`FlitCircleSync.fresh` verifies each signature through `CircleCore.verify`, and
`CircleCore.accept` applies the bounded seen set and window. Re-verifying inside
a pure fold would pin certificates from a test vector or a cached snapshot, so
the fold stays free of that side effect. `view` does apply `CircleCore.dedup`
because dedup is pure: a repeated id counts once even when a snapshot is
replayed. Replay uses the shared `6 h` window, `5 min` skew and `MAX_SEEN = 4096`
from `CircleCore`, unchanged for the pad verb.

### PadCore pins nothing

`PadCore` is a JVM-pure object with no mutable state between calls and no pin
store. It never reads or writes `TlsIdentity` pins; only `CircleCore.verify` and
the routes pin certificates. Each `view` rebuilds from the events passed in, and
the signed events in `FlitCircleStore.CircleState.events` remain the only durable
pad state — there is no second persisted copy and no per-line state. An app layer
may hold a derived `PadView` in a StateFlow rebuilt on each store fold, but this
protocol layer adds none.

### Stored bodies are plaintext with no content cipher

Pad text travels as signed JSON in the shared Circle log. The group key stays
membership/rekey material, not a content cipher: there is no per-line encryption
path, no wrap and no new key material. TLS keeps the body off the wire in
cleartext, but the stored body is plaintext JSON under the at-rest rule in
"Storage and permissions" — anyone with device or backup access can read it. Do
not put secrets in a pad line, the same rule as Circle messages. A departure, a
rekey or a `del` event cannot erase a line already stored on another device. The
courier and QR parcel paths may carry the event as opaque sealed payload, but
that is their own envelope, not a second cipher for the pad line.

Evidence: CODE for the verb, gate and pure fold, and PURE for the protocol tests
(`PadBodyTest`, `PadViewTest`, `PadMergeTest`, `PadCanonicalTest`). No harness
test and no device observation are added for this layer; pad events over the
production routes belong to the later app and CLI integration.

## Circle pad in app and CLI: signed edits, route verification, no keys in the UI (2026-12-16)

The 2026-12-16 integration puts the pad verb on both surfaces. The app exposes
`FlitEngine.padView: StateFlow<PadView>`, rebuilt from `FlitCircleStore` events
through `PadCore.view(events, circleId)` on every store fold, and publishes edits
with `FlitEngine.circlePadPut(key, text)` and `FlitEngine.circlePadDel(key)`.
The CLI adds `circle pad put --key K --text T`, `circle pad del --key K` and
`circle pad show`. Every body comes from `PadCore.padPutBody` / `padDelBody` and
is published as an ordinary signed `pad` event on the existing `/circle/sync`
fan-out. No route, header, DTO field, QR parameter, store field, signature
primitive or v2.2 status is added. The properties below are those of that one
signed event; the integration introduces no new trust anchor.

### Edits are signed pad events

A put or del leaves the app through `FlitCircleSync.postPadPut` /
`postPadDel` and leaves the CLI through its `circle pad` command path; both use
the `PadCore` builders, so a body cannot be minted in a shape the gate refuses.
The event is minted with the writer's own `TlsIdentity` through
`CircleCore.create` / `sign`, reusing the same `TlsIdentity.sign` path as every
other signed Circle verb. `CircleCore.canonical` covers the seven fields `id`,
`circleId`, `authorFp`, `lamport`, `type`, `body` and `createdMs` as one
domain-tagged, length-prefixed sequence, excluding only `sig` and `cert`; a
changed key or text, or a forged `authorFp`, fails verification. The UI's
`PadUi.keyError` / `textError` bounds mirror `PadCore.MAX_KEY_CHARS` and
`MAX_TEXT_CHARS`, and the builders refuse a blank or oversized value before an
event is created.

### Verification stays at the receiving route

Verification and the replay guard are owned by the receiving route, not by the
fold or the UI. `FlitCircleSync.fresh` runs `CircleCore.verify`, which binds the
embedded certificate's own SHA-256 DER to `authorFp` under the pin-after-verify
rule, and `CircleCore.accept` applies the shared `6 h` window, `5 min` skew and
`MAX_SEEN = 4096` before events reach the fold. `PadCore.view` still calls only
`CircleCore.dedup`: it never verifies, pins or reads a store, so a derived view
is not an authentication decision. `refreshCircle()` folds a pad digest into
its content-diff signature so an edit republishes without a log change; that
digest is a local change signal, not a signature over pad content and not a
replacement for route verification. Pins and the seen set remain in the
protocol and route layer.

### The UI holds no keys

`PadUi.kt` is Android-free: `object PadUi` with `data class PadRow(key, text)`,
`rows(view: PadView)` returning lexicographically ordered rows, and `keyError`
/ `textError` bound checks from `PadCore`. The Compose `PadCard(view, onPut,
onDel, onClose)` renders an M3 list with edit and delete controls at 48 dp
targets; `CircleUi` adds one pad surface entry, and `MainActivity` collects
`FlitEngine.padView` and supplies the callbacks. None of these UI objects holds
a `TlsIdentity`, a private key, the group key, a wrap or a certificate, and none
signs, verifies, pins or writes a store. The card renders a derived view only;
`onPut` and `onDel` call back into `FlitEngine`, which is the sole app caller
that mints a pad event. `circle pad show` likewise prints `PadView` lines and
holds no key. There is no per-line cipher: pad bodies stay plaintext JSON in the
bounded Circle log under the at-rest rule in "Storage and permissions", and no
UI layer adds an encryption path.

### No device claim

Evidence: CODE for the app and CLI seams named above and for the unchanged wire
surface; PURE for `PadUiTest` and `FlitEngineTest`, and SIM-HARN for `PadCliTest`.
No device observation is claimed for this layer: no phone-to-phone pad walk, no
hardware observation of a pad edit and no radio or carrier pad exchange is
recorded. Pad observation on hardware belongs to the device-walk date.

## Storage and permissions

The app asks for the minimum local capabilities and exposes almost nothing:

- Manifest permissions: `INTERNET`, `ACCESS_NETWORK_STATE`,
  `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`,
  `POST_NOTIFICATIONS`, the three Bluetooth radio permissions (asked
  contextually at the no-AP moment), `CAMERA` for QR scan only, media-read
  permissions for the opt-in album mirror, and `RECEIVE_BOOT_COMPLETED` for the
  opt-in boot start (`app/src/main/AndroidManifest.xml:1-41`). There is no
  `READ_SMS`, contacts, location or account permission.
- `android:allowBackup="false"` (`:45`): no cloud backup of the private state.
  The `FileProvider` is `exported="false"` with `grantUriPermissions="true"`
  (`:85-94`); the foreground service is not exported (`:108-111`); the Quick
  Settings tile requires `BIND_QUICK_SETTINGS_TILE` (`:113-123`). The activity and
  boot receiver are exported because they are OS entry points; the `flit://`
  VIEW filter is the invite link path (`:51-83`, `:96-106`).
- `res/xml/file_paths.xml` names exactly four roots: `Download/Flit/`,
  `received/`, app `files/`, and `cache/` (which covers the Circle spool). The
  `Download/Flit/` root was added for legacy ledger rows; there is no exported
  provider and no new path beyond what the ledger and spool use
  (`docs/open-file-and-sharing-restore.md:94-102`).
- Durable local state lives in `SharedPreferences` (`flit`): known devices,
  QR-only flag, pinned roster, routes, `circleInvite` and `sharingDesired`. The
  codecs are tolerant JSON with safe defaults; corrupt or forward-newer rows
  decode to "not desired"/empty and never throw (`FlitKnown.kt:65-73`,
  `FlitRoster.kt:165-180`, `docs/open-file-and-sharing-restore.md:137-141`).
- **At-rest reality:** Circle event bodies are plaintext JSON in the log, and
  received files are ordinary files in `Downloads/Flit/` or a chosen SAF tree.
  TLS protects them in transit and signatures authenticate authorship, but
  anyone with access to the device state can read them. Do not put secrets in
  Circle messages (`docs/circle.md:135-154`, `:359-363`).

## Security closeout wave (2026-11-28)

The patches across `:protocol`, `:core`, `:app` and `:harness` closed the
enumerated gaps below. Every row is CODE plus JVM PURE or in-process SIM/HARN;
none is a device observation and none is a security proof. Older counts quoted
elsewhere in this document stay historical (`docs/TESTING.md` §1).

**Prepare certificate and body-signature binding.** `Protocol.HEADER_PREPARE_CERT`
and `HEADER_PREPARE_SIG` carry the sender's Base64 DER and a signature over the
exact UTF-8 bytes of the prepare body (`Protocol.kt:60-74`,
`ProtocolClient.kt:650-663`); every prepare, including a re-prepare, sends both.
`ServerCore.verifyPrepareBinding` caps the headers at `8 KiB` / `2 KiB`, parses
the DER, verifies the signature with `TlsIdentity.verifyWithCertificate`, derives
the digest with `fingerprintOf` and requires it to equal the declared
`info.fingerprint` (`ServerCore.kt:463-493`, `:1311-1312`). When
`ServerConfig.requirePrepareCert()` is true, a missing, malformed, oversized,
unsigned or mismatched binding is refused before `onPrepare`
(`ServerCore.kt:124`, `:369-384`); a defaulted three-argument delegate keeps old
callers compiling (`:52-71`). The app's `FlitEngine.evaluatePrepareOffer`
delegates to the pure `TrustGate`, which compares declared and presented
fingerprints and consults caller-supplied persisted pin/known lists in a fixed
order (`core/.../TrustGate.kt:81-103`, `FlitEngine.kt:1642-1657`, `:1832-1859`).
`TrustGate.Config` defaults both gates off, so an unmarked v2.2 sender passes
and a v2.2 receiver ignores the headers; refusals log only a 12-hex code
(`TrustGate.kt:43-47`, `:125-143`). Evidence: CODE above; PURE `TrustGateTest`
(15), `ProtocolRefusalTest` (22), `PeerIdentityPinTest` (12); SIM/HARN
`RelayServerTest` over production endpoints. Open: the header proves possession
of a key pair, not out-of-band provisioning, and with both gates off the
declared fingerprint remains unverified by design.

**Sender TLS peer pin.** `SendTarget.expectServerFp` (`ProtocolClient.kt:31-50`)
is threaded through every client exchange (`:99`, `:210`, `:426`, `:564`,
`:642`, `:674`). `TcpLinkProvider` throws on a pin over plain HTTP before a
socket opens (`Link.kt:98`), `TlsLink` checks the peer leaf after the handshake
(`Link.kt:150-171`), and `PeerHttpClient` repeats the check on the link's
`peerCertificate` so a carrier that ignores the hint cannot smuggle a rogue
certificate (`PeerHttpClient.kt:80-92`). `TlsIdentity.checkPeerPin` fails closed
on absent certificate or mismatch (`TlsIdentity.kt:309-322`). On the app,
`expectedPinFor` selects the pin only from persisted Known devices and pinned
roster rows, never from the peer's wire claim (`FlitEngine.kt:1674-1694`).
Evidence: CODE above; PURE `PeerIdentityPinTest`, `FlitEngineTest` (49); SIM/HARN
piped-TLS match and mismatch. Open: a null pin keeps legacy unauthenticated TLS;
a peer holding the pinned private key is indistinguishable from the real device.

**Panic origin signature and hop-0 rule.** `PanicAlert` carries additive
`originSig` and `originCert` fields (`model/PanicAlert.kt:17-37`). `create` signs
`sig` and stores the same signature as `originSig` with the origin DER at hop
zero; `forward` re-signs only `sig` and carries the origin pair unchanged
(`PanicCore.kt:104-137`, `:246-273`). `verifyOrigin` rebuilds the hop-zero
canonical `(id, originFp, sentMs, text, hops = 0, originFp)` and requires
`originSig` to verify under a certificate whose own SHA-256 DER equals
`originFp`; a legacy blank origin falls back to the verified sender
(`:165-200`). The route calls it before delivery, pins only after the verdict
and delegate, and records the id only after a successful delivery
(`ServerCore.kt:1049-1124`). Evidence: CODE and PURE `PanicTest`,
`ProtocolRefusalTest`. Open: two-phone mesh hops are still not verified; the
one-phone-plus-box banner exchange remains the only device observation.

**CircleAuthority role, kick and rekey authority.** The shared pure gate now
classifies verbs: `ROLE` needs `OWNER`, `REKEY` needs an officer, an admin-mode
`KICK` needs an officer, a mode-less or vote kick needs membership, and `JOIN`
never folds (`CircleAuthority.kt:34-89`). Every fold site uses the defaulted
`authorityOf` argument; no roles means no privileged verb folds, including from
an opened courier parcel. Evidence: CODE and PURE `CircleAuthorityTest`,
`FlitCircleSyncTest`, `FlitCircleStoreTest`, `FlitCircleHostTest`; SIM/HARN
`CircleHarnessTest`. Roles stay local fingerprint-pinned labels, not accounts.

**JOIN proof digest single-use and retention.** `joinProofFailure` reserves the
domain-tagged proof digest, not the event id, so re-signing a captured proof
under a fresh id is still `join proof replay`; the certificate is pinned only
after every gate and a successful reservation (`CircleCore.kt:649-685`).
Reservations are retained for `REPLAY_WINDOW_MS + CLOCK_SKEW_MS` and fail closed
when the set is full of live entries (`:32-37`, `:123-147`). Evidence: PURE
`ReplayBoundaryTest` (5), `JoinProofTest`. Open: process memory only, with the
five-minute bucket as stateless backstop; no durable replay store landed.

**Replay saturation.** `CircleCore.Decision.Full` replaces eviction of live ids:
`reserveLocked` purges only expired entries, refuses a newcomer at `MAX_SEEN =
4096` and `fresh` maps that to false (`CircleCore.kt:87-147`, `:730-743`).
Evidence: CODE and PURE `ReplayBoundaryTest`. Open: bounded memory is proven;
absence of replay after the retention window is not, and state is process-local.

**Relay policy.** `RelayCore` adds per-IP wrong-token backoff after three
failures, a global 1024-ticket park cap, a 300 s park TTL and a strong-token
policy (`RelayCore.kt:104-155`, `:182-225`, `:280-298`); `RelayServer.start`
refuses a non-loopback bind with a missing or weak token before accepting
(`RelayServer.kt:24-42`, `:78-95`). The app's `relayHuntAllowed` and
`relayDispatchAllowed` require an explicit per-device `relayFor` plus AUTO or
RELAY_ONLY policy; null fails closed and a stale route mark is not consent
(`FlitEngine.kt:2618-2647`). Evidence: CODE and PURE `RelayCoreTest` (20),
`FlitEngineTest`; SIM/HARN `RelayServerTest`. Open: the relay is still blind,
single-point and opt-in, with one shared token rather than a per-peer ACL.

**Announce gate.** The pure `AnnounceAdmission` refuses malformed, non-64-hex,
`manual@` and self announces, out-of-range ports, aliases over 64 chars,
off-link source IPs (only RFC1918, 169.254, fe80::/10 and fc00::/7 pass), a
repeat inside 1 s, and a new peer when the 256-entry caller map is full; a
throwing map fails closed (`core/.../AnnounceAdmission.kt:56-223`).
`FlitEngine.decideAnnounce` / `admitAnnounceForIngest` run it before any
registry, route or pin write (`FlitEngine.kt:1724-1800`). The wire listener keeps
a separate stateful `UdpDiscovery.AnnounceAdmission` with injected clock and
caps (256 fingerprints, 64 per source, 60 s TTL, 8 concurrent registers)
(`UdpDiscovery.kt:220-340`). Evidence: CODE and PURE `AnnounceAdmissionTest`
(27), `UdpDiscoveryTest` (15). No camera or radio walk is claimed.

**Keyed landing name.** `DropKeyCore.safeLandingName` caps a keyed file name at
256 chars and refuses separators, drive/stream markers, ISO controls, `.`/`..`
segments, trailing dot or space and reserved Windows device names
(`DropKeyCore.kt:47-101`); the body builders, parser and gate apply it
(`:213`, `:230-240`). At landing, `landingFileFor` / `writeLandingFile`
re-check it, require a single child of the directory, and write with
`CREATE_NEW` + `NOFOLLOW_LINKS`, refusing symlinked parent or target
(`FlitEngine.kt:1137-1168`, `:1221-1244`). Evidence: CODE and PURE
`FlitEngineTest`, `DropKeyBodyTest`, `DropKeyCryptoTest`. No device walk.

**Intake caps.** The gzip unpack ceiling is `MAX_UNPACK_BYTES = 512 MiB` with
the count taken before each write and partial output deleted on failure
(`core/.../FlitCrush.kt:28-33`, `:86-123`); `plainNameOrNull` refuses traversal,
absolute and separator names (`:47-63`). Prepare headers, trust-gate input
(`TrustGate.kt:34-41`), panic fields (`PanicCore.kt:32-45`) and Circle bodies
(`CircleCore.kt:46-85`) carry their own bounds; announce caps are above.
Evidence: CODE and PURE `FlitCrushTest` (5), `ProtocolRefusalTest`,
`FlitEngineTest`. No device walk.

**Dependency upgrades.** `gradle/libs.versions.toml:1-16` raises Bouncy Castle
from 1.81.1 to 1.87 for both `bcprov-jdk18on` and `bcpkix-jdk18on` (`:6-8`,
`:33-34`), pins Netty at 4.2.21.Final with a root `resolutionStrategy` forcing
every `io.netty` module to >= 4.2.18.Final (`build.gradle.kts:11-24`), records
slf4j 2.0.17 and aligns `kotlin-reflect` with the Kotlin toolchain (`:19-21`).
This is a pin-set change, not an advisory report: no published scan or waiver
set is recorded in `docs/`, and a clean resolution does not prove the absence
of unknown advisories.

## Limitations

1. **No content encryption at rest.** The group key is membership/rekey
   material, not a cipher. A member in the roster can read everything; a
   departed device keeps earlier plaintext events already stored.
2. **Bearer invite and process-local JOIN proof replay.** A photographed
   pairing QR or Circle invite can be used by whoever holds it. Pairing pins
   only after a matching live answer for IP codes; relay/MAC-only codes pin
   directly from the code. A Circle JOIN carries one signed JOIN event with a
   `FLITJOIN1` proof bound to host, circle, invite, member and a five-minute
   bucket, so the invite alone cannot claim another member's fingerprint or
   route. A proof captured on the wire is a bearer token until its bucket rolls
   over: the host reserves its id in a bounded in-process set, so the verbatim
   event is refused a second time in that process, but a restart forgets the
   reservation and another process has no memory of it.
3. **TLS trust is fingerprint-based, not CA-based.** Trust-all managers and no
   hostname verification are deliberate. The security of a session rests on the
   out-of-band fingerprint check, not on a certificate authority or a DNS name.
4. **Pin cache is bounded and in-memory.** More than 512 distinct pins evict
   least-recently-used entries; a bare verify with no certificate in hand fails
   after eviction or restart. All production ingresses re-pin from a cert in the
   event or header.
5. **Replay state is in-memory.** Six-hour windows (24 h for courier parcels)
   and bounded FIFO sets survive neither restarts nor a flood past `MAX_SEEN`;
   full-log repair covers sync, not indefinite replay memory.
6. **Relay is blind, optional and single-point.** No discovery, no
   store-and-forward, no per-peer ACL; the token is one shared secret; both
   peers must be live. Opt-in controls Flit's use of it, not who else may use it.
7. **Panic is informational.** Any QR-known device can raise a banner; there is
   no remote action, no wipe and no automatic forwarding. Two-phone mesh hops
   are not verified.
8. **Trust gates are off by default.** QR-only and Trusted-senders-only are
   opt-in; the open roster is the default. The CLI `serve` auto-accepts incoming
   offers and has no trusted-senders gate (`README.md:152-154`).
9. **OS pairing is not Flit trust.** Accepting a Bluetooth system consent sheet
   does not pin a peer; a real two-phone RFCOMM transfer has no hardware on this
   rig, and Wi-Fi Aware is absent (`docs/bluetooth-carrier.md:266-278`,
   `docs/transport-bluetooth.md:96-100`).
10. **Storage is plaintext and externally readable with a grant.** A shared
    file's contents are whatever the picked viewer makes of them; MIUI and
    Android 15 `dataSync` rations can kill background sharing, with restoration
    on the next open rather than an OS restart
    (`docs/open-file-and-sharing-restore.md:198-217`).
11. **File delivery is at-post-time.** No backfill for later joiners; delivery
    status is never gossiped, so no peer can see another's counts.
12. **Courier handoff is best-effort and bounded.** A carrier can hold, delay,
    drop or partition parcels; the 24 h TTL expiry ends a held copy but not one
    an addressee already decrypted, and a later rekey or departure cannot revoke
    it. The carrier is blind to event content but sees the addressee fingerprint,
    timing and size. No carrier-to-absent-member walk is device-verified. A
    `handed`/accepted outcome means only that a peer accepted an offered parcel;
    it is not end-to-end delivery and gives no remote proof that bytes landed.
    Fold-time gates require a verified outer `parcel` from a current roster
    member and verified inner events that are never `JOIN` or `REKEY`. The app
    handoff projection is process-local: never signed, persisted or gossiped,
    cleared on restart, and no envelope text is rendered on the card.
13. **Air-gap carry is manual and best-effort.** Every frame must be carried and
    fully captured; a missing or altered frame leaves `complete` with nothing to
    release. A carrier sees frame metadata and ciphertext and can delay or drop
    the carry. The codec is pure and local — it opens no file, sends no message
    and takes no remote action. Real-camera capture and an end-to-end air-gap
    walk are not device-verified.
14. **Per-drop keys do not revoke past drops.** A fresh key seals each drop and a
    wrap reaches only current members with a usable certificate; a later joiner
    gets no backfill and a departure or rekey does not change a published body. A
    device that kept a wrap can reopen an old drop. There is no escrow, no
    group-key fallback and no remote action that deletes or invalidates a copy
    already held elsewhere.
15. **A receipt proves local landing only.** It is a signed acknowledgement by
    one member that a named drop landed on that member's device, not global
    delivery proof, and it says nothing about other members. The body is exactly
    `{dropId, sha256, keyId}` — no key material, file bytes, path or local status
    — and `keyId` is provenance, not a key. Minting or publishing one takes no
    remote action, and the confirmed tally is gated on the current-member roster:
    a departed or unknown author is not counted. The 2026-12-14 app and CLI
    integration adds no device receipt walk claim.
16. **A pad line is plaintext and PadCore pins nothing.** Pad events are signed
    and verified at the receiving route (`FlitCircleSync.fresh` /
    `CircleCore.accept`), but the stored body is plaintext JSON in the shared log
    with no per-line cipher — the group key is membership material, not
    encryption. `PadCore` is a pure fold with no pin store and never calls
    `CircleCore.verify`; it applies `dedup` only. Do not put secrets in a pad
    line, and a `del` or rekey cannot erase another device's stored copy.
17. **The pad UI is a view, not a trust decision.** The 2026-12-16 card and CLI
    display render a derived `PadView`; they hold no `TlsIdentity`, private key,
    group key, wrap or certificate, mint no event and verify nothing. Edits are
    signed by `FlitEngine` / the CLI and verified at the receiving route, so a
    rendered line is not by itself proof of authorship. No device walk or
    hardware pad observation is claimed for this layer.
18. **The 2026-11-28 security closeout is evidence-bounded.** Prepare binding,
    sender TLS pinning, panic origin signature, fold-time role/kick/rekey
    authority, digest-keyed JOIN single-use, replay saturation, relay policy,
    announce admission, keyed landing names, intake caps and dependency pins
    are CODE plus PURE/SIM-HARN observations (section above). A **durable pin
    source is not implemented**: persisted rows hold fingerprints, not DER, and
    the two-tier `PinCache` is memory-only. The **restart replay gap** remains:
    `CircleCore` / `PanicCore` seen sets and the proof reservation are
    process-local; the window and bucket grid are stateless backstops only.
    Other **process-local state** (courier handoff map, announce registry,
    verification marker ring) dies with the process. All gates remain **opt-in
    and off by default**. No device walk and no security proof are claimed: the
    tests assert enumerated inputs, and a pin-set change is not an advisory
    report.

## Evidence status at a glance

| Area | Code | Pure test | Harness / sim | On device |
| --- | --- | --- | --- | --- |
| Fingerprint pinning, pin cache | `TlsIdentity.kt` | `PinCacheTest` (6) | TLS parity suite | QR v2 paste logged `code verified` |
| Cert-in-event / pin-after-verify | `CircleCore.kt`, `CircleWire.kt`, `ServerCore.kt` | `CircleHardeningTest`, `CircleCoreTest`, `FlitCircleHostTest` | circle harness two-node walk | Circle join + inbound event rendered in feed |
| JOIN proof / Circle authority | `CircleCore.joinProofFailure`, `CircleAuthority.kt`, `ServerCore.circleRoute`, `FlitCircleSync.fresh` | `JoinProofTest`, `CircleAuthorityTest`, `ReplayBoundaryTest`, `FlitCircleSyncTest`, `FlitCircleStoreTest` | proof-less and wrong-host refusals, rightful signed join (in-process TLS) | no device walk |
| Prepare cert + body-signature binding | `Protocol.kt`, `ProtocolClient.prepareHeaders`, `ServerCore.verifyPrepareBinding`, `TrustGate.kt` | `TrustGateTest` (15), `ProtocolRefusalTest` (22), `PeerIdentityPinTest` (12) | prepare cert gate over production endpoints in `RelayServerTest` | no device walk |
| Sender TLS peer pin / pin tiers | `SendTarget.expectServerFp`, `TlsLink`, `PeerHttpClient`, `TlsIdentity.checkPeerPin` / `PinCache` | `PeerIdentityPinTest`, `FlitEngineTest` | piped TLS match and mismatch | no device walk |
| Panic origin signature (hop-0) | `PanicCore.verifyOrigin`, `PanicAlert.originSig/originCert`, `ServerCore.panicRoute` | `PanicTest`, `ProtocolRefusalTest` | — | one phone + box banner only; two-phone hops not verified |
| Replay saturation / retain window | `CircleCore.Decision.Full`, `reserveLocked`, `purgeExpiredLocked` | `ReplayBoundaryTest` (5) | — | no device walk |
| Relay policy | `RelayCore` token backoff/caps, `RelayServer.bindTokenProblem`, `FlitEngine.relayDispatchAllowed` | `RelayCoreTest` (20), `FlitEngineTest` | `RelayServerTest` | no device walk |
| Announce gate | `core/AnnounceAdmission.kt`, `UdpDiscovery.AnnounceAdmission`, `FlitEngine.admitAnnounceForIngest` | `AnnounceAdmissionTest` (27), `UdpDiscoveryTest` (15) | — | no camera or radio walk |
| Keyed landing name / intake caps | `DropKeyCore.safeLandingName`, `FlitEngine.writeLandingFile`, `FlitCrush` | `FlitEngineTest`, `DropKeyBodyTest`, `FlitCrushTest` (5) | — | no device walk |
| Replay / hop guards | `PanicCore.kt`, `CircleCore.kt` | `PanicTest`, `CircleHardeningTest` | panic via `LinkServer`; Circle full-log empty-delta pull | panic banner box↔phone; full-log repair line in log |
| QR authority / invite | `Qr.kt`, `FlitEngine.kt`, `FlitCircleSync.kt` | `FlitQrTest` | invite text/HTML pushed to Downloads | QR v3 join, full leave discards key |
| Relay blindness / opt-in | `RelayCore.kt`, `RelayEndpoints.kt`, `FlitEngine.kt` | `RelayCoreTest` (11) | `RelayServerTest` over production endpoints | 200 MiB cross + 300 MiB kill/resume |
| File no-path / local status | `CircleCore.validFileBody`, `FlitEngine` delivery loop | `CircleCoreTest`, `CircleHardeningTest`, `FlitSpoolTest` | `CircleHarnessTest` | 35 B drop landed hash-exact; Open-file walk |
| Per-drop content keys | `DropKeyCore.kt`, `CircleCore.validFileBody`, `TlsIdentity.decryptOaepSha256` | `DropKeyCoreTest`, `DropKeyBodyTest`, `DropKeyCryptoTest` (counts at integration) | not added on this date | no device walk |
| Keyed drops app + CLI receive gate | `FlitEngine.onUploadFinish`, `FlitCircleSync.postFile(body)` / `circleBodyValid`, `Harness.circleFileAnnouncement`, `ConsoleDelegate.onUploadFinish` | core body/crypto tests, `FlitCircleSyncTest`, `FlitEngineTest`, `DropParityTest` | CLI keyed-drop round-trip and refusals (`DropKeyCliTest`, `CircleHarnessTest`) | no device walk |
| Delivery receipts (local landing) | `CircleEvent.RECEIPT`, `CircleCore.RECEIPT_BODY_KEYS` / `validReceiptBody` / `receiptAuthors` | `ReceiptBodyTest`, `ReceiptTallyTest`, `ReceiptCanonicalTest` (counts at integration) | not added on this date | no device walk; one member's landing is not global delivery |
| Circle pad (signed verb, pure fold) | `CircleEvent.PAD`, `PadCore.view` / `merge` / `validPadBody` | `PadBodyTest`, `PadViewTest`, `PadMergeTest`, `PadCanonicalTest` (counts at integration) | not added on this date | no device walk |
| Circle pad app + CLI integration | `FlitEngine.padView` / `circlePadPut` / `circlePadDel`, `FlitCircleSync.postPadPut` / `postPadDel`, `PadUi.kt`, `PadCard`, CLI `circle pad` commands | `PadUiTest`, `FlitEngineTest` | `PadCliTest` | no device walk |
| Storage / permissions | `AndroidManifest.xml`, `file_paths.xml`, prefs codecs | `FlitRosterTest`, `SharingRestoreTest`, `FlitKnownTest` | not applicable | force-stop → silent sharing restore |
| Courier envelope / carry | `CourierEnvelope.kt`, `CourierCarryCore.kt` | courier pure tests (counts recorded at integration) | SIM via CLI carrier below | no device walk |
| App courier handoff UI | `CourierCardUi.kt`, `FlitEngine.courierHandoffs`, `FlitCircleSync.postParcel` | `CourierCardUiTest` (counts recorded at integration) | consumed CLI carrier outcomes | no device walk; peer acceptance is not end-to-end delivery |
| Air-gap QR parcel frames | `ParcelCodec.kt` (parcel package) | parcel pure tests (counts recorded at integration) | SIM via CLI row below | no device walk |
| Air-gap QR parcel CLI (encode/decode) | `ParcelCli.kt` (harness), `--cert` gate | `ParcelCliTest` (counts recorded at integration) | SIM-HARN round-trip + cert/recipient refusals | no device walk |

The 2026-11-26 three-node baseline walk is DEVICE evidence for a 3-identity
Circle: the phone plus two CLI identities joined one Circle, and event sync, a
poll, a hash-exact file drop and a kick/rekey were observed
(`docs/completions/2026-11-26-three-node-baseline.md`). The phone was host in
that walk, so outbound phone→host fan-out was not exercised.

Device walks not verified: a Circle mesh beyond those three identities; a real
phone-to-phone RFCOMM transfer; phone→host Circle fan-out with a clean peer-side
confirmation (still test-evidenced only — `docs/circle.md:336-343`, `:369-387`);
Wi-Fi Aware on this rig; any multi-member panic-hop walk beyond the
one-phone-plus-box case; a courier parcel offered through a carrier to an absent
member. For that last case an OK answer is acceptance of the offer, not a
device-verified delivery.

## Inconsistencies

None changes a runtime behavior; all are stale comments or wording that disagree
with the focused docs.

1. **`CircleCore.validFileBody` KDoc is stale.** `protocol/.../CircleCore.kt:312`
   says recipients locate bytes "by `sha256` through the bulk queue". The
   correction in `docs/circle.md:189-235` says bytes are **not**
   on the send-later/bulk queue; they go by a direct per-member `sendFiles` call
   from the sender's spool. `CircleEvent.kt:27-32` similarly calls `sendFiles`
   "the bulk transfer path". The enforcement code (`validFileBody`) is correct;
   only the prose is behind.
2. **Hop-limit wording is imprecise.** `docs/qr-only-and-panic.md:25-26` says
   `forward` returns null "once `hops >= DEFAULT_MAX_HOPS` (3)". The code
   (`PanicCore.kt:174-175`) checks `nextHops = hops + 1 >= maxHops`, i.e. it
   stops when the *current* count would be 2, and the receiver's `evaluate`
   drops alerts at `hops >= 3`. The effective result (at most two re-signed
   forwards) matches the doc's "three relays" story, but the literal condition
   differs.
3. **QR-only flag is written to two preference keys.** `FlitEngine.setQrOnly`
   (`:2264-2268`) writes both the plain `"qrOnly"` key and
   `FlitKnown.setQrOnly`'s `"known.qrOnly"`; reads use only `known.qrOnly`. The
   extra write is redundant legacy state, not a second gate.
4. **No group key on the QR is enforced by absence of a field, not by rejection
   of a `key=` parameter.** `parseAddCode` silently ignores unknown query keys
   (`Qr.kt:101-109`); `FlitQrTest:168-178` proves emitted codes contain no key,
   but a crafted code with `key=…` is parsed with that key ignored rather than
   refused. The security property holds (no path from QR to key store), but the
   refusal is by omission, not an explicit error.
