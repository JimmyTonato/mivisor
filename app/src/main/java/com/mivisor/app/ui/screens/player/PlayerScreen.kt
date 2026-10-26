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
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.mivisor.app.R
import com.mivisor.app.engine.PlaybackController
import com.mivisor.app.engine.PlaybackController.Phase
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
 * vídeo y cualquier tecla del mando lo vuelve a mostrar. Atrás vuelve a MiVisor y cierra la sesión del canal.
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

    BackHandler(enabled = true) { onBack() }

    // El panel se oculta 5 s después de la última tecla, solo si ya hay vídeo.
    LaunchedEffect(ui.phase, lastKey) {
        overlay = true
        if (ui.phase == Phase.PLAYING) {
            delay(5_000)
            overlay = false
        }
    }
    LaunchedEffect(overlay) {
        runCatching { if (overlay) firstButton.requestFocus() else catcher.requestFocus() }
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
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    player = PlaybackController.player
                }
            },
            onRelease = { it.player = null },
            update = { view ->
                ui.phase                                               // se vuelve a evaluar al cambiar de fase
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
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                    .padding(horizontal = 32.dp, vertical = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                var first = true
                fun mod(): Modifier = if (first) { first = false; Modifier.focusRequester(firstButton) } else Modifier
                if (ui.phase == Phase.FAILED) {
                    PrimaryButton("Reintentar", onRetry, mod(), icon = R.drawable.ic_refresh)
                }
                if (ui.canNext && ui.phase != Phase.FAILED) {
                    SecondaryButton("Siguiente fuente", { lastKey = System.currentTimeMillis(); PlaybackController.nextSource() }, mod(), icon = R.drawable.ic_chevron_right)
                }
                if (onExternal != null) SecondaryButton("Abrir con Ace Stream", onExternal, mod(), icon = R.drawable.ic_bolt)
                SecondaryButton("Volver", onBack, mod(), icon = R.drawable.ic_chevron_left)
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
