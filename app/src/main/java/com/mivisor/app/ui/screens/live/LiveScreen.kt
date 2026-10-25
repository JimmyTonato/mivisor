package com.mivisor.app.ui.screens.live

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mivisor.app.AgendaUi
import com.mivisor.app.R
import com.mivisor.app.model.AgendaEvent
import com.mivisor.app.ui.components.Icon
import com.mivisor.app.ui.components.Txt
import com.mivisor.app.ui.kit.AutoFocus
import com.mivisor.app.ui.kit.CompetitionLabel
import com.mivisor.app.ui.kit.EmptyPanel
import com.mivisor.app.ui.kit.LivePill
import com.mivisor.app.ui.kit.PageHeader
import com.mivisor.app.ui.kit.SecondaryButton
import com.mivisor.app.ui.kit.Ui
import com.mivisor.app.ui.neon.LocalAppLayout
import com.mivisor.app.ui.neon.Neon
import com.mivisor.app.ui.neon.NeonCard
import com.mivisor.app.ui.neon.NeonType
import com.mivisor.app.ui.neon.Participant
import com.mivisor.app.ui.neon.eventTime
import com.mivisor.app.ui.neon.sportIcon
import com.mivisor.app.ui.neon.sportLabel
import com.mivisor.app.ui.screens.relativeTime

/** En directo: solo lo que se puede reproducir ahora mismo. Sin calendario mezclado. */
@Composable
fun LiveScreen(
    agenda: AgendaUi,
    onSport: (String?) -> Unit,
    onRefresh: () -> Unit,
    onWatch: (AgendaEvent, String) -> Unit,
    onOpenCalendar: () -> Unit,
    restoreKey: String?,
    autoFocus: Boolean
) {
    val layout = LocalAppLayout.current
    val firstCard = remember { FocusRequester() }
    val firstFilter = remember { FocusRequester() }
    val restore = remember { FocusRequester() }
    val target = when {
        restoreKey != null && agenda.live.any { "live:${it.id}" == restoreKey } -> restore
        agenda.live.isNotEmpty() -> firstCard
        else -> firstFilter
    }
    AutoFocus(target, enabled = autoFocus || restoreKey != null, key = restoreKey)

    Column(Modifier.fillMaxSize().padding(start = if (layout.compact) layout.gutter else 6.dp, end = layout.gutter)) {
        PageHeader(
            title = "En directo ahora",
            subtitle = "Todo lo que puedes ver en este momento.",
            meta = if (agenda.fetchedAt > 0) "Agenda actualizada ${relativeTime(agenda.fetchedAt)}" + (if (agenda.offline) " · sin conexión" else "") else null
        ) {
            if (!layout.compact) SecondaryButton("Actualizar", onRefresh, icon = R.drawable.ic_refresh, height = 34.dp)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "all") {
                SecondaryButton("Todos", { onSport(null) }, Modifier.focusRequester(firstFilter), height = 34.dp, selected = agenda.liveSport == null)
            }
            items(agenda.liveSports, key = { it }) { sport ->
                SecondaryButton(sportLabel(sport), { onSport(sport) }, icon = sportIcon(sport), height = 34.dp, selected = agenda.liveSport == sport)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp)) {
            when {
                agenda.loading -> Txt("Cargando agenda…", NeonType.Body, color = Neon.Dim, modifier = Modifier.padding(8.dp))
                agenda.live.isEmpty() -> EmptyPanel(
                    if (agenda.liveSport == null) "Nada en directo ahora mismo" else "No hay ${sportLabel(agenda.liveSport)} en directo ahora",
                    "Consulta el calendario para ver qué empieza próximamente.",
                    icon = R.drawable.ic_live
                ) {
                    SecondaryButton("Ver calendario", onOpenCalendar, icon = R.drawable.ic_calendar, selected = true)
                    SecondaryButton("Actualizar", onRefresh, icon = R.drawable.ic_refresh)
                }
                else -> LazyVerticalGrid(
                    columns = if (layout.compact) GridCells.Adaptive(160.dp) else GridCells.Fixed(4),
                    contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(agenda.live, key = { "live:${it.id}" }) { event ->
                        val key = "live:${event.id}"
                        val mod = when {
                            key == restoreKey -> Modifier.focusRequester(restore)
                            event === agenda.live.first() -> Modifier.focusRequester(firstCard)
                            else -> Modifier
                        }
                        LiveGridCard(event, agenda.optionCount(event), { onWatch(event, key) }, mod)
                    }
                }
            }
        }
    }
}

/** Tarjeta de En directo: estado y competición arriba, hora, participantes y nº de fuentes. Toda la tarjeta es el control. */
@Composable
private fun LiveGridCard(event: AgendaEvent, sources: Int, onClick: () -> Unit, modifier: Modifier) {
    NeonCard(onClick = onClick, modifier = modifier.fillMaxWidth().height(116.dp), radius = 14.dp) { focused ->
        Box(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(listOf(Neon.Panel, Neon.Red.copy(alpha = if (focused) 0.22f else 0.10f))), RoundedCornerShape(14.dp)
            )
        )
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LivePill()
                Box(Modifier.weight(1f))
                CompetitionLabel(event)
            }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt(eventTime(event), NeonType.H3.copy(fontSize = 16.sp))
                Box(Modifier.weight(1f))
                if (sources > 0) Txt(if (sources == 1) "1 fuente" else "$sources fuentes", Ui.CardSmall.copy(color = Neon.Cyan, fontWeight = FontWeight.Bold))
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                val home = event.home
                val away = event.away
                if (home != null && away != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Participant(event.homeVisual, home, 30.dp, event.isPersonVsPerson)
                        Box(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Txt(home, Ui.CardTitle)
                            Txt(away, Ui.CardTitle)
                        }
                        Box(Modifier.width(6.dp))
                        Participant(event.awayVisual, away, 30.dp, event.isPersonVsPerson)
                        Icon(R.drawable.ic_chevron_right, if (focused) Neon.Cyan else Neon.Faint, size = 16.dp)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt(event.title, Ui.CardTitle.copy(fontSize = 14.sp), maxLines = 2, modifier = Modifier.weight(1f))
                        Icon(sportIcon(event.sport), Neon.Text, size = 28.dp)
                        Icon(R.drawable.ic_chevron_right, if (focused) Neon.Cyan else Neon.Faint, size = 16.dp)
                    }
                }
            }
        }
    }
}
