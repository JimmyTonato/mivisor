package com.mivisor.app.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mivisor.app.AgendaViewModel
import com.mivisor.app.MainViewModel
import com.mivisor.app.QuickServicesViewModel
import com.mivisor.app.diagnostics.CrashReporter
import com.mivisor.app.model.AgendaEvent
import com.mivisor.app.model.Channel
import com.mivisor.app.model.ChannelKind
import com.mivisor.app.model.Competition
import com.mivisor.app.model.PlayRequest
import com.mivisor.app.model.SourceProfile
import com.mivisor.app.model.displayTitle
import com.mivisor.app.player.StreamLauncher
import com.mivisor.app.services.PlaybackNeeds
import com.mivisor.app.services.RowAction
import com.mivisor.app.ui.components.AppPickerDialog
import com.mivisor.app.ui.components.ChannelOptionsDialog
import com.mivisor.app.ui.components.SourceAction
import com.mivisor.app.ui.components.SourceDialog
import com.mivisor.app.ui.components.SourceDialogMode
import com.mivisor.app.ui.kit.EmptyPanel
import com.mivisor.app.ui.kit.SecondaryButton
import com.mivisor.app.ui.neon.LocalAppLayout
import com.mivisor.app.ui.neon.rememberAppLayout
import com.mivisor.app.ui.neon.stadiumLights
import com.mivisor.app.ui.screens.ErrorLogScreen
import com.mivisor.app.ui.screens.HistoryScreen
import com.mivisor.app.ui.screens.ServicesScreen
import com.mivisor.app.ui.screens.SettingsScreen
import com.mivisor.app.ui.screens.SettingsSection
import com.mivisor.app.ui.screens.SourcesScreen
import com.mivisor.app.ui.screens.calendar.CalendarScreen
import com.mivisor.app.ui.screens.channels.ChannelGroupScreen
import com.mivisor.app.ui.screens.channels.ChannelsScreen
import com.mivisor.app.ui.screens.competition.CompetitionScreen
import com.mivisor.app.ui.screens.event.EventChannelsScreen
import com.mivisor.app.ui.screens.home.HomeScreen
import com.mivisor.app.ui.screens.live.LiveScreen
import com.mivisor.app.ui.shell.BottomBar
import com.mivisor.app.ui.shell.HelpDialog
import com.mivisor.app.ui.shell.NavItem
import com.mivisor.app.ui.shell.PreparingDialog
import com.mivisor.app.ui.shell.ProbeDialog
import com.mivisor.app.ui.shell.AceWatchedDialog
import com.mivisor.app.ui.shell.ServicesPanelDialog
import com.mivisor.app.ui.shell.Sidebar
import com.mivisor.app.ui.theme.LocalAnimations

/** Pantallas. Las 5 de la barra lateral son raíces; el resto se apilan encima (Atrás vuelve). */
private sealed class Route {
    data class Tab(val item: NavItem) : Route()
    object Calendar : Route()
    data class CompetitionPage(val competition: Competition) : Route()
    data class Event(val id: String, val backLabel: String) : Route()
    data class Group(val key: String, val backLabel: String) : Route()
    object Providers : Route()
    object ApiServices : Route()
    object ErrorLog : Route()
}

/** Entrada de la pila. [restoreKey] = elemento desde el que se abrió la siguiente pantalla (para volver a él). */
private data class Entry(val route: Route, val restoreKey: String? = null)

private class SourceDialogState(val mode: SourceDialogMode, val source: SourceProfile? = null)

private class PickerState(val request: PlayRequest, val intent: Intent, val apps: List<StreamLauncher.AppChoice>)

@Composable
fun MiVisorApp(vm: MainViewModel) {
    val agendaVm: AgendaViewModel = viewModel()
    val quick: QuickServicesViewModel = viewModel()
    val library by vm.ui.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val allChannels by vm.allChannels.collectAsStateWithLifecycle()
    val agenda by agendaVm.ui.collectAsStateWithLifecycle()
    val services by quick.ui.collectAsStateWithLifecycle()
    val prep by quick.prep.collectAsStateWithLifecycle()
    val launch by quick.launch.collectAsStateWithLifecycle()
    val panel by quick.panel.collectAsStateWithLifecycle()
    val help by quick.help.collectAsStateWithLifecycle()
    val probe by quick.probe.collectAsStateWithLifecycle()
    var probeChannel by remember { mutableStateOf<Channel?>(null) }

    val context = LocalContext.current
    val launcher = remember { StreamLauncher(context) }
    var animations by remember { mutableStateOf(launcher.preferences.animations) }
    val layout = rememberAppLayout()

    var stack by remember { mutableStateOf(listOf(Entry(Route.Tab(NavItem.HOME)))) }
    /** true = el cambio de pantalla vino del contenido (o del arranque): la nueva pantalla pone el foco. */
    var contentFocus by remember { mutableStateOf(true) }
    var settingsSection by remember { mutableStateOf(SettingsSection.GENERAL) }
    var sourceDialog by remember { mutableStateOf<SourceDialogState?>(null) }
    var optionsFor by remember { mutableStateOf<Channel?>(null) }
    var picker by remember { mutableStateOf<PickerState?>(null) }
    // Registro Ace Stream: última apertura Ace pendiente de "¿se ha visto?" al volver.
    var aceLaunched by remember { mutableStateOf<Triple<com.mivisor.app.model.Channel, Long, Boolean?>?>(null) }
    var aceAsk by remember { mutableStateOf<Triple<com.mivisor.app.model.Channel, Long, Boolean?>?>(null) }
    var aceAnswered by remember { mutableStateOf<Boolean?>(null) }
    var vpnVersion by remember { mutableIntStateOf(0) }
    val navFocus = remember { FocusRequester() }

    val root = (stack.first().route as? Route.Tab)?.item ?: NavItem.HOME
    val current = stack.last()

    fun toast(message: String?) = Toast.makeText(context, message ?: "No se pudo abrir el canal", Toast.LENGTH_LONG).show()

    fun selectTab(item: NavItem) {
        contentFocus = false
        stack = listOf(Entry(Route.Tab(item)))
    }

    fun push(route: Route, fromKey: String?) {
        contentFocus = true
        stack = stack.dropLast(1) + stack.last().copy(restoreKey = fromKey) + Entry(route)
    }

    /** "Ver calendario completo": abre la sección Agenda con el foco en el contenido. */
    fun openAgenda() {
        contentFocus = true
        stack = listOf(Entry(Route.Tab(NavItem.AGENDA)))
    }

    fun pop() {
        contentFocus = true
        stack = if (stack.size > 1) stack.dropLast(1) else listOf(Entry(Route.Tab(NavItem.HOME)))
    }

    // ------------------------------------------------------------------ reproducir

    /** Registro Ace Stream: qué app abrió el canal y por qué, ID intacto, VPN, estado del motor. */
    fun logAceLaunch(request: PlayRequest) {
        val ch = request.channel
        if (ch.kind != ChannelKind.ACE) return
        val t = launcher.lastTrace ?: return
        val vpn = com.mivisor.app.diagnostics.NetState.vpnActive(context)
        quick.logAce(com.mivisor.app.diagnostics.AceLog.entry(com.mivisor.app.diagnostics.AceLog.Launch(
            channel = ch.name, provider = ch.source, group = ch.group, storedUrl = ch.url, sentUri = t.sentUri,
            targetPackage = t.packageName, reason = t.reason, handlers = t.handlers, systemDefault = t.systemDefault,
            providerVpn = launcher.preferences.vpnFor(ch.providerId),
            systemVpn = vpn, warp = services.warpStatus.phase.name.lowercase(),
            vpnAutoConnect = launcher.preferences.vpnAutoConnect,
            httpEngineMode = t.httpMode, engineBefore = quick.engineText(services.aceStatus),
            engineOrigin = quick.engineOrigin(), aceAutoStart = launcher.preferences.aceAutoStart
        )))
        aceLaunched = Triple(ch, System.currentTimeMillis(), vpn)
    }

    fun launchNow(request: PlayRequest) {
        try {
            val prepared = request.channel.kind == ChannelKind.ACE && launcher.preferences.customAceEnabled
            val decision = launcher.open(request.channel, request.forceChooser, engineState = services.aceStatus.phase.name, enginePrepared = prepared)
            launcher.lastReport?.let { CrashReporter.saveDiagnostic(context, it) }
            when (decision) {
                is StreamLauncher.Decision.Choose -> picker = PickerState(request, decision.intent, decision.apps)
                StreamLauncher.Decision.Launched -> { vm.recordPlayback(request); logAceLaunch(request) }
            }
        } catch (e: Exception) {
            toast(e.message)
        }
    }

    /** OK = quiero ver esto: Ace y VPN se preparan solos si hacen falta y luego se abre el mismo contenido. */
    fun play(request: PlayRequest) {
        val needs = PlaybackNeeds(
            // acestream:// se entrega a la app de Ace Stream, que arranca SU motor (igual que la app antigua).
            // Solo con el "motor local (HTTP)" de Ajustes hay que tener el motor listo antes de abrir.
            ace = request.channel.kind == ChannelKind.ACE && launcher.preferences.customAceEnabled,
            vpn = launcher.preferences.vpnFor(request.channel.providerId)
        )
        quick.play(request, needs)
    }

    fun playChannel(channel: Channel) = play(PlayRequest(channel))

    /** Un partido: una fuente → se reproduce; varias (o ninguna) → "Canales del encuentro". */
    fun watch(event: AgendaEvent, key: String) {
        val sources = agenda.sourcesOf(event)
        if (sources.size == 1) {
            play(PlayRequest(sources.first(), event.displayTitle(), event.competition, event.id))
        } else {
            push(Route.Event(event.id, backLabelFor(root)), key)
        }
    }

    LaunchedEffect(launch) {
        val request = launch ?: return@LaunchedEffect
        quick.consumeLaunch()
        launchNow(request)
    }

    // Los canales de todos los proveedores alimentan la agenda ("¿dónde puedo verlo?").
    LaunchedEffect(allChannels) { agendaVm.setChannels(allChannels) }

    // Abrir un código (o un deep link) lleva a Canales con ese proveedor.
    LaunchedEffect(library.focusToken) {
        if (library.focusToken > 0) {
            contentFocus = true
            stack = listOf(Entry(Route.Tab(NavItem.CHANNELS)))
        }
    }

    // Ace Stream y VPN: estado real al abrir y al volver a MiVisor (p. ej. tras autorizar la VPN).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    aceLaunched?.let { (ch, at, vpn) ->
                        aceLaunched = null
                        val now = System.currentTimeMillis()
                        val away = (now - at) / 1000
                        val vpnNow = com.mivisor.app.diagnostics.NetState.vpnActive(context)
                        quick.describeEngine { engine -> quick.logAce(com.mivisor.app.diagnostics.AceLog.returned(away, engine, vpnNow)) }
                        if (com.mivisor.app.diagnostics.AceLog.shouldAsk(at, now)) { aceAnswered = null; aceAsk = Triple(ch, away, vpn) }
                    }
                    quick.onForeground()
                    vm.refreshStale()                 // canales: solo si la copia es vieja (la caché evita descargas)
                }
                Lifecycle.Event.ON_STOP -> quick.onBackground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Al entrar en Canales se comprueba si hay datos nuevos (sin repetir si se comprobó hace poco).
    LaunchedEffect(root) { if (root == NavItem.CHANNELS) vm.refreshStale() }

    // Pasos recientes para el registro de errores (solo el tipo de pantalla).
    LaunchedEffect(current.route) { CrashReporter.breadcrumb("Pantalla ${routeName(current.route)}") }

    BackHandler(enabled = stack.size > 1 || root != NavItem.HOME) { pop() }

    val onServiceOk: (RowAction) -> Unit = { quick.onOk(it) }

    CompositionLocalProvider(LocalAnimations provides animations, LocalAppLayout provides layout) {
        Box(Modifier.fillMaxSize().stadiumLights().windowInsetsPadding(WindowInsets.displayCutout)) {
            val content: @Composable () -> Unit = {
                val restoreKey = current.restoreKey
                when (val route = current.route) {
                    is Route.Tab -> when (route.item) {
                        NavItem.HOME -> HomeScreen(
                            agenda = agenda, services = services, onServiceOk = onServiceOk,
                            onWatch = ::watch,
                            onOpenEvent = { e, key -> push(Route.Event(e.id, "Inicio"), key) },
                            onOpenCompetition = { c, key -> push(Route.CompetitionPage(c), key) },
                            onOpenCalendar = { _ -> agendaVm.openCalendarFor(null); openAgenda() },
                            onOpenLive = { selectTab(NavItem.LIVE) },
                            onAddProvider = { sourceDialog = SourceDialogState(SourceDialogMode.ADD) },
                            restoreKey = restoreKey, autoFocus = contentFocus
                        )
                        NavItem.LIVE -> LiveScreen(
                            agenda = agenda, onSport = agendaVm::setLiveSport, onRefresh = { agendaVm.load(force = true) },
                            onWatch = ::watch,
                            onOpenCalendar = { agendaVm.openCalendarFor(null); openAgenda() },
                            restoreKey = restoreKey, autoFocus = contentFocus
                        )
                        NavItem.AGENDA -> CalendarScreen(
                            agenda = agenda,
                            onRange = agendaVm::setCalendarRange,
                            onDay = agendaVm::setCalendarDay,
                            onCompetition = agendaVm::setCalendarCompetition,
                            onRefresh = { agendaVm.load(force = true) },
                            onOpenEvent = { e, key -> push(Route.Event(e.id, "Agenda deportiva"), key) },
                            onWatch = ::watch,
                            back = null,
                            restoreKey = restoreKey, autoFocus = contentFocus
                        )
                        NavItem.CHANNELS -> ChannelsScreen(
                            library = library,
                            onSelectProvider = vm::select,
                            onOpenGroup = { g, key -> push(Route.Group(g.key, if (library.selected == null) "Canales / Todos" else "Canales / ${library.provider(library.selected)?.name.orEmpty()}"), key) },
                            onPlay = ::playChannel,
                            onOptions = { optionsFor = it },
                            onRefresh = { vm.refresh(it) },
                            onAddProvider = { sourceDialog = SourceDialogState(SourceDialogMode.ADD) },
                            onOpenCode = { sourceDialog = SourceDialogState(SourceDialogMode.OPEN_CODE) },
                            onManage = { push(Route.Providers, null) },
                            onEditProvider = { sourceDialog = SourceDialogState(SourceDialogMode.EDIT, it.source) },
                            restoreKey = restoreKey, autoFocus = contentFocus
                        )
                        NavItem.HISTORY -> HistoryScreen(
                            history = playback,
                            onOpen = { entry -> play(PlayRequest(entry.toChannel(), entry.title.takeIf { entry.isEvent }, entry.competition, entry.eventId)) },
                            onDelete = vm::removePlayback,
                            onClear = vm::clearPlayback,
                            autoFocus = contentFocus
                        )
                        NavItem.SETTINGS -> SettingsScreen(
                            prefs = launcher.preferences,
                            section = settingsSection,
                            onSection = { settingsSection = it },
                            animations = animations,
                            onAnimationsChange = { animations = it; launcher.preferences.animations = it },
                            services = services,
                            onServiceOk = onServiceOk,
                            onPreviewAce = quick::previewAce,
                            onPreviewWarp = quick::previewWarp,
                            onClearCache = vm::clearCache,
                            onClearHistory = vm::clearPlayback,
                            onManageProviders = { push(Route.Providers, null) },
                            onOpenApiServices = { push(Route.ApiServices, null) },
                            onOpenErrorLog = { push(Route.ErrorLog, null) },
                            autoFocus = contentFocus,
                            onImportRuleChanged = vm::rebuildAll
                        )
                    }
                    Route.Calendar -> CalendarScreen(
                        agenda = agenda,
                        onRange = agendaVm::setCalendarRange,
                        onDay = agendaVm::setCalendarDay,
                        onCompetition = agendaVm::setCalendarCompetition,
                        onRefresh = { agendaVm.load(force = true) },
                        onOpenEvent = { e, key -> push(Route.Event(e.id, "Agenda deportiva"), key) },
                        onWatch = ::watch,
                        back = backLabelFor(root) to { pop() },
                        restoreKey = restoreKey, autoFocus = contentFocus
                    )
                    is Route.CompetitionPage -> CompetitionScreen(
                        competition = route.competition, agenda = agenda,
                        onBack = { pop() },
                        onOpenEvent = { e, key -> push(Route.Event(e.id, route.competition.label), key) },
                        onOpenCalendar = { agendaVm.openCalendarFor(route.competition); push(Route.Calendar, null) },
                        restoreKey = restoreKey
                    )
                    is Route.Event -> {
                        val event = agenda.event(route.id)
                        if (event == null) {
                            Missing("Este evento ya no está en la agenda.") { pop() }
                        } else {
                            EventChannelsScreen(
                                event = event, agenda = agenda, backLabel = route.backLabel, onBack = { pop() },
                                onRequestChannels = agendaVm::requestChannels,
                                onPlay = { ch -> play(PlayRequest(ch, event.displayTitle(), event.competition, event.id)) },
                                onConfigureSources = { selectTab(NavItem.CHANNELS) }
                            )
                        }
                    }
                    is Route.Group -> {
                        val group = library.groups.firstOrNull { it.key == route.key }
                            ?.let { g -> library.selected?.let { g.only(it) } ?: g }
                        if (group == null) {
                            Missing("Este canal ya no está en tus proveedores.") { pop() }
                        } else {
                            ChannelGroupScreen(group, library, route.backLabel, { pop() }, ::playChannel) { optionsFor = it }
                        }
                    }
                    Route.Providers -> {
                        SourcesScreen(
                            sources = library.providers.orEmpty().filterNot { it.transient }.map { it.source },
                            vpnFor = { id -> vpnVersion.let { launcher.preferences.vpnFor(id) } },
                            onToggleVpn = { s -> launcher.preferences.setVpnFor(s.id, !launcher.preferences.vpnFor(s.id)); vpnVersion++ },
                            onOpen = { s -> vm.select(s.id); selectTab(NavItem.CHANNELS) },
                            onEdit = { sourceDialog = SourceDialogState(SourceDialogMode.EDIT, it) },
                            onMove = vm::moveSource,
                            onDelete = vm::removeSource,
                            onAdd = { sourceDialog = SourceDialogState(SourceDialogMode.ADD) },
                            onBack = { pop() }
                        )
                    }
                    Route.ApiServices -> ServicesScreen(onBack = { pop() })
                    Route.ErrorLog -> ErrorLogScreen(onBack = { pop() })
                }
            }

            if (layout.compact) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                    BottomBar(root, ::selectTab)
                }
            } else {
                Row(Modifier.fillMaxSize()) {
                    Sidebar(root, ::selectTab, services, onServiceOk, agenda.home.live.size, navFocus)
                    Box(Modifier.weight(1f).fillMaxHeight()) { content() }
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
                        SourceAction.OPEN_ONLY -> vm.openCode(code, name.ifBlank { null })
                        SourceAction.SAVE_AND_OPEN -> vm.saveSource(name, code, openAfter = true)
                        SourceAction.SAVE -> vm.saveSource(name, code, openAfter = false, replacing = dialog.source)
                    }
                }
            )
        }

        optionsFor?.let { channel ->
            ChannelOptionsDialog(
                channel = channel,
                onDismiss = { optionsFor = null },
                onOpen = { optionsFor = null; playChannel(channel) },
                onOpenWith = { optionsFor = null; play(PlayRequest(channel, forceChooser = true)) },
                onShare = {
                    optionsFor = null
                    runCatching { launcher.share(channel) }.onFailure { toast(it.message) }
                },
                onForget = {
                    optionsFor = null
                    launcher.preferences.forgetChannel(channel.name)
                    Toast.makeText(context, "Se volverá a preguntar con qué app abrirlo", Toast.LENGTH_SHORT).show()
                },
                onProbe = if (channel.kind == ChannelKind.ACE) {
                    { optionsFor = null; probeChannel = channel; quick.probe(channel.name, channel.url) }
                } else null
            )
        }

        aceAsk?.let { (ch, away, vpn) ->
            AceWatchedDialog(
                channelName = ch.name, awaySeconds = away, vpnAtLaunch = vpn, answered = aceAnswered,
                onYes = { quick.logAce(com.mivisor.app.diagnostics.AceLog.answer(true)); aceAsk = null },
                // Sin comprobación por HTTP: en Ace para Android requiere Premium. Se explica qué probar.
                onNo = { quick.logAce(com.mivisor.app.diagnostics.AceLog.answer(false)); aceAnswered = false },
                onDismiss = { aceAsk = null; aceAnswered = null }
            )
        }

        picker?.let { state ->
            AppPickerDialog(
                channel = state.request.channel,
                apps = state.apps,
                onDismiss = { picker = null },
                onPick = { app, scope ->
                    picker = null
                    try {
                        launcher.remember(state.request.channel, state.intent, app.packageName, scope)
                        launcher.launch(state.intent, app.packageName)
                        launcher.picked(app.packageName, scope)
                        vm.recordPlayback(state.request)
                        logAceLaunch(state.request)
                    } catch (e: Exception) {
                        toast(e.message)
                    }
                }
            )
        }

        prep?.let { p ->
            PreparingDialog(
                prep = p,
                onCancel = quick::cancelPrep,
                onAuthorize = quick::authorizeVpn,
                onRetry = quick::retryPrep,
                onOpenAnyway = quick::openAnyway,
                onInstallHelp = { quick.onOk(RowAction.WARP_INSTALL_HELP) }
            )
        }

        if (panel) {
            ServicesPanelDialog(
                services = services,
                onOk = onServiceOk,
                onManage = { quick.closePanel(); settingsSection = SettingsSection.SERVICES; selectTab(NavItem.SETTINGS) },
                onDismiss = quick::closePanel
            )
        }

        probe?.let { p ->
            ProbeDialog(
                probe = p,
                onPlay = { quick.closeProbe(); probeChannel?.let { playChannel(it) } },
                onRetry = { probeChannel?.let { quick.probe(it.name, it.url) } },
                onDismiss = quick::closeProbe
            )
        }

        help?.let { topic -> HelpDialog(topic, onRecheck = { quick.onOk(RowAction.REFRESH) }, onDismiss = quick::closeHelp) }
    }
}

private fun backLabelFor(root: NavItem): String = root.label

private fun routeName(route: Route): String = when (route) {
    is Route.Tab -> route.item.name
    Route.Calendar -> "CALENDAR"
    is Route.CompetitionPage -> "COMPETITION ${route.competition.name}"
    is Route.Event -> "EVENT"
    is Route.Group -> "GROUP"
    Route.Providers -> "PROVIDERS"
    Route.ApiServices -> "API_SERVICES"
    Route.ErrorLog -> "ERROR_LOG"
}

@Composable
private fun Missing(text: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        EmptyPanel(text, "Vuelve atrás para seguir.") { SecondaryButton("Volver", onBack, selected = true) }
    }
}
