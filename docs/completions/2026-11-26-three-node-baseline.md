# Three-node Circle baseline — 2026-11-26

## Nodes

| Node | Surface | Identity prefix | State |
|---|---|---|---|
| A | phone, Flit app, Circle host | `992AD6C9C2F2` | app `FlitCircleStore` |
| B | box, one-shot `flit-cli` | `2A61BF692FD3` | `FLIT_HARNESS_HOME=/tmp/opencode/bot-b` |
| C | box, one-shot `flit-cli` | `491821459AC1` | `FLIT_HARNESS_HOME=/tmp/opencode/bot-c` |

B and C used separate harness homes. No box identity ran a listener during the walk.

## Circle

ID `a611e3ae447f47cb`, invite
`87c8f829684f491aa59ae22caaace9296eb7d8f26e51451589e4b7bb98e30dd4`.
Decoded v3 QR from the phone screen:

```
flit://add?v=3&ip=192.168.100.86&port=53317&tls=1&fp=992AD6C9C2F2E3553505C5D620B74C2A57D80C0DC5601BC1B4BACC3E63FE4007&alias=Flit+%C2%B7+M2101K7BNY&mac=02%3A00%3A00%3A00%3A00%3A00&circle=a611e3ae447f47cb&invite=87c8f829684f491aa59ae22caaace9296eb7d8f26e51451589e4b7bb98e30dd4
```

No `key` parameter is present. `flit-cli parse-qr` decoded it at scale 1/1.

## Commands and observations

- `23:42:01.645` — phone: `circle: started a611e3ae447f47cb as owner`.
- B join: `joined a611e3ae447f47cb: 2 member(s), 0 event(s), key=52gQuBt6…`.
  Phone: `circle host: join 2A61BF692FD3 — 0 event(s)` at `23:42:36.338`.
- C join: `joined a611e3ae447f47cb: 3 member(s), 0 event(s), key=52gQuBt6…`.
  Phone: `circle host: join 491821459AC1 — 0 event(s)` at `23:42:37.506`.
- B post: `23:42:45 message 6a70c64b → 1 event(s), 3 member(s), gen=0`.
  Phone: `circle host: sync 2A61BF692FD3 — 1 event(s)` at `23:42:44.982`.
  C `circle sync` printed `2A61BF69 message {"text":"baseline-3-b"}`.
- Phone composer posted `baseline-3-phone`; the phone feed rendered it. Phone log:
  `circle sync: full-log repair to 2 peer(s)` and two `refused no answer` lines at
  `23:43:04`. B and C `circle sync` and their `circle.state` files never showed
  this event during the walk, including after the next repair tick at
  `23:44:04`. This is recorded as a DEVICE observation, not a concluded
  feature: a phone-host-authored message did not reach one-shot CLI pullers in
  this run.
- B poll: `23:45:10 poll 0d04cf50 → 2 event(s), 3 member(s), gen=0`.
  The CLI printed `Ship? (1 options)`; the stored body is
  `{"pollId":"f9fecbe61ba7","question":"Ship?","options":["no"]}`. Only one
  option survived the repeated `--option` flags. C voted `0` at `23:45:24`
  after a sync, the phone vote was tapped on the rendered `no` option, and C
  re-voted `1` at `23:45:43`. C sync printed both C votes and the phone vote;
  the tally keeps the latest answer per member.
- File drop from B: `23:45:55 file 6cd7bacf → 6 event(s), 3 member(s), gen=0`.
  Seed file `/tmp/opencode/baseline3.txt`, 25 bytes, sha256
  `386eec3481084279a28429dbee6df9aaac684191d33ae7d100acce80d6b188ab`.
  B printed `pending` for the phone and for C, then `sent` for the phone, with
  `file 386eec34…: [pending=1, sent=1] (status is local-only, never gossiped)`.
  Phone logged `receiving baseline3.txt` at `23:46:00.538` and
  `→ Downloads/Flit/baseline3.txt` at `23:46:00.624`. The landed file's sha256
  matched the announcement, the card showed `Landed here` and `Open file`, and
  the stored `file` event body keys were exactly `name`, `sha256`, `size`.
- Governance: the phone member sheet promoted B to `ADMIN`. B's synced log
  contains `{"targetFp":"2A61BF69…","role":"ADMIN"}`. B then ran
  `circle kick --target 491821…`, producing
  `23:47:29 kick 19cb083f → 9 event(s), 2 member(s), gen=1`. B's log contains one
  `rekey` event. C's later `circle sync` was refused:
  `!! sync refused: host refused: not-a-member`. The phone member sheet showed
  B as admin and two members; earlier event types remained in B's local log
  (`file`, `kick`, `message`, `poll`, `rekey`, `role`, `vote`).

## Evidence

- CODE: route shapes, closed file-body keys, replay bounds, roster gate and
  rekey semantics read from `protocol/` and `app/` sources.
- PURE: the rig is green at 396 tests (136 protocol + 228 app + 32 harness,
  0 failures) after a `--rerun-tasks` run. The earlier 302-test snapshot
  (82 protocol + 210 app + 10 harness) is historical.
- SIM-HARN: no new three-node harness case was added for this layer.
- DEVICE: Yes — this walk is device evidence for a 3-identity Circle, taken
  from phone logcat, UI dumps, `flit-cli` output and the two `circle.state`
  files on 2026-11-25 local time.

## Limitations

- Phone-host-authored message visibility to one-shot CLI pullers was not
  confirmed on this run. The payload reached the phone feed and stayed in the
  local log; no box pull returned it.
- The poll CLI kept one of two `--option` values. Vote re-answering and the
  phone tally were confirmed for that single option.
- One-shot box members have no listener or mailbox, so delay tolerance remains
  test-evidenced only.
- Outbound phone→host fan-out remains test-evidenced only; the phone-host path
  was not exercised in this walk.
- No second physical phone, no phone-to-phone RFCOMM and no live multi-phone
  mesh were available. Wi-Fi Aware is absent on this device.
