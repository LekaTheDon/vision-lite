package com.lekathedon.visionlite.player

import android.content.Context
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.home.Tmdb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Audio language "Original" (Vision's AudioLanguageOption.ORIGINAL): the title's original language from TMDB
 * (original_language, by its IMDb id). One /find request per title, ever: kept on the box. Looked up when the
 * links page opens, so the player already knows it when the film starts. No TMDB key = unknown (file's default).
 */
object OriginalLang {
    const val ORIGINAL = "original"
    private val sp by lazy { App.instance.getSharedPreferences("orig_lang", Context.MODE_PRIVATE) }

    /** The cached code ("en", "ja"…), "" = TMDB has none, null = not looked up yet. */
    fun cached(imdb: String?): String? = imdb?.takeIf { it.startsWith("tt") }?.let { sp.getString(it, null) }

    suspend fun resolve(imdb: String?, type: String): String? {
        val id = imdb?.substringBefore(':')?.takeIf { it.startsWith("tt") } ?: return null
        cached(id)?.let { return it.ifBlank { null } }
        val o = withContext(Dispatchers.IO) { Tmdb.get("find/$id?external_source=imdb_id") } ?: return null
        val list = if (type == "movie") listOf("movie_results", "tv_results", "tv_episode_results") else listOf("tv_results", "tv_episode_results", "movie_results")
        val code = list.firstNotNullOfOrNull { k -> o.optJSONArray(k)?.optJSONObject(0)?.optString("original_language")?.takeIf { it.isNotBlank() } }
            ?.let { normalize(it) }
        sp.edit().putString(id, code ?: "").apply()
        return code
    }

    /** TMDB uses "cn" for Cantonese (not ISO 639-1); files tag it zh / chi / yue. */
    private fun normalize(c: String): String = when (c.lowercase()) { "cn" -> "zh"; "xx" -> ""; else -> c.lowercase() }.ifBlank { "" }

    /** Settings' audio choices with "original" swapped for this title's language (dropped while unknown). */
    fun audioLangs(first: String, second: String, imdb: String?): List<String> {
        val orig = cached(imdb?.substringBefore(':'))?.ifBlank { null }
        // Preferred dub (Settings › Audio) goes before everything: a file with that track plays it.
        val dub = runCatching { com.lekathedon.visionlite.App.instance.prefs.dubLang.value }.getOrDefault("").ifEmpty { null }
        return (listOf(dub) + listOf(first, second).map { if (it == ORIGINAL) orig else it.ifEmpty { null } }).filterNotNull().distinct()
    }

    /** Two- and three-letter forms (mpv matches the tag as written in the file: "ja" or "jpn"/"jpn"). */
    fun withIso3(codes: List<String>): List<String> = codes.flatMap { c ->
        val l = runCatching { java.util.Locale(c) }.getOrNull()
        listOfNotNull(c, runCatching { l?.isO3Language }.getOrNull()?.takeIf { it.isNotBlank() },
            // ISO 639-2/B forms some files use.
            when (c) { "fr" -> "fre"; "de" -> "ger"; "zh" -> "chi"; "nl" -> "dut"; "cs" -> "cze"; "el" -> "gre"; "fa" -> "per"; "ro" -> "rum"; "sk" -> "slo"; "sq" -> "alb"; "hy" -> "arm"; "ka" -> "geo"; "is" -> "ice"; "mk" -> "mac"; "ms" -> "may"; "my" -> "bur"; else -> null })
    }.distinct()
}
