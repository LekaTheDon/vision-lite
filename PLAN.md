# Vision Lite — plan

Its own app, written fresh (no Arc / Nuvio / Vision code copied). Goal: small, snappy, efficient; Debrid Stream speed.
Package `com.lekathedon.visionlite`, name "Vision Lite". Private repo until the user says otherwise.

## Size and speed rules
- Few dependencies: Compose (foundation + material3), Media3 ExoPlayer, OkHttp, kotlinx.serialization, Coil 3, ZXing core (QR).
  No Hilt, no Room, no DataStore, no Supabase, no JS engine, no FFmpeg, no mpv.
- Target APK: about 10–15 MB (arm64), FEL native lib included.
- UI: no motion, focus moves in one frame, images decoded at drawn size, one network call per screen where possible,
  nothing runs that the user didn't switch on (no prefetch, no link search before Play).
- Look: Echo-style colour tint from the focused title by default; OLED black is an option.

## v1 (user, 2026-11-18)
1. **Add-ons**: Stremio protocol (manifest / catalog / meta / stream / subtitles), install by URL (QR from phone),
   order, remove. Debrid comes through the add-ons' own configured URLs.
2. **Home**: rows from add-on catalogs + Continue Watching; focused title's details on top.
3. **Detail**: meta, seasons/episodes, Play opens the stream list (autoplay = option, off).
4. **Player**: ExoPlayer; audio passthrough per format, frame-rate match, DV handling;
   **native FEL from day one** on AM9 (own renderer feeding the Amlogic dual-layer port, own JNI).
5. **Trakt**: device-code login (QR), history, progress, Continue Watching, scrobble.
6. **Search + Library**: search across add-ons; library = Trakt watchlist + local saves.
7. **Device check with suggestions**: probes box/TV/soundbar; each device setting offers only what the device can do,
   one Recommended; first start "Use these / Not now"; Settings > This TV with Reassess.

## Later (not v1)
Hubs (Live TV, Sports, Music, YouTube) one by one as modules; Vision TB; AI picks; profiles.

## Packages
`addons` (protocol, client, store) · `trakt` · `home` · `detail` · `streams` · `player` (+ `player/fel`, `cpp/`) ·
`device` (check) · `settings` · `search` · `library` · `ui` (shared components, look) · `data` (prefs, cache).

## Phases
1. Skeleton + add-ons + Home + Detail + stream list + ExoPlayer playback (first build).
2. FEL renderer + device check + passthrough / AFR.
3. Trakt + Continue Watching + scrobble.
4. Search + Library + settings (~50 options).
5. Measure against Debrid Stream on the TV (only with the user's OK), baseline profile.
