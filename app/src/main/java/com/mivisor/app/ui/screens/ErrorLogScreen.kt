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
import androidx.compose.ui.focus.focusRequester
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
    var aceLogsHelp by remember { mutableStateOf(false) }
    val prefs = remember { com.mivisor.app.player.PlayerPreferences(context) }
    var aceDiag by remember { mutableStateOf(prefs.aceDiagnostics) }
    val current = reports.firstOrNull { it.name == selected } ?: reports.firstOrNull()
    val text = remember(current?.name, reload) { current?.let { runCatching { it.readText() }.getOrNull() }.orEmpty() }

    DisposableEffect(Unit) { onDispose { remote.cancel() } }
    BackHandler(enabled = true) { onBack() }

    Column(Modifier.fillMaxSize().padding(horizontal = layout.gutter)) {
        ScreenHero(
            title = "Registro de errores",
            subtitle = "Errores de MiVisor y Registro Ace Stream (qué app abre cada canal, VPN, motor, si se vio). Sin tus códigos ni enlaces completos.",
            status = if (reports.isEmpty()) "Sin errores registrados" else "${reports.size} registro(s)"
        ) {
            if (current != null) {
                NeonButton("Ver en el móvil (QR)", { remote.startShow(text) }, icon = R.drawable.ic_share)
                NeonButton("Borrar registro", { confirmClear = true }, icon = R.drawable.ic_delete)
            }
            NeonButton("Actualizar", { reload++ }, icon = R.drawable.ic_refresh)
            // Registro Ace Stream: solo cuando haya algo que revisar (desactivado por defecto).
            NeonButton(
                if (aceDiag) "Registro Ace Stream: ACTIVADO" else "Registro Ace Stream: desactivado",
                {
                    aceDiag = !aceDiag
                    prefs.aceDiagnostics = aceDiag
                    android.widget.Toast.makeText(
                        context,
                        if (aceDiag) "Se anotará cada canal Ace que abras. Pásalo con «Ver en el móvil»." else "Registro Ace Stream desactivado",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                },
                icon = R.drawable.ic_bolt, selected = aceDiag
            )
            if (aceDiag) NeonButton(
                if (com.mivisor.app.diagnostics.AceSysLog.granted(context)) "Logs internos de Ace: con permiso" else "Logs internos de Ace",
                { aceLogsHelp = true }, icon = R.drawable.ic_bolt
            )
            NeonButton("Olvidar apps recordadas", {
                com.mivisor.app.player.PlayerPreferences(context).clearPreferredApps()
                android.widget.Toast.makeText(context, "Se volverá a preguntar con qué app abrir cada enlace", android.widget.Toast.LENGTH_SHORT).show()
            }, icon = R.drawable.ic_delete)
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
    if (aceLogsHelp) {
        val help = remember { aceLogsInstructions(context) }
        val focus = remember { FocusRequester() }
        TvDialog(title = "Logs internos de Ace Stream", onDismiss = { aceLogsHelp = false }, width = 640.dp, initialFocus = focus) {
            Txt(
                if (com.mivisor.app.diagnostics.AceSysLog.granted(context))
                    "ACTIVADOS. Tras abrir un canal Ace y volver a MiVisor, el Registro Ace Stream incluye lo que Ace escribió durante el intento."
                else "NO activados (falta el permiso de lectura de registros).",
                NeonType.H3, maxLines = 3
            )
            Txt(help, NeonType.Small, color = Neon.Dim, maxLines = 30)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NeonButton("Pasar al móvil (QR)", { aceLogsHelp = false; remote.startShow(help) }, icon = R.drawable.ic_share, modifier = Modifier.focusRequester(focus))
                NeonButton("Cerrar", { aceLogsHelp = false })
            }
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

private fun titleOf(f: File) = when {
    f.name.startsWith("crash-") -> "Cierre inesperado"
    f.name == com.mivisor.app.diagnostics.AceLog.FILE -> "Registro Ace Stream"
    f.name.startsWith("diag-") -> "Última apertura de canal"
    else -> "Error recuperado"
}

/** crash-20261024-213005-123.txt → 24/10/2026 21:30:05 */
private fun dateOf(f: File): String {
    val m = Regex("""(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})(\d{2})""").find(f.name)
        ?: return java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.US).format(java.util.Date(f.lastModified()))
    val (y, mo, d, h, mi, s) = m.destructured
    return "$d/$mo/$y $h:$mi:$s"
}

/** Instrucciones para activar los logs internos de Ace (texto que también se puede pasar al móvil). */
private fun aceLogsInstructions(context: android.content.Context): String = """
Por qué hace falta: Ace Stream guarda su log completo (acestream.log) en su carpeta privada, que ninguna otra app puede leer desde Android 11. Lo que Ace escribe en el log del sistema (AS/Player, AS/ContentStart…) sí se puede leer, pero Android solo da ese permiso desde un ordenador. Es una sola vez.

1. En la TV/tablet: Ajustes → Opciones de desarrollador → activa la Depuración USB (o la Depuración inalámbrica / ADB por red).
2. En el ordenador (con las herramientas "platform-tools" de Android): conecta por cable, o por red con
   adb connect <IP de la TV>
3. Ejecuta:
   ${com.mivisor.app.diagnostics.AceSysLog.grantCommand(context)}
4. Cierra MiVisor y vuelve a abrirlo. Android 13 o superior preguntará si MiVisor puede ver los registros del dispositivo: acepta.

MiVisor guarda solo las líneas de Ace del intento (no las de otras apps), sin IDs, URLs ni IPs. Para quitarlo:
   adb shell pm revoke ${context.packageName} android.permission.READ_LOGS

Alternativa sin MiVisor (desde el ordenador, mientras pruebas el mismo canal en MiVisor y en la app antigua):
   adb logcat -v time > ace_logcat.txt
   adb pull /sdcard/Android/data/org.acestream.node/files/acestream.log
(La segunda funciona en muchos dispositivos; es el log completo del motor: trackers, peers y errores.)
""".trim()
