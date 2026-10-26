package com.mivisor.app.engine

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.ui.AspectRatioFrameLayout
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
 *  - Retraso respecto al directo estable ([LiveDelayController]): los cortes no se acumulan. Por defecto la entrega
 *    es HLS (segmentos del motor cortados en keyframe): el retraso se mide con la ventana de la lista y un exceso
 *    grande se corrige saltando a "borde − objetivo" (un segmento que empieza en keyframe; las discontinuidades
 *    llegan marcadas). En TS continuo el retraso se estima y el salto es una reconexión (el motor entrega desde su
 *    posición actual, cerca del borde, con keyframe gate). El propio motor también salta si su cursor se aleja del
 *    borde del enjambre.
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
        val sourceId: String? = null,
        /** Todas las fuentes del canal con su estado, para el menú de fuentes. */
        val sources: List<SourceRow> = emptyList(),
        /** Pistas de audio y subtítulos de la fuente actual (ajustes del reproductor). */
        val tracks: List<TrackOption> = emptyList(),
        val paused: Boolean = false,
        /** Segunda pasada: resolviendo de nuevo desde cero las fuentes que fallaron. */
        val recovering: Boolean = false
    ) {
        val active: Boolean get() = phase == Phase.STARTING_ENGINE || phase == Phase.TRYING || phase == Phase.PLAYING
    }

    /** Una fila del menú de fuentes ("2 · FHD · ProveedorX · Sin peers"). Sin Content IDs. */
    data class SourceRow(
        val index: Int,
        val label: String,
        val provider: String?,
        val quality: String?,
        val status: SourceStatus,
        val reason: String?
    )

    /** Una pista elegible: audio ([C.TRACK_TYPE_AUDIO]) o subtítulos ([C.TRACK_TYPE_TEXT]). */
    data class TrackOption(val type: Int, val group: Int, val track: Int, val label: String, val selected: Boolean)

    /** Cómo se ajusta la imagen a la pantalla (se recuerda mientras la app está abierta). */
    enum class Fit(val label: String, val mode: Int) {
        FIT("Ajustar", AspectRatioFrameLayout.RESIZE_MODE_FIT),
        ZOOM("Rellenar (recorta)", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
        FILL("Estirar", AspectRatioFrameLayout.RESIZE_MODE_FILL)
    }

    private val _fit = MutableStateFlow(Fit.FIT)
    val fit: StateFlow<Fit> = _fit.asStateFlow()
    fun setFit(f: Fit) { _fit.value = f }

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
    private var health: SourceHealth? = null
    private var appContext: Context? = null
    private var resolvedReported = -1
    private val liveDelay = LiveDelayController()
    private val lagEstimator = LagEstimator()
    private var transport = com.mivisor.app.player.EngineTransport.HLS
    /** Salto en curso (seek HLS o reconexión TS): su "cargando" no es un corte de la fuente. */
    private var resyncing = false
    private var resyncStartedAt = 0L
    private var lastEngineBufferMs = 0L
    private var currentItem: MediaItem? = null

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
                health?.onSuccess(state.source.contentId)          // beneficia a TODOS los canales con ese Content ID
            }
            _ui.value = _ui.value.copy(phase = Phase.PLAYING, message = null, failure = null, sources = rows(sel))
            startLagEstimateIfNeeded()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val sel = selector ?: return
            when (playbackState) {
                Player.STATE_BUFFERING -> if (sel.state is SourceSelector.State.Playing) {
                    liveDelay.onInterruption(android.os.SystemClock.elapsedRealtime())
                    if (!resyncing) {
                        bufferingSince = System.currentTimeMillis()
                        loadControl?.policy?.onRebuffer()
                        EngineDiagnostics.log(EngineDiagnostics.Cat.BUFFER, "Corte de buffer: objetivo ${loadControl?.policy?.targetMs} ms", important = true)
                    }
                }
                Player.STATE_READY -> {
                    bufferingSince = 0
                    if (resyncing) {
                        resyncing = false
                        EngineDiagnostics.log(EngineDiagnostics.Cat.BUFFER, "Salto al directo completado en ${System.currentTimeMillis() - resyncStartedAt} ms", important = true)
                    }
                    startLagEstimateIfNeeded()
                }
                Player.STATE_ENDED -> currentGeneration()?.let { fail(it, FailureKind.DROPPED, "El directo terminó") }
                else -> Unit
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            _ui.value = _ui.value.copy(tracks = trackOptions(tracks))
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val p = player ?: return
            _ui.value = _ui.value.copy(paused = !isPlaying && !p.playWhenReady)
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
        val h = EngineManager.health(app).also { health = it }
        appContext = app
        transport = com.mivisor.app.player.PlayerPreferences(app).engineTransport
        liveDelay.reset(android.os.SystemClock.elapsedRealtime())
        EngineDiagnostics.update { it.copy(transport = if (transport == com.mivisor.app.player.EngineTransport.HLS) "HLS" else "TS continuo",
            liveResyncs = 0, liveDiscardedMs = 0, liveSpeedUps = 0, liveSpeedUpsDone = 0, liveDelayMs = null, liveSpeeding = false) }
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
            val (good, availability) = withContext(Dispatchers.IO) {
                mem.rememberedGood(channelKey) to candidates.associate { it.contentId to h.availability(it.contentId) }
            }
            val sel = SourceSelector(candidates, good, preferred, emptySet(), explicit, availability)
            selector = sel
            ensurePlayer(app)
            loadControl?.policy?.reset()
            val excluded = sel.order.size - sel.automaticCount
            EngineDiagnostics.log(EngineDiagnostics.Cat.SOURCE, "Canal \"$channelName\": ${sel.order.size} fuente(s)" +
                (if (excluded > 0) " · $excluded en cuarentena (no se prueban solas)" else "") +
                (good?.let { " · última buena ${mask(it)}" } ?: ""), important = true)
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

    /**
     * Menú de fuentes: ver otra fuente concreta (la siguiente, una anterior o reintentar una que falló). Se cierra la
     * sesión de la actual en el motor antes de abrir la elegida: nunca dos a la vez.
     */
    fun selectSource(index: Int) {
        val sel = selector ?: return
        if (index !in sel.order.indices || sel.currentIndex == index) return
        val old = sel.currentIndex?.let { sel.order[it] }
        monitor?.cancel()
        stopEngineSession(old?.contentId)
        player?.stop()
        player?.clearMediaItems()
        EngineDiagnostics.log(EngineDiagnostics.Cat.SOURCE, "Fuente elegida en el menú: ${index + 1}/${sel.order.size}", important = true)
        attempt(sel.choose(index))
    }

    /** Pausa / reanuda. Al reanudar se vuelve al directo (lo más reciente que tenga el motor). */
    fun togglePause() {
        val p = player ?: return
        if (p.playWhenReady) {
            p.pause()
        } else {
            // HLS: volver a "borde − objetivo" (salto limpio). TS: el control de retraso reconecta si hace falta.
            if (transport == com.mivisor.app.player.EngineTransport.HLS) {
                resyncing = true
                resyncStartedAt = System.currentTimeMillis()
                runCatching { p.seekToDefaultPosition() }
            }
            liveDelay.onInterruption(android.os.SystemClock.elapsedRealtime())
            p.play()
        }
        _ui.value = _ui.value.copy(paused = !p.playWhenReady)
    }

    /** Elige una pista de audio o de subtítulos. */
    fun selectTrack(option: TrackOption) {
        val p = player ?: return
        val group = p.currentTracks.groups.getOrNull(option.group) ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(option.type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.track))
            .build()
    }

    /** Subtítulos desactivados. */
    fun disableSubtitles() {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
    }

    private fun trackOptions(tracks: Tracks): List<TrackOption> {
        val out = ArrayList<TrackOption>()
        tracks.groups.forEachIndexed { gi, g ->
            if (g.type != C.TRACK_TYPE_AUDIO && g.type != C.TRACK_TYPE_TEXT) return@forEachIndexed
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                val number = out.count { it.type == g.type } + 1
                val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }?.let { code ->
                    runCatching { java.util.Locale(code).getDisplayLanguage(java.util.Locale("es")) }.getOrNull()?.takeIf { it.isNotBlank() } ?: code
                }
                val extra = if (g.type == C.TRACK_TYPE_AUDIO && f.channelCount > 2) " · ${f.channelCount} canales" else ""
                val name = listOfNotNull(f.label?.takeIf { it.isNotBlank() }, lang).distinct().joinToString(" · ").ifBlank {
                    if (g.type == C.TRACK_TYPE_AUDIO) "Audio $number" else "Subtítulos $number"
                }
                out += TrackOption(g.type, gi, i, name + extra, g.isTrackSelected(i))
            }
        }
        return out
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
        // El motor puede tardar en tener el primer segmento (resolver + peers): lectura larga; conexión corta (es local).
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(5_000)
            .setReadTimeoutMs(35_000)
            .setAllowCrossProtocolRedirects(false)
        return ExoPlayer.Builder(context)
            .setLoadControl(lc)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build().also {
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
                    sourceId = state.source.contentId, sources = rows(sel), tracks = emptyList(), paused = false,
                    recovering = sel.recovering
                )
                resolvedReported = -1
                EngineDiagnostics.update { it.copy(sourceIndex = state.index + 1, sourceCount = sel.order.size, sourceId = mask(state.source.contentId), sourceSwitches = sel.switches) }
                EngineDiagnostics.log(EngineDiagnostics.Cat.SOURCE, "Probando fuente ${state.index + 1}/${sel.order.size} (${state.source.qualityLabel ?: "resolución ?"}): ${mask(state.source.contentId)}" + if (preview) " · vista previa" else "", important = true)
                val p = player ?: return
                p.stop()
                p.clearMediaItems()
                val item = mediaItemFor(base, state.source.contentId)
                currentItem = item
                resyncing = false
                lagEstimator.reset()
                liveDelay.reset(android.os.SystemClock.elapsedRealtime(), keepStats = true)
                p.setPlaybackSpeed(1f)
                p.setMediaItem(item)
                p.volume = if (preview) 0f else 1f
                p.prepare()
                p.play()
                startMonitor(state)
            }
            is SourceSelector.State.Exhausted -> {
                current = null
                monitor?.cancel()
                if (state.recoverable && !preview) {
                    startRecovery(sel)
                    return
                }
                val kind = state.lastFailure
                val message = when {
                    sel.automaticCount == 0 -> "Todas las fuentes de este canal están desactivadas temporalmente (fallaron varios días). Puedes probarlas a mano en «Fuentes»."
                    else -> "No hay fuente disponible: ninguna de las ${sel.automaticCount} llegó a vídeo" +
                        (if (sel.recovering) " (ni tras resolverlas de nuevo)" else "") + (kind?.let { ". Último motivo: ${it.label}" } ?: "")
                }
                _ui.value = _ui.value.copy(phase = Phase.FAILED, failure = kind, canNext = false, sources = rows(sel), sourceId = null,
                    recovering = false, message = message)
                EngineDiagnostics.log(EngineDiagnostics.Cat.SOURCE, "Fin del ciclo: sin fuente disponible (${kind?.label ?: "—"})", important = true)
                recordConfirmedFailures(sel)
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
                    if (!sawStatus && resolvedReported != attempt.generation) {
                        resolvedReported = attempt.generation
                        val fresh = selector?.recovering == true
                        withContext(Dispatchers.IO) { health?.onResolved(attempt.source.contentId, fresh) }
                    }
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
                        status?.let { lastEngineBufferMs = it.engineBufferMs }
                        controlLiveDelay(status)
                        if (resyncing && System.currentTimeMillis() - resyncStartedAt > STALL_MS) {
                            resyncing = false
                            fail(attempt.generation, FailureKind.DROPPED, "El salto al directo no volvió a dar imagen")
                            return@launch
                        }
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
        val firstPass = !sel.recovering
        val app = appContext
        if (!preview && firstPass && kind in HealthPolicy.SOURCE_FAULTS) scope.launch(Dispatchers.IO) {
            // Primera pasada sin vídeo → SUSPECT (no suma fallo). Sin Internet no se culpa a la fuente.
            if (app == null || EngineManager.isOnline(app)) health?.onSuspect(active.contentId, kind)
        }
        stopEngineSession(active.contentId)
        player?.stop()
        player?.clearMediaItems()
        val next = sel.onFailure(gen, kind)
        scope.launch {
            delay(300)                                                  // deja cerrar la sesión anterior
            if (selector === sel && sel.state === next) attempt(next)     // salvo que se haya elegido otra en el menú
        }
    }

    /**
     * Segunda pasada: ninguna fuente dio vídeo con lo que el motor ya sabía. Se fuerza una resolución desde cero
     * (reinicio de nuestro motor: su caché de resoluciones es interna) y se prueba UNA vez cada fuente que falló por
     * causa propia. Después, fin del ciclo (sin bucles).
     */
    private fun startRecovery(sel: SourceSelector) {
        val app = appContext ?: return
        val suspects = sel.suspectIds()
        _ui.value = _ui.value.copy(phase = Phase.TRYING, recovering = true, sources = rows(sel), sourceId = null, peers = 0,
            message = "Ninguna dio imagen. Segunda pasada: resolviendo de nuevo ${suspects.size} fuente(s) desde cero…")
        EngineDiagnostics.log(EngineDiagnostics.Cat.SOURCE, "Segunda pasada con ${suspects.size} fuente(s) sospechosa(s)", important = true)
        job?.cancel()
        job = scope.launch {
            val engine = EngineManager.restartForFreshResolution(app)
            if (selector !== sel) return@launch
            val base = engine.baseUrl
            if (base == null) {
                // El motor no volvió: no es culpa de las fuentes, no se registra nada contra ellas.
                _ui.value = _ui.value.copy(phase = Phase.FAILED, failure = FailureKind.ENGINE_DOWN, recovering = false,
                    message = engine.message ?: "El motor P2P no volvió a arrancar")
                if (!preview) onExhausted?.invoke(FailureKind.ENGINE_DOWN)
                return@launch
            }
            baseUrl = base
            withContext(Dispatchers.IO) { suspects.keys.forEach { health?.onFreshResolutionAttempt(it) } }
            if (selector === sel) attempt(sel.startRecovery())
        }
    }

    /**
     * Fin de ciclo: los Content IDs que fallaron en las DOS pasadas suman un fallo confirmado (como mucho uno por
     * ventana; ver [HealthPolicy]). No se cuenta nada si no hay Internet o si parece un fallo global del resolvedor.
     */
    private fun recordConfirmedFailures(sel: SourceSelector) {
        if (preview) return
        val confirmed = sel.confirmedFailures()
        if (confirmed.isEmpty()) return
        val h = health ?: return
        val app = appContext
        val ids = sel.order.mapNotNull { AceStreamLink.normalizedId(it.contentId) }.toSet()
        scope.launch(Dispatchers.IO) {
            when {
                app != null && !EngineManager.isOnline(app) ->
                    EngineDiagnostics.log(EngineDiagnostics.Cat.SOURCE, "Sin Internet: no se cuenta como fallo de las fuentes", important = true)
                h.looksLikeGlobalOutage(exclude = ids) ->
                    EngineDiagnostics.log(EngineDiagnostics.Cat.RESOLUTION, "Parece un fallo general de resolución (otros canales también fallan): no se penaliza a estas fuentes", important = true)
                else -> confirmed.forEach { (id, kind) ->
                    val outcome = h.onConfirmedFailure(id, kind)
                    val text = when (outcome) {
                        SourceHealth.Outcome.COUNTED -> "fallo confirmado (${h.get(id)?.confirmedFailures ?: 0}/${HealthPolicy.QUARANTINE_AFTER_FAILURES})"
                        SourceHealth.Outcome.RATE_LIMITED -> "ya tenía un fallo confirmado reciente: no suma"
                        SourceHealth.Outcome.QUARANTINED -> "EN CUARENTENA ${HealthPolicy.QUARANTINE_DAYS} días (sigue guardada)"
                        SourceHealth.Outcome.IGNORED -> "no se cuenta"
                    }
                    EngineDiagnostics.log(EngineDiagnostics.Cat.SOURCE, "Fuente ${mask(id)}: $text", important = true)
                }
            }
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

    /** MediaItem de la fuente según la entrega: HLS en directo (con objetivo de retraso) o TS continuo. */
    private fun mediaItemFor(base: String, contentId: String): MediaItem {
        val b = MediaItem.Builder().setMediaId(EngineApi.key(contentId))
        return if (transport == com.mivisor.app.player.EngineTransport.HLS) {
            val target = loadControl?.policy?.targetMs ?: 6_000L
            b.setUri(Uri.parse(EngineApi.hlsUrl(base, contentId)))
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                // Arranque cerca del objetivo; la velocidad la gestiona LiveDelayController (no la de Media3).
                .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(target).setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build())
                .build()
        } else {
            b.setUri(Uri.parse(EngineApi.streamUrl(base, contentId))).build()
        }
    }

    /** TS continuo: la estimación de retraso arranca en el primer instante estable con imagen. */
    private fun startLagEstimateIfNeeded() {
        if (transport != com.mivisor.app.player.EngineTransport.TS || lagEstimator.started) return
        val p = player ?: return
        if (selector?.state !is SourceSelector.State.Playing || p.playbackState != Player.STATE_READY) return
        val buffered = (p.bufferedPosition - p.currentPosition).coerceAtLeast(0)
        lagEstimator.start(android.os.SystemClock.elapsedRealtime(), p.currentPosition, lastEngineBufferMs + buffered)
    }

    /** Retraso respecto al directo: medido (HLS: ventana de la lista − posición) o estimado (TS). */
    private fun measureLiveDelay(p: ExoPlayer): Long? = if (transport == com.mivisor.app.player.EngineTransport.HLS) {
        val duration = p.duration
        if (p.isCurrentMediaItemLive && duration != C.TIME_UNSET && duration > 0) (duration - p.currentPosition).coerceAtLeast(0) else null
    } else {
        lagEstimator.estimate(android.os.SystemClock.elapsedRealtime(), p.currentPosition)
    }

    /** Cada segundo mientras se ve: mide el retraso y aplica la corrección (nada / acelerar / salto limpio). */
    private fun controlLiveDelay(status: EngineStatus?) {
        val p = player ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        val target = loadControl?.policy?.targetMs ?: 6_000L
        val playing = p.playbackState == Player.STATE_READY && p.playWhenReady && !resyncing
        val delay = if (resyncing) null else measureLiveDelay(p)
        when (val action = liveDelay.onTick(now, delay, target, playing)) {
            is LiveDelayController.Action.SetSpeed -> {
                p.setPlaybackSpeed(action.speed)
                EngineDiagnostics.log(EngineDiagnostics.Cat.BUFFER,
                    if (action.speed > 1f) "Retraso ${delay ?: "?"} ms (objetivo $target): recuperación suave a ${action.speed}x" else "Retraso recuperado: vuelta a 1.0x")
            }
            is LiveDelayController.Action.Resync -> resyncToLive(p, action, delay)
            LiveDelayController.Action.None -> Unit
        }
        val st = liveDelay.stats
        EngineDiagnostics.update {
            it.copy(liveTargetMs = target, liveDelayMs = st.delayMs, liveDelayEstimated = transport == com.mivisor.app.player.EngineTransport.TS,
                liveResyncs = st.resyncs, liveDiscardedMs = st.discardedMs, liveSpeedUps = st.speedUps,
                liveSpeedUpsDone = st.speedUpsCompleted, liveSpeeding = st.speeding,
                enginePiecesBehind = status?.piecesBehindLive ?: it.enginePiecesBehind)
        }
    }

    /**
     * Salto limpio al directo: se descarta el atraso y se reanuda en un punto decodificable.
     *  - HLS: posición = fin de la ventana − objetivo. Media3 empieza a descodificar desde el inicio del segmento
     *    (que el motor corta en keyframe), así que no hay macrobloques; no es un "seek +N" sobre un TS no seekable.
     *  - TS continuo: nueva conexión a la misma sesión del motor (no se cierra): el motor entrega desde su posición
     *    actual, cerca del borde, y con keyframe gate por cliente.
     */
    private fun resyncToLive(p: ExoPlayer, action: LiveDelayController.Action.Resync, delay: Long?) {
        resyncing = true
        resyncStartedAt = System.currentTimeMillis()
        p.setPlaybackSpeed(1f)
        EngineDiagnostics.log(EngineDiagnostics.Cat.BUFFER,
            "Retraso ${delay ?: "?"} ms (objetivo ${action.targetDelayMs}): salto al directo, se descartan ~${action.discardMs / 1000.0} s", important = true)
        if (transport == com.mivisor.app.player.EngineTransport.HLS) {
            val duration = p.duration
            p.setSeekParameters(SeekParameters.CLOSEST_SYNC)
            p.seekTo((duration - action.targetDelayMs).coerceAtLeast(0))
        } else {
            val item = currentItem ?: return
            lagEstimator.reset()
            p.stop()
            p.setMediaItem(item)
            p.prepare()
            p.play()
        }
    }

    private fun rows(sel: SourceSelector): List<SourceRow> = sel.order.mapIndexed { i, c ->
        SourceRow(i, c.label, c.provider, c.qualityLabel, sel.statusOf(i), sel.failureOf(c.contentId)?.short)
    }

    private const val STALL_MS = 20_000L
}
