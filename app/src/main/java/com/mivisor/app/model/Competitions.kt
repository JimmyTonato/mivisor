package com.mivisor.app.model

import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Competiciones que ve el usuario ("Tus competiciones", página de competición, filtros del Calendario).
 * Se deducen de cada evento de agenda.json (deporte + nombre de la competición), sin red y sin datos nuevos.
 * El orden de declaración es el orden en pantalla.
 */
enum class Competition(val label: String, val short: String, val sport: String, val template: CompetitionTemplate) {
    LALIGA("LaLiga", "LaLiga", "futbol", CompetitionTemplate.LEAGUE),
    CHAMPIONS("Champions League", "Champions", "futbol", CompetitionTemplate.LEAGUE),
    PREMIER("Premier League", "Premier", "futbol", CompetitionTemplate.LEAGUE),
    SERIE_A("Serie A", "Serie A", "futbol", CompetitionTemplate.LEAGUE),
    BUNDESLIGA("Bundesliga", "Bundesliga", "futbol", CompetitionTemplate.LEAGUE),
    FORMULA1("Fórmula 1", "F1", "formula1", CompetitionTemplate.RACING),
    MOTOGP("MotoGP", "MotoGP", "motogp", CompetitionTemplate.RACING),
    ATP("ATP", "ATP", "tenis", CompetitionTemplate.TOURNAMENT),
    WTA("WTA", "WTA", "tenis", CompetitionTemplate.TOURNAMENT),
    LIGUE_1("Ligue 1", "Ligue 1", "futbol", CompetitionTemplate.LEAGUE),
    EUROPA_LEAGUE("Europa League", "Europa League", "futbol", CompetitionTemplate.LEAGUE),
    CONFERENCE("Conference League", "Conference", "futbol", CompetitionTemplate.LEAGUE),
    COPA_DEL_REY("Copa del Rey", "Copa del Rey", "futbol", CompetitionTemplate.LEAGUE),
    SUPERCOPA("Supercopa", "Supercopa", "futbol", CompetitionTemplate.LEAGUE),
    FUTBOL("Más fútbol", "Fútbol", "futbol", CompetitionTemplate.LEAGUE),
    TENIS("Tenis", "Tenis", "tenis", CompetitionTemplate.TOURNAMENT),
    UFC("UFC", "UFC", "ufc", CompetitionTemplate.TOURNAMENT),
    BALONCESTO("Baloncesto", "Baloncesto", "baloncesto", CompetitionTemplate.LEAGUE),
    MOTOR("Motor", "Motor", "motor", CompetitionTemplate.RACING),
    OTHER("Otros deportes", "Otros", "otros", CompetitionTemplate.LEAGUE);

    companion object {
        /** Siempre visibles en "Tus competiciones" aunque no tengan eventos esos días (p. ej. MotoGP sin carrera). */
        val PINNED = listOf(LALIGA, CHAMPIONS, PREMIER, FORMULA1, MOTOGP)

        fun of(event: AgendaEvent): Competition = when (event.sport) {
            "futbol" -> when (CompetitionKey.of(event.competition)) {
                CompetitionKey.LALIGA -> LALIGA
                CompetitionKey.CHAMPIONS -> CHAMPIONS
                CompetitionKey.PREMIER -> PREMIER
                CompetitionKey.SERIE_A -> SERIE_A
                CompetitionKey.BUNDESLIGA -> BUNDESLIGA
                CompetitionKey.LIGUE_1 -> LIGUE_1
                CompetitionKey.EUROPA_LEAGUE -> EUROPA_LEAGUE
                CompetitionKey.CONFERENCE -> CONFERENCE
                CompetitionKey.COPA_DEL_REY -> COPA_DEL_REY
                CompetitionKey.SUPERCOPA -> SUPERCOPA
                CompetitionKey.OTHER -> FUTBOL
            }
            "formula1" -> FORMULA1
            "motogp" -> MOTOGP
            "tenis" -> tennis(event.competition)
            "ufc" -> UFC
            "baloncesto" -> BALONCESTO
            "motor" -> MOTOR
            else -> OTHER
        }

        /** "ATP Paris Masters", "Rolex Paris Masters (ATP)", "WTA Finals"… Lo que no se reconoce queda en "Tenis". */
        internal fun tennis(competition: String?): Competition {
            val text = normalize(competition.orEmpty())
            val words = text.split(' ')
            return when {
                "wta" in words -> WTA
                "atp" in words -> ATP
                else -> TENIS
            }
        }

        fun byName(name: String?): Competition? = values().firstOrNull { it.name == name }

        private fun normalize(value: String): String =
            Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")
                .lowercase().replace(Regex("""[^a-z0-9]+"""), " ").trim()
    }
}

/** Plantilla de la página de competición: cambia las secciones según el deporte. */
enum class CompetitionTemplate { LEAGUE, RACING, TOURNAMENT }

/** Texto corto de una tarjeta de "Tus competiciones". [live] = parte que se pinta en rojo. */
data class CompetitionSummary(
    val competition: Competition,
    val today: Int,
    val live: Int,
    val nextStart: Long?,
    val text: String,
    val liveText: String?
)

/** Fechas en el huso del dispositivo (inyectable para las pruebas). */
class DayClock(val zone: TimeZone = TimeZone.getDefault()) {
    private val es = Locale("es", "ES")

    fun startOfDay(millis: Long, plusDays: Int = 0): Long = Calendar.getInstance(zone).run {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, plusDays)
        timeInMillis
    }

    /** 0 = hoy, 1 = mañana… (-1 = antes de hoy). Cuenta días de calendario, no bloques de 24 h. */
    fun dayIndex(now: Long, millis: Long): Int {
        if (millis < startOfDay(now)) return -1
        var day = 0
        while (day < 400 && millis >= startOfDay(now, day + 1)) day++
        return day
    }

    fun format(pattern: String, millis: Long): String =
        SimpleDateFormat(pattern, es).apply { timeZone = zone }.format(Date(millis))

    /** "mañana", "sáb 26", "9 nov". */
    fun shortDay(now: Long, millis: Long): String = when (val d = dayIndex(now, millis)) {
        0 -> "hoy"
        1 -> "mañana"
        in 2..6 -> format("EEE d", millis).replace(".", "")
        else -> format("d MMM", millis).replace(".", "")
    }

    fun dayOfWeek(millis: Long): Int = Calendar.getInstance(zone).run { timeInMillis = millis; get(Calendar.DAY_OF_WEEK) }
}

object CompetitionSummaries {
    /**
     * Resumen de cada competición con eventos (y de las fijas aunque no tengan), en el orden de [Competition].
     *  - "3 hoy · 1 en directo", "2 hoy", "Próximo: mañana"
     *  - Motor: "Fin de semana" si hay sesión en los próximos 3 días; si no, "Próxima carrera: 9 nov".
     *  - "Sin eventos próximos".
     */
    fun build(events: List<AgendaEvent>, now: Long, clock: DayClock = DayClock(), maxItems: Int = 10): List<CompetitionSummary> {
        val byComp = events.groupBy { Competition.of(it) }
        val wanted = Competition.values().filter { it in Competition.PINNED || byComp[it].orEmpty().any { e -> e.status(now) != EventStatus.FINISHED } }
            .filter { it != Competition.OTHER || byComp[it].orEmpty().isNotEmpty() }
        return wanted.map { summary(it, byComp[it].orEmpty(), now, clock) }.take(maxItems)
    }

    fun summary(competition: Competition, events: List<AgendaEvent>, now: Long, clock: DayClock = DayClock()): CompetitionSummary {
        val todayStart = clock.startOfDay(now)
        val tomorrow = clock.startOfDay(now, 1)
        val live = events.count { it.status(now) == EventStatus.LIVE }
        val today = events.count { it.startMillis in todayStart until tomorrow }
        val next = events.filter { it.status(now) == EventStatus.UPCOMING }.minByOrNull { it.startMillis }?.startMillis
        val liveText = if (live > 0) "$live en directo" else null
        val racing = competition.template == CompetitionTemplate.RACING
        val text = when {
            today > 0 && racing -> "Fin de semana"
            today > 0 -> "$today hoy"
            live > 0 -> ""
            next == null -> "Sin eventos próximos"
            racing && next < clock.startOfDay(now, 3) -> "Fin de semana"
            racing -> "Próxima carrera: ${clock.shortDay(now, next)}"
            else -> "Próximo: ${clock.shortDay(now, next)}"
        }
        return CompetitionSummary(competition, today, live, next, text, liveText)
    }
}

/** Interés de un evento para el Hero, "En directo ahora" y "Empieza pronto". Ajustable sin tocar la UI. */
object EventPriority {
    private val RULES = linkedMapOf(
        "champions" to 100, "liga de campeones" to 100, "real madrid" to 95, "barcelona" to 95,
        "la liga" to 90, "laliga" to 90, "copa del rey" to 85, "premier" to 80, "nba" to 75,
        "fórmula 1" to 75, "formula 1" to 75, "motogp" to 70, "ufc" to 68, "atp" to 65, "wta" to 60,
        "europa league" to 60, "serie a" to 55, "bundesliga" to 55, "ligue 1" to 50, "acb" to 50, "endesa" to 50
    )

    fun of(event: AgendaEvent): Int {
        val text = "${event.competition.orEmpty()} ${event.title} ${event.home.orEmpty()} ${event.away.orEmpty()}".lowercase()
        val byText = RULES.entries.firstOrNull { (key, _) -> key in text }?.value ?: 0
        val bySport = when (event.sport) { "formula1" -> 75; "motogp" -> 70; "ufc" -> 68; else -> 0 }
        return maxOf(byText, bySport)
    }
}
