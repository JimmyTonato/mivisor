package com.mivisor.app.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mivisor.app.model.Agenda
import com.mivisor.app.model.AgendaChannel
import com.mivisor.app.model.AgendaEvent
import com.mivisor.app.model.SourceStatus
import com.mivisor.app.model.SportHealth
import java.util.TimeZone

class InvalidAgendaException(message: String) : Exception(message)

/**
 * Lee agenda.json. Sin java.time (no existe en Android 5–7): las fechas ISO 8601 se
 * convierten a milisegundos con aritmética de calendario civil.
 */
object AgendaParser {
    private const val SUPPORTED_VERSION = 1

    fun parse(json: String): Agenda {
        val root = runCatching { JsonParser.parseString(json).asJsonObject }
            .getOrElse { throw InvalidAgendaException("La agenda no es un JSON válido") }
        val version = root.get("version")?.asInt ?: 0
        if (version > SUPPORTED_VERSION) throw InvalidAgendaException("Actualiza Mi Visor para leer esta agenda (v$version)")
        val events = root.getAsJsonArray("events")?.mapNotNull { el ->
            runCatching { event(el.asJsonObject) }.getOrNull()
        }.orEmpty().sortedBy { it.startMillis }
        return Agenda(
            generatedAt = root.str("generatedAt")?.let(::parseIsoMillis) ?: 0L,
            source = root.str("source") ?: "Agenda deportiva",
            events = events,
            health = health(root)
        )
    }

    /** Clave de "stats" (proveedor) → deporte que muestra la app. */
    private val STATS_SPORT = mapOf(
        "football" to "futbol",
        "tennis" to "tenis",
        "formula1" to "formula1",
        "motogp" to "motogp",
        "basketball" to "baloncesto"
    )

    /** "stats" es opcional: si falta o está mal formado, simplemente no hay avisos. */
    private fun health(root: JsonObject): Map<String, SportHealth> {
        val stats = root.get("stats")?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyMap()
        val out = LinkedHashMap<String, SportHealth>()
        for ((key, value) in stats.entrySet()) {
            val o = value?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val sport = STATS_SPORT[key] ?: key
            val status = when (o.str("status")?.lowercase()) {
                "ok" -> SourceStatus.OK
                "stale" -> SourceStatus.STALE
                "unavailable" -> SourceStatus.UNAVAILABLE
                else -> continue
            }
            out[sport] = SportHealth(status, o.str("lastSuccess")?.let(::parseIsoMillis))
        }
        return out
    }

    private fun event(o: JsonObject): AgendaEvent? {
        val start = o.str("start")?.let(::parseIsoMillis) ?: return null
        val home = o.str("home")
        val away = o.str("away")
        val title = o.str("title") ?: listOfNotNull(home, away).joinToString(" - ").ifBlank { return null }
        val channels = o.getAsJsonArray("channels")?.mapNotNull { el ->
            val c = el.asJsonObject
            val name = c.str("name") ?: return@mapNotNull null
            AgendaChannel(name, c.str("dial"), c.str("note"), c.str("logo"))
        }.orEmpty()
        return AgendaEvent(
            id = o.str("id") ?: "${start}_$title",
            sport = o.str("sport") ?: "futbol",
            competition = o.str("competition"),
            startMillis = start,
            home = home,
            away = away,
            title = title,
            channels = channels,
            homeLogo = o.str("homeLogo"),
            awayLogo = o.str("awayLogo"),
            competitionLogo = o.str("competitionLogo")
        )
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }

    private val ISO = Regex(
        """^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?$"""
    )

    /** ISO 8601 → epoch ms. Sin zona horaria se asume Europe/Madrid (la de la fuente). */
    fun parseIsoMillis(value: String): Long? {
        val m = ISO.matchEntire(value.trim()) ?: return null
        val g = m.groupValues
        val y = g[1].toInt(); val mo = g[2].toInt(); val d = g[3].toInt()
        val h = g[4].toInt(); val mi = g[5].toInt(); val s = g[6].ifEmpty { "0" }.toInt()
        val localAsUtc = (daysFromCivil(y, mo, d) * 86_400L + h * 3_600L + mi * 60L + s) * 1_000L
        val zone = g[7]
        val offsetMs = when {
            zone == "Z" -> 0L
            zone.isNotEmpty() -> {
                val sign = if (zone[0] == '-') -1 else 1
                val digits = zone.substring(1).replace(":", "")
                sign * (digits.substring(0, 2).toLong() * 60 + digits.substring(2, 4).toLong()) * 60_000L
            }
            else -> {
                val tz = TimeZone.getTimeZone("Europe/Madrid")
                tz.getOffset(localAsUtc - tz.getOffset(localAsUtc)).toLong()
            }
        }
        return localAsUtc - offsetMs
    }

    /** Días desde 1970-01-01 (algoritmo de H. Hinnant). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }
}
