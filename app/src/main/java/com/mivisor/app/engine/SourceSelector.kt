package com.mivisor.app.engine

import com.google.gson.JsonObject
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
enum class FailureKind(val label: String) {
    RESOLUTION("No se pudo resolver el Content ID (catálogo/descriptor)"),
    NO_PEERS("Sin peers: nadie está emitiendo esa fuente ahora"),
    TIMEOUT("Tiempo agotado sin llegar a vídeo"),
    PLAYER("Error del reproductor (Media3)"),
    DROPPED("La fuente se cayó durante la reproducción"),
    ENGINE_DOWN("El proceso del motor P2P no responde"),
    PORT("Conflicto de puerto local"),
    CANCELLED("Cancelada")
}

/**
 * Prueba las fuentes de UN canal de una en una (nunca varias a la vez). Lógica pura, sin Android.
 *
 *  - Orden: SIEMPRE primero la mayor resolución (UHD → FHD → HD → SD → sin indicar). Dentro de la misma resolución:
 *    la última que funcionó en ese canal, luego la elegida, luego el resto. Las que fallaron hace poco, al final.
 *  - Si el usuario eligió UNA fuente concreta ([explicit]), esa va la primera aunque sea de menos resolución.
 *  - Gana la primera que da VÍDEO REPRODUCIBLE (primer fotograma en Media3). Resolver el descriptor, tener peers o
 *    abrir la conexión no cuenta.
 *  - Si la ganadora se cae, se sigue con las que quedan sin probar. Siempre como mucho una activa.
 *  - Cada intento lleva un número de generación: los avisos de intentos anteriores (ya cancelados) se ignoran.
 */
class SourceSelector(
    candidates: List<SourceCandidate>,
    rememberedGood: String? = null,
    preferred: String? = null,
    recentlyFailed: Set<String> = emptySet(),
    explicit: Boolean = false
) {
    sealed class State {
        object Idle : State()
        data class Trying(val index: Int, val source: SourceCandidate, val generation: Int) : State()
        data class Playing(val index: Int, val source: SourceCandidate, val generation: Int) : State()
        data class Exhausted(val lastFailure: FailureKind?) : State()
    }

    val order: List<SourceCandidate> = run {
        val unique = candidates.distinctBy { it.contentId.lowercase() }
        val failed = recentlyFailed.map(String::lowercase).toSet()
        val pinned = if (explicit) unique.firstOrNull { it.contentId.equals(preferred, true) } else null
        fun tier(c: SourceCandidate) = when {
            c.contentId.equals(rememberedGood, true) -> 0
            c.contentId.equals(preferred, true) -> 1
            else -> 2
        }
        val rest = unique.filter { it !== pinned }.withIndex().sortedWith(
            compareBy<IndexedValue<SourceCandidate>> { if (it.value.contentId.lowercase() in failed) 1 else 0 }
                .thenByDescending { it.value.quality }
                .thenBy { tier(it.value) }
                .thenBy { it.index }
        ).map { it.value }
        listOfNotNull(pinned) + rest
    }

    var state: State = State.Idle
        private set
    private val tried = HashSet<Int>()
    private val failures = LinkedHashMap<String, FailureKind>()
    private var generation = 0
    val switches: Int get() = (tried.size - 1).coerceAtLeast(0)

    /** Primer intento. */
    fun start(): State = next(null)

    /** El intento [gen] llegó a vídeo: gana. Avisos de intentos viejos se ignoran. */
    fun onVideo(gen: Int): State {
        val s = state
        if (s is State.Trying && s.generation == gen) state = State.Playing(s.index, s.source, gen)
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
        failures[current.contentId] = if (s is State.Playing) FailureKind.DROPPED else kind
        return next(failures[current.contentId])
    }

    /** Opción avanzada: pasar a la siguiente fuente a mano (aunque la actual funcione). */
    fun manualNext(): State {
        val s = state
        if (s !is State.Trying && s !is State.Playing) return start()
        return next(FailureKind.CANCELLED)
    }

    fun failureOf(contentId: String): FailureKind? = failures[contentId]

    private fun next(lastFailure: FailureKind?): State {
        val index = order.indices.firstOrNull { it !in tried }
        state = if (index == null) State.Exhausted(lastFailure) else {
            tried += index
            State.Trying(index, order[index], ++generation)
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
    val title: String?
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
                title = meta?.get("title")?.takeIf { it.isJsonPrimitive }?.asString
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
