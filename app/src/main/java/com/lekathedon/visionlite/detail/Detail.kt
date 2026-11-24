package com.lekathedon.visionlite.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lekathedon.visionlite.App
import com.lekathedon.visionlite.addons.Meta
import com.lekathedon.visionlite.library.Saved
import com.lekathedon.visionlite.library.Saves
import com.lekathedon.visionlite.ui.ArtTint
import com.lekathedon.visionlite.ui.Button
import com.lekathedon.visionlite.ui.Focusable
import com.lekathedon.visionlite.ui.Img
import com.lekathedon.visionlite.ui.spoiler
import com.lekathedon.visionlite.ui.LocalLook
import com.lekathedon.visionlite.ui.Nav
import com.lekathedon.visionlite.ui.Palette
import com.lekathedon.visionlite.ui.Screen
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ImdbYellow = Color(0xFFF5C518)

/** One episode on the page: the add-on's art and words where it has them, IMDb's rating (and the rest when it doesn't). */
private data class Ep(val season: Int, val episode: Int, val videoId: String, val title: String?, val plot: String?, val image: String?,
                      val airedMs: Long?, val rating: Float?, val imdbId: String?)

private sealed interface Sheet {
    data class Links(val title: String, val groups: List<Pair<String, List<Imdb.Ref>>>) : Sheet
    data class Text(val title: String, val body: String) : Sheet
}

/**
 * A title, Vision's details page the Lite way (one screen, no tabs): billboard with logo, IMDb rating + votes, Metacritic,
 * Trakt, certificate, parental guide; Play (next episode on series, from Trakt), Trailer, Library, Sequel/Prequel and
 * Connections (IMDb); then Episodes (IMDb rating on each, spoiler rule in Settings), Ratings (every season at a
 * glance), Cast, More like this (IMDb), Reviews (IMDb or Trakt) and Parental guide.
 *
 * Cost: the add-on's meta + one IMDb request (+ the episode list for series) + Trakt rating / progress when signed in;
 * reviews only once their row is reached. All in memory; nothing is fetched before the page opens.
 */
@Composable
fun Detail(nav: Nav, s: Screen.Detail) {
    val prefs = App.instance.prefs
    var meta by remember(s.id) { mutableStateOf<Meta?>(null) }
    var imdb by remember(s.id) { mutableStateOf<Imdb.Title?>(null) }
    var eps by remember(s.id) { mutableStateOf<List<Ep>>(emptyList()) }
    var traktRating by remember(s.id) { mutableStateOf<Double?>(null) }
    var progress by remember(s.id) { mutableStateOf<com.lekathedon.visionlite.trakt.Trakt.Progress?>(null) }
    var loaded by remember(s.id) { mutableStateOf(false) }
    val imdbId = s.id.substringBefore(':').takeIf { Imdb.isId(it) }

    LaunchedEffect(s.id) {
        coroutineScope {
            val m = async { runCatching { App.instance.client.meta(s.type, s.id) }.getOrNull()
                ?: if (AnimeMeta.isAnimeId(s.id)) AnimeMeta.meta(s.id, s.type) else null }
            val t = async { imdbId?.let { Imdb.title(it) } }
            val e = async { if (imdbId != null && s.type == "series") Imdb.episodes(imdbId) else emptyList() }
            launch { traktRating = imdbId?.let { App.instance.trakt.rating(it, s.type == "series") }?.rating?.takeIf { it > 0 } }
            launch { if (imdbId != null && s.type == "series") progress = App.instance.trakt.progress(imdbId) }
            meta = m.await(); imdb = t.await(); loaded = true
            eps = merge(meta, e.await(), imdbId ?: s.id)
        }
    }

    val look = LocalLook.current
    val m = meta
    val t = imdb
    val name = m?.name?.ifBlank { null } ?: t?.name?.ifBlank { null } ?: s.name
    val backdrop = m?.background ?: imdbId?.let { "https://images.metahub.space/background/large/$it/img" }
    val logo = m?.logo ?: imdbId?.let { "https://images.metahub.space/logo/medium/$it/img" }
    val isSeries = if (m != null && t == null && AnimeMeta.isAnimeId(s.id)) m.type == "series" else s.type == "series" || t?.type == "series"
    var sheet by remember(s.id) { mutableStateOf<Sheet?>(null) }

    Box(Modifier.fillMaxSize().background(look.page)) {
        if (!look.oled) ArtTint(backdrop ?: m?.poster)
        if (m == null && t == null) {
            Text(if (loaded) "No details for ${s.name}" else s.name, color = Palette.Text, fontSize = 24.sp, modifier = Modifier.padding(48.dp))
            return@Box
        }
        val watched = remember(progress) {
            progress?.seasons?.flatMap { ss -> ss.episodes.filter { it.completed }.map { ss.number to it.number } }?.toSet() ?: emptySet()
        }
        val epRule by prefs.epRatings.flow.collectAsState()
        val blurOn by prefs.blurUnwatched.flow.collectAsState()
        fun showRating(e: Ep) = when (epRule) { "off" -> false; "watched" -> (e.season to e.episode) in watched; else -> true }

        val seasons = remember(eps) { eps.map { it.season }.distinct().sorted().let { l -> if (l.size > 1) l.filter { it != 0 } else l } }
        val next = progress?.next
        var season by remember(s.id, seasons, next) { mutableStateOf(next?.season?.takeIf { it in seasons } ?: seasons.firstOrNull() ?: 1) }
        val list = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val play = remember { FocusRequester() }
        var heroFocused by remember { mutableStateOf(false) }
        LaunchedEffect(s.id, m != null || t != null) { withFrameNanos { }; runCatching { play.requestFocus() } }

        // Trailer (Vision's IMDb source) on the billboard after the Home trailer delay, while focus rests in the hero.
        val trailersOn by prefs.trailers.flow.collectAsState()
        val sound by prefs.trailerSound.flow.collectAsState()
        val delayS by prefs.trailerDelay.flow.collectAsState()
        var trailer by remember(s.id) { mutableStateOf<com.lekathedon.visionlite.trailer.Trailer?>(null) }
        var trailerNow by remember(s.id) { mutableStateOf(false) }
        LaunchedEffect(heroFocused, trailerNow, trailersOn, imdbId) {
            if (!heroFocused && !trailerNow) { trailer = null; return@LaunchedEffect }
            if (!trailersOn && !trailerNow) return@LaunchedEffect
            if (!trailerNow) delay(((delayS.toDoubleOrNull() ?: 5.0) * 1000).toLong())
            // Vision's order: IMDb, then TMDB, then YouTube.
            trailer = com.lekathedon.visionlite.trailer.TrailerChain.find(com.lekathedon.visionlite.addons.MetaPreview(imdbId ?: s.id, s.type, s.name),
                com.lekathedon.visionlite.device.Tier.trailerHeight(2160))
        }

        Box(Modifier.fillMaxWidth().height(460.dp)) {
            Img(backdrop, 960.dp, 540.dp, Modifier.fillMaxSize())
            trailer?.let { com.lekathedon.visionlite.trailer.TrailerVideo(it.url, !sound, Modifier.fillMaxSize(), it.audio) { trailer = null; trailerNow = false } }
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xF0000000), 0.42f to Color(0x99000000), 0.75f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to look.page)))
        }

        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 120.dp)) {
            // ---------------------------------------------------------------- billboard
            item(key = "hero") {
                Column(Modifier.width(600.dp).heightIn(min = 380.dp)) {
                    var logoFailed by remember(logo) { mutableStateOf(logo == null) }
                    if (!logoFailed) coil3.compose.AsyncImage(logo, name, Modifier.size(340.dp, 110.dp), contentScale = ContentScale.Fit,
                        alignment = Alignment.BottomStart, onError = { logoFailed = true })
                    else Text(name, color = Palette.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                    Facts(m, t, isSeries)
                    val more by androidx.compose.runtime.produceState(MoreRatings.R(), imdbId) { imdbId?.let { value = MoreRatings.of(it, isSeries) } }
                    Ratings(t, traktRating, more)
                    val plot = m?.description ?: t?.plot
                    plot?.let { Text(it, color = Palette.Text.copy(alpha = 0.85f), fontSize = 14.sp, lineHeight = 20.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp)) }
                    val crew = t?.let { (if (isSeries) it.creators else it.directors).take(2).joinToString(", ") { p -> p.name } }.orEmpty()
                    if (crew.isNotBlank()) Text((if (isSeries) "Created by " else "Directed by ") + crew, color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    val stars = t?.cast?.take(4)?.joinToString(", ") { it.name }?.ifBlank { null } ?: m?.cast?.take(4)?.joinToString(", ")?.ifBlank { null }
                    stars?.let { Text("Starring $it", color = Palette.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
                    GuideChips(t?.guide.orEmpty())

                    // Back up at the buttons: the whole billboard shows again (logo, facts, plot), not just the buttons' line.
                    // Vision's hero actions: accent Play pill, round icon buttons, Sequel / Connections pills with a yellow
                    // count; the row scrolls sideways instead of squeezing the last button (user 2026-11-22).
                    Row(Modifier.padding(top = 18.dp).wrapContentWidth(Alignment.Start, unbounded = true).widthIn(max = 1100.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState()).onFocusChanged {
                        heroFocused = it.hasFocus
                        if (it.hasFocus && (list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0)) scope.launch { list.animateScrollToItem(0) }
                    }, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        val heroMod = Modifier
                        val playLabel = when {
                            !isSeries -> "Play"
                            next != null -> "Play S${next.season} E${next.number}"
                            else -> eps.firstOrNull { it.season == seasons.firstOrNull() }?.let { "Play S${it.season} E${it.episode}" } ?: "Play"
                        }
                        PlayPill(playLabel, {
                            if (!isSeries) nav.push(Screen.Streams(s.type, if (AnimeMeta.isAnimeId(s.id)) m?.id ?: s.id else s.id, name, s.id))
                            else {
                                val e = (next?.let { n -> eps.firstOrNull { it.season == n.season && it.episode == n.number } }
                                    ?: eps.firstOrNull { it.season == seasons.firstOrNull() })
                                    ?.takeIf { it.airedMs == null || it.airedMs <= System.currentTimeMillis() }
                                e?.let { nav.push(Screen.Streams("series", it.videoId, "$name S${it.season}E${it.episode}", s.id)) }
                            }
                        }, heroMod.focusRequester(play))
                        if (imdbId != null) RoundAction(com.lekathedon.visionlite.ui.Icons.Theaters, if (trailer != null) "Stop trailer" else "Trailer", trailer != null, {
                            if (trailer != null) { trailer = null; trailerNow = false } else trailerNow = true
                        }, heroMod)
                        var saved by remember(s.id) { mutableStateOf(Saves.has(s.id)) }
                        RoundAction(if (saved) com.lekathedon.visionlite.ui.Icons.Check else com.lekathedon.visionlite.ui.Icons.Bookmark, if (saved) "In Library" else "Add to Library", saved, {
                            saved = Saves.toggle(Saved(s.type, s.id, name, m?.poster ?: t?.poster))
                        }, heroMod)
                        // Love it / Like / Not for me: the one engine behind every recommendation.
                        val rc by com.lekathedon.visionlite.recs.Recs.changed.collectAsState()
                        val verdict = remember(s.id, rc) { com.lekathedon.visionlite.recs.Recs.verdict(s.id) }
                        RoundAction(com.lekathedon.visionlite.ui.Icons.Favorite, when (verdict) { "love" -> "Loved"; "like" -> "Liked"; "nope" -> "Not for me"; "watched" -> "Watched"; else -> "Like" }, verdict != null, {
                            com.lekathedon.visionlite.home.TasteMenu.show(com.lekathedon.visionlite.addons.MetaPreview(s.id, s.type, name, m?.poster ?: t?.poster))
                        }, heroMod)
                        val sp by prefs.sequelPrequel.flow.collectAsState()
                        val cn by prefs.connections.flow.collectAsState()
                        if (sp && t != null && t.sequels.isNotEmpty()) CountPill(com.lekathedon.visionlite.ui.Icons.Swap, "Sequel / Prequel", t.sequels.size, {
                            sheet = Sheet.Links("Sequel / Prequel", listOf("Follows" to t.follows, "Followed by" to t.followedBy).filter { it.second.isNotEmpty() })
                        }, heroMod)
                        if (cn && t != null && t.connections.isNotEmpty()) CountPill(com.lekathedon.visionlite.ui.Icons.AccountTree, "Connections", t.connections.size, {
                            sheet = Sheet.Links("Connections", t.connections.groupBy { it.link!! }.toSortedMap().map { (l, v) -> l.heading to v })
                        }, heroMod)
                    }
                }
            }

            // ---------------------------------------------------------------- episodes
            if (isSeries && eps.isNotEmpty()) {
                item(key = "seasons") {
                    LazyRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(seasons) { n ->
                            Focusable({ season = n }, shape = RoundedCornerShape(50), onFocus = { season = n }) { f ->
                                Text(if (n == 0) "Specials" else "Season $n", color = if (f) Color.Black else Palette.Text, fontSize = 14.sp,
                                    fontWeight = if (n == season) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.background(if (f) Color.White else if (n == season) Color(0x44FFFFFF) else Color(0x1AFFFFFF)).padding(horizontal = 16.dp, vertical = 7.dp))
                            }
                        }
                    }
                }
                item(key = "episodes") {
                    val inSeason = remember(eps, season) { eps.filter { it.season == season } }
                    var focusedEp by remember(season) { mutableStateOf<Ep?>(null) }
                    val epRow = rememberLazyListState()
                    LaunchedEffect(season, next) {
                        val i = if (next != null && next.season == season) inSeason.indexOfFirst { it.episode == next.number } else -1
                        if (i > 0) epRow.scrollToItem(i)
                    }
                    Column {
                        LazyRow(Modifier.padding(top = 12.dp), state = epRow, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(inSeason, key = { it.videoId }) { e ->
                                EpisodeCard(e, showRating(e), (e.season to e.episode) in watched, next?.let { it.season == e.season && it.number == e.episode } == true, blurOn,
                                    onFocus = { focusedEp = e }) { nav.push(Screen.Streams("series", e.videoId, "$name S${e.season}E${e.episode}", s.id)) }
                            }
                        }
                        val f = focusedEp ?: inSeason.firstOrNull()
                        f?.let { e ->
                            Column(Modifier.padding(top = 10.dp).width(700.dp).heightIn(min = 64.dp)) {
                                Text("S${e.season} E${e.episode}" + (e.title?.let { " · $it" } ?: "") + (e.airedMs?.let { " · " + date(it) } ?: ""),
                                    color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                e.plot?.let { Text(it, color = Palette.TextDim, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 3.dp).spoiler(blurOn && (e.season to e.episode) !in watched)) }
                            }
                        }
                    }
                }
                if (epRule != "off" && eps.any { it.rating != null }) item(key = "ratings") {
                    Header("Ratings")
                    RatingsGrid(eps.filter { it.season in seasons && it.season > 0 }, ::showRating) { n ->
                        season = n
                        scope.launch { list.animateScrollToItem(1) }
                    }
                }
            }

            // ---------------------------------------------------------------- creators & crew
            // Creator first (IMDb's "Creator"), then directors and writers, one card each with every role they had:
            // open one to Follow them, and their work steers the picks and gets its own "Because you like" row.
            val crew = run {
                val roles = LinkedHashMap<String, Pair<Imdb.Person, MutableList<String>>>()
                fun add(list: List<Imdb.Person>?, role: String) =
                    list.orEmpty().forEach { p -> roles.getOrPut(p.id) { p to mutableListOf() }.second.let { if (role !in it) it += role } }
                add(t?.creators, "Creator"); add(t?.directors, "Director"); add(t?.writers, "Writer")
                roles.values.map { (p, r) -> p.copy(role = r.joinToString(" · ")) }
            }
            if (crew.isNotEmpty()) item(key = "crew") {
                Header("Creators & crew")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(crew, key = { it.id }) { p -> PersonCircle(p) { nav.push(Screen.Person(p.id, p.name)) } }
                }
            }

            // ---------------------------------------------------------------- cast
            val cast = t?.cast.orEmpty()
            if (cast.isNotEmpty()) item(key = "cast") {
                Header("Cast")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(cast, key = { it.id }) { p -> PersonCircle(p) { nav.push(Screen.Person(p.id, p.name)) } }
                }
            }

            // ---------------------------------------------------------------- more like this
            val similar = t?.similar.orEmpty()
            if (similar.isNotEmpty()) item(key = "similar") {
                Header("More like this", "IMDb")
                val metas = remember(similar) { similar.map { r -> com.lekathedon.visionlite.addons.MetaPreview(r.imdb, r.type, r.title, r.poster,
                    releaseInfo = r.year?.toString(), imdbRating = r.rating?.let { "%.1f".format(it) }) } }
                com.lekathedon.visionlite.home.PageRow("similar", "", metas, sub = { m -> listOfNotNull(m.releaseInfo, m.imdbRating?.let { "★ $it" }).joinToString(" · ").ifEmpty { null } }) { m ->
                    nav.push(Screen.Detail(m.type, m.id, m.name))
                }
            }

            // ---------------------------------------------------------------- reviews
            if (imdbId != null) item(key = "reviews") { Reviews(imdbId, isSeries) { sheet = it } }

            // ---------------------------------------------------------------- parental guide
            val guide = t?.guide.orEmpty()
            if (guide.isNotEmpty()) item(key = "guide") {
                Header("Parental guide", "IMDb")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    guide.forEach { g ->
                        Column(Modifier.clip(RoundedCornerShape(10.dp)).background(Palette.Card).padding(horizontal = 14.dp, vertical = 10.dp).width(150.dp)) {
                            Text(g.category, color = Palette.TextDim, fontSize = 12.sp, maxLines = 2)
                            Text(g.severity, color = severityColor(g.severity), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }

        sheet?.let { sh -> SheetView(sh, onClose = { sheet = null }) { r -> sheet = null; nav.push(Screen.Detail(r.type, r.imdb, r.title)) } }
    }
}

/** The add-on's episodes and IMDb's, matched by season + episode: the add-on's art/words first, IMDb's rating always. */
private fun merge(m: Meta?, imdb: List<Imdb.Episode>, seriesId: String): List<Ep> {
    val byNum = imdb.associateBy { it.season to it.episode }
    val fromAddon = m?.videos.orEmpty().mapNotNull { v ->
        val sn = v.season ?: return@mapNotNull null
        val en = v.episode ?: return@mapNotNull null
        val i = byNum[sn to en]
        Ep(sn, en, v.id, v.name ?: v.title ?: i?.title, v.overview ?: v.description ?: i?.plot, v.thumbnail ?: i?.image,
            v.released?.let { r -> runCatching { java.time.Instant.parse(r).toEpochMilli() }.getOrNull() } ?: i?.airedMs, i?.rating, i?.imdb)
    }
    val have = fromAddon.mapTo(HashSet()) { it.season to it.episode }
    val onlyImdb = imdb.filter { (it.season to it.episode) !in have }.map { i ->
        Ep(i.season, i.episode, "$seriesId:${i.season}:${i.episode}", i.title, i.plot, i.image, i.airedMs, i.rating, i.imdb)
    }
    return (fromAddon + onlyImdb).distinctBy { it.season to it.episode }.sortedWith(compareBy({ it.season }, { it.episode }))
}

@Composable
private fun Facts(m: Meta?, t: Imdb.Title?, series: Boolean) {
    val years = t?.year?.let { y -> if (series) "$y–${t.endYear ?: ""}" else "$y" } ?: m?.releaseInfo
    val runtime = t?.runtimeMin?.let { if (it >= 60 && !series) "${it / 60}h ${it % 60}m" else "${it}m" } ?: m?.runtime
    val genres = (t?.genres?.ifEmpty { null } ?: m?.genres.orEmpty()).take(3).joinToString(" · ").ifBlank { null }
    val seasons = t?.seasons?.filter { it > 0 }?.size?.takeIf { series && it > 0 }?.let { if (it == 1) "1 season" else "$it seasons" }
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        t?.certificate?.let {
            Text(it, color = Palette.Text, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(Color(0x33FFFFFF)).padding(horizontal = 5.dp, vertical = 1.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(listOfNotNull(years, seasons, runtime, genres).joinToString("  ·  "), color = Palette.TextDim, fontSize = 14.sp)
        // Leave out: reached from Search, so it says why it shows nowhere else.
        val id = t?.id ?: m?.id
        val loRev by com.lekathedon.visionlite.recs.LeaveOut.revision.collectAsState()
        val why = remember(id, t, loRev) { id?.let { com.lekathedon.visionlite.recs.LeaveOut.why(it, t?.genres ?: m?.genres.orEmpty()) } }
        if (why != null) {
            Spacer(Modifier.width(12.dp))
            Text("⊘ Left out · $why (your settings)", color = Color(0xFFFCA5A5), fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(Color(0x33EF4444)).padding(horizontal = 6.dp, vertical = 1.dp))
        }
    }
}

/** Vision's ratings row: up to 8 sources, each a small mark plus its value; the ones without a value stay out. */
@Composable
private fun Ratings(t: Imdb.Title?, trakt: Double?, more: MoreRatings.R) {
    val chips = buildList<Triple<String, Color, String>> {
        t?.rating?.let { add(Triple("IMDb", ImdbYellow, "%.1f".format(it) + (Imdb.votes(t.votes)?.let { v -> " ($v)" } ?: ""))) }
        more.tmdb?.let { add(Triple("TMDB", Color(0xFF01B4E4), it)) }
        trakt?.let { add(Triple("Trakt", Color(0xFFED1C24), "${(it * 10).toInt()}%")) }
        more.letterboxd?.let { add(Triple("LB", Color(0xFF00C030), "%.1f".format(it))) }
        more.tomatoes?.let { add(Triple(if (it >= 60) "RT 🍅" else "RT", Color(0xFFFA320A), "$it%")) }
        more.audience?.let { add(Triple("RT 🍿", Color(0xFFFFB800), "$it%")) }
        t?.metascore?.let { add(Triple("MC", if (it >= 61) Color(0xFF54A72A) else if (it >= 40) Color(0xFFFFCC33) else Color(0xFFFF0000), "$it")) }
        more.mal?.let { add(Triple("MAL", Color(0xFF2E51A2), "%.2f".format(it))) }
    }
    if (chips.isEmpty()) return
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        chips.forEach { (mark, color, value) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(mark, color = if (color == ImdbYellow || color == Color(0xFFFFB800) || color == Color(0xFFFFCC33)) Color.Black else Color.White,
                    fontSize = 11.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(color).padding(horizontal = 4.dp, vertical = 1.dp))
                Text(" $value", color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Vision's PlayButton: a filled pill, play icon and label; white when focused. */
@Composable
private fun PlayPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    com.lekathedon.visionlite.ui.Focusable(onClick, modifier, RoundedCornerShape(50)) { f ->
        Row(Modifier.height(48.dp).background(if (f) Color.White else Color(0xFFE6E6E6).copy(alpha = 0.92f)).padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(com.lekathedon.visionlite.ui.Icons.Play, null, Modifier.size(24.dp), tint = Color.Black)
            Text(label, color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** Vision's ActionIconButton: round, the icon only; its name shows beside it while focused. [on] tints the icon. */
@Composable
private fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    com.lekathedon.visionlite.ui.Focusable(onClick, modifier, RoundedCornerShape(50)) { f ->
        Row(Modifier.height(48.dp).background(if (f) Color.White else Color(0x2EFFFFFF)).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(icon, label, Modifier.size(24.dp), tint = if (f) Color.Black else if (on) Color(0xFFF5C518) else Color.White)
            if (f) Text(label, color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 8.dp, end = 4.dp))
        }
    }
}

/** Vision's SequelPrequelPill (also Connections): card pill, icon, label, then the count in a yellow circle. */
@Composable
private fun CountPill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    com.lekathedon.visionlite.ui.Focusable(onClick, modifier, RoundedCornerShape(50)) { f ->
        Row(Modifier.height(48.dp).background(if (f) Color.White else Color(0x2EFFFFFF)).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(icon, null, Modifier.size(20.dp), tint = if (f) Color.Black else Color.White)
            Text(label, color = if (f) Color.Black else Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false,
                modifier = Modifier.padding(horizontal = 8.dp))
            Box(Modifier.size(24.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xFFF5C518)), contentAlignment = Alignment.Center) {
                Text("$count", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Vision's ranked parental-guide chips (worst first), "None" left out. */
@Composable
private fun GuideChips(g: List<Imdb.Guide>) {
    val rank = mapOf("severe" to 0, "moderate" to 1, "mild" to 2)
    val shown = g.filter { it.severity.lowercase() in rank }.sortedBy { rank[it.severity.lowercase()] }
    if (shown.isEmpty()) return
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        shown.forEach { x ->
            val short = x.category.substringBefore(" &").substringBefore(",")
            Text("$short · ${x.severity}", color = severityColor(x.severity), fontSize = 11.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x26FFFFFF)).padding(horizontal = 9.dp, vertical = 3.dp))
        }
    }
}

private fun severityColor(s: String) = when (s.lowercase()) {
    "severe" -> Color(0xFFFF6B6B); "moderate" -> Color(0xFFFFC266); "mild" -> Color(0xFFB9E3A0); else -> Color(0xFFBBBBBB)
}

/** A person's round photo, name and role (cast, creators & crew); OK opens their page (Follow is there). */
@Composable
private fun PersonCircle(p: Imdb.Person, open: () -> Unit) {
    Focusable(open, Modifier.width(110.dp), shape = RoundedCornerShape(10.dp)) { f ->
        Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(90.dp).clip(CircleShape).background(Palette.Card), contentAlignment = Alignment.Center) {
                if (p.photo != null) Img(p.photo, 90.dp, 90.dp, Modifier.fillMaxSize())
                else Text(p.name.take(1), color = Palette.Text, fontSize = 30.sp)
            }
            Text(p.name, color = if (f) Color.White else Palette.Text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            p.role?.let { Text(it, color = Palette.TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun Header(t: String, by: String? = null) {
    Row(Modifier.padding(top = 26.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        Text(t, color = Palette.Text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        by?.let { Text("  $it", color = Palette.TextDim, fontSize = 12.sp) }
    }
}

@Composable
private fun EpisodeCard(e: Ep, showRating: Boolean, watched: Boolean, isNext: Boolean, blurOn: Boolean, onFocus: () -> Unit, onClick: () -> Unit) {
    val aired = e.airedMs == null || e.airedMs <= System.currentTimeMillis()
    // Not out yet: nothing to play, so pressing it does nothing (user 2026-11-19).
    Focusable({ if (aired) onClick() }, Modifier.size(260.dp, 146.dp), RoundedCornerShape(10.dp), onFocus) { f ->
        Box(Modifier.fillMaxSize().background(Palette.Card)) {
            Img(e.image, 260.dp, 146.dp, Modifier.fillMaxSize(), blur = blurOn && !watched)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color(0xE6000000))))
            if (!aired) Box(Modifier.fillMaxSize().background(Color(0x99000000)))
            Row(Modifier.align(Alignment.TopStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isNext) Pill("Next", Color.White, Color.Black)
                if (watched) Pill("✓ Watched", Color(0xCC121316), Color.White)
                if (!aired) e.airedMs?.let { Pill(date(it), Color(0xCC121316), Color.White) }
            }
            if (showRating && e.rating != null) Row(Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xCC000000)).padding(horizontal = 5.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("★", color = ImdbYellow, fontSize = 11.sp)
                Text(" %.1f".format(e.rating), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Text("${e.episode}. ${e.title ?: "Episode ${e.episode}"}", color = Color.White, fontSize = 14.sp, fontWeight = if (f) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
        }
    }
}

@Composable
private fun Pill(t: String, bg: Color, fg: Color) =
    Text(t, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 8.dp, vertical = 2.dp))

/** Every season at a glance (Vision's Ratings tab): one row per season, one coloured cell per episode. OK opens the season. */
@Composable
private fun RatingsGrid(eps: List<Ep>, show: (Ep) -> Boolean, onSeason: (Int) -> Unit) {
    val bySeason = remember(eps) { eps.groupBy { it.season }.toSortedMap() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        bySeason.forEach { (n, list) ->
            Focusable({ onSeason(n) }, shape = RoundedCornerShape(6.dp)) { f ->
                Row(Modifier.background(if (f) Color(0x22FFFFFF) else Color.Transparent).padding(4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("S$n", color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.width(34.dp))
                    list.take(26).forEach { e ->
                        val r = e.rating?.takeIf { show(e) }
                        Box(Modifier.size(34.dp, 24.dp).clip(RoundedCornerShape(4.dp)).background(heat(r)), contentAlignment = Alignment.Center) {
                            Text(r?.let { "%.1f".format(it) } ?: "–", color = if (r == null) Palette.TextDim else Color.Black, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    list.mapNotNull { e -> e.rating?.takeIf { show(e) } }.takeIf { it.isNotEmpty() }?.average()?.let {
                        Text("  avg %.1f".format(it), color = Palette.TextDim, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

private fun heat(r: Float?) = when {
    r == null -> Color(0x1AFFFFFF)
    r >= 9f -> Color(0xFF1FAD5B); r >= 8f -> Color(0xFF6FCF6F); r >= 7f -> Color(0xFFE8D45A); r >= 6f -> Color(0xFFF2A54A); else -> Color(0xFFE5654F)
}

@Composable
private fun PosterRow(items: List<Imdb.Ref>, firstFocus: FocusRequester? = null, onOpen: (Imdb.Ref) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items, key = { it.imdb }) { r ->
            val mod = if (firstFocus != null && r == items.first()) Modifier.width(120.dp).focusRequester(firstFocus) else Modifier.width(120.dp)
            Focusable({ onOpen(r) }, mod, RoundedCornerShape(8.dp)) { f ->
                Column {
                    Box(Modifier.size(120.dp, 180.dp).clip(RoundedCornerShape(8.dp)).background(Palette.Card)) {
                        val art = com.lekathedon.visionlite.poster.Posters.poster(r.imdb, r.type, r.poster)
                        if (art != null) Img(art, 120.dp, 180.dp, Modifier.fillMaxSize(), fallback = r.poster)
                        else Text(r.title, color = Palette.Text, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center).padding(8.dp))
                        r.rating?.let {
                            Text("%.1f".format(it), color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(3.dp)).background(ImdbYellow).padding(horizontal = 4.dp, vertical = 1.dp))
                        }
                    }
                    Text(r.title, color = if (f) Color.White else Palette.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
                    r.year?.let { Text("$it", color = Palette.TextDim, fontSize = 11.sp, modifier = Modifier.padding(start = 2.dp)) }
                }
            }
        }
    }
}

/** Reviews: IMDb or Trakt (the chips switch; Settings picks the first), read only when this row is reached. */
@Composable
private fun Reviews(imdbId: String, series: Boolean, open: (Sheet) -> Unit) {
    val prefs = App.instance.prefs
    val src by prefs.reviewsSource.flow.collectAsState()
    data class R(val author: String, val rating: Int?, val summary: String, val text: String, val spoiler: Boolean, val likes: Int, val date: String?)
    var items by remember(imdbId, src) { mutableStateOf<List<R>?>(null) }
    var total by remember(imdbId, src) { mutableStateOf(0) }
    LaunchedEffect(imdbId, src) {
        if (src == "trakt") {
            val c = App.instance.trakt.comments(imdbId, series)
            items = c.map { R(it.user.username.ifBlank { "Trakt user" }, it.stats.rating, "", it.comment, it.spoiler, it.likes, it.createdAt.take(10)) }
            total = c.size
        } else {
            val (l, n) = Imdb.reviews(imdbId)
            items = l.map { R(it.author, it.rating, it.summary, it.text, it.spoiler, it.likes, it.date) }
            total = n
        }
    }
    Row(Modifier.padding(top = 26.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Reviews", color = Palette.Text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        listOf("imdb" to "IMDb", "trakt" to "Trakt").forEach { (k, label) ->
            Focusable({ prefs.reviewsSource.value = k }, shape = RoundedCornerShape(50)) { f ->
                Text(label, color = if (f) Color.Black else Palette.Text, fontSize = 13.sp, fontWeight = if (src == k) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.background(if (f) Color.White else if (src == k) Color(0x44FFFFFF) else Color(0x1AFFFFFF)).padding(horizontal = 14.dp, vertical = 5.dp))
            }
        }
        if (total > 0) Text("$total", color = Palette.TextDim, fontSize = 12.sp)
    }
    val list = items
    when {
        list == null -> Text("Loading…", color = Palette.TextDim, fontSize = 13.sp)
        list.isEmpty() -> Text("No reviews", color = Palette.TextDim, fontSize = 13.sp)
        else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(list) { r ->
                var reveal by remember(r) { mutableStateOf(!r.spoiler) }
                Focusable({ if (!reveal) reveal = true else open(Sheet.Text(r.summary.ifBlank { r.author }, listOfNotNull(r.summary.ifBlank { null }, r.text).joinToString("\n\n"))) },
                    Modifier.size(340.dp, 190.dp), RoundedCornerShape(10.dp)) { f ->
                    Column(Modifier.fillMaxSize().background(if (f) Color(0xFF2A2A30) else Palette.Card).padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(r.author, color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            r.rating?.let { Text("★ $it/10", color = ImdbYellow, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp)) }
                        }
                        Text(listOfNotNull(r.date, if (r.likes > 0) "👍 ${r.likes}" else null).joinToString(" · "), color = Palette.TextDim, fontSize = 11.sp)
                        if (!reveal) Text("Spoiler · OK to read", color = Color(0xFFFFC266), fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp))
                        else {
                            if (r.summary.isNotBlank()) Text(r.summary, color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                            Text(r.text, color = Palette.TextDim, fontSize = 12.sp, lineHeight = 17.sp, maxLines = if (r.summary.isBlank()) 6 else 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Sequel/Prequel and Connections lists (IMDb's headings, oldest first), and a review read in full. */
@Composable
private fun SheetView(sh: Sheet, onClose: () -> Unit, onOpen: (Imdb.Ref) -> Unit) {
    BackHandler { onClose() }
    val first = remember { FocusRequester() }
    LaunchedEffect(sh) { withFrameNanos { }; runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0xD9000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 820.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF1C1C1F)).padding(24.dp)) {
            when (sh) {
                is Sheet.Links -> {
                    Text(sh.title, color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        sh.groups.forEachIndexed { gi, (heading, refs) ->
                            item { Text(heading, color = Palette.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp)) }
                            item { PosterRow(refs, if (gi == 0) first else null, onOpen) }
                        }
                    }
                }
                is Sheet.Text -> {
                    Text(sh.title, color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    LazyColumn(Modifier.heightIn(max = 400.dp).padding(top = 10.dp)) {
                        sh.body.split("\n").filter { it.isNotBlank() }.forEachIndexed { i, para ->
                            item {
                                // Each paragraph takes focus, so the D-pad scrolls the review.
                                Focusable({ onClose() }, if (i == 0) Modifier.focusRequester(first) else Modifier, RoundedCornerShape(4.dp)) { _ ->
                                    Text(para, color = Palette.Text, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(vertical = 4.dp, horizontal = 6.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun date(ms: Long) = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(ms))

