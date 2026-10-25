package com.mivisor.app.diagnostics

import android.content.Context
import android.os.Build
import android.util.Log
import com.mivisor.app.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Registro de errores local: si MiVisor se cierra por un fallo, guarda la traza (limpia, sin códigos ni URLs)
 * para verla después en Ajustes → Registro de errores o enviarla desde el móvil por QR.
 * Después deja que Android cierre la app como siempre (no oculta el fallo).
 */
object CrashReporter {
    private const val DIR = "crashes"
    private const val MAX_FILES = 10
    private const val PREFS = "diagnostics"
    private val breadcrumbs = ArrayDeque<String>()
    @Volatile private var dir: File? = null

    fun install(context: Context) {
        if (dir != null) return
        val d = File(context.filesDir, DIR).apply { mkdirs() }
        dir = d
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(d, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Pasos recientes (pantallas), para entender qué se estaba haciendo. Sin datos personales. */
    @Synchronized
    fun breadcrumb(text: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        breadcrumbs.addLast("$time  ${LogSanitizer.clean(text)}")
        while (breadcrumbs.size > 30) breadcrumbs.removeFirst()
    }

    /** Errores que no cierran la app pero conviene ver (p. ej. un fallo de foco recuperado). */
    fun nonFatal(context: Context, where: String, error: Throwable) {
        Log.w("MiVisor/Crash", "non-fatal in $where: ${error.javaClass.simpleName}")
        runCatching { write(File(context.filesDir, DIR).apply { mkdirs() }, Thread.currentThread(), error, fatal = false, where = where) }
    }

    fun reports(context: Context): List<File> =
        File(context.filesDir, DIR).listFiles { f -> f.name.endsWith(".txt") }?.sortedByDescending { it.name }.orEmpty()

    fun clear(context: Context) {
        reports(context).forEach { it.delete() }
    }

    /** ¿Hay un cierre nuevo desde la última vez que se avisó? (para el aviso al abrir) */
    fun takeUnseenCrash(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val latest = reports(context).firstOrNull { it.name.startsWith("crash-") }?.name ?: return false
        if (prefs.getString("seen", null) == latest) return false
        prefs.edit().putString("seen", latest).apply()
        return true
    }

    @Synchronized
    private fun write(d: File, thread: Thread, error: Throwable, fatal: Boolean = true, where: String? = null) {
        val now = Date()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(now)
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val report = buildString {
            appendLine(if (fatal) "MiVisor se cerró inesperadamente" else "Error recuperado${where?.let { " ($it)" }.orEmpty()}")
            appendLine("Fecha: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)}")
            appendLine("Versión: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Hilo: ${thread.name}")
            appendLine()
            appendLine("Pasos recientes:")
            breadcrumbs.forEach { appendLine("  $it") }
            appendLine()
            appendLine("Traza:")
            append(trace)
        }
        File(d, "${if (fatal) "crash" else "error"}-$stamp.txt").writeText(LogSanitizer.clean(report))
        d.listFiles()?.sortedByDescending { it.name }?.drop(MAX_FILES)?.forEach { it.delete() }
    }
}
