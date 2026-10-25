package com.mivisor.app.ui.screens.calendar

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mivisor.app.AgendaUi
import com.mivisor.app.R
import com.mivisor.app.model.AgendaEvent
import com.mivisor.app.model.CalendarFilters
import com.mivisor.app.model.CalendarRange
import com.mivisor.app.model.DayClock
import com.mivisor.app.model.EventStatus
import com.mivisor.app.model.displayTitle
import com.mivisor.app.ui.components.Icon
import com.mivisor.app.ui.components.TvDialog
import com.mivisor.app.ui.components.Txt
import com.mivisor.app.ui.kit.AutoFocus
import com.mivisor.app.ui.kit.CompetitionLabel
import com.mivisor.app.ui.kit.EmptyPanel
import com.mivisor.app.ui.kit.InfoLine
import com.mivisor.app.ui.kit.PageHeader
import com.mivisor.app.ui.kit.PrimaryButton
import com.mivisor.app.ui.kit.SecondaryButton
import com.mivisor.app.ui.kit.StatusBadge
import com.mivisor.app.ui.neon.LocalAppLayout
import com.mivisor.app.ui.neon.Neon
import com.mivisor.app.ui.neon.NeonCard
import com.mivisor.app.ui.neon.NeonType
import com.mivisor.app.ui.neon.Participant
import com.mivisor.app.ui.neon.dayTitle
import com.mivisor.app.ui.neon.eventTime
import com.mivisor.app.ui.neon.eventTimeText
import com.mivisor.app.ui.neon.sportIcon
import com.mivisor.app.ui.neon.sportLabel
import com.mivisor.app.ui.screens.relativeTime

/**
 * Calendario (evolución de la Agenda): consultar lo que viene. Filtros de día (Hoy, Mañana, Esta semana,
 * Elegir fecha) y de competición; listado cronológico compacto y, en TV, el detalle del evento enfocado.
 */
@Composable
fun CalendarScreen(
    agenda: AgendaUi,
    onRange: (CalendarRange) -> Unit,
    onDay: (Int) -> Unit,
    onCompetition: (String?) -> Unit,
    onRefresh: () -> Unit,
    onOpenEvent: (AgendaEvent, String) -> Unit,
    onWatch: (AgendaEvent, String) -> Unit,
    back: Pair<String, () -> Unit>?,
    restoreKey: String?,
    autoFocus: Boolean
) {
    val layout = LocalAppLayout.current
    val clock = remember { DayClock() }
    val query = agenda.calendar
    val events = agenda.calendarEvents
    var focusedId by remember { mutableStateOf<String?>(null) }
    var pickDate by remember { mutableStateOf(false) }
    val firstFilter = remember { FocusRequester() }
    val firstRow = remember { FocusRequester() }
    val restore = remember { FocusRequester() }
    val target = when {
        restoreKey != null && events.any { "cal:${it.id}" == restoreKey } -> restore
        events.isNotEmpty() -> firstRow
        else -> firstFilter
    }
    AutoFocus(target, enabled = autoFocus || restoreKey != null, key = restoreKey)

    val focused = events.firstOrNull { it.id == focusedId } ?: events.firstOrNull()
    val showDay = query.range == CalendarRange.WEEK

    Column(Modifier.fillMaxSize().padding(start = if (layout.compact) layout.gutter else 6.dp, end = layout.gutter)) {
        PageHeader(
            title = "Calendario",
            subtitle = "Consulta los próximos encuentros y descubre en qué canales se emiten.",
            meta = agenda.agenda?.let { "Actualizado ${if (agenda.fetchedAt > 0) relativeTime(agenda.fetchedAt) else "—"}" + (if (agenda.offline) " · sin conexión" else "") },
            back = back
        ) {
            if (!layout.compact) SecondaryButton("Actualizar", onRefresh, icon = R.drawable.ic_refresh, height = 34.dp)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 4.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(CalendarRange.values().toList(), key = { "r_${it.name}" }) { range ->
                val label = if (range == CalendarRange.DATE && query.range == CalendarRange.DATE) "Fecha: ${clock.shortDay(agenda.now, clock.startOfDay(agenda.now, query.dayOffset))}" else range.label
                SecondaryButton(
                    label,
                    { if (range == CalendarRange.DATE) pickDate = true else onRange(range) },
                    if (range == CalendarRange.TODAY) Modifier.focusRequester(firstFilter) else Modifier,
                    icon = if (range == CalendarRange.DATE) R.drawable.ic_calendar else null,
                    height = 32.dp,
                    selected = query.range == range
                )
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 4.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(agenda.calendarChips, key = { "c_${it.key ?: "all"}" }) { chip ->
                SecondaryButton(
                    chip.label, { onCompetition(chip.key) },
                    icon = chip.competition?.let { sportIcon(it.sport) },
                    height = 32.dp, selected = query.competition == chip.key
                )
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when {
                    agenda.loading -> Txt("Cargando agenda…", NeonType.Body, color = Neon.Dim, modifier = Modifier.padding(8.dp))
                    agenda.error != null && agenda.agenda == null -> EmptyPanel("No se pudo cargar el calendario", agenda.error, danger = true) {
                        SecondaryButton("Reintentar", onRefresh, icon = R.drawable.ic_refresh, selected = true)
                    }
                    events.isEmpty() -> EmptyPanel(emptyTitle(agenda), "Prueba con otro día o con otra competición.", icon = R.drawable.ic_calendar) {
                        if (query.range != CalendarRange.WEEK) SecondaryButton("Ver esta semana", { onRange(CalendarRange.WEEK) }, selected = true)
                        if (query.competition != null) SecondaryButton("Todas las competiciones", { onCompetition(null) })
                    }
                    else -> LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(events, key = { "cal:${it.id}" }) { event ->
                            val key = "cal:${event.id}"
                            val mod = when {
                                key == restoreKey -> Modifier.focusRequester(restore)
                                event === events.first() -> Modifier.focusRequester(firstRow)
                                else -> Modifier
                            }
                            CalendarEventRow(
                                event, agenda.now, showDay, clock, agenda.optionCount(event),
                                onClick = { onOpenEvent(event, key) },
                                modifier = mod.onFocusChanged { if (it.isFocused) focusedId = event.id },
                                compact = layout.compact
                            )
                        }
                    }
                }
            }
            if (layout.expanded && focused != null) {
                DetailPanel(agenda, focused, { onWatch(focused, "cal:${focused.id}") }, { onOpenEvent(focused, "cal:${focused.id}") }, Modifier.width(240.dp).fillMaxHeight())
            }
        }
    }

    if (pickDate) {
        DatePickerDialog(agenda.now, clock, query.dayOffset.takeIf { query.range == CalendarRange.DATE }, onPick = { pickDate = false; onDay(it) }, onDismiss = { pickDate = false })
    }
}

private fun emptyTitle(agenda: AgendaUi): String {
    val chip = agenda.calendarChips.firstOrNull { it.key != null && it.key == agenda.calendar.competition }?.label
    val period = when (agenda.calendar.range) {
        CalendarRange.TODAY -> "para hoy"
        CalendarRange.TOMORROW -> "para mañana"
        CalendarRange.WEEK -> "esta semana"
        CalendarRange.DATE -> "ese día"
    }
    return if (chip == null) "No hay eventos $period" else "No hay eventos de $chip $period"
}

@Composable
internal fun CalendarEventRow(
    event: AgendaEvent,
    now: Long,
    showDay: Boolean,
    clock: DayClock,
    sources: Int,
    onClick: () -> Unit,
    modifier: Modifier,
    compact: Boolean
) {
    val status = event.status(now)
    NeonCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(if (compact) 64.dp else 50.dp),
        radius = 12.dp,
        borderColor = if (status == EventStatus.LIVE) Neon.Red.copy(alpha = 0.45f) else Neon.Stroke,
        contentAlignment = Alignment.CenterStart
    ) { focused ->
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(if (showDay) 62.dp else 48.dp)) {
                if (showDay) Txt(clock.shortDay(now, event.startMillis).replaceFirstChar { it.uppercase() }, NeonType.Small.copy(fontSize = 10.sp))
                Txt(if (status == EventStatus.FINISHED) "Final" else eventTime(event), NeonType.H3.copy(fontSize = 15.sp))
            }
            if (!compact) Box(Modifier.width(96.dp)) { StatusBadge(status) }
            if (!compact) CompetitionLabel(event, Modifier.width(120.dp), size = 16.dp, style = NeonType.Small.copy(color = Neon.Dim))
            val home = event.home
            val away = event.away
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                if (home != null && away != null) {
                    Participant(event.homeVisual, home, 22.dp, event.isPersonVsPerson)
                    Txt(home, NeonType.Body, modifier = Modifier.padding(start = 6.dp).weight(1f))
                    Txt("vs", NeonType.Small, color = Neon.Faint, modifier = Modifier.padding(horizontal = 6.dp))
                    Participant(event.awayVisual, away, 22.dp, event.isPersonVsPerson)
                    Txt(away, NeonType.Body, modifier = Modifier.padding(start = 6.dp).weight(1f))
                } else {
                    Icon(sportIcon(event.sport), Neon.Dim, size = 18.dp)
                    Txt(event.title, NeonType.Body, modifier = Modifier.padding(start = 8.dp).weight(1f))
                }
            }
            if (sources > 0) Txt("$sources", NeonType.Pill.copy(fontWeight = FontWeight.Bold), color = Neon.Cyan, modifier = Modifier.padding(horizontal = 6.dp))
            Icon(R.drawable.ic_chevron_right, if (focused) Neon.Cyan else Neon.Faint, size = 16.dp)
        }
    }
}

/** Columna derecha: el evento enfocado, con "Ver ahora" y "Ver canales" a un ▶ de distancia. */
@Composable
private fun DetailPanel(agenda: AgendaUi, event: AgendaEvent, onWatch: () -> Unit, onChannels: () -> Unit, modifier: Modifier) {
    val status = event.status(agenda.now)
    val sources = agenda.optionCount(event)
    val channels = agenda.channelsOf(event)
    Column(
        modifier.background(Neon.Panel.copy(alpha = 0.9f), RoundedCornerShape(16.dp)).border(1.dp, Neon.Stroke, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Txt(event.displayTitle(), NeonType.H3.copy(fontSize = 15.sp), maxLines = 2)
        val home = event.home
        val away = event.away
        if (home != null && away != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Participant(event.homeVisual, home, 42.dp, event.isPersonVsPerson)
                Txt("vs", NeonType.Small, color = Neon.Faint)
                Participant(event.awayVisual, away, 42.dp, event.isPersonVsPerson)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(status)
            Box(Modifier.width(8.dp))
            Txt(eventTimeText(event), NeonType.H3)
        }
        PrimaryButton("Ver ahora", onWatch, Modifier.fillMaxWidth(), height = 34.dp)
        SecondaryButton(if (sources > 1) "Ver $sources fuentes" else "Ver canales", onChannels, Modifier.fillMaxWidth(), icon = R.drawable.ic_tv, height = 34.dp)
        Txt("Información", NeonType.H3.copy(fontSize = 13.sp), modifier = Modifier.padding(top = 4.dp))
        InfoLine(R.drawable.ic_calendar, "Fecha", "${dayTitle(event.startMillis)}, ${eventTimeText(event)}")
        InfoLine(R.drawable.ic_trophy, "Competición", event.competition ?: sportLabel(event.sport))
        InfoLine(R.drawable.ic_tv, "Emisión", channels.take(3).joinToString(", ") { it.name }.ifEmpty { "Por confirmar" })
        InfoLine(R.drawable.ic_layers, "Tus fuentes", if (sources == 0) "Ninguna" else sources.toString(), if (sources > 0) Neon.Cyan else Neon.Dim)
    }
}

@Composable
private fun DatePickerDialog(now: Long, clock: DayClock, selected: Int?, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    TvDialog(title = "Elegir fecha", subtitle = "Próximos ${CalendarFilters.DAYS_AHEAD} días", onDismiss = onDismiss, width = 560.dp, initialFocus = first) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxWidth().height(200.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(4.dp)
        ) {
            items(CalendarFilters.DAYS_AHEAD) { offset ->
                val day = clock.startOfDay(now, offset)
                val label = when (offset) {
                    0 -> "Hoy"
                    1 -> "Mañana"
                    else -> clock.format("EEE d MMM", day).replace(".", "").replaceFirstChar { it.uppercase() }
                }
                SecondaryButton(
                    label, { onPick(offset) },
                    Modifier.fillMaxWidth().let { if (offset == (selected ?: 0)) it.focusRequester(first) else it },
                    height = 36.dp, selected = offset == selected
                )
            }
        }
    }
}
