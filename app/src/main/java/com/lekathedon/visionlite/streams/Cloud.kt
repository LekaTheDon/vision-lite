package com.lekathedon.visionlite.streams

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import com.lekathedon.visionlite.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.data.Net
import com.lekathedon.visionlite.integrations.Integrations
import com.lekathedon.visionlite.ui.Focusable
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Nav
import com.lekathedon.visionlite.ui.Palette
import com.lekathedon.visionlite.ui.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Your debrid cloud (DebridStream's Cloud Manager, written for Lite): what's already in your Real-Debrid, AllDebrid,
 * Premiumize, TorBox and Offcloud accounts, newest first, searchable; OK opens a torrent's files, OK on a file plays it.
 * Read-only: nothing is added or deleted here.
 * Cost: nothing until the page opens; then one list request per service you pick, one per torrent you open.
 */
object Cloud {
    class Item(val service: Debrid.Id, val id: String, val name: String, val bytes: Long, val added: Long, val ready: Boolean, val single: String? = null)
    class FileRow(val name: String, val bytes: Long, val ref: String)

    /** Services with an account set up (whether or not they're on for links). */
    fun services(): List<Debrid.Id> = Debrid.ALL.filter { id ->
        when (id.id) {
            Debrid.RD.id, Debrid.AD.id -> DebridSignIn.signedIn(id.id)
            Debrid.TB.id -> Debrid.tbKey().isNotBlank()
            else -> Integrations.values(Integrations.byId(id.id)).firstOrNull().orEmpty().isNotBlank()
        }
    }

    private fun body(r: Request): String = Net.http.newCall(r).execute().use { res ->
        val b = res.body?.string().orEmpty()
        if (!res.isSuccessful) throw java.io.IOException("HTTP ${res.code}")
        b
    }
    private fun iso(s: String): Long = runCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrDefault(0L)
    private fun key(id: String) = Integrations.values(Integrations.byId(id)).firstOrNull().orEmpty().trim()
    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    private val VIDEO = Regex("""\.(mkv|mp4|avi|m4v|ts|m2ts|webm|mov|wmv)$""", RegexOption.IGNORE_CASE)

    suspend fun list(s: Debrid.Id): List<Item> = withContext(Dispatchers.IO) {
        when (s.id) {
            Debrid.RD.id -> {
                val auth = DebridSignIn.rdAuthorization() ?: throw IllegalStateException("Sign in to Real-Debrid again")
                val a = JSONArray(body(Request.Builder().url("https://api.real-debrid.com/rest/1.0/torrents?limit=200").header("Authorization", auth).build()).ifBlank { "[]" })
                (0 until a.length()).map { a.getJSONObject(it) }.map { Item(s, it.optString("id"), it.optString("filename"), it.optLong("bytes"), iso(it.optString("added")), it.optString("status") == "downloaded") }
            }
            Debrid.AD.id -> {
                val k = DebridSignIn.adKey() ?: throw IllegalStateException("Sign in to AllDebrid again")
                val o = JSONObject(body(Request.Builder().url("https://api.alldebrid.com/v4.1/magnet/status?agent=${Debrid.AD_AGENT}").header("Authorization", "Bearer $k").build()))
                val m = o.optJSONObject("data")?.opt("magnets")
                val arr = when (m) { is JSONArray -> (0 until m.length()).map { m.getJSONObject(it) }; is JSONObject -> m.keys().asSequence().map { m.getJSONObject(it) }.toList(); else -> emptyList() }
                arr.map { Item(s, it.optLong("id").toString(), it.optString("filename"), it.optLong("size"), it.optLong("uploadDate") * 1000, it.optInt("statusCode") == 4) }
            }
            Debrid.PM.id -> {
                val o = JSONObject(body(Request.Builder().url("https://www.premiumize.me/api/item/listall?apikey=${enc(key(s.id))}").build()))
                val f = o.optJSONArray("files") ?: JSONArray()
                (0 until f.length()).map { f.getJSONObject(it) }.filter { VIDEO.containsMatchIn(it.optString("name")) }
                    .map { Item(s, it.optString("id"), it.optString("path").ifBlank { it.optString("name") }, it.optLong("size"), it.optLong("created_at") * 1000, true, single = it.optString("id")) }
            }
            Debrid.TB.id -> {
                val o = JSONObject(body(Request.Builder().url("https://api.torbox.app/v1/api/torrents/mylist?bypass_cache=true").header("Authorization", "Bearer ${Debrid.tbKey()}").build()))
                val d = o.optJSONArray("data") ?: JSONArray()
                (0 until d.length()).map { d.getJSONObject(it) }.map { Item(s, it.optString("id"), it.optString("name"), it.optLong("size"), iso(it.optString("created_at")), it.optBoolean("download_finished")) }
            }
            else -> {
                val a = JSONArray(body(Request.Builder().url("https://offcloud.com/api/cloud/history?key=${enc(key(s.id))}").build()).ifBlank { "[]" })
                (0 until a.length()).map { a.getJSONObject(it) }.map { Item(s, it.optString("requestId"), it.optString("fileName"), 0L, iso(it.optString("createdOn")), it.optString("status") == "downloaded") }
            }
        }.sortedByDescending { it.added }
    }

    /** A torrent's video files (biggest first within its own order kept by name). */
    suspend fun files(i: Item): List<FileRow> = withContext(Dispatchers.IO) {
        if (i.single != null) return@withContext listOf(FileRow(i.name.substringAfterLast('/'), i.bytes, i.single))
        val rows = when (i.service.id) {
            Debrid.RD.id -> {
                val auth = DebridSignIn.rdAuthorization() ?: throw IllegalStateException("Sign in to Real-Debrid again")
                val o = JSONObject(body(Request.Builder().url("https://api.real-debrid.com/rest/1.0/torrents/info/${i.id}").header("Authorization", auth).build()))
                val fs = o.optJSONArray("files") ?: JSONArray(); val links = o.optJSONArray("links") ?: JSONArray()
                // Real-Debrid's links follow its selected files, in order.
                (0 until fs.length()).map { fs.getJSONObject(it) }.filter { it.optInt("selected") == 1 }
                    .mapIndexedNotNull { n, f -> links.optString(n).takeIf { it.isNotBlank() }?.let { FileRow(f.optString("path").substringAfterLast('/'), f.optLong("bytes"), it) } }
            }
            Debrid.AD.id -> {
                val k = DebridSignIn.adKey() ?: throw IllegalStateException("Sign in to AllDebrid again")
                val fr = JSONObject(body(Request.Builder().url("https://api.alldebrid.com/v4/magnet/files?agent=${Debrid.AD_AGENT}").header("Authorization", "Bearer $k")
                    .post(FormBody.Builder().add("id[]", i.id).build()).build()))
                val tree = fr.optJSONObject("data")?.optJSONArray("magnets")?.optJSONObject(0)?.optJSONArray("files") ?: JSONArray()
                val out = ArrayList<FileRow>()
                fun walk(a: JSONArray) { for (n in 0 until a.length()) { val e = a.getJSONObject(n); e.optJSONArray("e")?.let(::walk) ?: out.add(FileRow(e.optString("n"), e.optLong("s"), e.optString("l"))) } }
                walk(tree); out
            }
            Debrid.TB.id -> {
                val o = JSONObject(body(Request.Builder().url("https://api.torbox.app/v1/api/torrents/mylist?id=${i.id}&bypass_cache=true").header("Authorization", "Bearer ${Debrid.tbKey()}").build()))
                val fs = o.optJSONObject("data")?.optJSONArray("files") ?: JSONArray()
                (0 until fs.length()).map { fs.getJSONObject(it) }.map { FileRow(it.optString("short_name").ifBlank { it.optString("name").substringAfterLast('/') }, it.optLong("size"), it.optInt("id").toString()) }
            }
            else -> {
                val raw = body(Request.Builder().url("https://offcloud.com/api/cloud/explore/${i.id}?key=${enc(key(i.service.id))}").build())
                val a = runCatching { JSONArray(raw) }.getOrNull() ?: throw IllegalStateException("Offcloud won't list this one")
                (0 until a.length()).map { a.optString(it) }.map { FileRow(java.net.URLDecoder.decode(it.substringAfterLast('/'), "UTF-8"), 0L, it) }
            }
        }
        rows.filter { VIDEO.containsMatchIn(it.name) }.ifEmpty { rows }.sortedBy { it.name.lowercase() }
    }

    /** The file's playable link. */
    suspend fun link(i: Item, f: FileRow): String = withContext(Dispatchers.IO) {
        when (i.service.id) {
            Debrid.RD.id -> {
                val auth = DebridSignIn.rdAuthorization() ?: throw IllegalStateException("Sign in to Real-Debrid again")
                JSONObject(body(Request.Builder().url("https://api.real-debrid.com/rest/1.0/unrestrict/link").header("Authorization", auth)
                    .post(FormBody.Builder().add("link", f.ref).build()).build())).optString("download")
            }
            Debrid.AD.id -> {
                val k = DebridSignIn.adKey() ?: throw IllegalStateException("Sign in to AllDebrid again")
                JSONObject(body(Request.Builder().url("https://api.alldebrid.com/v4/link/unlock?agent=${Debrid.AD_AGENT}").header("Authorization", "Bearer $k")
                    .post(FormBody.Builder().add("link", f.ref).build()).build())).optJSONObject("data")?.optString("link").orEmpty()
            }
            Debrid.PM.id -> JSONObject(body(Request.Builder().url("https://www.premiumize.me/api/item/details?id=${enc(f.ref)}&apikey=${enc(key(i.service.id))}").build())).optString("link")
            Debrid.TB.id -> {
                val k = Debrid.tbKey()
                JSONObject(body(Request.Builder().url("https://api.torbox.app/v1/api/torrents/requestdl?token=${enc(k)}&torrent_id=${i.id}&file_id=${f.ref}").header("Authorization", "Bearer $k").build())).optString("data")
            }
            else -> f.ref
        }.takeIf { it.startsWith("http") } ?: throw IllegalStateException("${i.service.name} gave no link")
    }

    fun size(b: Long): String = when { b <= 0 -> ""; b >= 1_000_000_000 -> "%.1f GB".format(b / 1e9); else -> "${b / 1_000_000} MB" }
}

@Composable
fun CloudScreen(nav: Nav) {
    val services = remember { Cloud.services() }
    var svc by remember { mutableStateOf(services.firstOrNull()) }
    var items by remember { mutableStateOf<List<Cloud.Item>?>(null) }
    var open by remember { mutableStateOf<Cloud.Item?>(null) }
    var files by remember { mutableStateOf<List<Cloud.FileRow>?>(null) }
    var note by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    val fmt = remember { java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()) }
    LaunchedEffect(svc) {
        val s = svc ?: return@LaunchedEffect
        items = null; open = null; files = null; note = ""
        items = runCatching { Cloud.list(s) }.onFailure { note = "${s.name}: ${it.message}" }.getOrDefault(emptyList())
    }
    LaunchedEffect(open) {
        val i = open ?: return@LaunchedEffect
        files = null
        files = runCatching { Cloud.files(i) }.onFailure { note = it.message ?: "Couldn't list the files" }.getOrDefault(emptyList())
    }
    BackHandler(open != null) { open = null; files = null }
    fun play(i: Cloud.Item, f: Cloud.FileRow) = scope.launch {
        note = "Getting the link…"
        runCatching { Cloud.link(i, f) }.fold({ note = ""; nav.push(Screen.Player(it, f.name.substringBeforeLast('.'), type = "vod", name = f.name, addon = i.service.name, bytes = f.bytes)) },
            { note = it.message ?: "No link" })
    }
    Column(Modifier.fillMaxSize().background(LocalLook.current.page).padding(start = 48.dp, end = 40.dp, top = 28.dp)) {
        Text(open?.name ?: "Your debrid cloud", color = Palette.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (services.isEmpty()) {
            Text("No debrid account set up. Settings › Integrations › Links: Real-Debrid, AllDebrid, Premiumize, TorBox or Offcloud.", color = Palette.TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 12.dp))
            return@Column
        }
        if (open == null) Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(services, key = { it.id }) { s ->
                    Focusable({ svc = s }, shape = RoundedCornerShape(50), onFocus = { if (svc != s) svc = s }) { f ->
                        Text(s.name, color = if (f) androidx.compose.ui.graphics.Color.Black else if (s == svc) Palette.Text else Palette.TextDim, fontSize = 15.sp,
                            fontWeight = if (s == svc) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
            }
            Focusable({}, Modifier.padding(start = 20.dp), RoundedCornerShape(50)) { f ->
                BasicTextField(q, { q = it.take(60) }, singleLine = true, textStyle = TextStyle(color = if (f) androidx.compose.ui.graphics.Color.Black else Palette.Text, fontSize = 15.sp),
                    cursorBrush = SolidColor(Palette.Text), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { inner ->
                    if (q.isEmpty()) Text("Search", color = if (f) androidx.compose.ui.graphics.Color.DarkGray else Palette.TextDim, fontSize = 15.sp)
                    inner()
                }
            }
        }
        if (note.isNotEmpty()) Text(note, color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        val words = q.lowercase().split(' ').filter { it.isNotBlank() }
        LazyColumn(Modifier.fillMaxSize().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
            val o = open
            if (o == null) {
                val list = items
                if (list == null) item { Text("Loading…", color = Palette.TextDim, fontSize = 14.sp) }
                else {
                    val shown = list.filter { i -> words.all { it in i.name.lowercase() } }
                    if (shown.isEmpty()) item { Text(if (words.isEmpty()) "Nothing in your ${svc?.name} cloud." else "Nothing matches.", color = Palette.TextDim, fontSize = 14.sp) }
                    items(shown, key = { it.service.id + it.id }) { i ->
                        CloudRow(i.name, listOf(Cloud.size(i.bytes), if (i.added > 0) fmt.format(java.util.Date(i.added)) else "", if (i.ready) "" else "Not ready").filter { it.isNotEmpty() }.joinToString(" · "),
                            if (i == shown.first()) Modifier.focusRequester(first) else Modifier) {
                            if (!i.ready) note = "Still downloading in ${i.service.name}"
                            else if (i.single != null) play(i, Cloud.FileRow(i.name.substringAfterLast('/'), i.bytes, i.single)) else open = i
                        }
                    }
                }
            } else {
                val fl = files
                if (fl == null) item { Text("Loading files…", color = Palette.TextDim, fontSize = 14.sp) }
                else {
                    if (fl.isEmpty()) item { Text("No video files.", color = Palette.TextDim, fontSize = 14.sp) }
                    items(fl, key = { it.ref }) { f -> CloudRow(f.name, Cloud.size(f.bytes), if (f == fl.first()) Modifier.focusRequester(first) else Modifier) { play(o, f) } }
                }
            }
        }
        LaunchedEffect(open, files != null) { if (open != null && files != null) runCatching { first.requestFocus() } }
    }
}

@Composable
private fun CloudRow(title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    Focusable(onClick, modifier.fillMaxWidth(), RoundedCornerShape(10.dp)) { f ->
        Column(Modifier.fillMaxWidth().background(if (f) Palette.Text else Palette.Card).padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(title, color = if (f) androidx.compose.ui.graphics.Color.Black else Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, color = if (f) androidx.compose.ui.graphics.Color.DarkGray else Palette.TextDim, fontSize = 13.sp)
        }
    }
}

