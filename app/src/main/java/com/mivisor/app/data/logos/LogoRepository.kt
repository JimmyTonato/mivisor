package com.mivisor.app.data.logos

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mivisor.app.data.AgendaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Caché de logos (en filesDir/logos, no se borra con la caché del sistema):
 *  - channel-logos.json: catálogo publicado por GitHub Actions junto a agenda.json. Se descarga como mucho
 *    UNA VEZ AL DÍA (con ETag: si no cambió, no se descarga). Sin red → la última copia.
 *  - harvested.json: logos que la app encuentra por su cuenta (canales de la agenda con logo de
 *    futbolenlatv y canales de TheSportsDB al abrir un partido). Una vez encontrado, no se vuelve a buscar.
 * Nunca afecta a la reproducción: cualquier fallo deja los canales sin logo y nada más.
 */
class LogoRepository(
    private val dir: File,
    private val ttlMs: Long = TimeUnit.HOURS.toMillis(24),
    private val clock: () -> Long = System::currentTimeMillis,
    private val download: (url: String, etag: String?) -> AgendaRepository.Download = AgendaRepository::httpGet
) {
    private val catalogFile get() = File(dir, "channel-logos.json")
    private val metaFile get() = File(dir, "channel-logos.meta")
    private val harvestFile get() = File(dir, "harvested.json")
    private val harvested = LinkedHashMap<String, LogoEntry>()
    private var harvestLoaded = false

    /** Resolver con lo que haya en disco; descarga el catálogo si toca (como mucho 1 vez/día). */
    suspend fun load(url: String, force: Boolean = false): ChannelLogoResolver = withContext(Dispatchers.IO) {
        var catalog = readCatalog()
        val meta = readMeta()
        val stale = catalog == null || force || clock() - (meta?.first ?: 0L) !in 0..ttlMs
        if (stale && url.isNotBlank()) {
            try {
                val dl = download(url, meta?.second.takeIf { catalog != null })
                when {
                    dl.status == 304 && catalog != null -> writeMeta(clock(), meta?.second)
                    dl.status == 200 && dl.body != null -> {
                        val parsed = LogoCatalog.parse(dl.body)
                        dir.mkdirs()
                        val tmp = File(dir, "channel-logos.json.tmp")
                        tmp.writeText(dl.body)
                        if (!tmp.renameTo(catalogFile)) { catalogFile.delete(); tmp.renameTo(catalogFile) }
                        writeMeta(clock(), dl.etag)
                        catalog = parsed
                    }
                    // 404 (aún no publicado) u otro error: se reintenta mañana, sin insistir.
                    else -> writeMeta(clock(), meta?.second)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Sin red o catálogo inválido: se sigue con lo guardado (o sin logos).
            }
        }
        ChannelLogoResolver(catalog ?: LogoCatalog.EMPTY, harvestedEntries())
    }

    /** Añade logos encontrados en la app. Devuelve true si hay algo nuevo (para recalcular la UI). */
    @Synchronized
    fun harvest(entries: List<LogoEntry>): Boolean {
        ensureHarvestLoaded()
        var changed = false
        for (e in entries) {
            if (!LogoCatalog.usable(e.logo)) continue
            val key = ChannelLogoResolver.channelKey(e.name)
            if (key.isEmpty()) continue
            val old = harvested[key]
            if (old == null || e.source.rank < old.source.rank) {
                harvested[key] = e
                changed = true
            }
        }
        while (harvested.size > MAX_HARVESTED) harvested.remove(harvested.keys.first())
        if (changed) writeHarvest()
        return changed
    }

    @Synchronized
    fun harvestedEntries(): List<LogoEntry> {
        ensureHarvestLoaded()
        return harvested.values.toList()
    }

    // ------------------------------------------------------------------ disco

    private fun readCatalog(): LogoCatalog? =
        if (!catalogFile.isFile) null else runCatching { LogoCatalog.parse(catalogFile.readText()) }.getOrNull()

    private fun readMeta(): Pair<Long, String?>? = runCatching {
        if (!metaFile.isFile) return null
        val lines = metaFile.readText().split('\n')
        (lines.getOrNull(0)?.toLongOrNull() ?: return null) to lines.getOrNull(1)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun writeMeta(at: Long, etag: String?) {
        runCatching { dir.mkdirs(); metaFile.writeText("$at\n${etag.orEmpty()}") }
    }

    private fun ensureHarvestLoaded() {
        if (harvestLoaded) return
        harvestLoaded = true
        runCatching {
            if (!harvestFile.isFile) return
            val arr = JsonParser.parseString(harvestFile.readText()).asJsonObject.getAsJsonArray("channels") ?: return
            for (el in arr) {
                val o = el.asJsonObject
                val name = o.get("name")?.asString ?: continue
                val logo = o.get("logo")?.asString?.takeIf(LogoCatalog::usable) ?: continue
                harvested[ChannelLogoResolver.channelKey(name)] = LogoEntry(name, logo, LogoSource.of(o.get("source")?.asString))
            }
        }
    }

    private fun writeHarvest() {
        runCatching {
            val arr = JsonArray()
            harvested.values.forEach { e ->
                arr.add(JsonObject().apply { addProperty("name", e.name); addProperty("logo", e.logo); addProperty("source", e.source.id) })
            }
            dir.mkdirs()
            harvestFile.writeText(JsonObject().apply { add("channels", arr) }.toString())
        }
    }

    companion object {
        const val MAX_HARVESTED = 600

        /** .../agenda-data/agenda.json → .../agenda-data/channel-logos.json */
        fun urlFor(agendaUrl: String): String {
            val base = agendaUrl.trim().substringBefore('?')
            if (base.isBlank()) return ""
            return if (base.endsWith(".json")) base.substringBeforeLast('/') + "/channel-logos.json"
            else base.trimEnd('/') + "/channel-logos.json"
        }
    }
}
