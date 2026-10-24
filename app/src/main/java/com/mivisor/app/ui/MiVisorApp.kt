package com.mivisor.app.ui

import android.content.Intent
import android.widget.Toast
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mivisor.app.MainViewModel
import com.mivisor.app.R
import com.mivisor.app.model.Channel
import com.mivisor.app.model.SourceProfile
import com.mivisor.app.player.StreamLauncher
import com.mivisor.app.ui.components.AppPickerDialog
import com.mivisor.app.ui.components.ChannelOptionsDialog
import com.mivisor.app.ui.components.Divider
import com.mivisor.app.ui.components.Icon
import com.mivisor.app.ui.components.SectionLabel
import com.mivisor.app.ui.components.SourceAction
import com.mivisor.app.ui.components.SourceDialog
import com.mivisor.app.ui.components.SourceDialogMode
import com.mivisor.app.ui.components.TvClickable
import com.mivisor.app.ui.components.Txt
import com.mivisor.app.ui.screens.agenda.AgendaScreen
import com.mivisor.app.ui.screens.CatalogScreen
import com.mivisor.app.ui.screens.HistoryScreen
import com.mivisor.app.ui.screens.SettingsScreen
import com.mivisor.app.ui.screens.SourcesScreen
import com.mivisor.app.ui.theme.Dimens
import com.mivisor.app.ui.theme.LocalAnimations
import com.mivisor.app.ui.theme.Palette
import com.mivisor.app.ui.theme.Type

private enum class Screen { CATALOG, AGENDA, HISTORY, SOURCES, SETTINGS }

private class SourceDialogState(val mode: SourceDialogMode, val source: SourceProfile? = null)

private class PickerState(val channel: Channel, val intent: Intent, val apps: List<StreamLauncher.AppChoice>)

@Composable
fun MiVisorApp(vm: MainViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val sourcesOrNull by vm.sources.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val sources = sourcesOrNull.orEmpty()

    val context = LocalContext.current
    val launcher = remember { StreamLauncher(context) }
    var animations by remember { mutableStateOf(launcher.preferences.animations) }

    var screen by remember { mutableStateOf(Screen.CATALOG) }
    var sourceDialog by remember { mutableStateOf<SourceDialogState?>(null) }
    var optionsFor by remember { mutableStateOf<Channel?>(null) }
    var picker by remember { mutableStateOf<PickerState?>(null) }
    var contentFocused by remember { mutableStateOf(false) }
    val railFocus = remember { FocusRequester() }

    fun toast(message: String?) {
        Toast.makeText(context, message ?: "No se pudo abrir el canal", Toast.LENGTH_LONG).show()
    }

    fun openChannel(channel: Channel, forceChooser: Boolean = false) {
        try {
            when (val decision = launcher.open(channel, forceChooser)) {
                is StreamLauncher.Decision.Choose -> picker = PickerState(channel, decision.intent, decision.apps)
                StreamLauncher.Decision.Launched -> Unit
            }
        } catch (e: Exception) {
            toast(e.message)
        }
    }

    fun focusRail() {
        runCatching { railFocus.requestFocus() }
    }

    // Arranque: abre la primera categoría (o la del deep link) una sola vez.
    LaunchedEffect(sourcesOrNull != null) {
        val list = sourcesOrNull ?: return@LaunchedEffect
        vm.autoOpen(list)
        withFrameNanos { }
        if (list.isNotEmpty()) focusRail()
    }

    // "Atrás": subpantalla → catálogo; contenido → menú; menú → salir (comportamiento del sistema).
    BackHandler(enabled = screen != Screen.CATALOG) {
        screen = Screen.CATALOG
        focusRail()
    }
    BackHandler(enabled = screen == Screen.CATALOG && contentFocused && sources.isNotEmpty()) {
        focusRail()
    }

    // Al salir de la agenda (pantalla completa) el menú vuelve a existir: darle el foco tras pintarse.
    var previousScreen by remember { mutableStateOf(screen) }
    LaunchedEffect(screen) {
        if (previousScreen == Screen.AGENDA && screen != Screen.AGENDA) {
            repeat(2) { withFrameNanos { } }
            focusRail()
        }
        previousScreen = screen
    }

    CompositionLocalProvider(LocalAnimations provides animations) {
      Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().background(Palette.Bg)) {
            if (screen != Screen.AGENDA) Rail(
                sources = sources,
                activeSourceId = ui.activeSourceId.takeIf { screen == Screen.CATALOG },
                screen = screen,
                railFocus = railFocus,
                onSource = { source ->
                    screen = Screen.CATALOG
                    vm.openSource(source)
                },
                onAdd = { sourceDialog = SourceDialogState(SourceDialogMode.ADD) },
                onOpenCode = { sourceDialog = SourceDialogState(SourceDialogMode.OPEN_CODE) },
                onScreen = { screen = it }
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onFocusChanged { contentFocused = it.hasFocus }
            ) {
                when (screen) {
                    Screen.CATALOG -> CatalogScreen(
                        state = ui,
                        hasSources = sources.isNotEmpty(),
                        onRefresh = vm::refresh,
                        onOpen = { openChannel(it) },
                        onOptions = { optionsFor = it },
                        onAddSource = { sourceDialog = SourceDialogState(SourceDialogMode.ADD) },
                        onOpenCode = { sourceDialog = SourceDialogState(SourceDialogMode.OPEN_CODE) }
                    )
                    Screen.HISTORY -> HistoryScreen(
                        history = history,
                        onOpen = {
                            screen = Screen.CATALOG
                            vm.openHistory(it)
                        },
                        onDelete = vm::removeHistory,
                        onClear = vm::clearHistory
                    )
                    Screen.SOURCES -> SourcesScreen(
                        sources = sources,
                        onOpen = {
                            screen = Screen.CATALOG
                            vm.openSource(it)
                        },
                        onEdit = { sourceDialog = SourceDialogState(SourceDialogMode.EDIT, it) },
                        onMove = vm::moveSource,
                        onDelete = vm::removeSource,
                        onAdd = { sourceDialog = SourceDialogState(SourceDialogMode.ADD) }
                    )
                    Screen.AGENDA -> Unit // se pinta a pantalla completa, más abajo
                    Screen.SETTINGS -> SettingsScreen(
                        prefs = launcher.preferences,
                        animations = animations,
                        onAnimationsChange = {
                            animations = it
                            launcher.preferences.animations = it
                        },
                        onClearCache = vm::clearCache
                    )
                }
            }
        }

        // Agenda deportiva: pantalla completa con su propio diseño (sin el menú lateral).
        if (screen == Screen.AGENDA) {
            AgendaScreen(
                onOpenChannel = { openChannel(it) },
                onExit = { screen = Screen.CATALOG },
                onSettings = { screen = Screen.SETTINGS }
            )
        }
      }

        sourceDialog?.let { dialog ->
            SourceDialog(
                mode = dialog.mode,
                initialName = dialog.source?.name.orEmpty(),
                initialCode = dialog.source?.code.orEmpty(),
                onDismiss = { sourceDialog = null },
                onResult = { name, code, action ->
                    sourceDialog = null
                    when (action) {
                        SourceAction.OPEN_ONLY -> {
                            screen = Screen.CATALOG
                            vm.openCode(code, name.ifBlank { null })
                        }
                        SourceAction.SAVE_AND_OPEN -> {
                            screen = Screen.CATALOG
                            vm.saveSource(name, code, openAfter = true)
                        }
                        SourceAction.SAVE -> vm.saveSource(name, code, openAfter = false, replacing = dialog.source)
                    }
                }
            )
        }

        optionsFor?.let { channel ->
            ChannelOptionsDialog(
                channel = channel,
                onDismiss = { optionsFor = null },
                onOpen = { optionsFor = null; openChannel(channel) },
                onOpenWith = { optionsFor = null; openChannel(channel, forceChooser = true) },
                onShare = {
                    optionsFor = null
                    runCatching { launcher.share(channel) }.onFailure { toast(it.message) }
                },
                onForget = {
                    optionsFor = null
                    launcher.preferences.forgetChannel(channel.name)
                    Toast.makeText(context, "Se volverá a preguntar con qué app abrirlo", Toast.LENGTH_SHORT).show()
                }
            )
        }

        picker?.let { state ->
            AppPickerDialog(
                channel = state.channel,
                apps = state.apps,
                onDismiss = { picker = null },
                onPick = { app, scope ->
                    picker = null
                    try {
                        launcher.remember(state.channel, state.intent, app.packageName, scope)
                        launcher.launch(state.intent, app.packageName)
                    } catch (e: Exception) {
                        toast(e.message)
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Rail(
    sources: List<SourceProfile>,
    activeSourceId: String?,
    screen: Screen,
    railFocus: FocusRequester,
    onSource: (SourceProfile) -> Unit,
    onAdd: () -> Unit,
    onOpenCode: () -> Unit,
    onScreen: (Screen) -> Unit
) {
    // El FocusRequester del menú va en la categoría activa (o en la primera entrada).
    val focusTargetId = activeSourceId ?: sources.firstOrNull()?.id
    Column(
        Modifier
            .width(Dimens.RailWidth)
            .fillMaxHeight()
            .background(Palette.Rail)
            .padding(start = 20.dp, end = 16.dp, top = Dimens.SafeV, bottom = 20.dp)
            .focusRestorer()
    ) {
        Brand()
        Box(Modifier.height(22.dp))
        RailItem("Agenda deportiva", active = screen == Screen.AGENDA, icon = R.drawable.ic_calendar, onClick = { onScreen(Screen.AGENDA) })
        Box(Modifier.height(14.dp))
        SectionLabel("Categorías", Modifier.padding(start = 12.dp, bottom = 8.dp))
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 2.dp)
        ) {
            items(sources, key = { it.id }) { source ->
                RailItem(
                    label = source.name,
                    active = source.id == activeSourceId,
                    onClick = { onSource(source) },
                    modifier = if (source.id == focusTargetId) Modifier.focusRequester(railFocus) else Modifier
                )
            }
            item(key = "__add") {
                RailItem(
                    label = "Añadir categoría",
                    icon = R.drawable.ic_add,
                    active = false,
                    dim = true,
                    onClick = onAdd,
                    modifier = if (focusTargetId == null) Modifier.focusRequester(railFocus) else Modifier
                )
            }
        }
        Divider(Modifier.padding(vertical = 10.dp))
        RailItem("Abrir código", active = false, icon = R.drawable.ic_search, onClick = onOpenCode)
        RailItem("Historial", active = screen == Screen.HISTORY, icon = R.drawable.ic_history, onClick = { onScreen(Screen.HISTORY) })
        RailItem("Gestionar", active = screen == Screen.SOURCES, icon = R.drawable.ic_list, onClick = { onScreen(Screen.SOURCES) })
        RailItem("Ajustes", active = screen == Screen.SETTINGS, icon = R.drawable.ic_settings, onClick = { onScreen(Screen.SETTINGS) })
    }
}

@Composable
private fun Brand() {
    Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(34.dp).background(Palette.Accent, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(R.drawable.ic_play, Palette.OnAccent, size = 20.dp)
        }
        Box(Modifier.width(12.dp))
        Column {
            Txt("Mi Visor", Type.Title)
            Txt("TV", Type.Overline, color = Palette.Accent)
        }
    }
}

@Composable
private fun RailItem(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    dim: Boolean = false
) {
    TvClickable(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(44.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (active) Palette.Surface else Color.Transparent,
        contentAlignment = Alignment.CenterStart
    ) { focused ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Barra de acento: indica la categoría abierta aunque el foco esté en otra parte.
            Box(
                Modifier.width(3.dp).height(20.dp).background(
                    if (active && !focused) Palette.Accent else Color.Transparent,
                    RoundedCornerShape(2.dp)
                )
            )
            Box(Modifier.width(9.dp))
            if (icon != null) {
                Icon(icon, if (focused) Palette.OnFocus else if (active) Palette.Accent else Palette.TextDim, size = 18.dp)
                Box(Modifier.width(12.dp))
            }
            Txt(
                label,
                Type.Label,
                color = when {
                    focused -> Palette.OnFocus
                    active -> Palette.Text
                    dim -> Palette.TextDim
                    else -> Palette.TextDim
                }
            )
        }
    }
}
