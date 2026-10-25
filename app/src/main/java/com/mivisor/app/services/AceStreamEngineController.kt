package com.mivisor.app.services

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.acestream.engine.ServiceClient
import org.acestream.engine.service.v0.IAceStreamEngine
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ace Stream Engine mediante la integración OFICIAL (ServiceClient + AIDL de acestream-engine-client).
 *
 *  - Detección: ServiceClient.getServicePackage() elige la app Ace Stream instalada (media, media.atv,
 *    core, core.atv u otra que exponga el servicio) con mayor versión. Nunca se miran procesos.
 *  - Estado al abrir/volver: solo HTTP get_version en el último puerto que dio el servicio (6878 si nunca
 *    lo dio). No se arranca el motor por abrir MiVisor.
 *  - Iniciar: ServiceClient.startEngine() → onConnected(service) → puerto HTTP real del servicio →
 *    get_version hasta que responda (READY con evidencia) o 15 s (ERROR). Sin bloquear el hilo principal.
 *  - Apagar: Ace Stream NO ofrece un método oficial y verificable para parar el motor (la AIDL no tiene
 *    stopEngine y la HTTP API no documenta shutdown). Por eso no hay interruptor OFF.
 *    Soltar el ServiceClient (release) NO apaga el motor: solo deja de estar enlazado.
 */
class AceStreamEngineController(
    context: Context,
    private val scope: CoroutineScope
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("quick_services", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(AceStatus(AcePhase.CHECKING))
    val state: StateFlow<AceStatus> = _state.asStateFlow()

    private var client: ServiceClient? = null
    private var startJob: Job? = null

    private var lastPort: Int
        get() = prefs.getInt(KEY_PORT, AceProtocol.DEFAULT_HTTP_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    /**
     * Última vez que MiVisor arrancó el motor por el servicio oficial (ms) y por qué. Para el Registro Ace Stream:
     * la app antigua NUNCA arranca el motor; lo hace la propia app de Ace al abrir acestream:// (la "redirección").
     */
    val startedByMiVisor: Pair<Long, String>?
        get() = prefs.getLong(KEY_STARTED, 0L).takeIf { it > 0 }?.let { it to prefs.getString(KEY_STARTED + "_why", null).orEmpty() }

    fun isInstalled(): Boolean = try {
        ServiceClient.getServicePackage(app); true
    } catch (e: ServiceClient.ServiceMissingException) {
        false
    }

    /** Estado real sin arrancar nada: instalado + HTTP API respondiendo. */
    suspend fun refresh(): AceStatus {
        if (_state.value.phase == AcePhase.STARTING) return _state.value
        val result = if (!isInstalled()) {
            AceStatus(AcePhase.NOT_INSTALLED)
        } else {
            val port = lastPort
            val version = probe(port)
            if (version != null) AceStatus(AcePhase.READY, port, version) else AceStatus(AcePhase.STOPPED, port)
        }
        if (_state.value.phase != AcePhase.STARTING) set(result, "STATUS")
        return _state.value
    }

    /** Pulsar "Iniciar"/"Reintentar": arranque oficial y espera con tiempo límite. */
    fun start(why: String = "botón Iniciar") {
        if (_state.value.phase == AcePhase.STARTING) return
        prefs.edit().putLong(KEY_STARTED, System.currentTimeMillis()).putString(KEY_STARTED + "_why", why).apply()
        startJob?.cancel()
        set(AceStatus(AcePhase.STARTING), "starting service")
        startJob = scope.launch {
            val result = withTimeoutOrNull(AceProtocol.START_TIMEOUT_MS) { startAndWait() }
                ?: AceStatus(AcePhase.ERROR, error = AceError.TIMEOUT)
            set(result, "start result")
        }
    }

    /**
     * Para la reproducción (uso futuro): READY → continuar; STOPPED/ERROR → iniciar y esperar.
     * Devuelve el estado final (READY o el error a mostrar).
     */
    suspend fun ensureReady(): AceStatus {
        val now = refresh()
        if (now.phase == AcePhase.READY || now.phase == AcePhase.NOT_INSTALLED) return now
        start()
        return state.first { it.phase != AcePhase.STARTING && it.phase != AcePhase.CHECKING }
    }

    /** Deja de estar enlazado al servicio (al pasar a segundo plano). NO apaga el motor. */
    fun release() {
        runCatching { client?.unbind() }
        client = null
    }

    // ------------------------------------------------------------------ interno

    private suspend fun startAndWait(): AceStatus {
        val connected = kotlinx.coroutines.CompletableDeferred<Int?>()   // puerto HTTP, o null si falló
        val callback = object : ServiceClient.Callback {
            override fun onConnected(service: IAceStreamEngine) {
                val port = client?.httpApiPort?.takeIf { it > 0 } ?: AceProtocol.DEFAULT_HTTP_PORT
                Log.i(TAG, "AceStream: service ready httpPort=$port")
                connected.complete(port)
            }
            override fun onFailed() { Log.i(TAG, "AceStream: service reported failure"); connected.complete(null) }
            override fun onDisconnected() { Log.i(TAG, "AceStream: service disconnected") }
            override fun onUnpacking() { Log.i(TAG, "AceStream: unpacking") }
            override fun onStarting() { Log.i(TAG, "AceStream: engine starting") }
            override fun onStopped() {
                Log.i(TAG, "AceStream: engine stopped")
                if (_state.value.phase == AcePhase.READY) set(AceStatus(AcePhase.STOPPED, lastPort), "onStopped")
            }
            override fun onPlaylistUpdated() = Unit
            override fun onEPGUpdated() = Unit
            override fun onRestartPlayer() = Unit
            override fun onSettingsUpdated() = Unit
            override fun onAuthUpdated() = Unit
        }
        val c = ServiceClient("MiVisor", app, callback, true)
        release()
        client = c
        try {
            c.startEngine()
        } catch (e: ServiceClient.ServiceMissingException) {
            return AceStatus(AcePhase.NOT_INSTALLED)
        } catch (e: Exception) {
            Log.w(TAG, "AceStream: start failed (${e.javaClass.simpleName})")
            return AceStatus(AcePhase.ERROR, error = AceError.START_FAILED)
        }
        val port = connected.await() ?: return AceStatus(AcePhase.ERROR, error = AceError.START_FAILED)
        lastPort = port
        // Evidencia real: la HTTP API del puerto que ha dado el servicio tiene que responder.
        return AceProtocol.awaitReady(port, AceProtocol.START_TIMEOUT_MS, ::probe).also {
            if (it.phase == AcePhase.READY) Log.i(TAG, "AceStream: engine ready httpPort=$port version=${it.version}")
        }
    }

    private suspend fun probe(port: Int): String? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(AceProtocol.versionUrl(port)).openConnection() as HttpURLConnection).apply {
                connectTimeout = 1_500
                readTimeout = 2_000
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != 200) return@withContext null
            AceProtocol.parseVersion(conn.inputStream.bufferedReader().use { it.readText().take(16_384) })
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun set(status: AceStatus, why: String) {
        if (_state.value != status) Log.i(TAG, "AceStream: $why -> ${status.phase}${status.error?.let { " ($it)" }.orEmpty()}")
        _state.value = status
    }

    private companion object {
        const val TAG = "MiVisor/Services"
        const val KEY_PORT = "ace_http_port"
        const val KEY_STARTED = "ace_started_by_mivisor"
    }
}
