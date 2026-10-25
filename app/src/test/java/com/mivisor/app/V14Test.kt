package com.mivisor.app

import com.mivisor.app.services.AceProbeProtocol
import com.mivisor.app.services.AceStreamProbe
import com.mivisor.app.services.ProbeStat
import com.mivisor.app.services.ProbeVerdict
import com.mivisor.app.ui.kit.TextFit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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

    /**
     * Motor falso con la API HTTP real (getstream?format=json → stat → stop), inyectado como función HTTP.
     * (Sin com.sun.net.httpserver: no existe en el classpath de los tests unitarios de Android.)
     */
    private class FakeEngine(private val stats: List<String>, private val startBody: String? = null) {
        val port = 6878
        val requests: MutableList<String> = Collections.synchronizedList(ArrayList<String>())
        private var i = 0
        val http: suspend (String) -> String? = { url ->
            val path = url.removePrefix("http://127.0.0.1:$port")
            requests += path
            when {
                path.startsWith("/ace/getstream") -> startBody ?: """{"response":{"stat_url":"http://127.0.0.1:$port/ace/stat/s1","command_url":"http://127.0.0.1:$port/ace/cmd/s1","playback_url":"http://127.0.0.1:$port/ace/r/s1","is_live":1},"error":null}"""
                path.startsWith("/ace/stat") -> stats[minOf(i++, stats.size - 1)]
                else -> """{"response":"ok","error":null}"""
            }
        }
    }

    @Test fun probeDetectsRealStreamingAndStopsSession() = runBlocking {
        val engine = FakeEngine(listOf(
            """{"response":{"status":"prebuf","peers":3,"speed_down":0,"downloaded":0,"progress":30},"error":null}""",
            """{"response":{"status":"dl","peers":11,"speed_down":900,"downloaded":200000},"error":null}""",
            """{"response":{"status":"dl","peers":12,"speed_down":950,"downloaded":900000},"error":null}"""
        ))
        val result = AceStreamProbe(http = engine.http, sleep = { }).run(engine.port, id, maxSeconds = 10)
        assertEquals(ProbeVerdict.STREAMING, result.verdict)
        assertTrue(engine.requests.first().startsWith("/ace/getstream?id=$id&format=json"))   // Content ID intacto
        assertTrue(engine.requests.any { it.contains("method=stop") })                           // no deja la sesión abierta
    }

    @Test fun probeReportsEngineOpenButNoData() = runBlocking {
        val engine = FakeEngine(listOf("""{"response":{"status":"prebuf","peers":0,"speed_down":0,"downloaded":0,"progress":0},"error":null}"""))
        val result = AceStreamProbe(http = engine.http, sleep = { }).run(engine.port, id, maxSeconds = 5)
        assertEquals(ProbeVerdict.PREBUFFERING_ONLY, result.verdict)
        assertEquals(5, result.seconds)
        assertTrue(engine.requests.any { it.contains("method=stop") })
    }

    @Test fun probeEngineRejectsContentOrIsDown() = runBlocking {
        val engine = FakeEngine(emptyList(), startBody = """{"response":null,"error":"failed to load content"}""")
        val r = AceStreamProbe(http = engine.http, sleep = { }).run(engine.port, id)
        assertEquals(ProbeVerdict.ENGINE_ERROR, r.verdict)
        assertTrue(r.detail.contains("failed to load content"))
        // Ace Stream para Android sin Premium: no es un error del canal.
        val premium = FakeEngine(emptyList(), startBody = """{"response":null,"error":"To continue, you need to activate premium"}""")
        val p = AceStreamProbe(http = premium.http, sleep = { }).run(premium.port, id)
        assertEquals(ProbeVerdict.PREMIUM_REQUIRED, p.verdict)
        assertTrue(p.detail.contains("no es un fallo del canal", ignoreCase = true))
        // Motor caído: la función HTTP no obtiene respuesta.
        val down = AceStreamProbe(http = { null }, sleep = { }).run(6878, id)
        assertEquals(ProbeVerdict.ENGINE_UNREACHABLE, down.verdict)
    }
}
