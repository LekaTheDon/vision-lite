// Any tile's page as a Home row (user 2026-11-24): a genre, decade, streaming service, mood, Scout tile or production
// company page, either the whole page (a mix of its first rows) or one row from inside it. Also the Anime hub's own
// Build your own row (MAL genres + themes + demographics, all together).
package com.lekathedon.visionlite.home

import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.addons.MetaPreview
import com.lekathedon.visionlite.anime.Hub
import com.lekathedon.visionlite.anime.MalGenres
import com.lekathedon.visionlite.browse.Wuplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * A page row is kept in the imported lists as source "PAGE", remoteId "kind~arg~rail~media" ([ALL] = the whole page),
 * and the hub it was added in (a row shows only on that hub's Home). Loaded like every row: only when it nears the screen.
 * Cost: one row = 1-2 pages of its source (TMDB, or the MAL pool on the box for anime); the whole page = its first
 * 4 rows' first page. Nothing at app start.
 */
object PageRows {
    const val ALL = "*"
    /** A row of a page: [key] stays stable (saved with the Home row), [title] is what the page shows. */
    data class R(val key: String, val title: String)

    private const val MAX = 40

    fun encode(kind: String, arg: String, rail: String, media: String = "all") = listOf(kind, arg, rail, media).joinToString("~")

    /** The rows a page shows, in its order (what the "Add to Home" picker lists). */
    suspend fun rails(kind: String, arg: String): List<R> = when (kind) {
        "genre" -> Wuplay.genre(arg)?.rails.orEmpty().map { R(it.key, it.title) }
        "decade" -> Wuplay.decade(arg)?.rails.orEmpty().map { R(it.key, it.title) }
        "agenre" -> ANIME_GENRE_RAILS.map { R(it.first, it.second) } +
            runCatching { MalGenres.subsWithin(arg) }.getOrDefault(emptyList()).map { R("sub|$it", it) }
        "adecade" -> listOf(R("pop|series", "Popular shows"), R("pop|movie", "Popular movies")) + MalGenres.MAIN.map { R("sub|$it", it) }
        "service" -> Services.S.entries.firstOrNull { it.id == arg }?.let { s -> Services.rows(s).map { R(it.first, it.first) } }.orEmpty()
        "mood" -> com.lekathedon.visionlite.recs.Moods.byKey(arg)?.let { m -> listOfNotNull(R("films", "Films").takeIf { !m.seriesOnly }, R("shows", "Shows")) }.orEmpty()
        "scout" -> com.lekathedon.visionlite.recs.ScoutSlices.byId(arg)?.takeIf { it.award == null }?.let { s ->
            listOfNotNull(R("films", "Films").takeIf { s.movie != null }, R("shows", "Shows").takeIf { s.tv != null }) }.orEmpty()
        "company" -> runCatching { com.lekathedon.visionlite.browse.Companies.rows(arg) }.getOrDefault(emptyList()).map { R(it.first, it.first) }
        "abuild" -> listOf(R(ALL, "Titles"))
        else -> emptyList()
    }

    /** The Anime genre page's own rows (key = "type|sort"). */
    val ANIME_GENRE_RAILS = listOf("series|popular" to "Popular shows", "movie|popular" to "Popular movies",
        "series|top" to "Top rated shows", "movie|top" to "Top rated movies", "series|latest" to "Latest shows")

    /** Page [page] (1-based) of one row of a page. */
    suspend fun load(kind: String, arg: String, rail: String, media: String, page: Int, hub: Hub): List<MetaPreview> = withContext(Dispatchers.IO) {
        val skip = (page - 1) * 30
        when (kind) {
            "genre" -> Wuplay.genre(arg)?.rails?.firstOrNull { it.key == rail }?.let { Wuplay.page(it, mediaOf(media), page) }.orEmpty()
            "decade" -> Wuplay.decade(arg)?.rails?.firstOrNull { it.key == rail }?.let { Wuplay.page(it, mediaOf(media), page) }.orEmpty()
            "agenre" -> if (rail.startsWith("sub|")) MalGenres.browseAll(listOf(arg, rail.removePrefix("sub|")), null, "popular", skip = skip)
                else rail.split('|').let { (t, s) -> MalGenres.browse(arg, t, s, skip = skip) }
            "adecade" -> {
                val decade = arg.toIntOrNull() ?: return@withContext emptyList()
                val to = minOf(decade + 9, Calendar.getInstance().get(Calendar.YEAR))
                if (rail.startsWith("pop|")) MalGenres.browse(null, rail.removePrefix("pop|"), "popular", decade, to, skip)
                else MalGenres.browse(rail.removePrefix("sub|"), null, "popular", decade, to, skip)
            }
            "service" -> Services.S.entries.firstOrNull { it.id == arg }?.let { s -> Services.rows(s).firstOrNull { it.first == rail }?.second?.invoke(page) }.orEmpty()
            "mood" -> com.lekathedon.visionlite.recs.Moods.byKey(arg)?.let { com.lekathedon.visionlite.recs.Moods.page(it, rail == "shows", page, hub) }.orEmpty()
            "scout" -> com.lekathedon.visionlite.recs.ScoutSlices.byId(arg)?.let { com.lekathedon.visionlite.recs.Scout.page(it, rail == "shows", page, hub) }.orEmpty()
            "company" -> if (page > 1) emptyList() else com.lekathedon.visionlite.browse.Companies.rows(arg).firstOrNull { it.first == rail }?.second.orEmpty()
            "abuild" -> AnimeBuild.decode(arg)?.let { b -> MalGenres.browseAll(b.genres, b.type, b.sort, skip = skip) }.orEmpty()
            else -> emptyList()
        }
    }

    /** A saved page row's titles: one row's first two pages, or the whole page's first 4 rows mixed (one from each in turn). */
    suspend fun items(remoteId: String, hub: Hub): List<MetaPreview> {
        val p = remoteId.split('~')
        if (p.size < 4) return emptyList()
        val (kind, arg, rail, media) = p
        if (rail != ALL || kind == "abuild") {
            val r = if (kind == "abuild") ALL else rail
            val first = load(kind, arg, r, media, 1, hub)
            val more = if (first.size in 1 until MAX) runCatching { load(kind, arg, r, media, 2, hub) }.getOrDefault(emptyList()) else emptyList()
            return (first + more).distinctBy { it.id }.take(MAX)
        }
        val lists = rails(kind, arg).take(4).map { r -> runCatching { load(kind, arg, r.key, media, 1, hub) }.getOrDefault(emptyList()) }
        return (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }.distinctBy { it.id }.take(MAX)
    }

    /** Saves a page row on [hub]'s Home. Returns a message for the screen. */
    fun add(kind: String, arg: String, rail: String, media: String, name: String, hub: Hub): String {
        val p = App.instance.prefs
        val cur = runCatching { JSONArray(p.importedLists.value) }.getOrDefault(JSONArray())
        val id = encode(kind, arg, rail, media)
        if ((0 until cur.length()).any { cur.optJSONObject(it)?.let { o -> o.optString("source") == "PAGE" && o.optString("remoteId") == id && o.optString("hub", hub.key) == hub.key } == true })
            return "✓ \"$name\" is already on Home"
        cur.put(JSONObject().put("source", "PAGE").put("remoteId", id).put("name", name).put("hub", hub.key))
        p.importedLists.value = cur.toString()
        return "✓ Added \"$name\" to ${hub.label} Home (move it in Settings › Home rows)"
    }

    private fun mediaOf(k: String) = when (k) { "movie" -> Wuplay.Media.MOVIES; "series" -> Wuplay.Media.SHOWS; else -> Wuplay.Media.ALL }
}

/**
 * The Anime hub's Build your own row: any of MAL's genres, themes and demographics, all on the title (Action + Isekai
 * + Shounen), shows / movies / both, popular / top rated / newest. Filled from the MAL pool on the box (no request).
 */
data class AnimeBuild(val genres: List<String> = emptyList(), val type: String? = null, val sort: String = "popular") {
    val empty get() = genres.isEmpty()
    fun press(g: String) = copy(genres = if (g in genres) genres - g else genres + g)
    val title: String get() = genres.joinToString(" · ").ifEmpty { "Anime" } + when (type) { "movie" -> " movies"; "series" -> " shows"; else -> "" } +
        when (sort) { "top" -> " · Top rated"; "latest" -> " · Newest"; else -> "" }
    fun encode() = genres.joinToString(",") + "|" + (type ?: "") + "|" + sort
    companion object {
        fun decode(s: String): AnimeBuild? = s.split('|').takeIf { it.size == 3 }?.let { (g, t, so) ->
            AnimeBuild(g.split(',').filter { it.isNotBlank() }, t.ifBlank { null }, so.ifBlank { "popular" })
        }
    }
}
