package com.lekathedon.visionlite.detail

import com.lekathedon.visionlite.addons.Video
import com.lekathedon.visionlite.home.Tmdb

/**
 * Seasons IMDb hasn't added yet, from TMDB (user 2026-11-26: JoJo's Steel Ball Run is S6 on Trakt / TMDB, IMDb lists 5,
 * so Play S6 E6 had no episode). Only when IMDb's seasons agree with TMDB's (same episode count each, ±2): shows TMDB
 * numbers differently (One Piece) are left as IMDb has them. Episode ids keep the IMDb form "tt…:s:e".
 * Cost: with a TMDB key, 2 small requests per series page (find + tv), +1 per missing season; none without a key.
 */
object TmdbSeasons {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, List<Video>>()

    fun beyond(imdb: String, imdbEps: List<Imdb.Episode>): List<Video> {
        cache[imdb]?.let { return it }
        val out = runCatching { load(imdb, imdbEps) }.getOrNull() ?: return emptyList()
        cache[imdb] = out
        return out
    }

    private fun load(imdb: String, imdbEps: List<Imdb.Episode>): List<Video> {
        val perSeason = imdbEps.filter { it.season > 0 }.groupingBy { it.season }.eachCount()
        val last = perSeason.keys.maxOrNull() ?: return emptyList()
        val id = Tmdb.get("find/$imdb?external_source=imdb_id")?.optJSONArray("tv_results")?.optJSONObject(0)?.optInt("id")
            ?.takeIf { it > 0 } ?: return emptyList()
        val seasons = Tmdb.get("tv/$id")?.optJSONArray("seasons") ?: return emptyList()
        val counts = (0 until seasons.length()).mapNotNull { seasons.optJSONObject(it) }
            .associate { it.optInt("season_number") to it.optInt("episode_count") }
        // Same numbering as IMDb up to IMDb's last season, else TMDB's later seasons mean something else.
        if ((1..last).any { n -> kotlin.math.abs((counts[n] ?: -99) - (perSeason[n] ?: 0)) > 2 }) return emptyList()
        return counts.keys.filter { it > last && (counts[it] ?: 0) > 0 }.sorted().flatMap { n ->
            val eps = Tmdb.get("tv/$id/season/$n")?.optJSONArray("episodes") ?: return@flatMap emptyList()
            (0 until eps.length()).mapNotNull { eps.optJSONObject(it) }.mapNotNull { e ->
                val en = e.optInt("episode_number").takeIf { it > 0 } ?: return@mapNotNull null
                val air = e.optString("air_date").takeIf { it.length == 10 }
                Video(id = "$imdb:$n:$en", title = e.optString("name").ifBlank { null }, name = e.optString("name").ifBlank { null },
                    season = n, episode = en, released = air?.let { "${it}T00:00:00Z" },
                    thumbnail = e.optString("still_path").takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w500$it" },
                    overview = e.optString("overview").ifBlank { null })
            }
        }.also { if (it.isNotEmpty()) android.util.Log.i("TmdbSeasons", "$imdb: +${it.size} episodes after IMDb S$last") }
    }
}
