# 2027-01-05 — CLI parity and rig hardening (Integration)

Layer: 2027-01-05 Integration. The work closes three CLI-side parity gaps, fixes
one relay stream-read gap and hardens the QR reader used by the parcel harness.
The CLI gates and the relay reply reader use production seams already in the
tree. This layer adds no route, header, JSON DTO field, QR parameter, persisted
store field, schema field, signature primitive or v2.2 status. The 2026-11-26
three-identity walk remains the only on-device Circle observation and is not
extended here.

Counts are read from the JUnit XML under `protocol/build/test-results/test/`,
`app/build/test-results/testDebugUnitTest/` and
`harness/build/test-results/test/`. At this layer the green run recorded 362 protocol +
324 app + 92 harness = 778 cases with zero failures, zero errors and zero
skipped (historical). The current inventory is 972 (443 + 167 + 254 + 108). Debug and release assemblies and the CLI distribution were green in
the same rig run. The 773 figure (362 + 324 + 87) is the historical inventory
before this layer.

## Files in the layer

| File | Content |
| --- | --- |
| `harness/src/main/kotlin/app/flit/share/harness/Harness.kt` | CLI seams exercised by the added cases: `circleFreshEvents` as the single fold gate over a pre-answer role snapshot, `circleLatestRekey` vetoing a REKEY signed by a same-batch leaver, `circleExpectedHostFp` resolving an explicit `--host-fp` against the learned pin, and `circle join` resolving that pin before `st.circleId` is rewritten |
| `protocol/src/main/kotlin/app/flit/share/protocol/relay/RelayEndpoints.kt` | `readReply` reads the relay reply one byte at a time up to the newline under the hello bound, leaving bytes written after `OK\n` on the raw stream for the TLS layer |
| `harness/src/main/kotlin/app/flit/share/harness/ParcelCli.kt` | Production PNG render and read seam; the 768/1024/1536 escalation in `renderFrames` is already recorded in `docs/TESTING.md` §8 and is not restated here as a new change |
| `harness/src/test/kotlin/app/flit/share/harness/CircleHarnessTest.kt` | Four added cases over production routes and CLI seams; 18 cases in the final XML |
| `harness/src/test/kotlin/app/flit/share/harness/RelayServerTest.kt` | One added case over a fake relay peer that writes the OK line and a TLS-record prefix together; 6 cases in the final XML |
| `harness/src/test/kotlin/app/flit/share/harness/ParcelCliHarnessTest.kt` | Hardened PNG read helper; unchanged at 3 cases |
| `docs/TESTING.md` | Inventory at this layer was 778 (historical) with 773 kept as a historical snapshot, the five added case descriptions, the QR reader note and the evidence-label wording |
| `.github/workflows/rig.yml` | Workflow comment line 2 updated to 362 protocol + 324 app + 92 harness tests |
| `docs/completions/2027-01-05-cli-parity.md` | This layer record |

## Rules held

- No new route, header, JSON DTO field, QR parameter, persisted store field,
  schema field, signature primitive or v2.2 status.
- The CLI fold, pin and relay read paths call the existing protocol and harness
  seams; no lookalike core is introduced and no gate is duplicated.
- QR frames remain opaque strings. The PNG render escalation changes pixel size
  only and never rewrites a frame string.
- A stored host pin belongs to the circle in which it was learned. A different
  circle needs a fresh `--host-fp`; a conflicting value for the same circle is
  refused.
- No device walk, camera capture, radio or second-phone observation is claimed.

## Added cases

- CLI `circleFreshEvents` gate: in a host-signed answer, a JOIN and a member
  REKEY are dropped while an ordinary verified event folds (the certificate /
  author mismatch case from the earlier layer stays separate).
- Departed-officer rekey ignored: a same-batch LEAVE vetoes that officer's
  generation-99 hand-off; it stays in the signed log as inert history while the
  host's generation-2 rotation installs the live CLI key.
- Stale `--host-fp` guard: a pin learned in one circle does not vouch for a join
  to another circle, a conflicting pin for the same circle is refused, and a
  join resolves the expected host before rewriting the circle id so the stale
  pin is cleared from the persisted state.
- Relay reply byte-wise read: a fake relay writes `OK\n` and a plausible TLS
  record prefix in one packet; after the bounded newline read, every following
  byte is still readable on the paired stream instead of lost to read-ahead.
- QR test decoder hardening: the parcel harness PNG reader supplies
  `TRY_HARDER` and QR-only decode hints and retries integer scales 1..6 before
  treating a frame as unreadable. This changes no case count;
  `ParcelCliTest` stays at 11 and `ParcelCliHarnessTest` at 3.

## Test counts

| Module | Tests | Task | Change at this layer |
| --- | --- | --- | --- |
| `protocol/` | 362 | `:protocol:test` | Unchanged from 773 |
| `app/` | 324 | `:app:testDebugUnitTest` | Unchanged from 773; `FlitQrTest` stays at 22 |
| `harness/` | 92 | `:harness:test` | +5: `CircleHarnessTest` 14 → 18, `RelayServerTest` 5 → 6 |
| Total | 778 | — | 0 failures, 0 errors, 0 skipped |

The other harness classes keep their XML counts, including `ParcelCliTest` at
11 and `ParcelCliHarnessTest` at 3. The app and protocol suites were not
extended by this layer.

## Evidence labels

Labels follow the weakest-level rule in `docs/TESTING.md` §1.

- CODE — source-level facts: the CLI gates in `Harness.kt`, the bounded
  byte-wise reply read in `RelayEndpoints.kt`, and the parcel render seam in
  `ParcelCli.kt`. A source fact alone is not a runtime result.
- PURE — JVM-only assertions. The unchanged protocol (362) and app (324)
  suites remain JVM-pure; no new PURE case is added by this layer.
- SIM-HARN — in-process runs over the production server, client and relay on
  one box: the five added cases and the parcel PNG round-trips. No hardware,
  camera or second phone is involved.
- DEVICE — no claim. No phone, second phone, radio, carrier or camera walk is
  recorded for this layer; the QR frames are decoded from generated PNGs by a
  JVM reader, not by a phone camera.
