package com.lekathedon.visionlite.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.verticalScroll
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
import com.lekathedon.visionlite.ui.Kit
import com.lekathedon.visionlite.ui.KitInk
import com.lekathedon.visionlite.ui.KitButton
import com.lekathedon.visionlite.ui.KitIcon
import com.lekathedon.visionlite.ui.Gold
import com.lekathedon.visionlite.ui.KitChip
import com.lekathedon.visionlite.ui.DisplayText
import com.lekathedon.visionlite.ui.Eyebrow
import com.lekathedon.visionlite.ui.Style
import com.lekathedon.visionlite.ui.skinPage
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
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
    /** A short choice (Trailer: stop / full screen; Production: which company), drawn in the look. */
    data class Menu(val title: String, val items: List<Triple<String, androidx.compose.ui.graphics.vector.ImageVector?, () -> Unit>>,
                    /** Labels that arrive later (Play with VOD: each file's own facts once opened), by item index. */
                    val live: androidx.compose.runtime.snapshots.SnapshotStateMap<Int, String>? = null) : Sheet
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
    val imdbId0 = s.id.substringBefore(':').takeIf { Imdb.isId(it) }
    // Back on a page (from a person, a company, the player…): drawn whole from memory at once, so the scroll and the
    // part last focused come back exactly (user 2026-11-24).
    var meta by remember(s.id) { mutableStateOf(App.instance.client.cachedMeta(s.type, s.id)) }
    var imdb by remember(s.id) { mutableStateOf(imdbId0?.let { Imdb.cachedTitle(it) }) }
    var eps by remember(s.id) { mutableStateOf(if (imdbId0 != null && s.type == "series") Imdb.cachedEpisodes(imdbId0)?.let { merge(meta, it, imdbId0) } ?: emptyList() else emptyList()) }
    var traktRating by remember(s.id) { mutableStateOf<Double?>(null) }
    var progress by remember(s.id) { mutableStateOf<com.lekathedon.visionlite.trakt.Trakt.Progress?>(null) }
    var loaded by remember(s.id) { mutableStateOf(false) }
    val imdbId = s.id.substringBefore(':').takeIf { Imdb.isId(it) }

    // An anime season opened by its MAL / AniList / Kitsu id: its IMDb show instead, on that season (IMDb-style
    // grouping, user 2026-11-26), so every season sits under one show.
    LaunchedEffect(s.id) {
        if (s.type == "series" && AnimeMeta.isAnimeId(s.id))
            AnimeMeta.parentOf(s.id)?.let { (tt, season) -> nav.replace(Screen.Detail("series", tt, s.name, season)); return@LaunchedEffect }
    }
    LaunchedEffect(s.id) {
        coroutineScope {
            val m = async { runCatching { App.instance.client.meta(s.type, s.id) }.getOrNull()
                ?: if (AnimeMeta.isAnimeId(s.id)) AnimeMeta.meta(s.id, s.type) else null }
            val t = async { imdbId?.let { Imdb.title(it) } }
            val e = async { if (imdbId != null && s.type == "series") Imdb.episodes(imdbId) else emptyList() }
            launch { traktRating = imdbId?.let { App.instance.trakt.rating(it, s.type == "series") }?.rating?.takeIf { it > 0 } }
            launch { if (imdbId != null && s.type == "series") progress = App.instance.trakt.progress(imdbId) }
            meta = m.await(); imdb = t.await(); loaded = true
            // What this title is (type, years, length) for Vision TB and the player, before Play (no extra call).
            imdb?.let { com.lekathedon.visionlite.tb.TitleFacts.put(it) }
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
    // "Play with VOD" (user 2026-11-26): only when your IPTV VOD lists this title (vod.db, no network) and IPTV is on.
    val vodHere by androidx.compose.runtime.produceState(false, s.id, name, isSeries, t?.year) {
        value = imdbId != null && com.lekathedon.visionlite.anime.Hubs.on("IPTV") &&
            com.lekathedon.visionlite.streams.VodLinks.present(if (isSeries) "series" else "movie", imdbId, listOf(name, s.name).distinct(), t?.year)
    }

    val style = Kit.style
    Box(Modifier.fillMaxSize().skinPage(look.skin)) {
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
        val plotsOff by prefs.hidePlots.flow.collectAsState()
        fun showRating(e: Ep) = when (epRule) { "off" -> false; "watched" -> (e.season to e.episode) in watched; else -> true }

        val seasons = remember(eps) { eps.map { it.season }.distinct().sorted().let { l -> if (l.size > 1) l.filter { it != 0 } else l } }
        val next = progress?.next
        var season by remember(s.id, seasons, next) { mutableStateOf(s.season?.takeIf { it in seasons } ?: next?.season?.takeIf { it in seasons } ?: seasons.firstOrNull() ?: 1) }
        val list = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val play = remember { FocusRequester() }
        var heroFocused by remember { mutableStateOf(false) }
        val mem = com.lekathedon.visionlite.ui.LocalFocusMemory.current
        LaunchedEffect(s.id, m != null || t != null) {
            withFrameNanos { }
            // Coming back: the part you left from takes focus; Play only if it never shows up.
            if (mem != null && mem.restoring) { while (mem.restoring) delay(100); if (mem.reqs.isNotEmpty() && mem.pending < 0) return@LaunchedEffect }
            runCatching { play.requestFocus() }
        }
        /** Focus back on the part that opened a menu / sheet / full-screen trailer. */
        fun refocus(id: Long?) = scope.launch { withFrameNanos { }; withFrameNanos { }; if (id == null || mem?.focus(id) != true) runCatching { play.requestFocus() } }

        // Trailer (Vision's IMDb source) on the billboard after the Home trailer delay, while focus rests in the hero.
        val trailersOn by prefs.trailers.flow.collectAsState()
        val sound by prefs.trailerSound.flow.collectAsState()
        val delayS by prefs.trailerDelay.flow.collectAsState()
        var trailer by remember(s.id) { mutableStateOf<com.lekathedon.visionlite.trailer.Trailer?>(null) }
        var trailerNow by remember(s.id) { mutableStateOf(false) }
        // The trailer plays by itself once per visit (user 2026-11-24): stopped, ended or scrolled away, it stays off
        // until the Trailer button, even after a person's page or another title and Back. Saved with the page.
        var autoDone by androidx.compose.runtime.saveable.rememberSaveable(s.id) { mutableStateOf(false) }
        /** Trailer › Play full screen: over the whole page with sound; Back returns to the page. */
        var fullTrailer by remember(s.id) { mutableStateOf(false) }
        LaunchedEffect(heroFocused, trailerNow, trailersOn, imdbId) {
            if (!heroFocused && !trailerNow) { trailer = null; return@LaunchedEffect }
            if (!trailerNow && (!trailersOn || autoDone)) return@LaunchedEffect
            if (trailerNow && trailer != null) return@LaunchedEffect   // already playing: focus moving doesn't restart it
            if (!trailerNow) delay(((delayS.toDoubleOrNull() ?: 5.0) * 1000).toLong())
            // Vision's order: IMDb, then TMDB, then YouTube.
            trailer = com.lekathedon.visionlite.trailer.TrailerChain.find(com.lekathedon.visionlite.addons.MetaPreview(imdbId ?: s.id, s.type, s.name),
                com.lekathedon.visionlite.device.Tier.trailerHeight(2160))
            if (trailer != null) autoDone = true
        }

        if (style == Style.TONAL) {
            // Tonal: the art in a big rounded frame on the right, gone once you scroll past the top.
            val atTop by remember { androidx.compose.runtime.derivedStateOf { list.firstVisibleItemIndex == 0 } }
            val a by androidx.compose.animation.core.animateFloatAsState(if (atTop) 1f else 0f, label = "frame")
            Box(Modifier.align(Alignment.TopEnd).padding(top = 26.dp, end = 26.dp).size(430.dp, 488.dp)
                .graphicsLayer { alpha = a }.clip(RoundedCornerShape(34.dp)).background(Kit.card)
                .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(34.dp))) {
                Img(backdrop, 430.dp, 488.dp, Modifier.fillMaxSize())
                trailer?.takeIf { !fullTrailer }?.let { com.lekathedon.visionlite.trailer.TrailerVideo(it.url, !sound, Modifier.fillMaxSize(), it.audio) { trailer = null; trailerNow = false } }
            }
        } else Box(Modifier.fillMaxWidth().height(if (style == Style.MARQUEE) 540.dp else 460.dp)) {
            Img(backdrop, 960.dp, 540.dp, Modifier.fillMaxSize())
            trailer?.takeIf { !fullTrailer }?.let { com.lekathedon.visionlite.trailer.TrailerVideo(it.url, !sound, Modifier.fillMaxSize(), it.audio) { trailer = null; trailerNow = false } }
            val pg = look.page
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to pg.copy(alpha = 0.94f), 0.42f to pg.copy(alpha = 0.6f), 0.75f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to pg)))
        }

        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 120.dp)) {
            // ---------------------------------------------------------------- billboard
            item(key = "hero") {
                Box(Modifier.fillMaxWidth()) {
                Column(Modifier.width(when (style) { Style.TONAL -> 440.dp; Style.MARQUEE -> 560.dp; Style.HALO -> 600.dp })
                    .heightIn(min = 380.dp).padding(top = if (style == Style.MARQUEE) 90.dp else 0.dp)) {
                    val more by androidx.compose.runtime.produceState(MoreRatings.R(), imdbId) { imdbId?.let { value = MoreRatings.of(it, isSeries) } }
                    val plot = m?.description ?: t?.plot
                    val crew = t?.let { (if (isSeries) it.creators else it.directors).take(2).joinToString(", ") { p -> p.name } }.orEmpty()
                    val stars = t?.cast?.take(4)?.joinToString(", ") { it.name }?.ifBlank { null } ?: m?.cast?.take(4)?.joinToString(", ")?.ifBlank { null }
                    when (style) {
                        Style.TONAL -> {
                            Facts(m, t, isSeries)
                            t?.awards?.let { aw -> Awards(aw, Modifier.padding(top = 10.dp), right = false) }
                            TitleArt(logo, name, 46.sp, Modifier.padding(top = 16.dp), 300.dp, 100.dp)
                            plot?.let { Text(it, color = Palette.Text.copy(alpha = 0.84f), fontSize = 15.sp, lineHeight = 22.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp)) }
                            ScoreTiles(t, traktRating, more)
                            if (crew.isNotBlank()) Text((if (isSeries) "Created by " else "Directed by ") + crew, color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                            GuideChips(t?.guide.orEmpty())
                        }
                        Style.MARQUEE -> {
                            val genres = (t?.genres?.ifEmpty { null } ?: m?.genres.orEmpty()).take(2).joinToString("  ·  ")
                            Eyebrow(listOfNotNull(genres.ifBlank { null }, crew.ifBlank { null }?.let { (if (isSeries) "Created by " else "By ") + it }).joinToString("  ·  "))
                            TitleArt(logo, name, 52.sp, Modifier.padding(top = 12.dp, bottom = 6.dp), 320.dp, 100.dp)
                            Facts(m, t, isSeries)
                            Ratings(t, traktRating, more)
                            plot?.let {
                                Row(Modifier.padding(top = 14.dp)) {
                                    Box(Modifier.padding(top = 9.dp, end = 12.dp).size(30.dp, 2.dp).background(Kit.accent))
                                    Text(it, color = Palette.Text.copy(alpha = 0.88f), fontSize = 15.sp, lineHeight = 22.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            GuideChips(t?.guide.orEmpty())
                        }
                        Style.HALO -> {
                            var logoFailed by remember(logo) { mutableStateOf(logo == null) }
                            if (!logoFailed) coil3.compose.AsyncImage(logo, name, Modifier.size(340.dp, 110.dp), contentScale = ContentScale.Fit,
                                alignment = Alignment.BottomStart, onError = { logoFailed = true })
                            else DisplayText(name, 42.sp, color = Kit.accent)
                            next?.let { n -> Text("Up next: S${n.season} E${n.number}" + (eps.firstOrNull { it.season == n.season && it.episode == n.number }?.title?.let { " · $it" } ?: ""),
                                color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp)) }
                            plot?.let { Text(it, color = Palette.Text.copy(alpha = 0.88f), fontSize = 15.sp, lineHeight = 23.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp)) }
                            Facts(m, t, isSeries)
                            Ratings(t, traktRating, more)
                            stars?.let { Text("Starring $it", color = Palette.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp)) }
                            GuideChips(t?.guide.orEmpty())
                        }
                    }

                    // Back up at the buttons: the whole billboard shows again (logo, facts, plot), not just the buttons' line.
                    // Play, then every action as an icon (user 2026-11-24: nothing taken away, a third smaller, size in
                    // Settings › Home and look › Button size); the focused icon's name shows in the caption under the row.
                    var hint by remember { mutableStateOf<String?>(null) }
                    // Any button in the row focused (from below, or moving along it): the whole billboard shows. Waits two
                    // frames so the list's own bring-into-view (just the button's line) doesn't cancel it (user 2026-11-24:
                    // stuck on "More like this" with the logo hidden until Left/Right).
                    var heroMoves by remember { mutableStateOf(0) }
                    LaunchedEffect(heroMoves) {
                        if (heroMoves == 0) return@LaunchedEffect
                        withFrameNanos { }; withFrameNanos { }
                        if (list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0) list.animateScrollToItem(0)
                    }
                    fun Modifier.hint(label: String) = this.onFocusChanged { if (it.isFocused) { hint = label; heroMoves++ } }
                    Row(Modifier.padding(top = if (style == Style.TONAL) 22.dp else 18.dp).wrapContentWidth(Alignment.Start, unbounded = true).widthIn(max = 1100.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState()).onFocusChanged {
                        heroFocused = it.hasFocus
                        if (!it.hasFocus) hint = null
                    }, horizontalArrangement = Arrangement.spacedBy(8.dp * Kit.soft), verticalAlignment = Alignment.CenterVertically) {
                        val I = com.lekathedon.visionlite.ui.Icons
                        val playLabel = when {
                            !isSeries -> "Play"
                            next != null -> "Play S${next.season} E${next.number}"
                            else -> eps.firstOrNull { it.season == seasons.firstOrNull() }?.let { "Play S${it.season} E${it.episode}" } ?: "Play"
                        }
                        /** What Play plays: (video id, title); null = nothing aired yet. */
                        fun playTarget(): Pair<String, String>? {
                            if (!isSeries) return (if (AnimeMeta.isAnimeId(s.id)) m?.id ?: s.id else s.id) to name
                            val listed = next?.let { n -> eps.firstOrNull { it.season == n.season && it.episode == n.number } }
                            // Trakt's next episode is missing from the list (a season IMDb hasn't added yet, user
                            // 2026-11-26 JoJo S6E6 played S1E1): play that episode anyway, never the first one.
                            if (next != null && listed == null && imdbId != null) return "$imdbId:${next.season}:${next.number}" to "$name S${next.season}E${next.number}"
                            val e = (listed ?: eps.firstOrNull { it.season == seasons.firstOrNull() })
                                ?.takeIf { it.airedMs == null || it.airedMs <= System.currentTimeMillis() } ?: return null
                            return e.videoId to "$name S${e.season}E${e.episode}"
                        }
                        KitButton(playLabel, I.Play, {
                            playTarget()?.let { (id, title) -> nav.push(Screen.Streams(if (isSeries) "series" else s.type, id, title, s.id)) }
                        }, Modifier.focusRequester(play).onFocusChanged { if (it.isFocused) { hint = null; heroMoves++ } }, primary = true)
                        // Play with VOD: your IPTV VOD's copies of this title / episode, each named (dub or subtitled,
                        // 4K / 1080p, Atmos, HDR, playlist); OK plays it straight away. Separate from Vision TB.
                        if (vodHere) KitButton("Play with VOD", I.LiveTv, {
                            val V = com.lekathedon.visionlite.streams.VodLinks
                            val kindS = if (isSeries) "series" else "movie"
                            val names = listOf(name, s.name).distinct()
                            val why = { com.lekathedon.visionlite.iptv.Vod.lastError?.let { "Your IPTV server refused ($it)" } }
                            // One copy list (dub / subtitled · 4K / 1080p · HDR / DV · playlist); each file is opened in the
                            // background (bounded Range reads, 2 at once) and its line updated in place.
                            fun copies(heading: String, id: String, title: String, opts0: List<com.lekathedon.visionlite.streams.Link>) {
                                val opts = V.sorted(opts0).take(12)
                                if (opts.isEmpty()) { sheet = Sheet.Menu(why() ?: "Not in your VOD", listOf(Triple("OK", null) {})); return }
                                val P = com.lekathedon.visionlite.probe.VodProbe
                                val live = androidx.compose.runtime.mutableStateMapOf<Int, String>()
                                val shownOpts = opts.map { l -> l.stream.url?.let(P::cached)?.let { l.copy(probe = it) } ?: l }
                                shownOpts.forEachIndexed { i, l ->
                                    val u = l.stream.url.orEmpty()
                                    if (l.probe == null && u.startsWith("http")) {
                                        live[i] = V.label(l).let { if ('\n' in it) "$it · checking the file…" else it }
                                        scope.launch { live[i] = V.label(P.probe(u)?.let { l.copy(probe = it) } ?: l) }
                                    }
                                }
                                sheet = Sheet.Menu(heading, shownOpts.map { l ->
                                    Triple(V.label(l), I.Play as androidx.compose.ui.graphics.vector.ImageVector?) {
                                        // Its own source name: never normal Play's resume link, link memory or fallback.
                                        nav.push(Screen.Player(l.stream.url.orEmpty(), title, emptyMap(), if (isSeries) "series" else s.type, id,
                                            listOfNotNull(l.stream.behaviorHints?.filename, l.stream.name, l.stream.title).joinToString("\n"), V.ADDON, l.bytes))
                                    }
                                }, live)
                            }
                            hint = "Looking in your VOD…"
                            if (!isSeries) scope.launch {
                                val (id, title) = playTarget() ?: (s.id to name)
                                val opts = runCatching { V.links("movie", imdbId ?: id, withTech = true, names = names, showYear = t?.year) }.getOrDefault(emptyList())
                                hint = null
                                copies("Play with VOD · $title", id, title, opts)
                            } else scope.launch {
                                // Shows (user 2026-11-26): what your VOD has, by season and episode, not just the episode you'd watch next.
                                val eps = runCatching { V.seriesEpisodes(kindS, imdbId ?: s.id, names, t?.year) }.getOrNull()
                                hint = null
                                if (eps.isNullOrEmpty()) { sheet = Sheet.Menu(why() ?: "Not in your VOD", listOf(Triple("OK", null) {})); return@launch }
                                val next = playTarget()?.first?.let(com.lekathedon.visionlite.streams.EpisodeCheck::targetOf)
                                fun episodeMenu(season: Int) {
                                    val list = eps.filter { it.season == season }
                                    sheet = Sheet.Menu("$name · Season $season in your VOD", list.map { e ->
                                        val best = e.links.maxOfOrNull { V.heightOf(it) } ?: 0
                                        val pic = when { best >= 2160 -> "4K"; best >= 1080 -> "1080p"; best >= 720 -> "720p"; else -> null }
                                        val mark = if (next != null && next.season == e.season && next.episode == e.number) " · next for you" else ""
                                        Triple("Episode ${e.number}${e.title.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}\n" +
                                            listOfNotNull("${e.links.size} ${if (e.links.size == 1) "copy" else "copies"}", pic?.let { "best $it" }).joinToString(" · ") + mark,
                                            I.Play as androidx.compose.ui.graphics.vector.ImageVector?) {
                                            val id = "${imdbId ?: s.id}:${e.season}:${e.number}"
                                            val title = "$name S${e.season}E${e.number}"
                                            scope.launch { copies("Play with VOD · $title", id, title, e.links) }
                                        }
                                    })
                                }
                                val seasons = eps.map { it.season }.distinct()
                                if (seasons.size == 1) episodeMenu(seasons[0])
                                else sheet = Sheet.Menu("$name in your VOD", seasons.map { sn ->
                                    val n = eps.count { it.season == sn }
                                    Triple("Season $sn\n$n ${if (n == 1) "episode" else "episodes"}" + (if (next?.season == sn) " · next for you" else ""),
                                        I.LiveTv as androidx.compose.ui.graphics.vector.ImageVector?) { scope.launch { episodeMenu(sn) } }
                                })
                            }
                        }, Modifier.hint("Play with VOD"))
                        // Trailer: a choice, so it can also play full screen instead of behind the page.
                        if (imdbId != null) KitIcon(if (trailer != null) "Trailer playing" else "Trailer", I.Theaters, {
                            sheet = Sheet.Menu("Trailer", listOf(
                                Triple("Play full screen", I.Fullscreen) { trailerNow = true; fullTrailer = true },
                                if (trailer != null) Triple("Stop trailer", I.Stop) { trailer = null; trailerNow = false }
                                else Triple("Play here", I.Play) { trailerNow = true }))
                        }, Modifier.hint("Trailer"), on = trailer != null)
                        var saved by remember(s.id) { mutableStateOf(Saves.has(s.id)) }
                        KitIcon(if (saved) "In Library" else "Add to Library", if (saved) I.Check else I.Bookmark, {
                            saved = Saves.toggle(Saved(s.type, s.id, name, m?.poster ?: t?.poster))
                        }, Modifier.hint(if (saved) "In Library" else "Add to Library"), on = saved)
                        // Follow (shows): checked every time on TVmaze and TMDB, so the next season is in Coming up the day it's dated.
                        if (isSeries && imdbId != null) {
                            val CU = com.lekathedon.visionlite.home.ComingUp
                            val fr by CU.followRev.collectAsState()
                            val following = remember(imdbId, fr) { CU.isFollowed(imdbId) }
                            KitIcon(if (following) "Following" else "Follow", I.Bell, { CU.follow(imdbId, name, !following) },
                                Modifier.hint(if (following) "Following · new seasons in Coming up · press to stop" else "Follow · new seasons in Coming up"), on = following)
                        }
                        // The one engine behind every recommendation: each verdict its own icon; pressed again, it's undone.
                        val rc by com.lekathedon.visionlite.recs.Recs.changed.collectAsState()
                        val verdict = remember(s.id, rc) { com.lekathedon.visionlite.recs.Recs.verdict(s.id) }
                        val R = com.lekathedon.visionlite.recs.Recs
                        val mp = com.lekathedon.visionlite.addons.MetaPreview(s.id, s.type, name, m?.poster ?: t?.poster)
                        fun toggle(v: String, set: () -> Unit) { if (verdict == v) R.forget(s.id) else set() }
                        KitIcon("Watched", I.Visibility, { toggle("watched") { com.lekathedon.visionlite.home.Ticks.watched(mp) } },
                            Modifier.hint(if (verdict == "watched") "Watched · press to undo" else "Mark as watched"), on = verdict == "watched")
                        KitIcon("Love it", I.Favorite, { toggle("love") { R.love(s.id, isSeries, name) } },
                            Modifier.hint(if (verdict == "love") "Loved · press to undo" else "Love it"), on = verdict == "love")
                        KitIcon("More like this", I.ThumbUp, { toggle("like") { R.moreLikeThis(s.id, isSeries, name) } },
                            Modifier.hint(if (verdict == "like") "Liked · press to undo" else "More like this"), on = verdict == "like")
                        KitIcon("Not for me", I.ThumbDown, { toggle("nope") { R.notForMe(s.id, isSeries, name) } },
                            Modifier.hint(if (verdict == "nope") "Not for me · press to undo" else "Not for me"), on = verdict == "nope")
                        if (imdbId != null && com.lekathedon.visionlite.home.Rate.available()) {
                            val mine = remember(s.id, rc) { com.lekathedon.visionlite.home.Rate.mine(imdbId) }
                            KitIcon("Rate", I.Star, { com.lekathedon.visionlite.home.Rate.ask(imdbId, isSeries, name) },
                                Modifier.hint(if (mine > 0) "Your rating: $mine/10 · Trakt" else "Rate on Trakt"), on = mine > 0)
                        }
                        // Sequel / Prequel and Connections: separate, each only when the title has some (user 2026-11-24).
                        val sp by prefs.sequelPrequel.flow.collectAsState()
                        val cn by prefs.connections.flow.collectAsState()
                        if (sp && t != null && t.sequels.isNotEmpty()) KitIcon("Sequel / Prequel", I.Swap, {
                            sheet = Sheet.Links("Sequel / Prequel", listOf("Follows" to t.follows, "Followed by" to t.followedBy).filter { it.second.isNotEmpty() })
                        }, Modifier.hint("Sequel / Prequel"), count = t.sequels.size)
                        if (cn && t != null && t.connections.isNotEmpty()) KitIcon("Connections", I.AccountTree, {
                            sheet = Sheet.Links("Connections", t.connections.groupBy { it.link!! }.toSortedMap().map { (l, v) -> l.heading to v })
                        }, Modifier.hint("Connections"), count = t.connections.size)
                    }
                    // The focused icon's name; Marquee also says when a film started now would end.
                    Row(Modifier.padding(top = 8.dp).height(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        hint?.let { com.lekathedon.visionlite.ui.Eyebrow(it, color = Palette.Text) }
                        if (hint == null && style == Style.MARQUEE && !isSeries) t?.runtimeMin?.let { min ->
                            val ends = remember(min) { android.text.format.DateFormat.getTimeFormat(App.instance).format(java.util.Date(System.currentTimeMillis() + min * 60_000L)) }
                            com.lekathedon.visionlite.ui.Eyebrow("Ends at $ends")
                        }
                    }
                }
                // Awards (IMDb), top right beside the billboard, in gold (WuPlay's "Won 1 Oscar").
                // Tonal's right side is the art frame, so its awards sit under the facts instead.
                if (style != Style.TONAL) t?.awards?.let { aw -> Awards(aw, Modifier.align(Alignment.BottomEnd).padding(bottom = 46.dp)) }
                }
            }

            // ---------------------------------------------------------------- episodes
            if (isSeries && eps.isNotEmpty()) {
                item(key = "seasons") {
                    val counts = remember(eps) { eps.groupingBy { it.season }.eachCount() }
                    SeasonTabs(seasons, season, counts) { season = it }
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
                        LazyRow(Modifier.padding(top = 14.dp), state = epRow, horizontalArrangement = Arrangement.spacedBy(if (style == Style.TONAL) 14.dp else 16.dp)) {
                            items(inSeason, key = { it.videoId }) { e ->
                                EpisodeCard(e, style, showRating(e), (e.season to e.episode) in watched, next?.let { it.season == e.season && it.number == e.episode } == true, blurOn,
                                    onFocus = { focusedEp = e }) { nav.push(Screen.Streams("series", e.videoId, "$name S${e.season}E${e.episode}", s.id)) }
                            }
                        }
                        val f = focusedEp ?: inSeason.firstOrNull()
                        f?.let { e ->
                            Column(Modifier.padding(top = 10.dp).width(700.dp).heightIn(min = 64.dp)) {
                                Text("S${e.season} E${e.episode}" + (e.title?.let { " · $it" } ?: "") + (e.airedMs?.let { " · " + date(it) } ?: ""),
                                    color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                e.plot?.let { Text(it, color = Palette.TextDim, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 3.dp).spoiler(plotsOff && (e.season to e.episode) !in watched)) }
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

            // ---------------------------------------------------------------- credits (Marquee: dotted leaders)
            if (style == Style.MARQUEE && t != null) item(key = "credits") {
                val lines = listOfNotNull(
                    t.creators.take(2).joinToString(", ") { it.name }.ifBlank { null }?.let { "Created by" to it },
                    t.directors.take(2).joinToString(", ") { it.name }.ifBlank { null }?.let { "Directed by" to it },
                    t.writers.take(2).joinToString(", ") { it.name }.ifBlank { null }?.let { "Written by" to it },
                    t.cast.take(3).joinToString(", ") { it.name }.ifBlank { null }?.let { "Starring" to it },
                    t.genres.take(3).joinToString(", ").ifBlank { null }?.let { "Genres" to it },
                    t.year?.let { "First out" to it.toString() },
                    t.runtimeMin?.let { "Runtime" to "$it min" })
                if (lines.isNotEmpty()) Column(Modifier.padding(top = 26.dp).width(620.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    com.lekathedon.visionlite.ui.KitHeader("Credits", Modifier.padding(bottom = 4.dp))
                    lines.forEach { (k, v) -> Leader(k, v) }
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

            // ---------------------------------------------------------------- production (Nuvio: logos under Cast)
            if (imdbId != null) item(key = "production") {
                val cos by androidx.compose.runtime.produceState<List<com.lekathedon.visionlite.browse.Companies.Co>>(emptyList(), imdbId, t) {
                    value = com.lekathedon.visionlite.browse.Companies.ofTitle(imdbId, isSeries, t?.companies.orEmpty())
                }
                if (cos.isNotEmpty()) Column {
                    Header("Production")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(cos, key = { it.id }) { c -> CompanyLogo(c) { nav.push(Screen.Company(c.id, c.name)) } }
                    }
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
                        Column(Modifier.clip(RoundedCornerShape(Kit.rowR)).background(Kit.card).padding(horizontal = 14.dp, vertical = 10.dp).width(150.dp)) {
                            Text(g.category, color = Palette.TextDim, fontSize = 12.sp, maxLines = 2)
                            Text(g.severity, color = severityColor(g.severity), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }

        if (fullTrailer) {
            val grab = remember { FocusRequester() }
            LaunchedEffect(Unit) { withFrameNanos { }; runCatching { grab.requestFocus() } }
            val from = remember { mem?.last }
            BackHandler { fullTrailer = false; refocus(from) }
            Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(grab).focusable(), contentAlignment = Alignment.Center) {
                val tr = trailer
                if (tr == null) Text("Finding the trailer…", color = Palette.TextDim, fontSize = 16.sp)
                else com.lekathedon.visionlite.trailer.TrailerVideo(tr.url, false, Modifier.fillMaxSize(), tr.audio) {
                    fullTrailer = false; trailer = null; trailerNow = false; refocus(from)
                }
            }
        }

        // A sheet closed (Back, or a title opened from it): focus goes back to the button that opened it, and Back from
        // that title lands there too (user 2026-11-24: not on Play).
        sheet?.let { sh ->
            val from = remember(sh) { mem?.last }
            SheetView(sh, onClose = { sheet = null; if (from != null) mem?.last = from; if (!fullTrailer) refocus(from) }) { r ->
                sheet = null; if (from != null) mem?.last = from
                nav.push(Screen.Detail(r.type, r.imdb, r.title))
            }
        }
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
    // IMDb's count, or more when TMDB filled in seasons IMDb hasn't listed yet (detail/TmdbSeasons.kt).
    val seasons = maxOf(t?.seasons?.count { it > 0 } ?: 0, m?.videos.orEmpty().mapNotNull { it.season }.filter { it > 0 }.distinct().size)
        .takeIf { series && it > 0 }?.let { if (it == 1) "1 season" else "$it seasons" }
    val style = Kit.style
    Row(Modifier.padding(top = if (style == Style.TONAL) 0.dp else 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (style == Style.TONAL) 8.dp else 0.dp)) {
        if (style == Style.TONAL) {
            // Tonal: the facts as coloured chips.
            listOfNotNull(t?.certificate, years, seasons ?: runtime, (t?.genres?.ifEmpty { null } ?: m?.genres.orEmpty()).firstOrNull()).forEach { KitChip(it) }
        } else {
            t?.certificate?.let {
                KitChip(it)
                Spacer(Modifier.width(10.dp))
            }
            Text(listOfNotNull(years, seasons, runtime, genres).joinToString(if (style == Style.MARQUEE) "  •  " else "  ·  "), color = Palette.TextDim, fontSize = 14.sp)
        }
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

/** A production company: its logo on a light tile (most logos are dark ink), the name under it; OK opens its works. */
@Composable
private fun CompanyLogo(c: com.lekathedon.visionlite.browse.Companies.Co, open: () -> Unit) {
    // The tiles are light, so the look's ring (white in Marquee) vanished on them (user 2026-11-24: "not sure which one
    // I'm on"): the tile sits inside the ring with a dark gap, unfocused tiles are dimmed and the focused one's name lights up.
    var on by remember { mutableStateOf(false) }
    Column(Modifier.width(140.dp)) {
        Focusable(open, Modifier.onFocusChanged { on = it.hasFocus }, RoundedCornerShape(Kit.rowR + 4.dp)) { f ->
            Box(Modifier.padding(4.dp).size(132.dp, 66.dp).graphicsLayer { alpha = if (f) 1f else 0.55f }
                .clip(RoundedCornerShape(Kit.rowR)).background(Color(0xFFECECEC)).padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center) {
                if (c.logo != null) coil3.compose.AsyncImage(c.logo, c.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else Text(c.name, color = Color(0xFF1A1A1A), fontFamily = Kit.display, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        Text(c.name, color = if (on) Palette.Text else Palette.TextDim, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, start = 6.dp))
    }
}

/** The title as on its poster: the show's own logo art (Fanart / metahub), the look's display text if there is none. */
@Composable
private fun TitleArt(logo: String?, name: String, size: androidx.compose.ui.unit.TextUnit, modifier: Modifier, w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp) {
    var failed by remember(logo) { mutableStateOf(logo == null) }
    if (!failed) coil3.compose.AsyncImage(logo, name, modifier.size(w, h), contentScale = ContentScale.Fit,
        alignment = Alignment.BottomStart, onError = { failed = true })
    else DisplayText(name, size, modifier, maxLines = 2)
}

/** IMDb's awards in gold: a trophy, the big prize ("Won 1 Oscar") and all wins / nominations under it. */
@Composable
private fun Awards(a: Imdb.Awards, modifier: Modifier = Modifier, right: Boolean = true) {
    Column(modifier, horizontalAlignment = if (right) Alignment.End else Alignment.Start) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(com.lekathedon.visionlite.ui.Icons.Trophy, null, Modifier.size(15.dp), tint = Gold)
            Text(a.headline ?: a.counts.orEmpty(), color = Gold, fontFamily = Kit.display, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 6.dp), maxLines = 1)
        }
        if (a.headline != null) a.counts?.let { Text(it, color = Gold.copy(alpha = 0.75f), fontSize = 10.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 2.dp)) }
    }
}

/** Tonal's score tiles: one rounded square per source, IMDb in its yellow. */
@Composable
private fun ScoreTiles(t: Imdb.Title?, trakt: Double?, more: MoreRatings.R) {
    val tiles = buildList {
        t?.rating?.let { add("%.1f".format(it) to "IMDB") }
        more.tomatoes?.let { add("$it" to "TOMATO") }
        trakt?.let { add("${(it * 10).toInt()}" to "TRAKT") }
        more.tmdb?.let { add(it.removeSuffix("%") to "TMDB") }
        t?.metascore?.let { add("$it" to "META") }
        more.letterboxd?.let { add("%.1f".format(it) to "LBXD") }
    }.take(5)
    if (tiles.isEmpty()) return
    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.forEachIndexed { i, (v, k) ->
            val imdb = i == 0 && t?.rating != null
            Column(Modifier.size(62.dp).clip(RoundedCornerShape(22.dp)).background(if (imdb) ImdbYellow else Kit.card2),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(v, color = if (imdb) Color(0xFF111111) else Palette.Text, fontFamily = com.lekathedon.visionlite.ui.Fonts.tonalDisplay,
                    fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                Text(k, color = if (imdb) Color(0xFF5A4A00) else Palette.TextDim, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
            }
        }
    }
}

/** Seasons in the look: Tonal joined segments, Marquee radio dots over an underline, Halo pills. Focus opens the season. */
@Composable
private fun SeasonTabs(seasons: List<Int>, season: Int, counts: Map<Int, Int>, pick: (Int) -> Unit) {
    val style = Kit.style
    LazyRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(when (style) { Style.TONAL -> 3.dp; Style.MARQUEE -> 26.dp; Style.HALO -> 8.dp })) {
        items(seasons.size) { idx ->
            val n = seasons[idx]
            val name = if (n == 0) "Specials" else "Season $n"
            val sel = n == season
            when (style) {
                Style.TONAL -> {
                    val big = 20.dp; val small = 8.dp
                    val shape = when {
                        sel || seasons.size == 1 -> RoundedCornerShape(big)
                        idx == 0 -> RoundedCornerShape(big, small, small, big)
                        idx == seasons.lastIndex -> RoundedCornerShape(small, big, big, small)
                        else -> RoundedCornerShape(small)
                    }
                    Focusable({ pick(n) }, shape = shape, onFocus = { pick(n) }) { _ ->
                        Text(name, color = if (sel) Kit.onAccent else Palette.TextDim, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.background(if (sel) Kit.accent else Kit.card2).padding(horizontal = 18.dp, vertical = 10.dp))
                    }
                }
                Style.MARQUEE -> Focusable({ pick(n) }, shape = RoundedCornerShape(8.dp), onFocus = { pick(n) }) { _ ->
                    Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(14.dp).clip(CircleShape).border(2.dp, if (sel) Kit.accent else Palette.TextDim, CircleShape).padding(3.5.dp)) {
                                if (sel) Box(Modifier.fillMaxSize().clip(CircleShape).background(Kit.accent))
                            }
                            Text(name, color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                            counts[n]?.let { Text("  $it episodes", color = Palette.TextDim, fontSize = 12.sp) }
                        }
                        Box(Modifier.padding(top = 7.dp).fillMaxWidth().height(2.dp).background(if (sel) Kit.accent else Color.Transparent))
                    }
                }
                Style.HALO -> Focusable({ pick(n) }, shape = RoundedCornerShape(50), onFocus = { pick(n) }) { _ ->
                    Text(name, color = if (sel) Kit.onAccent else Palette.TextDim, fontSize = 14.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.background(if (sel) Kit.accent else Color(0x40000000)).border(1.dp, if (sel) Color.Transparent else Kit.line, RoundedCornerShape(50))
                            .padding(horizontal = 18.dp, vertical = 8.dp))
                }
            }
        }
    }
}

/** Marquee's credit line: tracked label, dotted leader, name. */
@Composable
private fun Leader(k: String, v: String) {
    val dot = Kit.line
    Row(verticalAlignment = Alignment.Bottom) {
        Text(k.uppercase(), color = Palette.TextDim, fontFamily = Kit.label, fontSize = 12.sp, letterSpacing = 3.sp, fontWeight = FontWeight.SemiBold)
        Box(Modifier.weight(1f).height(10.dp).padding(horizontal = 8.dp).drawBehind {
            drawLine(dot, androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(size.width, size.height), 2f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(2f, 6f)))
        })
        Text(v, color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 380.dp))
    }
}

/**
 * Vision's ranked parental-guide tags (worst first), "None" left out, in the look (no grey pills): Marquee tracked
 * capitals split by thin rules, Tonal a coloured dot per tag, Halo the severity as a small coloured bar.
 */
@Composable
private fun GuideChips(g: List<Imdb.Guide>) {
    val rank = mapOf("severe" to 0, "moderate" to 1, "mild" to 2)
    val shown = g.filter { it.severity.lowercase() in rank }.sortedBy { rank[it.severity.lowercase()] }
    if (shown.isEmpty()) return
    Row(Modifier.padding(top = 12.dp).wrapContentWidth(Alignment.Start, unbounded = true), verticalAlignment = Alignment.CenterVertically) {
        shown.forEachIndexed { i, x ->
            val short = x.category.substringBefore(" &").substringBefore(",")
            val c = severityColor(x.severity)
            when (Kit.style) {
                Style.MARQUEE -> {
                    if (i > 0) Box(Modifier.padding(horizontal = 7.dp).size(1.dp, 9.dp).background(Kit.line))
                    Text(short.uppercase(), color = Palette.Text.copy(alpha = 0.85f), fontFamily = Kit.label, fontSize = 9.sp, letterSpacing = 1.3.sp, maxLines = 1, softWrap = false)
                    Text(" " + x.severity.uppercase(), color = c, fontFamily = Kit.label, fontSize = 9.sp, letterSpacing = 1.3.sp, maxLines = 1, softWrap = false)
                }
                Style.TONAL -> {
                    if (i > 0) Spacer(Modifier.width(10.dp))
                    Box(Modifier.size(6.dp).clip(CircleShape).background(c))
                    Text("  $short", color = Palette.Text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                    Text(" ${x.severity.lowercase()}", color = c, fontSize = 10.sp, maxLines = 1, softWrap = false)
                }
                Style.HALO -> {
                    if (i > 0) Spacer(Modifier.width(12.dp))
                    Column {
                        Text(short, color = Palette.Text, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                        Box(Modifier.padding(top = 3.dp).size(when (x.severity.lowercase()) { "severe" -> 36.dp; "moderate" -> 24.dp; else -> 12.dp }, 3.dp).clip(RoundedCornerShape(50)).background(c))
                    }
                }
            }
        }
    }
}

private fun severityColor(s: String) = when (s.lowercase()) {
    "severe" -> Color(0xFFFF6B6B); "moderate" -> Color(0xFFFFC266); "mild" -> Color(0xFFB9E3A0); else -> Color(0xFFBBBBBB)
}

/** A person's round photo, name and role (cast, creators & crew); OK opens their page (Follow is there). */
@Composable
private fun PersonCircle(p: Imdb.Person, open: () -> Unit) {
    Focusable(open, Modifier.width(110.dp), shape = RoundedCornerShape(Kit.rowR)) { f ->
        Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(90.dp).clip(if (Kit.style == Style.TONAL) RoundedCornerShape(30.dp) else CircleShape).background(Kit.card2), contentAlignment = Alignment.Center) {
                if (p.photo != null) Img(p.photo, 90.dp, 90.dp, Modifier.fillMaxSize())
                else Text(p.name.take(1), color = Palette.Text, fontSize = 30.sp)
            }
            Text(p.name, color = Palette.Text, fontSize = 13.sp, fontWeight = if (Kit.style == Style.TONAL) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            p.role?.let { Text(it, color = Palette.TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun Header(t: String, by: String? = null) = com.lekathedon.visionlite.ui.KitHeader(t, Modifier.padding(top = 30.dp, bottom = 12.dp), by)

@Composable
private fun EpisodeCard(e: Ep, style: Style, showRating: Boolean, watched: Boolean, isNext: Boolean, blurOn: Boolean, onFocus: () -> Unit, onClick: () -> Unit) {
    val aired = e.airedMs == null || e.airedMs <= System.currentTimeMillis()
    val title = e.title ?: "Episode ${e.episode}"
    val meta = listOfNotNull(e.airedMs?.let { date(it) }, if (watched) "Watched" else if (isNext) "Up next" else null,
        if (showRating && e.rating != null) "★ %.1f".format(e.rating) else null).joinToString(" · ")
    @Composable fun art(w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp, r: androidx.compose.ui.unit.Dp, overlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) =
        Box(Modifier.size(w, h).clip(RoundedCornerShape(r)).background(Kit.card2)) {
            Img(e.image, w, h, Modifier.fillMaxSize(), blur = blurOn && !watched)
            if (!aired) Box(Modifier.fillMaxSize().background(Color(0x99000000)))
            overlay()
        }
    // Not out yet: nothing to play, so pressing it does nothing (user 2026-11-19).
    when (style) {
        Style.TONAL -> Focusable({ if (aired) onClick() }, Modifier.width(232.dp), RoundedCornerShape(26.dp), onFocus) { f ->
            Column(Modifier.fillMaxWidth().background(if (f) Kit.lerp(Kit.card, Kit.accent, 0.24f) else Kit.card).padding(8.dp).padding(bottom = 6.dp)) {
                art(216.dp, 122.dp, 20.dp) {
                    Box(Modifier.padding(8.dp).size(34.dp).clip(RoundedCornerShape(12.dp)).background(if (isNext) Kit.accent else Color(0xCC000000)), contentAlignment = Alignment.Center) {
                        Text("${e.episode}", color = if (isNext) Kit.onAccent else Color.White, fontFamily = com.lekathedon.visionlite.ui.Fonts.tonalDisplay, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    if (watched) Box(Modifier.align(Alignment.TopEnd).padding(10.dp).size(26.dp).clip(CircleShape).background(Kit.accent), contentAlignment = Alignment.Center) {
                        Text("✓", color = Kit.onAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(title, color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 12.dp))
                Text(meta, color = Palette.TextDim, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
        Style.MARQUEE -> Focusable({ if (aired) onClick() }, Modifier.width(240.dp), RoundedCornerShape(10.dp), onFocus) { f ->
            Column {
                art(240.dp, 135.dp, 10.dp) {
                    if (isNext) Text("UP NEXT", color = Kit.onAccent, fontFamily = Kit.label, fontSize = 11.sp, letterSpacing = 2.sp,
                        modifier = Modifier.padding(8.dp).clip(RoundedCornerShape(4.dp)).background(Kit.accent).padding(horizontal = 7.dp, vertical = 3.dp))
                }
                Text("${e.episode}. $title", color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp))
                Text(meta, color = Palette.TextDim, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
            }
        }
        Style.HALO -> Focusable({ if (aired) onClick() }, Modifier.width(280.dp), RoundedCornerShape(18.dp), onFocus) { f ->
            Column(Modifier.fillMaxWidth().background(Kit.panel).border(1.dp, Kit.line, RoundedCornerShape(18.dp))) {
                art(280.dp, 150.dp, 0.dp) {
                    Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        KitChip("EPISODE ${e.episode}", strong = isNext)
                        if (watched) KitChip("✓ Watched")
                        if (!aired) e.airedMs?.let { KitChip(date(it)) }
                    }
                }
                Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (showRating && e.rating != null) Text("★ %.1f".format(e.rating), color = ImdbYellow, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Text(e.airedMs?.let { date(it) } ?: "", color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp))
            }
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
        com.lekathedon.visionlite.ui.KitHeader("Reviews")
        listOf("imdb" to "IMDb", "trakt" to "Trakt").forEach { (k, label) ->
            Focusable({ prefs.reviewsSource.value = k }, shape = RoundedCornerShape(if (Kit.style == Style.MARQUEE) 6.dp else 50.dp)) { f ->
                Text(label, color = if (src == k) Kit.onAccent else Palette.Text, fontSize = 13.sp, fontWeight = if (src == k) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.background(if (src == k) Kit.accent else Kit.card2).padding(horizontal = 14.dp, vertical = 5.dp))
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
                    Modifier.size(340.dp, 190.dp), RoundedCornerShape(Kit.cardR)) { f ->
                    Column(Modifier.fillMaxSize().background(if (f) Kit.card2 else Kit.card).padding(16.dp)) {
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
        Column(Modifier.widthIn(max = 820.dp).clip(RoundedCornerShape(Kit.cardR)).background(Kit.panel).border(1.dp, Kit.line, RoundedCornerShape(Kit.cardR)).padding(24.dp)) {
            when (sh) {
                is Sheet.Links -> {
                    Text(sh.title, color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        // Heading and its posters are one item, so going back up always shows the heading ("Spin-offs").
                        sh.groups.forEachIndexed { gi, (heading, refs) ->
                            item(key = "g$gi") {
                                Column {
                                    Text(heading, color = Palette.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
                                    PosterRow(refs, if (gi == 0) first else null, onOpen)
                                }
                            }
                        }
                    }
                }
                is Sheet.Menu -> {
                    com.lekathedon.visionlite.ui.Eyebrow(sh.title, Modifier.padding(bottom = 14.dp))
                    Column(Modifier.heightIn(max = 470.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        sh.items.forEachIndexed { i, (label0, icon, act) ->
                            val label = sh.live?.get(i) ?: label0
                            if ('\n' in label) {
                                // Two-line option (Play with VOD): the choice in bold, its file facts under it.
                                Focusable({ act(); onClose() }, (if (i == 0) Modifier.focusRequester(first) else Modifier).fillMaxWidth().padding(vertical = 3.dp),
                                    RoundedCornerShape(10.dp)) { f ->
                                    Column(Modifier.background(if (f) KitInk.bg else Kit.rowFill, RoundedCornerShape(10.dp)).fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                                        Text(label.substringBefore('\n'), color = if (f) KitInk.ink else KitInk.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                        Text(label.substringAfter('\n'), color = if (f) KitInk.inkDim else KitInk.dim, fontSize = 13.sp, maxLines = 2, lineHeight = 18.sp)
                                    }
                                }
                            } else KitButton(label, icon, { act(); onClose() }, if (i == 0) Modifier.focusRequester(first) else Modifier, primary = i == 0)
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

