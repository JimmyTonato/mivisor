package com.mivisor.app.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Lectura OPCIONAL del log del sistema para ver qué hace Ace Stream al abrir un canal desde MiVisor.
 *
 * Ace Stream guarda su log propio (acestream.log) en su carpeta privada (Android/data/…), que ninguna otra
 * app puede leer desde Android 11. Lo que sí escribe en el log del sistema (AS/Player, AS/ContentStart…)
 * se puede leer con el permiso READ_LOGS, que Android solo concede a mano desde un ordenador:
 *     adb shell pm grant <paquete de MiVisor> android.permission.READ_LOGS
 * Sin el permiso no se hace nada. Solo se guardan las líneas de Ace del intento, limpias ([AceSysLogFilter]).
 */
object AceSysLog {
    const val PERMISSION = "android.permission.READ_LOGS"

    fun granted(context: Context): Boolean =
        context.checkPermission(PERMISSION, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED

    fun grantCommand(context: Context): String = "adb shell pm grant ${context.packageName} $PERMISSION"

    /** Bloque de texto con las líneas de Ace entre [from] y [to], o null si no hay permiso. */
    suspend fun capture(context: Context, from: Long, to: Long): String? = withContext(Dispatchers.IO) {
        if (!granted(context)) return@withContext null
        runCatching {
            val process = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "8000").redirectErrorStream(true).start()
            val lines = process.inputStream.bufferedReader().useLines { seq -> seq.take(8000).toList() }
            process.destroy()   // con -d logcat termina solo; destroy por si se cortó la lectura (sin waitFor con tiempo: API 26+)
            val year = Calendar.getInstance().get(Calendar.YEAR)
            AceSysLogFilter.block(AceSysLogFilter.select(lines, from, to, year))
        }.getOrElse { "  📋 Log interno de Ace Stream: no se pudo leer (${it.javaClass.simpleName})\n" }
    }
}
