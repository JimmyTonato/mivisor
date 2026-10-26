package com.mivisor.app.ui.screens.player

import android.graphics.Color as AColor
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.mivisor.app.data.logos.ResolvedLogo
import com.mivisor.app.engine.PlaybackController
import com.mivisor.app.engine.PlaybackController.Phase
import com.mivisor.app.ui.components.ChannelLogoBadge
import com.mivisor.app.ui.components.Txt
import com.mivisor.app.ui.kit.Ui
import com.mivisor.app.ui.neon.Neon
import com.mivisor.app.ui.neon.NeonType
import com.mivisor.app.ui.neon.Spinner

/**
 * Vista previa del canal enfocado (Canales). Vídeo pequeño y SILENCIADO del motor interno; mientras busca una fuente
 * con imagen, spinner. OK sobre el canal abre la pantalla completa reutilizando esta misma sesión.
 *
 * [sessionKey] = clave del canal enfocado; el vídeo solo se pinta si la sesión en marcha es de ese canal.
 * Al salir de la pantalla, la vista previa se para (si no se ha pasado a pantalla completa).
 */
@OptIn(UnstableApi::class)
@Composable
fun PreviewPanel(
    title: String?,
    logo: ResolvedLogo?,
    sessionKey: String?,
    hint: String,
    modifier: Modifier = Modifier,
    footer: @Composable () -> Unit = {}
) {
    val ui by PlaybackController.ui.collectAsState()
    val mine = sessionKey != null && ui.channelKey == sessionKey && ui.phase != Phase.IDLE

    DisposableEffect(Unit) {
        onDispose { if (PlaybackController.ui.value.preview) PlaybackController.stop() }
    }

    Column(
        modifier.background(Neon.Panel.copy(alpha = 0.9f), RoundedCornerShape(16.dp)).border(1.dp, Neon.Stroke, RoundedCornerShape(16.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (mine) {
                // La superficie de vídeo tiene que estar puesta desde el principio: sin ella no hay primer fotograma.
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
                    update = { v ->
                        ui.phase
                        if (v.player !== PlaybackController.player) v.player = PlaybackController.player
                    },
                    modifier = Modifier.fillMaxSize()
                )
                when (ui.phase) {
                    Phase.PLAYING -> Unit
                    Phase.FAILED -> Txt("Sin imagen en ninguna fuente", NeonType.Small, color = Ui.Amber, maxLines = 2)
                    else -> Column(
                        Modifier.fillMaxSize().background(Color.Black),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
                    ) {
                        Spinner(34.dp, Neon.Cyan)
                        Txt(searchingLine(ui), NeonType.Small, color = Neon.Dim, maxLines = 2, modifier = Modifier.padding(horizontal = 10.dp))
                    }
                }
            } else if (logo != null) {
                ChannelLogoBadge(logo, 128.dp, 72.dp)
            }
        }
        title?.let { Txt(it, NeonType.H3, maxLines = 2) }
        Txt(
            if (mine) playbackStatus(ui) else hint,
            NeonType.Small, color = if (mine && ui.phase == Phase.FAILED) Ui.Amber else Neon.Dim, maxLines = 3
        )
        footer()
    }
}

/**
 * Lo que necesitan las pantallas de Canales para la vista previa. null = sin vista previa (modo externo, sin motor
 * interno, desactivada en Ajustes o pantalla de móvil).
 */
class PreviewHooks(
    /** Clave de sesión del canal agrupado, o null si no tiene fuentes Ace Stream (no hay vista previa). */
    val keyOfGroup: (com.mivisor.app.data.ChannelGroup) -> String?,
    val previewGroup: (com.mivisor.app.data.ChannelGroup) -> Unit,
    /** OK sobre un canal agrupado: pantalla completa con la mejor fuente (reutiliza la vista previa). */
    val playGroup: (com.mivisor.app.data.ChannelGroup) -> Unit,
    val keyOfChannel: (com.mivisor.app.model.Channel) -> String?,
    val previewChannel: (com.mivisor.app.model.Channel) -> Unit
)
