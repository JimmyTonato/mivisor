package com.mivisor.app.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mivisor.app.model.Overall
import com.mivisor.app.model.ServiceError
import com.mivisor.app.model.ServiceEvent
import com.mivisor.app.model.ServiceInfo
import com.mivisor.app.model.ServiceState
import com.mivisor.app.model.ServicesReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class InvalidServicesException(message: String) : Exception(message)

/**
 * Descarga services.json desde la misma base que agenda.json (rama agenda-data).
 *  - Caché corta ([ttlMs], 10 min) y ETag: si no ha cambiado, 304 sin descargar nada.
 *  - Sin conexión: la última copia guardada, marcada como posiblemente desactualizada.
 *  - "Actualizar" solo vuelve a descargar este fichero: NO ejecuta GitHub Actions ni llama a las APIs.
 */
class ServicesRepository(
    private val cacheDir: File,
    private val ttlMs: Long = TimeUnit.MINUTES.toMillis(10),
    private val clock: () -> Long = System::currentTimeMillis,
    private val download: (url: String, etag: String?) -> AgendaRepository.Download = AgendaRepository::httpGet
) {
    class Result(val report: ServicesReport, val fetchedAt: Long, val offline: Boolean)

    private val dataFile get() = File(cacheDir, "services.json")
    private val metaFile get() = File(cacheDir, "services.meta")

    suspend fun load(url: String, force: Boolean = false): Result = withContext(Dispatchers.IO) {
        val cached = readCache()
        if (!force && cached != null && clock() - cached.fetchedAt in 0..ttlMs) {
            return@withContext Result(cached.report, cached.fetchedAt, offline = false)
        }
        if (url.isBlank()) {
            cached?.let { return@withContext Result(it.report, it.fetchedAt, offline = true) }
            throw InvalidServicesException("No hay URL de agenda configurada (Ajustes → Agenda deportiva)")
        }
        try {
            val dl = download(url, cached?.etag)
            val now = clock()
            when {
                dl.status == 304 && cached != null -> {
                    writeMeta(now, cached.etag)
                    Result(cached.report, now, offline = false)
                }
                dl.status == 200 && dl.body != null -> {
                    val report = ServicesParser.parse(dl.body)
                    cacheDir.mkdirs()
                    val tmp = File(cacheDir, "services.json.tmp")
                    tmp.writeText(dl.body)
                    if (!tmp.renameTo(dataFile)) { dataFile.delete(); tmp.renameTo(dataFile) }
                    writeMeta(now, dl.etag)
                    Result(report, now, offline = false)
                }
                dl.status == 404 -> throw InvalidServicesException(
                    "Todavía no hay diagnóstico publicado (se crea en la próxima ejecución de la agenda)"
                )
                else -> throw IOException("HTTP ${dl.status}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cached?.let { Result(it.report, it.fetchedAt, offline = true) }
                ?: throw if (e is InvalidServicesException) e else InvalidServicesException("No se pudo descargar el estado de los servicios")
        }
    }

    private class Cached(val report: ServicesReport, val fetchedAt: Long, val etag: String?)

    private fun readCache(): Cached? {
        if (!dataFile.isFile) return null
        return runCatching {
            val meta = if (metaFile.isFile) metaFile.readText().split('\n') else emptyList()
            val fetchedAt = meta.getOrNull(0)?.toLongOrNull() ?: dataFile.lastModified()
            Cached(ServicesParser.parse(dataFile.readText()), fetchedAt, meta.getOrNull(1)?.takeIf { it.isNotBlank() })
        }.getOrNull()
    }

    private fun writeMeta(fetchedAt: Long, etag: String?) {
        runCatching { cacheDir.mkdirs(); metaFile.writeText("$fetchedAt\n${etag.orEmpty()}") }
    }

    companion object {
        /** .../agenda-data/agenda.json → .../agenda-data/services.json */
        fun urlFor(agendaUrl: String): String {
            val url = agendaUrl.trim()
            if (url.isBlank()) return ""
            val base = url.substringBefore('?')
            return if (base.endsWith(".json")) base.substringBeforeLast('/') + "/services.json"
            else base.trimEnd('/') + "/services.json"
        }
    }
}

object ServicesParser {
    private const val SUPPORTED_VERSION = 1

    fun parse(json: String): ServicesReport {
        val root = runCatching { JsonParser.parseString(json).asJsonObject }
            .getOrElse { throw InvalidServicesException("El diagnóstico no es un JSON válido") }
        val version = root.get("version")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() } ?: 0
        if (version < 1) throw InvalidServicesException("Diagnóstico sin versión")
        if (version > SUPPORTED_VERSION) throw InvalidServicesException("Actualiza Mi Visor para leer este diagnóstico (v$version)")
        val services = root.get("services")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw InvalidServicesException("Diagnóstico sin servicios")
        return ServicesReport(
            generatedAt = root.str("generatedAt")?.let(AgendaParser::parseIsoMillis) ?: 0L,
            overall = when (root.str("overall")) {
                "ok" -> Overall.OK
                "down" -> Overall.DOWN
                else -> Overall.DEGRADED
            },
            services = services.mapNotNull { el -> el?.takeIf { it.isJsonObject }?.asJsonObject?.let(::service) },
            history = root.get("history")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { el -> el?.takeIf { it.isJsonObject }?.asJsonObject?.let(::event) }.orEmpty()
        )
    }

    private fun service(o: JsonObject): ServiceInfo? {
        val id = o.str("id") ?: return null
        val error = o.get("lastError")?.takeIf { it.isJsonObject }?.asJsonObject?.let { e ->
            e.str("code")?.let { ServiceError(it, e.str("at")?.let(AgendaParser::parseIsoMillis)) }
        }
        return ServiceInfo(
            id = id,
            name = o.str("name") ?: id,
            role = o.str("role").orEmpty(),
            kind = o.str("kind") ?: "core",
            state = when (o.str("status")) {
                "ok" -> ServiceState.OK
                "stale" -> ServiceState.STALE
                "disabled" -> ServiceState.DISABLED
                else -> ServiceState.UNAVAILABLE
            },
            lastAttempt = o.str("lastAttempt")?.let(AgendaParser::parseIsoMillis),
            lastSuccess = o.str("lastSuccess")?.let(AgendaParser::parseIsoMillis),
            consecutiveFailures = o.get("consecutiveFailures")?.takeIf { it.isJsonPrimitive }
                ?.let { runCatching { it.asInt }.getOrNull() } ?: 0,
            lastError = error
        )
    }

    private fun event(o: JsonObject): ServiceEvent? {
        val service = o.str("service") ?: return null
        val at = o.str("at")?.let(AgendaParser::parseIsoMillis) ?: return null
        return ServiceEvent(service, at, o.str("type") ?: "error", o.str("code"))
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
}
