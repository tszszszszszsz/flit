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

A new module count is a result only once green JUnit XML exists for the
extended rig, with zero failures, zero errors and zero skipped cases. Until
that XML exists, every `:core` figure is a projection and no green result is
claimed for the relocated tests. The 778 figure (362 protocol + 324 app +
92 harness) stays historical until a new green inventory replaces it; a move
between modules does not by itself discard or reassign that baseline.

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

The last recorded full test run passed 972 tests. That figure is a recorded
historical run. The release assembly completed green
after the R8 keep-rule fix in `app/proguard-rules.pro`, and its JUnit XML has not
been recorded. No green rig is claimed for the 2027-01-14 consolidation until
that result is known with zero failures, zero errors and zero skipped cases.
The 778 floor stays historical at the 362 protocol + 112 core + 212 app +
92 harness projection until green XML for the extended rig replaces it; the
972 figure is not a re-baseline of that floor.

The 2027-01-12 protocol day carries PURE cases for the prepare certificate and
body signature, the sender-side TLS peer pin and the replay retention decisions.
The in-process pipe runs over production endpoints are SIM-HARN cases. No day in
this wave produces DEVICE evidence: there is no second phone, radio path or
camera walk. The dependency advisory scan and the dedicated hostile-input
campaign remain open, so the release consolidation does not close those
findings.
