package com.mivisor.app

import com.mivisor.app.data.AceStreamLink
import com.mivisor.app.data.CatalogBuilder
import com.mivisor.app.data.ChannelGrouping
import com.mivisor.app.data.ChannelMatcher
import com.mivisor.app.data.ChannelNameNormalizer
import com.mivisor.app.data.PayloadDecoder
import com.mivisor.app.model.Channel
import com.mivisor.app.model.ChannelKind
import com.mivisor.app.model.StreamItem
import com.mivisor.app.player.LaunchPlanner
import com.mivisor.app.player.LaunchReport
import com.mivisor.app.player.LegacyMyLinksPaste
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v13: normalización de nombres, solo Ace Stream como canal, URLs intactas y apertura igual que MyLinksPaste 4.6. */
class ImportAndLaunchTest {
    private val ID_A = "2a05a020b6c1d3e4f5061728394a5b6c7d8e9f01"
    private val ID_B = "9F1C00AABBCCDDEEFF00112233445566778899AA"      // mayúsculas: se respetan

    // ---------------------------------------------------------------- ChannelNameNormalizer

    @Test fun decorativeUnicodeBecomesText() {
        assertEquals("DAZN LALIGA", ChannelNameNormalizer.display("🄳🄰🅉🄽 🄻🄰🄻🄸🄶🄰"))
        assertEquals("MOVISTAR DEPORTES", ChannelNameNormalizer.display("🄼🄾🅅🄸🅂🅃🄰🅁 🄳🄴🄿🄾🅁🅃🄴🅂"))
        assertEquals("DAZN 1", ChannelNameNormalizer.display(" 🄳🄰🅉🄽 1 "))
    }

    @Test fun otherUnicodeStyles() {
        assertEquals("DAZN", ChannelNameNormalizer.display("🅳🅰🆉🅽"))             // cuadrado negativo
        assertEquals("DAZN", ChannelNameNormalizer.display("🅓🅐🅩🅝"))             // círculo negativo
        assertEquals("DAZN", ChannelNameNormalizer.display("ⒹⒶⓏⓃ"))               // círculo
        assertEquals("DAZN 1", ChannelNameNormalizer.display("ＤＡＺＮ　１"))          // ancho completo
        assertEquals("DAZN", ChannelNameNormalizer.display("𝐃𝐀𝐙𝐍"))                 // negrita matemática
        assertEquals("Dazn", ChannelNameNormalizer.display("𝘿𝙖𝙯𝙣"))
        assertEquals("DAZN", ChannelNameNormalizer.display("ᴅᴀᴢɴ"))                 // versalitas
        assertEquals("ES Eurosport", ChannelNameNormalizer.display("🇪🇸 Eurosport"))  // indicadores regionales
    }

    @Test fun spacesNewlinesAndInvisibles() {
        assertEquals("DAZN F1", ChannelNameNormalizer.display("  DAZN\\n\\t  F1  ".replace("\\n", "\n").replace("\\t", "\t")))
        assertEquals("DAZN F1", ChannelNameNormalizer.display("DA​ZN F1﻿"))
        assertEquals("M+ LaLiga", ChannelNameNormalizer.display("‎M+⁦ LaLiga⁩"))
    }

    @Test fun emojisAndDecorativeSeparators() {
        assertEquals("", ChannelNameNormalizer.display("🟧🟧🟧🟧🟧"))
        assertEquals("DEPORTES", ChannelNameNormalizer.display("🟧🟧 DEPORTES 🟧🟧"))
        assertEquals("LaLiga TV", ChannelNameNormalizer.display("⚽️ LaLiga TV ⚽️"))
        assertEquals("DAZN 1", ChannelNameNormalizer.display("═══ ▶ DAZN 1 ◀ ═══"))
        assertEquals("Canal+ Fútbol", ChannelNameNormalizer.display("★ Canal+ Fútbol ★ |"))
        assertEquals("1️⃣", "1️⃣")                                                   // (solo para documentar el keycap)
        assertEquals("1", ChannelNameNormalizer.display("1️⃣"))
    }

    @Test fun normalNamesStayCorrect() {
        for (name in listOf("DAZN LaLiga", "M+ Liga de Campeones", "Eurosport 2", "Teledeporte", "Real Madrid TV", "beIN Sports", "Fútbol Sala"))
            assertEquals(name, ChannelNameNormalizer.display(name))
        assertEquals("dazn laliga", ChannelNameNormalizer.matchKey("🄳🄰🅉🄽 🄻🄰🄻🄸🄶🄰"))
        assertEquals("futbol sala", ChannelNameNormalizer.matchKey("FÚTBOL  sala"))
    }

    // ---------------------------------------------------------------- AceStreamLink

    @Test fun onlyValidAceStreamIsAChannel() {
        assertNull(AceStreamLink.parse(""))                                         // URL vacía
        assertNull(AceStreamLink.parse(null))
        assertNull(AceStreamLink.parse("https://ciriaco.netlify.app/"))              // web
        assertNull(AceStreamLink.parse("http://127.0.0.1:6878/ace/getstream?id=$ID_A"))   // http: nunca se convierte
        assertNull(AceStreamLink.parse("acestream://"))
        assertNull(AceStreamLink.parse("acestream://1234"))                         // Content ID incompleto
        assertNull(AceStreamLink.parse("acestream://${ID_A}x"))
        assertNull(AceStreamLink.parse("magnet:?xt=urn:btih:$ID_A"))
        assertEquals("acestream://$ID_A", AceStreamLink.parse("acestream://$ID_A"))
        assertEquals("acestream://$ID_A", AceStreamLink.parse(ID_A))                 // Content ID puro, validado
        assertEquals("AceStream://$ID_A", AceStreamLink.parse("AceStream://$ID_A"))  // se respeta tal cual
    }

    @Test fun cleaningOnlyAtTheEnds() {
        assertEquals("acestream://$ID_A", AceStreamLink.parse("acestream://$ID_A      "))
        assertEquals("acestream://$ID_A", AceStreamLink.parse("  acestream://$ID_A\r\n"))
        assertEquals("acestream://$ID_A", AceStreamLink.parse("​acestream://$ID_A﻿"))
        assertNull(AceStreamLink.parse("acestream://2a05 a020b6c1d3e4f5061728394a5b6c7d8e9f01"))   // dentro: no se "arregla"
    }

    @Test fun contentIdIsNeverAltered() {
        assertEquals(ID_B, AceStreamLink.contentId("acestream://$ID_B "))            // mayúsculas intactas
        val catalog = CatalogBuilder.build(listOf(StreamItem("🄳🄰🅉🄽 1", " acestream://$ID_B\n")))
        assertEquals("acestream://$ID_B", catalog.sections[0].channels[0].url)
        assertEquals("DAZN 1", catalog.sections[0].channels[0].name)
        assertEquals("2a05…9f01 (40)", AceStreamLink.masked(ID_A))
    }

    // ---------------------------------------------------------------- importación

    private val SOURCE = """[
      {"name":"🟧🟧🟧🟧🟧","url":""},
      {"name":"AGENDA DEPORTIVA","url":"https://ciriaco.netlify.app/"},
      {"name":"🄳🄰🅉🄽 1","url":"acestream://$ID_A      "},
      {"name":"DAZN 1","url":"acestream://$ID_B"},
      {"name":" ═══ FÚTBOL ═══ ","subLinks":[
        {"name":"🄼🄾🅅🄸🅂🅃🄰🅁 🄻🄰🄻🄸🄶🄰","url":"acestream://1111111111111111111111111111111111111111"},
        {"name":"M+ LaLiga","url":"acestream://2222222222222222222222222222222222222222"},
        {"name":"Web del club","url":"https://example.com/club"},
        {"name":"──────────"},
        {"name":"Telegram","url":"tg://resolve?domain=x"}
      ]}
    ]"""

    @Test fun importPipelineKeepsOnlyAceStreamAndCounts() {
        val items = PayloadDecoder(com.google.gson.Gson()).decode(listOf(SOURCE))
        val catalog = CatalogBuilder.build(items)
        val all = catalog.sections.flatMap { it.channels }
        assertEquals(listOf("DAZN 1", "DAZN 1", "MOVISTAR LALIGA", "M+ LaLiga"), all.map { it.name })
        assertTrue(all.all { it.kind == ChannelKind.ACE && AceStreamLink.isValid(it.url) })
        assertEquals(listOf("Canales", "FÚTBOL"), catalog.sections.map { it.title })    // título de grupo normalizado
        val s = catalog.stats
        assertEquals(9, s.entries)
        assertEquals(4, s.valid)
        assertEquals(5, s.ignored)
        assertTrue(s.normalized >= 2)
        assertFalse(s.summary().contains(ID_A))                                        // el resumen no lleva Content IDs
    }

    @Test fun sameNameDifferentStreamsAreAllKept() {
        val catalog = CatalogBuilder.build(PayloadDecoder(com.google.gson.Gson()).decode(listOf(SOURCE)))
        val dazn = catalog.sections[0].channels.filter { it.name == "DAZN 1" }
        assertEquals(setOf("acestream://$ID_A", "acestream://$ID_B"), dazn.map { it.url }.toSet())
        // Y en "Canales" son UN canal lógico con dos fuentes (fallback posible).
        val groups = ChannelGrouping.group(listOf(Triple("p", "Proveedor", catalog.sections.flatMap { it.channels })))
        assertEquals(2, groups.first { it.name == "DAZN 1" }.sources.size)
        // "MOVISTAR LALIGA" y "M+ LaLiga": mismo canal lógico (alias después de normalizar).
        assertEquals(2, groups.first { it.key == ChannelGrouping.key("M+ LaLiga") }.sources.size)
    }

    @Test fun agendaMatchingAfterNormalization() {
        val catalog = CatalogBuilder.build(listOf(StreamItem("🄳🄰🅉🄽 🄻🄰🄻🄸🄶🄰", "acestream://$ID_A"), StreamItem("🄼🄾🅅🄸🅂🅃🄰🅁 🄻🄰 🄻🄸🄶🄰", "acestream://$ID_B")))
        val matcher = ChannelMatcher(catalog.sections.flatMap { it.channels })
        assertEquals(listOf("acestream://$ID_A"), matcher.match("DAZN LaLiga").map { it.url })
        assertEquals(listOf("acestream://$ID_B"), matcher.match("M+ LaLiga").map { it.url })
        assertEquals(listOf("acestream://$ID_B"), matcher.match("Movistar LaLiga").map { it.url })
        // También si la agenda trae decoración.
        assertEquals(listOf("acestream://$ID_A"), matcher.match("  ⚽ 🄳🄰🅉🄽 LaLiga ").map { it.url })
    }

    @Test fun legacyModeStillAvailable() {
        val items = listOf(StreamItem("HLS", "https://x/a.m3u8"), StreamItem("Ace", "acestream://$ID_A"))
        assertEquals(1, CatalogBuilder.build(items).channelCount)
        assertEquals(2, CatalogBuilder.build(items, aceOnly = false).channelCount)
    }

    // ---------------------------------------------------------------- apertura: MiVisor vs MyLinksPaste 4.6

    private fun plan(url: String, customAce: Boolean = false) = LaunchPlanner.plan(
        url, "DAZN 1", customAce, "http://127.0.0.1:6878/ace/getstream?id={id}", false, "", { it }
    )

    /** Mismos Content IDs con formatos reales de las listas (minúsculas, mayúsculas, con basura en extremos). */
    private val REAL_FORMAT_LINKS = listOf(
        "acestream://$ID_A",
        "acestream://$ID_B",
        "acestream://$ID_A      ",
        "  acestream://$ID_B\r\n",
        "acestream://0123456789abcdef0123456789abcdef01234567"
    )

    @Test fun aceLaunchIsIdenticalToLegacyApp() {
        for (link in REAL_FORMAT_LINKS) {
            val now = plan(link)
            val old = LegacyMyLinksPaste.plan(link, false, "")
            assertEquals(old.uri, now.uri)                                           // URI exacta enviada
            assertEquals(old.action, now.action)
            assertEquals(old.flags, now.flags)                                       // FLAG_ACTIVITY_NEW_TASK
            assertEquals(old.mime, now.mime)                                         // sin MIME
            assertTrue(now.extras.isEmpty())                                         // sin extras (antes se añadía "title")
            assertTrue(now.uri.startsWith("acestream://"))
            assertEquals(AceStreamLink.contentId(link), AceStreamLink.contentId(now.uri))
        }
    }

    @Test fun localHttpEngineModeMatchesLegacyTemplate() {
        val now = plan("acestream://$ID_B ", customAce = true)
        val old = LegacyMyLinksPaste.plan("acestream://$ID_B ", true, "http://127.0.0.1:6878/ace/getstream?id={id}")
        assertEquals(old.uri, now.uri)
        assertEquals("http://127.0.0.1:6878/ace/getstream?id=$ID_B", now.uri)          // sin codificar ni cambiar mayúsculas
        assertTrue(now.convertedToHttp)
    }

    @Test fun launchReportNamesTheDifferencesWithoutFullIds() {
        val spec = plan("acestream://$ID_A")
        val report = LaunchReport.build("DAZN 1", "DAZN", "acestream://$ID_A", spec, LegacyMyLinksPaste.plan("acestream://$ID_A", false, ""),
            "org.acestream.media (app recordada)", enginePrepared = false, engineState = "READY")
        assertTrue(report.contains("15. Diferencias con MyLinksPaste 4.6: ninguna"))
        assertTrue(report.contains("idéntico"))
        assertFalse(report.contains(ID_A))
        val withPrestart = LaunchReport.build("DAZN 1", null, "acestream://$ID_A", spec, LegacyMyLinksPaste.plan("acestream://$ID_A", false, ""),
            "sistema", enginePrepared = true, engineState = "STOPPED")
        assertTrue(withPrestart.contains("MiVisor arrancó antes el motor"))
    }

    @Test fun otherLinksKeepPlayerHints() {
        val hls = LaunchPlanner.plan("https://x/a.m3u8", "Canal", false, "", false, "", { it })
        assertEquals("application/vnd.apple.mpegurl", hls.mime)
        assertEquals("Canal", hls.extras["title"])
    }

    @Suppress("unused") private fun unused(c: Channel) = c
}
