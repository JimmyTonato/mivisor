package com.mivisor.app.data

/**
 * Cuándo volver a descargar los canales de un proveedor sin que el usuario lo pida:
 *  - al abrir MiVisor, al volver a ella o al entrar en Canales;
 *  - solo si la copia tiene más de [STALE_MS] (la caché evita descargas innecesarias);
 *  - y como mucho una comprobación cada [MIN_INTERVAL_MS] por proveedor (entrar y salir no repite descargas).
 * Si la descarga falla se conservan los últimos datos válidos (la lista nunca se vacía por un fallo de red).
 */
object RefreshPolicy {
    const val STALE_MS = 10 * 60_000L
    const val MIN_INTERVAL_MS = 2 * 60_000L

    fun shouldCheck(updatedAt: Long, lastCheck: Long, now: Long, loading: Boolean): Boolean {
        if (loading) return false
        if (lastCheck > 0 && now - lastCheck < MIN_INTERVAL_MS) return false
        return updatedAt <= 0L || now - updatedAt > STALE_MS
    }
}
