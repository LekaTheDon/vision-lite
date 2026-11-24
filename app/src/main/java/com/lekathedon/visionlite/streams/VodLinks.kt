package com.lekathedon.visionlite.streams

import com.lekathedon.visionlite.addons.Stream
import com.lekathedon.visionlite.addons.StreamHints
import com.lekathedon.visionlite.iptv.Iptv
import com.lekathedon.visionlite.iptv.Vod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Your IPTV VOD inside Vision TB (user 2026-11-22): when a title is pressed, the films / series already listed in vod.db
 * (VOD page, daily) are matched by name + year, and the matches join Vision TB's group, so autoplay can pick them like
 * any TorBox link (4K VOD beats a 1080p torrent; a title only on VOD plays too). No network for films; a series asks its
 * panel for the episode list once (Xtream get_series_info). Nothing runs when no playlist has VOD.
 */
object VodLinks {
    private val YEAR = Regex("""(?<!\d)(19|20)\d{2}(?!\d)""")
    private val TAGS = Regex("""\[[^]]*]|\([^)]*\)|\|[^|]*\||(?i)\b(4k|uhd|fhd|hd|sd|hevc|h\.?265|x265|1080p?|2160p?|720p?|multi|vostfr|dual|sub|subbed|dub|dubbed|hdr10?|dv|dolby vision)\b|مدبلج[ةه]?|مترجم[ةه]?|كامل[ةه]?""")
    private val PREFIX = Regex("""^\s*(?:[A-Za-z]{2,3}|[A-Za-z]{2,3}[-_ ][A-Za-z]{2,3})\s*[-:|•]\s+""")

    fun available(): Boolean = runCatching { Vod.count() > 0 }.getOrDefault(false)

    /** "EN - Dune: Part Two (2024) 4K" → "dune part two". */
    internal fun clean(name: String): String =
        name.replace(PREFIX, "").replace(TAGS, " ").replace(YEAR, " ").lowercase()
            .replace("&", " and ").replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي')
            .replace(Regex("[^a-z0-9\\u0621-\\u064A]+"), " ").trim().replace(Regex("\\s+"), " ")

    private fun height(name: String): Int {
        val t = name.lowercase()
        return when { "2160" in t || "4k" in t || "uhd" in t -> 2160; "1080" in t || "fhd" in t -> 1080; "720" in t || Regex("\\bhd\\b").containsMatchIn(t) -> 720; else -> 0 }
    }

    private fun yearOk(name: String, year: Int?): Boolean {
        if (year == null) return true
        val y = YEAR.find(name.replace(PREFIX, ""))?.value?.toIntOrNull() ?: return true
        return kotlin.math.abs(y - year) <= 1
    }

    /** Links for [type]/[videoId] ("tt…" or "tt…:s:e"). */
    suspend fun links(type: String, videoId: String): List<Link> = withContext(Dispatchers.IO) {
        if (!available()) return@withContext emptyList()
        val target = EpisodeCheck.targetOf(videoId)
        val imdb = videoId.substringBefore(':')
        val titles: List<String>; val year: Int?
        if (type == "movie") {
            MovieFacts.ensure(imdb)
            val f = MovieFacts.of(imdb) ?: return@withContext emptyList()
            titles = f.first; year = f.second
        } else {
            if (target == null) return@withContext emptyList()
            EpisodeFacts.ensure(type, videoId)
            titles = EpisodeFacts.titlesOf(target); year = null
        }
        // Preferred dub on: the title's name in that language too (dubbed copies are listed under it; one TMDB call, kept).
        val dubLang = com.lekathedon.visionlite.App.instance.prefs.dubLang.value
        val dubName = if (dubLang.isNotEmpty()) localName(imdb, kind0(type), dubLang) else null
        val wanted = (titles + listOfNotNull(dubName)).map(::clean).filter { it.length >= 2 }.toSet()
        if (wanted.isEmpty() && com.lekathedon.visionlite.iptv.VodArabic.forImdb(imdb) == null) return@withContext emptyList()
        val names = Iptv.playlists().associate { it.id to it.name }
        val kind = if (type == "movie") "movie" else "series"
        // Plus the VOD title a Home VOD card was opened from (Arabic names don't match the English ones).
        val saved = com.lekathedon.visionlite.iptv.VodArabic.forImdb(imdb)?.takeIf { it.kind == kind }
        val hits = (listOfNotNull(saved) + wanted.flatMap { w -> Vod.search(kind, w.split(' ').maxBy { it.length }) }
            .filter { clean(it.name) in wanted && yearOk(it.name, year) }).distinctBy { it.pl + it.id }
        if (type == "movie") hits.mapNotNull { v ->
            val url = v.url ?: return@mapNotNull null
            link(url, titles.first(), year?.toString(), v.name, names[v.pl] ?: "IPTV", DubLang.of(v.name + " " + v.group))
        } else hits.take(3).flatMap { v ->
            Vod.episodes(v).filter { it.season == target!!.season && it.number == target.episode }.map { e ->
                link(e.url, titles.first(), "S%02dE%02d".format(e.season, e.number), v.name + (e.title.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), names[v.pl] ?: "IPTV", DubLang.of(v.name + " " + v.group))
            }
        }
    }

    /** A VOD row in Vision TB's group: a release-style file name (the match was ours), so the autoplay checks read it. */
    private fun kind0(type: String) = if (type == "movie") "movie" else "tv"
    private val localNames = java.util.Collections.synchronizedMap(HashMap<String, String>())
    /** The title's name in [lang] (TMDB find by IMDb id); "" kept when TMDB has none, so it is asked once a run. */
    private fun localName(imdb: String, kind: String, lang: String): String? {
        val k = "$imdb|$lang"
        localNames[k]?.let { return it.ifEmpty { null } }
        val o = com.lekathedon.visionlite.home.Tmdb.get("find/$imdb?external_source=imdb_id&language=$lang") ?: return null
        val r = o.optJSONArray(if (kind == "movie") "movie_results" else "tv_results")?.optJSONObject(0)
        val n = (r?.optString(if (kind == "movie") "title" else "name")).orEmpty()
        localNames[k] = n
        return n.ifEmpty { null }
    }

    private fun link(url: String, title: String, tag: String?, shown: String, playlist: String, dub: String? = null): Link {
        val h = height(shown)
        val q = when (h) { 2160 -> "2160p"; 1080 -> "1080p"; 720 -> "720p"; else -> "" }
        val file = listOfNotNull(title, tag, q.ifEmpty { null }, dub?.let { "DUB.${it.uppercase()}" }, "IPTV.VOD").joinToString(".") { it.replace(' ', '.') } + "." + url.substringAfterLast('.', "mp4").substringBefore('?')
        return Link(com.lekathedon.visionlite.tb.VisionTb.NAME,
            Stream(url = url, name = "${com.lekathedon.visionlite.tb.VisionTb.NAME}\nVOD ${q.ifEmpty { "" }}".trim(), title = "$shown\n📺 $playlist VOD" + (dub?.let { " · ${java.util.Locale(it).getDisplayLanguage(java.util.Locale.ENGLISH)} dub" } ?: ""),
                behaviorHints = StreamHints(filename = file)), vod = true, dub = dub)
    }
}
