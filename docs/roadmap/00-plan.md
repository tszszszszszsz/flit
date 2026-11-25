# Next-build plan

Four additions are planned for Flit after v0.2.0. They are built in the order
below. Each one uses seams that already exist; none introduces an account, a
cloud service, telemetry or a payment path.

1. **Three-node baseline** — walk Circle with the phone and two CLI identities
   before adding transport features.
2. **Courier mode** — Circle members carry signed, encrypted parcels for
   absent members and hand them off on a later encounter.
3. **Air-gap QR parcels** — framed, signed QR sequences for small payloads with
   no radio at all.
4. **Per-drop content keys, delivery receipts and Circle pad** — privacy and
   coordination work that depends on the transport pieces above.

Detailed specifications are in the other files in this directory. The dated
order is in [01-schedule.md](01-schedule.md), agent assignments in
[02-waves.md](02-waves.md) and acceptance rules in
[03-quality-gates.md](03-quality-gates.md).

## Why this order

Circle already has signed events, deterministic ordering, dedup, a bounded log
and delta sync over `Link`. File drops already push bytes with `sendFiles` and
keep status local. The missing piece is time: two members who never overlap
online cannot exchange anything. Courier mode fills that gap using the existing
event log and carrier seam, so it is built first.

Air-gap parcels need the same framing and encryption rules but use QR frames
instead of a radio link. Doing them after courier mode avoids a second codec.

Per-drop keys and receipts change the meaning of existing file drops, so they
come after the transport is proven. Circle pad is a new event type on top of the
same transport and is last.

## Existing seams used

| Seam | Use |
|---|---|
| `CircleCore` event kinds and replay window | Parcel, key-wrap, receipt and pad events |
| `FlitCircleStore.update` | Atomic state under one lock |
| `FlitCircleSync.fanout` / repair tick | Delta handoff and bounded full-log repair |
| `Link` / `LinkProvider` | Byte handoff independent of carrier |
| `TlsIdentity` RSA keys and one signature path | Per-recipient encryption and receipts |
| `Qr.kt` / `flit-cli parse-qr` | Frame decode for air-gap parcels |
| `ProtocolClient.sendFiles` | Direct per-member byte delivery |
| `Harness` | CLI carrier and three logical nodes on the box |

## Out of scope for the whole plan

Wi-Fi Aware (hardware absent), multi-hop relay routing, bonded Wi-Fi plus
Bluetooth transfer, a desktop GUI and offline `.flitpack` bundles stay parked.
No remote revocation is claimed: a device that keeps an old drop can still open
it. New work does not change the v2.2 wire surface.
