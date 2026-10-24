package com.mivisor.app.ui.screens.agenda

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mivisor.app.R
import com.mivisor.app.data.CatalogBuilder
import com.mivisor.app.model.EventStatus
import com.mivisor.app.ui.components.Icon
import com.mivisor.app.ui.components.RemoteImage
import com.mivisor.app.ui.components.TvClickable
import com.mivisor.app.ui.components.Txt
import com.mivisor.app.ui.theme.Palette
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Estilo "neón" de la Agenda deportiva (según las pantallas de referencia):
 * azul noche, paneles con borde, foco cian con halo y acentos violeta/magenta.
 */
object Neon {
    val Bg = Color(0xFF060A1C)
    val Panel = Color(0xFF0D1330)
    val PanelHi = Color(0xFF141C45)
    val Stroke = Color(0xFF1E2858)
    val Cyan = Color(0xFF22D3EE)
    val Violet = Color(0xFF8B5CF6)
    val Magenta = Color(0xFFC026D3)
    val Blue = Color(0xFF3B5BFF)
    val Red = Color(0xFFE11D48)
    val RedBg = Color(0xFF3A0B1C)
    val Text = Color(0xFFF4F6FF)
    val Dim = Color(0xFFA3AED6)
    val Faint = Color(0xFF6672A3)
    val Subtitle = Color(0xFF8FB8FF)
}

object NeonType {
    val Hero = TextStyle(color = Neon.Text, fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.ExtraBold)
    val HeroSub = TextStyle(color = Neon.Subtitle, fontSize = 16.sp, lineHeight = 20.sp)
    val H2 = TextStyle(color = Neon.Text, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
    val H3 = TextStyle(color = Neon.Text, fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold)
    val Body = TextStyle(color = Neon.Text, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    val Small = TextStyle(color = Neon.Dim, fontSize = 12.sp, lineHeight = 16.sp)
    val Time = TextStyle(color = Neon.Text, fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
    val Pill = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold)
    val Team = TextStyle(color = Neon.Text, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
}

/**
 * Fondo de "estadio": degradados radiales violeta/azul dibujados en un solo paso
 * (sin imágenes: cero RAM extra y nítido a cualquier resolución).
 */
fun Modifier.stadiumLights(): Modifier = drawBehind {
    drawRect(Neon.Bg)
    drawRect(
        Brush.radialGradient(
            listOf(Neon.Violet.copy(alpha = 0.55f), Color.Transparent),
            center = Offset(size.width * 0.72f, -size.height * 0.05f), radius = size.width * 0.42f
        )
    )
    drawRect(
        Brush.radialGradient(
            listOf(Neon.Blue.copy(alpha = 0.45f), Color.Transparent),
            center = Offset(size.width * 0.98f, size.height * 0.10f), radius = size.width * 0.30f
        )
    )
    drawRect(
        Brush.radialGradient(
            listOf(Neon.Magenta.copy(alpha = 0.25f), Color.Transparent),
            center = Offset(size.width * 0.45f, size.height * 0.02f), radius = size.width * 0.22f
        )
    )
    // Haces de luz sutiles
    for (i in 0 until 5) {
        val x = size.width * (0.55f + i * 0.09f)
        drawLine(
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.07f), Color.Transparent), endY = size.height * 0.45f),
            start = Offset(x, 0f), end = Offset(x - size.width * 0.08f, size.height * 0.45f),
            strokeWidth = size.width * 0.012f
        )
    }
    drawRect(
        Brush.verticalGradient(
            listOf(Color.Transparent, Neon.Bg), startY = size.height * 0.18f, endY = size.height * 0.42f
        )
    )
}

/** Panel con degradado y borde fino (tarjetas no enfocables). */
fun Modifier.neonPanel(radius: Dp = 16.dp, highlight: Color = Neon.Violet): Modifier =
    background(
        Brush.linearGradient(listOf(highlight.copy(alpha = 0.22f), Neon.Panel, Neon.Panel)),
        RoundedCornerShape(radius)
    ).border(1.dp, Neon.Stroke, RoundedCornerShape(radius))

/** Elemento enfocable con borde cian y halo (el foco de las referencias). */
@Composable
fun NeonCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    radius: Dp = 14.dp,
    color: Color = Neon.Panel,
    focusedColor: Color = Neon.PanelHi,
    borderColor: Color = Neon.Stroke,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.(focused: Boolean) -> Unit
) {
    TvClickable(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(radius),
        color = color,
        focusedColor = focusedColor,
        borderColor = borderColor,
        focusedBorderColor = Neon.Cyan,
        glowColor = Neon.Cyan,
        glowCornerRadius = radius,
        focusedScale = 1.015f,
        contentAlignment = contentAlignment,
        content = content
    )
}

/** Botón píldora: normal, seleccionado (borde cian) o enfocado (cian con halo). */
@Composable
fun NeonButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    selected: Boolean = false,
    height: Dp = 36.dp
) {
    TvClickable(
        onClick = onClick,
        modifier = modifier.height(height),
        shape = RoundedCornerShape(height / 2),
        color = if (selected) Neon.PanelHi else Neon.Panel,
        focusedColor = Neon.PanelHi,
        borderColor = if (selected) Neon.Cyan else Neon.Stroke,
        borderWidth = if (selected) 2.dp else 1.dp,
        focusedBorderColor = Neon.Cyan,
        glowColor = Neon.Cyan,
        glowCornerRadius = height / 2,
        focusedScale = 1.04f,
        contentAlignment = Alignment.Center
    ) { focused ->
        Row(
            Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (icon != null) Icon(icon, if (focused || selected) Neon.Cyan else Neon.Dim, size = 16.dp)
            Txt(label, NeonType.Body, color = if (focused || selected) Neon.Text else Neon.Dim)
        }
    }
}

/** Estado del evento: "En vivo" (rojo), "Próximo" o "Finalizado". */
@Composable
fun StatusPill(status: EventStatus, modifier: Modifier = Modifier) {
    val (label, bg, fg) = when (status) {
        EventStatus.LIVE -> Triple("En vivo", Neon.RedBg, Color(0xFFFF5C7A))
        EventStatus.UPCOMING -> Triple("Próximo", Neon.PanelHi, Neon.Dim)
        EventStatus.FINISHED -> Triple("Finalizado", Color.Transparent, Neon.Faint)
    }
    Row(
        modifier
            .background(bg, RoundedCornerShape(8.dp))
            .border(1.dp, if (status == EventStatus.LIVE) Neon.Red.copy(alpha = 0.7f) else Neon.Stroke, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (status == EventStatus.LIVE) Box(Modifier.size(7.dp).background(Neon.Red, RoundedCornerShape(4.dp)))
        Txt(label, NeonType.Pill, color = fg)
    }
}

/** Chip informativo (dial, nota, calidad…). */
@Composable
fun InfoChip(text: String, color: Color = Neon.Dim, dot: Color? = null) {
    Row(
        Modifier.background(Neon.Panel, RoundedCornerShape(8.dp))
            .border(1.dp, Neon.Stroke, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (dot != null) Box(Modifier.size(7.dp).background(dot, RoundedCornerShape(4.dp)))
        Txt(text, NeonType.Pill, color = color)
    }
}

/**
 * Escudo/logo: imagen remota si hay URL (agenda.json o logos.json) y, mientras no,
 * iniciales sobre un escudo de color. Así la UI queda lista para cuando haya logos.
 */
@Composable
fun Crest(url: String?, name: String, size: Dp, modifier: Modifier = Modifier) {
    RemoteImage(url, size, modifier) {
        val tint = Palette.Tints[(name.hashCode() and 0x7fffffff) % Palette.Tints.size]
        Box(
            Modifier.size(size)
                .background(
                    Brush.verticalGradient(listOf(tint, tint.copy(alpha = 0.6f))),
                    RoundedCornerShape(topStart = size / 5, topEnd = size / 5, bottomStart = size / 2, bottomEnd = size / 2)
                )
                .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(topStart = size / 5, topEnd = size / 5, bottomStart = size / 2, bottomEnd = size / 2)),
            contentAlignment = Alignment.Center
        ) {
            Txt(
                CatalogBuilder.initialsOf(name),
                TextStyle(color = Color.White, fontSize = (size.value * 0.36f).sp, fontWeight = FontWeight.Bold)
            )
        }
    }
}

@DrawableRes
fun sportIcon(sport: String): Int = when (sport) {
    "baloncesto" -> R.drawable.ic_sport_baloncesto
    "tenis" -> R.drawable.ic_sport_tenis
    "motor" -> R.drawable.ic_sport_motor
    else -> R.drawable.ic_sport_futbol
}

fun sportLabel(sport: String): String = when (sport) {
    "futbol" -> "Fútbol"
    "baloncesto" -> "Baloncesto"
    "tenis" -> "Tenis"
    "motor" -> "Motor"
    else -> sport.replaceFirstChar { it.uppercase() }
}

private val SPANISH = Locale("es", "ES")

fun timeOf(millis: Long): String = SimpleDateFormat("HH:mm", SPANISH).format(Date(millis))

/** "Sábado 24" */
fun dayTitle(millis: Long): String =
    SimpleDateFormat("EEEE d", SPANISH).format(Date(millis)).replaceFirstChar { it.uppercase() }

/** Barra superior: marca, accesos y reloj. */
@Composable
fun TopBar(onMyChannels: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(20_000)
        }
    }
    Row(modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(34.dp).background(
                Brush.linearGradient(listOf(Neon.Violet, Neon.Magenta)), RoundedCornerShape(9.dp)
            ),
            contentAlignment = Alignment.Center
        ) { Icon(R.drawable.ic_play, Color.White, size = 18.dp) }
        Box(Modifier.width(12.dp))
        Txt("Mi Visor", NeonType.H2)
        Txt(" TV", NeonType.H2.copy(color = Neon.Violet))
        Box(Modifier.weight(1f))
        NeonButton("Mis canales", onMyChannels, icon = R.drawable.ic_list)
        Box(Modifier.width(10.dp))
        NeonButton("Ajustes", onSettings, icon = R.drawable.ic_settings)
        Box(Modifier.width(18.dp))
        Txt(timeOf(now), NeonType.H2.copy(fontWeight = FontWeight.Medium))
    }
}

/** Pareja de escudos "local VS visitante" con nombres debajo. */
@Composable
fun TeamsVs(
    home: String, homeLogo: String?, away: String, awayLogo: String?,
    crestSize: Dp, nameWidth: Dp, modifier: Modifier = Modifier, nameStyle: TextStyle = NeonType.Team
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        TeamColumn(home, homeLogo, crestSize, nameWidth, nameStyle)
        Txt("VS", NeonType.Small.copy(fontWeight = FontWeight.Bold), color = Neon.Faint, modifier = Modifier.padding(horizontal = 6.dp))
        TeamColumn(away, awayLogo, crestSize, nameWidth, nameStyle)
    }
}

@Composable
fun TeamColumn(name: String, logo: String?, crestSize: Dp, width: Dp, style: TextStyle = NeonType.Team) {
    Column(Modifier.width(width), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Crest(logo, name, crestSize)
        Txt(name, style)
    }
}
