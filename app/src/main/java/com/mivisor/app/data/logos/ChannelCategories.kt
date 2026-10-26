package com.mivisor.app.data.logos

/**
 * Tipo de canal, para agrupar los que no son de ninguna marca conocida (en vez de un "Otros" enorme).
 * Fuente: la categoría de iptv-org (en el catálogo) y, si no hay, palabras del nombre ("sport", "fútbol", "cine"…).
 */
enum class ChannelCategory(val id: String, val label: String) {
    SPORTS("sports", "Deportes"),
    MOVIES("movies", "Cine y series"),
    KIDS("kids", "Infantil"),
    DOCS("documentary", "Documentales y estilo de vida"),
    MUSIC("music", "Música"),
    NEWS("news", "Noticias"),
    GENERAL("general", "Generalistas");

    companion object {
        fun of(id: String?): ChannelCategory? = values().firstOrNull { it.id == id }
    }
}

object ChannelCategories {
    /** Raíces por tipo, en orden de prioridad (deportes primero: "Fox Deportes" es deporte, no cine). */
    private val ROOTS: List<Pair<ChannelCategory, List<String>>> = listOf(
        ChannelCategory.SPORTS to listOf(
            "sport", "deport", "futbol", "football", "soccer", "tenis", "tennis", "golf", "nba", "nfl", "nhl", "mlb", "ufc",
            "fight", "boxing", "boxeo", "rally", "motor", "moto", "racing", "arena", "liga", "champions", "premier",
            "toro", "extreme", "eurosport", "espn", "dazn", "bein", "setanta", "rfef", "federacion", "olymp", "cricket",
            "rugby", "ciclismo", "cycling", "baloncesto", "basket", "fifa", "uefa", "wwe", "wrestling", "hipica", "f1",
            "formula", "match", "atletico", "athletic", "madrid tv", "barca", "betis", "sevilla fc", "racing", "surf",
            "ski", "nautic", "padel", "hockey", "esport"
        ),
        ChannelCategory.KIDS to listOf(
            "disney", "nick", "cartoon", "boing", "clan", "kids", "junior", "baby", "boomerang", "toon", "infantil",
            "super3", "panda", "jetix", "nickelodeon"
        ),
        ChannelCategory.MOVIES to listOf(
            "cine", "cinema", "movie", "film", "hollywood", "series", "axn", "amc", "dark", "xtrm", "paramount", "hbo",
            "warner", "comedy", "drama", "accion", "estrenos", "western", "clasicos", "calle 13", "syfy", "sundance",
            "somos", "tcm", "fox life", "star channel", "atreseries", "divinity", "fdf"
        ),
        ChannelCategory.DOCS to listOf(
            "documental", "discovery", "odisea", "historia", "history", "natgeo", "geographic", "viajar", "cocina",
            "caza", "pesca", "decasa", "tlc", "dmax", "animal", "planet", "travel", "food", "lifestyle", "crime"
        ),
        ChannelCategory.MUSIC to listOf("musica", "music", "mtv", "40tv", "kiss", "vh1", "hit tv"),
        ChannelCategory.NEWS to listOf("news", "noticias", "24h", "24 horas", "cnn", "bbc world", "euronews", "informativo", "bloomberg"),
        ChannelCategory.GENERAL to listOf("antena", "telecinco", "cuatro", "lasexta", "la 1", "la 2", "tve", "zdf", "ard", "rtl", "orf", "tv3", "rai")
    )

    /** Tipo por palabras del nombre ya normalizado (tokens de ChannelCanon). null si no se reconoce. */
    fun guess(tokens: List<String>): ChannelCategory? {
        val joined = " " + tokens.joinToString(" ") + " "
        return ROOTS.firstOrNull { (_, roots) ->
            roots.any { root -> if (' ' in root || root.length <= 3) joined.contains(" $root ") || tokens.any { it == root } else tokens.any { it.contains(root) } }
        }?.first
    }
}
