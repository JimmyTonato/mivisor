package com.mivisor.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import com.mivisor.app.R
import com.mivisor.app.data.CatalogBuilder
import com.mivisor.app.model.HistoryItem
import com.mivisor.app.model.SourceProfile
import com.mivisor.app.ui.components.ButtonStyle
import com.mivisor.app.ui.components.ConfirmDialog
import com.mivisor.app.ui.components.MessagePanel
import com.mivisor.app.ui.components.Monogram
import com.mivisor.app.ui.components.TvButton
import com.mivisor.app.ui.components.TvClickable
import com.mivisor.app.ui.components.Txt
import com.mivisor.app.ui.theme.Dimens
import com.mivisor.app.ui.theme.Palette
import com.mivisor.app.ui.theme.Type

@Composable
fun ScreenScaffold(
    title: String,
    subtitle: String?,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit
) {
    val layout = com.mivisor.app.ui.neon.LocalAppLayout.current
    Column(Modifier.fillMaxSize().padding(horizontal = layout.gutter)) {
        com.mivisor.app.ui.neon.ScreenHero(title = title, subtitle = subtitle) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        content()
    }
}

/** Fila de lista: zona principal enfocable + botones a la derecha (navegables con ▶). */
@Composable
private fun ListRow(
    title: String,
    subtitle: String,
    initials: String,
    tint: Int,
    onClick: () -> Unit,
    trailing: @Composable RowScope.() -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TvClickable(
            onClick = onClick,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp),
            color = Palette.Surface,
            focusedScale = 1.015f
        ) { focused ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Monogram(initials, tint, size = 40.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Txt(title, Type.Card, color = if (focused) Palette.OnFocus else Palette.Text)
                    Txt(subtitle, Type.Caption, color = if (focused) Palette.OnFocusDim else Palette.TextDim)
                }
            }
        }
        trailing()
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HistoryScreen(
    history: List<HistoryItem>,
    onOpen: (HistoryItem) -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit
) {
    var confirmClear by remember { mutableStateOf(false) }
    ScreenScaffold(
        title = "Historial",
        subtitle = "Los últimos códigos que has abierto",
        actions = {
            if (history.isNotEmpty()) {
                TvButton("Borrar todo", { confirmClear = true }, icon = R.drawable.ic_delete, style = ButtonStyle.GHOST)
            }
        }
    ) {
        if (history.isEmpty()) {
            MessagePanel("Nada por aquí", "Cuando abras categorías o códigos aparecerán en esta lista.", icon = R.drawable.ic_history)
        } else {
            LazyColumn(
                Modifier.fillMaxSize().focusRestorer(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 32.dp, top = 4.dp, start = 4.dp, end = 4.dp)
            ) {
                itemsIndexed(history, key = { _, item -> item.code }) { _, item ->
                    val count = item.type?.toIntOrNull()
                    ListRow(
                        title = item.title.ifBlank { item.code },
                        subtitle = buildString {
                            append(item.code)
                            if (count != null) append(" · $count canales")
                            append(" · ").append(relativeTime(item.lastOpenedAt))
                        },
                        initials = CatalogBuilder.initialsOf(item.title.ifBlank { item.code }),
                        tint = (item.code.hashCode() and 0x7fffffff) % CatalogBuilder.TINTS,
                        onClick = { onOpen(item) }
                    ) {
                        TvButton("", { onDelete(item.code) }, icon = R.drawable.ic_delete, style = ButtonStyle.GHOST)
                    }
                }
            }
        }
    }
    if (confirmClear) {
        ConfirmDialog(
            title = "¿Borrar todo el historial?",
            message = "Tus categorías guardadas no se borran.",
            confirmLabel = "Borrar",
            onConfirm = { confirmClear = false; onClear() },
            onDismiss = { confirmClear = false }
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SourcesScreen(
    sources: List<SourceProfile>,
    onOpen: (SourceProfile) -> Unit,
    onEdit: (SourceProfile) -> Unit,
    onMove: (SourceProfile, Int) -> Unit,
    onDelete: (SourceProfile) -> Unit,
    onAdd: () -> Unit
) {
    var pendingDelete by remember { mutableStateOf<SourceProfile?>(null) }
    ScreenScaffold(
        title = "Categorías",
        subtitle = "Ordena, renombra o elimina las categorías del menú",
        actions = { TvButton("Añadir", onAdd, icon = R.drawable.ic_add, style = ButtonStyle.PRIMARY) }
    ) {
        if (sources.isEmpty()) {
            MessagePanel("No hay categorías", "Añade una con su nombre y su código.", icon = R.drawable.ic_list)
        } else {
            LazyColumn(
                Modifier.fillMaxSize().focusRestorer(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 32.dp, top = 4.dp, start = 4.dp, end = 4.dp)
            ) {
                itemsIndexed(sources, key = { _, s -> s.id }) { _, source ->
                    ListRow(
                        title = source.name,
                        subtitle = source.code,
                        initials = CatalogBuilder.initialsOf(source.name),
                        tint = (source.code.hashCode() and 0x7fffffff) % CatalogBuilder.TINTS,
                        onClick = { onOpen(source) }
                    ) {
                        TvButton("", { onMove(source, -1) }, icon = R.drawable.ic_up, style = ButtonStyle.GHOST)
                        TvButton("", { onMove(source, 1) }, icon = R.drawable.ic_down, style = ButtonStyle.GHOST)
                        TvButton("", { onEdit(source) }, icon = R.drawable.ic_edit, style = ButtonStyle.GHOST)
                        TvButton("", { pendingDelete = source }, icon = R.drawable.ic_delete, style = ButtonStyle.GHOST)
                    }
                }
            }
        }
    }
    pendingDelete?.let { source ->
        ConfirmDialog(
            title = "¿Eliminar «${source.name}»?",
            message = "Se quitará del menú. Podrás volver a añadirla con su código (${source.code}).",
            confirmLabel = "Eliminar",
            onConfirm = { pendingDelete = null; onDelete(source) },
            onDismiss = { pendingDelete = null }
        )
    }
}
