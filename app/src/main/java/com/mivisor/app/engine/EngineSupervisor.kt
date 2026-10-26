package com.mivisor.app.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL

/**
 * Ciclo de vida del ÚNICO proceso del motor P2P de MiVisor (JVM puro: se prueba con el binario real).
 *
 *  - Un solo proceso para toda la app; nunca uno por canal. [ensureStarted] es idempotente y está serializado.
 *  - Puerto: 127.0.0.1:16900 si está libre; si lo ocupa algo ajeno, otro puerto local libre. Nunca se mata a
 *    procesos de otras apps.
 *  - Huérfanos: se guarda el pid de NUESTRO proceso (engine.pid). Si la app murió y el motor quedó vivo, al volver
 *    se cierra solo si el pid sigue siendo nuestro binario (se comprueba /proc/<pid>/cmdline).
 *  - Parada: cierre normal, espera a que salga y, si no responde, terminación forzada SOLO de ese proceso.
 *  - La salida del proceso siempre se lee (si no, el proceso se bloquearía con la tubería llena); se pasa al
 *    diagnóstico, que decide si la guarda.
 */
class EngineSupervisor(
    private val binary: File,
    private val workDir: File,
    private val lowRam: Boolean,
    /** Fuerza la terminación de un pid NUESTRO (Android: android.os.Process.killProcess). */
    private val killPid: (Int) -> Unit,
    private val onLine: (String) -> Unit = {},
    private val preferredPort: Int = EngineConfig.PREFERRED_PORT,
    private val readyTimeoutMs: Long = 20_000
) {
    enum class Phase { STOPPED, STARTING, READY, FAILED }

    data class State(val phase: Phase, val port: Int? = null, val message: String? = null, val pid: Int? = null) {
        val baseUrl: String? get() = port?.takeIf { phase == Phase.READY }?.let { "http://${EngineConfig.HOST}:$it" }
    }

    private val mutex = Mutex()
    @Volatile var state: State = State(Phase.STOPPED)
        private set
    @Volatile private var process: Process? = null
    private val pidFile get() = File(workDir, "engine.pid")

    /** Arranca (o reutiliza) el motor. Devuelve el estado final: READY con puerto, o FAILED con el motivo. */
    suspend fun ensureStarted(listener: (State) -> Unit = {}): State = mutex.withLock {
        val current = process
        if (current != null && alive(current) && state.phase == Phase.READY && health(state.port)) return state
        withContext(Dispatchers.IO) {
            stopLocked()
            killOrphan()
            val port = choosePort()
            val cfg = EngineConfig(port, File(workDir, "outpace").absolutePath, workDir.absolutePath, lowRam)
            val problems = cfg.validate()
            if (problems.isNotEmpty()) return@withContext set(State(Phase.FAILED, message = "Configuración no válida: ${problems.first()}"), listener)
            if (!binary.isFile) return@withContext set(State(Phase.FAILED, message = "No está el binario del motor para esta arquitectura"), listener)
            set(State(Phase.STARTING, port), listener)
            EngineDiagnostics.log(EngineDiagnostics.Cat.PROCESS, "Arrancando motor en 127.0.0.1:$port (low-RAM: $lowRam)", important = true)
            val p = try {
                ProcessBuilder(binary.absolutePath, "serve").redirectErrorStream(true).directory(workDir.apply { mkdirs() }).also { pb ->
                    pb.environment().putAll(cfg.environment())
                }.start()
            } catch (e: Exception) {
                return@withContext set(State(Phase.FAILED, port, "No se pudo lanzar el proceso: ${e.javaClass.simpleName}"), listener)
            }
            process = p
            val pid = pidOf(p)
            pid?.let { runCatching { pidFile.writeText("$it\n${binary.absolutePath}") } }
            startReader(p)
            val deadline = System.currentTimeMillis() + readyTimeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (!alive(p)) break
                if (health(port)) {
                    EngineDiagnostics.log(EngineDiagnostics.Cat.PROCESS, "Motor preparado en el puerto $port", important = true)
                    return@withContext set(State(Phase.READY, port, pid = pid), listener)
                }
                delay(300)
            }
            val exited = !alive(p)
            if (exited) readerThread?.join(500)                             // que el log lea la última línea
            val reason = if (exited) "El proceso del motor terminó al arrancar (código ${runCatching { p.exitValue() }.getOrNull()})"
            else "El motor no respondió en ${readyTimeoutMs / 1000} s"
            EngineDiagnostics.log(if (lastLineLooksLikePort) EngineDiagnostics.Cat.PORT else EngineDiagnostics.Cat.PROCESS, reason, important = true)
            stopLocked()
            set(State(Phase.FAILED, port, reason + if (lastLineLooksLikePort) " (puerto ocupado)" else ""), listener)
        }
    }

    /** Para el motor (y sus sesiones). Idempotente. */
    suspend fun stop() = mutex.withLock { withContext(Dispatchers.IO) { stopLocked() } }

    fun isRunning(): Boolean = process?.let(::alive) == true

    // ------------------------------------------------------------------ interno

    @Volatile private var lastLineLooksLikePort = false
    @Volatile private var readerThread: Thread? = null

    private fun startReader(p: Process) {
        lastLineLooksLikePort = false
        readerThread = Thread({
            runCatching {
                p.inputStream.bufferedReader().useLines { seq ->
                    seq.forEach { line ->
                        if (EngineDiagnostics.classify(line) == EngineDiagnostics.Cat.PORT) lastLineLooksLikePort = true
                        onLine(line)
                    }
                }
            }
        }, "outpace-log").apply { isDaemon = true; start() }
    }

    private fun stopLocked() {
        // Sin proceso propio en esta instancia: NO se borra engine.pid (puede ser de un motor huérfano que
        // killOrphan tiene que cerrar justo después).
        val p = process ?: run { state = State(Phase.STOPPED); return }
        process = null
        val pid = pidOf(p)
        runCatching { p.destroy() }                                   // cierre normal
        val until = System.currentTimeMillis() + 1_500
        while (alive(p) && System.currentTimeMillis() < until) Thread.sleep(50)
        if (alive(p)) {                                                // no responde: solo ESTE proceso
            EngineDiagnostics.log(EngineDiagnostics.Cat.PROCESS, "El motor no se cerró a tiempo: terminación forzada", important = true)
            pid?.let { runCatching { killPid(it) } }
            val until2 = System.currentTimeMillis() + 1_000
            while (alive(p) && System.currentTimeMillis() < until2) Thread.sleep(50)
        }
        runCatching { p.inputStream.close() }
        runCatching { pidFile.delete() }
        state = State(Phase.STOPPED)
    }

    /** Un motor nuestro que sobrevivió a la app anterior: se cierra solo si el pid sigue siendo nuestro binario. */
    private fun killOrphan() {
        val lines = runCatching { pidFile.readLines() }.getOrNull() ?: return
        val pid = lines.firstOrNull()?.trim()?.toIntOrNull()
        val path = lines.getOrNull(1)?.trim()
        if (pid != null && path != null) {
            val cmdline = runCatching { File("/proc/$pid/cmdline").readText().replace('\u0000', ' ') }.getOrNull()
            if (cmdline != null && cmdline.contains(path)) {
                EngineDiagnostics.log(EngineDiagnostics.Cat.PROCESS, "Cerrando un motor anterior de MiVisor que quedó vivo (pid $pid)", important = true)
                runCatching { killPid(pid) }
                val until = System.currentTimeMillis() + 1_500
                while (File("/proc/$pid").exists() && System.currentTimeMillis() < until) Thread.sleep(50)
            }
        }
        runCatching { pidFile.delete() }
    }

    private fun choosePort(): Int {
        if (portFree(preferredPort)) return preferredPort
        EngineDiagnostics.log(EngineDiagnostics.Cat.PORT, "El puerto $preferredPort está ocupado por otra aplicación: se usa otro puerto local", important = true)
        return ServerSocket(0, 1, InetAddress.getByName(EngineConfig.HOST)).use { it.localPort }
    }

    private fun set(s: State, listener: (State) -> Unit): State {
        state = s
        listener(s)
        return s
    }

    companion object {
        /**
         * ¿Puede el motor escuchar en [port]? Igual que hace él al arrancar (SO_REUSEADDR): una conexión vieja en
         * TIME_WAIT no cuenta como ocupado; otra app ESCUCHANDO en ese puerto, sí.
         */
        fun portFree(port: Int): Boolean = runCatching {
            ServerSocket().use { s ->
                s.reuseAddress = true
                s.bind(java.net.InetSocketAddress(InetAddress.getByName(EngineConfig.HOST), port), 1)
                true
            }
        }.getOrDefault(false)

        fun health(port: Int?): Boolean {
            port ?: return false
            var c: HttpURLConnection? = null
            return try {
                c = URL("http://${EngineConfig.HOST}:$port/healthz").openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
                c.connectTimeout = 600
                c.readTimeout = 600
                c.responseCode == 200
            } catch (e: Exception) {
                false
            } finally {
                c?.disconnect()
            }
        }

        /** Process.isAlive() es de API 26: en API 23-25 se usa exitValue(). */
        fun alive(p: Process): Boolean = try {
            p.exitValue(); false
        } catch (e: IllegalThreadStateException) {
            true
        }

        /** pid del proceso (campo "pid" en Android; pid() en Java 9+). */
        fun pidOf(p: Process): Int? = runCatching {
            p.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(p)
        }.recoverCatching {
            (Process::class.java.getMethod("pid").invoke(p) as Number).toInt()
        }.getOrNull()
    }
}
