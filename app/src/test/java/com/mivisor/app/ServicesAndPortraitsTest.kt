package com.mivisor.app

import com.mivisor.app.data.AgendaParser
import com.mivisor.app.data.AgendaRepository
import com.mivisor.app.data.InvalidServicesException
import com.mivisor.app.data.ServicesParser
import com.mivisor.app.data.ServicesRepository
import com.mivisor.app.model.AgendaFilters
import com.mivisor.app.model.Overall
import com.mivisor.app.model.ServiceState
import com.mivisor.app.model.ServiceText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.util.TimeZone

class ServicesAndPortraitsTest {
    private val servicesJson = """
        {"version":1,"generatedAt":"2026-10-24T18:17:00Z","overall":"degraded",
         "services":[
           {"id":"football_data","name":"Football-Data","role":"Agenda de fútbol","kind":"core","status":"ok",
            "lastAttempt":"2026-10-24T18:17:00Z","lastSuccess":"2026-10-24T18:17:00Z","consecutiveFailures":0,"lastError":null},
           {"id":"live_tennis","name":"Live Tennis API","role":"Agenda de tenis","kind":"core","status":"stale",
            "lastAttempt":"2026-10-24T18:17:00Z","lastSuccess":"2026-10-24T16:17:00Z","consecutiveFailures":2,
            "lastError":{"code":"timeout","message":"Tiempo de espera agotado","at":"2026-10-24T18:17:00Z"}},
           {"id":"futbolenlatv","name":"Fútbol en la TV","role":"Canales de televisión","kind":"aux","status":"unavailable",
            "consecutiveFailures":1,"lastError":{"code":"parse_error"}},
           {"id":"raro","status":"???"}, 5, {"sin":"id"}
         ],
         "history":[
           {"service":"live_tennis","at":"2026-10-24T17:17:00Z","type":"error","code":"timeout"},
           {"service":"live_tennis","at":"2026-10-24T18:17:00Z","type":"error","code":"timeout"},
           {"service":"live_tennis","at":"2026-10-24T15:17:00Z","type":"recovery"},
           {"service":"futbolenlatv","at":"mal"}
         ]}
    """.trimIndent()

    // ------------------------------------------------------------------ services.json

    @Test fun parsesOkStaleAndUnavailable() {
        val r = ServicesParser.parse(servicesJson)
        assertEquals(Overall.DEGRADED, r.overall)
        val byId = r.services.associateBy { it.id }
        assertEquals(ServiceState.OK, byId["football_data"]?.state)
        assertEquals(ServiceState.STALE, byId["live_tennis"]?.state)
        assertEquals(ServiceState.UNAVAILABLE, byId["futbolenlatv"]?.state)
        assertEquals(ServiceState.UNAVAILABLE, byId["raro"]?.state)          // estado desconocido: sin crash
        assertEquals(2, byId["live_tennis"]?.consecutiveFailures)
        assertEquals("timeout", byId["live_tennis"]?.lastError?.code)
        assertNull(byId["football_data"]?.lastError)
        assertEquals(4, r.services.size)
    }

    @Test fun historyNewestFirstAndMessagesForPeople() {
        val r = ServicesParser.parse(servicesJson)
        val h = r.historyOf("live_tennis")
        assertEquals(listOf("error", "error", "recovery"), h.map { it.type })
        assertEquals("Sincronización fallida", ServiceText.event(h[0]))
        assertEquals("Servicio recuperado", ServiceText.event(h[2]))
        assertEquals("Tiempo de espera agotado", ServiceText.error("timeout"))
        assertEquals("Límite temporal de la API", ServiceText.error("rate_limited"))
        assertEquals("Error de autenticación", ServiceText.error("authentication_error"))
        assertEquals("Respuesta no válida", ServiceText.error("invalid_response"))
        assertEquals("La fuente ya no permite la consulta automática", ServiceText.error("robots_disallowed"))
        assertEquals("Ha cambiado el formato de la fuente", ServiceText.error("parse_error"))
        assertEquals("Servicio no disponible", ServiceText.error("requests.exceptions.ConnectTimeout"))   // nunca crudo
        assertEquals("Datos anteriores", ServiceText.state(ServiceState.STALE))
        assertEquals("Todo operativo", ServiceText.overall(Overall.OK))
        assertTrue(r.historyOf("futbolenlatv").isEmpty())                   // fecha inválida: se ignora
    }

    @Test fun dateFormat() {
        val ms = AgendaParser.parseIsoMillis("2026-10-24T18:17:00Z")!!
        assertEquals("24 oct · 20:17", ServiceText.date(ms, TimeZone.getTimeZone("Europe/Madrid")))
        assertEquals("—", ServiceText.date(null))
    }

    @Test fun invalidOrFutureServicesJson() {
        for (bad in listOf("", "<html>", "{}", """{"version":1}""", """{"version":2,"services":[]}""")) {
            try { ServicesParser.parse(bad); fail(bad) } catch (e: InvalidServicesException) { /* ok */ }
        }
    }

    @Test fun servicesUrlNextToAgenda() {
        assertEquals("https://raw.githubusercontent.com/u/r/agenda-data/services.json",
            ServicesRepository.urlFor("https://raw.githubusercontent.com/u/r/agenda-data/agenda.json"))
        assertEquals("https://x.example/data/services.json", ServicesRepository.urlFor("https://x.example/data/"))
        assertEquals("", ServicesRepository.urlFor(" "))
    }

    @Test fun repositoryCachesEtagAndWorksOffline() = runBlocking {
        val dir = Files.createTempDirectory("services").toFile()
        var now = 1_000_000L
        var calls = 0
        var online = true
        val repo = ServicesRepository(dir, clock = { now }, download = { _, etag ->
            calls++
            when {
                !online -> throw IOException("offline")
                etag == "s1" -> AgendaRepository.Download(304, null, null)
                else -> AgendaRepository.Download(200, servicesJson, "s1")
            }
        })
        val first = repo.load("https://x/services.json")
        assertFalse(first.offline)
        assertEquals(1, calls)
        repo.load("https://x/services.json")                      // caché corta: sin red
        assertEquals(1, calls)
        now += 11 * 60_000L
        repo.load("https://x/services.json")                      // 304
        assertEquals(2, calls)
        repo.load("https://x/services.json", force = true)        // "Actualizar": solo vuelve a descargar
        assertEquals(3, calls)
        online = false
        val offline = repo.load("https://x/services.json", force = true)
        assertTrue(offline.offline)                                // última copia, marcada
        assertEquals(Overall.DEGRADED, offline.report.overall)
    }

    @Test fun missingServicesJsonIsAClearError() = runBlocking {
        val repo = ServicesRepository(Files.createTempDirectory("s404").toFile(),
            download = { _, _ -> AgendaRepository.Download(404, null, null) })
        try { repo.load("https://x/services.json"); fail() } catch (e: InvalidServicesException) {
            assertTrue(e.message!!.contains("Todavía no hay diagnóstico"))
        }
        val broken = ServicesRepository(Files.createTempDirectory("sbad").toFile(),
            download = { _, _ -> AgendaRepository.Download(200, "{roto", null) })
        try { broken.load("https://x/services.json"); fail() } catch (e: InvalidServicesException) { /* ok */ }
    }

    // ------------------------------------------------------------------ retratos, UFC, hora por confirmar

    private val agendaJson = """
        {"version":1,"stats":{"ufc":{"status":"ok"}},"events":[
          {"id":"tennis-1","sport":"tenis","start":"2026-10-28T14:00:00+01:00","home":"Carlos Alcaraz","away":"Jannik Sinner",
           "title":"Carlos Alcaraz - Jannik Sinner","homeImage":"https://r2.example/alcaraz.png","channels":[]},
          {"id":"ufc-ufc-333","sport":"ufc","competition":"UFC 333","start":"2026-10-25T03:00:00+01:00",
           "home":"Alexander Volkanovski","away":"Movsar Evloev","title":"Alexander Volkanovski - Movsar Evloev",
           "homeImage":"https://cito.example/volk.png","awayImage":"https://cito.example/evloev.png",
           "card":[{"red":"A","blue":"B"}],"channels":[{"name":"DAZN","note":"PPV"}]},
          {"id":"ufc-fn","sport":"ufc","competition":"UFC Fight Night","start":"2026-10-31T13:00:00+01:00","title":"UFC Fight Night","timeTbd":true},
          {"id":"football-1","sport":"futbol","start":"2026-10-24T21:00:00+02:00","home":"Atleti","away":"Deportivo",
           "title":"Atleti - Deportivo","homeLogo":"https://crests.example/atm.png","homeImage":"no-es-url"}
        ]}
    """.trimIndent()

    @Test fun tennisPortraitsAndPlaceholder() {
        val ev = AgendaParser.parse(agendaJson).events.first { it.id == "tennis-1" }
        assertTrue(ev.isPersonVsPerson)
        assertEquals("https://r2.example/alcaraz.png", ev.homeVisual)
        assertNull(ev.awayVisual)                                   // sin foto: placeholder (iniciales)
    }

    @Test fun ufcPortraitsSportAndChannels() {
        val agenda = AgendaParser.parse(agendaJson)
        val ev = agenda.events.first { it.id == "ufc-ufc-333" }
        assertEquals("ufc", ev.sport)
        assertEquals("https://cito.example/volk.png", ev.homeVisual)
        assertEquals("https://cito.example/evloev.png", ev.awayVisual)
        assertEquals("PPV", ev.channels.single().note)
        assertTrue("ufc" in agenda.categories)
        assertTrue(AgendaFilters.bySport(agenda.events, "ufc", null).all { it.sport == "ufc" })
        assertFalse(AgendaFilters.hasSubFilters("ufc"))
    }

    @Test fun footballKeepsCrestsAndIgnoresBadImages() {
        val ev = AgendaParser.parse(agendaJson).events.first { it.id == "football-1" }
        assertFalse(ev.isPersonVsPerson)
        assertEquals("https://crests.example/atm.png", ev.homeVisual)
        assertNull(ev.homeImage)                                    // "no-es-url" se descarta
    }

    @Test fun timeToBeConfirmed() {
        val agenda = AgendaParser.parse(agendaJson)
        assertTrue(agenda.events.first { it.id == "ufc-fn" }.timeTbd)
        assertFalse(agenda.events.first { it.id == "ufc-ufc-333" }.timeTbd)
    }

    @Test fun oldAgendaWithoutNewFieldsStillWorks() {
        val ev = AgendaParser.parse(
            """{"version":1,"events":[{"id":"t","sport":"tenis","start":"2026-10-28T14:00:00+01:00","home":"A","away":"B","title":"A - B"}]}"""
        ).events.single()
        assertNull(ev.homeImage)
        assertFalse(ev.timeTbd)
        assertTrue(ev.channels.isEmpty())
    }
}
