package com.lekathedon.visionlite.sport

/**
 * Our own league list (written 2026-11-27). [key] is ESPN's public path ("soccer/eng.1"). [usStyle]: away team named
 * first ("Bears at Lions"); [minutes]: a broadcast's usual length (Record); [regions]: where the league is shown first
 * ("" = everywhere); [words]: how TV guides name it; [network]: the league's own channel, if it has one.
 */
data class League(
    val key: String, val name: String, val short: String, val sport: Sport, val regions: String = "",
    val usStyle: Boolean = false, val minutes: Int = 120, val words: List<String> = emptyList(), val network: String? = null,
    val table: Table = Table.POINTS,
) {
    val logo get() = "https://a.espncdn.com/i/leaguelogos/${sport.logoPath}/500/${key.substringAfter('/')}.png"
}

enum class Table { POINTS, US, HOCKEY, DRIVERS, NONE }

enum class Sport(val label: String, val logoPath: String, val words: List<String>) {
    SOCCER("Football", "soccer", listOf("football", "soccer", "futbol", "fútbol", "calcio", "fussball")),
    FOOTBALL("American football", "football", listOf("nfl", "american football", "college football", "ncaaf")),
    BASKETBALL("Basketball", "basketball", listOf("basketball", "nba", "wnba", "euroleague", "ncaab")),
    HOCKEY("Ice hockey", "hockey", listOf("hockey", "nhl", "ice hockey")),
    BASEBALL("Baseball", "baseball", listOf("baseball", "mlb")),
    RACING("Motor racing", "racing", listOf("formula 1", "f1", "grand prix", "nascar", "indycar", "motogp")),
    GOLF("Golf", "golf", listOf("golf", "pga", "lpga", "dp world", "liv golf")),
    TENNIS("Tennis", "tennis", listOf("tennis", "atp", "wta", "open")),
    FIGHT("Fighting", "mma", listOf("ufc", "mma", "boxing", "bellator", "pfl")),
    RUGBY("Rugby", "rugby", listOf("rugby", "six nations", "premiership rugby", "super rugby", "nrl")),
    CRICKET("Cricket", "cricket", listOf("cricket", "test match", "t20", "odi", "ipl")),
    AUSSIE("Aussie rules", "australian-football", listOf("afl", "aussie rules")),
    OTHER("More", "", emptyList()),
}

object Leagues {
    private fun s(key: String, name: String, short: String, regions: String = "", words: List<String> = emptyList(), minutes: Int = 120, table: Table = Table.POINTS) =
        League(key, name, short, Sport.SOCCER, regions, words = words, minutes = minutes, table = table)
    private fun us(key: String, name: String, short: String, sport: Sport, minutes: Int, network: String? = null, table: Table = Table.US, words: List<String> = emptyList()) =
        League(key, name, short, sport, "US,CA", usStyle = true, minutes = minutes, words = listOf(short.lowercase()) + words, network = network, table = table)

    val all: List<League> = listOf(
        // Football: Europe's leagues and cups first, then the rest of the world.
        s("soccer/eng.1", "Premier League", "EPL", "GB,IE", listOf("premier league", "epl")),
        s("soccer/eng.2", "Championship", "EFL", "GB,IE", listOf("efl championship", "sky bet championship")),
        s("soccer/eng.fa", "FA Cup", "FA Cup", "GB,IE", listOf("fa cup", "emirates fa cup"), table = Table.NONE),
        s("soccer/eng.league_cup", "League Cup", "EFL Cup", "GB,IE", listOf("carabao cup", "league cup", "efl cup"), table = Table.NONE),
        s("soccer/uefa.champions", "Champions League", "UCL", words = listOf("champions league", "ucl")),
        s("soccer/uefa.europa", "Europa League", "UEL", words = listOf("europa league", "uel")),
        s("soccer/uefa.europa.conf", "Conference League", "UECL", words = listOf("conference league", "uecl")),
        s("soccer/uefa.nations", "Nations League", "UNL", words = listOf("nations league")),
        s("soccer/uefa.euro", "European Championship", "EURO", words = listOf("euro 20", "uefa euro")),
        s("soccer/esp.1", "LaLiga", "LaLiga", "ES", listOf("laliga", "la liga")),
        s("soccer/esp.copa_del_rey", "Copa del Rey", "Copa", "ES", listOf("copa del rey"), table = Table.NONE),
        s("soccer/ita.1", "Serie A", "Serie A", "IT", listOf("serie a")),
        s("soccer/ger.1", "Bundesliga", "BL", "DE,AT,CH", listOf("bundesliga")),
        s("soccer/fra.1", "Ligue 1", "L1", "FR,BE", listOf("ligue 1")),
        s("soccer/por.1", "Primeira Liga", "Liga PT", "PT", listOf("liga portugal", "primeira liga")),
        s("soccer/ned.1", "Eredivisie", "ERE", "NL,BE", listOf("eredivisie")),
        s("soccer/sco.1", "Scottish Premiership", "SPFL", "GB", listOf("scottish premiership", "spfl")),
        s("soccer/tur.1", "Süper Lig", "Süper Lig", "TR", listOf("super lig", "süper lig")),
        s("soccer/ksa.1", "Saudi Pro League", "SPL", "SA,AE,QA,KW,BH,OM,EG", listOf("saudi pro league", "roshn")),
        s("soccer/afc.champions", "AFC Champions League", "ACL", "SA,AE,QA,KW,BH,OM,JP,KR,AU", listOf("afc champions league"), table = Table.NONE),
        s("soccer/usa.1", "MLS", "MLS", "US,CA", listOf("mls", "major league soccer")),
        s("soccer/mex.1", "Liga MX", "Liga MX", "MX,US", listOf("liga mx")),
        s("soccer/bra.1", "Brasileirão", "Série A", "BR", listOf("brasileirao", "brasileirão")),
        s("soccer/arg.1", "Liga Profesional", "LPF", "AR", listOf("liga profesional")),
        s("soccer/conmebol.libertadores", "Copa Libertadores", "Libertadores", words = listOf("libertadores"), table = Table.NONE),
        s("soccer/fifa.world", "World Cup", "World Cup", words = listOf("world cup", "fifa world cup"), table = Table.NONE),
        s("soccer/fifa.worldq.uefa", "World Cup qualifying (Europe)", "WCQ", words = listOf("world cup qualifier", "wc qualifier"), table = Table.NONE),
        s("soccer/fifa.cwc", "Club World Cup", "CWC", words = listOf("club world cup"), table = Table.NONE),
        s("soccer/fifa.friendly", "Friendlies", "Friendly", words = listOf("international friendly"), table = Table.NONE),
        s("soccer/conmebol.america", "Copa América", "Copa América", words = listOf("copa america", "copa américa"), table = Table.NONE),
        s("soccer/concacaf.gold", "Gold Cup", "Gold Cup", "US,CA,MX", listOf("gold cup"), table = Table.NONE),
        s("soccer/concacaf.nations.league", "CONCACAF Nations League", "CNL", "US,CA,MX", listOf("concacaf nations league"), table = Table.NONE),
        s("soccer/fifa.worldq.concacaf", "World Cup qualifying (CONCACAF)", "WCQ", "US,CA,MX", listOf("world cup qualifier"), table = Table.NONE),
        s("soccer/eng.w.1", "Women's Super League", "WSL", "GB", listOf("wsl", "women's super league")),
        // US leagues.
        us("football/nfl", "NFL", "NFL", Sport.FOOTBALL, 210, "NFL Network", words = listOf("nfl football", "sunday night football", "monday night football", "thursday night football")),
        us("football/college-football", "College Football", "NCAAF", Sport.FOOTBALL, 210, "SEC Network", words = listOf("college football", "ncaa football")),
        us("basketball/nba", "NBA", "NBA", Sport.BASKETBALL, 150, "NBA TV", words = listOf("nba basketball")),
        us("basketball/wnba", "WNBA", "WNBA", Sport.BASKETBALL, 120),
        us("basketball/mens-college-basketball", "College Basketball", "NCAAM", Sport.BASKETBALL, 150, words = listOf("college basketball", "ncaa basketball")),
        us("hockey/nhl", "NHL", "NHL", Sport.HOCKEY, 180, "NHL Network", Table.HOCKEY, listOf("nhl hockey")),
        us("baseball/mlb", "MLB", "MLB", Sport.BASEBALL, 210, "MLB Network", words = listOf("mlb baseball")),
        League("basketball/euroleague", "EuroLeague", "EuroLeague", Sport.BASKETBALL, minutes = 120, words = listOf("euroleague")),
        // Racing, golf, tennis, fighting: events with their own titles.
        League("racing/f1", "Formula 1", "F1", Sport.RACING, minutes = 150, words = listOf("formula 1", "f1", "grand prix"), network = "F1 TV", table = Table.DRIVERS),
        League("racing/nascar-premier", "NASCAR Cup Series", "NASCAR", Sport.RACING, "US", minutes = 240, words = listOf("nascar"), table = Table.DRIVERS),
        League("racing/irl", "IndyCar", "IndyCar", Sport.RACING, "US", minutes = 180, words = listOf("indycar", "indy 500"), table = Table.DRIVERS),
        League("golf/pga", "PGA Tour", "PGA", Sport.GOLF, minutes = 300, words = listOf("pga tour"), network = "Golf Channel", table = Table.NONE),
        League("golf/eur", "DP World Tour", "DPWT", Sport.GOLF, minutes = 300, words = listOf("dp world tour"), table = Table.NONE),
        League("golf/lpga", "LPGA Tour", "LPGA", Sport.GOLF, minutes = 240, words = listOf("lpga"), table = Table.NONE),
        League("golf/liv", "LIV Golf", "LIV", Sport.GOLF, minutes = 300, words = listOf("liv golf"), table = Table.NONE),
        League("tennis/atp", "ATP Tour", "ATP", Sport.TENNIS, minutes = 180, words = listOf("atp"), network = "Tennis Channel", table = Table.NONE),
        League("tennis/wta", "WTA Tour", "WTA", Sport.TENNIS, minutes = 180, words = listOf("wta"), table = Table.NONE),
        League("mma/ufc", "UFC", "UFC", Sport.FIGHT, minutes = 300, words = listOf("ufc"), table = Table.NONE),
        // Rugby, cricket, Aussie rules.
        League("rugby/180659", "Six Nations", "6N", Sport.RUGBY, "GB,IE,FR,IT", minutes = 120, words = listOf("six nations")),
        League("rugby/267979", "Premiership Rugby", "Prem Rugby", Sport.RUGBY, "GB", minutes = 120, words = listOf("premiership rugby", "gallagher premiership")),
        League("rugby/164205", "Rugby World Cup", "RWC", Sport.RUGBY, minutes = 120, words = listOf("rugby world cup"), table = Table.NONE),
        League("rugby-league/3", "NRL", "NRL", Sport.RUGBY, "AU,NZ", minutes = 120, words = listOf("nrl")),
        League("australian-football/afl", "AFL", "AFL", Sport.AUSSIE, "AU", minutes = 180, words = listOf("afl")),
    )

    private val byKey = all.associateBy { it.key }
    fun byKey(k: String): League? = byKey[k]

    /** Leagues in the order a region shows them: its own first, then those for everywhere, then the rest. */
    fun forRegion(region: String): List<League> {
        val r = region.uppercase()
        return all.sortedBy { l -> when { r.isNotEmpty() && r in l.regions.split(',') -> 0; l.regions.isEmpty() -> 1; else -> 2 } }
    }

    /** Leagues ESPN carries for a sport. */
    fun of(sport: Sport) = all.filter { it.sport == sport }

    /**
     * A key saved before the rewrite: ESPN paths stay; the old vocabulary's "taxonomy/…" names become our key where one
     * fits (else dropped).
     */
    fun migrate(old: String): String? {
        if (byKey(old) != null) return old
        if (!old.startsWith("taxonomy/")) return null
        val t = old.substringAfterLast('.').substringAfterLast('/')
        return when {
            "english-premier-league" in t -> "soccer/eng.1"
            "saudi-pro-league" in t -> "soccer/ksa.1"
            "fa-cup" in t -> "soccer/eng.fa"
            "afc-champions-league" in t -> "soccer/afc.champions"
            "fifa-club-world-cup" in t -> "soccer/fifa.cwc"
            "primeira-liga" in t -> "soccer/por.1"
            "european-club-tournaments" in t -> "soccer/uefa.champions"
            else -> null
        }
    }
}
