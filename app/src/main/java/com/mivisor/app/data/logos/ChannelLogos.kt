package com.mivisor.app.data.logos

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mivisor.app.data.ChannelGrouping
import com.mivisor.app.data.ChannelNameNormalizer
import java.text.Normalizer

/**
 * Logos de canales: SOLO metadatos visuales. Nunca se tocan los enlaces ni la reproducción; si no hay logo,
 * el canal funciona exactamente igual (se pinta el icono genérico).
 *
 * Dos niveles separados:
 *   grupo  (Movistar, DAZN, Eurosport…)  → logo general del proveedor/marca
 *   canal  (M+ LaLiga, DAZN F1…)          → logo específico
 * Prioridad para cada canal: logo del canal → logo de su grupo → icono genérico de TV.
 *
 * Fuentes por prioridad: manual (logos.json) → futbolenlatv.es/deporte → iptv-org → TheSportsDB.
 * Todo se busca con el nombre NORMALIZADO ([ChannelNameNormalizer] + alias), nunca con el texto decorativo.
 */
enum class LogoSource(val id: String, val rank: Int) {
    MANUAL("manual", 0), FUTBOLENLATV("futbolenlatv", 1), IPTV_ORG("iptv-org", 2), THESPORTSDB("thesportsdb", 3);

    companion object {
        fun of(id: String?): LogoSource = values().firstOrNull { it.id == id } ?: THESPORTSDB
    }
}

data class LogoEntry(
    val name: String,
    val logo: String,
    val source: LogoSource,
    val group: String? = null,
    val aliases: List<String> = emptyList()
)

data class LogoGroup(val key: String, val name: String, val aliases: List<String>, val logo: String? = null)

class LogoCatalog(val groups: List<LogoGroup>, val channels: List<LogoEntry>, val updated: String? = null) {
    companion object {
        val EMPTY = LogoCatalog(BuiltinGroups.ALL, emptyList())

        /** channel-logos.json (versión 1). Entradas sin logo https o con SVG se descartan. */
        fun parse(body: String): LogoCatalog {
            val root = JsonParser.parseString(body).asJsonObject
            require((root.get("version")?.asInt ?: 0) == 1) { "versión de catálogo no soportada" }
            val groups = root.getAsJsonObject("groups")?.entrySet()?.mapNotNull { (key, el) ->
                val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                LogoGroup(key, o.str("name") ?: key, o.strings("aliases"), o.str("logo")?.takeIf(::usable))
            }.orEmpty()
            val channels = root.getAsJsonObject("channels")?.entrySet()?.mapNotNull { (_, el) ->
                val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val logo = o.str("logo")?.takeIf(::usable) ?: return@mapNotNull null
                LogoEntry(o.str("name") ?: return@mapNotNull null, logo, LogoSource.of(o.str("source")), o.str("group"), o.strings("aliases"))
            }.orEmpty()
            // Grupos que el catálogo no trae (versión antigua): los de serie.
            val known = groups.map { it.key }.toSet()
            return LogoCatalog(groups + BuiltinGroups.ALL.filter { it.key !in known }, channels, root.str("updated"))
        }

        /** Solo imágenes https que el cargador sabe dibujar (no SVG). */
        fun usable(url: String?): Boolean =
            url != null && url.startsWith("https://") && !url.substringBefore('?').lowercase().endsWith(".svg")

        private fun JsonObject.str(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
        private fun JsonObject.strings(k: String) =
            get(k)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() } }.orEmpty()
    }
}

/** Resultado para pintar: logo del canal, su grupo y el logo del grupo (cualquiera puede faltar). */
data class ResolvedLogo(
    val channelLogo: String?,
    val groupKey: String?,
    val groupName: String?,
    val groupLogo: String?
) {
    /** Lo que se pinta en el canal: su logo o, si no tiene, el del grupo. */
    val best: String? get() = channelLogo ?: groupLogo

    companion object {
        val NONE = ResolvedLogo(null, null, null, null)
    }
}

/**
 * Normaliza → decide el grupo → busca el logo del canal → el del grupo. Resultados memorizados.
 * [extra] = logos recogidos en la propia app (agenda/futbolenlatv y TheSportsDB), ya guardados.
 */
class ChannelLogoResolver(private val catalog: LogoCatalog, extra: List<LogoEntry> = emptyList()) {

    private val groupsByKey = catalog.groups.associateBy { it.key }
    /** Alias de grupo en tokens, del más largo al más corto (gana el alias más largo). */
    private val groupAliases: List<Pair<List<String>, String>> = catalog.groups
        .flatMap { g -> (g.aliases + g.name).map { brandTokens(it) to g.key } }
        .filter { it.first.isNotEmpty() }
        .sortedByDescending { it.first.joinToString(" ").length }

    /** Clave canónica del canal → mejor entrada (por prioridad de fuente). */
    private val channelIndex: Map<String, LogoEntry> = HashMap<String, LogoEntry>().also { index ->
        for (e in catalog.channels + extra) {
            for (n in (listOf(e.name) + e.aliases)) {
                val k = channelKey(n)
                if (k.isEmpty()) continue
                val old = index[k]
                if (old == null || e.source.rank < old.source.rank) index[k] = e
            }
        }
    }

    private val memo = HashMap<String, ResolvedLogo>()

    val groups: List<LogoGroup> get() = catalog.groups
    fun group(key: String?): LogoGroup? = key?.let { groupsByKey[it] }

    /**
     * [name] = nombre del canal tal como llega (puede traer letras decorativas).
     * [listGroup] = grupo en la lista del proveedor ("Movistar Deportes"), solo como pista si el nombre no basta.
     */
    @Synchronized
    fun resolve(name: String, listGroup: String? = null): ResolvedLogo = memo.getOrPut("$name\u0000${listGroup.orEmpty()}") {
        val entry = channelIndex[channelKey(name)]
        val groupKey = groupOf(name) ?: entry?.group?.takeIf { it in groupsByKey } ?: listGroup?.let { groupOf(it) }
        val group = groupsByKey[groupKey]
        ResolvedLogo(entry?.logo, group?.key, group?.name, group?.logo ?: flagshipLogo(group))
    }

    /** Grupo cuyo alias es el PRINCIPIO del nombre: "DAZN LaLiga" → dazn, "M+ LaLiga" → movistar. */
    fun groupOf(name: String?): String? {
        val tokens = brandTokens(name ?: return null)
        if (tokens.isEmpty()) return null
        return groupAliases.firstOrNull { (alias, _) -> tokens.size >= alias.size && tokens.subList(0, alias.size) == alias }?.second
    }

    /** Si el catálogo no trae logo del grupo: el de un canal que se llame como el grupo ("DAZN", "Eurosport"). */
    private fun flagshipLogo(group: LogoGroup?): String? =
        group?.let { g -> (listOf(g.name) + g.aliases).firstNotNullOfOrNull { channelIndex[channelKey(it)]?.logo } }

    companion object {
        /** Igual que MiVisor agrupa canales (sin calidad, con alias M+ = Movistar). */
        fun channelKey(name: String): String = ChannelGrouping.key(name)

        private val BRAND_ALIASES = listOf(
            "movistar plus+" to "movistar ", "movistar plus" to "movistar ", "movistar+" to "movistar ",
            "m+" to "movistar ", "la liga" to "laliga", "laliga+" to "laliga plus"
        )

        /** Tokens para decidir el grupo (mismas reglas que tools/agenda/channel_logos.py → norm_text). */
        fun brandTokens(name: String): List<String> {
            var text = Normalizer.normalize(ChannelNameNormalizer.display(name).lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
            BRAND_ALIASES.forEach { (from, to) -> text = text.replace(from, to) }
            return text.replace(Regex("[^a-z0-9]+"), " ").trim().split(' ').filter { it.isNotEmpty() }
        }
    }
}

/** Grupos de serie (sin logos) por si aún no hay catálogo descargado. Deben coincidir con channel_brands.json. */
object BuiltinGroups {
    val ALL = listOf(
        LogoGroup("movistar", "Movistar Plus+", listOf("movistar plus+", "movistar plus", "movistar+", "movistar", "m+")),
        LogoGroup("dazn", "DAZN", listOf("dazn")),
        LogoGroup("eurosport", "Eurosport", listOf("eurosport")),
        LogoGroup("laliga", "LaLiga TV", listOf("laliga tv", "laliga hypermotion", "laliga+", "laliga plus", "hypermotion")),
        LogoGroup("tennischannel", "Tennis Channel", listOf("tennis channel")),
        LogoGroup("rtve", "RTVE", listOf("teledeporte", "tdp", "rtve", "la 1", "la 2")),
        LogoGroup("gol", "GOL", listOf("gol play", "gol tv", "gol")),
        LogoGroup("bein", "beIN SPORTS", listOf("bein sports", "bein")),
        LogoGroup("sky", "Sky Sports", listOf("sky sports", "sky sport")),
        LogoGroup("espn", "ESPN", listOf("espn")),
        LogoGroup("eleven", "Eleven Sports", listOf("eleven sports", "eleven")),
        LogoGroup("tnt", "TNT Sports", listOf("tnt sports", "tnt sport")),
        LogoGroup("redbull", "Red Bull TV", listOf("red bull tv", "redbull tv"))
    )
}

/** Una sección de Canales: cabecera del grupo (logo general) y sus canales (cada uno con su logo). */
class LogoSection<T>(
    val key: String?,
    val name: String,
    val logo: ResolvedLogo,
    val items: List<Pair<T, ResolvedLogo>>
)

object LogoSections {
    const val OTHERS = "Otros canales"

    /**
     * Agrupa por grupo/proveedor (Movistar, DAZN…) conservando el orden de los canales; los grupos salen en el
     * orden en que aparece su primer canal y los canales sin grupo van al final, en "Otros canales".
     * [name] y [listGroup] dicen cómo se llama cada elemento y su grupo en la lista del proveedor.
     */
    fun <T> build(items: List<T>, resolver: ChannelLogoResolver, name: (T) -> String, listGroup: (T) -> String?): List<LogoSection<T>> {
        val byKey = LinkedHashMap<String?, MutableList<Pair<T, ResolvedLogo>>>()
        for (item in items) {
            val logo = resolver.resolve(name(item), listGroup(item))
            byKey.getOrPut(logo.groupKey) { ArrayList() } += item to logo
        }
        val known = byKey.filterKeys { it != null }.map { (key, list) ->
            val first = list.first().second
            LogoSection(key, first.groupName ?: resolver.group(key)?.name ?: key!!, ResolvedLogo(null, key, first.groupName, first.groupLogo), list)
        }
        val others = byKey[null]?.let { listOf(LogoSection(null, OTHERS, ResolvedLogo.NONE, it)) }.orEmpty()
        return known + others
    }
}
