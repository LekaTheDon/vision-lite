package com.lekathedon.visionlite.detail

import com.lekathedon.visionlite.data.Net
import com.lekathedon.visionlite.home.Tmdb
import com.lekathedon.visionlite.recs.AiPicks
import com.lekathedon.visionlite.ui.Lang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Content in the app language (user 2026-11-26: "UI plus content in the same language"): a title's plot and its
 * episodes' names and plots. TMDB's own translation first (with your TMDB key); what TMDB doesn't have in that language
 * is translated by Gemini (with your Gemini key), in one request per page / season. English = nothing runs.
 * Kept on disk (files/localized/<lang>/), so each title is fetched or translated once.
 * Cost: on opening a details page in another language, 2 TMDB calls (+1 per season looked at); Gemini only for gaps.
 */
object Localized {
    data class Title(val name: String?, val plot: String?)
    data class Episode(val title: String?, val plot: String?)

    private fun lang() = Lang.chosen().takeIf { it != "en" }
    private fun dir(l: String) = java.io.File(com.lekathedon.visionlite.App.instance.filesDir, "localized/$l").apply { mkdirs() }
    private fun readJson(f: java.io.File) = runCatching { JSONObject(f.readText()) }.getOrNull()

    private val ids = com.lekathedon.visionlite.data.Lru<String, Pair<String, Int>>(500)
    private fun tmdbId(imdb: String, series: Boolean): Pair<String, Int>? = ids[imdb] ?: run {
        val o = Tmdb.get("find/$imdb?external_source=imdb_id") ?: return null
        val tv = o.optJSONArray("tv_results")?.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }
        val mv = o.optJSONArray("movie_results")?.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }
        val r = if (series) tv?.let { "tv" to it } ?: mv?.let { "movie" to it } else mv?.let { "movie" to it } ?: tv?.let { "tv" to it }
        r?.also { ids[imdb] = it }
    }

    /** The title's name and plot in the app language; [english] = the plot shown now (translated when TMDB has none). */
    suspend fun title(imdb: String, series: Boolean, english: String?): Title? = withContext(Dispatchers.IO) {
        val l = lang() ?: return@withContext null
        val f = java.io.File(dir(l), "$imdb.json")
        readJson(f)?.let { return@withContext Title(it.optString("name").ifBlank { null }, it.optString("plot").ifBlank { null }) }
        var name: String? = null; var plot: String? = null
        tmdbId(imdb, series)?.let { (kind, id) ->
            Tmdb.get("$kind/$id?language=$l")?.let { o ->
                name = o.optString(if (kind == "tv") "name" else "title").ifBlank { null }
                plot = o.optString("overview").ifBlank { null }
            }
        }
        if (plot == null && !english.isNullOrBlank()) plot = translate(listOf(english), l)?.firstOrNull()
        if (name == null && plot == null) return@withContext null
        runCatching { f.writeText(JSONObject().put("name", name ?: "").put("plot", plot ?: "").toString()) }
        Title(name, plot)
    }

    /** Episode names and plots of one season in the app language, by episode number. */
    suspend fun season(imdb: String, season: Int, english: Map<Int, Pair<String?, String?>>): Map<Int, Episode> = withContext(Dispatchers.IO) {
        val l = lang() ?: return@withContext emptyMap()
        val f = java.io.File(dir(l), "$imdb-s$season.json")
        readJson(f)?.let { o -> return@withContext o.keys().asSequence().associate { k -> val e = o.getJSONObject(k)
            k.toInt() to Episode(e.optString("t").ifBlank { null }, e.optString("p").ifBlank { null }) } }
        val out = HashMap<Int, Episode>()
        tmdbId(imdb, true)?.takeIf { it.first == "tv" }?.let { (_, id) ->
            Tmdb.get("tv/$id/season/$season?language=$l")?.optJSONArray("episodes")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    val n = e.optInt("episode_number").takeIf { it > 0 } ?: continue
                    // TMDB fills a missing translation with "Episode 5"-style names: those don't count.
                    val t = e.optString("name").ifBlank { null }?.takeIf { !Regex("^\\D*\\d+\\D*$").matches(it) }
                    out[n] = Episode(t, e.optString("overview").ifBlank { null })
                }
            }
        }
        // Gaps (no TMDB key, or not translated there): Gemini, one request for the season.
        val gaps = english.filter { (n, p) -> (out[n]?.plot == null && !p.second.isNullOrBlank()) || (out[n]?.title == null && !p.first.isNullOrBlank()) }
        if (gaps.isNotEmpty()) {
            val keys = gaps.keys.sorted()
            val src = keys.flatMap { n -> listOf(gaps[n]!!.first.orEmpty(), gaps[n]!!.second.orEmpty()) }
            translate(src, l)?.let { tr ->
                keys.forEachIndexed { i, n ->
                    val old = out[n]
                    out[n] = Episode(old?.title ?: tr.getOrNull(2 * i)?.ifBlank { null }, old?.plot ?: tr.getOrNull(2 * i + 1)?.ifBlank { null })
                }
            }
        }
        if (out.isNotEmpty()) runCatching {
            f.writeText(JSONObject().apply { out.forEach { (n, e) -> put("$n", JSONObject().put("t", e.title ?: "").put("p", e.plot ?: "")) } }.toString())
        }
        out
    }

    /** Gemini translation of [texts] into [lang], same order; null without a Gemini key or on failure. */
    private fun translate(texts: List<String>, lang: String): List<String>? {
        val ai = AiPicks.Ai.GEMINI
        if (!ai.on) return null
        val name = java.util.Locale.forLanguageTag(lang).getDisplayLanguage(java.util.Locale.ENGLISH)
        val prompt = "Translate each string of this JSON array into $name. These are TV/film titles and plot summaries: natural, " +
            "fluent $name, keep names of people and places as commonly written in $name. Empty strings stay empty. Answer with a JSON array " +
            "of the same length and order, nothing else.\n" + JSONArray(texts).toString()
        val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/${ai.model}:generateContent").header("x-goog-api-key", ai.key)
            .post(JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.2)).toString()
                .toRequestBody("application/json".toMediaType())).build()
        return runCatching {
            Net.http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                val text = JSONObject(r.body!!.string()).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                    ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text") ?: return null
                val arr = JSONArray(text)
                (0 until arr.length()).map { arr.optString(it) }.takeIf { it.size == texts.size }
            }
        }.getOrNull()
    }
}
