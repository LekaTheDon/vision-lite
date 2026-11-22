package com.lekathedon.visionlite.link

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import com.lekathedon.visionlite.ui.Screen
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What the phone sees of the app, kept by the app itself (no root reads, no media-session listener): the player that
 * plays now, whether Lite is on screen, and Home's rows. Everything here is a few references; JSON is built only
 * when the phone asks.
 */
object LinkState {
    private val main = Handler(Looper.getMainLooper())

    // ---- On screen ----
    @Volatile var front = false

    // ---- The player ----
    class Playing(val player: WeakReference<Player>, val title: String, val subtitle: String, val art: String?, val type: String, val id: String)
    @Volatile private var playing: Playing? = null
    private val revLock = Object()
    @Volatile private var rev = 0

    fun bump() { synchronized(revLock) { rev++; revLock.notifyAll() } }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = bump()
        override fun onPositionDiscontinuity(o: Player.PositionInfo, n: Player.PositionInfo, reason: Int) = bump()
        override fun onPlaybackStateChanged(state: Int) = bump()
    }

    /** The player screen calls this when it starts ([p] = null when it closes). Main thread. */
    fun setPlayer(p: Player?, title: String = "", subtitle: String = "", art: String? = null, type: String = "", id: String = "") {
        playing?.player?.get()?.removeListener(listener)
        playing = p?.let { Playing(WeakReference(it), title, subtitle, art, type, id) }
        p?.addListener(listener)
        bump()
    }

    /** Runs [f] on the player's thread (ExoPlayer is main-thread only) and waits up to 2 s for it. */
    fun <T> onPlayer(f: (Player, Playing) -> T): T? {
        val now = playing ?: return null
        val p = now.player.get() ?: return null
        var out: T? = null
        val done = CountDownLatch(1)
        main.post { runCatching { out = f(p, now) }; done.countDown() }
        done.await(2, TimeUnit.SECONDS)
        return out
    }

    fun mediaJson(): JSONObject? = onPlayer { p, now ->
        val dur = p.duration.takeIf { it > 0 } ?: 0L
        JSONObject().put("title", now.title).put("subtitle", now.subtitle)
            .put("pos", p.currentPosition.coerceAtLeast(0)).put("dur", dur)
            .put("playing", p.playWhenReady && p.playbackState != Player.STATE_ENDED)
            .put("seekable", dur > 0 && p.isCurrentMediaItemSeekable).put("live", dur <= 0)
            .put("episode", now.type == "series").put("art", now.art.orEmpty()).put("type", now.type).put("id", now.id)
    }

    /** Waits until what plays changes from [since], or [ms]; returns the new revision. */
    fun await(since: Int, ms: Long): Int = synchronized(revLock) {
        val until = System.currentTimeMillis() + ms
        while (rev == since) {
            val left = until - System.currentTimeMillis()
            if (left <= 0) break
            revLock.wait(left)
        }
        rev
    }

    // ---- Home rows, as the phone's TvRows reads them ----
    @Volatile var rows: JSONObject? = null

    // ---- Screens the phone asked for; MainActivity pushes them ----
    /** Kept until MainActivity takes them, even if it is still starting. */
    val open = kotlinx.coroutines.channels.Channel<Screen>(8)
    /** The phone's Next episode press, taken by the player that is open. */
    val next = kotlinx.coroutines.channels.Channel<Unit>(1, kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    /** Each hub's last rows (Films & TV, Anime): a hub publishes when its Home is shown; the phone gets both, tagged "hub". */
    private val hubRows = java.util.concurrent.ConcurrentHashMap<String, JSONArray>()

    /** Builds the rows answer: per hub, Continue watching first ("kind":"continue"), then Home's rows with items. */
    fun publishRows(hub: com.lekathedon.visionlite.anime.Hub, cw: List<com.lekathedon.visionlite.trakt.Resume>, rows: List<Pair<String, List<com.lekathedon.visionlite.addons.MetaPreview>>>) {
        val a = JSONArray()
        if (cw.isNotEmpty()) a.put(JSONObject().put("title", "Continue watching").put("kind", "continue").put("hub", hub.key).put("items", JSONArray().apply {
            cw.forEach { r ->
                put(JSONObject().put("id", r.imdb).put("imdb", r.imdb).put("type", r.type).put("name", r.name)
                    .put("poster", com.lekathedon.visionlite.library.posterOf(r.imdb)).put("backdrop", com.lekathedon.visionlite.library.backdropOf(r.imdb))
                    .put("videoId", r.streamId).put("season", r.season?.toString()).put("episode", r.episode?.toString())
                    .put("progress", r.progress.toString()))
            }
        }))
        rows.forEach { (title, items) ->
            val list = items.filter { it.type == "movie" || it.type == "series" }
            if (list.isEmpty()) return@forEach
            a.put(JSONObject().put("title", title).put("source", "Nuvio Vision").put("hub", hub.key).put("items", JSONArray().apply {
                list.take(40).forEach { m ->
                    put(JSONObject().put("id", m.id).put("type", m.type).put("name", m.name).put("year", m.releaseInfo)
                        .put("poster", m.poster).put("backdrop", m.background).put("imdb", m.id.takeIf { it.startsWith("tt") }))
                }
            }))
        }
        hubRows[hub.key] = a
        val all = JSONArray()
        com.lekathedon.visionlite.anime.Hub.entries.forEach { h -> hubRows[h.key]?.let { r -> for (i in 0 until r.length()) all.put(r.get(i)) } }
        this.rows = JSONObject().put("at", System.currentTimeMillis()).put("rows", all)
            .put("hubs", JSONArray(hubRows.keys.toList()))
    }
}
