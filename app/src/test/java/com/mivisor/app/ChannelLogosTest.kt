package com.mivisor.app

import com.mivisor.app.data.AgendaRepository
import com.mivisor.app.data.TvChannelsRepository
import com.mivisor.app.data.logos.BuiltinGroups
import com.mivisor.app.data.logos.ChannelLogoResolver
import com.mivisor.app.data.logos.LogoCatalog
import com.mivisor.app.data.logos.LogoEntry
import com.mivisor.app.data.logos.LogoRepository
import com.mivisor.app.data.logos.LogoSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Logos de grupos y canales: normalizar → grupo → logo del canal → logo del grupo → genérico. */
class ChannelLogosTest {

    private val catalogJson = """{"version":1,"updated":"2026-10-26T10:00:00Z",
      "groups":{
        "movistar":{"name":"Movistar Plus+","aliases":["movistar plus+","movistar plus","movistar+","movistar","m+"],"logo":"https://l/movistar.png"},
        "dazn":{"name":"DAZN","aliases":["dazn"],"logo":"https://l/dazn.png"},
        "eurosport":{"name":"Eurosport","aliases":["eurosport"]},
        "tennischannel":{"name":"Tennis Channel","aliases":["tennis channel"]}
      },
      "channels":{
        "movistar laliga":{"name":"M+ LaLiga","aliases":["Movistar LaLiga TV"],"logo":"https://futbolenlatv/mlaliga.png","group":"movistar","source":"futbolenlatv"},
        "movistar vamos":{"name":"Movistar Vamos","logo":"https://iptv/vamos.png","group":"movistar","source":"iptv-org"},
        "dazn laliga":{"name":"DAZN LaLiga","logo":"https://futbolenlatv/daznlaliga.png","group":"dazn","source":"futbolenlatv"},
        "daznf1":{"name":"DAZN F1","logo":"https://iptv/daznf1.png","group":"dazn","source":"iptv-org"},
        "eurosport1":{"name":"Eurosport 1","logo":"https://iptv/es1.png","group":"eurosport","source":"iptv-org"},
        "eurosport":{"name":"Eurosport","logo":"https://iptv/eurosport.png","group":"eurosport","source":"iptv-org"},
        "vector":{"name":"Vector TV","logo":"https://iptv/v.svg","source":"iptv-org"},
        "insecure":{"name":"Insecure","logo":"http://iptv/i.png","source":"iptv-org"}
      }}"""

    private fun resolver(extra: List<LogoEntry> = emptyList()) = ChannelLogoResolver(LogoCatalog.parse(catalogJson), extra)

    @Test fun decorativeNameIsNormalizedBeforeSearching() {
        val r = resolver().resolve("🄼🄾🅅🄸🅂🅃🄰🅁 🄻🄰🄻🄸🄶🄰")
        assertEquals("https://futbolenlatv/mlaliga.png", r.channelLogo)      // "M+ LaLiga" = "Movistar LaLiga"
        assertEquals("movistar", r.groupKey)
        assertEquals("https://l/movistar.png", r.groupLogo)
    }

    @Test fun groupAndChannelAreDifferentLevels() {
        val res = resolver()
        val vamos = res.resolve("M+ Vamos FHD")
        assertEquals("https://iptv/vamos.png", vamos.channelLogo)
        assertEquals("https://l/movistar.png", vamos.groupLogo)
        assertTrue(vamos.channelLogo != vamos.groupLogo)
        // Sin logo propio: se usa el del grupo como respaldo.
        val deportes = res.resolve("M+ Deportes 2")
        assertNull(deportes.channelLogo)
        assertEquals("https://l/movistar.png", deportes.best)
        // DAZN LaLiga es del grupo DAZN y NO toma el logo de Movistar LaLiga.
        val daznLaliga = res.resolve("DAZN LaLiga")
        assertEquals("dazn", daznLaliga.groupKey)
        assertEquals("https://futbolenlatv/daznlaliga.png", daznLaliga.channelLogo)
        assertEquals("https://iptv/daznf1.png", res.resolve("𝐃𝐀𝐙𝐍 𝐅𝟏").channelLogo)
        // DAZN 1 y DAZN 2 no se confunden: sin logo propio → logo de DAZN.
        assertNull(res.resolve("DAZN 2").channelLogo)
        assertEquals("https://l/dazn.png", res.resolve("DAZN 2").best)
    }

    @Test fun groupLogoFallsBackToFlagshipChannelAndThenGeneric() {
        val res = resolver()
        assertEquals("https://iptv/eurosport.png", res.resolve("Eurosport 2").groupLogo)   // el grupo no trae logo
        val tennis = res.resolve("Tennis Channel")
        assertEquals("tennischannel", tennis.groupKey)
        assertNull(tennis.best)                                                             // → icono genérico
        val rfef = res.resolve("Primera Federación (RFEF)")
        assertNull(rfef.groupKey)
        assertNull(rfef.best)
        // Pista del grupo de la lista del proveedor cuando el nombre no dice nada.
        assertEquals("dazn", res.resolve("Canal 3", listGroup = "DAZN").groupKey)
    }

    @Test fun onlyDrawableHttpsLogosAndSourcePriority() {
        val res = resolver(listOf(
            LogoEntry("M+ LaLiga", "https://tsdb/mlaliga.png", LogoSource.THESPORTSDB),        // no gana a futbolenlatv
            LogoEntry("M+ Deportes", "https://tsdb/mdeportes.png", LogoSource.THESPORTSDB),    // rellena un hueco
            LogoEntry("Movistar Vamos", "https://futbolenlatv/vamos.png", LogoSource.FUTBOLENLATV) // gana a iptv-org
        ))
        assertNull(res.resolve("Vector TV").channelLogo)
        assertNull(res.resolve("Insecure").channelLogo)
        assertEquals("https://futbolenlatv/mlaliga.png", res.resolve("M+ LaLiga").channelLogo)
        assertEquals("https://tsdb/mdeportes.png", res.resolve("Movistar Deportes").channelLogo)
        assertEquals("https://futbolenlatv/vamos.png", res.resolve("M+ Vamos").channelLogo)
        // El grupo se decide por el principio del nombre.
        assertEquals("movistar", res.groupOf("Movistar Plus+"))
        assertNull(res.groupOf("Golf+"))
        assertEquals("laliga", ChannelLogoResolver(LogoCatalog.EMPTY).groupOf("LaLiga TV Hypermotion"))
    }

    @Test fun sectionsByGroupKeepOrderAndPutOthersLast() {
        val names = listOf("DAZN 1", "Primera Federación (RFEF)", "M+ LaLiga", "DAZN F1", "🄼🄾🅅🄸🅂🅃🄰🅁 🅅🄰🄼🄾🅂", "Eurosport 1")
        val sections = com.mivisor.app.data.logos.LogoSections.build(names, resolver(), { it }, { null })
        assertEquals(listOf("dazn", "movistar", "eurosport", null), sections.map { it.key })
        assertEquals(listOf("DAZN 1", "DAZN F1"), sections[0].items.map { it.first })
        assertEquals("https://l/dazn.png", sections[0].logo.groupLogo)                     // logo general del grupo
        assertEquals("https://iptv/daznf1.png", sections[0].items[1].second.channelLogo)  // logo específico del canal
        assertEquals("Movistar Plus+", sections[1].name)
        assertEquals("https://iptv/vamos.png", sections[1].items[1].second.channelLogo)   // nombre decorativo → M+ Vamos
        assertEquals(com.mivisor.app.data.logos.LogoSections.OTHERS, sections.last().name)
        assertEquals(listOf("Primera Federación (RFEF)"), sections.last().items.map { it.first })
    }

    /** Caso de la captura: el catálogo traía el logo de "DAZN 1" como logo del grupo DAZN. */
    @Test fun numberedChannelLogoIsNeverTheGroupLogo() {
        val json = """{"version":1,"groups":{"dazn":{"name":"DAZN","aliases":["dazn"],"logo":"https://iptv/dazn1.png"},
            "movistar":{"name":"Movistar Plus+","aliases":["movistar","m+"],"logo":"https://iptv/mplus.png"}},
          "channels":{"dazn 1":{"name":"DAZN 1","logo":"https://iptv/dazn1.png","group":"dazn","source":"iptv-org"},
            "dazn f1":{"name":"DAZN F1","logo":"https://iptv/daznf1.png","group":"dazn","source":"iptv-org"},
            "movistar":{"name":"Movistar Plus+","logo":"https://iptv/mplus.png","group":"movistar","source":"iptv-org"}}}"""
        val res = ChannelLogoResolver(LogoCatalog.parse(json))
        val motogp = res.resolve("DAZN MotoGP")
        assertEquals("dazn", motogp.groupKey)
        assertNull(motogp.groupLogo)                        // no hereda el logo de DAZN 1
        assertNull(motogp.best)                             // → nombre del grupo / icono genérico
        assertEquals("https://iptv/dazn1.png", res.resolve("DAZN 1").channelLogo)   // DAZN 1 sí conserva el suyo
        assertEquals("https://iptv/daznf1.png", res.resolve("DAZN F1").best)
        // El logo de la marca (canal que se llama como el grupo, sin número) sí vale.
        assertEquals("https://iptv/mplus.png", res.resolve("M+ LaLiga").groupLogo)
    }

    @Test fun deletedImagePlaceholderIsNotALogo() {
        assertTrue(LogoCatalog.isPlaceholder("https://i.imgur.com/removed.png"))
        assertFalse(LogoCatalog.isPlaceholder("https://i.imgur.com/abc123.png"))
        assertFalse(LogoCatalog.isPlaceholder(null))
    }

    @Test fun builtinGroupsMatchTheCatalogDefinition() {
        val file = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "tools/agenda/channel_brands.json") }.firstOrNull { it.isFile } ?: return
        val groups = com.google.gson.JsonParser.parseString(file.readText()).asJsonObject.getAsJsonObject("groups")
        assertEquals(groups.keySet(), BuiltinGroups.ALL.map { it.key }.toSet())
        for (g in BuiltinGroups.ALL) {
            val def = groups.getAsJsonObject(g.key)
            assertEquals(def.get("name").asString, g.name)
            assertEquals(def.getAsJsonArray("aliases").map { it.asString }, g.aliases)
        }
    }

    @Test fun theSportsDbLogoIsKept() {
        val body = """{"tvevent":[{"idEvent":"1","strChannel":"DAZN LaLiga","strCountry":"Spain","strLogo":"https://www.thesportsdb.com/images/media/channel/logo/x.png"},
            {"idEvent":"1","strChannel":"Sky","strCountry":"UK","strLogo":"https://x/y.svg"}]}"""
        val ch = TvChannelsRepository.parse(body, "1")
        assertEquals("https://www.thesportsdb.com/images/media/channel/logo/x.png", ch[0].logo)
        assertNull(ch[1].logo)
    }

    @Test fun repositoryDownloadsAtMostDailyAndKeepsHarvest() = runBlocking {
        val dir = Files.createTempDirectory("logos").toFile()
        try {
            var now = 1_000_000L
            val calls = ArrayList<String?>()
            var reply: () -> AgendaRepository.Download = { AgendaRepository.Download(200, catalogJson, "\"v1\"") }
            fun repo() = LogoRepository(dir, clock = { now }, download = { _, etag -> calls += etag; reply() })
            val url = LogoRepository.urlFor("https://raw.githubusercontent.com/u/r/agenda-data/agenda.json")
            assertEquals("https://raw.githubusercontent.com/u/r/agenda-data/channel-logos.json", url)

            assertEquals("movistar", repo().load(url).resolve("M+ LaLiga").groupKey)
            assertEquals(1, calls.size)
            now += 60 * 60 * 1000L                                      // 1 h después: de disco, sin descargar
            assertEquals("https://futbolenlatv/mlaliga.png", repo().load(url).resolve("M+ LaLiga").channelLogo)
            assertEquals(1, calls.size)
            now += 25 * 60 * 60 * 1000L                                 // al día siguiente: con ETag → 304
            reply = { AgendaRepository.Download(304, null, null) }
            repo().load(url)
            assertEquals(listOf(null, "\"v1\""), calls)
            now += 25 * 60 * 60 * 1000L                                 // sin red: se sigue con la copia
            reply = { throw java.io.IOException("offline") }
            assertEquals("https://futbolenlatv/mlaliga.png", repo().load(url).resolve("M+ LaLiga").channelLogo)

            val r1 = repo()
            assertTrue(r1.harvest(listOf(LogoEntry("Gol Play", "https://futbolenlatv/gol.png", LogoSource.FUTBOLENLATV))))
            assertFalse(r1.harvest(listOf(LogoEntry("GOL PLAY", "https://tsdb/gol.png", LogoSource.THESPORTSDB))))
            assertEquals("https://futbolenlatv/gol.png", repo().load("").resolve("Gol Play").channelLogo)   // persistido
        } finally { dir.deleteRecursively() }
    }
}
