package com.mivisor.app.model

/** Agenda deportiva publicada por tools/agenda/scrape_agenda.py (agenda.json, versión 1). */

class AgendaChannel(
    val name: String,
    /** Dial en operadores, p. ej. "M55 O113". */
    val dial: String?,
    /** "Ver en directo", "PPV", "Replay"… */
    val note: String?,
    val logo: String? = null
)

class AgendaEvent(
    val id: String,
    val sport: String,
    val competition: String?,
    val startMillis: Long,
    val home: String?,
    val away: String?,
    val title: String,
    val channels: List<AgendaChannel>,
    val homeLogo: String?,
    val awayLogo: String?,
    val competitionLogo: String? = null
) {
    val isMatch: Boolean get() = !home.isNullOrBlank() && !away.isNullOrBlank()

    fun status(now: Long): EventStatus {
        val duration = when (sport) {
            "baloncesto" -> 150L
            "tenis" -> 180L
            "motor" -> 120L
            else -> 120L
        } * 60_000L
        return when {
            now < startMillis - 5 * 60_000L -> EventStatus.UPCOMING
            now <= startMillis + duration -> EventStatus.LIVE
            else -> EventStatus.FINISHED
        }
    }
}

enum class EventStatus { UPCOMING, LIVE, FINISHED }

class Agenda(
    val generatedAt: Long,
    val source: String,
    val events: List<AgendaEvent>
) {
    val sports: List<String> by lazy { events.map { it.sport }.distinct() }
}
