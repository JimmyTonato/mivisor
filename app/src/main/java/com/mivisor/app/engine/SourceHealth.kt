package com.mivisor.app.engine

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.mivisor.app.data.AceStreamLink
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Números de la política de salud, en UN sitio (no repartidos por el código).
 */
object HealthPolicy {
    /** Fallos CONFIRMADOS necesarios para la cuarentena… */
    const val QUARANTINE_AFTER_FAILURES = 4
    /** …repartidos en al menos estos días distintos… */
    const val QUARANTINE_MIN_DISTINCT_DAYS = 3
    /** …y cuánto dura la cuarentena antes de un intento de recuperación. */
    const val QUARANTINE_DAYS = 7L
    /** Como mucho un fallo confirmado por Content ID dentro de esta ventana (entrar 10 veces no suma 10). */
    const val CONFIRMED_FAILURE_WINDOW_HOURS = 20L
    /** Las que fallaron hace menos de esto van al final de la cola (sin contar como fallo de salud). */
    const val RECENT_FAILURE_MINUTES = 10L
    /** Fallo global del resolvedor: sin ningún éxito en esta ventana y al menos [GLOBAL_MIN_CONTENT_IDS] IDs sin resolver. */
    const val GLOBAL_WINDOW_MINUTES = 30L
    const val GLOBAL_MIN_CONTENT_IDS = 3
    /** Registros guardados como máximo (se descartan antes los más antiguos sin historial negativo). */
    const val MAX_RECORDS = 3000

    const val HOUR_MS = 60L * 60 * 1000
    const val DAY_MS = 24 * HOUR_MS

    /** Tipos de fallo que SÍ son de la fuente. Motor caído, puerto, cancelado o "se cortó tras verse" no lo son. */
    val SOURCE_FAULTS = setOf(FailureKind.RESOLUTION, FailureKind.NO_PEERS, FailureKind.TIMEOUT, FailureKind.PLAYER)
}

/** Estados de salud de una fuente. No existe "muerta": los streams vuelven a aparecer. */
enum class HealthState { ACTIVE, SUSPECT, QUARANTINED }

/** Cómo entra una fuente en la búsqueda automática según su salud. */
enum class Availability { NORMAL, RECENTLY_FAILED, PROBATION, EXCLUDED }

/**
 * Salud por Content ID NORMALIZADO (filesDir/engine/health.json). La comparten todos los canales que usan ese ID.
 * Sin Android: el reloj y el fichero se inyectan. Métodos sincronizados; escribir el fichero es rápido (JSON pequeño)
 * pero se llama fuera del hilo principal.
 */
class SourceHealth(
    private val file: File?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: TimeZone = TimeZone.getDefault()
) {
    data class Record(
        var contentId: String = "",
        var state: HealthState = HealthState.ACTIVE,
        var lastSuccessAt: Long = 0,
        var lastFailureAt: Long = 0,
        var lastFailureReason: String? = null,
        /** Fallos confirmados (ciclo completo: datos conocidos + resolución fresca, ambos sin vídeo). */
        var confirmedFailures: Int = 0,
        /** Días (aaaammdd) en que hubo fallo confirmado, sin repetir. */
        var failureDays: MutableList<String> = ArrayList(),
        var lastConfirmedAt: Long = 0,
        var quarantineUntil: Long = 0,
        /** Última vez que el motor resolvió el Content ID (hubo estado de sesión). */
        var lastValidResolutionAt: Long = 0,
        /** Última resolución forzada desde cero (segunda pasada, con el motor reiniciado). */
        var lastFreshResolutionAt: Long = 0,
        var quarantines: Int = 0
    ) {
        fun copyDeep() = copy(failureDays = ArrayList(failureDays))
    }

    private val gson = Gson()
    private var records: LinkedHashMap<String, Record>? = null

    private fun key(id: String) = AceStreamLink.normalizedId(id) ?: id.lowercase(Locale.ROOT)

    @Synchronized fun get(contentId: String): Record? = load()[key(contentId)]?.copyDeep()

    @Synchronized fun all(): List<Record> = load().values.map { it.copyDeep() }

    @Synchronized fun quarantined(): List<Record> = load().values.filter { it.state == HealthState.QUARANTINED }.map { it.copyDeep() }

    /** Cómo entra en la búsqueda automática ahora mismo. */
    @Synchronized fun availability(contentId: String): Availability {
        val r = load()[key(contentId)] ?: return Availability.NORMAL
        val now = clock()
        return when {
            r.state == HealthState.QUARANTINED && now < r.quarantineUntil -> Availability.EXCLUDED
            r.state == HealthState.QUARANTINED -> Availability.PROBATION
            r.lastFailureAt > r.lastSuccessAt && now - r.lastFailureAt < HealthPolicy.RECENT_FAILURE_MINUTES * 60_000 -> Availability.RECENTLY_FAILED
            else -> Availability.NORMAL
        }
    }

    /** Vídeo real con esta fuente: vuelve a ACTIVE y se borra su historial negativo (desde cualquier canal). */
    @Synchronized fun onSuccess(contentId: String) {
        val r = record(contentId)
        r.state = HealthState.ACTIVE
        r.lastSuccessAt = clock()
        r.confirmedFailures = 0
        r.failureDays.clear()
        r.quarantineUntil = 0
        r.lastFailureReason = null
        save()
    }

    /** El motor resolvió el Content ID ([fresh] = en la segunda pasada, tras forzar resolución desde cero). */
    @Synchronized fun onResolved(contentId: String, fresh: Boolean) {
        val r = record(contentId)
        r.lastValidResolutionAt = clock()
        if (fresh) r.lastFreshResolutionAt = clock()
        save()
    }

    /** Se intentó una resolución desde cero (aunque no llegara a resolver). */
    @Synchronized fun onFreshResolutionAttempt(contentId: String) {
        record(contentId).lastFreshResolutionAt = clock()
        save()
    }

    /**
     * Primera pasada sin vídeo: queda SUSPECT. NO suma fallo confirmado (puede ser un mal momento, un cambio de
     * canal, etc.). Si estaba en cuarentena, sigue en cuarentena.
     */
    @Synchronized fun onSuspect(contentId: String, kind: FailureKind) {
        if (kind !in HealthPolicy.SOURCE_FAULTS) return
        val r = record(contentId)
        r.lastFailureAt = clock()
        r.lastFailureReason = kind.short
        if (r.state == HealthState.ACTIVE) r.state = HealthState.SUSPECT
        save()
    }

    /** Resultado de registrar un fallo confirmado. */
    enum class Outcome { COUNTED, RATE_LIMITED, QUARANTINED, IGNORED }

    /**
     * Fallo CONFIRMADO (ciclo completo sin vídeo). Como mucho uno por ventana; con suficientes fallos en días
     * distintos y sin éxitos entre medias → cuarentena temporal. Una fuente en periodo de prueba que vuelve a
     * fallar regresa a cuarentena directamente.
     */
    @Synchronized fun onConfirmedFailure(contentId: String, kind: FailureKind): Outcome {
        if (kind !in HealthPolicy.SOURCE_FAULTS) return Outcome.IGNORED
        val now = clock()
        val r = record(contentId)
        val onProbation = r.state == HealthState.QUARANTINED && now >= r.quarantineUntil
        r.lastFailureAt = now
        r.lastFailureReason = kind.short
        if (onProbation) {
            quarantine(r, now)
            save()
            return Outcome.QUARANTINED
        }
        if (r.lastConfirmedAt > 0 && now - r.lastConfirmedAt < HealthPolicy.CONFIRMED_FAILURE_WINDOW_HOURS * HealthPolicy.HOUR_MS) {
            if (r.state == HealthState.ACTIVE) r.state = HealthState.SUSPECT
            save()
            return Outcome.RATE_LIMITED
        }
        r.confirmedFailures++
        r.lastConfirmedAt = now
        val day = dayKey(now)
        if (day !in r.failureDays) r.failureDays += day
        while (r.failureDays.size > 14) r.failureDays.removeAt(0)
        if (r.state != HealthState.QUARANTINED) r.state = HealthState.SUSPECT
        val result = if (r.confirmedFailures >= HealthPolicy.QUARANTINE_AFTER_FAILURES &&
            r.failureDays.size >= HealthPolicy.QUARANTINE_MIN_DISTINCT_DAYS
        ) { quarantine(r, now); Outcome.QUARANTINED } else Outcome.COUNTED
        save()
        return result
    }

    /** Acción manual (diagnóstico): "Reactivar" → ACTIVE y sin historial negativo. */
    @Synchronized fun reactivate(contentId: String) {
        val r = load()[key(contentId)] ?: return
        r.state = HealthState.ACTIVE
        r.confirmedFailures = 0
        r.failureDays.clear()
        r.quarantineUntil = 0
        r.lastFailureReason = null
        r.lastFailureAt = 0
        save()
    }

    @Synchronized fun reactivateAll() {
        load().values.filter { it.state != HealthState.ACTIVE }.forEach { reactivate(it.contentId) }
    }

    /**
     * ¿Parece un fallo GLOBAL del resolvedor (afecta a todas las fuentes, no solo a las de este canal)? Ningún éxito
     * en la ventana, y al menos [HealthPolicy.GLOBAL_MIN_CONTENT_IDS] Content IDs de OTROS canales ([exclude] = los
     * del ciclo actual) también fallando en ella. En ese caso no se penaliza a nadie.
     */
    @Synchronized fun looksLikeGlobalOutage(exclude: Set<String> = emptySet()): Boolean {
        val since = clock() - HealthPolicy.GLOBAL_WINDOW_MINUTES * 60_000
        val values = load().values
        if (values.any { it.lastSuccessAt >= since }) return false
        val ex = exclude.map(::key).toSet()
        return values.count { it.lastFailureAt >= since && it.contentId !in ex } >= HealthPolicy.GLOBAL_MIN_CONTENT_IDS
    }

    fun dayKey(at: Long): String = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = zone }.format(Date(at))

    private fun quarantine(r: Record, now: Long) {
        r.state = HealthState.QUARANTINED
        r.quarantineUntil = now + HealthPolicy.QUARANTINE_DAYS * HealthPolicy.DAY_MS
        r.quarantines++
    }

    private fun record(contentId: String): Record {
        val k = key(contentId)
        val map = load()
        return map[k] ?: Record(contentId = k).also { map[k] = it }
    }

    private fun load(): LinkedHashMap<String, Record> {
        records?.let { return it }
        val loaded = LinkedHashMap<String, Record>()
        runCatching {
            if (file != null && file.isFile) {
                val type = object : TypeToken<List<Record>>() {}.type
                val list: List<Record> = gson.fromJson(JsonParser.parseString(file.readText()).asJsonObject.get("records"), type)
                list.forEach { r ->
                    val k = AceStreamLink.normalizedId(r.contentId) ?: return@forEach
                    @Suppress("SENSELESS_COMPARISON")
                    if (r.failureDays == null) r.failureDays = ArrayList()
                    @Suppress("SENSELESS_COMPARISON")
                    if (r.state == null) r.state = HealthState.ACTIVE
                    r.contentId = k
                    loaded[k] = r                       // si el fichero tuviera duplicados, queda uno por Content ID
                }
            }
        }
        records = loaded
        return loaded
    }

    private fun save() {
        val map = load()
        if (map.size > HealthPolicy.MAX_RECORDS) {
            val removable = map.values.filter { it.state == HealthState.ACTIVE }.sortedBy { maxOf(it.lastSuccessAt, it.lastFailureAt) }
            removable.take(map.size - HealthPolicy.MAX_RECORDS).forEach { map.remove(it.contentId) }
        }
        val f = file ?: return
        runCatching {
            val root = com.google.gson.JsonObject()
            root.addProperty("version", 1)
            root.add("records", gson.toJsonTree(map.values.toList()))
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }
}
