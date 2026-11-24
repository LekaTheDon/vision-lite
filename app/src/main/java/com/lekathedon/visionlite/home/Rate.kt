// Rate on Trakt (user 2026-11-24): hold OK on any title › Rate on Trakt, and (Settings › Recommendations, on by
// default) a prompt after you finish a film, or the first episode you finish of a show you haven't rated. The rating
// goes to Trakt (the engine reads Trakt ratings as taste) and steers the engine at once: 8-10 = Love, 6-7 = Like,
// 1-4 = Not for me, 5 = neutral. Cost: one Trakt request per rating; nothing when Trakt isn't signed in.
package com.lekathedon.visionlite.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.recs.Recs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

object Rate {
    data class Ask(val imdb: String, val tv: Boolean, val name: String, val afterWatching: Boolean)
    internal val open = MutableStateFlow<Ask?>(null)
    private val sp get() = App.instance.prefs.sp

    fun available() = App.instance.trakt.signedIn()
    fun mine(imdb: String): Int = sp.getInt("my_rating_$imdb", 0)

    /** Hold menu › Rate on Trakt. */
    fun ask(imdb: String, tv: Boolean, name: String) { if (available() && imdb.startsWith("tt")) open.value = Ask(imdb, tv, name, false) }

    /** After the player closes on a finished title (90 %+): films always, a show once until it's rated or skipped. */
    fun afterWatching(imdb: String, tv: Boolean, name: String) {
        if (!App.instance.prefs.rateAfter.value || !available() || !imdb.startsWith("tt") || mine(imdb) > 0) return
        if (tv && sp.getBoolean("rate_skip_$imdb", false)) return
        open.value = Ask(imdb, tv, name, true)
    }

    fun skip(a: Ask) { if (a.tv) sp.edit().putBoolean("rate_skip_${a.imdb}", true).apply() }

    fun set(a: Ask, rating: Int) {
        sp.edit().putInt("my_rating_${a.imdb}", rating).apply()
        // The engine hears it at once; Trakt's copy steers the next build too.
        when {
            rating >= 8 -> Recs.love(a.imdb, a.tv, a.name)
            rating in 6..7 -> Recs.moreLikeThis(a.imdb, a.tv, a.name)
            rating in 1..4 -> Recs.notForMe(a.imdb, a.tv, a.name)
            rating == 0 -> Recs.forget(a.imdb)
        }
        TasteMenu.scope.launch {
            val ok = App.instance.trakt.rate(a.imdb, a.tv, rating)
            android.util.Log.i("Rate", "${a.imdb} -> $rating on Trakt: $ok")
            if (ok) Recs.rebuildNow()
        }
    }
}

/** Drawn once at the app's root, like the hold menu. */
@Composable
fun RateHost() {
    val a by Rate.open.collectAsState()
    val cur = a ?: return
    val close = { Rate.open.value = null }
    val first = remember { FocusRequester() }
    var done by remember(cur) { mutableStateOf<String?>(null) }
    LaunchedEffect(cur) { withFrameNanos { }; runCatching { first.requestFocus() } }
    LaunchedEffect(done) { if (done != null) { kotlinx.coroutines.delay(900); close() } }
    Dialog(onDismissRequest = { if (cur.afterWatching) Rate.skip(cur); close() }) {
        Column(Modifier.width(560.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF1C1C1F)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (cur.afterWatching) "How was ${cur.name}?" else "Rate ${cur.name}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(done ?: "Goes to Trakt and steers your recommendations. 8-10 = loved, 6-7 = liked, 1-4 = not for you.", color = Color(0x99FFFFFF), fontSize = 13.sp)
            val current = Rate.mine(cur.imdb)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..10).forEach { n ->
                    com.lekathedon.visionlite.ui.Button(if (n == current) "$n ✓" else "$n", {
                        Rate.set(cur, n); done = "Saved: $n/10 on Trakt"
                    }, if (n == (if (current > 0) current else 8)) Modifier.focusRequester(first) else Modifier)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (current > 0) com.lekathedon.visionlite.ui.Button("Remove rating", { Rate.set(cur, 0); done = "Rating removed" })
                com.lekathedon.visionlite.ui.Button(if (cur.afterWatching) "Not now" else "Cancel", { if (cur.afterWatching) Rate.skip(cur); close() })
            }
        }
    }
}
