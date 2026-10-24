package com.mivisor.app.model

import java.util.Calendar
import java.util.TimeZone

/**
 * services.json (versión 1): estado de las fuentes externas de la agenda, generado por GitHub Actions
 * con la MISMA fuente de verdad que los avisos de la Agenda ("stats" de agenda.json).
 */
enum class ServiceState { OK, STALE, UNAVAILABLE, DISABLED }

enum class Overall { OK, DEGRADED, DOWN }

class ServiceError(val code: String, val at: Long?)

class ServiceInfo(
    val id: String,
    val name: String,
    val role: String,
    /** "core" (agenda), "aux" (TV, retratos) o "system". */
    val kind: String,
    val state: ServiceState,
    val lastAttempt: Long?,
    val lastSuccess: Long?,
    val consecutiveFailures: Int,
    val lastError: ServiceError?
)

class ServiceEvent(
    val service: String,
    val at: Long,
    /** "error" o "recovery". */
    val type: String,
    val code: String?
)

class ServicesReport(
    val generatedAt: Long,
    val overall: Overall,
    val services: List<ServiceInfo>,
    val history: List<ServiceEvent>
) {
    /** Historial de un servicio, lo más reciente primero. */
    fun historyOf(id: String): List<ServiceEvent> = history.filter { it.service == id }.sortedByDescending { it.at }
}

/** Textos para personas. Nunca se muestran errores técnicos crudos. */
object ServiceText {
    fun state(state: ServiceState): String = when (state) {
        ServiceState.OK -> "Operativo"
        ServiceState.STALE -> "Datos anteriores"
        ServiceState.UNAVAILABLE -> "No disponible"
        ServiceState.DISABLED -> "Desactivado"
    }

    fun overall(overall: Overall): String = when (overall) {
        Overall.OK -> "Todo operativo"
        Overall.DEGRADED -> "Algunos servicios tienen problemas"
        Overall.DOWN -> "No se ha podido actualizar la agenda"
    }

    fun error(code: String?): String = when (code) {
        "timeout" -> "Tiempo de espera agotado"
        "rate_limited" -> "Límite temporal de la API"
        "authentication_error" -> "Error de autenticación"
        "invalid_response" -> "Respuesta no válida"
        "robots_disallowed" -> "La fuente ya no permite la consulta automática"
        "parse_error" -> "Ha cambiado el formato de la fuente"
        "no_agenda" -> "No se ha podido generar la agenda"
        else -> "Servicio no disponible"
    }

    fun event(event: ServiceEvent): String =
        if (event.type == "recovery") "Servicio recuperado" else "Sincronización fallida"

    private val MONTHS = arrayOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sept", "oct", "nov", "dic")

    /** "24 oct · 20:17" en la zona del dispositivo (o la indicada, para tests). */
    fun date(millis: Long?, zone: TimeZone = TimeZone.getDefault()): String {
        millis ?: return "—"
        val c = Calendar.getInstance(zone).apply { timeInMillis = millis }
        return "%d %s · %02d:%02d".format(
            c.get(Calendar.DAY_OF_MONTH), MONTHS[c.get(Calendar.MONTH)], c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)
        )
    }
}
