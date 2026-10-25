package com.mivisor.app.diagnostics

/**
 * Limpia los textos del registro de errores antes de guardarlos o mostrarlos:
 * nada de URLs completas, códigos largos, hashes de Ace Stream ni tokens.
 */
object LogSanitizer {
    private val URL = Regex("""\b([a-zA-Z][a-zA-Z0-9+.-]{1,15})://[^\s"'<>)]+""")
    private val LONG_TOKEN = Regex("""\b[A-Za-z0-9_\-+/=]{24,}\b""")
    private val HEX = Regex("""\b[0-9a-fA-F]{16,}\b""")

    fun clean(text: String): String = text
        .replace(URL) { m -> "${m.groupValues[1]}://[…]" }
        .replace(HEX, "[oculto]")
        .replace(LONG_TOKEN) { m -> if (looksLikeToken(m.value)) "[oculto]" else m.value }

    /**
     * Tokens/códigos largos mezclan letras y números; los nombres de clase de las trazas
     * (LazyLayoutBeyondBoundsModifierLocal…) no, así que se conservan.
     */
    private fun looksLikeToken(s: String) = s.count { it.isDigit() } >= 2 && s.count { it.isLetter() } >= 2
}
