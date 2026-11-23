package com.lekathedon.visionlite.tb

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.Reader
import java.io.StringReader
import java.net.URLEncoder

/** A request a source wants made. Bodies are JSON; the engine adds auth where marked. */
data class SourceRequest(
    val source: DiscoverySource,
    val url: String,
    val jsonBody: String? = null,
    val needsTorboxAuth: Boolean = false,
    /** The URL holds the user's key (keyed sources): never log or store it. */
    val secretUrl: Boolean = false,
    /** Which source this counts for in Vision TB's report (TbSources); null = [source]'s own. */
    val key: String? = null
) {
    val statKey: String get() = key ?: source.name
}

/**
 * Request builders and response parsers for every discovery source. Parsers never throw:
 * a changed or broken response gives an empty list, and the engine logs the count.
 */
object DiscoverySources {
    const val TORBOX_SEARCH_BASE = "https://search-api.torbox.app"

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    /** TorBox Search API by IMDb id (Prowlarr definition's route), cached releases only. */
    fun torboxSearch(target: DiscoveryTarget): SourceRequest? {
        val imdb = target.imdbId ?: return null
        val episodePart = if (target.isSeries && target.season != null && target.episode != null) {
            "&season=${target.season}&episode=${target.episode}"
        } else ""
        return SourceRequest(
            DiscoverySource.TORBOX_SEARCH,
            "$TORBOX_SEARCH_BASE/torrents/imdb:$imdb?check_cache=true&cached_only=true$episodePart",
            needsTorboxAuth = true
        )
    }

    /** apibay (The Pirate Bay's API) searched by IMDb id: results carry their own imdb tag. */
    fun apibay(target: DiscoveryTarget): SourceRequest? {
        val imdb = target.imdbId ?: return null
        return SourceRequest(DiscoverySource.APIBAY, "https://apibay.org/q.php?q=${enc(imdb)}&cat=${if (target.isSeries) "200" else "0"}")
    }

    const val TORRENTIO_BASE = "https://torrentio.strem.fun"
    const val COMET_BASE = "https://cometfortheweebs.midnightignite.me"
    const val METEOR_BASE = "https://meteorfortheweebs.midnightignite.me"

    fun torrentio(target: DiscoveryTarget) = stremio(DiscoverySource.TORRENTIO, TORRENTIO_BASE, target)

    /**
     * Torrentio's regional trackers alone: Brazilian (Comando, BluDV, Mico-Leão Dublado), Russian (Rutor,
     * RuTracker), French (Torrent9), Italian (ilCorSaRoNeRo), Spanish (MejorTorrent, Wolfmax4K, Cinecalidad),
     * Polish (BestTorrents). Its default list includes them but capped; asked alone more come back (live
     * 2026-10-29: Money Heist S01E01 +9, Invisible City +1).
     */
    const val TORRENTIO_REGIONAL_PROVIDERS =
        "rutor,rutracker,comando,bludv,micoleaodublado,torrent9,ilcorsaro,mejortorrent,wolfmax4k,cinecalidad,besttorrents"
    fun torrentioRegional(target: DiscoveryTarget) =
        stremio(DiscoverySource.TORRENTIO_REGIONAL, "$TORRENTIO_BASE/providers=$TORRENTIO_REGIONAL_PROVIDERS", target)
    fun comet(target: DiscoveryTarget) = stremio(DiscoverySource.COMET, COMET_BASE, target)
    fun meteor(target: DiscoveryTarget) = stremio(DiscoverySource.METEOR, METEOR_BASE, target)

    /**
     * Peerflix, no key (live 2026-11-05: Shawshank 173 hashes, 135 not on Torrentio; tt15239678 384 / 283 new;
     * Breaking Bad S01E01 55 / 43 new). Strong on Spanish trackers (DivxTotal, GranTorrent, pctnew) but carries
     * English 4K too. Torrentio-style title lines ("👤 11 💾 347.58 MB"). DMM's own scraper uses it.
     */
    const val PEERFLIX_BASE = "https://addon.peerflix.mov"
    fun peerflix(target: DiscoveryTarget) = stremio(DiscoverySource.PEERFLIX, PEERFLIX_BASE, target)

    /**
     * StremThru's Torz index by IMDb id, no key. Its `/v0/torrents` route returned everything its Torznab did on 8
     * titles (live 2026-11-06) plus the rows Torznab hides, marked private (25-86 on 4 movies, 3-12 on 5 episodes).
     * Those only play if TorBox already has them, and that is all Vision TB shows. Answers carry each torrent's file
     * list, up to 12.4 MB of JSON for One Piece and 75 KB without the lists, so read them with [parseStremThru]
     * over the stream and never as one String. Community-hosted.
     */
    const val STREMTHRU_BASE = "https://stremthru.13377001.xyz"
    fun stremThru(target: DiscoveryTarget): SourceRequest? {
        val imdb = target.imdbId ?: return null
        val sid = if (target.isSeries) {
            val s = target.season ?: return null
            val e = target.episode ?: return null
            "$imdb:$s:$e"
        } else imdb
        return SourceRequest(DiscoverySource.STREMTHRU, "$STREMTHRU_BASE/v0/torrents?sid=${enc(sid)}")
    }

    /**
     * Reads as the answer arrives and skips the file lists unread, so they are never held in memory. Every item is
     * found by the IMDb id asked, so it counts as id-tagged. Unknown seeders / size may be <= 0. A dropped connection
     * throws IOException, as the engine's other reads do; an answer of another shape gives what was read so far.
     */
    internal fun parseStremThru(answer: Reader): List<DiscoveryCandidate> {
        val out = ArrayList<DiscoveryCandidate>()
        try {
            val json = JsonReader(answer)
            json.beginObject()
            while (json.hasNext()) {
                if (json.nextName() != "data" || json.peek() != JsonToken.BEGIN_OBJECT) {
                    json.skipValue()
                    continue
                }
                json.beginObject()
                while (json.hasNext()) {
                    if (json.nextName() != "items" || json.peek() != JsonToken.BEGIN_ARRAY) {
                        json.skipValue()
                        continue
                    }
                    json.beginArray()
                    while (json.hasNext()) readStremThruItem(json)?.let(out::add)
                    return out
                }
                return out
            }
        } catch (_: IllegalStateException) {
            // Not the expected shape.
        }
        return out
    }

    private fun readStremThruItem(json: JsonReader): DiscoveryCandidate? {
        if (json.peek() != JsonToken.BEGIN_OBJECT) {
            json.skipValue()
            return null
        }
        var hash: String? = null
        var name: String? = null
        var size: Long? = null
        var seeders: Long? = null
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "hash" -> hash = json.stringOrNull()
                "name" -> name = json.stringOrNull()
                "size" -> size = json.stringOrNull()?.toLongOrNull()
                "seeders" -> seeders = json.stringOrNull()?.toLongOrNull()
                else -> json.skipValue() // the file lists among them
            }
        }
        json.endObject()
        return DiscoveryCandidate(
            hash = InfoHashes.normalize(hash) ?: return null,
            name = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null,
            sizeBytes = size?.takeIf { it > 0 },
            seeders = seeders?.takeIf { it >= 0 }?.toInt(),
            source = DiscoverySource.STREMTHRU,
            idTagged = true
        )
    }

    /** A string or number as text; anything else is skipped. */
    private fun JsonReader.stringOrNull(): String? = when (peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> nextString()
        else -> {
            skipValue()
            null
        }
    }

    /**
     * TorrentsDB: public Stremio add-on, no key (live 2026-11-06: 17-173 rows a title, ~0.5 s). Found in the Magneto
     * Kodi module. Torrentio-style lines. Adds a few regional and 4K rows the others lack.
     */
    const val TORRENTSDB_BASE = "https://torrentsdb.com"
    fun torrentsDb(target: DiscoveryTarget) = stremio(DiscoverySource.TORRENTSDB, TORRENTSDB_BASE, target)

    /**
     * Zilean: index of Debrid Media Manager's shared hash lists, by IMDb id, no key (live 2026-11-06: 2-372 rows a
     * title, ~0.5 s, mostly packs). Found in the Magneto Kodi module. Community-hosted.
     */
    const val ZILEAN_BASE = "https://zilean.stremio.ru"
    fun zilean(target: DiscoveryTarget): SourceRequest? {
        val imdb = target.imdbId ?: return null
        val episodePart = if (target.isSeries) {
            val s = target.season ?: return null
            val e = target.episode ?: return null
            "&Season=$s&Episode=$e"
        } else ""
        return SourceRequest(DiscoverySource.ZILEAN, "$ZILEAN_BASE/dmm/filtered?ImdbId=${enc(imdb)}$episodePart")
    }

    /** Every item is found by the IMDb id asked, so it counts as id-tagged. Size comes as a string. */
    internal fun parseZilean(body: String): List<DiscoveryCandidate> {
        val array = JSONArray(body)
        val out = ArrayList<DiscoveryCandidate>(array.length())
        for (i in 0 until array.length()) {
            val t = array.optJSONObject(i) ?: continue
            val hash = InfoHashes.normalize(t.optStringOrNull("info_hash")) ?: continue
            val name = t.optStringOrNull("raw_title") ?: continue
            out += DiscoveryCandidate(
                hash = hash,
                name = name,
                sizeBytes = t.optStringOrNull("size")?.toLongOrNull()?.takeIf { it > 0 },
                seeders = null,
                source = DiscoverySource.ZILEAN,
                idTagged = true
            )
        }
        return out
    }

    /** A public Stremio add-on asked by IMDb id with no config: raw torrents (hash + file), no debrid key. */
    private fun stremio(source: DiscoverySource, base: String, target: DiscoveryTarget): SourceRequest? {
        val imdb = target.imdbId ?: return null
        val path = if (target.isSeries) {
            val s = target.season ?: return null
            val e = target.episode ?: return null
            "series/$imdb:$s:$e"
        } else "movie/$imdb"
        return SourceRequest(source, "$base/stream/$path.json")
    }

    /** Your add-on (Settings › Vision TB) by IMDb id; its URL may hold your keys, so it is never logged. */
    fun userAddon(base: String, key: String, target: DiscoveryTarget): SourceRequest? =
        stremio(DiscoverySource.USER_ADDON, base, target)?.copy(secretUrl = true, key = key)

    fun knaben(query: String): SourceRequest = SourceRequest(
        DiscoverySource.KNABEN,
        "https://api.knaben.org/v1",
        jsonBody = JSONObject()
            .put("search_type", "100%")
            .put("search_field", "title")
            .put("query", query)
            .put("order_by", "seeders")
            .put("order_direction", "desc")
            .put("size", 100)
            .put("hide_unsafe", true)
            .put("hide_xxx", true)
            .toString()
    )

    fun torrentsCsv(query: String): SourceRequest =
        SourceRequest(DiscoverySource.TORRENTS_CSV, "https://torrents-csv.com/service/search?q=${enc(query)}&size=100")

    fun nyaa(query: String): SourceRequest =
        SourceRequest(DiscoverySource.NYAA, "https://nyaa.si/?page=rss&q=${enc(query)}&c=1_2&f=0")

    fun parse(source: DiscoverySource, body: String, target: DiscoveryTarget): List<DiscoveryCandidate> = try {
        when (source) {
            DiscoverySource.TORBOX_SEARCH -> parseTorboxSearch(body)
            DiscoverySource.APIBAY -> parseApibay(body, target.imdbId)
            DiscoverySource.KNABEN -> parseKnaben(body)
            DiscoverySource.TORRENTS_CSV -> parseTorrentsCsv(body)
            DiscoverySource.NYAA -> parseNyaa(body)
            DiscoverySource.STREMTHRU -> parseStremThru(StringReader(body))
            DiscoverySource.ZILEAN -> parseZilean(body)
            DiscoverySource.ADDONS, DiscoverySource.ARABP2P -> emptyList()
            // Same Stremio shape as Comet, but not trusted: Comet returns other arcs' same episode number by Kitsu id.
            DiscoverySource.COMET_KITSU -> parseStremioStreams(body, source).map { it.copy(trustedMatch = false) }
            DiscoverySource.TSUKIHIME -> AnimeSources.parseTsukiHime(body)
            DiscoverySource.ANIMETOSHO -> AnimeSources.parseAnimeTosho(body)
            DiscoverySource.NEKOBT -> AnimeSources.parseNekoBt(body, target.anime?.anilistId)
            DiscoverySource.JACKETTIO, DiscoverySource.METEOR_KEYED, DiscoverySource.USER_ADDON -> AnimeSources.parseKeyedStreams(body, source)
            DiscoverySource.TORRENTIO, DiscoverySource.COMET, DiscoverySource.METEOR,
            DiscoverySource.TORRENTIO_REGIONAL, DiscoverySource.PEERFLIX, DiscoverySource.TORRENTSDB -> parseStremioStreams(body, source)
        }
    } catch (_: Throwable) {
        // Throwable, not Exception: a parser that can't load on the device (bad pattern) costs one source, not Vision TB.
        emptyList()
    }

    internal fun parseTorboxSearch(body: String): List<DiscoveryCandidate> {
        val root = JSONObject(body)
        if (!root.optBoolean("success", false)) return emptyList()
        val torrents = root.optJSONObject("data")?.optJSONArray("torrents") ?: return emptyList()
        val out = ArrayList<DiscoveryCandidate>()
        for (i in 0 until torrents.length()) {
            val t = torrents.optJSONObject(i) ?: continue
            val hash = InfoHashes.normalize(t.optStringOrNull("hash")) ?: InfoHashes.fromMagnet(t.optStringOrNull("magnet")) ?: continue
            val name = t.optStringOrNull("raw_title") ?: t.optStringOrNull("title") ?: continue
            out += DiscoveryCandidate(
                hash = hash,
                name = name,
                sizeBytes = t.optLongOrNull("size"),
                seeders = t.optLongOrNull("last_known_seeders")?.toInt(),
                source = DiscoverySource.TORBOX_SEARCH,
                idTagged = true,
                sourceSaysCached = if (t.has("cached")) t.optBoolean("cached") else null
            )
        }
        return out
    }

    internal fun parseApibay(body: String, imdbId: String?): List<DiscoveryCandidate> {
        val array = JSONArray(body)
        val out = ArrayList<DiscoveryCandidate>()
        for (i in 0 until array.length()) {
            val t = array.optJSONObject(i) ?: continue
            val hash = InfoHashes.normalize(t.optStringOrNull("info_hash")) ?: continue
            if (hash.all { it == '0' }) continue // "No results returned" placeholder
            val name = t.optStringOrNull("name") ?: continue
            val tag = t.optStringOrNull("imdb")
            out += DiscoveryCandidate(
                hash = hash,
                name = name,
                sizeBytes = t.optStringOrNull("size")?.toLongOrNull(),
                seeders = t.optStringOrNull("seeders")?.toIntOrNull(),
                source = DiscoverySource.APIBAY,
                idTagged = imdbId != null && tag.equals(imdbId, ignoreCase = true)
            )
        }
        return out
    }

    internal fun parseKnaben(body: String): List<DiscoveryCandidate> {
        val hits = JSONObject(body).optJSONArray("hits") ?: return emptyList()
        val out = ArrayList<DiscoveryCandidate>()
        for (i in 0 until hits.length()) {
            val t = hits.optJSONObject(i) ?: continue
            val hash = InfoHashes.normalize(t.optStringOrNull("hash")) ?: InfoHashes.fromMagnet(t.optStringOrNull("magnetUrl")) ?: continue
            val name = t.optStringOrNull("title") ?: continue
            out += DiscoveryCandidate(hash, name, t.optLongOrNull("bytes"), t.optLongOrNull("seeders")?.toInt(), DiscoverySource.KNABEN)
        }
        return out
    }

    internal fun parseTorrentsCsv(body: String): List<DiscoveryCandidate> {
        val torrents = JSONObject(body).optJSONArray("torrents") ?: return emptyList()
        val out = ArrayList<DiscoveryCandidate>()
        for (i in 0 until torrents.length()) {
            val t = torrents.optJSONObject(i) ?: continue
            val hash = InfoHashes.normalize(t.optStringOrNull("infohash")) ?: continue
            val name = t.optStringOrNull("name") ?: continue
            out += DiscoveryCandidate(hash, name, t.optLongOrNull("size_bytes"), t.optLongOrNull("seeders")?.toInt(), DiscoverySource.TORRENTS_CSV)
        }
        return out
    }

    private val STREMIO_SIZE = Regex("💾\\s*([\\d.]+)\\s*([KMGT])i?B", RegexOption.IGNORE_CASE)
    private val STREMIO_SEEDERS = Regex("[👥👤]\\s*(\\d+)")

    /** Stremio add-on streams with an infoHash (no debrid): name from the first description line. */
    internal fun parseStremioStreams(body: String, source: DiscoverySource): List<DiscoveryCandidate> {
        val streams = JSONObject(body).optJSONArray("streams") ?: return emptyList()
        val out = ArrayList<DiscoveryCandidate>()
        for (i in 0 until streams.length()) {
            val s = streams.optJSONObject(i) ?: continue
            if (!s.optStringOrNull("url").isNullOrBlank()) continue
            val hash = InfoHashes.normalize(s.optStringOrNull("infoHash")) ?: continue
            // Torrentio: title = torrent name, then the file. Comet/Meteor: description starts with the file.
            val description = s.optStringOrNull("description") ?: s.optStringOrNull("title").orEmpty()
            val hints = s.optJSONObject("behaviorHints")
            val fileName = hints?.optStringOrNull("filename")
            val firstLine = description.lineSequence().firstOrNull()?.replace(Regex("^[^\\p{L}\\p{N}\\[(]+"), "")?.trim()?.takeIf { it.isNotEmpty() }
                ?: fileName
                ?: continue
            // Vision (2026-11-17): Comet/Meteor start with the bare file name ("Cyberpunk.Edgerunners.S01E06.mkv");
            // the resolution sits in the stream's name ("Meteor\n2160p"), a later description line ("2160p | h265")
            // or the pack's folder ("... 4K HEVC AI"). Without it a cached 4K pack showed as an unrated row, sorted
            // low, and auto-play never took it. Carry the resolution over when the name itself has none.
            val name = if (ReleaseQuality.parse(firstLine).resolution != null) firstLine else {
                val hintText = listOfNotNull(
                    s.optStringOrNull("name"),
                    description.lineSequence().drop(1).joinToString(" "),
                    hints?.optStringOrNull("folderName")
                ).joinToString(" ")
                ReleaseQuality.parse(hintText).resolution?.let { "$firstLine [${it}p]" } ?: firstLine
            }
            // "💾 7.79 GiB / 111.51 GiB": the first value is the file, the second the pack.
            val size = hints?.optLongOrNull("videoSize") ?: STREMIO_SIZE.find(description)?.let { m ->
                val mult = when (m.groupValues[2].uppercase()) { "K" -> 1L shl 10; "M" -> 1L shl 20; "G" -> 1L shl 30; else -> 1L shl 40 }
                m.groupValues[1].toDoubleOrNull()?.let { (it * mult).toLong() }
            }
            out += DiscoveryCandidate(
                hash = hash,
                name = name,
                sizeBytes = size,
                seeders = STREMIO_SEEDERS.find(description)?.groupValues?.get(1)?.toIntOrNull(),
                source = source,
                idTagged = true,
                trustedMatch = true,
                fileIdx = if (s.has("fileIdx") && !s.isNull("fileIdx")) s.optInt("fileIdx") else null,
                fileName = fileName
            )
        }
        return out
    }

    private val NYAA_ITEM = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
    private val NYAA_TITLE = Regex("<title>(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?</title>", RegexOption.DOT_MATCHES_ALL)
    private val NYAA_HASH = Regex("<nyaa:infoHash>([0-9a-fA-F]{40})</nyaa:infoHash>")
    private val NYAA_SEEDERS = Regex("<nyaa:seeders>(\\d+)</nyaa:seeders>")
    private val NYAA_SIZE = Regex("<nyaa:size>([\\d.]+) ?([KMGT])iB</nyaa:size>")

    internal fun parseNyaa(body: String): List<DiscoveryCandidate> =
        NYAA_ITEM.findAll(body).mapNotNull { item ->
            val xml = item.groupValues[1]
            val hash = InfoHashes.normalize(NYAA_HASH.find(xml)?.groupValues?.get(1)) ?: return@mapNotNull null
            val name = NYAA_TITLE.find(xml)?.groupValues?.get(1)?.unescapeXml() ?: return@mapNotNull null
            val size = NYAA_SIZE.find(xml)?.let { m ->
                val mult = when (m.groupValues[2]) { "K" -> 1L shl 10; "M" -> 1L shl 20; "G" -> 1L shl 30; else -> 1L shl 40 }
                m.groupValues[1].toDoubleOrNull()?.let { (it * mult).toLong() }
            }
            DiscoveryCandidate(hash, name, size, NYAA_SEEDERS.find(xml)?.groupValues?.get(1)?.toIntOrNull(), DiscoverySource.NYAA)
        }.toList()

    /** TorBox checkcached (object format, list_files=true) -> verdict per asked hash. */
    fun parseCheckCached(body: String, asked: Collection<String>): Map<String, CacheVerdict>? {
        val root = try { JSONObject(body) } catch (_: Exception) { return null }
        if (!root.optBoolean("success", false)) return null
        val data = root.opt("data")
        val found = HashMap<String, CacheVerdict.Cached>()
        fun read(item: JSONObject, keyHash: String?) {
            val hash = InfoHashes.normalize(item.optStringOrNull("hash") ?: keyHash) ?: return
            val filesJson = item.optJSONArray("files")
            val files = if (filesJson == null) emptyList() else (0 until filesJson.length()).mapNotNull { idx ->
                val f = filesJson.optJSONObject(idx) ?: return@mapNotNull null
                val path = f.optStringOrNull("name") ?: f.optStringOrNull("short_name") ?: return@mapNotNull null
                CachedFile(f.optLongOrNull("id")?.toInt(), path, f.optLongOrNull("size"), f.optStringOrNull("mimetype"))
            }
            found[hash] = CacheVerdict.Cached(item.optStringOrNull("name"), item.optLongOrNull("size"), files)
        }
        when (data) {
            is JSONObject -> data.keys().forEach { key -> data.optJSONObject(key)?.let { read(it, key) } }
            is JSONArray -> for (i in 0 until data.length()) data.optJSONObject(i)?.let { read(it, null) }
            else -> Unit // null data = nothing cached
        }
        return asked.associateWith { found[it] ?: CacheVerdict.NotCached }
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
