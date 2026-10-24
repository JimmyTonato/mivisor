package com.mivisor.app.ui.components

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mivisor.app.R
import com.mivisor.app.ui.theme.Palette
import com.mivisor.app.ui.theme.Type
import androidx.compose.runtime.withFrameNanos

/**
 * Campo de texto pensado para mando.
 *
 * Un BasicTextField normal abre el teclado en cuanto recibe el foco: al bajar con el
 * D-pad por un formulario el teclado salta y "atrapa" la navegación. Aquí el campo es
 * un botón; con OK se entra en edición y con "Hecho" (o moviendo el foco) se sale.
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    focusRequester: FocusRequester = remember { FocusRequester() }
) {
    var editing by remember { mutableStateOf(false) }
    var returnFocus by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(12.dp)

    fun finish() {
        keyboard?.hide()
        returnFocus = true
        editing = false
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Txt(label, Type.Caption)
        if (!editing) {
            TvClickable(
                onClick = { editing = true },
                modifier = Modifier.fillMaxWidth().height(52.dp).focusRequester(focusRequester),
                enabled = enabled,
                shape = shape,
                color = Palette.Bg,
                borderColor = if (error != null) Palette.Danger else Palette.Outline,
                contentAlignment = Alignment.CenterStart
            ) { focused ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Txt(
                        value.ifEmpty { placeholder },
                        Type.Body,
                        modifier = Modifier.weight(1f),
                        color = when {
                            focused -> if (value.isEmpty()) Palette.OnFocusDim else Palette.OnFocus
                            value.isEmpty() -> Palette.TextFaint
                            else -> Palette.Text
                        }
                    )
                    Icon(R.drawable.ic_edit, if (focused) Palette.OnFocus else Palette.TextFaint, size = 18.dp)
                }
            }
        } else {
            var hadFocus by remember { mutableStateOf(false) }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = Type.Body,
                cursorBrush = SolidColor(Palette.Accent),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = keyboardType,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { finish() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .focusRequester(fieldFocus)
                    .onFocusChanged { state ->
                        if (state.isFocused) hadFocus = true
                        else if (hadFocus) editing = false
                    }
                    .background(Palette.Bg, shape)
                    .border(2.dp, Palette.Accent, shape)
                    .padding(horizontal = 16.dp, vertical = 15.dp)
            )
            LaunchedEffect(Unit) {
                withFrameNanos { }
                runCatching { fieldFocus.requestFocus() }
                keyboard?.show()
            }
        }
        if (error != null) Txt(error, Type.Caption, color = Palette.Danger)
    }

    LaunchedEffect(editing) {
        if (!editing && returnFocus) {
            returnFocus = false
            withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
        }
    }
}

/** Fila con interruptor (ajustes). */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null
) {
    TvClickable(
        onClick = onToggle,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Palette.SurfaceHi
    ) { focused ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Txt(title, Type.Label, color = if (focused) Palette.OnFocus else Palette.Text)
                if (description != null) {
                    Txt(description, Type.Caption, color = if (focused) Palette.OnFocusDim else Palette.TextDim, maxLines = 2)
                }
            }
            Box(Modifier.width(16.dp))
            Switch(checked, focused)
        }
    }
}

@Composable
private fun Switch(checked: Boolean, focused: Boolean) {
    val track = when {
        checked -> Palette.Accent
        focused -> Palette.OnFocusDim
        else -> Palette.Outline
    }
    Box(
        Modifier.width(44.dp).height(24.dp).background(track, RoundedCornerShape(12.dp)).padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            Modifier.size(18.dp).background(
                if (checked) Palette.OnAccent else Palette.Text, RoundedCornerShape(9.dp)
            )
        )
    }
}
