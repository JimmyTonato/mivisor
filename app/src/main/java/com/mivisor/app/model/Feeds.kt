package com.mivisor.app.model

/*
 * Qué se enseña en Inicio, En directo y Calendario a partir de agenda.json.
 * Lógica pura (sin Android): se prueba en la JVM.
 */

// ============================================================================ Inicio

class HomeFeed(
    /** Evento principal: el directo más interesante o, si no hay, el próximo relevante. */
    val hero: AgendaEvent?,
    val competitions: List<CompetitionSummary>,
    val live: List<AgendaEvent>,
    val soon: List<AgendaEvent>
) {
    companion object {
        val EMPTY = HomeFeed(null, emptyList(), emptyList(), emptyList())
    }
}

object HomeFeedBuilder {
    const val SOON_HOURS = 12
    const val MAX_LIVE = 12
    const val MAX_SOON = 10

    /**
     * [options] = nº de fuentes del usuario para un evento: a igualdad de interés se prefiere lo que se puede ver.
     */
    fun build(
        events: List<AgendaEvent>,
        now: Long,
        clock: DayClock = DayClock(),
        options: (AgendaEvent) -> Int = { 0 }
    ): HomeFeed {
        val live = events.filter { it.status(now) == EventStatus.LIVE }
            .sortedWith(compareByDescending<AgendaEvent> { options(it) > 0 }.thenByDescending { EventPriority.of(it) }.thenBy { it.startMillis })
        val upcoming = events.filter { it.status(now) == EventStatus.UPCOMING && !it.timeTbd }.sortedBy { it.startMillis }
        val horizon = now + SOON_HOURS * 3_600_000L
        val soon = upcoming.filter { it.startMillis <= horizon }.let { near ->
            // Pocas cosas en las próximas horas: se completa con lo siguiente (sin convertirlo en la agenda entera).
            if (near.size >= 3) near else upcoming.filter { it.startMillis <= now + 48 * 3_600_000L }.take(5)
        }.take(MAX_SOON)
        val hero = live.firstOrNull()
            ?: upcoming.filter { it.startMillis <= now + 24 * 3_600_000L }
                .maxWithOrNull(compareBy<AgendaEvent> { EventPriority.of(it) }.thenByDescending { it.startMillis })   // a igual interés, el más cercano
            ?: upcoming.firstOrNull()
        return HomeFeed(hero, CompetitionSummaries.build(events, now, clock), live.take(MAX_LIVE), soon)
    }
}

// ============================================================================ En directo

/** Filtros de "En directo": Todos y los deportes que tengan algo en directo (Fútbol, Tenis, F1 y MotoGP siempre). */
object LiveFilters {
    val ALWAYS = listOf("futbol", "tenis", "formula1", "motogp")

    fun sports(liveEvents: List<AgendaEvent>): List<String> =
        (ALWAYS + liveEvents.map { it.sport }).distinct()
            .sortedBy { Agenda.SPORT_ORDER.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }

    fun apply(events: List<AgendaEvent>, now: Long, sport: String?): List<AgendaEvent> =
        events.filter { it.status(now) == EventStatus.LIVE && (sport == null || it.sport == sport) }
            .sortedWith(compareByDescending<AgendaEvent> { EventPriority.of(it) }.thenBy { it.startMillis })
}

// ============================================================================ Calendario

enum class CalendarRange(val label: String) { TODAY("Hoy"), TOMORROW("Mañana"), WEEK("Esta semana"), DATE("Elegir fecha") }

/** Chip de competición del Calendario. key = null → "Todos"; "TENIS" = ATP + WTA + resto del tenis. */
data class CalendarChip(val key: String?, val label: String, val competition: Competition?)

data class CalendarQuery(
    val range: CalendarRange = CalendarRange.TODAY,
    /** Día elegido con "Elegir fecha" (0 = hoy, 1 = mañana…). */
    val dayOffset: Int = 0,
    val competition: String? = null
)

object CalendarFilters {
    const val TENNIS_KEY = "TENIS"
    const val DAYS_AHEAD = 14

    private val FIXED = listOf(
        Competition.LALIGA, Competition.CHAMPIONS, Competition.PREMIER, Competition.SERIE_A, Competition.BUNDESLIGA,
        Competition.FORMULA1, Competition.MOTOGP
    )

    /** Todos · LaLiga · Champions · Premier · Serie A · Bundesliga · F1 · MotoGP · Tenis · (otras con eventos). */
    fun chips(events: List<AgendaEvent>): List<CalendarChip> {
        val present = events.map { Competition.of(it) }.toSet()
        val extra = Competition.values().filter {
            it in present && it !in FIXED && it.sport != "tenis" && it != Competition.OTHER
        }
        return listOf(CalendarChip(null, "Todos", null)) +
            FIXED.map { CalendarChip(it.name, it.short, it) } +
            CalendarChip(TENNIS_KEY, "Tenis", Competition.TENIS) +
            extra.map { CalendarChip(it.name, it.short, it) }
    }

    fun matches(event: AgendaEvent, key: String?): Boolean = when (key) {
        null -> true
        TENNIS_KEY -> event.sport == "tenis"
        else -> Competition.of(event).name == key
    }

    /** [from, to) del rango pedido. */
    fun window(query: CalendarQuery, now: Long, clock: DayClock = DayClock()): Pair<Long, Long> = when (query.range) {
        CalendarRange.TODAY -> clock.startOfDay(now) to clock.startOfDay(now, 1)
        CalendarRange.TOMORROW -> clock.startOfDay(now, 1) to clock.startOfDay(now, 2)
        CalendarRange.WEEK -> clock.startOfDay(now) to clock.startOfDay(now, 7)
        CalendarRange.DATE -> clock.startOfDay(now, query.dayOffset) to clock.startOfDay(now, query.dayOffset + 1)
    }

    /**
     * Listado cronológico. En "Hoy" los finalizados van al final (lo que queda por ver, primero).
     */
    fun apply(events: List<AgendaEvent>, query: CalendarQuery, now: Long, clock: DayClock = DayClock()): List<AgendaEvent> {
        val (from, to) = window(query, now, clock)
        val list = events.filter { it.startMillis in from until to && matches(it, query.competition) }.sortedBy { it.startMillis }
        return if (query.range == CalendarRange.TODAY || (query.range == CalendarRange.DATE && query.dayOffset == 0)) {
            list.sortedBy { if (it.status(now) == EventStatus.FINISHED) 1 else 0 }
        } else list
    }

    /** Eventos de una competición para su página, repartidos por la plantilla del deporte. */
    fun competitionSections(
        competition: Competition,
        events: List<AgendaEvent>,
        now: Long,
        clock: DayClock = DayClock()
    ): List<Pair<String, List<AgendaEvent>>> {
        val mine = events.filter { Competition.of(it) == competition }.sortedBy { it.startMillis }
        val live = mine.filter { it.status(now) == EventStatus.LIVE }
        val todayEnd = clock.startOfDay(now, 1)
        val upcoming = mine.filter { it.status(now) == EventStatus.UPCOMING }
        return when (competition.template) {
            CompetitionTemplate.RACING -> {
                val weekendEnd = clock.startOfDay(now, 4)
                listOf(
                    "Ahora" to live,
                    "Este fin de semana" to upcoming.filter { it.startMillis < weekendEnd },
                    "Próximos grandes premios" to upcoming.filter { it.startMillis >= weekendEnd }
                )
            }
            else -> listOf(
                "En directo" to live,
                "Hoy" to upcoming.filter { it.startMillis < todayEnd },
                "Próximos" to upcoming.filter { it.startMillis >= todayEnd }
            )
        }.filter { it.second.isNotEmpty() }
    }
}
