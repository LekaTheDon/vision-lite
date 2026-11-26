package com.lekathedon.visionlite.tb

/**
 * Vision TB's pure stages, used by the app and by the measurement harness alike:
 * plan requests -> per source: match + claim new hashes -> TorBox cache check -> verify -> rank.
 */
object DiscoveryPipeline {
    /** Hashes per checkcached call without file lists (POST takes many; answers stay small). */
    const val CACHE_CHUNK = 250
    /** Hashes per checkcached call with file lists (TorBox's documented batch size for checks). */
    const val FILES_CHUNK = 100
    /** Most hashes one screen may cache-check across all sources. */
    const val MAX_CACHE_CHECKS = 1500
    /** Most cached packs whose file list is fetched to find the episode. */
    const val MAX_FILE_LISTS = 100

    private val TRUSTED_OVERRIDABLE = setOf("title", "no_year", "year_unknown", "no_episode_info")

    data class Rejection(val hash: String, val name: String, val source: DiscoverySource, val reason: String)

    data class Matched(val releases: List<DiscoveredRelease>, val rejected: List<Rejection>)

    data class Verified(
        val shown: List<VerifiedRelease>,
        val notCached: Int,
        val unknown: Int,
        /** Cached, title matched, but the episode's file wasn't found (or couldn't be checked). */
        val fileMissing: List<DiscoveredRelease>
    )

    /** What one source added: releases shown and counts for the log/harness. */
    data class SourceOutcome(
        val source: DiscoverySource,
        val candidates: Int,
        val matched: Int,
        val newHashes: Int,
        val verified: Verified,
        val fileListChecks: Int
    )

    fun plan(target: DiscoveryTarget, includeTorboxSearch: Boolean): List<SourceRequest> =
        planById(target, includeTorboxSearch) + planByText(target)

    /** Sources asked by IMDb id: they can start before the title metadata arrives. */
    fun planById(target: DiscoveryTarget, includeTorboxSearch: Boolean, includeRegional: Boolean = false): List<SourceRequest> {
        val out = ArrayList<SourceRequest>()
        if (includeTorboxSearch) DiscoverySources.torboxSearch(target)?.let(out::add)
        DiscoverySources.torrentio(target)?.let(out::add)
        if (includeRegional) DiscoverySources.torrentioRegional(target)?.let(out::add)
        DiscoverySources.comet(target)?.let(out::add)
        DiscoverySources.meteor(target)?.let(out::add)
        DiscoverySources.stremThru(target)?.let(out::add)
        DiscoverySources.peerflix(target)?.let(out::add)
        DiscoverySources.torrentsDb(target)?.let(out::add)
        DiscoverySources.zilean(target)?.let(out::add)
        DiscoverySources.apibay(target)?.let(out::add)
        return out
    }

    /**
     * With a TVDB hint: Torrentio and Comet asked again under this episode's number in TVDB's other orders (its
     * DVD number...); what they return is kept only when it names this episode (see matchOne).
     */
    fun planAlternates(target: DiscoveryTarget, max: Int = 1): List<SourceRequest> {
        val hint = target.episodeHint ?: return emptyList()
        return hint.alternates.distinct().take(max).flatMap { (s, e) ->
            val alt = target.copy(season = s, episode = e)
            listOfNotNull(DiscoverySources.torrentio(alt), DiscoverySources.comet(alt))
        }
    }

    /**
     * Anime only (after the metadata says anime): Comet by Kitsu id, TsukiHime and nekoBT by the Nyaa-style query,
     * Anime Tosho by AniDB id. Sources whose id is unknown are skipped. Up to 4 requests.
     */
    fun planAnime(target: DiscoveryTarget, nekoBtKey: String?): List<SourceRequest> {
        if (!target.isAnime) return emptyList()
        val out = ArrayList<SourceRequest>()
        AnimeSources.cometKitsu(target)?.let(out::add)
        AnimeSources.animeTosho(target)?.let(out::add)
        AnimeSources.animeQuery(target)?.let { q ->
            out += AnimeSources.tsukiHime(q)
            out += AnimeSources.nekoBt(q, nekoBtKey)
        }
        return out
    }

    /** Switch "Keyed sources": Jackettio and Meteor with the user's TorBox key. 2 requests, any title. */
    fun planKeyed(target: DiscoveryTarget, torboxKey: String): List<SourceRequest> =
        listOfNotNull(AnimeSources.jackettio(target, torboxKey), AnimeSources.meteorKeyed(target, torboxKey))

    /** Sources searched by title: need the titles (and the anime flag for Nyaa). */
    fun planByText(target: DiscoveryTarget): List<SourceRequest> {
        val out = ArrayList<SourceRequest>()
        val queries = QueryExpander.queries(target)
        queries.forEach { out += DiscoverySources.knaben(it) }
        queries.take(2).forEach { out += DiscoverySources.torrentsCsv(it) }
        if (target.isAnime) QueryExpander.animeQuery(target)?.let { out += DiscoverySources.nyaa(it) }
        return out
    }

    private fun matchOne(c: DiscoveryCandidate, target: DiscoveryTarget): ReleaseMatch {
        val base = matchByNumber(c, target)
        val hint = target.episodeHint ?: return base
        val evidence = listOfNotNull(c.name, c.fileName).map { EpisodeTitleCheck.evidence(it, hint, target.titles) }
        val confirms = TitleEvidence.CONFIRMS in evidence
        if (base.accepted) {
            // The right number but another episode's title: numbered in another order (Firefly's DVD "S01E01 Serenity").
            return if (!confirms && TitleEvidence.CONTRADICTS in evidence) ReleaseMatch.reject("other_episode_title") else base
        }
        // Numbered in another order: accepted only when it names this episode.
        if (confirms && base.reason in ALT_ORDER_REASONS) {
            for ((s, e) in hint.alternates) {
                if (matchByNumber(c, target.copy(season = s, episode = e, episodeHint = null)).accepted) {
                    return ReleaseMatch(ReleaseMatch.Kind.EPISODE, "alt_order")
                }
            }
        }
        return base
    }

    private val ALT_ORDER_REASONS = setOf("episode", "season")

    /** Sources whose rows are matched with Kitsu's titles too and never trusted by id alone. */
    val ANIME_SOURCES = setOf(DiscoverySource.COMET_KITSU, DiscoverySource.TSUKIHIME, DiscoverySource.ANIMETOSHO, DiscoverySource.NEKOBT)

    /** Manga / book / archive releases (Comet by Kitsu id returned manga volumes). */
    private val NOT_VIDEO = Regex("\\b(?:cbz|cbr|cb7|epub|pdf|mobi|azw3?)\\b|\\b(?:manga|light novel|artbook|scans?)\\b", RegexOption.IGNORE_CASE)

    /**
     * Strict rule (user 2026-11-26: "Monster" played two different shows on two boxes): a one-word show name ("Monster",
     * "Dark", "Lost") is many shows, and title-parsing indexes (text search, DMM/Zilean, TorBox search) mix them up, so
     * such a release must also name the show's year (±1).
     * Stremio add-ons that matched the id themselves (Torrentio, Comet…) and anime-only sources (named by the anime's own ids)
     * don't need it. Cost: none (text only).
     */
    private fun matchByNumber(c: DiscoveryCandidate, target: DiscoveryTarget): ReleaseMatch {
        val m = matchByNumber0(c, target)
        // The title's facts hold for every source, trusted add-ons included (TitleFacts).
        if (m.accepted) clash(c.name, target)?.let { return ReleaseMatch.reject(it) }
        if (m.accepted) c.fileName?.let { f -> clash(f, target)?.let { return ReleaseMatch.reject(it) } }
        if (!m.accepted || !target.isSeries || c.trustedMatch || c.source in ANIME_SOURCES || (target.isAnime && c.source == DiscoverySource.NYAA)) return m
        if (!oneWordTitle(target)) return m
        val year = target.year ?: return ReleaseMatch.reject("ambiguous_title")
        fun names(n: String?) = n != null && ReleaseMatcher.tokens(n).any { t -> t.toIntOrNull()?.let { kotlin.math.abs(it - year) <= 1 } == true }
        return if (names(c.name) || names(c.fileName)) m else ReleaseMatch.reject("ambiguous_title")
    }

    private val EP_MARK = Regex("""(?i)\bs\d{1,2}(?:e\d{1,3})?\b|\b\d{1,2}x\d{2,3}\b|\bseason\b|\bcomplete\b""")
    private val HIGH_END = Regex("""(?i)\b(2160p|4k|uhd|dovi|dv|hdr10\+?|hdr|dolby[ ._-]?vision|atmos)\b""")
    private val REMASTER = Regex("""(?i)\b(remaster(ed)?|upscaled?|ai[ ._-]?upscale)\b""")

    /**
     * A release that can't be this title, whatever source vouched for it (user 2026-11-26):
     *  - year: a show's release naming a year before the show began, or (season 1) well after it began, or after it
     *    ended; a film's release naming another year (years inside the title itself are ignored);
     *  - type: a 4K / HDR / Dolby Vision / Atmos copy of an anime that ended before 2015 (no such masters exist; the
     *    4K "Monster 2026" Netflix show is not the 2004 anime) unless it says remastered / upscaled.
     * Null = no clash. Text only.
     */
    internal fun clash(name: String, target: DiscoveryTarget): String? {
        // A hand fix (TitleFixes) says this release is never the title.
        if (TitleFixes.rejects(target.imdbId, name)) return "fix_reject"
        val y0 = target.year ?: return null
        val tokens = ReleaseMatcher.tokens(name)
        val titleTokens = target.titles.flatMap { ReleaseMatcher.tokens(it) }.toSet()
        val years = (if (target.isSeries) {
            val cut = tokens.indexOfFirst { EP_MARK.matches(it) }.let { if (it < 0) tokens.size else it }
            tokens.subList(0, cut)
        } else tokens.take(12)).filter { it !in titleTokens }.mapNotNull { it.toIntOrNull()?.takeIf { y -> y in 1920..2099 } }
        if (target.isSeries) {
            val last = (target.endYear ?: java.time.Year.now().value) + 1
            if (years.any { it < y0 - 1 || it > last }) return "year_clash"
            if (target.season == 1 && years.any { it > y0 + 1 }) return "year_clash"
        } else if (years.isNotEmpty() && years.none { kotlin.math.abs(it - y0) <= 1 }) return "year_clash"
        if (target.isSeries && target.isAnime && (target.endYear ?: y0) < 2015 && HIGH_END.containsMatchIn(name) && !REMASTER.containsMatchIn(name)) return "type_clash"
        return null
    }

    private fun oneWordTitle(target: DiscoveryTarget): Boolean {
        val main = target.titles.firstOrNull() ?: return false
        return ReleaseMatcher.tokens(main).filter { it != "the" }.size <= 1
    }

    private fun matchByNumber0(c: DiscoveryCandidate, target: DiscoveryTarget): ReleaseMatch {
        val animeNumbers = c.source in ANIME_SOURCES
        val byName = ReleaseMatcher.match(c.name, target, c.idTagged, animeNumbers)
        if (byName.accepted) return byName
        // Torrentio names packs in other languages ("Во все тяжкие / Breaking Bad / Сезон: 2"); its file is plain.
        val byFile = c.fileName?.let { ReleaseMatcher.match(it, target, c.idTagged, animeNumbers) }
        if (byFile != null && byFile.accepted) return byFile
        // An add-on that matched by id may name things in another language or without a year; it is
        // kept only when the name or file still shows the year / episode (guards add-on mismatches).
        if (c.trustedMatch && byName.reason in TRUSTED_OVERRIDABLE &&
            (ReleaseMatcher.hasIdEvidence(c.name, target) || c.fileName?.let { ReleaseMatcher.hasIdEvidence(it, target) } == true)
        ) {
            return ReleaseMatch(if (target.isSeries) ReleaseMatch.Kind.EPISODE else ReleaseMatch.Kind.MOVIE, "addon_id")
        }
        return byName
    }

    /**
     * @param keepLowQuality false drops CAM / TS / screener copies, the same filter the user's
     *   Torrentio applies (qualityfilter=scr,cam).
     */
    fun matchAndDedupe(candidates: List<DiscoveryCandidate>, target: DiscoveryTarget, keepLowQuality: Boolean = false): Matched {
        val rejected = ArrayList<Rejection>()
        val byHash = LinkedHashMap<String, MutableList<Pair<DiscoveryCandidate, ReleaseMatch>>>()
        // Anime sources name releases by their romaji / English titles: Kitsu's titles count as the show's too.
        val animeTarget by lazy {
            if (target.animeTitles.isEmpty()) target
            else target.copy(titles = (target.titles + target.animeTitles).distinctBy { ReleaseMatcher.normalizeTitle(it) })
        }
        for (c in candidates) {
            val anime = c.source in ANIME_SOURCES
            if (anime && (NOT_VIDEO.containsMatchIn(c.name) || c.fileName?.let { NOT_VIDEO.containsMatchIn(it) } == true)) {
                rejected += Rejection(c.hash, c.name, c.source, "not_video")
                continue
            }
            if (!keepLowQuality && (ReleaseQuality.parse(c.name).lowQuality || c.fileName?.let { ReleaseQuality.parse(it).lowQuality } == true)) {
                rejected += Rejection(c.hash, c.name, c.source, "low_quality")
                continue
            }
            // Can't play on this box / TV / internet (streams/DeviceFit): not worth a TorBox check (user 2026-11-24).
            if (com.lekathedon.visionlite.streams.DeviceFit.fit(c.name + " " + c.fileName.orEmpty()) ==
                com.lekathedon.visionlite.streams.DeviceFit.NO) {
                rejected += Rejection(c.hash, c.name, c.source, "device")
                continue
            }
            val m = matchOne(c, if (anime) animeTarget else target)
            if (!m.accepted) {
                rejected += Rejection(c.hash, c.name, c.source, m.reason)
                continue
            }
            if (c.sourceSaysCached == false) continue
            byHash.getOrPut(c.hash) { ArrayList() } += c to m
        }
        val releases = byHash.map { (hash, hits) ->
            // Same hash = same release, whatever each source called it. Name from the most trusted source.
            val best = hits.minBy { it.first.source.ordinal }
            val s = target.season
            val e = target.episode
            val fileHit = (if (s != null && e != null) {
                hits.firstOrNull { h -> h.first.fileName?.let { fileNamesEpisode(it, target, s, e) } == true }
            } else null) ?: hits.firstOrNull { it.first.fileName != null }
            DiscoveredRelease(
                hash = hash,
                name = best.first.name,
                sizeBytes = (fileHit ?: best).first.sizeBytes ?: hits.firstNotNullOfOrNull { it.first.sizeBytes },
                seeders = hits.mapNotNull { it.first.seeders }.maxOrNull(),
                sources = hits.map { it.first.source }.toSortedSet(),
                idTagged = hits.any { it.first.idTagged },
                match = best.second,
                fileIdx = fileHit?.first?.fileIdx ?: hits.firstNotNullOfOrNull { it.first.fileIdx },
                fileName = fileHit?.first?.fileName
            )
        }
        // A hash rejected by one source but accepted by another is not a rejection.
        val acceptedHashes = byHash.keys
        return Matched(releases, rejected.filter { it.hash !in acceptedHashes })
    }

    private fun fileNamesEpisode(fileName: String, season: Int, episode: Int, anime: Boolean): Boolean =
        CachedFilePicker.pickEpisode(listOf(CachedFile(null, fileName, null, null)), season, episode, anime) != null

    /** With a TVDB hint: a file naming this episode wins; one naming another episode is never the pick. */
    private fun fileNamesEpisode(fileName: String, target: DiscoveryTarget, season: Int, episode: Int): Boolean {
        val hint = target.episodeHint ?: return fileNamesEpisode(fileName, season, episode, target.isAnime)
        return when (EpisodeTitleCheck.evidence(fileName.substringAfterLast('/'), hint, target.titles)) {
            TitleEvidence.CONFIRMS -> true
            TitleEvidence.CONTRADICTS -> false
            TitleEvidence.NONE -> fileNamesEpisode(fileName, season, episode, target.isAnime)
        }
    }

    private fun pickEpisodeFile(files: List<CachedFile>, target: DiscoveryTarget, season: Int, episode: Int, seasonPack: Boolean = false): CachedFile? {
        val hint = target.episodeHint
            ?: return CachedFilePicker.pickEpisode(files, season, episode, allowAbsolute = target.isAnime, seasonPack = seasonPack)
        fun ev(f: CachedFile) = EpisodeTitleCheck.evidence(f.path.substringAfterLast('/'), hint, target.titles)
        // A pack in another order: the file with this episode's title is the one to play.
        CachedFilePicker.pickMovie(files.filter { ev(it) == TitleEvidence.CONFIRMS })?.let { return it }
        val byNumber = CachedFilePicker.pickEpisode(files, season, episode, allowAbsolute = target.isAnime, seasonPack = seasonPack) ?: return null
        return byNumber.takeIf { ev(it) != TitleEvidence.CONTRADICTS }
    }

    /** Hashes to cache-check, best-looking first, capped. */
    fun hashesToCheck(releases: List<DiscoveredRelease>, exclude: Set<String>, max: Int = MAX_CACHE_CHECKS): List<String> =
        releases.asSequence()
            .filter { it.hash !in exclude }
            .map { it to ReleaseQuality.parse(it.name).score() } // scored once, not per comparison
            .sortedWith(compareByDescending<Pair<DiscoveredRelease, Int>> { it.first.idTagged }
                .thenByDescending { it.second }
                .thenByDescending { it.first.seeders ?: 0 })
            .map { it.first.hash }
            .take(max)
            .toList()

    fun verify(
        releases: List<DiscoveredRelease>,
        verdicts: Map<String, CacheVerdict>,
        target: DiscoveryTarget
    ): Verified {
        val shown = ArrayList<VerifiedRelease>()
        val missing = ArrayList<DiscoveredRelease>()
        var notCached = 0
        var unknown = 0
        for (r in releases) {
            val v = verdicts[r.hash] ?: continue
            when (v) {
                CacheVerdict.NotCached -> notCached++
                CacheVerdict.Unknown -> unknown++
                is CacheVerdict.Cached -> {
                    // TorBox's own name for the torrent can expose a source's wrong hash
                    // (Comet listed "S03E03 When Flanders Failed" as S10E05): a contradiction drops it.
                    // A TVDB-confirmed title (another order's number) outweighs TorBox's numbering.
                    val titleConfirms = v.name != null && target.episodeHint?.let {
                        EpisodeTitleCheck.evidence(v.name, it, target.titles) == TitleEvidence.CONFIRMS
                    } == true
                    // Before the titles are known (quick pass) only the numbering can contradict.
                    val torboxReason = v.name?.let {
                        if (target.titles.isEmpty()) ReleaseMatcher.matchNumbersOnly(it, target).reason
                        else ReleaseMatcher.match(it, target, idTagged = true).reason
                    }
                    // Shows with TorBox's file list: the picked file must name this episode, so the torrent's
                    // name doesn't decide (TorBox names some season packs after one file inside: an S02 pack
                    // came back as "Breaking.Bad.S02E05...mkv" and hid S02E03, live 2026-11-04).
                    val fileListDecides = target.isSeries && v.files.isNotEmpty()
                    if (v.name != null && !titleConfirms && !fileListDecides && torboxReason in CONTRADICTIONS) {
                        missing += r
                        continue
                    }
                    val file = pickFile(r, v, target)
                    if (file == MISSING) {
                        missing += r
                    } else {
                        val confirmed = if (v.files.isNotEmpty()) file?.size?.takeIf { it > 0 } else null
                        shown += VerifiedRelease(r, ReleaseQuality.parse(r.name), file, v.name, v.size, confirmed)
                    }
                }
            }
        }
        return Verified(rank(shown), notCached, unknown, missing)
    }

    private val MISSING = CachedFile(null, "\u0000missing", null, null)
    private val CONTRADICTIONS = setOf("year", "season", "episode", "series_marker")

    /**
     * The file to play, pinned by name so TorBox's file picker plays exactly it. Null = no pin (a movie:
     * the picker then takes the largest video). [MISSING] = not shown until a file list pins the episode:
     * the picker only reads SxxEyy / NxNN itself, so "Title - 03" or "s02ep3" would not play unpinned.
     */
    private fun pickFile(r: DiscoveredRelease, v: CacheVerdict.Cached, target: DiscoveryTarget): CachedFile? {
        if (!target.isSeries) {
            // A listed torrent without a video (TorBox keeps some as one .zip) can't play.
            if (v.files.isNotEmpty()) return CachedFilePicker.pickMovie(v.files) ?: MISSING
            return r.fileName?.let { CachedFile(null, it, r.sizeBytes, null) }
        }
        val s = target.season ?: return MISSING
        val e = target.episode ?: return MISSING
        if (v.files.isNotEmpty()) {
            val seasonPack = CachedFilePicker.namesOnlySeason(r.name, s) || v.name?.let { CachedFilePicker.namesOnlySeason(it, s) } == true
            return pickEpisodeFile(v.files, target, s, e, seasonPack) ?: MISSING
        }
        // No file list: only the episode file a source named can be pinned; anything else asks for the list.
        return r.fileName?.takeIf { fileNamesEpisode(it, target, s, e) }?.let { CachedFile(null, it, r.sizeBytes, null) }
            ?: MISSING
    }

    /**
     * One source's results, end to end. [claim] returns the releases no earlier source took (so each
     * hash is checked and shown once); [check] asks TorBox (hashes, withFiles), Unknown on failure.
     */
    suspend fun processSource(
        source: DiscoverySource,
        candidates: List<DiscoveryCandidate>,
        target: DiscoveryTarget,
        claim: (List<DiscoveredRelease>) -> List<DiscoveredRelease>,
        check: suspend (List<String>, Boolean) -> Map<String, CacheVerdict>
    ): SourceOutcome {
        val matched = matchAndDedupe(candidates, target)
        val fresh = claim(matched.releases)
        val hashes = hashesToCheck(fresh, exclude = emptySet()).toSet()
        val toCheck = fresh.filter { it.hash in hashes }
        val firstVerdicts = if (hashes.isEmpty()) emptyMap() else check(hashes.toList(), false)
        val first = verify(toCheck, firstVerdicts, target)
        // Cached episodes/packs whose file no source named and whose answer had no file list: ask with lists.
        val packs = first.fileMissing
            .filter { (firstVerdicts[it.hash] as? CacheVerdict.Cached)?.files?.isEmpty() == true }
            .take(MAX_FILE_LISTS)
        val verified = if (packs.isEmpty()) first else {
            val second = verify(packs, check(packs.map { it.hash }, true), target)
            Verified(
                shown = rank(first.shown + second.shown),
                notCached = first.notCached,
                unknown = first.unknown + second.unknown,
                fileMissing = second.fileMissing + first.fileMissing.filter { it !in packs }
            )
        }
        return SourceOutcome(source, candidates.size, matched.releases.size, fresh.size, verified, packs.size)
    }

    /**
     * User rule (2026-11-04): 4K first, then size always wins, quality only breaks a tie in size. Names
     * that leave out "Remux"/"DV" no longer lose to smaller files that list them. 4K = the release or the
     * pinned file name says 2160p/4K/UHD. Size = TorBox's listed file size, else the stated one; rows with
     * no size go last. CAM-type releases stay at the bottom.
     */
    fun rank(items: List<VerifiedRelease>): List<VerifiedRelease> =
        items.sortedWith(
            compareBy<VerifiedRelease> { it.quality.lowQuality }
                .thenByDescending { it.is4k }
                .thenByDescending { it.sortSize ?: -1L }
                .thenByDescending { it.quality.score() }
                .thenBy { it.release.hash }
        )
}
