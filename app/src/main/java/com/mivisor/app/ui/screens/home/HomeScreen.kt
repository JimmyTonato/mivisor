package com.mivisor.app.ui.screens.home

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mivisor.app.AgendaUi
import com.mivisor.app.QuickServicesUi
import com.mivisor.app.R
import com.mivisor.app.model.AgendaEvent
import com.mivisor.app.model.Competition
import com.mivisor.app.model.CompetitionSummary
import com.mivisor.app.model.EventStatus
import com.mivisor.app.model.displayTitle
import com.mivisor.app.services.RowAction
import com.mivisor.app.ui.components.Icon
import com.mivisor.app.ui.components.Txt
import com.mivisor.app.ui.kit.AutoFocus
import com.mivisor.app.ui.kit.Clock
import com.mivisor.app.ui.kit.CompetitionLabel
import com.mivisor.app.ui.kit.EventCard
import com.mivisor.app.ui.kit.FitText
import com.mivisor.app.ui.kit.LivePill
import com.mivisor.app.ui.kit.PrimaryButton
import com.mivisor.app.ui.kit.SecondaryButton
import com.mivisor.app.ui.kit.SectionTitle
import com.mivisor.app.ui.kit.SoftPill
import com.mivisor.app.ui.kit.Ui
import com.mivisor.app.ui.neon.LocalAppLayout
import com.mivisor.app.ui.neon.Neon
import com.mivisor.app.ui.neon.NeonCard
import com.mivisor.app.ui.neon.NeonType
import com.mivisor.app.ui.neon.Participant
import com.mivisor.app.ui.neon.dayTitle
import com.mivisor.app.ui.neon.eventTimeText
import com.mivisor.app.ui.neon.sportIcon
import com.mivisor.app.ui.shell.ServiceChips

/**
 * Inicio: ¿qué puedo ver?, ¿qué pasa ahora?, ¿qué competiciones tengo?, ¿qué empieza pronto?
 * En una TV 16:9 caben las cuatro secciones sin desplazarse (Hero, Tus competiciones, En directo ahora, Empieza pronto).
 */
@Composable
fun HomeScreen(
    agenda: AgendaUi,
    services: QuickServicesUi,
    onServiceOk: (RowAction) -> Unit,
    onWatch: (AgendaEvent, String) -> Unit,
    onOpenEvent: (AgendaEvent, String) -> Unit,
    onOpenCompetition: (Competition, String) -> Unit,
    onOpenCalendar: (String) -> Unit,
    onOpenLive: () -> Unit,
    onAddProvider: () -> Unit,
    restoreKey: String?,
    autoFocus: Boolean
) {
    val layout = LocalAppLayout.current
    val feed = agenda.home
    val now = agenda.now
    val heroFocus = remember { FocusRequester() }
    val restore = remember { FocusRequester() }
    AutoFocus(if (restoreKey != null) restore else heroFocus, enabled = autoFocus || restoreKey != null, key = restoreKey)

    fun mod(key: String): Modifier = if (key == restoreKey) Modifier.focusRequester(restore) else Modifier

    val body: @Composable () -> Unit = {
        Hero(agenda, heroFocus, restoreKey, restore, onWatch, onOpenEvent, onOpenCalendar, onAddProvider)

        SectionTitle("Tus competiciones", Modifier.padding(top = 8.dp, start = 2.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 4.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(feed.competitions, key = { "comp:${it.competition.name}" }) { summary ->
                val key = "comp:${summary.competition.name}"
                CompetitionCard(summary, { onOpenCompetition(summary.competition, key) }, mod(key))
            }
        }

        SectionTitle("En directo ahora", Modifier.padding(top = 2.dp, start = 2.dp), count = feed.live.size.takeIf { it > 0 })
        if (feed.live.isEmpty()) {
            QuietLine(if (agenda.loading) "Cargando agenda…" else "Ahora mismo no hay nada en directo.")
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 4.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(feed.live, key = { "live:${it.id}" }) { event ->
                    val key = "live:${event.id}"
                    EventCard(event, now, { onWatch(event, key) }, mod(key), width = cardWidth(layout.compact), height = 88.dp)
                }
                if (feed.live.size >= 6) item(key = "live:all") { MoreCard("Ver todo en directo", R.drawable.ic_live, onOpenLive, 88.dp) }
            }
        }

        SectionTitle("Empieza pronto", Modifier.padding(top = 2.dp, start = 2.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 4.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(feed.soon, key = { "soon:${it.id}" }) { event ->
                val key = "soon:${event.id}"
                EventCard(event, now, { onWatch(event, key) }, mod(key), width = cardWidth(layout.compact), height = 84.dp)
            }
            item(key = "soon:calendar") {
                MoreCard("Ver calendario completo", R.drawable.ic_calendar, { onOpenCalendar("soon:calendar") }, 84.dp, mod("soon:calendar"))
            }
        }
    }

    if (layout.compact) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = layout.gutter, vertical = 8.dp)) {
            ServiceChips(services, onServiceOk, Modifier.padding(bottom = 8.dp))
            body()
        }
    } else {
        Column(Modifier.fillMaxSize().padding(start = 6.dp, end = layout.gutter, top = 12.dp, bottom = 6.dp)) { body() }
    }
}

private fun cardWidth(compact: Boolean): Dp = if (compact) 160.dp else 152.dp

@Composable
private fun QuietLine(text: String) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 5.dp).height(40.dp)
            .background(Neon.Panel.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).border(1.dp, Neon.Stroke, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart
    ) { Txt(text, NeonType.Body, color = Neon.Dim) }
}

// ============================================================================ Hero

@Composable
private fun Hero(
    agenda: AgendaUi,
    focus: FocusRequester,
    restoreKey: String?,
    restore: FocusRequester,
    onWatch: (AgendaEvent, String) -> Unit,
    onOpenEvent: (AgendaEvent, String) -> Unit,
    onOpenCalendar: (String) -> Unit,
    onAddProvider: () -> Unit
) {
    val layout = LocalAppLayout.current
    val event = agenda.home.hero
    val height = if (layout.compact) 190.dp else 156.dp
    Box(
        Modifier.fillMaxWidth().height(height)
            .background(Brush.linearGradient(listOf(Neon.Violet.copy(alpha = 0.45f), Neon.Panel, Neon.Blue.copy(alpha = 0.40f))), RoundedCornerShape(18.dp))
            .drawBehind {
                // Luces de estadio (sin imágenes): el hueco para la imagen del evento queda preparado.
                drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent), center = Offset(size.width * 0.75f, size.height * 0.2f), radius = size.height),
                    radius = size.height, center = Offset(size.width * 0.75f, size.height * 0.2f))
            }
            .border(1.dp, Neon.Stroke, RoundedCornerShape(18.dp))
    ) {
        if (!layout.compact) Box(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 16.dp)) { Clock() }
        if (event == null) {
            Column(Modifier.fillMaxHeight().padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.Center) {
                Txt(if (agenda.loading) "Cargando agenda…" else "Bienvenido a MiVisor", Ui.PageTitle.copy(fontSize = 24.sp))
                Txt(
                    if (agenda.loading) "Un momento…" else "Añade tus proveedores para ver los partidos y canales que tienes disponibles.",
                    NeonType.HeroSub, maxLines = 2
                )
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Añadir proveedor", onAddProvider, Modifier.focusRequester(focus), icon = R.drawable.ic_add)
                    SecondaryButton("Ver calendario", { onOpenCalendar("hero") }, icon = R.drawable.ic_calendar)
                }
            }
        } else {
            HeroContent(agenda, event, focus, restoreKey, restore, onWatch, onOpenEvent)
        }
    }
}

@Composable
private fun HeroContent(
    agenda: AgendaUi,
    event: AgendaEvent,
    focus: FocusRequester,
    restoreKey: String?,
    restore: FocusRequester,
    onWatch: (AgendaEvent, String) -> Unit,
    onOpenEvent: (AgendaEvent, String) -> Unit
) {
    val layout = LocalAppLayout.current
        val status = event.status(agenda.now)
        val sources = agenda.optionCount(event)
        Row(Modifier.fillMaxSize().padding(start = 22.dp, end = 16.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (status == EventStatus.LIVE) LivePill(small = false) else SoftPill(if (status == EventStatus.UPCOMING) "Próximo" else "Finalizado")
                    Box(Modifier.width(10.dp))
                    CompetitionLabel(event, size = 16.dp, style = NeonType.Body.copy(color = Neon.Text))
                    Txt(
                        "  ·  " + if (status == EventStatus.UPCOMING && !isToday(event, agenda.now)) "${dayTitle(event.startMillis)} · ${eventTimeText(event)}" else eventTimeText(event),
                        NeonType.Body, color = Neon.Dim
                    )
                }
                FitText(
                    event.displayTitle(), Ui.PageTitle, baseSp = if (layout.compact) 21f else 25f, minSp = 17f,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Txt(
                    when {
                        sources > 1 -> "Disponible en $sources fuentes de tus proveedores."
                        sources == 1 -> "Disponible en tus proveedores."
                        agenda.channelsOf(event).isNotEmpty() -> "Emisión: " + agenda.channelsOf(event).take(3).joinToString(", ") { it.name }
                        else -> "Todavía no hay canal confirmado."
                    },
                    NeonType.HeroSub.copy(fontSize = 13.sp), maxLines = 1, modifier = Modifier.padding(top = 2.dp)
                )
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val heroMod = if (restoreKey == "hero") Modifier.focusRequester(restore) else Modifier.focusRequester(focus)
                    PrimaryButton("Ver ahora", { onWatch(event, "hero") }, heroMod)
                    if (sources > 1) SecondaryButton("$sources fuentes", { onOpenEvent(event, "hero:sources") }, icon = R.drawable.ic_layers)
                    else SecondaryButton("Ver canales", { onOpenEvent(event, "hero:sources") }, icon = R.drawable.ic_list)
                }
            }
            if (!layout.compact) HeroVisual(event)
        }
}

private fun isToday(event: AgendaEvent, now: Long): Boolean =
    com.mivisor.app.model.DayClock().dayIndex(now, event.startMillis) == 0

@Composable
private fun HeroVisual(event: AgendaEvent) {
    val home = event.home
    val away = event.away
    Row(Modifier.padding(start = 12.dp, end = 30.dp), verticalAlignment = Alignment.CenterVertically) {
        if (home != null && away != null) {
            Participant(event.homeVisual, home, 78.dp, event.isPersonVsPerson)
            Txt("VS", NeonType.H2.copy(fontWeight = FontWeight.ExtraBold, color = Neon.Faint), modifier = Modifier.padding(horizontal = 14.dp))
            Participant(event.awayVisual, away, 78.dp, event.isPersonVsPerson)
        } else {
            Box(
                Modifier.width(110.dp).height(90.dp).background(Neon.Bg.copy(alpha = 0.35f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) { Icon(sportIcon(event.sport), Neon.Text, size = 52.dp) }
        }
    }
}

// ============================================================================ tarjetas

@Composable
private fun CompetitionCard(summary: CompetitionSummary, onClick: () -> Unit, modifier: Modifier) {
    val c = summary.competition
    NeonCard(onClick = onClick, modifier = modifier.width(158.dp).height(46.dp), radius = 12.dp, contentAlignment = Alignment.CenterStart) { focused ->
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            CompetitionIcon(c, 24.dp)
            Box(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                FitText(c.label, Ui.CardTitle, baseSp = 13f, minSp = 11f, maxLines = 1)
                Row {
                    if (summary.text.isNotEmpty()) Txt(summary.text, Ui.CardSmall)
                    if (summary.liveText != null) {
                        Txt(if (summary.text.isNotEmpty()) " · " else "", Ui.CardSmall)
                        Txt(summary.liveText, Ui.CardSmall.copy(color = Ui.LiveRed, fontWeight = FontWeight.Bold))
                    }
                }
            }
            Icon(R.drawable.ic_chevron_right, if (focused) Neon.Cyan else Neon.Faint, size = 14.dp)
        }
    }
}

/** Icono de competición: deporte + color propio (hasta tener logos oficiales). */
@Composable
fun CompetitionIcon(c: Competition, size: Dp) {
    val tint = when (c) {
        Competition.LALIGA -> Color(0xFFFF4B44)
        Competition.CHAMPIONS -> Color(0xFF6FA8FF)
        Competition.PREMIER -> Color(0xFFC77DFF)
        Competition.SERIE_A -> Color(0xFF4FA3FF)
        Competition.BUNDESLIGA -> Color(0xFFFF5252)
        Competition.FORMULA1 -> Color(0xFFFF3B30)
        Competition.MOTOGP -> Color(0xFFEDEDED)
        Competition.ATP -> Color(0xFF7FD3FF)
        Competition.WTA -> Color(0xFFB57BFF)
        Competition.TENIS -> Color(0xFFC6F432)
        else -> Neon.Dim
    }
    Box(
        Modifier.width(size).height(size).background(tint.copy(alpha = 0.14f), RoundedCornerShape(size / 4)),
        contentAlignment = Alignment.Center
    ) { Icon(sportIcon(c.sport), tint, size = size * 0.7f) }
}

@Composable
private fun MoreCard(label: String, icon: Int, onClick: () -> Unit, height: Dp, modifier: Modifier = Modifier) {
    NeonCard(onClick = onClick, modifier = modifier.width(140.dp).height(height), radius = 12.dp, contentAlignment = Alignment.Center) { focused ->
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, if (focused) Neon.Cyan else Neon.Dim, size = 22.dp)
            Txt(label, Ui.CardTitle.copy(fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center), maxLines = 2)
        }
    }
}
