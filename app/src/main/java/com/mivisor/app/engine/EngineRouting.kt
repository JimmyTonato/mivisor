package com.mivisor.app.engine

import com.mivisor.app.data.AceStreamLink
import com.mivisor.app.data.ChannelGroup
import com.mivisor.app.data.ChannelGrouping
import com.mivisor.app.data.Quality
import com.mivisor.app.model.Channel
import com.mivisor.app.model.ChannelKind
import com.mivisor.app.player.EngineMode

/**
 * Decide quién reproduce un canal y con qué fuentes. Lógica pura (se prueba sin Android).
 *
 *  - Solo los Ace Stream pasan por el motor interno; HTTP/HLS siguen como hasta ahora.
 *  - Externo: modo "Ace Stream externo", o Automático en un dispositivo sin motor interno.
 *  - El externo como alternativa del Automático se ofrece SOLO tras pulsar Ver/OK (nunca al enfocar).
 *  - Las fuentes van SIEMPRE de mayor a menor resolución (salvo la que el usuario eligió a mano).
 */
object EngineRouting {

    enum class Route { INTERNAL, EXTERNAL, UNSUPPORTED, NOT_ACE }

    fun route(mode: EngineMode, channel: Channel, internalSupported: Boolean): Route = when {
        channel.kind != ChannelKind.ACE || AceStreamLink.contentId(channel.url) == null -> Route.NOT_ACE
        mode == EngineMode.EXTERNAL -> Route.EXTERNAL
        internalSupported -> Route.INTERNAL
        mode == EngineMode.AUTO -> Route.EXTERNAL
        else -> Route.UNSUPPORTED
    }

    /** ¿La vista previa puede usar el motor interno para este canal? (nunca el externo al enfocar). */
    fun previewAllowed(mode: EngineMode, channel: Channel, internalSupported: Boolean): Boolean =
        route(mode, channel, internalSupported) == Route.INTERNAL

    /**
     * Fuentes que se probarán y la clave con la que se recuerda la última buena.
     * [explicit] = el usuario eligió UNA fuente concreta ([preferred]): va la primera.
     */
    data class Plan(val channelKey: String, val name: String, val candidates: List<SourceCandidate>, val preferred: String?, val explicit: Boolean)

    /**
     * Una fuente concreta (pantalla de fuentes, historial, partido): la pulsada primero y después las del MISMO canal
     * (misma variante: "M LaLiga 2" no se mezcla con "M LaLiga 1") en el resto de proveedores, de mayor a menor
     * resolución. Para un partido, [eventSources] son todos los canales que lo emiten. Sin repetidos. Máximo [max].
     */
    fun plan(clicked: Channel, groups: List<ChannelGroup>, eventSources: List<Channel>? = null, max: Int = 8, title: String? = null): Plan {
        val group = groups.firstOrNull { g -> g.sources.any { it.channel.id == clicked.id } }
        val variantKey = group?.sources?.firstOrNull { it.channel.id == clicked.id }?.variantKey
        val pool: List<Channel> = when {
            !eventSources.isNullOrEmpty() -> eventSources
            group != null -> group.sources.filter { it.variantKey == variantKey }.map { it.channel }
            else -> emptyList()
        }
        val key = when {
            group != null -> group.key + "|" + (variantKey ?: "")
            else -> "ch|" + clicked.name.lowercase()
        }
        val clickedCandidate = candidateOf(clicked)
        val others = sortByQuality(pool.mapNotNull(::candidateOf))
        val list = dedupe(listOfNotNull(clickedCandidate) + others).take(max)
        return Plan(key, title ?: clicked.name, list, clickedCandidate?.contentId, explicit = true)
    }

    /**
     * Un canal agrupado entero (tarjeta de Canales / vista previa): su primera variante ("DAZN LaLiga", no la 2),
     * todas sus fuentes de mayor a menor resolución, sin ninguna fijada.
     */
    fun planGroup(group: ChannelGroup, max: Int = 8): Plan {
        val variant = group.variants.firstOrNull()
        val sources = variant?.sources ?: group.sources
        val list = dedupe(sortByQuality(sources.mapNotNull { s -> candidateOf(s.channel, s.quality) })).take(max)
        return Plan(group.key + "|" + (variant?.key ?: sources.first().variantKey), variant?.name ?: group.name, list, null, explicit = false)
    }

    fun candidateOf(c: Channel, known: Quality? = null): SourceCandidate? {
        if (c.kind != ChannelKind.ACE) return null
        if (AceStreamLink.contentId(c.url) == null) return null
        // Identidad = Content ID normalizado (minúsculas): el mismo ID escrito distinto es la misma fuente.
        val id = AceStreamLink.normalizedId(c.url) ?: return null
        val q = known ?: ChannelGrouping.quality(c.name)
        return SourceCandidate(id, c.name, c.source, q?.rank ?: 0, q?.let(::shortLabel))
    }

    fun shortLabel(q: Quality): String = when (q) {
        Quality.UHD -> "4K"
        Quality.FHD -> "FHD"
        Quality.HD -> "HD"
        Quality.SD -> "SD"
    }

    private fun sortByQuality(list: List<SourceCandidate>) = list.withIndex()
        .sortedWith(compareByDescending<IndexedValue<SourceCandidate>> { it.value.quality }.thenBy { it.index })
        .map { it.value }

    /** Segunda defensa (además del registro y de la agrupación): un Content ID una sola vez por búsqueda. */
    private fun dedupe(list: List<SourceCandidate>): List<SourceCandidate> {
        val seen = HashSet<String>()
        return list.filter { seen.add(AceStreamLink.normalizedId(it.contentId) ?: it.contentId.lowercase()) }
    }
}
