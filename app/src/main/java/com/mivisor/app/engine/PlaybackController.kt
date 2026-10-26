package com.mivisor.app.engine

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import com.mivisor.app.data.AceStreamLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.ConnectException

/**
 * Reproducción con el motor interno: Content ID → motor P2P → HTTP localhost → Media3.
 *
 *  - UN reproductor (ExoPlayer) para toda la app y UNA sola fuente activa: antes de probar otra se para el
 *    reproductor y se cierra la sesión en el motor (DELETE). Nada en paralelo.
 *  - Las fuentes de un canal se prueban en orden ([SourceSelector]). Gana la primera que pinta el primer fotograma.
 *  - Si la que se está viendo se cae (error o atasco largo), se sigue con las que quedan.
 *  - Fallo temprano con causa (resolución, sin peers, motor caído…) en lugar de esperar a ciegas.
 *  - Cambio rápido de canal: el intento anterior se cancela (generación + job).
 *  - Buffer pequeño y adaptativo ([AdaptiveLoadControl]).
 *  - Vista previa (Canales): la misma sesión, SILENCIADA. Al pulsar OK sobre ese canal se [promote] (se quita el
 *    silencio y pasa a pantalla completa) sin reiniciar nada. La vista previa nunca abre Ace Stream externo.
 * Se usa siempre desde el hilo principal.
 */
@OptIn(UnstableApi::class)
object PlaybackController {

    enum class Phase { IDLE, STARTING_ENGINE, TRYING, PLAYING, FAILED }

    data class Ui(
        val phase: Phase = Phase.IDLE,
        val channel: String? = null,
        val sourceIndex: Int = 0,
        val sourceCount: Int = 0,
        val sourceLabel: String? = null,
        val message: String? = null,
        val failure: FailureKind? = null,
        val peers: Int = 0,
        val canNext: Boolean = false,
        /** Vista previa silenciada (Canales). */
        val preview: Boolean = false,
        /** Canal de la sesión (para reutilizarla al pulsar OK). */
        val channelKey: String = "",
        /** Resolución de la fuente actual ("FHD"), si se conoce. */
        val quality: String? = null,
        /** Content ID de la fuente actual: solo para comparar en memoria; nunca se muestra ni se registra. */
        val sourceId: String? = null
    ) {
        val active: Boolean get() = phase == Phase.STARTING_ENGINE || phase == Phase.TRYING || phase == Phase.PLAYING
    }

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    var player: ExoPlayer? = null
        private set
    private var loadControl: AdaptiveLoadControl? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var monitor: Job? = null
    private var selector: SourceSelector? = null
    private var memory: SourceMemory? = null
    private var baseUrl: String? = null
    private var channelKey: String = ""
    private var current: SourceSelector.State.Trying? = null
    private var attemptStartedAt = 0L
    private var playingSince = 0L
    private var bufferingSince = 0L
    private var onExhausted: ((FailureKind?) -> Unit)? = null
    private var lastCandidates: List<SourceCandidate> = emptyList()
    private var lastSkippedGaps = 0L
    private var preview = false
    private var explicit = false

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            val attempt = current ?: return
            val sel = selector ?: return
            if (sel.state != attempt) return
            val elapsed = System.currentTimeMillis() - attemptStartedAt
            val state = sel.onVideo(attempt.generation) as? SourceSelector.State.Playing ?: return
            playingSince = System.currentTimeMillis()
            EngineDiagnostics.log(EngineDiagnostics.Cat.SOURCE, "Vídeo OK con la fuente ${state.index + 1}/${sel.order.size} (${mask(state.source.contentId)}) en $elapsed ms", important = true)
            EngineDiagnostics.update { it.copy(firstFrameMs = elapsed) }
            val base = baseUrl
            scope.launch(Dispatchers.IO) {
                val status = base?.let { EngineApi.status(it, state.source.contentId) }
                memory?.onSuccess(channelKey, state.source.contentId, status)
            }
            _ui.value = _ui.value.copy(phase = Phase.PLAYING, message = null, failure = null)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val sel = selector ?: return
            when (playbackState) {
                Player.STATE_BUFFERING -> if (sel.state is SourceSelector.State.Playing) {
                    bufferingSince = System.currentTimeMillis()
                    loadControl?.policy?.onRebuffer()
                    EngineDiagnostics.log(EngineDiagnostics.Cat.BUFFER, "Corte de buffer: objetivo ${loadControl?.policy?.targetMs} ms", important = true)
                }
                Player.STATE_READY -> bufferingSince = 0
                Player.STATE_ENDED -> currentGeneration()?.let { fail(it, FailureKind.DROPPED, "El directo terminó") }
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val gen = currentGeneration() ?: return
            val (kind, detail) = classify(error)
            fail(gen, kind, detail)
        }
    }

    /** Empieza un canal: arranca/reutiliza el motor y prueba sus fuentes. [onExhausted]: ninguna dio vídeo. */
    fun play(
        context: Context,
        channelName: String,
        channelKey: String,
        candidates: List<SourceCandidate>,
        preferred: String?,
        explicit: Boolean = false,
        preview: Boolean = false,
        onExhausted: (FailureKind?) -> Unit
    ) {
        cancelAttempt()
        this.preview = preview
        this.explicit = explicit
        val app = context.applicationContext
        this.channelKey = channelKey
        this.onExhausted = onExhausted
        this.lastCandidates = candidates
        val mem = memory ?: SourceMemory(File(app.filesDir, "engine/sources.json")).also { memory = it }
        _ui.value = Ui(Phase.STARTING_ENGINE, channelName, 0, candidates.size, message = "Preparando el motor P2P…", preview = preview, channelKey = channelKey)
        EngineDiagnostics.update { it.copy(channel = channelName, sourceCount = candidates.size, sourceSwitches = 0, rebuffers = 0, firstFrameMs = null) }
        job = scope.launch {
            val engine = EngineManager.ensureStarted(app)
            val base = engine.baseUrl
            if (base == null) {
                _ui.value = _ui.value.copy(phase = Phase.FAILED, failure = FailureKind.ENGINE_DOWN, message = engine.message ?: "El motor P2P no arrancó")
                EngineDiagnostics.log(EngineDiagnostics.Cat.PROCESS, "No se pudo usar el motor: ${engine.message}", important = true)
                if (!preview) onExhausted(FailureKind.ENGINE_DOWN)
                return@launch
            }
            baseUrl = base
            val (good, failed) = withContext(Dispatchers.IO) {
                mem.rememberedGood(channelKey) to mem.recentlyFailed(candidates.map { it.contentId })
            }
            val sel = SourceSelector(candidates, good, preferred, failed, explicit)
            selector = sel
            ensurePlayer(app)
            loadControl?.policy?.reset()
            EngineDiagnostics.log(EngineDiagnostics.Cat.SOURCE, "Canal \"$channelName\": ${sel.order.size} fuente(s)" + (good?.let { " · primero la última buena ${mask(it)}" } ?: ""), important = true)
            attempt(sel.start())
        }
    }

    /** ¿Hay una sesión en marcha (vista previa o no) de este canal? */
    fun isActiveSession(channelKey: String): Boolean = _ui.value.active && _ui.value.channelKey == channelKey

    /** ¿Se puede reutilizar la sesión actual para ver [channelKey] (y, si se eligió una fuente, que sea esa)? */
    fun canReuse(channelKey: String, explicitSource: String?): Boolean =
        isActiveSession(channelKey) && (explicitSource == null || _ui.value.sourceId.equals(explicitSource, ignoreCase = true))

    /**
     * La vista previa pasa a ser LA reproducción: se quita el silencio y se recupera la alternativa externa del modo
     * Automático. No se reinicia el motor ni el reproductor: misma sesión, misma fuente.
     */
    fun promote(channelName: String, onExhausted: (FailureKind?) -> Unit) {
        preview = false
        this.onExhausted = onExhausted
        player?.volume = 1f
        _ui.value = _ui.value.copy(preview = false, channel = channelName)
        EngineDiagnostics.log(EngineDiagnostics.Cat.SOURCE, "Vista previa → pantalla completa (misma sesión)", important = true)
    }

    /** Opción avanzada: pasar a la siguiente fuente aunque la actual funcione. */
    fun nextSource() {
        val sel = selector ?: return
        val old = (sel.state as? SourceSelector.State.Trying)?.source ?: (sel.state as? SourceSelector.State.Playing)?.source
        stopEngineSession(old?.contentId)
        player?.stop()
        EngineDiagnostics.log(EngineDiagnostics.Cat.SOURCE, "Cambio manual de fuente", important = true)
        attempt(sel.manualNext())
    }

    /** Reintenta el canal desde el principio. */
    fun retry(context: Context) {
        if (lastCandidates.isEmpty()) return
        play(context, _ui.value.channel ?: "", channelKey, lastCandidates, null, explicit = false, preview = preview, onExhausted = onExhausted ?: {})
    }

    /** Para la reproducción y cierra la sesión del motor (el motor sigue vivo para el siguiente canal). */
    fun stop() {
        cancelAttempt()
        player?.stop()
        player?.clearMediaItems()
        _ui.value = Ui()
        EngineDiagnostics.update { it.copy(channel = null, sourceId = null, peers = 0, bitrateBps = 0, engineBufferMs = 0, playerBufferMs = 0) }
    }

    /** Libera el reproductor (segundo plano / poca memoria). */
    fun release() {
        stop()
        player?.removeListener(listener)
        player?.release()
        player = null
        loadControl = null
    }

    // ------------------------------------------------------------------ interno

    private fun ensurePlayer(context: Context): ExoPlayer {
        player?.let { return it }
        val lowRam = EngineManager.isLowRam(context)
        val lc = AdaptiveLoadControl(AdaptiveBuffer(lowRam), lowRam)
        loadControl = lc
        return ExoPlayer.Builder(context).setLoadControl(lc).build().also {
            it.addListener(listener)
            it.playWhenReady = true
            player = it
        }
    }

    private fun attempt(state: SourceSelector.State) {
        val sel = selector ?: return
        when (state) {
            is SourceSelector.State.Trying -> {
                current = state
                attemptStartedAt = System.currentTimeMillis()
                playingSince = 0
                bufferingSince = 0
                lastSkippedGaps = 0
                val base = baseUrl ?: return
                _ui.value = _ui.value.copy(
                    phase = Phase.TRYING, sourceIndex = state.index + 1, sourceCount = sel.order.size,
                    sourceLabel = state.source.provider ?: state.source.label, message = "Buscando peers y preparando vídeo…",
                    failure = null, canNext = state.index + 1 < sel.order.size, peers = 0, quality = state.source.qualityLabel,
                    sourceId = state.source.contentId
                )
                EngineDiagnostics.update { it.copy(sourceIndex = state.index + 1, sourceCount = sel.order.size, sourceId = mask(state.source.contentId), sourceSwitches = sel.switches) }
                EngineDiagnostics.log(EngineDiagnostics.Cat.SOURCE, "Probando fuente ${state.index + 1}/${sel.order.size} (${state.source.qualityLabel ?: "resolución ?"}): ${mask(state.source.contentId)}" + if (preview) " · vista previa" else "", important = true)
                val p = player ?: return
                p.stop()
                p.clearMediaItems()
                p.setMediaItem(MediaItem.Builder().setMediaId(EngineApi.key(state.source.contentId)).setUri(Uri.parse(EngineApi.streamUrl(base, state.source.contentId))).build())
                p.volume = if (preview) 0f else 1f
                p.prepare()
                p.play()
                startMonitor(state)
            }
            is SourceSelector.State.Exhausted -> {
                current = null
                monitor?.cancel()
                val kind = state.lastFailure
                _ui.value = _ui.value.copy(phase = Phase.FAILED, failure = kind, canNext = false,
                    message = "Ninguna de las ${sel.order.size} fuentes llegó a vídeo" + (kind?.let { ". Último motivo: ${it.label}" } ?: ""))
                EngineDiagnostics.log(EngineDiagnostics.Cat.SOURCE, "Sin fuentes válidas (${kind?.label ?: "—"})", important = true)
                if (!preview) onExhausted?.invoke(kind)
            }
            else -> Unit
        }
    }

    /** Cada segundo: estado del motor (peers, buffer, recuperaciones) y decisión de fallo temprano. */
    private fun startMonitor(attempt: SourceSelector.State.Trying) {
        monitor?.cancel()
        monitor = scope.launch {
            var sawStatus = false
            var maxPeers = 0
            var tick = 0
            while (isActive) {
                delay(1_000)
                tick++
                val base = baseUrl ?: break
                val status = withContext(Dispatchers.IO) { EngineApi.status(base, attempt.source.contentId) }
                if (status == null && sawStatus && tick > 3 && !EngineSupervisor.health(EngineManager.state.value.port)) {
                    fail(attempt.generation, FailureKind.ENGINE_DOWN, "El proceso del motor dejó de responder")
                    break
                }
                if (status != null) {
                    sawStatus = true
                    maxPeers = maxOf(maxPeers, status.peers)
                    if (status.skippedGaps > lastSkippedGaps) {
                        EngineDiagnostics.log(EngineDiagnostics.Cat.DISCONTINUITY, "El motor saltó ${status.skippedGaps - lastSkippedGaps} hueco(s); el vídeo sigue desde el siguiente keyframe", important = true)
                        lastSkippedGaps = status.skippedGaps
                    }
                }
                val p = player
                val policy = loadControl?.policy
                policy?.onTick()
                EngineDiagnostics.update {
                    it.copy(
                        peers = status?.peers ?: it.peers, bitrateBps = status?.bitrateBps ?: it.bitrateBps,
                        engineBufferMs = status?.engineBufferMs ?: 0, recoveries = status?.recoveries ?: it.recoveries,
                        skippedGaps = status?.skippedGaps ?: it.skippedGaps,
                        playerBufferMs = p?.let { pl -> (pl.bufferedPosition - pl.currentPosition).coerceAtLeast(0) } ?: 0,
                        bufferTargetMs = policy?.targetMs ?: 0, rebuffers = policy?.rebuffers ?: 0
                    )
                }
                _ui.value = _ui.value.copy(peers = status?.peers ?: _ui.value.peers)
                val sel = selector ?: break
                when (sel.state) {
                    is SourceSelector.State.Trying -> {
                        val elapsed = System.currentTimeMillis() - attemptStartedAt
                        FailurePolicy.earlyFailure(elapsed, sawStatus, maxPeers)?.let { kind ->
                            fail(attempt.generation, kind, "${elapsed / 1000} s sin vídeo · peers máx. $maxPeers")
                            return@launch
                        }
                    }
                    is SourceSelector.State.Playing -> {
                        // Atasco largo con la fuente que se veía: se considera caída y se pasa a la siguiente.
                        if (bufferingSince > 0 && System.currentTimeMillis() - bufferingSince > STALL_MS) {
                            fail(attempt.generation, FailureKind.DROPPED, "Más de ${STALL_MS / 1000} s sin datos")
                            return@launch
                        }
                    }
                    else -> break
                }
            }
        }
    }

    private fun fail(gen: Int, kind: FailureKind, detail: String) {
        val sel = selector ?: return
        val active = (sel.state as? SourceSelector.State.Trying)?.takeIf { it.generation == gen }?.source
            ?: (sel.state as? SourceSelector.State.Playing)?.takeIf { it.generation == gen }?.source
            ?: return
        monitor?.cancel()
        EngineDiagnostics.log(categoryOf(kind), "Fuente ${mask(active.contentId)} falló: ${kind.label} · $detail", important = true)
        scope.launch(Dispatchers.IO) { memory?.onFailure(active.contentId, kind) }
        stopEngineSession(active.contentId)
        player?.stop()
        player?.clearMediaItems()
        val next = sel.onFailure(gen, kind)
        scope.launch {
            delay(300)                                                  // deja cerrar la sesión anterior
            if (selector === sel) attempt(next)
        }
    }

    private fun cancelAttempt() {
        job?.cancel()
        monitor?.cancel()
        val sel = selector
        val active = (sel?.state as? SourceSelector.State.Trying)?.source ?: (sel?.state as? SourceSelector.State.Playing)?.source
        stopEngineSession(active?.contentId)
        selector = null
        current = null
    }

    private fun stopEngineSession(contentId: String?) {
        val base = baseUrl ?: return
        contentId ?: return
        scope.launch(Dispatchers.IO) { EngineApi.stop(base, contentId) }
    }

    private fun currentGeneration(): Int? = (selector?.state as? SourceSelector.State.Trying)?.generation
        ?: (selector?.state as? SourceSelector.State.Playing)?.generation

    private fun classify(error: PlaybackException): Pair<FailureKind, String> {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                return FailurePolicy.fromHttpStatus(cause.responseCode) to "HTTP ${cause.responseCode} del motor"
            }
            if (cause is ConnectException) return FailureKind.ENGINE_DOWN to "Conexión rechazada en 127.0.0.1"
            cause = cause.cause
        }
        return FailureKind.PLAYER to "${error.errorCodeName}: ${error.message.orEmpty().take(120)}"
    }

    private fun categoryOf(kind: FailureKind) = when (kind) {
        FailureKind.RESOLUTION -> EngineDiagnostics.Cat.RESOLUTION
        FailureKind.NO_PEERS -> EngineDiagnostics.Cat.PEERS
        FailureKind.TIMEOUT -> EngineDiagnostics.Cat.TIMEOUT
        FailureKind.PLAYER -> EngineDiagnostics.Cat.MEDIA3
        FailureKind.ENGINE_DOWN -> EngineDiagnostics.Cat.PROCESS
        FailureKind.PORT -> EngineDiagnostics.Cat.PORT
        FailureKind.DROPPED, FailureKind.CANCELLED -> EngineDiagnostics.Cat.SOURCE
    }

    private fun mask(id: String) = AceStreamLink.masked(id)

    private const val STALL_MS = 20_000L
}
