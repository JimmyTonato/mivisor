package com.mivisor.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mivisor.app.model.ChannelKind
import com.mivisor.app.ui.theme.Palette
import com.mivisor.app.ui.theme.Type

@Composable
fun Txt(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = 1
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = if (color == Color.Unspecified) style else style.copy(color = color),
        overflow = TextOverflow.Ellipsis,
        softWrap = maxLines > 1,
        maxLines = maxLines
    )
}

@Composable
fun Icon(@DrawableRes id: Int, tint: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Image(
        painter = painterResource(id),
        contentDescription = null,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint)
    )
}

enum class ButtonStyle { NORMAL, PRIMARY, DANGER, GHOST }

@Composable
fun TvButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    style: ButtonStyle = ButtonStyle.NORMAL,
    enabled: Boolean = true
) {
    val bg = when (style) {
        ButtonStyle.PRIMARY -> Palette.Accent
        ButtonStyle.DANGER -> Palette.DangerBg
        ButtonStyle.GHOST -> Color.Transparent
        ButtonStyle.NORMAL -> Palette.SurfaceHi
    }
    val fg = when (style) {
        ButtonStyle.PRIMARY -> Palette.OnAccent
        ButtonStyle.DANGER -> Palette.Danger
        else -> Palette.Text
    }
    TvClickable(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        enabled = enabled,
        shape = RoundedCornerShape(22.dp),
        color = bg,
        borderColor = if (style == ButtonStyle.GHOST) Palette.Outline else Color.Transparent,
        focusedScale = 1.04f,
        contentAlignment = Alignment.Center
    ) { focused ->
        val tint = if (focused) Palette.OnFocus else fg
        Row(
            Modifier.padding(horizontal = if (label.isEmpty()) 12.dp else 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (icon != null) Icon(icon, tint, size = 18.dp)
            if (label.isNotEmpty()) Txt(label, Type.Label, color = tint)
        }
    }
}

/** Avatar con iniciales: sustituye a los logos (que no vienen en el JSON) sin cargar imágenes. */
@Composable
fun Monogram(initials: String, tint: Int, size: Dp = 36.dp) {
    Box(
        Modifier
            .size(size)
            .background(Palette.Tints[tint % Palette.Tints.size], RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        Txt(initials, Type.Label, color = Color.White)
    }
}

fun kindColor(kind: ChannelKind): Color = when (kind) {
    ChannelKind.ACE -> Color(0xFF8EA6FF)
    ChannelKind.HLS -> Color(0xFF4FD1C5)
    ChannelKind.VIDEO -> Color(0xFFFBBF24)
    ChannelKind.PLAYLIST -> Color(0xFFA3E635)
    ChannelKind.MAGNET -> Color(0xFFF472B6)
    ChannelKind.TELEGRAM -> Color(0xFF38BDF8)
    ChannelKind.WEB -> Color(0xFFB8BFCC)
    ChannelKind.OTHER -> Color(0xFFB8BFCC)
}

@Composable
fun KindBadge(kind: ChannelKind, focused: Boolean) {
    val color = kindColor(kind)
    Box(
        Modifier
            .background(if (focused) Palette.OnFocus else color.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Txt(kind.label, Type.Badge, color = color)
    }
}

/** Panel informativo grande (bienvenida, vacío, error). */
@Composable
fun MessagePanel(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    danger: Boolean = false,
    actions: @Composable () -> Unit = {}
) {
    Column(
        modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .background(if (danger) Palette.DangerBg else Palette.Surface, RoundedCornerShape(20.dp))
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (icon != null) {
            Icon(icon, if (danger) Palette.Danger else Palette.Accent, size = 32.dp)
        }
        Txt(title, Type.Title, maxLines = 2)
        Txt(detail, Type.Body, color = Palette.TextDim, maxLines = 4)
        Box(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { actions() }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Txt(text.uppercase(), Type.Overline, modifier = modifier)
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Palette.Outline))
}

@Composable
fun HSpace(width: Dp) = Box(Modifier.width(width))

@Composable
fun VSpace(height: Dp) = Box(Modifier.height(height))
