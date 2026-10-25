package com.mivisor.app.ui.screens.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mivisor.app.ErrorKind
import com.mivisor.app.LibraryUi
import com.mivisor.app.ProviderState
import com.mivisor.app.R
import com.mivisor.app.data.ChannelGroup
import com.mivisor.app.data.ChannelSource
import com.mivisor.app.model.Channel
import com.mivisor.app.ui.components.Icon
import com.mivisor.app.ui.components.KindBadge
import com.mivisor.app.ui.components.TvClickable
import com.mivisor.app.ui.components.Txt
import com.mivisor.app.ui.kit.AutoFocus
import com.mivisor.app.ui.kit.ChannelLogo
import com.mivisor.app.ui.kit.CountBadge
import com.mivisor.app.ui.kit.EmptyPanel
import com.mivisor.app.ui.kit.InfoLine
import com.mivisor.app.ui.kit.PageHeader
import com.mivisor.app.ui.kit.PrimaryButton
import com.mivisor.app.ui.kit.ProviderTag
import com.mivisor.app.ui.kit.QualityTag
import com.mivisor.app.ui.kit.SecondaryButton
import com.mivisor.app.ui.kit.Ui
import com.mivisor.app.ui.neon.LocalAppLayout
import com.mivisor.app.ui.neon.Neon
import com.mivisor.app.ui.neon.NeonCard
import com.mivisor.app.ui.neon.NeonType
import com.mivisor.app.ui.neon.Spinner
import com.mivisor.app.ui.screens.relativeTime

/**
 * Canales organizados por proveedor (antes "categorías"). Un mismo canal de varios proveedores aparece una vez:
 * "DAZN 1 · 3 fuentes". OK: si hay una sola fuente se reproduce; si hay varias, se elige.
 */
@Composable
fun ChannelsScreen(
    library: LibraryUi,
    onSelectProvider: (String?) -> Unit,
    onOpenGroup: (ChannelGroup, String) -> Unit,
    onPlay: (Channel) -> Unit,
    onOptions: (Channel) -> Unit,
    onRefresh: (String?) -> Unit,
    onAddProvider: () -> Unit,
    onOpenCode: () -> Unit,
    onManage: () -> Unit,
    onEditProvider: (ProviderState) -> Unit,
    restoreKey: String?,
    autoFocus: Boolean
) {
    val layout = LocalAppLayout.current
    val providers = library.providers.orEmpty()
    val selected = library.provider(library.selected)
    val groups = remember(library.groups, library.selected) { library.visibleGroups() }
    val firstCard = remember { FocusRequester() }
    val firstProvider = remember { FocusRequester() }
    val restore = remember { FocusRequester() }
    val target = when {
        restoreKey != null && groups.any { "grp:${it.key}" == restoreKey } -> restore
        groups.isNotEmpty() && (autoFocus || library.focusToken > 0) -> firstCard
        else -> firstProvider
    }
    AutoFocus(target, enabled = autoFocus || restoreKey != null, key = restoreKey ?: library.focusToken)

    Column(Modifier.fillMaxSize().padding(start = if (layout.compact) layout.gutter else 6.dp, end = layout.gutter)) {
        PageHeader(
            title = "Canales",
            subtitle = if (selected == null) "Explora todos los canales disponibles organizados por proveedor."
            else "Proveedor: ${selected.name} · ${groups.size} canales",
            meta = if (providers.isEmpty()) null else "${providers.size} ${if (providers.size == 1) "proveedor" else "proveedores"}  ·  ${library.channelTotal} canales" +
                (if (library.refreshing) "  ·  actualizando…" else "")
        ) {
            if (!layout.compact && providers.isNotEmpty()) {
                SecondaryButton(if (library.refreshing) "Actualizando…" else "Actualizar", { onRefresh(library.selected) }, icon = R.drawable.ic_refresh, height = 34.dp)
            }
        }
        if (library.providers == null) {
            Txt("Cargando…", NeonType.Body, color = Neon.Dim)
        } else if (providers.isEmpty()) {
            Welcome(onAddProvider, onOpenCode, firstProvider)
        } else if (layout.compact) {
            ProviderChips(providers, library, onSelectProvider, onAddProvider, firstProvider)
            Content(library, selected, groups, firstCard, restore, restoreKey, onOpenGroup, onPlay, onOptions, onRefresh, onEditProvider, Modifier.weight(1f))
        } else {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProviderColumn(providers, library, onSelectProvider, onAddProvider, onOpenCode, onManage, firstProvider, Modifier.width(178.dp).fillMaxHeight())
                Content(library, selected, groups, firstCard, restore, restoreKey, onOpenGroup, onPlay, onOptions, onRefresh, onEditProvider, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun ProviderColumn(
    providers: List<ProviderState>,
    library: LibraryUi,
    onSelect: (String?) -> Unit,
    onAdd: () -> Unit,
    onOpenCode: () -> Unit,
    onManage: () -> Unit,
    focus: FocusRequester,
    modifier: Modifier
) {
    Column(modifier) {
        Txt("Proveedores", Ui.Section, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item(key = "all") {
                ProviderItem("Todos", library.channelTotal, null, library.selected == null, loading = false, error = false, onClick = { onSelect(null) },
                    modifier = if (library.selected == null) Modifier.focusRequester(focus) else Modifier)
            }
            items(providers, key = { "p_${it.id}" }) { p ->
                ProviderItem(
                    p.name, p.channelCount, p.name, library.selected == p.id, loading = p.loading, error = p.error != null,
                    onClick = { onSelect(p.id) },
                    modifier = if (library.selected == p.id) Modifier.focusRequester(focus) else Modifier,
                    transient = p.transient
                )
            }
            item(key = "add") { ActionItem("Añadir proveedor", R.drawable.ic_add, onAdd) }
        }
        Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionItem("Abrir código", R.drawable.ic_search, onOpenCode)
            ActionItem("Gestionar", R.drawable.ic_edit, onManage)
        }
    }
}

@Composable
private fun ProviderItem(
    label: String,
    count: Int,
    logoName: String?,
    selected: Boolean,
    loading: Boolean,
    error: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    transient: Boolean = false
) {
    TvClickable(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(42.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Neon.PanelHi else Neon.Panel,
        focusedColor = Neon.PanelHi,
        borderColor = if (selected) Neon.Cyan else Neon.Stroke,
        borderWidth = if (selected) 2.dp else 1.dp,
        focusedScale = 1.02f,
        contentAlignment = Alignment.CenterStart
    ) { focused ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (logoName == null) Icon(R.drawable.ic_grid, if (selected || focused) Neon.Cyan else Neon.Dim, size = 22.dp)
            else ChannelLogo(logoName, 24.dp)
            Box(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Txt(label, NeonType.H3.copy(fontSize = 14.sp), color = if (selected || focused) Neon.Text else Neon.Dim)
                if (transient) Txt("Sin guardar", NeonType.Pill, color = Ui.Amber)
            }
            when {
                loading -> Spinner(14.dp, Neon.Cyan)
                error -> Box(Modifier.size(8.dp).background(Neon.Red, RoundedCornerShape(4.dp)))
                else -> Txt(count.toString(), NeonType.Small, color = if (selected) Neon.Text else Neon.Faint)
            }
            Box(Modifier.width(4.dp))
            Icon(R.drawable.ic_chevron_right, if (selected || focused) Neon.Cyan else Neon.Faint, size = 14.dp)
        }
    }
}

@Composable
private fun ActionItem(label: String, icon: Int, onClick: () -> Unit) {
    TvClickable(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(38.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        focusedColor = Neon.PanelHi,
        borderColor = Neon.Stroke,
        contentAlignment = Alignment.CenterStart
    ) { focused ->
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, if (focused) Neon.Cyan else Neon.Dim, size = 16.dp)
            Box(Modifier.width(10.dp))
            Txt(label, NeonType.Body, color = if (focused) Neon.Text else Neon.Dim)
        }
    }
}

@Composable
private fun ProviderChips(
    providers: List<ProviderState>,
    library: LibraryUi,
    onSelect: (String?) -> Unit,
    onAdd: () -> Unit,
    focus: FocusRequester
) {
    LazyRow(contentPadding = PaddingValues(4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "all") { SecondaryButton("Todos", { onSelect(null) }, Modifier.focusRequester(focus), height = 32.dp, selected = library.selected == null) }
        items(providers, key = { it.id }) { p -> SecondaryButton(p.name, { onSelect(p.id) }, height = 32.dp, selected = library.selected == p.id) }
        item(key = "add") { SecondaryButton("Añadir", onAdd, icon = R.drawable.ic_add, height = 32.dp) }
    }
}

@Composable
private fun Content(
    library: LibraryUi,
    selected: ProviderState?,
    groups: List<ChannelGroup>,
    firstCard: FocusRequester,
    restore: FocusRequester,
    restoreKey: String?,
    onOpenGroup: (ChannelGroup, String) -> Unit,
    onPlay: (Channel) -> Unit,
    onOptions: (Channel) -> Unit,
    onRefresh: (String?) -> Unit,
    onEditProvider: (ProviderState) -> Unit,
    modifier: Modifier
) {
    val layout = LocalAppLayout.current
    Box(modifier.fillMaxWidth()) {
        when {
            selected?.error != null && selected.catalog == null -> EmptyPanel(selected.error.title, selected.error.detail, danger = selected.error.kind != ErrorKind.OFFLINE) {
                SecondaryButton("Reintentar", { onRefresh(selected.id) }, Modifier.focusRequester(firstCard), icon = R.drawable.ic_refresh, selected = true)
                if (!selected.transient) SecondaryButton("Editar proveedor", { onEditProvider(selected) }, icon = R.drawable.ic_edit)
            }
            groups.isEmpty() && (selected?.loading == true || library.providers.orEmpty().all { it.loading }) ->
                Txt("Cargando canales…", NeonType.Body, color = Neon.Dim, modifier = Modifier.padding(8.dp))
            groups.isEmpty() -> EmptyPanel("Sin canales", "Este proveedor no contiene enlaces reproducibles.", icon = R.drawable.ic_tv) {
                SecondaryButton("Actualizar", { onRefresh(library.selected) }, Modifier.focusRequester(firstCard), icon = R.drawable.ic_refresh)
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(if (layout.compact) 140.dp else 150.dp),
                contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(groups, key = { "grp:${it.key}" }) { group ->
                    val key = "grp:${group.key}"
                    val mod = when {
                        key == restoreKey -> Modifier.focusRequester(restore)
                        group === groups.first() -> Modifier.focusRequester(firstCard)
                        else -> Modifier
                    }
                    GroupCard(
                        group, perProvider = library.selected != null,
                        onClick = { if (group.sources.size == 1) onPlay(group.first.channel) else onOpenGroup(group, key) },
                        onLongClick = { onOptions(group.first.channel) },
                        modifier = mod
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupCard(group: ChannelGroup, perProvider: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier) {
    TvClickable(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().height(92.dp),
        shape = RoundedCornerShape(14.dp),
        color = Neon.Panel,
        focusedColor = Neon.PanelHi,
        borderColor = Neon.Stroke,
        focusedScale = 1.03f
    ) { _ ->
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                ChannelLogo(group.name, 32.dp, tint = group.tint)
                Box(Modifier.weight(1f))
                val n = group.sources.size
                if (n > 1) CountBadge(if (perProvider) "$n canales" else "$n fuentes")
            }
            Txt(group.name, Ui.CardTitle.copy(fontSize = 14.sp), modifier = Modifier.padding(top = 6.dp))
            Box(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                if (perProvider) {
                    ProviderTag(group.first.providerName)
                    QualityTag(group.bestQuality)
                } else {
                    val names = group.providers
                    names.take(2).forEach { ProviderTag(it) }
                    if (names.size > 2) Txt("+${names.size - 2}", NeonType.Pill, color = Neon.Faint)
                }
            }
        }
    }
}

@Composable
private fun Welcome(onAdd: () -> Unit, onOpenCode: () -> Unit, focus: FocusRequester) {
    EmptyPanel(
        "Añade tu primer proveedor",
        "Cada proveedor es un código que trae una lista de canales. Los canales que se repiten entre proveedores " +
            "se agrupan, y el Calendario los usa para decirte dónde ver cada partido.",
        icon = R.drawable.ic_tv
    ) {
        PrimaryButton("Añadir proveedor", onAdd, Modifier.focusRequester(focus), icon = R.drawable.ic_add)
        SecondaryButton("Solo abrir un código", onOpenCode, icon = R.drawable.ic_search)
    }
    AutoFocus(focus)
}

// ============================================================================ canal agrupado

/** "DAZN 1": sus fuentes (una por proveedor/enlace). Toda la fila es el control: OK reproduce. */
@Composable
fun ChannelGroupScreen(
    group: ChannelGroup,
    library: LibraryUi,
    backLabel: String,
    onBack: () -> Unit,
    onPlay: (Channel) -> Unit,
    onOptions: (Channel) -> Unit
) {
    val layout = LocalAppLayout.current
    val first = remember { FocusRequester() }
    AutoFocus(first, key = group.key)
    val updated = group.sources.mapNotNull { s -> library.provider(s.providerId)?.updatedAt?.takeIf { it > 0 } }.maxOrNull()

    Row(Modifier.fillMaxSize().padding(start = if (layout.compact) layout.gutter else 6.dp, end = layout.gutter), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            PageHeader(group.name, "${group.sources.size} fuentes disponibles para este canal.", back = backLabel to onBack)
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(group.sources, key = { i, s -> "${s.channel.id}_$i" }) { index, source ->
                    SourceRow(source, { onPlay(source.channel) }, { onOptions(source.channel) }, if (index == 0) Modifier.focusRequester(first) else Modifier)
                }
            }
        }
        if (layout.expanded) {
            Column(
                Modifier.width(230.dp).fillMaxHeight().padding(top = 12.dp, bottom = 10.dp)
                    .background(Neon.Panel.copy(alpha = 0.9f), RoundedCornerShape(16.dp)).border(1.dp, Neon.Stroke, RoundedCornerShape(16.dp))
                    .padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ChannelLogo(group.name, 72.dp, tint = group.tint)
                Txt(group.name, NeonType.H2)
                Row(
                    Modifier.background(Neon.PanelHi, RoundedCornerShape(50)).border(1.dp, Neon.Stroke, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(R.drawable.ic_link, Neon.Dim, size = 14.dp)
                    Box(Modifier.width(6.dp))
                    Txt("Canal agrupado", NeonType.Small)
                }
                Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoLine(R.drawable.ic_layers, "Fuentes", "${group.sources.size} disponibles")
                    InfoLine(R.drawable.ic_tv, "Mejor calidad", group.bestQuality?.label ?: "—")
                    InfoLine(R.drawable.ic_clock, "Actualizado", updated?.let { relativeTime(it) } ?: "—")
                }
                Box(Modifier.weight(1f))
                SecondaryButton("Volver a canales", onBack, Modifier.fillMaxWidth(), icon = R.drawable.ic_chevron_left)
            }
        }
    }
}

@Composable
private fun SourceRow(source: ChannelSource, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier) {
    TvClickable(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().height(64.dp),
        shape = RoundedCornerShape(14.dp),
        color = Neon.Panel,
        focusedColor = Neon.PanelHi,
        borderColor = Neon.Stroke,
        focusedScale = 1.015f,
        contentAlignment = Alignment.CenterStart
    ) { focused ->
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(source.providerName, 42.dp)
            Box(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt(source.channel.name, NeonType.H3.copy(fontSize = 15.sp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProviderTag(source.providerName)
                    QualityTag(source.quality)
                    KindBadge(source.channel.kind, focused)
                    source.channel.group?.let { Txt(it, NeonType.Pill, color = Neon.Faint) }
                }
            }
            Row(
                Modifier.height(36.dp).background(if (focused) Neon.Cyan else Neon.PanelHi, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(R.drawable.ic_play, if (focused) Neon.Bg else Neon.Dim, size = 14.dp)
                Box(Modifier.width(8.dp))
                Txt("Ver canal", NeonType.Body.copy(fontWeight = FontWeight.Bold), color = if (focused) Neon.Bg else Neon.Dim)
            }
        }
    }
}

