package com.mivisor.app

import com.mivisor.app.data.ChannelCanon
import com.mivisor.app.data.ChannelGrouping
import com.mivisor.app.data.logos.ChannelLogoResolver
import com.mivisor.app.data.logos.LogoCatalog
import com.mivisor.app.model.Channel
import com.mivisor.app.model.ChannelKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nombres reales de las listas (captura de Canales): todo lo que coincide con un canal va a ese canal. */
class ChannelCanonTest {
    private val providers = listOf("SHICKAT", "ELCANO", "MAMANDURRIAS", "ICASTRESANA", "EUPHORBIA", "NANOBOX", "NANOSTREAM", "MATTEO", "ORDOÑEZ")
    private fun p(name: String) = ChannelCanon.parse(name, providers)

    @Test fun listTagsProviderAndIdFragmentsAreRemoved() {
        assertEquals("dazn 1", p("DAZN 1 Elcano ...398").variantKey)
        assertEquals("dazn 1", p("DAZN 1 Elcano …63d").variantKey)
        assertEquals("dazn 1", p("DAZN 1 Italia Elcano ...2ef").variantKey)
        assertEquals("dazn 1", p("DAZN 1 DE ...229").variantKey)
        assertEquals("dazn 1", p("DAZN 1 BAR").variantKey)
        assertEquals("dazn laliga", p("ES: DAZN LALIGA New Loop").variantKey)
        assertEquals("dazn laliga", p("Dazn La Liga New Loop").variantKey)
        assertEquals("dazn laliga", p("DAZN LaLiga Elcano ...889").variantKey)
        assertEquals("dazn laliga 2", p("DAZN LaLiga 2 Elcano ...d8e").variantKey)
        assertEquals("dazn motogp", p("DAZN MOTO GP New Era VI").variantKey)
        assertEquals("dazn motogp", p("Dazn MotoGP Elcano ...b1").variantKey)
        assertEquals("dazn f1", p("ES: DAZN FORMULA 1 New Loop").variantKey)
        assertEquals("dazn f1", p("DAZN F1 Elcano ...bd4").variantKey)
        assertEquals("dazn baloncesto 3", p("DAZN Baloncesto 3 Elcano ...7a").variantKey)
        assertEquals("movistar hits", p("M+ Hits Spain New Loop").variantKey)
        assertEquals("movistar", p("Movistar Plus --> NEW ERA VI (shickat.me)").variantKey)
        assertEquals("movistar champions", p("Movistar Liga de Campeones").variantKey)
        assertEquals("movistar laliga", p("🄼🄾🅅🄸🅂🅃🄰🅁 🄻🄰🄻🄸🄶🄰 FHD").variantKey)
        // El nombre a mostrar conserva las palabras originales.
        assertEquals("DAZN LaLiga 2", p("DAZN LaLiga 2 Elcano ...d8e").variantName)
        assertEquals("M+ Hits", p("M+ Hits Spain New Loop").variantName)
    }

    @Test fun numberSeparatesVariantsOfTheSameChannel() {
        val v = p("DAZN LaLiga 2 Elcano ...d8e")
        assertEquals("dazn laliga", v.baseKey)
        assertEquals(2, v.number)
        assertEquals("dazn", p("DAZN 4").baseKey)
        assertEquals("dazn fifa", p("DAZN FIFA 3").baseKey)
        assertEquals("dazn laliga", p("DAZN LA LIGA 1").baseKey)
        // "La 1" y "La 2" son canales distintos, no variantes de "La".
        assertEquals("la 1", p("La 1").baseKey)
        assertNull(p("DAZN F1").number)                         // F1 no es una variante numerada
        // Un proveedor que se llama como una marca no borra la marca; y un nombre de proveedor DELANTE se respeta.
        assertEquals("dazn 1", ChannelCanon.parse("DAZN 1", listOf("DAZN")).variantKey)
        assertEquals("arena premium 1", ChannelCanon.parse("Arena Premium 1 HD", listOf("Arena")).variantKey)
    }

    private fun ch(name: String, url: String, provider: String) =
        Channel(id = url, name = name, url = url, group = null, kind = ChannelKind.ACE, initials = "", tint = 0).withProvider(provider.lowercase(), provider)

    @Test fun screenshotDaznChannelsGroupTogether() {
        val elcano = listOf(
            ch("DAZN LaLiga Elcano ...889", "acestream://1", "ELCANO"), ch("DAZN LaLiga Elcano ...d8e", "acestream://2", "ELCANO"),
            ch("DAZN LaLiga 2 Elcano ...a1", "acestream://3", "ELCANO"), ch("DAZN 1 Elcano ...398", "acestream://4", "ELCANO"),
            ch("DAZN 1 Italia Elcano ...2ef", "acestream://5", "ELCANO"), ch("DAZN 2 Elcano ...11b", "acestream://6", "ELCANO"),
            ch("Dazn MotoGP Elcano ...b1", "acestream://7", "ELCANO")
        )
        val icastresana = listOf(
            ch("Dazn La Liga New Loop", "acestream://8", "ICASTRESANA"), ch("ES: DAZN LALIGA New Loop", "acestream://9", "ICASTRESANA"),
            ch("DAZN MOTO GP New Era VI", "acestream://10", "ICASTRESANA"), ch("DAZN 1 DE ...229", "acestream://11", "ICASTRESANA"),
            ch("DAZN 1 BAR", "acestream://12", "ICASTRESANA"), ch("DAZN 4", "acestream://13", "ICASTRESANA")
        )
        val groups = ChannelGrouping.group(listOf(Triple("elcano", "ELCANO", elcano), Triple("icastresana", "ICASTRESANA", icastresana)))
        val byKey = groups.associateBy { it.key }
        assertEquals(setOf("dazn laliga", "dazn", "dazn motogp"), byKey.keys)

        val laliga = byKey.getValue("dazn laliga")
        assertEquals(listOf("dazn laliga", "dazn laliga 2"), laliga.variants.map { it.key })   // sin número primero
        assertEquals(4, laliga.variants[0].sources.size)
        assertEquals(1, laliga.variants[1].sources.size)
        assertEquals("DAZN LaLiga", laliga.name)

        val dazn = byKey.getValue("dazn")
        assertEquals(listOf("dazn 1", "dazn 2", "dazn 4"), dazn.variants.map { it.key })
        assertEquals(4, dazn.variants[0].sources.size)                    // Elcano, Italia, DE, BAR: todos "DAZN 1"
        assertEquals("DAZN 1", dazn.first.variantName)
        assertTrue(dazn.sources.any { it.channel.name == "DAZN 1 Italia Elcano ...2ef" })   // el nombre original se conserva
        assertEquals(2, byKey.getValue("dazn motogp").sources.size)
        // Enlaces intactos.
        assertEquals((1..13).map { "acestream://$it" }.toSet(), groups.flatMap { g -> g.sources.map { it.channel.url } }.toSet())
    }

    @Test fun eachVariantGetsItsOwnLogoNeverAnotherVariants() {
        val json = """{"version":1,"groups":{"dazn":{"name":"DAZN","aliases":["dazn"]}},
          "channels":{
            "a":{"name":"DAZN 1","logo":"https://l/dazn1.png","group":"dazn","source":"iptv-org"},
            "b":{"name":"DAZN 2","logo":"https://l/dazn2.png","group":"dazn","source":"iptv-org"},
            "c":{"name":"DAZN LaLiga","logo":"https://l/laliga.png","group":"dazn","source":"futbolenlatv"},
            "d":{"name":"DAZN","logo":"https://l/dazn.png","group":"dazn","source":"iptv-org"}}}"""
        val r = ChannelLogoResolver(LogoCatalog.parse(json))
        // La pantalla pide el logo con el nombre ya limpio de la variante (sin proveedor).
        fun logoOf(raw: String) = r.resolve(p(raw).variantName)
        assertEquals("https://l/dazn1.png", logoOf("DAZN 1 Elcano ...398").channelLogo)
        assertEquals("https://l/dazn1.png", logoOf("DAZN 1 Italia Elcano ...2ef").channelLogo)
        assertEquals("https://l/dazn2.png", logoOf("DAZN 2 Elcano ...11b").channelLogo)
        assertEquals("https://l/laliga.png", r.resolve("ES: DAZN LALIGA New Loop").channelLogo)
        assertEquals("https://l/laliga.png", r.resolve("DAZN LaLiga 2").channelLogo)       // variante sin logo → el del canal
        assertEquals("https://l/dazn.png", r.resolve("DAZN 4").best)                        // → marca, NUNCA el de DAZN 1
        assertTrue(r.resolve("DAZN 4").channelLogo != "https://l/dazn1.png")
    }
}
