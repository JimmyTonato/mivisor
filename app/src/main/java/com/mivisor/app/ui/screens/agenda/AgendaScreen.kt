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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
import com.mivisor.app.model.AgendaFilters
import com.mivisor.app.model.Channel
import com.mivisor.app.model.EventStatus
import com.mivisor.app.model.SourceStatus
import com.mivisor.app.model.sourceNoticeText
import com.mivisor.app.ui.components.Icon
import com.mivisor.app.ui.components.Txt
import com.mivisor.app.ui.neon.Crest
import com.mivisor.app.ui.neon.Participant
import com.mivisor.app.ui.neon.eventTime
import com.mivisor.app.ui.neon.eventTimeText
import com.mivisor.app.ui.neon.FilterChip
import com.mivisor.app.ui.neon.LocalAppLayout
import com.mivisor.app.ui.neon.Neon
import com.mivisor.app.ui.neon.NeonButton
import com.mivisor.app.ui.neon.NeonCard
import com.mivisor.app.ui.neon.NeonType
import com.mivisor.app.ui.neon.ScreenHero
import com.mivisor.app.ui.neon.sportInSentence
import com.mivisor.app.ui.neon.SideItem
import com.mivisor.app.ui.neon.StatusPill
import com.mivisor.app.ui.neon.TeamsVs
import com.mivisor.app.ui.neon.dayTitle
import com.mivisor.app.ui.neon.neonPanel
import com.mivisor.app.ui.neon.sportIcon
import com.mivisor.app.ui.neon.sportLabel
import com.mivisor.app.ui.neon.timeOf
import com.mivisor.app.ui.screens.relativeTime

/**
 * Agenda deportiva: lista y detalle ("Canales del encuentro"). Se adapta al tamaño:
 *  - TV / tablet horizontal: filtros a la izquierda, lista y columna de destacados.
 *  - Tablet vertical: filtros + lista (sin destacados).
 *  - Móvil: filtros en filas de chips y filas de partido apiladas.
 */
@Composable
fun AgendaScreen(onOpenChannel: (Channel) -> Unit, vm: AgendaViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<AgendaEvent?>(null) }
    var lastOpenedId by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        vm.load()
        vm.refreshIndex()
    }
    BackHandler(enabled = selected != null) { selected = null }

    val event = selected
    if (event == null) {
        AgendaHome(
            ui = ui,
            vm = vm,
            listState = listState,
            restoreFocusId = lastOpenedId,
            onOpenEvent = { lastOpenedId = it.id; selected = it }
        )
    } else {
        EventDetail(
            event = event,
            ui = ui,
            vm = vm,
            onBack = { selected = null },
            onOpenChannel = onOpenChannel,
            onOpenEvent = { lastOpenedId = it.id; selected = it }
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AgendaHome(
    ui: AgendaUi,
    vm: AgendaViewModel,
    listState: LazyListState,
    restoreFocusId: String?,
    onOpenEvent: (AgendaEvent) -> Unit
) {
    val layout = LocalAppLayout.current
    val firstFilter = remember { FocusRequester() }
    val restore = remember { FocusRequester() }

    // Al volver del detalle, el foco vuelve al partido que se abrió.
    LaunchedEffect(Unit) {
        if (restoreFocusId == null || ui.visible.none { it.id == restoreFocusId }) return@LaunchedEffect
        repeat(3) {
            withFrameNanos { }
            if (runCatching { restore.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = layout.gutter)) {
        ScreenHero(
            title = "Agenda deportiva",
            subtitle = "Consulta los próximos encuentros y descubre en qué canales se emiten.",
            status = statusLine(ui),
            statusColor = if (ui.offline) Color(0xFFFFC857) else Neon.Faint
        ) {
            if (!layout.compact) NeonButton("Actualizar", { vm.load(force = true) }, icon = R.drawable.ic_refresh)
        }

        if (layout.compact) {
            CompactFilters(ui, vm, firstFilter)
            EventList(ui, listState, restoreFocusId, restore, onOpenEvent, compact = true, modifier = Modifier.weight(1f), vm = vm)
        } else {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SideFilters(ui, vm, firstFilter, Modifier.width(150.dp).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    DayHeader(ui, vm)
                    SubFilterRow(ui, vm)
                    EventList(ui, listState, restoreFocusId, restore, onOpenEvent, compact = false, modifier = Modifier.weight(1f), vm = vm)
                }
                if (layout.expanded) RightColumn(ui, onOpenEvent, Modifier.width(220.dp).fillMaxHeight())
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun EventList(
    ui: AgendaUi,
    listState: LazyListState,
    restoreFocusId: String?,
    restore: FocusRequester,
    onOpenEvent: (AgendaEvent) -> Unit,
    compact: Boolean,
    modifier: Modifier,
    vm: AgendaViewModel
) {
    val notices = ui.agenda?.notices(ui.sport).orEmpty()
    Column(modifier.fillMaxWidth()) {
        // Avisos discretos por deporte cuando su proveedor ha fallado (stale / unavailable).
        if (!ui.loading && notices.isNotEmpty()) {
            Column(Modifier.padding(start = 6.dp, end = 6.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                notices.forEach { (sport, status) -> SourceNotice(sport, status) }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                ui.loading -> Txt("Cargando agenda…", NeonType.Body, color = Neon.Dim, modifier = Modifier.padding(8.dp))
                ui.error != null && ui.agenda == null -> Message("No se pudo cargar la agenda", ui.error) {
                    NeonButton("Reintentar", { vm.load(force = true) }, icon = R.drawable.ic_refresh)
                }
                ui.visible.isEmpty() -> Message(emptyTitle(ui), emptyDetail(ui)) {}
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().focusRestorer(),
                    // Margen para que el halo del foco no se recorte.
                    contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(ui.visible, key = { it.id }, contentType = { "event" }) { event ->
                        val mod = if (event.id == restoreFocusId) Modifier.focusRequester(restore) else Modifier
                        if (compact) {
                            EventCardCompact(event, ui.optionCount(event), mod) { onOpenEvent(event) }
                        } else {
                            EventRow(event, ui.optionCount(event), showDay = ui.day == DayFilter.NEXT, modifier = mod) { onOpenEvent(event) }
                        }
                    }
                }
            }
        }
    }
}

private val Amber = Color(0xFFF59E0B)

@Composable
private fun SourceNotice(sport: String, status: SourceStatus) {
    val color = if (status == SourceStatus.STALE) Amber else Neon.Red
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color, RoundedCornerShape(50)))
        Box(Modifier.width(8.dp))
        Txt(sourceNoticeText(sportInSentence(sport), status), NeonType.Small, color = Neon.Dim, maxLines = 2)
    }
}

private fun statusLine(ui: AgendaUi): String {
    val agenda = ui.agenda ?: return ""
    val updated = if (ui.fetchedAt > 0) relativeTime(ui.fetchedAt) else "—"
    return if (ui.offline) "Sin conexión · datos de $updated · ${agenda.source}"
    else "Actualizado $updated · fuente: ${agenda.source}"
}

/** Categorías: también las que hoy no tienen eventos (MotoGP sin carrera sigue apareciendo). */
private fun sportsOf(ui: AgendaUi) =
    ui.agenda?.categories?.takeIf { it.isNotEmpty() } ?: listOf("futbol", "tenis", "formula1", "motogp")

/** "No hay eventos de MotoGP para hoy" / "…de LaLiga para mañana"… */
private fun emptyTitle(ui: AgendaUi): String {
    val what = ui.subOptions.firstOrNull { it.key != null && it.key == ui.subKey }?.label
        ?: ui.sport?.let(::sportLabel)
    val period = when (ui.day) {
        DayFilter.TODAY -> "para hoy"
        DayFilter.TOMORROW -> "para mañana"
        DayFilter.LIVE -> "en directo ahora"
        DayFilter.NEXT -> "en los próximos días"
    }
    return if (what == null) {
        if (ui.day == DayFilter.LIVE) "Nada en directo ahora mismo" else "No hay eventos $period"
    } else "No hay eventos de $what $period"
}

private fun emptyDetail(ui: AgendaUi): String {
    val sport = ui.sport ?: return "Prueba con otro día, otra franja horaria u otro deporte."
    val agenda = ui.agenda
    return when {
        agenda?.problems?.get(sport) == SourceStatus.UNAVAILABLE ->
            sourceNoticeText(sportInSentence(sport), SourceStatus.UNAVAILABLE)
        agenda != null && agenda.eventCount(sport) == 0 ->
            "No hay eventos de ${sportLabel(sport)} en los próximos 14 días. Aparecerán aquí en cuanto haya."
        else -> "Prueba con otro día u otra franja horaria."
    }
}

private val DAYS = listOf(DayFilter.TODAY, DayFilter.TOMORROW, DayFilter.LIVE, DayFilter.NEXT)

private fun dayLabel(day: DayFilter, ui: AgendaUi) =
    if (day == DayFilter.LIVE && ui.liveCount > 0) "${day.label} · ${ui.liveCount}" else day.label

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SideFilters(ui: AgendaUi, vm: AgendaViewModel, firstFocus: FocusRequester, modifier: Modifier) {
    LazyColumn(
        modifier.focusRestorer(),
        contentPadding = PaddingValues(6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        items(DAYS, key = { "d_${it.name}" }) { day ->
            SideItem(
                label = dayLabel(day, ui),
                icon = if (day == DayFilter.LIVE) R.drawable.ic_live else R.drawable.ic_calendar,
                selected = ui.day == day,
                modifier = if (day == DayFilter.TODAY) Modifier.focusRequester(firstFocus) else Modifier,
                onClick = { vm.setDay(day) },
                height = 38.dp
            )
        }
        item(key = "sep") { Box(Modifier.height(4.dp)) }
        items(sportsOf(ui), key = { "s_$it" }) { sport ->
            SideItem(
                label = sportLabel(sport),
                icon = sportIcon(sport),
                selected = ui.sport == sport,
                onClick = { vm.toggleSport(sport) },
                height = 38.dp
            )
        }
    }
}

/** Móvil: tres filas deslizables de chips (día, deporte, franja). */
@Composable
private fun CompactFilters(ui: AgendaUi, vm: AgendaViewModel, firstFocus: FocusRequester) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        LazyRow(contentPadding = PaddingValues(6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(DAYS, key = { it.name }) { day ->
                FilterChip(
                    dayLabel(day, ui), ui.day == day, { vm.setDay(day) },
                    icon = if (day == DayFilter.LIVE) R.drawable.ic_live else null,
                    modifier = if (day == DayFilter.TODAY) Modifier.focusRequester(firstFocus) else Modifier
                )
            }
        }
        LazyRow(contentPadding = PaddingValues(6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sportsOf(ui), key = { it }) { sport ->
                FilterChip(sportLabel(sport), ui.sport == sport, { vm.toggleSport(sport) }, icon = sportIcon(sport))
            }
            items(TimeSlot.values().toList(), key = { it.name }) { slot ->
                FilterChip(slot.label, ui.slot == slot, { vm.setSlot(slot) })
            }
        }
        SubFilterRow(ui, vm)
    }
}

/**
 * Segundo nivel de filtros (hoy: competiciones de Fútbol). Solo aparece si el deporte activo
 * tiene subfiltros. "Todos" va siempre primero; filtra en local, sin red.
 */
@Composable
private fun SubFilterRow(ui: AgendaUi, vm: AgendaViewModel) {
    if (!AgendaFilters.hasSubFilters(ui.sport) || ui.subOptions.isEmpty()) return
    LazyRow(contentPadding = PaddingValues(6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(ui.subOptions, key = { "sub_${it.key ?: "all"}" }) { option ->
            FilterChip(option.label, ui.subKey == option.key, { vm.setSubFilter(option.key) })
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
    Row(Modifier.fillMaxWidth().padding(start = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(title, NeonType.H2, modifier = Modifier.padding(end = 10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(6.dp)) {
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
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(70.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (showDay) Txt(dayTitle(event.startMillis), NeonType.Small)
                Txt(eventTime(event), NeonType.Time)
                if (!showDay) StatusPill(status)
            }
            Box(Modifier.width(1.dp).height(44.dp).background(Neon.Stroke))
            Box(Modifier.width(12.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Crest(event.competitionLogo, event.competition ?: sportLabel(event.sport), 24.dp)
                Box(Modifier.width(8.dp))
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
                TeamsVs(home, event.homeVisual, away, event.awayVisual, crestSize = 30.dp, nameWidth = 66.dp, portrait = event.isPersonVsPerson)
            } else {
                Txt(event.title, NeonType.H3, maxLines = 2, modifier = Modifier.width(162.dp))
            }
            Box(Modifier.width(10.dp))
            ViewChannelsPill(focused, options)
        }
    }
}

/** Móvil: tarjeta apilada (hora/estado arriba, equipos en medio, "Ver canales" abajo). */
@Composable
private fun EventCardCompact(event: AgendaEvent, options: Int, modifier: Modifier, onClick: () -> Unit) {
    val now = remember(event.id) { System.currentTimeMillis() }
    val status = event.status(now)
    NeonCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        radius = 14.dp,
        borderColor = if (status == EventStatus.LIVE) Neon.Red.copy(alpha = 0.5f) else Neon.Stroke
    ) { focused ->
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(eventTime(event), NeonType.Time.copy(fontSize = 18.sp))
                Box(Modifier.width(8.dp))
                StatusPill(status)
                Box(Modifier.width(10.dp))
                Crest(event.competitionLogo, event.competition ?: sportLabel(event.sport), 18.dp)
                Box(Modifier.width(6.dp))
                Txt(event.competition ?: sportLabel(event.sport), NeonType.Small, modifier = Modifier.weight(1f))
            }
            val home = event.home
            val away = event.away
            if (home != null && away != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Participant(event.homeVisual, home, 26.dp, event.isPersonVsPerson)
                    Box(Modifier.width(8.dp))
                    Txt(home, NeonType.H3, modifier = Modifier.weight(1f))
                    Txt("VS", NeonType.Small.copy(fontWeight = FontWeight.Bold), color = Neon.Faint, modifier = Modifier.padding(horizontal = 6.dp))
                    Txt(away, NeonType.H3.copy(textAlign = androidx.compose.ui.text.style.TextAlign.End), modifier = Modifier.weight(1f))
                    Box(Modifier.width(8.dp))
                    Participant(event.awayVisual, away, 26.dp, event.isPersonVsPerson)
                }
            } else {
                Txt(event.title, NeonType.H3, maxLines = 2)
            }
            ViewChannelsPill(focused, options, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ViewChannelsPill(focused: Boolean, options: Int, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(
                if (focused) Brush.horizontalGradient(listOf(Neon.Cyan.copy(alpha = 0.35f), Neon.Blue.copy(alpha = 0.35f)))
                else Brush.horizontalGradient(listOf(Neon.PanelHi, Neon.PanelHi)),
                RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
    ) {
        Icon(R.drawable.ic_play, if (focused) Neon.Text else Neon.Dim, size = 14.dp)
        Txt("Ver canales", NeonType.Body, color = if (focused) Neon.Text else Neon.Dim)
        if (options > 0) Txt("· $options", NeonType.Body.copy(fontWeight = FontWeight.Bold), color = Neon.Cyan)
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
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ui.featured, key = { it.id }) { event -> FeaturedCard(event) { onOpenEvent(event) } }
        }
    }
}

@Composable
private fun FeaturedCard(event: AgendaEvent, onClick: () -> Unit) {
    val now = remember(event.id) { System.currentTimeMillis() }
    NeonCard(onClick = onClick, modifier = Modifier.fillMaxWidth().height(70.dp), radius = 12.dp) { _ ->
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(Neon.Panel, Neon.Violet.copy(alpha = 0.30f))), RoundedCornerShape(12.dp)
            )
        )
        Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Participant(event.homeVisual, event.home ?: event.title, 20.dp, event.isPersonVsPerson)
                    if (event.away != null) Participant(event.awayVisual, event.away, 20.dp, event.isPersonVsPerson)
                }
                Txt(if (event.home != null && event.away != null) "${event.home} vs ${event.away}" else event.title, NeonType.Body)
                Txt(listOfNotNull(event.competition, eventTimeText(event)).joinToString(" · "), NeonType.Small)
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
