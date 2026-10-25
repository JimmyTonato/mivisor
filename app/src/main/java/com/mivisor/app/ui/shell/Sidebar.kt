package com.mivisor.app.ui.shell

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mivisor.app.QuickServicesUi
import com.mivisor.app.R
import com.mivisor.app.services.Indicator
import com.mivisor.app.services.RowAction
import com.mivisor.app.services.ServiceRow
import com.mivisor.app.ui.components.Icon
import com.mivisor.app.ui.components.TvClickable
import com.mivisor.app.ui.components.Txt
import com.mivisor.app.ui.kit.Ui
import com.mivisor.app.ui.neon.Neon
import com.mivisor.app.ui.neon.NeonType
import com.mivisor.app.ui.neon.Spinner

/** Navegación principal (5 secciones). Calendario y competiciones se abren desde Inicio y En directo. */
enum class NavItem(val label: String, @DrawableRes val icon: Int) {
    HOME("Inicio", R.drawable.ic_home),
    LIVE("En directo", R.drawable.ic_live),
    CHANNELS("Canales", R.drawable.ic_tv),
    HISTORY("Historial", R.drawable.ic_history),
    SETTINGS("Ajustes", R.drawable.ic_settings)
}

val SidebarWidth: Dp = 184.dp

/**
 * Barra lateral: logo, las 5 secciones y, abajo, "Servicios de reproducción" (Ace Stream y VPN/WARP).
 * Al entrar con ◀ desde el contenido, el foco cae en la sección activa (no en la más cercana).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Sidebar(
    selected: NavItem,
    onSelect: (NavItem) -> Unit,
    services: QuickServicesUi,
    onServiceOk: (RowAction) -> Unit,
    liveCount: Int,
    selectedFocus: FocusRequester,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .width(SidebarWidth)
            .fillMaxHeight()
            .background(Brush.horizontalGradient(listOf(Neon.Bg.copy(alpha = 0.92f), Neon.Bg.copy(alpha = 0.55f))))
            .padding(start = 14.dp, end = 10.dp, top = 14.dp, bottom = 12.dp)
            .focusProperties { enter = { selectedFocus } }
            .focusGroup()
    ) {
        Logo()
        Box(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NavItem.values().forEach { item ->
                NavRow(
                    item = item,
                    selected = item == selected,
                    badge = if (item == NavItem.LIVE && liveCount > 0) liveCount.toString() else null,
                    onClick = { onSelect(item) },
                    modifier = if (item == selected) Modifier.focusRequester(selectedFocus) else Modifier
                )
            }
        }
        Box(Modifier.weight(1f))
        Txt(
            "Servicios de reproducción",
            NeonType.Small.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Neon.Faint),
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ServiceRowView(services.ace, R.drawable.ic_bolt, onServiceOk)
            ServiceRowView(services.warp, R.drawable.ic_shield, onServiceOk)
        }
        if (services.preview) {
            Txt("Vista previa de estados", NeonType.Pill, color = Ui.Amber, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
        }
    }
}

@Composable
fun Logo(modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(if (compact) 26.dp else 32.dp).background(
                Brush.linearGradient(listOf(Neon.Violet, Neon.Magenta)), RoundedCornerShape(9.dp)
            ),
            contentAlignment = Alignment.Center
        ) { Icon(R.drawable.ic_play, Color.White, size = if (compact) 14.dp else 17.dp) }
        Box(Modifier.width(10.dp))
        Txt("Mi Visor", NeonType.H2.copy(fontSize = if (compact) 17.sp else 19.sp))
        Txt(" TV", NeonType.H2.copy(fontSize = if (compact) 17.sp else 19.sp, color = Neon.Violet))
    }
}

@Composable
private fun NavRow(item: NavItem, selected: Boolean, badge: String?, onClick: () -> Unit, modifier: Modifier) {
    TvClickable(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(40.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Neon.PanelHi else Color.Transparent,
        focusedColor = Neon.PanelHi,
        borderColor = if (selected) Neon.Cyan.copy(alpha = 0.75f) else Color.Transparent,
        borderWidth = if (selected) 2.dp else 1.dp,
        focusedScale = 1.02f,
        contentAlignment = Alignment.CenterStart
    ) { focused ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(item.icon, if (selected || focused) Neon.Cyan else Neon.Dim, size = 19.dp)
            Box(Modifier.width(12.dp))
            Txt(item.label, NeonType.H3, color = if (selected || focused) Neon.Text else Neon.Dim, modifier = Modifier.weight(1f))
            if (badge != null) {
                Box(Modifier.background(Neon.Red, RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 1.dp)) {
                    Txt(badge, NeonType.Pill.copy(fontWeight = FontWeight.Bold), color = Color.White)
                }
            }
        }
    }
}

fun Indicator.color(): Color = when (this) {
    Indicator.OK -> Ui.Green
    Indicator.NEUTRAL -> Neon.Faint
    Indicator.WARNING -> Ui.Amber
    Indicator.ERROR -> Neon.Red
    Indicator.BUSY -> Neon.Cyan
}

/**
 * Fila de servicio: icono, nombre y estado ("● Activo"). Si hay que actuar, el botón aparece en la propia fila.
 * Todo correcto = discreto: sin banners ni animaciones.
 */
@Composable
fun ServiceRowView(row: ServiceRow, @DrawableRes icon: Int, onOk: (RowAction) -> Unit, modifier: Modifier = Modifier) {
    val attention = row.indicator == Indicator.WARNING || row.indicator == Indicator.ERROR
    val c = row.indicator.color()
    TvClickable(
        onClick = { onOk(row.onOk) },
        modifier = modifier.fillMaxWidth().heightIn(min = 46.dp),
        shape = RoundedCornerShape(12.dp),
        color = Neon.Panel.copy(alpha = 0.85f),
        focusedColor = Neon.PanelHi,
        borderColor = if (attention) c.copy(alpha = 0.5f) else Neon.Stroke,
        focusedScale = 1.02f,
        contentAlignment = Alignment.CenterStart
    ) { focused ->
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, if (attention) c else Neon.Dim, size = 16.dp)
                Box(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Txt(row.title, NeonType.H3.copy(fontSize = 12.sp, lineHeight = 15.sp), color = if (focused) Neon.Text else Neon.Dim)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StateDot(row.indicator, 8.dp)
                        Box(Modifier.width(5.dp))
                        Txt(row.status, NeonType.Small.copy(fontSize = 11.sp), color = if (row.indicator == Indicator.OK || row.indicator == Indicator.NEUTRAL || row.indicator == Indicator.BUSY) Neon.Dim else c, maxLines = 2)
                    }
                }
                if (row.actionLabel == null) Icon(R.drawable.ic_chevron_right, Neon.Faint, size = 14.dp)
            }
            row.actionLabel?.let { label ->
                Box(
                    Modifier.fillMaxWidth()
                        .background(if (focused) c.copy(alpha = 0.30f) else c.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                        .border(1.dp, c.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                        .padding(vertical = 3.dp),
                    contentAlignment = Alignment.Center
                ) { Txt(label, NeonType.Pill.copy(fontWeight = FontWeight.Bold), color = if (focused) Neon.Text else c) }
            }
        }
    }
}

/** ● correcto (verde) · ○ desconectado (gris) · ● ámbar requiere acción · ● rojo error · spinner en curso. */
@Composable
fun StateDot(indicator: Indicator, size: Dp) {
    val c = indicator.color()
    when (indicator) {
        Indicator.NEUTRAL -> Box(Modifier.size(size).border(1.5.dp, c, CircleShape))
        Indicator.BUSY -> Spinner(size + 2.dp, c)
        else -> Box(Modifier.size(size).background(c, CircleShape))
    }
}

/** Móvil: barra inferior con las 5 secciones. */
@Composable
fun BottomBar(selected: NavItem, onSelect: (NavItem) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Neon.Bg.copy(alpha = 0.95f), Neon.Bg)))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        NavItem.values().forEach { item ->
            val active = item == selected
            TvClickable(
                onClick = { onSelect(item) },
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(12.dp),
                color = if (active) Neon.PanelHi else Neon.Panel,
                focusedColor = Neon.PanelHi,
                borderColor = if (active) Neon.Cyan else Neon.Stroke,
                contentAlignment = Alignment.Center
            ) { focused ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(item.icon, if (active || focused) Neon.Cyan else Neon.Dim, size = 19.dp)
                    Txt(item.label, NeonType.Pill.copy(fontSize = 10.sp), color = if (active || focused) Neon.Text else Neon.Dim)
                }
            }
        }
    }
}

/** Móvil: los dos servicios como chips arriba de Inicio. */
@Composable
fun ServiceChips(services: QuickServicesUi, onOk: (RowAction) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(services.ace to "Ace", services.warp to "VPN").forEach { (row, short) ->
            TvClickable(
                onClick = { onOk(row.onOk) },
                modifier = Modifier.height(30.dp),
                shape = RoundedCornerShape(15.dp),
                color = Neon.Panel,
                borderColor = Neon.Stroke,
                contentAlignment = Alignment.Center
            ) { _ ->
                Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    StateDot(row.indicator, 8.dp)
                    Box(Modifier.width(6.dp))
                    Txt("$short · ${row.actionLabel ?: row.status}", NeonType.Pill, color = Neon.Text)
                }
            }
        }
    }
}

/** Indicador de servicio para paneles grandes (Ajustes, panel rápido). */
@Composable
fun BigStateGlyph(indicator: Indicator, @DrawableRes icon: Int) {
    val c = indicator.color()
    Box(
        Modifier.size(38.dp).background(c.copy(alpha = 0.14f), CircleShape).border(1.dp, c.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (indicator == Indicator.BUSY) Spinner(20.dp, c) else Icon(icon, c, size = 20.dp)
    }
}

@Composable
internal fun rememberFocus(): FocusRequester = remember { FocusRequester() }
