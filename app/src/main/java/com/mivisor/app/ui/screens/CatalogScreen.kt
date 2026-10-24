package com.mivisor.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import com.mivisor.app.ErrorKind
import com.mivisor.app.HomeUiState
import com.mivisor.app.R
import com.mivisor.app.model.Channel
import com.mivisor.app.model.Section
import com.mivisor.app.ui.components.ButtonStyle
import com.mivisor.app.ui.components.KindBadge
import com.mivisor.app.ui.components.MessagePanel
import com.mivisor.app.ui.components.Monogram
import com.mivisor.app.ui.components.TvButton
import com.mivisor.app.ui.components.TvClickable
import com.mivisor.app.ui.components.Txt
import com.mivisor.app.ui.theme.Dimens
import com.mivisor.app.ui.theme.Palette
import com.mivisor.app.ui.theme.Type

@Composable
fun CatalogScreen(
    state: HomeUiState,
    hasSources: Boolean,
    onRefresh: () -> Unit,
    onOpen: (Channel) -> Unit,
    onOptions: (Channel) -> Unit,
    onAddSource: () -> Unit,
    onOpenCode: () -> Unit
) {
    val firstCard = remember { FocusRequester() }
    val primaryAction = remember { FocusRequester() }
    var handledToken by remember { mutableIntStateOf(0) }
    val catalog = state.catalog

    // Tras una carga pedida por el usuario, el foco salta al primer canal (o al botón de
    // reintentar si hubo error). Así "elegir categoría → OK → OK" ya reproduce.
    val settled = !state.loading
    LaunchedEffect(state.focusToken, settled, catalog, state.error) {
        if (state.focusToken == handledToken || !settled) return@LaunchedEffect
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

    Column(Modifier.fillMaxSize().padding(top = Dimens.SafeV)) {
        if (state.code.isNotEmpty()) {
            Header(state, onRefresh)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.code.isEmpty() -> Centered {
                    if (hasSources) {
                        MessagePanel(
                            "Elige una categoría",
                            "Usa el menú de la izquierda para abrir una de tus categorías.",
                            icon = R.drawable.ic_list
                        )
                    } else {
                        WelcomePanel(onAddSource, onOpenCode, primaryAction)
                    }
                }
                state.loading -> LoadingSkeleton()
                state.error != null -> Centered {
                    MessagePanel(
                        title = state.error.title,
                        detail = state.error.detail,
                        icon = if (state.error.kind == ErrorKind.OFFLINE) R.drawable.ic_refresh else R.drawable.ic_search,
                        danger = state.error.kind != ErrorKind.OFFLINE
                    ) {
                        TvButton("Reintentar", onRefresh, Modifier.focusRequester(primaryAction), icon = R.drawable.ic_refresh, style = ButtonStyle.PRIMARY)
                        TvButton("Abrir otro código", onOpenCode, icon = R.drawable.ic_search)
                    }
                }
                catalog == null || catalog.isEmpty -> Centered {
                    MessagePanel(
                        "Sin canales",
                        "Este código no contiene enlaces reproducibles.",
                        icon = R.drawable.ic_list
                    ) {
                        TvButton("Actualizar", onRefresh, Modifier.focusRequester(primaryAction), icon = R.drawable.ic_refresh)
                    }
                }
                else -> SectionList(catalog.sections, firstCard, onOpen, onOptions)
            }
        }
        if (catalog != null && !catalog.isEmpty && !state.loading) HintBar()
    }
}

@Composable
private fun Header(state: HomeUiState, onRefresh: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 32.dp, end = Dimens.SafeH, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Txt(state.title.ifBlank { state.code }, Type.Display)
            StatusLine(state)
        }
        TvButton(
            label = if (state.refreshing) "Actualizando…" else "Actualizar",
            onClick = onRefresh,
            icon = R.drawable.ic_refresh
            // Nunca se deshabilita: un botón que se desactiva teniendo el foco lo "pierde" en TV.
        )
    }
}

@Composable
private fun StatusLine(state: HomeUiState) {
    val catalog = state.catalog
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt(state.code, Type.Caption, color = Palette.TextFaint)
        if (catalog != null && !state.loading) {
            Dot()
            Txt(
                "${catalog.channelCount} canales · ${catalog.sections.size} ${if (catalog.sections.size == 1) "grupo" else "grupos"}",
                Type.Caption
            )
        }
        if (state.updatedAt > 0 && !state.loading) {
            Dot()
            when {
                state.refreshing -> Txt("Actualizando…", Type.Caption, color = Palette.Accent)
                state.offline -> Txt("Sin conexión · copia de ${relativeTime(state.updatedAt)}", Type.Caption, color = Palette.Warning)
                else -> Txt("Actualizado ${relativeTime(state.updatedAt)}", Type.Caption)
            }
        }
        if (state.failedRefs > 0 && !state.loading) {
            Dot()
            Txt(
                if (state.failedRefs == 1) "1 subcategoría no disponible" else "${state.failedRefs} subcategorías no disponibles",
                Type.Caption, color = Palette.Warning
            )
        }
    }
}

@Composable
private fun Dot() {
    Box(Modifier.size(4.dp).background(Palette.TextFaint, RoundedCornerShape(2.dp)))
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SectionList(
    sections: List<Section>,
    firstCard: FocusRequester,
    onOpen: (Channel) -> Unit,
    onOptions: (Channel) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 6.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp)
    ) {
        itemsIndexed(sections, key = { _, s -> s.id }, contentType = { _, _ -> "section" }) { sectionIndex, section ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.padding(start = 32.dp, end = Dimens.SafeH),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Txt(section.title, Type.Title, modifier = Modifier.weight(1f, fill = false))
                    Txt("${section.channels.size}", Type.Caption, color = Palette.TextFaint)
                }
                LazyRow(
                    // focusRestorer: al volver a esta fila el foco cae en el último canal visitado.
                    modifier = Modifier.fillMaxWidth().focusRestorer(),
                    contentPadding = PaddingValues(start = 32.dp, end = Dimens.SafeH, top = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(section.channels, key = { it.id }, contentType = { "channel" }) { channel ->
                        ChannelCard(
                            channel = channel,
                            onOpen = onOpen,
                            onOptions = onOptions,
                            modifier = if (sectionIndex == 0 && channel === section.channels[0]) {
                                Modifier.focusRequester(firstCard)
                            } else {
                                Modifier
                            }
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
    modifier: Modifier = Modifier
) {
    TvClickable(
        onClick = { onOpen(channel) },
        onLongClick = { onOptions(channel) },
        modifier = modifier.width(Dimens.CardWidth).height(Dimens.CardHeight),
        shape = RoundedCornerShape(14.dp),
        color = Palette.Surface,
        focusedScale = 1.07f
    ) { focused ->
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Monogram(channel.initials, channel.tint)
                Box(Modifier.weight(1f))
                KindBadge(channel.kind, focused)
            }
            Box(Modifier.weight(1f))
            Txt(
                channel.name,
                Type.Card,
                color = if (focused) Palette.OnFocus else Palette.Text,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun LoadingSkeleton() {
    // Estático a propósito: un shimmer animado consume GPU en TVs modestas.
    Column(
        Modifier.fillMaxSize().padding(start = 32.dp, top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp)
    ) {
        repeat(3) { row ->
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.width(if (row == 0) 180.dp else 140.dp).height(18.dp)
                        .background(Palette.Surface, RoundedCornerShape(6.dp))
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    repeat(5) {
                        Box(
                            Modifier.width(Dimens.CardWidth).height(Dimens.CardHeight)
                                .background(Palette.Surface.copy(alpha = 0.7f - row * 0.18f), RoundedCornerShape(14.dp))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomePanel(onAdd: () -> Unit, onOpenCode: () -> Unit, focus: FocusRequester) {
    Column(Modifier.width(620.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Txt("Bienvenido a Mi Visor", Type.Display)
        Txt(
            "Añade una categoría con su código y tendrás tus canales en el menú lateral, " +
                "listos para abrir con el mando.",
            Type.Body, color = Palette.TextDim, maxLines = 3
        )
        Box(Modifier.height(6.dp))
        Step("1", "Pulsa «Añadir categoría» y escribe un nombre y el código.")
        Step("2", "Elige un canal y pulsa OK para reproducirlo.")
        Step("3", "Mantén pulsado OK sobre un canal para más opciones.")
        Box(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton("Añadir categoría", onAdd, Modifier.focusRequester(focus), icon = R.drawable.ic_add, style = ButtonStyle.PRIMARY)
            TvButton("Solo abrir un código", onOpenCode, icon = R.drawable.ic_search)
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
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier.size(28.dp).background(Palette.SurfaceHi, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) { Txt(number, Type.Label, color = Palette.Accent) }
        Txt(text, Type.Body, color = Palette.Text)
    }
}

@Composable
private fun HintBar() {
    Row(
        Modifier.fillMaxWidth().background(Palette.Rail).padding(start = 32.dp, end = Dimens.SafeH, top = 10.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        Hint("OK", "Reproducir")
        Hint("OK mantenido", "Opciones")
        Hint("◀", "Menú")
    }
}

@Composable
private fun Hint(key: String, action: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.background(Palette.SurfaceHi, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp)
        ) { Txt(key, Type.Badge, color = Palette.Text) }
        Txt(action, Type.Caption)
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(start = 32.dp, end = Dimens.SafeH, bottom = Dimens.SafeV), contentAlignment = Alignment.CenterStart) {
        content()
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
