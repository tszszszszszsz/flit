# 2026-11-26 — device install and launch smoke (Device)

A physical device was connected by USB and the v0.3.1 release APK was installed
and launched. This note records only what was observed on that device. It does
not record a feature walk: no peer, circle, transfer, carrier, receipt, pad,
courier or parcel flow was exercised.

## Device and build

| Item | Observed value |
| --- | --- |
| Device model | `M2101K7BNY` (Redmi), brand Redmi |
| Android | 17, SDK 37 |
| Build fingerprint | `Redmi/rosemary_global/rosemary:17/CP2A.260605.016/OS4.0.6.0.XBNMIXM:user/release-keys` |
| Package | `app.flit.share`, versionName `0.3.1`, versionCode `4` |
| APK SHA-256 | `aeb6dafcace5b120c859b0431ad593a059813eb097221f6f7c787dd73a55f7ed` |
| Source commit | `716b9aa` |
| ADB serial | `VWUO7HZ9LNU4QCAI` |

The device previously held version 0.2.0 signed with a different key. An
in-place update was refused with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, so the
old package was removed before the release APK was installed. A backup was
attempted first and produced no data: the shell account cannot read the app's
private directory and the device backup prompt was not confirmed. Local data
from the 0.2.0 install was therefore not carried over.

## Observations

| Step | Command or action | Observed result | Evidence |
| --- | --- | --- | --- |
| Install | `adb install -r app-release.apk` after removing 0.2.0 | `Success`; package manager reports versionName `0.3.1`, versionCode `4` | DEVICE |
| Launch | `adb shell am start -n app.flit.share/.MainActivity` | Activity became the focused window; no fatal exception or crash line in logcat | DEVICE |
| Render | screen captured after unlocking the device | Main screen showed the Flit dock, Circle, QR parcel, Nearby devices and bottom action bar with readable text and no blank or clipped surface | DEVICE |

The screen capture was taken at 2026-11-26 21:28 local time. The device was
charging at 48% and connected to Wi-Fi; the application reported "not sharing —
one tap from the dock". No permission was changed for this smoke check.

## Evidence level

CODE, PURE and SIM-HARN evidence for the shipped build is recorded in
`docs/TESTING.md`. This note adds DEVICE evidence for install, launch and
render only. Every flow in `docs/roadmap/device-walk-2026-12-31.md` remains
unperformed; none of those flows is claimed here. Two-phone RFCOMM, a Circle
mesh larger than three identities, Wi-Fi Aware and MIUI autostart behaviour
remain unverified on hardware.

## Limitations

- One device, one session, one build. No second phone, relay box or peer was
  used, so no transport path was observed.
- The previous install's local data was not preserved; this note makes no
  claim about migration from 0.2.0.
- No timing, throughput or power measurement was taken.
- The screen capture is an observation of layout and text only; it does not
  prove interaction semantics or accessibility behaviour.
