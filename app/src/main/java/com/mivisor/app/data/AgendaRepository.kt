package com.mivisor.app.data

import com.mivisor.app.model.Agenda
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Descarga agenda.json (publicado por GitHub Actions) con caché en disco:
 *  - Si la copia tiene menos de [ttlMs] se usa sin red.
 *  - Sin conexión se muestra la última copia guardada (offline = true).
 *  - Usa ETag: si no ha cambiado, el servidor responde 304 y no se descarga nada.
 */
class AgendaRepository(
    private val cacheDir: File,
    private val ttlMs: Long = TimeUnit.MINUTES.toMillis(15),
    private val clock: () -> Long = System::currentTimeMillis,
    private val download: (url: String, etag: String?) -> Download = ::httpGet
) {
    class Result(val agenda: Agenda, val fetchedAt: Long, val offline: Boolean)

    class Download(val status: Int, val body: String?, val etag: String?)

    private val dataFile get() = File(cacheDir, "agenda.json")
    private val metaFile get() = File(cacheDir, "agenda.meta")

    suspend fun load(url: String, force: Boolean = false): Result = withContext(Dispatchers.IO) {
        val cached = readCache()
        if (!force && cached != null && clock() - cached.fetchedAt in 0..ttlMs) {
            return@withContext Result(cached.agenda, cached.fetchedAt, offline = false)
        }
        if (url.isBlank()) {
            cached?.let { return@withContext Result(it.agenda, it.fetchedAt, offline = true) }
            throw InvalidAgendaException("No hay URL de agenda configurada (Ajustes → Agenda)")
        }
        try {
            val dl = download(url, cached?.etag)
            val now = clock()
            when {
                dl.status == 304 && cached != null -> {
                    writeMeta(now, cached.etag)
                    Result(cached.agenda, now, offline = false)
                }
                dl.status == 200 && dl.body != null -> {
                    val agenda = withContext(Dispatchers.Default) { AgendaParser.parse(dl.body) }
                    cacheDir.mkdirs()
                    val tmp = File(cacheDir, "agenda.json.tmp")
                    tmp.writeText(dl.body)
                    if (!tmp.renameTo(dataFile)) { dataFile.delete(); tmp.renameTo(dataFile) }
                    writeMeta(now, dl.etag)
                    Result(agenda, now, offline = false)
                }
                dl.status == 404 -> throw InvalidAgendaException(
                    "Todavía no hay agenda publicada (revisa el workflow «Agenda deportiva» en GitHub)"
                )
                else -> throw IOException("HTTP ${dl.status}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InvalidAgendaException) {
            cached?.let { Result(it.agenda, it.fetchedAt, offline = true) } ?: throw e
        } catch (e: Exception) {
            cached?.let { Result(it.agenda, it.fetchedAt, offline = true) } ?: throw e
        }
    }

    private class Cached(val agenda: Agenda, val fetchedAt: Long, val etag: String?)

    private fun readCache(): Cached? {
        if (!dataFile.isFile) return null
        return runCatching {
            val meta = if (metaFile.isFile) metaFile.readText().split('\n') else emptyList()
            val fetchedAt = meta.getOrNull(0)?.toLongOrNull() ?: dataFile.lastModified()
            val etag = meta.getOrNull(1)?.takeIf { it.isNotBlank() }
            Cached(AgendaParser.parse(dataFile.readText()), fetchedAt, etag)
        }.getOrNull()
    }

    private fun writeMeta(fetchedAt: Long, etag: String?) {
        runCatching {
            cacheDir.mkdirs()
            metaFile.writeText("$fetchedAt\n${etag.orEmpty()}")
        }
    }

    companion object {
        private const val MAX_BYTES = 3 * 1024 * 1024

        fun httpGet(url: String, etag: String?): Download {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8_000
                connection.readTimeout = 12_000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "MiVisor/4")
                if (etag != null) connection.setRequestProperty("If-None-Match", etag)
                val status = connection.responseCode
                if (status != 200) return Download(status, null, null)
                val body = connection.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        if (out.size() > MAX_BYTES) throw IOException("Agenda demasiado grande")
                    }
                    out.toString("UTF-8")
                }
                return Download(200, body, connection.getHeaderField("ETag"))
            } finally {
                connection.disconnect()
            }
        }
    }
}
