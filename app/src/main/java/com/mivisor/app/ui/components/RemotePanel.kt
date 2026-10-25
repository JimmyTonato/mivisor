package com.mivisor.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mivisor.app.R
import com.mivisor.app.remote.QrMatrix
import com.mivisor.app.remote.RemoteSession
import com.mivisor.app.remote.RemoteState
import com.mivisor.app.ui.neon.Spinner
import com.mivisor.app.ui.theme.Palette
import com.mivisor.app.ui.theme.Type
import kotlinx.coroutines.delay

/** QR dibujado en local, con margen blanco de 4 módulos (necesario para que lo lean los móviles). */
@Composable
fun QrImage(matrix: QrMatrix, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size).background(Color.White, RoundedCornerShape(10.dp))) {
        val quiet = 4
        val cells = matrix.size + quiet * 2
        val cell = this.size.minDimension / cells
        for (y in 0 until matrix.size) for (x in 0 until matrix.size) {
            if (matrix[x, y]) {
                drawRect(Color.Black, Offset((x + quiet) * cell, (y + quiet) * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

/**
 * Contenido del diálogo mientras se usa el móvil: QR, instrucciones y "Cancelar" (Atrás hace lo mismo).
 * Caducado o error: mensaje claro y "Generar nuevo QR".
 */
@Composable
fun RemotePanel(
    state: RemoteState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    firstFocus: FocusRequester
) {
    // Cada cambio (QR → caducado → nuevo QR) vuelve a poner el foco en el primer botón.
    key(state::class) { InitialFocus(firstFocus) }
    when (state) {
        is RemoteState.Waiting -> Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            QrImage(state.qr, 210.dp)
            Column(Modifier.width(260.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt(
                    if (state.mode == RemoteSession.Mode.INPUT) "Escanea el QR y pega el código desde tu móvil."
                    else "Escanea el QR para ver el registro en el móvil y copiarlo.",
                    Type.Body, maxLines = 3
                )
                Txt("El móvil y el televisor deben estar conectados a la misma red Wi-Fi/LAN.", Type.Caption, maxLines = 3)
                Txt("Si no abre, comprueba que la red permite la comunicación entre dispositivos.", Type.Caption, color = Palette.TextFaint, maxLines = 3)
                Txt("O abre en el móvil: ${state.url.removePrefix("http://").substringBeforeLast('/')}/…", Type.Caption, color = Palette.TextFaint, maxLines = 2)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.mode == RemoteSession.Mode.INPUT) {
                        Spinner(14.dp, Palette.Cyan)
                        Box(Modifier.width(8.dp))
                        Txt("Esperando texto…", Type.Caption)
                    }
                    Box(Modifier.width(10.dp))
                    Countdown(state.expiresAt)
                }
                TvButton("Cancelar", onCancel, Modifier.focusRequester(firstFocus), style = ButtonStyle.GHOST)
            }
        }
        is RemoteState.Expired -> Message("La sesión ha caducado.", null, onRetry, onCancel, firstFocus)
        is RemoteState.Failed -> Message(
            if (state.reason == RemoteState.Reason.NO_LAN) "No se ha podido iniciar la conexión con el móvil."
            else "No se pudo iniciar el servicio de escritura remota.",
            if (state.reason == RemoteState.Reason.NO_LAN) "Conecta el televisor a tu red Wi-Fi o por cable y vuelve a intentarlo." else null,
            onRetry, onCancel, firstFocus
        )
        else -> Unit
    }
}

@Composable
private fun Message(title: String, detail: String?, onRetry: () -> Unit, onCancel: () -> Unit, firstFocus: FocusRequester) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Txt(title, Type.Body, maxLines = 2)
        if (detail != null) Txt(detail, Type.Caption, maxLines = 3)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton("Generar nuevo QR", onRetry, Modifier.focusRequester(firstFocus), icon = R.drawable.ic_refresh, style = ButtonStyle.PRIMARY)
            TvButton("Volver", onCancel, style = ButtonStyle.GHOST)
        }
    }
}

@Composable
private fun Countdown(expiresAt: Long, prefix: String = "Caduca en ") {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAt) {
        while (now < expiresAt) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val s = ((expiresAt - now) / 1000).coerceAtLeast(0)
    Txt(prefix + "%d:%02d".format(s / 60, s % 60), Type.Caption, color = Palette.TextFaint, modifier = Modifier.padding(start = 2.dp))
}

/**
 * "Editar con el móvil" dentro del formulario: el QR está SIEMPRE visible (la sesión se crea al abrir).
 * Muestra discretamente el tiempo de sesión; al caducar, "Sesión terminada" y "Generar nuevo QR" aquí mismo.
 * No tiene botón de cerrar: Guardar y Salir siguen en el pie del formulario en todo momento.
 */
@Composable
fun MobileEditPanel(
    state: RemoteState,
    sends: Int,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
    qrSize: Dp = 150.dp,
    startFocus: FocusRequester? = null
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(R.drawable.ic_phone, Palette.Cyan, size = 18.dp)
            Box(Modifier.width(8.dp))
            Txt("Editar con el móvil", Type.Label, maxLines = 1)
        }
        val startMod = if (startFocus != null) Modifier.focusRequester(startFocus) else Modifier
        when (state) {
            is RemoteState.Waiting -> {
                QrImage(state.qr, qrSize)
                Countdown(state.expiresAt, prefix = "Sesión: ")
                Txt(
                    if (sends == 0) "Escanea, corrige título y código y pulsa «Enviar al televisor». Puedes enviar varias veces."
                    else "Recibidos $sends ${if (sends == 1) "envío" else "envíos"}. Puedes seguir enviando.",
                    Type.Caption, color = if (sends == 0) Palette.TextFaint else Palette.Cyan, maxLines = 3
                )
            }
            is RemoteState.Expired -> {
                QrPlaceholder(qrSize, "Sesión terminada")
                Txt("El móvil ya no puede enviar cambios.", Type.Caption, maxLines = 2)
                TvButton("Generar nuevo QR", onStart, startMod, icon = R.drawable.ic_refresh, style = ButtonStyle.NORMAL)
            }
            is RemoteState.Failed -> {
                QrPlaceholder(qrSize, "Sin QR")
                Txt(
                    if (state.reason == RemoteState.Reason.NO_LAN) "No se ha podido iniciar la conexión con el móvil."
                    else "No se pudo iniciar el servicio de escritura remota.",
                    Type.Caption, maxLines = 3
                )
                Txt("Asegúrate de que ambos dispositivos están en la misma red y que la red permite comunicación entre dispositivos.",
                    Type.Caption, color = Palette.TextFaint, maxLines = 4)
                TvButton("Generar nuevo QR", onStart, startMod, icon = R.drawable.ic_refresh, style = ButtonStyle.NORMAL)
            }
            else -> {
                QrPlaceholder(qrSize, "Preparando QR…")
            }
        }
    }
}

/** Hueco del QR cuando no hay sesión (mismo tamaño: el formulario no "salta"). */
@Composable
private fun QrPlaceholder(size: Dp, text: String) {
    Box(
        Modifier.size(size).background(Palette.Bg, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) { Txt(text, Type.Caption, color = Palette.TextFaint, maxLines = 2) }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp).background(Palette.SurfaceHi, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Txt(n.toString(), Type.Badge, color = Palette.Text)
        }
        Box(Modifier.width(8.dp))
        Txt(text, Type.Caption)
    }
}
