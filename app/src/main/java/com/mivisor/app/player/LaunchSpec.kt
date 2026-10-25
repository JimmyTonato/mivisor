package com.mivisor.app.player

import com.mivisor.app.data.AceStreamLink
import java.util.Locale

/**
 * Lo que se va a pedir a Android para abrir un canal (lógica pura, comparable y probada en la JVM).
 * [StreamLauncher] solo convierte esto en un Intent.
 */
data class LaunchSpec(
    val action: String,
    /** URI EXACTA que se envía (sin re-codificar). */
    val uri: String,
    val mime: String?,
    val flags: Int,
    /** Extras del Intent (claves → valor). Vacío para Ace Stream: igual que la app antigua. */
    val extras: Map<String, String>,
    /** true si acestream:// se convirtió en la URL HTTP del motor local (plantilla de Ajustes). */
    val convertedToHttp: Boolean
) {
    val scheme: String get() = uri.substringBefore(':', "").lowercase(Locale.ROOT)
}

object LaunchPlanner {
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
    const val EXTRA_TITLE = "android.intent.extra.TITLE"
    const val DEFAULT_ACE_TEMPLATE = "http://127.0.0.1:6878/ace/getstream?id={id}"

    /**
     * Mismas reglas que MyLinksPaste 4.6 para Ace Stream:
     *  - acestream:// → VIEW con la URI tal cual (limpia de extremos), FLAG_ACTIVITY_NEW_TASK, SIN MIME y SIN extras.
     *  - Con "motor local (HTTP)" activado: se quita "acestream://" (sin distinguir mayúsculas) y se sustituye {id}
     *    en la plantilla, sin codificar el Content ID.
     *  - Magnet con servidor remoto: plantilla con el enlace codificado (como la app antigua).
     * Para el resto (HLS, vídeo…): MIME por extensión y título en extras (lo usan VLC/MX Player).
     */
    fun plan(
        url: String,
        title: String,
        customAce: Boolean,
        aceTemplate: String,
        customMagnet: Boolean,
        magnetTemplate: String,
        encode: (String) -> String
    ): LaunchSpec {
        val raw = AceStreamLink.clean(url)
        require(raw.isNotEmpty()) { "Este canal no tiene enlace" }
        val scheme = raw.substringBefore(':', "").lowercase(Locale.ROOT)
        val isAce = scheme == "acestream"
        val finalUrl = when {
            isAce && customAce -> aceTemplate.ifBlank { DEFAULT_ACE_TEMPLATE }.replace("{id}", raw.replace(AceStreamLink.PREFIX, "", ignoreCase = true))
            scheme == "magnet" && customMagnet -> magnetTemplate.replace("{id}", encode(raw))
            else -> raw
        }
        val aceLike = isAce                                  // acestream:// directo o convertido al motor local
        return LaunchSpec(
            action = ACTION_VIEW,
            uri = finalUrl,
            mime = if (aceLike && !customAce) null else mimeFor(finalUrl),
            flags = FLAG_ACTIVITY_NEW_TASK,
            extras = if (aceLike) emptyMap() else mapOf(EXTRA_TITLE to title, "title" to title),
            convertedToHttp = isAce && customAce
        )
    }

    fun mimeFor(url: String): String? {
        val path = url.lowercase(Locale.ROOT).substringBefore('?')
        return when {
            ".m3u8" in path -> "application/vnd.apple.mpegurl"
            path.endsWith(".m3u") -> "audio/x-mpegurl"
            path.endsWith(".mp4") -> "video/mp4"
            path.endsWith(".mkv") -> "video/x-matroska"
            path.endsWith(".ts") -> "video/mp2t"
            path.endsWith(".mpd") -> "application/dash+xml"
            path.endsWith(".webm") -> "video/webm"
            else -> null
        }
    }
}

/**
 * Cómo abría los enlaces MyLinksPaste 4.6 (MainActivity.abrirStream), transcrito de su bytecode para poder
 * comparar en pruebas: trim → Uri.parse → Intent(VIEW, uri) + FLAG_ACTIVITY_NEW_TASK; setDataAndType solo para
 * http/https con .m3u8/.m3u/.mp4/.mkv; ningún extra.
 */
object LegacyMyLinksPaste {
    fun plan(url: String, customAce: Boolean, aceTemplate: String): LaunchSpec {
        val obj = url.trim()
        val scheme = obj.substringBefore(':', "").lowercase(Locale.ROOT)
        val finalUrl = if (scheme == "acestream" && customAce) {
            aceTemplate.replace("{id}", obj.replace("acestream://", "", ignoreCase = true))
        } else obj
        val finalScheme = finalUrl.substringBefore(':', "").lowercase(Locale.ROOT)
        val mime = if (finalScheme == "http" || finalScheme == "https") when {
            finalUrl.contains(".m3u8", true) -> "application/vnd.apple.mpegurl"
            finalUrl.contains(".m3u", true) -> "audio/x-mpegurl"
            finalUrl.contains(".mp4", true) -> "video/mp4"
            finalUrl.contains(".mkv", true) -> "video/x-matroska"
            else -> null
        } else null
        return LaunchSpec(LaunchPlanner.ACTION_VIEW, finalUrl, mime, LaunchPlanner.FLAG_ACTIVITY_NEW_TASK, emptyMap(), scheme == "acestream" && customAce)
    }
}

/** Informe de apertura (Ajustes → Sistema → Registro de errores). Content IDs siempre enmascarados. */
object LaunchReport {
    fun build(
        channelName: String,
        provider: String?,
        storedUrl: String,
        spec: LaunchSpec,
        legacy: LaunchSpec,
        target: String,
        enginePrepared: Boolean,
        engineState: String
    ): String {
        val idBefore = AceStreamLink.contentId(storedUrl)
        val idAfter = if (spec.convertedToHttp) idBefore?.takeIf { spec.uri.contains(it) } else AceStreamLink.contentId(spec.uri)
        val diffs = buildList {
            if (spec.uri != legacy.uri) add("URI distinta")
            if (spec.mime != legacy.mime) add("MIME distinto (${spec.mime ?: "—"} / ${legacy.mime ?: "—"})")
            if (spec.flags != legacy.flags) add("flags distintos")
            if (spec.extras.isNotEmpty()) add("extras ${spec.extras.keys.joinToString()} (la app antigua no enviaba)")
            if (enginePrepared) add("MiVisor arrancó antes el motor (la app antigua no)")
        }
        fun mask(u: String): String {
            val id = AceStreamLink.contentId(u) ?: return u.substringBefore(':') + "://…"
            return u.replace(id, AceStreamLink.masked(id))
        }
        return buildString {
            appendLine("Diagnóstico de apertura")
            appendLine("Canal: $channelName${provider?.let { " · $it" }.orEmpty()}")
            appendLine("1. Valor guardado (ya limpio de extremos): ${if (idBefore != null) mask(storedUrl) else "no es Ace Stream"}")
            appendLine("2. Tras la importación: sin cambios internos (solo se limpian espacios/saltos de los extremos)")
            appendLine("3. URI enviada: ${if (spec.convertedToHttp) "motor local HTTP con el Content ID" else mask(spec.uri)}")
            appendLine("4. Prefijo acestream:// conservado: ${if (spec.convertedToHttp) "no (convertido a HTTP local por Ajustes)" else if (spec.scheme == "acestream") "sí" else "no aplica"}")
            appendLine("5. Content ID antes / después: ${AceStreamLink.masked(idBefore)} / ${if (idAfter == idBefore) "idéntico" else "DISTINTO"}")
            appendLine("6. Intent: ${spec.action}")
            appendLine("7. Destino: $target")
            appendLine("8. Flags: 0x${Integer.toHexString(spec.flags)} (NEW_TASK)")
            appendLine("9. MIME: ${spec.mime ?: "ninguno"}")
            appendLine("10. Integración con el motor: ${if (enginePrepared) "MiVisor lo inició antes (modo HTTP local)" else "la app de Ace Stream gestiona su motor"}")
            appendLine("11. Estado del motor antes de abrir: $engineState")
            appendLine("12. URL HTTP local generada: ${if (spec.convertedToHttp) "sí (activado en Ajustes → Reproductor)" else "no"}")
            appendLine("13. Redirección intermedia en MiVisor: ninguna (se abre directamente)")
            appendLine("14. Parser modificó la URL: ${if (idBefore != null && idAfter == idBefore) "no" else "revisar"}")
            appendLine("15. Diferencias con MyLinksPaste 4.6: ${diffs.ifEmpty { listOf("ninguna") }.joinToString("; ")}")
        }
    }
}
