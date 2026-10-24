package com.mivisor.app.data

import com.mivisor.app.model.Channel
import java.text.Normalizer

/**
 * Relaciona los canales de la agenda ("DAZN LaLiga", "M+ LaLiga TV") con los canales
 * de TUS categorías ("DAZN LALIGA FHD", "Movistar LaLiga 1080"), para poder ofrecer
 * "Abrir canal" directamente desde un partido.
 *
 * Reglas (conservadoras: mejor no enlazar que enlazar mal):
 *  - Se normaliza: minúsculas, sin acentos ni símbolos, alias (M+ → movistar),
 *    y se quitan etiquetas de calidad (HD, FHD, 4K, 1080p…).
 *  - Coincidencia exacta tras normalizar: puntuación máxima.
 *  - Tus canales pueden tener palabras EXTRA sin números ("dazn laliga multi" ⊇ "dazn laliga"),
 *    pero no números distintos: "DAZN LaLiga" no enlaza con "DAZN LaLiga 2".
 */
class ChannelMatcher(channels: List<Channel>) {

    private class Entry(val tokens: List<String>, val channel: Channel)

    private val entries: List<Entry> = channels.mapNotNull { ch ->
        tokens(ch.name).takeIf { it.isNotEmpty() }?.let { Entry(it, ch) }
    }

    val isEmpty: Boolean get() = entries.isEmpty()

    /**
     * Todas tus versiones de [agendaName] (de cualquier código/categoría), mejores primero.
     * Mismo enlace repetido en varios códigos = una sola opción.
     */
    fun match(agendaName: String, limit: Int = 12): List<Channel> {
        val wanted = tokens(agendaName)
        if (wanted.isEmpty()) return emptyList()
        return entries.asSequence()
            .mapNotNull { e -> score(wanted, e.tokens)?.let { it to e.channel } }
            .sortedByDescending { it.first }
            .distinctBy { it.second.url }
            .take(limit)
            .map { it.second }
            .toList()
    }

    private fun score(wanted: List<String>, have: List<String>): Int? {
        if (have == wanted) return 100
        val wantedSet = wanted.toSet()
        // Todas las palabras buscadas, en cualquier orden ("LaLiga DAZN" = "DAZN LaLiga")…
        if (!have.toSet().containsAll(wantedSet)) return null
        // …y lo que sobra sin números: "DAZN LaLiga 2" no es "DAZN LaLiga".
        val extra = have.filter { it !in wantedSet }
        if (extra.any { t -> t.any(Char::isDigit) }) return null
        // Un nombre de una sola palabra genérica ("dazn") no debe enlazar con todo.
        if (wantedSet.size == 1 && extra.isNotEmpty()) return null
        val sameOrder = have.filter { it in wantedSet } == wanted
        return (if (sameOrder) 90 else 85) - extra.size
    }

    companion object {
        private val QUALITY = setOf(
            "hd", "fhd", "uhd", "sd", "4k", "2k", "8k", "1080", "1080p", "720", "720p", "2160p",
            "hevc", "h265", "x265", "50fps", "60fps", "hdr", "multi", "multiaudio", "backup", "alt", "es", "spain", "tv", "canal"
        )
        private val ALIASES = listOf(
            "movistar plus+" to "movistar",
            "movistar plus" to "movistar",
            "movistar+" to "movistar",
            "m+" to "movistar",
            "la liga" to "laliga",
            "liga de campeones" to "champions",
            "champions league" to "champions",
            "#vamos" to "vamos",
            "teledeporte" to "tdp",
            "eurosport 1" to "eurosport1",
            "eurosport 2" to "eurosport2",
            "dazn f1" to "daznf1"
        )

        fun tokens(name: String): List<String> {
            var text = Normalizer.normalize(name.lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
            ALIASES.forEach { (from, to) -> text = text.replace(from, " $to ") }
            return text.replace(Regex("[^a-z0-9]+"), " ")
                .split(' ')
                .filter { it.isNotEmpty() && it !in QUALITY }
        }
    }
}
