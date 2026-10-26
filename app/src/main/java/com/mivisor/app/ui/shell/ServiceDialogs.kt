package com.mivisor.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.mivisor.app.HelpTopic
import com.mivisor.app.PrepUi
import com.mivisor.app.QuickServicesUi
import com.mivisor.app.R
import com.mivisor.app.services.Indicator
import com.mivisor.app.services.PrepProblem
import com.mivisor.app.services.QuickServiceText
import com.mivisor.app.services.RowAction
import com.mivisor.app.services.ServiceRow
import com.mivisor.app.services.StepState
import com.mivisor.app.services.WarpPhase
import com.mivisor.app.ui.components.Icon
import com.mivisor.app.ui.components.InitialFocus
import com.mivisor.app.ui.components.TvDialog
import com.mivisor.app.ui.components.Txt
import com.mivisor.app.ui.kit.PrimaryButton
import com.mivisor.app.ui.kit.SecondaryButton
import com.mivisor.app.ui.kit.Ui
import com.mivisor.app.ui.neon.Neon
import com.mivisor.app.ui.neon.NeonType
import com.mivisor.app.ui.neon.Spinner

/** Panel rápido "Servicios de reproducción" (OK en una fila que está bien). */
@Composable
fun ServicesPanelDialog(
    services: QuickServicesUi,
    onOk: (RowAction) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit
) {
    val first = remember { FocusRequester() }
    TvDialog(title = "Servicios de reproducción", subtitle = "Necesarios para algunos contenidos.", onDismiss = onDismiss, width = 560.dp, initialFocus = first) {
        ServiceCard(services.ace, R.drawable.ic_bolt, onOk, extra = null, focus = first)
        ServiceCard(
            services.warp, R.drawable.ic_shield, onOk,
            extra = if (services.warpStatus.phase == WarpPhase.CONNECTED) "Desconectar" to RowAction.WARP_DISCONNECT else null,
            focus = null
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Gestionar servicios", onManage, icon = R.drawable.ic_settings)
            SecondaryButton("Comprobar de nuevo", { onOk(RowAction.REFRESH) }, icon = R.drawable.ic_refresh)
            SecondaryButton("Cerrar", onDismiss)
        }
    }
}

@Composable
fun ServiceCard(row: ServiceRow, icon: Int, onOk: (RowAction) -> Unit, extra: Pair<String, RowAction>?, focus: FocusRequester?) {
    val c = row.indicator.color()
    Row(
        Modifier.fillMaxWidth().background(Neon.Panel, RoundedCornerShape(14.dp)).border(1.dp, Neon.Stroke, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BigStateGlyph(row.indicator, icon)
        Box(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(row.title, NeonType.H3)
                Box(Modifier.width(10.dp))
                StateDot(row.indicator, 8.dp)
                Box(Modifier.width(5.dp))
                Txt(row.status, NeonType.Body, color = if (row.indicator == Indicator.NEUTRAL || row.indicator == Indicator.BUSY) Neon.Dim else c)
            }
            if (row.detail.isNotBlank()) Txt(row.detail, NeonType.Small, maxLines = 2)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val action = row.actionLabel
            val mod = if (focus != null) Modifier.focusRequester(focus) else Modifier
            when {
                action != null -> SecondaryButton(action, { onOk(row.onOk) }, mod, height = 34.dp, selected = true)
                extra != null -> SecondaryButton(extra.first, { onOk(extra.second) }, mod, height = 34.dp)
                else -> SecondaryButton("Comprobar", { onOk(RowAction.REFRESH) }, mod, height = 34.dp)
            }
        }
    }
}

@Composable
fun HelpDialog(topic: HelpTopic, onRecheck: () -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    val (title, text) = when (topic) {
        HelpTopic.ACE_INSTALL -> "Cómo instalar Ace Stream" to QuickServiceText.ACE_INSTALL_HELP
        HelpTopic.WARP_INSTALL -> "Cómo instalar la VPN / WARP" to QuickServiceText.WARP_INSTALL_HELP
        HelpTopic.WARP_SIGNATURE -> "La VPN no permite el control" to QuickServiceText.WARP_SIGNATURE_HELP
    }
    TvDialog(title = title, onDismiss = onDismiss, width = 520.dp, initialFocus = first) {
        Txt(text, NeonType.Body, color = Neon.Dim, maxLines = 6)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Entendido", onDismiss, Modifier.focusRequester(first), selected = true)
            SecondaryButton("Comprobar de nuevo", { onRecheck(); onDismiss() }, icon = R.drawable.ic_refresh)
        }
    }
}

/**
 * "Preparando reproducción…": Ace y VPN en un único panel (sin cascada de diálogos). Solo cambia a una
 * pregunta cuando hace falta una persona; al resolverla, el contenido se abre solo.
 */
@Composable
fun PreparingDialog(
    prep: PrepUi,
    onCancel: () -> Unit,
    onAuthorize: () -> Unit,
    onRetry: () -> Unit,
    onOpenAnyway: () -> Unit,
    onInstallHelp: () -> Unit
) {
    val first = remember { FocusRequester() }
    val problem = prep.problem
    val what = prep.request.eventTitle ?: prep.request.channel.name
    if (problem == null) {
        TvDialog(title = "Preparando reproducción…", subtitle = what, onDismiss = onCancel, width = 460.dp, initialFocus = first) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                prep.ace?.let { StepLine(it, "Iniciando Ace Stream…", "Ace Stream listo") }
                prep.vpn?.let {
                    StepLine(it, if (prep.awaitingAuth) "Esperando tu autorización en Android…" else "Conectando VPN…", "VPN conectada")
                }
            }
            Txt("Se abrirá en cuanto esté listo.", NeonType.Small)
            SecondaryButton("Cancelar", onCancel, Modifier.focusRequester(first))
        }
        return
    }
    val (title, text) = when (problem) {
        PrepProblem.VPN_AUTH -> "Se necesita autorización para usar la VPN" to
            "Android necesita tu permiso antes de conectar la VPN. Al pulsar Autorizar se abrirá la pantalla del sistema; " +
            "al volver, MiVisor continuará con este contenido."
        PrepProblem.ACE_FAILED -> "No se pudo iniciar Ace Stream" to "El motor no ha arrancado. Puedes reintentarlo o abrir el contenido igualmente."
        PrepProblem.VPN_FAILED -> "No se pudo conectar la VPN" to "Este proveedor está configurado para usar la VPN."
        PrepProblem.VPN_SETUP -> "Falta configurar la VPN" to "Abre WARP una vez para completar su configuración y vuelve a MiVisor."
        PrepProblem.VPN_MISSING -> "La VPN / WARP no está disponible" to "Este proveedor está configurado para usar la VPN, pero no se encuentra la app (o no permite el control)."
    }
    TvDialog(title = title, subtitle = what, onDismiss = onCancel, width = 520.dp, initialFocus = first) {
        // Cada pregunta enfoca su acción principal (key: al cambiar de pregunta se vuelve a enfocar).
        key(problem) { InitialFocus(first) }
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                if (problem == PrepProblem.ACE_FAILED) R.drawable.ic_bolt else R.drawable.ic_shield,
                if (problem == PrepProblem.VPN_AUTH || problem == PrepProblem.VPN_SETUP || problem == PrepProblem.VPN_MISSING) Ui.Amber else Neon.Red,
                size = 26.dp
            )
            Box(Modifier.width(12.dp))
            Txt(text, NeonType.Body, color = Neon.Dim, maxLines = 5, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val mod = Modifier.focusRequester(first)
            when (problem) {
                PrepProblem.VPN_AUTH -> PrimaryButton("Autorizar", onAuthorize, mod, icon = R.drawable.ic_shield)
                PrepProblem.ACE_FAILED, PrepProblem.VPN_FAILED -> PrimaryButton("Reintentar", onRetry, mod, icon = R.drawable.ic_refresh)
                PrepProblem.VPN_SETUP -> PrimaryButton("Configurar", onRetry, mod, icon = R.drawable.ic_settings)
                PrepProblem.VPN_MISSING -> PrimaryButton("Cómo instalar", onInstallHelp, mod, icon = R.drawable.ic_info)
            }
            if (problem != PrepProblem.VPN_AUTH) {
                SecondaryButton(if (problem == PrepProblem.ACE_FAILED) "Abrir igualmente" else "Abrir sin VPN", onOpenAnyway)
            }
            SecondaryButton("Cancelar", onCancel)
        }
    }
}

@Composable
private fun StepLine(state: StepState, working: String, done: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            when (state) {
                StepState.DONE -> Icon(R.drawable.ic_play, Ui.Green, size = 16.dp)
                StepState.WORKING -> Spinner(18.dp, Neon.Cyan)
                StepState.WAITING -> Box(Modifier.size(10.dp).background(Ui.Amber, RoundedCornerShape(5.dp)))
            }
        }
        Box(Modifier.width(10.dp))
        Txt(if (state == StepState.DONE) done else working, NeonType.Body, color = if (state == StepState.DONE) Neon.Dim else Neon.Text)
    }
}

/** Resultado de "Comprobar si se reproduce": diferencia "motor abierto" de "stream llegando". */
@Composable
fun ProbeDialog(probe: com.mivisor.app.ProbeUi, onPlay: () -> Unit, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    val result = probe.result
    val color = when (result?.verdict) {
        com.mivisor.app.services.ProbeVerdict.STREAMING -> Ui.Green
        com.mivisor.app.services.ProbeVerdict.PREBUFFERING_ONLY, com.mivisor.app.services.ProbeVerdict.NO_DATA,
        com.mivisor.app.services.ProbeVerdict.PREMIUM_REQUIRED -> Ui.Amber
        null -> Neon.Cyan
        else -> Neon.Red
    }
    TvDialog(title = "Comprobar stream", subtitle = probe.channelName, onDismiss = onDismiss, width = 520.dp, initialFocus = first) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (probe.running) Spinner(20.dp, Neon.Cyan) else Box(Modifier.size(12.dp).background(color, RoundedCornerShape(6.dp)))
            Box(Modifier.width(12.dp))
            Txt(
                when (result?.verdict) {
                    com.mivisor.app.services.ProbeVerdict.STREAMING -> "Se reproduce: llegan datos"
                    com.mivisor.app.services.ProbeVerdict.PREBUFFERING_ONLY -> "Solo prebuffer: aún no llegan datos"
                    com.mivisor.app.services.ProbeVerdict.NO_DATA -> "El motor está abierto, pero no llegan datos"
                    com.mivisor.app.services.ProbeVerdict.ENGINE_ERROR -> "El motor devolvió un error"
                    com.mivisor.app.services.ProbeVerdict.ENGINE_UNREACHABLE -> "El motor no está disponible"
                    com.mivisor.app.services.ProbeVerdict.PREMIUM_REQUIRED -> "Comprobación no disponible sin Premium"
                    null -> "Comprobando…"
                },
                NeonType.H3, color = color, maxLines = 2
            )
        }
        Txt(probe.step, NeonType.Body, color = Neon.Dim, maxLines = 5)
        probe.stat?.let { st ->
            Txt(
                "Estado ${st.status ?: "—"} · ${st.peers} peers · ${st.speedDownKbps} KB/s" + (st.progress?.let { " · prebuffer $it %" } ?: ""),
                NeonType.Small, maxLines = 2
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (result?.verdict == com.mivisor.app.services.ProbeVerdict.STREAMING) PrimaryButton("Ver ahora", onPlay, Modifier.focusRequester(first))
            else if (!probe.running) SecondaryButton("Repetir", onRetry, Modifier.focusRequester(first), icon = R.drawable.ic_refresh, selected = true)
            SecondaryButton(if (probe.running) "Cancelar" else "Cerrar", onDismiss, if (probe.running) Modifier.focusRequester(first) else Modifier)
        }
    }
}
