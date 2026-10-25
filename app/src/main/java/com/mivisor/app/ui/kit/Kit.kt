package com.mivisor.app.ui.kit

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.mivisor.app.data.Quality
import com.mivisor.app.model.AgendaEvent
import com.mivisor.app.model.Competition
import com.mivisor.app.model.EventStatus
import com.mivisor.app.ui.components.Icon
import com.mivisor.app.ui.components.TvClickable
import com.mivisor.app.ui.components.Txt
import com.mivisor.app.ui.neon.Crest
import com.mivisor.app.ui.neon.LocalAppLayout
import com.mivisor.app.ui.neon.Neon
import com.mivisor.app.ui.neon.NeonCard
import com.mivisor.app.ui.neon.NeonType
import com.mivisor.app.ui.neon.Participant
import com.mivisor.app.ui.neon.eventTime
import com.mivisor.app.ui.neon.sportIcon
import com.mivisor.app.ui.neon.sportLabel
import com.mivisor.app.ui.neon.timeOf
import com.mivisor.app.ui.theme.Palette
import kotlinx.coroutines.delay

/*
 * Piezas comunes del rediseño (TV primero): cabeceras, píldoras de estado, tarjetas de evento,
 * etiquetas de proveedor/calidad y botones. Compactas, sin bajar la letra por debajo de lo legible desde el sofá.
 */

object Ui {
    val Green = Color(0xFF34D399)
    val Amber = Color(0xFFF59E0B)
    val LiveRed = Color(0xFFFF4D6D)

    val PageTitle = TextStyle(color = Neon.Text, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.ExtraBold)
    val Section = TextStyle(color = Neon.Text, fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold)
    val CardTitle = TextStyle(color = Neon.Text, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold)
    val CardSmall = TextStyle(color = Neon.Dim, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
}

/**
 * Texto que se adapta al ancho real disponible: hasta [maxLines] líneas y, si aún no cabe, letra algo más pequeña
 * (hasta [minSp]). La elipsis solo aparece si ni así cabe. Tiene en cuenta el escalado de fuente del sistema.
 */
@Composable
fun FitText(
    text: String,
    style: TextStyle,
    baseSp: Float,
    minSp: Float,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
    textAlign: TextAlign? = null,
    color: Color = Color.Unspecified
) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
        val sp = TextFit.fit(text, maxWidth.value, baseSp, minSp, maxLines, fontScale)
        val fitted = style.copy(fontSize = sp.sp, lineHeight = (sp * 1.2f).sp, textAlign = textAlign ?: style.textAlign)
        Txt(text, fitted, color = color, maxLines = maxLines)
    }
}

/** Hora actual (se refresca cada 20 s). */
@Composable
fun Clock(style: TextStyle = NeonType.H2.copy(fontWeight = FontWeight.Medium)) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(20_000)
        }
    }
    Txt(timeOf(now), style)
}

/** Pide el foco tras el primer frame, con reintentos (las listas tardan en colocar sus elementos). */
@Composable
fun AutoFocus(requester: FocusRequester, enabled: Boolean = true, key: Any? = Unit) {
    LaunchedEffect(key, enabled) {
        if (!enabled) return@LaunchedEffect
        repeat(6) {
            withFrameNanos { }
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
}

/** "< Canales / Todos": vuelta atrás enfocable (también vale el botón Atrás del mando). */
@Composable
fun BackLink(label: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    NeonCard(
        onClick = onBack,
        modifier = modifier.height(28.dp),
        radius = 14.dp,
        color = Color.Transparent,
        borderColor = Color.Transparent,
        contentAlignment = Alignment.CenterStart
    ) { focused ->
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(R.drawable.ic_chevron_left, Neon.Subtitle, size = 16.dp)
            Box(Modifier.width(4.dp))
            Txt(label, NeonType.Body, color = if (focused) Neon.Text else Neon.Subtitle)
        }
    }
}

/** Cabecera de pantalla: título, subtítulo, línea de datos y acciones + reloj a la derecha. */
@Composable
fun PageHeader(
    title: String,
    subtitle: String? = null,
    meta: String? = null,
    metaColor: Color = Neon.Faint,
    back: Pair<String, () -> Unit>? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val layout = LocalAppLayout.current
    Column(modifier.fillMaxWidth().padding(top = if (layout.isTv) 12.dp else 8.dp, bottom = 8.dp)) {
        if (back != null) BackLink(back.first, back.second, Modifier.padding(bottom = 2.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Txt(title, if (layout.compact) Ui.PageTitle.copy(fontSize = 22.sp, lineHeight = 26.sp) else Ui.PageTitle)
                if (subtitle != null && !layout.short) Txt(subtitle, NeonType.HeroSub.copy(fontSize = 14.sp), maxLines = 2)
                if (!meta.isNullOrBlank()) Txt(meta, NeonType.Small, color = metaColor)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
            if (!layout.compact) {
                Box(Modifier.width(16.dp))
                Clock()
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, count: Int? = null, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Txt(text, Ui.Section)
        if (count != null) Txt("  ($count)", Ui.Section.copy(color = Neon.Faint, fontWeight = FontWeight.Medium))
        Box(Modifier.weight(1f))
        trailing()
    }
}

// ============================================================================ estado del evento

@Composable
fun LivePill(modifier: Modifier = Modifier, small: Boolean = true) {
    Row(
        modifier.background(Neon.Red, RoundedCornerShape(50))
            .padding(horizontal = if (small) 7.dp else 10.dp, vertical = if (small) 2.dp else 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(if (small) 6.dp else 7.dp).background(Color.White, CircleShape))
        Box(Modifier.width(5.dp))
        Txt("EN DIRECTO", NeonType.Pill.copy(fontWeight = FontWeight.ExtraBold, fontSize = if (small) 10.sp else 11.sp), color = Color.White)
    }
}

@Composable
fun SoftPill(text: String, modifier: Modifier = Modifier, color: Color = Neon.Dim) {
    Box(
        modifier.background(Neon.PanelHi, RoundedCornerShape(50)).border(1.dp, Neon.Stroke, RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 2.dp)
    ) { Txt(text, NeonType.Pill, color = color) }
}

@Composable
fun StatusBadge(status: EventStatus, modifier: Modifier = Modifier) = when (status) {
    EventStatus.LIVE -> LivePill(modifier)
    EventStatus.UPCOMING -> SoftPill("Próximo", modifier)
    EventStatus.FINISHED -> SoftPill("Finalizado", modifier, Neon.Faint)
}

/** Logo/escudo de la competición (o sus iniciales) + nombre corto. */
@Composable
fun CompetitionLabel(event: AgendaEvent, modifier: Modifier = Modifier, size: Dp = 14.dp, style: TextStyle = Ui.CardSmall, maxLines: Int = 1) {
    val competition = Competition.of(event)
    val label = if (competition == Competition.FUTBOL || competition == Competition.TENIS || competition == Competition.OTHER || competition == Competition.MOTOR)
        event.competition ?: sportLabel(event.sport) else competition.short
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (event.competitionLogo != null) {
            Crest(event.competitionLogo, label, size)
        } else {
            Icon(sportIcon(event.sport), Neon.Dim, size = size)
        }
        Box(Modifier.width(5.dp))
        if (maxLines > 1) FitText(label, style, baseSp = style.fontSize.value.takeIf { it > 0 } ?: 11f, minSp = 10f, maxLines = maxLines)
        else Txt(label, style)
    }
}

/** Tarjeta compacta de "En directo ahora" y "Empieza pronto". Toda la tarjeta es el control: OK = quiero ver esto. */
@Composable
fun EventCard(
    event: AgendaEvent,
    now: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 150.dp,
    height: Dp = 90.dp
) {
    val status = event.status(now)
    NeonCard(onClick = onClick, modifier = modifier.width(width).height(height), radius = 12.dp) { focused ->
        Box(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(listOf(Neon.Panel, (if (status == EventStatus.LIVE) Neon.Red else Neon.Violet).copy(alpha = if (focused) 0.26f else 0.14f))),
                RoundedCornerShape(12.dp)
            )
        )
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (status == EventStatus.LIVE) LivePill() else SoftPill(if (status == EventStatus.FINISHED) "Final" else "Próximo")
                Box(Modifier.weight(1f))
                CompetitionLabel(event, Modifier.widthIn(max = width * 0.55f))
            }
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
              Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                val home = event.home
                val away = event.away
                if (home != null && away != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Participant(event.homeVisual, home, 22.dp, event.isPersonVsPerson)
                        Txt("vs", Ui.CardSmall.copy(color = Neon.Faint), modifier = Modifier.padding(horizontal = 8.dp))
                        Participant(event.awayVisual, away, 22.dp, event.isPersonVsPerson)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(sportIcon(event.sport), Neon.Text, size = 22.dp)
                    }
                }
              }
              // La hora va junto a los escudos: los nombres tienen todo el ancho de la tarjeta.
              Txt(eventTime(event), Ui.CardSmall.copy(color = Neon.Text, fontWeight = FontWeight.Bold))
            }
            FitText(
                if (event.home != null && event.away != null) "${event.home} vs ${event.away}" else event.title,
                Ui.CardTitle, baseSp = 12f, minSp = 10f, modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ============================================================================ canales y proveedores

/** Logo de canal o proveedor mientras no hay imágenes: iniciales sobre un degradado de su color. */
@Composable
fun ChannelLogo(name: String, size: Dp, modifier: Modifier = Modifier, tint: Int = (name.hashCode() and 0x7fffffff) % CatalogBuilder.TINTS) {
    val base = Palette.Tints[tint % Palette.Tints.size]
    Box(
        modifier.size(size).background(Brush.linearGradient(listOf(base, base.copy(alpha = 0.55f))), RoundedCornerShape(size / 5))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(size / 5)),
        contentAlignment = Alignment.Center
    ) {
        Txt(
            CatalogBuilder.initialsOf(name),
            TextStyle(color = Color.White, fontSize = (size.value * 0.36f).sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
        )
    }
}

/** Etiqueta pequeña de proveedor: "■ Movistar". */
@Composable
fun ProviderTag(name: String, modifier: Modifier = Modifier) {
    Row(
        modifier.background(Neon.Bg.copy(alpha = 0.6f), RoundedCornerShape(6.dp)).border(1.dp, Neon.Stroke, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val tint = Palette.Tints[(name.hashCode() and 0x7fffffff) % Palette.Tints.size]
        Box(Modifier.size(8.dp).background(tint, RoundedCornerShape(2.dp)))
        Box(Modifier.width(5.dp))
        Txt(name, NeonType.Pill, color = Neon.Dim)
    }
}

/** "Full HD" (ámbar) · "4K" (cian) · "HD"/"SD" (neutro). */
@Composable
fun QualityTag(quality: Quality?, modifier: Modifier = Modifier) {
    quality ?: return
    val color = when (quality) {
        Quality.UHD -> Neon.Cyan
        Quality.FHD -> Ui.Amber
        else -> Neon.Dim
    }
    Box(
        modifier.border(1.dp, color.copy(alpha = 0.8f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
    ) { Txt(quality.label, NeonType.Pill.copy(fontWeight = FontWeight.Bold), color = color) }
}

/** Chip informativo con punto de color opcional ("● En directo", "Turquía"). */
@Composable
fun MetaTag(text: String, modifier: Modifier = Modifier, dot: Color? = null, color: Color = Neon.Dim) {
    Row(
        modifier.background(Neon.Panel, RoundedCornerShape(6.dp)).border(1.dp, Neon.Stroke, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dot != null) {
            Box(Modifier.size(6.dp).background(dot, CircleShape))
            Box(Modifier.width(5.dp))
        }
        Txt(text, NeonType.Pill, color = color)
    }
}

/** "3 fuentes" en la esquina de una tarjeta. */
@Composable
fun CountBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.background(Neon.PanelHi, RoundedCornerShape(50)).border(1.dp, Neon.Stroke, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) { Txt(text, NeonType.Pill, color = Neon.Text) }
}

/** Botón principal ("Ver ahora"): relleno cian; al enfocarlo, halo. */
@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = R.drawable.ic_play,
    height: Dp = 38.dp
) {
    TvClickable(
        onClick = onClick,
        modifier = modifier.height(height),
        shape = RoundedCornerShape(10.dp),
        color = Neon.Cyan.copy(alpha = 0.85f),
        focusedColor = Neon.Cyan,
        borderColor = Color.Transparent,
        focusedBorderColor = Color.White,
        glowColor = Neon.Cyan,
        focusedScale = 1.04f,
        contentAlignment = Alignment.Center
    ) { _ ->
        Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, Neon.Bg, size = 16.dp)
                Box(Modifier.width(8.dp))
            }
            Txt(label, NeonType.H3, color = Neon.Bg)
        }
    }
}

/** Botón secundario con borde. */
@Composable
fun SecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    height: Dp = 38.dp,
    selected: Boolean = false
) {
    TvClickable(
        onClick = onClick,
        modifier = modifier.height(height),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) Neon.PanelHi else Neon.Panel.copy(alpha = 0.85f),
        focusedColor = Neon.PanelHi,
        borderColor = if (selected) Neon.Cyan else Neon.Stroke,
        focusedBorderColor = Neon.Cyan,
        glowColor = Neon.Cyan,
        focusedScale = 1.04f,
        contentAlignment = Alignment.Center
    ) { focused ->
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, if (focused || selected) Neon.Cyan else Neon.Dim, size = 16.dp)
                Box(Modifier.width(8.dp))
            }
            Txt(label, NeonType.Body, color = if (focused || selected) Neon.Text else Neon.Dim)
        }
    }
}

/** Panel vacío/error con acción al lado (los errores siempre tienen una acción). */
@Composable
fun EmptyPanel(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int = R.drawable.ic_info,
    danger: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Column(
        modifier.widthIn(max = 620.dp).fillMaxWidth()
            .background(Brush.linearGradient(listOf((if (danger) Neon.Red else Neon.Violet).copy(alpha = 0.18f), Neon.Panel, Neon.Panel)), RoundedCornerShape(16.dp))
            .border(1.dp, Neon.Stroke, RoundedCornerShape(16.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, if (danger) Neon.Red else Neon.Cyan, size = 24.dp)
        Txt(title, NeonType.H2, maxLines = 2)
        Txt(detail, NeonType.Body, color = Neon.Dim, maxLines = 4)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), content = actions)
    }
}

/** Fila "dato: valor" de los paneles laterales de información. */
@Composable
fun InfoLine(@DrawableRes icon: Int, label: String, value: String, valueColor: Color = Neon.Text) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, Neon.Faint, size = 15.dp)
        Box(Modifier.width(8.dp))
        Txt(label, NeonType.Small, modifier = Modifier.width(92.dp))
        Txt(value, NeonType.Body.copy(fontSize = 13.sp), color = valueColor, modifier = Modifier.weight(1f), maxLines = 2)
    }
}
