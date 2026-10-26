package com.mivisor.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mivisor.app.R
import com.mivisor.app.RemoteInputViewModel
import com.mivisor.app.engine.EngineDiagnostics
import com.mivisor.app.engine.EngineManager
import com.mivisor.app.player.PlayerPreferences
import com.mivisor.app.remote.RemoteState
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Ajustes → Reproductor → Diagnóstico del motor. Métricas en vivo (cada segundo) y el registro del motor interno,
 * clasificado para distinguir resolución, sin peers, timeout, discontinuidad, Media3, proceso y puerto.
 * Los registros detallados están DESACTIVADOS por defecto (los avisos importantes se guardan siempre); el registro
 * tiene un tope de líneas y nunca incluye Content IDs completos, códigos ni enlaces.
 */
@Composable
fun EngineDiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val layout = LocalAppLayout.current
    val prefs = remember { PlayerPreferences(context) }
    val remote: RemoteInputViewModel = viewModel()
    val remoteState by remote.state.collectAsState()
    val scope = rememberCoroutineScope()
    var logs by remember { mutableStateOf(prefs.engineLogs) }
    var tick by remember { mutableLongStateOf(0L) }
    val engine by EngineManager.state.collectAsState()

    DisposableEffect(Unit) { onDispose { remote.cancel() } }
    BackHandler(enabled = true) { onBack() }
    LaunchedEffect(Unit) {
        while (true) {
            EngineManager.refreshMemoryMetrics(context)
            EngineDiagnostics.update { it.copy(engineMode = prefs.engineMode.label) }
            tick = EngineDiagnostics.revision
            delay(1_000)
        }
    }

    val m = remember(tick) { EngineDiagnostics.metrics }
    val lines = remember(tick) { EngineDiagnostics.snapshot().takeLast(300).reversed() }

    Column(Modifier.fillMaxSize().padding(horizontal = layout.gutter)) {
        ScreenHero(
            title = "Diagnóstico del motor",
            subtitle = "Motor P2P interno: estado, fuente, peers, buffers, memoria y registro. Sin Content IDs completos ni enlaces.",
            status = "Motor ${engine.phase.name.lowercase(Locale.ROOT)}" + (engine.port?.let { " · puerto $it" } ?: "")
        ) {
            NeonButton(
                if (logs) "Registros: ACTIVADOS" else "Registros: desactivados",
                { logs = !logs; prefs.engineLogs = logs; EngineDiagnostics.enabled = logs },
                icon = R.drawable.ic_bolt, selected = logs
            )
            NeonButton("Copiar al móvil (QR)", { remote.startShow(EngineDiagnostics.report()) }, icon = R.drawable.ic_share)
            NeonButton("Limpiar", { EngineDiagnostics.clear() }, icon = R.drawable.ic_delete)
            NeonButton("Parar motor", { scope.launch { EngineManager.stop() } }, icon = R.drawable.ic_close)
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(
                Modifier.then(if (layout.compact) Modifier.fillMaxWidth() else Modifier.weight(0.42f)).padding(6.dp).neonBox(16.dp).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Metric("Modo", m.engineMode)
                Metric("Motor", m.engineState + (m.port?.let { " · 127.0.0.1:$it" } ?: ""))
                engine.message?.let { Metric("Detalle", it) }
                Metric("Canal", m.channel ?: "—")
                Metric("Fuente", if (m.sourceCount == 0) "—" else "${m.sourceIndex} de ${m.sourceCount} · ${m.sourceId ?: "—"}")
                Metric("Peers", m.peers.toString())
                Metric("Bitrate", String.format(Locale.US, "%.2f Mb/s", m.bitrateBps / 1_000_000.0))
                Metric("Buffer motor", "${m.engineBufferMs} ms")
                Metric("Buffer Media3", "${m.playerBufferMs} ms (objetivo ${m.bufferTargetMs} ms)")
                Metric("1.ª imagen", m.firstFrameMs?.let { "$it ms" } ?: "—")
                Metric("Recuperación", "${m.recoveries} reconexiones · ${m.skippedGaps} huecos")
                Metric("Cambios", "${m.sourceSwitches} de fuente · ${m.rebuffers} cortes")
                Metric("RAM", "${m.deviceAvailMb}/${m.deviceRamMb} MB libres · MiVisor ${m.appUsedMb} MB" + if (m.lowRam) " · low-RAM" else "")
            }
            if (!layout.compact) {
                LazyColumn(
                    Modifier.weight(0.58f).fillMaxHeight().padding(6.dp).neonBox(16.dp),
                    contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (lines.isEmpty()) item {
                        Txt(
                            if (logs) "Sin entradas todavía. Reproduce un canal Ace Stream." else "Solo se guardan los avisos importantes. Activa «Registros» para ver el detalle.",
                            NeonType.Small, color = Neon.Dim, maxLines = 3
                        )
                    }
                    val chunks = lines.chunked(6)
                    items(chunks.size) { i ->
                        NeonCard(onClick = {}, modifier = Modifier.fillMaxWidth(), radius = 8.dp, color = Neon.Panel,
                            borderColor = androidx.compose.ui.graphics.Color.Transparent) { _ ->
                            Txt(chunks[i].joinToString("\n"), NeonType.Small, color = Neon.Dim, maxLines = 12,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                }
            }
        }
    }

    if (remoteState !is RemoteState.Idle) {
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        TvDialog(title = "Diagnóstico en el móvil", onDismiss = { remote.cancel() }, width = 640.dp) {
            RemotePanel(remoteState, onCancel = { remote.cancel() }, onRetry = { remote.startShow(EngineDiagnostics.report()) }, firstFocus = focus)
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row {
        Txt(label, NeonType.Small, modifier = Modifier.width(110.dp))
        Txt(value, NeonType.Small, color = Neon.Text, maxLines = 2)
    }
}
