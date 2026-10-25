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
/** Envío de "Editar con móvil": título y código tal cual llegan (sin guardar nada). */
data class FieldEdit(val title: String, val code: String, val seq: Int)

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

    private val _edits = MutableStateFlow<FieldEdit?>(null)
    /** "Editar con móvil": último envío recibido (seq crece en cada envío de la sesión). */
    val edits: StateFlow<FieldEdit?> = _edits.asStateFlow()

    private var server: MobileInputServer? = null
    private var session: RemoteSession? = null
    private var expiryJob: Job? = null

    /** "Usar móvil": nueva sesión de un solo uso para rellenar el campo. */
    fun startInput() = start(RemoteSession.Mode.INPUT, null)

    /** Mostrar un texto en el móvil (registro de errores), de solo lectura. */
    fun startShow(text: String) = start(RemoteSession.Mode.SHOW, text)

    /**
     * "Editar con móvil": sesión de 5 min que admite varios envíos de título + código.
     * La página del móvil se abre con los valores actuales del formulario ([updateFields] los mantiene al día).
     */
    fun startEdit(heading: String, title: String, code: String) {
        _edits.value = null
        start(RemoteSession.Mode.EDIT, null)
        session?.let { it.heading = heading; it.title = title; it.code = code }
    }

    /** El formulario de la TV ha cambiado: quien abra (o recargue) la página verá estos valores. */
    fun updateFields(title: String, code: String) {
        session?.takeIf { it.mode == RemoteSession.Mode.EDIT }?.let { it.title = title; it.code = code }
    }

    private fun start(mode: RemoteSession.Mode, shown: String?) {
        stop()
        val lan = lanAddress()
        if (lan == null) {
            log("Remote input: no LAN address")
            _state.value = RemoteState.Failed(RemoteState.Reason.NO_LAN)
            return
        }
        var owner: MobileInputServer? = null
        var seq = 0
        val srv = MobileInputServer(InetAddress.getByName(lan.address), clock, onFields = { title, code ->
            seq++
            log("Remote edit received")                          // nunca el contenido
            _edits.value = FieldEdit(title, code, seq)            // la sesión sigue abierta: se puede volver a enviar
        }) { text ->
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
        this.session = session
        val path = when (mode) { RemoteSession.Mode.INPUT -> "pair"; RemoteSession.Mode.SHOW -> "log"; RemoteSession.Mode.EDIT -> "edit" }
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
        _edits.value = null
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
        session = null
    }
}
