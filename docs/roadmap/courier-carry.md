# Courier carry core

A bounded, JVM-only bag that holds signed courier parcels for absent members and
hands them to the addressee on a later encounter.

## Date and layer

2026-11-27 — Protocol (courier envelope + carry core, pure JVM, tests).

## Depends on

None from an earlier date. The `parcel` event kind, its closed body keys and the
envelope bytes are defined by the same-day courier-envelope specification; this
layer consumes them unchanged. The same-date coupling is an integration seam,
not a later-date dependency, so both pieces land in the one 2026-11-27 commit.

## Scope

`CourierCarryCore` is the decision layer for a carrier: a Circle member that
meets other members and holds parcels for those it cannot currently reach. It
accepts signed `parcel` events into a bounded bag, selects the parcels addressed
to a peer it meets, removes parcels the peer acknowledges, drops expired ones,
and merges two carriers' bags deterministically.

Parcels travel as `parcel` events in `CircleRequest.events` over the existing
`/circle/sync` route. No new route, no `LinkProvider` and no DTO change is added.
The core treats the envelope inside a parcel as opaque bytes; encryption, the
per-recipient wrap and the envelope's own canonical bytes belong to the
courier-envelope specification. File bytes never enter a parcel.

## Design

### File and package

`protocol/src/main/kotlin/app/flit/share/protocol/courier/CourierCarryCore.kt`,
package `app.flit.share.protocol.courier`. JVM-pure: imports `CircleCore`,
`CircleEvent` and `WireJson` only. No Android, no sockets, no delegate calls and
no clock read inside a reducer — every time-dependent rule takes `nowMs`.

### Event kind and body

`PARCEL = "parcel"` is added to `CircleEvent.TYPES` as a tenth verb. This is
additive: `CircleCore.validShape` is type-agnostic bounds, so no existing
event's signature or acceptance changes.

`PARCEL_BODY_KEYS = setOf("toFp", "envelope")`. The body is exactly
`{"toFp":"<UPPERCASE fingerprint>","envelope":"<Base64 envelope bytes>"}`.
`validParcelBody` refuses any extra key (`token`, `path`, `spoolPath`, …), a
blank `toFp`, a `toFp` longer than `CircleCore.MAX_FIELD_CHARS`, and a blank or
oversized `envelope`, the same closed-key discipline `validFileBody` applies to
`file`.

### Constants

| Constant | Value | Source / reason |
| --- | --- | --- |
| `PARCEL` | `"parcel"` | Tenth Circle event verb. |
| `PARCEL_BODY_KEYS` | `{"toFp","envelope"}` | Closed body key set. |
| `MAX_ENVELOPE_CHARS` | `12 * 1024` | Base64 envelope cap; body stays under `CircleCore.MAX_BODY_CHARS` (16 KiB) after JSON framing. |
| `MAX_PARCELS` | `256` | Bounded carrier bag. |
| `MAX_SEEN` | `4096` | Bounded id reservation, matching `CircleCore.MAX_SEEN`. |
| Courier replay window (`MAX_PARCEL_TTL_MS`) | `24 * 60 * 60 * 1000L` | `evaluate` uses the 24 h parcel TTL as its bounded replay window, not `CircleCore.REPLAY_WINDOW_MS` (6 h). |
| `CLOCK_SKEW_MS` | `5 * 60 * 1000L` | Reused from `CircleCore`. |
| `MAX_PARCEL_TTL_MS` | `24 * 60 * 60 * 1000L` | Ceiling for an undelivered parcel. |
| `MAX_FIELD_CHARS` | `256` | Reused from `CircleCore` for `toFp`. |

`expiresMs = createdMs + MAX_PARCEL_TTL_MS`, derived once in `parcelOf`. A
hand-edited expiry outside `[createdMs, createdMs + MAX_PARCEL_TTL_MS]` is
Invalid.

### Types

```kotlin
data class CourierParcel(
    val id: String,          // the signed parcel event's id
    val toFp: String,        // normalised addressee fingerprint
    val fromFp: String,      // event.authorFp, the signer
    val createdMs: Long,     // event.createdMs
    val expiresMs: Long,     // createdMs + MAX_PARCEL_TTL_MS
    val event: CircleEvent,  // signed event; envelope bytes live in its body
)

data class CarryState(val parcels: List<CourierParcel> = emptyList())
```

`encode(state): String` and `decode(raw: String?): CarryState?` use `WireJson`.
Decode is total and normalising: a null, blank, truncated, wrongly typed or
otherwise corrupt blob decodes to an empty state, never an exception. A parsed
blob keeps the first row for a duplicate id; it drops rows with a blank or
oversized id or `toFp` and rows with a non-positive or overflowing `createdMs`;
it repairs a numeric expiry that disagrees with `createdMs +
MAX_PARCEL_TTL_MS`; and it trims to the latest `MAX_PARCELS` rows by `order`.
Decode has no clock, so wall-clock expiry is applied by `expire(state, nowMs)`
and by `select`, not by decode.

### Decision and precedence

```kotlin
sealed interface Decision {
    data object Accept : Decision       // id reserved; parcel may enter the bag
    data object Duplicate : Decision    // id already reserved
    data object Expired : Decision      // nowMs >= expiresMs
    data object OutsideWindow : Decision
    data object Invalid : Decision      // shape or body failed
    data object Full : Decision         // bag at MAX_PARCELS
}
```

Exact precedence for `evaluate`: `Invalid → Expired → OutsideWindow →
Duplicate → Full → Accept`. Expiry is checked before the window so a
TTL-elapsed parcel is reported as Expired; the courier replay window is the
same bounded 24 h span as the TTL. Every non-Accept decision leaves the
bounded `seen` set untouched, so a parcel refused as Full can be offered again
once space frees. The window compares two positive timestamps by subtraction,
never `nowMs ± window`, so an extreme clock cannot overflow into a false Accept.
Callers verify the event with `CircleCore.verify` before calling `evaluate`;
reserving an id before crypto would burn it for a forgery.

### API

- `validParcelBody(body: String): Boolean` — closed-key structural gate.
- `parcelOf(event: CircleEvent): CourierParcel?` — null unless `type == PARCEL`,
  `CircleCore.validShape`, `validParcelBody`, non-blank `cert` and a derived
  expiry inside the TTL bound; `toFp` is uppercased.
- `canonical(p: CourierParcel): ByteArray` — domain tag `FLITCARRY1`, then
  length-prefixed UTF-8 `id`, `toFp`, `fromFp`, `writeLong(createdMs)`,
  `writeLong(expiresMs)`, then the envelope string, with `event.sig` and
  `event.cert` excluded. Used for carry-state digests and later receipts; it is
  not a second envelope signature.
- `order(parcels: Iterable<CourierParcel>): List<CourierParcel>` — deterministic
  `(createdMs, fromFp, id, …event fields)` by delegating to `CircleCore.order`
  on the underlying events.
- `evaluate(parcel: CourierParcel, bagSize: Int, nowMs: Long): Decision` —
  atomic check-and-record over the shared bounded `seen` set.
- `fresh(id: String, nowMs: Long): Boolean` — id-only reservation on the same
  set, for caller-owned ids with no signed event yet.
- `fold(state: CarryState, parcel: CourierParcel): CarryState` — append when the
  id is absent, then trim to `MAX_PARCELS` by `order`, not by arrival order.
- `select(state: CarryState, toFp: String, nowMs: Long, limit: Int = MAX_PARCELS):
  List<CourierParcel>` — parcels addressed to the normalised `toFp` with
  `nowMs < expiresMs`, deterministically ordered and capped.
- `ack(state: CarryState, ids: Collection<String>): CarryState` — remove by id;
  unknown ids are inert.
- `expire(state: CarryState, nowMs: Long): CarryState` — drop parcels with
  `nowMs >= expiresMs`; the rest are unchanged.
- `merge(a: CarryState, b: CarryState, nowMs: Long): CarryState` — deterministic,
  bounded union: dedup by id through `CircleCore.dedup` on the events, drop
  expired, trim to `MAX_PARCELS` by `order`. Idempotent and commutative.
- `clear()` — test hook that forgets every reserved id.

### State ownership

The core owns only the in-process bounded `seen` set, as `CircleCore` owns its
replay set. The bag is durable state owned by the caller:

- App: an additive `courierBag: String = ""` field on `CircleState`, holding the
  encoded `CarryState`. Writes go through `FlitCircleStore.update`, so a bag
  write cannot interleave with a sync `upsert` or clobber the event log.
- CLI: `harnessHome()/courier-bag/<id>.parcel` holding the encoded parcel bytes,
  with local row state in `harnessHome()/courier.state` beside `circle.state`.

No carrier, dial loop or timer lives in the protocol module. The app sync worker
and the CLI carrier pass the current bag into each reducer and persist the
returned state at their own layer.

### Wire path

A carrier offers each held parcel as a `parcel` event inside `CircleRequest.events`
on `/circle/sync`, through `FlitCircleSync.fanout` and the calm repair pass. A
peer folds accepted events into its own bag with `fold` after `evaluate`; the
addressee verifies and opens the envelope with the courier-envelope rules. The
route returns a normal signed `CircleResponse`; no new header, route or
`CircleRequest` field is introduced.

An OK answer on the sync route means the peer accepted the offered parcel; it
is not proof that the envelope was opened or that bytes landed, and no remote
receipt is inferred by the core.

## Invariants

1. Every accepted parcel carries a signed `parcel` event accepted by
   `CircleCore.verify`, with its cert bound to `authorFp` by the cert's own
   SHA-256 DER.
2. A parcel body's key set is exactly `{"toFp","envelope"}`; an unknown key is
   refused before any id is reserved.
3. `evaluate` follows `Invalid → Expired → OutsideWindow → Duplicate → Full →
   Accept` and only Accept reserves an id.
4. `seen` is bounded at 4096 and is shared by `evaluate` and `fresh`.
5. `expiresMs` is exactly `createdMs + MAX_PARCEL_TTL_MS`; a parcel with
   `nowMs >= expiresMs` is never selected.
6. `order`, `fold` and `merge` are arrival-order independent: two carriers
   holding the same parcels converge on the same bag.
7. Bag size never exceeds `MAX_PARCELS`; trimming drops the earliest parcels by
   `order`.
8. `select` returns only parcels addressed to the requested normalised `toFp`;
   the core never reads, decrypts or rewrites envelope bytes.
9. `ack` and `expire` create no records; unknown ids and future-only parcels are
   inert.
10. Every entry point is total: malformed bodies, corrupt persisted rows and
    extreme clocks produce a decision or a bounded state, never a throw.
11. `CourierCarryCore` has no Android import, socket, clock read or v2.2 wire
    change; existing Circle event signatures are untouched.

## Failure modes

| Condition | Conclusion | Keeps bytes | Drops | Evidence |
| --- | --- | --- | --- | --- |
| Wrong type, bad body keys, blank/oversized `toFp` or `envelope` | Invalid; no id reserved | Nothing | Incoming event | `PURE` reject; carrier log `courier: invalid parcel body` |
| Signature fails or cert fingerprint does not match `authorFp` | Refused before `evaluate` | Existing bag unchanged | Incoming event | `PURE` forged-author reject; `courier: parcel signature refused` |
| `createdMs` older than the 24 h courier replay window or more than 5 min ahead | OutsideWindow | Existing bag unchanged | Incoming event | `PURE` window reject; `courier: parcel outside window` |
| `nowMs >= expiresMs` | Expired | Existing bag unchanged | Incoming or held parcel | `PURE` expiry test; `courier: parcel expired` |
| Id already reserved or already in the bag | Duplicate | Held copy stays | Incoming copy | `PURE` dedup test; `courier: duplicate parcel id` |
| Bag at `MAX_PARCELS` | Full; no id reserved | All 256 held parcels | Incoming offer | `PURE` cap test; `courier: bag full, 256 parcel(s) held` |
| Addressee not met; no route to `toFp` | Pending | Parcel stays in the bag for a later encounter | Nothing | `SIM-HARN` carrier test; `courier: holding for <fp>` |
| Peer accepted the offer (OK answer) | Offer accepted; `ack` removes the held row; no end-to-end proof | Nothing | Acknowledged parcels after `ack` | `SIM-HARN` handoff test; `courier: handed off N parcel(s) to <fp>` |
| Carrier restarts before an ack | Re-offer | Persisted bag and signed event survive | Nothing until addressee dedups by id | `SIM-HARN` restart test; `courier: re-offered, recipient deduped` |
| Corrupt `courier-bag/` or `courierBag` row | Empty bounded state | Unrelated Circle state | Corrupt row | `PURE` tolerant-decode test; `courier: bag row unreadable, starting empty` |

## Tests

Pure tests first, in `protocol/src/test/kotlin/app/flit/share/protocol/courier/CourierCarryCoreTest.kt`
(`PURE`, `:protocol:test`):

- `validParcelBody` accepts exactly `{"toFp","envelope"}`; an extra key, a
  string `size`-style non-string field, a blank `toFp`, and an envelope over
  `MAX_ENVELOPE_CHARS` are refused.
- `parcelOf` derives id, `toFp`, `fromFp`, `createdMs` and `expiresMs`; a wrong
  type, a blank `cert` or an out-of-bound expiry yields null; `toFp` is
  uppercased.
- `canonical` is deterministic and a delimiter-like value inside `toFp` or
  `envelope` cannot collide with a field boundary.
- `evaluate` pins the exact six-way precedence; only Accept reserves an id; a
  Full refusal reserves nothing and succeeds after `fold` frees space; extreme
  clock values cannot overflow the window.
- A 4097th distinct id evicts the oldest reservation; `fresh` and `evaluate`
  share the same set.
- `fold` dedups by id and trims to `MAX_PARCELS` by `order`, so reversing arrival
  order gives the same bag.
- `select` returns only the requested addressee's live parcels, in deterministic
  order, capped at `limit`; an expired parcel is never returned.
- `ack` and `expire` are inert on unknown ids and leave other parcels byte-equal.
- `merge(a,b)` equals `merge(b,a)` and is idempotent; two carriers converge on
  the same bounded bag.
- `encode`/`decode` round-trip; a corrupt blob, duplicate ids, blank fields,
  over-cap lists and expired rows decode to a bounded state.

Harness tests at the 2026-11-30 CLI layer (`SIM-HARN`,
`:harness:test`), using the real `/circle/sync` route and two distinct
`FLIT_HARNESS_HOME` values:

- A CLI carrier accepts a parcel from bot A, meets bot B on a later pass and
  hands the parcel off; B's addressee folds it once and the ack removes it from
  the carrier's bag.
- Two carriers with different parcels merge their bags and then select the same
  handoff sets for a given addressee.
- Restarting a carrier before the ack re-offers the parcel; the addressee's
  replay guard reports Duplicate and shows one parcel.

Device walk at the 2026-12-02 layer (`DEVICE`, evidence note only):

- Three logical nodes on the box and phone: sender posts a parcel for an absent
  member, a carrier holds it across a break in contact, then hands it to the
  addressee on a later encounter. Record the `courier:` log lines and the
  folded event; label the observation `DEVICE` only when the walk is run.

## Evidence for this day

A new `docs/completions/2026-11-27-courier-core.md` records the fresh pure-test
run (`source env.sh && ./gradlew --no-daemon --rerun-tasks :protocol:test`) with
the exact number of added `CourierCarryDecisionTest` cases and the protocol count
moving from 82 to 136, against the rig at that layer,
714 = 330 protocol + 309 app + 75 harness (historical; current inventory is
972 = 443 protocol + 167 core + 254 app + 108 harness).
The 302 baseline (82 protocol + 210 app + 10 harness) and the 396 inventory are
historical. No
`SIM-HARN` or `DEVICE` evidence is claimed at this layer; those rows stay
planned until their dated walks run.

## Out of scope

Envelope encryption, the per-recipient key wrap and the envelope's own
signature and canonical bytes (courier-envelope specification); carrier CLI
commands and the persisted CLI bag wiring (2026-11-30); app sync integration,
file-card states and UI (2026-12-01); the three-node courier device walk
(2026-12-02); all QR frame codecs and camera display (2026-12-03 onward);
per-drop content keys (2026-12-09), delivery receipts (2026-12-11) and the
Circle pad (2026-12-15); file bytes inside parcels; store-and-forward inside a
relay; revocation beyond parcel expiry; any change to the v2.2 wire surface.

## Commit

2026-11-27 Protocol — Courier carry core: bounded parcel bag, window/expiry/dedup rules, pure JVM tests
