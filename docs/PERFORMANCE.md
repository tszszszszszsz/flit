# Performance and energy

> Part of the Flit engineering docs set. [Ladder and index](transport-bluetooth.md) ·
> [Bluetooth carrier](bluetooth-carrier.md) · [Circle](circle.md) ·
> [Open-file / sharing restore](open-file-and-sharing-restore.md) ·
> [README](../README.md).

Current rig state: **136 protocol + 228 app + 32 harness = 396 tests, 0 failures** after a green `--rerun-tasks` run. Older docs quote older counts (76, 86, 115, 302) from the era in which they were written — those are historical, not current, figures.

There is **no power monitor, battery-current sample, profiler trace or frame-time capture anywhere in this repo**. Every energy statement below is duty-cycle arithmetic plus design intent, or an on-device observation (an idle soak, a log line), never a measured mAh or watt figure.

---

## 1. Measured throughput

### 1.1 The one hard number

On 2026-11-24 a **512 MiB** file sent from the reference CLI to the phone completed in **15.9 s** in both the single-stream and the multi-socket configuration: **32.1 MiB/s, roughly 270 Mbps** ([bluetooth-carrier.md](bluetooth-carrier.md), "Speed, measured 2026-11-24"). The same number for both modes means the ceiling is this Wi-Fi link and the phone's storage, not Flit's HTTP/TLS framing; turbo is resume-aware but cannot outrun the radio. Bluetooth is a reachability fallback, never a speed upgrade.

That is one file, one link, one phone, one day. It is **not** a throughput matrix: no LTE/cellular figure, no RFCOMM figure, no cross-device comparison and no CPU cost were captured alongside it.

### 1.2 Where progress and rate come from

- The sender hashes each outgoing file before prepare (`ProtocolClient.sendFiles`, `protocol/.../client/ProtocolClient.kt:223-252`, `sha256Hex` over a fresh stream). That hash is a one-pass read of every source file; it is not cached across sends.
- The receiver logs a paced line every **16 MiB** and at EOF (`FlitEngine.kt:1005-1017`): `rx <done>/<total> pace <X> MiB/s`, derived from `System.nanoTime()` deltas. This is a log-only measurement used on slow receive paths; it is not fed back into the UI.
- The server caps progress events at roughly **10 Hz** (`ServerCore.kt:585` and `:757`, `now - lastEmit > 100_000_000L`), so a fast multi-socket upload does not generate an unbounded callback storm into the app layer.
- Buffers are fixed and small: 64 KiB for hashing and HTTP body copies (`FlitEngine.kt:228`, `PeerHttpClient.kt:96`, `HttpExchange.kt:44,60`), 256 KiB `BufferedOutputStream` for direct-to-storage receives (`FlitEngine.kt:2989,3020`), 16 KiB TLS record chunks (`protocol/.../tls/StreamTls.kt:394`).

### 1.3 The speed-readout fix (pure-test evidence)

The old estimator sampled on every progress callback but reset its clock only when the notification was repainted (every 350 ms). Each callback re-divided the *same* elapsed time against a growing byte count, so a healthy 30 MB/s transfer showed as KB/s and then spiked into the gigabits when the window closed.

`FlitSpeed` (`app/.../FlitSpeed.kt:19-56`) separates counting from painting:

- samples closer than `minSampleMs = 80 ms` are ignored;
- a single sample cannot exceed `4×` the current estimate;
- smoothing is an exponential half-life of `700 ms`: `alpha = 1 - 0.5^(dt/halfLife)`;
- the first real sample seeds the estimate outright.

`FlitProgress.paint` (`FlitNotify.kt:62-127`) then does two different things at two different rates: it assigns a `TransferTelemetry` **StateFlow on every `tick`** (cheap, drives the hero strip and dock ring), while actual `NotificationManager.notify` repaints are budgeted to **350 ms** (`tick(timeBudgetMs = 350)`). Completion always repaints (`fileDone` → budget 0). The per-file ledger merges with `maxOf(a, b)` so a late or out-of-order multi-socket callback can never drag the displayed byte count backwards. The progress scale is 10,000 steps, avoiding `setProgress` Int overflow.

`FlitSpeedTest` (4 pure tests) pins the sub-minimum-sample rule, the no-negative/no-acceleration rule, the half-life cap against a bogus 900 MB sample, and reset semantics. `protocol/.../TurboProgressTest.kt` drives the production multi-socket path over a real TCP server with a 64 MiB body split into four 16 MiB ranges and asserts progress is monotonic, bounded by file size, and ends exactly at it — the old code had four absolute offsets overwriting one ledger slot.

**Not measured:** the relationship between the displayed rate and ground-truth disk write rate; notification repaint cost; UI frame time during a transfer.

---

## 2. Energy design and BLE duty cycle

### 2.1 Dormancy is the primary energy saving

The arbiter's first rule is that BLE discovery is **off whenever a Wi-Fi access point exists** (`FlitBle.stance`, `FlitBle.kt:223-239`; applied by `startBleFlipper`, `FlitEngine.kt:1759-1836`, on a 5 s tick). Wi-Fi discovery owns the roster in that case. No always-on scanning exists in any state; the comment at `FlitBle.kt:214` states the intent plainly: once a real peer row exists, continuous scanning is "pure battery burn".

### 2.2 The no-AP duty window

When no usable Wi-Fi network answers (`wifiNetwork()`, `FlitEngine.kt:1865-1874` — Wi-Fi transport, VPN excluded; a live SoftAP counts), permissions are granted and the radio is on, discovery is still not continuous. It is gated by pure modulo arithmetic (`FlitBle.kt:151-206`):

| Constant | Value | Meaning |
| --- | --- | --- |
| `DUTY_TICK_MS` | 5,000 | Engine tick that samples the phase (`FlitEngine.kt:1774`) |
| `DUTY_ON_TICKS` | 2 | Two on ticks = 10 s |
| `DUTY_OFF_TICKS` | 9 | Nine off ticks = 45 s |
| `DUTY_PERIOD_TICKS` | 11 | One full cycle |
| `DUTY_PERIOD_MS` | 55,000 | 10 s on / 45 s off |
| `FRESH_TOUCH_MS` | 10,000 | A Wi-Fi/manual touch this recent owns the row; the radio may only fill lapses |

`phaseOn(tick) = Math.floorMod(tick, 11) < 2`, with `clockTick = floorDiv(nowMs, 5000)`; negative ticks wrap, a non-positive window never wakes and a window at least as long as the period stays always on (fail-closed, `FlitBle.kt:176-184`). `FlitBleTest` asserts 5,000 / 10,000 / 45,000 / 11 / 55,000 exactly, the repeated 11-tick pattern, the wrap cases and the degenerate windows.

The resulting stance (`FlitBle.kt:232-238`):

| Facts | Stance |
| --- | --- |
| AP up, or no permission, or radio off | advertise **false**, scan **false** |
| No AP, hidden mode on, no live peer | advertise false, scan only during the on window |
| No AP, a live non-manual peer exists | advertise during the on window, **scan false** |
| No AP, normal, no live peer | advertise and scan during the on window |

A live peer (`hasLivePeer`, `FlitBle.kt:143-149` — non-`manual@`, within the 30 s TTL) puts the ears to sleep outright; hidden mode listens in the on window but never announces, because a register is an announcement. `DutyPhase.ALWAYS` exists only for debug/walkthroughs; the shipped source is `DutyPhase.DUTY`.

### 2.3 Radio-level parameters

`FlitBleRadio.apply` (`FlitBleRadio.kt:62-80`) only starts/stops what the stance asks, and skips a rebuild when the record and connectable flag are unchanged (`:94`). Extended advertising uses `INTERVAL_MEDIUM`, `TX_POWER_MEDIUM`, no device name, service data under our UUID (`FlitBle.kt:32-56`; 52-byte extended payload = 2 magic/ver + 32 fp + 18 alias, matching the device walk). Legacy advertising uses `ADVERTISE_MODE_BALANCED` and an 8-byte truncated fingerprint with no alias. Scanning uses `SCAN_MODE_LOW_POWER` plus a service-UUID filter; an unfiltered dump is only the `debug.flit.bledump=1` path (`FlitBleRadio.kt:172-187`).

Connectable advertising — required for RFCOMM — is `connectable && stance.advertise` (`FlitBleRadio.kt:76`): the carrier may be enabled while the duty window rests, but resting never leaves a connectable record on the air. The engine passes `connectable = btClient != null` (`FlitEngine.kt:1792-1796`), so discovery-only advertising stays non-connectable even when the radio is on.

### 2.4 Observed idle behaviour, and what it does not prove

The 2026-10-30 idle soak ([bluetooth-carrier.md](bluetooth-carrier.md), "Idle soak, 0.2.0 on hardware") ran 8 minutes screen-off with the engine serving: empty crash buffer, process alive, radios in the state the user left them (Bluetooth off), and only the mprober's refusals against a serve-down box in the log. That is evidence the rationing *rests* without a surprise; it is **not** a power measurement. No current draw, charge delta or wake count was recorded.

OS-level energy limits are outside app control and are documented as such ([open-file-and-sharing-restore.md](open-file-and-sharing-restore.md), "MIUI / Android limitations"): MIUI battery saver, autostart rules and memory pressure can kill the process, and Android 15 rations `dataSync` foreground time to about six hours a day. At the cap `onTimeout()` drops the notification rather than crashing, leaving the engine alive while a Flit surface is open; the next cold open restores sharing from the persisted `sharingDesired` row. The Options "Background battery" helper sends the user to system settings; it cannot change the ROM's policy. Keep-screen-on is a deliberate counter-trade: `FLAG_KEEP_SCREEN_ON` is held **only while a transfer is in flight** and only when the option is on (default true) (`MainActivity.kt:571-586`, `FlitEngine.transferActive`). It spends display power to keep a long transfer from being interrupted by sleep.

---

## 3. Worker cadences

All engine loops are `scope.launch(Dispatchers.IO)` with `while (isActive && seq == startSeq)`; a rebind bumps `startSeq` and retires every old loop, so no generation leaves a duplicate worker behind. None of these loops is a busy poll.

| Worker | Cadence | Work per beat / bound | Source |
| --- | --- | --- | --- |
| Registry pruner | 5 s | Drop peers older than `PEER_TTL_MS = 30 s`; `manual@` stubs exempt | `FlitEngine.kt:78-79,1364-1376` |
| BLE flipper | 5 s | Recompute AP/perms/radio/hidden/livePeer → stance; log only on real no-AP change | `FlitEngine.kt:1759-1836` |
| Sweep loop | 5 s tick, **walk gated by `nextSweepAt` = 120 s** | `sweepKeep` then `sweepOnce`; ≤24 knocks in flight; ranges >1024 refused | `FlitEngine.kt:81-88,1429-1447` |
| Sweep keeper | 15 s per found peer | One targeted re-register; 3 consecutive misses retire the row | `FlitEngine.kt:84-88,1459-1490` |
| Manual prober | 20 s | One `register` per typed address, 3 s timeout | `FlitEngine.kt:1384-1411` |
| Hunter | 2 s tick | Probes owed fingerprints not currently dispatching; backoff 2/5/15/30/60 s | `FlitEngine.kt:1612-1641`, `FlitRoute.kt:43-50` |
| Relay subscriber | reconnect 2 s, doubling to 60 s | Holds one parked subscription; on success resets to 2 s | `FlitEngine.kt:1716-1739` |
| Circle sync | 3 s first pass, then 30 s | Full-log repair to reachable peers; `CIRCLE_SYNC_MIN_INTERVAL_MS = 30 s` gate | `FlitEngine.kt:632-645`, `FlitCircleSync.kt:40-41,454-515` |
| Circle file delivery | 30 s tick | Retries rows with a pending/failed recipient, per-sha backoff | `FlitEngine.kt:611-630` |
| Mirror watcher | event-driven + start scan | `FileObserver(CLOSE_WRITE or MOVED_TO)`; notice polls MediaStore 15 × 200 ms | `FlitEngine.kt:1973-2120` |
| Intake spool | one-shot | Waits up to 20 s in 250 ms steps for a context, then copies streams | `FlitEngine.kt:2884-2923` |
| UDP announce (protocol) | 3 s | LAN multicast announce while discovery is allowed | `protocol/.../discovery/UdpDiscovery.kt:82,157` |
| Bluetooth accept loop | retry 2 s → 60 s doubling | Listener acquisition/handshake; 30 s handshake timeout | `FlitBluetooth.kt:331-361`, `[bluetooth-carrier.md](bluetooth-carrier.md)` |
| Spool reaper | engine start only | Deletes spool-named cache files older than 24 h not whitelisted by a queue token | `FlitSpool.kt:22-52` |

The hunter schedule deserves a note: `huntDelayMs(attempt) = listOf(2_000, 5_000, 15_000, 30_000).getOrElse(attempt) { 60_000 }`. An owed fingerprint therefore costs a handful of directed dials per beat for as long as the row survives, not a probe every 2 s; cancelling the row removes its bookkeeping on the next tick (`huntNextAt.keys.retainAll(owed)`, `FlitEngine.kt:1632-1634`). The relay is tried only after every direct candidate fails (`FlitEngine.kt:1689-1699`), so a live direct answer never pays the relay's 5 s register budget.

---

## 4. Circle: watermarks, fan-out and full-log repair

### 4.1 Bounds (code reality)

`FlitCircleStore.EVENT_CAP = 500` (`FlitCircleStore.kt:72`); `CircleCore.MAX_EVENTS_PER_REQUEST = 1024`, `MAX_SEEN = 4096`, `MAX_BODY_CHARS = 16 KiB`, `MAX_REQUEST_BYTES = 8 MiB`, field caps 256 chars, signature cap 1024, cert cap 8 KiB (`protocol/.../circle/CircleCore.kt:29-50`). A single event body is a small JSON object; file drops are `{name, size, sha256}` only — no bytes, no spool path. The host refuses more than 1024 events in one request (`ServerCore.kt:957-965`). PanicCore uses a separate 1024-id seen set and 3-hop cap; TLS pin cache is an LRU of 512 (`TlsIdentity.kt:163-177`). All of these are memory ceilings, not measured working sets.

### 4.2 Delta posts vs the full-log repair tick

A `CircleWatermark` is `(lamport, id)` — the greatest event a peer acknowledged (`FlitCircleSync.kt:91-100,126-127`). The tie-break on `id` makes the delta stable when two events share a Lamport value.

- **Fan-out on a post** (`fanout`, `:656-716`) is delta-only: `circleDelta(current.events, watermarks[fp])` offers only events newer than that peer's watermark, capped at `MAX_EVENTS_PER_REQUEST`. An OK advances the watermark; a refusal leaves it untouched and applies per-peer backoff. An empty delta still makes one tiny probe so the answer can be folded and the watermark stays current. Dials are bounded by `CIRCLE_PEER_DIALS = 4` (`:88-89`), not by roster size.
- **The calm repair tick** (`sync`, `:454-515`) deliberately ignores watermarks: `circleRepairBatch(state.events)` posts the bounded full log to each reachable peer, and the answer is folded. This is what heals a delta a peer missed. It is gated by `CIRCLE_SYNC_MIN_INTERVAL_MS = 30 s` and skips backed-off peers.
- **Per-peer backoff** is `min(5_000 shl n, 120_000)` with `n` clamped 0..5 (`:43-54`). Because the first recorded refusal is attempt 1, the real delays are 10 s, 20 s, 40 s, 80 s, 120 s, then 120 s. Any answer resets the peer's misses and watermark book stays current; departed fingerprints' bookkeeping is pruned on a tick (`:729-737`).
- **Host side no-op** (`FlitCircleHost.isEvidence`, `FlitCircleHost.kt:106-115`): a sync that only echoes the stored log is not "evidence" and stays out of the noisy diagnostic path. A plain echo still costs a verify-and-fold pass, but no state change and no log line.

### 4.3 File drops ride a separate, calm path

A file announcement is signed and fanned out like any other event. The bytes are **not** put on the send-later queue: the sender runs `pathOutgoing` through `sendFiles` with `multi = false` per current member, under one `Mutex` per SHA (`FlitEngine.kt:507-609`). Terminal statuses are `SENT`, `REFUSED`, `FAILED`; a thrown carrier or no route leaves the recipient `PENDING` (spool kept, owed a retry). The 30 s worker (`:611-630`) re-offers rows with a pending or failed recipient. The per-SHA backoff is `min(60_000, 10_000 shl min(attempt, 3))` with the first recorded attempt = 1, i.e. **20 s → 40 s → 60 s cap** (`:603-605`). The spool is deleted once every current recipient is terminal (`:599-601`). Status is sender-local only; no gossip, so no extra wire cost for delivery state.

### 4.4 Inconsistency found while reading

[circle.md](circle.md) §"Event log (the only new core)" says that **every** `SYNC` — pull, outbound post or calm tick — "posts the full bounded local log" and that "the whole log travels each time". The code does not do that for posts: `fanout` uses per-peer watermark deltas (§4.2 above), and only the calm repair tick re-offers the full bounded log. The document's "store-and-forward ... a lost one-shot is repaired by the next post or tick" is true of the **tick**, not of every post.

---

## 5. Concurrency caps

| Cap | Value | Where / why |
| --- | --- | --- |
| Sweep knocks in flight | 24 | `Semaphore(24)` in `startSweeper`, `FlitEngine.kt:1431` |
| Hosts per walk | 1024 | `FlitSubnet.hosts(cap = 1024)`, admission before materialization — a /16 is refused whole, `FlitSubnet.kt:27-42` |
| TCP dial filter / register budget | 250 ms / 400 ms | `tcpOpen`, `probeRoute`, `FlitEngine.kt:1577-1594`; a cold TLS handshake slower than 400 ms is invisible to the sweep |
| Circle peer dials | 4 | `CIRCLE_PEER_DIALS`, `FlitCircleSync.kt:88-89` |
| Files sent concurrently | 4 | `ProtocolClient.sendFiles(parallelism = 4)` + `Semaphore`, `ProtocolClient.kt:223,299-303` |
| Turbo streams per file | 4 client / 6 server | `TURBO_MAX_STREAMS = 4`, split target 16 MiB, min file 16 MiB (`ProtocolClient.kt:682-686`); receiver advertises 8 MiB chunks and caps at `MAX_TURBO_STREAMS = 6` (`ServerCore.kt:1103-1113`) |
| Upload attempts | 3 | `ServerCore.MAX_ATTEMPTS = 3` (`:1103`); client mirrors it in its retry loop (`ProtocolClient.kt:402`) |
| Circle seen-id memory | 4096 | `CircleCore.MAX_SEEN` (`:29`); survives the 500-event trim |
| Panic seen-id memory / hops | 1024 / 3 | `PanicCore.kt:18,27` |
| TLS pin cache | 512 LRU | `TlsIdentity.MAX_PINS` (`:165-177`) |
| One dispatch per peer | 1 | `dispatching` set, claimed *before* spooling (`FlitEngine.kt:2154-2166,2246`) |
| One delivery pass per SHA | 1 | `circleDeliveryLocks` Mutex map (`FlitEngine.kt:374-377,557-560`) |

The client cap of four is what the README and Play listing mean by "up to four parallel streams". The server accepts up to six, but the shipped client never opens more than four; the measured 512 MiB multi run gained nothing over single stream, so the extra capacity is headroom, not a speed feature.

---

## 6. Digest cache

The received ledger has no digest column. `FlitEngine` maintains `receivedDigests`, a `ConcurrentHashMap<String, String>` keyed by `path ?: uri` (`FlitEngine.kt:210-236`):

- `digestOf(r)` streams the file/uri through `MessageDigest("SHA-256")` with a 64 KiB buffer and returns `null` on any failure, so a stale ledger path can never crash the caller (`:222-236`).
- On each receive, `addReceived` first publishes the row (ledger capped at `.takeLast(20)`) and persists it, then computes the digest **off the UI thread** on `Dispatchers.IO` and calls `refreshLandedShas()` (`:271-280`). "Open file" in Circle is therefore an O(1) set lookup after a short warm-up.
- On a cold process, `loadReceived` rebuilds the cache from the persisted (at most 20) rows, again on IO (`:294-310`). Cost is bounded by the ledger size but is a full read of each landed file — up to 20 sequential hashes at boot, one pass each; not measured in wall-clock terms.
- `relocateReceived` carries the digest across a copy into SAF/Downloads by moving the cache key rather than re-hashing when possible (`:282-292`).
- `circleReceivedFor(sha)` walks at most 20 ledger rows on IO, consulting the cache first and falling back to `digestOf` only on a miss (`:703-719`). `CircleUi` normalizes the landed set once with `remember` (`CircleUi.kt:148-156`), so hex case never causes a repeated comparison.

`refreshLandedShas()` rebuilds the whole `_circleLandedShas` set from `receivedDigests.values` on every mutation (`:216-218`). With a 20-row ceiling that is a trivial allocation, but it is a fresh Set identity — the memoized card state keys off the Set value, not its reference (§7).

---

## 7. Memoized tallies and `StateFlow` content-diffing

### 7.1 Circle feed tallies

`CircleUi.computeFeedData` (`CircleUi.kt:1048-1252`) is the deliberate answer to a real quadratic path. Previously each poll/kick card walked the whole event log once per member and re-parsed vote bodies on the main thread; a new `events` list recomposed every card and multiplied the work by the card count. Now:

- `parseEvent` calls `parseToJsonElement` **exactly once per event body** and shares the resulting `JsonObject` between the visible card and the tally pass (`:1091-1098`).
- `computeFeedData` then derives **every** poll and kick tally in one pass, using "latest vote per author per subject" maps keyed by `(lamport, id)` tie-break (`:1163-1251`). Zero-vote polls are kept so their cards show 0.
- The screen memoizes with `remember(events, members, selfFp) { computeFeedData(...) }` (`:157-164`). Compose compares these keys with **value equality**, so a refresh that republishes an unchanged log does not redo any parse or tally.

Vote identity rules (raw, non-lowercased types; `lamport >=` ties to the later event; option >= 0; an open/admin kick with no explicit answer counting as the author's yes) are preserved exactly — this is a cost refactor, not a semantics change.

### 7.2 StateFlow content-diff in the engine

`refreshCircle()` (`FlitEngine.kt:381-400`) is called after every sync tick, post, delivery status change and fold. It loads persisted state and builds a small **signature** from: circle id, key, event count + last event id, member `(fp:role)` list, rekey generation, and delivery `(key:statuses)` list. If the signature equals `lastCircleSignature`, it returns before touching any `StateFlow`. That is the content-diff: a calm 30 s tick that discovers no change causes **zero** `_circleState` / `_circleEvents` / `_circleDeliveries` writes and therefore zero Compose invalidations. `_circleEvents` is only republished as `CircleCore.order(events)` when the signature actually moved.

The same idea exists in two smaller places: `FlitCircleHost.isEvidence` (§4.2) suppresses diagnostic noise for a log-echo sync, and `FlitProgress.paint` assigns `_transfer` telemetry on every progress callback while gating the expensive notification rebuild to 350 ms (§1.3). The received ledger and queue StateFlows use bounded list replacement (`.takeLast(20)` for received; the queue is bounded by outstanding rows), so republishing is cheap even when it is unconditional.

---

## 8. Rescue sweep, keeper and spool/replay costs

### 8.1 What a sweep round costs, and what was actually seen

A round fires only while discovery is silent (no live non-manual row), never in hidden mode, and at most once per 120 s. It walks the device's own Wi-Fi subnets (station and SoftAP, VPNs excluded, IPv4 only), skipping `manual@` addresses. Each host first gets a 250 ms TCP connect filter, then a `register` with a 400 ms budget, TLS first for a TLS route (plain first otherwise). Up to 24 knocks run concurrently; a single range beyond 1024 hosts is refused before allocation.

On-device evidence ([bluetooth-carrier.md](bluetooth-carrier.md), "Rescue sweep on hardware", 2026-11-14): a `/24` produced `253 hosts, nothing found in 2774 ms`, and the next logged empty round was ~120 s later (the throttle read from timestamps, since empty rounds log once). A dial-only box was found unassisted: `found 1 (...) in 2773 ms` with no multicast, no typed address and no QR. A real defect was caught by that walk: the 120 s walk outlived the 30 s roster TTL, so a dial-only peer blinked; the keeper fixed it with a 15 s targeted re-register, three consecutive misses retiring the row. After the fix the row held at 2.5 min with exactly one found line in the log. Hidden mode produced **zero** sweep lines for 26 s (a round would have fired within ~10 s), and the self-dial case showed a cold TLS handshake outrunning the 400 ms register budget.

Pure-test evidence backs the pure parts: `FlitBleTest` duty/stance/hasLivePeer; `FlitSubnet.hosts` cap and prefix admission. There is no harness timing suite for the sweep itself.

### 8.2 Spool costs

Share-sheet intake copies every incoming stream into app cache **before** the user picks a peer (`spoolIntake`/`spoolUri`, `FlitEngine.kt:2884-2950`), so non-persistable grants can never expire and every picked file is queueable. The cost is one full write per shared file at intake plus one read during hashing and one during upload; file names carry `share|queue|crush` tags, a timestamp and a sequence number. A copy that dies mid-write is deleted immediately (`:2941-2947`), and a discarded intake deletes its copies (`:2916-2920`).

The start-of-engine reaper (`FlitSpool.kt`) deletes spool-named cache files older than `STAGING_WINDOW_MS = 24 h` unless an absolute path is whitelisted by a live queue token. It is a listing + stat pass at start, not a background worker. The day-long window is deliberate: a share-sheet detour that has not started the engine yet must not have its staged copy collected.

Queue flushes re-spool from source tokens on each attempt (`flushQueueFor`, `FlitEngine.kt:2163-2233`) and delete their copies on every outcome (`cleanSpools` in `finally`). The **per-peer claim is taken before spooling** for a hard reason recorded in the code and in [milestone A](milestone-a.md): a hunter answer every 2 s once started five flushes of one 800 MB shot, each spooling a full copy into cache; the near-full /data partition refused every copy and the old empty-spool branch dropped the row. Now one peer gets one spool and one wire attempt; a throw keeps the row and the tokens, never the spool path (`:2222-2226`).

Circle file drops use their own private spool under `cacheDir/circle/`, hashed at announce time and deleted only once every recipient is terminal (§4.3). The event itself carries no path.

### 8.3 Replay / resume costs

A resumed send pays one extra `/partial` probe on attempt 1 (`carried > 0 || attempt == 1`) and then streams only the missing tail. The receiver's progress is a `.part` plus a `.token` sidecar, swept back into the in-memory parts registry at start with an empty `senderIp` (see [milestone C](milestone-c.md)). Hardware evidence: 400,000,000-byte file killed 32% in, receiver restarted, sender resumed from exactly 127,959,040 bytes and landed hash-exact; the 2026-11-24 relay kill resumed from 31866880 bytes; the 300 MiB camera-shot kill resumed from 33980416 bytes ([transport-bluetooth.md](transport-bluetooth.md), A and E ladder entries).

Resume is deliberately narrower than "continue anywhere":

- a Wi-Fi↔Bluetooth switch changes the origin and `ServerCore.partsBase` refuses the part — clean full resend, no cross-carrier continuation ([bluetooth-carrier.md](bluetooth-carrier.md));
- a prefix near completion in a sparse multi-socket file is not advertised as a resume base — a late kill (~90% of an 800 MiB send) landed hash-exact but took the full-resend path;
- a cold TLS handshake slower than the 400 ms probe budget stays invisible to the sweep;
- non-persistable mixed sources cannot be checkpointed and remain best-effort (milestone C's stated edge).

On the receive side, API 29+ non-crushed wires stream straight into a pending `Downloads/Flit` row or a chosen SAF tree with a 256 KiB buffer — no temp file, no second copy (`openIncomingSink`/`sinkIntoTree`, `FlitEngine.kt:2958-3036`). Crushed (gzip) wires and old devices take the classic two-step part-file path because the whole body must be assembled before a one-shot decode.

### 8.4 Courier CLI: one-shot cost and bag copy

The `courier` command is one-shot, as `circle` is: each invocation loads `courier.state`, performs one operation, saves the whole state file and exits. There is no worker, timer or carrier loop behind it, so an idle courier node does no repeated work. `status` is read-only; `drop`, `carry`, `receive` and `sweep` each rewrite the whole state file once per invocation. The cost of an invocation is therefore one state read, one whole-file write and the operation itself; the write grows with the number of state rows, not with any parcel payload. That ledger is small JSON, one line per parcel, and admission to the bag is bounded by the `MAX_PARCELS` quota from courier-carry.md. No wall-clock or byte figure has been captured for any invocation.

The portable unit is the `courier-bag/` directory, copied by the operator with USB or any ordinary file copy; the CLI implements no transport of its own. A handoff costs a directory listing plus one read of each `<id>.parcel` at the source and one write at the destination. Parcel bytes are persisted verbatim as `CourierCarryCore.encode(CourierCarryCore.parcelOf(envelope))`, so the copy does no decode, re-encode, re-encryption or re-signing: its cost scales with the number and size of the parcel files, not with cryptographic work. Those sizes are structural caps only — `MAX_ENVELOPE_CHARS` (`12 * 1024`) from courier-envelope.md and `MAX_PARCELS` (`256`) from courier-carry.md — not measured sizes. `receive` then pays one `CourierCarryCore.decode` plus one `CourierEnvelope.open` (AES-GCM decrypt, RSA key unwrap, signature verification) per parcel addressed to the local fingerprint; a parcel for another fingerprint stays `pending` and is not opened. No timing, throughput or byte count has been measured for the copy or the open pass.

### 8.5 QR parcel frames: structural byte and frame caps

The 2026-12-03 Protocol layer ([qr-parcel-codec.md](roadmap/qr-parcel-codec.md)) defines the air-gap frame grammar: one opaque UTF-8 `String` per QR frame, EC level M and margin 2 in both CLI and app so both render the same bytes. It carries no camera, socket, persistence or clock dependency. Its limits are structural caps — bounds on how much can be framed and held — not measured sizes or timings:

| Constant | Value | Meaning |
| --- | --- | --- |
| `MAX_FRAMES` | 64 | Wire cap and app display cap on the number of frames in one parcel; `seq` space 1..64. |
| `MAX_CHUNK_BYTES` | 512 | Largest raw payload byte count in a single frame. |
| `MAX_AIR_GAP_PARCEL_BYTES` | 131072 | Defensive frame-layer cap (128 KiB), named apart from `CourierEnvelope.MAX_ENVELOPE_CHARS`. |

At `MAX_CHUNK_BYTES = 512` and `MAX_FRAMES = 64`, the largest frameable byte string is `64 × 512 = 32768` bytes (32 KiB). That is a reachable ceiling derived from the two caps, not a measured working set or a captured byte count. The `MAX_AIR_GAP_PARCEL_BYTES = 131072` ceiling sits above that reachable maximum, so it is a defensive guard: it bounds an oversized `total` field during `parse` before any reassembly, but with the shipped chunk and frame caps it never binds on honestly encoded output. `MAX_FRAMES = 64` is the same number as the app's `MAX_PARCEL_FRAMES = 64`, so every sequence the CLI emits is one the app will display; the two caps are kept equal by design.

Each frame repeats the parcel id, its `seq`, `count` and `totalBytes`, and carries a CRC32 over canonical bytes, so a captured frame can be checked on its own before the set is complete. `complete` verifies sequence, count, digest and recipient before decrypting through `CourierEnvelope.open`; it adds no second crypto path. None of this has a captured wall-clock, throughput or device byte measurement: frame rendering, camera decode and per-frame cost are unverified at this layer, and real-camera decode belongs to a later air-gap walk.

### 8.6 QR parcel CLI: PNG render and read cost

The 2026-12-04 CLI layer ([qr-parcel-cli.md](roadmap/qr-parcel-cli.md)) supplies the image step that §8.5 leaves unmeasured. `parcel encode` renders each opaque frame string to a **768 × 768 PNG** with zxing's `QRCodeWriter` at error correction level **M**, margin 2 and UTF-8; `parcel decode` reads each `frame-%04d.png` back with `MultiFormatReader` through `RGBLuminanceSource` and `HybridBinarizer`, the same image shape `parse-qr` uses. Both commands are one-shot and offline: neither opens a socket, registers a route nor starts a worker, so an idle invocation does no repeated work.

The cost is qualitative and bounded by frame count, not by any captured figure:

- Encode pays one QR matrix render and one PNG write per frame, plus an in-memory render → decode → compare round-trip of that frame string before its PNG is written. No PNG is emitted until its own round-trip returns the identical string. With `MAX_FRAMES = 64`, one invocation is at most 64 such renders, writes and round-trips, each aimed at the fixed 768 × 768 raster; the frame count comes from the codec, with no extra CLI-side frame limit.
- Decode pays one PNG read and one QR decode per numbered image, then one `ParcelCodec.parse` per decoded string for the seen/duplicate/missing counts and a single `ParcelCodec.complete` over the collected strings. An image with no readable QR counts as one rejected image and does not abort the batch. A killed process leaves no output file, no partial bytes and no progress record.
- Verification inside `complete` (CRC32, sequence, count, digest and recipient gate) and the underlying `CourierEnvelope.open` (AES-GCM decrypt, RSA key unwrap, signature verification) run once per successful decode, not once per frame. The CLI adds no second crypto or digest computation.

The 768 × 768 size and EC `M` are CLI-side constants shared with `Qr.encode`, not a measured minimum or a captured frame budget; EC `M` trades redundancy for module density at that fixed raster. No wall-clock timing, throughput figure, CPU or heap sample, frame-time capture or device byte count has been recorded for rendering or reading a frame, and no camera capture belongs to this layer. The §8.5 caps remain structural bounds, not measured working sets.

### 8.7 QR parcel app: frame cadence and on-screen render

The 2026-12-07 App layer ([qr-parcel-app.md](roadmap/qr-parcel-app.md)) turns the codec frames of §8.5 into a screen sequence and folds camera reads back into one verified payload. It adds no new frame grammar and no second render format: the opaque strings come from `ParcelCodec.encodeParcel` and go straight to `Qr.encode`, which already fixes EC level `M`, UTF-8 and margin 2 (`Qr.kt:143-150`). The app-side numbers below are structural constants, not measured render times, frame times, camera throughput or power figures.

| Constant | Value | Meaning |
| --- | --- | --- |
| `PARCEL_FRAME_INTERVAL_MS` | `400L` | Dwell on one frame before advancing to the next or wrapping to index zero. |
| `PARCEL_FRAME_SIZE_PX` | `240` | Side passed to `Qr.encode` for an on-screen parcel frame (square raster). |
| `MAX_PARCEL_FRAMES` | `64` | App display/assembly cap, set equal to the codec's `MAX_FRAMES` so every emitted sequence is displayable (§8.5). |
| `PARCEL_PASTE_MAX_CHARS` | `16_000` | Cap on newline-separated frame text typed into the paste field when camera permission is denied. |

On the show side, `ParcelUi.kt` holds the immutable list of frame strings and a display index in a Compose `remember`, discarded on close. A timer advances the index by one every `PARCEL_FRAME_INTERVAL_MS = 400 ms`, wrapping from the last frame back to index zero; next/back controls move it manually. Each frame is rendered with `Qr.encode(frame, PARCEL_FRAME_SIZE_PX)` at the same EC `M`/margin 2 hints the codec and CLI use. At 400 ms dwell, a full 64-frame sequence nominally cycles in `64 × 400 ms = 25.6 s` before returning to frame 1, with no extra dwell on the wrap. That figure is duty-cycle arithmetic from two constants, the same kind of derivation as the BLE window in §2.2, not a captured cycle time; the real cadence on a device is not measured here.

`PARCEL_FRAME_SIZE_PX = 240` is the on-screen raster side, deliberately smaller than the CLI's 768 × 768 PNG in §8.6 because it is drawn into a Compose canvas rather than written to a file. Both paths call `Qr.encode` with identical hints (EC `M`, UTF-8, margin 2), so the module grid is the same and only the raster resolution differs. The 240 px value is a chosen size, not a measured legibility minimum or a captured frame budget, and no pixel-level scan study accompanies it.

On the scan side, the camera preview feeds the existing `CameraQrView` analyzer in `QrUi.kt:265-308`: a single-thread executor gated to one decode attempt per `120_000_000L` ns (~8 fps), Y-plane only through `Qr.decodeYPlane` with the plane's row/pixel strides and a rotation step. Each decoded string is offered to `ParcelScanState`, which parses with `ParcelCodec.parse`, dedups by `(parcelId, seq)`, reports progress from the descriptor's `seq` and `count`, and closes with `ParcelCodec.complete(frames, recipientFp, identity)`. Bytes are written through an injected `(ByteArray) -> Unit` callback; the save name is local and no file name travels in a frame. With camera permission denied, a paste field feeds the same state holder up to `PARCEL_PASTE_MAX_CHARS`. These are structural paths and caps: no decode rate, reassembly time, byte count or current draw has been captured for them.

No frame-time capture, render wall-clock, camera-decode throughput, reassembly timing, battery/current sample or device scan result belongs to this layer. Real-camera capture is the 2026-12-08 air-gap walk; until then camera decode and end-to-end frame delivery stay unverified, consistent with §8.5, §8.6 and §10.

---

## 9. Tradeoffs, stated

| Choice | Buys | Costs |
| --- | --- | --- |
| 10 s on / 45 s off BLE window | Roughly 18% duty on the no-AP radio; no always-on scan | Up to ~45 s to first discovery after the window closes; a peer arriving in the off phase waits |
| Scan stops when a live peer exists | No battery burn after discovery succeeded | A peer that ages out is not re-scanned until the next on window |
| Wi-Fi always wins; relay last; Bluetooth opt-in | No silent carrier switches; cheapest carrier first | Bluetooth and relay capacity are reachability, not speed; relay adds a 5 s register budget only when nothing direct answers |
| Delta fan-out + full-log repair tick | Bounded wire bytes on posts; bounded healing on ticks | A missed delta is not repaired until the next 30 s tick |
| Bounded 500-event log, 4096 seen ids | Fixed memory and wire caps | No search beyond the log; a very old event id out of the log is still remembered as seen |
| Digest-at-receive + boot rebuild | O(1) "Open file" lookup on the hot path | Up to 20 full-file hashes on a cold process; done on IO, not measured |
| Memoized feed tallies | One JSON parse per event, no per-card log walks | A changed `events`/`members`/`selfFp` instance recomputes the whole feed once |
| Spool at intake | Grants can never expire; queue is checkpointable | One extra full write + read per shared file; 24 h reaper window holds cache |
| Direct-to-storage receive | No temp file, no double write | Crushed wires and pre-API-29 still take the two-step path; a failed SAF create deletes the half-written document |
| Keep-screen-on during transfers | Long sends survive sleep | Display power while a transfer is active, default on |
| Four client streams | Some headroom on fast links | No measured gain here; server can take six but client never opens them |

---

## 10. What is not measured (explicit)

- **Battery/energy**: no current, charge or wake measurement of any kind. Duty cycle and dormancy are design arithmetic; the 8-minute screen-off soak is a stability observation, not a power result.
- **Bluetooth/RFCOMM throughput**: no number. The bench box has no Bluetooth adapter and there is no second phone, so a real phone-to-phone RFCOMM transfer is unverified ([bluetooth-carrier.md](bluetooth-carrier.md)).
- **CPU, memory, allocation and frame time**: no profiler traces, no heap dumps, no jank figures. The memoization and content-diff sections describe complexity changes, not measured frame budgets.
- **Storage write amplification**: the spool path adds one cache copy; direct receive removes one. Neither was quantified on device.
- **Wi-Fi Aware**: feature absent on the rig phone (`adb shell pm has-feature android.software.wifi.aware` → false, 2026-11-21); no Aware carrier exists to benchmark.
- **Multi-member mesh**: the 2026-11-26 three-node baseline walk ([2026-11-26-three-node-baseline.md](completions/2026-11-26-three-node-baseline.md)) ran a real 3-identity Circle on device (phone + two box CLIs), so this is no longer two-node-only evidence; the fan-out *cost* at three or more identities is still not measured ([circle.md](circle.md)).
- **Phone→host Circle fan-out on device**: behaviour is test-evidenced only, with an injected carrier; the 2026-11-25 walk could not produce a clean peer-side confirmation, so no device evidence exists and its live wire cost is not measured ([circle.md](circle.md)).
- **Cross-ROM/RAM behaviour**: MIUI autostart/battery rules and the Android 15 `dataSync` ~6 h cap are ROM/OS limits; no other device was available.
- **Sweep beyond one subnet**: the hardware walk was a `/24`; the 1024-host cap is a pure admission rule, not a measured worst case.
- **Wire-byte totals** for Circle repair beyond the structural caps (1024 events/request, 8 MiB request body, 16 KiB event body); no traffic capture was published.
- **Courier CLI one-shot and bag copy**: no wall-clock timing, invocation count or byte count was captured. The one-shot read / whole-file-write shape and the directory-copy handoff in §8.4 are structural descriptions, not measured costs.
- **QR air-gap frames**: the `MAX_FRAMES = 64`, `MAX_CHUNK_BYTES = 512` and `MAX_AIR_GAP_PARCEL_BYTES = 131072` limits in §8.5 are structural caps, not measured sizes or costs. The 32 KiB reachable maximum is arithmetic from the first two caps. No frame-render, PNG read, camera-scan, CRC or reassembly timing, throughput or device byte count has been captured; the 768 × 768 / EC `M` PNG render and read cost in §8.6 is described qualitatively only, and real-camera decode is not verified by this layer. The app-layer `PARCEL_FRAME_INTERVAL_MS = 400L` dwell, `PARCEL_FRAME_SIZE_PX = 240` raster, `MAX_PARCEL_FRAMES = 64` display cap and `PARCEL_PASTE_MAX_CHARS = 16_000` paste cap in §8.7 are likewise structural: the 25.6 s full-cycle figure is arithmetic from the dwell and frame cap, and no render, cadence, camera-decode or reassembly timing was captured.

---

## 11. Consistency notes

1. **circle.md "every SYNC posts the full bounded log"** conflicts with `FlitCircleSync.fanout`, which posts per-peer watermark deltas; only the calm `sync()` repair tick posts the full bounded log. Documented in §4.4.
2. **Historical test counts** in the milestone docs (76 / 86 / 115 / 302) are from their respective eras and do not match the current 396-test rig; they are not contradictions, but a docs index that states a single current number should say which era each older figure belongs to.
3. **README/Play "four parallel streams"** matches the client (`TURBO_MAX_STREAMS = 4`); the server's `MAX_TURBO_STREAMS = 6` is unused headroom from the shipped client's perspective.
4. **Circle delivery backoff** in `FlitEngine` starts at an effective 20 s (`10s << attempt`, attempt = 1), not 10 s; the Circle *sync* backoff starts at 10 s (`5s << 1`). The two schedules are different by design and are both stated exactly in §3/§4.3.
5. **QR frame cap vs app display cap**: the wire `MAX_FRAMES = 64` and the app's `MAX_PARCEL_FRAMES = 64` are the same number by design (§8.5). The frame-layer `MAX_AIR_GAP_PARCEL_BYTES = 131072` is named apart from `CourierEnvelope.MAX_ENVELOPE_CHARS`, so the air-gap byte ceiling and the envelope character ceiling are not conflated.
6. **App render side vs CLI PNG raster**: the app draws a frame at `PARCEL_FRAME_SIZE_PX = 240` into a Compose canvas while the CLI writes 768 × 768 PNGs (§8.6/§8.7). Both go through `Qr.encode` at EC `M`, UTF-8 and margin 2, so the module grid is identical and only the raster resolution differs — two sizes for two surfaces, not a conflict.
