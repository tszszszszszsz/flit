# State convergence — one persisted CircleState for phone, CLI and desktop

## Status

This document is a plan. The shared `:core` module exists, but no convergence
of the state types exists: there is no desktop source set, no desktop binary and
no migrated state file. The
only facts below that are already implemented are the phone-side `CircleState`
codec and the CLI's eight-line file; their current locations are cited so the
plan can be checked against them. Nothing here proposes a wire change: no new
route, header, DTO field or Circle event verb. The desktop application this
feeds is the design target in `docs/roadmap/desktop-app.md`.

## The single persisted type

The persisted unit is one Kotlin `CircleState`, serialized as one JSON string.
The type and its codec exist today in
`app/src/main/kotlin/app/flit/share/FlitCircleStore.kt:137-170` (`CircleState`),
`:179-198` (`encode`/`decode`) and `:200-246` (`normalize`). The plan moves the
pure type, codec, normalizer and reducers into the JVM `:core` module described
in `desktop-app.md` §"Extraction before UI work". The phone keeps its
SharedPreferences adapter (`FlitCircleStore.kt:248-303`); the CLI and the desktop
each persist the same encoded string in a file. The file location stays a
platform adapter decision, not part of the format.

Fields of the shared type, with the CLI values they replace:

| Field | Current definition | CLI source today | Migration rule |
|---|---|---|---|
| `id` | `CircleState.id`, `FlitCircleStore.kt:139` | `CircleCliState.circleId`, `Harness.kt:918`, line 0 | Copy verbatim. Event folding compares it case-sensitively (`:353`). |
| `keyB64` | `:140`, capped by `MAX_KEY_CHARS = 256` (`:84`) | `key`, `Harness.kt:920`, line 2 | Copy verbatim. The cap is enforced on rekey installs (`applyRekey`, `:472-473`); `decode` does not truncate a stored key. |
| `role` | `:141-142`, `Role` enum at `:89` | No CLI field; derived by `circleRoleMap` (`Harness.kt:1034-1052`) | Derive once: pinned host is OWNER, other listed members MEMBER, ADMIN only from a pre-existing ROLE event. Write the derived role onto each `Member` and set this device's role by the same rule. |
| `members` | `:143`, `List<Member>` with `fp`, `alias`, `role`, `joinedMs` (`:91-97`) | `members`, `Harness.kt:922`, comma-joined fingerprints on line 4 | Split the line as today, then build `Member` rows. Blank aliases and `joinedMs = 0` are the legacy defaults; roles come from the derivation above. |
| `events` | `:144`, capped to `EVENT_CAP = 500` by `normalize` | `events`, `Harness.kt:923`, JSON array on line 5 | Decode with the existing `WireJson` array, then let the shared normalizer dedup ids and trim the log. |
| `rekeyGen` | `:145`, floored at zero by `normalize` | `rekeyGen`, `Harness.kt:924`, line 6 | Copy with the same floor. |
| `selfFp` | `:146-154`, blank means "legacy row" | No CLI field | Set from the loaded identity fingerprint where the CLI knows it; otherwise leave blank and keep the uniqueness-inference fallback in `applyRole` (`:429-433`). |
| `deliveries` | `:155-160`, `Map<String, CircleDelivery>` | No persisted CLI field; confirmations are derived from the signed log (`Harness.kt:1234-1239`) | Seed empty. CLI confirmation output keeps deriving from signed events until local rows are written through the shared delivery reducers (`:500-536`). |
| `courierBag` | `:161-169`, blank means empty bag | No CLI field; parcels live in the CLI bag folder (`CourierCli.kt:62`) | Seed blank. Do not move bag-folder files into the encoded blob as part of this step. |

The CLI has three local-only values that the current type does not carry:
`invite` (line 1), `host` (line 3) and `hostFp` (line 7). The plan adds them to
`CircleState` as additive fields with blank defaults, marked local-only like
`CircleDelivery.spoolPath` (`:105`) and `courierBag` (`:161-169`): they are
persisted and displayed, but never signed, gossiped or placed in a response
body. Keeping them on the shared type is what lets the desktop read the same
state the CLI wrote instead of introducing a second schema. Whether `invite`
and `host` instead belong in a CLI-side sidecar is an open question; the answer
does not affect the shared fields above.

## Legacy CLI line-file migration

The CLI file is `harnessHome()/circle.state`. Today it is UTF-8 text with eight
newline-joined lines, written by `saveCircleState` (`Harness.kt:952-963`) and
parsed by `loadCircleState` (`:931-950`) and again by `loadCliCircleState`
(`CourierCli.kt:626-645`). The loader accepts seven lines and treats a missing
eighth as a blank pin; such a row is forced through a fresh verified join before
any answer is persisted (`:945-947`).

Migration is read-time and one-way. On load, try the shared `decode` first. If
it returns null and the file has at least seven lines, parse the legacy lines
with the mapping in the table above, build a `CircleState`, and continue. The
next ordinary state save writes shared JSON through the same `encode` the phone
uses; there is no separate migration command and no background rewrite at load.
During the transition both encodings are accepted, so an interrupted or partial
migration leaves a readable file. Once a JSON row has been written, the legacy
branch is not consulted again for that file.

The identity file needs no migration: it is already at the shared home described
below. Parcel files and the courier bag folder are also left in place; moving
them into `courierBag` is a separate later step.

## `--host-fp` storage

Today the pin is the eighth line of the CLI state file. `circleExpectedHostFp`
(`Harness.kt:1009-1018`) takes an explicit `--host-fp` for the invocation, falls
back to the stored pin, and returns no pin when the two disagree; it also drops
the stored pin when `--circle` names a different circle. Join learns the pin from
the host's signed answer (`:1500`), and `circleAnswerSigned` (`:1021-1025`)
verifies later answers with `CircleResponse.verify`. A legacy seven-line row
starts with a blank pin and therefore cannot accept an answer before a verified
join.

In the planned format the pin moves to the additive `hostFp` field on the shared
state. The flag keeps its current per-invocation meaning and the verification
path is unchanged. The stored value is trim/uppercase normalized and capped at
128 characters, matching `normalized` (`Harness.kt:520-521`); the duplicate
normalizers in `CourierCli.kt:668` and `ParcelCli.kt:452` are removed in favour
of one shared helper. The pin is never put into an event body or a response. The
desktop uses the same stored pin when it verifies host answers, and shows the
fingerprint of its own identity from the `.p12`, not the host pin, in settings.

## Identity home

`harnessHome()` (`Harness.kt:97-112`) resolves to
`~/.config/flit/flit-harness` by default and to `FLIT_HARNESS_HOME` when that
variable is non-blank. `loadOrCreateIdentity()` (`:109-115`) loads or creates
`identity.p12` there; the comment at `:97-102` records why the file lives
outside the build tree.

The desktop default is that same directory and file. It loads the existing
`.p12` and does not mint a second key on first launch, so a fingerprint
established by `flit-cli` is the fingerprint shown on the desktop. The state
file sits in the same home, so the one-home-per-running-node rule covers both
identity and state: two nodes on one box use two `FLIT_HARNESS_HOME` values.
Sharing one home between two running nodes mixes fingerprints and state, as
recorded in the device-walk notes. Convergence is about the format of the state
file, not about two running nodes sharing one file.

## Duplicated helpers to delete

Codec and file helpers, removed by this change:

| Helper today | Replacement |
|---|---|
| `CircleCliState` (`Harness.kt:917-927`) | shared `CircleState` |
| `loadCircleState` (`Harness.kt:931-950`) and `loadCliCircleState` (`CourierCli.kt:626-645`) | shared `decode` plus the legacy fallback above |
| `saveCircleState` (`Harness.kt:952-963`) | shared `encode` |

Fold and body helpers, removed as the CLI moves onto the shared reducers. These
are separable from the file change but belong to the same convergence:

| Helper today | Shared equivalent |
|---|---|
| `circleRoleMap` (`Harness.kt:1034-1052`) | `FlitCircleStore.rolesOf` (`FlitCircleStore.kt:605-607`) and real `Member.role` values |
| `circleFreshEvents` (`Harness.kt:1062-1082`) | `FlitCircleSync.fresh` (`FlitCircleSync.kt:1011`) with the `CircleCore` gates, `order` and `dedup` (`CircleCore.kt:196`, `:229`, `:236`) |
| `circleLatestRekey` (`Harness.kt:1094-1119`) | `FlitCircleStore.applyRekey` (`FlitCircleStore.kt:458-477`) over a `merge` pre-batch snapshot (`:326-330`) and `leavingAuthors` (`:609-614`) |
| `mergeCircleAnswer` (`Harness.kt:1126-1138`) | `FlitCircleStore.merge` (`:326-330`) |
| `circleNextLamport` (`Harness.kt:1140-1141`) and `nextLamport` (`CourierCli.kt:649-650`) | one shared lamport helper |
| `circleEvent` (`Harness.kt:1143-1155`) | `CircleCore.create` (`CircleCore.kt:143`) |
| `normalized` (`Harness.kt:520-521`), `norm` (`CourierCli.kt:668`, `ParcelCli.kt:452`) | one shared fingerprint normalizer |
| `circleBody`/`circleText`/`circleBool` (`Harness.kt:532-547`) | `bodyOf`/`str`/`bool` in `FlitCircleStore.kt:616-629` |
| `circleFileAnnouncement` (`Harness.kt:1249`) and `circleReceiptBody` (`Harness.kt:1160`) | `circleFileBody` (`FlitCircleSync.kt:208`) and `receiptBody` (`FlitEngine.kt:439`) |

The top-level aliases `encode`, `decodeCircle` and `reduce` at
`FlitCircleStore.kt:20-32` become unnecessary as CLI callers move to `:core`.
`decode` cannot become a top-level alias with the same signature: it would
clash with `FlitKnown.decode(String?): List<Device>` (`FlitKnown.kt:17`), which
is why the named entry point exists (see the comment at
`FlitCircleStore.kt:23-27`). That name stays.

## Boundaries and open questions

No code described here has been written, no test has been run for it and no
device walk is claimed. The historical rig baseline is 778 tests (362 protocol,
324 app, 92 harness); that count is historical and is a floor, not a target.

Open questions to settle before implementation:

- Whether `invite` and `host` stay as local-only fields on `CircleState` or move
  to a CLI sidecar, and whether `hostFp` stays on the type or is stored as a
  separate local setting beside it.
- How `role`, `selfFp` and `hostFp` relate when the CLI node is itself the host;
  the shared reducer uses `selfFp` for role events and `hostFp` for answer
  verification, and those are different roles.
- Whether the legacy file is overwritten in place, kept under a backup name, or
  left for the operator to remove after a successful JSON write.
- When the courier bag folder is folded into `courierBag`, and whether received
  ledger rows on the desktop use the same local-only fields as the CLI.
- Where the shared `decode` fallback lives so that both CLI entry points and the
  desktop resolve the same legacy file without a third copy of the parser.
