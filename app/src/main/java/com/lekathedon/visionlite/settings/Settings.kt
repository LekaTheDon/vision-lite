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

/** All settings on one list; OK on a choice steps to its next value (no dialogs). Each one here does something. */
@Composable
fun Settings(nav: Nav) {
    val p = App.instance.prefs
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val scope = rememberCoroutineScope()
    var cleared by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().background(LocalLook.current.page), contentPadding = PaddingValues(48.dp)) {
        item { Title("Settings") }
        item { Link("This TV", "Device check: Dolby Vision 7, frame-rate matching, what the TV and soundbar take", Modifier.focusRequester(first)) { nav.push(Screen.ThisTv) } }
        item { Link("Add-ons", "Install, order, remove") { nav.push(Screen.Addons) } }
        item { Link("Integrations and tracking", "Every key and sign-in; who drives each group, who helps") { nav.push(Screen.Integrations) } }
        item { Link("Trakt", "Connect by QR: Continue Watching, Library, scrobbling") { nav.push(Screen.Trakt) } }
        item { Link("Vision TB", "TorBox-cached links straight from the torrent sites, first in the list") { nav.push(Screen.VisionTb) } }

        item { Section("Playback") }
        item { Toggle("Smart autoplay", "Off until Vision TB is autoplay safe. On: after every add-on and Vision TB answer, plays the right episode in the best quality (4K first), then the biggest file", p.autoplay) }
        item { Toggle("Next episode", "When an episode ends (or the popup's 10 s run out), open the next one's links", p.nextEpisode) }
        item { Toggle("Next episode popup", "A card at the outro (or the last 40 s): OK plays the next one", p.nextPopup) }
        item { Choice("Skip intro and outro", "IntroDB, AniSkip for anime (Vision's sources)", p.skipIntro, listOf("button" to "Button (OK skips)", "auto" to "Automatic", "off" to "Off")) }
        item { Toggle("Resume", "Start where you stopped (Trakt progress)", p.resume) }
        item { Choice("Step back after pause", "Replays a few seconds when you press play again", p.rewindOnResume,
            listOf("0" to "Off", "3" to "3 s", "5" to "5 s", "10" to "10 s")) }

        item { Section("Audio and subtitles") }
        item { Choice("Audio language", "Picked first when the file has it", p.audioLang, Langs) }
        item { Choice("Second audio language", "If the first isn't there", p.audioLang2, Langs) }
        item { Choice("Subtitle language", "", p.subLang, Langs) }
        item { Choice("Second subtitle language", "", p.subLang2, Langs) }
        item { Choice("Show subtitles", "Foreign audio only = no subtitles when the audio is already in your language", p.subsWhen,
            listOf("foreign" to "Foreign audio only", "always" to "Always", "off" to "Off")) }
        item { Choice("Subtitle size", "", p.subSize, listOf("s" to "Small", "m" to "Medium", "l" to "Large", "xl" to "Extra large")) }
        item { Choice("Subtitle style", "", p.subStyle, listOf("outline" to "White, outline", "white" to "White, shadow", "yellow" to "Yellow", "box" to "White on black box")) }

        item { Section("Links") }
        item { Choice("Sort links", "", p.streamSort, listOf("addon" to "Add-on order", "quality" to "Best quality first", "biggest" to "Biggest file first", "smallest" to "Smallest file first")) }
        item { Choice("Highest quality", "Links above this are hidden", p.maxQuality, listOf("0" to "Any", "2160" to "4K", "1080" to "1080p", "720" to "720p")) }
        item { Choice("File size limit", "Links bigger than this are hidden", p.sizeLimit, listOf("0" to "None", "5" to "5 GB", "10" to "10 GB", "20" to "20 GB", "40" to "40 GB", "80" to "80 GB")) }
        item { Toggle("Hide torrent-only links", "Links that need a debrid add-on to play", p.hideTorrents) }
        item { Toggle("Hide CAM / TS", "Cinema recordings", p.hideCam) }

        item { Section("Home") }
        item { Toggle("OLED black", "Pure black pages instead of the colour tint", p.oledBlack) }
        item { Choice("Home look", "", p.homeLook, listOf("echo" to "Echo", "classic" to "Classic")) }
        item { Toggle("Trailers", "Play after focus rests on the billboard or an opened poster", p.trailers) }
        item { Choice("Trailer delay", "", p.trailerDelay, listOf("1.2" to "1.2 s", "5" to "5 s", "10" to "10 s", "20" to "20 s", "30" to "30 s", "60" to "60 s")) }
        item { Toggle("Trailer sound", "", p.trailerSound) }
        item { Choice("Poster size", "", p.posterSize, listOf("s" to "Small", "m" to "Medium", "l" to "Large")) }
        item { Toggle("Clock", "Time in the top bar", p.clock) }
        item { Toggle("Ratings on posters", "IMDb rating when the catalog gives it", p.ratings) }
        item { Link("Home rows", "On / off, order, refresh") { nav.push(Screen.Rows) } }

        item { Section("Details page") }
        item { Choice("Episode IMDb ratings", "", p.epRatings, listOf("all" to "Show all", "watched" to "Hide on episodes not watched yet", "off" to "Hide")) }
        item { Choice("Reviews", "Which reviews the page shows first", p.reviewsSource, listOf("imdb" to "IMDb", "trakt" to "Trakt")) }
        item { Toggle("Sequel / Prequel", "IMDb's Follows / Followed by", p.sequelPrequel) }
        item { Toggle("Connections", "Spin-offs, remakes and other versions (IMDb)", p.connections) }

        item { Section("Recommendations") }
        item { Choice("IMDb minimum", "Nothing rated lower is recommended (unrated titles stay)", p.recMinImdb,
            listOf("0" to "Off", "6" to "6", "6.5" to "6.5", "7" to "7", "7.5" to "7.5", "8" to "8")) }
        item {
            val pool by com.lekathedon.visionlite.recs.Recs.pool.collectAsState()
            val busy by com.lekathedon.visionlite.recs.Recs.building.collectAsState()
            val status = when {
                busy -> "Building…"
                pool == null -> "Not built yet · OK builds now"
                pool!!.seeds == 0 -> "Nothing to learn from yet (connect Trakt, save titles or mark picks)"
                else -> "${pool!!.picks.size} picks from ${pool!!.seeds} titles, built ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(pool!!.builtAt))} · OK rebuilds"
            }
            Link("Picks", status) { if (!busy) com.lekathedon.visionlite.recs.Recs.rebuildNow() }
        }
        item { Link("Start over", "Forgets the picks and cached facts; keeps More like this / Not for me") { com.lekathedon.visionlite.recs.Recs.reset() } }

        item { Section("App") }
        item { Toggle("Ask before leaving", "Back on Home asks first", p.exitConfirm) }
        item { Link("Clear image cache", cleared.ifEmpty { "Frees memory and disk; posters load again" }) {
            scope.launch { cleared = clearImages() }
        } }
        item {
            Text("Vision Lite ${com.lekathedon.visionlite.BuildConfig.VERSION_NAME}", color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 18.dp))
        }
    }
}

private suspend fun clearImages(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val loader = coil3.SingletonImageLoader.get(App.instance)
    loader.memoryCache?.clear()
    loader.diskCache?.clear()
    "Cleared"
}

@Composable
private fun Section(t: String) = Text(t, color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 26.dp, bottom = 2.dp))

@Composable
private fun Link(title: String, sub: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Focusable(onClick, modifier.padding(top = 10.dp).fillMaxWidth(0.6f)) { f -> RowText(title, sub, f) }
}

@Composable
private fun Toggle(title: String, sub: String, flag: Prefs.Flag, modifier: Modifier = Modifier) {
    val on by flag.flow.collectAsState()
    Focusable({ flag.value = !on }, modifier.padding(top = 10.dp).fillMaxWidth(0.6f)) { f -> RowText("$title: ${if (on) "On" else "Off"}", sub, f) }
}

@Composable
private fun Choice(title: String, sub: String, pref: Prefs.Text, options: List<Pair<String, String>>) {
    val v by pref.flow.collectAsState()
    val i = options.indexOfFirst { it.first == v }.coerceAtLeast(0)
    Focusable({ pref.value = options[(i + 1) % options.size].first }, Modifier.padding(top = 10.dp).fillMaxWidth(0.6f)) { f ->
        RowText("$title: ${options[i].second}", sub, f)
    }
}

@Composable
private fun RowText(title: String, sub: String, focused: Boolean) {
    Column(Modifier.fillMaxWidth().background(if (focused) Color(0x33FFFFFF) else Palette.Card).padding(14.dp)) {
        Text(title, color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        if (sub.isNotEmpty()) Text(sub, color = Palette.TextDim, fontSize = 13.sp)
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
