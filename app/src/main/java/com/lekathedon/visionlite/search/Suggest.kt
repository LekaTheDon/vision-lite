package com.lekathedon.visionlite.search

import com.lekathedon.visionlite.addons.MetaPreview
import com.lekathedon.visionlite.data.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Request
import java.net.URLEncoder

/**
 * Search-as-you-type: IMDb's own suggestion feed (the one behind imdb.com's search box). No key, one small request
 * per pause in typing, films and series only. The ids are IMDb tt ids, which every add-on and the title page take.
 */
object Suggest {
    @Serializable private data class Img(val imageUrl: String? = null)
    @Serializable private data class Hit(val id: String = "", val l: String = "", val qid: String? = null, val y: Int? = null, val i: Img? = null, val s: String? = null)
    @Serializable private data class Res(val d: List<Hit> = emptyList())

    private val cache = object : LinkedHashMap<String, List<Hit>>(32, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, List<Hit>>) = size > 40
    }

    /**
     * IMDb's suggestion feed for [term], once per term. [kind] "titles/" or "names/": the mixed feed gives only 8 hits
     * in all, mostly people, so "ray" never showed Ray (2004) (user 2026-11-27); each kind has its own 8.
     */
    private suspend fun hits(term: String, kind: String): List<Hit> {
        val key = term.trim().lowercase()
        if (key.length < 2) return emptyList()
        val ck = kind + key
        synchronized(cache) { cache[ck] }?.let { return it }
        val list = withContext(Dispatchers.IO) {
            runCatching {
                val q = URLEncoder.encode(key, "UTF-8").replace("+", "%20")
                val req = Request.Builder().url("${com.lekathedon.visionlite.data.Our.suggest.replace("/suggestion/", "/suggestion/$kind")}$q.json").build()
                Net.http.newCall(req).execute().use { r -> if (!r.isSuccessful) null else Net.json.decodeFromString(Res.serializer(), r.body!!.string()) }
            }.getOrNull()?.d
        } ?: return emptyList()
        synchronized(cache) { cache[ck] = list }
        return list
    }

    suspend fun of(term: String): List<MetaPreview> = hits(term, "titles/")
        .filter { it.id.startsWith("tt") && it.l.isNotBlank() }
        // The title that is exactly what you typed comes first ("ray" → Ray, 2004), IMDb's order after that.
        .sortedBy { if (it.l.equals(term.trim(), true)) 0 else 1 }
        .mapNotNull { h ->
            val type = when (h.qid) { "movie", "tvMovie" -> "movie"; "tvSeries", "tvMiniSeries" -> "series"; else -> null } ?: return@mapNotNull null
            // Small poster: IMDb resizes on the fly.
            val poster = h.i?.imageUrl?.replace("._V1_.jpg", "._V1_UX300_.jpg")
            MetaPreview(id = h.id, type = type, name = h.l, poster = poster, releaseInfo = h.y?.toString())
        }.take(8)

    /** People (actors, directors…) for the same term, like IMDb's search (user 2026-11-27); [MetaPreview.description] = "Director, Dune (2021)". */
    suspend fun people(term: String): List<MetaPreview> = hits(term, "names/")
        .filter { it.id.startsWith("nm") && it.l.isNotBlank() }
        .map { h -> MetaPreview(id = h.id, type = "person", name = h.l, poster = h.i?.imageUrl?.replace("._V1_.jpg", "._V1_UX300_.jpg"), description = h.s) }
        .take(8)
}
