# 2026-12-10 — Per-drop key parity (Integration) — DRAFT

Layer: 2026-12-10 Integration. The protocol-layer per-drop content keys from
2026-12-09 are bound into the app's Circle drop path and the CLI's `circle file`
path. Both surfaces mint one fresh key per file drop, wrap it to the current
members' certificates, seal the bytes with AES-GCM, announce the sealed drop in
a signed `file` event on the existing `/circle/sync` route, and push the sealed
bytes with the existing `sendFiles` call. The receive side unwraps with the local
identity, opens the bytes, checks the announced plaintext digest and lands the
plaintext through the normal received-file path. Legacy three-key `file` events
stay plaintext and keep their current behaviour.

No new route, header, DTO field, QR parameter or v2.2 status code is part of
this layer. The layer is built ahead of its scheduled day; the code is written
now and the commit stays on 2026-12-10.

Counts below are placeholders. They are to be read from the JUnit XML of a fresh
`--rerun-tasks` run, as required by `TESTING.md` §3 and §8. No Gradle run was
made for this draft, and no test result is claimed here. No device observation
is claimed on this date; the receive side is test-evidenced only.

## Files in the layer

| File | Content |
| --- | --- |
| `app/src/main/kotlin/app/flit/share/FlitEngine.kt` | `circlePostFile` keyed announcement path, receive-side unwrap/open/digest seam, `cipherSha` index, `NO_KEY` in the terminal set, delivery push over the sealed spool |
| `app/src/main/kotlin/app/flit/share/FlitCircleSync.kt` | `postFile(body: String)` overload; `circleBodyValid` FILE branch delegates to `CircleCore.validFileBody` |
| `app/src/main/kotlin/app/flit/share/FlitCircleStore.kt` | `CircleDelivery` additive `cipherSha` / `wrappedFor` fields and `NO_KEY` status, only if needed for keyed state |
| `harness/src/main/kotlin/app/flit/share/harness/Harness.kt` | Keyed `circleFileAnnouncement` overload, keyed `circle file` path, `CircleFileDelivery` additive fields, `ConsoleDelegate.onUploadFinish` keyed landing |
| `app/src/test/kotlin/app/flit/share/FlitCircleSyncTest.kt` | Keyed-body post/gate tests |
| `app/src/test/kotlin/app/flit/share/FlitEngineTest.kt` | Receive seam and delivery conclusion tests |
| `harness/src/test/kotlin/app/flit/share/harness/DropKeyCliTest.kt` | CLI keyed-drop parity tests |
| `protocol/src/test/kotlin/app/flit/share/protocol/DropParityTest.kt` | Cross-surface keyed parity tests |
| `docs/TESTING.md` | New test classes with counts from the integration run |
| `docs/CONTRACTS.md` | Keyed-file body contract on the app and CLI seams |
| `docs/SECURITY.md` | Per-drop key handling on send and receive; no remote revocation |
| `docs/FAILURE-MODES.md` | No-cert, over-cap, missing-wrap, tag and digest refusals |
| `docs/OPERATIONS.md` | Limits of this layer in operational terms |
| `docs/ARCHITECTURE.md` | Integration seam for the drop key core |
| `docs/completions/2026-12-10-per-drop-parity.md` | This layer record |
| `docs/roadmap/per-drop-parity.md`, `docs/roadmap/01-schedule.md`, `docs/roadmap/02-waves.md` | Finalized integration seams and build-ahead note |

The app side consumes `DropKeyCore`, `CircleCore` and `TlsIdentity` from
`app.flit.share.protocol`; the CLI consumes the same protocol core. No Android
import enters the protocol module, and no route, signature primitive or wire
shape is added.

## Public surface

The exact API held by the frozen seams is the 2026-12-09 protocol core:

```
object DropKeyCore {
  const val KEY_BYTES = 32, IV_BYTES = 12, TAG_BYTES = 16
  const val MAX_WRAPS = 32, MAX_WRAPPED_CHARS = 512
  const val DOMAIN = "FLITDROP1"
  data class Key(val bytes: ByteArray, val iv: ByteArray)
  data class KeyedFile(val name: String, val size: Long, val sha256: String,
                       val keyGen: Int, val cipherSha: String, val iv: String,
                       val wraps: Map<String, String>)
  fun mint(): Key
  fun wrap(key: ByteArray, recipients: Map<String, X509Certificate>): Map<String, String>
  fun unwrap(wrapped: Map<String, String>, identity: TlsIdentity): ByteArray?
  fun seal(key: Key, plaintext: ByteArray): ByteArray   // IV || ciphertext || tag
  fun open(key: ByteArray, sealed: ByteArray): ByteArray?
  fun keyedFileBody(v: KeyedFile): String
  fun parseFileKeyBody(body: String): KeyedFile?
  fun keyId(dropId: String, keyGen: Int): String = "$dropId:$keyGen"
}
```

The integration adds no new method to `DropKeyCore`. The host-side seams are:

```
FlitCircleSync.postFile(name: String, size: Long, sha: String)  // legacy, kept
FlitCircleSync.postFile(body: String)                           // caller-built body
FlitCircleSync.circleBodyValid(type: String, body: String): Boolean  // FILE accepts both key sets
FlitEngine.circlePostFile(name: String, size: Long, sha: String, spoolPath: String)
FlitCircleStore.CircleDelivery(
  id, name, size, sha256, spoolPath, statuses, createdMs,
  cipherSha: String = "", wrappedFor: List<String> = emptyList(),
)
FlitCircleStore.CircleDelivery.NO_KEY = "no-key"
Harness.circleFileAnnouncement(name: String, size: Long, sha256: String)  // legacy, kept
Harness.circleFileAnnouncement(v: DropKeyCore.KeyedFile): String          // keyed overload
```

`circleDeliveryOutcome(report, wireName)` and `CircleDeliveryStatus` on the CLI
are unchanged by this layer. `CircleCore.validFileBody` and `canonical` are
unchanged here; the app's `circleBodyValid` FILE branch now delegates to that
gate so a keyed event is not refused before unwrap.

## Event shape

The signed keyed `file` body has exactly the keyed key set:

```
{"name":"...","size":N,"sha256":"<plaintext sha>",
 "keyGen":1,"cipherSha":"<sha256 of sealed bytes>",
 "iv":"<base64 12 bytes>","wraps":{"<UPPERCASE FP>":"<base64 wrapped key>"}}
```

`KEYED_FILE_BODY_KEYS = {name, size, sha256, keyGen, cipherSha, iv, wraps}`.
`FILE_BODY_KEYS = {name, size, sha256}` stays the legacy set. The whole body
stays under `MAX_BODY_CHARS` (16 KiB); each string stays under
`MAX_FIELD_CHARS` (256) except each `wraps` value, bounded by
`MAX_WRAPPED_CHARS` (512). `MAX_WRAPS` is 32. A member list above `MAX_WRAPS`
is refused with a log line, not trimmed. `CircleCore.canonical` binds the
signature over `cipherSha`, `iv` and every wrap.

## Send path

Both surfaces follow the same steps. `mint()` draws a fresh 32-byte key and
12-byte IV. `wrap(key, recipients)` produces one RSA-OAEP/SHA-256 Base64 entry
per uppercase fingerprint, using the certificate from `TlsIdentity.pinned(fp)`
or the member's `CircleEvent.cert` in the folded log. `seal(key, plaintext)`
emits `IV || ciphertext || tag`. `keyedFileBody(...)` builds the body above and
the event is signed on the existing route. The sealed bytes are pushed with
`sendFiles(..., multi = false)`; the declared `FileInfo.sha256` is the digest of
the sealed bytes, equal to `cipherSha`.

App: `FlitEngine.circlePostFile` gathers current members from
`FlitCircleStore.load`, seals a cache copy at `<spool>.flitdrop`, posts through
`FlitCircleSync.postFile(body)` and starts delivery with the sealed path. No
usable certificate logs `circle drop: no recipient key material — not posted`;
a roster above 32 logs `circle drop: N recipients exceeds key wrap cap`. In both
cases nothing is posted and the source spool is kept.

CLI: `Harness.circleFileAnnouncement` gains the keyed overload used by
`circle file`; `circle post --file` stays legacy. `circle file` wraps to the
current members in `CircleCliState`, seals a `*.flitdrop` copy and calls the
existing `deliverCircleFile` with the sealed path. Above the wrap cap or with no
usable certificate the command prints `!! no recipient key material — nothing
posted` and exits without an event.

## Receive path

`FlitEngine` rebuilds an in-memory `cipherSha → DropKeyMaterial` index from the
bounded folded event log at boot. An `UploadResult.Saved` whose landed digest
matches an indexed `cipherSha` is unwrapped with the local `identity`, opened,
and checked against the announced plaintext `sha256`. On success the plaintext
is written under the announced name and `publishReceived` records its digest so
`_circleLandedShas` matches the card; the ciphertext temp is deleted. A missing
wrap, a GCM tag failure or a plaintext digest mismatch logs `circle drop: cannot
open <name> (<reason>)`, drops the landed ciphertext and adds no ledger entry.
A landed file with no `cipherSha` match takes the existing plaintext path.

`ConsoleDelegate.onUploadFinish` applies the same unwrap/open/digest step
against the CLI's own `circle.state` events. A success prints `drop-open <name>
<plainSha> (<bytes> bytes)`. A non-recipient prints `drop-refused <name>: no
wrap`; a tag or plaintext digest failure prints `drop-refused <name>: open
failed` and leaves no plaintext.

`flit-cli parse-qr`, `Qr.kt` v1/v2/v3 and the parcel frame codecs are untouched;
sealed bytes cross them as opaque payload.

## State ownership

Content keys exist only as wrapped ciphertext in the signed event, as the
sender's ephemeral key until the drop finishes, and as an in-memory unwrapped
key for one decrypt pass. `FlitCircleStore` and `circle.state` persist no
plaintext key. The sealed spool lives in app cache / the CLI temp path and is
removed when all current recipients are terminal or the drop is refused. The
received ledger keeps the plaintext digest, as before. `CircleDelivery`
`cipherSha` and `wrappedFor` are local-only bookkeeping with defaults so older
rows decode. `NO_KEY` is a terminal local status; a fingerprint with no wrap
gets `NO_KEY` locally and no bytes.

Delivery conclusions are unchanged: the per-sha `Mutex`, 30 s worker and
20/40/60 s backoff stay as they are. A returned report concludes the recipient
(`SENT` / `REFUSED` / `FAILED`), a thrown carrier or no route stays `PENDING`,
and all-terminal deletes the sealed spool. Legacy drops land in plaintext with
the same status rules.

## Verification

Fresh rig run, counts to be recorded from the JUnit XML:

```
source env.sh && ./gradlew --no-daemon --rerun-tasks \
  :protocol:test :app:testDebugUnitTest :harness:test
```

| Module | Before (last green inventory) | After this layer | Task |
| --- | --- | --- | --- |
| `protocol` | 210 | 216 | `:protocol:test` |
| `app` | 257 | 272 | `:app:testDebugUnitTest` |
| `harness` | 52 | 62 | `:harness:test` |
| Total | 519 | 550 | — |

`--rerun-tasks` is required: stale XML has previously reported green against an
older class layout. The "before" figures are the prior green inventory in
`TESTING.md` §2; the "after" figures come from a clean `--rerun-tasks` run.

## Tests

| Class | Cases | What it pins |
| --- | --- | --- |
| `DropParityTest` (protocol, new) | 6 | Cross-surface keyed parity: mint uniqueness, wrap round-trip, unwrap with the wrong identity, seal/open byte-exact, flipped tag/byte refusal, `MAX_WRAPS` / `MAX_WRAPPED_CHARS`, keyed and legacy body codecs, exact key sets, partial and extra keys refused |
| `FlitCircleSyncTest` (new cases) | 4 | `postFile` with a caller-built keyed body emits a `file` event whose signature verifies and whose body passes the keyed gate; legacy builder unchanged |
| `FlitEngineTest` (new cases) | 11 | Receive seam maps a `cipherSha` match to a plaintext landing, refuses a missing wrap, a tag failure and a sha mismatch, leaves a legacy landing unchanged; `NO_KEY` terminal and additive fields survive encode/decode |
| `DropKeyCliTest` (new, harness) | 10 | CLI keyed drop parity: receiver lands plaintext hash-exact to the announced `sha256` and prints `drop-open`; sealed bytes on the wire hash to `cipherSha`; a third identity with no wrap lands no plaintext; a tampered keyed event is skipped at the signature gate; identical bytes repeat with a different `cipherSha` and wrapped key; legacy `circle file` still lands plaintext |

Case counts come from a clean `--rerun-tasks` JUnit XML run, not from a target
figure. Test names are the `@Test` declarations in each class. No count is
claimed in this draft.

## Evidence

| Label | Claimed here | Basis |
| --- | --- | --- |
| CODE | Yes | The app and CLI seams match the API and byte rules recorded above; no new route, header, DTO field, QR parameter or status code |
| PURE | Yes | The four test classes above are planned for a fresh `--rerun-tasks` run; counts not yet read from XML |
| SIM-HARN | Yes | In-process CLI parity walks planned for the keyed drop send/receive path; counts not yet read from XML |
| DEVICE | No | No phone, second phone, radio or camera observation is made on this date |

The weakest recorded level governs each claim. No device observation is claimed
by this layer. Two-phone RFCOMM and a live multi-member mesh stay unverified
until a later walk.

## Limitations

- No device walk on this date. The receive side is test-evidenced only until a
  full feature walk records a keyed drop landing on the phone.
- No delivery receipts, Circle pad work, migration of already-landed drops,
  remote revocation or deletion of a drop already landed on another device is
  part of this layer. There is no key escrow or recovery and no second content
  key per recipient.
- Message, poll and vote events are not encrypted. `Qr.kt` byte shape, the
  parcel frame codec, the `/circle/sync` route and the v2.2 surface are unchanged.
- No plaintext content key is persisted. Removing the app's in-memory index or
  restarting the CLI still leaves only the wrapped key in the bounded event log.
- A member who kept a wrap can still open an old drop; a later joiner gets no
  backfill. No guarantee to the contrary is made here.
- Performance figures are not stated; no timings are invented.

## Verification status

Counts (6 + 4 + 11 + 10), the module totals and the rig total come from a clean
`--rerun-tasks` run once the code and tests land. CODE, PURE and SIM-HARN
evidence are recorded above as planned; no device observation is claimed. The
route and frame surface is unchanged from the 2026-12-09 protocol layer. Every
numeric field in this draft is a placeholder pending that run.
