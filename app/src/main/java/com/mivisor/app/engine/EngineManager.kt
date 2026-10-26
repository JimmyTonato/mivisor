package com.mivisor.app.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Motor P2P interno de MiVisor (Android). Un solo proceso para toda la app, gestionado por [EngineSupervisor].
 *
 *  - Disponible en Android 6.0+ (el binario se compila contra la API 23) y en ARM64/ARMv7. En el resto, el modo
 *    Automático usa Ace Stream externo y el Interno avisa de que no está disponible.
 *  - No necesita la app oficial de Ace Stream ni ningún servicio de terceros como canalpeer.
 *  - En segundo plano: se para la reproducción enseguida y el motor al cabo de un rato (antes en low-RAM).
 */
object EngineManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var supervisor: EngineSupervisor? = null
    private var backgroundStop: Job? = null
    private val _state = MutableStateFlow(EngineSupervisor.State(EngineSupervisor.Phase.STOPPED))
    val state: StateFlow<EngineSupervisor.State> = _state.asStateFlow()

    fun binary(context: Context): File = File(context.applicationInfo.nativeLibraryDir, EngineConfig.BINARY_NAME)

    /** ¿Puede usarse el motor interno en este dispositivo? */
    fun isSupported(context: Context): Boolean = Build.VERSION.SDK_INT >= 23 && binary(context).isFile

    fun unsupportedReason(context: Context): String? = when {
        Build.VERSION.SDK_INT < 23 -> "El motor interno necesita Android 6.0 o superior"
        !binary(context).isFile -> "El motor interno no está incluido para esta arquitectura (${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"})"
        else -> null
    }

    fun isLowRam(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return true
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return am.isLowRamDevice || info.totalMem in 1..(1_536L shl 20)      // ≤ 1,5 GB: límites conservadores
    }

    @Synchronized
    private fun supervisor(context: Context): EngineSupervisor = supervisor ?: EngineSupervisor(
        binary = binary(context),
        workDir = File(context.filesDir, "engine"),
        lowRam = isLowRam(context),
        killPid = { pid -> android.os.Process.killProcess(pid) },    // solo procesos de nuestro propio uid
        onLine = EngineDiagnostics::engineLine
    ).also { supervisor = it }

    /** Arranca o reutiliza el motor. Idempotente: nunca crea un segundo proceso. */
    suspend fun ensureStarted(context: Context): EngineSupervisor.State {
        cancelBackgroundStop()
        unsupportedReason(context)?.let {
            return EngineSupervisor.State(EngineSupervisor.Phase.FAILED, message = it).also { s -> _state.value = s }
        }
        return supervisor(context.applicationContext).ensureStarted { s ->
            _state.value = s
            EngineDiagnostics.update { m -> m.copy(engineState = s.phase.label(), port = s.port) }
        }
    }

    suspend fun stop() {
        supervisor?.stop()
        _state.value = EngineSupervisor.State(EngineSupervisor.Phase.STOPPED)
        EngineDiagnostics.update { it.copy(engineState = "detenido", port = null) }
    }

    /** La app pasa a segundo plano: el motor se para si no vuelve en [delayMs] (30 s en low-RAM, 2 min si no). */
    fun onBackground(context: Context) {
        val delayMs = if (isLowRam(context)) 30_000L else 120_000L
        backgroundStop?.cancel()
        backgroundStop = scope.launch {
            delay(delayMs)
            EngineDiagnostics.log(EngineDiagnostics.Cat.PROCESS, "MiVisor en segundo plano: se para el motor para liberar memoria", important = true)
            stop()
        }
    }

    fun cancelBackgroundStop() {
        backgroundStop?.cancel()
        backgroundStop = null
    }

    /** RAM del dispositivo y de MiVisor (incluido el proceso del motor no: es otro proceso; se ve en "motor"). */
    fun refreshMemoryMetrics(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val pss = runCatching { Debug.getPss() / 1024 }.getOrDefault(0L)
        EngineDiagnostics.update {
            it.copy(deviceRamMb = info.totalMem shr 20, deviceAvailMb = info.availMem shr 20, appUsedMb = pss, lowRam = isLowRam(context))
        }
    }

    private fun EngineSupervisor.Phase.label() = when (this) {
        EngineSupervisor.Phase.STOPPED -> "detenido"
        EngineSupervisor.Phase.STARTING -> "arrancando"
        EngineSupervisor.Phase.READY -> "preparado"
        EngineSupervisor.Phase.FAILED -> "error"
    }
}
