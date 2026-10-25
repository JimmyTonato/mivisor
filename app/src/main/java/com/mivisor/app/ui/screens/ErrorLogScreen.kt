package com.mivisor.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mivisor.app.R
import com.mivisor.app.RemoteInputViewModel
import com.mivisor.app.diagnostics.CrashReporter
import com.mivisor.app.remote.RemoteState
import com.mivisor.app.ui.components.ConfirmDialog
import com.mivisor.app.ui.components.RemotePanel
import com.mivisor.app.ui.components.Txt
import com.mivisor.app.ui.components.TvDialog
import com.mivisor.app.ui.neon.LocalAppLayout
import com.mivisor.app.ui.neon.Neon
import com.mivisor.app.ui.neon.NeonButton
import com.mivisor.app.ui.neon.NeonCard
import com.mivisor.app.ui.neon.NeonType
import com.mivisor.app.ui.neon.ScreenHero
import com.mivisor.app.ui.neon.neonBox
import java.io.File

/**
 * Ajustes → Registro de errores. Muestra los cierres inesperados y los errores recuperados (trazas limpias:
 * sin códigos, URLs ni claves). "Ver en el móvil" abre un QR de solo lectura para copiar el texto
 * y enviarlo; el servidor se apaga al cerrar el QR o salir de la pantalla.
 */
@Composable
fun ErrorLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val layout = LocalAppLayout.current
    val remote: RemoteInputViewModel = viewModel()
    val remoteState by remote.state.collectAsState()
    var reload by remember { mutableIntStateOf(0) }
    val reports = remember(reload) { CrashReporter.reports(context) }
    var selected by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val current = reports.firstOrNull { it.name == selected } ?: reports.firstOrNull()
    val text = remember(current?.name, reload) { current?.let { runCatching { it.readText() }.getOrNull() }.orEmpty() }

    DisposableEffect(Unit) { onDispose { remote.cancel() } }
    BackHandler(enabled = true) { onBack() }

    Column(Modifier.fillMaxSize().padding(horizontal = layout.gutter)) {
        ScreenHero(
            title = "Registro de errores",
            subtitle = "Si MiVisor se cierra o falla, aquí queda el detalle para poder corregirlo. No incluye tus códigos ni enlaces.",
            status = if (reports.isEmpty()) "Sin errores registrados" else "${reports.size} registro(s)"
        ) {
            if (current != null) {
                NeonButton("Ver en el móvil (QR)", { remote.startShow(text) }, icon = R.drawable.ic_share)
                NeonButton("Borrar registro", { confirmClear = true }, icon = R.drawable.ic_delete)
            }
            NeonButton("Actualizar", { reload++ }, icon = R.drawable.ic_refresh)
        }
        if (reports.isEmpty()) {
            Column(Modifier.padding(6.dp).neonBox().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt("Todo en orden", NeonType.H2)
                Txt(
                    "Si la app se cierra al navegar, vuelve a abrirla y entra aquí: verás el error y podrás pasarlo al móvil.",
                    NeonType.Body, color = Neon.Dim, maxLines = 3
                )
            }
        } else {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (!layout.compact) {
                    LazyColumn(Modifier.weight(0.38f).fillMaxHeight(), contentPadding = PaddingValues(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(reports, key = { it.name }) { f ->
                            NeonCard(
                                onClick = { selected = f.name },
                                modifier = Modifier.fillMaxWidth().height(58.dp),
                                radius = 12.dp,
                                borderColor = if (f == current) Neon.Cyan.copy(alpha = 0.6f) else Neon.Stroke
                            ) { _ ->
                                Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp)) {
                                    Txt(titleOf(f), NeonType.H3)
                                    Txt(dateOf(f), NeonType.Small)
                                }
                            }
                        }
                    }
                }
                ReportText(text, Modifier.weight(0.62f).fillMaxHeight())
            }
        }
    }

    if (remoteState !is RemoteState.Idle) {
        val focus = remember { FocusRequester() }
        TvDialog(title = "Ver el registro en el móvil", onDismiss = { remote.cancel() }, width = 640.dp) {
            RemotePanel(remoteState, onCancel = { remote.cancel() }, onRetry = { remote.startShow(text) }, firstFocus = focus)
        }
    }
    if (confirmClear) {
        ConfirmDialog(
            title = "Borrar registro de errores",
            message = "Se eliminarán todos los registros guardados en este dispositivo.",
            confirmLabel = "Borrar",
            onConfirm = { CrashReporter.clear(context); confirmClear = false; selected = null; reload++ },
            onDismiss = { confirmClear = false }
        )
    }
}

/** El texto va en bloques enfocables para poder recorrerlo con el mando. */
@Composable
private fun ReportText(text: String, modifier: Modifier) {
    val chunks = remember(text) { text.lines().chunked(10).map { it.joinToString("\n") } }
    LazyColumn(modifier.padding(6.dp).neonBox(16.dp), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(chunks.size, key = { it }) { i ->
            NeonCard(onClick = {}, modifier = Modifier.fillMaxWidth(), radius = 8.dp, color = Neon.Panel, borderColor = androidx.compose.ui.graphics.Color.Transparent) { _ ->
                Txt(chunks[i], NeonType.Small, color = Neon.Dim, maxLines = 10, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
    }
}

private fun titleOf(f: File) = if (f.name.startsWith("crash-")) "Cierre inesperado" else "Error recuperado"

/** crash-20261024-213005-123.txt → 24/10/2026 21:30:05 */
private fun dateOf(f: File): String {
    val m = Regex("""(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})(\d{2})""").find(f.name) ?: return f.name
    val (y, mo, d, h, mi, s) = m.destructured
    return "$d/$mo/$y $h:$mi:$s"
}
