package com.lekathedon.visionlite.tb

/** Info hashes the user's add-ons already returned, read into Vision TB candidates (no extra requests). */
object AddonHarvest {
    private val HEX40 = Regex("^[0-9a-fA-F]{40}$")
    private val SIZE = Regex("💾\\s*([\\d.]+)\\s*([KMGT])i?B", RegexOption.IGNORE_CASE)

    /**
     * The hash in a playback URL's path, when exactly one segment is a 40-hex hash (AIOStreams torz
     * links, Torrentio resolve links). Opaque or ambiguous URLs give null. The URL is never logged.
     */
    fun hashFromUrl(url: String?): String? {
        if (url.isNullOrBlank() || !url.startsWith("http", ignoreCase = true)) return null
        val path = url.substringAfter("://").substringAfter('/', "").substringBefore('?').substringBefore('#')
        val hits = path.split('/').filter { HEX40.matches(it) }.distinct()
        return hits.singleOrNull()?.lowercase()
    }

    /**
     * One add-on row as a candidate. [infoHash] is the row's own hash (Stream.getEffectiveInfoHash);
     * otherwise the URL path is read. Name = first line of the description/title, else the file name.
     */
    fun candidate(
        infoHash: String?,
        url: String?,
        title: String?,
        description: String?,
        fileName: String?,
        videoSize: Long?,
        fileIdx: Int?
    ): DiscoveryCandidate? {
        val hash = InfoHashes.normalize(infoHash) ?: hashFromUrl(url) ?: return null
        val text = description ?: title.orEmpty()
        val firstLine = text.lineSequence().firstOrNull()
            ?.replace(Regex("^[^\\p{L}\\p{N}\\[(]+"), "")?.trim()?.takeIf { it.isNotEmpty() }
        // Formatted add-ons (AIOStreams) often show tags, not the release name: the file name is safer then.
        val name = fileName?.takeIf { firstLine == null || !looksLikeRelease(firstLine) } ?: firstLine ?: fileName ?: return null
        val size = videoSize ?: SIZE.find(text)?.let { m ->
            val mult = when (m.groupValues[2].uppercase()) { "K" -> 1L shl 10; "M" -> 1L shl 20; "G" -> 1L shl 30; else -> 1L shl 40 }
            m.groupValues[1].toDoubleOrNull()?.let { (it * mult).toLong() }
        }
        return DiscoveryCandidate(
            hash = hash,
            name = name,
            sizeBytes = size,
            seeders = null,
            source = DiscoverySource.ADDONS,
            idTagged = true,
            // Some add-ons search by title text: the name must carry the show's title (no add-on is taken on trust).
            trustedMatch = false,
            fileIdx = fileIdx,
            fileName = fileName
        )
    }

    /** A release name has dots/underscores between words or a year/resolution token. */
    private fun looksLikeRelease(s: String): Boolean =
        s.count { it == '.' || it == '_' } >= 3 || Regex("(19|20)\\d{2}|\\b\\d{3,4}p\\b|S\\d{1,2}E\\d{1,3}", RegexOption.IGNORE_CASE).containsMatchIn(s)
}

