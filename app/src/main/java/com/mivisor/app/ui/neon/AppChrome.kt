package com.mivisor.app.ui.neon

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mivisor.app.R
import com.mivisor.app.ui.components.Icon
import com.mivisor.app.ui.components.TvClickable
import com.mivisor.app.ui.components.Txt
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------------------------
// Diseño adaptable: la misma app en TV, tablet (horizontal/vertical) y móvil.
// ---------------------------------------------------------------------------------------------

enum class WindowClass { COMPACT, MEDIUM, EXPANDED }

class AppLayout(
    val windowClass: WindowClass,
    /** Pantalla baja (móvil en horizontal): cabeceras más pequeñas. */
    val short: Boolean,
    val isTv: Boolean
) {
    val compact get() = windowClass == WindowClass.COMPACT
    val expanded get() = windowClass == WindowClass.EXPANDED

    /** Margen lateral. En TV respeta la zona segura de overscan. */
    val gutter: Dp get() = when (windowClass) {
        WindowClass.COMPACT -> 16.dp
        WindowClass.MEDIUM -> 24.dp
        WindowClass.EXPANDED -> 32.dp
    }
    val top: Dp get() = if (isTv) 16.dp else 10.dp

    /** Columna izquierda de filtros/categorías (no existe en móvil: pasan a fila de chips). */
    val sideWidth: Dp get() = if (windowClass == WindowClass.EXPANDED) 190.dp else 168.dp

    val heroStyle: TextStyle get() = when {
        compact || short -> HeroBase.copy(fontSize = 26.sp, lineHeight = 30.sp)
        windowClass == WindowClass.MEDIUM -> HeroBase.copy(fontSize = 34.sp, lineHeight = 40.sp)
        else -> HeroBase
    }

    private companion object {
        val HeroBase = TextStyle(color = Neon.Text, fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.ExtraBold)
    }
}

val LocalAppLayout = staticCompositionLocalOf { AppLayout(WindowClass.EXPANDED, short = false, isTv = true) }

@Composable
fun rememberAppLayout(): AppLayout {
    val config = LocalConfiguration.current
    val context = LocalContext.current
    val isTv = remember(context) { isTelevision(context) }
    val width = config.screenWidthDp
    val height = config.screenHeightDp
    return remember(width, height, isTv) {
        val windowClass = when {
            isTv -> WindowClass.EXPANDED
            width < 600 -> WindowClass.COMPACT
            width < 900 -> WindowClass.MEDIUM
            else -> WindowClass.EXPANDED
        }
        AppLayout(windowClass, short = !isTv && height < 480, isTv = isTv)
    }
}

private fun isTelevision(context: Context): Boolean {
    val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        context.packageManager.hasSystemFeature("android.software.leanback")
}

// ---------------------------------------------------------------------------------------------
// Barra superior con pestañas (TV/tablet) y barra inferior (móvil).
// ---------------------------------------------------------------------------------------------

class NavTab(val label: String, @DrawableRes val icon: Int)

@Composable
fun AppTopBar(
    tabs: List<NavTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    selectedFocus: FocusRequester,
    modifier: Modifier = Modifier
) {
    val layout = LocalAppLayout.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(20_000)
        }
    }
    Row(modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
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
        if (!layout.compact) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEachIndexed { index, tab ->
                    NeonButton(
                        label = tab.label,
                        onClick = { onSelect(index) },
                        icon = tab.icon,
                        selected = index == selected,
                        modifier = if (index == selected) Modifier.focusRequester(selectedFocus) else Modifier
                    )
                }
            }
            Box(Modifier.width(18.dp))
        }
        Txt(timeOf(now), NeonType.H2.copy(fontWeight = FontWeight.Medium))
    }
}

/** Navegación inferior para móvil (las pestañas no caben arriba). */
@Composable
fun BottomNav(tabs: List<NavTab>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Neon.Bg.copy(alpha = 0.95f), Neon.Bg)))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tabs.forEachIndexed { index, tab ->
            val active = index == selected
            TvClickable(
                onClick = { onSelect(index) },
                modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(14.dp),
                color = if (active) Neon.PanelHi else Neon.Panel,
                focusedColor = Neon.PanelHi,
                borderColor = if (active) Neon.Cyan else Neon.Stroke,
                contentAlignment = Alignment.Center
            ) { focused ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Icon(tab.icon, if (active || focused) Neon.Cyan else Neon.Dim, size = 20.dp)
                    Txt(tab.label, NeonType.Pill.copy(fontSize = 12.sp), color = if (active || focused) Neon.Text else Neon.Dim)
                }
            }
        }
    }
}

/** Cabecera grande de cada pantalla: título, subtítulo y acciones a la derecha. */
@Composable
fun ScreenHero(
    title: String,
    subtitle: String?,
    status: String? = null,
    statusColor: Color = Neon.Faint,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {}
) {
    val layout = LocalAppLayout.current
    Row(modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Txt(title, layout.heroStyle)
            if (subtitle != null && !layout.short) Txt(subtitle, NeonType.HeroSub, maxLines = 2)
            if (!status.isNullOrBlank()) Txt(status, NeonType.Small, color = statusColor)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

/** Elemento de columna lateral (categorías, filtros) con el estilo de la Agenda. */
@Composable
fun SideItem(
    label: String,
    @DrawableRes icon: Int?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    height: Dp = 40.dp
) {
    TvClickable(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().height(height),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Neon.PanelHi else Neon.Panel,
        focusedColor = Neon.PanelHi,
        borderColor = if (selected) Neon.Cyan else Neon.Stroke,
        focusedScale = 1.015f,
        contentAlignment = Alignment.CenterStart
    ) { focused ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, if (selected || focused) Neon.Cyan else Neon.Dim, size = 19.dp)
                Box(Modifier.width(10.dp))
            }
            Txt(label, NeonType.H3, color = if (selected || focused) Neon.Text else Neon.Dim, modifier = Modifier.weight(1f))
            if (selected) Icon(R.drawable.ic_chevron_right, Neon.Cyan, size = 18.dp)
        }
    }
}

/** Chip de filtro para la fila horizontal (sustituye a la columna lateral en móvil). */
@Composable
fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit, @DrawableRes icon: Int? = null, modifier: Modifier = Modifier) {
    NeonButton(label, onClick, modifier, icon = icon, selected = selected, height = 34.dp)
}

/** Separador fino para columnas. */
@Composable
fun NeonDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Neon.Stroke))
}

/** Etiqueta pequeña de sección en mayúsculas. */
@Composable
fun Overline(text: String, modifier: Modifier = Modifier) {
    Txt(text.uppercase(), NeonType.Small.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = Neon.Faint), modifier = modifier)
}

/** Marco redondeado con borde: para cajas informativas. */
fun Modifier.neonBox(radius: Dp = 14.dp): Modifier =
    background(Neon.Panel, RoundedCornerShape(radius)).border(1.dp, Neon.Stroke, RoundedCornerShape(radius))
