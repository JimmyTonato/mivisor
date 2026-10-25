package com.mivisor.app.remote

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Sesión temporal de escritura (o de consulta) desde el móvil.
 *
 *  - Token de 24 bytes de SecureRandom (192 bits) en Base64 URL: aleatorio, no predecible, único por sesión.
 *  - Caduca a los [ttlMs] (5 min por defecto).
 *  - INPUT es de un solo uso: tras recibir el texto, el token deja de valer.
 *  - Cancelar o salir del formulario la invalida.
 */
class RemoteSession(
    val mode: Mode,
    val token: String,
    private val createdAt: Long,
    val ttlMs: Long,
    /** Texto que se muestra en el móvil (solo en modo SHOW, p. ej. el registro de errores). */
    val shownText: String? = null
) {
    enum class Mode { INPUT, SHOW }
    enum class Check { OK, INVALID, EXPIRED, USED, CANCELLED }

    val expiresAt: Long get() = createdAt + ttlMs

    @Volatile private var used = false
    @Volatile private var cancelled = false

    /** Comprueba token (en tiempo constante), caducidad, uso y cancelación. */
    fun check(candidate: String?, now: Long): Check = when {
        candidate == null || !MessageDigest.isEqual(candidate.toByteArray(), token.toByteArray()) -> Check.INVALID
        cancelled -> Check.CANCELLED
        used -> Check.USED
        now >= expiresAt -> Check.EXPIRED
        else -> Check.OK
    }

    /** Marca la sesión INPUT como usada. Devuelve false si ya no era válida (carrera entre dos envíos). */
    @Synchronized
    fun consume(candidate: String?, now: Long): Boolean {
        if (check(candidate, now) != Check.OK) return false
        used = true
        return true
    }

    fun cancel() { cancelled = true }

    val isActive: Boolean get() = !used && !cancelled

    companion object {
        const val DEFAULT_TTL_MS = 5 * 60_000L
        private const val TOKEN_BYTES = 24
        private val random = SecureRandom()

        fun newToken(): String {
            val bytes = ByteArray(TOKEN_BYTES)
            random.nextBytes(bytes)
            return base64Url(bytes)
        }

        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        /** Base64 URL sin relleno (java.util.Base64 no existe en Android 5–7). */
        internal fun base64Url(bytes: ByteArray): String {
            val out = StringBuilder((bytes.size * 4 + 2) / 3)
            var i = 0
            while (i < bytes.size) {
                val b0 = bytes[i].toInt() and 0xff
                val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else 0
                val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else 0
                out.append(ALPHABET[b0 ushr 2])
                out.append(ALPHABET[((b0 and 3) shl 4) or (b1 ushr 4)])
                if (i + 1 < bytes.size) out.append(ALPHABET[((b1 and 15) shl 2) or (b2 ushr 6)])
                if (i + 2 < bytes.size) out.append(ALPHABET[b2 and 63])
                i += 3
            }
            return out.toString()
        }

        fun create(mode: Mode, now: Long, ttlMs: Long = DEFAULT_TTL_MS, shownText: String? = null) =
            RemoteSession(mode, newToken(), now, ttlMs, shownText)
    }
}
