package com.mivisor.app.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.mivisor.app.BuildConfig
import com.mivisor.app.model.Channel
import java.util.Locale

/**
 * Abre los canales en reproductores externos, con las mismas reglas que la app original:
 * plantillas para AceStream y magnet, tipo MIME según la extensión y app preferida por
 * canal → grupo → protocolo.
 */
class StreamLauncher(context: Context) {
    private val app = context.applicationContext
    val preferences = PlayerPreferences(app)

    class AppChoice(val packageName: String, val label: String)

    sealed class Decision {
        object Launched : Decision()
        class Choose(val intent: Intent, val apps: List<AppChoice>) : Decision()
    }

    fun buildIntent(channel: Channel): Intent {
        val raw = channel.url.trim()
        require(raw.isNotEmpty()) { "Este canal no tiene enlace" }
        val scheme = Uri.parse(raw).scheme?.lowercase(Locale.ROOT).orEmpty()
        val finalUrl = when {
            scheme == "acestream" && preferences.customAceEnabled -> {
                val id = raw.replaceFirst(Regex("^acestream:(//)?", RegexOption.IGNORE_CASE), "")
                preferences.customAceTemplate.ifBlank { BuildConfig.ACESTREAM_TEMPLATE }.replace("{id}", id)
            }
            scheme == "magnet" && preferences.customMagnetEnabled ->
                preferences.customMagnetTemplate.ifBlank { BuildConfig.MAGNET_TEMPLATE }
                    .replace("{id}", Uri.encode(raw))
            else -> raw
        }
        val uri = Uri.parse(finalUrl)
        return Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val mime = mimeFor(finalUrl)
            if (mime != null) setDataAndType(uri, mime) else data = uri
            putExtra(Intent.EXTRA_TITLE, channel.name)
            putExtra("title", channel.name) // VLC, MX Player y similares
        }
    }

    /** Lanza directamente si puede; si hay que elegir, devuelve la lista de apps. */
    fun open(channel: Channel, forceChooser: Boolean = false): Decision {
        val intent = buildIntent(channel)
        val scheme = intent.data?.scheme?.lowercase(Locale.ROOT)
        if (!forceChooser) {
            if (!preferences.selectorEnabled || scheme == "tvgram" || scheme == "tg") {
                launch(intent); return Decision.Launched
            }
            preferences.preferredPackage(channel.name, channel.group, scheme)
                ?.takeIf(::isInstalled)
                ?.let { pkg ->
                    if (runCatching { launch(intent, pkg) }.isSuccess) return Decision.Launched
                }
        }
        val apps = compatibleApps(intent)
        return when {
            apps.isEmpty() -> throw IllegalStateException("No hay ninguna app instalada que pueda abrir este enlace")
            apps.size == 1 && !forceChooser -> { launch(intent, apps[0].packageName); Decision.Launched }
            else -> Decision.Choose(intent, apps)
        }
    }

    fun launch(intent: Intent, packageName: String? = null) {
        val target = Intent(intent)
        if (!packageName.isNullOrBlank()) target.setPackage(packageName)
        try {
            app.startActivity(target)
        } catch (e: ActivityNotFoundException) {
            throw IllegalStateException("No hay ninguna app instalada que pueda abrir este enlace", e)
        } catch (e: SecurityException) {
            throw IllegalStateException("La app elegida no permite abrir este enlace", e)
        }
    }

    fun remember(channel: Channel, intent: Intent, packageName: String, scope: RememberScope) {
        preferences.remember(
            packageName, channel.name, channel.group,
            intent.data?.scheme?.lowercase(Locale.ROOT), scope
        )
    }

    fun share(channel: Channel) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, channel.url)
            putExtra(Intent.EXTRA_SUBJECT, channel.name)
        }
        try {
            app.startActivity(Intent.createChooser(send, "Compartir").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            throw IllegalStateException("No hay apps para compartir en este dispositivo", e)
        }
    }

    private fun compatibleApps(intent: Intent): List<AppChoice> {
        val pm = app.packageManager
        return pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .asSequence()
            .map { it.activityInfo }
            .filter { it.packageName != app.packageName }
            .distinctBy { it.packageName }
            .map { AppChoice(it.packageName, it.loadLabel(pm)?.toString() ?: it.packageName) }
            .sortedBy { it.label.lowercase(Locale.ROOT) }
            .toList()
    }

    private fun isInstalled(packageName: String): Boolean = try {
        app.packageManager.getApplicationInfo(packageName, 0); true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun mimeFor(url: String): String? {
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
