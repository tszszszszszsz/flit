# Agent waves

Every wave uses 16 agents on the same model and with the same effort as the
orchestrator: `opencode/hy4-flash-free#high`. All agents in a wave work on one
layer. Files are disjoint. No agent commits.

Build-ahead: implementation may run ahead of the wave dates below. The dates
govern commits, not when work happens. Commits stay spoon-sized and land on the
scheduled weekdays; finished work is held until its wave date.

The 2026-11-30 CLI wave is built ahead. Implementation runs now, but the commit
stays on 2026-11-30; no earlier wave carries it.

The 2026-12-03 Protocol wave is built ahead. QR parcel frame codec
implementation runs now, but the commit stays on 2026-12-03; no earlier wave
carries it.

The 2026-12-04 CLI wave is built ahead. Parcel encode and decode implementation
runs now, but the commit stays on 2026-12-04; no earlier wave carries it.

The 2026-12-07 App wave is built ahead. Parcel display and camera decode
implementation runs now, but the commit stays on 2026-12-07; no earlier wave
carries it.

The 2026-12-09 Protocol wave is built ahead. Per-drop content keys implementation
runs now, but the commit stays on 2026-12-09; no earlier wave carries it.

The 2026-12-10 Integration wave is built ahead. Per-drop key parity implementation
in the app and CLI runs now, but the commit stays on 2026-12-10; no earlier wave
carries it.

The 2026-12-11 Protocol wave is built ahead. Delivery receipts implementation runs
now, but the commit stays on 2026-12-11; no earlier wave carries it.

The 2026-12-14 Integration wave is built ahead. Delivery receipts implementation in
the app and CLI runs now, but the commit stays on 2026-12-14; no earlier wave carries
it.

The 2026-12-15 Protocol wave is built ahead. Circle pad CRDT core implementation runs
now, but the commit stays on 2026-12-15; no earlier wave carries it.

The 2026-12-16 Integration wave is built ahead. Circle pad app and CLI implementation
runs now, but the commit stays on 2026-12-16; no earlier wave carries it.

The 2026-12-21 Integration wave is built ahead. Three reviewed ideas are integrated
now, but the commit stays on 2026-12-21; no earlier wave carries it.

The 2026-12-22 through 2026-12-28 waves are built ahead. The JOIN proof, Circle
authority gate, replay guard, CLI parity and documentation are written now, but
the commits stay on their assigned dates.

## Wave 0 — planning (2026-11-25)

Each agent writes one specification under `docs/roadmap/`. The orchestrator
owns the index, schedule, wave assignments and quality gates. The wave is
integrated as one commit after all 16 files are present and cross-checked.

## Implementation waves

| Date | Layer | Agent ownership |
|---|---|---|
| 2026-11-26 | Device | 16 agents on the three-node walk: execution, log capture and the evidence note |
| 2026-11-27 | Protocol | 16 agents split across envelope codec, crypto wrap, carry quota, TTL, replay guard, event shapes, pure tests and the completion note |
| 2026-11-30 | CLI | 16 agents split across carrier loop, flag parsing, identity home, status output, harness tests and the completion note |
| 2026-12-01 | App | 16 agents split across sync worker, store adapters, file-card states, permissions, pure tests and the completion note |
| 2026-12-02 | Device | 16 agents on courier carrier and recipient logs and the evidence note |
| 2026-12-03 | Protocol | 16 agents split across frame format, sequencing, CRC, size caps, reassembly, tests and the completion note |
| 2026-12-04 | CLI | 16 agents split across encode, decode, image input, verification output, tests and the completion note |
| 2026-12-07 | App | 16 agents split across frame display, camera intake, progress states, accessibility, tests and the completion note |
| 2026-12-08 | Device | 16 agents on air-gap frame captures and the evidence note |
| 2026-12-09 | Protocol | 16 agents split across key generation, wrapping to member certs, unwrap paths, leave behavior, tests and the completion note |
| 2026-12-10 | Integration | 16 agents split across app store, CLI parity, migration of old drops, tests and the completion note |
| 2026-12-11 | Protocol | 16 agents split across receipt shape, canonical bytes, signing, verification, tests and the completion note |
| 2026-12-14 | Integration | 16 agents split across app receipt UI, CLI export, device walk, tests and the completion note |
| 2026-12-15 | Protocol | 16 agents split across CRDT state, merge function, bounds, event shapes, tests and the completion note |
| 2026-12-16 | Integration | 16 agents split across app pad UI, CLI pad commands, sync wiring, tests and the completion note |
| 2026-12-17 | Device | Full feature walk deferred — no physical walk was available, so no wave ran and no evidence note was added |
| 2026-12-18 | Release | Rig runners, docs consolidators, release notes, tag and asset checks |
| 2026-12-21 | Integration | Eight tasks: three approved ideas across pad UI, circle UI, activity labels, BLE audit, tests and two read-only reviews |
| 2026-12-22 | Protocol | 16 tasks across the JOIN proof core, Circle authority gate, replay caller, route gate and pure tests |
| 2026-12-23 | Integration | 16 tasks across app sync/store/host, CLI host and commands, courier and harness tests |
| 2026-12-24 | Docs | 16 tasks across security disclosure, contracts, failure modes, test inventory and completion notes |
| 2026-12-28 | Release | 16 tasks across release notes, tag checks and final rig record |

## Integration gate

A wave ends when all 16 agents have reported and their files are present. The
orchestrator then resolves overlaps, compiles, runs the full rig and, for a
device layer, reviews the log. Only then is one commit made. An agent that
finishes early does not trigger a partial merge.
