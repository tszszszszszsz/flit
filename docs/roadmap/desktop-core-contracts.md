# Desktop `:core` public contracts

## Status

`:core` is present on disk with its leaf pure seams and local JUnit XML;
`ui-common`, a desktop source set and a desktop binary do not exist. This
document proposes a public surface for a later extraction; it adds no source
file, build task, repository operation or release step. The 778-test figure
(362 protocol + 324 app + 92 harness) quoted in `desktop-app.md:141` is a
historical baseline. No desktop test run or device walk is claimed; local `:core` JUnit XML
exists.

The proposal follows the extraction order in `desktop-app.md:49-95`: a JVM-pure
`:core` is shared by `app`, `harness` and the later desktop target, with
platform adapters left in their current modules. The six facades below are the
names callers adopt. Their behaviour, constants, canonical forms and wire
effects are the ones already implemented under `protocol/src/main/kotlin/app/
flit/share/` and `app/src/main/kotlin/app/flit/share/FlitCircleStore.kt`. No new
Circle verb, route, header, DTO field or v2.2 status is implied. No `android.*`
import appears in `:core`; BLE radio, CameraX, SAF, foreground service and
notification code stay as app actuals.

The proposed package is `app.flit.share.core`. The declarations stay at their
current coordinates until extraction is implemented, so the signature blocks
below describe the target API rather than files present today.

## Facades

### `CircleState`

The app-side owner is `FlitCircleStore.kt:75-170`, with Context persistence at
`FlitCircleStore.kt:248-313`. The CLI mirror is `CircleCliState` at
`Harness.kt:917-927` and its loader at `Harness.kt:931-963`. `:core` owns the
type, the tolerant codec, the pure reducers and the atomic port-backed writes.
The codec stays additive: `selfFp`, `deliveries` and `courierBag` keep their
defaults so older rows decode, and `normalize` keeps the roster, event-id,
delivery-key and bag repair rules from `FlitCircleStore.kt:200-246`.

```kotlin
package app.flit.share.core

@Serializable
data class CircleState(
    val id: String,
    val keyB64: String,
    val role: Role,
    val members: List<Member>,
    val events: List<CircleEvent> = emptyList(),
    val rekeyGen: Int = 0,
    val selfFp: String = "",
    val deliveries: Map<String, CircleDelivery> = emptyMap(),
    val courierBag: String = "",
) {
    companion object {
        const val EVENT_CAP = 500
        const val MAX_ALIAS_CHARS = 64
        const val MAX_KEY_CHARS = 256

        fun encode(state: CircleState): String
        fun decode(raw: String?): CircleState?

        fun reduce(state: CircleState, event: CircleEvent): CircleState
        fun reduce(
            state: CircleState,
            event: CircleEvent,
            preBatchRoles: Map<String, String>,
            batchLeavers: Set<String>,
        ): CircleState
        fun merge(state: CircleState, events: List<CircleEvent>): CircleState

        fun load(store: Store, key: String): CircleState?
        fun save(store: Store, key: String, state: CircleState)
        fun clear(store: Store, key: String)
        fun upsert(store: Store, key: String, events: List<CircleEvent>): CircleState?
        fun update(
            store: Store,
            key: String,
            transform: (CircleState) -> CircleState,
        ): CircleState?
        fun persistDelivery(store: Store, key: String, delivery: CircleDelivery): CircleState?
        fun persistDeliveryStatus(
            store: Store, key: String, sha: String, fp: String, status: String,
        ): CircleState?
        fun dropDelivery(store: Store, key: String, sha: String): CircleState?
    }
}

enum class Role { OWNER, ADMIN, MEMBER }

@Serializable
data class Member(
    val fp: String,
    val alias: String,
    val role: Role = Role.MEMBER,
    val joinedMs: Long = 0L,
)

@Serializable
data class CircleDelivery(
    val id: String,
    val name: String,
    val size: Long,
    val sha256: String,
    val spoolPath: String,
    val statuses: Map<String, String> = emptyMap(),
    val createdMs: Long = 0L,
    val cipherSha: String = "",
    val wrappedFor: List<String> = emptyList(),
) {
    companion object {
        const val PENDING = "pending"
        const val SENT = "sent"
        const val REFUSED = "refused"
        const val FAILED = "failed"
        const val NO_KEY = "no-key"
    }
}
```

The port-backed `load`, `save`, `clear`, `upsert` and `update` replace the seven
Context helpers at `FlitCircleStore.kt:264-313`. The single-lock guarantee is
kept: each port call performs decode, fold, encode and write inside one
synchronised section. The `Store` key is supplied by the caller; the app passes
`"circle.state"` and the CLI keeps `circleStatePath()` from
`Harness.kt:929`, so no surface changes its file location.

### `CircleAuthority`

The existing gate is `protocol/.../circle/CircleAuthority.kt:21-44`, called from
the app host (`FlitCircleHost.kt:188`), the app store fold
(`FlitCircleStore.kt:470`) and the app and CLI sync folds
(`FlitCircleSync.kt:931,1021`, `Harness.kt:382,1072,1108`, `CourierCli.kt:443`).
The facade is unchanged: it reads only `circleId`, `type` and `authorFp` plus a
caller-supplied role map, verifies no signature, reads no clock and holds no
state.

```kotlin
object CircleAuthority {
    fun foldable(event: CircleEvent, circleId: String, roles: Map<String, String>): Boolean
}
```

`CircleState` supplies the canonical fingerprint-to-role map from its roster
using the same uppercase-and-trim rule as `FlitCircleStore.kt:605-607`; callers
with no roster continue to pass `emptyMap()`.

### `PadCore`

The existing pure fold is `protocol/.../circle/PadCore.kt:21-187`. Verification,
pinning and the replay guard stay at the receiving route; `view` calls
`CircleCore.dedup` but no signature check.

```kotlin
object PadCore {
    const val MAX_LINES = 256
    const val MAX_KEY_CHARS = 128
    const val MAX_TEXT_CHARS = 4 * 1024
    val PAD_PUT_KEYS: Set<String>   // {"op", "key", "text"}
    val PAD_DEL_KEYS: Set<String>   // {"op", "key"}

    data class PadLine(
        val key: String,
        val text: String,
        val lamport: Long = 0L,
        val authorFp: String = "",
        val id: String = "",
        val deleted: Boolean = false,
    )
    data class PadView(
        val lines: List<PadLine>,
        val winners: Map<String, PadLine> = emptyMap(),
    )

    fun padPutBody(key: String, text: String): String
    fun padDelBody(key: String): String
    fun validPadBody(body: String): Boolean
    fun view(events: Iterable<CircleEvent>, circleId: String): PadView
    fun merge(views: Iterable<PadView>): PadView
}
```

App consumers are the gate at `FlitCircleSync.kt:186`, the builders at
`FlitCircleSync.kt:613,619`, the view rebuild at `FlitEngine.kt:533`, and the
bounds in `PadUi.kt:71-80`. CLI consumers are the builders at
`Harness.kt:1737,1750` and the view at `Harness.kt:1803-1804`.

### `DropKeyCore`

The existing core is `protocol/.../circle/DropKeyCore.kt:30-239`. It mints one
fresh key per drop, wraps to member certificates, seals with AES-GCM and owns
no key cache, sealed bytes or drop state. The facade keeps the single OAEP and
GCM configurations and writes no pin.

```kotlin
object DropKeyCore {
    const val KEY_BYTES = 32
    const val IV_BYTES = 12
    const val TAG_BYTES = 16
    const val MAX_WRAPS = 32
    const val MAX_WRAPPED_CHARS = 512
    const val DOMAIN = "FLITDROP1"

    data class Key(val bytes: ByteArray, val iv: ByteArray)
    data class KeyedFile(
        val name: String,
        val size: Long,
        val sha256: String,
        val keyGen: Int,
        val cipherSha: String,
        val iv: String,
        val wraps: Map<String, String>,
    )

    fun mint(): Key
    fun wrap(key: ByteArray, recipients: Map<String, X509Certificate>): Map<String, String>
    fun wrapVerified(key: ByteArray, recipients: Map<String, X509Certificate>): Map<String, String>
    fun unwrap(wrapped: Map<String, String>, identity: TlsIdentity): ByteArray?
    fun seal(key: Key, plaintext: ByteArray): ByteArray
    fun open(key: ByteArray, sealed: ByteArray): ByteArray?
    fun keyedFileBody(v: KeyedFile): String
    fun parseFileKeyBody(body: String): KeyedFile?
    fun keyId(dropId: String, keyGen: Int): String
}
```

App consumers are the parse at `FlitEngine.kt:420`, the send path at
`FlitEngine.kt:1015-1052`, the receive path at `FlitEngine.kt:1091-1093`, and
the receipt key id at `FlitEngine.kt:1157`. CLI consumers are the receive path
at `Harness.kt:614-630`, the body and roster checks at
`Harness.kt:1194-1199,1271-1272,1548-1563`, and the confirmation fold at
`Harness.kt:1818`.

### `CourierEnvelope`

The existing envelope is `protocol/.../courier/CourierEnvelope.kt:55-301`, with
one crypto path shared with `ParcelCodec`. `sig` and `cert` stay outside the
canonical bytes; `verify` binds the certificate's own digest to `fromFp` before
it writes a pin.

```kotlin
data class CourierEnvelope(
    val id: String,
    val circleId: String,
    val fromFp: String,
    val toFp: String,
    val createdMs: Long,
    val alg: String,
    val ivB64: String,
    val cipherB64: String,
    val keyWrapB64: String,
    val sig: String = "",
    val cert: String = "",
) {
    companion object {
        const val ALG = "AESGCM+RSAOAEP256"
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val MAX_ENVELOPE_CHARS = 12 * 1024

        fun canonical(e: CourierEnvelope): ByteArray
        fun encode(e: CourierEnvelope): String
        fun decode(raw: String?): CourierEnvelope?
        fun seal(
            events: List<CircleEvent>,
            circleId: String,
            fromFp: String,
            toFp: String,
            recipientCert: X509Certificate?,
            identity: TlsIdentity,
            nowMs: Long,
        ): CourierEnvelope?
        fun verify(e: CourierEnvelope): Boolean
        fun open(e: CourierEnvelope, identity: TlsIdentity, nowMs: Long): List<CircleEvent>?
    }
}
```

`nowMs` is already an explicit argument to `seal` and `open`; the Clock port
supplies it at the call sites. App consumers are `FlitCircleSync.kt:909,915`
and `FlitEngine.kt:1193,1232`. CLI consumers are `CourierCli.kt:244,251,330,
405,415,665`. The bag stays in `CourierCarryCore` and in the encoded
`CircleState.courierBag` field; it is not pulled into this facade.

### `ParcelCodec`

The existing frame codec is `protocol/.../parcel/ParcelCodec.kt:47-388`. It owns
the frame grammar, per-frame CRC32, whole-parcel digest and caps, and delegates
all crypto to `CourierEnvelope`.

```kotlin
object ParcelCodec {
    const val MAX_FRAMES = 64
    const val MAX_AIR_GAP_PARCEL_BYTES = 131072
    const val MAX_AIR_GAP_PAYLOAD_BYTES = 5120
    const val MAX_CHUNK_BYTES = 512
    const val FRAME_MAGIC = "FLITPARCEL1"

    data class FrameDescriptor(
        val parcelId: String,
        val seq: Int,
        val count: Int,
        val totalBytes: Int,
        val crc: Long,
    )
    data class ParcelPayload(
        val parcelId: String,
        val recipientFp: String,
        val signerFp: String,
        val sha256: String,
        val bytes: ByteArray,
    )

    fun parse(frame: String): FrameDescriptor?
    fun encodeParcel(
        payload: ByteArray,
        parcelId: String,
        recipientFp: String,
        recipientCert: X509Certificate,
        signerFp: String,
        identity: TlsIdentity,
    ): List<String>
    fun complete(frames: List<String>, recipientFp: String, identity: TlsIdentity): ParcelPayload?

    // Additive Clock-fed forms; the existing bodies keep their current defaults.
    fun encodeParcel(
        payload: ByteArray,
        parcelId: String,
        recipientFp: String,
        recipientCert: X509Certificate,
        signerFp: String,
        identity: TlsIdentity,
        clock: Clock,
    ): List<String>
    fun complete(
        frames: List<String>,
        recipientFp: String,
        identity: TlsIdentity,
        clock: Clock,
    ): ParcelPayload?
}
```

The two existing entry points read `System.currentTimeMillis()` at
`ParcelCodec.kt:149,233`. The additive overloads let a desktop caller and a JVM
test drive the same envelope window from a `Clock`; they add no second frame
grammar and no second crypto path. Consumers are `ParcelScanState.kt:33-35,69-
70,98-106`, `ParcelUi.kt:85`, `MainActivity.kt:2611,2628`, and
`ParcelCli.kt:182-196,282,297`.

## Ports replacing Android `Context`

`Context` in `app` currently supplies four jobs that `:core` needs and one set
of jobs that must stay platform-side. The mapping is:

| `Context` call site | Job | Port |
|---|---|---|
| `FlitKnown.kt:107-119`, `FlitCircleStore.kt:250-260`, `FlitRoster.kt:130-136`, `FlitEngine.kt:1634-1636`, `MainActivity.kt:347` | SharedPreferences string/bool rows | `Store` |
| `FlitCircleSync.kt:335,396`; `ParcelCodec.kt:149,233` | current time | `Clock` |
| `FlitEngine.kt:1363-1394`; `FlitBluetooth.kt:68-69` | Wi-Fi, Bluetooth and relay route choice | `Dial` |
| `FlitEngine.kt:3829-3832`; `Harness.kt:103-115` | identity file location and key material | `Identity` |
| `FlitNotify.kt:31-32,114,134`, `FlitService.kt:52,78,94`, `ParcelUi.kt:226,415`, `FlitBluetooth.kt:59` | notifications, FGS, camera, BLE radio, SAF | no `:core` port; app actuals |

```kotlin
interface Store {
    fun string(key: String): String?
    fun putString(key: String, value: String)
    fun bool(key: String, default: Boolean): Boolean
    fun putBool(key: String, value: Boolean)
}

interface Clock {
    fun nowMs(): Long
}

enum class Carrier { WIFI, BLUETOOTH, RELAY }

data class Route(
    val host: String,
    val port: Int,
    val tls: Boolean,
    val carrier: Carrier,
    val relayFor: String? = null,
)

interface Dial {
    fun route(fingerprint: String): Route?
}

interface Identity {
    fun current(): TlsIdentity
    fun certificateFor(fingerprint: String): X509Certificate?
}
```

`Store` is the surface already defined as `FlitKnown.Store` at
`FlitKnown.kt:78-83`; `:core` makes it the single port for known devices,
Circle state, roster rows and settings. The app actual keeps the existing
`"flit"` SharedPreferences file, so the current keys and on-device rows keep
their meaning. The CLI and desktop actuals write one file per node under their
own home. No Schema version field is added.

`Clock` has a precedent in `FlitCircleSync.clockHook` at
`FlitCircleSync.kt:335` and `nowMs()` at `:396`. The port makes that seam
explicit instead of a mutable hook. The protocol bodies that still take
`System.currentTimeMillis()` as a default keep that default until extraction;
no surface gains a second clock authority.

`Dial` returns the route that the caller turns into the existing
`SendTarget` and `ProtocolClient` from `app.flit.share.protocol.client`. The
app actual keeps the policy at `FlitEngine.kt:1362-1394`: a live Wi-Fi route
first, Bluetooth from a known device's `mac` second, relay only when that
device has `relayFor` set. The desktop actual returns `WIFI` or `RELAY` routes
from UDP discovery and known-device settings; it never returns `BLUETOOTH`.
No carrier policy moves into `:core`.

`Identity.current()` supplies the `TlsIdentity` argument already required by
`CourierEnvelope.seal`/`open`, `DropKeyCore.unwrap` and `ParcelCodec`.
`certificateFor()` replaces the direct pin and event-cert lookups used before
keyed sends. The app actual reads `ctx.filesDir/flit-identity.p12` as at
`FlitEngine.kt:3829-3832`; the CLI actual reads
`harnessHome()/identity.p12` as at `Harness.kt:109-115`; the desktop actual
reads the same CLI home by default, with `FLIT_HARNESS_HOME` still separating
two nodes on one box, as recorded in `desktop-app.md:88-95`. No surface mints a
second key on first launch. Signature pinning inside `CourierEnvelope.verify`
keeps its current behaviour; the pin cache does not move into `Identity` in
this proposal.

## Adoption sites in `app`

- `FlitCircleStore.kt:3,17,248-313` — drop `android.content.Context`, point the
  `CircleState` typealias at the `:core` type, and remove the local
  SharedPreferences adapter and the seven Context helpers in favour of the
  `Store`-backed companions. The `ioLock` serialisation moves with them.
- `FlitKnown.kt:3,78-119,121-131` — remove the local `Store` declaration and
  its Context adapter; keep `Device`, `RoutePolicy`, `encode` and `decode`, and
  route `all`, `put`, `forget`, `qrOnly`, `setQrOnly` and `policy` through the
  `:core` `Store`.
- `FlitEngine.kt:1629-1640,1699-1701` — `initAppCtx`, `prefs()`, `ctx()` and
  `start(ctx)` become the place where the Android `Store`, `Clock`, `Dial` and
  `Identity` actuals are installed. `appCtx` stays for the platform actuals;
  `:core` never reads it.
- `FlitEngine.kt:420,533,1015-1052,1091-1093,1157,1193,1232` — retarget
  `DropKeyCore`, `PadCore` and `CourierEnvelope` calls to the `:core` names,
  with recipient certificates supplied by `Identity.certificateFor` and the
  envelope time supplied by `Clock`.
- `FlitEngine.kt:1362-1394` — `circleDial` becomes the `Dial` actual. The
  `CircleRoute` wrapper keeps its `SendTarget` and client; the policy and the
  relay opt-in check stay here.
- `FlitEngine.kt:3829-3832` — `loadOrCreateIdentity(ctx)` becomes the
  `Identity` actual over `filesDir`.
- `FlitCircleHost.kt:188`, `FlitCircleStore.kt:470,605-607`,
  `FlitCircleSync.kt:931,1021` — retarget `CircleAuthority.foldable`; the
  fingerprint-to-role map comes from the shared `CircleState` rule.
- `FlitCircleSync.kt:186,613,619,909,915` — retarget the pad gate/builders and
  the envelope decode/open calls; `nowMs()` becomes `Clock.nowMs()` and the
  `clockHook` at `:335` is removed after its tests are moved to a fake `Clock`.
- `PadUi.kt:71-80`, `ParcelUi.kt:85`, `ParcelScanState.kt:33-35,69-70,98-106`,
  `MainActivity.kt:2611,2628` — read bounds and descriptors from the `:core`
  facades; Compose rendering, camera intake and the injected byte writer stay
  in the app.
- `FlitRoster.kt:130-136` and `MainActivity.kt:347` — the second and third
  SharedPreferences adapters adopt the same `Store`; no roster or settings key
  changes meaning.
- `FlitService.kt:52,78,94`, `BootReceiver.kt:14-27`, `FlitTile.kt:23-27` —
  these only pass a `Context` into engine startup. They keep doing so; no
  `:core` contract is added for lifecycle or notifications.

## Adoption sites in `harness`

- `Harness.kt:98-115` — `harnessHome()` and `loadOrCreateIdentity()` become the
  CLI `Identity` actual. The default path stays
  `~/.config/flit/flit-harness/identity.p12` and the `FLIT_HARNESS_HOME`
  override stays as-is.
- `Harness.kt:917-927,929-963` — `CircleCliState` and its eight-line file
  loader are replaced by the shared `CircleState` codec behind a `Store` actual
  rooted at `circleStatePath()`. The CLI keeps its own state file and its own
  reducer calls; it stops carrying a second state schema, as proposed in
  `desktop-app.md:83-87`. The `invite`, `host` and `hostFp` CLI columns have no
  field in the shared type, so the adapter either keeps them as a CLI-side
  sidecar or maps them into additive defaulted fields before the move; this is
  an open point below.
- `Harness.kt:382,1072,1108`, `CourierCli.kt:3,443` — retarget
  `CircleAuthority.foldable` to the `:core` gate with the same role map rules.
- `Harness.kt:614-630,1194-1199,1271-1272,1548-1563,1818` and
  `ReceiptCliTest.kt:75-85,120-122` — retarget `DropKeyCore` at the receive,
  body, roster-cap and receipt sites. The keyed body bytes and `keyId` stay
  verbatim.
- `Harness.kt:1737,1750,1803-1804` and `PadCliTest.kt:63-70,126-128` —
  retarget `PadCore` builders, gate and view; `circle pad show` keeps printing
  the same ordered lines.
- `CourierCli.kt:244,251,330,405,415,665` — retarget `CourierEnvelope` calls,
  with `nowMs` supplied by a CLI `Clock` actual and recipient certificates
  resolved through the CLI `Identity` actual.
- `ParcelCli.kt:182-196,282,297` — retarget `ParcelCodec` calls. PNG rendering,
  the numbered `frame-%04d.png` names and the zxing reader stay in the CLI; the
  optional `Clock` overloads are used only if a caller needs a fixed window.
- `CourierCli.kt:626-647` — the second CLI circle-state loader converges on
  the same `CircleState` codec. The `courier-bag/` directory and the
  `courier.state` rows keep their paths and meaning.

## Semantics held fixed

- No new route, header, DTO field, Circle verb or v2.2 status. The ten existing
  event verbs, the `FLITCIRCLE1`, `FLITCOURIER1`, `FLITDROP1`, `FLITPARD1` and
  `FLITCARRY1` tags and the canonical field rule are unchanged.
- One crypto path per operation. `:core` adds no `Cipher`, `Signature`,
  certificate parser or key store of its own; `ParcelCodec` continues to reach
  `CourierEnvelope`, and `CourierEnvelope.verify` continues to pin only after
  the signature check.
- Caps and reductions are unchanged: `CircleState.EVENT_CAP = 500`,
  `DropKeyCore.MAX_WRAPS = 32`, `PadCore.MAX_LINES = 256`,
  `CourierEnvelope.MAX_ENVELOPE_CHARS = 12 * 1024`, and
  `ParcelCodec.MAX_FRAMES = 64` with `MAX_AIR_GAP_PAYLOAD_BYTES = 5120`.
- The received bucket, courier bag and delivery statuses remain local-only.
  No account, cloud, ads, telemetry or phone-home dependency enters the ports.

## Verification proposed, not performed

`common:test` is the named JVM test location for every seam moved into `:core`
and for the pure helpers in `ui-common` (`desktop-app.md:145-147`), including
the shared `CircleState` codec cases that replace the two CLI schemas. Fakes
for `Store`, `Clock`, `Dial` and `Identity` keep those tests free of Android,
file and network access. `desktop:test` is the named location for identity-home
resolution, discovery, file state holders and parcel round-trips
(`desktop-app.md:148-151`). None of these tests exists yet.

## Open points

- Whether `:core` owns the declarations outright or re-exports the existing
  protocol objects for one extraction phase. The facade signatures above work
  either way; the choice changes only where the bodies live.
- How the CLI's `invite`, `host` and `hostFp` columns are represented once
  `CircleCliState` is replaced by `CircleState`. A CLI-side sidecar and three
  additive defaulted fields are both compatible with the codec rules; no
  choice is recorded here.
- Whether the pin cache stays process-global through `TlsIdentity` or becomes
  an injected part of `Identity` in a later phase. Keeping it global preserves
  current behaviour, but it leaves one piece of process state outside the
  ports.
- Whether `Dial` needs a fan-out member for Circle sync as well as the
  per-fingerprint `route(fingerprint)`. The app's fan-out target selection is
  still in `FlitCircleSync`; moving it would widen this port.
- Which directory the desktop `Store` uses and how one-home-per-node is shown
  in settings. `desktop-app.md:104-109` lists the settings but does not fix
  the file name.

## Boundaries

Every call-site reference above points at shipped code and describes a future
adoption. No facade, port, adapter, module, test or desktop window has been
added by this document. No commit, build task, release step or device claim is
made.
