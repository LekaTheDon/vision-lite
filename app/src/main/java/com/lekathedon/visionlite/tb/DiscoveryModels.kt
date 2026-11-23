package com.lekathedon.visionlite.tb

/*
 * Vision TB (TorBox discovery): asks torrent sources directly, keeps the releases TorBox already
 * has cached, and shows them as one stream group, like TorBox Instant did with Torrentio and Comet.
 * Pure Kotlin (no Android), so it runs under kotlinc and plain JUnit.
 *
 * Routes studied (endpoint knowledge only, no code taken):
 * - DebridDownloader (CasaVargas, GPL-3.0): TorBox Search API meta search -> Torznab by IMDb id,
 *   then torrents/checkcached.
 * - TorBox Prowlarr indexer definition: Search API torrents/imdb:{id}?season=&episode=.
 * - Debrify (AGPL-3.0): public torrent-site engines (Knaben, apibay, torrents-csv, Nyaa...).
 * - Torrentio, Comet, Meteor, Peerflix: public Stremio add-ons asked by IMDb id without any debrid config.
 */

/** What we are looking for. Unknown fields stay null; nothing is guessed. */
data class DiscoveryTarget(
    val imdbId: String?,
    val isSeries: Boolean,
    /** Main title first, then original and alternative titles. */
    val titles: List<String>,
    val year: Int?,
    val season: Int? = null,
    val episode: Int? = null,
    val isAnime: Boolean = false,
    /** TheTVDB's titles and other orders for this episode (Vision TB with a TVDB key); null = numbering only. */
    val episodeHint: EpisodeOrderHint? = null,
    /** Anime only: the title's Kitsu / AniList / AniDB ids (anime sources are skipped without them). */
    val anime: AnimeRefs? = null,
    /** Kitsu's own titles for this entry: names from the anime sources may carry any of them. */
    val animeTitles: List<String> = emptyList()
)

/** Where a release was found. Order = trust when two sources disagree on the name. */
enum class DiscoverySource(val label: String) {
    TORBOX_SEARCH("TorBox"),
    TORRENTIO("Torrentio"),
    APIBAY("TPB"),
    KNABEN("Knaben"),
    TORRENTS_CSV("TorrentsCSV"),
    NYAA("Nyaa"),
    COMET("Comet"),
    METEOR("Meteor"),
    /** StremThru's public torrents route (its Torz index: hashes its users' debrid checks found, by IMDb id). */
    STREMTHRU("StremThru"),
    /** Peerflix: public Stremio add-on, many Spanish/regional trackers plus general ones (found via DMM's scraper). */
    PEERFLIX("Peerflix"),
    /** Torrentio asked for its regional trackers only (its default list caps them). */
    TORRENTIO_REGIONAL("Torrentio regional"),
    /** TorrentsDB: public Stremio add-on, a few regional and 4K rows the others lack. */
    TORRENTSDB("TorrentsDB"),
    /** Zilean: index of Debrid Media Manager's shared hash lists, by IMDb id. */
    ZILEAN("Zilean"),
    /** Comet asked by Kitsu id (anime): its rows must name the show, they are not trusted (other arcs come back). */
    COMET_KITSU("Comet Kitsu"),
    /** TsukiHime: anime index (regional / dub groups, BD packs). */
    TSUKIHIME("TsukiHime"),
    /** Anime Tosho: anime index by AniDB id (Nyaa backup). */
    ANIMETOSHO("Anime Tosho"),
    /** nekoBT: anime tracker, Torznab, key optional. */
    NEKOBT("nekoBT"),
    /** Jackettio with the user's TorBox key (switch "Keyed sources"). */
    JACKETTIO("Jackettio"),
    /** Meteor with the user's TorBox key (switch "Keyed sources"). */
    METEOR_KEYED("Meteor+key"),
    /** Experimental engine only: hashes the user's own add-ons returned (no request of its own). */
    ADDONS("Add-ons"),
    /** One of your own add-ons added in Settings › Vision TB (feeds only Vision TB). */
    USER_ADDON("Your add-on")
}

/** One release as a source reported it, before matching. */
data class DiscoveryCandidate(
    val hash: String,
    val name: String,
    val sizeBytes: Long?,
    val seeders: Int?,
    val source: DiscoverySource,
    /** True when the source itself tied the release to the IMDb id (not a text search). */
    val idTagged: Boolean = false,
    /** True only when the source says TorBox has it cached; null = the source didn't say. */
    val sourceSaysCached: Boolean? = null,
    /** The source matched it to the id itself (a Stremio add-on): a foreign-language name is fine. */
    val trustedMatch: Boolean = false,
    /** File index inside the torrent when the source gave one. */
    val fileIdx: Int? = null,
    /** The file the source says holds this movie/episode (a Stremio add-on's behaviorHints.filename). */
    val fileName: String? = null
)

/** A release after de-duplication by info hash. */
data class DiscoveredRelease(
    val hash: String,
    val name: String,
    val sizeBytes: Long?,
    val seeders: Int?,
    val sources: Set<DiscoverySource>,
    val idTagged: Boolean,
    val match: ReleaseMatch,
    val fileIdx: Int? = null,
    val fileName: String? = null
)

/** The TorBox cache answer for one hash. */
sealed class CacheVerdict {
    data class Cached(val name: String?, val size: Long?, val files: List<CachedFile>) : CacheVerdict()
    data object NotCached : CacheVerdict()
    /** The check failed or was not made: never shown as cached or uncached. */
    data object Unknown : CacheVerdict()
}

data class CachedFile(val id: Int?, val path: String, val size: Long?, val mimeType: String?)

/** A release ready to show: matched, cached, with the file to play when we could pin it. */
data class VerifiedRelease(
    val release: DiscoveredRelease,
    val quality: ReleaseQuality,
    /** File inside the torrent that holds the wanted movie/episode, when TorBox listed files. */
    val file: CachedFile?,
    val cachedName: String?,
    val cachedSize: Long?,
    /** Size of [file] as TorBox's own file list states it; null when no list pinned the file. */
    val confirmedSize: Long? = null
) {
    /** Size of what will actually play: the chosen file, else the file size a source stated, else the torrent. */
    val playSize: Long? get() = file?.size ?: release.sizeBytes ?: cachedSize

    /** Sort size: TorBox's listed file size first, else what [playSize] knows. */
    val sortSize: Long? get() = confirmedSize ?: playSize

    /** 4K by the release name or the pinned file's name. */
    val is4k: Boolean get() = quality.resolution == 2160 ||
        file?.path?.substringAfterLast('/')?.let { ReleaseQuality.parse(it).resolution == 2160 } == true
}
