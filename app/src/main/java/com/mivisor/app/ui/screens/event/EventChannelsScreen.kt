package com.mivisor.app.ui.screens.event

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.mivisor.app.data.ChannelGrouping
import com.mivisor.app.model.AgendaChannel
import com.mivisor.app.model.AgendaEvent
import com.mivisor.app.model.Channel
import com.mivisor.app.model.EventStatus
import com.mivisor.app.model.displayTitle
import com.mivisor.app.ui.components.Icon
import com.mivisor.app.ui.components.Txt
import com.mivisor.app.ui.kit.AutoFocus
import com.mivisor.app.ui.kit.ChannelLogo
import com.mivisor.app.ui.kit.CompetitionLabel
import com.mivisor.app.ui.kit.InfoLine
import com.mivisor.app.ui.kit.MetaTag
import com.mivisor.app.ui.kit.PageHeader
import com.mivisor.app.ui.kit.PrimaryButton
import com.mivisor.app.ui.kit.ProviderTag
import com.mivisor.app.ui.kit.QualityTag
import com.mivisor.app.ui.kit.SecondaryButton
import com.mivisor.app.ui.kit.SectionTitle
import com.mivisor.app.ui.kit.StatusBadge
import com.mivisor.app.ui.kit.Ui
import com.mivisor.app.ui.neon.LocalAppLayout
import com.mivisor.app.ui.neon.Neon
import com.mivisor.app.ui.neon.NeonCard
import com.mivisor.app.ui.neon.NeonType
import com.mivisor.app.ui.neon.Participant
import com.mivisor.app.ui.neon.dayTitle
import com.mivisor.app.ui.neon.eventTimeText
import com.mivisor.app.ui.neon.sportIcon
import com.mivisor.app.ui.neon.sportLabel

/** Una fila reproducible: canal de la agenda + una de tus fuentes para él. */
private class SourceLine(val agendaChannel: AgendaChannel, val source: Channel)

/**
 * "Canales del encuentro": filas grandes, toda la fila recibe el foco y OK reproduce (preparando Ace/VPN si hace falta).
 */
@Composable
fun EventChannelsScreen(
    event: AgendaEvent,
    agenda: AgendaUi,
    backLabel: String,
    onBack: () -> Unit,
    onRequestChannels: (AgendaEvent) -> Unit,
    onPlay: (Channel) -> Unit,
    onConfigureSources: () -> Unit
) {
    val layout = LocalAppLayout.current
    LaunchedEffect(event.id) { onRequestChannels(event) }
    val channels = agenda.channelsOf(event)
    val lines = remember(event.id, agenda.links, channels) {
        channels.flatMap { ch -> agenda.links[ch.name].orEmpty().map { SourceLine(ch, it) } }.distinctBy { it.source.url }
    }
    val withoutSource = channels.filter { agenda.links[it.name].isNullOrEmpty() }
    val loading = event.id in agenda.tvLoading
    val failed = event.id in agenda.tvFailed
    val status = event.status(agenda.now)
    val first = remember { FocusRequester() }
    val action = remember { FocusRequester() }
    AutoFocus(if (lines.isNotEmpty()) first else action, key = lines.isNotEmpty())

    Row(Modifier.fillMaxSize().padding(start = if (layout.compact) layout.gutter else 6.dp, end = layout.gutter), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            PageHeader("Canales del encuentro", "Selecciona un canal para ver el partido.", back = backLabel to onBack)
            MatchCard(event, status)
            SectionTitle("Canales disponibles", Modifier.padding(top = 10.dp, bottom = 4.dp), count = lines.size)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (lines.isEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Txt(
                            when {
                                loading -> "Buscando canales…"
                                failed -> "No se han podido consultar los canales. Vuelve a abrir el partido para reintentarlo."
                                channels.isEmpty() -> "Todavía no hay canal confirmado para este encuentro."
                                agenda.indexedChannels == 0 -> "Añade tus proveedores para ver aquí con qué fuentes puedes verlo."
                                else -> "Ninguno de tus proveedores tiene estos canales."
                            },
                            NeonType.Body, color = Neon.Dim, maxLines = 2, modifier = Modifier.padding(horizontal = 4.dp)
                        )
                        if (withoutSource.isNotEmpty()) BroadcastStrip(withoutSource)
                        SourcesHint(onConfigureSources, Modifier.focusRequester(action))
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(lines, key = { i, l -> "${l.source.url}_$i" }) { index, line ->
                            SourceRow(line, status, event.competition, { onPlay(line.source) }, if (index == 0) Modifier.focusRequester(first) else Modifier)
                        }
                        if (withoutSource.isNotEmpty()) item(key = "broadcast") { BroadcastStrip(withoutSource) }
                        item(key = "hint") { SourcesHint(onConfigureSources, Modifier.focusRequester(action)) }
                    }
                }
            }
        }
        if (layout.expanded) {
            SidePanel(event, agenda, lines.firstOrNull()?.source, onPlay, Modifier.width(236.dp).fillMaxHeight().padding(top = 12.dp, bottom = 10.dp))
        }
    }
}

@Composable
private fun MatchCard(event: AgendaEvent, status: EventStatus) {
    val home = event.home
    val away = event.away
    Row(
        Modifier.fillMaxWidth().height(74.dp)
            .background(Brush.horizontalGradient(listOf(Neon.Violet.copy(alpha = 0.30f), Neon.Panel, Neon.Blue.copy(alpha = 0.30f))), RoundedCornerShape(16.dp))
            .border(1.dp, Neon.Stroke, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (home != null && away != null) {
            Participant(event.homeVisual, home, 42.dp, event.isPersonVsPerson)
            Txt(home, NeonType.H2.copy(fontSize = 17.sp), modifier = Modifier.padding(start = 10.dp).weight(1f))
            Center(event, status)
            Txt(away, NeonType.H2.copy(fontSize = 17.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End), modifier = Modifier.padding(end = 10.dp).weight(1f))
            Participant(event.awayVisual, away, 42.dp, event.isPersonVsPerson)
        } else {
            Icon(sportIcon(event.sport), Neon.Text, size = 34.dp)
            Txt(event.title, NeonType.H2.copy(fontSize = 17.sp), maxLines = 2, modifier = Modifier.padding(start = 12.dp).weight(1f))
            Center(event, status)
        }
    }
}

@Composable
private fun Center(event: AgendaEvent, status: EventStatus) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.width(170.dp)) {
        CompetitionLabel(event, size = 14.dp, style = NeonType.Small.copy(color = Neon.Text))
        Txt("${dayTitle(event.startMillis)} · ${eventTimeText(event)}", NeonType.Body)
        StatusBadge(status)
    }
}

@Composable
private fun SourceRow(line: SourceLine, status: EventStatus, competition: String?, onClick: () -> Unit, modifier: Modifier) {
    val source = line.source
    val quality = ChannelGrouping.quality(source.name)
    NeonCard(onClick = onClick, modifier = modifier.fillMaxWidth().height(76.dp), radius = 14.dp, contentAlignment = Alignment.CenterStart) { focused ->
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(line.agendaChannel.name, 44.dp)
            Box(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Txt(line.agendaChannel.name, NeonType.H3.copy(fontSize = 15.sp))
                Txt(listOfNotNull("Deportes", competition).joinToString("  |  "), NeonType.Small)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (status == EventStatus.LIVE) MetaTag("En directo", dot = Neon.Red, color = Ui.LiveRed)
                    line.agendaChannel.note?.takeIf { it.isNotBlank() }?.let { MetaTag(it) }
                    line.agendaChannel.dial?.takeIf { it.isNotBlank() }?.let { MetaTag(it) }
                    source.source?.let { ProviderTag(it) }
                    QualityTag(quality)
                }
            }
            // "Ver canal": visual; el control es toda la fila.
            Row(
                Modifier.height(36.dp)
                    .background(if (focused) Neon.Cyan else Neon.PanelHi, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(R.drawable.ic_play, if (focused) Neon.Bg else Neon.Dim, size = 14.dp)
                Box(Modifier.width(8.dp))
                Txt("Ver canal", NeonType.Body.copy(fontWeight = FontWeight.Bold), color = if (focused) Neon.Bg else Neon.Dim)
            }
        }
    }
}

/** Canales que emiten el partido pero no están en tus proveedores (solo informativo). */
@Composable
private fun BroadcastStrip(channels: List<AgendaChannel>) {
    Column(
        Modifier.fillMaxWidth().background(Neon.Panel.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Txt("También lo emiten (sin fuente en tus proveedores)", NeonType.Small)
        Txt(
            channels.take(8).joinToString("  ·  ") { c -> listOfNotNull(c.name, c.note?.takeIf { it.isNotBlank() }).joinToString(" — ") },
            NeonType.Body.copy(fontSize = 13.sp), color = Neon.Dim, maxLines = 2
        )
    }
}

@Composable
private fun SourcesHint(onConfigure: () -> Unit, modifier: Modifier) {
    Row(
        Modifier.fillMaxWidth().background(Neon.Panel.copy(alpha = 0.8f), RoundedCornerShape(14.dp)).border(1.dp, Neon.Stroke, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(R.drawable.ic_info, Neon.Dim, size = 20.dp)
        Box(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Txt("¿No ves el canal que buscas?", NeonType.H3.copy(fontSize = 13.sp))
            Txt("Añade o revisa tus proveedores de contenido.", NeonType.Small)
        }
        SecondaryButton("Configurar fuentes", onConfigure, modifier, icon = R.drawable.ic_settings, height = 34.dp)
    }
}

/** Resumen con los datos que sí tenemos (sin inventar jornada, estadio ni ciudad). */
private fun aboutText(event: AgendaEvent, agenda: AgendaUi): String {
    val comp = event.competition ?: sportLabel(event.sport)
    val first = if (event.home != null && event.away != null) "${event.home} recibe a ${event.away} en $comp." else "${event.title} · $comp."
    val channels = agenda.channelsOf(event).take(3).map { it.name }
    val sources = agenda.optionCount(event)
    return listOfNotNull(
        first,
        "${dayTitle(event.startMillis)}, ${eventTimeText(event)}.",
        channels.takeIf { it.isNotEmpty() }?.let { "Se emite en ${it.joinToString(", ")}." },
        when (sources) { 0 -> "Ninguno de tus proveedores lo tiene todavía."; 1 -> "Tienes 1 fuente en tus proveedores."; else -> "Tienes $sources fuentes en tus proveedores." }
    ).joinToString(" ")
}

@Composable
private fun SidePanel(event: AgendaEvent, agenda: AgendaUi, best: Channel?, onPlay: (Channel) -> Unit, modifier: Modifier) {
    val status = event.status(agenda.now)
    Column(
        modifier.background(Neon.Panel.copy(alpha = 0.9f), RoundedCornerShape(16.dp)).border(1.dp, Neon.Stroke, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(status)
            Box(Modifier.weight(1f))
            CompetitionLabel(event)
        }
        val home = event.home
        val away = event.away
        if (home != null && away != null) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Participant(event.homeVisual, home, 50.dp, event.isPersonVsPerson)
                Txt("VS", NeonType.H3, color = Neon.Faint)
                Participant(event.awayVisual, away, 50.dp, event.isPersonVsPerson)
            }
        }
        Txt(event.displayTitle(), NeonType.H3.copy(fontSize = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center), maxLines = 2, modifier = Modifier.fillMaxWidth())
        Txt("${dayTitle(event.startMillis)} · ${eventTimeText(event)}", NeonType.Body.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center), color = Neon.Dim, modifier = Modifier.fillMaxWidth())
        if (status == EventStatus.LIVE) Txt("● En directo ahora", NeonType.Body.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center), color = Ui.Green, modifier = Modifier.fillMaxWidth())
        if (best != null) PrimaryButton("Ver ahora", { onPlay(best) }, Modifier.fillMaxWidth(), height = 36.dp)
        Txt("Acerca de este partido", NeonType.H3.copy(fontSize = 13.sp), modifier = Modifier.padding(top = 4.dp))
        Txt(aboutText(event, agenda), NeonType.Small.copy(fontSize = 12.sp, lineHeight = 16.sp), maxLines = 5)
        InfoLine(R.drawable.ic_trophy, "Competición", event.competition ?: sportLabel(event.sport))
        InfoLine(R.drawable.ic_calendar, "Fecha", "${dayTitle(event.startMillis)}, ${eventTimeText(event)}")
        InfoLine(R.drawable.ic_tv, "Emisión", agenda.channelsOf(event).take(3).joinToString(", ") { it.name }.ifEmpty { "Por confirmar" })
    }
}
