package com.lekathedon.visionlite.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import com.lekathedon.visionlite.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import com.lekathedon.visionlite.trakt.Resume
import com.lekathedon.visionlite.ui.Nav
import com.lekathedon.visionlite.ui.Screen

/** Hold OK on a card: [onHold] once the press repeats (HoldGuard keeps the release off the menu). */
fun Modifier.onHoldOk(onHold: () -> Unit): Modifier = onPreviewKeyEvent { e ->
    val ok = e.key == Key.DirectionCenter || e.key == Key.Enter
    if (ok && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0) {
        com.lekathedon.visionlite.ui.HoldGuard.held(); onHold(); true
    } else false
}

/** Hold OK on a Continue watching card: play choosing the link yourself, the title's page, or drop it from the row. */
@Composable
fun CwMenu(r: Resume, nav: Nav, onDrop: () -> Unit, onClose: () -> Unit) {
    androidx.activity.compose.BackHandler { onClose() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        com.lekathedon.visionlite.ui.KitPopup(r.label, Modifier.width(380.dp), eyebrow = "Continue watching") {
            com.lekathedon.visionlite.ui.KitMenuItem("Play: choose the link", {
                onClose(); nav.push(Screen.Streams(r.type, r.streamId, r.label, r.imdb, manual = true))
            }, Modifier.fillMaxWidth().focusRequester(first))
            com.lekathedon.visionlite.ui.KitMenuItem(if (r.type == "series") "Show details" else "Movie details", {
                onClose(); nav.push(Screen.Detail(r.type, r.imdb, r.name))
            }, Modifier.fillMaxWidth())
            com.lekathedon.visionlite.ui.KitMenuItem(if (r.type == "series") "Drop this show" else "Drop this movie", { onDrop(); onClose() }, Modifier.fillMaxWidth())
            // Follow: the next season shows in Coming up the day it's dated.
            if (r.type == "series" && r.imdb.startsWith("tt")) {
                val following = remember(r.imdb) { ComingUp.isFollowed(r.imdb) }
                com.lekathedon.visionlite.ui.KitMenuItem(if (following) "Following ✓ (stop)" else "Follow · new seasons in Coming up", {
                    ComingUp.follow(r.imdb, r.name, !following); onClose()
                }, Modifier.fillMaxWidth())
            }
            // Wrong hub: the title moves (and leaves this Continue watching for the other hub's).
            val anime = com.lekathedon.visionlite.anime.AnimeIds.isAnime(r.imdb)
            com.lekathedon.visionlite.ui.KitMenuItem(if (anime) "Move to Films & TV" else "Move to Anime", {
                com.lekathedon.visionlite.anime.AnimeIds.move(r.imdb, !anime); onClose()
            }, Modifier.fillMaxWidth())
            com.lekathedon.visionlite.ui.KitMenuItem("Cancel", { onClose() }, Modifier.fillMaxWidth())
        }
    }
}
