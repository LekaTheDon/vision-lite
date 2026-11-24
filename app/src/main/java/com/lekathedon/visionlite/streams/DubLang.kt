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
