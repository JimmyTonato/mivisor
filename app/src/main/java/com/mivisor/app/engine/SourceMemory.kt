package com.mivisor.app.engine

import com.google.gson.Gson
import java.io.File

/**
 * Memoria local de fuentes (filesDir/engine/sources.json), sin datos personales:
 *  - por canal: la última fuente que dio vídeo → se prueba primero al volver (caduca a los 3 días);
 *  - por Content ID: último éxito, último fallo (y su tipo) y metadatos verificados que dio el motor
 *    (título, bitrate). Las fuentes que fallaron hace menos de 10 min van al final de la cola.
 *
 * Límite del motor actual: su API no expone el descriptor/transport ni el infohash de un Content ID, y su índice de
 * descriptores verificados vive solo en memoria del proceso. Por eso aquí se guarda lo que la API sí da; el
 * respaldo real ante una caída del catálogo es BEP-9 (activado) y mantener el proceso vivo mientras se usa la app.
 * Los Content IDs no son eternos: nada de esto se da por bueno indefinidamente.
 */
class SourceMemory(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {

    data class ContentInfo(
        var lastSuccess: Long = 0,
        var lastFailure: Long = 0,
        var lastFailureKind: String? = null,
        var title: String? = null,
        var bitrateBps: Long = 0,
        var successes: Int = 0
    )

    private data class Snapshot(
        val good: MutableMap<String, Pair<String, Long>> = LinkedHashMap(),
        val content: MutableMap<String, ContentInfo> = LinkedHashMap()
    )

    private val gson = Gson()
    private var data: Snapshot? = null

    @Synchronized
    fun rememberedGood(channelKey: String): String? {
        val (id, at) = load().good[channelKey] ?: return null
        return id.takeIf { clock() - at < GOOD_TTL_MS }
    }

    @Synchronized
    fun recentlyFailed(ids: Collection<String>): Set<String> = ids.filter { id ->
        val info = load().content[id.lowercase()] ?: return@filter false
        info.lastFailure > info.lastSuccess && clock() - info.lastFailure < FAILED_RECENT_MS
    }.toSet()

    @Synchronized
    fun onSuccess(channelKey: String, contentId: String, status: EngineStatus?) {
        val d = load()
        val id = contentId.lowercase()
        d.good.remove(channelKey)
        d.good[channelKey] = id to clock()
        val info = d.content.getOrPut(id) { ContentInfo() }
        info.lastSuccess = clock()
        info.successes++
        status?.title?.takeIf { it.isNotBlank() }?.let { info.title = it.take(120) }
        status?.bitrateBps?.takeIf { it > 0 }?.let { info.bitrateBps = it }
        trimAndSave(d)
    }

    @Synchronized
    fun onFailure(contentId: String, kind: FailureKind) {
        val d = load()
        val info = d.content.getOrPut(contentId.lowercase()) { ContentInfo() }
        info.lastFailure = clock()
        info.lastFailureKind = kind.name
        trimAndSave(d)
    }

    @Synchronized
    fun info(contentId: String): ContentInfo? = load().content[contentId.lowercase()]?.copy()

    @Synchronized
    fun clear() {
        data = Snapshot()
        runCatching { file.delete() }
    }

    private fun load(): Snapshot {
        data?.let { return it }
        val loaded = runCatching {
            val root = com.google.gson.JsonParser.parseString(file.readText()).asJsonObject
            val good = LinkedHashMap<String, Pair<String, Long>>()
            root.getAsJsonObject("good")?.entrySet()?.forEach { (k, v) ->
                val o = v.asJsonObject
                good[k] = o.get("id").asString to o.get("at").asLong
            }
            val content = LinkedHashMap<String, ContentInfo>()
            root.getAsJsonObject("content")?.entrySet()?.forEach { (k, v) -> content[k] = gson.fromJson(v, ContentInfo::class.java) }
            Snapshot(good, content)
        }.getOrNull() ?: Snapshot()
        data = loaded
        return loaded
    }

    private fun trimAndSave(d: Snapshot) {
        while (d.good.size > MAX_CHANNELS) d.good.remove(d.good.keys.first())
        while (d.content.size > MAX_CONTENT) d.content.remove(d.content.keys.first())
        runCatching {
            val root = com.google.gson.JsonObject()
            root.add("good", com.google.gson.JsonObject().apply {
                d.good.forEach { (k, v) -> add(k, com.google.gson.JsonObject().apply { addProperty("id", v.first); addProperty("at", v.second) }) }
            })
            root.add("content", gson.toJsonTree(d.content))
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    companion object {
        const val GOOD_TTL_MS = 3L * 24 * 60 * 60 * 1000
        const val FAILED_RECENT_MS = 10L * 60 * 1000
        const val MAX_CHANNELS = 300
        const val MAX_CONTENT = 600
    }
}
