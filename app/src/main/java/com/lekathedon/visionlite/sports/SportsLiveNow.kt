package com.lekathedon.visionlite.sports

/**
 * Which games a day shows and which of them are "Live now", in one place: the Sports page (All Events)
 * and the clock panel's Live now tab both read these, so their lists and counts can't differ.
 * Moved here unchanged from SportsScreen (Nuvio Vision).
 */
internal object SportsLiveNow {

    /** A day's games on the Sports page (no league picked from the top bar). */
    fun dayEvents(
        events: List<SportsEvent>,
        matches: Map<String, List<ChannelMatch>>,
        active: Set<String>,
        filterToChannels: Boolean,
        follows: SportsFollows,
        dayStart: Long,
        dayEnd: Long,
        now: Long
    ): List<SportsEvent> = events.filter { ev ->
        // A finished game or event shows on the day it finished only (a race weekend or a
        // tournament isn't repeated on every day of its dates once it's over).
        val onDay = when {
            ev.state == GameState.POST -> {
                val finished = if (ev.hasTime) ev.startMs else minOf(ev.endMs ?: ev.startMs, now)
                finished in dayStart until dayEnd
            }
            ev.hasTime -> ev.startMs in dayStart until dayEnd
            // A tournament or race weekend still to come: on the day it starts only.
            ev.state == GameState.PRE -> ev.startMs in dayStart until dayEnd
            // One under way: today and the days ahead it still runs (not the days before today).
            else -> dayEnd > now && ev.startMs < dayEnd && (ev.endMs ?: (ev.startMs + 1)) >= dayStart
        }
        ev.leagueId in active && onDay &&
            (!filterToChannels || ev.state == GameState.POST || isMine(ev, follows) || matches[ev.id]?.isNotEmpty() == true)
    }

    /** Golf, tennis, a race weekend are "in progress" all week in the feed: live only while one of your channels shows it. */
    fun airingNow(ev: SportsEvent, matches: Map<String, List<ChannelMatch>>, now: Long): Boolean =
        matches[ev.id]?.any { m -> m.program?.let { now >= it.startMs && now < it.stopMs } == true } == true

    /** Week-long events under way but not on a channel now: listed under This Week, not Live now. */
    fun weekLong(shown: List<SportsEvent>, matches: Map<String, List<ChannelMatch>>, now: Long): List<SportsEvent> =
        shown.filter { !it.hasTime && it.state == GameState.LIVE && !airingNow(it, matches, now) }

    /** The Live now list, in the Sports page's order: yours first, then on a channel, then league rank, then start. */
    fun live(
        shown: List<SportsEvent>,
        weekIds: Set<String>,
        matches: Map<String, List<ChannelMatch>>,
        follows: SportsFollows,
        rank: (SportsEvent) -> Int
    ): List<SportsEvent> = shown.filter { it.state == GameState.LIVE && it.id !in weekIds }
        .sortedWith(
            // ReSeq: confirmed live games first, then guide-only broadcasts, replays last.
            compareBy<SportsEvent> { it.source == EventSource.GUIDE }
                .thenBy { it.isReplay }
                .thenByDescending { isMine(it, follows) }
                .thenByDescending { matches[it.id]?.isNotEmpty() == true }
                .thenBy { rank(it) }
                .thenBy { it.startMs }
        )

    /** The "N live" count beside All Events. */
    fun liveCount(dayEvents: List<SportsEvent>, matches: Map<String, List<ChannelMatch>>, now: Long): Int =
        // ReSeq: a guide broadcast airing now doesn't prove the game is live, so it isn't counted.
        // Vision: the clock's test (also no replays or stale LIVE), so the count and the clock always agree.
        dayEvents.count { reallyLive(it, matches, now) }

    /**
     * Vision: really under way now, for the clock. The page's "N live" test (no guide-only broadcast, a week-long
     * event only while a channel shows it), no replays, and nothing whose LIVE state is plainly old: past its known
     * end, or a game that started more than [STALE_LIVE_MS] ago (the feed hadn't updated it to FINAL yet).
     */
    fun reallyLive(ev: SportsEvent, matches: Map<String, List<ChannelMatch>>, now: Long): Boolean =
        ev.source != EventSource.GUIDE && ev.state == GameState.LIVE && !ev.isReplay &&
            (ev.hasTime || airingNow(ev, matches, now)) &&
            ev.endMs?.let { now < it } != false &&
            !(ev.hasTime && ev.endMs == null && now - ev.startMs > STALE_LIVE_MS)

    /** A timed game still LIVE this long after its start is a feed that stopped updating, not a game. */
    private const val STALE_LIVE_MS = 6 * 60 * 60_000L

}
