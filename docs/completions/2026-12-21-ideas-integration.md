# 2026-12-21 — Ideas program integration

Twenty JVM-pure prototype modules were written ahead of the release schedule,
each with a design note under `docs/ideas/` and a pure test class. None changed
the wire format, added a route or touched Android when written. Each was then
reviewed on brilliance, necessity and code quality, with the finding checked
against the current app and protocol code. The dispositions — WIRE, REVISE and
DROP — and the reason for each module are kept in `docs/ideas/PROGRAM.md`.

Three modules were integrated and seventeen were pruned from the tree: 3 WIRE,
11 REVISE and 6 DROP. The pruned modules remain documented by their disposition
rows, so a later need does not recreate the same sketch without the review.

## Integrated modules

`PadSearch` is a bounded, case-insensitive search over the lines of a supplied
`PadView`. It returns offsets into the original line text and holds no state.
`PadUi.search` projects the hits onto the pad card; the view is not modified and
no search state is stored. The pure core lives in
`protocol/src/main/kotlin/app/flit/share/protocol/ideas/padsearch/`.

`PeerAlias` derives an 11-character label from a certificate fingerprint: a
four-character stem and six suffix hex digits from a domain-tagged SHA-256
digest. `CircleUi` uses it for fingerprint fallback labels, and `MainActivity`
uses it where a full fingerprint was truncated in roster, share and log lines.
A roster-assigned alias stays authoritative, and full fingerprint equality is
still used wherever identity is verified. The core lives in
`protocol/src/main/kotlin/app/flit/share/protocol/ideas/peeralias/`.

`NoiseBudget` checks a duty schedule against a caller-supplied ceiling using
integer arithmetic over merged windows. `FlitBle.validateDutySchedule` wraps the
core, and `FlitBle.auditDutySchedule` logs its findings. The audit is called from
`FlitEngine.start`. Findings are observation only: the carrier arbiter does not
read them, and the shipped 10 s on / 45 s off duty phases are unchanged. The core
lives in `protocol/src/main/kotlin/app/flit/share/protocol/ideas/noisebudget/`.

None of the three integrations adds a route, header, JSON DTO field, QR
parameter, persisted store field or schema field. The app wiring stays in
`PadUi.kt`, `CircleUi.kt`, `MainActivity.kt`, `FlitBle.kt` and `FlitEngine.kt`.

## Rig inventory

Counts for this point are read from the JUnit XML result files. `protocol` 330,
`app` 309, `harness` 75, total 714, with 0 failures, 0 errors and 0 skipped. All
assemblies are green. Earlier inventory figures in `docs/TESTING.md` are
snapshots of earlier layers.

## Tests

| Class | Cases | Scope |
| --- | --- | --- |
| `PadSearchTest` (new, protocol) | 17 | Case matching, UTF-16 offsets into original text, hit order, query gate, each cap, determinism and hostile input |
| `PeerAliasTest` (new, protocol) | 12 | Fixed vectors, input bounds, stem and suffix derivation, `isValid` / `matches`, determinism and hostile input |
| `NoiseBudgetTest` (new, protocol) | 18 | Union of overlapping windows, integer ceiling check, structural findings, precedence, determinism and hostile input |
| `PadUiTest` (existing, app) | 15 total, of which 7 are search cases | Pad row derivation and field bounds plus the query-to-hit projection, including refused and capped queries |
| `FlitBleTest` (existing, app) | 36 total, of which 6 are budget cases | BLE record codec, arbiter and duty constants plus the audit wrapper over the schedule report |

Case counts are read from the JUnit XML; the two app classes are existing suites
with added cases, not new classes.

## Evidence

| Label | Basis |
| --- | --- |
| CODE | The three cores and their app wiring match the seams described above and in `docs/ideas/PROGRAM.md`; no wire or store surface changed. |
| PURE | All five test classes run in the JVM rig; no radio, camera or physical-device path is exercised. |

No DEVICE evidence is recorded. The 2026-12-17 walk remains deferred, with rig
evidence only.

## Limitations

- The three cores are pure functions of their inputs. `PadSearch` does not
  verify signatures and searches any view it is handed; its offsets are UTF-16
  code units, not code-point or grapheme indices.
- `PeerAlias` is a display label, not an authentication token. Its collision
  bound is 24 bits plus a four-hex stem, and it does not resolve collisions or
  look up a peer.
- `NoiseBudget` performs arithmetic over supplied numbers. It makes no radio
  measurement, does not schedule a scan or announce, and takes no regulatory
  ceiling from the platform.
- The seventeen pruned modules are absent from the tree. Their reasons stay in
  `docs/ideas/PROGRAM.md`; no pruned module is carried as dead code.
- No device walk, timing figure or hardware result is attached to this record.
