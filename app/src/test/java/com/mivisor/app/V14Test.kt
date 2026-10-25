package com.mivisor.app

import com.mivisor.app.services.AceProbeProtocol
import com.mivisor.app.services.AceStreamProbe
import com.mivisor.app.services.ProbeStat
import com.mivisor.app.services.ProbeVerdict
import com.mivisor.app.ui.kit.TextFit
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Collections

/** v14: nombres largos legibles y comprobación real de streams Ace (motor abierto ≠ stream llegando). */
class V14Test {

    // ---------------------------------------------------------------- nombres largos

    private val longNames = listOf(
        "Atlético de Madrid", "Deportivo de La Coruña", "Borussia Mönchengladbach", "Wolverhampton Wanderers",
        "Brighton & Hove Albion", "Paris Saint-Germain", "Real Sociedad de Fútbol", "Athletic Club de Bilbao"
    )
    private val longCompetitions = listOf("UEFA Europa Conference League", "Copa de S.M. el Rey", "Premier League", "UEFA Champions League")

    @Test fun longTeamNamesFitInTwoLinesWithoutEllipsis() {
        // Ancho de cada nombre en la fila del Calendario en una TV 1080p (960 dp de ancho, con panel lateral): ~95 dp.
        for (name in longNames) {
            val sp = TextFit.fit(name, widthDp = 95f, baseSp = 14f, minSp = 11.5f, maxLines = 2)
            assertTrue("$name no cabe", TextFit.fits(name, 95f, sp, 2))
            assertTrue(sp in 11.5f..14f)
        }
        // Competiciones: columna de 98 dp, 2 líneas, de 11 a 10 sp.
        for (c in longCompetitions) assertTrue("$c no cabe", TextFit.fits(c, 98f, TextFit.fit(c, 98f, 11f, 10f, 2), 2))
        // Lo que pasaba antes: una sola línea a 14 sp → "Atlético de Mad…".
        assertFalse(TextFit.fits("Atlético de Madrid", 95f, 14f, maxLines = 1))
        assertTrue(TextFit.fits("Atlético de Madrid", 95f, 14f, maxLines = 2))
    }

    @Test fun shortNamesKeepBaseSizeAndScaleRespected() {
        assertTrue(TextFit.fit("Lazio", 95f, 14f, 11.5f) == 14f)
        // Con escalado de fuente del sistema (1.3) se reduce antes de cortar.
        val scaled = TextFit.fit("Wolverhampton Wanderers", 95f, 14f, 11.5f, 2, fontScale = 1.3f)
        assertTrue(scaled <= 14f)
        assertEquals(1, TextFit.linesNeeded("Lazio vs Parma", 30))
        assertEquals(2, TextFit.linesNeeded("Atlético de Madrid vs Deportivo", 20))
    }

    @Test fun homeCardMatchupFits() {
        // Tarjeta de Inicio: 152 dp - 16 de margen = 136 dp para "Local vs Visitante" (la hora va junto a los escudos).
        for (text in listOf("Atlético de Madrid vs Deportivo", "Borussia Mönchengladbach vs Bayern", "Wolverhampton Wanderers vs Brighton")) {
            val sp = TextFit.fit(text, 136f, 12f, 10f, 2)
            assertTrue("$text no cabe", TextFit.fits(text, 136f, sp, 2))
        }
        // Con la hora en la misma línea (diseño anterior, ~100 dp) no cabía: por eso se movió.
        assertFalse(TextFit.fits("Atlético de Madrid vs Deportivo", 100f, 10.5f, 2))
    }

    // ---------------------------------------------------------------- comprobación de stream

    private val id = "2a05a020b6c1d3e4f5061728394a5b6c7d8e9f01"

    @Test fun parseStartAndStat() {
        val (s, err) = AceProbeProtocol.parseStart(
            """{"response":{"stat_url":"http://127.0.0.1:6878/ace/stat/x/y","command_url":"http://127.0.0.1:6878/ace/cmd/x/y","playback_url":"http://127.0.0.1:6878/ace/r/x/y","is_live":1},"error":null}"""
        )
        assertEquals(null, err)
        assertEquals("http://127.0.0.1:6878/ace/stat/x/y", s!!.statUrl)
        assertEquals(true, s.isLive)
        assertEquals("missing content", AceProbeProtocol.parseStart("""{"response":null,"error":"missing content"}""").second)
        val st = AceProbeProtocol.parseStat("""{"response":{"status":"dl","peers":12,"speed_down":850,"downloaded":1048576},"error":null}""")!!
        assertEquals(ProbeStat("dl", 12, 850, 1048576, null, null), st)
    }

    @Test fun engineOpenIsNotStreaming() {
        fun st(status: String, peers: Int = 0, speed: Int = 0, dl: Long = 0) = ProbeStat(status, peers, speed, dl, null, null)
        assertEquals(ProbeVerdict.STREAMING, AceProbeProtocol.verdict(listOf(st("prebuf"), st("dl", 8, 600, 100), st("dl", 9, 700, 500))))
        assertEquals(ProbeVerdict.PREBUFFERING_ONLY, AceProbeProtocol.verdict(listOf(st("prebuf"), st("prebuf"))))
        assertEquals(ProbeVerdict.NO_DATA, AceProbeProtocol.verdict(listOf(st("check"), st("wait"))))
        assertEquals(ProbeVerdict.NO_DATA, AceProbeProtocol.verdict(listOf(st("dl", 0, 0, 0), st("dl", 0, 0, 0))))   // "dl" sin datos no basta
        assertEquals(ProbeVerdict.ENGINE_ERROR, AceProbeProtocol.verdict(listOf(ProbeStat(null, 0, 0, 0, null, "unknown content"))))
    }

    /** Motor falso con la API HTTP real (getstream?format=json → stat → stop) sobre un socket local. */
    private fun fakeEngine(stats: List<String>, startBody: String? = null): Pair<HttpServer, MutableList<String>> {
        val requests = Collections.synchronizedList(ArrayList<String>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var i = 0
        server.createContext("/") { ex ->
            val uri = ex.requestURI.toString()
            requests += uri
            val port = server.address.port
            val body = when {
                uri.startsWith("/ace/getstream") -> startBody ?: """{"response":{"stat_url":"http://127.0.0.1:$port/ace/stat/s1","command_url":"http://127.0.0.1:$port/ace/cmd/s1","playback_url":"http://127.0.0.1:$port/ace/r/s1","is_live":1},"error":null}"""
                uri.startsWith("/ace/stat") -> stats[minOf(i++, stats.size - 1)]
                else -> """{"response":"ok","error":null}"""
            }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server to requests
    }

    @Test fun probeDetectsRealStreamingAndStopsSession() = runBlocking {
        val (server, requests) = fakeEngine(listOf(
            """{"response":{"status":"prebuf","peers":3,"speed_down":0,"downloaded":0,"progress":30},"error":null}""",
            """{"response":{"status":"dl","peers":11,"speed_down":900,"downloaded":200000},"error":null}""",
            """{"response":{"status":"dl","peers":12,"speed_down":950,"downloaded":900000},"error":null}"""
        ))
        try {
            val result = AceStreamProbe(sleep = { }).run(server.address.port, id, maxSeconds = 10)
            assertEquals(ProbeVerdict.STREAMING, result.verdict)
            assertTrue(requests.first().startsWith("/ace/getstream?id=$id&format=json"))   // Content ID intacto
            assertTrue(requests.any { it.contains("method=stop") })                           // no deja la sesión abierta
        } finally { server.stop(0) }
    }

    @Test fun probeReportsEngineOpenButNoData() = runBlocking {
        val (server, _) = fakeEngine(listOf("""{"response":{"status":"prebuf","peers":0,"speed_down":0,"downloaded":0,"progress":0},"error":null}"""))
        try {
            val result = AceStreamProbe(sleep = { }).run(server.address.port, id, maxSeconds = 5)
            assertEquals(ProbeVerdict.PREBUFFERING_ONLY, result.verdict)
            assertEquals(5, result.seconds)
        } finally { server.stop(0) }
    }

    @Test fun probeEngineRejectsContentOrIsDown() = runBlocking {
        val (server, _) = fakeEngine(emptyList(), startBody = """{"response":null,"error":"failed to load content"}""")
        try {
            val r = AceStreamProbe(sleep = { }).run(server.address.port, id)
            assertEquals(ProbeVerdict.ENGINE_ERROR, r.verdict)
            assertTrue(r.detail.contains("failed to load content"))
        } finally { server.stop(0) }
        val down = AceStreamProbe(sleep = { }).run(1, id)
        assertEquals(ProbeVerdict.ENGINE_UNREACHABLE, down.verdict)
    }
}
