package com.lekathedon.visionlite.streams

/**
 * Does a link say it carries subtitles in a language? (user 2026-11-26: autoplay picks the add-on whose link has
 * subtitles in your language.) Read from the link's own name / description, as add-ons write it: "Arabic Subs",
 * "Subs: ara, eng", "ESub", "مترجم", "VOSTFR", "Multi-Subs". 2 = says your language, 1 = "multi subs" (probably),
 * 0 = nothing. Pure text, no calls.
 */
object SubsIn {
    /**
     * Your subtitle languages whenever subtitles aren't Off (user 2026-11-27: set once, then always followed). The
     * "prefer over higher quality" switch only decides whether a link NAMING them beats 4K ([wanted]).
     */
    fun yours(): List<String> = runCatching {
        val p = com.lekathedon.visionlite.App.instance.prefs
        if (p.subsWhen.value == "off") emptyList() else listOf(p.subLang.value, p.subLang2.value).filter { it.length in 2..3 }.map { it.take(2).lowercase() }.distinct()
    }.getOrDefault(emptyList())

    /** The link lists its subtitle languages and none of yours is among them (and no MULTI subs): you'd get none. */
    fun lacksYours(text: String, langs: List<String>): Boolean {
        if (langs.isEmpty() || MULTI.containsMatchIn(text)) return false
        if (langs.any { score(text, it) > 0 }) return false
        // Named languages near a subtitle word, none of them yours.
        return SUB.findAll(text).any { m ->
            val win = text.substring((m.range.first - 40).coerceAtLeast(0), (m.range.last + 60).coerceAtMost(text.length))
            NAMES.values.any { it.containsMatchIn(win) }
        }
    }

    /** Your subtitle languages when the autoplay switch is on, else none. */
    fun wanted(): List<String> = runCatching {
        val p = com.lekathedon.visionlite.App.instance.prefs
        if (!p.autoplaySubs.value) emptyList() else listOf(p.subLang.value, p.subLang2.value).filter { it.length in 2..3 }.map { it.take(2).lowercase() }.distinct()
    }.getOrDefault(emptyList())

    private val SUB = Regex("""(?i)\b(subs?|subbed|subtitles?|subtitulad[ao]s?|legendad[ao]s?|sous[- ]titres?|untertitel|altyaz[ıi]|субтитры)\b""")
    private val MULTI = Regex("""(?i)\bmulti[- ._]?subs?\b|\bmultisub\b""")
    private val NAMES = mapOf(
        "ar" to """arabic|arab|ara|عربي""", "en" to """english|eng""", "fr" to """french|français|francais|fre|fra""",
        "de" to """german|deutsch|ger|deu""", "es" to """spanish|español|espanol|spa|latino""", "it" to """italian|italiano|ita""",
        "pt" to """portuguese|português|portugues|por|pt-br|brazilian""", "ru" to """russian|rus|русские""", "tr" to """turkish|türkçe|turkce|tur""",
        "hi" to """hindi|hin""", "ur" to """urdu|urd""", "fa" to """persian|farsi|fas|per""", "zh" to """chinese|chs|cht|chi|zho""",
        "ja" to """japanese|jpn""", "ko" to """korean|kor""",
    ).mapValues { Regex("""(?i)(?<![\p{L}])(${it.value})(?![\p{L}])""") }
    /** Words that mean "subtitled in X" on their own. */
    private val OWN = mapOf(
        "ar" to Regex("""مترجم"""), "en" to Regex("""(?i)\be-?subs?\b"""), "fr" to Regex("""(?i)\bvost(fr)?\b"""),
        "es" to Regex("""(?i)\bvose\b"""), "pt" to Regex("""(?i)\blegendado\b"""),
    )

    fun score(text: String, lang: String): Int {
        OWN[lang]?.let { if (it.containsMatchIn(text)) return 2 }
        val name = NAMES[lang] ?: return if (MULTI.containsMatchIn(text)) 1 else 0
        // The language named near a subtitle word ("Arabic Subs", "Subs: ara,eng", "Sub: Arabic").
        SUB.findAll(text).forEach { m ->
            val win = text.substring((m.range.first - 40).coerceAtLeast(0), (m.range.last + 60).coerceAtMost(text.length))
            if (name.containsMatchIn(win)) return 2
        }
        return if (MULTI.containsMatchIn(text)) 1 else 0
    }
}
