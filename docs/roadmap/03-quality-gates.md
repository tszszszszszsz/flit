# Quality gates

A day's layer is accepted only when every rule below holds.

## Completeness

- One layer, one commit. No partial merge, no deferred stub, no placeholder.
- Every task in the wave has reported; all owned files are present. The
  scheduled waves use 16 agents; the 2026-12-21 review wave used eight tasks.
- The completion note under `docs/completions/` exists for that date.
- Anything not built is listed as out of scope in that day's specification.

## Code and tests

- Full rig is green: `protocol:test`, `core:test`, `app:testDebugUnitTest`, `harness:test`,
  `assembleDebug`, `assembleRelease`, `installDist`, `distZip`.
- New behavior has pure tests first. Harness tests cover production endpoints.
  Device claims require a log and an observation; a passing test is not a
  device walk.
- Evidence labels are CODE, PURE, SIM-HARN and DEVICE. Where several apply,
  the weakest governs what may be asserted.
- No existing v2.2 route, DTO shape or status code changes. New behavior is
  additive.

## Re-baseline

When a test-bearing seam moves into `:core`, the rig task list and the module
count tables are updated in the same commit as the move. A relocation changes
where a case runs, not what it covers: every moved test keeps its assertions
and its evidence level, and no case is dropped to make a per-module figure
read cleanly. The rig task list and the counts are one change, not a follow-up
to the source move.

A new module count is a result only with green JUnit XML. The extended-rig XML
exists and records `:core` at 167 cases within 972 (443 + 167 + 254 + 108),
zero failures, errors and skipped cases; the 112 figure is historical. The three-module 778 (362 + 324 + 92) and the projected four-module 778
(362 + 112 + 212 + 92) are historical; the current inventory is 972. A move
between modules does not by itself discard or reassign a baseline.

## Failure stories

Every new path states: what retries, what concludes, what keeps bytes, what
drops them, and the on-disk state that survives process death. Silent fallback
is not acceptable; a refusal or a limit is logged or shown.

## Documentation

- Plain engineering prose. No decision attribution, self-reference, slogans
  or defensive qualifiers.
- Constants, dates, counts and file references match the code.
- Cross-file links point to real headings. The link check is part of the day.
- Two-phone RFCOMM, live multi-member mesh, Wi-Fi Aware and ROM battery rules
  remain labelled as unverified or out of scope until walked.

## Release

`v0.3.0` was tagged after the final rig on 2026-12-18; the tag followed the rig
only. The full feature walk planned for 2026-12-17/2026-12-18 was deferred. The
release notes describe what was walked and what was not.

## Security wave (2027-01-12 to 2027-01-14)

The three security-wave days use the same gates as every other layer: one
commit per day, the full rig green before the commit, and a completion note under
`docs/completions/` for that date. All three layers are built ahead. Work
written early is held until its own weekday; no day carries work from a later
date and no completion note is backdated.

The last recorded full test run passed 972 tests with zero failures, errors
and skipped cases. The release assembly completed green after the R8 keep-rule
fix in `app/proguard-rules.pro`; assembly results are not part of the JUnit XML.
The four test tasks plus both assemblies passed in one full rig run after that
fix. The 778 floor stays historical at the 362 protocol + 112 core + 212 app +
92 harness projection; the 972 figure is the current count from green XML.

The 2027-01-12 protocol day carries PURE cases for the prepare certificate and
body signature, the sender-side TLS peer pin and the replay retention decisions.
The in-process pipe runs over production endpoints are SIM-HARN cases. No day in
this wave produces DEVICE evidence: there is no second phone, radio path or
camera walk. The dependency advisory scan and the dedicated hostile-input
campaign remain open, so the release consolidation does not close those
findings.
