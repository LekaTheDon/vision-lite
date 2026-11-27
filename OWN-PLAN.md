# Vision Lite — "fully mine" plan (to-do)

Started 2026-11-27. Goal: a sellable subscription app (DebridStream level) where every line is our own,
snappy in every hub. Audit: ~/work/lite-audit/REPORT.md (re-scan: `python3 ~/work/lite-audit/scan.py`).

## Rules for every rewrite
- [ ] Clean room: write a behaviour spec first, then fresh code with the old file closed. Never paste or "port as is".
- [ ] Snappy first: one network call per screen where possible, nothing runs until the hub is opened,
      focus moves in one frame, images decoded at drawn size, no motion. Every change states its speed / size / memory cost.
- [ ] No bloat: no feature, option, class or dependency that isn't needed. Smaller than what it replaces.
- [ ] One look: every hub uses the app's own Kit / Look / Palette (ui/), no hub-specific theme.
- [ ] Re-scan after each section: that section must show 0 matched lines.
- [ ] Dormant until enabled; Row entry = first card; Back lands exactly (existing rules hold).

## Order
1. [ ] **Sports** — rewrite first, in the app's look and skin (replaces 36 files / 12,806 lines; target ≤ 3,500).
   - [x] Spec: spec/SPORTS.md (2026-11-27), all features kept + Refresh channels everywhere.
   - [x] Replace the 345 KB ReSeq vocabulary (resources/sports-taxonomy.json) with our own ≤ 30 KB data.
   - [x] Replace the nuvio-data source (non-league football, wrestling) with our own data / TheSportsDB.
   - [x] Data: ESPN scoreboard only for followed leagues + on-screen league, one loop, stops when off screen.
   - [x] Channel matching: own matcher (guide → channel name → league network), no Gemini unless switched on.
   - [x] Screens on Kit: Live now / Today rows, game page, My Teams picker, tables — same cards, rows, focus as Home.
   - [ ] Delete old sports/ package; re-scan 0%; build; user tests on TV.
1b. [ ] **Home rewrite: flash-fast Up/Down** (user 2026-11-27: "a full up and down uninterrupted" is the goal; also
       removes the Echo-copied Home code). Spec to write: spec/HOME.md. Causes found in Echo.kt today:
   - every focus move recomposes every row (each row reads the focused title `shown`), on a TV CPU;
   - the focused row grows (plot lines, heightIn) so rows below shift on every move;
   - rows vanish/appear mid-scroll (empty when done, LoadingHold, watched-hide) so the order changes under you;
   - a move waits for the next LazyRow to be built and focused (frame waits, scrollToItem, up to 90 frames);
   - the loading card holds focus on a row until it loads.
   Plan: a frozen layout per visit — every row a fixed height and fixed place (computed when Home opens), an empty row
   keeps its place and a random row fills it, nothing inserted/removed until you leave Home; a held Up/Down moves a
   cursor at key-repeat speed and the page jumps to the row's known offset (no waiting for focus), focus settles when
   released; the focused title's text in a fixed-height area; only the row that gains/loses focus redraws; rows keep
   their full user order (rearrange freely in Settings; the order is applied the next time Home opens).
   - [ ] "Open season" (user 2026-11-27): ALL of Home is prepared in the loading screen after the profile is picked
     (every row's items, the frozen layout, first posters decoded) so inside Home nothing loads while you scroll.
   - [x] One look only (0.41.11): Tonal, Halo and "Home follows the look" removed (tag wip-looks-tonal-halo); skins stay.
2. [ ] **Server foundation** (vision-meta): IMDb mirror + downloaded queries, daily IMDb files, own score
       (movies/series blend; anime from AniList/MAL/Kitsu, min 7), no IMDb logos. Move secrets out of the APK
       (REPORT_TOKEN, TRAKT_SECRET, MAL secret). Needs the user's OK on the daily/hourly jobs.
3. [ ] **FEL player** (Arc's code, ~95%): own renderer + JNI from our kernel knowledge (port open, amlvideo route, sideband after seek).
4. [ ] **Dolby Vision chain**: unmodified media3 extractor (Apache-2.0) + own DV7→8.1 bridge on libdovi (MIT), own RPU / HDR10+ strippers.
5. [ ] **Seek thumbnails** (Arc, 48 files in com/nuvio/tv): own smaller design, then delete the com/nuvio package.
6. [ ] **IPTV**: own M3U / XMLTV / catch-up readers.
7. [ ] **Small pieces**: poster URL resolver, blur, audio delay, Hdr10 SEI, chapters; replace the Echo look with our own.
8. [ ] **Product**: own accounts + Nuvio import, subscriptions + licence check, drop api.nuvio.tv and the "nuvio-tv" client name.
9. [ ] **Hardening**: stronger R8 rules, valuable logic on the server, tamper / signature check; full scan = 0%; full test pass.

## Open questions for the user
- [ ] Own score for movies/series: include IMDb as one input, or Trakt + TMDB + Metacritic only?
- [x] Sports features to keep: all (2026-11-27).
- [ ] Licences before selling: TMDB / IMDb commercial or free-to-sell sources only; Vision TB torrent search legal check.

## Sports features (user 2026-11-27: keep ALL, every one rewritten in the app's look)
- [x] Live now + day list (also feeds the clock panel's Live now tab)
- [x] My Teams: follow teams / leagues / sports, rows per team and league
- [x] Pick Your Teams picker (sport → league → teams)
- [x] Game page: score, scoring plays, team stats, pre-game (place, last five, win chance)
- [x] League tables (ESPN; TheSportsDB for Qatar/UAE/Egypt/Kuwait/Bahrain/Oman/Jordan/Morocco + Emir Cup; F1 / NASCAR / IndyCar)
- [x] Channel finder for each game (guide, channel name, league networks) + failover to the next channel
- [x] Gemini channel finder (2026-11-27, sport/AiMatch.kt): reads only your channel names + guide, never the web; "Find with Gemini" on a game page, or by itself when switched on; what it finds is learned per league + network so later games need no call; 30 calls/h max
- [x] Goal alerts for followed teams (shown anywhere)
- [x] Pinned scores
- [x] Record a game (Live TV recordings)
- [x] Region (which leagues show) + flags
- [x] NEW: **Refresh channels** everywhere, not only the clock panel: a button on the Sports page and on every live
      game's page. On a game page it looks again for that game only (fresh TV guide + scores + channel match, each step
      shown, "Updated 14:05 · 3 channels"); on the Sports page it does all live games. One shared refresh job, so
      pressing it in two places never runs it twice.
