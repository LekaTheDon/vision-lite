# Vision launch tracker (subscription, Play Store, Qatar first) — 2026-11-28

Launch model: DebridStream way (ships empty; owner-made add-ons on a separate site; user add-ons optional with a value/cost check
and consent). Proof of concept on the home laptop + Cloudflare up to ~100 paying customers, then a rented server.
App goes up only when the More-like-this school is finished (or dropped). From launch on, laptop + Worker serve users only:
no school classes, no training/test jobs.

| # | Area | Ready | Left |
|---|---|---|---|
| 1 | Smart autoplay per device + internet | 80% | Vision TB decided on the server; more devices proven (AM9, TCL so far) |
| 2 | Vision TB + recs on the server | 15% | link live; nothing moved yet |
| 3 | Own More like this | 25% | school running, target 80% of IMDb (Telegram @Vision_Class_bot) |
| 4 | Cast / sequels / bio without IMDb | 50% | bios missing; app still asks IMDb |
| 5 | Sources the DebridStream way | 20% | built-in scrapers out of the APK; add-on site; consent screen |
| 6 | Setup for non-tech users | 40% | setup screen in today's look; one guided flow |
| 7 | Accounts + sync | 10% | profiles/pictures per device today |
| 8 | Subscription (Play Billing, trial, server entitlement) | 0% | |
| 9 | Play listing (privacy, data safety, rating, no Sports streams) | 25% | needs 5 |
| 10 | Code that's fully yours (52% others' GPL) | 15% | OWN-PLAN.md |
| 11 | Laptop launch hardening | 20% | power-on after outage, Telegram down/up alerts, nightly backup to private GitHub, updates without reboot, Worker last-good |
| 12 | Stability + polish | 65% | focus/Back regressions, own crash reports |
| 13 | More devices (Fire TV, Shield, Google TV) | 30% | |
| 14 | Phone app | 60% | onboarding look, full setup from phone |
| 15 | Arabic UI | 0% | parked — needed at launch? (user) |
| 16 | Data licences (TMDB commercial, IMDb data in the star) | 10% | before charging |

Worker free tier: 100k calls/day; ~100 users ≈ 30–80k/day. $5 plan only when the free limit is really hit.

## Weak boxes, NuvioTV-Lite's lessons (user 2026-11-28: after seek thumbnails)
1. [x] Ship a Baseline Profile (fast from first launch on every sideloaded box, no adb compile).
2. [x] Light/Standard: posters RGB565, hardware bitmaps off.
3. [x] Light: no looping loading animations (static).
4. [x] Size caps on every in-memory list/map (title facts, anime pages, art).
5. [x] (already) Catalogue rows: in-memory cache + one request in flight per row.
6. [x] (none exist) Light: no background poster re-checks.
7. [x] (clean) Release: no debug logging / per-frame work.
8. [x] (reviewed) Startup: everything lazy, nothing blocks the first screen.
Done in Lab 7f98061 (0.41.33), untested on a weak box.

- [ ] When finished and the user asks: build a shared app (DEV=0) and send it to the user for testing.
