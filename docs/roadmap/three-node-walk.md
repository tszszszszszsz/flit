# Three-node baseline walk

Establish baseline evidence for a three-node Circle on the shipped v0.2.0 rig before Courier mode, QR parcels, per-drop keys, receipts and the Circle pad change the observed behaviour.

## Date and layer

2026-11-26, Device. Commit scope: three-node baseline walk and evidence doc. No code, build change or release artifact is part of this date.

## Depends on

none. The 2026-11-25 planning date produced the roadmap files; the walk exercises shipped v0.2.0 behaviour last green at `72225f8`.

## Scope

Three identities share one Circle: A is the phone running the Flit app as host, B and C are two `flit-cli` identities on the box with separate harness homes and no always-on listener. The walk records Circle founding, the v3 QR join, the three-member roster, message fan-out, a poll with a changed vote, a file drop with a hash-exact landing on the phone, local-only delivery status, and a departure with rekey. It also records the delay baseline: a box identity that is absent at post time has no mailbox and sees the events only on its next `circle sync`.

No route, header, DTO, codec or state field is added. The output is one device walk and its evidence note.

## Design

### Nodes and identity separation

| Node | Surface | Identity and state |
| --- | --- | --- |
| A | phone, Flit app, Circle host | `flit-identity.p12` in app `filesDir`; `FlitCircleStore` state in SharedPreferences `flit`, key `circle.state` |
| B | box, one-shot `flit-cli circle` commands | `FLIT_HARNESS_HOME=/tmp/opencode/bot-b`, holding `identity.p12` and the seven-line `circle.state` written by `Harness.kt` |
| C | box, one-shot `flit-cli circle` commands | `FLIT_HARNESS_HOME=/tmp/opencode/bot-c`, holding its own `identity.p12` and `circle.state` |

`harnessHome()` in `harness/src/main/kotlin/app/flit/share/harness/Harness.kt` reads `FLIT_HARNESS_HOME` and defaults to `~/.config/flit/flit-harness`. One home shared by B and C, or shared with a `serve` process, mixes fingerprints and `circle.state`; that is what made an earlier device fan-out observation ambiguous (`docs/TESTING.md` §8, `docs/circle.md:357-361`). Each node uses its own home. The phone keeps the keystore identity the QR advertised; no new key material is minted for the walk.

### Wire shapes observed

- Routes: `POST /api/flit/v1/circle/join` and `POST /api/flit/v1/circle/sync`, with `X-Flit-Circle-Cert`. No v2.2 route changes.
- `CircleRequest(action, circleId, invite, memberFp, events, body)` with `action` in `{join, sync}`; `CircleResponse(ok, key, members, events, reason, serverCert, sig)`. A refusal is a signed `200` with `ok=false` and a reason. The group key appears only in a successful JOIN answer; a SYNC answer carries `key = ""`.
- `CircleEvent(id, circleId, authorFp, lamport, type, body, sig, createdMs, cert)`; canonical bytes domain-tagged `FLITCIRCLE1` and length-prefixed; nine closed verbs. A file body is exactly `{"name":"…","size":<non-negative integer>,"sha256":"…"}` (`CircleCore.FILE_BODY_KEYS`), with no `token`, `path` or `spoolPath`.
- v3 QR: `flit://add?v=3&ip=<box-lan-ip>&port=53317&tls=1&fp=<host-fingerprint>&alias=<alias>&circle=<ID>&invite=<TOKEN>`. No `key` parameter; `Qr.parseAddCode` refuses a v3 code missing `circle` or `invite` (`app/.../Qr.kt:119-123`). `Qr.encode` uses EC level M, margin 2 and UTF-8; `Qr.decodeYPlane` is the camera-shaped frame decoder.
- File bytes travel by `ProtocolClient.sendFiles` from the sender's private spool, not by pull-from-hash and not through the send-later queue. The landed SHA-256 correlates a card with bytes; status lives in `FlitCircleStore.CircleDelivery` and the per-node `circle.state` as `pending`, `sent`, `refused`, `failed`, never on the wire.

### Constants held by the walk

| Constant | Value |
| --- | --- |
| `CircleCore.REPLAY_WINDOW_MS` | `6 * 60 * 60 * 1000L` (6 h) |
| `CircleCore.CLOCK_SKEW_MS` | `5 * 60 * 1000L` (5 min) |
| `CircleCore.MAX_SEEN` | `4096` |
| `CircleCore.MAX_BODY_CHARS` / `MAX_FIELD_CHARS` | `16 * 1024` / `256` |
| `CircleCore.MAX_SIG_CHARS` / `MAX_CERT_CHARS` | `1024` / `8 * 1024` |
| `CircleCore.MAX_EVENTS_PER_REQUEST` / `MAX_REQUEST_BYTES` | `1024` / `8 * 1024 * 1024` |
| `FlitCircleStore.EVENT_CAP` | `500` |
| `FlitCircleHost.MAX_MEMBERS` | `500` |
| `FlitCircleSync.CIRCLE_SYNC_MIN_INTERVAL_MS` | `30_000L` |
| `FlitCircleSync.CIRCLE_BACKOFF_BASE_MS` / `CIRCLE_BACKOFF_MAX_MS` | `5_000L` / `120_000L`; real delays `10, 20, 40, 80, 120 s` for attempts 1–5 |
| `FlitCircleSync.CIRCLE_PEER_DIALS` | `4` |
| `TlsIdentity.MAX_PINS` | `512` |
| `Protocol.DEFAULT_PORT` | `53317` |

### State ownership

The phone holds circle id, key, roster, bounded event log and rekey generation in `FlitCircleStore`; every read-modify-write is serialised under one `ioLock`. B and C hold their copies in their own `circle.state` files and rewrite them after each one-shot command. Host authority stays with `FlitCircleHost` on the phone: JOIN gates the invite, SYNC gates the roster, departure rotates the key. Delivery records and their statuses are local to the sender and are never gossiped. The group key is membership and rekey material, not a content cipher; stored event bodies stay plaintext and earlier events remain readable on a device after its departure.

### Log lines read

`circle host: join`; `circle sync: +K event(s) from <fp>`; `circle fan-out: N new event(s), M peer(s)[, K backed off]`; `circle fan-out: ok to <fp> (N event(s) offered)`; `circle fan-out: no route to <fp>`; `circle sync: full-log repair to M peer(s)`. On the box: `joined <id>: N member(s), M event(s), key=…`; `recv-start …`, `recv-ok …`, `recv-BAD …`, `recv-ERR …`. Line text is taken from `FlitCircleSync.kt` and `ConsoleDelegate` in `Harness.kt`; no new line is added for this layer.

## Invariants

1. The three fingerprints are distinct uppercase-hex SHA-256 values of the certificate DER; B and C use different `FLIT_HARNESS_HOME` paths.
2. The v3 QR has no `key` parameter, and `parseAddCode` returns `null` for a v3 code without a non-blank `circle` and `invite`.
3. After both joins every node shows the same three-member roster; the key is present only in a successful JOIN answer and absent from SYNC answers.
4. Every stored event passed `validShape → CircleCore.verify → CircleCore.evaluate`/`accept → store`; decision precedence is `Invalid → OutsideWindow → Duplicate → Accept`, and only `Accept` reserves the id.
5. Event order is `(lamport, authorFp, id, …signed fields)` and `CircleCore.dedup` keeps one winner per id regardless of arrival order.
6. A file body has exactly the keys `name`, `size`, `sha256`; no spool path or queue token enters a signed event, and a landing is gated on SHA-256.
7. Delivery status is local-only; a card's landed state is established by a local hash match, not by a remote report.
8. Fan-out uses per-peer deltas keyed by `(lamport, id)`; the calm repair pass offers the bounded full log; ticks are at least `30_000L` apart; retries wait `10, 20, 40, 80, 120 s`; concurrent dials never exceed `4`.
9. A departure removes the roster entry, saturates `rekeyGen` and makes the host sign a fresh 32-byte key; a later sync from the departed fingerprint is refused and the earlier local log stays plaintext.
10. A one-shot box identity with no listener has no mailbox: events posted while it is absent appear only on its next `circle sync`.
11. No route, header, DTO or canonical byte layout changes; a stock LocalSend v2.2 peer sees the same `/api/localsend/v2` surface.
12. A claim is recorded at the weakest supporting label. An unobserved direction stays `test-evidenced only` or `not verified`; nothing is written as DEVICE before the walk runs.

## Failure modes

| Condition | Conclusion | Keeps bytes | Drops | Evidence |
| --- | --- | --- | --- | --- |
| B and C share a harness home, or a home is shared with `serve` | Peer-side confirmation is ambiguous; discard that observation and re-run with separate homes | Local state files | The ambiguous observation | CODE `Harness.kt:81-99`; `TESTING.md` §8 |
| v3 QR missing `circle` or `invite` | `parseAddCode` returns `null`; no join | Nothing | The QR payload | CODE `Qr.kt:119-123` |
| Wrong invite or a cert not bound to its fingerprint | Signed `200` with `ok=false` and a reason; no key persisted | Prior circle state | Invite acceptance | CODE `FlitCircleHost.kt:119-144`; PURE `FlitCircleHostTest` |
| Box identity absent when an event is posted | Fan-out logs `no route` or a backed-off peer; the event stays in the local log | Local event and any spool | Nothing | DEVICE log line; CODE `FlitCircleSync.kt:700-714` |
| File body carrying an extra key | `validFileBody` false; event skipped | Log unchanged | The event | CODE `CircleCore.kt:315-325`; PURE `CircleHardeningTest` |
| Full-length file body with a bad digest | Upload answered `422` | Nothing at the receiver | Temp and sidecars | CODE `ServerCore.kt:614-624`; `FAILURE-MODES.md` §2.1 |
| Identity joins after a file drop | Card folded, no old bytes; no backfill | Local log and landed files | Nothing | CODE `docs/circle.md:317-322` |
| Departed fingerprint syncs later | Refused at the roster gate | Earlier local log stays plaintext | Roster entry and old key material | CODE `FlitCircleHost.kt:147-151`; PURE `FlitCircleHostTest` |
| No second phone and no Bluetooth adapter on the box | Phone-to-phone RFCOMM stays `not verified` | Existing evidence | No claim | CODE `TESTING.md` §1 and §6 |

## Tests

PURE — no new test is added on this date. Re-run the shipped suites with `--rerun-tasks`: 82 protocol + 210 app + 10 harness = 302 tests, 0 failures, at `72225f8` (historical). The rig at that layer was 714 = 330 protocol + 309 app + 75 harness (historical; current inventory is 972) after a green `--rerun-tasks` run; the 396 inventory is historical. The relevant classes are `CircleCoreTest`, `CircleHardeningTest`, `FlitCircleHostTest`, `FlitCircleSyncTest`, `FlitQrTest`, `DtoParityTest` and `LinkServerParityTest`; constants and refusal reasons are asserted exactly, with `createdMs` injected for window edges. The rig command is the CI command in `docs/TESTING.md` §3.

SIM-HARN — re-run `CircleHarnessTest` (five two-node cases over production routes) and `bt-sim` as the transport-agnostic regression gate. No three-node harness case is added on this date; the two box identities are CLI processes in the device walk, not a new harness module.

DEVICE — the walk, run only on 2026-11-26:

1. Found the Circle from the phone's app. Record the circle id, invite, host fingerprint and the exact v3 QR URI; confirm no `key` parameter.
2. Join B with `FLIT_HARNESS_HOME=/tmp/opencode/bot-b` and `flit-cli circle join --host <box-lan-ip>:53317 --circle <ID> --invite <TOKEN>`, then C with `/tmp/opencode/bot-c`. Record the phone's `circle host: join` lines, the `joined …: 3 member(s)` lines, the three fingerprint prefixes and the roster on each node.
3. Post from B with `flit-cli circle post --text baseline-3-b --host <box-lan-ip>:53317`. Confirm the phone feed renders author and timestamp with `circle sync: +1 event(s) from <fp>`, and that C's `flit-cli circle sync --host <box-lan-ip>:53317` shows the same event.
4. Post from the phone's composer. Record the exact `circle fan-out:` line and any `no route to` lines, then pull the event on C. Record that no box identity had a listener or mailbox while the post was made.
5. Open a poll from B with `flit-cli circle poll --text 'Ship?' --option yes --option no --host <box-lan-ip>:53317`; vote `0` from C and the phone, then re-vote `1` from C. Confirm `circle sync` keeps both votes and counts the latest one per member.
6. Create a small seeded file, record its size and sha256, and post it with `flit-cli circle file --file <path> --host <box-lan-ip>:53317`. Confirm the phone card reads `Landed here`, `recv-ok` matches the sha256, B's local status map is `sent` for the phone and `pending`/`failed` for C, and the stored event JSON has exactly the three file keys.
7. Promote B to ADMIN from the phone's member sheet, have B kick C, and confirm the roster drops to two, `rekeyGen` advances and a fresh key is signed. Confirm C's later `circle sync` is refused while earlier events remain on the phone.

## Evidence for this day

`docs/completions/2026-11-26-three-node-baseline.md`, written when the walk runs. It holds the date, the node table with harness homes and fingerprint prefixes, the circle id and invite as observed, the exact v3 QR URI, the rig command and its counts, the exact walk commands, timestamped log excerpts for each step, the file size and sha256 with the landed path, the stored event key set, the roster and rekey observations, and the conclusions labelled CODE / PURE / SIM-HARN / DEVICE. Limitations recorded with it: outbound phone→host fan-out stays test-evidenced only because the phone is host in this walk; phone-to-phone RFCOMM and a real multi-phone mesh are not verified; Wi-Fi Aware is absent on the rig phone; one-shot box members have no mailbox, so delay tolerance stays test-evidenced only. No completion note is written before the walk happens.

## Out of scope

No code, Gradle change, git operation or commit; no new route, header, DTO, event verb or test. Courier mode, QR parcels, per-drop content keys, delivery receipts and the Circle pad are not implemented or exercised on this date. So are a second physical phone, phone-to-phone RFCOMM, multi-hop chains, more than three nodes, Wi-Fi Aware, a desktop GUI and `.flitpack` bundles. Existing docs are not edited here; the only new file from a completed day is the evidence note under `docs/completions/`.

## Commit

2026-11-26 Device — Three-node baseline walk, evidence doc
