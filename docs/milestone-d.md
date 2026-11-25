# Milestone D — album mirror

> Part of the Flit transport design. [Main ladder and index](transport-bluetooth.md).

## Milestone D design — album mirror

The ladder line: trusted-peer forward-sync of new camera files, deduped,
checkpointed, queue-flush driven. Read against the code, the shape is
already there — **the mirror is a producer into the queue machinery from
milestone C.**

- **One target, chosen and trusted.** Options gains a switch
  ("mirror new camera shots") and, when it is on, a target row that opens a
  picker over the trusted (pinned) peers. One fingerprint, stored. The row
  speaks no transport vocabulary — it names a device, not a mechanism.
- **The watcher.** A `FileObserver` on `DCIM/Camera` (the stock camera's
  folder) notices `CLOSE_WRITE` and `MOVED_TO` — a finished file, not a
  half-written one. Camera shots, videos, everything the stock camera
  saves; screenshots and downloads stay out.
- **The ledger is the dedup.** Keyed `name∥size` (camera names are
  timestamped; size separates an edit), persisted JSON, capped at 2000 with
  FIFO eviction. Seeded at enable time with the folder as it stands — the
  feature syncs *forward*, it does not replay the album's history. Marked
  **only on send success**; between notice and success the queue row (the
  checkpoint) carries the file, so a death loses nothing — the next
  re-notice or scan just merges into the surviving row.
- **Scan on engine start.** The observer covers liveness; a stat-only scan
  of the folder at every engine start covers completeness — anything that
  landed while the app was dead (or that an OEM FUSE quirk swallowed) is
  caught by the diff, then, and nothing is re-hashed or re-sent.
- **The flush is the queue's.** Notice → ledger check → `FlitRoster.enqueue`
  of a `QueuedSend` for the target fingerprint (path tokens, exactly what
  the queue-flush already consumes) → the same row manual queue entries
  ride: merges with anything pending, auto-flushes on the peer's touch
  (sweep, keeper, multicast — at most one keeper beat of latency),
  checkpoints through any death, drops precisely on success. A live peer
  gets a direct flush nudge at enqueue; an absent one catches the batch on
  its next touch.
- **Success marks the ledger** in the same `dropSent` door every sent batch
  leaves through — filtered to tokens that are files inside the camera
  folder, so manual sends never write it.
- **Edges, listed:** mirroring runs only while sharing runs (the
  flush needs the engine); the target must stay pinned — unpinning pauses
  the mirror rather than re-pointing it silently; files the user deletes
  from the phone after mirroring are not deleted on the peer (forward-sync
  only, never backwards); a phone whose camera saves elsewhere (pro modes,
  third-party cameras) is not watched.

Tests: the pure diff (plan: existing folder vs ledger → candidates), ledger
seeding and cap, the enqueue-merge with a manual row. The observer, the
scan, and the flush are device truth — walked on hardware: a synthetic shot
pushed into `DCIM/Camera` while the box's row is live, arriving on the box
auto-accepted and hash-exact; a restart with nothing new re-sending nothing.

The walk reshaped three choices of this design — raw-path tokens failed with
EACCES, the stat-only scan failed under scoped storage, and the Files
collection returned nothing.

### Milestone D on hardware (2026-11-15)

Fixture: the box serves the freshly rebuilt `flit-cli` (`--hidden`, downloads
into `/tmp/opencode/flit-cli-downs`); the phone runs the freshly installed
0.2.0 release APK with the box pinned, trusted, and chosen as the mirror
target. The device: the Redmi Note 10S (API 36) as always.

**The first walk caught a defect.** The toggle went on, the seed ran
(`mirror: baseline seeded — 1 existing camera
file(s) stay put`), the watcher went live, a synthetic 3,000,000-byte shot
(`IMG_20261115_141900.jpg`, sha256 `18c6ac31…`) was pushed into
`DCIM/Camera` — and the queue's flush died on contact:

    mirror: IMG_20261115_141900.jpg (3000000 bytes) — new camera file
    queue: 1 queued for Flit (szszszs) — sending now
    send failed: /storage/emulated/0/DCIM/Camera/IMG_…: open failed: EACCES (Permission denied)

The design's "path tokens, exactly what the flush consumes" was wrong for
camera files: **shared storage is not openable by raw path** — scoped
storage forbids it, and the offer never left the phone. Three catches in
one walk, each fixed before the redo:

1. **Tokens are MediaStore uris now.** A camera shot's token is its
   `content://media/…` uri (the queue's spool step opens it through the
   ContentResolver like any SAF uri). Reading media asks for the photo/video
   read — `READ_MEDIA_IMAGES` + `READ_MEDIA_VIDEO` (plus
   `READ_EXTERNAL_STORAGE` capped at 32) — the mirror's **one system
   dialog**, fired when mirroring is switched on; a refusal leaves
   mirroring off ("photo access is needed — mirroring stays
   off"). Mirror rows are gated to API 29+ (`RELATIVE_PATH` is the query's
   spine). The ledger marks close through `dropSent` via the batch's
   *mirror duty* and the spooled names/sizes — no path sniffing.
2. **The seed and the scan ride MediaStore, not `stat`.** The enable-time
   seed and the engine-start scan diff the same query the watcher's notice
   polls: `cameraShots()` — names, sizes, uris of everything in
   `DCIM/Camera/` — against the ledger. This also killed a second silent
   no-op the walk caught: the toggle can fire *before the engine ever
   started*, and the original seed crashed inside a `runCatching` and said
   nothing — `mirrorSeed(ctx)` now takes the caller's context.
3. **Never silent, enforced.** An empty query, a failed query, a grant
   refused — each logs a line (`camera query answered nothing
   (grant or folder empty?)`), because the walk also proved the app can sit
   there "watching" an album it cannot see, and that must be visible.

Two device quirks the walk pinned down (both are walk-only artifacts; real
use never sees them, but they cost real debugging time):

- **adb-pushed files land `is_pending=1 owner_package_name=com.android.shell`.**
  The shell sees its own pending rows; the app — correctly — does not see
  other apps' pending rows. `pm grant` of the media permissions, revoking
  the partial `READ_MEDIA_VISUAL_USER_SELECTED` grant, nothing helped until
  the row was finalized with a
  `MEDIA_SCANNER_SCAN_FILE` broadcast (`is_pending=0` after it). Real
  camera shots are written pending by the *camera*, owned by it, and
  finalized by it — never pending to their reader.
- **MIUI had pre-granted the media permissions** (`USER_SET` flags), so the
  system ask never showed on this device; on a stock device the ask is the
  expected first step of the toggle.

The redo, after the fixes:

1. Toggle on → `mirror: baseline seeded — 1 existing camera file(s) stay
   put` (the first shot had already been mirrored by the pre-fix walk; the
   seed holds it put — the ledger doing its job).
2. Sharing on → `serving https://0.0.0.0:53317` + `mirror: watching
   /storage/emulated/0/DCIM/Camera`; the first sweep round re-finds the box
   on its own (`found 1 (Flit (szszszs)@192.168.100.12)`).
3. A fresh synthetic shot, `IMG_20261115_144400.jpg`, 2,500,000 bytes,
   pushed and finalized. Fourteen seconds later — the whole chain, unprompted:

       mirror: IMG_20261115_144400.jpg (2500000 bytes) — new camera file
       queue: 1 queued for Flit (szszszs) — sending now
       sent 1/1 to Flit (szszszs): IMG_20261115_144400.jpg

   notice → queued → sent in under 200 ms (the flush nudge fired on a live
   peer).
4. On the box: `recv-ok IMG_20261115_144400.jpg →
   … (2500000 bytes)`, and the landing is hash-exact —
   `3325c7abad2dc3bf163a1e5dd9a053184bf57cc346b759fdacf0e86b13a8874d` on
   the sender side and on the box.
5. **The dedup proof:** force-stop, relaunch, sharing back on. The
   engine-start scan ran over the same folder and said nothing — no
   re-queue, no `scan found` line; the box holds exactly one copy, no
   `(1)` rename. The ledger is the dedup.

One more MediaStore finding from the walk: a query of the **Files
collection answered nothing under the very same grant that made the Images
collection answer** — so the shipped `cameraShots()` queries the **Images
and Video collections** (the homes camera JPEG/MP4 rows live in), merged
and token-deduped. The watcher also nudges the scanner and polls the query
for a few beats: a freshly closed file is not instantly indexed, and the
next scan would catch it anyway — nothing lost, nothing re-sent.

Rig at commit time: 86 tests green (23 protocol including the death sim;
63 app including the mirror diff/merge additions), debug + release
assemblies and the CLI dist green. Screenshot of the configured mirror:
`/tmp/opencode/ui-mirror.png`.

What this milestone leaves open, listed: mirroring runs only while sharing
runs; a device whose camera saves
elsewhere is not watched (only `DCIM/Camera` feeds MediaStore's
`RELATIVE_PATH='DCIM/Camera/'` filter — pro modes and third-party cameras
that write elsewhere stay out, and the empty-query log line makes that
visible); deletions are never mirrored backwards; the ledger keys
name∥size, so a same-name same-size re-save is considered already-mirrored
(camera names are timestamped).

