package com.mivisor.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mivisor.app.R
import com.mivisor.app.model.PlaybackEntry
import com.mivisor.app.model.SourceProfile
import com.mivisor.app.ui.components.ConfirmDialog
import com.mivisor.app.ui.components.Icon
import com.mivisor.app.ui.components.TvClickable
import com.mivisor.app.ui.components.Txt
import com.mivisor.app.ui.kit.AutoFocus
import com.mivisor.app.ui.kit.ChannelLogo
import com.mivisor.app.ui.kit.EmptyPanel
import com.mivisor.app.ui.kit.MetaTag
import com.mivisor.app.ui.kit.PageHeader
import com.mivisor.app.ui.kit.ProviderTag
import com.mivisor.app.ui.kit.SecondaryButton
import com.mivisor.app.ui.kit.Ui
import com.mivisor.app.ui.neon.LocalAppLayout
import com.mivisor.app.ui.neon.Neon
import com.mivisor.app.ui.neon.NeonType

/** Columna estándar de pantallas sencillas (Historial, Gestionar…). */
@Composable
fun ScreenScaffold(
    title: String,
    subtitle: String?,
    back: Pair<String, () -> Unit>? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit
) {
    val layout = LocalAppLayout.current
    Column(Modifier.fillMaxSize().padding(start = if (layout.compact) layout.gutter else 6.dp, end = layout.gutter)) {
        PageHeader(title = title, subtitle = subtitle, back = back, actions = actions)
        content()
    }
}

private enum class HistoryTab(val label: String) { RECENT("Recientes"), EVENTS("Partidos"), CHANNELS("Canales") }

/** Historial: qué se vio, por qué canal, de qué competición y cuándo. OK lo vuelve a abrir. */
@Composable
fun HistoryScreen(
    history: List<PlaybackEntry>,
    onOpen: (PlaybackEntry) -> Unit,
    onDelete: (PlaybackEntry) -> Unit,
    onClear: () -> Unit,
    autoFocus: Boolean
) {
    var tab by remember { mutableStateOf(HistoryTab.RECENT) }
    var confirmClear by remember { mutableStateOf(false) }
    val list = when (tab) {
        HistoryTab.RECENT -> history
        HistoryTab.EVENTS -> history.filter { it.isEvent }
        HistoryTab.CHANNELS -> history.filterNot { it.isEvent }
    }
    val first = remember { FocusRequester() }
    val firstTab = remember { FocusRequester() }
    AutoFocus(if (list.isNotEmpty()) first else firstTab, enabled = autoFocus)

    ScreenScaffold(
        title = "Historial",
        subtitle = "Vuelve rápidamente a lo último que has visto.",
        actions = { if (history.isNotEmpty()) SecondaryButton("Borrar todo", { confirmClear = true }, icon = R.drawable.ic_delete, height = 34.dp) }
    ) {
        LazyRow(contentPadding = PaddingValues(horizontal = 4.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(HistoryTab.values().toList(), key = { it.name }) { t ->
                SecondaryButton(t.label, { tab = t }, if (t == HistoryTab.RECENT) Modifier.focusRequester(firstTab) else Modifier, height = 32.dp, selected = tab == t)
            }
        }
        if (list.isEmpty()) {
            EmptyPanel(
                "Nada por aquí todavía",
                "Cuando reproduzcas un partido o un canal aparecerá aquí para volver a él con un OK.",
                Modifier.padding(top = 8.dp), icon = R.drawable.ic_history
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(list, key = { i, e -> "${e.url}|${e.eventId}|$i" }) { index, entry ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HistoryRow(entry, { onOpen(entry) }, if (index == 0) Modifier.focusRequester(first) else Modifier, Modifier.weight(1f))
                        SecondaryButton("", { onDelete(entry) }, icon = R.drawable.ic_delete, height = 40.dp)
                    }
                }
            }
        }
    }
    if (confirmClear) {
        ConfirmDialog(
            title = "¿Borrar todo el historial?",
            message = "Tus proveedores y canales no se borran.",
            confirmLabel = "Borrar",
            onConfirm = { confirmClear = false; onClear() },
            onDismiss = { confirmClear = false }
        )
    }
}

@Composable
private fun HistoryRow(entry: PlaybackEntry, onClick: () -> Unit, focus: Modifier, modifier: Modifier) {
    TvClickable(
        onClick = onClick,
        modifier = modifier.then(focus).height(58.dp),
        shape = RoundedCornerShape(14.dp),
        color = Neon.Panel,
        focusedColor = Neon.PanelHi,
        borderColor = Neon.Stroke,
        focusedScale = 1.015f,
        contentAlignment = Alignment.CenterStart
    ) { focused ->
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(entry.channelName.ifBlank { entry.title }, 38.dp)
            Box(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Txt(entry.title, NeonType.H3.copy(fontSize = 15.sp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (entry.isEvent) Txt(entry.channelName, NeonType.Small)
                    entry.competition?.let { MetaTag(it) }
                    entry.provider?.let { ProviderTag(it) }
                }
            }
            Txt("Visto ${relativeTime(entry.at)}", NeonType.Small, color = if (focused) Neon.Text else Neon.Dim)
            Box(Modifier.width(8.dp))
            Icon(R.drawable.ic_play, if (focused) Neon.Cyan else Neon.Faint, size = 16.dp)
        }
    }
}

/** Gestionar proveedores: ordenar, editar, eliminar y decidir si su contenido necesita la VPN. */
@Composable
fun SourcesScreen(
    sources: List<SourceProfile>,
    vpnFor: (String) -> Boolean,
    onToggleVpn: (SourceProfile) -> Unit,
    onOpen: (SourceProfile) -> Unit,
    onEdit: (SourceProfile) -> Unit,
    onMove: (SourceProfile, Int) -> Unit,
    onDelete: (SourceProfile) -> Unit,
    onAdd: () -> Unit,
    onBack: () -> Unit
) {
    var pendingDelete by remember { mutableStateOf<SourceProfile?>(null) }
    val first = remember { FocusRequester() }
    AutoFocus(first)
    ScreenScaffold(
        title = "Proveedores",
        subtitle = "Ordena, renombra o elimina tus proveedores. Marca «VPN» si su contenido debe abrirse con la VPN conectada.",
        back = "Canales" to onBack,
        actions = { SecondaryButton("Añadir proveedor", onAdd, if (sources.isEmpty()) Modifier.focusRequester(first) else Modifier, icon = R.drawable.ic_add, height = 34.dp, selected = true) }
    ) {
        if (sources.isEmpty()) {
            EmptyPanel("No hay proveedores", "Añade uno con su código.", icon = R.drawable.ic_tv)
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 24.dp)
            ) {
                itemsIndexed(sources, key = { _, s -> s.id }) { index, source ->
                    val vpn = vpnFor(source.id)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvClickable(
                            onClick = { onOpen(source) },
                            modifier = Modifier.weight(1f).height(52.dp).let { if (index == 0) it.focusRequester(first) else it },
                            shape = RoundedCornerShape(14.dp),
                            color = Neon.Panel,
                            focusedColor = Neon.PanelHi,
                            borderColor = Neon.Stroke,
                            contentAlignment = Alignment.CenterStart
                        ) { focused ->
                            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                ChannelLogo(source.name, 32.dp)
                                Box(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Txt(source.name, NeonType.H3, color = if (focused) Neon.Text else Neon.Dim)
                                    Txt(source.code, NeonType.Small)
                                }
                                if (vpn) MetaTag("VPN", dot = Ui.Green)
                            }
                        }
                        SecondaryButton(if (vpn) "VPN: sí" else "VPN: no", { onToggleVpn(source) }, icon = R.drawable.ic_shield, height = 40.dp, selected = vpn)
                        SecondaryButton("", { onMove(source, -1) }, icon = R.drawable.ic_up, height = 40.dp)
                        SecondaryButton("", { onMove(source, 1) }, icon = R.drawable.ic_down, height = 40.dp)
                        SecondaryButton("", { onEdit(source) }, icon = R.drawable.ic_edit, height = 40.dp)
                        SecondaryButton("", { pendingDelete = source }, icon = R.drawable.ic_delete, height = 40.dp)
                    }
                }
            }
        }
    }
    pendingDelete?.let { source ->
        ConfirmDialog(
            title = "¿Eliminar «${source.name}»?",
            message = "Desaparecerán sus canales. Podrás volver a añadirlo con su código (${source.code}).",
            confirmLabel = "Eliminar",
            onConfirm = { pendingDelete = null; onDelete(source) },
            onDismiss = { pendingDelete = null }
        )
    }
}
