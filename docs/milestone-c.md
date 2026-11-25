# Milestone C — process-death checkpoints

> Part of the Flit transport design. [Main ladder and index](transport-bluetooth.md).

## Milestone C design — process-death checkpoints

Read out of the code as it stands (2026-11-14). Three facts:

1. **The sender's owe-state already survives death — for queue batches.**
   `dropQueue` fires only after `dispatchTo` *returns* (the far side spoke:
   sent or declined). A batch killed mid-flight keeps its roster row, its
   spooled files, its persistable grants — all on disk — and the next
   `touchPeer` (multicast, keeper, sweep, manual add) re-enters
   `flushQueueFor`, whose per-file `/partial` probe resumes into the
   receiver's prefix. What is missing is the *fresh* dispatch: a send the
   user started right now is not owed anywhere until it fails — a process
   death mid-flight loses it silently and strands its spool copies.
2. **The receiver's progress lives on disk but is unreachable after death.**
   Interrupted parts persist as `.part` files (plus `.pieces` sidecars) beside
   their targets, but the `parts` registry is in-memory: after a restart the
   `/partial` probe answers 404 and the sender re-sends whole — hash-gated and
   correct, but the prefix is squandered. (The start sweeps only *age out*
   parts older than a day; they never repopulate.)
3. **The flush-on-restart path exists.** Queue rows survive reboot;
   any path that lands the peer as a live row auto-flushes. No new machinery
   needed there.

- **One truth for owe-state: the queue row, written *before* bytes move.**
  A fresh dispatch converges onto the queue's machinery — enqueue the row at
  dispatch time, send through the same path, `dropQueue` on confirmed success
  exactly as the flush path does today. The enqueue-on-failure dance in the
  fresh path dies; every send the engine attempts is owed somewhere first.

- **One truth for progress: the receiver's disk.** No sender-side byte
  counters to persist — the sender re-learns the prefix through `/partial` on
  every re-entry, and the hash gate verifies every landing. A file that fully
  landed before the death re-enters as a zero-byte resume and settles.
- **Receiver durability: the token rides beside the part.** At part-creation
  the receiver writes `resumeToken(file)` into a sidecar next to the `.part`;
  server start sweeps parts (and sidecars) back into the `parts` registry
  with an empty senderIp — which the probe already accepts from anyone.
  The 24-hour aging stays as-is.
- **Edges, listed:** non-persistable sources (mixed spool+URI grants)
  cannot be checkpointed and stay best-effort; a resumed-after-death prefix is
  served with an empty senderIp, trusted only because the probe is a read of
  byte count, not of content; the checkpoint covers deaths of the app process —
  a receiver that died loses nothing (its parts are durable), a sender that
  died keeps its owe and re-enters.

Tests: token sidecar round-trip; a fresh `ServerCore` over a dirty dest answers
`/partial` with the sidecar base; the fresh dispatch owes before bytes (row
visible the moment dispatch starts); the death walk on hardware — transfer
running, `am kill`, restart, same prefix, hash-exact end.

### Milestone C on hardware (2026-11-14)

Fixture: the box serves the freshly rebuilt `flit-cli` (the C build — verified
by the `.part.token` literal in its installed jar); the phone runs the freshly
installed 0.2.0 release APK (owe-first dispatch + attempt-1 probe), parallel
streams toggled OFF in Options so the walk exercises the plain single-stream
path. The payload: `/tmp/opencode/bigresume.bin`, 400,000,000 bytes,
sha256 `8a4acb100cd4ea4596ce4c495a4080ad19f2fd0b8346823dd225e9dc00a79849`,
pushed to the phone's `Download/`. Then, chronologically:

1. **Owe before bytes.** The send chip on the roster row → SAF picker
   (`OpenMultipleDocuments` — persistable grants, so the batch is
   checkpointable) → `bigresume.bin`. With the C build the queue row is written
   *before* any byte moves; 3–4 s later, mid-body, the sender's process is
   killed outright: `adb shell am force-stop app.flit.share`.
2. **The receiver's disk keeps the prefix — and its own discoverability.**
   The abrupt disconnect surfaces as a checksum mismatch; the server kept the
   prefix as `.bigresume.bin.part` —
   **127,959,040 bytes (32 %)** — and wrote the token sidecar
   `.bigresume.bin.part.token` beside it at keep time
   (`41133d96bf74e2023c12bfa26f953f0d`, the content-bound
   name∥size∥sha256 token the sender probes with). The serve log shows
   `recv-start` → `recv-BAD checksum mismatch` — no special death path, the
   ordinary keep rules did it.
3. **The receiver dies too.** The `flit-cli` serve process is killed and
   restarted — the in-memory parts registry is gone; the only trace of the
   prefix is the sidecar on disk. `sweepOnStart` → `repopulateParts` reads it
   back with an empty senderIp.
4. **The sender comes back owed.** `am start` relaunches the app; sharing
   starts from the dock; the queue row survived the force-stop in prefs. The
   engine's first sweep round re-finds the hidden box on its own
   (`sweep … found 1 (Flit (szszszs)@192.168.100.12)`), and the row's touch
   auto-flushes the owed batch — one log line, no user action:
   `queue: 1 queued for Flit (szszszs) — sending now`.
5. **The fresh sender re-learns the prefix.** The flush's `sendFiles` probes
   `/partial` on its first attempt (the C change: `carried > 0 || attempt == 1`)
   and the restarted receiver answers **from its disk** with 127,959,040 —
   the sender attaches with `X-Flit-Resume` on its own and streams only the
   remaining 272 MB. Seven seconds later:
   `sent 1/1 to Flit (szszszs): bigresume.bin`.
6. The receiver's log records `recv-resume bigresume.bin from 127959040
   bytes`, and the landing is hash-exact: `sha256sum` on the box equals the
   reference hash above. The part and its sidecar are gone from the directory
   (consumed by the success move); the queue row is gone from the phone
   (precise drop, sent tokens out, grants released). Screenshot:
   `/tmp/opencode/ui-resume-done.png`.

One death was simulated twice over and the transfer still cost only the bytes
that never landed.

