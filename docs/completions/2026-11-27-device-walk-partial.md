# 2026-11-27 — partial feature walk on one phone (Device)

A physical phone and one Linux box acted as peers on the same Wi‑Fi LAN. This
note records only observations made on that phone during the session. The full
plan in `docs/roadmap/device-walk-2026-12-31.md` was not completed: several
flows were not exercised and are listed as not verified at the end.

## Rig

| Item | Observed value |
| --- | --- |
| Phone | Redmi `M2101K7BNY`, Android 17, IP `192.168.100.86` |
| Box | `192.168.100.12`, `flit-cli` 0.3.1 from commit `716b9aa` |
| Build on phone | v0.3.1, versionCode 4, APK SHA-256 `aeb6dafc…5f7ed` |
| Host identity | `C53120B19DFBBBBE0ABAB81DB69915D5D979CF90C7D084FA62C1D1F4AB2E838C` |
| Phone identity | `6A61F0ED794D3152BD9F037600EDD3C524250CC6DA8C6658DBC37820374D9EE4` |
| Circle | `f3925e55e5ef4228`, three identities after the CLI peer joined |
| Logs | `adb logcat -s FlitSend:I` on the phone; box `flit-cli` stdout |

The box ran `flit-cli serve` with the circle id and invite; a second CLI
process joined as a member with `--host-fp` pinned to the host fingerprint.
The phone joined pasting a v3 `flit://add` code into the Circle join dialog
after Wi‑Fi sharing was started.

## Observed flows

| Flow | Observation | Evidence |
| --- | --- | --- |
| Circle join with signed proof | Phone pasted the v3 invite; the feed opened with the host chip `C531-B5C709` and self chip `6A61-0FC3F0`, and logged `circle sync: full-log repair to 1 peer(s)`. The CLI peer reported three members. | DEVICE |
| CLI member message reaches phone | `circle post --text 'device-walk: CLI member message'`; phone logged `+1 event(s) from C53120B19DFB` on the next 30 s repair tick and rendered the message with author `A2B8-3E0139` and timestamp `23:41`. | DEVICE |
| Phone fan-out reaches a CLI peer | Phone sent `device-walk: phone member message`; logcat showed `circle fan-out: 1 new event(s), 1 peer(s)` and `ok to C53120B19DFB`. The CLI peer's `circle sync` showed the message under author `6A61F0ED`. | DEVICE |
| File landing hash-exact | `flit-cli send 192.168.100.86 flit-walk-drop.txt`; phone logged `offer`, `receiving`, `→ Downloads/Flit/flit-walk-drop.txt`. On-device `sha256sum` returned `31b5679bb0d513228516f875a9e5ae90a2b9ee4bbbbca298dbd68667e7e30b4d`, matching the source file. | DEVICE |
| Pad put and search | CLI peer published `circle pad put --key walk-key --text 'device-walk pad line'`; the phone pad sheet showed `1 line`. Typing `walk` in the pad search field changed the header to `1 hit`. | DEVICE |
| Poll and vote | CLI peer published `device-walk poll` with `yes`/`no`; the phone rendered both options with zero tallies. Tapping `yes` produced a phone vote event and a host fan-out; the CLI peer's `circle sync` showed `vote {"pollId":"ecbd32c6ba3a","option":0}` and `tally … [0=1]`. | DEVICE |
| QR parcel encode | The box encoded the same 33-byte file into 7 PNG frames at 768 px with the phone certificate, signer `AFF5EBF9…`, recipient `6A61F0ED…`. | CLI on box only |

## Observation to keep

The Circle feed rendered the pad event as `Unsupported circle event / pad`,
even though the pad sheet itself showed the line and search worked. The feed
and the pad surface are separate views; this is a display observation, not an
evidence of data loss. No change was made during the walk.

## Not verified on this run

- Receipt minting and confirmation: the only file sent was a direct peer send,
  not a keyed Circle drop, so `circle receipt` refused with `no key id for drop`.
- Officer-only REKEY and departure rotation: the CLI peer was a plain member
  and the host was driven by `serve`, so no officer action was performed from
  the phone.
- Courier handoff and QR parcel scan on the phone: frames were generated on the
  box, but there was no second screen for the phone camera to scan and no
  courier bag exchange was run.
- Two-phone RFCOMM, Wi‑Fi Aware, MIUI autostart/battery behavior, and a mesh
  larger than three identities.
- Any timing, throughput or power measurement.

## Evidence level

CODE, PURE and SIM-HARN evidence for the shipped build is in `docs/TESTING.md`.
This note adds DEVICE evidence only for the flows in the table above. The items
in the not-verified list stay at their prior level or remain unverified; no
finality is claimed for the full walk.
