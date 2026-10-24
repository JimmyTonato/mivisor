package com.mivisor.app.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Sistema visual "neón" de Mi Visor (el de la Agenda, ahora en toda la app):
 *
 * - Azul noche con luces violeta/azul de fondo y paneles con borde fino.
 * - Foco: el elemento se ilumina un poco, borde cian y halo (contornos translúcidos,
 *   sin sombras ni desenfoques: fluido incluso en GPUs de TV de gama baja).
 * - Violeta para acciones principales; rojo solo para "En vivo" y acciones peligrosas.
 */
object Palette {
    val Bg = Color(0xFF060A1C)
    val Rail = Color(0xFF0A0F26)
    val Surface = Color(0xFF0D1330)
    val SurfaceHi = Color(0xFF141C45)
    val Outline = Color(0xFF1E2858)
    val Text = Color(0xFFF4F6FF)
    val TextDim = Color(0xFFA3AED6)
    val TextFaint = Color(0xFF6672A3)
    val Accent = Color(0xFF8B5CF6)
    val OnAccent = Color(0xFFFFFFFF)
    val Cyan = Color(0xFF22D3EE)
    val Focus = Color(0xFF18235A)
    val OnFocus = Color(0xFFF4F6FF)
    val OnFocusDim = Color(0xFFB9C3EA)
    val Warning = Color(0xFFFFC857)
    val Danger = Color(0xFFFF5C7A)
    val DangerBg = Color(0xFF3A0B1C)

    /** Colores de monograma: tonos apagados para que el acento siga destacando. */
    val Tints = listOf(
        Color(0xFF3B5BA9), Color(0xFF2F7D6D), Color(0xFF8A4F9E), Color(0xFFA0522D),
        Color(0xFF2E6F95), Color(0xFF7A6A2B), Color(0xFF9B3D5A), Color(0xFF4F6B3A)
    )
}

object Type {
    val Display = TextStyle(color = Palette.Text, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.ExtraBold)
    val Title = TextStyle(color = Palette.Text, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
    val Card = TextStyle(color = Palette.Text, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val Body = TextStyle(color = Palette.Text, fontSize = 16.sp, lineHeight = 22.sp)
    val Label = TextStyle(color = Palette.Text, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val Caption = TextStyle(color = Palette.TextDim, fontSize = 13.sp, lineHeight = 18.sp)
    val Overline = TextStyle(
        color = Palette.TextFaint, fontSize = 12.sp, lineHeight = 16.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp
    )
    val Badge = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
}

object Dimens {
    /** Márgenes seguros de overscan recomendados para Android TV (5 %). */
    val SafeH = 48.dp
    val SafeV = 27.dp
    val RailWidth = 232.dp
    val CardWidth = 208.dp
    val CardHeight = 118.dp
}

/** Animaciones de foco activables (en TVs muy justas se desactivan para ganar fluidez). */
val LocalAnimations = staticCompositionLocalOf { true }
