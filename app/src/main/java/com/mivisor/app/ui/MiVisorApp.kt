package com.mivisor.app.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import com.mivisor.app.diagnostics.CrashReporter
import com.mivisor.app.ui.screens.ErrorLogScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mivisor.app.MainViewModel
import com.mivisor.app.R
import com.mivisor.app.model.Channel
import com.mivisor.app.model.SourceProfile
import com.mivisor.app.player.StreamLauncher
import com.mivisor.app.ui.components.AppPickerDialog
import com.mivisor.app.ui.components.ChannelOptionsDialog
import com.mivisor.app.ui.components.SourceAction
import com.mivisor.app.ui.components.SourceDialog
import com.mivisor.app.ui.components.SourceDialogMode
import com.mivisor.app.ui.neon.AppTopBar
import com.mivisor.app.ui.neon.BottomNav
import com.mivisor.app.ui.neon.LocalAppLayout
import com.mivisor.app.ui.neon.NavTab
import com.mivisor.app.ui.neon.rememberAppLayout
import com.mivisor.app.ui.neon.stadiumLights
import com.mivisor.app.ui.screens.CatalogScreen
import com.mivisor.app.ui.screens.HistoryScreen
import com.mivisor.app.ui.screens.ServicesScreen
import com.mivisor.app.QuickServicesViewModel
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mivisor.app.ui.screens.SettingsScreen
import com.mivisor.app.ui.screens.SourcesScreen
import com.mivisor.app.ui.screens.agenda.AgendaScreen
import com.mivisor.app.ui.theme.LocalAnimations

/** Pantallas. Las 4 primeras son pestañas; SOURCES ("Gestionar") se abre desde Canales. */
private enum class Screen(val tab: Int) { CATALOG(0), AGENDA(1), HISTORY(2), SETTINGS(3), SOURCES(0), SERVICES(3), LOGS(3) }

private val TABS = listOf(
    NavTab("Canales", R.drawable.ic_apps),
    NavTab("Agenda", R.drawable.ic_calendar),
    NavTab("Historial", R.drawable.ic_history),
    NavTab("Ajustes", R.drawable.ic_settings)
)

private fun screenForTab(index: Int) = when (index) {
    1 -> Screen.AGENDA
    2 -> Screen.HISTORY
    3 -> Screen.SETTINGS
    else -> Screen.CATALOG
}

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
    val layout = rememberAppLayout()

    var screen by remember { mutableStateOf(Screen.CATALOG) }
    var sourceDialog by remember { mutableStateOf<SourceDialogState?>(null) }
    var optionsFor by remember { mutableStateOf<Channel?>(null) }
    var picker by remember { mutableStateOf<PickerState?>(null) }
    val tabFocus = remember { FocusRequester() }
    val categoryFocus = remember { FocusRequester() }

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

    // Arranque: abre la primera categoría (o la del deep link) una sola vez.
    LaunchedEffect(sourcesOrNull != null) {
        val list = sourcesOrNull ?: return@LaunchedEffect
        vm.autoOpen(list)
        withFrameNanos { }
        runCatching { categoryFocus.requestFocus() }
    }

    // Al cambiar de pestaña, el foco va a la pestaña activa (en TV, para seguir navegando con el mando).
    LaunchedEffect(screen) {
        if (screen == Screen.CATALOG || layout.compact) return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        runCatching { tabFocus.requestFocus() }
    }

    // Servicios rápidos (Ace Stream, VPN/WARP): estado real al abrir y al volver a MiVisor (p. ej. tras
    // autorizar la VPN), sin que el usuario tenga que pulsar nada. Al salir se sueltan receptores y enlaces.
    val quick: QuickServicesViewModel = viewModel()
    val quickUi by quick.ui.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> quick.onForeground()
                Lifecycle.Event.ON_STOP -> quick.onBackground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Pasos recientes para el registro de errores (solo el nombre de la pantalla).
    LaunchedEffect(screen) { CrashReporter.breadcrumb("Pantalla ${screen.name}") }

    // Atrás: cualquier pantalla → Canales; en Canales, el sistema (salir).
    BackHandler(enabled = screen != Screen.CATALOG) {
        screen = if (screen == Screen.SERVICES || screen == Screen.LOGS) Screen.SETTINGS else Screen.CATALOG
    }

    CompositionLocalProvider(LocalAnimations provides animations, LocalAppLayout provides layout) {
        Box(
            Modifier
                .fillMaxSize()
                .stadiumLights()
                // Pantalla completa: solo se respeta el notch/cámara de móviles y tablets.
                .windowInsetsPadding(WindowInsets.displayCutout)
        ) {
            Column(Modifier.fillMaxSize()) {
                AppTopBar(
                    tabs = TABS,
                    selected = screen.tab,
                    onSelect = { screen = screenForTab(it) },
                    selectedFocus = tabFocus,
                    modifier = Modifier.padding(start = layout.gutter, end = layout.gutter, top = layout.top)
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (screen) {
                        Screen.CATALOG -> CatalogScreen(
                            state = ui,
                            sources = sources,
                            categoryFocus = categoryFocus,
                            onSelectSource = { vm.openSource(it) },
                            onRefresh = vm::refresh,
                            onOpen = { openChannel(it) },
                            onOptions = { optionsFor = it },
                            onAddSource = { sourceDialog = SourceDialogState(SourceDialogMode.ADD) },
                            onOpenCode = { sourceDialog = SourceDialogState(SourceDialogMode.OPEN_CODE) },
                            onManage = { screen = Screen.SOURCES },
                            services = quickUi,
                            onServiceOk = quick::onOk
                        )
                        Screen.AGENDA -> AgendaScreen(onOpenChannel = { openChannel(it) })
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
                        Screen.SETTINGS -> SettingsScreen(
                            prefs = launcher.preferences,
                            animations = animations,
                            onAnimationsChange = {
                                animations = it
                                launcher.preferences.animations = it
                            },
                            onClearCache = vm::clearCache,
                            onOpenServices = { screen = Screen.SERVICES },
                            onOpenErrorLog = { screen = Screen.LOGS }
                        )
                        Screen.SERVICES -> ServicesScreen(onBack = { screen = Screen.SETTINGS })
                        Screen.LOGS -> ErrorLogScreen(onBack = { screen = Screen.SETTINGS })
                    }
                }
                if (layout.compact) {
                    BottomNav(TABS, screen.tab, onSelect = { screen = screenForTab(it) })
                }
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
