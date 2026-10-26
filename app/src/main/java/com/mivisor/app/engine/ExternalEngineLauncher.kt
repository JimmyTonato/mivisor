package com.mivisor.app.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Ace Stream EXTERNO: delega en la app que resuelva `Intent.ACTION_VIEW` con `acestream://<contentId>`.
 *
 *  - No se fija ninguna versión ni paquete concreto: se pregunta al sistema qué apps lo abren. Si hay varias, el
 *    selector de MiVisor deja elegir y recordar (y comprueba que la recordada siga instalada) — ver StreamLauncher.
 *  - No hay preview embebida: Ace Stream externo se abre fuera de MiVisor, así que SOLO se usa tras una acción
 *    explícita de reproducción (OK/Ver), nunca por mover el foco.
 */
object ExternalEngineLauncher {
    fun intentFor(contentId: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("acestream://" + contentId.trim().lowercase())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Apps instaladas que abren acestream:// (paquetes). */
    fun handlers(context: Context): List<String> = runCatching {
        context.packageManager.queryIntentActivities(intentFor("0".repeat(40)), PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
            .filter { it != context.packageName }
            .distinct()
    }.getOrDefault(emptyList())

    fun isAvailable(context: Context): Boolean = handlers(context).isNotEmpty()
}
