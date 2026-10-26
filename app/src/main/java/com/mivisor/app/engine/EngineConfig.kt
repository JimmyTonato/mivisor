package com.mivisor.app.engine

/**
 * Entorno del proceso del motor P2P integrado (outpace, AGPL-3.0-or-later). Lógica pura y probada.
 *
 * Cliente de reproducción, no nodo de la red:
 *  - HTTP solo en 127.0.0.1 (puerto preferido 16900, o uno libre si está ocupado por otra app).
 *  - Sin listener de peers entrantes, sin seeding y sin mapeo de puertos (UPnP/NAT-PMP): no son necesarios para
 *    ver un canal y eran la fuente de colisiones de puertos en la POC. RTMP aislado en un puerto efímero.
 *  - Sin rutas compatibles con Ace (las de /ace): MiVisor usa la API nativa.
 *  - Resolución de Content ID por el catálogo y, si falla, BEP-9 (comprueba SHA-1 del descriptor).
 *
 * Memoria acotada (pensado para Fire TV / Android TV de ~1 GB): prebuffer del motor corto, cola de arranque con
 * tope en bytes, piezas por delante limitadas y caché de piezas pequeña y en RAM. En low-RAM, límites más bajos.
 * No se arreglan cortes subiendo peers/buffer/RAM: más peers no es más calidad.
 */
data class EngineConfig(
    val bindPort: Int,
    val dataDir: String,
    val homeDir: String,
    val lowRam: Boolean
) {
    fun environment(): Map<String, String> {
        val limits = if (lowRam) LOW_RAM else NORMAL
        return linkedMapOf(
            "OUTPACE_BIND" to "$HOST:$bindPort",
            "OUTPACE_DATA_DIR" to dataDir,
            "HOME" to homeDir,
            // Servicios auxiliares: aislados o apagados.
            "OUTPACE_RTMP_BIND" to "$HOST:0",
            "OUTPACE_PEER_LISTEN" to "$HOST:0",
            "OUTPACE_ENABLE_INBOUND" to "0",
            "OUTPACE_ENABLE_SEEDING" to "0",
            "OUTPACE_ENABLE_PORT_MAPPING" to "0",
            "OUTPACE_PORT_MAP_BACKEND" to "none",
            "OUTPACE_ACE_COMPAT" to "0",
            // Resolución: catálogo primero; BEP-9 solo como respaldo verificado.
            "OUTPACE_BEP9_CONTENT_ID_FALLBACK" to "1",
            // Arranque rápido: el motor guarda poco; el colchón adaptativo lo lleva el reproductor.
            "OUTPACE_PREBUFFER_MS" to "2000",
            "OUTPACE_PREBUFFER_TIMEOUT_MS" to "5000",
            "OUTPACE_PREBUFFER_BYTES" to limits.prebufferBytes.toString(),
            // Recuperación: re-pedir piezas pronto y cambiar de upstream antes de quedarse sin datos.
            "OUTPACE_REQUEST_TIMEOUT_MS" to "1500",
            "OUTPACE_REQUEST_CHECK_INTERVAL_MS" to "750",
            "OUTPACE_STALE_UPSTREAM_TIMEOUT_MS" to "12000",
            "OUTPACE_MAX_ACTIVE_UPSTREAMS" to limits.upstreams.toString(),
            "OUTPACE_MAX_PARALLEL_CONNECT" to limits.parallelConnect.toString(),
            // Memoria: piezas por delante y caché de piezas acotadas (las piezas pueden ser de hasta 1 MiB).
            "OUTPACE_MAX_PIECE_ADVANCE" to limits.pieceAdvance.toString(),
            "OUTPACE_MAX_REASM_PIECES_AHEAD" to limits.reasmAhead.toString(),
            "OUTPACE_SESSION_BUFFER" to limits.sessionBuffer.toString(),
            "OUTPACE_CACHE_TYPE" to "memory",
            "OUTPACE_SEED_STORE_BYTES" to limits.seedStoreBytes.toString(),
            "OUTPACE_SEED_RETENTION_SECS" to "20",
            "OUTPACE_SEED_TTL_SECS" to "60"
        )
    }

    /** Comprueba las reglas que el propio motor exige al arrancar (si no, ni siquiera se lanza). */
    fun validate(): List<String> {
        val env = environment()
        fun n(k: String) = env.getValue(k).toLong()
        return buildList {
            if (n("OUTPACE_REQUEST_TIMEOUT_MS") >= n("OUTPACE_STALE_UPSTREAM_TIMEOUT_MS")) add("REQUEST_TIMEOUT debe ser < STALE_UPSTREAM_TIMEOUT")
            if (n("OUTPACE_REQUEST_CHECK_INTERVAL_MS") > n("OUTPACE_REQUEST_TIMEOUT_MS")) add("REQUEST_CHECK_INTERVAL debe ser <= REQUEST_TIMEOUT")
            if (n("OUTPACE_MAX_REASM_PIECES_AHEAD") < n("OUTPACE_MAX_PIECE_ADVANCE")) add("MAX_REASM_PIECES_AHEAD debe ser >= MAX_PIECE_ADVANCE")
            if (n("OUTPACE_SEED_STORE_BYTES") < MAX_PIECE_BYTES) add("SEED_STORE_BYTES debe admitir al menos una pieza de 1 MiB")
            if (bindPort !in 1..65535) add("puerto fuera de rango")
        }
    }

    private class Limits(
        val upstreams: Int, val parallelConnect: Int, val pieceAdvance: Int, val reasmAhead: Int,
        val sessionBuffer: Int, val prebufferBytes: Long, val seedStoreBytes: Long
    )

    companion object {
        const val HOST = "127.0.0.1"
        const val PREFERRED_PORT = 16900
        const val BINARY_NAME = "liboutpace.so"
        private const val MAX_PIECE_BYTES = 1L shl 20

        private val NORMAL = Limits(
            upstreams = 4, parallelConnect = 12, pieceAdvance = 64, reasmAhead = 128,
            sessionBuffer = 256, prebufferBytes = 24L shl 20, seedStoreBytes = 24L shl 20
        )
        private val LOW_RAM = Limits(
            upstreams = 3, parallelConnect = 8, pieceAdvance = 32, reasmAhead = 64,
            sessionBuffer = 128, prebufferBytes = 12L shl 20, seedStoreBytes = 12L shl 20
        )
    }
}
