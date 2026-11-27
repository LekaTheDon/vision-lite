package com.lekathedon.visionlite.data

import com.lekathedon.visionlite.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
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

    /**
     * GraphQL titles / charts / lists (POST {query, variables}). Asked from the device first (IMDb refuses Cloudflare's
     * servers, 2026-11-27); when IMDb fails, [Route] asks our server, which answers the same from TMDB.
     */
    const val imdb = "https://api.graphql.imdb.com/"
    /** Search suggestions: [suggest] + the words + ".json"; same rule as [imdb]. */
    const val suggest = "https://v3.sg.media-imdb.com/suggestion/x/"
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

    /**
     * "Use our server's key" (2026-11-27: the app ships with no keys; users bring only debrid + Trakt / Simkl). Code that
     * needs a TMDB / TVDB / MDBList / AI key gets this when the user set none and the server is on; [Route] strips it and
     * sends the call to our server, which adds the real key.
     */
    const val KEY = "~"
    /** The user's own key, else [KEY] when our server is on, else null (that feature stays off). */
    fun keyOr(own: String?): String? = own?.trim()?.takeIf { it.isNotBlank() } ?: if (on) KEY else null

    /**
     * Services that need a key our server holds (its /v1/x route). Free, keyless sources (AniList, Jikan, Kitsu,
     * TVmaze, ani.zip, IntroDB, AniSkip, SeaDex) stay direct from the device: some ban Cloudflare's servers.
     */
    private val ROUTED = setOf("api.themoviedb.org", "api4.thetvdb.com", "api.mdblist.com", "api.myanimelist.net",
    ) // AI is never routed: only the user's own Gemini key, sent straight to Google.
    private val CRED_PARAMS = listOf("api_key", "apikey", "key")
    private fun blank(v: String?) = v == null || v.isBlank() || v == KEY || v == "server"

    /**
     * Net's client: a call to one of [ROUTED] that carries no real credential of the user's (none, blank or [KEY]) goes
     * to our server instead, unchanged otherwise. Calls with the user's own key or sign-in go straight to the service.
     * Off (no server in the build) = nothing changes.
     */
    object Route : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val r = chain.request()
            if (on && (r.url.host == "api.graphql.imdb.com" || r.url.host == "v3.sg.media-imdb.com")) return imdbOrOurs(chain, r)
            if (!on || r.url.host !in ROUTED) return chain.proceed(r)
            val auth = r.header("Authorization")?.removePrefix("Bearer")?.trim()
            val goog = r.header("x-goog-api-key")
            val malId = r.header("X-MAL-CLIENT-ID")
            if (!blank(auth) || !blank(goog) || CRED_PARAMS.any { !blank(r.url.queryParameter(it)) && r.url.queryParameter(it) != null }) return chain.proceed(r)
            if (r.url.host == "api.myanimelist.net" && !blank(malId) && malId != com.lekathedon.visionlite.BuildConfig.MAL_CLIENT_ID) return chain.proceed(r)
            val to = (base + "/v1/x/" + r.url.host + r.url.encodedPath).toHttpUrl().newBuilder().apply {
                for (i in 0 until r.url.querySize) {
                    val k = r.url.queryParameterName(i)
                    if (k in CRED_PARAMS) continue
                    addQueryParameter(k, r.url.queryParameterValue(i))
                }
            }.build()
            val b = r.newBuilder().url(to).removeHeader("Authorization").removeHeader("x-goog-api-key").removeHeader("X-MAL-CLIENT-ID")
            if (BuildConfig.META_KEY.isNotBlank()) b.header("x-app-key", BuildConfig.META_KEY)
            return chain.proceed(b.build())
        }
    }

    /** IMDb from the device; on an error, a block or an answer with no data, the same request to our server. */
    private fun imdbOrOurs(chain: Interceptor.Chain, r: okhttp3.Request): Response {
        val first = runCatching { chain.proceed(r) }.getOrNull()
        if (first != null && first.isSuccessful) {
            val ok = r.url.host != "api.graphql.imdb.com" || runCatching {
                val o = org.json.JSONObject(first.peekBody(4L shl 20).string()).optJSONObject("data")
                o != null && o.keys().asSequence().any { !o.isNull(it) }
            }.getOrDefault(false)
            if (ok) return first
        }
        first?.close()
        val to = if (r.url.host == "api.graphql.imdb.com") "$base/v1/imdb"
            else "$base/v1/suggest/" + r.url.pathSegments.lastOrNull().orEmpty().removeSuffix(".json")
        val b = r.newBuilder().url(to)
        if (BuildConfig.META_KEY.isNotBlank()) b.header("x-app-key", BuildConfig.META_KEY)
        return chain.proceed(b.build())
    }

    /** Net's client: requests to our server carry the app's key. */
    object Key : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val r = chain.request()
            if (!mine(r.url) || BuildConfig.META_KEY.isBlank()) return chain.proceed(r)
            return chain.proceed(r.newBuilder().header("x-app-key", BuildConfig.META_KEY).build())
        }
    }
}
