package com.mivisor.app.data

/**
 * Enlaces Ace Stream. Separado del nombre: aquí no se normaliza nada del título y allí no se toca la URL.
 *
 *  - Válido: `acestream://` + Content ID de 40 caracteres hexadecimales (opcionalmente seguido de `?…`).
 *    El esquema puede venir en mayúsculas ("AceStream://"); se respeta tal cual.
 *  - Content ID "puro" (solo los 40 hex): se acepta y se le antepone `acestream://` (validado; nada más se convierte).
 *  - Una URL http/https NUNCA se convierte en Ace Stream (ni siquiera la del motor local /ace/getstream).
 *  - Limpieza: SOLO basura externa en los extremos (espacios, \r, \n, tabuladores y caracteres invisibles).
 *    El Content ID no se modifica: ni mayúsculas, ni codificación, ni recortes internos.
 */
object AceStreamLink {
    const val PREFIX = "acestream://"
    private val CONTENT_ID = Regex("^[0-9a-fA-F]{40}$")

    /** Quita basura externa de los extremos y nada más. */
    fun clean(raw: String?): String {
        if (raw == null) return ""
        var start = 0
        var end = raw.length
        while (start < end && isJunk(raw[start])) start++
        while (end > start && isJunk(raw[end - 1])) end--
        return raw.substring(start, end)
    }

    /** URL Ace Stream lista para abrir, o null si la entrada no es un Ace Stream válido (no es un canal). */
    fun parse(raw: String?): String? {
        val s = clean(raw)
        if (s.isEmpty()) return null
        if (s.regionMatches(0, PREFIX, 0, PREFIX.length, ignoreCase = true)) {
            val rest = s.substring(PREFIX.length)
            val id = rest.substringBefore('?').removeSuffix("/")
            return if (CONTENT_ID.matches(id)) s else null
        }
        return if (CONTENT_ID.matches(s)) PREFIX + s else null
    }

    fun isValid(raw: String?): Boolean = parse(raw) != null

    /** Content ID de una URL ya validada (tal cual, sin cambiar mayúsculas). */
    fun contentId(url: String?): String? {
        val s = parse(url) ?: return null
        return s.substring(PREFIX.length).substringBefore('?').removeSuffix("/")
    }

    /**
     * IDENTIDAD técnica de una fuente Ace: el mismo Content ID escrito de cualquier forma da el mismo valor.
     * Acepta `acestream://ID`, `acestream://ID?…`, el ID solo, o una URI con `content_id=` / `id=` (p. ej. el
     * `/ace/getstream?content_id=` de un motor). Quita basura de los extremos, pasa a minúsculas y valida 40 hex.
     * Solo sirve para comparar/deduplicar y como clave de salud: el enlace guardado del canal NO se modifica.
     */
    fun normalizedId(raw: String?): String? {
        val s = clean(raw)
        if (s.isEmpty()) return null
        val candidate = when {
            s.regionMatches(0, PREFIX, 0, PREFIX.length, ignoreCase = true) ->
                s.substring(PREFIX.length).substringBefore('?').substringBefore('#').trim('/', ' ')
            CONTENT_ID.matches(s) -> s
            else -> URI_ID.find(s)?.groupValues?.get(1)
        } ?: return null
        return candidate.takeIf { CONTENT_ID.matches(it) }?.lowercase(java.util.Locale.ROOT)
    }

    private val URI_ID = Regex("[?&](?:content_id|id)=([0-9a-fA-F]{40})(?:[&#]|$)")

    /** Para registros: "2a05…9f1c (40)". Nunca el Content ID completo. */
    fun masked(id: String?): String =
        if (id.isNullOrEmpty()) "—" else if (id.length <= 8) "…(${id.length})" else "${id.take(4)}…${id.takeLast(4)} (${id.length})"

    private fun isJunk(c: Char): Boolean =
        c.isWhitespace() || c == '\u0000' || Character.getType(c) == Character.FORMAT.toInt() || c.isISOControl()
}
