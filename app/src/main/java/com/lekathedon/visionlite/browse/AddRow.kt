// "Add to Home" on a tile's page (user 2026-11-24): the whole page as one row, or any one row from inside it.
package com.lekathedon.visionlite.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import com.lekathedon.visionlite.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lekathedon.visionlite.anime.Hubs
import com.lekathedon.visionlite.home.PageRows
import com.lekathedon.visionlite.ui.Button
import com.lekathedon.visionlite.ui.DisplayText
import com.lekathedon.visionlite.ui.Eyebrow
import com.lekathedon.visionlite.ui.Kit
import com.lekathedon.visionlite.ui.Palette

/**
 * The page's "Add to Home" button and what it said last. [kind]/[arg] name the page (see [PageRows]); [media] = the
 * page's Movies / Shows chip, kept in the row. Opens a short list: the whole page, then each of its rows.
 */
@Composable
fun AddToHome(kind: String, arg: String, pageTitle: String, media: String = "all", modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    var said by remember(kind, arg) { mutableStateOf<String?>(null) }
    val btn = remember { FocusRequester() }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Button("Add to Home", { open = true }, Modifier.focusRequester(btn))
        said?.let { Text(it, color = Palette.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp)) }
    }
    if (open) AddRowSheet(kind, arg, pageTitle, media, onPicked = { said = it }) { open = false; runCatching { btn.requestFocus() } }
}

@Composable
private fun AddRowSheet(kind: String, arg: String, pageTitle: String, media: String, onPicked: (String) -> Unit, onClose: () -> Unit) {
    val rails by produceState<List<PageRows.R>?>(null, kind, arg) { value = runCatching { PageRows.rails(kind, arg) }.getOrDefault(emptyList()) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    val mediaLabel = when (media) { "movie" -> " (Movies)"; "series" -> " (Shows)"; else -> "" }
    fun pick(rail: String, name: String) {
        onPicked(PageRows.add(kind, arg, rail, media, name, Hubs.now()))
        onClose()
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
            Column(Modifier.width(420.dp).heightIn(max = 560.dp).clip(RoundedCornerShape(Kit.cardR)).background(Kit.card.copy(alpha = 0.98f)).padding(20.dp)) {
                Eyebrow("ADD TO ${Hubs.now().label.uppercase()} HOME")
                DisplayText(pageTitle, 20.sp, Modifier.padding(top = 4.dp, bottom = 10.dp), maxLines = 1)
                LazyColumn(contentPadding = PaddingValues(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item(key = "all") {
                        Button("The whole page (a mix of its rows)", { pick(PageRows.ALL, pageTitle + mediaLabel) }, Modifier.fillMaxWidth().focusRequester(first))
                    }
                    val list = rails
                    if (list == null) item(key = "wait") { Text("Loading its rows…", color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(6.dp)) }
                    else {
                        if (list.isNotEmpty()) item(key = "or") { Text("Or one row:", color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, start = 4.dp)) }
                        items(list, key = { it.key }) { r ->
                            Button(r.title, { pick(r.key, "$pageTitle · ${r.title}$mediaLabel") }, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}
