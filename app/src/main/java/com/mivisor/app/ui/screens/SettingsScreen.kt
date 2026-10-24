package com.mivisor.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mivisor.app.BuildConfig
import com.mivisor.app.R
import com.mivisor.app.player.PlayerPreferences
import com.mivisor.app.ui.components.ButtonStyle
import com.mivisor.app.ui.components.SectionLabel
import com.mivisor.app.ui.components.ToggleRow
import com.mivisor.app.ui.components.TvButton
import com.mivisor.app.ui.components.TvTextField
import com.mivisor.app.ui.components.Txt
import com.mivisor.app.ui.theme.Palette
import com.mivisor.app.ui.theme.Type

@Composable
fun SettingsScreen(
    prefs: PlayerPreferences,
    animations: Boolean,
    onAnimationsChange: (Boolean) -> Unit,
    onClearCache: () -> Unit,
    onOpenServices: () -> Unit = {}
) {
    val context = LocalContext.current
    var selector by remember { mutableStateOf(prefs.selectorEnabled) }
    var aceEnabled by remember { mutableStateOf(prefs.customAceEnabled) }
    var aceTemplate by remember { mutableStateOf(prefs.customAceTemplate.ifBlank { BuildConfig.ACESTREAM_TEMPLATE }) }
    var magnetEnabled by remember { mutableStateOf(prefs.customMagnetEnabled) }
    var magnetTemplate by remember { mutableStateOf(prefs.customMagnetTemplate.ifBlank { BuildConfig.MAGNET_TEMPLATE }) }
    var agendaUrl by remember { mutableStateOf(prefs.agendaUrl) }

    ScreenScaffold(title = "Ajustes", subtitle = "Mi Visor ${BuildConfig.VERSION_NAME}") {
        Column(
            Modifier.widthIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(bottom = 40.dp, start = 4.dp, end = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionLabel("Reproducción", Modifier.padding(top = 4.dp))
            ToggleRow(
                "Preguntar con qué app abrir",
                selector,
                { selector = !selector; prefs.selectorEnabled = selector },
                description = "Si lo desactivas, Android decide la app (o usa la que tenga por defecto)."
            )
            TvButton("Olvidar apps recordadas", {
                prefs.clearPreferredApps()
                Toast.makeText(context, "Apps recordadas borradas", Toast.LENGTH_SHORT).show()
            }, icon = R.drawable.ic_refresh, style = ButtonStyle.GHOST)

            SectionLabel("AceStream", Modifier.padding(top = 12.dp))
            ToggleRow(
                "Abrir con el motor local (HTTP)",
                aceEnabled,
                { aceEnabled = !aceEnabled; prefs.customAceEnabled = aceEnabled },
                description = "Convierte acestream:// en una URL para reproductores sin soporte AceStream."
            )
            if (aceEnabled) {
                TvTextField(
                    aceTemplate,
                    { aceTemplate = it; prefs.customAceTemplate = it },
                    "Plantilla ({id} se sustituye por el identificador)",
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri
                )
            }

            SectionLabel("Magnet", Modifier.padding(top = 12.dp))
            ToggleRow(
                "Enviar a un servidor remoto",
                magnetEnabled,
                { magnetEnabled = !magnetEnabled; prefs.customMagnetEnabled = magnetEnabled },
                description = "Útil con clientes torrent en otro equipo (p. ej. una interfaz web)."
            )
            if (magnetEnabled) {
                TvTextField(
                    magnetTemplate,
                    { magnetTemplate = it; prefs.customMagnetTemplate = it },
                    "Plantilla ({id} se sustituye por el enlace magnet)",
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri
                )
            }

            SectionLabel("Agenda deportiva", Modifier.padding(top = 12.dp))
            TvTextField(
                agendaUrl,
                { agendaUrl = it; prefs.agendaUrl = it },
                "URL de agenda.json (vacío = la de tu repositorio)",
                placeholder = BuildConfig.AGENDA_URL.ifBlank { "https://raw.githubusercontent.com/usuario/repo/agenda-data/agenda.json" },
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri
            )

            TvButton("Servicios y diagnóstico", onOpenServices, icon = R.drawable.ic_list, style = ButtonStyle.GHOST)
            Txt(
                "Comprueba qué fuentes de la agenda, los canales y los retratos están funcionando.",
                Type.Caption, color = Palette.TextDim
            )

            SectionLabel("Rendimiento", Modifier.padding(top = 12.dp))
            ToggleRow(
                "Animaciones de foco",
                animations,
                { onAnimationsChange(!animations) },
                description = "Desactívalas si la TV va justa: la navegación será más fluida."
            )
            TvButton("Borrar caché de canales", onClearCache, icon = R.drawable.ic_delete, style = ButtonStyle.GHOST)

            SectionLabel("Información", Modifier.padding(top = 12.dp))
            Column(
                Modifier.fillMaxWidth().background(Palette.Surface, RoundedCornerShape(14.dp)).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                InfoLine("Dominio", BuildConfig.STREAM_DOMAIN)
                InfoLine("DNS", "8.8.8.8 · 1.1.1.1 (UDP/TCP) → Google / Cloudflare DoH")
                InfoLine("Enlaces", "mylinkspaste://código · mivisor://código")
                InfoLine("Versión", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row {
        Txt(label, Type.Caption, color = Palette.TextFaint, modifier = Modifier.widthIn(min = 90.dp))
        Txt(value, Type.Caption, color = Palette.TextDim)
    }
}
