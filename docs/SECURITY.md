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

Rig status: 136 protocol + 228 app + 32 harness = 396 tests, 0 failures (last
green `--rerun-tasks` run). Properties asserted only by a code comment or an
unrun path are marked "not verified".

## Assets and trust boundaries

Assets worth protecting: the device's TLS private key (`TlsIdentity`), the
fingerprint that names it, received file bytes, the sender's local spool, Circle
event bodies and group key, the invite token, the relay token, and the QR codes
that carry all of the above indirectly.

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
| Forged fingerprint | an attacker certificate lands under the attacker's own SHA-256 DER fingerprint; a **signed** event naming a victim's `senderFp`/`authorFp` fails without that victim's private key, so signed events cannot be forged | a Circle `JOIN` is not signed: it is gated by invite + `memberFp`, and the member certificate DER is public in Circle events. An invite holder can replay a member's certificate in `X-Flit-Circle-Cert`, name that member's `memberFp`, and take its roster slot and route; no private-key proof is demanded |
| Stolen QR photo (pairing) | pinning for an IP-bearing code happens only after a live `register` answers with the exact fingerprint in the code | the photo is a bearer hint: whoever shows it to a device that answers correctly gets trusted. A relay-only/MAC-only code has no probe and pins directly from the code — the human eyeball is the only check |
| Stolen Circle invite (v3 QR) | the code carries no group key; the key arrives only inside a TLS session to the host named in the QR, and the answer is signed by that host | the invite token is a bearer credential: whoever holds the photo can complete a JOIN while it is valid, including under another member's `memberFp` and route. Retiring a suspicious code means starting a new Circle |
| Compromised circle member | cannot forge another member's **signed events** (signature + cert pin), cannot inject `JOIN` or `REKEY` through `SYNC`, cannot inject a `parcel` into another device's bag unless it is a current roster member, cannot read a key the host never sends in a sync answer | can read the whole plaintext log and can emit signed events as itself; with the invite it can JOIN as another member and redirect that member's route to itself; earlier events already stored on a departed device cannot be erased |
| Malicious panic sender | must own the private key for the claimed `senderFp`; hops and author are signed; recipient gates on the original `originFp` being QR-known; dedup stops replays | any QR-known device can send a loud banner; panic is informational and never wipes, locks or acts on the recipient |
| Local attacker with device access | no cloud copy to pull, no account to reset, `android:allowBackup="false"` | can read the app's plaintext state, preferences, spool and ledger; there is no content cipher and no screen lock of its own |
| Malicious external viewer | Flit hands one content URI with a scoped read grant, never the private key or the whole spool | whatever the user chooses to do with that file is outside Flit's control |

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
- **Invariant (with one exception):** TLS provides confidentiality and transport
  integrity; it does **not** by itself establish *which* device is on the other
  end. For a TLS session, identity rests on the announced fingerprint matching
  one obtained out of band (QR scan) or from a pinned roster row; for a signed
  event, the signature must verify under the pinned certificate, so a peer that
  cannot present the private key for a fingerprint cannot sign as it. The
  exception is Circle `JOIN`: the certificate DER is public in Circle events,
  and a header carrying it is not proof of private-key possession (see
  "Signatures and cert-in-event").
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
- **Failure mode:** the cache is memory-only and bounded. After a restart or
  after eviction, a bare `verify(fp, …)` with no certificate in hand returns
  false. Every production ingress therefore carries the certificate in the
  event or header and re-pins after verifying; the fallback-to-pinned path is
  for already-known relayed senders only (`CircleCore.kt:172-187`).

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
  `text`, `hops` (int), `originFp` (`protocol/.../panic/PanicCore.kt:48-83`).
  Every immutable field including `hops` is signed; `sig` is not.
- `CircleCore.canonical`: `FLITCIRCLE1`, then `id`, `circleId`, `authorFp`,
  `lamport`, `type`, `body`, `createdMs` (`protocol/.../circle/CircleCore.kt:73-111`).
  Every field except `sig` **and `cert`** is signed.
- `CircleResponse.canonical`: `FLITCIRCLE1` with the answer's `ok`, `key`,
  members, events and reason; `serverCert` and `sig` are excluded
  (`protocol/.../model/CircleWire.kt:60-90`).

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
(`ServerCore.kt:971-997`), but that check is **not** proof of private-key
possession: the certificate DER is public in every signed Circle event, so
anyone who can read a Circle log can replay a member's certificate in the
header and name that member's `memberFp`. `ServerCore` only pins the header
certificate and compares its own digest to the claimed `memberFp`; the host
admits a `JOIN` on invite + `memberFp`, with no nonce and no signature over the
JOIN (`FlitCircleHost.kt:119-144`, `FlitEngine.kt:1110-1125`). An invite holder
can therefore claim another member's fingerprint and route: the host adds that
`memberFp` to the roster and remembers the requester's IP as the route back to
it. This remains an open impersonation gap; a header requirement alone did not
close it. Signing a nonce-bound JOIN is a future protocol change, not a current
guarantee.

The host signs every Circle answer; a joiner verifies it against the host
fingerprint from the QR via `CircleResponse.verify(response, host)` before
persisting anything (`CircleWire.kt:92-108`, `app/.../FlitCircleSync.kt:390-403`).
A rogue relay or on-path peer cannot substitute the key.

Pure-test evidence: `PanicTest.kt:103-133` (any edit or forged `senderFp`
breaks verification and the route answers 403), `CircleHardeningTest.kt:180-219`
(embedded cert bound to `authorFp`, rejected signature never pins the stranger),
`CircleCoreTest.kt:145` and `FlitCircleHostTest.kt` (real signed identities,
not mocks). Harness evidence: 403 refusal branches for missing, malformed and
mismatched cert headers are exercised through `ServerCore` in
`CircleHardeningTest.kt:362-441` (pure route, production code). Those are
header-shape and digest-comparison refusals only; no test demonstrates a JOIN
that proves possession of the member's private key.

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
  `Duplicate` → `Accept` and reserves the id atomically; `accept` is an alias of
  `evaluate`, and `fresh` shares the same set
  (`CircleCore.kt:326-387`). The production order is
  `validShape → verify → evaluate → store`: reserve **after** crypto, so a
  forgery cannot burn the id of a legitimate event (`:331-337`).
- The Circle window compares two positive timestamps by subtraction, never
  `now ± window`, so an extreme clock cannot overflow into a false Accept
  (`CircleCore.kt:358-359`).
- Deterministic total order is
  `(lamport, authorFp, id, type, body, createdMs, sig, cert)`; `dedup` picks the
  winner by that order, not by arrival order, so two peers converge without a
  server (`CircleCore.kt:189-219`).
- Bounded store-and-forward: every `SYNC` posts the full bounded local log, so a
  lost one-shot is repaired by the next post or tick. The relay is never given
  storage (`docs/circle.md:145-158`).
- Courier deliberately uses a longer, bounded replay window: the **24 h parcel
  TTL** (`MAX_PARCEL_TTL_MS`), not `CircleCore.REPLAY_WINDOW_MS` (6 h). Both
  `CourierEnvelope.open` and `CourierCarryCore.evaluate` apply it to `createdMs`
  with the same five-minute future skew; see "Courier: recipient-cert encryption
  and carrier blindness".

**Failure modes and limits.** The seen sets are process memory, not durable
state; a restart forgets them, and the six-hour window is the backstop. A
legitimate event delayed more than six hours is stale replay and is refused. A
forwarded panic that has already been delivered once returns 200 on the wire but
is not delivered again to the delegate (`ServerCore.kt:941-944`) — the quiet
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
retired by starting a new Circle (`docs/circle.md:96-108`, `:335-337`).

The timing of persistence is load-bearing:

- The host admits a JOIN only when the presented invite equals its own invite
  (case-insensitive) and the requester's `memberFp` equals the digest of the
  certificate in `X-Flit-Circle-Cert` (`FlitCircleHost.kt:119-144`,
  `ServerCore.kt:971-997`). That is a string-and-public-certificate comparison,
  not proof of the private key: an invite holder can present another member's
  public certificate, claim that member's `memberFp`, and have the host
  remember the requester's IP as that fingerprint's route
  (`FlitEngine.kt:1118-1125`). Roster growth is capped at `MAX_MEMBERS = 500`
  (`FlitCircleHost.kt:48`, `:131-134`).
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
  persisted (`docs/circle.md:343-352`).

**Failure modes:** a wrong invite gets `ok=false, reason="bad invite"`; a
departed or never-joined fingerprint gets `"not-a-member"`; a wrong-circle id
gets `"wrong circle"`; all are well-formed 200 responses so a caller can tell
refusal from a dead carrier (`ServerCore.kt:947-1007`,
`FlitCircleHost.kt:59-66`).

## Circle wire authority, roles and rekey

Beyond the cert-header binding already described — which proves possession of a
public certificate, not of its private key — the host state machine is the
authority for Circle membership and key rotation:

- `SYNC` is roster-gated and rejects events not authored by the requester's own
  fingerprint. It filters out injected `JOIN`, out-of-circle events, oversized
  bodies, non-`FILE_BODY_KEYS` file bodies, and member-supplied `REKEY`; only
  OWNER/ADMIN may contribute a rekey. It then verifies signatures and applies
  both dedup layers **last**, so a forged event cannot reserve a legitimate id
  (`FlitCircleHost.kt:146-185`).
- Every membership change triggers a rekey: a fresh 32-byte `SecureRandom` key
  plus a signed `rekey` event, with the generation bumped by the store reducer
  (`FlitCircleHost.kt:206-242`). A later SYNC from the departed fingerprint is
  refused at the roster gate. Roles are local authority labels pinned to a
  fingerprint, not accounts: only OWNER appoints/removes ADMIN, an ADMIN (or
  OWNER) kicks a non-owner, and nobody appoints a second OWNER
  (`docs/circle.md:58-94`, `app/.../FlitCircleStore.kt:288-362`).
- The group key is **membership/rekey material, not a content cipher**. There is
  no encryption path for event bodies. Confidentiality on the wire is TLS;
  integrity and authorship are signatures plus the cert pin
  (`docs/circle.md:119-125`). Leaving a Circle deletes the local key and hides
  the feed; it cannot wipe or change another device, and earlier events on the
  departed device stay plaintext (`docs/circle.md:49-56`, `:127-129`).

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
was delivered (`docs/circle.md:162-166`):

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
  (`docs/circle.md:162-180`, `:206-209`).
- Delivery status is **sender-local only**. One record per member holds
  `pending`, `sent`, `refused` or `failed`; `sent`/`refused`/`failed` are
  terminal and `pending` is the only owed state. Status is not an event, is not
  gossiped, and a recipient's card cannot claim the bytes are missing
  (`docs/circle.md:181-204`). A later joiner folds the signed announcement but
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
  Circle messages (`docs/circle.md:110-129`, `:330-334`).

## Limitations

1. **No content encryption at rest.** The group key is membership/rekey
   material, not a cipher. A member in the roster can read everything; a
   departed device keeps earlier plaintext events already stored.
2. **Bearers are bearers; JOIN proves no key possession.** A photographed
   pairing QR or Circle invite can be used by whoever holds it. Pairing pins
   only after a matching live answer for IP codes; relay/MAC-only codes pin
   directly from the code. A Circle `JOIN` carries no nonce and no signature:
   `X-Flit-Circle-Cert` is a public certificate that anyone can copy from a
   Circle event, and the host gates on invite + `memberFp` only. An invite
   holder can therefore claim another member's fingerprint and route. A signed,
   nonce-bound JOIN that proves private-key possession is a future protocol
   change, not a delivered guarantee.
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

## Evidence status at a glance

| Area | Code | Pure test | Harness / sim | On device |
| --- | --- | --- | --- | --- |
| Fingerprint pinning, pin cache | `TlsIdentity.kt` | `PinCacheTest` (6) | TLS parity suite | QR v2 paste logged `code verified` |
| Cert-in-event / pin-after-verify | `CircleCore.kt`, `CircleWire.kt`, `ServerCore.kt` | `CircleHardeningTest`, `CircleCoreTest`, `FlitCircleHostTest` | circle harness two-node walk | Circle join + inbound event rendered in feed |
| Replay / hop guards | `PanicCore.kt`, `CircleCore.kt` | `PanicTest`, `CircleHardeningTest` | panic via `LinkServer`; Circle full-log empty-delta pull | panic banner box↔phone; full-log repair line in log |
| QR authority / invite | `Qr.kt`, `FlitEngine.kt`, `FlitCircleSync.kt` | `FlitQrTest` | invite text/HTML pushed to Downloads | QR v3 join, full leave discards key |
| Relay blindness / opt-in | `RelayCore.kt`, `RelayEndpoints.kt`, `FlitEngine.kt` | `RelayCoreTest` (11) | `RelayServerTest` over production endpoints | 200 MiB cross + 300 MiB kill/resume |
| File no-path / local status | `CircleCore.validFileBody`, `FlitEngine` delivery loop | `CircleCoreTest`, `CircleHardeningTest`, `FlitSpoolTest` | `CircleHarnessTest` | 35 B drop landed hash-exact; Open-file walk |
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
confirmation (still test-evidenced only — `docs/circle.md:310-319`, `:343-361`);
Wi-Fi Aware on this rig; any multi-member panic-hop walk beyond the
one-phone-plus-box case; a courier parcel offered through a carrier to an absent
member. For that last case an OK answer is acceptance of the offer, not a
device-verified delivery.

## Inconsistencies

None changes a runtime behavior; all are stale comments or wording that disagree
with the focused docs.

1. **`CircleCore.validFileBody` KDoc is stale.** `protocol/.../CircleCore.kt:312`
   says recipients locate bytes "by `sha256` through the bulk queue". The
   correction in `docs/circle.md:162-209` says bytes are **not**
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
