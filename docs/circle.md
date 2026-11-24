# Circle — accountless group coordination (design, written before code)

> Part of the Flit transport design. [Main ladder and index](transport-bluetooth.md).
> **Status: v1 built, 2026-11-24.** JVM-pure event core, governance store,
> QR v3 invites, app sync worker, M3 feed, harness CLI and the two-node walks
> are in. See the evidence section at the end.

## Why this exists

Flit moves files between devices. People who move files together also need to
coordinate: *which file, who has it, does everyone agree, is anyone stuck*.
Today that happens outside the app, in tools that demand accounts, phone
numbers, a server and an internet connection.

A **Circle** is that missing primitive: a temporary, accountless, offline-first
group of fingerprint-pinned devices with one encrypted local event log. It
carries three kinds of things — short messages, file drops and re-votable
polls — over the carriers Flit already has. It is not a chat app clone: no
accounts, no cloud, no phone numbers, no public directory, no auto-actions.

## Product shape (v1)

- A Circle is founded by one device and joined by scanning (or pasting) a QR
  code. Members are identified by their existing `TlsIdentity` fingerprint;
  aliases are local labels, never an identity claim.
- The surface is a compact **activity feed**, not chat bubbles: message cards,
  file-drop cards, poll cards, and a row of member chips (short fingerprint +
  local label).
- Three event kinds, nothing else in v1:
  1. **Message** — short signed text.
  2. **FileDrop** — a reference to a file (name, size, SHA-256, resume token).
     The bytes are pulled through the existing queue/resume path; the event
     never contains file data.
  3. **Poll** — a question with options, plus signed **Vote** events. A member
     can change their answer by publishing a newer vote; the tally uses each
     member's latest vote, while the feed keeps the change visible.
- One active Circle per device in v1. Bounded local log (≈500 events).
- **Governance (revised 2026-11-24):** the founder is the owner; the owner may
  appoint admins. An admin can kick a member directly. Any member can open a
  vote-kick against another member (not the owner in v1); one latest vote per
  member, changeable, simple majority of current members decides. Every
  membership change — leave, admin kick, successful vote-kick — triggers an
  immediate **rekey** to the remaining members: full leave really means full
  leave, and a departed device cannot read a single later event.

## Founding and key distribution

QR v2 is extended with `circle=<random id>`, `invite=<random token>`, the host
fingerprint and the host's route hints (Wi‑Fi address, Bluetooth MAC, optional
relay address + token). **The group key is not in the QR.** The joiner opens an
authenticated TLS session to the host using the fingerprint from the code,
presents the invite token and receives the group key *inside that session*. A
relay or a network observer sees only opaque bytes.

The QR is therefore a bearer invite, not the key itself: a photographed code
can be used by whoever holds it. That is stated honestly in the UI; suspicious
codes are retired by starting a new Circle.

## Threat model and safety rails

| Adversary | What holds | What does not |
| --- | --- | --- |
| Passive network observer | payloads encrypted with the group key | sees IPs/MACs, timing and byte counts |
| Malicious relay | pairs blindly, never reads content | sees which fingerprints paired and traffic volume |
| Stolen QR photo | bearer invite can join | does not reveal the group key without a TLS session to the host |
| Compromised member | can read and emit as that member | cannot forge another fingerprint (signature + pinning) |

Non-negotiables: no file opens automatically; no vote triggers an action;
leaving a Circle deletes the local key and hides the feed, never wipes or
changes another device. There is no remote auto-action of any kind.

## Event log (the only genuinely new core)

`@Serializable CircleEvent(id, circleId, authorFp, lamport, type, payload, sig, createdMs)`

- **Canonical bytes** cover every field except `sig`; the author signs them
  with its `TlsIdentity`. This generalises the existing panic-signing primitive
  into a shared `sign(bytes)` / `verify(fp, bytes, sig)` pair, with the panic
  route kept as a thin wrapper. No second crypto implementation.
- **Ordering:** Lamport clock per author; deterministic total order by
  `(lamport, authorFp, id)` so every member converges without a server.
- **Dedup/replay:** bounded seen-id set (≈4096) and a clock-skew window; future
  or replayed events are refused. Same discipline as `PanicCore`, reused.
- **Sync:** peers exchange event-id summaries, then fetch only missing events
  over an existing `Link`/`LinkProvider` session. App-layer store-and-forward
  (bounded log) lets an offline member catch up when it returns. The relay is
  never given storage; it stays a blind pipe.
- **Vote resolution:** the latest signed `Vote(pollId)` per member wins;
  tally counts winners per option. Changing an answer appends a new vote, so
  the history stays honest. Ties are displayed as ties, not hidden.

## Transport and battery

Events ride the already-existing route policy: **Wi‑Fi and Bluetooth are both
first-class carriers**, chosen per peer, never silently — plus the blind relay
when neither local carrier answers. Each member can sync over either radio;
bonded aggregation of both at once is deliberately out of scope here. Sync happens on foreground
and on a calm periodic tick; no always-on scanning, no new radio duty cycle.
Message/poll events are tiny; file bytes use the bulk queue path unchanged.

## Explicit scope cuts for v1

No voice, video, reactions, threads, read receipts, search beyond the bounded
log, multi-hop mesh or bonded aggregation of both radios at once. Group-key
rotation **is** in v1 because leave/kick must be real. Voting is limited to
kicks in v1; polls cover decisions. These are later items only if a real use
demands them.

## Test story

- **Pure JVM:** canonical/sign/verify; forged author rejected; dedup and replay
  window; Lamport convergence from scrambled delivery order; vote change and
  tie handling; file-drop manifest validation; QR v2 Circle fields parse.
- **Harness, two nodes (phone + box CLI):** message sync, poll re-vote, file
  drop landing hash-exact through the existing queue, and offline catch-up —
  once over direct Wi‑Fi and once through the relay.
- **Device:** the two-node walk above. A genuine multi-member mesh needs
  hardware that is not on this rig; it stays a listed limitation.

## Relationship to the rest of Flit

Circle reuses QR v2, known devices, route policy, TLS pinning, the relay, the
queue with process-death checkpoints, the resume gate and the SHA-256 landing
proof. The only new machinery is the pure event log and a thin feed UI. That is
the difference between a Swiss‑Army knife and a drawer of half-finished tools.

## Build and verification record (2026-11-24)

**Built:** `CircleCore` with signer-certificate transport on every event (a
receiver can pin an author it has never met), generalized `TlsIdentity`
signing, additive `/circle/join` and `/circle/sync` routes, a default-refusing
delegate hook, `FlitCircleStore` governance (full leave, admin kick,
changeable majority vote-kick, rekey generation), QR v3 invite codes with no
key on the wire, a calm app sync worker, an M3 activity feed, and
`flit-cli circle <create|join|post|poll|vote|kick|leave|sync>`.

**Harness:** two in-process TLS nodes synced a message, a poll with a changed
vote (tally flipped 0→1 with both votes retained), a file-drop manifest, and a
kick that removed the member, rotated the key and refused a later sync from
the departed fingerprint.

**Device (phone + box CLI):** joined a two-node circle by v3 QR
paste; posted a message from the CLI; after adding signer-cert transport the
phone logged `circle sync: +1 event(s) from AFF5EBF981DE` and rendered the
message with author and timestamp in the M3 feed. Full leave was walked: the
member sheet's Leave action logged `circle: left fully — key discarded` and
the feed disappeared.

**Honest limitation:** the phone→host fan-out path did not produce a device
log line in this walk even though the event landed locally and the harness
two-node test proves fan-out. Inbound sync, join, leave and the governance
logic are device-evidenced; that one direction is test-evidenced only and is
listed rather than hidden.

**Tap-to-join (later the same day):** `flit://add?v=3&…&circle=…&invite=…`
now opens the app via a `VIEW` intent filter, starts sharing and joins the
Circle without pasting. Device evidence: `invite link parsed=true`,
`circle sync armed for 4 member(s)`, and a feed showing the box's poll with
changeable votes and two bot messages. Invite text/HTML were also pushed to
the phone's Downloads for manual opening.
