package com.mivisor.app.ui.screens.player

import android.graphics.Color as AColor
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.DisposableEffect
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.mivisor.app.R
import com.mivisor.app.engine.PlaybackController
import com.mivisor.app.engine.PlaybackController.Phase
import com.mivisor.app.engine.SourceStatus
import com.mivisor.app.ui.components.TvClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.mivisor.app.ui.neon.Spinner
import com.mivisor.app.ui.components.Txt
import com.mivisor.app.ui.kit.PrimaryButton
import com.mivisor.app.ui.kit.SecondaryButton
import com.mivisor.app.ui.kit.Ui
import com.mivisor.app.ui.neon.Neon
import com.mivisor.app.ui.neon.NeonType
import kotlinx.coroutines.delay

/**
 * Pantalla completa del motor interno. El vídeo lo pinta Media3 ([PlayerView] sin controles propios); encima,
 * un panel con el canal, la fuente que se está probando, peers y el estado. El panel se oculta solo cuando ya hay
 * vídeo; cualquier tecla del mando o TOCAR la pantalla lo vuelve a mostrar (tocar otra vez lo oculta).
 * Atrás vuelve a MiVisor y cierra la sesión del canal.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    onRetry: () -> Unit,
    /** Abrir con Ace Stream externo (null si el modo es Interno o no hay ninguna app instalada). */
    onExternal: (() -> Unit)?
) {
    val ui by PlaybackController.ui.collectAsState()
    var lastKey by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var overlay by remember { mutableStateOf(true) }
    val firstButton = remember { FocusRequester() }
    val catcher = remember { FocusRequester() }
    var menu by remember { mutableStateOf(false) }
    val menuFocus = remember { FocusRequester() }
    var settings by remember { mutableStateOf(false) }
    val settingsFocus = remember { FocusRequester() }
    val sheet = menu || settings
    val fit by PlaybackController.fit.collectAsState()

    BackHandler(enabled = true) { onBack() }
    BackHandler(enabled = sheet) { menu = false; settings = false }    // Atrás con un menú abierto: solo lo cierra

    // Pantalla siempre encendida mientras esta pantalla está abierta: sin esto la TV se oscurece (salvapantallas /
    // atenuación) al cabo de ~1-2 min sin tocar el mando aunque el vídeo se esté reproduciendo.
    val hostView = LocalView.current
    DisposableEffect(hostView) {
        hostView.keepScreenOn = true
        val window = (hostView.context as? android.app.Activity)?.window
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            hostView.keepScreenOn = false
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // El panel se oculta 5 s después de la última tecla, solo si ya hay vídeo.
    LaunchedEffect(ui.phase, lastKey, sheet) {
        overlay = true
        if (ui.phase == Phase.PLAYING && !sheet && !ui.paused) {
            delay(5_000)
            overlay = false
        }
    }
    LaunchedEffect(overlay, menu, settings) {
        runCatching {
            when {
                menu -> menuFocus.requestFocus()
                settings -> settingsFocus.requestFocus()
                overlay -> firstButton.requestFocus()
                else -> catcher.requestFocus()
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onPreviewKeyEvent { lastKey = System.currentTimeMillis(); false }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShutterBackgroundColor(AColor.BLACK)
                    setKeepContentOnPlayerReset(false)
                    keepScreenOn = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    player = PlaybackController.player
                }
            },
            onRelease = { it.player = null },
            update = { view ->
                if (view.resizeMode != fit.mode) view.resizeMode = fit.mode
                // Pantalla siempre encendida mientras se busca fuente o se reproduce (sin esto la TV se atenúa
                // a los pocos minutos sin tocar el mando). Si todo falla, se deja que la TV se apague sola.
                view.keepScreenOn = ui.phase != Phase.FAILED
                if (view.player !== PlaybackController.player) view.player = PlaybackController.player
            },
            modifier = Modifier.fillMaxSize()
        )
        // Mientras se busca una fuente con imagen: spinner grande en el centro (no una pantalla negra muda).
        if (ui.phase == Phase.IDLE || ui.phase == Phase.STARTING_ENGINE || ui.phase == Phase.TRYING) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Spinner(56.dp, Neon.Cyan)
                Txt(searchingLine(ui), NeonType.H3, color = Neon.Dim)
            }
        }
        // Pantalla táctil (móvil, tablet, TV con touch): tocar el vídeo muestra los controles; tocar otra vez con
        // el vídeo en marcha los oculta. Va encima del vídeo y debajo de los controles (los botones siguen
        // recibiendo sus toques). Sin esto el PlayerView se quedaba con el toque y no salía nada.
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(onTap = {
                    if (menu) return@detectTapGestures
                    if (overlay && ui.phase == Phase.PLAYING) overlay = false
                    else lastKey = System.currentTimeMillis()
                })
            }
        )
        // Pantalla táctil / ratón: tocar el vídeo muestra los controles (u oculta si ya están y hay vídeo).
        // Va debajo de los botones, así que tocar un botón sigue siendo pulsarlo.
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(onTap = {
                    when {
                        menu || settings -> { menu = false; settings = false }
                        overlay && ui.phase == Phase.PLAYING -> overlay = false
                        else -> lastKey = System.currentTimeMillis()
                    }
                })
            }
        )
        // Recoge las teclas cuando el panel está oculto (para volver a mostrarlo).
        Box(Modifier.size(1.dp).focusRequester(catcher).focusable())

        if (overlay || ui.phase != Phase.PLAYING) {
            Column(
                Modifier.fillMaxWidth().align(Alignment.TopStart)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)))
                    .padding(horizontal = 32.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Txt(ui.channel ?: "Canal", NeonType.H2)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (ui.phase == Phase.STARTING_ENGINE || ui.phase == Phase.TRYING) {
                        Spinner(16.dp, Neon.Cyan)
                        Box(Modifier.width(10.dp))
                    }
                    Txt(playbackStatus(ui), NeonType.Body, color = if (ui.phase == Phase.FAILED) Ui.Amber else Neon.Dim, maxLines = 2)
                }
                ui.message?.let { Txt(it, NeonType.Small, color = Neon.Faint, maxLines = 3) }
            }
            if (!sheet) Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                    .padding(horizontal = 32.dp, vertical = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                var first = true
                fun mod(): Modifier = if (first) { first = false; Modifier.focusRequester(firstButton) } else Modifier
                if (ui.sources.size > 1 || (ui.phase == Phase.FAILED && ui.sources.isNotEmpty())) {
                    val n = ui.sources.size
                    val label = if (ui.sourceIndex > 0 && ui.phase != Phase.FAILED) "Fuentes (${ui.sourceIndex} de $n)" else "Fuentes ($n)"
                    PrimaryButton(label, { menu = true }, mod(), icon = R.drawable.ic_layers)
                }
                if (ui.phase == Phase.FAILED) SecondaryButton("Reintentar todo", onRetry, mod(), icon = R.drawable.ic_refresh)
                if (ui.phase == Phase.PLAYING) {
                    SecondaryButton(if (ui.paused) "Reanudar" else "Pausa", { lastKey = System.currentTimeMillis(); PlaybackController.togglePause() }, mod(),
                        icon = if (ui.paused) R.drawable.ic_play else R.drawable.ic_clock)
                }
                SecondaryButton("Ajustes", { settings = true }, mod(), icon = R.drawable.ic_settings)
                if (onExternal != null) SecondaryButton("Abrir con Ace Stream", onExternal, mod(), icon = R.drawable.ic_bolt)
                SecondaryButton("Volver", onBack, mod(), icon = R.drawable.ic_chevron_left)
            }
        }
        if (menu) {
            SourcesMenu(
                ui = ui,
                focus = menuFocus,
                onPick = { i -> menu = false; lastKey = System.currentTimeMillis(); PlaybackController.selectSource(i) },
                onClose = { menu = false },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
        if (settings) {
            PlayerSettingsMenu(
                ui = ui,
                fit = fit,
                focus = settingsFocus,
                onClose = { settings = false; lastKey = System.currentTimeMillis() },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

/**
 * Menú de fuentes (sube desde abajo): todas las fuentes del canal, de mayor a menor resolución, con lo que ha pasado
 * con cada una. Se puede ir a cualquiera: la siguiente, una anterior o reintentar una que falló.
 */
@Composable
private fun SourcesMenu(
    ui: PlaybackController.Ui,
    focus: FocusRequester,
    onPick: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier
) {
    val current = ui.sources.firstOrNull { it.status == SourceStatus.PLAYING || it.status == SourceStatus.TRYING }?.index ?: 0
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (current - 1).coerceAtLeast(0))
    Column(
        modifier.fillMaxWidth().fillMaxHeight(0.62f)
            .pointerInput(Unit) { detectTapGestures { } }               // tocar dentro del menú no lo cierra
            .background(Neon.Panel.copy(alpha = 0.97f), RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .border(1.dp, Neon.Stroke, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .padding(horizontal = 28.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt("Fuentes de ${ui.channel ?: "este canal"}", NeonType.H2)
                Txt("Ordenadas por resolución. OK para verla · Atrás para cerrar", NeonType.Small, color = Neon.Faint)
            }
            SecondaryButton("Cerrar", onClose, icon = R.drawable.ic_close)
        }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(ui.sources, key = { it.index }) { row ->
                SourceMenuRow(row, onClick = { onPick(row.index) }, modifier = if (row.index == current) Modifier.focusRequester(focus) else Modifier)
            }
        }
    }
}

/**
 * Ajustes del reproductor (sube desde abajo): imagen (ajustar / rellenar / estirar), audio, subtítulos y pausa.
 * Pensado para el mando: todo son filas con OK, sin barras de progreso (es un directo).
 */
@Composable
private fun PlayerSettingsMenu(
    ui: PlaybackController.Ui,
    fit: PlaybackController.Fit,
    focus: FocusRequester,
    onClose: () -> Unit,
    modifier: Modifier
) {
    val audio = ui.tracks.filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
    val text = ui.tracks.filter { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
    Column(
        modifier.fillMaxWidth().fillMaxHeight(0.62f)
            .pointerInput(Unit) { detectTapGestures { } }               // tocar dentro del menú no lo cierra
            .background(Neon.Panel.copy(alpha = 0.97f), RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .border(1.dp, Neon.Stroke, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .padding(horizontal = 28.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt("Ajustes del reproductor", NeonType.H2)
                Txt("OK para elegir · Atrás para cerrar", NeonType.Small, color = Neon.Faint)
            }
            SecondaryButton("Cerrar", onClose, icon = R.drawable.ic_close)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f).fillMaxWidth()) {
            item(key = "h_img") { Txt("IMAGEN", Ui.Section) }
            items(PlaybackController.Fit.values().toList(), key = { "fit_${it.name}" }) { f ->
                OptionRow(f.label, f == fit, { PlaybackController.setFit(f) },
                    if (f == PlaybackController.Fit.values().first()) Modifier.focusRequester(focus) else Modifier)
            }
            item(key = "h_audio") { Txt("AUDIO", Ui.Section, modifier = Modifier.padding(top = 6.dp)) }
            if (audio.isEmpty()) item(key = "no_audio") { Txt("Esta fuente solo trae una pista de audio (o aún no se conoce).", NeonType.Small, color = Neon.Faint) }
            items(audio, key = { "a_${it.group}_${it.track}" }) { t -> OptionRow(t.label, t.selected, { PlaybackController.selectTrack(t) }) }
            item(key = "h_text") { Txt("SUBTÍTULOS", Ui.Section, modifier = Modifier.padding(top = 6.dp)) }
            if (text.isEmpty()) {
                item(key = "no_text") { Txt("Esta fuente no trae subtítulos.", NeonType.Small, color = Neon.Faint) }
            } else {
                item(key = "t_off") { OptionRow("Desactivados", text.none { it.selected }, { PlaybackController.disableSubtitles() }) }
                items(text, key = { "t_${it.group}_${it.track}" }) { t -> OptionRow(t.label, t.selected, { PlaybackController.selectTrack(t) }) }
            }
        }
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TvClickable(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(46.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Neon.PanelHi else Color.Transparent,
        focusedColor = Neon.PanelHi,
        borderColor = if (selected) Neon.Cyan.copy(alpha = 0.5f) else Neon.Stroke
    ) { _ ->
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(if (selected) "●" else "○", NeonType.Body, color = if (selected) Neon.Cyan else Neon.Faint, modifier = Modifier.width(26.dp))
            Txt(label, NeonType.Body, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun SourceMenuRow(row: PlaybackController.SourceRow, onClick: () -> Unit, modifier: Modifier) {
    val active = row.status == SourceStatus.PLAYING || row.status == SourceStatus.TRYING
    TvClickable(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (active) Neon.PanelHi else Color.Transparent,
        focusedColor = Neon.PanelHi,
        borderColor = if (active) Neon.Cyan.copy(alpha = 0.5f) else Neon.Stroke
    ) { _ ->
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("${row.index + 1}", NeonType.H3, color = Neon.Faint, modifier = Modifier.width(30.dp))
            Box(
                Modifier.width(52.dp).background(if (row.quality != null) Neon.Cyan.copy(alpha = 0.16f) else Color.Transparent, RoundedCornerShape(6.dp))
                    .padding(vertical = 3.dp),
                contentAlignment = Alignment.Center
            ) { Txt(row.quality ?: "—", NeonType.Pill, color = if (row.quality != null) Neon.Cyan else Neon.Faint) }
            Box(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(row.label, NeonType.Body)
                row.provider?.let { Txt(it, NeonType.Small, color = Neon.Faint) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.status == SourceStatus.TRYING) { Spinner(14.dp, Neon.Cyan); Box(Modifier.width(8.dp)) }
                val (text, color) = when (row.status) {
                    SourceStatus.PLAYING -> "▶ Viéndose" to Neon.Cyan
                    SourceStatus.TRYING -> "Probando…" to Neon.Dim
                    SourceStatus.FAILED -> "✕ " + (row.reason ?: "Falló") to Ui.Amber
                    SourceStatus.LEFT -> "Probada" to Neon.Dim
                    SourceStatus.PENDING -> "Sin probar" to Neon.Faint
                }
                Txt(text, NeonType.Small, color = color)
            }
        }
    }
}

internal fun playbackStatus(ui: PlaybackController.Ui): String {
    val src = "fuente ${ui.sourceIndex} de ${ui.sourceCount}" + (ui.quality?.let { " · $it" } ?: "") + (ui.sourceLabel?.let { " · $it" } ?: "")
    return when (ui.phase) {
        Phase.IDLE -> "Preparando…"
        Phase.STARTING_ENGINE -> "Arrancando el motor P2P de MiVisor…"
        Phase.TRYING -> src.replaceFirstChar { it.uppercase() } + " · ${ui.peers} peers"
        Phase.PLAYING -> "En directo · $src · ${ui.peers} peers"
        Phase.FAILED -> ui.failure?.label ?: "No se pudo reproducir"
    }
}

/** Texto bajo el spinner: "Buscando una fuente con imagen… (2 de 5 · FHD)". */
internal fun searchingLine(ui: PlaybackController.Ui): String = when (ui.phase) {
    Phase.TRYING -> "Buscando una fuente con imagen… (${ui.sourceIndex} de ${ui.sourceCount}" + (ui.quality?.let { " · $it" } ?: "") + ")"
    else -> "Preparando el motor P2P…"
}
