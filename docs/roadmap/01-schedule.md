# Commit schedule

One layer per working day, one commit per day, no weekend commits. A commit
contains a complete layer: code, tests, a completion note under
`docs/completions/`, and docs updates. Nothing is committed halfway through a
layer.

Build-ahead: code may be written ahead of the dates below. The dates govern
commits, not when work happens. Commits stay spoon-sized and land on the
scheduled weekdays; work finished early is held until its assigned date.

The 2026-11-30 CLI layer is built ahead of its scheduled day. The code is
written now, but the commit stays on 2026-11-30; no earlier date carries it.

The 2026-12-03 Protocol layer is built ahead of its scheduled day. The QR
parcel frame codec code and tests are written now, but the commit stays on
2026-12-03; no earlier date carries it.

The 2026-12-04 CLI layer is built ahead of its scheduled day. The parcel encode
and decode code and tests are written now, but the commit stays on 2026-12-04;
no earlier date carries it.

The 2026-12-07 App layer is built ahead of its scheduled day. The parcel display
and camera decode code and tests are written now, but the commit stays on
2026-12-07; no earlier date carries it.

The 2026-12-09 Protocol layer is built ahead of its scheduled day. The per-drop
content keys code and tests are written now, but the commit stays on 2026-12-09;
no earlier date carries it.

The 2026-12-10 Integration layer is built ahead of its scheduled day. The
per-drop key parity code in the app and CLI and its tests are written now, but the
commit stays on 2026-12-10; no earlier date carries it.

The 2026-12-11 Protocol layer is built ahead of its scheduled day. The delivery
receipts body gate and author tally code and tests are written now, but the commit
stays on 2026-12-11; no earlier date carries it.

The 2026-12-14 Integration layer is built ahead of its scheduled day. The delivery
receipts code in the app and CLI and its tests are written now, but the commit stays
on 2026-12-14; no earlier date carries it.

The 2026-12-15 Protocol layer is built ahead of its scheduled day. The circle pad
CRDT core code and tests are written now, but the commit stays on 2026-12-15; no
earlier date carries it.

The 2026-12-16 Integration layer is built ahead of its scheduled day. The circle
pad app and CLI code and tests are written now, but the commit stays on
2026-12-16; no earlier date carries it.

The 2026-12-21 Integration layer is built ahead of its scheduled day. The three
reviewed ideas and their completions are written now, but the commit stays on
2026-12-21; no earlier date carries it.

The 2026-12-17 device layer has no commit. No physical walk was available for
this run, so the date moves under the rule below and no placeholder commit is
made. The flows are covered by the in-process rig recorded in `docs/TESTING.md`.

## Dates

| Date | Layer | Commit scope |
|---|---|---|
| 2026-11-25 | Planning | This document set and the 16 specifications |
| 2026-11-26 | Device | Three-node baseline walk and evidence note |
| 2026-11-27 | Protocol | Courier envelope and carry core, pure JVM, tests |
| 2026-11-30 | CLI | Courier carrier, tests |
| 2026-12-01 | App | Courier sync and file-card states, tests |
| 2026-12-02 | Device | Courier three-node walk and evidence note |
| 2026-12-03 | Protocol | QR parcel frame codec, pure JVM, tests |
| 2026-12-04 | CLI | Parcel encode and decode, tests |
| 2026-12-07 | App | Parcel display and camera decode, tests |
| 2026-12-08 | Device | Air-gap walk and evidence note |
| 2026-12-09 | Protocol | Per-drop content keys, pure JVM, tests |
| 2026-12-10 | Integration | Per-drop keys in app and CLI, tests |
| 2026-12-11 | Protocol | Delivery receipts, pure JVM, tests |
| 2026-12-14 | Integration | Receipts in app and CLI, device walk, note |
| 2026-12-15 | Protocol | Circle pad CRDT core, pure JVM, tests |
| 2026-12-16 | Integration | Circle pad in app and CLI, tests (specified in `circle-pad.md`) |
| 2026-12-17 | Device | Full feature walk — deferred until a physical walk is available |
| 2026-12-18 | Release | Final rig, docs consolidation, v0.3.0 |
| 2026-12-21 | Integration | Three reviewed ideas in app surfaces, tests |

## Rules for a day

- The layer is finished by every agent assigned to it before integration.
- Integration runs the full rig once. A green rig is required for the commit.
- A device layer also needs the log and observation recorded in a new file
  under `docs/completions/` named `<date>-<topic>.md`.
- A commit message names the layer and the evidence. No day carries work from
  a later date.
- If a day cannot be completed, the date moves; no placeholder commit is made.

## Historical record

Actual commit hashes and dates are added to the bottom of this file as each day
lands. Planned dates above are targets, not claims of completed work.
