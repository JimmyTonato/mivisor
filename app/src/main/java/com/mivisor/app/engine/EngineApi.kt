package com.mivisor.app.engine

import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/**
 * API nativa del motor (solo 127.0.0.1). Cada Content ID es una sesión compartida: TS, estado y DELETE usan la
 * misma clave "cid:<id>". Nada de esto sale del dispositivo.
 */
object EngineApi {
    /** Sesión de reproducción MPEG-TS continua (la que consume Media3). */
    fun streamUrl(baseUrl: String, contentId: String): String = "$baseUrl/streams/ace/${key(contentId)}.ts"

    /**
     * La misma sesión como HLS en directo (lista de segmentos cortados en keyframe + ventana deslizante). Es la
     * entrega por defecto: permite volver cerca del borde en directo saltando a un segmento limpio.
     */
    fun hlsUrl(baseUrl: String, contentId: String): String = "$baseUrl/streams/ace/${key(contentId)}.m3u8"

    /** Estado de la sesión; null si aún no existe (antes de resolver) o ya terminó. */
    fun status(baseUrl: String, contentId: String): EngineStatus? {
        val (code, body) = request("GET", "$baseUrl/streams/ace/${key(contentId)}/status", 800)
        return if (code == 200) EngineStatus.parse(body) else null
    }

    /** Cierra la sesión en el motor (antes de probar otra fuente o al salir). 404 = ya no existía. */
    fun stop(baseUrl: String, contentId: String): Boolean = request("DELETE", "$baseUrl/streams/ace/${key(contentId)}", 1_500).first in listOf(204, 404)

    fun key(contentId: String) = "cid:" + contentId.trim().lowercase()

    private fun request(method: String, url: String, timeoutMs: Int): Pair<Int, String?> {
        var c: HttpURLConnection? = null
        return try {
            c = URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            val code = c.responseCode
            code to (if (code == 200) c.inputStream.bufferedReader().use { it.readText().take(64_000) } else null)
        } catch (e: Exception) {
            -1 to null
        } finally {
            c?.disconnect()
        }
    }
}
