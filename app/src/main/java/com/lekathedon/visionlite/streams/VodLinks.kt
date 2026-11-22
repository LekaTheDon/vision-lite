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
    private val TAGS = Regex("""\[[^]]*]|\([^)]*\)|\|[^|]*\||(?i)\b(4k|uhd|fhd|hd|sd|hevc|h\.?265|x265|1080p?|2160p?|720p?|multi|vostfr|dual|sub|dub|hdr10?|dv|dolby vision)\b""")
    private val PREFIX = Regex("""^\s*(?:[A-Za-z]{2,3}|[A-Za-z]{2,3}[-_ ][A-Za-z]{2,3})\s*[-:|•]\s+""")

    fun available(): Boolean = runCatching { Vod.count() > 0 }.getOrDefault(false)

    /** "EN - Dune: Part Two (2024) 4K" → "dune part two". */
    internal fun clean(name: String): String =
        name.replace(PREFIX, "").replace(TAGS, " ").replace(YEAR, " ").lowercase()
            .replace("&", " and ").replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")

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
        val wanted = titles.map(::clean).filter { it.length >= 2 }.toSet()
        if (wanted.isEmpty()) return@withContext emptyList()
        val names = Iptv.playlists().associate { it.id to it.name }
        val kind = if (type == "movie") "movie" else "series"
        val hits = wanted.flatMap { w -> Vod.search(kind, w.split(' ').maxBy { it.length }) }.distinctBy { it.pl + it.id }
            .filter { clean(it.name) in wanted && yearOk(it.name, year) }
        if (type == "movie") hits.mapNotNull { v ->
            val url = v.url ?: return@mapNotNull null
            link(url, titles.first(), year?.toString(), v.name, names[v.pl] ?: "IPTV")
        } else hits.take(3).flatMap { v ->
            Vod.episodes(v).filter { it.season == target!!.season && it.number == target.episode }.map { e ->
                link(e.url, titles.first(), "S%02dE%02d".format(e.season, e.number), v.name + (e.title.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), names[v.pl] ?: "IPTV")
            }
        }
    }

    /** A VOD row in Vision TB's group: a release-style file name (the match was ours), so the autoplay checks read it. */
    private fun link(url: String, title: String, tag: String?, shown: String, playlist: String): Link {
        val h = height(shown)
        val q = when (h) { 2160 -> "2160p"; 1080 -> "1080p"; 720 -> "720p"; else -> "" }
        val file = listOfNotNull(title, tag, q.ifEmpty { null }, "IPTV.VOD").joinToString(".") { it.replace(' ', '.') } + "." + url.substringAfterLast('.', "mp4").substringBefore('?')
        return Link(com.lekathedon.visionlite.tb.VisionTb.NAME,
            Stream(url = url, name = "${com.lekathedon.visionlite.tb.VisionTb.NAME}\nVOD ${q.ifEmpty { "" }}".trim(), title = "$shown\n📺 $playlist VOD",
                behaviorHints = StreamHints(filename = file)), vod = true)
    }
}
