package com.mivisor.app.ui.screens.competition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.mivisor.app.AgendaUi
import com.mivisor.app.R
import com.mivisor.app.model.AgendaEvent
import com.mivisor.app.model.CalendarFilters
import com.mivisor.app.model.Competition
import com.mivisor.app.model.CompetitionSummaries
import com.mivisor.app.model.DayClock
import com.mivisor.app.ui.components.Txt
import com.mivisor.app.ui.kit.AutoFocus
import com.mivisor.app.ui.kit.BackLink
import com.mivisor.app.ui.kit.Clock
import com.mivisor.app.ui.kit.EmptyPanel
import com.mivisor.app.ui.kit.SecondaryButton
import com.mivisor.app.ui.kit.SectionTitle
import com.mivisor.app.ui.kit.Ui
import com.mivisor.app.ui.neon.LocalAppLayout
import com.mivisor.app.ui.neon.NeonType
import com.mivisor.app.ui.screens.calendar.CalendarEventRow
import com.mivisor.app.ui.screens.home.CompetitionIcon

/**
 * Página de competición. La plantilla cambia con el deporte:
 *  - Ligas: En directo · Hoy · Próximos
 *  - Motor: Ahora · Este fin de semana · Próximos grandes premios
 *  - Torneos (tenis, UFC): En directo · Hoy · Próximos
 */
@Composable
fun CompetitionScreen(
    competition: Competition,
    agenda: AgendaUi,
    onBack: () -> Unit,
    onOpenEvent: (AgendaEvent, String) -> Unit,
    onOpenCalendar: () -> Unit,
    restoreKey: String?
) {
    val layout = LocalAppLayout.current
    val clock = remember { DayClock() }
    val events = agenda.agenda?.events.orEmpty()
    val sections = remember(events, agenda.now, competition) {
        CalendarFilters.competitionSections(competition, events, agenda.now, clock)
    }
    val summary = remember(events, agenda.now, competition) {
        CompetitionSummaries.summary(competition, events.filter { Competition.of(it) == competition }, agenda.now, clock)
    }
    val first = remember { FocusRequester() }
    val restore = remember { FocusRequester() }
    val firstKey = sections.firstOrNull()?.second?.firstOrNull()?.let { "comp:${it.id}" }
    AutoFocus(if (restoreKey != null && restoreKey != firstKey && sections.any { s -> s.second.any { "comp:${it.id}" == restoreKey } }) restore else first, key = restoreKey)

    Column(Modifier.fillMaxSize().padding(start = if (layout.compact) layout.gutter else 6.dp, end = layout.gutter)) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            BackLink("Inicio", onBack)
            Box(Modifier.weight(1f))
            if (!layout.compact) Clock()
        }
        Row(Modifier.padding(top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CompetitionIcon(competition, 46.dp)
            Box(Modifier.width(14.dp))
            Column {
                Txt(competition.label, Ui.PageTitle)
                Txt(listOfNotNull(summary.text.ifEmpty { null }, summary.liveText).joinToString(" · "), NeonType.HeroSub)
            }
        }
        if (sections.isEmpty()) {
            EmptyPanel(
                "No hay eventos de ${competition.label} en los próximos ${CalendarFilters.DAYS_AHEAD} días",
                "Aparecerán aquí en cuanto la agenda los publique.",
                icon = R.drawable.ic_calendar
            ) {
                SecondaryButton("Ver calendario completo", onOpenCalendar, Modifier.focusRequester(first), icon = R.drawable.ic_calendar, selected = true)
            }
        } else LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            sections.forEach { (title, list) ->
                item(key = "h_$title") { SectionTitle(title, Modifier.padding(top = 8.dp, bottom = 2.dp), count = list.size) }
                items(list, key = { "comp:${it.id}" }) { event ->
                    val key = "comp:${event.id}"
                    val mod = when {
                        key == firstKey -> Modifier.focusRequester(first)
                        key == restoreKey -> Modifier.focusRequester(restore)
                        else -> Modifier
                    }
                    CalendarEventRow(
                        event, agenda.now, showDay = clock.dayIndex(agenda.now, event.startMillis) != 0, clock = clock,
                        sources = agenda.optionCount(event), onClick = { onOpenEvent(event, key) }, modifier = mod, compact = layout.compact
                    )
                }
            }
            item(key = "calendar") {
                SecondaryButton("Ver calendario completo", onOpenCalendar, Modifier.padding(top = 10.dp), icon = R.drawable.ic_calendar)
            }
        }
    }
}
