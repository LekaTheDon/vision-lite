package com.lekathedon.visionlite.streams

import com.lekathedon.visionlite.addons.Stream
import com.lekathedon.visionlite.addons.StreamHints
import com.lekathedon.visionlite.iptv.Iptv
import com.lekathedon.visionlite.iptv.Vod
import com.lekathedon.visionlite.probe.ProbeTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/**
 * Your IPTV VOD inside Vision TB (user 2026-11-22): when a title is pressed, the films / series already listed in vod.db
 * (VOD page, daily) are matched by name + year, and the matches join Vision TB's group, so autoplay can pick them like
 * any TorBox link (4K VOD beats a 1080p torrent; a title only on VOD plays too). No network for films; a series asks its
 * panel for the episode list once (Xtream get_series_info). Nothing runs when no playlist has VOD.
 */
object VodLinks {
    /** The player's source name for Play with VOD: such a play never touches normal Play's resume link, link memory or fallback. */
    const val ADDON = "IPTV VOD"
    private val YEAR = Regex("""(?<!\d)(19|20)\d{2}(?!\d)""")
    private val TAGS = Regex("""\[[^]]*]|\([^)]*\)|\|[^|]*\||(?i)\b(4k|uhd|fhd|hd|sd|hevc|h\.?265|x265|1080p?|2160p?|720p?|multi|vostfr|dual|sub|subbed|dub|dubbed|hdr10?|dv|dolby vision)\b|مدبلج[ةه]?|مترجم[ةه]?|كامل[ةه]?""")
    // "EN - ", "AR-KID - ", "AR-ANM-D - ", "AR-AS-S-S - ": the provider's tag in front.
    // "4K-FR - ", "D+ - ", "4K-D+ - ", "4K-ES-HDR - " too (user 2026-11-26: these copies were never matched, so no 4K showed);
    // capitals only, so "Dune: Part Two" and "It: …" keep their names.
    private val PREFIX = Regex("""^\s*(?=[A-Z0-9+]{0,3}[A-Z+])[A-Z0-9+]{1,4}(?:[-_ ][A-Z0-9+]{1,4}){0,3}\s*[-:|•]\s+""")

    fun available(): Boolean = runCatching { Vod.count() > 0 }.getOrDefault(false)

    /**
     * The title page's "Play with VOD" (user 2026-11-26): true when your VOD lists this film / show, by name (+ year for
     * films) in vod.db only: no network, so it is cheap enough to ask on every title page. Episodes are checked on press.
     */
    suspend fun present(type: String, imdb: String, names: List<String>, year: Int?): Boolean = withContext(Dispatchers.IO) {
        if (!available()) return@withContext false
        val kind = if (type == "movie") "movie" else "series"
        if (com.lekathedon.visionlite.iptv.VodArabic.forImdb(imdb)?.kind == kind) return@withContext true
        val wanted = names.map(::clean).filter { it.length >= 2 }.toSet()
        wanted.any { w ->
            runCatching { Vod.search(kind, w.split(' ').maxBy { it.length }) }.getOrDefault(emptyList())
                .any { clean(it.name) in wanted && yearOk(it.name, year) }
        }
    }

    /**
     * One VOD copy as the "Play with VOD" list names it: audio (dub / subtitled / original), then the panel's real file
     * facts when it gave them (picture, HDR / Dolby Vision, codec, Atmos + sound format + channels, audio language,
     * bitrate), else what the name says; then the playlist.
     */
    fun label(l: Link): String {
        val shown = l.stream.title.orEmpty().substringBefore('\n')
        val t = shown.lowercase()
        val p = l.probe
        val parts = mutableListOf<String>()
        parts += l.dub?.let { lang(it) + " dub" }
            ?: if (Regex("""\b(sub|subbed|subs|vostfr)\b|مترجم""").containsMatchIn(t) || tagSubs(shown) != null) "Subtitled" else "Original audio"
        val more = mutableListOf<String>()
        if (p != null) {
            // The file itself (opened when the list showed): picture, HDR / DV, sound and subtitle tracks.
            picture(p.width, p.height)?.let(parts::add)
            hdrWord(p.hdr)?.let(parts::add)
            val audio = p.tracks.filter { it.kind == 'a' }
            if (audio.any { it.name.orEmpty().contains("atmos", true) }) parts += "Atmos"
            audio.take(3).joinToString(" + ") { sound(it) }.takeIf { it.isNotBlank() }?.let(more::add)
            p.tracks.filter { it.kind == 's' }.mapNotNull { s -> langOrNull(s.lang)?.let { if (s.forced) "$it (forced)" else it } }.distinct()
                .takeIf { it.isNotEmpty() }?.let { more += "Subs: " + it.take(4).joinToString(", ") + if (it.size > 4) " +${it.size - 4}" else "" }
        } else if (l.tech != null) { parts += l.tech.substringBefore('\n'); l.tech.substringAfter('\n', "").takeIf { it.isNotBlank() }?.let(more::add) } else {
            when (height(shown)) { 2160 -> "4K"; 1080 -> "1080p"; 720 -> "720p"; else -> null }?.let(parts::add)
            if (Regex("""\b(dv|dolby vision)\b""").containsMatchIn(t)) parts += "Dolby Vision" else if (Regex("""\bhdr(10)?\b""").containsMatchIn(t)) parts += "HDR"
            if ("atmos" in t) parts += "Atmos"
            if (parts.size == 1) more += "No file details"
        }
        tagSubs(shown)?.takeIf { p == null || p.tracks.none { it.kind == 's' } }?.let { more += "$it subs" }
        l.stream.title.orEmpty().substringAfter("📺 ", "").substringBefore(" VOD").takeIf { it.isNotBlank() }?.let(more::add)
        l.group?.takeIf { it.isNotBlank() }?.let { more += "in: $it" }
        shown.takeIf { it.isNotBlank() }?.let(more::add)
        // Two lines: what you choose by (audio · picture), then the file facts, playlist and the provider's name for it.
        return parts.joinToString(" · ") + "\n" + more.filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun lang(code: String) = java.util.Locale(code).getDisplayLanguage(java.util.Locale.ENGLISH).ifBlank { code }
    private fun langOrNull(code: String?) = code?.substringBefore('-')?.takeIf { it.length in 2..3 && it != "und" && it != "zxx" && it != "mul" }?.let(::lang)
    private fun picture(w: Int, h: Int) = when { w >= 3200 || h >= 2000 -> "4K"; w >= 1800 || h >= 1000 -> "1080p"; w >= 1200 || h >= 700 -> "720p"; w > 0 -> "SD"; else -> null }
    /** "Dolby Vision Profile 8.1 · HDR10 base" → "Dolby Vision 8.1"; "No Dolby Vision · HDR10" → "HDR10"; SDR → null. */
    private fun hdrWord(t: String): String? = when {
        t.startsWith("Dolby Vision") -> t.substringBefore(" · ").replace("Profile ", "").replace(" (profile unreadable)", "")
        else -> t.substringAfter(" · ").let { when (it) { "SDR" -> null; "HDR (type unknown)" -> "HDR"; else -> it } }
    }
    private fun sound(a: ProbeTrack): String {
        val c = a.codec.uppercase()
        val codec = when {
            "TRUEHD" in c || c == "MLPA" -> "TrueHD"; "EAC3" in c || c == "EC-3" -> "DD+"; "AC3" in c || c == "AC-3" -> "DD"
            "DTS" in c -> if (a.name.orEmpty().contains("MA", false)) "DTS-HD MA" else "DTS"; "AAC" in c || c == "MP4A" -> "AAC"
            "OPUS" in c -> "Opus"; "FLAC" in c -> "FLAC"; "MPEG" in c || "MP3" in c -> "MP3"; else -> null
        }
        val ch = when (a.channels) { 0 -> null; 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "${a.channels} ch" }
        return listOfNotNull(langOrNull(a.lang), codec, ch).joinToString(" ")
    }
    /** The provider's tag in front ("AR-SUBS - …", "EN-S - …") naming subtitles: their language, else null. */
    private fun tagSubs(name: String): String? {
        val tag = PREFIX.find(name)?.value?.trim()?.trimEnd('-', ':', '|', '•')?.trim() ?: return null
        val tokens = tag.split('-', '_', ' ').filter { it.isNotBlank() }
        if (tokens.size < 2 || tokens.drop(1).none { it.equals("SUB", true) || it.equals("SUBS", true) || it.equals("S", true) }) return null
        return langOrNull(tokens[0].lowercase())
    }

    /** The provider's file facts (Xtream info: video / audio / bitrate) as one line; null when it gave none. */
    internal fun tech(info: org.json.JSONObject?): String? {
        if (info == null) return null
        val v = info.optJSONObject("video"); val a = info.optJSONObject("audio")
        val head = mutableListOf<String>(); val parts = mutableListOf<String>()
        val w = v?.optInt("width") ?: 0; val h = v?.optInt("height") ?: 0
        when { w >= 3200 || h >= 2000 -> "4K"; w >= 1800 || h >= 1000 -> "1080p"; w >= 1200 || h >= 700 -> "720p"; w > 0 -> "SD"; else -> null }?.let(head::add)
        val side = v?.optJSONArray("side_data_list")?.toString().orEmpty()
        val tr = v?.optString("color_transfer").orEmpty()
        when {
            side.contains("DOVI", true) || v?.optString("codec_tag_string")?.startsWith("dv") == true -> "Dolby Vision"
            tr == "smpte2084" -> "HDR10"; tr == "arib-std-b67" -> "HLG"; else -> null
        }?.let(head::add)
        when (v?.optString("codec_name")) { "hevc" -> "HEVC"; "h264" -> "H.264"; "av1" -> "AV1"; else -> null }?.let(parts::add)
        if (a != null) {
            val prof = a.optString("profile")
            val codec = when (val c = a.optString("codec_name")) {
                "eac3" -> "DD+"; "ac3" -> "Dolby Digital"; "truehd" -> "TrueHD"; "dts" -> if (prof.contains("MA")) "DTS-HD MA" else "DTS"
                "aac" -> "AAC"; "opus" -> "Opus"; "flac" -> "FLAC"; else -> c.uppercase().ifBlank { null }
            }
            val ch = when (val n = a.optInt("channels")) { 0 -> null; 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "$n ch" }
            if (prof.contains("Atmos", true)) head += "Atmos"
            listOfNotNull(codec, ch).joinToString(" ").ifBlank { null }?.let(parts::add)
            a.optJSONObject("tags")?.optString("language")?.takeIf { it.length in 2..3 && it != "und" }
                ?.let { java.util.Locale(it).getDisplayLanguage(java.util.Locale.ENGLISH).ifBlank { null } }?.let { parts += "$it audio" }
        }
        if (head.isEmpty() && parts.isEmpty()) return null
        return head.joinToString(" · ") + "\n" + parts.joinToString(" · ")
    }

    fun heightOf(l: Link): Int = l.tech?.let { when { it.startsWith("4K") -> 2160; it.startsWith("1080") -> 1080; it.startsWith("720") -> 720; else -> 0 } }
        ?: height(l.stream.title.orEmpty())

    /** Best first: the quality (the panel's facts first), then original audio before dubs. */
    fun sorted(links: List<Link>): List<Link> = links.sortedWith(compareByDescending<Link> { heightOf(it) }.thenBy { if (it.dub == null) 0 else 1 })

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
    /** [withTech]: also ask the panel for each copy's file facts (Play with VOD; films one small call each, at most 12). */
    /** What a title page matched: the VOD copies, the name to show, year, the title's own language and your playlists. */
    private class Match(val hits: List<com.lekathedon.visionlite.iptv.VodItem>, val main: String, val year: Int?, val original: String?, val dubLang: String, val plNames: Map<String, String>)

    private suspend fun match(type: String, videoId: String, names: List<String>, showYear: Int?): Match? {
        if (!available()) return null
        val imdb = videoId.substringBefore(':')
        val titles: List<String>; val year: Int?
        if (type == "movie") {
            MovieFacts.ensure(imdb)
            val f = MovieFacts.of(imdb)
            titles = ((f?.first ?: emptyList()) + names).filter { it.isNotBlank() }.distinct(); year = f?.second ?: showYear
        } else {
            EpisodeCheck.targetOf(videoId)?.let { t -> EpisodeFacts.ensure(type, videoId); EpisodeFacts.titlesOf(t) }.orEmpty()
                .let { titles = (it + names).filter { n -> n.isNotBlank() }.distinct() }
            year = showYear
        }
        // Preferred dub on: the title's name in that language too (dubbed copies are listed under it; one TMDB call, kept).
        val dubLang = DubLang.wanted()
        // The title's own language: a VOD copy tagged in another language (AR-KID, DE …) is that language's dub.
        val original = com.lekathedon.visionlite.player.OriginalLang.resolve(imdb, type)
        val dubName = if (dubLang.isNotEmpty()) localName(imdb, kind0(type), dubLang) else null
        val wanted = (titles + listOfNotNull(dubName)).map(::clean).filter { it.length >= 2 }.toSet()
        val kind = if (type == "movie") "movie" else "series"
        // Plus the VOD title a Home VOD card was opened from (Arabic names don't match the English ones).
        val saved = com.lekathedon.visionlite.iptv.VodArabic.forImdb(imdb)?.takeIf { it.kind == kind }
        if (wanted.isEmpty() && saved == null && tmdbOf(imdb, kind0(type)) == null) return null
        // First what the panel itself tied to this title's TMDB id (exact), then the name + year matches.
        val byId = tmdbOf(imdb, kind0(type))?.let { Vod.byTmdb(kind, it) }.orEmpty()
        val hits = (listOfNotNull(saved) + byId + wanted.flatMap { w -> Vod.search(kind, w.split(' ').maxBy { it.length }) }
            .filter { clean(it.name) in wanted && yearOk(it.name, year) }).distinctBy { it.pl + it.id }
        val main = titles.firstOrNull() ?: saved?.name?.replace(PREFIX, "")?.trim() ?: return null
        return Match(hits, main, year, original, dubLang, Iptv.playlists().associate { it.id to it.name })
    }

    /** Copies in your languages first (no tag, English, the title's own, your preferred dub), then the rest. */
    private fun ranked(m: Match): List<com.lekathedon.visionlite.iptv.VodItem> {
        val mine = setOfNotNull("EN", m.original?.uppercase(), m.dubLang.uppercase().ifBlank { null })
        fun rank(v: com.lekathedon.visionlite.iptv.VodItem): Int {
            val tag = PREFIX.find(v.name)?.value.orEmpty().uppercase().split('-', '_', ' ', ':', '|').filter { it.isNotBlank() }
            return if (tag.isEmpty() || tag.any { it in mine }) 0 else 1
        }
        return m.hits.sortedBy(::rank)
    }

    /** Every copy's episode list, asked at once (12 copies at most, one small call each, kept 10 minutes). */
    private suspend fun lists(m: Match): List<Pair<com.lekathedon.visionlite.iptv.VodItem, List<com.lekathedon.visionlite.iptv.VodEpisode>>> {
        // 4 at a time (panels count connections), and no more asks once the panel refused one.
        val out = ArrayList<Pair<com.lekathedon.visionlite.iptv.VodItem, List<com.lekathedon.visionlite.iptv.VodEpisode>>>()
        for (chunk in ranked(m).take(12).chunked(4)) {
            out += kotlinx.coroutines.coroutineScope { chunk.map { v -> async { v to runCatching { Vod.episodes(v) }.getOrDefault(emptyList()) } }.map { it.await() } }
            if (Vod.lastError != null) break
        }
        return out
    }

    private fun epLink(m: Match, v: com.lekathedon.visionlite.iptv.VodItem, e: com.lekathedon.visionlite.iptv.VodEpisode) =
        link(e.url, m.main, "S%02dE%02d".format(e.season, e.number), v.name + (e.title.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
            m.plNames[v.pl] ?: "IPTV", DubLang.ofVod(v.name, v.group, m.original)).copy(tech = tech(e.info), group = v.group)

    /** One VOD episode as the season / episode menus list it: its title and how many copies (and the best picture) your VOD has. */
    data class Episode(val season: Int, val number: Int, val title: String, val links: List<Link>)

    /**
     * Play with VOD for a show (user 2026-11-26): everything your VOD has, by season and episode, so a new episode the
     * VOD lacks never hides the ones it has. Null when no copy matched; empty when the panel refused (see [Vod.lastError]).
     */
    suspend fun seriesEpisodes(type: String, imdb: String, names: List<String>, showYear: Int?): List<Episode>? = withContext(Dispatchers.IO) {
        Vod.lastError = null
        val m = match(type, imdb, names, showYear) ?: return@withContext null
        if (m.hits.isEmpty()) return@withContext null
        lists(m).flatMap { (v, eps) -> eps.map { e -> e to epLink(m, v, e) } }
            .groupBy { it.first.season to it.first.number }
            .map { (k, l) -> Episode(k.first, k.second, l.map { it.first.title }.firstOrNull { it.isNotBlank() && !it.contains(" - S", true) }.orEmpty(), l.map { it.second }) }
            .sortedWith(compareBy({ it.season }, { it.number }))
    }

    /** [names] / [showYear]: the title page's own names and year (the same ones [present] matched by), so a press finds what the button promised. */
    suspend fun links(type: String, videoId: String, withTech: Boolean = false, names: List<String> = emptyList(), showYear: Int? = null): List<Link> = withContext(Dispatchers.IO) {
        Vod.lastError = null
        val m = match(type, videoId, names, showYear) ?: return@withContext emptyList()
        if (type == "movie") {
            val films = ranked(m).filter { it.url != null }.take(if (withTech) 12 else Int.MAX_VALUE)
            val infos = if (withTech) films.chunked(4).flatMap { c -> kotlinx.coroutines.coroutineScope { c.map { v -> async { Vod.vodInfo(v) } }.map { it.await() } } } else films.map { null }
            films.mapIndexed { i, v ->
                link(v.url!!, m.main, m.year?.toString(), v.name, m.plNames[v.pl] ?: "IPTV", DubLang.ofVod(v.name, v.group, m.original)).copy(tech = tech(infos[i]), group = v.group)
            }
        } else {
            val target = EpisodeCheck.targetOf(videoId) ?: return@withContext emptyList()
            lists(m).flatMap { (v, eps) -> eps.filter { it.season == target.season && it.number == target.episode }.map { e -> epLink(m, v, e) } }
        }
    }

    /** A VOD row in Vision TB's group: a release-style file name (the match was ours), so the autoplay checks read it. */
    private val tmdbIds = java.util.Collections.synchronizedMap(HashMap<String, String>())
    /** The title's TMDB id from its IMDb id (TMDB find, kept for the run; "" = none). */
    private fun tmdbOf(imdb: String, kind: String): String? {
        if (!imdb.startsWith("tt")) return null
        val k = "$imdb|$kind"
        tmdbIds[k]?.let { return it.ifEmpty { null } }
        val o = runCatching { com.lekathedon.visionlite.home.Tmdb.get("find/$imdb?external_source=imdb_id") }.getOrNull() ?: return null
        val id = o.optJSONArray(if (kind == "movie") "movie_results" else "tv_results")?.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }?.toString().orEmpty()
        tmdbIds[k] = id
        return id.ifEmpty { null }
    }

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
