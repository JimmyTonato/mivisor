package com.mivisor.app.services

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * "¿Se reproduce DE VERDAD?" — distingue entre "el motor está abierto" y "el stream está llegando".
 *
 * Usa la API HTTP oficial del motor (la misma que usan los reproductores):
 *   1. GET /ace/getstream?id=<ContentID>&format=json → playback_url, stat_url, command_url (sesión de reproducción).
 *   2. GET stat_url cada segundo → status (prebuf/dl/check/wait…), peers, speed_down, downloaded, progreso de prebuffer.
 *   3. GET command_url?method=stop al terminar (no se deja una sesión abierta).
 * El Content ID se usa tal cual (nunca se codifica ni se cambia de mayúsculas).
 */

enum class ProbeVerdict {
    /** Llegan datos del stream (status dl con descarga/peers): se puede reproducir. */
    STREAMING,
    /** El motor aceptó el contenido pero solo prebuffer, sin datos aún al terminar el tiempo. */
    PREBUFFERING_ONLY,
    /** El motor responde pero el contenido no arranca (sin peers / sin datos). */
    NO_DATA,
    /** El motor rechazó el Content ID o devolvió un error. */
    ENGINE_ERROR,
    /** No se pudo hablar con el motor (no está listo / no responde en el puerto). */
    ENGINE_UNREACHABLE,
    /**
     * Ace Stream para Android no deja usar su API HTTP (la de los navegadores/reproductores externos) sin Premium:
     * la comprobación no se puede hacer. NO dice nada de si el canal funciona.
     */
    PREMIUM_REQUIRED
}

/** Una lectura de stat_url. */
data class ProbeStat(
    val status: String?,
    val peers: Int,
    val speedDownKbps: Int,
    val downloaded: Long,
    val progress: Int?,
    val error: String?
)

data class ProbeResult(
    val verdict: ProbeVerdict,
    val lastStat: ProbeStat?,
    val seconds: Int,
    val detail: String,
    val isLive: Boolean? = null
)

object AceProbeProtocol {
    fun startUrl(port: Int, contentId: String, pid: String) =
        "http://127.0.0.1:$port/ace/getstream?id=$contentId&format=json&pid=$pid"

    /** Respuesta de getstream: stat_url, command_url, playback_url (o el error del motor). */
    class Session(val statUrl: String, val commandUrl: String?, val playbackUrl: String?, val isLive: Boolean?)

    fun parseStart(body: String?): Pair<Session?, String?> {
        val root = runCatching { JsonParser.parseString(body.orEmpty()).asJsonObject }.getOrNull() ?: return null to "respuesta no válida"
        root.errorText()?.let { return null to it }
        val r = root.get("response")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null to "sin respuesta"
        val stat = r.str("stat_url") ?: return null to "sin stat_url"
        return Session(stat, r.str("command_url"), r.str("playback_url"), r.get("is_live")?.let { runCatching { it.asInt == 1 }.getOrNull() }) to null
    }

    fun parseStat(body: String?): ProbeStat? {
        val root = runCatching { JsonParser.parseString(body.orEmpty()).asJsonObject }.getOrNull() ?: return null
        val err = root.errorText()
        val r = root.get("response")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return ProbeStat(null, 0, 0, 0, null, err ?: "sin datos")
        return ProbeStat(
            status = r.str("status"),
            peers = r.int("peers"),
            speedDownKbps = r.int("speed_down"),
            downloaded = r.long("downloaded"),
            progress = r.get("progress")?.let { runCatching { it.asInt }.getOrNull() }
                ?: r.get("total_progress")?.let { runCatching { it.asInt }.getOrNull() },
            error = err
        )
    }

    /**
     * Decisión sobre las lecturas: STREAMING si hay "dl" con datos (descargado creciendo o velocidad > 0).
     * Nunca se da por bueno solo porque el motor responda.
     */
    fun verdict(stats: List<ProbeStat>): ProbeVerdict {
        if (stats.any { it.error != null && it.status == null }) return ProbeVerdict.ENGINE_ERROR
        val dl = stats.filter { it.status == "dl" }
        val growing = stats.zipWithNext().any { (a, b) -> b.downloaded > a.downloaded }
        if (dl.isNotEmpty() && (dl.any { it.speedDownKbps > 0 } || growing)) return ProbeVerdict.STREAMING
        if (stats.any { it.status == "prebuf" }) return ProbeVerdict.PREBUFFERING_ONLY
        return ProbeVerdict.NO_DATA
    }

    /** "To continue, you need to activate premium" (motor Android sin Premium). */
    fun isPremiumError(error: String?): Boolean = error?.contains("premium", ignoreCase = true) == true

    private fun JsonObject.errorText(): String? =
        get("error")?.takeIf { !it.isJsonNull }?.let { e -> runCatching { e.asString }.getOrElse { e.toString() } }?.takeIf { it.isNotBlank() }

    private fun JsonObject.str(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
    private fun JsonObject.int(k: String) = get(k)?.let { runCatching { it.asInt }.getOrNull() } ?: 0
    private fun JsonObject.long(k: String) = get(k)?.let { runCatching { it.asLong }.getOrNull() } ?: 0L
}

/** Ejecuta la comprobación contra el motor local. [onUpdate] recibe cada lectura (para la UI). */
class AceStreamProbe(
    private val http: suspend (String) -> String? = ::httpGet,
    private val sleep: suspend (Long) -> Unit = { delay(it) }
) {
    suspend fun run(port: Int, contentId: String, maxSeconds: Int = 25, onUpdate: (ProbeStat) -> Unit = {}): ProbeResult {
        val pid = "mivisor-probe-" + System.nanoTime().toString(36)
        val startBody = http(AceProbeProtocol.startUrl(port, contentId, pid))
            ?: return ProbeResult(ProbeVerdict.ENGINE_UNREACHABLE, null, 0, "El motor no responde en el puerto $port.")
        val (session, error) = AceProbeProtocol.parseStart(startBody)
        if (session == null) {
            if (AceProbeProtocol.isPremiumError(error)) return ProbeResult(ProbeVerdict.PREMIUM_REQUIRED, null, 0, PREMIUM_TEXT)
            return ProbeResult(ProbeVerdict.ENGINE_ERROR, null, 0, "El motor rechazó el contenido: ${error ?: "error desconocido"}")
        }
        val stats = ArrayList<ProbeStat>()
        var seconds = 0
        try {
            while (seconds < maxSeconds) {
                sleep(1_000)
                seconds++
                val stat = AceProbeProtocol.parseStat(http(session.statUrl)) ?: continue
                stats += stat
                onUpdate(stat)
                if (AceProbeProtocol.verdict(stats.takeLast(3)) == ProbeVerdict.STREAMING) break
                if (stat.error != null && stat.status == null) break
            }
        } finally {
            session.commandUrl?.let { runCatching { http(it + (if ('?' in it) "&" else "?") + "method=stop") } }
        }
        val verdict = AceProbeProtocol.verdict(stats)
        val last = stats.lastOrNull()
        val detail = when (verdict) {
            ProbeVerdict.STREAMING -> "El stream está llegando (${last?.peers ?: 0} peers, ${last?.speedDownKbps ?: 0} KB/s)."
            ProbeVerdict.PREBUFFERING_ONLY -> "El motor está preparando el contenido (prebuffer ${last?.progress ?: 0} %) pero aún no llegan datos."
            ProbeVerdict.NO_DATA -> "El motor acepta el contenido pero no llegan datos (${last?.peers ?: 0} peers). Puede que la emisión no esté activa ahora."
            ProbeVerdict.ENGINE_ERROR -> "El motor devolvió un error: ${last?.error ?: "desconocido"}"
            ProbeVerdict.ENGINE_UNREACHABLE -> "El motor no responde."
            ProbeVerdict.PREMIUM_REQUIRED -> PREMIUM_TEXT
        }
        return ProbeResult(verdict, last, seconds, detail, session.isLive)
    }

    companion object {
        const val PREMIUM_TEXT = "Esta comprobación usa la API HTTP del motor, que en Ace Stream para Android requiere Premium. " +
            "No es un fallo del canal ni de cómo lo abre MiVisor (MiVisor lo abre con acestream://, que no la usa). " +
            "Mira en la pantalla de Ace: Helping / DL indican si hay conexiones."

        suspend fun httpGet(url: String): String? = withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection).apply {
                    connectTimeout = 2_000
                    readTimeout = 5_000
                    instanceFollowRedirects = false
                }
                if (conn.responseCode != 200) return@withContext null
                conn.inputStream.bufferedReader().use { it.readText().take(64_000) }
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null
            } finally {
                conn?.disconnect()
            }
        }
    }
}
