package com.mivisor.app

import com.mivisor.app.data.AceStreamLink
import com.mivisor.app.data.CatalogBuilder
import com.mivisor.app.data.ChannelGrouping
import com.mivisor.app.data.PayloadDecoder
import com.mivisor.app.diagnostics.AceLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Registro Ace Stream + regresión con la estructura del código que se queda cargando (IDs inventados). */
class AceLogTest {

    private fun id(n: Int) = n.toString(16).padStart(2, '0').repeat(20)

    /** Misma forma que la lista real: grupos con url "", subLinks, entradas web, nombres con tildes. */
    private val payload = """[{"name":"Grupo Telegram","url":"https://t.me/+x"},
        {"name":"Primera Federación (RFEF)","url":"acestream://${id(1)}"},
        {"name":"Paginas Web Oficiales","url":"","subLinks":[{"name":"Web Principal","url":"https://example.org"}]},
        {"name":"DAZN","url":"","subLinks":[{"name":"DAZN 1","url":"acestream://${id(2)}"},{"name":"DAZN 1 (Web)","url":"https://example.org"},
            {"name":"DAZN 2","url":"acestream://${id(3)}"},{"name":"DAZN LaLiga","url":"acestream://${id(4)}"},{"name":"DAZN LaLiga 2","url":"acestream://${id(5)}"}]},
        {"name":"Movistar Deportes","url":"","subLinks":[{"name":"Movistar LaLiga","url":"acestream://${id(6)}"},{"name":"Movistar Plus","url":"acestream://${id(7)}"},
            {"name":"Movistar Deportes","url":"acestream://${id(8)}"},{"name":"Movistar Deportes 2","url":"acestream://${id(9)}"}]},
        {"name":"Movistar Cine","url":"","subLinks":[{"name":"Movistar Cine Español","url":"acestream://${id(10)}"},{"name":"Movistar Acción","url":"acestream://${id(11)}"}]}]"""

    @Test fun nestedListKeepsEveryContentIdIntactAndSeparate() {
        val catalog = CatalogBuilder.build(PayloadDecoder().decode(listOf(payload)), aceOnly = true)
        val channels = catalog.sections.flatMap { it.channels }
        assertEquals(11, channels.size)                                     // solo los acestream://
        for (n in 1..11) assertTrue(channels.any { it.url == "acestream://${id(n)}" })
        channels.forEach { assertEquals(it.url.removePrefix("acestream://"), AceStreamLink.contentId(it.url)) }
        // Ningún canal distinto se fusiona con otro (DAZN LaLiga ≠ Movistar LaLiga, Movistar Deportes ≠ … 2).
        val groups = ChannelGrouping.group(listOf(Triple("p", "P", channels)))
        assertEquals(11, groups.size)
        assertEquals("DAZN", channels.first { it.name == "DAZN 1" }.group)
    }

    private fun launch(pkg: String?, reason: String) = AceLog.Launch(
        channel = "DAZN 1", provider = "Proveedor", group = "DAZN",
        storedUrl = "acestream://${id(2)}", sentUri = "acestream://${id(2)}",
        targetPackage = pkg, reason = reason, handlers = listOf("org.acestream.core", "org.acestream.media"),
        systemDefault = null, providerVpn = false, systemVpn = true, httpEngineMode = false,
        engineBefore = "stopped"
    )

    @Test fun entryExplainsWhichAppAndWhyWithoutFullId() {
        val text = AceLog.entry(launch("org.acestream.core", "app recordada para el grupo «DAZN»"))
        assertTrue(text.contains("org.acestream.core — app recordada para el grupo «DAZN»"))
        assertTrue(text.contains("llega intacto"))
        assertTrue(text.contains("VPN activa en la TV: SÍ"))
        assertTrue(AceLog.returned(77, "responde", vpnNow = true).contains("VPN activa ahora: SÍ"))
        assertTrue(text.contains("org.acestream.core, org.acestream.media"))
        assertFalse(text.contains(id(2)))                                    // nunca el Content ID completo
        assertTrue(text.contains(AceStreamLink.masked(id(2))))
    }

    @Test fun appendKeepsLastEntriesAndSanitizes() {
        val dir = Files.createTempDirectory("acelog").toFile()
        try {
            repeat(AceLog.MAX_ENTRIES + 5) { i ->
                AceLog.append(dir, AceLog.entry(launch("org.acestream.media", "única app compatible #$i")))
                AceLog.append(dir, AceLog.returned(42, "responde"))
            }
            AceLog.append(dir, AceLog.answer(false) + " https://secret.example/token?x=1")
            val text = File(dir, AceLog.FILE).readText()
            assertEquals(AceLog.MAX_ENTRIES, Regex("(?m)^── ").findAll(text).count())
            assertFalse(text.contains("#0\n") || text.contains("#4\n"))       // las más antiguas se van
            assertTrue(text.contains("#44"))
            assertTrue(text.contains("NO se veía"))
            assertFalse(text.contains("secret.example"))                    // URLs limpiadas
            assertTrue(text.startsWith("Registro Ace Stream"))
            assertEquals(1, Regex("Registro Ace Stream \\(").findAll(text).count())
        } finally { dir.deleteRecursively() }
    }

    /** Varias versiones de la lista: se elige como MyLinksPaste (más entradas de primer nivel; empate → la primera). */
    @Test fun choosesListVersionLikeOldApp() {
        val older = """[{"name":"DAZN","url":"","subLinks":[{"name":"DAZN LaLiga","url":"acestream://${id(20)}"},{"name":"Extra","url":"acestream://${id(21)}"},{"name":"Extra 2","url":"acestream://${id(22)}"}]},{"name":"Eurosport 1","url":"acestream://${id(23)}"}]"""
        val newer = """[{"name":"DAZN","url":"","subLinks":[{"name":"DAZN LaLiga","url":"acestream://${id(30)}"}]},{"name":"Eurosport 1","url":"acestream://${id(31)}"}]"""
        // Mismo número de entradas de primer nivel (2): gana la PRIMERA (Google), aunque la otra tenga más canales dentro.
        val d = PayloadDecoder().decodeDetailed(listOf(newer, older))
        assertEquals(1, d.chosen)
        assertEquals(2, d.versions)
        val laliga = CatalogBuilder.build(d.items).sections.flatMap { it.channels }.first { it.name == "DAZN LaLiga" }
        assertEquals("acestream://${id(30)}", laliga.url)          // antes MiVisor elegía "older" (más canales) → otro ID
        assertEquals(1, PayloadDecoder.chooseVersion(listOf(5, 9, 9)))
        assertEquals(0, PayloadDecoder.chooseVersion(listOf(9, 9)))
    }

    @Test fun asksOnlyWhenComingBackSoon() {
        assertTrue(AceLog.shouldAsk(0, 40_000))
        assertFalse(AceLog.shouldAsk(0, 2 * 60 * 60 * 1000L))               // viste el partido entero: no molesta
        assertEquals("1 min 5 s", AceLog.duration(65))
    }
}
