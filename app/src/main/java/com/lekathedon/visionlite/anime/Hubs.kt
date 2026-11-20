package com.lekathedon.visionlite.anime

import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.addons.MetaPreview
import com.lekathedon.visionlite.data.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * The two content hubs: Films & TV and Anime. Every title belongs to exactly one, so nothing shows in both
 * (rows, Continue watching, Coming up, picks, pages, search). Key "NUVIO" stays Films & TV's key so saved settings carry over.
 */
enum class Hub(val key: String, val label: String) {
    FILMS("NUVIO", "Films & TV"), ANIME("ANIME", "Anime");
    /** Prefs key suffix: Films & TV keeps the old keys. */
    val suffix get() = if (this == FILMS) "" else "_anime"
    companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } }
}

object Hubs {
    private val p get() = App.instance.prefs
    private val _content by lazy { MutableStateFlow(Hub.of(p.contentHub.value) ?: Hub.FILMS) }
    /** The content hub shown on Home (the last of Films & TV / Anime picked; IPTV, YouTube and Music leave it as it was). */
    val content: StateFlow<Hub> get() = _content
    fun now(): Hub = _content.value

    /** The pill's section changes: a content hub also becomes Home's hub. */
    fun setSection(key: String) {
        p.appSection.value = key
        Hub.of(key)?.let { h -> if (_content.value != h) { _content.value = h; p.contentHub.value = h.key } }
    }

    /** People and streaming services are no title: they show in both hubs (their pages filter their titles). */
    fun keeps(hub: Hub, m: MetaPreview): Boolean = when (m.type) {
        "service", "person" -> true
        else -> keepsId(hub, m.id)
    }
    fun keepsId(hub: Hub, id: String): Boolean = AnimeIds.isAnime(id) == (hub == Hub.ANIME)
    fun hubOf(id: String) = if (AnimeIds.isAnime(id)) Hub.ANIME else Hub.FILMS

    fun rowOrder(h: Hub) = if (h == Hub.FILMS) p.rowOrder else p.rowOrderAnime
    fun rowsHidden(h: Hub) = if (h == Hub.FILMS) p.rowsHidden else p.rowsHiddenAnime
}

/**
 * Which titles are anime: the anime databases' cross-reference (Fribb/anime-lists: AniDB, AniList, MAL, Kitsu → IMDb
 * and TMDB ids), shipped in the app (assets/anime_ids.txt, ~6,100 IMDb + ~5,700 TMDB ids) and refreshed once a week
 * (one 1.2 MB download, kept as a 120 KB file). Anime-site ids (kitsu:, mal:, anilist:, anidb:) are anime by
 * themselves. Western animation (Avatar, Arcane) is not in those databases, so it stays in Films & TV.
 * Your own moves (hold OK › Move to …) win over the list. Memory: three sorted int arrays, about 50 KB.
 */
object AnimeIds {
    private const val TAG = "AnimeIds"
    private const val SOURCE = "https://raw.githubusercontent.com/Fribb/anime-lists/master/anime-list-mini.json"
    private const val WEEK = 7L * 86_400_000
    private class Index(val imdb: IntArray, val tv: IntArray, val movie: IntArray)
    @Volatile private var index: Index? = null
    private val file get() = File(App.instance.filesDir, "anime_ids.txt")
    private val p get() = App.instance.prefs
    private val _changed = MutableStateFlow(0)
    /** Bumps when the list or a move changes, so screens filter again. */
    val changed: StateFlow<Int> get() = _changed

    @Volatile private var moves: Map<String, Boolean>? = null
    private fun moves(): Map<String, Boolean> = moves ?: p.animeMoves.value.split('\n').filter { it.length > 1 }
        .associate { it.substring(1) to (it[0] == '+') }.also { moves = it }

    fun isAnime(id: String): Boolean {
        if (id.isEmpty()) return false
        val base = baseId(id)
        moves()[base]?.let { return it }
        if (base.startsWith("kitsu:") || base.startsWith("mal:") || base.startsWith("anilist:") || base.startsWith("anidb:")) return true
        val ix = index()
        return when {
            base.startsWith("tt") -> base.substring(2).toIntOrNull()?.let { ix.imdb.binarySearch(it) >= 0 } == true
            base.startsWith("tmdb:tv:") -> base.removePrefix("tmdb:tv:").toIntOrNull()?.let { ix.tv.binarySearch(it) >= 0 } == true
            base.startsWith("tmdb:movie:") -> base.removePrefix("tmdb:movie:").toIntOrNull()?.let { ix.movie.binarySearch(it) >= 0 } == true
            else -> false
        }
    }

    /** "tt123:1:2" → "tt123"; "kitsu:12:3" → "kitsu:12"; "tmdb:tv:5" stays. */
    private fun baseId(id: String): String = when {
        id.startsWith("tt") -> id.substringBefore(':')
        id.startsWith("tmdb:") -> id.split(':').take(3).joinToString(":")
        ':' in id -> id.split(':').take(2).joinToString(":")
        else -> id
    }

    /** Hold OK › Move to Anime / Move to Films & TV: this title (its IMDb id) goes to that hub from now on. */
    fun move(id: String, anime: Boolean) {
        val base = baseId(id)
        val m = moves().toMutableMap(); m[base] = anime
        p.animeMoves.value = m.entries.joinToString("\n") { (if (it.value) "+" else "-") + it.key }
        moves = m; _changed.value++
    }

    /** An anime-site id was turned into this IMDb id (charts, AniList lists): it is anime even before the list knows it. */
    fun learn(imdb: String) {
        if (!imdb.startsWith("tt") || isAnime(imdb) || moves().containsKey(imdb)) return
        val m = moves().toMutableMap(); m[imdb] = true
        p.animeMoves.value = m.entries.joinToString("\n") { (if (it.value) "+" else "-") + it.key }
        moves = m; _changed.value++
    }

    /** For the phone: when the list last changed (0 = the one shipped in the app), the list itself, and your moves. */
    fun stamp(): Long = if (file.length() > 10_000) file.lastModified() else 0L
    fun listText(): String = runCatching { if (file.length() > 10_000) file.readText() else null }.getOrNull()
        ?: runCatching { App.instance.assets.open("anime_ids.txt").bufferedReader().readText() }.getOrDefault("")
    fun movesText(): String = p.animeMoves.value

    private fun index(): Index = index ?: synchronized(this) { index ?: load().also { index = it } }

    /** Loads off the main thread ahead of the first screen that filters. */
    suspend fun warm() { withContext(Dispatchers.IO) { index() } }

    private fun load(): Index {
        val lines = runCatching { if (file.length() > 10_000) file.readLines() else null }.getOrNull()
            ?: runCatching { App.instance.assets.open("anime_ids.txt").bufferedReader().readLines() }.getOrDefault(emptyList())
        return parse(lines)
    }

    private fun parse(lines: List<String>): Index {
        val imdb = ArrayList<Int>(7000); val tv = ArrayList<Int>(6000); val movie = ArrayList<Int>(2000)
        lines.forEach { l ->
            when {
                l.startsWith("tt") -> l.substring(2).toIntOrNull()?.let(imdb::add)
                l.startsWith("tv:") -> l.substring(3).toIntOrNull()?.let(tv::add)
                l.startsWith("movie:") -> l.substring(6).toIntOrNull()?.let(movie::add)
            }
        }
        return Index(imdb.toIntArray().apply { sort() }, tv.toIntArray().apply { sort() }, movie.toIntArray().apply { sort() })
    }

    /** Once a week at most (asked a minute after Home shows): the databases' newest anime join the list. */
    suspend fun refreshIfDue() = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() - p.sp.getLong("anime_ids_at", 0L) < WEEK) return@withContext
        p.sp.edit().putLong("anime_ids_at", System.currentTimeMillis()).apply()
        runCatching {
            val out = ArrayList<String>(12_000)
            Net.http.newCall(Request.Builder().url(SOURCE).build()).execute().use { r ->
                if (!r.isSuccessful) return@runCatching
                JsonReader(r.body!!.charStream()).use { j -> read(j, out) }
            }
            if (out.size < 5_000) return@runCatching   // a cut-off download never replaces the list
            File(file.path + ".tmp").apply { writeText(out.joinToString("\n")) }.renameTo(file)
            index = parse(out); _changed.value++
            Log.i(TAG, "list refreshed: ${out.size} ids")
            Unit
        }.onFailure { Log.w(TAG, "refresh: ${it.message}") }
    }

    private fun read(j: JsonReader, out: MutableList<String>) {
        j.beginArray()
        while (j.hasNext()) {
            j.beginObject()
            while (j.hasNext()) {
                when (j.nextName()) {
                    "imdb_id" -> if (j.peek() == JsonToken.BEGIN_ARRAY) {
                        j.beginArray(); while (j.hasNext()) { if (j.peek() == JsonToken.STRING) j.nextString().takeIf { it.startsWith("tt") }?.let(out::add) else j.skipValue() }; j.endArray()
                    } else if (j.peek() == JsonToken.STRING) j.nextString().takeIf { it.startsWith("tt") }?.let(out::add) else j.skipValue()
                    "themoviedb_id" -> if (j.peek() == JsonToken.BEGIN_OBJECT) {
                        j.beginObject()
                        while (j.hasNext()) {
                            val k = j.nextName()
                            if ((k == "tv" || k == "movie") && j.peek() == JsonToken.NUMBER) out += "$k:${j.nextLong()}" else j.skipValue()
                        }
                        j.endObject()
                    } else j.skipValue()
                    else -> j.skipValue()
                }
            }
            j.endObject()
        }
        j.endArray()
    }
}
