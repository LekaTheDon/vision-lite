package com.lekathedon.visionlite.trakt

import com.lekathedon.visionlite.BuildConfig
import com.lekathedon.visionlite.data.Net
import com.lekathedon.visionlite.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

@Serializable data class Ids(val trakt: Long? = null, val imdb: String? = null, val tmdb: Long? = null)
@Serializable data class TMovie(val title: String = "", val year: Int? = null, val ids: Ids = Ids())
@Serializable data class TShow(val title: String = "", val year: Int? = null, val ids: Ids = Ids())
@Serializable data class TEpisode(val season: Int = 0, val number: Int = 0, val title: String? = null, val ids: Ids = Ids())
@Serializable data class Playback(val progress: Float = 0f, val type: String = "", val movie: TMovie? = null, val show: TShow? = null, val episode: TEpisode? = null,
                                  @SerialName("paused_at") val pausedAt: String = "")
@Serializable data class Watched(val show: TShow, @SerialName("last_watched_at") val lastWatchedAt: String = "")
@Serializable data class Progress(@SerialName("next_episode") val next: TEpisode? = null, val aired: Int = 0, val completed: Int = 0)
@Serializable data class ListItem(val type: String = "", val movie: TMovie? = null, val show: TShow? = null)
@Serializable data class DeviceCode(@SerialName("device_code") val deviceCode: String, @SerialName("user_code") val userCode: String,
                                    @SerialName("verification_url") val url: String, @SerialName("expires_in") val expiresIn: Int, val interval: Int)
@Serializable data class Tokens(@SerialName("access_token") val access: String, @SerialName("refresh_token") val refresh: String,
                                @SerialName("created_at") val createdAt: Long, @SerialName("expires_in") val expiresIn: Long)

/** One "continue" entry: a paused title (with %), or the next episode of a show being watched. */
data class Resume(val type: String, val imdb: String, val name: String, val season: Int?, val episode: Int?, val progress: Float, val at: String) {
    /** Stremio id: "tt…" for movies, "tt…:s:e" for episodes. */
    val streamId get() = if (season != null && episode != null) "$imdb:$season:$episode" else imdb
    val label get() = if (season != null) "$name · S${season}E$episode" else name
}

/**
 * Trakt: device-code login, Continue Watching, watchlist, scrobble. Nothing is called until the user connects;
 * Continue Watching is asked when Home opens (one playback call + progress of the 8 latest shows, in parallel).
 */
class Trakt(private val prefs: Prefs) {
    private val tokens = MutableStateFlow(runCatching { prefs.sp.getString("trakt_tokens", null)?.let { Net.json.decodeFromString<Tokens>(it) } }.getOrNull())
    val connected: StateFlow<Tokens?> get() = tokens
    val configured get() = BuildConfig.TRAKT_ID.isNotBlank()
    private val jsonType = "application/json".toMediaType()

    private fun save(t: Tokens?) {
        tokens.value = t
        prefs.sp.edit().apply { if (t == null) remove("trakt_tokens") else putString("trakt_tokens", Net.json.encodeToString(Tokens.serializer(), t)) }.apply()
    }

    private suspend fun call(method: String, path: String, body: JsonObject? = null, auth: Boolean = true): Pair<Int, String> = withContext(Dispatchers.IO) {
        if (auth) refreshIfNeeded()
        val b = Request.Builder().url("https://api.trakt.tv/$path")
            .header("Content-Type", "application/json").header("trakt-api-version", "2")
            .header("trakt-api-key", BuildConfig.TRAKT_ID).header("User-Agent", "VisionLite")
        tokens.value?.takeIf { auth }?.let { b.header("Authorization", "Bearer ${it.access}") }
        b.method(method, body?.toString()?.toRequestBody(jsonType) ?: if (method == "POST") "{}".toRequestBody(jsonType) else null)
        Net.http.newCall(b.build()).execute().use { r -> r.code to (r.body?.string() ?: "") }
    }

    private suspend inline fun <reified T> get(path: String): T {
        val (code, text) = call("GET", path)
        if (code == 401) save(null)
        if (code !in 200..299) throw IOException("Trakt $code")
        return Net.json.decodeFromString(text)
    }

    // --- login ---

    suspend fun startLogin(): DeviceCode {
        val (code, text) = call("POST", "oauth/device/code", buildJsonObject { put("client_id", BuildConfig.TRAKT_ID) }, auth = false)
        if (code != 200) throw IOException("Trakt $code")
        return Net.json.decodeFromString(text)
    }

    /** Polls until the code is entered on the phone. True = connected. */
    suspend fun waitForLogin(dc: DeviceCode): Boolean {
        var interval = dc.interval.coerceAtLeast(2) * 1000L
        val until = System.currentTimeMillis() + dc.expiresIn * 1000L
        while (System.currentTimeMillis() < until) {
            delay(interval)
            val (code, text) = call("POST", "oauth/device/token", buildJsonObject {
                put("code", dc.deviceCode); put("client_id", BuildConfig.TRAKT_ID); put("client_secret", BuildConfig.TRAKT_SECRET)
            }, auth = false)
            when (code) {
                200 -> { save(Net.json.decodeFromString(text)); return true }
                400 -> {}
                429 -> interval += 1000
                else -> return false
            }
        }
        return false
    }

    fun disconnect() = save(null)

    private suspend fun refreshIfNeeded() {
        val t = tokens.value ?: return
        if (System.currentTimeMillis() / 1000 < t.createdAt + t.expiresIn - 86_400) return
        val (code, text) = call("POST", "oauth/token", buildJsonObject {
            put("refresh_token", t.refresh); put("client_id", BuildConfig.TRAKT_ID); put("client_secret", BuildConfig.TRAKT_SECRET)
            put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob"); put("grant_type", "refresh_token")
        }, auth = false)
        if (code == 200) save(Net.json.decodeFromString(text)) else if (code in 400..499) save(null)
    }

    // --- data ---

    /** Paused titles first (newest), then the next episode of recently watched shows not already listed. */
    suspend fun continueWatching(): List<Resume> = coroutineScope {
        if (tokens.value == null) return@coroutineScope emptyList()
        val paused = async { runCatching { get<List<Playback>>("sync/playback?limit=30") }.getOrDefault(emptyList()) }
        val shows = runCatching { get<List<Watched>>("sync/watched/shows?extended=noseasons") }.getOrDefault(emptyList())
            .sortedByDescending { it.lastWatchedAt }.take(8)
        val next = shows.map { w ->
            async {
                val imdb = w.show.ids.imdb ?: return@async null
                val p = runCatching { get<Progress>("shows/${w.show.ids.trakt}/progress/watched?hidden=false&specials=false") }.getOrNull() ?: return@async null
                p.next?.let { Resume("series", imdb, w.show.title, it.season, it.number, 0f, w.lastWatchedAt) }
            }
        }.awaitAll().filterNotNull()
        val p = paused.await().filter { it.progress in 2f..92f }.mapNotNull { pb ->
            when {
                pb.movie != null -> pb.movie.ids.imdb?.let { Resume("movie", it, pb.movie.title, null, null, pb.progress, pb.pausedAt) }
                pb.show != null && pb.episode != null -> pb.show.ids.imdb?.let { Resume("series", it, pb.show.title, pb.episode.season, pb.episode.number, pb.progress, pb.pausedAt) }
                else -> null
            }
        }
        val seen = p.mapTo(HashSet()) { it.imdb }
        (p + next.filter { it.imdb !in seen }).sortedByDescending { it.at }
    }

    suspend fun watchlist(): List<ListItem> = if (tokens.value == null) emptyList()
        else runCatching { get<List<ListItem>>("sync/watchlist?sort_by=added&sort_how=desc") }.getOrDefault(emptyList())

    /** Saved % for this title (to resume), or 0. */
    suspend fun savedProgress(imdb: String, season: Int?, episode: Int?): Float =
        runCatching { get<List<Playback>>("sync/playback?limit=50") }.getOrDefault(emptyList()).firstOrNull {
            if (season == null) it.movie?.ids?.imdb == imdb else it.show?.ids?.imdb == imdb && it.episode?.season == season && it.episode.number == episode
        }?.progress ?: 0f

    /** action = start | pause | stop. Trakt marks watched at stop >= 80%. */
    suspend fun scrobble(action: String, imdb: String, season: Int?, episode: Int?, progress: Float) {
        if (tokens.value == null) return
        val body = buildJsonObject {
            if (season == null) putJsonObject("movie") { putJsonObject("ids") { put("imdb", imdb) } }
            else {
                putJsonObject("show") { putJsonObject("ids") { put("imdb", imdb) } }
                putJsonObject("episode") { put("season", season); put("number", episode ?: 0) }
            }
            put("progress", progress.coerceIn(0f, 100f))
        }
        runCatching { call("POST", "scrobble/$action", body) }
    }
}
