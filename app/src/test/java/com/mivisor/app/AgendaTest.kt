package com.mivisor.app

import com.mivisor.app.data.AgendaParser
import com.mivisor.app.data.AgendaRepository
import com.mivisor.app.data.ChannelMatcher
import com.mivisor.app.data.InvalidAgendaException
import com.mivisor.app.model.Channel
import com.mivisor.app.model.ChannelKind
import com.mivisor.app.model.EventStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

class AgendaTest {

    private val json = """{"version":1,"source":"futbolenlatv.es","generatedAt":"2026-10-24T10:00:00Z",
      "events":[
        {"id":"b","sport":"futbol","competition":"Premier League","start":"2026-10-24T21:00:00+02:00",
         "home":"Arsenal","away":"Chelsea","title":"Arsenal - Chelsea","channels":[{"name":"DAZN 1","dial":"M72"}]},
        {"id":"a","sport":"futbol","competition":"La Liga EA Sports","start":"2026-10-24T18:00:00+02:00",
         "home":"Real Madrid","away":"Sevilla","title":"Real Madrid - Sevilla","competitionLogo":"https://x/laliga.png",
         "channels":[{"name":"DAZN LaLiga","dial":"M55 O113","logo":"https://x/dazn.png"},{"name":"DAZN","note":"Ver en directo"}]},
        {"id":"c","sport":"motor","start":"no-es-fecha","title":"Roto","channels":[]},
        {"id":"d","sport":"motor","start":"2026-10-25T08:00:00","title":"GP de Singapur","channels":[]}
      ]}"""

    @Test fun parsesAndSorts() {
        val agenda = AgendaParser.parse(json)
        assertEquals(listOf("a", "b", "d"), agenda.events.map { it.id })
        val rm = agenda.events[0]
        assertEquals(1_792_857_600_000L, rm.startMillis) // 2026-10-24T16:00:00Z
        assertEquals("M55 O113", rm.channels[0].dial)
        assertEquals("Ver en directo", rm.channels[1].note)
        assertEquals("https://x/laliga.png", rm.competitionLogo)
        assertEquals("https://x/dazn.png", rm.channels[0].logo)
        assertNull(rm.channels[1].logo)
        assertTrue(rm.isMatch)
        assertFalse(agenda.events[2].isMatch)
        assertEquals(listOf("futbol", "motor"), agenda.sports)
        assertEquals(1_792_836_000_000L, agenda.generatedAt)
    }

    @Test fun isoParsing() {
        assertEquals(0L, AgendaParser.parseIsoMillis("1970-01-01T00:00:00Z"))
        assertEquals(1_792_857_600_000L, AgendaParser.parseIsoMillis("2026-10-24T16:00Z"))
        // Sin zona → Europe/Madrid: el 25/10/2026 ya es horario de invierno (+01:00).
        assertEquals(1_792_911_600_000L, AgendaParser.parseIsoMillis("2026-10-25T08:00:00"))
        assertEquals(AgendaParser.parseIsoMillis("2026-10-24T18:00:00+0200"), AgendaParser.parseIsoMillis("2026-10-24T16:00:00Z"))
        assertNull(AgendaParser.parseIsoMillis("24/10/2026"))
    }

    @Test fun status() {
        val ev = AgendaParser.parse(json).events[0]
        assertEquals(EventStatus.UPCOMING, ev.status(ev.startMillis - 3_600_000))
        assertEquals(EventStatus.LIVE, ev.status(ev.startMillis + 60_000))
        assertEquals(EventStatus.FINISHED, ev.status(ev.startMillis + 3 * 3_600_000))
    }

    @Test fun rejectsFutureVersion() {
        try { AgendaParser.parse("""{"version":9,"events":[]}"""); fail() } catch (e: InvalidAgendaException) { /* ok */ }
    }

    private fun ch(name: String) = Channel(name, name, "http://x/${name.hashCode()}", null, ChannelKind.WEB, "", 0)

    @Test fun channelMatching() {
        val mine = listOf(
            ch("DAZN LALIGA FHD"), ch("DAZN LaLiga 2 1080"), ch("Movistar LaLiga 1080p"),
            ch("DAZN 1 HD"), ch("DAZN 2"), ch("Liga de Campeones 1"), ch("M. LaLiga")
        )
        val m = ChannelMatcher(mine)
        assertEquals(listOf("DAZN LALIGA FHD"), m.match("DAZN LaLiga").map { it.name })
        assertEquals(listOf("Movistar LaLiga 1080p"), m.match("M+ LaLiga TV").map { it.name })
        assertEquals(listOf("DAZN 1 HD"), m.match("DAZN 1").map { it.name })
        // "DAZN" a secas es ambiguo: no se enlaza con nada.
        assertTrue(m.match("DAZN").isEmpty())
        assertEquals(listOf("Liga de Campeones 1"), m.match("Champions League 1").map { it.name })
        assertTrue(ChannelMatcher(emptyList()).isEmpty)
    }

    @Test fun sameChannelFromSeveralCodesGivesSeveralOptions() {
        fun src(name: String, url: String, source: String) =
            Channel("$source:$url", name, url, null, ChannelKind.ACE, "", 0, source)
        val mine = listOf(
            src("DAZN LaLiga 1080", "acestream://aaa", "Deportes"),
            src("LALIGA DAZN FHD", "acestream://bbb", "Fútbol"),
            src("DAZN LaLiga", "acestream://ccc", "Extra"),
            src("DAZN LaLiga", "acestream://aaa", "Copia"),      // mismo enlace: no es otra opción
            src("DAZN LaLiga 2", "acestream://ddd", "Deportes"), // otro canal
            src("Movistar LaLiga", "acestream://eee", "Deportes")
        )
        val options = ChannelMatcher(mine).match("DAZN LaLiga")
        assertEquals(listOf("acestream://aaa", "acestream://ccc", "acestream://bbb"), options.map { it.url })
        assertEquals(listOf("Deportes", "Extra", "Fútbol"), options.map { it.source })
    }

    @Test fun repositoryCachesAndFallsBack() = runBlocking {
        val dir = Files.createTempDirectory("agenda").toFile()
        var now = 1_000_000L
        var calls = 0
        var online = true
        val repo = AgendaRepository(dir, ttlMs = 60_000, clock = { now }, download = { _, etag ->
            calls++
            when {
                !online -> throw IOException("offline")
                etag == "v1" -> AgendaRepository.Download(304, null, null)
                else -> AgendaRepository.Download(200, json, "v1")
            }
        })
        val first = repo.load("https://x/agenda.json")
        assertEquals(3, first.agenda.events.size)
        assertEquals(1, calls)

        repo.load("https://x/agenda.json")          // fresca: sin red
        assertEquals(1, calls)

        now += 120_000
        val notModified = repo.load("https://x/agenda.json")
        assertEquals(2, calls)
        assertFalse(notModified.offline)

        now += 120_000
        online = false
        val offline = repo.load("https://x/agenda.json")
        assertTrue(offline.offline)
        assertEquals(3, offline.agenda.events.size)

        try {
            AgendaRepository(Files.createTempDirectory("vacia").toFile()).load("")
            fail()
        } catch (e: InvalidAgendaException) { /* ok */ }
    }
}
