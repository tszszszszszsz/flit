# Ideas program

Twenty JVM-pure prototype modules were written ahead of the release schedule.
Each one had a design note under `docs/ideas/` and a pure test class. None of
them changed the wire format, added a route, or touched Android at the time
they were written.

Each prototype was then reviewed on its own: brilliance, necessity and code
quality were scored, and each finding was checked against the current app and
protocol code. The review used three dispositions. WIRE means the module can
be integrated as-is. REVISE means the code is sound but the module has no
consumer, duplicates a wired path, or carries a defect that blocks
integration. DROP means the module solves a problem the product does not have,
or would introduce a second source of truth where one already exists.

## Dispositions

| Module | Disposition | Reason |
|---|---|---|
| TransferManifest | DROP | Signed batch attestation has no route or trust policy; signed file and receipt events already carry the same data. Cap gaps let `build` emit JSON that `fromJson` refuses. |
| Quorum | REVISE | `CircleCore.kickTally` already tallies votes over verified events. The prototype takes caller-supplied `seq` and cannot express owner or admin immunity. |
| PadSearch | WIRE | Correct, bounded, and aligned with the pad caps. Wired into the pad card. |
| PinAudit | REVISE | No pin-age producer or report surface; short hex tokens can fill the entry cap. |
| CarrierScore | REVISE | The duplicate-collapse comparator kept the worse route; it duplicates the route ranking concepts in the route explain core. |
| BatteryBudget | DROP | No consumer, and the shipped BLE duty window is already fixed and tested. The name implies an energy guarantee the arithmetic cannot make. |
| RouteExplain | REVISE | Sanitizer allows Unicode line separators, and winner matching by reference identity breaks hand-built rankings. No measured-score producer exists. |
| FileDedupe | REVISE | The received ledger has no digest field; normalization was not idempotent at the truncation boundary. |
| LedgerExport | DROP | No consumer; the digest has no origin authenticity and the live ledger is already persisted locally. |
| PanicDrill | DROP | Its separate signature domain cannot validate the production panic route; `PanicCore` and its tests already cover tampering. |
| CircleRollup | REVISE | No consumer and no verified-input gate; a forged event can attribute counts to another fingerprint. |
| ReceiptBundle | REVISE | Duplicates `CircleCore.receiptAuthors` with a weaker order: signatures are checked after winner selection. |
| QrFramePlanner | DROP | Balanced chunks conflict with the fixed-size framing that `ParcelCodec` requires; defaults do not match the encoder caps. |
| TransferJournal | DROP | Duplicates the delivery status already stored in `FlitCircleStore`; the journal has no clock, identity check or consumer. |
| PrivacyScan | REVISE | Loopback detection is lexical and incomplete; the file-safety claim is broader than the code. No caller exists. |
| A11yChecklist | REVISE | Applies text checks to decorative nodes and uses a role vocabulary that does not match Compose semantics; no metadata collector exists. |
| LocalSearch | REVISE | Ordering leaked input order for case-colliding hits; no search surface exists yet. |
| PeerAlias | WIRE | Fixed-vector tests match an independent computation. Wired into fingerprint fallback labels in the circle and activity surfaces. |
| NoiseBudget | WIRE | Exact integer ceiling check over merged duty windows, total on hostile input. Wired as an observation-only audit of the shipped BLE schedule. |
| OfflineMap | REVISE | Label sanitizer allowed C1 controls; no display or debug consumer exists. |

## Integrating the approved three

The three WIRE modules keep their pure cores unchanged. The integrations are
small and local:

- `PadSearch.search` feeds a query field on the pad card. Hits carry offsets
  into the original line text; the view is not modified and no new state is
  stored.
- `PeerAlias.of` supplies display labels where a fingerprint used to be
  truncated. A user-assigned roster alias stays authoritative, and full
  fingerprint equality is still used wherever identity is verified.
- `NoiseBudget.validate` checks the shipped 10 s on / 45 s off BLE duty
  schedule against a 55 s period. Findings are logged; the carrier arbiter
  never reads them, so carrier behavior is unchanged.

The seventeen other modules were removed from the tree. The dispositions table
above keeps the reason for each one so a future need does not recreate the same
sketch without the review.
