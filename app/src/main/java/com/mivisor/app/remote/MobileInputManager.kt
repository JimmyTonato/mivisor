package com.mivisor.app.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.InetAddress

/** Lo que muestra el formulario mientras se usa el móvil. */
sealed class RemoteState {
    object Idle : RemoteState()
    class Waiting(val url: String, val qr: QrMatrix, val expiresAt: Long, val mode: RemoteSession.Mode) : RemoteState()
    /** Texto recibido: el formulario lo pone en el campo (sin guardar) y llama a [MobileInputManager.consume]. */
    class Received(val text: String) : RemoteState()
    object Expired : RemoteState()
    class Failed(val reason: Reason) : RemoteState()

    enum class Reason { NO_LAN, SERVER }
}

/**
 * Orquesta sesión + servidor + QR. El servidor solo existe mientras hay una sesión:
 * se cierra al recibir el texto, al cancelar, al salir del formulario o al caducar.
 */
class MobileInputManager(
    private val scope: CoroutineScope,
    private val lanAddress: () -> NetCandidate?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = RemoteSession.DEFAULT_TTL_MS,
    private val ports: IntRange = MobileInputServer.DEFAULT_PORTS,
    private val log: (String) -> Unit = {}
) {
    private val _state = MutableStateFlow<RemoteState>(RemoteState.Idle)
    val state: StateFlow<RemoteState> = _state.asStateFlow()

    private var server: MobileInputServer? = null
    private var expiryJob: Job? = null

    /** "Usar móvil": nueva sesión de un solo uso para rellenar el campo. */
    fun startInput() = start(RemoteSession.Mode.INPUT, null)

    /** Mostrar un texto en el móvil (registro de errores), de solo lectura. */
    fun startShow(text: String) = start(RemoteSession.Mode.SHOW, text)

    private fun start(mode: RemoteSession.Mode, shown: String?) {
        stop()
        val lan = lanAddress()
        if (lan == null) {
            log("Remote input: no LAN address")
            _state.value = RemoteState.Failed(RemoteState.Reason.NO_LAN)
            return
        }
        var owner: MobileInputServer? = null
        val srv = MobileInputServer(InetAddress.getByName(lan.address), clock) { text ->
            log("Remote input received")                       // nunca el contenido
            _state.value = RemoteState.Received(text)
            // Un solo uso: el token ya no vale. Se apaga el servidor tras dejar que el móvil reciba el "OK"
            // (y solo si sigue siendo esta sesión: el usuario puede haber pedido otro QR).
            scope.launch {
                delay(STOP_AFTER_RECEIVE_MS)
                if (server === owner) stop()
            }
        }
        owner = srv
        val port = try {
            srv.start(ports)
        } catch (e: Exception) {
            log("Remote input: server could not start")
            _state.value = RemoteState.Failed(RemoteState.Reason.SERVER)
            return
        }
        val session = RemoteSession.create(mode, clock(), ttlMs, shown)
        srv.session = session
        server = srv
        val path = if (mode == RemoteSession.Mode.INPUT) "pair" else "log"
        val url = "http://${lan.address}:$port/$path/${session.token}"
        _state.value = RemoteState.Waiting(url, QrGenerator.encode(url), session.expiresAt, mode)
        log("Remote input: session started on ${lan.interfaceName} port $port")
        expiryJob = scope.launch {
            delay(ttlMs)
            if (_state.value is RemoteState.Waiting) {
                stop()
                _state.value = RemoteState.Expired
                log("Remote input: session expired")
            }
        }
    }

    /** El formulario ya ha usado el texto recibido. */
    fun consume() {
        if (_state.value is RemoteState.Received) _state.value = RemoteState.Idle
    }

    /** Cancelar o salir del formulario: invalida el token, cierra el servidor y no toca el campo. */
    fun cancel() {
        stop()
        _state.value = RemoteState.Idle
    }

    val isServerRunning: Boolean get() = server?.isRunning == true

    companion object {
        const val STOP_AFTER_RECEIVE_MS = 600L
    }

    private fun stop() {
        expiryJob?.cancel()
        expiryJob = null
        server?.stop()
        server = null
    }
}
