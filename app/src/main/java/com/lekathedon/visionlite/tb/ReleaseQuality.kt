package com.lekathedon.visionlite.tb

/**
 * What a release name says about its quality. Every field is null when the name doesn't
 * say it: a missing "HDR" tag means "not stated", never "SDR".
 */
data class ReleaseQuality(
    val resolution: Int?,
    val dolbyVision: Boolean?,
    val hdr10Plus: Boolean?,
    val hdr: Boolean?,
    val source: Source?,
    val codec: String?,
    val audio: Audio?,
    val lowQuality: Boolean
) {
    enum class Source(val rank: Int, val label: String) {
        REMUX(5, "Remux"), BLURAY(4, "BluRay"), WEB_DL(3, "WEB-DL"), WEBRIP(2, "WEBRip"),
        HDTV(1, "HDTV"), DVD(0, "DVD")
    }

    enum class Audio(val rank: Int, val label: String) {
        ATMOS(5, "Atmos"), TRUEHD(4, "TrueHD"), DTS_HD(4, "DTS-HD"), DTS_X(5, "DTS:X"),
        DDP(3, "DD+"), DTS(2, "DTS"), DD(1, "DD"), AAC(0, "AAC")
    }

    /** Sort key: higher is better. Unknowns add nothing. */
    fun score(): Int {
        var s = 0
        s += when (resolution) { 2160 -> 4000; 1080 -> 3000; 720 -> 2000; null -> 1500; else -> 1000 }
        if (dolbyVision == true) s += 400
        if (hdr10Plus == true) s += 250 else if (hdr == true) s += 200
        s += (source?.rank ?: 1) * 60
        s += (audio?.rank ?: 0) * 25
        if (lowQuality) s -= 5000
        return s
    }

    /** Short label for the stream list, e.g. "2160p DV HDR10 Remux Atmos". */
    fun label(): String = listOfNotNull(
        resolution?.let { "${it}p" },
        if (dolbyVision == true) "DV" else null,
        when { hdr10Plus == true -> "HDR10+"; hdr == true -> "HDR"; else -> null },
        source?.label,
        codec,
        audio?.label
    ).joinToString(" ")

    companion object {
        // Compiled once: parse() runs for every candidate on every stream screen.
        private val R0 = Regex("[._\\[\\]()]")
        private val R1 = Regex("[ -](2160p|4k)[ -]")
        private val R2 = Regex("[ -]1080[pi][ -]")
        private val R3 = Regex("[ -]720p[ -]")
        private val R4 = Regex("[ -](576p|480p|360p)[ -]")
        private val R5 = Regex("[ -]uhd[ -]")
        private val R6 = Regex("[ -](dv|dovi|dolby ?vision|dolbyvision)[ -]")
        private val R7 = Regex("hdr10(\\+|plus| \\+)")
        private val R8 = Regex("[ -]hdr(10)?[ -+]")
        private val R9 = Regex("[ -](remux|bdremux)[ -]")
        private val R10 = Regex("[ -](blu ?ray|bdrip|brrip|bd)[ -]")
        private val R11 = Regex("[ -]web ?-?dl[ -]|[ -]web[ -]")
        private val R12 = Regex("[ -]webrip[ -]")
        private val R13 = Regex("[ -](hdtv|pdtv)[ -]")
        private val R14 = Regex("[ -](dvdrip|dvd|dvd5|dvd9)[ -]")
        private val R15 = Regex("[ -](x265|h ?265|hevc)[ -]")
        private val R16 = Regex("[ -]av1[ -]")
        private val R17 = Regex("[ -](x264|h ?264|avc)[ -]")
        private val R18 = Regex("atmos")
        private val R19 = Regex("[ -]dts ?-?x[ -]|dts:x")
        private val R20 = Regex("truehd")
        private val R21 = Regex("dts ?-?hd|dts ?ma")
        private val R22 = Regex("[ -](ddp|dd\\+|eac3|e ac3|dd ?p ?5)")
        private val R23 = Regex("[ -]dts[ -]")
        private val R24 = Regex("[ -](dd ?5|ac3|dd2)")
        private val R25 = Regex("[ -]aac")
        private val R26 = Regex("[ -](cam|camrip|hdcam|hqcam|hq ?-?cam|ts|hd ?-?ts|telesync|tc|hd ?-?tc|telecine|scr|screener|dvdscr|pre ?-?dvd|predvdrip)[ -]")

        fun parse(name: String): ReleaseQuality {
            val n = " " + name.lowercase().replace(R0, " ") + " "
            val resolution = when {
                // An explicit pixel height wins over "UHD" (UHD BluRay sources are re-encoded to 1080p too).
                R1.containsMatchIn(n) -> 2160
                R2.containsMatchIn(n) -> 1080
                R3.containsMatchIn(n) -> 720
                R4.containsMatchIn(n) -> 480
                R5.containsMatchIn(n) -> 2160
                else -> null
            }
            val dv = R6.containsMatchIn(n).takeIf { it }
            val hdr10p = R7.containsMatchIn(n).takeIf { it }
            val hdr = R8.containsMatchIn(n).takeIf { it }
            val source = when {
                R9.containsMatchIn(n) -> Source.REMUX
                R10.containsMatchIn(n) -> Source.BLURAY
                R11.containsMatchIn(n) -> Source.WEB_DL
                R12.containsMatchIn(n) -> Source.WEBRIP
                R13.containsMatchIn(n) -> Source.HDTV
                R14.containsMatchIn(n) -> Source.DVD
                else -> null
            }
            val codec = when {
                R15.containsMatchIn(n) -> "HEVC"
                R16.containsMatchIn(n) -> "AV1"
                R17.containsMatchIn(n) -> "AVC"
                else -> null
            }
            val audio = when {
                R18.containsMatchIn(n) -> Audio.ATMOS
                R19.containsMatchIn(n) -> Audio.DTS_X
                R20.containsMatchIn(n) -> Audio.TRUEHD
                R21.containsMatchIn(n) -> Audio.DTS_HD
                R22.containsMatchIn(n) -> Audio.DDP
                R23.containsMatchIn(n) -> Audio.DTS
                R24.containsMatchIn(n) -> Audio.DD
                R25.containsMatchIn(n) -> Audio.AAC
                else -> null
            }
            val low = R26.containsMatchIn(n)
            // ".ts"/"TC" can be a container: a named BluRay/WEB/Remux source outranks the cam tags.
            val lowQuality = low && source !in setOf(Source.REMUX, Source.BLURAY, Source.WEB_DL, Source.WEBRIP)
            return ReleaseQuality(resolution, dv, hdr10p, hdr, source, codec, audio, lowQuality)
        }
    }
}

/** Picks the file to play inside a cached torrent, using TorBox's file list. */
object CachedFilePicker {
    private val VIDEO_EXT = Regex("\\.(mkv|mp4|m4v|avi|mov|ts|m2ts|webm|wmv)$", RegexOption.IGNORE_CASE)

    fun isVideo(file: CachedFile): Boolean =
        file.mimeType?.startsWith("video/", ignoreCase = true) == true || VIDEO_EXT.containsMatchIn(file.path)

    private fun basename(file: CachedFile) = file.path.substringAfterLast('/')

    private val SAMPLE = Regex("(?i)(^|[^a-z])(sample|trailer|featurette)([^a-z]|$)")

    private fun notSample(file: CachedFile) = !SAMPLE.containsMatchIn(basename(file))

    /** Biggest real video: the movie. Null when TorBox listed no video. */
    fun pickMovie(files: List<CachedFile>): CachedFile? =
        files.filter { isVideo(it) && notSample(it) }.maxByOrNull { it.size ?: 0L }

    /** Pack extras that also carry episode numbers: previews, menus, NCOP/NCED, specials, mini-series. */
    private val EXTRA = Regex(
        "(?i)(^|[^a-z])(previews?|menus?|nced|ncop|creditless|sp\\d+|specials?|extras?|bonus|pv|yokoku|mini ?anime|recap)([^a-z]|$)"
    )

    /**
     * Several numbered hits: the episode is the clearly biggest one (2x the next). Two files with the same
     * name: no pick, since the pin is the file name and playback can't tell them apart.
     */
    private fun clearPick(hits: List<CachedFile>): CachedFile? {
        if (hits.size <= 1) return hits.firstOrNull()
        val bySize = hits.sortedByDescending { it.size ?: 0L }
        val top = bySize[0]
        val next = bySize[1]
        if (hits.count { basename(it).equals(basename(top), ignoreCase = true) } > 1) return null
        val topSize = top.size ?: return null
        val nextSize = next.size ?: return null
        return top.takeIf { topSize >= 2 * nextSize }
    }

    /**
     * The episode's file: SxxEyy / NxNN / "Season 02. Episode 03" in the file name, or the bare number
     * ("- 03", "Ep 03", "03. Title") for season-1 anime and for packs that hold only this season
     * ([seasonPack]). Extras are skipped. Null when the torrent has no such file (a wrong or partial pack).
     */
    fun pickEpisode(files: List<CachedFile>, season: Int, episode: Int, allowAbsolute: Boolean, seasonPack: Boolean = false): CachedFile? {
        val videos = files.filter { isVideo(it) && notSample(it) }
        val pattern = Regex(
            "(?<![a-z0-9])(?:s0*${season}[ ._-]?(?:ep?|episode)[ ._-]?0*${episode}|0*${season}x0*${episode}|season[ ._-]{0,3}0*${season}[ ._-]{0,3}episode[ ._-]{0,3}0*${episode})(?![0-9])",
            RegexOption.IGNORE_CASE
        )
        videos.filter { pattern.containsMatchIn(basename(it)) }.maxByOrNull { it.size ?: 0L }?.let { return it }
        if ((allowAbsolute && season == 1) || seasonPack) {
            val absolute = Regex("(?:^|[ _-])(?:e|ep)?0*${episode}(?:v\\d)?(?=[ ._\\[(-]|$)", RegexOption.IGNORE_CASE)
            val hits = videos.filter { absolute.containsMatchIn(basename(it).substringBeforeLast('.').replace(Regex("[\\[(][^\\])]*[\\])]"), " ")) }
            // Inside the torrent's own folder only: a pack called "... [BD + Extras]" is not all extras.
            return clearPick(hits.filterNot { EXTRA.containsMatchIn(if ('/' in it.path) it.path.substringAfter('/') else it.path) })
        }
        return null
    }

    private val SEASON_TOKEN = Regex("(?i)(?<![a-z0-9])(?:s(\\d{1,2})(?!\\d)(?![ ._-]?e\\d)|season[ ._-]{0,3}(\\d{1,2})(?!\\d)|сезон[ ._-]{0,3}(\\d{1,2})(?!\\d))")
    private val SEASON_RANGE = Regex("(?i)(?<![a-z0-9])s?\\d{1,2}[ ]*[-–~][ ]*s?\\d{1,2}(?![0-9])(?![ ._-]?(?:e|x)\\d)|seasons|complete series|integrale")

    /** A torrent named for this one season only ("S02", "[S02]", "Season 2"): bare episode numbers in it are this season's. */
    fun namesOnlySeason(name: String, season: Int): Boolean {
        val seasons = SEASON_TOKEN.findAll(name).mapNotNull { m -> m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toIntOrNull() }.toSet()
        return seasons == setOf(season) && !SEASON_RANGE.containsMatchIn(name.replace(Regex("(?<![0-9])(19|20)\\d{2}[ ]*[-–][ ]*(19|20)\\d{2}"), " "))
    }
}
