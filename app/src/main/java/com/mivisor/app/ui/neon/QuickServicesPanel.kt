package com.mivisor.app.ui.neon

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mivisor.app.QuickServicesUi
import com.mivisor.app.services.Indicator
import com.mivisor.app.services.RowAction
import com.mivisor.app.services.ServiceRow
import com.mivisor.app.ui.components.Txt
import com.mivisor.app.ui.theme.LocalAnimations

private val Green = Color(0xFF34D399)
private val Amber = Color(0xFFF59E0B)

private fun Indicator.color() = when (this) {
    Indicator.OK -> Green
    Indicator.NEUTRAL -> Neon.Faint
    Indicator.WARNING -> Amber
    Indicator.ERROR -> Neon.Red
    Indicator.BUSY -> Neon.Cyan
}

/**
 * "SERVICIOS" en lo alto de la columna lateral (TV/tablet): Ace Stream y VPN/WARP.
 * Cada fila es un único control enfocable: OK hace lo que indica la fila (iniciar, ON/OFF, autorizar…).
 * Si todo va bien ocupan poco; si hay que actuar, la propia fila muestra el botón.
 */
@Composable
fun QuickServicesSide(ui: QuickServicesUi, onOk: (RowAction) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Overline("Servicios", Modifier.padding(start = 8.dp, bottom = 4.dp))
        Column(Modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            ServiceSideRow(ui.ace, onOk)
            ServiceSideRow(ui.warp, onOk)
        }
        NeonDivider(Modifier.padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 8.dp))
    }
}

@Composable
private fun ServiceSideRow(row: ServiceRow, onOk: (RowAction) -> Unit) {
    val attention = row.indicator == Indicator.WARNING || row.indicator == Indicator.ERROR
    NeonCard(
        onClick = { onOk(row.onOk) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
        radius = 12.dp,
        borderColor = if (attention) row.indicator.color().copy(alpha = 0.55f) else Neon.Stroke,
        contentAlignment = Alignment.CenterStart
    ) { focused ->
        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StateGlyph(row.indicator, 12.dp)
                Box(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Txt(row.title, NeonType.H3.copy(fontSize = 13.sp, lineHeight = 16.sp), color = if (focused) Neon.Text else Neon.Dim)
                    Txt(
                        row.status, NeonType.Small.copy(fontSize = 12.sp),
                        color = if (attention) row.indicator.color() else Neon.Faint, maxLines = 2
                    )
                }
                row.toggle?.let { on -> Toggle(on, busy = row.indicator == Indicator.BUSY) }
            }
            row.actionLabel?.let { label -> ActionPill(label, row.indicator, focused) }
        }
    }
}

/** Interruptor visual (no es un control aparte: OK sobre la fila hace lo mismo). */
@Composable
private fun Toggle(on: Boolean, busy: Boolean) {
    val bg = if (on) Green.copy(alpha = if (busy) 0.35f else 0.85f) else Neon.Stroke
    Box(
        Modifier.width(30.dp).heightIn(min = 16.dp).background(bg, RoundedCornerShape(50)),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(Modifier.padding(2.dp).size(12.dp).background(Color.White.copy(alpha = if (busy) 0.6f else 1f), CircleShape))
    }
    Box(Modifier.width(4.dp))
    Txt(if (on) "ON" else "OFF", NeonType.Pill.copy(fontWeight = FontWeight.Bold), color = if (on) Green else Neon.Faint)
}

@Composable
private fun ActionPill(label: String, indicator: Indicator, focused: Boolean) {
    val c = indicator.color()
    Box(
        Modifier.fillMaxWidth()
            .background(if (focused) c.copy(alpha = 0.25f) else Color.Transparent, RoundedCornerShape(8.dp))
            .border(1.dp, c.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Txt(label, NeonType.Pill.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp), color = if (focused) Neon.Text else c)
    }
}

/** ● correcto · ○ detenido/desconectado · ⚠ requiere atención · spinner en curso. */
@Composable
fun StateGlyph(indicator: Indicator, size: Dp) {
    val c = indicator.color()
    when (indicator) {
        Indicator.OK -> Box(Modifier.size(size).background(c, CircleShape))
        Indicator.NEUTRAL -> Box(Modifier.size(size).border(2.dp, c, CircleShape))
        Indicator.WARNING, Indicator.ERROR -> Box(
            Modifier.size(size + 2.dp).background(c, CircleShape), contentAlignment = Alignment.Center
        ) { Txt("!", NeonType.Pill.copy(fontSize = 10.sp, fontWeight = FontWeight.ExtraBold), color = Neon.Bg) }
        Indicator.BUSY -> Spinner(size, c)
    }
}

@Composable
fun Spinner(size: Dp, color: Color) {
    val angle = if (LocalAnimations.current) {
        val t = rememberInfiniteTransition(label = "spinner")
        val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "angle")
        a
    } else 0f
    Canvas(Modifier.size(size)) {
        drawArc(color.copy(alpha = 0.25f), 0f, 360f, false, style = Stroke(2.dp.toPx()))
        drawArc(color, angle, 100f, false, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** Móvil: los dos servicios como chips al principio de la fila de categorías. */
@Composable
fun QuickServiceChip(row: ServiceRow, onOk: (RowAction) -> Unit, modifier: Modifier = Modifier) {
    val label = buildString {
        append(if (row.title.startsWith("VPN")) "VPN" else "Ace")
        append(" · ")
        append(row.actionLabel?.lowercase()?.replaceFirstChar { it.uppercase() } ?: row.toggle?.let { if (it) "ON" else "OFF" } ?: row.status)
    }
    NeonCard(onClick = { onOk(row.onOk) }, modifier = modifier.heightIn(min = 34.dp), radius = 17.dp, contentAlignment = Alignment.Center) { _ ->
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            StateGlyph(row.indicator, 10.dp)
            Box(Modifier.width(6.dp))
            Txt(label, NeonType.Pill, color = Neon.Text)
        }
    }
}
