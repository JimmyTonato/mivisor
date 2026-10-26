package com.mivisor.app.engine

import com.mivisor.app.diagnostics.LogSanitizer
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Diagnóstico del motor interno (lo que se ve en Ajustes → Diagnóstico del motor, sin ADB).
 *
 *  - Log en memoria con TAMAÑO MÁXIMO ([MAX_LINES]); nunca crece sin límite. Se puede activar/desactivar, copiar
 *    (al móvil por QR) y limpiar. Desactivado solo guarda los eventos de MiVisor marcados como importantes; las
 *    líneas del proceso del motor se leen igualmente (hay que vaciar su salida) pero no se guardan.
 *  - Cada línea lleva una categoría para distinguir el problema: RESOLUCIÓN, PEERS, TIMEOUT, DISCONTINUIDAD,
 *    MEDIA3, PROCESO, PUERTO, FUENTE, BUFFER, MEMORIA.
 *  - Content IDs, hashes, URLs y tokens se limpian ([LogSanitizer]); los IDs se muestran enmascarados (abcd…wxyz).
 *  - Métricas de la sesión actual (peers, bitrate, buffers, recuperaciones, cambios de fuente, primera imagen…).
 */
object EngineDiagnostics {
    const val MAX_LINES = 800
    private const val MAX_LINE_CHARS = 300

    enum class Cat(val label: String) {
        RESOLUTION("RESOLUCIÓN"), PEERS("PEERS"), TIMEOUT("TIMEOUT"), DISCONTINUITY("DISCONTINUIDAD"), MEDIA3("MEDIA3"),
        PROCESS("PROCESO"), PORT("PUERTO"), SOURCE("FUENTE"), BUFFER("BUFFER"), MEMORY("MEMORIA"), ENGINE("MOTOR")
    }

    data class Metrics(
        val engineMode: String = "—",
        val engineState: String = "detenido",
        val port: Int? = null,
        val channel: String? = null,
        val sourceIndex: Int = 0,
        val sourceCount: Int = 0,
        val sourceId: String? = null,
        val peers: Int = 0,
        val bitrateBps: Long = 0,
        val engineBufferMs: Long = 0,
        val playerBufferMs: Long = 0,
        val bufferTargetMs: Long = 0,
        val recoveries: Long = 0,
        val skippedGaps: Long = 0,
        val sourceSwitches: Int = 0,
        val rebuffers: Int = 0,
        val firstFrameMs: Long? = null,
        val livePositionMs: Long? = null,
        val deviceRamMb: Long = 0,
        val deviceAvailMb: Long = 0,
        val appUsedMb: Long = 0,
        val lowRam: Boolean = false
    )

    private val lines = ArrayDeque<String>()
    @Volatile var enabled: Boolean = false
    @Volatile var metrics: Metrics = Metrics()
        private set
    @Volatile var revision: Long = 0
        private set

    fun log(cat: Cat, text: String, important: Boolean = false) {
        if (!enabled && !important) return
        add("${stamp()} [${cat.label}] ${LogSanitizer.clean(text).take(MAX_LINE_CHARS)}")
    }

    /** Línea del proceso del motor: se clasifica por palabras clave para que se distinga el tipo de problema. */
    fun engineLine(raw: String) {
        if (!enabled) return
        add("${stamp()} [motor/${classify(raw).label}] ${LogSanitizer.clean(raw).take(MAX_LINE_CHARS)}")
    }

    fun update(transform: (Metrics) -> Metrics) {
        metrics = transform(metrics)
        revision++
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() {
        lines.clear()
        revision++
    }

    /** Texto para copiar (al móvil por QR) con las métricas y el log. */
    fun report(): String = buildString {
        val m = metrics
        appendLine("Diagnóstico del motor de reproducción · ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("Motor seleccionado: ${m.engineMode} · estado: ${m.engineState} · puerto localhost: ${m.port ?: "—"}")
        appendLine("Canal: ${m.channel ?: "—"} · fuente ${m.sourceIndex}/${m.sourceCount} · ${m.sourceId ?: "—"}")
        appendLine("Peers: ${m.peers} · bitrate: ${"%.2f".format(Locale.US, m.bitrateBps / 1_000_000.0)} Mb/s")
        appendLine("Buffer motor: ${m.engineBufferMs} ms · buffer Media3: ${m.playerBufferMs} ms (objetivo ${m.bufferTargetMs} ms)")
        appendLine("Recuperaciones: ${m.recoveries} · huecos saltados: ${m.skippedGaps} · cambios de fuente: ${m.sourceSwitches} · cortes: ${m.rebuffers}")
        appendLine("Primera imagen: ${m.firstFrameMs?.let { "$it ms" } ?: "—"} · posición en directo: ${m.livePositionMs?.let { "$it ms" } ?: "—"}")
        appendLine("RAM dispositivo: ${m.deviceRamMb} MB (libre ${m.deviceAvailMb} MB) · MiVisor: ${m.appUsedMb} MB · low-RAM: ${if (m.lowRam) "sí" else "no"}")
        appendLine()
        snapshot().forEach { appendLine(it) }
    }

    fun classify(line: String): Cat {
        val l = line.lowercase(Locale.ROOT)
        return when {
            "address in use" in l || "addrinuse" in l || "bind" in l && "fail" in l -> Cat.PORT
            "catalog" in l || "descriptor" in l || "resolv" in l || "bep9" in l || "bep-9" in l -> Cat.RESOLUTION
            "discontinu" in l || "keyframe" in l || "gap" in l || "skipped" in l -> Cat.DISCONTINUITY
            "peer" in l || "tracker" in l || "dht" in l || "upstream" in l -> Cat.PEERS
            "timeout" in l || "timed out" in l -> Cat.TIMEOUT
            "panic" in l || "fatal" in l || "error" in l -> Cat.PROCESS
            else -> Cat.ENGINE
        }
    }

    @Synchronized
    private fun add(line: String) {
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        revision++
    }

    private fun stamp() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
}
