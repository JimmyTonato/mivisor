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
import com.mivisor.app.player.EngineMode
import com.mivisor.app.engine.EngineManager
import com.mivisor.app.engine.EngineRouting
import com.mivisor.app.engine.ExternalEngineLauncher
import com.mivisor.app.engine.FailureKind
import com.mivisor.app.engine.PlaybackController
import com.mivisor.app.ui.screens.player.PlayerScreen
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
    object EngineDiagnostics : Route()
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
    val logos by vm.logos.collectAsStateWithLifecycle()
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
    remember { com.mivisor.app.engine.EngineDiagnostics.enabled = launcher.preferences.engineLogs; 0 }
    val layout = rememberAppLayout()

    var stack by remember { mutableStateOf(listOf(Entry(Route.Tab(NavItem.HOME)))) }
    /** true = el cambio de pantalla vino del contenido (o del arranque): la nueva pantalla pone el foco. */
    var contentFocus by remember { mutableStateOf(true) }
    var settingsSection by remember { mutableStateOf(SettingsSection.GENERAL) }
    var sourceDialog by remember { mutableStateOf<SourceDialogState?>(null) }
    var optionsFor by remember { mutableStateOf<Channel?>(null) }
    var picker by remember { mutableStateOf<PickerState?>(null) }
    /** Canal en el reproductor interno (pantalla completa del motor P2P de MiVisor); null = no hay. */
    var internal by remember { mutableStateOf<PlayRequest?>(null) }
    // Registro Ace Stream: última apertura Ace pendiente de "¿se ha visto?" al volver.
    var aceLaunched by remember { mutableStateOf<Triple<com.mivisor.app.model.Channel, Long, Boolean?>?>(null) }
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
        if (ch.kind != ChannelKind.ACE || !launcher.preferences.aceDiagnostics) return
        val t = launcher.lastTrace ?: return
        val vpn = com.mivisor.app.diagnostics.NetState.vpnActive(context)
        val provider = library.provider(ch.providerId)
        quick.logAce(com.mivisor.app.diagnostics.AceLog.entry(com.mivisor.app.diagnostics.AceLog.Launch(
            channel = ch.name, provider = ch.source, group = ch.group, storedUrl = ch.url, sentUri = t.sentUri,
            targetPackage = t.packageName, reason = t.reason, handlers = t.handlers, systemDefault = t.systemDefault,
            providerVpn = launcher.preferences.vpnFor(ch.providerId),
            systemVpn = vpn, warp = services.warpStatus.phase.name.lowercase(),
            vpnAutoConnect = launcher.preferences.vpnAutoConnect,
            httpEngineMode = t.httpMode, engineBefore = quick.engineText(services.aceStatus),
            engineOrigin = quick.engineOrigin(), aceAutoStart = launcher.preferences.aceAutoStart,
            listAgeMinutes = provider?.updatedAt?.takeIf { it > 0 }?.let { (System.currentTimeMillis() - it).coerceAtLeast(0) / 60_000 },
            listOffline = provider?.offline == true,
            fromActivity = launcher.launchesFromActivity,
            listSource = provider?.sourceInfo,
            listFailure = provider?.lastFailure?.let { "${it.kind.name.lowercase()} (${it.title})" }
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

    fun closePlayer() {
        PlaybackController.stop()
        internal = null
    }

    /** Ace Stream externo (la app instalada), como siempre. Solo tras pulsar Ver/OK. */
    fun playExternal(request: PlayRequest) {
        val needs = PlaybackNeeds(
            ace = request.channel.kind == ChannelKind.ACE && launcher.preferences.customAceEnabled,
            vpn = launcher.preferences.vpnFor(request.channel.providerId)
        )
        quick.play(request, needs)
    }

    /** Automático: si ninguna fuente da vídeo con el motor interno, Ace Stream externo (ya se pulsó Ver/OK). */
    fun fallbackExternal(request: PlayRequest, mode: EngineMode): (FailureKind?) -> Unit = { kind ->
        if (mode == EngineMode.AUTO && kind != FailureKind.CANCELLED && ExternalEngineLauncher.isAvailable(context)) {
            Toast.makeText(context, "El motor interno no consiguió vídeo: se abre Ace Stream", Toast.LENGTH_LONG).show()
            closePlayer()
            playExternal(request)
        }
    }

    /**
     * Pantalla completa con el motor interno. Si la vista previa ya está con ese canal (y, si se eligió una fuente
     * concreta, con esa fuente), se reutiliza la sesión: solo se quita el silencio. Si no, se empieza de cero.
     */
    fun startInternal(plan: EngineRouting.Plan, request: PlayRequest, mode: EngineMode) {
        if (plan.candidates.isEmpty()) { playExternal(request); return }
        internal = request
        vm.recordPlayback(request)
        val title = request.eventTitle ?: plan.name
        val onExhausted = fallbackExternal(request, mode)
        if (PlaybackController.canReuse(plan.channelKey, if (plan.explicit) plan.preferred else null)) {
            PlaybackController.promote(title, onExhausted)
        } else {
            PlaybackController.play(context, title, plan.channelKey, plan.candidates, plan.preferred, explicit = plan.explicit, onExhausted = onExhausted)
        }
    }

    fun playInternal(request: PlayRequest, mode: EngineMode) {
        val eventSources = request.eventId?.let { id -> agenda.event(id)?.let { agenda.sourcesOf(it) } }
        startInternal(EngineRouting.plan(request.channel, library.groups, eventSources), request, mode)
    }

    /** OK = quiero ver esto: Ace y VPN se preparan solos si hacen falta y luego se abre el mismo contenido. */
    fun play(request: PlayRequest) {
        val mode = launcher.preferences.engineMode
        if (!request.forceChooser) {
            when (EngineRouting.route(mode, request.channel, EngineManager.isSupported(context))) {
                EngineRouting.Route.INTERNAL -> { playInternal(request, mode); return }
                EngineRouting.Route.UNSUPPORTED -> {
                    toast(EngineManager.unsupportedReason(context) ?: "El motor interno no está disponible en este dispositivo")
                    return
                }
                EngineRouting.Route.EXTERNAL, EngineRouting.Route.NOT_ACE -> Unit
            }
        }
        playExternal(request)
    }

    fun playChannel(channel: Channel) = play(PlayRequest(channel))

    // Vista previa en Canales (fase 2): solo con el motor interno, nunca abre nada externo al mover el foco.
    val engineSupported = remember { EngineManager.isSupported(context) }
    val engineMode = launcher.preferences.engineMode
    val previewHooks: com.mivisor.app.ui.screens.player.PreviewHooks? =
        if (layout.compact || !engineSupported || engineMode == EngineMode.EXTERNAL || !launcher.preferences.previewEnabled) null
        else com.mivisor.app.ui.screens.player.PreviewHooks(
            keyOfGroup = { g -> EngineRouting.planGroup(g).takeIf { it.candidates.isNotEmpty() }?.channelKey },
            previewGroup = { g ->
                val plan = EngineRouting.planGroup(g)
                if (plan.candidates.isNotEmpty() && !PlaybackController.isActiveSession(plan.channelKey)) {
                    PlaybackController.play(context, plan.name, plan.channelKey, plan.candidates, null, preview = true, onExhausted = {})
                }
            },
            playGroup = { g ->
                val plan = EngineRouting.planGroup(g)
                val best = plan.candidates.firstOrNull()?.contentId
                val channel = g.sources.firstOrNull { s -> com.mivisor.app.data.AceStreamLink.contentId(s.channel.url).equals(best, true) }?.channel ?: g.first.channel
                startInternal(plan, PlayRequest(channel), engineMode)
            },
            keyOfChannel = { c -> EngineRouting.candidateOf(c)?.let { EngineRouting.plan(c, library.groups).channelKey } },
            previewChannel = { c ->
                val plan = EngineRouting.plan(c, library.groups)
                if (plan.candidates.isNotEmpty() && !PlaybackController.canReuse(plan.channelKey, plan.preferred)) {
                    PlaybackController.play(context, plan.name, plan.channelKey, plan.candidates, plan.preferred, explicit = true, preview = true, onExhausted = {})
                }
            }
        )

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

    // Logos que ya trae la agenda (futbolenlatv.es) y los de TheSportsDB al abrir un partido: se guardan.
    LaunchedEffect(agenda.agenda, agenda.tvChannels) {
        val fromAgenda = agenda.agenda?.events.orEmpty().asSequence().flatMap { it.channels.asSequence() }
            .mapNotNull { c -> c.logo?.let { com.mivisor.app.data.logos.LogoEntry(c.name, it, com.mivisor.app.data.logos.LogoSource.FUTBOLENLATV) } }
        val fromTsdb = agenda.tvChannels.values.asSequence().flatten()
            .mapNotNull { c -> c.logo?.let { com.mivisor.app.data.logos.LogoEntry(c.name, it, com.mivisor.app.data.logos.LogoSource.THESPORTSDB) } }
        vm.harvestLogos((fromAgenda + fromTsdb).distinctBy { it.name.lowercase() to it.source }.toList())
    }

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
                        if (launcher.preferences.aceDiagnostics) quick.afterAceReturn(at, now, away, vpnNow)
                    }
                    quick.onForeground()
                    vm.refreshStale()                 // canales: solo si la copia es vieja (la caché evita descargas)
                }
                Lifecycle.Event.ON_START -> EngineManager.cancelBackgroundStop()
                Lifecycle.Event.ON_STOP -> {
                    quick.onBackground()
                    // Segundo plano: la reproducción interna se corta ya (no sigue descargando) y el motor, al rato.
                    PlaybackController.release()
                    internal = null
                    EngineManager.onBackground(context)
                }
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
        val playing = internal
        if (playing != null) {
            val mode = launcher.preferences.engineMode
            PlayerScreen(
                onBack = ::closePlayer,
                onRetry = { PlaybackController.retry(context) },
                onExternal = if (mode != EngineMode.INTERNAL && ExternalEngineLauncher.isAvailable(context)) {
                    { closePlayer(); playExternal(playing) }
                } else null
            )
        } else Box(Modifier.fillMaxSize().stadiumLights().windowInsetsPadding(WindowInsets.displayCutout)) {
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
                            restoreKey = restoreKey, autoFocus = contentFocus, logos = logos, preview = previewHooks
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
                            onOpenEngineDiagnostics = { push(Route.EngineDiagnostics, null) },
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
                            ChannelGroupScreen(group, library, route.backLabel, { pop() }, ::playChannel, logos = logos, preview = previewHooks) { optionsFor = it }
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
                    Route.EngineDiagnostics -> com.mivisor.app.ui.screens.EngineDiagnosticsScreen(onBack = { pop() })
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
                // Comprobación con el motor (requiere Ace Premium): solo con el Registro Ace Stream activado.
                onProbe = if (channel.kind == ChannelKind.ACE && launcher.preferences.aceDiagnostics) {
                    { optionsFor = null; probeChannel = channel; quick.probe(channel.name, channel.url) }
                } else null
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
    Route.EngineDiagnostics -> "ENGINE_DIAGNOSTICS"
}

@Composable
private fun Missing(text: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        EmptyPanel(text, "Vuelve atrás para seguir.") { SecondaryButton("Volver", onBack, selected = true) }
    }
}
