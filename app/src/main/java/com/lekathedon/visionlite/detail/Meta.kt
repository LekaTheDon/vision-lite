package com.lekathedon.visionlite.detail

import android.util.Log
import com.lekathedon.visionlite.BuildConfig
import com.lekathedon.visionlite.data.Net
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lite's own metadata server (~/src/vision-meta, a Cloudflare Worker): cast and Sequel/Prequel for an IMDb id from
 * TMDB + Wikidata, so the details page keeps them if IMDb's private API ever changes or shuts us out.
 * Asked first for cast and sequels; IMDb's answer fills what it lacks (ratings, votes, anyone it has no IMDb id for).
 * Dormant: no META_URL in the build = no calls. Cost: one small GET per details page (~5-15 KB), kept with the title (6 h);
 * the server keeps each title 30 days, so TMDB/Wikidata are asked once a month per title at most.
 */
object Meta {
    private const val TAG = "LiteMeta"
    fun on() = BuildConfig.META_URL.isNotBlank()

    class Answer(val name: String, val type: String, val year: Int?, val endYear: Int?, val poster: String?, val plot: String?,
                 val genres: List<String>, val runtimeMin: Int?, val seasons: List<Int>,
                 val cast: List<Imdb.Person>, val directors: List<Imdb.Person>, val creators: List<Imdb.Person>,
                 val writers: List<Imdb.Person>, val sequels: List<Imdb.Ref>)

    /** Blocking; call off the main thread. Null = off, unknown or not reachable. */
    fun title(id: String): Answer? {
        if (!on()) return null
        return runCatching {
            val req = Request.Builder().url(BuildConfig.META_URL.trimEnd('/') + "/v1/title/$id")
                .apply { if (BuildConfig.META_KEY.isNotBlank()) header("x-app-key", BuildConfig.META_KEY) }.build()
            Net.http.newCall(req).execute().use { r -> if (!r.isSuccessful) null else parse(JSONObject(r.body?.string().orEmpty())) }
        }.onFailure { Log.w(TAG, "$id: ${it.message}") }.getOrNull()
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONObject.str(k: String) = optString(k).takeIf { has(k) && !isNull(k) && it.isNotBlank() }
    private fun JSONObject.int(k: String) = if (has(k) && !isNull(k)) optInt(k).takeIf { it > 0 } else null

    /** People without an IMDb id are left out: the person page opens by it. */
    private fun people(a: JSONArray?) = a.objects().mapNotNull { p ->
        val id = p.str("id")?.takeIf { it.startsWith("nm") } ?: return@mapNotNull null
        Imdb.Person(id, p.str("name") ?: return@mapNotNull null, p.str("photo"), p.str("role"))
    }

    private fun parse(o: JSONObject): Answer? {
        val name = o.str("name") ?: return null
        return Answer(name, o.str("type") ?: "movie", o.int("year"), o.int("endYear"), o.str("poster"), o.str("plot"),
            o.optJSONArray("genres")?.let { g -> (0 until g.length()).mapNotNull { g.optString(it).ifBlank { null } } }.orEmpty(),
            o.int("runtime"), o.optJSONArray("seasons")?.let { s -> (0 until s.length()).map { s.optInt(it) }.filter { it > 0 } }.orEmpty(),
            people(o.optJSONArray("cast")), people(o.optJSONArray("directors")), people(o.optJSONArray("creators")),
            people(o.optJSONArray("writers")),
            o.optJSONArray("sequels").objects().mapNotNull { s ->
                val tt = s.str("imdb")?.takeIf { it.startsWith("tt") } ?: return@mapNotNull null
                val link = if (s.str("link") == "follows") Imdb.Link.PREQUEL else Imdb.Link.SEQUEL
                Imdb.Ref(tt, s.str("type") ?: "movie", s.str("title") ?: return@mapNotNull null, s.int("year"), null, null, s.str("poster"), link)
            })
    }

    /** IMDb's title with the server's cast and sequels first; either side alone still makes a page. */
    fun merge(id: String, imdb: Imdb.Title?, srv: Answer?): Imdb.Title? {
        if (srv == null) return imdb
        if (imdb == null) return Imdb.Title(id, srv.name, srv.type, srv.year, srv.endYear, null, null, null, null, srv.runtimeMin,
            srv.plot, srv.genres, srv.poster, srv.seasons, srv.directors, srv.creators, srv.cast, emptyList(),
            srv.sequels, emptyList(), emptyList(), srv.writers)
        // Sequels: the server's, with IMDb's rating/votes/poster where IMDb knows the same title; then IMDb-only ones.
        val known = imdb.sequels.associateBy { it.imdb }
        val sequels = (srv.sequels.map { s -> known[s.imdb]?.let { k -> s.copy(rating = k.rating, votes = k.votes, poster = k.poster ?: s.poster, type = k.type) } ?: s } +
            imdb.sequels.filter { k -> srv.sequels.none { it.imdb == k.imdb } })
            .sortedWith(compareBy<Imdb.Ref> { it.link?.ordinal ?: 0 }.thenBy { it.year ?: Int.MAX_VALUE })
        fun pick(a: List<Imdb.Person>, b: List<Imdb.Person>) = if (a.size >= minOf(b.size, 5)) a else b
        return imdb.copy(cast = pick(srv.cast, imdb.cast), directors = pick(srv.directors, imdb.directors),
            creators = pick(srv.creators, imdb.creators), writers = pick(srv.writers, imdb.writers), sequels = sequels)
    }
}
