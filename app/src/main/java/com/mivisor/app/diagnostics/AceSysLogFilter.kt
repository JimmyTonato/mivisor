package com.mivisor.app.diagnostics

import java.util.Calendar

/**
 * Filtro (puro, se prueba en la JVM) de las líneas del log del sistema (`logcat -v time`) que escribe Ace Stream
 * mientras intenta reproducir un canal abierto desde MiVisor.
 *
 * Etiquetas de Ace Stream (sacadas de la propia app): AS/Player, AS/ContentStart, AS/Manager, AS/Main,
 * AS/ServiceClient…, AceStream/engine, AceStream/DS, acestream/debug. Además, las líneas del sistema
 * (ActivityManager…) que nombran a org.acestream (arranque/cierre/caída de su proceso).
 *
 * Solo se guardan esas líneas, dentro de la ventana [desde, hasta] del intento, limpias de Content IDs/hashes,
 * URLs, tokens e IPs de otras personas (peers).
 */
object AceSysLogFilter {
    /** 10-25 20:24:25.123 D/AS/Player( 1234): mensaje */
    private val LINE = Regex("""^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d{3})\s+([VDIWEFA])/(.+?)\(\s*(\d+)\):\s?(.*)$""")
    private val ACE_TAG = Regex("""^(AS/|AceStream|acestream)""", RegexOption.IGNORE_CASE)
    private val IPV4 = Regex("""\b(?!127\.0\.0\.1\b)(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})\b""")
    private val IPV6 = Regex("""\b(?:[0-9a-fA-F]{1,4}:){3,7}[0-9a-fA-F]{1,4}\b""")

    const val MAX_LINES = 150
    const val KEEP_LAST = 90

    data class Line(val time: Long, val level: Char, val tag: String, val message: String)

    fun parse(raw: String, year: Int): Line? {
        val m = LINE.find(raw.trimEnd()) ?: return null
        val g = m.groupValues
        val cal = Calendar.getInstance().apply {
            clear()
            set(year, g[1].toInt() - 1, g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt())
            set(Calendar.MILLISECOND, g[6].toInt())
        }
        return Line(cal.timeInMillis, g[7][0], g[8].trim(), g[10])
    }

    fun isAce(l: Line): Boolean =
        ACE_TAG.containsMatchIn(l.tag) || (l.message.contains("org.acestream", ignoreCase = true) &&
            (l.tag.startsWith("ActivityManager") || l.tag.startsWith("ActivityTaskManager") || l.tag == "AndroidRuntime" || l.tag == "libprocessgroup"))

    /**
     * Líneas de Ace entre [from] y [to] (ms). Si hay demasiadas: todas las W/E/F + las últimas [KEEP_LAST],
     * en orden cronológico, sin pasar de [MAX_LINES]. Devuelve texto ya limpio, una línea por entrada.
     */
    class Selection(val lines: List<String>, val total: Int)

    fun select(rawLines: List<String>, from: Long, to: Long, year: Int): Selection {
        val ace = rawLines.mapNotNull { parse(it, year) }.filter { it.time in (from - 2_000)..(to + 2_000) && isAce(it) }
        val chosen = if (ace.size <= MAX_LINES) ace else {
            val last = ace.takeLast(KEEP_LAST).toSet()
            val important = ace.filter { it.level in "WEFA" && it !in last }.takeLast(MAX_LINES - KEEP_LAST)
            ace.filter { it in last || it in important }
        }
        return Selection(chosen.map { format(it) }, ace.size)
    }

    fun format(l: Line): String {
        val c = Calendar.getInstance().apply { timeInMillis = l.time }
        val t = "%02d:%02d:%02d.%03d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND), c.get(Calendar.MILLISECOND))
        return "$t ${l.level}/${l.tag}: ${clean(l.message)}"
    }

    /** Limpieza general del registro + IPs (las de los peers son de otras personas). */
    fun clean(text: String): String =
        LogSanitizer.clean(text)
            .replace(IPV6, "[ip]")
            .replace(IPV4, "[ip]")
            .take(400)

    /** Bloque para el Registro Ace Stream. */
    fun block(sel: Selection): String = buildString {
        appendLine("  📋 Log interno de Ace Stream durante el intento (${sel.lines.size}${if (sel.total > sel.lines.size) " de ${sel.total}" else ""} líneas):")
        if (sel.lines.isEmpty()) appendLine("     (sin líneas de Ace: si Android preguntó si MiVisor puede ver los registros del dispositivo, hay que aceptar)")
        sel.lines.forEach { appendLine("     $it") }
    }
}
