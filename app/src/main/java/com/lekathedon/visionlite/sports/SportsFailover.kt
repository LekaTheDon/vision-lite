package com.lekathedon.visionlite.sports



/**
 * Vision (idea from CouchSide): a game started from Sports remembers the other channels found for it. When the
 * channel playing won't play (failed twice, or never started), Live TV moves to the next one for the same game
 * instead of retrying the dead one or looking for a channel with the same name. Only the channels of the game you
 * started count: zapping to anything else leaves this idle.
 */
object SportsFailover {
    private val lock = Any()
    private var gameTitle: String? = null
    private var alternatives: List<LiveChannel> = emptyList()
    private val tried = HashSet<String>()

    /** [matches] in the order Sports shows them (best first); [playing] is the one started. */
    fun arm(title: String, matches: List<ChannelMatch>, playing: LiveChannel) = synchronized(lock) {
        gameTitle = title
        alternatives = matches.map { it.channel }.distinctBy { it.key }
        tried.clear()
        tried += playing.key
    }

    /** The next channel for the game whose channel [failedKey] just failed, or null (not a Sports game / none left). */
    fun next(failedKey: String): Pair<String, LiveChannel>? = synchronized(lock) {
        if (alternatives.none { it.key == failedKey }) return null
        tried += failedKey
        val pick = alternatives.firstOrNull { it.key !in tried } ?: return null
        tried += pick.key
        (gameTitle ?: "this game") to pick
    }
}
