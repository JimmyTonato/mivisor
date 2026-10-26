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
    val aliases: List<String> = emptyList(),
    /** Tipo según iptv-org ("sports", "movies"…), si se conoce. */
    val category: String? = null
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
                LogoEntry(o.str("name") ?: return@mapNotNull null, logo, LogoSource.of(o.str("source")), o.str("group"), o.strings("aliases"), o.str("category"))
            }.orEmpty()
            // Grupos que el catálogo no trae (versión antigua): los de serie.
            val known = groups.map { it.key }.toSet()
            return LogoCatalog(groups + BuiltinGroups.ALL.filter { it.key !in known }, channels, root.str("updated"))
        }

        /** Imagen de aviso de un servidor (logo borrado) en lugar del logo: Imgur redirige a /removed.png. */
        fun isPlaceholder(finalUrl: String?): Boolean {
            val path = finalUrl?.substringBefore('?')?.lowercase() ?: return false
            return path.endsWith("/removed.png") || path.endsWith("/removed.jpg") || path.contains("/image-not-found")
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
    val groupLogo: String?,
    /** Tipo de canal, para agrupar los que no son de ninguna marca. */
    val category: ChannelCategory? = null
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

    /**
     * Logos de canales NUMERADOS ("DAZN 1"). Nunca valen como logo de grupo: si no, "DAZN MotoGP" (sin logo propio)
     * heredaría el de DAZN 1 y parecería DAZN 1. También filtra catálogos ya descargados con ese fallo.
     */
    private val numberedLogos: Set<String> = (catalog.channels + extra).filter { e -> e.name.any(Char::isDigit) }.map { it.logo }.toSet()

    private val memo = HashMap<String, ResolvedLogo>()

    val groups: List<LogoGroup> get() = catalog.groups
    fun group(key: String?): LogoGroup? = key?.let { groupsByKey[it] }

    /**
     * [name] = nombre del canal tal como llega (puede traer letras decorativas).
     * [listGroup] = grupo en la lista del proveedor ("Movistar Deportes"), solo como pista si el nombre no basta.
     */
    @Synchronized
    fun resolve(name: String, listGroup: String? = null): ResolvedLogo = memo.getOrPut("$name\u0000${listGroup.orEmpty()}") {
        // Variante exacta ("DAZN LaLiga 2") → el canal sin número ("DAZN LaLiga"), sin coger nunca el logo de
        // OTRA variante numerada (DAZN 4 no toma el de DAZN 1).
        val canon = com.mivisor.app.data.ChannelCanon.parse(name)
        val entry = channelIndex[canon.variantKey]
            ?: channelIndex[canon.baseKey]?.takeIf { canon.baseKey != canon.variantKey && it.logo !in numberedLogos }
        val groupKey = groupOf(name) ?: entry?.group?.takeIf { it in groupsByKey } ?: listGroup?.let { groupOf(it) }
        val group = groupsByKey[groupKey]
        val category = ChannelCategory.of(entry?.category) ?: ChannelCategories.guess(canon.variantKey.split(' '))
        ResolvedLogo(entry?.logo, group?.key, group?.name, group?.logo?.takeIf { it !in numberedLogos } ?: flagshipLogo(group), category)
    }

    /** Grupo cuyo alias es el PRINCIPIO del nombre: "DAZN LaLiga" → dazn, "M+ LaLiga" → movistar. */
    fun groupOf(name: String?): String? {
        val tokens = brandTokens(name ?: return null)
        if (tokens.isEmpty()) return null
        return groupAliases.firstOrNull { (alias, _) -> tokens.size >= alias.size && tokens.subList(0, alias.size) == alias }?.second
    }

    /** Si el catálogo no trae logo del grupo: el de un canal que se llame como la MARCA ("DAZN", "Eurosport"), nunca "DAZN 1". */
    private fun flagshipLogo(group: LogoGroup?): String? =
        group?.let { g ->
            (listOf(g.name) + g.aliases).filterNot { n -> n.any(Char::isDigit) }
                .firstNotNullOfOrNull { channelIndex[channelKey(it)]?.logo?.takeIf { logo -> logo !in numberedLogos } }
        }

    companion object {
        /** Igual que MiVisor agrupa canales (sin calidad, con alias M+ = Movistar). */
        fun channelKey(name: String): String = ChannelGrouping.key(name)

        /**
         * Tokens para decidir el grupo: la MISMA lectura de nombres que agrupa los canales ([ChannelCanon]):
         * "M LALIGA" → movistar laliga · "CANAL+ Sport" → canalplus sport · "Eurosport360" → eurosport 360.
         */
        fun brandTokens(name: String): List<String> =
            com.mivisor.app.data.ChannelCanon.parse(name).variantKey.split(' ').filter { it.isNotEmpty() }
    }
}

/** Grupos de serie (sin logos) por si aún no hay catálogo descargado. Deben coincidir con channel_brands.json. */
object BuiltinGroups {
    val ALL = listOf(
        LogoGroup("movistar", "Movistar Plus+", listOf("movistar plus+", "movistar plus", "movistar+", "movistar", "m+", "liga de campeones")),
        LogoGroup("dazn", "DAZN", listOf("dazn")),
        LogoGroup("eurosport", "Eurosport", listOf("eurosport")),
        LogoGroup("laliga", "LaLiga TV", listOf("laliga tv", "laliga hypermotion", "laliga+", "laliga plus", "hypermotion")),
        LogoGroup("tennischannel", "Tennis Channel", listOf("tennis channel")),
        LogoGroup("rtve", "RTVE", listOf("teledeporte", "tdp", "rtve", "la 1", "la 2", "clan", "canal 24 horas", "24h", "tve")),
        LogoGroup("gol", "GOL", listOf("gol play", "gol tv", "gol")),
        LogoGroup("bein", "beIN SPORTS", listOf("bein sports", "bein")),
        LogoGroup("sky", "Sky Sports", listOf("sky sports", "sky sport", "skysp", "sky sp")),
        LogoGroup("espn", "ESPN", listOf("espn")),
        LogoGroup("eleven", "Eleven Sports", listOf("eleven sports", "eleven")),
        LogoGroup("tnt", "TNT Sports", listOf("tnt sports", "tnt sport", "bt sport")),
        LogoGroup("redbull", "Red Bull TV", listOf("red bull tv", "redbull tv")),
        LogoGroup("fox", "FOX Sports", listOf("fox sports", "fox sport", "fox deportes", "fox premium", "fox")),
        LogoGroup("canalplus", "CANAL+", listOf("canal+", "canal plus")),
        LogoGroup("polsat", "Polsat Sport", listOf("polsat")),
        LogoGroup("arena", "Arena Sport", listOf("arena sport")),
        LogoGroup("sportklub", "Sport Klub", listOf("sport klub", "sportklub")),
        LogoGroup("tvp", "TVP", listOf("tvp")),
        LogoGroup("viasat", "Viasat", listOf("viasat")),
        LogoGroup("setanta", "Setanta Sports", listOf("setanta")),
        LogoGroup("ziggo", "Ziggo Sport", listOf("ziggo")),
        LogoGroup("magenta", "MagentaSport", listOf("magenta sport", "magentasport", "magenta")),
        LogoGroup("rtl", "RTL", listOf("rtl", "super rtl", "rtl+", "rtl plus")),
        LogoGroup("orf", "ORF", listOf("orf")),
        LogoGroup("ardzdf", "ARD / ZDF", listOf("zdf", "ard", "das erste")),
        LogoGroup("prosieben", "ProSiebenSat.1", listOf("prosieben", "pro7", "sat 1", "sat1", "kabel eins", "sixx")),
        LogoGroup("servus", "ServusTV", listOf("servus tv", "servustv")),
        LogoGroup("match", "Match!", listOf("match")),
        LogoGroup("premiersports", "Premier Sports", listOf("premier sports", "premier sport")),
        LogoGroup("nba", "NBA TV", listOf("nba")),
        LogoGroup("ufc", "UFC", listOf("ufc")),
        LogoGroup("realmadrid", "Real Madrid TV", listOf("real madrid")),
        LogoGroup("rfef", "RFEF", listOf("rfef", "1 federacion", "primera federacion")),
        LogoGroup("atresmedia", "Atresmedia", listOf("antena 3", "lasexta", "la sexta", "neox", "atreseries")),
        LogoGroup("mediaset", "Mediaset España", listOf("telecinco", "cuatro", "fdf", "energy", "divinity", "boing", "be mad", "bemad")),
        LogoGroup("amc", "AMC Networks", listOf("amc", "canal cocina", "caza y pesca", "dark", "odisea", "canal hollywood", "xtrm", "sol musica", "decasa", "somos")),
        LogoGroup("axn", "AXN", listOf("axn")),
        LogoGroup("paramount", "Paramount", listOf("comedy central", "nickelodeon", "nick jr", "paramount", "mtv")),
        LogoGroup("disney", "Disney", listOf("disney", "national geographic", "nat geo")),
        LogoGroup("wbd", "Warner Bros. Discovery", listOf("discovery", "dmax", "cartoon network", "boomerang", "tlc")),
        LogoGroup("tresc", "3Cat", listOf("esport", "esport3", "tv3", "3cat", "super3")),
        LogoGroup("golfchannel", "Golf Channel", listOf("golf channel", "golf tv"))
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
        // Sin marca: por tipo (Deportes, Cine y series…) y lo que no se reconoce, al final en "Otros canales".
        val noBrand = byKey[null].orEmpty()
        val byCategory = noBrand.groupBy { it.second.category }
        val categories = ChannelCategory.values().mapNotNull { c ->
            byCategory[c]?.let { LogoSection("cat:${c.id}", c.label, ResolvedLogo.NONE, it) }
        }
        val others = byCategory[null]?.let { listOf(LogoSection(null, OTHERS, ResolvedLogo.NONE, it)) }.orEmpty()
        return known + categories + others
    }
}
