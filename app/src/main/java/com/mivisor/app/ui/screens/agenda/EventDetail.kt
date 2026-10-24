package com.mivisor.app.ui.screens.agenda

import android.widget.Toast
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mivisor.app.AgendaUi
import com.mivisor.app.AgendaViewModel
import com.mivisor.app.R
import com.mivisor.app.model.AgendaChannel
import com.mivisor.app.model.AgendaEvent
import com.mivisor.app.model.Channel
import com.mivisor.app.model.EventStatus
import com.mivisor.app.ui.components.Icon
import com.mivisor.app.ui.components.Txt
import com.mivisor.app.ui.neon.Crest
import com.mivisor.app.ui.neon.InfoChip
import com.mivisor.app.ui.neon.LocalAppLayout
import com.mivisor.app.ui.neon.Neon
import com.mivisor.app.ui.neon.NeonCard
import com.mivisor.app.ui.neon.NeonType
import com.mivisor.app.ui.neon.StatusPill
import com.mivisor.app.ui.neon.TeamColumn
import com.mivisor.app.ui.neon.TeamsVs
import com.mivisor.app.ui.neon.dayTitle
import com.mivisor.app.ui.neon.neonPanel
import com.mivisor.app.ui.neon.sportLabel
import com.mivisor.app.ui.neon.timeOf

/**
 * "Canales del encuentro": partido, canales que lo emiten y, para cada uno, TODAS tus
 * opciones (de cualquier código). En TV/tablet: columnas; en móvil: una sola columna.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun EventDetail(
    event: AgendaEvent,
    ui: AgendaUi,
    vm: AgendaViewModel,
    onBack: () -> Unit,
    onOpenChannel: (Channel) -> Unit,
    onOpenEvent: (AgendaEvent) -> Unit
) {
    val layout = LocalAppLayout.current
    val context = LocalContext.current
    val firstChannel = remember { FocusRequester() }
    val backButton = remember { FocusRequester() }
    val now = remember(event.id) { System.currentTimeMillis() }
    val status = event.status(now)
    val more = remember(event.id, ui.agenda) { vm.moreToday(event.id) }
    val blocks: List<Pair<AgendaChannel, List<Channel>>> = remember(event.id, ui.links) {
        // Primero los canales para los que tienes opciones.
        event.channels.map { it to ui.links[it.name].orEmpty() }.sortedByDescending { it.second.isNotEmpty() }
    }
    val totalOptions = ui.optionCount(event)
    val onMissing: (AgendaChannel) -> Unit = { channel ->
        Toast.makeText(
            context,
            if (ui.indexedChannels == 0) "Abre antes tus categorías para poder enlazar canales"
            else "«${channel.name}» no está en ninguno de tus códigos",
            Toast.LENGTH_SHORT
        ).show()
    }

    LaunchedEffect(event.id) {
        repeat(3) {
            withFrameNanos { }
            val target = if (event.channels.isNotEmpty()) firstChannel else backButton
            if (runCatching { target.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    if (layout.compact) {
        // Móvil: todo en una única lista desplazable.
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = layout.gutter),
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "back") { BackLink(onBack, backButton) }
            item(key = "title") { Titles(compact = true) }
            item(key = "hero") { MatchHero(event, status, compact = true) }
            item(key = "label") { ChannelsLabel(totalOptions) }
            if (event.channels.isEmpty()) {
                item(key = "none") { NoChannels() }
            }
            itemsIndexed(blocks, key = { i, b -> "${b.first.name}_$i" }) { index, (channel, options) ->
                ChannelBlock(
                    channel, event.competition, status, options,
                    firstFocus = if (index == 0) Modifier.focusRequester(firstChannel) else Modifier,
                    onOpen = onOpenChannel, onMissing = { onMissing(channel) }, compact = true
                )
            }
            item(key = "info") { InfoStrip() }
            if (more.isNotEmpty()) {
                item(key = "more") { MoreHeader() }
                items(more, key = { "m_${it.id}" }) { e -> MoreCard(e) { onOpenEvent(e) } }
            }
        }
        return
    }

    Row(
        Modifier.fillMaxSize().padding(horizontal = layout.gutter),
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            BackLink(onBack, backButton)
            Titles(compact = layout.short)
            MatchHero(event, status, compact = false)
            ChannelsLabel(totalOptions)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (event.channels.isEmpty()) {
                    NoChannels()
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize().focusRestorer(),
                        contentPadding = PaddingValues(6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(blocks, key = { i, b -> "${b.first.name}_$i" }) { index, (channel, options) ->
                            ChannelBlock(
                                channel, event.competition, status, options,
                                firstFocus = if (index == 0) Modifier.focusRequester(firstChannel) else Modifier,
                                onOpen = onOpenChannel, onMissing = { onMissing(channel) }, compact = false
                            )
                        }
                    }
                }
            }
            InfoStrip(Modifier.padding(top = 6.dp, bottom = 12.dp))
        }
        if (layout.expanded) MoreToday(more, onOpenEvent, Modifier.width(250.dp).fillMaxHeight())
    }
}

@Composable
private fun BackLink(onBack: () -> Unit, focus: FocusRequester) {
    NeonCard(
        onClick = onBack,
        modifier = Modifier.focusRequester(focus).height(30.dp),
        radius = 15.dp,
        color = Color.Transparent,
        borderColor = Color.Transparent,
        contentAlignment = Alignment.CenterStart
    ) { focused ->
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(R.drawable.ic_chevron_left, Neon.Subtitle, size = 18.dp)
            Txt("Agenda deportiva", NeonType.Body.copy(fontSize = 15.sp), color = if (focused) Neon.Text else Neon.Subtitle)
        }
    }
}

@Composable
private fun Titles(compact: Boolean) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Txt(
            "Canales del encuentro",
            NeonType.Hero.copy(fontSize = if (compact) 24.sp else 32.sp, lineHeight = if (compact) 28.sp else 36.sp)
        )
        if (!compact) Txt("Consulta los canales disponibles para este encuentro.", NeonType.HeroSub)
    }
}

@Composable
private fun ChannelsLabel(totalOptions: Int) {
    Row(Modifier.padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt("Canales disponibles", NeonType.H2)
        if (totalOptions > 0) {
            Txt(
                "  ·  ${if (totalOptions == 1) "1 opción" else "$totalOptions opciones"}",
                NeonType.Body, color = Neon.Cyan
            )
        }
    }
}

@Composable
private fun NoChannels() {
    Txt("Todavía no hay canal confirmado para este encuentro.", NeonType.Body, color = Neon.Dim, modifier = Modifier.padding(6.dp))
}

@Composable
private fun InfoStrip(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().neonPanel(14.dp).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(R.drawable.ic_live, Neon.Violet, size = 28.dp)
        Box(Modifier.width(12.dp))
        Column {
            Txt("Al abrir un canal, se iniciará en tu reproductor externo.", NeonType.Body, maxLines = 2)
            Txt("Se abrirá en la app de TV o reproductor que tengas configurado.", NeonType.Small, maxLines = 2)
        }
    }
}

@Composable
private fun MoreHeader() {
    Row(Modifier.padding(start = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(R.drawable.ic_star, Neon.Blue, size = 18.dp)
        Box(Modifier.width(8.dp))
        Txt("Más partidos de hoy", NeonType.H3)
    }
}

@Composable
private fun MatchHero(event: AgendaEvent, status: EventStatus, compact: Boolean) {
    val home = event.home
    val away = event.away
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (compact) 104.dp else 88.dp)
            .background(
                Brush.horizontalGradient(listOf(Neon.Violet.copy(alpha = 0.35f), Neon.Panel, Neon.Blue.copy(alpha = 0.35f))),
                RoundedCornerShape(16.dp)
            )
            .border(1.dp, Neon.Stroke, RoundedCornerShape(16.dp))
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (home != null && away != null) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                TeamColumn(home, event.homeLogo, if (compact) 36.dp else 46.dp, if (compact) 96.dp else 170.dp, NeonType.H3.copy(fontSize = if (compact) 13.sp else 16.sp))
            }
            MatchCenter(event, status, compact)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                TeamColumn(away, event.awayLogo, if (compact) 36.dp else 46.dp, if (compact) 96.dp else 170.dp, NeonType.H3.copy(fontSize = if (compact) 13.sp else 16.sp))
            }
        } else {
            Column(Modifier.weight(1f)) {
                Txt(event.title, NeonType.H2, maxLines = 2)
                Txt(sportLabel(event.sport), NeonType.Small)
            }
            MatchCenter(event, status, compact)
        }
    }
}

@Composable
private fun MatchCenter(event: AgendaEvent, status: EventStatus, compact: Boolean) {
    Column(Modifier.width(if (compact) 120.dp else 200.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (event.competitionLogo != null) Crest(event.competitionLogo, event.competition ?: "", 22.dp)
            Txt(event.competition ?: sportLabel(event.sport), NeonType.H3)
        }
        Txt(
            if (compact) timeOf(event.startMillis) else "${dayTitle(event.startMillis)} · ${timeOf(event.startMillis)}",
            NeonType.Body.copy(fontSize = 16.sp, color = Neon.Dim)
        )
        StatusPill(status)
    }
}

/**
 * Un canal de la agenda y, debajo, TODAS tus opciones para él (de cualquier código):
 * "Opción 1 · Deportes · ACE", "Opción 2 · Fútbol · HLS"… OK sobre una opción la abre.
 */
@Composable
private fun ChannelBlock(
    channel: AgendaChannel,
    competition: String?,
    status: EventStatus,
    options: List<Channel>,
    firstFocus: Modifier,
    onOpen: (Channel) -> Unit,
    onMissing: () -> Unit,
    compact: Boolean
) {
    Column(
        Modifier.fillMaxWidth().neonPanel(14.dp, highlight = Neon.Blue).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Logo del canal (o su nombre abreviado mientras no haya logo).
            Box(
                Modifier.width(64.dp).height(32.dp).background(Neon.Bg, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                com.mivisor.app.ui.components.RemoteImage(channel.logo, 28.dp) {
                    Txt(
                        channel.name.substringBefore(' ').take(7),
                        NeonType.Pill.copy(color = Neon.Text, fontWeight = FontWeight.ExtraBold)
                    )
                }
            }
            Box(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(channel.name, NeonType.H3)
                Txt(listOfNotNull("Deportes", competition).joinToString("  |  "), NeonType.Small)
            }
            if (!compact) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                channel.dial?.let { InfoChip(it.replace(" ", " · ")) }
                channel.note?.let { InfoChip(it, Neon.Text, dot = Neon.Blue) }
                if (status == EventStatus.LIVE) InfoChip("En vivo", androidx.compose.ui.graphics.Color(0xFFFF5C7A), dot = Neon.Red)
            }
        }
        if (options.isEmpty()) {
            NeonCard(onClick = onMissing, modifier = firstFocus.height(40.dp), radius = 10.dp, contentAlignment = Alignment.CenterStart) { _ ->
                Txt("Sin opciones: no está en ninguno de tus códigos", NeonType.Small, color = Neon.Faint, modifier = Modifier.padding(horizontal = 12.dp))
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(options, key = { i, c -> "${c.id}_$i" }) { i, option ->
                    OptionCard(
                        number = i + 1,
                        option = option,
                        modifier = if (i == 0) firstFocus else Modifier,
                        onClick = { onOpen(option) }
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionCard(number: Int, option: Channel, modifier: Modifier, onClick: () -> Unit) {
    NeonCard(onClick = onClick, modifier = modifier.width(176.dp).height(48.dp), radius = 12.dp, color = Neon.PanelHi) { focused ->
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(28.dp).background(
                    if (focused) Brush.linearGradient(listOf(Neon.Cyan, Neon.Blue))
                    else Brush.linearGradient(listOf(Neon.Stroke, Neon.Stroke)),
                    RoundedCornerShape(14.dp)
                ),
                contentAlignment = Alignment.Center
            ) { Icon(R.drawable.ic_play, Neon.Text, size = 14.dp) }
            Box(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Txt("Opción $number", NeonType.H3.copy(fontSize = 14.sp))
                Txt(
                    listOfNotNull(option.source, option.kind.label).joinToString(" · "),
                    NeonType.Small, color = if (focused) Neon.Cyan else Neon.Dim
                )
            }
        }
    }
}

@Composable
private fun MoreToday(events: List<AgendaEvent>, onOpenEvent: (AgendaEvent) -> Unit, modifier: Modifier) {
    Column(modifier) {
        MoreHeader()
        if (events.isEmpty()) {
            Txt("No hay más eventos hoy.", NeonType.Small, modifier = Modifier.padding(6.dp))
        }
        LazyColumn(contentPadding = PaddingValues(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(events, key = { it.id }) { event -> MoreCard(event) { onOpenEvent(event) } }
        }
    }
}

@Composable
private fun MoreCard(event: AgendaEvent, onClick: () -> Unit) {
    val now = remember(event.id) { System.currentTimeMillis() }
    NeonCard(onClick = onClick, modifier = Modifier.fillMaxWidth().height(116.dp), radius = 14.dp) { _ ->
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Neon.Violet.copy(alpha = 0.25f), Neon.Panel)), RoundedCornerShape(14.dp)
            )
        )
        Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (event.competitionLogo != null) Crest(event.competitionLogo, event.competition ?: "", 18.dp)
                Txt(event.competition ?: sportLabel(event.sport), NeonType.Body)
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                val home = event.home
                val away = event.away
                if (home != null && away != null) {
                    TeamsVs(home, event.homeLogo, away, event.awayLogo, crestSize = 30.dp, nameWidth = 84.dp)
                } else {
                    Txt(event.title, NeonType.H3, maxLines = 2)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt(timeOf(event.startMillis), NeonType.H3.copy(fontSize = 17.sp))
                StatusPill(event.status(now))
            }
        }
    }
}
