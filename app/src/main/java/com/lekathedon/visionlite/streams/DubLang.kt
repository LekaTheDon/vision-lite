package com.lekathedon.visionlite.streams

/**
 * Which language a release or VOD title is dubbed in, read from its own name / category (user 2026-11-24, Preferred
 * dub). Only explicit dub words count ("مدبلج", "Arabic Dubbed", "Hindi Dub", "VFF", "Latino", "Dublado"); a name with
 * no dub word is not a dub. Pure text, no calls.
 */
object DubLang {
    private val MARK = Regex("""(?i)\b(dub|dubbed|dubs|dublado|doblad[ao]|doublé|synchro|dublaj|dublat|duplicado)\b|مدبلج|دبلجة|دوبلاج""")
    private val ARABIC_MARK = Regex("مدبلج|دبلجة|دوبلاج")
    private val ARABIC = Regex("[\\u0621-\\u064A]")
    /** Words that name the dub's language without a separate dub word (French VFF/TRUEFRENCH, Latino Spanish). */
    private val IMPLIED = listOf(Regex("""(?i)\b(vff|vf2|truefrench|vfq)\b""") to "fr", Regex("""(?i)\b(latino|castellano)\b""") to "es")
    private val WORDS = listOf(
        "ar" to Regex("""(?i)\b(arabic|arab|ara)\b|عربي"""), "en" to Regex("""(?i)\b(english|eng)\b"""),
        "fr" to Regex("""(?i)\b(french|français|francais|fre)\b"""), "de" to Regex("""(?i)\b(german|deutsch|ger)\b"""),
        "es" to Regex("""(?i)\b(spanish|español|espanol|spa)\b"""), "it" to Regex("""(?i)\b(italian|italiano|ita)\b"""),
        "pt" to Regex("""(?i)\b(portuguese|português|portugues|por|brazil)\b"""), "ru" to Regex("""(?i)\b(russian|rus)\b"""),
        "tr" to Regex("""(?i)\b(turkish|türkçe|turkce|tur)\b"""), "hi" to Regex("""(?i)\b(hindi|hin)\b"""),
        "ja" to Regex("""(?i)\b(japanese|jpn)\b"""), "ko" to Regex("""(?i)\b(korean|kor)\b"""), "zh" to Regex("""(?i)\b(chinese|mandarin|chi)\b"""),
        "sr" to Regex("""(?i)\b(serbian|srpski)\b"""), "hr" to Regex("""(?i)\b(croatian|hrvatski)\b""")
    )
    /** "AR - Title", "EN| Title", "|FR| Title": the provider's language tag in front. */
    private val PREFIX = Regex("""^\s*\|?\s*([A-Za-z]{2})\s*[-:|•]""")

    /**
     * The language autoplay should look for a dub in: Settings › Audio "Preferred dub", or when that is Off, the Audio
     * language itself if it names a language (user 2026-11-26: Arabic audio on Case Closed must play the Arabic dub).
     */
    fun wanted(): String = runCatching {
        val p = com.lekathedon.visionlite.App.instance.prefs
        p.dubLang.value.ifEmpty { p.audioLang.value.takeIf { it.length in 2..3 && it != "original" }.orEmpty() }
    }.getOrDefault("")

    /** "AR-KID - …", "AR-ANM-D - …", "EN| …", "|FR| …": the provider's tag in front, split ("AR", "-KID"). */
    private val VOD_TAG = Regex("""^\s*\|?\s*([A-Za-z]{2})((?:[-_ ][A-Za-z0-9+]{1,4}){0,3})\s*[-:|•]\s""")
    /** Subtitled, not dubbed: Arabic "مترجم", VOSTFR, "multi-subs", "subbed". */
    private val SUBBED = Regex("""(?i)مترجم|\bvost(fr)?\b|\bsub(s|bed|titled)?\b|multi[- ]?subs""")

    /**
     * A VOD title's dub language (user 2026-11-26): explicit dub words first; else the provider's language tag when the
     * title was made in another language and nothing says subtitled ("AR-KID - المحقق كونان" in old cartoons = the
     * Arabic dub of a Japanese show; "AR-ANM-S" or "… مترجمة" = Japanese audio with Arabic subtitles). [original] is
     * the title's original language (TMDB); unknown = only explicit words count.
     */
    fun ofVod(name: String, group: String, original: String?): String? {
        of("$name $group")?.let { return it }
        val m = VOD_TAG.find(name) ?: return null
        val lang = m.groupValues[1].lowercase()
        val segs = m.groupValues[2].split('-', '_', ' ').filter { it.isNotBlank() }.map { it.uppercase() }
        if ("S" in segs || SUBBED.containsMatchIn("$name $group")) return null
        if (original.isNullOrBlank() || original == lang) return null
        return lang.takeIf { "D" in segs || WORDS.any { w -> w.first == it } }
    }

    /** Flags add-ons print for the audio (🇪🇸, 🇮🇹…): country → language. */
    private val FLAG_LANG = mapOf("GB" to "en", "US" to "en", "AU" to "en", "CA" to "en", "ES" to "es", "MX" to "es", "AR" to "es",
        "IT" to "it", "FR" to "fr", "DE" to "de", "AT" to "de", "JP" to "ja", "KR" to "ko", "CN" to "zh", "TW" to "zh", "HK" to "zh",
        "RU" to "ru", "UA" to "uk", "PT" to "pt", "BR" to "pt", "TR" to "tr", "IN" to "hi", "SA" to "ar", "AE" to "ar", "EG" to "ar",
        "PL" to "pl", "NL" to "nl", "SE" to "sv", "DK" to "da", "NO" to "no", "FI" to "fi", "GR" to "el", "CZ" to "cs", "HU" to "hu",
        "RO" to "ro", "IL" to "he", "TH" to "th", "VN" to "vi", "ID" to "id", "RS" to "sr", "HR" to "hr", "IS" to "is")
    private val MULTI = Regex("""(?i)\b(multi|dual[ .-]?audio|dual|multi[ .-]?audio)\b|🌐""")

    /**
     * Every audio language a link names (words, the provider's tag, flags), or null when it names none or says
     * MULTI / Dual audio (any language may be inside). Used by autoplay for Audio "Original" (user 2026-11-27).
     */
    fun named(text: String): Set<String>? {
        if (MULTI.containsMatchIn(text)) return null
        val out = HashSet<String>()
        WORDS.forEach { (c, r) -> if (r.containsMatchIn(text)) out += c }
        IMPLIED.forEach { (r, c) -> if (r.containsMatchIn(text)) out += c }
        if (ARABIC.containsMatchIn(text)) out += "ar"
        val cps = text.codePoints().toArray()
        var i = 0
        while (i + 1 < cps.size) {
            val a = cps[i]; val b = cps[i + 1]
            if (a in 0x1F1E6..0x1F1FF && b in 0x1F1E6..0x1F1FF) {
                val cc = "${'A' + (a - 0x1F1E6)}${'A' + (b - 0x1F1E6)}"
                FLAG_LANG[cc]?.let { out += it }
                i += 2
            } else i++
        }
        return out.ifEmpty { null }
    }

    /** The dub language of [text] (a name, plus its category), or null when it doesn't say it is dubbed. */
    fun of(text: String): String? {
        IMPLIED.firstOrNull { it.first.containsMatchIn(text) }?.let { return it.second }
        if (!MARK.containsMatchIn(text)) return null
        if (ARABIC_MARK.containsMatchIn(text)) return "ar"
        WORDS.firstOrNull { it.second.containsMatchIn(text) }?.let { return it.first }
        PREFIX.find(text)?.groupValues?.get(1)?.lowercase()?.let { c -> if (WORDS.any { it.first == c }) return c }
        return if (ARABIC.containsMatchIn(text)) "ar" else null
    }
}
