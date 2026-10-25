package com.mivisor.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
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
    onForget: () -> Unit,
    onProbe: (() -> Unit)? = null
) {
    val first = remember { FocusRequester() }
    TvDialog(
        title = channel.name,
        subtitle = listOfNotNull(channel.group, channel.kind.label).joinToString(" · "),
        onDismiss = onDismiss,
        initialFocus = first
    ) {
        Txt(
            com.mivisor.app.data.AceStreamLink.contentId(channel.url)?.let { "acestream://" + com.mivisor.app.data.AceStreamLink.masked(it) } ?: channel.url,
            Type.Caption, color = Palette.TextFaint, maxLines = 2
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MenuRow("Abrir", R.drawable.ic_play, onOpen, Modifier.focusRequester(first))
            MenuRow("Abrir con…", R.drawable.ic_apps, onOpenWith)
            if (onProbe != null) MenuRow("Comprobar si se reproduce (Ace Stream)", R.drawable.ic_bolt, onProbe)
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
 * "Añadir / Editar" (título + código). Organización:
 *   cabecera · [Título, Código | QR "Editar con el móvil"] · pie fijo con Salir / Abrir / Guardar.
 * - El QR está visible desde el primer momento: la sesión (5 min, varios envíos) se crea al abrir el formulario.
 * - El pie no se desplaza nunca: campos, QR, Guardar y Salir están siempre disponibles.
 * - Pantalla ancha: dos columnas (formulario | QR). Estrecha o con mucho escalado: formulario, QR y botones apilados
 *   (el cuerpo se desplaza con el D-pad; el pie queda fijo).
 * Lo que llega del móvil solo rellena los campos; guardar es siempre una decisión en la TV.
 */
@Composable
fun SourceDialog(
    mode: SourceDialogMode,
    initialName: String = "",
    initialCode: String = "",
    onDismiss: () -> Unit,
    onResult: (name: String, code: String, action: SourceAction) -> Unit
) {
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val remote: RemoteInputViewModel = viewModel()
    val remoteState by remote.state.collectAsState()
    val edit by remote.edits.collectAsState()
    var name by remember { mutableStateOf(initialName) }
    var code by remember { mutableStateOf(initialCode) }
    var showError by remember { mutableStateOf(false) }
    var lastApplied by remember { mutableStateOf(0) }
    var fromPhone by remember { mutableStateOf(false) }
    val valid = Codes.normalizeOrNull(code) != null
    val titleFocus = remember { FocusRequester() }

    val title = when (mode) {
        SourceDialogMode.OPEN_CODE -> "Abrir código del proveedor"
        SourceDialogMode.ADD -> "Añadir proveedor"
        SourceDialogMode.EDIT -> "Editar proveedor"
    }

    fun startPhone() {
        lastApplied = 0
        remote.startEdit(title, name, code)
    }

    // QR siempre visible: la sesión se crea al abrir. Salir del formulario la invalida y apaga el servidor.
    LaunchedEffect(Unit) { startPhone() }
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

    fun submit(action: SourceAction) {
        if (!valid) {
            showError = true
            return
        }
        remote.cancel()
        onResult(name, code, action)
    }

    val codeError = if (showError && !valid) "Solo letras, números, puntos y guiones" else null
    val sends = if (remoteState is RemoteState.Waiting) lastApplied else 0

    // Medidas a partir de la pantalla real (resolución + escalado): nada fijo que pueda salirse.
    val screenW = config.screenWidthDp.toFloat()
    val screenH = config.screenHeightDp.toFloat()
    val dialogW = minOf(screenW * 0.94f, 860f)
    val wide = dialogW >= 620f
    val qrSize = (screenH * 0.30f).coerceIn(110f, 190f).dp

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        InitialFocus(titleFocus)
        Column(
            Modifier
                .padding(8.dp)
                .widthIn(max = dialogW.dp)
                .fillMaxWidth()
                .heightIn(max = (screenH * 0.94f).dp)
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(listOf(Palette.Accent.copy(alpha = 0.22f), Palette.Surface, Palette.Surface)),
                    RoundedCornerShape(20.dp)
                )
                .border(1.dp, Palette.Outline, RoundedCornerShape(20.dp))
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Cabecera
            Column {
                Txt(title, Type.Title, maxLines = 1)
                Txt("Escribe con el mando o edita desde el móvil escaneando el QR. Nada se guarda hasta que pulses Guardar.", Type.Caption, maxLines = 2)
            }
            // Cuerpo (se desplaza si no cabe; el pie no)
            Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                val fields: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvTextField(
                            name, { name = it; fromPhone = false },
                            if (mode == SourceDialogMode.EDIT) "Título" else "Título (opcional)",
                            placeholder = "P. ej. DAZN F1", focusRequester = titleFocus
                        )
                        TvTextField(
                            code, { code = it; showError = false; fromPhone = false }, "Código",
                            placeholder = "P. ej. deportes", error = codeError
                        )
                        if (fromPhone) {
                            Txt(
                                "✓ Actualizado desde el móvil${if (lastApplied > 1) " (envío $lastApplied)" else ""}. Revisa y pulsa ${if (mode == SourceDialogMode.EDIT) "Guardar" else "Guardar y abrir"}.",
                                Type.Caption, color = Palette.Cyan, maxLines = 2
                            )
                        }
                    }
                }
                val panel: @Composable (Modifier) -> Unit = { m ->
                    MobileEditPanel(
                        remoteState, sends, ::startPhone,
                        m.background(Palette.Bg.copy(alpha = 0.6f), RoundedCornerShape(16.dp)).border(1.dp, Palette.Outline, RoundedCornerShape(16.dp)).padding(10.dp),
                        qrSize = qrSize
                    )
                }
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Box(Modifier.weight(1f)) { fields() }
                        panel(Modifier.width(qrSize + 60.dp))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        fields()
                        panel(Modifier.fillMaxWidth())
                    }
                }
            }
            // Pie fijo: siempre visibles
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton("Salir", { remote.cancel(); onDismiss() }, style = ButtonStyle.GHOST)
                Box(Modifier.weight(1f))
                when (mode) {
                    SourceDialogMode.OPEN_CODE, SourceDialogMode.ADD -> {
                        TvButton("Abrir", { submit(SourceAction.OPEN_ONLY) }, icon = R.drawable.ic_play)
                        TvButton("Guardar y abrir", { submit(SourceAction.SAVE_AND_OPEN) }, icon = R.drawable.ic_add, style = ButtonStyle.PRIMARY)
                    }
                    SourceDialogMode.EDIT -> TvButton("Guardar", { submit(SourceAction.SAVE) }, style = ButtonStyle.PRIMARY)
                }
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
