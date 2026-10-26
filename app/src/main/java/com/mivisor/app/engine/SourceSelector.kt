package com.mivisor.app.engine

import com.google.gson.JsonObject
import com.mivisor.app.data.AceStreamLink
import com.google.gson.JsonParser

/**
 * Una fuente de un canal: un Content ID (el enlace original no se toca). [quality] = rango de resolución
 * (UHD > FHD > HD > SD; 0 = desconocida) y [qualityLabel] su texto ("FHD").
 */
data class SourceCandidate(
    val contentId: String,
    val label: String,
    val provider: String? = null,
    val quality: Int = 0,
    val qualityLabel: String? = null
)

/** Por qué una fuente no llegó a vídeo. Cada tipo se ve tal cual en el diagnóstico. */
enum class FailureKind(val label: String, val short: String) {
    RESOLUTION("No se pudo resolver el Content ID (catálogo/descriptor)", "No se encuentra"),
    NO_PEERS("Sin peers: nadie está emitiendo esa fuente ahora", "Sin peers"),
    TIMEOUT("Tiempo agotado sin llegar a vídeo", "Sin imagen a tiempo"),
    PLAYER("Error del reproductor (Media3)", "Error de vídeo"),
    DROPPED("La fuente se cayó durante la reproducción", "Se cortó"),
    ENGINE_DOWN("El proceso del motor P2P no responde", "Motor sin respuesta"),
    PORT("Conflicto de puerto local", "Puerto ocupado"),
    CANCELLED("Cambiada a mano", "Cambiada a mano")
}

/** Cómo va cada fuente en el menú de fuentes del reproductor. */
enum class SourceStatus { PENDING, TRYING, PLAYING, FAILED, LEFT, QUARANTINED }

/**
 * Prueba las fuentes de UN canal de una en una (nunca varias a la vez). Lógica pura, sin Android.
 *
 *  - Identidad: Content ID NORMALIZADO. El mismo ID escrito de dos formas es una sola fuente: no se prueba dos veces.
 *  - Orden: SIEMPRE primero la mayor resolución (UHD → FHD → HD → SD → sin indicar). Dentro de la misma resolución:
 *    la última que funcionó en ese canal, luego la elegida, luego el resto. Las que fallaron hace poco van detrás;
 *    las de cuarentena en periodo de prueba, al final; las de cuarentena vigente NO se prueban solas (sí a mano).
 *  - Si el usuario eligió UNA fuente concreta ([explicit]), esa va la primera aunque sea de menos resolución.
 *  - Gana la primera que da VÍDEO REPRODUCIBLE (primer fotograma en Media3). Resolver el descriptor, tener peers o
 *    abrir la conexión no cuenta.
 *  - Dos pasadas y fin: 1) cada fuente una vez con lo que el motor ya sabe; 2) si ninguna dio vídeo, las que
 *    fallaron por causa propia otra vez, tras forzar una resolución desde cero ([startRecovery]). Si tampoco,
 *    "No hay fuente disponible": nunca se vuelve al principio (sin bucles A → B → A).
 *  - Solo un fallo en la SEGUNDA pasada de una fuente que ya falló en la primera es un fallo confirmado.
 *  - Si la ganadora se cae, se sigue con las que quedan sin probar. Siempre como mucho una activa.
 *  - Cada intento lleva un número de generación: los avisos de intentos anteriores (ya cancelados) se ignoran.
 */
class SourceSelector(
    candidates: List<SourceCandidate>,
    rememberedGood: String? = null,
    preferred: String? = null,
    recentlyFailed: Set<String> = emptySet(),
    explicit: Boolean = false,
    /** Salud de cada Content ID (normalizado). Sin entrada = normal. */
    availability: Map<String, Availability> = emptyMap()
) {
    sealed class State {
        object Idle : State()
        data class Trying(val index: Int, val source: SourceCandidate, val generation: Int) : State()
        data class Playing(val index: Int, val source: SourceCandidate, val generation: Int) : State()
        /** Sin más fuentes que probar. [recoverable] = queda la segunda pasada (resolución desde cero). */
        data class Exhausted(val lastFailure: FailureKind?, val recoverable: Boolean = false) : State()
    }

    private fun norm(id: String?): String? = id?.let { AceStreamLink.normalizedId(it) ?: it.trim().lowercase() }

    private val availabilityOf: Map<String, Availability> = HashMap<String, Availability>().apply {
        availability.forEach { (k, v) -> norm(k)?.let { put(it, v) } }
        recentlyFailed.forEach { k -> norm(k)?.let { if (get(it) == null) put(it, Availability.RECENTLY_FAILED) } }
    }

    val order: List<SourceCandidate> = run {
        val unique = candidates.distinctBy { norm(it.contentId) }
        val good = norm(rememberedGood)
        val pref = norm(preferred)
        val pinned = if (explicit) unique.firstOrNull { norm(it.contentId) == pref } else null
        fun tier(c: SourceCandidate) = when (norm(c.contentId)) {
            good -> 0
            pref -> 1
            else -> 2
        }
        fun health(c: SourceCandidate) = when (availabilityOf[norm(c.contentId)]) {
            Availability.RECENTLY_FAILED -> 1
            Availability.PROBATION -> 2
            Availability.EXCLUDED -> 3
            else -> 0
        }
        val rest = unique.filter { it !== pinned }.withIndex().sortedWith(
            compareBy<IndexedValue<SourceCandidate>> { health(it.value) }
                .thenByDescending { it.value.quality }
                .thenBy { tier(it.value) }
                .thenBy { it.index }
        ).map { it.value }
        listOfNotNull(pinned) + rest
    }

    /** Fuentes en cuarentena vigente: fuera de la búsqueda automática (se pueden elegir a mano). */
    private val excluded: Set<Int> = order.indices.filter { availabilityOf[norm(order[it].contentId)] == Availability.EXCLUDED }.toSet()

    var state: State = State.Idle
        private set
    private val tried = HashSet<Int>()
    private val failures = LinkedHashMap<String, FailureKind>()
    private val firstPassFailure = HashMap<Int, FailureKind>()
    private val succeeded = HashSet<Int>()
    private var generation = 0

    /** Segunda pasada en curso. */
    var recovering = false
        private set
    private var recoveryQueue: List<Int> = emptyList()
    private val recoveryTried = HashSet<Int>()
    private val confirmed = LinkedHashMap<String, FailureKind>()

    val switches: Int get() = (tried.size + recoveryTried.size - 1).coerceAtLeast(0)

    /** Cuántas se probarán solas (sin las de cuarentena vigente). */
    val automaticCount: Int get() = order.size - excluded.size

    /** Primer intento. */
    fun start(): State = next(null)

    /** El intento [gen] llegó a vídeo: gana. Avisos de intentos viejos se ignoran. */
    fun onVideo(gen: Int): State {
        val s = state
        if (s is State.Trying && s.generation == gen) {
            state = State.Playing(s.index, s.source, gen)
            succeeded += s.index
            confirmed.remove(norm(s.source.contentId))
        }
        return state
    }

    /** El intento [gen] falló (o la fuente que se estaba viendo se cayó): siguiente sin probar. */
    fun onFailure(gen: Int, kind: FailureKind): State {
        val s = state
        val (index, current) = when (s) {
            is State.Trying -> if (s.generation == gen) s.index to s.source else return state
            is State.Playing -> if (s.generation == gen) s.index to s.source else return state
            else -> return state
        }
        val effective = if (s is State.Playing) FailureKind.DROPPED else kind
        failures[current.contentId] = effective
        if (!recovering) {
            if (index !in firstPassFailure) firstPassFailure[index] = effective
        } else if (index in recoveryQueue && effective in HealthPolicy.SOURCE_FAULTS && index !in succeeded) {
            confirmed[norm(current.contentId)!!] = effective
        }
        return next(effective)
    }

    /** Opción avanzada: pasar a la siguiente fuente a mano (aunque la actual funcione). */
    fun manualNext(): State {
        val s = state
        if (s !is State.Trying && s !is State.Playing) return start()
        val active = currentIndex
        if (active != null) failures[order[active].contentId] = FailureKind.CANCELLED
        return next(FailureKind.CANCELLED)
    }

    fun failureOf(contentId: String): FailureKind? = failures[contentId]

    /** Índice de la fuente activa (probándose o viéndose), o null. */
    val currentIndex: Int? get() = when (val s = state) {
        is State.Trying -> s.index
        is State.Playing -> s.index
        else -> null
    }

    /** Estado de la fuente [index] para el menú. */
    fun statusOf(index: Int): SourceStatus {
        val s = state
        if (s is State.Trying && s.index == index) return SourceStatus.TRYING
        if (s is State.Playing && s.index == index) return SourceStatus.PLAYING
        return when (failures[order[index].contentId]) {
            null -> if (index in excluded && index !in tried) SourceStatus.QUARANTINED else SourceStatus.PENDING
            FailureKind.CANCELLED -> SourceStatus.LEFT
            else -> SourceStatus.FAILED
        }
    }

    /** Fuentes que fallaron en la primera pasada por causa propia (candidatas a la segunda). */
    fun suspects(): List<Int> = firstPassFailure.filter { (i, k) -> k in HealthPolicy.SOURCE_FAULTS && i !in succeeded }.keys.sorted()

    /**
     * Segunda pasada: cada sospechosa UNA vez más (el llamador ha forzado antes una resolución desde cero).
     * Solo se puede hacer una vez por ciclo.
     */
    fun startRecovery(): State {
        if (recovering) return state
        recovering = true
        recoveryQueue = suspects()
        return next(null)
    }

    /** Content IDs (normalizados) con fallo CONFIRMADO en este ciclo: fallaron en las dos pasadas. */
    fun confirmedFailures(): Map<String, FailureKind> = LinkedHashMap(confirmed)

    /** Content IDs (normalizados) que fallaron en la primera pasada por causa propia. */
    fun suspectIds(): Map<String, FailureKind> = suspects().associate { norm(order[it].contentId)!! to firstPassFailure.getValue(it) }

    /**
     * El usuario elige una fuente concreta en el menú (cualquiera: siguiente, anterior, una que ya falló o una en
     * cuarentena, para reintentarla). La activa se deja como "cambiada a mano". Si esa no da imagen, se sigue con las
     * que queden de la pasada en curso.
     */
    fun choose(index: Int): State {
        require(index in order.indices)
        val s = state
        val active = currentIndex
        if (active == index) return s
        if (active != null) failures[order[active].contentId] = FailureKind.CANCELLED
        failures.remove(order[index].contentId)
        if (recovering) recoveryTried += index else tried += index
        state = State.Trying(index, order[index], ++generation)
        return state
    }

    private fun next(lastFailure: FailureKind?): State {
        state = if (!recovering) {
            val index = order.indices.firstOrNull { it !in tried && it !in excluded }
            if (index == null) State.Exhausted(lastFailure, recoverable = suspects().isNotEmpty()) else {
                tried += index
                State.Trying(index, order[index], ++generation)
            }
        } else {
            val index = recoveryQueue.firstOrNull { it !in recoveryTried }
            if (index == null) State.Exhausted(lastFailure, recoverable = false) else {
                recoveryTried += index
                failures.remove(order[index].contentId)
                State.Trying(index, order[index], ++generation)
            }
        }
        return state
    }
}

/** Estado de una sesión del motor (`GET /streams/ace/<id>/status`). */
data class EngineStatus(
    val peers: Int,
    val bitrateBps: Long,
    val engineBufferMs: Long,
    val upstreamReconnects: Long,
    val staleTeardowns: Long,
    val skippedGaps: Long,
    val activeUpstreams: Int,
    val title: String?,
    /** Piezas entre el cursor del motor y el borde en directo del enjambre (livepos.live_last - pos), si se sabe. */
    val piecesBehindLive: Long? = null
) {
    val recoveries: Long get() = upstreamReconnects + staleTeardowns

    companion object {
        fun parse(body: String?): EngineStatus? = runCatching {
            val o = JsonParser.parseString(body.orEmpty()).asJsonObject
            val r = o.getAsJsonObject("recovery") ?: JsonObject()
            val meta = o.get("metadata")?.takeIf { it.isJsonObject }?.asJsonObject
            EngineStatus(
                peers = o.num("peers").toInt(),
                bitrateBps = o.num("bitrate"),
                engineBufferMs = o.num("buffer_ms"),
                upstreamReconnects = r.num("upstream_reconnects"),
                staleTeardowns = r.num("stale_teardowns"),
                skippedGaps = r.num("skipped_evicted_gaps"),
                activeUpstreams = r.num("active_upstreams").toInt(),
                title = meta?.get("title")?.takeIf { it.isJsonPrimitive }?.asString,
                piecesBehindLive = o.get("livepos")?.takeIf { it.isJsonObject }?.asJsonObject?.let { lp ->
                    val pos = lp.get("pos")?.takeIf { it.isJsonPrimitive }?.asLong
                    val last = lp.get("live_last")?.takeIf { it.isJsonPrimitive }?.asLong
                    if (pos != null && last != null) (last - pos).coerceAtLeast(0) else null
                }
            )
        }.getOrNull()

        private fun JsonObject.num(k: String): Long = get(k)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() } ?: 0L
    }
}

/**
 * Clasifica un fallo para el diagnóstico y para decidir pronto (sin esperar al tiempo máximo):
 *  - HTTP 422/400 del motor al abrir → resolución (Content ID sin descriptor válido);
 *  - conexión rechazada a 127.0.0.1 → el proceso del motor no responde;
 *  - sin peers durante [noPeersMs] → nadie emite esa fuente;
 *  - sin vídeo en [timeoutMs] → tiempo agotado.
 */
object FailurePolicy {
    const val TIMEOUT_MS = 30_000L
    const val NO_PEERS_MS = 15_000L

    fun fromHttpStatus(code: Int): FailureKind = when (code) {
        400, 404, 422 -> FailureKind.RESOLUTION
        // HLS: la lista no llegó a tener su primer segmento a tiempo (fuente sin datos), no es el motor.
        503 -> FailureKind.TIMEOUT
        in 500..599 -> FailureKind.ENGINE_DOWN
        else -> FailureKind.PLAYER
    }

    /**
     * ¿Abandonar ya el intento? [maxPeersSeen] = máximo de peers visto en este intento (el estado existe desde que
     * el motor resolvió el Content ID; si nunca hubo estado, se espera hasta el tiempo máximo).
     */
    fun earlyFailure(elapsedMs: Long, sawStatus: Boolean, maxPeersSeen: Int): FailureKind? = when {
        elapsedMs >= TIMEOUT_MS -> if (!sawStatus) FailureKind.RESOLUTION else if (maxPeersSeen == 0) FailureKind.NO_PEERS else FailureKind.TIMEOUT
        elapsedMs >= NO_PEERS_MS && sawStatus && maxPeersSeen == 0 -> FailureKind.NO_PEERS
        else -> null
    }
}
