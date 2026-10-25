package com.mivisor.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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

/**
 * "Añadir proveedor" / "Abrir código del proveedor" / "Editar proveedor".
 * Título + código a la izquierda y "Editar con móvil" a la derecha: el móvil abre una página con los valores
 * actuales del formulario y cada "Enviar al televisor" los actualiza aquí (varias veces durante 5 min).
 * Enviar desde el móvil NUNCA guarda: el formulario de la TV sigue siendo el punto final de revisión.
 */
@Composable
fun SourceDialog(
    mode: SourceDialogMode,
    initialName: String = "",
    initialCode: String = "",
    onDismiss: () -> Unit,
    onResult: (name: String, code: String, action: SourceAction) -> Unit
) {
    val layout = com.mivisor.app.ui.neon.LocalAppLayout.current
    val remote: RemoteInputViewModel = viewModel()
    val remoteState by remote.state.collectAsState()
    val edit by remote.edits.collectAsState()
    var name by remember { mutableStateOf(initialName) }
    var code by remember { mutableStateOf(initialCode) }
    var showError by remember { mutableStateOf(false) }
    var lastApplied by remember { mutableStateOf(0) }
    var fromPhone by remember { mutableStateOf(false) }
    val valid = Codes.normalizeOrNull(code) != null
    val codeFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }

    val title = when (mode) {
        SourceDialogMode.OPEN_CODE -> "Abrir código del proveedor"
        SourceDialogMode.ADD -> "Añadir proveedor"
        SourceDialogMode.EDIT -> "Editar proveedor"
    }

    // Salir del formulario (guardar, cancelar, Atrás) invalida la sesión y apaga el servidor.
    DisposableEffect(Unit) { onDispose { remote.cancel() } }
    // La página del móvil siempre se abre con lo que hay ahora en el formulario.
    LaunchedEffect(name, code) { remote.updateFields(name, code) }

    // Cada envío del móvil sustituye título y código. NO se guarda: el usuario decide con Guardar.
    LaunchedEffect(edit) {
        val e = edit ?: return@LaunchedEffect
        if (e.seq <= lastApplied) return@LaunchedEffect
        lastApplied = e.seq
        name = e.title.trim()
        code = RemoteFill.apply(code, e.code)
        showError = false
        fromPhone = true
    }

    fun startPhone() {
        lastApplied = 0
        remote.startEdit(title, name, code)
    }

    fun submit(action: SourceAction) {
        if (!valid) {
            showError = true
            return
        }
        remote.cancel()
        onResult(name, code, action)
    }

    val subtitle = when (mode) {
        SourceDialogMode.EDIT -> "Cambia el título o el código de este proveedor."
        else -> "Escribe el código con el mando o usa «Editar con móvil» para no teclear códigos largos."
    }
    val codeError = if (showError && !valid) "Solo letras, números, puntos y guiones" else null
    val sends = if (remoteState is RemoteState.Waiting) lastApplied else 0

    TvDialog(title = title, subtitle = subtitle, onDismiss = onDismiss, width = if (layout.compact) 560.dp else 760.dp, initialFocus = codeFocus) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TvTextField(
                    name, { name = it; fromPhone = false },
                    if (mode == SourceDialogMode.EDIT) "Título" else "Título (opcional)",
                    placeholder = "P. ej. DAZN F1"
                )
                TvTextField(
                    code, { code = it; showError = false; fromPhone = false }, "Código",
                    placeholder = "P. ej. deportes", error = codeError, focusRequester = codeFocus
                )
                if (fromPhone) {
                    Txt(
                        "✓ Actualizado desde el móvil${if (lastApplied > 1) " (envío $lastApplied)" else ""}. Revisa y pulsa ${if (mode == SourceDialogMode.EDIT) "Guardar" else "Guardar y abrir"}.",
                        Type.Caption, color = Palette.Cyan, maxLines = 2
                    )
                }
            }
            if (!layout.compact) {
                MobileEditPanel(
                    remoteState, sends, ::startPhone, { remote.cancel() },
                    Modifier.width(220.dp).background(Palette.Surface, RoundedCornerShape(16.dp)).border(1.dp, Palette.Outline, RoundedCornerShape(16.dp)).padding(12.dp),
                    startFocus = panelFocus
                )
            }
        }
        if (layout.compact) MobileEditPanel(remoteState, sends, ::startPhone, { remote.cancel() }, Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(R.drawable.ic_info, Palette.TextFaint, size = 16.dp)
            Box(Modifier.width(8.dp))
            Txt("Lo que envíes desde el móvil solo rellena estos campos. Nada se guarda hasta que pulses un botón aquí.", Type.Caption, maxLines = 2)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (mode) {
                SourceDialogMode.OPEN_CODE, SourceDialogMode.ADD -> {
                    TvButton("Abrir", { submit(SourceAction.OPEN_ONLY) }, icon = R.drawable.ic_play, style = ButtonStyle.PRIMARY)
                    TvButton("Guardar y abrir", { submit(SourceAction.SAVE_AND_OPEN) }, icon = R.drawable.ic_add)
                }
                SourceDialogMode.EDIT ->
                    TvButton("Guardar", { submit(SourceAction.SAVE) }, style = ButtonStyle.PRIMARY)
            }
            TvButton("Cancelar", { remote.cancel(); onDismiss() }, style = ButtonStyle.GHOST)
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
