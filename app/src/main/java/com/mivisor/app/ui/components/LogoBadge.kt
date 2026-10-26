package com.mivisor.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mivisor.app.R
import com.mivisor.app.data.logos.ResolvedLogo
import com.mivisor.app.ui.neon.Neon

/**
 * Logo de un canal o de un grupo, dentro de una caja de tamaño FIJO (nunca hay saltos de layout):
 *  - El logo se encaja entero (ContentScale.Fit): mantiene su proporción y no se deforma.
 *  - Va sobre una placa clara: la mayoría de logos de canal son oscuros o transparentes y sobre el fondo
 *    oscuro de MiVisor no se verían.
 *  - [urls] se prueban en orden (logo del canal → logo del grupo). Mientras carga o si todas fallan
 *    (sin red, 404, SVG…), se pinta el icono genérico de TV.
 */
@Composable
fun LogoBadge(urls: List<String>, width: Dp, height: Dp = width, modifier: Modifier = Modifier, fallbackText: String? = null) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { maxOf(width, height).roundToPx() }
    val candidates = urls.filter { it.isNotBlank() }.distinct()
    val image by produceState<ImageBitmap?>(candidates.firstNotNullOfOrNull { ImageLoader.peek(it, px) }, candidates, px) {
        if (value != null) return@produceState
        for (url in candidates) {
            val bitmap = ImageLoader.load(context, url, px)
            if (bitmap != null) {
                value = bitmap.asImageBitmap()
                return@produceState
            }
        }
    }
    val shape = RoundedCornerShape(minOf(width, height) / 5)
    val loaded = image
    Box(
        modifier.size(width, height).clip(shape)
            .background(if (loaded != null) LogoPlate else Neon.PanelHi, shape)
            .border(1.dp, if (loaded != null) Color.White.copy(alpha = 0.35f) else Neon.Stroke, shape),
        contentAlignment = Alignment.Center
    ) {
        if (loaded != null) {
            Image(
                loaded, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(horizontal = width * 0.1f, vertical = height * 0.12f)
            )
        } else if (!fallbackText.isNullOrBlank()) {
            // Grupo sin logo de marca: su nombre, mejor que el logo de uno de sus canales.
            Txt(
                fallbackText.uppercase(),
                androidx.compose.ui.text.TextStyle(
                    color = Neon.Text, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                    fontSize = (height.value * 0.34f).coerceAtMost(14f).sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                ),
                maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp)
            )
        } else {
            Icon(R.drawable.ic_tv, Neon.Dim, size = minOf(width, height) * 0.5f)
        }
    }
}

/** Atajo: canal (logo propio → logo del grupo → genérico). */
@Composable
fun ChannelLogoBadge(logo: ResolvedLogo, width: Dp, height: Dp = width, modifier: Modifier = Modifier) =
    LogoBadge(listOfNotNull(logo.channelLogo, logo.groupLogo), width, height, modifier)

/** Atajo: grupo (solo su logo de MARCA → su nombre en texto). Nunca el logo de uno de sus canales. */
@Composable
fun GroupLogoBadge(logo: ResolvedLogo, width: Dp, height: Dp = width, modifier: Modifier = Modifier) =
    LogoBadge(listOfNotNull(logo.groupLogo), width, height, modifier, fallbackText = logo.groupName)

/** Placa clara para los logos (ligeramente gris para no deslumbrar en la TV). */
private val LogoPlate = Color(0xFFF1F3F7)
