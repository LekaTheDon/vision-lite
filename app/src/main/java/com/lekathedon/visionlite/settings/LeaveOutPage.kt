package com.lekathedon.visionlite.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import com.lekathedon.visionlite.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.recs.LeaveOut
import com.lekathedon.visionlite.recs.LeaveOut.Kind
import com.lekathedon.visionlite.ui.Button
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Palette

/**
 * Leave out (Scout's idea, strict): genres, original languages and countries that never show in this profile. OK on
 * one turns it on or off (⊘ = left out). Takes effect at once on every row, tile, page and pick.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LeaveOutPage() {
    val text by App.instance.prefs.leaveOut.flow.collectAsState()
    val lists = remember(text) { LeaveOut.lists() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val sections = listOf(
        Triple(Kind.GENRE, "Genres", LeaveOut.GENRES.map { it to it }),
        Triple(Kind.LANG, "Original languages", LeaveOut.LANGS),
        Triple(Kind.REGION, "Countries (where a title is made first)", LeaveOut.REGIONS)
    )
    LazyColumn(Modifier.fillMaxSize().background(LocalLook.current.page), contentPadding = PaddingValues(48.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Leave out", color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("What you pick here never shows in this profile: not in Home rows, rotating rows, tiles, pages, More like this " +
                "or recommendations, and rows about it (\"Korean Thrillers\", a Horror tile) go too. Search still finds a title " +
                "by name and marks it ⊘ Left out. ⊘ = left out; OK switches.", color = Palette.TextDim, fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
            val n = lists.genres.size + lists.langs.size + lists.regions.size
            if (n > 0) Text("Left out now: " + (lists.genres.map { it } + lists.langs.map { LeaveOut.name(Kind.LANG, it) } +
                lists.regions.map { LeaveOut.name(Kind.REGION, it) }).joinToString(", "), color = Palette.Text, fontSize = 14.sp)
        }
        sections.forEachIndexed { si, (kind, title, opts) ->
            item(key = kind.key) {
                Text(title, color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
                val on = lists.of(kind)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    opts.forEachIndexed { i, (v, name) ->
                        Button((if (v in on) "⊘ " else "") + name, { LeaveOut.toggle(kind, v) },
                            if (si == 0 && i == 0) Modifier.focusRequester(first) else Modifier)
                    }
                }
            }
        }
        if (!lists.empty) item(key = "clear") { Button("Bring everything back", { LeaveOut.clearAll() }, Modifier.padding(top = 12.dp)) }
    }
}
