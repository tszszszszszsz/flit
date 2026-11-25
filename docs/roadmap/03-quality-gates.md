# Quality gates

A day's layer is accepted only when every rule below holds.

## Completeness

- One layer, one commit. No partial merge, no deferred stub, no placeholder.
- All 16 agents in the wave have reported; all owned files are present.
- The completion note under `docs/completions/` exists for that date.
- Anything not built is listed as out of scope in that day's specification.

## Code and tests

- Full rig is green: `protocol:test`, `app:testDebugUnitTest`, `harness:test`,
  `assembleDebug`, `assembleRelease`, `installDist`, `distZip`.
- New behavior has pure tests first. Harness tests cover production endpoints.
  Device claims require a log and an observation; a passing test is not a
  device walk.
- Evidence labels are CODE, PURE, SIM-HARN and DEVICE. Where several apply,
  the weakest governs what may be asserted.
- No existing v2.2 route, DTO shape or status code changes. New behavior is
  additive.

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

`v0.3.0` is tagged only after the final rig and the full feature walk on
2026-12-18. The release notes describe what was walked and what was not.
