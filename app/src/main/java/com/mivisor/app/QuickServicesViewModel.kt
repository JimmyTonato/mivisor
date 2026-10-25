package com.mivisor.app

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mivisor.app.model.PlayRequest
import com.mivisor.app.player.PlayerPreferences
import com.mivisor.app.services.AcePhase
import com.mivisor.app.services.AceStatus
import com.mivisor.app.services.AceStreamEngineController
import com.mivisor.app.services.PlaybackNeeds
import com.mivisor.app.services.PlaybackPlanner
import com.mivisor.app.services.PrepDecision
import com.mivisor.app.services.PrepProblem
import com.mivisor.app.services.QuickServiceText
import com.mivisor.app.services.RowAction
import com.mivisor.app.services.ServiceRow
import com.mivisor.app.services.StepState
import com.mivisor.app.services.WarpController
import com.mivisor.app.services.WarpPhase
import com.mivisor.app.services.WarpStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class QuickServicesUi(
    val ace: ServiceRow,
    val warp: ServiceRow,
    val aceStatus: AceStatus,
    val warpStatus: WarpStatus,
    /** Estados simulados para revisar la interfaz (Ajustes → Servicios → Vista previa). */
    val preview: Boolean = false
)

/** Comprobación "¿se reproduce de verdad?" de un canal Ace Stream. */
data class ProbeUi(
    val channelName: String,
    val running: Boolean,
    val step: String,
    val stat: com.mivisor.app.services.ProbeStat? = null,
    val result: com.mivisor.app.services.ProbeResult? = null
)

/** Ayuda que se muestra en un diálogo ("Cómo instalar"…). */
enum class HelpTopic { ACE_INSTALL, WARP_INSTALL, WARP_SIGNATURE }

/** "Preparando reproducción…": un solo panel para Ace y VPN, y la pregunta solo si hace falta una persona. */
data class PrepUi(
    val request: PlayRequest,
    val ace: StepState?,
    val vpn: StepState?,
    val problem: PrepProblem? = null,
    val awaitingAuth: Boolean = false
)

/**
 * Servicios de reproducción (Ace Stream y VPN/WARP): estado real para la barra lateral, acciones,
 * y la preparación automática antes de reproducir. La UI no habla con Ace ni con WARP directamente.
 */
class QuickServicesViewModel(application: Application) : AndroidViewModel(application) {
    val ace = AceStreamEngineController(application, viewModelScope)
    val warp = WarpController(application, viewModelScope)
    private val prefs = PlayerPreferences(application)

    private val previewAce = MutableStateFlow<AceStatus?>(null)
    private val previewWarp = MutableStateFlow<WarpStatus?>(null)

    val ui: StateFlow<QuickServicesUi> = combine(ace.state, warp.state, previewAce, previewWarp) { a, w, pa, pw ->
        val shownAce = pa ?: a
        val shownWarp = pw ?: w
        QuickServicesUi(QuickServiceText.ace(shownAce), QuickServiceText.warp(shownWarp), shownAce, shownWarp, preview = pa != null || pw != null)
    }.stateIn(
        viewModelScope, SharingStarted.Eagerly,
        QuickServicesUi(QuickServiceText.ace(ace.state.value), QuickServiceText.warp(warp.state.value), ace.state.value, warp.state.value)
    )

    private val _help = MutableStateFlow<HelpTopic?>(null)
    val help: StateFlow<HelpTopic?> = _help.asStateFlow()

    private val _panel = MutableStateFlow(false)
    /** Panel rápido de servicios (al pulsar OK en una fila que está bien). */
    val panel: StateFlow<Boolean> = _panel.asStateFlow()

    private val _prep = MutableStateFlow<PrepUi?>(null)
    val prep: StateFlow<PrepUi?> = _prep.asStateFlow()

    private val _launch = MutableStateFlow<PlayRequest?>(null)
    /** Contenido listo para abrir: la UI lo lanza y llama a [consumeLaunch]. */
    val launch: StateFlow<PlayRequest?> = _launch.asStateFlow()

    private var planner: PlaybackPlanner? = null

    private val _probe = MutableStateFlow<ProbeUi?>(null)
    val probe: StateFlow<ProbeUi?> = _probe.asStateFlow()
    private var probeJob: kotlinx.coroutines.Job? = null

    /**
     * Comprueba con el motor si el stream llega de verdad (no solo si Ace Stream se abre).
     * Arranca el motor si hace falta (acción explícita del usuario) y cierra la sesión de prueba al acabar.
     */
    fun probe(channelName: String, url: String) {
        val id = com.mivisor.app.data.AceStreamLink.contentId(url) ?: run {
            _probe.value = ProbeUi(channelName, false, "No es un enlace Ace Stream válido.")
            return
        }
        probeJob?.cancel()
        _probe.value = ProbeUi(channelName, true, "Preparando el motor de Ace Stream…")
        probeJob = viewModelScope.launch {
            val engine = ace.ensureReady()
            if (engine.phase != AcePhase.READY) {
                _probe.value = ProbeUi(channelName, false, "El motor no está disponible (${QuickServiceText.ace(engine).status}).",
                    result = com.mivisor.app.services.ProbeResult(com.mivisor.app.services.ProbeVerdict.ENGINE_UNREACHABLE, null, 0, "El motor no está listo."))
                logAce(com.mivisor.app.diagnostics.AceLog.probe(channelName, "MOTOR NO DISPONIBLE", null, null, null, 0, engineText(engine)))
                return@launch
            }
            _probe.value = _probe.value?.copy(step = "Pidiendo el contenido al motor…")
            val result = com.mivisor.app.services.AceStreamProbe().run(engine.httpPort ?: 6878, id) { stat ->
                _probe.value = _probe.value?.copy(step = "Estado: ${stat.status ?: "—"}", stat = stat)
            }
            _probe.value = _probe.value?.copy(running = false, step = result.detail, result = result)
            logAce(com.mivisor.app.diagnostics.AceLog.probe(
                channelName, verdictLabel(result.verdict), result.lastStat?.peers, result.lastStat?.speedDownKbps,
                result.lastStat?.status, result.seconds, result.detail
            ))
        }
    }

    // ------------------------------------------------------------------ Registro Ace Stream

    private val aceLogDir get() = java.io.File(getApplication<Application>().filesDir, "crashes")

    fun logAce(text: String) {
        val dir = aceLogDir
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { com.mivisor.app.diagnostics.AceLog.append(dir, text) }
    }

    /** Estado real del motor (get_version por HTTP; no lo arranca). */
    fun describeEngine(onDone: (String) -> Unit) {
        viewModelScope.launch {
            val a = ace.refresh()
            onDone(engineText(a))
        }
    }

    fun engineText(a: AceStatus): String = when (a.phase) {
        AcePhase.READY -> "responde (versión ${a.version ?: "?"}, puerto ${a.httpPort ?: 6878})"
        else -> "${a.phase.name.lowercase()} (${QuickServiceText.ace(a).status})"
    }

    private fun verdictLabel(v: com.mivisor.app.services.ProbeVerdict) = when (v) {
        com.mivisor.app.services.ProbeVerdict.STREAMING -> "SE REPRODUCE"
        com.mivisor.app.services.ProbeVerdict.PREBUFFERING_ONLY -> "SOLO PREBUFFER"
        com.mivisor.app.services.ProbeVerdict.NO_DATA -> "SIN DATOS"
        com.mivisor.app.services.ProbeVerdict.ENGINE_ERROR -> "ERROR DEL MOTOR"
        com.mivisor.app.services.ProbeVerdict.ENGINE_UNREACHABLE -> "MOTOR NO RESPONDE"
    }

    fun closeProbe() {
        probeJob?.cancel()
        _probe.value = null
    }
    private var aceAutoDone = false
    private var vpnAutoDone = false

    init {
        // Cualquier cambio real de Ace o WARP hace avanzar la preparación en curso.
        viewModelScope.launch {
            combine(ace.state, warp.state) { a, w -> a to w }.collect { (a, w) -> advance(a, w) }
        }
    }

    /** onResume (abrir MiVisor, volver de la autorización VPN o de WARP): estado real, sin pulsar nada. */
    fun onForeground() {
        warp.startListening()
        planner?.onForeground()
        viewModelScope.launch {
            val w = warp.refresh()
            // "Conectar al abrir MiVisor": solo la primera vez, nunca al volver de otra app.
            if (!vpnAutoDone && prefs.vpnAutoConnect && w.phase == WarpPhase.DISCONNECTED) warp.connect()
            vpnAutoDone = true
        }
        viewModelScope.launch {
            val a = ace.refresh()
            if (!aceAutoDone && prefs.aceAutoStart && a.phase == AcePhase.STOPPED) ace.start()
            aceAutoDone = true
        }
    }

    /** onStop: sin receptores ni enlaces innecesarios. Esto NO apaga el motor ni la VPN. */
    fun onBackground() {
        warp.stopListening()
        ace.release()
    }

    fun onOk(action: RowAction) {
        when (action) {
            RowAction.NONE -> Unit
            RowAction.REFRESH -> onForeground()
            RowAction.PANEL -> _panel.value = true
            RowAction.ACE_START -> ace.start()
            RowAction.ACE_INSTALL_HELP -> _help.value = HelpTopic.ACE_INSTALL
            RowAction.WARP_CONNECT -> warp.connect()
            RowAction.WARP_DISCONNECT -> warp.disconnect()
            RowAction.WARP_AUTHORIZE -> if (!warp.requestPermission()) toast("No se pudo abrir la autorización de la VPN")
            RowAction.WARP_OPEN_APP -> if (!warp.openApp()) toast("No se pudo abrir WARP")
            RowAction.WARP_INSTALL_HELP -> _help.value = HelpTopic.WARP_INSTALL
            RowAction.WARP_SIGNATURE_HELP -> _help.value = HelpTopic.WARP_SIGNATURE
        }
    }

    fun openPanel() { _panel.value = true }
    fun closePanel() { _panel.value = false }
    fun closeHelp() { _help.value = null }

    // ------------------------------------------------------------------ vista previa (simulación)

    fun previewAce(phase: AcePhase?) {
        previewAce.value = phase?.let { AceStatus(it, 6878, if (it == AcePhase.READY) "3.1.74" else null) }
    }

    fun previewWarp(phase: WarpPhase?) {
        previewWarp.value = phase?.let { WarpStatus(it, if (it == WarpPhase.ERROR) "CONNECT_TIMEOUT" else null) }
    }

    // ------------------------------------------------------------------ preparar reproducción

    /** OK sobre un contenido. Si no necesita nada, se abre ya; si no, se prepara (y continúa solo). */
    fun play(request: PlayRequest, needs: PlaybackNeeds) {
        if (!needs.any) {
            _launch.value = request
            return
        }
        val p = PlaybackPlanner(needs)
        planner = p
        _prep.value = PrepUi(request, if (needs.ace) StepState.WORKING else null, if (needs.vpn) StepState.WORKING else null)
        advance(ace.state.value, warp.state.value)
    }

    fun cancelPrep() {
        planner = null
        _prep.value = null
    }

    fun authorizeVpn() {
        val p = planner ?: return
        p.onAuthorizeRequested()
        _prep.value = _prep.value?.copy(problem = null, awaitingAuth = true)
        if (!warp.requestPermission()) {
            toast("No se pudo abrir la autorización de la VPN")
            p.onForeground()
        }
    }

    fun retryPrep() {
        val p = planner ?: return
        val problem = _prep.value?.problem ?: return
        p.retry(problem)
        if (problem == PrepProblem.VPN_SETUP && !warp.openApp()) toast("No se pudo abrir WARP")
        _prep.value = _prep.value?.copy(problem = null)
        advance(ace.state.value, warp.state.value)
    }

    /** "Abrir igualmente": el usuario decide reproducir sin el requisito. */
    fun openAnyway() {
        val request = _prep.value?.request ?: return
        cancelPrep()
        _launch.value = request
    }

    fun consumeLaunch() { _launch.value = null }

    private fun advance(a: AceStatus, w: WarpStatus) {
        val p = planner ?: return
        val current = _prep.value ?: return
        when (val d = p.next(a, w)) {
            PrepDecision.Launch -> {
                planner = null
                _prep.value = null
                _launch.value = current.request                  // continúa con el mismo contenido
            }
            is PrepDecision.Work -> {
                if (d.startAce) ace.start()
                if (d.connectVpn) warp.connect()
                _prep.value = current.copy(ace = d.ace, vpn = d.vpn, problem = p.pendingAsk,
                    awaitingAuth = current.awaitingAuth && w.phase == WarpPhase.PERMISSION_REQUIRED)
            }
            is PrepDecision.Ask -> _prep.value = current.copy(problem = d.problem, awaitingAuth = false)
        }
    }

    override fun onCleared() {
        onBackground()
    }

    private fun toast(text: String) = Toast.makeText(getApplication(), text, Toast.LENGTH_SHORT).show()
}
