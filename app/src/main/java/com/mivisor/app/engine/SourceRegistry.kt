package com.mivisor.app.engine

import com.mivisor.app.data.AceStreamLink
import com.mivisor.app.model.Channel
import com.mivisor.app.model.ChannelKind

/**
 * Registro GLOBAL de fuentes Ace: un Content ID normalizado = UNA fuente ([AceSource]), la referencien uno o varios
 * canales de una o varias listas. Lógica pura.
 *
 *  - Channel → puede tener varias fuentes; AceSource → un Content ID único; [ChannelRef] = la relación entre ambos.
 *  - Dos canales con el mismo Content ID NO se fusionan (conservan nombre, logo y categoría), pero comparten
 *    fuente: misma salud, mismo éxito, no se prueba dos veces en un ciclo.
 *  - Se reconstruye al cargar/importar listas (en memoria, como clave única por Content ID normalizado). La salud se
 *    guarda aparte, por Content ID ([SourceHealth]).
 */
object SourceRegistry {

    data class ChannelRef(val providerId: String, val providerName: String, val channelId: String, val channelName: String)

    data class AceSource(val contentId: String, val refs: List<ChannelRef>) {
        /** Nombres de canal distintos que apuntan a esta fuente. */
        val channelNames: List<String> get() = refs.map { it.channelName.trim() }.distinctBy { it.lowercase() }
    }

    /** Diagnóstico de deduplicación (Ajustes → Diagnóstico del motor). */
    data class Report(
        val references: Int,
        val uniqueContentIds: Int,
        val unified: Int,
        val sameChannelRepeats: Int,
        val sharedByChannels: Int,
        val invalid: Int
    ) {
        fun lines(): List<String> = listOf(
            "Referencias a fuentes Ace: $references",
            "Content IDs únicos: $uniqueContentIds",
            "Duplicados unificados: $unified (de ellos, $sameChannelRepeats repetidos en el mismo canal)",
            "Content IDs compartidos por varios canales: $sharedByChannels",
            "Enlaces Ace no válidos (ignorados): $invalid"
        )
    }

    data class Snapshot(val sources: Map<String, AceSource>, val report: Report) {
        fun of(contentId: String?): AceSource? = AceStreamLink.normalizedId(contentId)?.let { sources[it] }
        /** Fuentes que usan varios canales distintos (para el diagnóstico). */
        fun shared(): List<AceSource> = sources.values.filter { it.channelNames.size > 1 }.sortedByDescending { it.channelNames.size }
    }

    @Volatile var current: Snapshot = build(emptyList())
        private set

    fun update(providers: List<Triple<String, String, List<Channel>>>): Snapshot = build(providers).also { current = it }

    fun build(providers: List<Triple<String, String, List<Channel>>>): Snapshot {
        val map = LinkedHashMap<String, MutableList<ChannelRef>>()
        var refs = 0
        var invalid = 0
        for ((providerId, providerName, channels) in providers) {
            for (c in channels) {
                if (c.kind != ChannelKind.ACE) continue
                val id = AceStreamLink.normalizedId(c.url)
                if (id == null) { invalid++; continue }
                refs++
                map.getOrPut(id) { ArrayList() } += ChannelRef(providerId, providerName, c.id, c.name)
            }
        }
        val sources = map.mapValues { (id, r) -> AceSource(id, r) }
        val sameChannel = sources.values.sumOf { s -> s.refs.size - s.refs.distinctBy { it.channelName.trim().lowercase() }.size }
        val report = Report(
            references = refs,
            uniqueContentIds = sources.size,
            unified = refs - sources.size,
            sameChannelRepeats = sameChannel,
            sharedByChannels = sources.values.count { it.channelNames.size > 1 },
            invalid = invalid
        )
        return Snapshot(sources, report)
    }
}
