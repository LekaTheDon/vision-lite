package com.lekathedon.visionlite.iptv

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.browse.Chips
import com.lekathedon.visionlite.ui.Focusable
import com.lekathedon.visionlite.ui.Img
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Nav
import com.lekathedon.visionlite.ui.Palette
import com.lekathedon.visionlite.ui.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class VodItem(val pl: String, val kind: String, val id: String, val name: String, val group: String, val poster: String?, val url: String?, val rating: String?)
data class VodEpisode(val season: Int, val number: Int, val title: String, val url: String, val plot: String?, val image: String?)

/**
 * VOD (Vision's On Demand): films and series from your playlists. Xtream logins list them through their own API, asked
 * only when VOD opens (at most once a day); M3U lists hand theirs over with the channels. Kept in vod.db, not memory.
 */
object Vod {
    private const val TAG = "Vod"
    private val lock = Mutex()
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> get() = _status
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> get() = _version

    private val VOD_PATH = Regex("/(movie|series)/", RegexOption.IGNORE_CASE)
    private val VOD_EXT = listOf(".mp4", ".mkv", ".avi", ".m4v", ".mov", ".wmv")
    fun looksVod(url: String) = VOD_PATH.containsMatchIn(url) || url.substringBefore('?').lowercase().let { u -> VOD_EXT.any { u.endsWith(it) } }

    fun fromM3u(pl: String, entries: List<M3uEntry>) {
        write(pl, "m3u", entries.mapIndexed { i, e -> VodItem(pl, "movie", "m$i", e.name, e.group?.ifBlank { null } ?: "Films", e.logo, e.url, null) })
    }

    fun removePlaylist(pl: String) { db.writableDatabase.delete("items", "pl=?", arrayOf(pl)); db.writableDatabase.delete("meta", "k LIKE ?", arrayOf("$pl|%")); _version.value++ }

    /** Lists the Xtream logins' films and series that are new or a day old (called when VOD opens). */
    suspend fun ensureLoaded(force: Boolean = false) = lock.withLock {
        withContext(Dispatchers.IO) {
            for (pl in Iptv.playlists().filter { it.xtream }) {
                if (!force && System.currentTimeMillis() - loadedAt(pl.id) < Iptv.staleMs()) continue
                try {
                    val first = loadedAt(pl.id) == 0L
                    _status.value = if (first) "Downloading ${pl.name}'s films and series (first time only)…" else "Updating ${pl.name}'s films and series…"
                    val base = Iptv.xtreamBase(pl)
                    val api = "$base/player_api.php?username=${Iptv.encode(pl.user)}&password=${Iptv.encode(pl.pass)}"
                    // Streamed straight into vod.db: big panels send 50-100 MB lists (one string of them ran the app out of memory).
                    // Kept in the provider's order (TiviMate shows them so): the VOD page lists categories this way.
                    fun names(action: String, kind: String): Map<String, String> = LinkedHashMap<String, String>().also { m ->
                        eachObject("$api&action=$action") { o -> m[o["category_id"].orEmpty()] = o["category_name"].orEmpty() }
                        if (m.isNotEmpty()) metaPut("${pl.id}|$kind|cats", m.values.distinct().joinToString("\n"))
                    }
                    val movieCats = names("get_vod_categories", "movie")
                    writeStream(pl.id, "movie") { add ->
                        eachObject("$api&action=get_vod_streams") { o ->
                            val id = o["stream_id"]?.ifBlank { null } ?: return@eachObject
                            add(VodItem(pl.id, "movie", id, o["name"].orEmpty(), movieCats[o["category_id"].orEmpty()] ?: "Films", o["stream_icon"]?.takeIf { it.startsWith("http") },
                                "$base/movie/${Iptv.encode(pl.user)}/${Iptv.encode(pl.pass)}/$id.${o["container_extension"]?.ifBlank { null } ?: "mp4"}", o["rating"]?.takeIf { it.isNotBlank() && it != "0" }))
                        }
                    }
                    val seriesCats = names("get_series_categories", "series")
                    writeStream(pl.id, "series") { add ->
                        eachObject("$api&action=get_series") { o ->
                            val id = o["series_id"]?.ifBlank { null } ?: return@eachObject
                            add(VodItem(pl.id, "series", id, o["name"].orEmpty(), seriesCats[o["category_id"].orEmpty()] ?: "Series", o["cover"]?.takeIf { it.startsWith("http") }, null,
                                o["rating"]?.takeIf { it.isNotBlank() && it != "0" }))
                        }
                    }
                    db.writableDatabase.execSQL("INSERT OR REPLACE INTO meta(k,v) VALUES(?,?)", arrayOf("${pl.id}|loaded", System.currentTimeMillis().toString()))
                    _status.value = ""
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "${pl.name}: ${e.message}"); _status.value = "${pl.name}'s VOD didn't load: ${e.message}"
                }
                _version.value++
            }
        }
    }

    // ---- the shelf: the whole VOD page, built at once (user 2026-11-24: "open it to find it ready like TiviMate") ----

    /** Every category of one kind with its first [SHELF] titles, in the order shown. */
    class Shelf(val groups: List<Pair<String, Int>>, val rows: Map<String, List<VodItem>>)
    const val SHELF = 12
    private val shelves = java.util.concurrent.ConcurrentHashMap<String, Shelf>()
    private val _shelfRev = MutableStateFlow(0)
    /** Goes up when a whole new shelf is ready; the page redraws only then (never row by row). */
    val shelfRev: StateFlow<Int> get() = _shelfRev
    private val prepLock = Mutex()
    @Volatile private var preparedAt = -1

    fun shelf(kind: String): Shelf? = shelves[kind]

    /**
     * Builds both shelves from vod.db (836 categories ≈ 0.3 s on the AM9, ~3 MB held) and puts the first posters of the
     * top 40 categories in the image cache. Runs after an IPTV update and when Live TV / VOD opens; never at app start.
     */
    suspend fun prepare(force: Boolean = false) = prepLock.withLock {
        withContext(Dispatchers.IO) {
            val v = _version.value
            if (!force && preparedAt == v && shelves.isNotEmpty()) return@withContext
            if (count() == 0L) { shelves.clear(); preparedAt = v; _shelfRev.value++; return@withContext }
            val first = shelves.isEmpty()
            if (first && _status.value.isEmpty()) _status.value = "Building VOD…"
            val built = listOf("movie", "series").associateWith { kind ->
                val gs = arranged(kind, groups(kind))
                Shelf(gs, gs.associate { (g, _) -> g to items(kind, g, 0, SHELF) })
            }
            shelves.putAll(built); preparedAt = v
            if (_status.value == "Building VOD…") _status.value = ""
            _shelfRev.value++
            built.values.forEach { sh -> prefetch(sh.groups.take(40).flatMap { (g, _) -> sh.rows[g].orEmpty().take(7).mapNotNull { card(it.poster) } }) }
        }
    }
    /** Re-sorts the built shelves after Arrange (no DB read of titles). */
    fun rearrange(kind: String) {
        val sh = shelves[kind] ?: return
        shelves[kind] = Shelf(arranged(kind, groups(kind)), sh.rows); _shelfRev.value++
    }

    /** Provider posters are often TMDB's 600×900; cards are ~200 wide, so the 342 size loads ~3× faster. */
    fun card(poster: String?): String? = poster?.replace(Regex("""(image\.tmdb\.org/t/p/)(w600_and_h900_bestv2|original|w780|w500)/"""), "$1w342/")

    private fun prefetch(urls: List<String>) {
        val ctx = App.instance
        val loader = coil3.SingletonImageLoader.get(ctx)
        urls.distinct().forEach { u -> loader.enqueue(coil3.request.ImageRequest.Builder(ctx).data(u).memoryCachePolicy(coil3.request.CachePolicy.DISABLED).build()) }
    }

    /** Playlists loaded before the provider's category order was kept: just their two category lists (2 small calls, once). */
    suspend fun ensureCatOrder() = withContext(Dispatchers.IO) {
        var got = false
        for (pl in Iptv.playlists().filter { it.xtream && loadedAt(it.id) > 0 }) for ((kind, action) in listOf("movie" to "get_vod_categories", "series" to "get_series_categories")) {
            if (metaGet("${pl.id}|$kind|cats") != null) continue
            runCatching {
                val api = "${Iptv.xtreamBase(pl)}/player_api.php?username=${Iptv.encode(pl.user)}&password=${Iptv.encode(pl.pass)}"
                val names = ArrayList<String>()
                eachObject("$api&action=$action") { o -> o["category_name"]?.let { if (it !in names) names.add(it) } }
                if (names.isNotEmpty()) { metaPut("${pl.id}|$kind|cats", names.joinToString("\n")); got = true }
            }.onFailure { Log.w(TAG, "${pl.name} categories: ${it.message}") }
        }
        if (got) { groupCache.clear(); _version.value++ }
    }

    /**
     * Each object of a top-level JSON array at [url], as flat strings (nested values skipped), read as a stream: memory
     * stays flat however long the list is. A panel answering with an object (or nothing) gives no items.
     */
    private fun eachObject(url: String, each: (Map<String, String?>) -> Unit) {
        android.util.JsonReader(Iptv.openStream(url).bufferedReader()).use { r ->
            r.isLenient = true
            if (r.peek() != android.util.JsonToken.BEGIN_ARRAY) { r.skipValue(); return }
            r.beginArray()
            val o = HashMap<String, String?>()
            while (r.hasNext()) {
                if (r.peek() != android.util.JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                o.clear(); r.beginObject()
                while (r.hasNext()) {
                    val k = r.nextName()
                    when (r.peek()) {
                        android.util.JsonToken.STRING, android.util.JsonToken.NUMBER -> o[k] = r.nextString()
                        android.util.JsonToken.BOOLEAN -> o[k] = r.nextBoolean().toString()
                        android.util.JsonToken.NULL -> { r.nextNull(); o[k] = null }
                        else -> r.skipValue()
                    }
                }
                r.endObject(); each(o)
            }
            r.endArray()
        }
    }

    /** Replaces [pl]'s [kind] items with what [fill] adds, in one transaction (nothing changes when it fails). */
    /**
     * Streams one kind into vod.db in one transaction. When the provider's list is exactly what we have (same
     * fingerprint as last time), the transaction is dropped: nothing changes on screen and no row reloads.
     */
    private fun writeStream(pl: String, kind: String, fill: ((VodItem) -> Unit) -> Unit) {
        val w = db.writableDatabase
        var changed = false
        w.beginTransaction()
        try {
            var h = 1125899906842597L
            w.delete("items", "pl=? AND kind=?", arrayOf(pl, kind))
            val st = w.compileStatement("INSERT OR REPLACE INTO items(pl,kind,id,name,grp,poster,url,rating) VALUES(?,?,?,?,?,?,?,?)")
            var n = 0
            val showCount = _status.value.contains("first time")
            fill { v ->
                n++
                if (showCount && n % 5000 == 0) _status.value = "Downloading ${if (kind == "movie") "films" else "series"}: ${"%,d".format(n)}… (first time only)"
                st.clearBindings(); st.bindString(1, v.pl); st.bindString(2, v.kind); st.bindString(3, v.id); st.bindString(4, v.name); st.bindString(5, v.group)
                v.poster?.let { st.bindString(6, it) }; v.url?.let { st.bindString(7, it) }; v.rating?.let { st.bindString(8, it) }; st.executeInsert()
                h = 31 * h + "${v.id}|${v.name}|${v.group}|${v.poster}|${v.url}|${v.rating}".hashCode()
            }
            val sig = "$h:$n"
            val key = "$pl|$kind|sig"
            if (n == 0 || sig != metaGet(key)) {
                w.execSQL("INSERT OR REPLACE INTO meta(k,v) VALUES(?,?)", arrayOf(key, sig))
                w.setTransactionSuccessful(); changed = true
            }
        } finally { w.endTransaction() }
        if (changed) _version.value++
    }

    /** A series' episodes (Xtream get_series_info). */
    suspend fun episodes(item: VodItem): List<VodEpisode> = withContext(Dispatchers.IO) {
        val pl = Iptv.playlists().firstOrNull { it.id == item.pl && it.xtream } ?: return@withContext emptyList()
        val base = Iptv.xtreamBase(pl)
        runCatching {
            val o = JSONObject(Iptv.openStream("$base/player_api.php?username=${Iptv.encode(pl.user)}&password=${Iptv.encode(pl.pass)}&action=get_series_info&series_id=${item.id}")
                .bufferedReader().use { it.readText() })
            val eps = o.optJSONObject("episodes") ?: return@runCatching emptyList()
            eps.keys().asSequence().flatMap { s ->
                val a = eps.optJSONArray(s) ?: JSONArray()
                (0 until a.length()).mapNotNull { i ->
                    val e = a.optJSONObject(i) ?: return@mapNotNull null
                    val info = e.optJSONObject("info")
                    VodEpisode(s.toIntOrNull() ?: e.optInt("season"), e.optInt("episode_num"), e.optString("title"),
                        "$base/series/${Iptv.encode(pl.user)}/${Iptv.encode(pl.pass)}/${e.optString("id")}.${e.optString("container_extension").ifBlank { "mp4" }}",
                        info?.optString("plot")?.takeIf { it.isNotBlank() }, info?.optString("movie_image")?.takeIf { it.startsWith("http") })
                }.asSequence()
            }.sortedWith(compareBy({ it.season }, { it.number })).toList()
        }.getOrDefault(emptyList())
    }

    /** Categories in the provider's order (its category list), then any it didn't list; see [arranged] for yours. */
    fun groups(kind: String): List<Pair<String, Int>> {
        val v = _version.value
        groupCache[kind]?.takeIf { it.first == v }?.let { return it.second }
        val rows = db.readableDatabase.rawQuery("SELECT grp, COUNT(*) FROM items WHERE kind=? GROUP BY grp ORDER BY MIN(rowid)", arrayOf(kind)).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getInt(1)) }
        }
        val order = HashMap<String, Int>()
        Iptv.playlists().forEach { pl -> metaGet("${pl.id}|$kind|cats")?.split('\n')?.forEach { g -> order.putIfAbsent(g, order.size) } }
        return rows.withIndex().sortedBy { (i, r) -> order[r.first] ?: (order.size + i) }.map { it.value }
            .also { groupCache[kind] = v to it }
    }
    /** The page opens with its categories at once (no empty frame) once they were read this run. */
    fun groupsCached(kind: String): List<Pair<String, Int>> = groupCache[kind]?.second.orEmpty()

    /** The categories you moved first, in your order; then the provider's order. Adult ones out when Hide adult is on. */
    fun arranged(kind: String, list: List<Pair<String, Int>>, withHidden: Boolean = false): List<Pair<String, Int>> {
        val adult = App.instance.prefs.hideAdult.value
        return Groups.arrange(kind, list.filter { !adult || ADULT.none { a -> it.first.contains(a, true) } }, { it.first }, withHidden)
    }
    private val ADULT = listOf("+18", "18+", "adult", "xxx", "للكبار")

    /** Saves [kind]'s categories in this order (the Arrange list). */
    fun saveOrder(kind: String, groups: List<String>) = Groups.saveOrder(kind, groups)
    private val groupCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, List<Pair<String, Int>>>>()

    fun items(kind: String, group: String?, offset: Int = 0, limit: Int = 3000): List<VodItem> {
        val (w, a) = if (group == null) "kind=?" to arrayOf(kind) else "kind=? AND grp=?" to arrayOf(kind, group)
        return db.readableDatabase.rawQuery("SELECT pl,kind,id,name,grp,poster,url,rating FROM items WHERE $w ORDER BY rowid LIMIT $limit OFFSET $offset", a).use { c ->
            buildList { while (c.moveToNext()) add(VodItem(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7))) }
        }
    }

    /** One title by its keys (a Home VOD card opens it). */
    fun item(pl: String, kind: String, id: String): VodItem? = db.readableDatabase.rawQuery(
        "SELECT pl,kind,id,name,grp,poster,url,rating FROM items WHERE pl=? AND kind=? AND id=?", arrayOf(pl, kind, id)).use { c ->
        if (c.moveToFirst()) VodItem(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7)) else null
    }

    /** Small saved facts (VOD → TMDB matches); kept across refreshes. */
    fun metaGet(k: String): String? = db.readableDatabase.rawQuery("SELECT v FROM meta WHERE k=?", arrayOf(k)).use { if (it.moveToFirst()) it.getString(0) else null }
    fun metaPut(k: String, v: String) { db.writableDatabase.execSQL("INSERT OR REPLACE INTO meta(k,v) VALUES(?,?)", arrayOf(k, v)) }

    /** How many films and series vod.db holds (0 = no VOD: Vision TB doesn't look here). */
    fun count(): Long = db.readableDatabase.rawQuery("SELECT COUNT(*) FROM items", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    /** Titles of [kind] whose name holds [word] (Vision TB's VOD match narrows with this, then compares whole names). */
    fun search(kind: String, word: String, limit: Int = 200): List<VodItem> {
        if (word.isBlank()) return emptyList()
        return db.readableDatabase.rawQuery("SELECT pl,kind,id,name,grp,poster,url,rating FROM items WHERE kind=? AND name LIKE ? LIMIT $limit",
            arrayOf(kind, "%${word.replace("%", "").replace("_", "")}%")).use { c ->
            buildList { while (c.moveToNext()) add(VodItem(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7))) }
        }
    }

    private fun write(pl: String, what: String, list: List<VodItem>) {
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            if (what == "m3u") w.delete("items", "pl=? AND id LIKE 'm%'", arrayOf(pl)) else w.delete("items", "pl=? AND kind=?", arrayOf(pl, what))
            val st = w.compileStatement("INSERT OR REPLACE INTO items(pl,kind,id,name,grp,poster,url,rating) VALUES(?,?,?,?,?,?,?,?)")
            list.forEach { v ->
                st.clearBindings(); st.bindString(1, v.pl); st.bindString(2, v.kind); st.bindString(3, v.id); st.bindString(4, v.name); st.bindString(5, v.group)
                v.poster?.let { st.bindString(6, it) }; v.url?.let { st.bindString(7, it) }; v.rating?.let { st.bindString(8, it) }; st.executeInsert()
            }
            w.setTransactionSuccessful()
        } finally { w.endTransaction() }
        _version.value++
    }

    private fun loadedAt(pl: String) = db.readableDatabase.rawQuery("SELECT v FROM meta WHERE k=?", arrayOf("$pl|loaded")).use { if (it.moveToFirst()) it.getString(0).toLongOrNull() ?: 0L else 0L }

    private val db by lazy {
        object : SQLiteOpenHelper(App.instance, com.lekathedon.visionlite.data.Profiles.db("vod.db"), null, 1) {
            override fun onCreate(d: SQLiteDatabase) {
                d.execSQL("CREATE TABLE items(pl TEXT, kind TEXT, id TEXT, name TEXT, grp TEXT, poster TEXT, url TEXT, rating TEXT, PRIMARY KEY(pl,kind,id))")
                d.execSQL("CREATE INDEX items_grp ON items(kind, grp)")
                d.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)")
            }
            override fun onUpgrade(d: SQLiteDatabase, o: Int, n: Int) {}
        }.also { it.setWriteAheadLoggingEnabled(true) }   // TMDB matches written while rows read (no lock waits)
    }
}

/**
 * VOD the TiviMate way (user 2026-11-24): the provider's categories in the provider's order (or yours: Arrange), its own
 * portrait posters with our badge drawn on them, no TMDB/IMDb lookups, no trailers, no landscape cards. OK plays the
 * provider's link (a series opens its episodes). Each row reads 40 titles from vod.db and the next 40 at its end.
 */
@Composable
fun VodScreen(nav: Nav) {
    val status by Vod.status.collectAsState()
    val rev by Vod.shelfRev.collectAsState()
    LaunchedEffect(Unit) {
        com.lekathedon.visionlite.iptv.IptvSync.kick()
        Vod.ensureCatOrder()
        Vod.prepare()
    }
    val adult by App.instance.prefs.hideAdult.flow.collectAsState()
    LaunchedEffect(adult) { withContext(Dispatchers.IO) { Vod.rearrange("movie"); Vod.rearrange("series") } }
    var kind by rememberSaveable { mutableStateOf("movie") }
    val shelf = remember(rev, kind) { Vod.shelf(kind) }
    val groups = shelf?.groups.orEmpty()
    var series by remember { mutableStateOf<VodItem?>(null) }
    var arranging by remember { mutableStateOf(false) }
    if (series != null) { androidx.activity.compose.BackHandler { series = null }; Episodes(nav, series!!); return }
    if (arranging) {
        androidx.activity.compose.BackHandler { arranging = false }
        val all = remember(kind) { Vod.arranged(kind, Vod.groupsCached(kind).ifEmpty { groups }, withHidden = true).map { it.first } }
        GroupArrange("Arrange ${if (kind == "movie") "film" else "series"} categories", kind, all, onChanged = { Vod.rearrange(kind) }) { arranging = false }
        return
    }
    val store = remember(rev, kind) { HashMap<String, VodRow>() }
    Column(Modifier.fillMaxSize().background(LocalLook.current.page).padding(start = 40.dp, end = 24.dp, top = 24.dp)) {
        Row {
            Text("VOD", color = Palette.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(status.ifEmpty { if (Iptv.playlists().isEmpty()) "Add a playlist in Live TV first." else "${groups.sumOf { it.second }} titles · ${groups.size} categories" },
                color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(start = 14.dp, top = 8.dp))
        }
        Chips(listOf("movie" to "Films", "series" to "Series", "arrange" to "Arrange / hide categories"), kind, Modifier.padding(top = 12.dp)) {
            if (it == "arrange") arranging = true else kind = it
        }
        // Not built yet (first open, or the very first download): one message, then the whole page at once.
        if (shelf == null) {
            Text(status.ifEmpty { "Building VOD…" }, color = Palette.TextDim, fontSize = 18.sp, modifier = Modifier.padding(top = 60.dp))
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 60.dp)) {
            items(groups, key = { "$kind|${it.first}" }) { (g, n) ->
                VodRail(nav, kind, g, n, shelf.rows[g].orEmpty(), store) { series = it }
            }
        }
    }
}

/** One category row's titles, kept by the page so a row scrolled away and back keeps what it had. */
class VodRow { val items = androidx.compose.runtime.mutableStateListOf<VodItem>(); var busy = false; var end = false }

private val YEAR_RX = Regex("""(?<!\d)(19[3-9]\d|20[0-4]\d)(?!\d)""")

/** A VOD title as a card: the provider's own name and poster (nothing looked up). */
private fun vodCard(v: VodItem) = com.lekathedon.visionlite.addons.MetaPreview("vod:${v.pl}|${v.kind}|${v.id}", if (v.kind == "series") "series" else "movie",
    v.name, Vod.card(v.poster), imdbRating = v.rating, releaseInfo = YEAR_RX.find(v.name)?.value)

@Composable
private fun VodRail(nav: Nav, kind: String, group: String, count: Int, first: List<VodItem>, store: MutableMap<String, VodRow>, openSeries: (VodItem) -> Unit) {
    // Starts with the shelf's titles (already in memory): the row is whole the moment it shows; more load at its end.
    val d = store.getOrPut(group) { VodRow().also { it.items.addAll(first); it.end = first.size < Vod.SHELF } }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun more() {
        if (d.busy || d.end) return
        d.busy = true
        scope.launch {
            try {
                val got = withContext(Dispatchers.IO) { Vod.items(kind, group, d.items.size, 40) }
                d.items.addAll(got); d.end = got.size < 40
            } finally { d.busy = false }
        }
    }
    LaunchedEffect(group) { if (d.items.isEmpty()) more() }
    val size = d.items.size
    val metas = remember(size) { d.items.map(::vodCard) }
    val byId = remember(size) { d.items.associateBy { "vod:${it.pl}|${it.kind}|${it.id}" } }
    com.lekathedon.visionlite.home.PageRow("vod|$kind|$group", "$group · $count", metas, loading = !d.end && metas.isEmpty(),
        onEnd = { more() }, expand = false, filter = false, overlay = { m -> VodBadge(m) }) { m ->
        val v = byId[m.id] ?: return@PageRow
        if (v.kind == "series") openSeries(v) else v.url?.let { nav.push(Screen.Player(it, v.name, type = "vod")) }
    }
}

private val QUALITY_RX = Regex("""(?i)\b(4k|uhd|2160p?)\b""")
private val Gold = Color(0xFFE6B422)

/** Our badge on the provider's poster: the provider's rating (gold, bottom left), 4K and the year on top. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.VodBadge(m: com.lekathedon.visionlite.addons.MetaPreview) {
    val rating = m.imdbRating?.toDoubleOrNull()?.takeIf { it > 0 }
    val fourK = QUALITY_RX.containsMatchIn(m.name)
    if (fourK || m.releaseInfo != null) Row(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
        if (fourK) Text("4K", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.background(Gold, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
        m.releaseInfo?.let { Text(it, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp).background(Color(0xB3000000), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp)) }
    }
    if (rating != null) Text("★ ${"%.1f".format(if (rating > 10) rating / 10 else rating)}", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).background(Gold, RoundedCornerShape(topEnd = 8.dp, bottomStart = 8.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
}

@Composable
internal fun Episodes(nav: Nav, s: VodItem) {
    val eps by produceState<List<VodEpisode>?>(null, s) { value = Vod.episodes(s) }
    val seasons = eps.orEmpty().map { it.season }.distinct()
    var season by remember(s) { mutableStateOf<Int?>(null) }
    val sea = season ?: seasons.firstOrNull()
    Column(Modifier.fillMaxSize().background(LocalLook.current.page).padding(40.dp)) {
        Row {
            if (s.poster != null) Img(s.poster, 110.dp, 165.dp, Modifier.size(110.dp, 165.dp))
            Column(Modifier.padding(start = 18.dp)) {
                Text(s.name, color = Palette.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(if (eps == null) "Loading…" else "${eps!!.size} episodes", color = Palette.TextDim, fontSize = 14.sp)
                if (seasons.size > 1) Chips(seasons.map { it.toString() to "Season $it" }, sea?.toString(), Modifier.padding(top = 12.dp)) { season = it.toInt() }
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(eps.orEmpty().filter { it.season == sea }) { e ->
                Focusable({ nav.push(Screen.Player(e.url, "${s.name} · S${e.season}E${e.number}", type = "vod")) }, Modifier.fillMaxWidth()) { f ->
                    Column(Modifier.fillMaxWidth().background(if (f) Color.White else Color(0x12FFFFFF)).padding(12.dp)) {
                        Text("${e.number}. ${e.title}", color = if (f) Color.Black else Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        e.plot?.let { Text(it, color = if (f) Color.DarkGray else Palette.TextDim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}

/** A VOD series opened from a Home row (no TMDB match): its episodes. */
@Composable
fun VodSeriesScreen(nav: Nav, pl: String, id: String) {
    val item = remember(pl, id) { Vod.item(pl, "series", id) }
    if (item == null) { Text("Not in your VOD any more", color = Palette.TextDim, modifier = Modifier.padding(48.dp)); return }
    Episodes(nav, item)
}
