# Flit — Token Spine (adopted system: Material 3 Expressive)

Per craft-intent "ecosystem briefs use the official system": Jetpack Compose **material3 1.4.0**
(BOM 2026.06.00). This file records which tokens Flit uses and the few places Flit adds its own.
Values are from m3.material.io / the M3 token sets; nothing invented unless marked **ours**.

**Craft Read (2026-10-27):** native Android app UI (full M3 Expressive reskin) for a single-owner
local file-sharing user, product language, Material You dynamic color + Google-blue static
fallback, variance 4, signature bet: FAB-paired floating dock whose active screen is marked by a
filled icon-button morph.

## Color (semantic layer = M3 color roles)

Light and dark are both intentional; `isSystemInDarkTheme()` decides; no in-app toggle (brief §6).

- **API 31+**: `dynamicLightColorScheme` / `dynamicDarkColorScheme` (Material You).
- **API < 31** — static Google brand-blue (the static scheme Google's own apps show), over M3
  baseline roles for everything not listed:

| Role | Light | Dark |
|---|---|---|
| primary | `#0B57D0` | `#A8C7FA` |
| onPrimary | `#FFFFFF` | `#062E6F` |
| primaryContainer | `#D3E3FD` | `#0842A0` |
| onPrimaryContainer | `#041E49` | `#D3E3FD` |

Accent budget: the `primary` family is the single accent; ≤5 placements per viewport. No
gradients, no colored pills on numbers.

Deliberate color exceptions:
- Peer avatar hues — `SheetUi.hueFor` stable per-fingerprint palette = user content, not chrome.
- QR code canvas stays pure black/white for scan reliability.
- Transfer state never gets decorative color that contradicts the truth (brief principle 2).

## Corner radius — M3 10-step scale

| Step | Dp | Use |
|---|---|---|
| none | 0 | blocks, app bar |
| extra small | 4 | text field default |
| small | 8 | snackbar, dense chips |
| medium | 12 | mid-size containers |
| large | 16 | cards, banners |
| large increased | 20 | roomy cards |
| extra large | 28 | dialogs, sheets |
| extra large increased | 32 | hero containers |
| extra extra large | 48 | shell-scale containers |
| full | fully rounded | buttons, FAB, dock, toolbar, avatars |

Nested rule: inner radius = outer − padding. Dense piles never get big corners.

Docs-verified component geometry: toolbar 64dp, fully rounded, 16dp window margin; buttons 40dp
full-round, pressed shape-morphs 8–16dp by size; switch track 52×32 (handle 16→24dp); loading
indicator 48dp. Deliberate (ours, on the 10-step scale): history cards and the share banner use
large (16); peer avatars are circles; everything else keeps MaterialTheme defaults.

## Spacing — 4pt grid

16 window edge margin (toolbar margin, docs-verified); 16/24 screen content padding; 8 between
list rows; 24–32 between sections; 12 FAB↔toolbar gap (bars.md pairing); bottom docking respects
the nav-bar inset plus ≥16.

## Typography

M3 default scale (Compose `Typography()`), sentence case everywhere. **Emphasis** (size tactic):
semibold/bold reserved for the status headline, selections, and primary verbs. Byte counts and
timings render with tabular figures (`"tnum"` feature setting). System font only — no second
faces; the platform is the brand (brief principle 4).

## Motion — Expressive (`md.sys.motion.spring.*.expressive`, exact)

| Spec | damping | stiffness |
|---|---|---|
| default spatial | 0.8 | 380 |
| fast spatial | 0.6 | 800 |
| slow spatial | 0.8 | 200 |
| default effects | 1.0 | 1600 |
| fast effects | 1.0 | 3800 |
| slow effects | 1.0 | 800 |

Contract: effects (color/opacity) never overshoot; shape/corner is spatial and may bounce. Pressed
shape-morphs use fast spatial, entrances default spatial. **Hero moments (max 2):** the Send
FAB/toolbar entrance, and the engine start/stop morph. Entrance stagger (ours, no official value):
50 ms. Exits ~75% of their entrance equivalent. `MaterialExpressiveTheme`
(`LocalUsingExpressiveTheme` on) so components pick up the expressive scale.

## Component tokens (ours — where Flit substitutes because material3 1.4.0 stable lacks them)

| Component | Decision | Status |
|---|---|---|
| Dock | `HorizontalFloatingToolbar` geometry from bars.md: 64dp, full-round, 16dp margin, FAB gap 12, 40dp icon items, active = filled (primary/onPrimary), inactive = standard icon button | own `FlitDock` — official composable not yet in stable 1.4.0; migrate when it ships |
| Short waits (200 ms–5 s) | `CircularProgressIndicator` 48dp as loading indicator | official expressive `LoadingIndicator` not in stable; substitute marked |
| Long waits (>5 s) | determinate `LinearProgressIndicator` | expressive wavy variant not in stable; substitute marked |
| Peer rows | expressive list row + circle avatar (initials), trailing actions as icon buttons | `SheetUi.initials` / `hueFor` reused |
| Received items | M3 filled cards, 16dp corners, list-style single column | docs: weapon variant is styling-only |
| Options | list rows + M3 `Switch` (52×32) | input.md |
| Note dialog | M3 text field in `AlertDialog` | containment.md |
| Snackbar | inverse-surface colors, one at a time, 4–10 s, anchored above the dock | feedback.md |

When material3 ships `HorizontalFloatingToolbar` / `LoadingIndicator` / `MaterialShapes` in a
stable line, replace the marked substitutes — geometry tokens above were kept component-faithful
on purpose so the swap is mechanical.
