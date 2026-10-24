package com.mivisor.app.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Sistema visual de Mi Visor.
 *
 * - Fondo grafito (no negro puro: evita el "blooming" de los paneles LED baratos).
 * - Un solo acento ámbar para estado activo y acciones principales.
 * - El foco INVIERTE la tarjeta (fondo claro, texto oscuro): se ve desde el sofá y
 *   no necesita sombras ni brillos, que son caros en GPUs de TV de gama baja.
 */
object Palette {
    val Bg = Color(0xFF0D0F13)
    val Rail = Color(0xFF111419)
    val Surface = Color(0xFF181C23)
    val SurfaceHi = Color(0xFF222731)
    val Outline = Color(0xFF2E3440)
    val Text = Color(0xFFEEF1F6)
    val TextDim = Color(0xFFA4ACBA)
    val TextFaint = Color(0xFF6C7483)
    val Accent = Color(0xFFFFB020)
    val OnAccent = Color(0xFF1E1500)
    val Focus = Color(0xFFF5F7FA)
    val OnFocus = Color(0xFF0D0F13)
    val OnFocusDim = Color(0xFF4A5261)
    val Warning = Color(0xFFFFC857)
    val Danger = Color(0xFFFF6B6B)
    val DangerBg = Color(0xFF34181C)

    /** Colores de monograma: tonos apagados para que el acento siga destacando. */
    val Tints = listOf(
        Color(0xFF3B5BA9), Color(0xFF2F7D6D), Color(0xFF8A4F9E), Color(0xFFA0522D),
        Color(0xFF2E6F95), Color(0xFF7A6A2B), Color(0xFF9B3D5A), Color(0xFF4F6B3A)
    )
}

object Type {
    val Display = TextStyle(color = Palette.Text, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
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
