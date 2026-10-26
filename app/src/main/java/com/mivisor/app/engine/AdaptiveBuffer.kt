package com.mivisor.app.engine

/**
 * Buffer del reproductor PEQUEÑO y ADAPTATIVO (lógica pura; la usa [AdaptiveLoadControl] de Media3).
 *
 *  - Arranque: en cuanto hay ~2,5 s útiles (si la fuente lo permite), no 20-30 s.
 *  - Viendo el canal: colchón normal de ~6 s (5 en dispositivos low-RAM).
 *  - Fuente inestable (cortes de buffer): el objetivo sube por pasos hasta ~10 s (8 en low-RAM) como máximo.
 *  - Fuente estable de nuevo: no sigue creciendo y, tras unos minutos sin cortes, baja poco a poco.
 *
 * Estos valores son iniciales para medir y ajustar. El buffer no se puede "fabricar": si el P2P solo llega a
 * tiempo real, el colchón no crece por mucho que suba el objetivo; por eso subirlo es barato y no es la cura
 * de todo (la causa real queda en el diagnóstico).
 */
class AdaptiveBuffer(lowRam: Boolean, private val clock: () -> Long = System::currentTimeMillis) {
    val startMs: Long = 2_500
    val normalMs: Long = if (lowRam) 5_000 else 6_000
    val unstableCapMs: Long = if (lowRam) 8_000 else 10_000
    /** Tras un corte se pide algo más que el arranque antes de seguir, para no entrar en bucle de cortes. */
    val afterRebufferMs: Long = 4_000
    private val stepUpMs = 1_500L
    private val stepDownMs = 1_000L
    private val stableBeforeShrinkMs = 3 * 60_000L
    private val shrinkEveryMs = 60_000L
    /** Histéresis: se deja de cargar en el objetivo y se reanuda 1 s por debajo (evita encender/apagar sin parar). */
    private val hysteresisMs = 1_000L

    @Volatile var targetMs: Long = normalMs
        private set
    @Volatile var rebuffers: Int = 0
        private set
    private var lastRebufferAt = 0L
    private var lastShrinkAt = 0L
    private var loading = true

    /** Un corte con el vídeo ya en marcha: la fuente está inestable → objetivo un poco más alto (con tope). */
    @Synchronized
    fun onRebuffer() {
        rebuffers++
        lastRebufferAt = clock()
        targetMs = (targetMs + stepUpMs).coerceAtMost(unstableCapMs)
    }

    /** Cada pocos segundos mientras se reproduce: si lleva rato estable, el objetivo vuelve hacia el normal. */
    @Synchronized
    fun onTick() {
        val now = clock()
        if (targetMs <= normalMs) return
        if (now - lastRebufferAt < stableBeforeShrinkMs) return
        if (now - lastShrinkAt < shrinkEveryMs) return
        targetMs = (targetMs - stepDownMs).coerceAtLeast(normalMs)
        lastShrinkAt = now
    }

    fun shouldStartPlayback(bufferedMs: Long, rebuffering: Boolean): Boolean =
        bufferedMs >= if (rebuffering) minOf(afterRebufferMs, targetMs) else startMs

    @Synchronized
    fun shouldContinueLoading(bufferedMs: Long): Boolean {
        loading = when {
            bufferedMs >= targetMs -> false
            bufferedMs < targetMs - hysteresisMs -> true
            else -> loading
        }
        return loading
    }

    @Synchronized
    fun reset() {
        targetMs = normalMs
        rebuffers = 0
        lastRebufferAt = 0
        lastShrinkAt = 0
        loading = true
    }
}
