package com.mivisor.app.model

/**
 * Agenda deportiva publicada por tools/agenda/generate_agenda.py (agenda.json, versión 1).
 * La app no sabe qué proveedor generó cada evento: solo lee el formato común.
 */

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
            "formula1", "motor" -> 120L
            "motogp" -> 60L
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

/** Estado de la fuente de datos de un deporte ("stats" de agenda.json). */
enum class SourceStatus { OK, STALE, UNAVAILABLE }

class SportHealth(
    val status: SourceStatus,
    /** Última actualización correcta (epoch ms) o null si no se conoce. */
    val lastSuccess: Long?
)

class Agenda(
    val generatedAt: Long,
    val source: String,
    val events: List<AgendaEvent>,
    /** Estado por deporte ("futbol", "tenis"…). Vacío si la agenda no trae "stats". */
    val health: Map<String, SportHealth> = emptyMap()
) {
    val sports: List<String> by lazy { events.map { it.sport }.distinct() }

    /** Deportes cuya agenda no se ha podido actualizar (stale) o cargar (unavailable). */
    val problems: Map<String, SourceStatus> by lazy {
        health.filterValues { it.status != SourceStatus.OK }.mapValues { it.value.status }
    }

    /** Avisos a mostrar: solo el del deporte filtrado, o todos los problemáticos con "Todos". */
    fun notices(sportFilter: String?): List<Pair<String, SourceStatus>> =
        problems.filterKeys { sportFilter == null || it == sportFilter }.toList()
}

/** Texto del aviso por deporte. [sportName] ya va en minúsculas si es un nombre común ("tenis"). */
fun sourceNoticeText(sportName: String, status: SourceStatus): String = when (status) {
    SourceStatus.STALE ->
        "La agenda de $sportName no se ha podido actualizar. Se muestran los últimos datos disponibles."
    SourceStatus.UNAVAILABLE -> "No se ha podido cargar la agenda de $sportName."
    SourceStatus.OK -> ""
}
