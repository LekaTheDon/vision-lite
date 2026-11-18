package com.lekathedon.visionlite.tb

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * An anime's ids in the anime databases, for the anime-only sources. Null = not known (that source is skipped).
 * [kitsuEpisode] is the episode inside the Kitsu entry (an entry is one season and counts from 1).
 */
data class AnimeRefs(
    val kitsuId: Int? = null,
    val kitsuEpisode: Int? = null,
    val anilistId: Int? = null,
    val anidbId: Int? = null
)

/**
 * Anime sources for Vision TB (handoff 082, live checks 2026-11-08) and the two sources that need the user's
 * TorBox key. Pure: request builders and parsers only; parsers never throw (a changed answer gives nothing).
 *
 * - Comet asked by Kitsu id: +426 releases over 5 anime episodes that the IMDb-id sources lack. Its rows are
 *   NOT trusted like other add-on rows: Comet returns the same episode number of other arcs and seasons (Demon
 *   Slayer episode 5 also brought Entertainment District, Mugen Train, Swordsmith Village and manga), so each
 *   name must carry one of the show's titles (TMDB's plus Kitsu's own) followed by a release marker.
 * - TsukiHime (search route; its anime route lists newest first, 100 a page), Anime Tosho (by AniDB id, first
 *   page of 75), nekoBT (Torznab, no key needed; a key is optional): text and id searches, matched like Nyaa.
 * - SeaDex: no new hashes, but marks the best release of a show; used as a badge and a Best link tie-break.
 * - Jackettio and Meteor with the user's TorBox key (both on midnightignite.me): rows carry the hash in the
 *   playback URL's path; the URL itself (it holds the key) is never kept or logged.
 */
object AnimeSources {
    const val KITSU_API = "https://kitsu.app/api/edge"
    const val TSUKIHIME_API = "https://api.tsukihime.org"
    const val ANIMETOSHO_FEED = "https://feed.animetosho.net/json"
    const val NEKOBT_TORZNAB = "https://nekobt.to/api/torznab/api"
    const val SEADEX_API = "https://seadex.moe/api/collections/entries/records"
    const val JACKETTIO_BASE = "https://jackettiofortheweebs.midnightignite.me"

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    /** Comet by Kitsu id: series `kitsu:ID:EP`, movies `kitsu:ID`. */
    fun cometKitsu(target: DiscoveryTarget): SourceRequest? {
        val refs = target.anime ?: return null
        val id = refs.kitsuId ?: return null
        val path = if (target.isSeries) {
            val ep = refs.kitsuEpisode ?: return null
            "series/kitsu:$id:$ep"
        } else "movie/kitsu:$id"
        return SourceRequest(DiscoverySource.COMET_KITSU, "${DiscoverySources.COMET_BASE}/stream/$path.json")
    }

    /** Kitsu's titles for one entry (one request, kept a day by the engine). */
    fun kitsuTitlesUrl(kitsuId: Int): String =
        "$KITSU_API/anime/$kitsuId?fields%5Banime%5D=titles,canonicalTitle,abbreviatedTitles"

    /** Every title Kitsu gives the entry: all languages, canonical, abbreviations. Empty on any other answer. */
    fun parseKitsuTitles(body: String): List<String> = try {
        val a = JSONObject(body).optJSONObject("data")?.optJSONObject("attributes")
        if (a == null) emptyList() else {
            val out = ArrayList<String>()
            // Canonical (romaji) first: it is the search query for TsukiHime / nekoBT (see animeQuery).
            a.optStringOrNull("canonicalTitle")?.let(out::add)
            a.optJSONObject("titles")?.let { t -> t.keys().forEach { k -> t.optStringOrNull(k)?.let(out::add) } }
            a.optJSONArray("abbreviatedTitles")?.let { arr -> for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let(out::add) }
            // A 2-3 letter abbreviation ("KnY") would match too loosely.
            out.map { it.trim() }.filter { ReleaseMatcher.normalizeTitle(it).length >= 4 }.distinct()
        }
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Text query for the anime indexers: Kitsu's canonical (romaji) title and the entry's episode ("Sousou no Frieren
     * 05": nekoBT 8 rows, TsukiHime 25; TMDB's "Frieren: Beyond Journey's End 05": 0 and 3, live 2026-11-08).
     * Without Kitsu's titles: the Nyaa query.
     */
    fun animeQuery(target: DiscoveryTarget): String? {
        val title = target.animeTitles.firstOrNull { it.any { ch -> ch in 'A'..'z' } }
            ?: return QueryExpander.animeQuery(target)
        val ep = target.anime?.kitsuEpisode ?: target.episode
        val clean = QueryExpander.foldAccents(title).replace(Regex("[\"':!?,]"), " ").replace(Regex("\\s+"), " ").trim()
        return if (ep != null && target.isSeries) "$clean %02d".format(ep) else clean
    }

    /** TsukiHime's text search, first 100 rows (the API's maximum page). */
    fun tsukiHime(query: String): SourceRequest =
        SourceRequest(DiscoverySource.TSUKIHIME, "$TSUKIHIME_API/v1/search/torrents?q=${enc(query)}&limit=100")

    internal fun parseTsukiHime(body: String): List<DiscoveryCandidate> {
        val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<DiscoveryCandidate>(results.length())
        for (i in 0 until results.length()) {
            val t = results.optJSONObject(i) ?: continue
            if (t.optInt("is_adult", 0) != 0) continue
            val hash = InfoHashes.normalize(t.optStringOrNull("btih")) ?: continue
            val name = t.optStringOrNull("name") ?: continue
            out += DiscoveryCandidate(hash, name, t.optLongOrNull("totalsize")?.takeIf { it > 0 }, null, DiscoverySource.TSUKIHIME)
        }
        return out
    }

    /** Anime Tosho's feed for one AniDB anime: the newest 75 releases (one page). */
    fun animeTosho(target: DiscoveryTarget): SourceRequest? {
        val aid = target.anime?.anidbId ?: return null
        return SourceRequest(DiscoverySource.ANIMETOSHO, "$ANIMETOSHO_FEED?aid=$aid")
    }

    /** Every row is filed under the AniDB id asked, so it counts as id-tagged. */
    internal fun parseAnimeTosho(body: String): List<DiscoveryCandidate> {
        val array = JSONArray(body)
        val out = ArrayList<DiscoveryCandidate>(array.length())
        for (i in 0 until array.length()) {
            val t = array.optJSONObject(i) ?: continue
            val hash = InfoHashes.normalize(t.optStringOrNull("info_hash")) ?: continue
            val name = t.optStringOrNull("torrent_name") ?: t.optStringOrNull("title") ?: continue
            out += DiscoveryCandidate(
                hash = hash,
                name = name,
                sizeBytes = t.optLongOrNull("total_size")?.takeIf { it > 0 },
                seeders = t.optLongOrNull("seeders")?.toInt(),
                source = DiscoverySource.ANIMETOSHO,
                idTagged = true
            )
        }
        return out
    }

    /** nekoBT's Torznab text search (100 rows). [apiKey] is optional; it is only added to the URL when set. */
    fun nekoBt(query: String, apiKey: String?): SourceRequest {
        val key = apiKey?.trim()?.takeIf { it.isNotEmpty() }?.let { "&apikey=${enc(it)}" }.orEmpty()
        return SourceRequest(DiscoverySource.NEKOBT, "$NEKOBT_TORZNAB?t=search&q=${enc(query)}&limit=100$key")
    }

    private val ITEM = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
    // Android's regex engine (ICU) rejects a bare ']' or '}' that desktop Java accepts: always escape them here,
    // or this object fails to load on the TV (vision.49: "qa.d", Vision TB gone for every title).
    private val TITLE = Regex("<title>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</title>", RegexOption.DOT_MATCHES_ALL)
    private val NEKO_TAGS = Regex("\\s*\\{Tags:[^\\}]*\\}\\s*")

    private fun attr(xml: String, name: String): String? =
        Regex("<torznab:attr name=\"$name\" value=\"([^\"]*)\"").find(xml)?.groupValues?.get(1)

    /** Rows tagged with this AniList id count as id-tagged. nekoBT's "{Tags:...}" suffix is cut from names. */
    internal fun parseNekoBt(body: String, anilistId: Int?): List<DiscoveryCandidate> =
        ITEM.findAll(body).mapNotNull { item ->
            val xml = item.groupValues[1]
            val hash = InfoHashes.normalize(attr(xml, "infohash")) ?: return@mapNotNull null
            val name = TITLE.find(xml)?.groupValues?.get(1)?.unescapeXml()?.replace(NEKO_TAGS, " ")?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            DiscoveryCandidate(
                hash = hash,
                name = name,
                sizeBytes = attr(xml, "size")?.toLongOrNull()?.takeIf { it > 0 },
                seeders = attr(xml, "seeders")?.toIntOrNull(),
                source = DiscoverySource.NEKOBT,
                idTagged = anilistId != null && attr(xml, "anilistid")?.toIntOrNull() == anilistId
            )
        }.toList()

    /** SeaDex's entry for one AniList id, only the fields used (about 300 bytes). */
    fun seaDexUrl(anilistId: Int): String =
        "$SEADEX_API?filter=(alID=$anilistId)&expand=trs&fields=alID,expand.trs.infoHash,expand.trs.isBest&perPage=5"

    /** Hashes SeaDex marks best. Private-tracker rows (hash "<redacted>") are dropped by the hash check. */
    fun parseSeaDexBest(body: String): Set<String> = try {
        val out = HashSet<String>()
        val items = JSONObject(body).optJSONArray("items")
        if (items != null) for (i in 0 until items.length()) {
            val trs = items.optJSONObject(i)?.optJSONObject("expand")?.optJSONArray("trs") ?: continue
            for (j in 0 until trs.length()) {
                val t = trs.optJSONObject(j) ?: continue
                if (!t.optBoolean("isBest", false)) continue
                InfoHashes.normalize(t.optStringOrNull("infoHash"))?.let(out::add)
            }
        }
        out
    } catch (_: Exception) {
        emptySet()
    }

    private const val B64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** Base64url without padding (java.util.Base64 needs API 26; the app supports 24). */
    internal fun b64url(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1
            out.append(B64URL[b0 ushr 2])
            out.append(B64URL[((b0 and 0x03) shl 4) or (if (b1 < 0) 0 else b1 ushr 4)])
            if (b1 >= 0) out.append(B64URL[((b1 and 0x0F) shl 2) or (if (b2 < 0) 0 else b2 ushr 6)])
            if (b2 >= 0) out.append(B64URL[b2 and 0x3F])
            i += 3
        }
        return out.toString()
    }

    private fun stremioPath(target: DiscoveryTarget): String? {
        val imdb = target.imdbId ?: return null
        return if (target.isSeries) {
            val s = target.season ?: return null
            val e = target.episode ?: return null
            "series/$imdb:$s:$e"
        } else "movie/$imdb"
    }

    /** Jackettio with the user's TorBox key in its config (it needs a debrid service to answer). */
    fun jackettio(target: DiscoveryTarget, torboxKey: String): SourceRequest? {
        val path = stremioPath(target) ?: return null
        if (torboxKey.isBlank()) return null
        val config = JSONObject()
            .put("qualities", JSONArray(listOf(0, 360, 480, 720, 1080, 2160)))
            .put("maxTorrents", 30)
            .put("hideUncached", false)
            .put("indexerTimeoutSec", 6)
            .put("debridId", "torbox")
            .put("debridApiKey", torboxKey.trim())
        return SourceRequest(DiscoverySource.JACKETTIO, "$JACKETTIO_BASE/${b64url(config.toString())}/stream/$path.json", secretUrl = true)
    }

    /** Meteor with the user's TorBox key: it then also lists what its debrid lookups found (+215 rows on 7 titles). */
    fun meteorKeyed(target: DiscoveryTarget, torboxKey: String): SourceRequest? {
        val path = stremioPath(target) ?: return null
        if (torboxKey.isBlank()) return null
        val config = JSONObject()
            .put("services", JSONArray().put(JSONObject().put("name", "torbox").put("apiKey", torboxKey.trim())))
            .put("maxResults", 0)
        return SourceRequest(DiscoverySource.METEOR_KEYED, "${DiscoverySources.METEOR_BASE}/${b64url(config.toString())}/stream/$path.json", secretUrl = true)
    }

    private val HEX40 = Regex("^[0-9a-fA-F]{40}$")

    /**
     * Keyed add-on rows: the hash is the one 40-hex segment of the playback URL (Jackettio
     * `/download/<type>/<id>/<hash>/<name>`, Meteor `/play/<hash>/<fileIdx>/<name>`). Name = the segment after the
     * hash for Jackettio (its title line is cut short with "..."), else the first title line, else the file name.
     */
    internal fun parseKeyedStreams(body: String, source: DiscoverySource): List<DiscoveryCandidate> {
        val streams = JSONObject(body).optJSONArray("streams") ?: return emptyList()
        val out = ArrayList<DiscoveryCandidate>()
        for (i in 0 until streams.length()) {
            val s = streams.optJSONObject(i) ?: continue
            val url = s.optStringOrNull("url")
            val hints = s.optJSONObject("behaviorHints")
            val fileName = hints?.optStringOrNull("filename")
            val base = AddonHarvest.candidate(
                infoHash = s.optStringOrNull("infoHash"),
                url = url,
                title = s.optStringOrNull("title"),
                description = s.optStringOrNull("description"),
                fileName = fileName,
                videoSize = hints?.optLongOrNull("videoSize"),
                fileIdx = null
            ) ?: continue
            val segments = url?.substringAfter("://")?.substringBefore('?')?.split('/').orEmpty()
            val at = segments.indexOfFirst { HEX40.matches(it) }
            val afterHash = segments.getOrNull(at + 1)?.takeIf { at >= 0 && source == DiscoverySource.JACKETTIO }
                ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
                ?.trim()?.takeIf { it.length >= 6 }
            out += base.copy(
                name = afterHash ?: base.name,
                source = source,
                // Their own id search: kept only when the name still shows the year / episode, like other add-ons.
                trustedMatch = true,
                idTagged = true
            )
        }
        return out
    }

    private fun String.unescapeXml() =
        replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key, "").takeIf { it.isNotBlank() }

    private fun JSONObject.optLongOrNull(key: String): Long? {
        if (isNull(key)) return null
        return when (val v = opt(key)) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull()
            else -> null
        }
    }
}

/**
 * SeaDex "best release" hashes seen in this app run, for the row badge and Best link's tie-break. Process-wide
 * memory only, at most 4000 hashes (40 bytes each), oldest dropped first.
 */
object SeaDexBest {
    private val hashes = object : LinkedHashMap<String, Unit>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > 4000
    }

    fun remember(best: Collection<String>) {
        if (best.isEmpty()) return
        synchronized(hashes) { best.forEach { hashes[it.lowercase()] = Unit } }
    }

    fun isBest(hash: String?): Boolean {
        val h = hash?.trim()?.lowercase()?.takeIf { it.length == 40 } ?: return false
        return synchronized(hashes) { hashes.containsKey(h) }
    }
}
