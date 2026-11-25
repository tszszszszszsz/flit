# Flit — Design Brief

## 1. Product purpose

Flit transfers files directly between an Android phone and nearby phones or computers on the same
Wi-Fi network — LAN-only, no accounts, no cloud, no server in between.

## 2. Primary user

A person sharing files among their own devices at home — bursty, seconds-long sessions ("send this,
done") on a mid-range Android phone, often in the evening, with zero tolerance for setup steps.

## 3. Principles (in conflict order — higher wins)

1. **The move is the product.** Every screen's top job is getting a file from here to there;
   anything that does not serve an imminent or just-finished transfer loses the space.
2. **Show the truth.** Real peer state, real progress, real byte counts, real errors — a fake
   spinner or a stale "online" chip is the one unforgivable UI sin here.
3. **Nothing gates the files.** No accounts, no setup wall, no upsell; every option stays reachable,
   and a redesign may reskin but never remove a behavior or option.
4. **Android's language, spoken well.** Material You, expressive springs, dynamic color,
   follow-system dark — delight comes from the platform's own patterns executed with craft, not a
   bespoke skin.

## 4. Success metric

From "I have a file to send" to a visible sent confirmation: one tap past whatever the system share
sheet forces. On the home screen, engine state, the primary peer, and the last transfer's outcome
are readable within ~3 seconds without leaving the screen.

## 5. Out of scope

- No internet relay, accounts, or cloud of any kind — LAN or nothing.
- No NEW transfer dashboards, batch managers, or history search — history is a plain list; the
  send-later queue is per-peer and that is enough.
- No NEW theme options: the look follows the system setting + dynamic color. (The pre-existing
  auto/dark/light option predates this brief and stays — a redesign may reskin but never remove. ·
  see principle 3.)
- No onboarding wizard, tour, or rate-me nag.
- No monetization surfaces, ever.

## 6. Learned constraints

- **2026-10-27** — Visual language is M3 Expressive + Material You, built with ui-craft discipline —
  native-Google feel, not stock-Material slop.
- **2026-10-27** — Accent and theme = "Google's one": dynamic color from wallpaper on API 31+;
  below that the static Google brand-blue scheme Google's own apps show; theme follows the system
  (no in-app toggle).
- **2026-10-27** — Motion is Expressive (springs, shape-morph press states), but zero behavior
  change — the visual rewrite is a pure reskin; the phase is look-only.
