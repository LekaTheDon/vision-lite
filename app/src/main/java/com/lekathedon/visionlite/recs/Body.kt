package com.lekathedon.visionlite.recs

/**
 * Keywords about bodies, nudity or sex never steer a pick or name its reason (user 2026-11-24: "because you like Shogun:
 * more male rear nudity"). Titles that show such scenes are not blocked; the words just carry no weight.
 * Adult.kt keeps its own list for the adult-content check, which is a different job.
 */
object Body {
    private val RX = Regex(
        "\\b(nud(e|ity|ist|ism)|naked|topless|bottomless|nipples?|breasts?|boobs?|cleavage|butt(ocks)?|bum|rear|" +
        "genital(s|ia)?|penis|vagina|pubic|crotch|groin|testicles?|bare (chest|back|feet|legs|shoulders)|shirtless|" +
        "sex(ual|uality|y|ism)?|erotic(a|ism)?|orgasm|masturbat\\w*|foreplay|intercourse|threesome|orgy|fetish\\w*|" +
        "lingerie|underwear|panties|bra|thong|bikini|striptease|stripper|strip club|lap dance|prostitut\\w*|" +
        "brothel|porn\\w*|seduc\\w*|lust|kinky|bdsm|voyeur\\w*|condom|virginity|one night stand|affair|lovemaking|" +
        "making love|kiss(ing)?|sensual\\w*|innuendo)\\b")

    fun word(k: String): Boolean = RX.containsMatchIn(k.lowercase())
}
