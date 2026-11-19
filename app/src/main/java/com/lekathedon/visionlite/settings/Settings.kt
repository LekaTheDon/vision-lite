package com.lekathedon.visionlite.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.data.Prefs
import com.lekathedon.visionlite.ui.Button
import com.lekathedon.visionlite.ui.Focusable
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Nav
import com.lekathedon.visionlite.ui.Palette
import com.lekathedon.visionlite.ui.Screen
import kotlinx.coroutines.launch

private val Langs = listOf("" to "Any", "en" to "English", "ar" to "Arabic", "fr" to "French", "de" to "German", "es" to "Spanish",
    "it" to "Italian", "pt" to "Portuguese", "ru" to "Russian", "tr" to "Turkish", "sr" to "Serbian", "hr" to "Croatian",
    "hi" to "Hindi", "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese")

/** One group of settings: its name on the left list, its rows on the right. */
private class Group(val name: String, val note: String, val rows: List<@Composable (Modifier) -> Unit>)

/**
 * Settings the WuPlay way: the groups down the left, the chosen group's rows on the right (name left, value right).
 * Focus on a group shows its rows; Right goes in, Left or Back comes out to the group. OK on a row steps its value.
 */
@Composable
fun Settings(nav: Nav) {
    val p = App.instance.prefs
    val scope = rememberCoroutineScope()
    var cleared by remember { mutableStateOf("") }
    val groups = listOf(
        Group("Accounts", "Sign-ins, keys, add-ons", listOf(
            { m -> Link("Integrations and tracking", "Every key and sign-in; who drives each group, who helps", m) { nav.push(Screen.Integrations) } },
            { m -> Link("Trakt", "Continue Watching, Library, scrobbling", m) { nav.push(Screen.Trakt) } },
            { m -> Link("Add-ons", "Install, order, remove", m) { nav.push(Screen.Addons) } },
            { m -> Link("Vision TB", "TorBox-cached links straight from the torrent sites", m) { nav.push(Screen.VisionTb) } },
        )),
        Group("This TV", "Device check", listOf(
            { m -> Link("This TV", "Dolby Vision 7, frame-rate matching, what the TV and soundbar take", m) { nav.push(Screen.ThisTv) } },
        )),
        Group("Playback", "Autoplay, next episode, skips", listOf(
            { m -> Toggle("Smart autoplay", "Off until Vision TB is autoplay safe: plays the right episode, 4K first, then the biggest file", p.autoplay, m) },
            { m -> Toggle("Next episode", "When an episode ends, open the next one's links", p.nextEpisode, m) },
            { m -> Toggle("Next episode popup", "A card at the outro: OK plays the next one", p.nextPopup, m) },
            { m -> Choice("Skip intro and outro", "IntroDB, AniSkip for anime", p.skipIntro, listOf("button" to "Button", "auto" to "Automatic", "off" to "Off"), m) },
            { m -> Toggle("Resume", "Start where you stopped (Trakt progress)", p.resume, m) },
            { m -> Choice("Step back after pause", "Replays a few seconds when you press play again", p.rewindOnResume,
                listOf("0" to "Off", "3" to "3 s", "5" to "5 s", "10" to "10 s"), m) },
        )),
        Group("Audio and subtitles", "Languages, size, style", listOf(
            { m -> Choice("Audio language", "Picked first when the file has it", p.audioLang, Langs, m) },
            { m -> Choice("Second audio language", "If the first isn't there", p.audioLang2, Langs, m) },
            { m -> Choice("Subtitle language", "", p.subLang, Langs, m) },
            { m -> Choice("Second subtitle language", "", p.subLang2, Langs, m) },
            { m -> Choice("Show subtitles", "Foreign only = none when the audio is already your language", p.subsWhen,
                listOf("foreign" to "Foreign audio only", "always" to "Always", "off" to "Off"), m) },
            { m -> Choice("Subtitle size", "", p.subSize, listOf("s" to "Small", "m" to "Medium", "l" to "Large", "xl" to "Extra large"), m) },
            { m -> Choice("Subtitle style", "", p.subStyle, listOf("outline" to "Outline", "white" to "Shadow", "yellow" to "Yellow", "box" to "Black box"), m) },
        )),
        Group("Links", "Sort and filter", listOf(
            { m -> Choice("Sort links", "", p.streamSort, listOf("addon" to "Add-on order", "quality" to "Best quality", "biggest" to "Biggest file", "smallest" to "Smallest file"), m) },
            { m -> Choice("Highest quality", "Links above this are hidden", p.maxQuality, listOf("0" to "Any", "2160" to "4K", "1080" to "1080p", "720" to "720p"), m) },
            { m -> Choice("File size limit", "Links bigger than this are hidden", p.sizeLimit, listOf("0" to "None", "5" to "5 GB", "10" to "10 GB", "20" to "20 GB", "40" to "40 GB", "80" to "80 GB"), m) },
            { m -> Toggle("Hide torrent-only links", "Links that need a debrid add-on to play", p.hideTorrents, m) },
            { m -> Toggle("Hide CAM / TS", "Cinema recordings", p.hideCam, m) },
        )),
        Group("Home", "Look, rows, trailers", listOf(
            { m -> Link("Home rows", "On / off, order, refresh", m) { nav.push(Screen.Rows) } },
            { m -> Choice("Home look", "", p.homeLook, listOf("echo" to "Echo", "classic" to "Classic"), m) },
            { m -> Toggle("OLED black", "Pure black pages instead of the colour tint", p.oledBlack, m) },
            { m -> Choice("Poster size", "", p.posterSize, listOf("s" to "Small", "m" to "Medium", "l" to "Large"), m) },
            { m -> Toggle("Ratings on posters", "IMDb rating when the catalog gives it", p.ratings, m) },
            { m -> Toggle("Clock", "Time in the top bar", p.clock, m) },
            { m -> Toggle("Trailers", "After focus rests on the billboard or an opened poster", p.trailers, m) },
            { m -> Choice("Trailer delay", "", p.trailerDelay, listOf("1.2" to "1.2 s", "5" to "5 s", "10" to "10 s", "20" to "20 s", "30" to "30 s", "60" to "60 s"), m) },
            { m -> Toggle("Trailer sound", "", p.trailerSound, m) },
            { m ->
                var n by remember { mutableStateOf(com.lekathedon.visionlite.home.ComingUp.dropped().size) }
                Link("Dropped from Coming up", if (n == 0) "Hold OK on a Coming up card to drop a show" else "OK brings them all back", m,
                    value = if (n == 0) "None" else "$n shows") { com.lekathedon.visionlite.home.ComingUp.undropAll(); n = 0 }
            },
        )),
        Group("Details page", "Ratings, reviews, links", listOf(
            { m -> Choice("Episode IMDb ratings", "", p.epRatings, listOf("all" to "Show all", "watched" to "Watched only", "off" to "Hide"), m) },
            { m -> Choice("Reviews", "Which reviews show first", p.reviewsSource, listOf("imdb" to "IMDb", "trakt" to "Trakt"), m) },
            { m -> Toggle("Sequel / Prequel", "IMDb's Follows / Followed by", p.sequelPrequel, m) },
            { m -> Toggle("Connections", "Spin-offs, remakes and other versions", p.connections, m) },
        )),
        Group("Recommendations", "Picks engine", listOf(
            { m -> Choice("IMDb minimum", "Nothing rated lower is recommended", p.recMinImdb,
                listOf("0" to "Off", "6" to "6", "6.5" to "6.5", "7" to "7", "7.5" to "7.5", "8" to "8"), m) },
            { m ->
                val pool by com.lekathedon.visionlite.recs.Recs.pool.collectAsState()
                val busy by com.lekathedon.visionlite.recs.Recs.building.collectAsState()
                val status = when {
                    busy -> "Building…"
                    pool == null -> "Not built yet · OK builds now"
                    pool!!.seeds == 0 -> "Nothing to learn from yet"
                    else -> "${pool!!.picks.size} picks from ${pool!!.seeds} titles · OK rebuilds"
                }
                Link("Picks", status, m) { if (!busy) com.lekathedon.visionlite.recs.Recs.rebuildNow() }
            },
            { m -> Link("Start over", "Forgets the picks; keeps More like this / Not for me", m) { com.lekathedon.visionlite.recs.Recs.reset() } },
        )),
        Group("App", "Version ${com.lekathedon.visionlite.BuildConfig.VERSION_NAME}", listOf(
            { m -> Toggle("Ask before leaving", "Back on Home asks first", p.exitConfirm, m) },
            { m -> Link("Clear image cache", "Frees memory and disk; posters load again", m, value = cleared) { scope.launch { cleared = clearImages() } } },
        )),
    )
    var sel by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
    val groupReq = remember { List(groups.size) { FocusRequester() } }
    val paneFirst = remember { FocusRequester() }
    var inPane by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { androidx.compose.runtime.withFrameNanos { }; runCatching { groupReq[sel].requestFocus() } }
    androidx.activity.compose.BackHandler(enabled = inPane) { runCatching { groupReq[sel].requestFocus() } }
    Row(Modifier.fillMaxSize().background(LocalLook.current.page).padding(start = 48.dp, top = 40.dp, end = 48.dp)) {
        Column(Modifier.width(300.dp).fillMaxHeight()) {
            Text("Settings", color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, bottom = 18.dp))
            groups.forEachIndexed { i, g ->
                GroupItem(g, i == sel, Modifier.focusRequester(groupReq[i])
                    .onFocusChanged { if (it.isFocused) sel = i }
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { runCatching { paneFirst.requestFocus() }; true } else false
                    }) { runCatching { paneFirst.requestFocus() } }
            }
        }
        Box(Modifier.padding(start = 28.dp).width(1.dp).fillMaxHeight().background(Color(0x22FFFFFF)))
        val g = groups[sel]
        LazyColumn(
            Modifier.weight(1f).fillMaxHeight().padding(start = 28.dp)
                .onFocusChanged { inPane = it.hasFocus }
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { runCatching { groupReq[sel].requestFocus() }; true } else false
                },
            contentPadding = PaddingValues(bottom = 48.dp)
        ) {
            item(key = "head$sel") {
                Column(Modifier.padding(bottom = 10.dp, top = 6.dp)) {
                    Text(g.name, color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(g.note, color = Palette.TextDim, fontSize = 13.sp)
                }
            }
            g.rows.forEachIndexed { i, row ->
                item(key = "$sel:$i") { row(if (i == 0) Modifier.focusRequester(paneFirst) else Modifier) }
            }
        }
    }
}

@Composable
private fun GroupItem(g: Group, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Focusable(onClick, modifier.padding(vertical = 2.dp).fillMaxWidth(), shape = RoundedCornerShape(10.dp)) { f ->
        Text(
            g.name, color = if (f) Color.Black else if (selected) Palette.Text else Palette.TextDim, fontSize = 16.sp,
            fontWeight = if (selected || f) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.fillMaxWidth().background(if (f) Color.White else if (selected) Color(0x1FFFFFFF) else Color.Transparent)
                .padding(horizontal = 16.dp, vertical = 11.dp)
        )
    }
}

private suspend fun clearImages(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val loader = coil3.SingletonImageLoader.get(App.instance)
    loader.memoryCache?.clear()
    loader.diskCache?.clear()
    "Cleared"
}

@Composable
private fun Link(title: String, sub: String, modifier: Modifier = Modifier, value: String = "›", onClick: () -> Unit) {
    Focusable(onClick, modifier.padding(top = 6.dp).fillMaxWidth(), shape = RoundedCornerShape(10.dp)) { f -> RowText(title, sub, value.ifEmpty { "›" }, f) }
}

@Composable
private fun Toggle(title: String, sub: String, flag: Prefs.Flag, modifier: Modifier = Modifier) {
    val on by flag.flow.collectAsState()
    Focusable({ flag.value = !on }, modifier.padding(top = 6.dp).fillMaxWidth(), shape = RoundedCornerShape(10.dp)) { f -> RowText(title, sub, null, f, switch = on) }
}

@Composable
private fun Choice(title: String, sub: String, pref: Prefs.Text, options: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    val v by pref.flow.collectAsState()
    val i = options.indexOfFirst { it.first == v }.coerceAtLeast(0)
    Focusable({ pref.value = options[(i + 1) % options.size].first }, modifier.padding(top = 6.dp).fillMaxWidth(), shape = RoundedCornerShape(10.dp)) { f ->
        RowText(title, sub, options[i].second, f)
    }
}

/** A settings row: name and note on the left, the value (or a switch) on the right; focused = white card, dark text. */
@Composable
private fun RowText(title: String, sub: String, value: String?, focused: Boolean, switch: Boolean? = null) {
    val fg = if (focused) Color.Black else Palette.Text
    val dim = if (focused) Color(0x99000000) else Palette.TextDim
    Row(Modifier.fillMaxWidth().background(if (focused) Color.White else Color(0x14FFFFFF)).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = fg, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (sub.isNotEmpty()) Text(sub, color = dim, fontSize = 12.sp, maxLines = 2)
        }
        if (switch != null) {
            val track = if (switch) (if (focused) Color.Black else Color.White) else (if (focused) Color(0x33000000) else Color(0x33FFFFFF))
            val knob = if (switch) (if (focused) Color.White else Color.Black) else (if (focused) Color.White else Color(0xFFBDBDBD))
            Box(Modifier.padding(start = 16.dp).size(40.dp, 22.dp).clip(RoundedCornerShape(50)).background(track).padding(3.dp),
                contentAlignment = if (switch) Alignment.CenterEnd else Alignment.CenterStart) {
                Box(Modifier.size(16.dp).clip(RoundedCornerShape(50)).background(knob))
            }
        } else if (value != null) Text(value, color = dim, fontSize = 15.sp, modifier = Modifier.padding(start = 16.dp))
    }
}

@Composable
private fun Title(t: String) = Text(t, color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))

/** Installed add-ons: add by phone (QR) or by URL, move up/down, remove. */
@Composable
fun Addons() {
    val store = App.instance.addons
    val list by store.addons.collectAsState()
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var byPhone by remember { mutableStateOf(false) }
    fun install(u: String) { scope.launch {
        msg = "Installing…"
        msg = runCatching { store.install(u) }.fold({ url = ""; "Installed ${it.manifest.name}" }, { "Could not install: ${it.message}" })
    } }
    LazyColumn(Modifier.fillMaxSize().background(LocalLook.current.page), contentPadding = PaddingValues(48.dp)) {
        item {
            Title("Add-ons")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BasicTextField(
                    url, { url = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Palette.Text, fontSize = 15.sp),
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {}),
                    modifier = Modifier.fillMaxWidth(0.55f).background(Palette.Card).padding(12.dp),
                    decorationBox = { inner -> if (url.isEmpty()) Text("Add-on URL (…/manifest.json)", color = Palette.TextDim, fontSize = 15.sp); inner() }
                )
                Button("Install", { install(url) })
                Button("Add by phone", { byPhone = true })
            }
            if (byPhone) com.lekathedon.visionlite.phone.PhoneBox("Add an add-on",
                listOf(com.lekathedon.visionlite.phone.PhoneForm.Field("Add-on URL", "https://…/manifest.json", note = "Any Stremio add-on or catalog list (IMDb, Trakt, MDBList…); stremio:// links work too")),
                onSave = { v -> byPhone = false; v.firstOrNull()?.takeIf { it.isNotBlank() }?.let { install(it) } },
                onCancel = { byPhone = false })
            if (msg.isNotEmpty()) Text(msg, color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        }
        items(list, key = { it.base }) { a ->
            Row(Modifier.padding(top = 12.dp).fillMaxWidth(0.85f).background(Palette.Card).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("${a.manifest.name} ${a.manifest.version}", color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(a.base.substringBefore("/", a.base).let { a.base.removePrefix("https://").substringBefore('/') }, color = Palette.TextDim, fontSize = 12.sp)
                }
                Button("Up", { store.move(a.base, -1) })
                Button("Down", { store.move(a.base, 1) })
                Button("Remove", { store.remove(a.base) })
            }
        }
    }
}
