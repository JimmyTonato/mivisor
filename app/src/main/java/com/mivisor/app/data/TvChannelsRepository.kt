package com.mivisor.app.data

import com.google.gson.JsonParser
import com.mivisor.app.model.AgendaChannel
import com.mivisor.app.model.AgendaEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Canales de TV de un partido, pedidos a TheSportsDB SOLO cuando el usuario abre el partido.
 *
 *  - Si el partido ya trae canales en agenda.json → se usan y NO se llama a nadie.
 *  - Si ya se pidieron antes → se usan los guardados (memoria y disco) y NO se llama.
 *  - Si no → 1 llamada a lookuptv.php?id=tvId, se guardan y se muestran.
 *
 * Se aceptan broadcasters de cualquier país: `name` es solo el nombre del canal (lo que usa
 * ChannelMatcher) y el país va en `note`. Mismo canal + mismo país = duplicado.
 * Si TheSportsDB aún no tiene canales, la respuesta vacía se guarda [emptyTtlMs] y luego se reintenta.
 * Un fallo nunca rompe nada: el partido se ve igual, sin canales.
 */
class TvChannelsRepository(
    private val cacheDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val emptyTtlMs: Long = TimeUnit.HOURS.toMillis(2),
    private val maxAgeMs: Long = TimeUnit.DAYS.toMillis(3),
    private val fetch: (url: String) -> String = ::httpGet
) {
    /** [failed] = no se pudo consultar (sin red, límite de peticiones…). */
    class Result(val channels: List<AgendaChannel>, val failed: Boolean = false)

    private class Entry(val channels: List<AgendaChannel>, val fetchedAt: Long)

    private val memory = ConcurrentHashMap<String, Entry>()

    /** Canales ya conocidos sin hacer ninguna llamada (null = habría que pedirlos). */
    fun known(event: AgendaEvent): List<AgendaChannel>? {
        if (event.channels.isNotEmpty()) return event.channels
        val id = event.tvId ?: return emptyList()
        return fresh(memory[id] ?: readDisk(id)?.also { memory[id] = it })?.channels
    }

    suspend fun channelsFor(event: AgendaEvent): Result = withContext(Dispatchers.IO) {
        known(event)?.let { return@withContext Result(it) }
        val id = event.tvId!!
        try {
            val body = fetch("$BASE/$PUBLIC_KEY/lookuptv.php?id=${java.net.URLEncoder.encode(id, "UTF-8")}")
            val channels = parse(body, id)
            val entry = Entry(channels, clock())
            memory[id] = entry
            writeDisk(id, entry)
            Result(channels)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result(emptyList(), failed = true)
        }
    }

    private fun fresh(entry: Entry?): Entry? {
        entry ?: return null
        val age = clock() - entry.fetchedAt
        return when {
            age < 0 || age > maxAgeMs -> null
            entry.channels.isEmpty() && age > emptyTtlMs -> null   // vacío: se vuelve a preguntar más tarde
            else -> entry
        }
    }

    // ------------------------------------------------------------------ disco

    private val dir get() = File(cacheDir, "tv")

    private fun file(id: String) = File(dir, id.filter { it.isLetterOrDigit() || it == '-' } + ".json")

    private fun readDisk(id: String): Entry? = runCatching {
        val f = file(id)
        if (!f.isFile) return null
        val lines = f.readText().split('\n', limit = 2)
        Entry(parse(lines[1], id), lines[0].trim().toLong())
    }.getOrNull()

    private fun writeDisk(id: String, entry: Entry) {
        runCatching {
            dir.mkdirs()
            // Formato propio y mínimo: hora de descarga + lista en el mismo formato de TheSportsDB.
            val rows = entry.channels.joinToString(",") { c ->
                """{"idEvent":${quote(id)},"strChannel":${quote(c.name)},"strCountry":${quote(c.note)}}"""
            }
            val tmp = File(dir, "${file(id).name}.tmp")
            tmp.writeText("${entry.fetchedAt}\n{\"tvevent\":[$rows]}")
            if (!tmp.renameTo(file(id))) { file(id).delete(); tmp.renameTo(file(id)) }
            prune()
        }
    }

    /** Borra lo guardado hace más de [maxAgeMs] (partidos ya pasados). */
    private fun prune() {
        val limit = clock() - maxAgeMs
        dir.listFiles()?.filter { it.lastModified() < limit }?.forEach { it.delete() }
    }

    companion object {
        const val BASE = "https://www.thesportsdb.com/api/v1/json"
        /** Clave gratuita PÚBLICA documentada por TheSportsDB. No es un secreto. */
        const val PUBLIC_KEY = "123"
        private const val MAX_BYTES = 512 * 1024

        /**
         * {"tvevent":[{"strChannel":"DAZN LaLiga","strCountry":"Spain"},…]} → canales de TODOS los
         * países, sin duplicados. {"tvevent":null}, cuerpo vacío o texto de error → lista vacía.
         */
        fun parse(body: String?, idEvent: String? = null): List<AgendaChannel> {
            if (body.isNullOrBlank()) return emptyList()
            val root = runCatching { JsonParser.parseString(body) }.getOrNull()
                ?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
            val rows = root.get("tvevent")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
            val seen = HashSet<String>()
            val out = ArrayList<AgendaChannel>()
            for (el in rows) {
                val o = el?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                fun str(key: String) = o.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
                val event = str("idEvent")
                if (idEvent != null && event != null && event != idEvent) continue
                val name = str("strChannel") ?: continue
                val country = str("strCountry")
                // strLogo: logo del canal en TheSportsDB (solo visual; se usa como última fuente de logos).
                val logo = str("strLogo")?.takeIf { com.mivisor.app.data.logos.LogoCatalog.usable(it) }
                if (seen.add("${norm(name)}|${norm(country.orEmpty())}")) out += AgendaChannel(name, null, country, logo)
            }
            return out
        }

        private fun norm(s: String) =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("\\s+"), " ").trim()

        private fun quote(s: String?): String =
            if (s == null) "null" else com.google.gson.JsonPrimitive(s).toString()

        fun httpGet(url: String): String {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8_000
                connection.readTimeout = 10_000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "MiVisor/4")
                val status = connection.responseCode
                if (status != 200) throw IOException("HTTP $status")
                return connection.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        if (out.size() > MAX_BYTES) throw IOException("Respuesta demasiado grande")
                    }
                    out.toString("UTF-8")
                }
            } finally {
                connection.disconnect()
            }
        }
    }
}
