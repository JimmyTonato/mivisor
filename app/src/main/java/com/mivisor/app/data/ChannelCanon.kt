package com.mivisor.app.data

import com.mivisor.app.data.logos.BuiltinGroups
import java.text.Normalizer

/**
 * Nombre REAL de un canal a partir de lo que trae cada lista, para que todas sus copias vayan juntas:
 *
 *   "DAZN 1 Elcano …398"            → variante "DAZN 1"        · canal "DAZN"
 *   "ES: DAZN LALIGA New Loop"       → variante "DAZN LALIGA"   · canal "DAZN LALIGA"
 *   "DAZN LaLiga 2 Elcano …d8e"      → variante "DAZN LaLiga 2" · canal "DAZN LaLiga"
 *   "Dazn MOTO GP New Era VI"        → variante "Dazn MOTO GP"  · canal "Dazn MOTO GP"   (clave "dazn motogp")
 *   "M+ Hits Spain New Loop"         → variante "M+ Hits"       · canal "M+ Hits"         (clave "movistar hits")
 *   "Movistar Plus --> NEW ERA VI (shickat.me)" → "Movistar Plus"
 *
 * Se quita: lo que va tras "-->", corchetes/paréntesis, fragmentos de ID ("…398", "...63d"), prefijos de país
 * ("ES:"), etiquetas de lista (New Loop, New Era, Spain, shickat…), calidad (HD, FHD, 1080p…), país/versión
 * (DE, Italia, BAR…) y el nombre del proveedor cuando va añadido detrás ("DAZN 1 Elcano").
 * Se unifican escrituras: Moto GP = MotoGP, La Liga = LaLiga, Fórmula 1 = F1, M+ / Movistar Plus+ = Movistar.
 *
 * El número final separa variantes del MISMO canal: "DAZN LaLiga" y "DAZN LaLiga 2" son el canal "DAZN LaLiga".
 * Solo afecta a cómo se agrupan y se nombran en pantalla: el enlace de cada fuente no se toca nunca.
 */
object ChannelCanon {

    class Parsed(
        /** Nombre a mostrar de la variante ("DAZN LaLiga 2"), con las palabras originales. */
        val variantName: String,
        /** Clave de la variante ("dazn laliga 2"): mismas fuentes = misma variante. */
        val variantKey: String,
        /** Nombre a mostrar del canal ("DAZN LaLiga"). */
        val baseName: String,
        /** Clave del canal ("dazn laliga"). */
        val baseKey: String,
        /** Número de la variante (2 en "DAZN LaLiga 2"), o null. */
        val number: Int?
    )

    private val QUALITY = setOf(
        "hd", "fhd", "uhd", "sd", "4k", "2k", "8k", "1080", "1080p", "1080i", "720", "720p", "2160p", "fullhd",
        "hevc", "h265", "h264", "x265", "x264", "50fps", "60fps", "hdr", "multi", "multiaudio", "backup", "alt",
        "op", "opcion", "option", "directo", "live"
    )
    /** Etiquetas de las listas, no del canal. */
    private val LIST_TAGS = setOf("spain", "espana", "es", "esp", "loop", "acestream", "ace", "stream", "eventos")
    /** País / versión: el mismo canal en otra versión → misma variante (el nombre original se ve en cada fuente). */
    private val EDITIONS = setOf(
        "bar", "bares", "deutschland", "germany", "ita", "italia", "italy", "portugal", "france", "mexico", "usa",
        "eng", "ingles", "english", "latam", "belgium", "belgie", "belgique", "nederland", "holland", "polska", "poland",
        "romania", "russia", "turkiye", "turkey", "greece", "hellas", "croatia", "serbia", "srbija", "slovenija",
        "czech", "slovakia", "hungary", "austria", "osterreich", "schweiz", "suisse", "sverige", "norge", "danmark",
        "suomi", "ireland", "brasil", "brazil", "argentina", "chile", "colombia", "peru", "canada", "australia", "arabia"
    )
    /**
     * Códigos de país de 2 letras ("ESPN 2 BE", "DAZN 1 DE"). Solo se quitan si van EN MAYÚSCULAS o al final, porque
     * en minúsculas son palabras ("Canal de Historia", "No te lo pierdas").
     */
    private val COUNTRY_CODES = setOf(
        "de", "it", "uk", "gb", "pt", "fr", "mx", "us", "en", "ar", "co", "be", "nl", "pl", "ro", "ru", "bg", "hr", "rs",
        "si", "ba", "cz", "sk", "hu", "at", "ch", "se", "no", "dk", "fi", "gr", "tr", "cy", "al", "mk", "me", "ua", "ie",
        "br", "cl", "pe", "ca", "au", "ad"
    )
    /** "M LaLiga", "M Deportes": la M es Movistar (M+ con el "+" perdido) si va delante de un canal suyo. */
    private val MOVISTAR_PRODUCTS = setOf(
        "laliga", "deportes", "champions", "vamos", "golf", "ellas", "plus", "estrenos", "documentales", "originales",
        "hits", "accion", "comedia", "drama", "clasicos", "cine", "series", "seriesxtra", "futbol", "f1", "motor",
        "tenis", "baloncesto", "liga", "copa", "toros", "indie", "romance", "western", "musica", "tematico",
        "supercopa", "euroliga", "motogp", "formula", "suscripcion", "ligadecampeones", "cineespanol", "festival"
    )
    private val ROMAN = setOf("i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x")
    private val COUNTRY_PREFIX = Regex("""^\s*(ES|ESP|SPAIN|UK|EN|DE|IT|PT|FR|US|MX|AR)\s*[:|]\s*""", RegexOption.IGNORE_CASE)
    private val ELLIPSIS_TAIL = Regex("""(\.{2,}|…)\s*[^\s]*""")
    private val BRACKETS = Regex("""[\[(][^\])]*[\])]""")
    private val SEPARATORS = Regex("""[\s|/_·,;]+""")

    /** Palabras de marca que nunca se quitan aunque un proveedor se llame igual ("DAZN"). */
    private val BRAND_TOKENS: Set<String> by lazy {
        BuiltinGroups.ALL.flatMap { g -> (g.aliases + g.name).flatMap { norm(it).split(' ') } }.filter { it.isNotEmpty() }.toSet()
    }

    /**
     * [providerNames] = nombres de los proveedores del usuario: si aparecen DETRÁS del nombre del canal
     * ("DAZN 1 Elcano"), son una etiqueta y se quitan.
     */
    fun parse(raw: String, providerNames: Collection<String> = emptyList()): Parsed {
        var text = ChannelNameNormalizer.display(raw)
        for (cut in listOf("-->", "->", "=>", "»")) text = text.substringBefore(cut)
        text = text.replace(BRACKETS, " ").replace(ELLIPSIS_TAIL, " ").replace(COUNTRY_PREFIX, "")

        val providerTokens = providerNames.flatMap { norm(it).split(' ') }.filter { it.length >= 3 && it !in BRAND_TOKENS }.toSet()
        // (palabra original, token normalizado)
        val words = text.split(SEPARATORS).map { it.trim(':', '-', '.', '·', '*', '#', '"', '\'') }.filter { it.isNotEmpty() }
            .map { it to norm(it) }.filter { it.second.isNotEmpty() }.toMutableList()

        splitGlued(words)
        joinAliases(words)
        movistarM(words)

        val kept = ArrayList<Pair<String, String>>()
        var i = 0
        while (i < words.size) {
            val (word, tok) = words[i]
            val next = words.getOrNull(i + 1)?.second
            when {
                tok == "new" && (next == "loop" || next == "era") -> {          // "New Loop", "New Era VI"
                    i += 2
                    if (words.getOrNull(i)?.second in ROMAN) i++
                    continue
                }
                tok in QUALITY || tok in LIST_TAGS || tok.contains("shickat") -> Unit
                tok in EDITIONS && kept.isNotEmpty() -> Unit
                tok in COUNTRY_CODES && kept.isNotEmpty() && (word == word.uppercase() || i == words.lastIndex) -> Unit
                tok in providerTokens && kept.isNotEmpty() -> Unit           // proveedor detrás del nombre
                looksLikeIdFragment(tok) && kept.isNotEmpty() -> Unit
                else -> kept += word to tok
            }
            i++
        }
        if (kept.isEmpty()) {
            val fallback = text.trim().ifEmpty { raw.trim() }
            val key = norm(fallback)
            return Parsed(fallback, key, fallback, key, null)
        }
        // Número final = variante del mismo canal, salvo que lo que queda sea demasiado corto para ser un canal
        // ("La 1" y "La 2" son canales distintos, no variantes de "La").
        val number = kept.last().second
            .takeIf { kept.size > 1 && it.length <= 3 && it.all(Char::isDigit) }
            ?.takeIf { kept.dropLast(1).joinToString("") { p -> p.second }.length >= 3 }
            ?.toIntOrNull()
        val base = if (number != null) kept.dropLast(1) else kept
        return Parsed(
            variantName = kept.joinToString(" ") { it.first },
            variantKey = kept.joinToString(" ") { it.second },
            baseName = base.joinToString(" ") { it.first },
            baseKey = base.joinToString(" ") { it.second },
            number = number
        )
    }

    /** "moto gp" → motogp · "la liga" → laliga · "formula 1" → f1 · "movistar plus+" / "m+" → movistar … */
    private fun joinAliases(words: MutableList<Pair<String, String>>) {
        val pairs = mapOf(
            listOf("moto", "gp") to "motogp", listOf("la", "liga") to "laliga", listOf("formula", "1") to "f1",
            listOf("movistar", "plus") to "movistar", listOf("liga", "campeones") to "champions",
            listOf("champions", "league") to "champions", listOf("premier", "league") to "premier"
        )
        // "Liga de Campeones" → champions (antes de que el "de" se tome por la edición alemana).
        var k = 0
        while (k < words.size - 2) {
            if (words[k].second == "liga" && words[k + 1].second == "de" && words[k + 2].second == "campeones") {
                words[k] = "${words[k].first} ${words[k + 1].first} ${words[k + 2].first}" to "champions"
                words.removeAt(k + 1); words.removeAt(k + 1)
            }
            k++
        }
        var i = 0
        while (i < words.size - 1) {
            val joined = pairs[listOf(words[i].second, words[i + 1].second)]
            if (joined != null) {
                words[i] = "${words[i].first} ${words[i + 1].first}" to joined
                words.removeAt(i + 1)
            } else i++
        }
        for (j in words.indices) if (words[j].second == "formula1") words[j] = words[j].first to "f1"
    }

    /** "Esport3" → Esport 3 · "ESPN2" → ESPN 2 · "Eurosport360" → Eurosport 360 (letras ≥3 + número pegado). */
    private fun splitGlued(words: MutableList<Pair<String, String>>) {
        var i = 0
        while (i < words.size) {
            val (word, tok) = words[i]
            val m = GLUED.matchEntire(tok)
            if (m != null && word.length > m.groupValues[2].length) {
                val digits = m.groupValues[2]
                words[i] = word.dropLast(digits.length) to m.groupValues[1]
                words.add(i + 1, digits to digits)
                i += 2
            } else i++
        }
    }
    private val GLUED = Regex("""([a-z]{3,})(\d{1,3})""")

    /** "M LALIGA" / "M Deportes" → Movistar LaLiga / Movistar Deportes (el nombre visible no cambia). */
    private fun movistarM(words: MutableList<Pair<String, String>>) {
        if (words.size >= 2 && words[0].second == "m" && words[1].second in MOVISTAR_PRODUCTS) {
            words[0] = words[0].first to "movistar"
        }
    }

    /** "63d", "0cb", "d8e", "a1b2c3": fragmento de ID (hexadecimal con letras Y números). "f1" o "4k" no. */
    private fun looksLikeIdFragment(tok: String): Boolean =
        tok.length in 3..12 && tok.all { it in '0'..'9' || it in 'a'..'f' } && tok.any(Char::isDigit) && tok.any(Char::isLetter)

    /** Minúsculas, sin acentos ni símbolos; "m+" / "movistar+" → "movistar". */
    fun norm(text: String): String {
        val lower = Normalizer.normalize(ChannelNameNormalizer.display(text).lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return lower.split(Regex("\\s+")).joinToString(" ") { w ->
            when {
                w in setOf("m+", "movistar+", "m+plus", "movistarplus+", "movistarplus") -> "movistar"
                // "Canal+" ≠ "Canal", "Sport+" ≠ "Sport": el "+" forma parte del nombre.
                w.length > 1 && w.endsWith("+") -> w.replace(Regex("[^\\p{L}\\p{N}]"), "") + "plus"
                else -> w.replace(Regex("[^\\p{L}\\p{N}]"), "")
            }
        }.trim().replace(Regex("\\s+"), " ")
    }
}
