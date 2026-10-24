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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mivisor.app.ErrorKind
import com.mivisor.app.HomeUiState
import com.mivisor.app.R
import com.mivisor.app.model.Channel
import com.mivisor.app.model.SourceProfile
import com.mivisor.app.ui.components.Icon
import com.mivisor.app.ui.components.KindBadge
import com.mivisor.app.ui.components.Monogram
import com.mivisor.app.ui.components.Txt
import com.mivisor.app.ui.neon.FilterChip
import com.mivisor.app.ui.neon.LocalAppLayout
import com.mivisor.app.ui.neon.Neon
import com.mivisor.app.ui.neon.NeonButton
import com.mivisor.app.ui.neon.NeonCard
import com.mivisor.app.ui.neon.NeonType
import com.mivisor.app.ui.neon.Overline
import com.mivisor.app.ui.neon.ScreenHero
import com.mivisor.app.ui.neon.SideItem
import com.mivisor.app.ui.neon.QuickServicesSide
import com.mivisor.app.ui.neon.QuickServiceChip
import com.mivisor.app.QuickServicesUi
import com.mivisor.app.services.RowAction
import com.mivisor.app.ui.neon.neonPanel

/**
 * "Canales": cabecera con la categoría abierta, categorías (columna en TV/tablet,
 * chips en móvil) y filas de canales por grupo.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CatalogScreen(
    state: HomeUiState,
    sources: List<SourceProfile>,
    categoryFocus: FocusRequester,
    onSelectSource: (SourceProfile) -> Unit,
    onRefresh: () -> Unit,
    onOpen: (Channel) -> Unit,
    onOptions: (Channel) -> Unit,
    onAddSource: () -> Unit,
    onOpenCode: () -> Unit,
    onManage: () -> Unit,
    services: QuickServicesUi? = null,
    onServiceOk: (RowAction) -> Unit = {}
) {
    val layout = LocalAppLayout.current
    val firstCard = remember { FocusRequester() }
    val primaryAction = remember { FocusRequester() }
    var handledToken by remember { mutableIntStateOf(0) }
    var channelsFocused by remember { mutableStateOf(false) }
    val catalog = state.catalog

    // Tras una carga pedida por el usuario, el foco salta al primer canal (o a "Reintentar").
    LaunchedEffect(state.focusToken, state.loading, catalog, state.error) {
        if (state.focusToken == handledToken || state.loading) return@LaunchedEffect
        val target = when {
            catalog != null && !catalog.isEmpty -> firstCard
            state.error != null -> primaryAction
            else -> return@LaunchedEffect
        }
        handledToken = state.focusToken
        repeat(3) {
            withFrameNanos { }
            if (runCatching { target.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    // Atrás desde los canales → columna de categorías (en TV); desde ahí, salir.
    BackHandler(enabled = channelsFocused && !layout.compact && sources.isNotEmpty()) {
        runCatching { categoryFocus.requestFocus() }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = layout.gutter)) {
        ScreenHero(
            title = state.title.ifBlank { if (sources.isEmpty()) "Bienvenido" else "Tus canales" },
            subtitle = if (state.code.isEmpty()) "Elige una categoría o añade un código nuevo." else null,
            status = statusLine(state),
            statusColor = if (state.offline || state.failedRefs > 0) com.mivisor.app.ui.theme.Palette.Warning else Neon.Faint
        ) {
            if (state.code.isNotEmpty() && !layout.compact) {
                NeonButton(if (state.refreshing) "Actualizando…" else "Actualizar", onRefresh, icon = R.drawable.ic_refresh)
            }
        }

        if (layout.compact) {
            CategoryChips(sources, state.activeSourceId, categoryFocus, onSelectSource, onAddSource, onOpenCode, onManage, services, onServiceOk)
            Content(
                state, sources.isNotEmpty(), firstCard, primaryAction, onRefresh, onOpen, onOptions, onAddSource, onOpenCode,
                cardWidth = 150.dp, cardHeight = 96.dp,
                modifier = Modifier.weight(1f).onFocusChanged { channelsFocused = it.hasFocus }
            )
        } else {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CategoryColumn(
                    sources, state.activeSourceId, categoryFocus, onSelectSource, onAddSource, onOpenCode, onManage,
                    Modifier.width(layout.sideWidth).fillMaxHeight(), services, onServiceOk
                )
                Content(
                    state, sources.isNotEmpty(), firstCard, primaryAction, onRefresh, onOpen, onOptions, onAddSource, onOpenCode,
                    cardWidth = if (layout.expanded) 196.dp else 176.dp, cardHeight = if (layout.expanded) 110.dp else 104.dp,
                    modifier = Modifier.weight(1f).fillMaxHeight().onFocusChanged { channelsFocused = it.hasFocus }
                )
            }
        }
    }
}

private fun statusLine(state: HomeUiState): String {
    val catalog = state.catalog
    if (state.code.isEmpty()) return ""
    val parts = mutableListOf(state.code)
    if (catalog != null && !state.loading) {
        parts += "${catalog.channelCount} canales · ${catalog.sections.size} ${if (catalog.sections.size == 1) "grupo" else "grupos"}"
    }
    if (state.updatedAt > 0 && !state.loading) {
        parts += when {
            state.refreshing -> "actualizando…"
            state.offline -> "sin conexión · copia de ${relativeTime(state.updatedAt)}"
            else -> "actualizado ${relativeTime(state.updatedAt)}"
        }
    }
    if (state.failedRefs > 0 && !state.loading) {
        parts += if (state.failedRefs == 1) "1 subcategoría no disponible" else "${state.failedRefs} subcategorías no disponibles"
    }
    return parts.joinToString("  ·  ")
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CategoryColumn(
    sources: List<SourceProfile>,
    activeId: String?,
    focus: FocusRequester,
    onSelect: (SourceProfile) -> Unit,
    onAdd: () -> Unit,
    onOpenCode: () -> Unit,
    onManage: () -> Unit,
    modifier: Modifier,
    services: QuickServicesUi?,
    onServiceOk: (RowAction) -> Unit
) {
    val focusTarget = activeId ?: sources.firstOrNull()?.id
    Column(modifier) {
        // Servicios rápidos ANTES de las categorías: Ace Stream → VPN/WARP → primera categoría (D-pad ↑↓).
        if (services != null) QuickServicesSide(services, onServiceOk)
        Overline("Categorías", Modifier.padding(start = 8.dp, bottom = 4.dp))
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().focusRestorer(),
            contentPadding = PaddingValues(6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            items(sources, key = { it.id }) { source ->
                SideItem(
                    label = source.name,
                    icon = null,
                    selected = source.id == activeId,
                    onClick = { onSelect(source) },
                    modifier = if (source.id == focusTarget) Modifier.focusRequester(focus) else Modifier
                )
            }
            item(key = "__add") {
                SideItem(
                    "Añadir categoría", R.drawable.ic_add, false, onAdd,
                    modifier = if (focusTarget == null) Modifier.focusRequester(focus) else Modifier
                )
            }
        }
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            SideItem("Abrir código", R.drawable.ic_search, false, onOpenCode)
            SideItem("Gestionar", R.drawable.ic_edit, false, onManage)
        }
    }
}

@Composable
private fun CategoryChips(
    sources: List<SourceProfile>,
    activeId: String?,
    focus: FocusRequester,
    onSelect: (SourceProfile) -> Unit,
    onAdd: () -> Unit,
    onOpenCode: () -> Unit,
    onManage: () -> Unit,
    services: QuickServicesUi?,
    onServiceOk: (RowAction) -> Unit
) {
    val focusTarget = activeId ?: sources.firstOrNull()?.id
    LazyRow(contentPadding = PaddingValues(6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (services != null) {
            item(key = "__ace") { QuickServiceChip(services.ace, onServiceOk) }
            item(key = "__warp") { QuickServiceChip(services.warp, onServiceOk) }
        }
        items(sources, key = { it.id }) { source ->
            FilterChip(
                source.name, source.id == activeId, { onSelect(source) },
                modifier = if (source.id == focusTarget) Modifier.focusRequester(focus) else Modifier
            )
        }
        item(key = "__add") { FilterChip("Añadir", false, onAdd, icon = R.drawable.ic_add) }
        item(key = "__code") { FilterChip("Código", false, onOpenCode, icon = R.drawable.ic_search) }
        item(key = "__manage") { FilterChip("Gestionar", false, onManage, icon = R.drawable.ic_edit) }
    }
}

@Composable
private fun Content(
    state: HomeUiState,
    hasSources: Boolean,
    firstCard: FocusRequester,
    primaryAction: FocusRequester,
    onRefresh: () -> Unit,
    onOpen: (Channel) -> Unit,
    onOptions: (Channel) -> Unit,
    onAddSource: () -> Unit,
    onOpenCode: () -> Unit,
    cardWidth: Dp,
    cardHeight: Dp,
    modifier: Modifier
) {
    val catalog = state.catalog
    Box(modifier.fillMaxWidth()) {
        when {
            state.code.isEmpty() && !hasSources -> Welcome(onAddSource, onOpenCode, primaryAction)
            state.code.isEmpty() -> Panel("Elige una categoría", "Usa la lista de categorías para abrir tus canales.") {}
            state.loading -> LoadingSkeleton(cardWidth, cardHeight)
            state.error != null -> Panel(state.error.title, state.error.detail, danger = state.error.kind != ErrorKind.OFFLINE) {
                NeonButton("Reintentar", onRefresh, Modifier.focusRequester(primaryAction), icon = R.drawable.ic_refresh, selected = true)
                NeonButton("Abrir otro código", onOpenCode, icon = R.drawable.ic_search)
            }
            catalog == null || catalog.isEmpty -> Panel("Sin canales", "Este código no contiene enlaces reproducibles.") {
                NeonButton("Actualizar", onRefresh, Modifier.focusRequester(primaryAction), icon = R.drawable.ic_refresh)
            }
            else -> SectionList(catalog.sections, firstCard, onOpen, onOptions, cardWidth, cardHeight)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SectionList(
    sections: List<com.mivisor.app.model.Section>,
    firstCard: FocusRequester,
    onOpen: (Channel) -> Unit,
    onOptions: (Channel) -> Unit,
    cardWidth: Dp,
    cardHeight: Dp
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 2.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        itemsIndexed(sections, key = { _, s -> s.id }, contentType = { _, _ -> "section" }) { sectionIndex, section ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(section.title, NeonType.H2, modifier = Modifier.weight(1f, fill = false))
                    Txt("  ${section.channels.size}", NeonType.Small, color = Neon.Faint)
                }
                LazyRow(
                    // focusRestorer: al volver a esta fila el foco cae en el último canal visitado.
                    modifier = Modifier.fillMaxWidth().focusRestorer(),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(section.channels, key = { it.id }, contentType = { "channel" }) { channel ->
                        ChannelCard(
                            channel, onOpen, onOptions, cardWidth, cardHeight,
                            if (sectionIndex == 0 && channel === section.channels[0]) Modifier.focusRequester(firstCard) else Modifier
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelCard(
    channel: Channel,
    onOpen: (Channel) -> Unit,
    onOptions: (Channel) -> Unit,
    width: Dp,
    height: Dp,
    modifier: Modifier
) {
    com.mivisor.app.ui.components.TvClickable(
        onClick = { onOpen(channel) },
        onLongClick = { onOptions(channel) },
        modifier = modifier.width(width).height(height),
        shape = RoundedCornerShape(14.dp),
        color = Neon.Panel,
        focusedColor = Neon.PanelHi,
        borderColor = Neon.Stroke,
        focusedScale = 1.06f
    ) { focused ->
        // Brillo violeta sutil arriba a la derecha (como las tarjetas de la agenda).
        Box(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(listOf(Neon.Panel, Neon.Violet.copy(alpha = if (focused) 0.35f else 0.18f))),
                RoundedCornerShape(14.dp)
            )
        )
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Monogram(channel.initials, channel.tint, size = if (height < 100.dp) 30.dp else 36.dp)
                Box(Modifier.weight(1f))
                KindBadge(channel.kind, focused)
            }
            Box(Modifier.weight(1f))
            Txt(channel.name, NeonType.H3.copy(fontSize = if (height < 100.dp) 14.sp else 15.sp), maxLines = 2)
        }
    }
}

@Composable
private fun LoadingSkeleton(cardWidth: Dp, cardHeight: Dp) {
    // Estático a propósito: un shimmer animado consume GPU en TVs modestas.
    Column(Modifier.fillMaxSize().padding(start = 6.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        repeat(3) { row ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.width(if (row == 0) 180.dp else 140.dp).height(18.dp).background(Neon.Panel, RoundedCornerShape(6.dp)))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(5) {
                        Box(
                            Modifier.width(cardWidth).height(cardHeight)
                                .background(Neon.Panel.copy(alpha = 0.8f - row * 0.2f), RoundedCornerShape(14.dp))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Welcome(onAdd: () -> Unit, onOpenCode: () -> Unit, focus: FocusRequester) {
    Column(
        Modifier.padding(6.dp).widthIn(max = 640.dp).neonPanel(18.dp).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Txt("Añade tu primera categoría", NeonType.H2)
        Txt(
            "Cada categoría es un código que trae una lista de canales. Aparecerán aquí, " +
                "y la Agenda deportiva los usará para ofrecerte dónde ver cada partido.",
            NeonType.Body, color = Neon.Dim, maxLines = 4
        )
        Step("1", "Pulsa «Añadir categoría» y escribe un nombre y el código.")
        Step("2", "Elige un canal y pulsa OK (o tócalo) para reproducirlo.")
        Step("3", "Mantén pulsado un canal para más opciones.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NeonButton("Añadir categoría", onAdd, Modifier.focusRequester(focus), icon = R.drawable.ic_add, selected = true)
            NeonButton("Solo abrir un código", onOpenCode, icon = R.drawable.ic_search)
        }
    }
    LaunchedEffect(Unit) {
        repeat(3) {
            withFrameNanos { }
            if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
}

@Composable
private fun Step(number: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(26.dp).background(Brush.linearGradient(listOf(Neon.Violet, Neon.Blue)), RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center
        ) { Txt(number, NeonType.Pill.copy(color = Neon.Text)) }
        Txt(text, NeonType.Body, maxLines = 2)
    }
}

@Composable
private fun Panel(title: String, detail: String, danger: Boolean = false, actions: @Composable () -> Unit) {
    Column(
        Modifier.padding(6.dp).widthIn(max = 640.dp).fillMaxWidth()
            .neonPanel(18.dp, highlight = if (danger) Neon.Red else Neon.Violet).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(if (danger) R.drawable.ic_search else R.drawable.ic_list, if (danger) Neon.Red else Neon.Cyan, size = 28.dp)
        Txt(title, NeonType.H2, maxLines = 2)
        Txt(detail, NeonType.Body, color = Neon.Dim, maxLines = 4)
        Box(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { actions() }
    }
}

fun relativeTime(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = ((now - timestamp) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> "ahora mismo"
        minutes < 60 -> "hace $minutes min"
        minutes < 60 * 24 -> "hace ${minutes / 60} h"
        else -> "hace ${minutes / (60 * 24)} d"
    }
}
