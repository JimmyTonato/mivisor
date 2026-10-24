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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.mivisor.app.ui.components.MenuRow
import com.mivisor.app.ui.components.TvDialog
import com.mivisor.app.ui.components.Txt

/** "Canales del encuentro": partido, canales que lo emiten y "Abrir canal" con tus enlaces. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun EventDetail(
    event: AgendaEvent,
    ui: AgendaUi,
    vm: AgendaViewModel,
    onBack: () -> Unit,
    onOpenChannel: (Channel) -> Unit,
    onOpenEvent: (AgendaEvent) -> Unit,
    onExit: () -> Unit,
    onSettings: () -> Unit
) {
    val context = LocalContext.current
    val firstChannel = remember { FocusRequester() }
    val backButton = remember { FocusRequester() }
    var choosing by remember { mutableStateOf<Pair<AgendaChannel, List<Channel>>?>(null) }
    val now = remember(event.id) { System.currentTimeMillis() }
    val status = event.status(now)
    val more = remember(event.id, ui.agenda) { vm.moreToday(event.id) }

    LaunchedEffect(event.id) {
        repeat(3) {
            withFrameNanos { }
            val target = if (event.channels.isNotEmpty()) firstChannel else backButton
            if (runCatching { target.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    Column(Modifier.fillMaxSize().padding(start = EdgeH, end = EdgeH, top = EdgeTop)) {
        TopBar(onMyChannels = onExit, onSettings = onSettings)
        Row(Modifier.fillMaxSize().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                NeonCard(
                    onClick = onBack,
                    modifier = Modifier.focusRequester(backButton).height(26.dp),
                    radius = 14.dp,
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    borderColor = androidx.compose.ui.graphics.Color.Transparent,
                    contentAlignment = Alignment.CenterStart
                ) { focused ->
                    Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(R.drawable.ic_chevron_left, Neon.Subtitle, size = 18.dp)
                        Txt("Agenda deportiva", NeonType.Body.copy(fontSize = 15.sp), color = if (focused) Neon.Text else Neon.Subtitle)
                    }
                }
                Txt("Canales del encuentro", NeonType.Hero.copy(fontSize = 32.sp, lineHeight = 36.sp))
                Txt("Consulta los canales disponibles para este encuentro.", NeonType.HeroSub, modifier = Modifier.padding(bottom = 8.dp))

                MatchHero(event, status)

                Txt("Canales disponibles", NeonType.H2, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (event.channels.isEmpty()) {
                        Txt("Todavía no hay canal confirmado para este encuentro.", NeonType.Body, color = Neon.Dim, modifier = Modifier.padding(6.dp))
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize().focusRestorer(),
                            contentPadding = PaddingValues(6.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            itemsIndexed(event.channels, key = { i, c -> "${c.name}_$i" }) { index, channel ->
                                val links = ui.links[channel.name].orEmpty()
                                ChannelRow(
                                    channel = channel,
                                    competition = event.competition,
                                    status = status,
                                    links = links.size,
                                    modifier = if (index == 0) Modifier.focusRequester(firstChannel) else Modifier
                                ) {
                                    when {
                                        links.size == 1 -> onOpenChannel(links[0])
                                        links.size > 1 -> choosing = channel to links
                                        else -> Toast.makeText(
                                            context,
                                            if (ui.indexedChannels == 0) "Abre antes tus categorías para poder enlazar canales"
                                            else "«${channel.name}» no está en tus categorías",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 14.dp).neonPanel(14.dp).padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(R.drawable.ic_live, Neon.Violet, size = 28.dp)
                    Box(Modifier.width(12.dp))
                    Column {
                        Txt("Al abrir un canal, se iniciará en tu reproductor externo.", NeonType.Body)
                        Txt("Se abrirá en la app de TV o reproductor que tengas configurado.", NeonType.Small)
                    }
                }
            }
            MoreToday(more, onOpenEvent, Modifier.width(250.dp).fillMaxHeight())
        }
    }

    choosing?.let { (agendaChannel, options) ->
        val first = remember { FocusRequester() }
        TvDialog(
            title = "¿Qué canal abrir?",
            subtitle = "Tienes ${options.size} canales que coinciden con «${agendaChannel.name}»",
            onDismiss = { choosing = null },
            initialFocus = first
        ) {
            LazyColumn(Modifier.fillMaxWidth().height(260.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(options, key = { i, c -> "${c.id}_$i" }) { index, channel ->
                    MenuRow(
                        listOfNotNull(channel.name, channel.group).joinToString("  ·  "),
                        R.drawable.ic_play,
                        { choosing = null; onOpenChannel(channel) },
                        if (index == 0) Modifier.focusRequester(first) else Modifier
                    )
                }
            }
        }
    }
}

@Composable
private fun MatchHero(event: AgendaEvent, status: EventStatus) {
    val home = event.home
    val away = event.away
    Row(
        Modifier
            .fillMaxWidth()
            .height(88.dp)
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
                TeamColumn(home, event.homeLogo, 46.dp, 170.dp, NeonType.H3.copy(fontSize = 16.sp))
            }
            MatchCenter(event, status)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                TeamColumn(away, event.awayLogo, 46.dp, 170.dp, NeonType.H3.copy(fontSize = 16.sp))
            }
        } else {
            Column(Modifier.weight(1f)) {
                Txt(event.title, NeonType.H2, maxLines = 2)
                Txt(sportLabel(event.sport), NeonType.Small)
            }
            MatchCenter(event, status)
        }
    }
}

@Composable
private fun MatchCenter(event: AgendaEvent, status: EventStatus) {
    Column(Modifier.width(200.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (event.competitionLogo != null) Crest(event.competitionLogo, event.competition ?: "", 22.dp)
            Txt(event.competition ?: sportLabel(event.sport), NeonType.H3)
        }
        Txt("${dayTitle(event.startMillis)} · ${timeOf(event.startMillis)}", NeonType.Body.copy(fontSize = 16.sp, color = Neon.Dim))
        StatusPill(status)
    }
}

@Composable
private fun ChannelRow(
    channel: AgendaChannel,
    competition: String?,
    status: EventStatus,
    links: Int,
    modifier: Modifier,
    onClick: () -> Unit
) {
    NeonCard(onClick = onClick, modifier = modifier.fillMaxWidth().height(46.dp), radius = 12.dp) { focused ->
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Caja del logo del canal (o su nombre abreviado mientras no haya logo).
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
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                channel.dial?.let { InfoChip(it.replace(" ", " · ")) }
                channel.note?.let { InfoChip(it, Neon.Text, dot = Neon.Blue) }
                if (status == EventStatus.LIVE) InfoChip("En vivo", androidx.compose.ui.graphics.Color(0xFFFF5C7A), dot = Neon.Red)
            }
            Box(Modifier.width(10.dp))
            OpenPill(links, focused)
        }
    }
}

@Composable
private fun OpenPill(links: Int, focused: Boolean) {
    val enabled = links > 0
    Row(
        Modifier
            .width(132.dp)
            .height(32.dp)
            .background(
                when {
                    focused && enabled -> Brush.horizontalGradient(listOf(Neon.Cyan.copy(alpha = 0.45f), Neon.Blue.copy(alpha = 0.45f)))
                    else -> Brush.horizontalGradient(listOf(Neon.PanelHi, Neon.PanelHi))
                },
                RoundedCornerShape(10.dp)
            )
            .border(1.dp, if (enabled) Neon.Cyan.copy(alpha = if (focused) 1f else 0.5f) else Neon.Stroke, RoundedCornerShape(10.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (enabled) {
            Icon(R.drawable.ic_play, Neon.Text, size = 15.dp)
            Box(Modifier.width(8.dp))
            Txt(if (links > 1) "Abrir canal ($links)" else "Abrir canal", NeonType.Body)
        } else {
            Txt("No disponible", NeonType.Small, color = Neon.Faint)
        }
    }
}

@Composable
private fun MoreToday(events: List<AgendaEvent>, onOpenEvent: (AgendaEvent) -> Unit, modifier: Modifier) {
    Column(modifier.padding(top = 4.dp)) {
        Row(Modifier.padding(start = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(R.drawable.ic_star, Neon.Blue, size = 18.dp)
            Box(Modifier.width(8.dp))
            Txt("Más partidos de hoy", NeonType.H3)
        }
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
