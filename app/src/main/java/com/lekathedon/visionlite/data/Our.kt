package com.lekathedon.visionlite.data

import com.lekathedon.visionlite.BuildConfig
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.net.URLEncoder

/**
 * Our own server (vision-meta), the app's only door to outside metadata: titles, charts and lists, search suggestions,
 * catalogs, pictures and our score. The app never calls IMDb, Cinemeta or metahub itself, so when a source changes,
 * closes or is replaced, only the server changes: every installed app keeps working without an update.
 */
object Our {
    val base: String = BuildConfig.META_URL.trimEnd('/')
    /** No server in the build yet: the same answers straight from the sources, so nothing breaks before it's deployed. */
    val on = base.isNotBlank()
    private val host: String? = runCatching { java.net.URI(base).host }.getOrNull()
    /** The host the network checks time (ours once deployed). */
    val metaHost: String get() = host ?: "v3-cinemeta.strem.io"

    /** GraphQL titles / charts / lists (POST {query, variables}). */
    val imdb = if (on) "$base/v1/imdb" else "https://api.graphql.imdb.com/"
    /** Search suggestions: [suggest] + the words + ".json", e.g. "${Our.suggest}matrix.json". */
    val suggest = if (on) "$base/v1/suggest/" else "https://v3.sg.media-imdb.com/suggestion/x/"
    /** The catalog add-on (Stremio protocol: /manifest.json, /catalog/…, /meta/…). */
    val cinemeta = if (on) "$base/v1/cinemeta" else "https://v3-cinemeta.strem.io"

    /** A title's pictures by IMDb id; the server answers with the best one it has (TMDB first). */
    fun poster(id: String, w: String = "medium") = pic("poster", id, w)
    fun backdrop(id: String, w: String = "large") = pic("background", id, w)
    fun logo(id: String, w: String = "medium") = pic("logo", id, w)
    private fun pic(kind: String, id: String, w: String) =
        if (on) "$base/v1/img/$kind/$id?w=$w" else "https://images.metahub.space/$kind/$w/$id/img"

    /** Our score: the average of the relevant sources (films & TV: IMDb, TMDB, Trakt…; anime: AniList, MAL, Kitsu). Null = no server. */
    fun score(type: String, id: String) = if (on) "$base/v1/score/$type/${URLEncoder.encode(id, "UTF-8")}" else null

    fun mine(u: HttpUrl) = host != null && u.host == host

    /** Net's client: requests to our server carry the app's key. */
    object Key : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val r = chain.request()
            if (!mine(r.url) || BuildConfig.META_KEY.isBlank()) return chain.proceed(r)
            return chain.proceed(r.newBuilder().header("x-app-key", BuildConfig.META_KEY).build())
        }
    }
}
