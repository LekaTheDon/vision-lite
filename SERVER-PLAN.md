# Vision: unique logic off the APK, out of the grey areas (plan, 2026-11-28)

User decisions:
- Unique logic (Vision TB, recommendation engine) runs ONLY on the home laptop (vision-server). The Cloudflare Worker
  (vision-meta) is the public front: auth, cache, last-good answers while the laptop is off. Nothing unique in the
  Worker or the APK.
- Grey areas the DebridStream way (study: ~/work/debridstream-study/STUDY.md).
- Sports: undecided (maybe dropped for VOD + Live TV focus). Keep it dormant-gated, no new work on it.

## Shape
    app (TV / phone)  --HTTPS-->  Worker (vision-meta: app key, cache, fallback)  --Tailscale Funnel-->  laptop: vision-brain
- Device does the calls that need the user's keys or IP (debrid cache checks, add-on fetches, IMDb). It never sends
  debrid keys to the server (rule unique-logic-on-server).
- Server decides: what to search, how to match, how to rank, what to recommend. The APK keeps only a thin client.

## Phases
0. Link: Tailscale login + Funnel on vision-server (user approves once on the website); Worker routes /v1/brain/* to it
   with secrets BRAIN_URL/BRAIN_KEY; health page; last-good cache in the Worker.
1. Grey areas (APK ships with no sources):
   - Every built-in scraper/indexer host (Torrentio, Comet, Meteor/Jackett fortheweebs, apibay, nyaa, animetosho,
     torrents-csv, Zilean, StremThru, ArabP2P, SeaDex…) out of the APK: the source list becomes a signed JSON from the
     server ("source recipes"), changeable/removable without an app update.
   - User add-ons: one-time agreement screen (user confirms the add-on's content is legal / theirs to use), then the
     value + Vision TB cost check (memory maintained-sources-user-addons-optional).
   - The app sells the experience (Pro), never content; the add-on site stays separate and unpromoted.
2. Vision TB on the server: device sends what it found (names, sizes, hashes, which are cached on the user's debrid);
   server returns the ordered picks + the episode/season match verdicts. Recipes (which sources, which queries) come
   from the server too. Then tb/ (DiscoveryPipeline, DiscoverySources, AnimeSources, ReleaseMatcher, TbSources,
   EpisodeVouches, AddonHarvest, ArabP2P…) leaves the APK.
3. Recommendations on the server: device sends the profile's taste signals (likes, drops, watched, follows; no keys);
   server returns rows / picks / reasons. Then recs/ (Recs, RecsLogic, Scout, Interests, Moods, AiPicks…) and the
   ranking parts of AutoPick leave the APK.
4. What stays in the APK gets R8 obfuscation; check the release APK with a decompile (on GitHub, not Termux) that no
   unique rule is left.

Costs to watch (no-bloat rule): one round-trip per Play (picks) and per Home build (rows); Worker cache keeps Home
instant; offline/laptop-down = last-good answers, then plain order (biggest 4K first), never an error screen.
