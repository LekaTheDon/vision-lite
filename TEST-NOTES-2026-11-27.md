# Live test notes — 2026-11-27 (0.41.7-104 on AM9)
No builds/installs until the user says so. Fixes are kept as uncommitted edits.

1. HDR/DV subtitles look undimmed (Haunting of Hill House S1E3, DV, IPT_TUNNEL)
   - Dimming IS applied: sub_color FFFFFFFF, sub_hdr_bright 58 -> on screen ~169/255.
   - Still glares: DV core blends the graphics plane as SDR (graphic_fmt=FORMAT_SDR) at fixed level; very dark scene.
   - Options: user lowers HDR subtitle brightness (20-30), or lower default for DV. No code change yet.

2. Player side panel loses focus after a pick (picked Arabic subs, Down did nothing, needed Back)
   - Cause: Controls.kt closes panel (panel = null), focused row vanishes, no focus restore.
   - Fix (uncommitted, Controls.kt): dockFocus requesters + `opener`; LaunchedEffect(panel) refocuses the
     opening dock button (Subtitles/Audio/Speed/More) after pick or Back. Not built.

3. Sources button ends playback (user: should browse other links without leaving the film)
   - Cause: Player.kt:697 onSources does nav.replace(Screen.Streams(... manual = true)) - the player screen
     is replaced, so the link closes.
   - Fix plan (not coded): Sources opens an in-player side panel (like Subtitles) listing the title's links
     while the film keeps playing; Back = keep watching, pick = switch link and resume at the same position.

4. Subtitles panel in the player: change style, colour and HDR dimming level there (not only Settings).
   - Default dimming must look like old Vision ("clearly dimmed").
   - Checked: Vision default = 58, same formula (0.7*b+30)% -> white 180/255; user's Vision kept the default
     (no subtitle_hdr_brightness stored). Lite measured 169-180 = same text level. Difference: Vision also dims
     outline + background colours (forVideo), Lite dims text only; Vision also had "dim player controls".
   - Before choosing a new default: compare Vision vs Lite on the same DV title (with user's OK); likely lower default.

5. Player dock order: Play/Pause, Restart from beginning, Subtitles, Audio, Sources, Next, Speed, ... Aspect, Stats
   (Aspect then Stats = last two, moved out of More).
   - More gets "Player button size": Small (current) / Medium (1.5x) / Big (2x).

6. Player top-left styled title (logo + S·E) 1/3 bigger.

7. Details > Ratings: show the show's overall average first (single entry, like Nuvio Legacy probably);
   one press opens the current seasons/episodes ratings view (season averages); Back returns to the overall view.

8. Unreleased films/TV (not digitally released yet, e.g. "Digger") must not appear in lists as if watchable.
   - Either hide everywhere, or an option "Hide content not digitally released"; upcoming only in its own
     "Coming soon / Most anticipated" type rows (like the anime upcoming lists).

9. Left-out content (country/region/language e.g. Korean, genres, ...) filtered EVERYWHERE: catalog rows,
   random rows, add-on catalogs, search?, not just recommendations. (User saw a whole Korean row with Korean left out.)

10. Search like IMDb: people (actors/directors) in results; picking one opens their person page.
    - Person/biography page has a grey background -> must be OLED black. Audit every Home-side screen/menu
      for non-OLED backgrounds and apply the app's OLED background everywhere.

## Status: items 2-10 done in 0.41.8 (105), installed 2026-11-27 (wip-041 @ 5ac39a7). Item 1 = new HDR default 30 + panel. Not verified on TV.
