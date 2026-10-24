package com.mivisor.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mivisor.app.R
import com.mivisor.app.ServicesUi
import com.mivisor.app.ServicesViewModel
import com.mivisor.app.model.Overall
import com.mivisor.app.model.ServiceInfo
import com.mivisor.app.model.ServiceState
import com.mivisor.app.model.ServiceText
import com.mivisor.app.ui.components.Txt
import com.mivisor.app.ui.neon.LocalAppLayout
import com.mivisor.app.ui.neon.Neon
import com.mivisor.app.ui.neon.NeonButton
import com.mivisor.app.ui.neon.NeonCard
import com.mivisor.app.ui.neon.NeonType
import com.mivisor.app.ui.neon.Overline
import com.mivisor.app.ui.neon.ScreenHero
import com.mivisor.app.ui.neon.neonBox

private val Green = Color(0xFF34D399)
private val Amber = Color(0xFFF59E0B)

private fun colorOf(state: ServiceState) = when (state) {
    ServiceState.OK -> Green
    ServiceState.STALE -> Amber
    ServiceState.UNAVAILABLE -> Neon.Red
    ServiceState.DISABLED -> Neon.Faint
}

private fun colorOf(overall: Overall) = when (overall) {
    Overall.OK -> Green
    Overall.DEGRADED -> Amber
    Overall.DOWN -> Neon.Red
}

/**
 * Ajustes → Servicios y diagnóstico: qué fuentes funcionan, última sincronización correcta y fallos
 * recientes. "Actualizar" solo vuelve a descargar services.json.
 */
@Composable
fun ServicesScreen(onBack: () -> Unit, vm: ServicesViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val layout = LocalAppLayout.current
    val selected = ui.report?.services?.firstOrNull { it.id == ui.selected }
    BackHandler(enabled = true) { if (layout.compact && selected != null) vm.select(null) else onBack() }

    Column(Modifier.fillMaxSize().padding(horizontal = layout.gutter)) {
        ScreenHero(
            title = "Servicios",
            subtitle = "Estado de las fuentes de la agenda, los canales y los retratos.",
            status = statusLine(ui),
            statusColor = if (ui.offline) Amber else Neon.Faint
        ) {
            NeonButton("Actualizar", { vm.load(force = true) }, icon = R.drawable.ic_refresh)
        }
        val report = ui.report
        when {
            ui.loading -> Txt("Cargando diagnóstico…", NeonType.Body, color = Neon.Dim, modifier = Modifier.padding(8.dp))
            report == null -> Column(Modifier.padding(6.dp).neonBox().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt("Diagnóstico no disponible", NeonType.H2)
                Txt(ui.error ?: "Inténtalo de nuevo más tarde.", NeonType.Body, color = Neon.Dim, maxLines = 3)
            }
            layout.compact && selected != null -> ServiceDetail(selected, ui, Modifier.fillMaxSize())
            layout.compact -> ServiceList(ui, vm, Modifier.fillMaxSize())
            else -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ServiceList(ui, vm, Modifier.weight(1f).fillMaxHeight())
                ServiceDetail(selected ?: report.services.firstOrNull { it.state != ServiceState.OK } ?: report.services.firstOrNull(),
                    ui, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

private fun statusLine(ui: ServicesUi): String {
    val report = ui.report ?: return ""
    val generated = ServiceText.date(report.generatedAt.takeIf { it > 0 })
    return if (ui.offline) "Sin conexión · copia guardada (puede no estar actualizada) · generada $generated"
    else "Generado $generated · comprobado ${relativeTime(ui.fetchedAt)}"
}

@Composable
private fun Dot(color: Color, size: Int = 9) {
    Box(Modifier.size(size.dp).background(color, CircleShape))
}

@Composable
private fun ServiceList(ui: ServicesUi, vm: ServicesViewModel, modifier: Modifier) {
    val report = ui.report ?: return
    LazyColumn(modifier, contentPadding = PaddingValues(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "overall") {
            Row(Modifier.fillMaxWidth().neonBox().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(colorOf(report.overall), 12)
                Box(Modifier.width(10.dp))
                Column {
                    Overline("Estado general")
                    Txt(ServiceText.overall(report.overall), NeonType.H3)
                }
            }
        }
        items(report.services, key = { it.id }) { service ->
            NeonCard(
                onClick = { vm.select(service.id) },
                modifier = Modifier.fillMaxWidth().height(58.dp),
                radius = 12.dp,
                borderColor = if (service.id == ui.selected) Neon.Cyan.copy(alpha = 0.6f) else Neon.Stroke
            ) { _ ->
                Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Txt(service.name, NeonType.H3)
                        Txt(service.role, NeonType.Small)
                    }
                    Dot(colorOf(service.state))
                    Box(Modifier.width(8.dp))
                    Txt(ServiceText.state(service.state), NeonType.Body, color = if (service.state == ServiceState.OK) Neon.Dim else colorOf(service.state))
                }
            }
        }
    }
}

@Composable
private fun ServiceDetail(service: ServiceInfo?, ui: ServicesUi, modifier: Modifier) {
    service ?: return
    val history = ui.report?.historyOf(service.id).orEmpty().take(30)
    LazyColumn(
        modifier.padding(6.dp).neonBox(16.dp),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "head") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt(service.name, NeonType.H2)
                Txt(service.role, NeonType.Body, color = Neon.Dim)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Dot(colorOf(service.state))
                    Box(Modifier.width(8.dp))
                    Txt(ServiceText.state(service.state), NeonType.H3, color = colorOf(service.state))
                }
            }
        }
        item(key = "facts") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 6.dp)) {
                Fact("Último intento", ServiceText.date(service.lastAttempt))
                Fact("Última sincronización correcta", ServiceText.date(service.lastSuccess))
                if (service.consecutiveFailures > 0) Fact("Fallos consecutivos", service.consecutiveFailures.toString())
                service.lastError?.let { err ->
                    Fact(
                        if (service.state == ServiceState.OK) "Último error (resuelto)" else "Último error",
                        "${ServiceText.error(err.code)}${err.at?.let { " · " + ServiceText.date(it) }.orEmpty()}"
                    )
                }
            }
        }
        item(key = "hist") { Overline("Historial reciente", Modifier.padding(top = 10.dp)) }
        if (history.isEmpty()) {
            item(key = "none") { Txt("Sin incidencias en los últimos 30 días.", NeonType.Body, color = Neon.Dim) }
        }
        items(history.size, key = { "h$it" }) { i ->
            val e = history[i]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(ServiceText.date(e.at), NeonType.Small, modifier = Modifier.width(110.dp))
                Dot(if (e.type == "recovery") Green else Neon.Red, 7)
                Box(Modifier.width(8.dp))
                Column {
                    Txt(ServiceText.event(e), NeonType.Body)
                    if (e.type != "recovery") Txt(ServiceText.error(e.code), NeonType.Small)
                }
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row {
        Txt(label, NeonType.Small, modifier = Modifier.width(210.dp))
        Txt(value, NeonType.Body, maxLines = 2)
    }
}
