package com.mivisor.app.data

import java.text.Normalizer

/**
 * Punto ÚNICO por el que pasan todos los nombres de canal (y de grupo) antes de entrar en el catálogo.
 * Reglas GENERALES (no hay listas de canales):
 *
 *  1. Letras "decorativas" que Unicode no descompone → A–Z: letras en cuadro/círculo negativo (🅰 🅐),
 *     indicadores regionales (🇩), versalitas (ᴅᴀᴢɴ). Se hace por rangos de código, no por nombres.
 *  2. Unicode NFKC: letras en cuadro/círculo (🄳 Ⓓ), ancho completo (ＤＡＺＮ), matemáticas (𝐃𝐀𝐙𝐍), ligaduras…
 *  3. Fuera caracteres invisibles (categoría Cf: espacios de ancho cero, BOM, marcas de dirección, guion blando),
 *     selectores de variación y marcas que encierran (keycaps).
 *  4. Emojis, símbolos y separadores decorativos (🟧 ★ ═ • ▶) → espacio. Se conservan letras, números y
 *     los signos que sí forman parte de nombres: + & . ' - : / ( ) ! ? # @ _ ,
 *  5. Espacios y saltos de línea → un espacio; se recortan extremos y separadores colgantes ("- DAZN 1 |").
 *
 * [display] da el nombre que se ve (respeta mayúsculas). [matchKey] es la forma de comparar:
 * minúsculas, sin acentos y sin signos. Los alias de canal ("M+" = "Movistar") van DESPUÉS, en [ChannelMatcher].
 * Nunca toca URLs: solo recibe nombres.
 */
object ChannelNameNormalizer {
    private const val KEEP = "+&.'-:/()!?#@_,"
    private const val DANGLING = "-|:.,/·_"

    /** Versalitas sin descomposición Unicode → letra latina (tabla del alfabeto, no de canales). */
    private val SMALL_CAPS = mapOf(
        'ᴀ' to 'A', 'ʙ' to 'B', 'ᴄ' to 'C', 'ᴅ' to 'D', 'ᴇ' to 'E', 'ꜰ' to 'F', 'ɢ' to 'G', 'ʜ' to 'H', 'ɪ' to 'I',
        'ᴊ' to 'J', 'ᴋ' to 'K', 'ʟ' to 'L', 'ᴍ' to 'M', 'ɴ' to 'N', 'ᴏ' to 'O', 'ᴘ' to 'P', 'ǫ' to 'Q', 'ʀ' to 'R',
        'ꜱ' to 'S', 'ᴛ' to 'T', 'ᴜ' to 'U', 'ᴠ' to 'V', 'ᴡ' to 'W', 'ʏ' to 'Y', 'ᴢ' to 'Z'
    )

    /** Nombre limpio para mostrar y guardar. Vacío si el nombre era solo decoración (🟧🟧🟧). */
    fun display(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val mapped = mapDecorativeLetters(raw)
        val nfkc = Normalizer.normalize(mapped, Normalizer.Form.NFKC)
        val sb = StringBuilder(nfkc.length)
        var i = 0
        while (i < nfkc.length) {
            val cp = nfkc.codePointAt(i)
            i += Character.charCount(cp)
            when {
                isInvisible(cp) -> Unit                                      // se elimina sin dejar hueco
                Character.isLetterOrDigit(cp) -> sb.appendCodePoint(cp)
                Character.getType(cp) == Character.NON_SPACING_MARK.toInt() ||
                    Character.getType(cp) == Character.COMBINING_SPACING_MARK.toInt() -> sb.appendCodePoint(cp)   // acentos combinados
                cp < 0x10000 && cp.toChar() in KEEP -> sb.appendCodePoint(cp)
                else -> sb.append(' ')                                        // espacios, saltos, emojis, separadores
            }
        }
        // NFC final: las letras con acento combinado vuelven a ser un solo carácter ("é").
        val collapsed = Normalizer.normalize(sb.toString(), Normalizer.Form.NFC).replace(Regex("""\s+"""), " ").trim()
        return trimDangling(collapsed)
    }

    /** Clave de comparación: display + minúsculas + sin acentos + solo letras/números separados por un espacio. */
    fun matchKey(raw: String?): String {
        val d = display(raw)
        if (d.isEmpty()) return ""
        return Normalizer.normalize(d.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
            .replace(Regex(""" +"""), " ")
    }

    /** ¿Cambió el nombre al normalizarlo? (para el resumen de importación). */
    fun changed(raw: String?, display: String): Boolean = (raw ?: "").trim() != display

    // ------------------------------------------------------------------ reglas

    private fun mapDecorativeLetters(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            val letter: Int? = when (cp) {
                in 0x1F130..0x1F149 -> 'A'.code + (cp - 0x1F130)              // 🄰 cuadradas (NFKC también lo haría)
                in 0x1F150..0x1F169 -> 'A'.code + (cp - 0x1F150)              // 🅐 círculo negativo
                in 0x1F170..0x1F189 -> 'A'.code + (cp - 0x1F170)              // 🅰 cuadrado negativo
                in 0x1F1E6..0x1F1FF -> 'A'.code + (cp - 0x1F1E6)              // 🇦 indicadores regionales
                else -> if (cp < 0x10000) SMALL_CAPS[cp.toChar()]?.code else null
            }
            if (letter != null) sb.append(letter.toChar()) else sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    private fun isInvisible(cp: Int): Boolean {
        val type = Character.getType(cp)
        return type == Character.FORMAT.toInt() ||                           // ZWSP, ZWJ, BOM, LRM/RLM, guion blando…
            type == Character.ENCLOSING_MARK.toInt() ||                       // ⃣ keycap
            cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF ||                 // selectores de variación
            cp in 0xE0000..0xE007F ||                                        // etiquetas (banderas de subdivisión)
            cp == 0x034F || cp == 0x115F || cp == 0x1160 || cp == 0x3164 || cp == 0xFFA0   // "hangul filler" invisibles
    }

    private fun trimDangling(s: String): String {
        var r = s
        while (r.isNotEmpty() && (r.first() in DANGLING || r.first() == ' ')) r = r.drop(1)
        while (r.isNotEmpty() && (r.last() in DANGLING || r.last() == ' ')) r = r.dropLast(1)
        return r
    }
}
