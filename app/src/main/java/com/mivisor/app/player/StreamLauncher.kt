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

    /** Especificación pura (misma para pruebas y para el Intent real). */
    fun spec(channel: Channel): LaunchSpec = LaunchPlanner.plan(
        url = channel.url,
        title = channel.name,
        customAce = preferences.customAceEnabled,
        aceTemplate = preferences.customAceTemplate.ifBlank { BuildConfig.ACESTREAM_TEMPLATE },
        customMagnet = preferences.customMagnetEnabled,
        magnetTemplate = preferences.customMagnetTemplate.ifBlank { BuildConfig.MAGNET_TEMPLATE },
        encode = { Uri.encode(it) }
    )

    fun buildIntent(channel: Channel): Intent = intentFor(spec(channel))

    private fun intentFor(spec: LaunchSpec): Intent {
        // Uri.parse NO re-codifica: el Content ID llega tal cual al reproductor.
        val uri = Uri.parse(spec.uri)
        return Intent(spec.action, uri).apply {
            addFlags(spec.flags)
            if (spec.mime != null) setDataAndType(uri, spec.mime)
            spec.extras.forEach { (k, v) -> putExtra(k, v) }
        }
    }

    /** Último informe de apertura (para Registro de errores). Content IDs enmascarados. */
    var lastReport: String? = null
        private set

    /** Lanza directamente si puede; si hay que elegir, devuelve la lista de apps. */
    fun open(channel: Channel, forceChooser: Boolean = false, engineState: String = "desconocido", enginePrepared: Boolean = false): Decision {
        val spec = spec(channel)
        val intent = intentFor(spec)
        val scheme = spec.scheme
        fun report(target: String) {
            lastReport = LaunchReport.build(
                channel.name, channel.source, channel.url, spec,
                LegacyMyLinksPaste.plan(channel.url, preferences.customAceEnabled, preferences.customAceTemplate.ifBlank { BuildConfig.ACESTREAM_TEMPLATE }),
                target, enginePrepared, engineState
            )
        }
        if (!forceChooser) {
            if (!preferences.selectorEnabled || scheme == "tvgram" || scheme == "tg") {
                report("el sistema (app predeterminada para $scheme)")
                launch(intent); return Decision.Launched
            }
            preferences.preferredPackage(channel.name, channel.group, scheme)
                ?.takeIf(::isInstalled)
                ?.let { pkg ->
                    report("$pkg (app recordada)")
                    if (runCatching { launch(intent, pkg) }.isSuccess) return Decision.Launched
                }
        }
        val apps = compatibleApps(intent)
        return when {
            apps.isEmpty() -> throw IllegalStateException("No hay ninguna app instalada que pueda abrir este enlace")
            apps.size == 1 && !forceChooser -> { report("${apps[0].packageName} (única app compatible)"); launch(intent, apps[0].packageName); Decision.Launched }
            else -> { report("elegida por el usuario entre ${apps.size} apps"); Decision.Choose(intent, apps) }
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

}
