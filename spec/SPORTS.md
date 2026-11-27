# Sports — behaviour spec (clean room)

Written 2026-11-27 from how Sports behaves today. It describes WHAT the hub does, never how the old code did it.
The rewrite is written from this file only: the old `sports/` package stays closed while writing, and is deleted
when the new one is done. Re-scan (`python3 ~/work/lite-audit/scan.py`) must show 0 matched lines for `sports/`.

Old size: 36 files, 12,806 lines + a 345 KB vocabulary file (ReSeq's). Target: **≤ 3,500 lines, ≤ 40 KB own data.**

---

## 0. Promise and budgets

"Everything you want, fast, in every hub."

| Budget | Limit |
|---|---|
| Opening Sports (warm) | first rows drawn in ≤ 150 ms from what's saved; live data replaces it in place |
| Opening Sports (cold) | ≤ 1 s to scores on a normal line |
| Focus move | one frame, no animation |
| Requests | nothing until Sports, a game page or the clock's Live now is on screen (or a pin/reminder/alert needs it) |
| Score refresh | 30 s while a game on screen is live; 5 min otherwise; stops when nothing is on screen |
| Channel matching | off the main thread; one full pass ≤ 1.5 s for 20k channels; reruns only when scores, guide or channels change |
| Memory | channel-name index built once per channel list; caches bounded (LRU) |
| Disk | per-league scoreboards, team lists (1 week), tables (1 hour), own small data files |

Every option and part below must pay for itself; if something costs a request at start or a background job, it is gated.

---

## 1. Look and skin (the main reason for the rewrite)

Sports wears the app's look like every page past Home — nothing of its own:
- Page: `skinPage()` / the skin's colours (`Palette`, `Kit`), the look's fonts (`Inside`), headings `KitHeader`,
  small labels `Eyebrow`, buttons `KitButton`, chips `KitChip`, switches `KitSwitch`, pop-ups `KitPopup` + `KitMenuItem`,
  focus ring = the look's, `Focusable` + `remembersFocus` (Back lands exactly), lists with `FastList` (held Up/Down).
- Tonal / Marquee / Halo and every skin (OLED, Tinted, Midnight, Ember, Moss) must look right with no Sports-only colours.
- Team colours: only as a thin stripe/edge or behind the logo disc on a game card — never a page or band background.
- Logos: team/league logos decoded at drawn size (`Img`). Flags small.
- Game cards: same corner, padding, focus and sizes as Home cards; a row of cards behaves like a Home row
  (Row entry = first card; Up reaches headings).
- No emoji as icons; use the app's `Icons`.

---

## 2. Screens

### 2.1 Sports page
Top: header "Sports" + a chip bar of views: **Live now · All events · My Teams · <each followed sport> · <each league on
the top bar> · Tables · League networks**. A **Refresh** button sits on the bar (see 4.6). Days bar under it for
All events / a league: a week back to a week ahead, today selected; days other than yesterday..tomorrow are fetched only
when picked.

Layout option (setting "Video window", default on):
- On: game list on the left, a video window on the right (the app's MiniPlayer). Under the window: the playing game
  (teams, score, clock), its latest scoring play, and the channel it's on; a hint when nothing plays.
- Off: cards two per row across the full width; games open full screen.

Lists:
- **Live now**: games really under way — your teams first, then games on one of your channels, then the rest.
- **All events**: the day's games grouped by league in the region's order. A "Choose leagues" line above shows how many
  leagues are on and opens the league chooser (2.6).
- **My Teams**: rows — on now and next across everything you follow, then a row per followed team, a row per top-bar
  league, a row per followed whole league/sport, a row per followed name (guide-only sports). A team row header can say
  its table place ("2nd in Premier League").
- **A sport / a league**: the same as All events filtered.
- Setting "Only games on my channels": leave out games none of your channels show (followed teams always show).
- Setting "Hide scores": scores hidden everywhere until the game page is opened.

Keys:
- OK on a game: its game page.
- Hold OK on a game: watch its best channel now (in the window, or full screen when the window is off).
- First time with nothing followed: Pick Your Teams opens by itself once.

### 2.2 Game card / line
League logo + state ("LIVE 63'", "Q3 4:12", "Final", "7:30 PM", "Tomorrow 20:00"), both teams with logos and scores
(home first for football, rugby, cricket, Aussie rules…; away first "at" for US sports), or the event's own title
(races, golf, tennis tournaments, wrestling shows — title cleaned: no dates, no episode numbers, promotion shown as logo).
Marks: ● REC when set to record, a bell when a reminder is set, a pin when pinned, "on N channels" when matched.

### 2.3 Game page
- Top: both teams (logo, name, record), score and clock/state; before the game: each team's table place, last five
  (W/D/L) and win chance, plus kick-off time. OK on a team opens its team card (2.4).
- Actions (KitButtons): Watch (best channel) · Follow team(s) · Remind me · Record · Pin score · **Refresh channels**.
- **Channels**: each channel showing it — logo, name, what the guide says now, quality (4K/FHD/HD/SD), "playing" mark on
  the one in the window. OK plays it in the window; OK again goes full screen. Menu on a channel:
  "This is the right channel" (also: for this network / team / league from now on) and "Not this channel".
  "Fix channel": type part of a channel's name and pick it.
- Live: score by period, scoring plays (refreshed when the score changes and every 30 s while live), team stats side
  by side with bars, leaders. Races/golf: running order / leaderboard. Tennis tournament: matches on now, today's results,
  next ones ("Sinner def. Rune · 6-4 6-3").
- From the start: when the channel can replay (catch-up), "Watch from the start".

### 2.4 Team card
Team's season: results and games to come (each game under its own competition), table place, follow/unfollow, the
team's own channel if set. OK on a game opens it.

### 2.5 Pick Your Teams (also Settings › My teams)
Sport on the left, its leagues across the top, the league's teams below (logos). OK follows/unfollows a team; a whole
league or sport can be followed; guide-only sports follow by name. Everything followed sits in a bar at the top
(OK there unfollows). "Arrange My Sports": order of the top bar. A league's team list is fetched only when opened,
kept a week.

### 2.6 League chooser (All events)
Leagues grouped by sport, groups closed at first, search box on top (search opens every group with a match).
OK on a sport opens/closes it; OK on a league turns it on/off. The order never changes while open.

### 2.7 Tables
Your leagues' tables (first of yours by default, or the one asked for, with given teams marked). Views per league:
division / conference / league where they exist. Columns by kind: points tables P W D L GD PTS; US W L PCT GB STRK;
hockey GP W L OTL PTS; drivers' championship (F1, NASCAR, IndyCar). Zones coloured (Champions League, relegation…),
playoff lines for US leagues with seeds, clinch marks (x/y/z/e), last five, places moved since last round (remembered
per league). Fetched when looked at, kept an hour.

### 2.8 League networks
Each league's/sport's own channel (NFL Network, NBA TV, Golf Channel…): your channel for it, chosen by the app or by you.

### 2.9 Clock panel — Live now tab
Same Live now list and counts as the Sports page (one shared source). OK on a game lists its channels (small logos +
names under the game); first OK on a channel plays it in the panel's window, OK again full screen. Refresh button with
step text and "Updated 14:05 · 3 live".

### 2.10 Everywhere (host)
- Reminders: a card bottom right a few minutes before a picked game (and, when on, your teams' games) with "Watch now";
  each shown once. With "Switch by itself" on: a 10 s countdown about a minute before the start, then the best channel.
- Scoring alerts (when on): "GOAL · Arsenal 2–1" for 8 s, not focusable, delayed by the alert delay (default 30 s) so it
  never beats the stream.
- Pinned scores: up to 3 live games, top left over every screen including the player; this session only; dropped
  10 min after the game ends.
- Runs the background loop only while something needs it (reminder, pin, alerts) and only after Live TV or Sports was
  opened this session.

### 2.11 Full screen
Playing a game full screen: the game's other channels are the player's Up/Down list. **Failover**: if the channel fails
twice or shows nothing for 15 s, the next channel found for the same game takes over. Only for channels started from
Sports. Score on the player's info bar (setting).

### 2.12 Record
Record this game: pick the channel and the length (◂ 2:00 ▸, default = the sport's usual length rounded up to the half
hour: football 2:00, NFL 3:30…), starting at least 5 minutes before kick-off, using Live TV's recordings.

---

## 3. Data

| Source | What | When |
|---|---|---|
| ESPN public site API (no key) | scoreboards per league and day range, game summary (plays, stats, leaders), team schedules, team lists, standings | as in budgets |
| sporting-events.org | dates of events ESPN doesn't cover | when such a sport is followed / shown |
| TheSportsDB (by image address only) | league/show logos for wrestling etc. | with the data that names them |
| Your TV guide (Lite's IPTV) | games ESPN doesn't carry (darts, snooker, wrestling, other leagues): today + tomorrow | when the guide changes |
| Gemini (your key, opt-in) | re-order a game's channels when the guide is unclear | only if "Background guide matching" is on or a lookup is asked |
| flagcdn | region flags | when shown |

Days: one local day spans two US-Eastern dates; each league/date asked once. Followed and top-bar leagues: their months
up to five weeks ahead, every 6 h. A team looked up: its season, kept 1 h (max 6 teams).
Non-league football / wrestling from the old "nuvio-data" files: **dropped as a source** — replace with our own small
data file on our server, or TheSportsDB directly (decide when building; free tier allowed for our use?).

Own data (written fresh by us, small): the league catalogue (~80 leagues: id, sport, name, ESPN path, region order,
country, cups/European links), sport list, guide words per sport (replaces the 345 KB taxonomy with ≤ 30 KB), team
nicknames for guides, network ↔ channel-name words, replay/highlight words.

---

## 4. Channel matching (behaviour)

### 4.1 Ways a channel is found for a game (best first)
1. **Your guide**: a programme around kick-off naming both teams (or the event / competition) on that channel.
2. **The channel's own name** names the game ("NFL 03: Bears @ Lions") — event channels.
3. **The network** ESPN lists (FOX, ESPN, TNT…) mapped to your channels of that network, with a sports programme on at
   the time.
4. **Your own picks**: "right channel" for the game / network / team / league; never channels you said were wrong.

### 4.2 Rules it must keep
- Works in any country (UK gets Sky Sports / TNT Sports from its own guide).
- Guide times use your guide time shift (Live TV settings).
- Not a real broadcast: 24/7 channels, blocks of 6 h or more, a listing that is just the channel's own name.
- Replays and highlights are not the game ("replay", "highlights", "classics", "condensed"…).
- A programme naming a different game ("Bills at Raiders") is not this one; a competition word only this event uses
  wins over a generic one ("PGA Tour" while the DP World Tour is also on); another event of the same kind running now
  ("Vienna" vs the Basel tournament) is not this one.
- Team names: strong names count alone (with the other team); weak ones (a city) only with more evidence; college teams
  by school; "FC"/"AFC" optional; guide nicknames.
- Channel names compared without region prefixes and quality words ("US| ESPN FHD (backup)" = "espn"; "ESPN 2 HD" = "espn2").
  Separator lines ("##### USA SPORTS #####") are not channels. Hidden and locked channels are never used.
- Order: your preferred groups first (Sports setting), then your own region's channels, then the rest; 4K before
  lower; your language when the channel says one ("ES:", "Deportes").
- A dedicated team feed is a candidate, not proof.
- Disc golf is its own sport; its programmes never go to another event.
- A quick pass never erases verified guide results while a full refresh is pending.
- Looks ahead as far as most guides cover (2–3 days).
- Optional: a Live TV category per followed game, its channels best first, gone 30 min after the end (setting).

### 4.3 Your corrections (kept in Sports settings)
Channel per game (with its start), per network, per team (club/country across competitions), per league, per league
network; "not this channel" per game; preferred channel groups.

### 4.4 Gemini (opt-in)
Uses the AI key from Settings › Integrations › AI (QR/phone form). Re-orders a game's channels. Content-keyed decision
cache and a device-wide daily request budget. Nothing is sent unless switched on or asked.

### 4.5 One game now
On a game page (or "Search now"): the same matcher for that game alone; channels found before stay when none are found.

### 4.6 Refresh channels (NEW everywhere)
One shared job, shown on the Sports page bar, every live game page and the clock's Live now:
1. Guide asked again (downloaded only when the provider's copy changed), playlist checked.
2. Scores: every shown league now (6 at a time) — on a game page only that game's league.
3. Guide-only games read again.
4. Matching: all games (Sports page / clock) or that one game (game page).
Each step's text is shown on the button's note; then "Updated 14:05 · 3 channels" (game) / "· 5 live" (page).
A press while it runs is ignored; pressing it in two places never runs it twice.

---

## 5. Settings (Settings › Sports + Sports' own)
Region (blank = TV's country) · Video window · Hide scores · Score on the player's info bar · Best channel first ·
Remind me of my teams' games + minutes before (0–60) · Switch to the game by itself · Scoring alerts + delay ·
Only games on my channels · Live TV category per followed game · Background Gemini matching · Leagues hidden from
All events · Preferred channel groups · Show Sports in the hub menu · My teams (opens 2.5).
Stored as one JSON value in the profile's prefs (existing key, so users keep their follows and corrections).
**Migration**: the new code reads the existing saved settings and follows unchanged.

## 6. Ties to the rest of the app (keep the same entry points)
`Screen.Sports`, `SportsTeams`, `SportsMyTeams`, `SportsGame(id)`, `Standings`; `SportsHost` in MainActivity; the clock
panel's Live now tab (Echo.kt); Player failover hook (`next(channelKey)`); Settings rows; IPTV's channel list + guide.
Lite's IPTV channel model is read directly (no Vision-shaped adapter class).

## 7. Done when
- Every item above works on the TV (user tests), in all three looks and five skins.
- `sports/` re-scan = 0 matched lines; vocabulary file replaced; old package deleted.
- Line count ≤ 3,500; APK smaller than before; budgets in §0 measured and written in the handoff.
