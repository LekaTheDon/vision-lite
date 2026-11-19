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

@Serializable data class Ids(val trakt: Long? = null, val imdb: String? = null, val tmdb: Long? = null, val tvdb: Long? = null)
@Serializable data class TMovie(val title: String = "", val year: Int? = null, val ids: Ids = Ids())
@Serializable data class TShow(val title: String = "", val year: Int? = null, val ids: Ids = Ids())
@Serializable data class TEpisode(val season: Int = 0, val number: Int = 0, val title: String? = null, val ids: Ids = Ids())
@Serializable data class Playback(val progress: Float = 0f, val type: String = "", val movie: TMovie? = null, val show: TShow? = null, val episode: TEpisode? = null,
                                  @SerialName("paused_at") val pausedAt: String = "")
@Serializable data class Watched(val show: TShow, @SerialName("last_watched_at") val lastWatchedAt: String = "", val plays: Int = 0)
@Serializable data class WatchedMovie(val movie: TMovie, @SerialName("last_watched_at") val lastWatchedAt: String = "", val plays: Int = 0)
@Serializable data class Rated(val type: String = "", val rating: Int = 0, @SerialName("rated_at") val ratedAt: String = "", val movie: TMovie? = null, val show: TShow? = null)
@Serializable data class CalShow(@SerialName("first_aired") val firstAired: String = "", val episode: TEpisode = TEpisode(), val show: TShow = TShow())
@Serializable data class CalMovie(val released: String = "", val movie: TMovie = TMovie())
@Serializable data class Related(val title: String = "", val year: Int? = null, val ids: Ids = Ids())

/** What the recommendation engine learns from: everything watched, rated and paused. */
class TraktTaste(val movies: List<WatchedMovie>, val shows: List<Watched>, val ratings: List<Rated>, val paused: List<Playback>)
@Serializable data class Progress(@SerialName("next_episode") val next: TEpisode? = null, val aired: Int = 0, val completed: Int = 0)
@Serializable data class HistoryItem(val id: Long = 0, @SerialName("watched_at") val watchedAt: String = "", val type: String = "",
                                     val movie: TMovie? = null, val show: TShow? = null, val episode: TEpisode? = null)
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

    /** Signed in and, in Integrations, driving Tracking or switched on as a helper. */
    private fun on() = tokens.value != null && com.lekathedon.visionlite.integrations.Integrations.active("trakt")
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
                put("code", dc.deviceCode); put("client_id", BuildConfig.TRAKT_ID); if (BuildConfig.TRAKT_SECRET.isNotBlank()) put("client_secret", BuildConfig.TRAKT_SECRET)
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
        // Refreshed in the last quarter of its life (Trakt's tokens can be as short as a day).
        if (System.currentTimeMillis() / 1000 < t.createdAt + t.expiresIn - minOf(86_400L, t.expiresIn / 4L)) return
        val (code, text) = call("POST", "oauth/token", buildJsonObject {
            put("refresh_token", t.refresh); put("client_id", BuildConfig.TRAKT_ID); if (BuildConfig.TRAKT_SECRET.isNotBlank()) put("client_secret", BuildConfig.TRAKT_SECRET)
            put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob"); put("grant_type", "refresh_token")
        }, auth = false)
        if (code == 200) save(Net.json.decodeFromString(text)) else if (code in 400..499) save(null)
    }

    // --- data ---

    /** Paused titles first (newest), then the next episode of recently watched shows not already listed. */
    suspend fun continueWatching(): List<Resume> = coroutineScope {
        if (!on()) return@coroutineScope emptyList()
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
        // One card per title (like Vision/Nuvio): a show's newest entry wins, whether a paused episode or its next one.
        (p + next).sortedByDescending { it.at }.distinctBy { it.imdb }
    }

    /** What you watched last (the clock's Activity tab): one request. */
    suspend fun history(limit: Int = 40): List<HistoryItem> = if (!on()) emptyList()
        else runCatching { get<List<HistoryItem>>("sync/history?limit=$limit") }.getOrDefault(emptyList())

    suspend fun watchlist(): List<ListItem> = if (!on()) emptyList()
        else runCatching { get<List<ListItem>>("sync/watchlist?sort_by=added&sort_how=desc") }.getOrDefault(emptyList())

    /** Saved % for this title (to resume), or 0. */
    suspend fun savedProgress(imdb: String, season: Int?, episode: Int?): Float = if (!on()) 0f else
        runCatching { get<List<Playback>>("sync/playback?limit=50") }.getOrDefault(emptyList()).firstOrNull {
            if (season == null) it.movie?.ids?.imdb == imdb else it.show?.ids?.imdb == imdb && it.episode?.season == season && it.episode.number == episode
        }?.progress ?: 0f

    /** Watched movies and shows, ratings and paused titles (4 calls, in parallel). Null when Trakt is off. */
    suspend fun taste(): TraktTaste? = if (!on()) null else coroutineScope {
        val m = async { runCatching { get<List<WatchedMovie>>("sync/watched/movies") }.getOrNull() }
        val s = async { runCatching { get<List<Watched>>("sync/watched/shows?extended=noseasons") }.getOrNull() }
        val r = async { runCatching { get<List<Rated>>("sync/ratings/all") }.getOrDefault(emptyList()) }
        val p = async { runCatching { get<List<Playback>>("sync/playback?limit=50") }.getOrDefault(emptyList()) }
        val movies = m.await(); val shows = s.await()
        if (movies == null && shows == null) null else TraktTaste(movies.orEmpty(), shows.orEmpty(), r.await(), p.await())
    }

    /** Trakt's "people who watched this also watched" for an IMDb id. Null = Trakt off or not reachable. */
    suspend fun related(imdb: String, tv: Boolean, limit: Int = 20): List<Related>? = if (!on()) null else
        runCatching { get<List<Related>>("${if (tv) "shows" else "movies"}/$imdb/related?limit=$limit") }.getOrNull()

    /** IMDb ids of every film and show watched (for Home's Watched titles: Dim / Hide). Null when Trakt is off. */
    suspend fun watchedIds(): Set<String>? = if (!on()) null else coroutineScope {
        val m = async { runCatching { get<List<WatchedMovie>>("sync/watched/movies") }.getOrNull() }
        val s = async { watchedShows() }
        val movies = m.await(); val shows = s.await()
        if (movies == null && shows == null) null
        else (movies.orEmpty().mapNotNull { it.movie.ids.imdb } + shows.orEmpty().mapNotNull { it.show.ids.imdb }).toSet()
    }

    /** Every show ever watched (no seasons), newest first. Null when Trakt is off or not reachable. */
    suspend fun watchedShows(): List<Watched>? = if (!on()) null else runCatching { get<List<Watched>>("sync/watched/shows?extended=noseasons") }.getOrNull()

    /** Calendar: episodes of shows you watch and films you follow, from today for [days] days. Empty when Trakt is off. */
    suspend fun calendar(days: Int = 14): Pair<List<CalShow>, List<CalMovie>> = if (!on()) emptyList<CalShow>() to emptyList() else coroutineScope {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val s = async { runCatching { get<List<CalShow>>("calendars/my/shows/$today/$days") }.getOrDefault(emptyList()) }
        val m = async { runCatching { get<List<CalMovie>>("calendars/my/movies/$today/$days") }.getOrDefault(emptyList()) }
        s.await() to m.await()
    }

    // --- Coming up: every show anywhere in your Trakt library ---

    @Serializable data class TListIds(val trakt: Long? = null, val slug: String? = null)
    @Serializable data class TList(val name: String = "", val ids: TListIds = TListIds())
    @Serializable data class ShowRow(val show: TShow? = null)

    /** Shows in your collection, your ratings and every one of your own lists (name by IMDb id). Empty when Trakt is off. */
    suspend fun libraryShows(): List<TShow> = if (!on()) emptyList() else coroutineScope {
        val col = async { runCatching { get<List<ShowRow>>("sync/collection/shows?extended=noseasons") }.getOrDefault(emptyList()) }
        val rat = async { runCatching { get<List<ShowRow>>("sync/ratings/shows") }.getOrDefault(emptyList()) }
        val lists = runCatching { get<List<TList>>("users/me/lists") }.getOrDefault(emptyList())
        val items = lists.mapNotNull { it.ids.trakt }.map { id -> async { runCatching { get<List<ShowRow>>("users/me/lists/$id/items/shows") }.getOrDefault(emptyList()) } }
        (col.await() + rat.await() + items.flatMap { it.await() }).mapNotNull { it.show }
    }

    // --- details page ---

    @Serializable data class PEp(val number: Int = 0, val completed: Boolean = false)
    @Serializable data class PSeason(val number: Int = 0, val episodes: List<PEp> = emptyList())
    @Serializable data class Progress(val seasons: List<PSeason> = emptyList(), @SerialName("next_episode") val next: TEpisode? = null,
                                      @SerialName("last_episode") val last: TEpisode? = null)
    @Serializable data class TUser(val username: String = "")
    @Serializable data class UStats(val rating: Int? = null)
    @Serializable data class Comment(val id: Long = 0, val comment: String = "", val spoiler: Boolean = false, val review: Boolean = false,
                                     val likes: Int = 0, @SerialName("created_at") val createdAt: String = "", val user: TUser = TUser(),
                                     @SerialName("user_stats") val stats: UStats = UStats())
    @Serializable data class TRating(val rating: Double = 0.0, val votes: Int = 0)

    /** A show's watched episodes and the next one to watch (null when Trakt is off). */
    suspend fun progress(imdb: String): Progress? = if (!on()) null else
        runCatching { get<Progress>("shows/$imdb/progress/watched?specials=false&count_specials=false") }.getOrNull()

    /** Trakt's own rating (0-10) and vote count. Public: no sign-in needed. */
    suspend fun rating(imdb: String, tv: Boolean): TRating? = if (!configured) null else
        runCatching { val (c, t) = call("GET", "${if (tv) "shows" else "movies"}/$imdb/ratings", auth = false); if (c in 200..299) Net.json.decodeFromString<TRating>(t) else null }.getOrNull()

    /** Trakt comments, most liked first (an episode's when [season]/[episode] are given). Public. */
    suspend fun comments(imdb: String, tv: Boolean, season: Int? = null, episode: Int? = null): List<Comment> = if (!configured) emptyList() else runCatching {
        val path = if (!tv) "movies/$imdb/comments/likes" else if (season != null && episode != null) "shows/$imdb/seasons/$season/episodes/$episode/comments/likes" else "shows/$imdb/comments/likes"
        val (c, t) = call("GET", "$path?limit=25", auth = false)
        if (c in 200..299) Net.json.decodeFromString<List<Comment>>(t) else emptyList()
    }.getOrDefault(emptyList())

    /** action = start | pause | stop. Trakt marks watched at stop >= 80%. */
    suspend fun scrobble(action: String, imdb: String, season: Int?, episode: Int?, progress: Float) {
        if (!on()) return
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
