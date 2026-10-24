package com.mivisor.app.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.nativeKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.mivisor.app.ui.theme.LocalAnimations

/** Estado de pulsación de OK; se guarda fuera del estado de Compose (no provoca recomposición). */
private class PressState {
    var down = false
    var longFired = false
}

/**
 * Pieza base de toda la interfaz: un elemento enfocable con mando.
 *
 *  - OK / Enter: clic al SOLTAR, y solo si la pulsación empezó aquí. Evita el clásico bug
 *    de TV en el que el "soltar" de una pulsación anterior activa el elemento que acaba
 *    de recibir el foco (p. ej. al abrir un diálogo).
 *  - OK mantenido o tecla MENU/INFO: acción secundaria (opciones).
 *  - Táctil/ratón: toque y pulsación larga.
 *  - El escalado se aplica en la capa gráfica: no relanza layout ni recomposición.
 */
@Composable
fun TvClickable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    color: Color = Color.Transparent,
    focusedColor: Color = com.mivisor.app.ui.theme.Palette.Focus,
    borderColor: Color = Color.Transparent,
    focusedScale: Float = 1f,
    borderWidth: Dp = 1.dp,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.(focused: Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val press = remember { PressState() }
    val currentClick by rememberUpdatedState(onClick)
    val currentLongClick by rememberUpdatedState(onLongClick)

    val targetScale = if (focused && enabled) focusedScale else 1f
    val scaleState = if (LocalAnimations.current) {
        animateFloatAsState(targetScale, tween(durationMillis = 110), label = "focusScale")
    } else {
        rememberUpdatedState(targetScale)
    }

    Box(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                val s = scaleState.value
                scaleX = s
                scaleY = s
            }
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) press.down = false
            }
            .onKeyEvent { event ->
                if (!enabled) return@onKeyEvent false
                val native = event.nativeKeyEvent
                when (native.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
                    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER, AndroidKeyEvent.KEYCODE_BUTTON_A -> {
                        when (native.action) {
                            AndroidKeyEvent.ACTION_DOWN -> {
                                if (native.repeatCount == 0) {
                                    press.down = true
                                    press.longFired = false
                                } else if (press.down && !press.longFired && currentLongClick != null &&
                                    (native.isLongPress || native.repeatCount >= LONG_PRESS_REPEATS)
                                ) {
                                    press.longFired = true
                                    currentLongClick?.invoke()
                                }
                                true
                            }
                            AndroidKeyEvent.ACTION_UP -> {
                                val wasDown = press.down
                                press.down = false
                                if (wasDown && !press.longFired) currentClick()
                                true
                            }
                            else -> false
                        }
                    }
                    AndroidKeyEvent.KEYCODE_MENU, AndroidKeyEvent.KEYCODE_INFO -> {
                        val long = currentLongClick ?: return@onKeyEvent false
                        if (native.action == AndroidKeyEvent.ACTION_UP) long()
                        true
                    }
                    else -> false
                }
            }
            .focusable(enabled)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onTap = { currentClick() },
                    onLongPress = { currentLongClick?.invoke() }
                )
            }
            .then(if (enabled) Modifier else Modifier.alpha(0.4f))
            .background(if (focused) focusedColor else color, shape)
            .then(
                if (!focused && borderColor != Color.Transparent) Modifier.border(borderWidth, borderColor, shape)
                else Modifier
            ),
        contentAlignment = contentAlignment
    ) {
        content(focused)
    }
}

/** ~0,5 s a la cadencia de repetición típica de los mandos (≈ 50 ms). */
private const val LONG_PRESS_REPEATS = 10
