# Circle — accountless group coordination

> Part of the Flit transport design. [Main ladder and index](transport-bluetooth.md).
> **Status: v1 built, 2026-11-24.** JVM-pure event core, governance store,
> QR v3 invites, app sync worker, M3 feed, harness CLI and the two-node walks
> are in. See the evidence section at the end.

> **Non-profit constraint (hard, everywhere):** no accounts, no cloud, no ads,
> no telemetry, no SDKs that phone home. A Circle is a temporary group of devices
> already known by fingerprint — there is no server that hosts it, no identity to
> register, and nothing to collect. A phone can itself be the host; see
> [Host and role enforcement](#host-and-role-enforcement-as-built).

## Why this exists

Flit moves files between devices. People who move files together also need to
coordinate: *which file, who has it, does everyone agree, is anyone stuck*.
Today that happens outside the app, in tools that demand accounts, phone
numbers, a server and an internet connection.

A **Circle** is that missing primitive: a temporary, accountless, offline-first
group of fingerprint-pinned devices with one **signed** local event log —
authenticated by fingerprint-pinned signatures and TLS-protected on the wire,
but plaintext at rest. It carries three kinds of things — short messages, file
drops and re-votable polls — over the carriers Flit already has. It is not a
chat app clone: no accounts, no cloud, no ads, no telemetry, no phone numbers,
no public directory, no auto-actions.

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
  leave, and the host refuses a departed device's later sync so it cannot fetch
  any new events. (Earlier events it already stored stay plaintext on that
  device — there is no content cipher to erase.)

## Host and role enforcement (as built)

A Circle has a **host** — the device that founded it and holds the authoritative
roster, event log and group key. The harness box has always hosted its Circle in
the rig; this wave makes the **phone a real host too**, rather than a read-only
event vending machine.

- **Phone as host.** The app's host path (`FlitCircleHost`, wired from
  `FlitEngine.onCircleRequest`) does not just hand out stored events. For every
  incoming request it verifies each event through `CircleCore` with the
  event's signer certificate pinned to the author; a `memberFp` that is not in
  the roster is refused (except a `JOIN` carrying the correct invite). `JOIN`
  admits the member and returns the key plus the ordered log inside the
  authenticated TLS session; `SYNC` folds verified events through the
  `FlitCircleStore` reducers. A leave or kick rekeys the group, and a later
  `SYNC` from a departed fingerprint is refused. Wrong-circle and bad-invite
  requests get honest refusal reasons, not silent success.
- **Owner and admins.** The founder is `OWNER`; only the owner can appoint or
  remove an `ADMIN`. This is now a real, wired action —
  `FlitEngine.circleSetRole(fp, role)` publishes a `role` event, the owner-only
  controls on the member sheet invoke it, and the store's `applyRole` reducer
  enforces it. Nobody, not even the owner, can appoint a second `OWNER`; an
  `ADMIN` (or the `OWNER`) can kick
  a non-owner member, and a plain member can only open a vote-kick. Roles are
  local authority labels pinned to a fingerprint, **not** cloud identities or
  accounts — nothing is registered anywhere.
- **Local, not remote.** Every governance change is applied to each device's own
  view of the log; no action is pushed onto another device. A departed device is
  refused at the host's roster gate and is not handed the rotated key material —
  a membership decision, not a server revoking content already on that device.

## Founding and key distribution

The QR v3 Circle invite extends the base QR with `v=3`, `circle=<random id>`,
`invite=<random token>`, the host fingerprint and the host's route hints
(Wi‑Fi address, Bluetooth MAC, optional relay address + token). **The group key
is not in the QR.** The joiner opens an authenticated TLS session to the host
using the fingerprint from the code, presents the invite token and receives the
group key *inside that session*. A relay or a network observer sees only
TLS-opaque bytes — the secrecy here is the transport's, not a group-key cipher.

The QR is therefore a bearer invite, not the key itself: a photographed code
can be used by whoever holds it. That is stated honestly in the UI; suspicious
codes are retired by starting a new Circle.

## Threat model and safety rails

| Adversary | What holds | What does not |
| --- | --- | --- |
| Passive network observer | event traffic is TLS-protected; signatures prove authorship | sees IPs/MACs, timing and byte counts; stored event bodies are plaintext JSON (authenticated, **not encrypted at rest**) |
| Malicious relay | pairs blindly, never reads content | sees which fingerprints paired and traffic volume |
| Stolen QR photo | bearer invite can join | does not reveal the group key without a TLS session to the host |
| Compromised member | can read the plaintext log and emit as that member | cannot forge another fingerprint (signature + pinning) |

**What "the group key" is — and is not.** It is shared membership/rekey material
distributed only inside the authenticated TLS join session and rotated when a
member leaves or is kicked, so a departed device can no longer be handed the new
material. It is **not** a content cipher: there is no encryption code path for
event bodies. Confidentiality comes from TLS on the wire; integrity and
authorship come from the signature over canonical bytes and the signer-cert
pin. Anyone with access to a device's stored state can read its log in plaintext.

Non-negotiables: no file opens automatically; no vote triggers an action;
leaving a Circle deletes the local key and hides the feed, never wipes or
changes another device. There is no remote auto-action of any kind.

## Event log (the only genuinely new core)

`@Serializable CircleEvent(id, circleId, authorFp, lamport, type, body, sig, createdMs, cert)`

Here `body` is a small plaintext JSON object whose shape depends on `type`
(`message`, `file`, `poll`, `vote`, `join`, `leave`, `kick`, `role`, `rekey`); it
is signed but not encrypted.

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
  tie handling; file-drop manifest validation; QR v3 Circle fields parse.
- **Harness, two nodes (phone + box CLI):** message sync, poll re-vote, file
  drop landing hash-exact through the existing queue, and offline catch-up —
  once over direct Wi‑Fi and once through the relay — plus role promotion and a
  kick/rekey that refuses a later sync from the departed fingerprint.
- **Host state machine:** a pure, deterministic host verifies every incoming
  event against the roster, admits `JOIN` only with the right invite, folds
  `SYNC` through the store reducers, and refuses departed members and
  wrong-circle requests.
- **Device:** the two-node walk above. A genuine multi-member mesh needs
  hardware that is not on this rig; it stays a listed limitation.

## Relationship to the rest of Flit

Circle reuses QR v3 invites, known devices, route policy, TLS pinning, the
relay, the queue with process-death checkpoints, the resume gate and the SHA-256
landing proof. The only new machinery is the pure event log and a thin feed UI.
That is the difference between a Swiss‑Army knife and a drawer of half-finished
tools.

## Build and verification record (2026-11-24)

**Built:** `CircleCore` with signer-certificate transport on every event (a
receiver can pin an author it has never met), generalized `TlsIdentity`
signing, additive `/circle/join` and `/circle/sync` routes, a default-refusing
delegate hook, `FlitCircleStore` governance (full leave, admin kick,
changeable majority vote-kick, rekey generation), QR v3 invite codes with no
key on the wire, a calm app sync worker, an M3 activity feed, and
`flit-cli circle <create|join|post|poll|vote|kick|leave|sync>`. The phone is now a
**full host** (`FlitCircleHost` behind `FlitEngine.onCircleRequest`): it verifies
incoming events, gates the roster, applies rekey and refuses departed members,
and the owner→admin role change is wired through `circleSetRole` and the
owner-only member-sheet controls.

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

**Tap-to-join (later the same day):** `flit://add?v=3&…&circle=…&invite=…`
opens the app via a `VIEW` intent filter, starts sharing and joins the Circle
without pasting. Device evidence: the feed showed the box's poll with
changeable votes and two bot messages; invite text/HTML were also pushed to the
phone's Downloads for manual opening. The two one-shot diagnostic lines from
that first walk (`invite link parsed=…`, `circle sync armed…`) were noise and
have been removed — the feed content, not those lines, is the evidence.

## Known limitations (recorded, not hidden)

- **Outbound phone→host fan-out:** in the 2026-11-24 walk an event posted on the
  phone did not produce a device log line, even though it landed locally and the
  harness two-node test proves fan-out. Inbound sync, join, leave and governance
  are device-evidenced; that one direction was **test-evidenced only**. This
  wave hardens the sync worker's outbound fan-out and adds evidence-bearing
  per-peer logs; the phone→host device re-walk is the final verification item.
  Until it passes, the direction stays listed here rather than claimed as
  device-verified.
- **Multi-member mesh:** only the two-node walk (phone + box CLI) has been on
  hardware. A genuine three-or-more-device mesh needs hardware not on this rig.
- **One Circle at a time:** v1 supports one active Circle per device and a
  bounded local log (≈500 events); there is no search beyond that log.
- **No content encryption:** event bodies are plaintext JSON in the local log.
  TLS protects them in transit and signatures authenticate authorship, but the
  stored log is readable by anyone with access to the device's state, and a
  member in the roster can read everything. The shared key is membership/rekey
  material, not a cipher. Do not put secrets in Circle messages.
- **Bearer invite:** the QR is a bearer token — whoever holds the photo can join.
  It does not reveal the group key without a TLS session to the host; retire a
  suspicious code by starting a new Circle.
- **Blind relay:** store-and-forward is app-layer and bounded; the relay itself
  stores nothing and has no discovery, so peers must be configured by hand.

### Final 2026-11-25 polish wave — honest close

The eleven-agent polish wave closed the P0 host gap (the phone now runs a real
`FlitCircleHost` behind `onCircleRequest`), wired owner→admin role changes,
hardened the protocol core (size caps, replay guards, pin-after-verify,
deterministic dedup), added atomic store persistence, a real a11y/M3 pass, and
a two-node harness walk covering role, admin kick, rekey and departed-member
refusal. Two additional device bugs were found and fixed: the invite token is
now persisted (a host could not admit members after a restart) and JOIN/SYNC
now remembers the requester's return route.

**Verified on device:** QR-decoded invite → join; phone as host logging
`circle host: join` and `sync … event(s)`; inbound event rendered in the M3
feed; auto-scroll follows own sends and does not yank history; full leave
discards the key. **Still not device-proven:** outbound phone→host fan-out
posting from the app — the behavioural fan-out tests pass with an injected
carrier, but the device walk could not produce a clean peer-side confirmation
because the demo bots' CLI state and their `serve` process shared identities.
This remains test-evidenced only, recorded here rather than hidden.
