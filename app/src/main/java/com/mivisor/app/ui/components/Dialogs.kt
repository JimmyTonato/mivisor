package com.mivisor.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mivisor.app.R
import com.mivisor.app.RemoteInputViewModel
import com.mivisor.app.remote.RemoteFill
import com.mivisor.app.remote.RemoteState
import com.mivisor.app.data.Codes
import com.mivisor.app.model.Channel
import com.mivisor.app.player.RememberScope
import com.mivisor.app.player.StreamLauncher
import com.mivisor.app.ui.theme.Palette
import com.mivisor.app.ui.theme.Type

@Composable
fun TvDialog(
    title: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    width: androidx.compose.ui.unit.Dp = 560.dp,
    initialFocus: FocusRequester? = null,
    content: @Composable () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        if (initialFocus != null) InitialFocus(initialFocus)
        // En móvil ocupa casi todo el ancho; en TV/tablet, el ancho indicado.
        Column(
            Modifier
                .padding(12.dp)
                .widthIn(max = width)
                .fillMaxWidth()
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(Palette.Accent.copy(alpha = 0.22f), Palette.Surface, Palette.Surface)
                    ),
                    RoundedCornerShape(22.dp)
                )
                .border(1.dp, Palette.Outline, RoundedCornerShape(22.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt(title, Type.Title, maxLines = 2)
                if (subtitle != null) Txt(subtitle, Type.Caption, maxLines = 2)
            }
            content()
        }
    }
}

/** Pide el foco tras el primer frame (con reintentos: la ventana del diálogo tarda en adjuntarse). */
@Composable
fun InitialFocus(requester: FocusRequester) {
    LaunchedEffect(requester) {
        repeat(5) {
            withFrameNanos { }
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
}

@Composable
fun ChannelOptionsDialog(
    channel: Channel,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onOpenWith: () -> Unit,
    onShare: () -> Unit,
    onForget: () -> Unit
) {
    val first = remember { FocusRequester() }
    TvDialog(
        title = channel.name,
        subtitle = listOfNotNull(channel.group, channel.kind.label).joinToString(" · "),
        onDismiss = onDismiss,
        initialFocus = first
    ) {
        Txt(channel.url, Type.Caption, color = Palette.TextFaint, maxLines = 2)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MenuRow("Abrir", R.drawable.ic_play, onOpen, Modifier.focusRequester(first))
            MenuRow("Abrir con…", R.drawable.ic_apps, onOpenWith)
            MenuRow("Compartir enlace", R.drawable.ic_share, onShare)
            MenuRow("Olvidar la app recordada para este canal", R.drawable.ic_refresh, onForget)
        }
    }
}

@Composable
fun MenuRow(label: String, icon: Int, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false) {
    TvClickable(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Palette.SurfaceHi else androidx.compose.ui.graphics.Color.Transparent
    ) { focused ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(icon, if (focused) Palette.OnFocus else if (selected) Palette.Accent else Palette.TextDim)
            Txt(label, Type.Label, color = if (focused) Palette.OnFocus else Palette.Text, modifier = Modifier.weight(1f))
            if (selected) Txt("✓", Type.Label, color = if (focused) Palette.OnFocus else Palette.Accent)
        }
    }
}

@Composable
fun AppPickerDialog(
    channel: Channel,
    apps: List<StreamLauncher.AppChoice>,
    onDismiss: () -> Unit,
    onPick: (StreamLauncher.AppChoice, RememberScope) -> Unit
) {
    var scope by remember { mutableStateOf(RememberScope.ONCE) }
    val first = remember { FocusRequester() }
    val scopes = RememberScope.values().filter { it != RememberScope.GROUP || channel.group != null }
    TvDialog(title = "Abrir con", subtitle = channel.name, onDismiss = onDismiss, width = 640.dp, initialFocus = first) {
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 260.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
                MenuRow(
                    app.label, R.drawable.ic_play, { onPick(app, scope) },
                    if (index == 0) Modifier.focusRequester(first) else Modifier
                )
            }
        }
        Divider()
        SectionLabel("Recordar elección")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            scopes.forEach { option ->
                TvButton(
                    label = shortLabel(option),
                    onClick = { scope = option },
                    style = if (scope == option) ButtonStyle.PRIMARY else ButtonStyle.GHOST
                )
            }
        }
    }
}

private fun shortLabel(scope: RememberScope) = when (scope) {
    RememberScope.ONCE -> "No"
    RememberScope.CHANNEL -> "Canal"
    RememberScope.GROUP -> "Grupo"
    RememberScope.PROTOCOL -> "Tipo de enlace"
}

enum class SourceDialogMode { OPEN_CODE, ADD, EDIT }
enum class SourceAction { OPEN_ONLY, SAVE_AND_OPEN, SAVE }

@Composable
fun SourceDialog(
    mode: SourceDialogMode,
    initialName: String = "",
    initialCode: String = "",
    onDismiss: () -> Unit,
    onResult: (name: String, code: String, action: SourceAction) -> Unit
) {
    val remote: RemoteInputViewModel = viewModel()
    val remoteState by remote.state.collectAsState()
    var name by remember { mutableStateOf(initialName) }
    var code by remember { mutableStateOf(initialCode) }
    var showError by remember { mutableStateOf(false) }
    var receivedNotice by remember { mutableStateOf(false) }
    var focusCodeTick by remember { mutableIntStateOf(0) }
    val valid = Codes.normalizeOrNull(code) != null
    val codeFocus = remember { FocusRequester() }
    val nameFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }
    val showingQr = remoteState !is RemoteState.Idle && remoteState !is RemoteState.Received

    // Salir del formulario (guardar, cancelar, Atrás, cambiar de pantalla) invalida la sesión y apaga el servidor.
    DisposableEffect(Unit) { onDispose { remote.cancel() } }

    // Texto recibido: sustituye el contenido del campo. NO se guarda: el usuario decide.
    LaunchedEffect(remoteState) {
        val s = remoteState
        if (s is RemoteState.Received) {
            code = RemoteFill.apply(code, s.text)
            showError = false
            receivedNotice = true
            remote.consume()
            focusCodeTick++
        }
    }
    LaunchedEffect(focusCodeTick) {
        if (focusCodeTick > 0) {
            repeat(5) {
                withFrameNanos { }
                if (runCatching { codeFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        }
    }

    fun submit(action: SourceAction) {
        if (!valid) {
            showError = true
            return
        }
        remote.cancel()
        onResult(name, code, action)
    }

    fun closeQr() {
        remote.cancel()
        focusCodeTick++
    }

    val title = when (mode) {
        SourceDialogMode.OPEN_CODE -> "Abrir un código"
        SourceDialogMode.ADD -> "Nueva categoría"
        SourceDialogMode.EDIT -> "Editar categoría"
    }
    val subtitle = when {
        showingQr -> "Escribir desde el móvil"
        mode == SourceDialogMode.OPEN_CODE -> "Escribe el código. Puedes guardarlo como categoría para tenerlo en el menú."
        else -> "Las categorías aparecen en el menú lateral en el orden que elijas."
    }
    val codeError = if (showError && !valid) "Solo letras, números, puntos y guiones" else null

    TvDialog(
        title = title,
        subtitle = subtitle,
        // Con el QR en pantalla, Atrás solo cierra el QR (y anula la sesión); sin él, cierra el formulario.
        onDismiss = { if (showingQr) closeQr() else onDismiss() },
        width = if (showingQr) 640.dp else 560.dp,
        initialFocus = if (mode == SourceDialogMode.OPEN_CODE) codeFocus else nameFocus
    ) {
        if (showingQr) {
            RemotePanel(remoteState, onCancel = { closeQr() }, onRetry = { remote.startInput() }, firstFocus = panelFocus)
        } else {
            val codeField: @Composable () -> Unit = {
                TvTextField(
                    code, { code = it; showError = false; receivedNotice = false }, "Código",
                    placeholder = "p. ej. deportes", error = codeError, focusRequester = codeFocus
                )
                if (receivedNotice) Txt("✓ Texto recibido desde el móvil", Type.Caption, color = Palette.Cyan)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvButton("Escribir con mando", { focusCodeTick++ }, icon = R.drawable.ic_edit, style = ButtonStyle.GHOST)
                    TvButton("Usar móvil / QR", { receivedNotice = false; remote.startInput() }, icon = R.drawable.ic_share, style = ButtonStyle.GHOST)
                }
            }
            if (mode == SourceDialogMode.OPEN_CODE) {
                codeField()
                TvTextField(name, { name = it }, "Nombre (opcional, para guardarlo)", placeholder = "p. ej. Deportes", focusRequester = nameFocus)
            } else {
                TvTextField(name, { name = it }, "Nombre", placeholder = "p. ej. Deportes", focusRequester = nameFocus)
                codeField()
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                when (mode) {
                    SourceDialogMode.OPEN_CODE -> {
                        TvButton("Abrir", { submit(SourceAction.OPEN_ONLY) }, icon = R.drawable.ic_play, style = ButtonStyle.PRIMARY)
                        TvButton("Guardar y abrir", { submit(SourceAction.SAVE_AND_OPEN) }, icon = R.drawable.ic_add)
                    }
                    SourceDialogMode.ADD ->
                        TvButton("Guardar y abrir", { submit(SourceAction.SAVE_AND_OPEN) }, icon = R.drawable.ic_add, style = ButtonStyle.PRIMARY)
                    SourceDialogMode.EDIT ->
                        TvButton("Guardar", { submit(SourceAction.SAVE) }, style = ButtonStyle.PRIMARY)
                }
                TvButton("Cancelar", { remote.cancel(); onDismiss() }, style = ButtonStyle.GHOST)
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val cancel = remember { FocusRequester() }
    TvDialog(title = title, subtitle = message, onDismiss = onDismiss, width = 480.dp, initialFocus = cancel) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // El foco empieza en "Cancelar": borrar nunca debe ser la opción por defecto.
            TvButton("Cancelar", onDismiss, Modifier.focusRequester(cancel), style = ButtonStyle.GHOST)
            TvButton(confirmLabel, onConfirm, icon = R.drawable.ic_delete, style = ButtonStyle.DANGER)
        }
    }
}
