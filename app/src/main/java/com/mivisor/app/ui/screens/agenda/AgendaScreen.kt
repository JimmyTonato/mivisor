package com.mivisor.app.ui.screens.agenda

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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mivisor.app.AgendaUi
import com.mivisor.app.AgendaViewModel
import com.mivisor.app.DayFilter
import com.mivisor.app.R
import com.mivisor.app.TimeSlot
import com.mivisor.app.model.AgendaEvent
import com.mivisor.app.model.Channel
import com.mivisor.app.model.EventStatus
import com.mivisor.app.ui.components.Icon
import com.mivisor.app.ui.components.Txt
import com.mivisor.app.ui.screens.relativeTime

/** Márgenes de la agenda (algo más ajustados que el resto: es una pantalla densa). */
internal val EdgeH = 32.dp
internal val EdgeTop = 16.dp

/**
 * Agenda deportiva a pantalla completa: lista (diseño "Agenda deportiva") y detalle
 * ("Canales del encuentro"). Atrás: detalle → lista → salir de la agenda.
 */
@Composable
fun AgendaScreen(
    onOpenChannel: (Channel) -> Unit,
    onExit: () -> Unit,
    onSettings: () -> Unit,
    vm: AgendaViewModel = viewModel()
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<AgendaEvent?>(null) }
    var lastOpenedId by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        vm.load()
        vm.refreshIndex()
    }
    BackHandler(enabled = selected != null) { selected = null }

    Box(Modifier.fillMaxSize().stadiumLights()) {
        val event = selected
        if (event == null) {
            AgendaHome(
                ui = ui,
                vm = vm,
                listState = listState,
                restoreFocusId = lastOpenedId,
                onOpenEvent = { lastOpenedId = it.id; selected = it },
                onExit = onExit,
                onSettings = onSettings
            )
        } else {
            EventDetail(
                event = event,
                ui = ui,
                vm = vm,
                onBack = { selected = null },
                onOpenChannel = onOpenChannel,
                onOpenEvent = { lastOpenedId = it.id; selected = it },
                onExit = onExit,
                onSettings = onSettings
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AgendaHome(
    ui: AgendaUi,
    vm: AgendaViewModel,
    listState: LazyListState,
    restoreFocusId: String?,
    onOpenEvent: (AgendaEvent) -> Unit,
    onExit: () -> Unit,
    onSettings: () -> Unit
) {
    val firstSide = remember { FocusRequester() }
    val restore = remember { FocusRequester() }

    // Al entrar: foco en el filtro de día; al volver del detalle: en el partido que se abrió.
    LaunchedEffect(Unit) {
        repeat(3) {
            withFrameNanos { }
            val target = if (restoreFocusId != null && ui.visible.any { it.id == restoreFocusId }) restore else firstSide
            if (runCatching { target.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    Column(Modifier.fillMaxSize().padding(start = EdgeH, end = EdgeH, top = EdgeTop)) {
        TopBar(onMyChannels = onExit, onSettings = onSettings)
        Column(Modifier.padding(top = 8.dp, bottom = 12.dp)) {
            Txt("Agenda deportiva", NeonType.Hero)
            Txt("Consulta los próximos encuentros y descubre en qué canales se emiten.", NeonType.HeroSub)
            Txt(statusLine(ui), NeonType.Small, color = if (ui.offline) Palette2.Warning else Neon.Faint)
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SideFilters(ui, vm, firstSide, Modifier.width(148.dp).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight()) {
                DayHeader(ui, vm)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        ui.loading -> Txt("Cargando agenda…", NeonType.Body, color = Neon.Dim, modifier = Modifier.padding(8.dp))
                        ui.error != null && ui.agenda == null -> Message(
                            "No se pudo cargar la agenda", ui.error
                        ) { NeonButton("Reintentar", { vm.load(force = true) }, icon = R.drawable.ic_refresh) }
                        ui.visible.isEmpty() -> Message(
                            if (ui.day == DayFilter.LIVE) "Nada en directo ahora mismo" else "No hay eventos",
                            "Prueba con otro día, otra franja horaria u otro deporte."
                        ) {}
                        else -> LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().focusRestorer(),
                            // Margen para que el halo del foco no se recorte.
                            contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(ui.visible, key = { it.id }, contentType = { "event" }) { event ->
                                EventRow(
                                    event = event,
                                    options = ui.optionCount(event),
                                    showDay = ui.day == DayFilter.NEXT,
                                    modifier = if (event.id == restoreFocusId) Modifier.focusRequester(restore) else Modifier,
                                    onClick = { onOpenEvent(event) }
                                )
                            }
                        }
                    }
                }
            }
            RightColumn(ui, onOpenEvent, Modifier.width(220.dp).fillMaxHeight())
        }
    }
}

private object Palette2 {
    val Warning = androidx.compose.ui.graphics.Color(0xFFFFC857)
}

private fun statusLine(ui: AgendaUi): String {
    val agenda = ui.agenda ?: return ""
    val updated = if (ui.fetchedAt > 0) relativeTime(ui.fetchedAt) else "—"
    return if (ui.offline) "Sin conexión · datos de $updated · ${agenda.source}"
    else "Actualizado $updated · fuente: ${agenda.source}"
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SideFilters(ui: AgendaUi, vm: AgendaViewModel, firstFocus: FocusRequester, modifier: Modifier) {
    val sports = ui.agenda?.sports?.takeIf { it.isNotEmpty() } ?: listOf("futbol", "baloncesto", "tenis", "motor")
    LazyColumn(
        modifier.focusRestorer(),
        contentPadding = PaddingValues(6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        items(listOf(DayFilter.TODAY, DayFilter.TOMORROW, DayFilter.LIVE, DayFilter.NEXT), key = { "d_${it.name}" }) { day ->
            SideItem(
                label = if (day == DayFilter.LIVE && ui.liveCount > 0) "${day.label} · ${ui.liveCount}" else day.label,
                icon = if (day == DayFilter.LIVE) R.drawable.ic_live else R.drawable.ic_calendar,
                selected = ui.day == day,
                modifier = if (day == DayFilter.TODAY) Modifier.focusRequester(firstFocus) else Modifier,
                onClick = { vm.setDay(day) }
            )
        }
        item(key = "sep") { Box(Modifier.height(4.dp)) }
        items(sports, key = { "s_$it" }) { sport ->
            SideItem(
                label = sportLabel(sport),
                icon = sportIcon(sport),
                selected = ui.sport == sport,
                onClick = { vm.toggleSport(sport) }
            )
        }
    }
}

@Composable
private fun SideItem(label: String, icon: Int, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    NeonCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(38.dp),
        radius = 12.dp,
        color = if (selected) Neon.PanelHi else Neon.Panel,
        borderColor = if (selected) Neon.Cyan else Neon.Stroke,
        contentAlignment = Alignment.CenterStart
    ) { focused ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, if (selected || focused) Neon.Cyan else Neon.Dim, size = 20.dp)
            Box(Modifier.width(10.dp))
            Txt(label, NeonType.H3, color = if (selected || focused) Neon.Text else Neon.Dim, modifier = Modifier.weight(1f))
            if (selected) Icon(R.drawable.ic_chevron_right, Neon.Cyan, size = 18.dp)
        }
    }
}

@Composable
private fun DayHeader(ui: AgendaUi, vm: AgendaViewModel) {
    val title = when (ui.day) {
        DayFilter.TODAY -> dayTitle(System.currentTimeMillis())
        DayFilter.TOMORROW -> dayTitle(System.currentTimeMillis() + 86_400_000L)
        DayFilter.LIVE -> "En vivo ahora"
        DayFilter.NEXT -> "Próximos días"
    }
    Row(Modifier.fillMaxWidth().padding(start = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(title, NeonType.H2, modifier = Modifier.padding(end = 14.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(6.dp)
        ) {
            items(TimeSlot.values().toList(), key = { it.name }) { slot ->
                NeonButton(slot.label, { vm.setSlot(slot) }, selected = ui.slot == slot, height = 32.dp)
            }
        }
    }
}

@Composable
private fun EventRow(event: AgendaEvent, options: Int, showDay: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val now = remember(event.id) { System.currentTimeMillis() }
    val status = event.status(now)
    NeonCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(70.dp),
        radius = 14.dp,
        borderColor = if (status == EventStatus.LIVE) Neon.Red.copy(alpha = 0.5f) else Neon.Stroke
    ) { focused ->
        Row(
            Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.width(70.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (showDay) Txt(dayTitle(event.startMillis), NeonType.Small)
                Txt(timeOf(event.startMillis), NeonType.Time)
                if (!showDay) StatusPill(status)
            }
            Box(Modifier.width(1.dp).height(44.dp).background(Neon.Stroke))
            Box(Modifier.width(12.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Crest(event.competitionLogo, event.competition ?: sportLabel(event.sport), 24.dp)
                Box(Modifier.width(8.dp))
                // Hasta 2 líneas: los nombres de competición largos ("La Liga EA Sports") no se cortan.
                Txt(
                    event.competition ?: sportLabel(event.sport),
                    NeonType.H3.copy(fontSize = 13.sp, lineHeight = 16.sp),
                    maxLines = 2,
                    modifier = Modifier.weight(1f)
                )
            }
            val home = event.home
            val away = event.away
            if (home != null && away != null) {
                TeamsVs(home, event.homeLogo, away, event.awayLogo, crestSize = 30.dp, nameWidth = 66.dp)
            } else {
                Txt(event.title, NeonType.H3, maxLines = 2, modifier = Modifier.width(162.dp))
            }
            Box(Modifier.width(10.dp))
            ViewChannelsPill(focused, options)
        }
    }
}

@Composable
/** "Ver canales" y, si tienes enlaces para el partido, cuántas opciones hay ("· 4"). */
private fun ViewChannelsPill(focused: Boolean, options: Int) {
    Row(
        Modifier
            .background(
                if (focused) Brush.horizontalGradient(listOf(Neon.Cyan.copy(alpha = 0.35f), Neon.Blue.copy(alpha = 0.35f)))
                else Brush.horizontalGradient(listOf(Neon.PanelHi, Neon.PanelHi)),
                RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(R.drawable.ic_play, if (focused) Neon.Text else Neon.Dim, size = 14.dp)
        Txt("Ver canales", NeonType.Body, color = if (focused) Neon.Text else Neon.Dim)
        if (options > 0) Txt("· $options", NeonType.Body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = Neon.Cyan)
    }
}

@Composable
private fun RightColumn(ui: AgendaUi, onOpenEvent: (AgendaEvent) -> Unit, modifier: Modifier) {
    Column(modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().neonPanel(16.dp).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(R.drawable.ic_live, Neon.Violet, size = 40.dp)
            Box(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt("Encuentra dónde ver cada partido", NeonType.H3, maxLines = 2)
                Txt("Elige un encuentro para ver en qué canales se emite.", NeonType.Small, maxLines = 3)
            }
        }
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(R.drawable.ic_star, Neon.Blue, size = 18.dp)
            Box(Modifier.width(8.dp))
            Txt("Destacados de hoy", NeonType.H3)
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(ui.featured, key = { it.id }) { event ->
                FeaturedCard(event) { onOpenEvent(event) }
            }
        }
    }
}

@Composable
private fun FeaturedCard(event: AgendaEvent, onClick: () -> Unit) {
    val now = remember(event.id) { System.currentTimeMillis() }
    NeonCard(onClick = onClick, modifier = Modifier.fillMaxWidth().height(70.dp), radius = 12.dp,
        color = Neon.Panel) { _ ->
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(Neon.Panel, Neon.Violet.copy(alpha = 0.30f))), RoundedCornerShape(12.dp)
            )
        )
        Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Crest(event.homeLogo, event.home ?: event.title, 20.dp)
                    if (event.away != null) Crest(event.awayLogo, event.away, 20.dp)
                }
                Txt(if (event.home != null && event.away != null) "${event.home} vs ${event.away}" else event.title, NeonType.Body)
                Txt(listOfNotNull(event.competition, timeOf(event.startMillis)).joinToString(" · "), NeonType.Small)
            }
            if (event.status(now) == EventStatus.LIVE) StatusPill(EventStatus.LIVE, Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
internal fun Message(title: String, detail: String, actions: @Composable () -> Unit) {
    Column(
        Modifier.padding(6.dp).fillMaxWidth().neonPanel(16.dp).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(R.drawable.ic_calendar, Neon.Cyan, size = 28.dp)
        Txt(title, NeonType.H2)
        Txt(detail, NeonType.Body, color = Neon.Dim, maxLines = 3)
        Box(Modifier.size(4.dp))
        actions()
    }
}
