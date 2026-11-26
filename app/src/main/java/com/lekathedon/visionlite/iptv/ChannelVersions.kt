package com.lekathedon.visionlite.iptv

import com.lekathedon.visionlite.App

/**
 * Channel versions (TiviMate-style, user 2026-11-26): the same channel listed more than once ("FR: BEIN SPORTS 1 4K",
 * "FR: beIN SPORTS 1 HD", "FR: BEIN SPORTS 1 HEVC"). Same = same region prefix and the same name once quality tags
 * are taken off, or the same guide id in the same playlist. "+1" (an hour later) is never a version of the live one.
 * Used for: auto-switch when a channel won't play (Player), the "Other versions" buttons (Live TV), hiding +1 channels.
 * Cost: one LIKE scan of ch (~57k rows, tens of ms) only when a channel fails or is focused; no network.
 */
object ChannelVersions {
    private val prefixRe = Regex("^\\s*(?:\\|([^|]{1,12})\\||([^:|]{1,12})[:|])\\s*")
    private val qualityTags = Regex("(?i)\\b(4k|8k|uhd|fhd|hd\\+?|sd|hevc|h\\.?26[45]|x26[45]|raw|backup|multi(?:audio)?|50\\s?fps|60\\s?fps|fps|1080p?|720p?|2160p?)\\b")

    fun isPlusOne(name: String) = Regex("(?:^| )\\+1(?: |$)").containsMatchIn(base(name))

    /** Region prefix ("FR", "UK", "TR VIP"), lower case, or "" when there is none. */
    fun prefix(name: String): String = prefixRe.find(name)?.let { (it.groupValues[1].ifEmpty { it.groupValues[2] }).trim().lowercase() }.orEmpty()

    /** Superscript tags (ᴴᴰ ᴿᴬᵂ), symbols (◉ ★) and bracket noise off; letters of any script and digits kept. */
    private fun clean(name: String): String = buildString {
        for (ch in name) {
            val t = Character.getType(ch)
            when {
                ch.code in 0x1D00..0x1DBF || ch.code in 0x2070..0x209F || ch in "ᵃᵇᶜᵈᵉᶠᵍʰⁱʲᵏˡᵐⁿᵒᵖʳˢᵗᵘᵛʷˣʸᶻ" -> append(' ')
                t == Character.OTHER_SYMBOL.toInt() || t == Character.MATH_SYMBOL.toInt() && ch != '+' -> append(' ')
                else -> append(ch)
            }
        }
    }

    /** The name a channel's versions share: prefix, quality tags and symbols off, "+1" kept. */
    fun base(name: String): String {
        var s = clean(name.replaceFirst(prefixRe, ""))
        s = s.replace(Regex("(\\d)\\+\\s?1\\b"), "$1 +1")
        s = s.replace(qualityTags, " ").lowercase()
        return s.replace(Regex("[^\\p{L}\\p{N}+]+"), " ").replace(Regex("(^| )\\+(?!\\d)"), "$1").replace(Regex(" +"), " ").trim()
    }

    /** Higher = better picture: 4K 4, FHD 3, HD / HEVC 2, no tag 1.5, SD 1. */
    fun quality(name: String): Double {
        val s = clean(name).lowercase() + " " + name.lowercase()
        return when {
            Regex("\\b(4k|uhd|2160p?|8k)\\b").containsMatchIn(s) -> 4.0
            Regex("\\b(fhd|1080p?)\\b").containsMatchIn(s) -> 3.0
            Regex("\\b(hd\\+?|hevc|h\\.?265|720p?)\\b").containsMatchIn(s) || name.contains("ᴴᴰ") -> 2.0
            Regex("\\bsd\\b").containsMatchIn(s) -> 1.0
            else -> 1.5
        }
    }

    /** Short label for a version's button: its quality tag, else its playlist position. */
    fun label(c: Channel): String = when (quality(c.name)) { 4.0 -> "4K"; 3.0 -> "FHD"; 2.0 -> if (Regex("(?i)hevc").containsMatchIn(c.name)) "HEVC" else "HD"; 1.0 -> "SD"; else -> "#${c.n}" }

    /** The other versions of [c], best picture first (same playlist before others). */
    fun of(c: Channel): List<Channel> {
        val b = base(c.name)
        if (b.isBlank()) return emptyList()
        val pre = prefix(c.name)
        val plus = isPlusOne(c.name)
        // LIKE on its (up to 3 longest) words narrows the scan; the exact test is base() below.
        val words = b.split(' ').filter { it.isNotBlank() && it != "+1" }.sortedByDescending { it.length }.take(3)
        if (words.isEmpty()) return emptyList()
        val found = Iptv.versionCandidates(words, c.tvg?.takeIf { it.isNotBlank() }, c.pl)
        return found.asSequence()
            .filter { it.key != c.key && isPlusOne(it.name) == plus && prefix(it.name) == pre }
            .filter { base(it.name) == b || (c.tvg != null && c.tvg.isNotBlank() && it.tvg == c.tvg && it.pl == c.pl) }
            .distinctBy { it.url }
            .sortedWith(compareByDescending<Channel> { quality(it.name) }.thenBy { if (it.pl == c.pl) 0 else 1 }.thenBy { it.n })
            .take(12).toList()
    }

    // ---- auto-switch: one failing channel walks through its versions once, never in circles ----

    @Volatile private var tried: MutableSet<String> = HashSet()
    /** The key the last auto-switch tuned to: a player on any other key means you picked it, so a fresh start. */
    @Volatile private var pending: String? = null

    /** Called when a live channel opens. */
    fun opened(key: String) {
        if (key != pending) tried = HashSet()
        pending = null
        tried.add(key)
    }

    /** The next version to try for the failing [c], or null (off, none left). */
    fun next(c: Channel): Channel? {
        if (!App.instance.prefs.iptvAutoSwitch.value) return null
        val n = of(c).firstOrNull { it.key !in tried } ?: return null
        tried.add(n.key); pending = n.key
        return n
    }
}
