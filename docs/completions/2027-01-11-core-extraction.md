# 2027-01-11 — Core extraction

Layer: 2027-01-11 Extraction. This wave adds a JVM-only `:core` module to the
build and moves ten Android-free source/test pairs out of `:app` into it. The
move is a relocation of existing code together with its tests, not a change in
behaviour: nine production classes carry their unit-test classes with them,
`FlitCrush` moves without a test because none existed in the app module, the
`app.flit.share` package is kept, and no case is rewritten, merged or retired.
No route, header, JSON DTO field, QR parameter, persisted store field, schema
field, signature primitive or status value changes at this layer. The protocol
and harness modules are untouched, and the Android shell keeps only code that
needs Android.

The nine test classes, with their case counts, are `FlitBle` 36, `FlitQr` 22,
`ParcelScanState` 18, `FlitSubnet` 8, `FlitRoute` 7, `FlitMirror` 5,
`FlitSpool` 5, `FlitSpeed` 4, `FlitSpeedEdge` 4 and `SheetUi` 3. `FlitSpeed`
has two test classes; `FlitCrush` has none. They sum to 112 cases. `FlitBle` covers the BLE record codec, the arbiter,
the rank rule, the contextual ask book and the duty-cycle constants;
`FlitQr` covers the v2/v3 URI round-trips, order and duplicate resolution,
junk refusal and the frame shapes; `ParcelScanState` covers the Android-free
frame-assembly reducer; `FlitSubnet` covers the host walks and prefix bounds;
`FlitRoute` covers route decoding, candidate priority and the hunt backoff;
`FlitMirror` covers the diff offer and ledger rules; `FlitSpool` covers the
sweep, queue whitelist and crush-litter rules; `FlitSpeed` and `FlitSpeedEdge`
cover the rate samples and their boundary cases; `SheetUi` covers the initials,
hue and count-phrasing helpers. Every one of these was already JVM-pure, which
is why the cases could leave the Android module without a rewrite. The package
name is retained, so the relocation does not change the imports that name
these classes.

No test was dropped. Each of the ten test classes arrives in `core/src/test`
with its original cases, and each of the ten production classes arrives in
`core/src/main`, so the inventory is preserved exactly: the 112 cases formerly
counted in the app module are now counted in the core module. Nothing is
deleted from the test set, no case is skipped or disabled, and no replacement
test is introduced to stand in for a moved one. The remaining app suite is the
previous app count minus these 112 cases; the protocol and harness suites keep
their classes and their counts.

The historical inventory is 362 protocol + 324 app + 92 harness = 778 cases.
After the relocation the projected split is 362 protocol + 112 core + 212 app
+ 92 harness = 778. The total is unchanged because this is a move between
modules rather than an addition or removal of cases; only the module column
changes. `docs/TESTING.md` §1/§2 now records the four-module green XML 443 + 167 +
254 + 108 = 972; the pre-move 778 split is historical. The figures above were
projections from the file inventory at this layer.

The 112 core and 212 app figures remain source-level projections for this
extraction layer. The full rig result is recorded in `docs/TESTING.md` §1/§2 and
`docs/completions/2027-01-14-release-032.md`: the four-module XML records
443 + 167 + 254 + 108 = 972 cases with zero failures, errors and skipped cases.

Evidence labels follow the weakest-level rule in `docs/TESTING.md` §1.

- CODE — source-level facts: the new JVM module in the build, the ten source
  and test pairs under `core/src/main` and `core/src/test`, the unchanged
  `app.flit.share` package, and the absence of the ten classes from the app
  tree. A source fact alone is not a runtime result.
- PURE — JVM-only assertions: the 112 moved cases stay JVM-pure in their new
  module, and no Android runtime, emulator, camera or radio enters them. The
  protocol and harness suites keep their existing JVM-only and in-process
  character.

There is no DEVICE label for this layer: no phone, second phone, radio,
carrier or camera walk is recorded, and the 2026-11-26 three-identity
observation stays the only on-device Circle record and is not extended here.
This wave changes only the JVM module boundary and makes no runtime claim
about any client surface.
