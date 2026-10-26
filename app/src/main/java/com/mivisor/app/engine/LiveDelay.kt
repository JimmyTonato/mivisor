package com.mivisor.app.engine

/**
 * Números del control de retraso respecto al directo, en un sitio.
 * El OBJETIVO no está aquí: es el del buffer adaptativo ([AdaptiveBuffer.targetMs]), ~5–7 s normal y hasta
 * 8–10 s temporalmente si la fuente se corta.
 */
object LiveDelayPolicy {
    /** Dentro de ±esto no se hace nada. */
    const val TOLERANCE_MS = 1_000L
    /** Exceso pequeño (hasta esto): se recupera acelerando un poco. A partir de aquí: salto limpio. */
    const val RESYNC_FROM_MS = 3_000L
    /** Velocidad durante la recuperación suave (imperceptible: Media3 mantiene el tono del audio). */
    const val SPEED_UP = 1.06f
    /** Se vuelve a 1.0x cuando el exceso baja de esto. */
    const val SPEED_UP_DONE_MS = 250L
    /** Tras una interrupción se espera esto (reproducción estable) antes de medir y decidir. */
    const val SETTLE_MS = 2_000L
    /** Como mucho un salto cada… (evita saltos en cadena si la medida tarda en estabilizarse). */
    const val RESYNC_COOLDOWN_MS = 10_000L
}

/**
 * Mantiene el retraso respecto al directo cerca del objetivo para que los cortes NO se acumulen
 * (8 s → corte → 13 s → corte → 18 s…). Lógica pura: recibe medidas y devuelve qué hacer.
 *
 *  - Exceso ≤ tolerancia: nada (1.0x).
 *  - Exceso pequeño (1–3 s): aceleración ligera temporal hasta volver al objetivo; luego 1.0x.
 *  - Exceso grande (≥ 3 s): salto limpio a "borde − objetivo" (lo atrasado se descarta). Quien ejecuta el salto lo
 *    hace en un punto decodificable (segmento HLS que empieza en keyframe, o nueva conexión con keyframe gate).
 *  - En directo, mantener el retraso objetivo tiene prioridad sobre conservar la posición.
 */
class LiveDelayController {

    sealed class Action {
        object None : Action()
        data class SetSpeed(val speed: Float) : Action()
        /** Volver a [targetDelayMs] del borde descartando [discardMs] de atraso. */
        data class Resync(val targetDelayMs: Long, val discardMs: Long) : Action()
    }

    data class Stats(
        val targetMs: Long = 0,
        val delayMs: Long? = null,
        val resyncs: Int = 0,
        val discardedMs: Long = 0,
        val speedUps: Int = 0,
        val speedUpsCompleted: Int = 0,
        val speeding: Boolean = false
    ) {
        val deviationMs: Long? get() = delayMs?.let { it - targetMs }
    }

    var stats = Stats()
        private set
    private var unstableSince = 0L
    private var stableSince = -1L
    private var lastResyncAt = Long.MIN_VALUE / 2

    /** Algo interrumpió la reproducción (buffering, salto, reconexión, pausa): se espera a que se estabilice. */
    fun onInterruption(nowMs: Long) {
        unstableSince = nowMs
        stableSince = -1
    }

    /**
     * Cada ~1 s con la reproducción en marcha. [delayMs] = retraso medido respecto al borde (null = no se sabe);
     * [playing] = listo y reproduciendo (no pausado, no cargando).
     */
    fun onTick(nowMs: Long, delayMs: Long?, targetMs: Long, playing: Boolean): Action {
        stats = stats.copy(targetMs = targetMs, delayMs = delayMs)
        if (!playing || delayMs == null) {
            if (!playing) onInterruption(nowMs)
            return stopSpeeding()
        }
        if (stableSince < 0) stableSince = nowMs
        if (nowMs - stableSince < LiveDelayPolicy.SETTLE_MS) return Action.None
        val excess = delayMs - targetMs
        return when {
            excess >= LiveDelayPolicy.RESYNC_FROM_MS && nowMs - lastResyncAt >= LiveDelayPolicy.RESYNC_COOLDOWN_MS -> {
                lastResyncAt = nowMs
                stats = stats.copy(resyncs = stats.resyncs + 1, discardedMs = stats.discardedMs + excess, speeding = false)
                onInterruption(nowMs)                                // el salto es una interrupción: se vuelve a medir
                Action.Resync(targetMs, excess)
            }
            excess > LiveDelayPolicy.TOLERANCE_MS && !stats.speeding -> {
                stats = stats.copy(speedUps = stats.speedUps + 1, speeding = true)
                Action.SetSpeed(LiveDelayPolicy.SPEED_UP)
            }
            stats.speeding && excess <= LiveDelayPolicy.SPEED_UP_DONE_MS -> {
                stats = stats.copy(speedUpsCompleted = stats.speedUpsCompleted + 1, speeding = false)
                Action.SetSpeed(1f)
            }
            else -> Action.None
        }
    }

    /** Nueva fuente / nuevo canal: se olvida la medida (las estadísticas de saltos se conservan por sesión). */
    fun reset(nowMs: Long, keepStats: Boolean = false) {
        if (!keepStats) stats = Stats()
        stats = stats.copy(speeding = false, delayMs = null)
        onInterruption(nowMs)
        lastResyncAt = Long.MIN_VALUE / 2
    }

    private fun stopSpeeding(): Action =
        if (stats.speeding) { stats = stats.copy(speeding = false); Action.SetSpeed(1f) } else Action.None
}

/**
 * Retraso estimado cuando la entrega es TS continuo (sin ventana HLS que medir): lo que el reloj avanza y la
 * reproducción no. Base = retraso en el primer instante estable (buffer del motor + buffer del reproductor).
 * Cada parón (el reloj sigue, la posición no) suma; acelerar resta.
 */
class LagEstimator {
    private var t0 = -1L
    private var p0 = 0L
    private var base = 0L

    val started: Boolean get() = t0 >= 0

    fun start(nowMs: Long, positionMs: Long, baseDelayMs: Long) {
        t0 = nowMs; p0 = positionMs; base = baseDelayMs
    }

    fun reset() { t0 = -1 }

    fun estimate(nowMs: Long, positionMs: Long): Long? =
        if (t0 < 0) null else (base + (nowMs - t0) - (positionMs - p0)).coerceAtLeast(0)
}
