package com.mivisor.app

import com.mivisor.app.data.AgendaParser
import com.mivisor.app.data.TvChannelsRepository
import com.mivisor.app.model.AgendaChannel
import com.mivisor.app.model.AgendaEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

/** Canales pedidos a TheSportsDB al abrir un partido. Sin Internet: respuestas simuladas. */
class TvChannelsTest {
    private fun ev(id: String = "football-1", tvId: String? = "2501", channels: List<AgendaChannel> = emptyList()) =
        AgendaEvent(id, "futbol", "LaLiga", 1_000L, "Real Madrid", "Sevilla", "Real Madrid - Sevilla", channels, null, null, tvId = tvId)

    private fun row(channel: String?, country: String?, id: String = "2501") =
        """{"idEvent":"$id","strChannel":${channel?.let { "\"$it\"" } ?: "null"},"strCountry":${country?.let { "\"$it\"" } ?: "null"},"strLogo":"https://x/logo.png"}"""

    private fun body(vararg rows: String) = """{"tvevent":[${rows.joinToString(",")}]}"""

    private fun pairs(list: List<AgendaChannel>) = list.map { it.name to it.note }

    // ---------------------------------------------------------------- broadcasters de cualquier país

    @Test fun spain() = assertEquals(listOf("DAZN LaLiga" to "Spain"), pairs(TvChannelsRepository.parse(body(row("DAZN LaLiga", "Spain")))))

    @Test fun unitedKingdom() = assertEquals(listOf("Sky Sports Premier League" to "United Kingdom"),
        pairs(TvChannelsRepository.parse(body(row("Sky Sports Premier League", "United Kingdom")))))

    @Test fun unitedStates() = assertEquals(listOf("ESPN+" to "United States"), pairs(TvChannelsRepository.parse(body(row("ESPN+", "United States")))))

    @Test fun anyOtherCountry() = assertEquals(listOf("Match! Futbol 1" to "Russia"), pairs(TvChannelsRepository.parse(body(row("Match! Futbol 1", "Russia")))))

    @Test fun severalCountriesInTheSameEvent() {
        val list = TvChannelsRepository.parse(body(
            row("DAZN LaLiga", "Spain"), row("beIN Sports", "France"), row("Sky Sports", "United Kingdom"),
            row("ESPN", "United States"), row("Nova Sport 4 CZ", "Czechia"), row("Disney+ Hotstar", "India")
        ))
        assertEquals(6, list.size)
        assertEquals(setOf("Spain", "France", "United Kingdom", "United States", "Czechia", "India"), list.map { it.note }.toSet())
    }

    @Test fun noBroadcaster() {
        assertTrue(TvChannelsRepository.parse("""{"tvevent":null}""").isEmpty())
        assertTrue(TvChannelsRepository.parse("").isEmpty())
        assertTrue(TvChannelsRepository.parse("""{"tvevent":"Invalid id"}""").isEmpty())
        assertTrue(TvChannelsRepository.parse("<html>").isEmpty())
    }

    @Test fun duplicatesRemovedButSameChannelOtherCountryKept() {
        val list = TvChannelsRepository.parse(body(row("DAZN", "Spain"), row("DAZN", "Spain"), row(" dazn ", "SPAIN"), row("DAZN", "Italy")))
        assertEquals(listOf("DAZN" to "Spain", "DAZN" to "Italy"), pairs(list))
    }

    @Test fun countryGoesToNoteNeverToName() {
        val c = TvChannelsRepository.parse(body(row("DAZN LaLiga", "Spain"))).single()
        assertEquals("DAZN LaLiga", c.name)
        assertEquals("Spain", c.note)
        assertEquals("https://x/logo.png", c.logo)          // strLogo de TheSportsDB (solo visual)
        assertNull(c.dial)
    }

    @Test fun countryAbsent() {
        val list = TvChannelsRepository.parse(body(row("Canal+ Sport", null), row("TNT Sports", "")))
        assertEquals(listOf("Canal+ Sport" to null, "TNT Sports" to null), pairs(list))
    }

    @Test fun rowsWithoutChannelOrFromAnotherEventAreIgnored() {
        val list = TvChannelsRepository.parse(body(row(null, "Spain"), row("Otro", "Peru", id = "9999"), row("Movistar Plus+", "Spain")), "2501")
        assertEquals(listOf("Movistar Plus+" to "Spain"), pairs(list))
    }

    // ---------------------------------------------------------------- cuándo se llama y caché

    private class Counter(var calls: Int = 0, var response: () -> String)

    private fun repo(dir: java.io.File, counter: Counter, now: () -> Long = { 1_000_000L }) =
        TvChannelsRepository(dir, clock = now, fetch = { counter.calls++; counter.response() })

    @Test fun eventWithChannelsNeverCalls() = runBlocking {
        val counter = Counter { body(row("X", "Spain")) }
        val r = repo(Files.createTempDirectory("tv").toFile(), counter)
        val result = r.channelsFor(ev(channels = listOf(AgendaChannel("DAZN LaLiga", null, "Spain"))))
        assertEquals(listOf("DAZN LaLiga" to "Spain"), pairs(result.channels))
        assertEquals(0, counter.calls)
    }

    @Test fun eventWithoutTvIdNeverCalls() = runBlocking {
        val counter = Counter { body(row("X", "Spain")) }
        val result = repo(Files.createTempDirectory("tv").toFile(), counter).channelsFor(ev(tvId = null))
        assertTrue(result.channels.isEmpty())
        assertEquals(0, counter.calls)
    }

    @Test fun firstOpenCallsThenItIsKeptInMemoryAndDisk() = runBlocking {
        val dir = Files.createTempDirectory("tv").toFile()
        val counter = Counter { body(row("DAZN LaLiga", "Spain"), row("beIN Sports", "France")) }
        val r = repo(dir, counter)
        assertNull(r.known(ev()))                                  // aún no se sabe: hay que pedirlo
        assertEquals(2, r.channelsFor(ev()).channels.size)
        assertEquals(1, counter.calls)
        r.channelsFor(ev())                                        // segunda vez: memoria
        assertEquals(1, counter.calls)
        val reopened = repo(dir, counter)                          // app reiniciada: disco
        assertEquals(listOf("DAZN LaLiga" to "Spain", "beIN Sports" to "France"), pairs(reopened.channelsFor(ev()).channels))
        assertEquals(1, counter.calls)
    }

    @Test fun emptyAnswerIsRetriedLater() = runBlocking {
        var now = 1_000_000L
        val counter = Counter { """{"tvevent":null}""" }
        val r = repo(Files.createTempDirectory("tv").toFile(), counter) { now }
        assertTrue(r.channelsFor(ev()).channels.isEmpty())
        r.channelsFor(ev())
        assertEquals(1, counter.calls)                             // vacío reciente: no se insiste
        now += 3 * 3_600_000L
        counter.response = { body(row("DAZN", "Spain")) }
        assertEquals(1, r.channelsFor(ev()).channels.size)         // más tarde sí se vuelve a pedir
        assertEquals(2, counter.calls)
    }

    @Test fun failureIsReportedAndNotCached() = runBlocking {
        val counter = Counter { throw IOException("HTTP 429") }
        val r = repo(Files.createTempDirectory("tv").toFile(), counter)
        val result = r.channelsFor(ev())
        assertTrue(result.failed)
        assertTrue(result.channels.isEmpty())
        counter.response = { body(row("DAZN", "Spain")) }
        val retry = r.channelsFor(ev())
        assertFalse(retry.failed)
        assertEquals(2, counter.calls)
    }

    @Test fun requestUsesPublicKeyAndTvId() = runBlocking {
        var url = ""
        val r = TvChannelsRepository(Files.createTempDirectory("tv").toFile(), fetch = { url = it; """{"tvevent":null}""" })
        r.channelsFor(ev(tvId = "2494052"))
        assertEquals("https://www.thesportsdb.com/api/v1/json/123/lookuptv.php?id=2494052", url)
    }

    // ---------------------------------------------------------------- agenda.json

    @Test fun parserReadsTvIdAndCategoriesIncludeSportsWithoutEvents() {
        val agenda = AgendaParser.parse(
            """{"version":1,"stats":{"football":{"status":"ok"},"tennis":{"status":"ok"},"formula1":{"status":"ok"},"motogp":{"status":"ok","events":0}},
               "events":[{"id":"football-1","sport":"futbol","start":"2026-10-24T21:00:00+02:00","title":"A - B","tvId":"2501","channels":[]},
                         {"id":"tennis-1","sport":"tenis","start":"2026-10-24T14:00:00+02:00","title":"C - D","channels":[]}]}"""
        )
        assertEquals("2501", agenda.events.first { it.sport == "futbol" }.tvId)
        assertNull(agenda.events.first { it.sport == "tenis" }.tvId)
        assertEquals(listOf("futbol", "tenis", "formula1", "motogp"), agenda.categories)
        assertEquals(0, agenda.eventCount("motogp"))
        // Sin "stats": solo los deportes con eventos.
        val old = AgendaParser.parse("""{"version":1,"events":[{"id":"x","sport":"tenis","start":"2026-10-24T14:00:00+02:00","title":"C - D"}]}""")
        assertEquals(listOf("tenis"), old.categories)
    }
}
