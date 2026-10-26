package com.mivisor.app.ui.screens

import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mivisor.app.BuildConfig
import com.mivisor.app.QuickServicesUi
import com.mivisor.app.R
import com.mivisor.app.player.PlayerPreferences
import com.mivisor.app.services.AcePhase
import com.mivisor.app.services.QuickServiceText
import com.mivisor.app.services.RowAction
import com.mivisor.app.services.WarpPhase
import com.mivisor.app.services.WarpStatus
import com.mivisor.app.ui.components.Icon
import com.mivisor.app.ui.components.ToggleRow
import com.mivisor.app.ui.components.TvClickable
import com.mivisor.app.ui.components.TvTextField
import com.mivisor.app.ui.components.Txt
import com.mivisor.app.ui.kit.AutoFocus
import com.mivisor.app.ui.kit.PageHeader
import com.mivisor.app.ui.kit.SecondaryButton
import com.mivisor.app.ui.kit.Ui
import com.mivisor.app.ui.neon.LocalAppLayout
import com.mivisor.app.ui.neon.Neon
import com.mivisor.app.ui.neon.NeonType
import com.mivisor.app.ui.shell.ServiceCard

enum class SettingsSection(val label: String, @DrawableRes val icon: Int) {
    GENERAL("General", R.drawable.ic_settings),
    PLAYER("Reproductor", R.drawable.ic_play),
    INTERFACE("Interfaz", R.drawable.ic_tv),
    SERVICES("Servicios", R.drawable.ic_shield),
    CHANNELS("Canales", R.drawable.ic_list),
    DATA("Datos", R.drawable.ic_calendar),
    SYSTEM("Sistema", R.drawable.ic_info)
}

/**
 * Ajustes por secciones. Ace Stream y VPN/WARP tienen aquí su configuración avanzada (sus estados siguen en la
 * barra lateral). Las APIs de la agenda (Football-Data, Live Tennis…) están en Servicios → Fuentes de datos.
 */
@Composable
fun SettingsScreen(
    prefs: PlayerPreferences,
    section: SettingsSection,
    onSection: (SettingsSection) -> Unit,
    animations: Boolean,
    onAnimationsChange: (Boolean) -> Unit,
    services: QuickServicesUi,
    onServiceOk: (RowAction) -> Unit,
    onPreviewAce: (AcePhase?) -> Unit,
    onPreviewWarp: (WarpPhase?) -> Unit,
    onClearCache: () -> Unit,
    onClearHistory: () -> Unit,
    onManageProviders: () -> Unit,
    onOpenApiServices: () -> Unit,
    onOpenErrorLog: () -> Unit,
    autoFocus: Boolean,
    onImportRuleChanged: () -> Unit = {},
    onOpenEngineDiagnostics: () -> Unit = {}
) {
    val layout = LocalAppLayout.current
    val first = remember { FocusRequester() }
    AutoFocus(first, enabled = autoFocus)
    Column(Modifier.fillMaxSize().padding(start = if (layout.compact) layout.gutter else 6.dp, end = layout.gutter)) {
        PageHeader("Ajustes", "Mi Visor ${BuildConfig.VERSION_NAME}")
        if (layout.compact) {
            LazyRow(contentPadding = PaddingValues(4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SettingsSection.values().toList(), key = { it.name }) { s ->
                    SecondaryButton(s.label, { onSection(s) }, if (s == section) Modifier.focusRequester(first) else Modifier, height = 32.dp, selected = s == section)
                }
            }
            Content(section, prefs, animations, onAnimationsChange, services, onServiceOk, onPreviewAce, onPreviewWarp,
                onClearCache, onClearHistory, onManageProviders, onOpenApiServices, onOpenErrorLog, onImportRuleChanged, onOpenEngineDiagnostics, Modifier.weight(1f))
        } else {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.width(176.dp).fillMaxHeight().padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingsSection.values().forEach { s ->
                        MenuItem(s, s == section, { onSection(s) }, if (s == section) Modifier.focusRequester(first) else Modifier)
                    }
                }
                Content(section, prefs, animations, onAnimationsChange, services, onServiceOk, onPreviewAce, onPreviewWarp,
                    onClearCache, onClearHistory, onManageProviders, onOpenApiServices, onOpenErrorLog, onImportRuleChanged, onOpenEngineDiagnostics, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun MenuItem(s: SettingsSection, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    TvClickable(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(40.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Neon.PanelHi else Color.Transparent,
        focusedColor = Neon.PanelHi,
        borderColor = if (selected) Neon.Cyan.copy(alpha = 0.75f) else Neon.Stroke,
        borderWidth = if (selected) 2.dp else 1.dp,
        contentAlignment = Alignment.CenterStart
    ) { focused ->
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(s.icon, if (selected || focused) Neon.Cyan else Neon.Dim, size = 17.dp)
            Box(Modifier.width(10.dp))
            Txt(s.label, NeonType.H3, color = if (selected || focused) Neon.Text else Neon.Dim)
        }
    }
}

@Composable
private fun Content(
    section: SettingsSection,
    prefs: PlayerPreferences,
    animations: Boolean,
    onAnimationsChange: (Boolean) -> Unit,
    services: QuickServicesUi,
    onServiceOk: (RowAction) -> Unit,
    onPreviewAce: (AcePhase?) -> Unit,
    onPreviewWarp: (WarpPhase?) -> Unit,
    onClearCache: () -> Unit,
    onClearHistory: () -> Unit,
    onManageProviders: () -> Unit,
    onOpenApiServices: () -> Unit,
    onOpenErrorLog: () -> Unit,
    onImportRuleChanged: () -> Unit,
    onOpenEngineDiagnostics: () -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    Column(
        modifier.widthIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        when (section) {
            SettingsSection.GENERAL -> {
                Heading("General")
                InfoBox(
                    "Versión" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    "Enlaces" to "mylinkspaste://código · mivisor://código",
                    "Inicio" to "Hero, tus competiciones, en directo y lo que empieza pronto"
                )
                Hint("Ace Stream y la VPN se controlan desde «Servicios de reproducción», abajo en la barra lateral.")
            }
            SettingsSection.PLAYER -> {
                var selector by remember { mutableStateOf(prefs.selectorEnabled) }
                var aceEnabled by remember { mutableStateOf(prefs.customAceEnabled) }
                var aceTemplate by remember { mutableStateOf(prefs.customAceTemplate.ifBlank { BuildConfig.ACESTREAM_TEMPLATE }) }
                var magnetEnabled by remember { mutableStateOf(prefs.customMagnetEnabled) }
                var magnetTemplate by remember { mutableStateOf(prefs.customMagnetTemplate.ifBlank { BuildConfig.MAGNET_TEMPLATE }) }
                var engineMode by remember { mutableStateOf(prefs.engineMode) }
                val unsupported = remember { com.mivisor.app.engine.EngineManager.unsupportedReason(context) }
                Heading("Motor de reproducción (Ace Stream)")
                ChipRow(com.mivisor.app.player.EngineMode.values().map { m ->
                    (if (m == engineMode) "● " else "") + m.label to { engineMode = m; prefs.engineMode = m }
                })
                Hint(engineMode.help)
                InfoBox(
                    "Motor" to (unsupported ?: "Motor P2P de MiVisor incluido (${android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "?"})"),
                    "Externo" to (com.mivisor.app.engine.ExternalEngineLauncher.handlers(context).takeIf { it.isNotEmpty() }?.joinToString() ?: "Ninguna app de Ace Stream instalada")
                )
                SecondaryButton("Diagnóstico del motor", onOpenEngineDiagnostics, icon = R.drawable.ic_info)
                Heading("Reproductor")
                ToggleRow("Preguntar con qué app abrir", selector, { selector = !selector; prefs.selectorEnabled = selector },
                    description = "Si lo desactivas, Android decide la app (o usa la que tenga por defecto).")
                SecondaryButton("Olvidar apps recordadas", {
                    prefs.clearPreferredApps()
                    Toast.makeText(context, "Apps recordadas borradas", Toast.LENGTH_SHORT).show()
                }, icon = R.drawable.ic_refresh)
                ToggleRow("Abrir Ace Stream con el motor local (HTTP)", aceEnabled, { aceEnabled = !aceEnabled; prefs.customAceEnabled = aceEnabled },
                    description = "Convierte acestream:// en una URL para reproductores sin soporte Ace Stream.")
                if (aceEnabled) TvTextField(aceTemplate, { aceTemplate = it; prefs.customAceTemplate = it }, "Plantilla ({id} = identificador)",
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri)
                ToggleRow("Enviar magnet a un servidor remoto", magnetEnabled, { magnetEnabled = !magnetEnabled; prefs.customMagnetEnabled = magnetEnabled },
                    description = "Útil con clientes torrent en otro equipo.")
                if (magnetEnabled) TvTextField(magnetTemplate, { magnetTemplate = it; prefs.customMagnetTemplate = it }, "Plantilla ({id} = enlace magnet)",
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri)
            }
            SettingsSection.INTERFACE -> {
                Heading("Interfaz")
                ToggleRow("Animaciones de foco", animations, { onAnimationsChange(!animations) },
                    description = "Desactívalas si la TV va justa: la navegación será más fluida.")
            }
            SettingsSection.SERVICES -> {
                var aceAuto by remember { mutableStateOf(prefs.aceAutoStart) }
                var vpnAuto by remember { mutableStateOf(prefs.vpnAutoConnect) }
                Heading("Ace Stream")
                ServiceCard(services.ace, R.drawable.ic_bolt, onServiceOk, extra = null, focus = null)
                services.aceStatus.version?.let { Hint("Versión del motor: $it") }
                ToggleRow("Iniciar automáticamente al abrir MiVisor", aceAuto, { aceAuto = !aceAuto; prefs.aceAutoStart = aceAuto })
                Heading("VPN / WARP")
                ServiceCard(
                    services.warp, R.drawable.ic_shield, onServiceOk,
                    extra = if (services.warpStatus.phase == WarpPhase.CONNECTED) "Desconectar" to RowAction.WARP_DISCONNECT else null, focus = null
                )
                ToggleRow("Conectar automáticamente al abrir MiVisor", vpnAuto, { vpnAuto = !vpnAuto; prefs.vpnAutoConnect = vpnAuto },
                    description = "Además, cada proveedor puede marcarse para usar la VPN (Canales → Gestionar).")
                Heading("Vista previa de estados")
                Hint("Solo cambia lo que se ve, para revisar el diseño de cada estado. No toca Ace Stream ni la VPN.")
                ChipRow(AcePhase.values().filter { it != AcePhase.CHECKING }.map { p -> QuickServiceText.ace(com.mivisor.app.services.AceStatus(p)).status to { onPreviewAce(p) } })
                ChipRow(WarpPhase.values().filter { it != WarpPhase.CHECKING }.map { p -> QuickServiceText.warp(WarpStatus(p, if (p == WarpPhase.ERROR) "CONNECT_TIMEOUT" else null)).status to { onPreviewWarp(p) } })
                if (services.preview) SecondaryButton("Volver al estado real", { onPreviewAce(null); onPreviewWarp(null) }, icon = R.drawable.ic_refresh, selected = true)
                Heading("Fuentes de datos de la agenda")
                Hint("Football-Data, Live Tennis, Jolpica, Blacktop, Cito, TheSportsDB… (diagnóstico y mantenimiento).")
                SecondaryButton("Ver estado de las fuentes", onOpenApiServices, icon = R.drawable.ic_list)
            }
            SettingsSection.CHANNELS -> {
                var aceOnly by remember { mutableStateOf(prefs.aceOnlyImport) }
                Heading("Canales")
                ToggleRow(
                    "Importar solo canales Ace Stream", aceOnly,
                    { aceOnly = !aceOnly; prefs.aceOnlyImport = aceOnly; onImportRuleChanged() },
                    description = "Separadores, títulos, webs y otros enlaces no se consideran canales. Desactívalo si tus proveedores traen también HLS u otros enlaces."
                )
                SecondaryButton("Gestionar proveedores", onManageProviders, icon = R.drawable.ic_edit)
                Hint("Ordena, renombra o elimina proveedores y marca cuáles necesitan VPN.")
                SecondaryButton("Borrar caché de canales", onClearCache, icon = R.drawable.ic_delete)
            }
            SettingsSection.DATA -> {
                var agendaUrl by remember { mutableStateOf(prefs.agendaUrl) }
                Heading("Datos")
                TvTextField(
                    agendaUrl, { agendaUrl = it; prefs.agendaUrl = it },
                    "URL de agenda.json (vacío = la de tu repositorio)",
                    placeholder = BuildConfig.AGENDA_URL.ifBlank { "https://raw.githubusercontent.com/usuario/repo/agenda-data/agenda.json" },
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri
                )
                SecondaryButton("Borrar historial de reproducciones", onClearHistory, icon = R.drawable.ic_history)
            }
            SettingsSection.SYSTEM -> {
                Heading("Sistema")
                SecondaryButton("Registro de errores", onOpenErrorLog, icon = R.drawable.ic_history)
                Hint("Si la app se cierra o falla, aquí verás el detalle y podrás pasarlo al móvil con un QR.")
                InfoBox(
                    "Dominio" to BuildConfig.STREAM_DOMAIN,
                    "DNS" to "8.8.8.8 · 1.1.1.1 (UDP/TCP) → Google / Cloudflare DoH",
                    "Versión" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                )
                Heading("Licencias")
                InfoBox(
                    "Motor P2P" to "outpace · AGPL-3.0-or-later (sin modificar, proceso aparte)",
                    "Código" to "github.com/jamezrin/outpace · commit 7d77f41",
                    "Texto" to "Incluido en la app: assets/licenses (NOTICE y licencia completa)"
                )
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Txt(text, Ui.Section, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Hint(text: String) {
    Txt(text, NeonType.Small, maxLines = 3)
}

@Composable
private fun ChipRow(items: List<Pair<String, () -> Unit>>) {
    LazyRow(contentPadding = PaddingValues(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = { it.first }) { (label, action) -> SecondaryButton(label, action, height = 30.dp) }
    }
}

@Composable
private fun InfoBox(vararg lines: Pair<String, String>) {
    Column(
        Modifier.fillMaxWidth().background(Neon.Panel, RoundedCornerShape(14.dp)).border(1.dp, Neon.Stroke, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        lines.forEach { (label, value) ->
            Row {
                Txt(label, NeonType.Small, modifier = Modifier.width(90.dp))
                Txt(value, NeonType.Body.copy(fontSize = 13.sp), color = Neon.Dim, maxLines = 2)
            }
        }
    }
}
