package com.mivisor.app.engine

import com.mivisor.app.data.AceStreamLink
import com.mivisor.app.data.ChannelGroup
import com.mivisor.app.model.Channel
import com.mivisor.app.model.ChannelKind
import com.mivisor.app.player.EngineMode

/**
 * Decide quién reproduce un canal y con qué fuentes. Lógica pura (se prueba sin Android).
 *
 *  - Solo los Ace Stream pasan por el motor interno; HTTP/HLS siguen como hasta ahora.
 *  - Externo: modo "Ace Stream externo", o Automático en un dispositivo sin motor interno.
 *  - El externo como alternativa del Automático se ofrece SOLO tras pulsar Ver/OK (nunca al enfocar).
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

    /** Fuentes que se probarán y la clave con la que se recuerda la última buena. */
    data class Plan(val channelKey: String, val candidates: List<SourceCandidate>, val preferred: String?)

    /**
     * Fuentes de un canal: la pulsada primero (preferida) y después las del MISMO canal (misma variante: "M LaLiga 2"
     * no se mezcla con "M LaLiga 1") en el resto de proveedores. Para un partido, [eventSources] son todos los
     * canales que lo emiten. Sin repetidos por Content ID. Máximo [max] para no eternizar la búsqueda.
     */
    fun plan(clicked: Channel, groups: List<ChannelGroup>, eventSources: List<Channel>? = null, max: Int = 8): Plan {
        val clickedId = AceStreamLink.contentId(clicked.url)
        val group = groups.firstOrNull { g -> g.sources.any { it.channel.id == clicked.id } }
        val variantKey = group?.sources?.firstOrNull { it.channel.id == clicked.id }?.variantKey
        val pool: List<Channel> = when {
            !eventSources.isNullOrEmpty() -> eventSources
            group != null -> group.sources.filter { it.variantKey == variantKey }.map { it.channel }
            else -> emptyList()
        }
        val seen = HashSet<String>()
        val out = ArrayList<SourceCandidate>()
        fun add(c: Channel) {
            if (c.kind != ChannelKind.ACE) return
            val id = AceStreamLink.contentId(c.url) ?: return
            if (seen.add(id.lowercase()) && out.size < max) out += SourceCandidate(id, c.name, c.source)
        }
        add(clicked)
        pool.forEach(::add)
        val key = when {
            group != null -> group.key + "|" + (variantKey ?: "")
            else -> "ch|" + clicked.name.lowercase()
        }
        return Plan(key, out, clickedId)
    }
}
