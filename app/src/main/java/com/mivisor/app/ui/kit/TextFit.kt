package com.mivisor.app.ui.kit

/**
 * Tamaño de letra para nombres (equipos, competiciones) según su longitud y el ancho disponible.
 * Lógica pura (se prueba en la JVM): primero se permiten 2 líneas, luego se reduce un poco la letra
 * (nunca por debajo de [minSp]) y la elipsis queda como último recurso.
 *
 * Aproximación de ancho medio de carácter ≈ 0,55 × tamaño de letra (fuentes sans de Android TV).
 */
object TextFit {
    const val CHAR_WIDTH_FACTOR = 0.55f

    /** Caracteres que caben en una línea de [widthDp] con letra [sp] (1 sp ≈ 1 dp con escala de fuente 1). */
    fun charsPerLine(widthDp: Float, sp: Float, fontScale: Float = 1f): Int =
        (widthDp / (sp * fontScale * CHAR_WIDTH_FACTOR)).toInt().coerceAtLeast(1)

    /** Líneas que ocupa [text] partiendo por palabras (sin cortar palabras salvo que no quepan). */
    fun linesNeeded(text: String, perLine: Int): Int {
        var lines = 1
        var used = 0
        for (word in text.trim().split(Regex("""\s+"""))) {
            val w = word.length
            if (used == 0) {
                used = w
                while (used > perLine) { lines++; used -= perLine }
            } else if (used + 1 + w <= perLine) {
                used += 1 + w
            } else {
                lines++
                used = w
                while (used > perLine) { lines++; used -= perLine }
            }
        }
        return lines
    }

    /**
     * Mayor tamaño entre [baseSp] y [minSp] (pasos de 0,5) con el que [text] cabe en [maxLines] líneas de [widthDp].
     * Si ni con [minSp] cabe, devuelve [minSp] (y la UI corta con elipsis: último recurso).
     */
    fun fit(text: String, widthDp: Float, baseSp: Float, minSp: Float, maxLines: Int = 2, fontScale: Float = 1f): Float {
        var sp = baseSp
        while (sp >= minSp) {
            if (linesNeeded(text, charsPerLine(widthDp, sp, fontScale)) <= maxLines) return sp
            sp -= 0.5f
        }
        return minSp
    }

    /** ¿Cabe sin elipsis con [sp]? */
    fun fits(text: String, widthDp: Float, sp: Float, maxLines: Int = 2, fontScale: Float = 1f): Boolean =
        linesNeeded(text, charsPerLine(widthDp, sp, fontScale)) <= maxLines
}
