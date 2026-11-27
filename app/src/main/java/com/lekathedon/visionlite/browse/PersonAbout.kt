// A person's short wiki at the top of their page (user 2026-11-24): photo, what they do, born / died, awards, a few
// lines of IMDb's biography; "Biography" opens all of it. Same IMDb request as the page's rows.
package com.lekathedon.visionlite.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.lekathedon.visionlite.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lekathedon.visionlite.home.People
import com.lekathedon.visionlite.ui.Button
import com.lekathedon.visionlite.ui.DisplayText
import com.lekathedon.visionlite.ui.Eyebrow
import com.lekathedon.visionlite.ui.Gold
import com.lekathedon.visionlite.ui.Img
import com.lekathedon.visionlite.ui.Kit
import com.lekathedon.visionlite.ui.Palette
import kotlinx.coroutines.launch

/** The page's head: photo left, then jobs, name, born / died, awards, three lines of biography, Follow and Biography. */
@Composable
fun PersonAbout(name: String, roles: People.Roles?, following: Boolean, first: FocusRequester, bioFocus: FocusRequester, onFollow: () -> Unit, onBio: () -> Unit) {
    val a = roles?.about
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        // Small on purpose (size rule): 110 × 150.
        Box(Modifier.size(110.dp, 150.dp).clip(RoundedCornerShape(Kit.rowR)).background(Kit.card2), contentAlignment = Alignment.Center) {
            val photo = a?.photo ?: roles?.person?.photo
            if (photo != null) Img(photo, 110.dp, 150.dp, Modifier.fillMaxSize())
            else Text(name.take(1), color = Palette.Text, fontSize = 34.sp)
        }
        Column(Modifier.padding(start = 18.dp).weight(1f)) {
            Eyebrow(a?.jobs?.joinToString(" · ")?.uppercase()?.ifEmpty { null } ?: if (roles == null) "LOADING…" else "PERSON")
            DisplayText(name, 28.sp, Modifier.padding(top = 2.dp, bottom = 4.dp), maxLines = 1)
            a?.born?.let { Text("Born $it", color = Palette.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            a?.died?.let { Text("Died $it", color = Palette.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            a?.height?.let { Text("Height $it", color = Palette.TextDim, fontSize = 12.sp, maxLines = 1) }
            if (a != null && (a.prestige != null || a.awards != null)) Row(Modifier.padding(top = 3.dp)) {
                a.prestige?.let { Text(it, color = Gold, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
                a.awards?.let { Text((if (a.prestige != null) "  ·  " else "") + it, color = Palette.TextDim, fontSize = 13.sp, maxLines = 1) }
            }
            a?.bio?.let { Text(it, color = Palette.Text.copy(alpha = 0.85f), fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp).fillMaxWidth(0.85f)) }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(if (following) "Following ✓" else "Follow", onFollow, Modifier.focusRequester(first))
                if (a?.bio != null) Button("Biography", onBio, Modifier.focusRequester(bioFocus))
            }
        }
    }
}

/** The whole biography over the page: Up / Down scroll it, Back closes it. */
@Composable
fun BioSheet(name: String, bio: String, onClose: () -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { fr.requestFocus() } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
            Column(Modifier.width(680.dp).fillMaxHeight(0.86f).clip(RoundedCornerShape(Kit.cardR)).background(Kit.panel.copy(alpha = 0.98f)).border(1.dp, Kit.line, RoundedCornerShape(Kit.cardR)).padding(24.dp)) {
                Eyebrow("BIOGRAPHY · IMDb")
                DisplayText(name, 24.sp, Modifier.padding(top = 4.dp, bottom = 10.dp), maxLines = 1)
                Box(Modifier.fillMaxWidth().weight(1f).focusRequester(fr).focusable()
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionDown -> { scope.launch { scroll.animateScrollBy(260f) }; true }
                            Key.DirectionUp -> { scope.launch { scroll.animateScrollBy(-260f) }; true }
                            Key.DirectionLeft, Key.DirectionRight -> true
                            else -> false
                        }
                    }.verticalScroll(scroll)) {
                    Text(bio, color = Palette.Text, fontSize = 15.sp, lineHeight = 22.sp)
                }
                Text("Up / Down to read · Back to close", color = Palette.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}
