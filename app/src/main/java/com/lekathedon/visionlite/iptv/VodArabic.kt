package com.lekathedon.visionlite.iptv

import com.lekathedon.visionlite.addons.MetaPreview
import com.lekathedon.visionlite.home.Tmdb
import com.lekathedon.visionlite.home.objects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * Arabic VOD rows (user 2026-11-23): your playlist's Arabic categories (Egyptian, Gulf, dubbed, Ramadan, …) as Home
 * rows in the profiles that switch them on. Found by the group's name: Arabic letters, not Persian/Kurdish, not songs,
 * sports or Quran. Anime categories go to the Anime hub only. Each card is matched to TMDB once (Arabic search, saved in
 * vod.db) so it opens the full title page and Vision TB; a title TMDB doesn't know plays straight from VOD.
 * Cost: no calls until a row shows; then ≤40 TMDB searches per row, once ever per title.
 */
object VodArabic {
    const val PREFIX = "vod/"
    private val ARABIC = Regex("[\\u0621-\\u064A]")
    private val PERSIAN = Regex("[\\u067E\\u0686\\u0698\\u06AF\\u06A9\\u06CC]") // پ چ ژ گ ک ی
    private val FOREIGN = Regex("^\\s*(IR|KU|FA|KR|TR|IN|PK|AF)\\b", RegexOption.IGNORE_CASE)
    private val AR_TAG = Regex("^\\s*(AR|ARA|ARABIC|عربي)\\b", RegexOption.IGNORE_CASE)
    private val SKIP = listOf("اغاني", "أغاني", "اغانى", "طرب", "كليب", "رياض", "مصارع", "قران", "قرآن", "اناشيد", "أناشيد", "ufc", "wwe", "sport", "music", "+18", "18+", "adult")
    private val ANIME = listOf("انمي", "أنمي", "انيمي", "أنيمي", "anime")

    data class Group(val kind: String, val grp: String, val count: Int) {
        val key get() = "$PREFIX$kind/$grp"
        val anime get() = ANIME.any { grp.contains(it, ignoreCase = true) }
    }

    fun isArabic(g: String): Boolean {
        if (FOREIGN.containsMatchIn(g) || PERSIAN.containsMatchIn(g)) return false
        if (SKIP.any { g.contains(it, ignoreCase = true) }) return false
        return ARABIC.containsMatchIn(g) || AR_TAG.containsMatchIn(g)
    }

    @Volatile private var cache: Pair<Int, List<Group>>? = null
    /** The Arabic categories, films first (read from vod.db; cached until VOD reloads). */
    fun groups(): List<Group> {
        val v = Vod.version.value
        cache?.takeIf { it.first == v }?.let { return it.second }
        val list = runCatching { listOf("movie", "series").flatMap { k -> Vod.groups(k).filter { isArabic(it.first) && it.second > 0 }.map { Group(k, it.first, it.second) } } }
            .getOrDefault(emptyList())
        cache = v to list
        return list
    }

    fun byKey(key: String): Group? = groups().firstOrNull { it.key == key }

    private fun vodId(v: VodItem) = "vod:${v.pl}|${v.kind}|${v.id}"
    fun item(id: String): VodItem? {
        val p = id.removePrefix("vod:").split('|', limit = 3).takeIf { it.size == 3 } ?: return null
        return Vod.item(p[0], p[1], p[2])
    }

    private val YEAR = Regex("\\b(19[3-9]\\d|20[0-4]\\d)\\b")
    private val NOISE = Regex("^\\s*[A-Z]{2,3}\\s*[-|:]\\s*|\\[[^]]*]|\\([^)]*\\)|\\b(4K|UHD|FHD|HD|SD|1080p?|720p?|HEVC|x26[45]|مدبلج|مترجم|كامل|بجودة.*)\\b", RegexOption.IGNORE_CASE)
    private fun query(name: String) = name.replace(NOISE, " ").replace(YEAR, " ").replace(Regex("[\\-_|:.]+"), " ").replace(Regex("\\s+"), " ").trim()

    /** A row's first 40 titles as cards; [matched] = after TMDB matching (two passes: VOD cards at once, matched next). */
    fun cards(g: Group, matchNow: Boolean): List<MetaPreview> = Vod.items(g.kind, g.grp, 0, 40).map { v ->
        val m = Vod.metaGet(mapKey(v))?.takeIf { it.isNotEmpty() && it != "-" }
        val type = if (g.kind == "series") "series" else "movie"
        if (m != null) {
            val (tm, poster) = m.split('\n', limit = 2).let { it[0] to it.getOrNull(1)?.ifBlank { null } }
            MetaPreview(tm, type, v.name.replace(NOISE, " ").trim().ifBlank { v.name }, poster ?: v.poster, releaseInfo = YEAR.find(v.name)?.value)
        } else MetaPreview(vodId(v), type, v.name, v.poster, releaseInfo = YEAR.find(v.name)?.value)
    }

    private fun mapKey(v: VodItem) = "tm|${v.pl}|${v.kind}|${v.id}"

    /** Matches this row's unmatched titles to TMDB (Arabic search; saved, never asked again). False = no TMDB key. */
    suspend fun match(g: Group): Boolean = withContext(Dispatchers.IO) {
        if (Tmdb.key() == null) return@withContext false
        val todo = Vod.items(g.kind, g.grp, 0, 40).filter { Vod.metaGet(mapKey(it)) == null }
        val gate = Semaphore(4)
        coroutineScope {
            todo.map { v -> async { gate.withPermit {
                val q = query(v.name).takeIf { it.length >= 2 } ?: return@withPermit Vod.metaPut(mapKey(v), "-")
                val tv = v.kind == "series"
                val y = YEAR.find(v.name)?.value
                val path = "search/${if (tv) "tv" else "movie"}?language=ar&query=${URLEncoder.encode(q, "UTF-8")}" +
                    (if (y != null) (if (tv) "&first_air_date_year=$y" else "&year=$y") else "")
                val o = Tmdb.get(path) ?: return@withPermit // network trouble: try again next time
                val r = o.optJSONArray("results").objects().firstOrNull() ?: return@withPermit Vod.metaPut(mapKey(v), "-")
                val id = "tmdb:${if (tv) "tv" else "movie"}:${r.optInt("id")}"
                Vod.metaPut(mapKey(v), id + "\n" + (Tmdb.img(r.optString("poster_path"), "w342") ?: ""))
                Vod.metaPut("vodtm|$id", "${v.pl}|${v.kind}|${v.id}")
            } } }.awaitAll()
        }
        true
    }

    /** A matched card was opened and its IMDb id is known: Vision TB's VOD group finds this title by it. */
    fun remember(tmdbId: String, imdb: String) {
        val v = runCatching { Vod.metaGet("vodtm|$tmdbId") }.getOrNull() ?: return
        runCatching { Vod.metaPut("vodimdb|$imdb", v) }
    }

    /** The VOD title saved for this IMDb id (from a Home VOD card), if any. */
    fun forImdb(imdb: String): VodItem? = runCatching { Vod.metaGet("vodimdb|$imdb") }.getOrNull()?.let { item("vod:$it") }
}
