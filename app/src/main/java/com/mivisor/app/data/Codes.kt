package com.mivisor.app.data

object Codes {
    private val VALID = Regex("[a-z0-9][a-z0-9._-]{0,126}")
    private val PREFIXES = listOf("mylinkspaste://", "mivisor://", "mylinkspaste:", "mivisor:")

    /** Normaliza lo que escribe el usuario o llega por deep link. Devuelve null si no es válido. */
    fun normalizeOrNull(raw: String?): String? {
        var value = raw?.trim()?.lowercase() ?: return null
        PREFIXES.firstOrNull { value.startsWith(it) }?.let { value = value.removePrefix(it) }
        value = value.trim('/', ' ', '.')
        return value.takeIf { VALID.matches(it) && !it.contains("..") }
    }

    fun normalize(raw: String?): String =
        normalizeOrNull(raw) ?: throw IllegalArgumentException("Código no válido")

    /**
     * Igual que la app original: el dominio se añade siempre, salvo que el código ya lo lleve.
     * (Mi Visor v3 no lo añadía si el código contenía un punto, lo que rompía referencias
     * heredadas del tipo "futbol.hoy".)
     */
    fun hostFor(code: String, domain: String): String =
        if (code == domain || code.endsWith(".$domain")) code else "$code.$domain"
}
