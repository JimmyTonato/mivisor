package com.mivisor.app.model

import java.text.Normalizer

/**
 * Competiciones de fútbol que se pueden filtrar. El orden de declaración es el orden de los chips
 * (no alfabético). OTHER agrupa todo lo que no se reconoce.
 */
enum class CompetitionKey(val label: String) {
    LALIGA("LaLiga"),
    CHAMPIONS("Champions"),
    PREMIER("Premier"),
    SERIE_A("Serie A"),
    BUNDESLIGA("Bundesliga"),
    LIGUE_1("Ligue 1"),
    EUROPA_LEAGUE("Europa League"),
    CONFERENCE("Conference League"),
    COPA_DEL_REY("Copa del Rey"),
    SUPERCOPA("Supercopa"),
    OTHER("Otras");

    companion object {
        private val RULES: List<Pair<Regex, CompetitionKey>> = listOf(
            // El orden importa: "Europa Conference League" antes que "Europa League".
            Regex("""^(uefa )?(europa )?conference league$|^uecl$""") to CONFERENCE,
            Regex("""^(uefa )?europa league$|^uel$""") to EUROPA_LEAGUE,
            Regex("""^(uefa )?(champions league|liga de campeones)$|^ucl$""") to CHAMPIONS,
            Regex("""^(spanish )?(la ?liga|primera division)( ea sports| santander)?$""") to LALIGA,
            Regex("""^(english |barclays )?premier league$|^epl$|^premier$""") to PREMIER,
            Regex("""^(italian )?serie a( tim| enilive)?$""") to SERIE_A,
            Regex("""^(german |1 )?bundesliga$""") to BUNDESLIGA,
            Regex("""^(french )?ligue 1( uber eats| mcdonald ?s)?$""") to LIGUE_1,
            Regex("""^copa del rey$|^copa de s ?m el rey$""") to COPA_DEL_REY,
            Regex("""^supercopa( de espana)?$""") to SUPERCOPA
        )

        /**
         * Nombre de competición (de cualquier proveedor) → filtro. Ignora mayúsculas, acentos y
         * signos: "LaLiga EA Sports", "Primera División", "UEFA Champions League", "UCL"…
         * Segundas divisiones, femenino, juveniles u otros países → OTHER. Nunca lanza excepciones.
         */
        fun of(competition: String?): CompetitionKey {
            if (competition.isNullOrBlank()) return OTHER
            val text = normalize(competition)
            return RULES.firstOrNull { (regex, _) -> regex.matches(text) }?.second ?: OTHER
        }

        internal fun normalize(value: String): String =
            Normalizer.normalize(value, Normalizer.Form.NFD)
                .replace(Regex("""\p{M}+"""), "")
                .lowercase()
                .replace(Regex("""[^a-z0-9]+"""), " ")
                .trim()
    }
}

fun AgendaEvent.competitionKey(): CompetitionKey = CompetitionKey.of(competition)

/** Opción de un segundo nivel de filtro (chip). [key] = null es "Todos". */
data class SubFilterOption(val key: String?, val label: String)

/**
 * Segundo nivel de filtros por deporte. Hoy solo Fútbol → competición; añadir otro deporte
 * (ATP/WTA, GP…) es añadir una entrada aquí, sin tocar la pantalla.
 */
object AgendaFilters {
    const val ALL_LABEL = "Todos"

    private interface SubFilter {
        fun keyOf(event: AgendaEvent): String
        fun label(key: String): String
        fun order(key: String): Int
    }

    private val FOOTBALL = object : SubFilter {
        override fun keyOf(event: AgendaEvent) = event.competitionKey().name
        override fun label(key: String) = runCatching { CompetitionKey.valueOf(key).label }.getOrDefault(key)
        override fun order(key: String) = runCatching { CompetitionKey.valueOf(key).ordinal }.getOrDefault(Int.MAX_VALUE)
    }

    private val BY_SPORT: Map<String, SubFilter> = mapOf("futbol" to FOOTBALL)

    fun hasSubFilters(sport: String?): Boolean = sport != null && sport in BY_SPORT

    /**
     * Chips del segundo nivel: "Todos" siempre primero y después solo las competiciones que
     * tienen algún evento en la agenda cargada, por prioridad. Vacío si el deporte no tiene
     * subfiltros (o no hay deporte seleccionado).
     */
    fun options(events: List<AgendaEvent>, sport: String?): List<SubFilterOption> {
        val filter = sport?.let { BY_SPORT[it] } ?: return emptyList()
        val keys = events.asSequence().filter { it.sport == sport }.map(filter::keyOf).toSet()
        return listOf(SubFilterOption(null, ALL_LABEL)) +
            keys.sortedBy(filter::order).map { SubFilterOption(it, filter.label(it)) }
    }

    /**
     * Subfiltro efectivo: el recordado para ese deporte si sigue teniendo eventos; si no, "Todos".
     */
    fun effective(events: List<AgendaEvent>, sport: String?, remembered: Map<String, String>): String? {
        val key = sport?.let { remembered[it] } ?: return null
        return key.takeIf { k -> options(events, sport).any { it.key == k } }
    }

    /** Filtro local por deporte y subfiltro (sin red). sport = null → todos los deportes. */
    fun bySport(events: List<AgendaEvent>, sport: String?, subKey: String?): List<AgendaEvent> {
        if (sport == null) return events
        val filter = BY_SPORT[sport]
        return events.filter { it.sport == sport && (subKey == null || filter == null || filter.keyOf(it) == subKey) }
    }
}
