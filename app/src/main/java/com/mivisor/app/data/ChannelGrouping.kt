package com.mivisor.app.data

import com.mivisor.app.model.Channel
import java.text.Normalizer

/** Calidad deducida del nombre del canal ("DAZN 1 FHD", "M+ LaLiga 1080p", "Eurosport 4K"). */
enum class Quality(val label: String, val rank: Int) {
    UHD("4K", 4), FHD("Full HD", 3), HD("HD", 2), SD("SD", 1)
}

/** Una forma de ver un canal: el enlace concreto de un proveedor. */
class ChannelSource(
    val channel: Channel,
    val providerId: String,
    val providerName: String,
    val quality: Quality?,
    /** Variante a la que pertenece ("DAZN LaLiga 2") y su clave; el nombre original sigue en [channel]. */
    val variantName: String = channel.name,
    val variantKey: String = channel.name.lowercase(),
    val number: Int? = null
)

/** Una variante del canal ("DAZN LaLiga 2") con todas sus fuentes. */
class ChannelVariant(val key: String, val name: String, val number: Int?, val sources: List<ChannelSource>)

/**
 * Canal agrupado: "DAZN LaLiga" con todo lo que coincide con él, venga como venga escrito
 * ("DAZN LaLiga Elcano …889", "ES: DAZN LALIGA New Loop", "DAZN LaLiga 2"…).
 * Dentro, sus [variants] ("DAZN LaLiga", "DAZN LaLiga 2"), cada una con sus fuentes.
 * [sources] nunca está vacío, no repite enlaces y va ordenado por variante y calidad.
 */
class ChannelGroup(
    val key: String,
    val name: String,
    val sources: List<ChannelSource>
) {
    /** Variantes en orden: sin número primero, luego 1, 2, 3…; a igualdad, por orden de aparición. */
    val variants: List<ChannelVariant> by lazy {
        sources.groupBy { it.variantKey }.values.map { list ->
            ChannelVariant(list.first().variantKey, ChannelGrouping.bestDisplay(list.map { it.variantName }), list.first().number, list)
        }.sortedBy { it.number ?: 0 }
    }
    val providers: List<String> get() = sources.map { it.providerName }.distinct()
    val bestQuality: Quality? get() = sources.mapNotNull { it.quality }.maxByOrNull { it.rank }
    val initials: String get() = CatalogBuilder.initialsOf(name)
    val tint: Int get() = (key.hashCode() and 0x7fffffff) % CatalogBuilder.TINTS
    val first: ChannelSource get() = sources.first()

    /** Fuentes de un proveedor concreto (vista "Por proveedor"). */
    fun only(providerId: String): ChannelGroup? =
        sources.filter { it.providerId == providerId }.takeIf { it.isNotEmpty() }?.let { ChannelGroup(key, name, it) }
}

object ChannelGrouping {
    private val QUALITY_WORDS = mapOf(
        "4k" to Quality.UHD, "uhd" to Quality.UHD, "2160p" to Quality.UHD, "2160" to Quality.UHD,
        "fhd" to Quality.FHD, "1080p" to Quality.FHD, "1080" to Quality.FHD, "fullhd" to Quality.FHD, "1080i" to Quality.FHD,
        "hd" to Quality.HD, "720p" to Quality.HD, "720" to Quality.HD,
        "sd" to Quality.SD, "480p" to Quality.SD, "576p" to Quality.SD
    )

    /** Palabras que no cambian qué canal es (calidad, códec, "backup"…); se quitan del nombre visible. */
    private val NOISE = setOf(
        "hevc", "h265", "h264", "x265", "x264", "50fps", "60fps", "hdr", "multi", "multiaudio", "backup", "alt", "op", "opcion",
        "full"
    )

    fun quality(name: String): Quality? {
        val words = words(name)
        if ("full" in words && "hd" in words) return Quality.FHD
        return words.mapNotNull { QUALITY_WORDS[it] }.maxByOrNull { it.rank }
    }

    /**
     * Clave de agrupación: mismas palabras que usa el emparejado con la agenda ([ChannelMatcher.tokens]),
     * es decir sin calidad, alias (M+ = Movistar) y en minúsculas. Los números se conservan:
     * "DAZN 1" y "DAZN 2" son canales distintos.
     */
    fun key(name: String): String = ChannelCanon.parse(name).variantKey.ifEmpty { name.trim().lowercase() }

    /** "DAZN 1 FHD [ES]" → "DAZN 1". Respeta mayúsculas originales. */
    fun cleanName(name: String): String {
        val withoutBrackets = name.replace(Regex("""[\[(][^\])]*[\])]"""), " ")
        val kept = withoutBrackets.split(Regex("""\s+""")).filter { word ->
            val w = simplify(word)
            w.isNotEmpty() && w !in QUALITY_WORDS && w !in NOISE || (w.isEmpty() && word.any { it.isLetterOrDigit() })
        }
        val joined = kept.joinToString(" ").trim().trim('-', '|', '·', ':', '_', '/').trim()
        return joined.ifEmpty { name.trim() }
    }

    /**
     * Agrupa los canales de todos los proveedores por su nombre REAL ([ChannelCanon]): todo lo que coincide con
     * "DAZN LaLiga" va a ese canal, con sus variantes ("DAZN LaLiga 2") dentro. El orden es el de aparición.
     * El mismo Content ID (o enlace) dentro de un canal = una sola fuente (la del primero). Los enlaces no se tocan.
     */
    fun group(providers: List<Triple<String, String, List<Channel>>>): List<ChannelGroup> {
        val providerNames = providers.map { it.second }
        val parsed = ArrayList<Triple<Triple<String, String, Channel>, ChannelCanon.Parsed, Unit>>()
        for ((providerId, providerName, channels) in providers) {
            for (channel in channels) {
                parsed += Triple(Triple(providerId, providerName, channel), ChannelCanon.parse(channel.name, providerNames), Unit)
            }
        }
        // Identidad de la fuente: Content ID normalizado (acestream://ABC…, abc…, …?content_id=abc… = la misma).
        // Dentro de un canal agrupado cada fuente aparece UNA vez; dos canales distintos que comparten Content ID
        // siguen siendo dos canales (no se fusionan), pero apuntan a la misma fuente (ver SourceRegistry).
        val seenInGroup = HashMap<String, HashSet<String>>()
        // El número final solo separa variantes si de verdad hay varias del mismo canal ("DAZN 1", "DAZN 2"…).
        // "Calle 13" o "Antena 3" solos siguen siendo un canal con su nombre completo.
        val variantsPerBase = parsed.groupBy { it.second.baseKey }.mapValues { (_, list) -> list.map { it.second.variantKey }.toSet().size }
        val order = LinkedHashMap<String, MutableList<ChannelSource>>()
        val names = HashMap<String, MutableList<String>>()
        for ((p, canon) in parsed) {
            val (providerId, providerName, channel) = p
            val split = canon.number == null || (variantsPerBase[canon.baseKey] ?: 0) > 1
            val key = if (split) canon.baseKey else canon.variantKey
            if (!seenInGroup.getOrPut(key) { HashSet() }.add(sourceIdentity(channel))) continue
            order.getOrPut(key) { ArrayList() } += ChannelSource(
                channel, providerId, providerName, quality(channel.name), canon.variantName, canon.variantKey, canon.number.takeIf { split }
            )
            names.getOrPut(key) { ArrayList() } += if (split) canon.baseName else canon.variantName
        }
        return order.map { (k, sources) ->
            val variantOrder = sources.map { it.variantKey }.distinct().sortedBy { vk -> sources.first { it.variantKey == vk }.number ?: 0 }
            ChannelGroup(
                k, bestDisplay(names[k].orEmpty()),
                sources.sortedWith(compareBy<ChannelSource> { variantOrder.indexOf(it.variantKey) }.thenByDescending { it.quality?.rank ?: 0 })
            )
        }
    }

    /** Identidad de una fuente: el Content ID normalizado si es Ace Stream; si no, el enlace sin espacios. */
    fun sourceIdentity(channel: Channel): String = AceStreamLink.normalizedId(channel.url)?.let { "ace:$it" } ?: channel.url.trim()

    /** Escritura más repetida; a igualdad, la que tiene más mayúsculas iniciales y luego la más corta. */
    fun bestDisplay(candidates: List<String>): String =
        candidates.groupingBy { it }.eachCount().entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenByDescending { e -> e.key.count { c -> c.isUpperCase() } > 0 && e.key != e.key.uppercase() }
                    .thenBy { it.key.length }
            )
            .firstOrNull()?.key ?: "Canal"

    private fun words(name: String): List<String> =
        Normalizer.normalize(name.lowercase(), Normalizer.Form.NFD).replace(Regex("""\p{Mn}+"""), "")
            .replace(Regex("""[^a-z0-9]+"""), " ").trim().split(' ').filter { it.isNotEmpty() }

    private fun simplify(word: String): String =
        Normalizer.normalize(word.lowercase(), Normalizer.Form.NFD).replace(Regex("""\p{Mn}+"""), "")
            .replace(Regex("""[^a-z0-9]+"""), "")
}
