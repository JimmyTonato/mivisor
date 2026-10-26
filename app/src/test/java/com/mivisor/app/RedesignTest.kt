package com.mivisor.app

import com.mivisor.app.data.ChannelGrouping
import com.mivisor.app.data.Quality
import com.mivisor.app.model.CalendarFilters
import com.mivisor.app.model.CalendarQuery
import com.mivisor.app.model.CalendarRange
import com.mivisor.app.model.Channel
import com.mivisor.app.model.ChannelKind
import com.mivisor.app.model.Competition
import com.mivisor.app.model.CompetitionSummaries
import com.mivisor.app.model.DayClock
import com.mivisor.app.model.AgendaEvent
import com.mivisor.app.model.HomeFeedBuilder
import com.mivisor.app.model.LiveFilters
import com.mivisor.app.model.PlayRequest
import com.mivisor.app.model.PlaybackEntry
import com.mivisor.app.model.displayTitle
import com.mivisor.app.services.AceError
import com.mivisor.app.services.AcePhase
import com.mivisor.app.services.AceStatus
import com.mivisor.app.services.Indicator
import com.mivisor.app.services.PlaybackNeeds
import com.mivisor.app.services.PlaybackPlanner
import com.mivisor.app.services.PrepDecision
import com.mivisor.app.services.PrepProblem
import com.mivisor.app.services.QuickServiceText
import com.mivisor.app.services.RowAction
import com.mivisor.app.services.StepState
import com.mivisor.app.services.WarpPhase
import com.mivisor.app.services.WarpStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Rediseño (v11): Inicio, competiciones, En directo, Calendario, canales agrupados y preparación de la reproducción. */
class RedesignTest {
    private val zone = TimeZone.getTimeZone("Europe/Madrid")
    private val clock = DayClock(zone)

    /** Sábado 24/10/2026 a las 22:42 (hora de Madrid). */
    private val now = at(2026, 10, 24, 22, 42)

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int): Long = Calendar.getInstance(zone).run {
        clear(); set(y, m - 1, d, h, min, 0); timeInMillis
    }

    private fun ev(
        id: String, sport: String = "futbol", competition: String? = "LaLiga", start: Long = now,
        home: String? = "Atlético de Madrid", away: String? = "Deportivo"
    ) = AgendaEvent(id, sport, competition, start, home, away, "$home - $away", emptyList(), null, null)

    private val hour = 3_600_000L

    // ---------------------------------------------------------------- competiciones

    @Test fun competitionMapping() {
        assertEquals(Competition.LALIGA, Competition.of(ev("1", competition = "LaLiga EA Sports")))
        assertEquals(Competition.CHAMPIONS, Competition.of(ev("2", competition = "UEFA Champions League")))
        assertEquals(Competition.FUTBOL, Competition.of(ev("3", competition = "Segunda División")))
        assertEquals(Competition.FORMULA1, Competition.of(ev("4", sport = "formula1", competition = "GP de Japón")))
        assertEquals(Competition.MOTOGP, Competition.of(ev("5", sport = "motogp", competition = null)))
        assertEquals(Competition.ATP, Competition.of(ev("6", sport = "tenis", competition = "ATP Paris Masters")))
        assertEquals(Competition.WTA, Competition.of(ev("7", sport = "tenis", competition = "WTA Finals")))
        assertEquals(Competition.TENIS, Competition.of(ev("8", sport = "tenis", competition = "Copa Davis")))
        assertEquals(Competition.UFC, Competition.of(ev("9", sport = "ufc")))
        assertEquals(Competition.OTHER, Competition.of(ev("10", sport = "curling")))
    }

    @Test fun competitionSummaries() {
        val events = listOf(
            ev("a", start = now - hour),                                          // LaLiga en directo
            ev("b", start = now + hour / 2),                                      // LaLiga hoy
            ev("c", start = at(2026, 10, 25, 14, 0)),                             // LaLiga mañana
            ev("d", competition = "Champions League", start = at(2026, 10, 25, 21, 0)),
            ev("e", sport = "formula1", competition = "GP de México", start = at(2026, 10, 25, 20, 0), home = null, away = null),
            ev("f", sport = "motogp", competition = "GP de Malasia", start = at(2026, 11, 8, 8, 0), home = null, away = null)
        )
        val list = CompetitionSummaries.build(events, now, clock)
        val laliga = list.first { it.competition == Competition.LALIGA }
        assertEquals("2 hoy", laliga.text)
        assertEquals("1 en directo", laliga.liveText)
        assertEquals("Próximo: mañana", list.first { it.competition == Competition.CHAMPIONS }.text)
        assertEquals("Fin de semana", list.first { it.competition == Competition.FORMULA1 }.text)
        assertEquals("Próxima carrera: 8 nov", list.first { it.competition == Competition.MOTOGP }.text)
        // Las fijas aparecen aunque no tengan eventos.
        assertEquals("Sin eventos próximos", list.first { it.competition == Competition.PREMIER }.text)
        // Orden: el del enum (LaLiga, Champions, Premier, …, F1, MotoGP).
        assertEquals(Competition.LALIGA, list.first().competition)
        assertTrue(list.indexOfFirst { it.competition == Competition.FORMULA1 } < list.indexOfFirst { it.competition == Competition.MOTOGP })
    }

    // ---------------------------------------------------------------- Inicio

    @Test fun homeFeedHeroLiveAndSoon() {
        val events = listOf(
            ev("live-serie-a", competition = "Serie A", start = now - hour, home = "Lazio", away = "Parma"),
            ev("live-laliga", start = now - hour / 2),
            ev("soon-1", competition = "Premier League", start = now + hour, home = "Aston Villa", away = "Man City"),
            ev("soon-2", start = now + 2 * hour, home = "Rayo", away = "Elche"),
            ev("soon-3", competition = "Serie A", start = now + 3 * hour, home = "Cagliari", away = "Bologna"),
            ev("finished", start = now - 6 * hour),
            ev("far", start = now + 5 * 24 * hour)
        )
        val feed = HomeFeedBuilder.build(events, now, clock)
        assertEquals("live-laliga", feed.hero?.id)                               // LaLiga interesa más que Serie A
        assertEquals(listOf("live-laliga", "live-serie-a"), feed.live.map { it.id })
        assertEquals(listOf("soon-1", "soon-2", "soon-3"), feed.soon.map { it.id })   // no es toda la agenda
        // Con fuentes del usuario, se prefiere lo que se puede ver.
        val withOptions = HomeFeedBuilder.build(events, now, clock) { if (it.id == "live-serie-a") 2 else 0 }
        assertEquals("live-serie-a", withOptions.hero?.id)
    }

    @Test fun homeHeroWithoutLiveIsNextRelevant() {
        val events = listOf(
            ev("small", competition = "Segunda División", start = now + hour),
            ev("big", competition = "Champions League", start = now + 3 * hour)
        )
        assertEquals("big", HomeFeedBuilder.build(events, now, clock).hero?.id)
        assertNull(HomeFeedBuilder.build(emptyList(), now, clock).hero)
    }

    // ---------------------------------------------------------------- En directo

    @Test fun liveOnlyActiveEvents() {
        val events = listOf(
            ev("f", start = now - hour), ev("t", sport = "tenis", competition = "ATP", start = now - hour),
            ev("up", start = now + hour), ev("old", start = now - 10 * hour)
        )
        assertEquals(setOf("f", "t"), LiveFilters.apply(events, now, null).map { it.id }.toSet())
        assertEquals(listOf("t"), LiveFilters.apply(events, now, "tenis").map { it.id })
        assertEquals(listOf("futbol", "tenis", "formula1", "motogp"), LiveFilters.sports(emptyList()))
    }

    // ---------------------------------------------------------------- Calendario

    @Test fun calendarRangesAndChips() {
        val events = listOf(
            ev("today-done", start = now - 6 * hour),
            ev("today", start = now + hour / 2),
            ev("tomorrow", competition = "Premier League", start = at(2026, 10, 25, 16, 0)),
            ev("week", sport = "tenis", competition = "WTA Finals", start = at(2026, 10, 28, 18, 0)),
            ev("later", start = at(2026, 11, 5, 21, 0)),
            ev("ligue", competition = "Ligue 1", start = at(2026, 10, 26, 21, 0))
        )
        assertEquals(listOf("today", "today-done"), CalendarFilters.apply(events, CalendarQuery(CalendarRange.TODAY), now, clock).map { it.id })
        assertEquals(listOf("tomorrow"), CalendarFilters.apply(events, CalendarQuery(CalendarRange.TOMORROW), now, clock).map { it.id })
        assertEquals(
            listOf("today-done", "today", "tomorrow", "ligue", "week"),
            CalendarFilters.apply(events, CalendarQuery(CalendarRange.WEEK), now, clock).map { it.id }
        )
        assertEquals(listOf("later"), CalendarFilters.apply(events, CalendarQuery(CalendarRange.DATE, dayOffset = 12), now, clock).map { it.id })
        assertEquals(
            listOf("week"),
            CalendarFilters.apply(events, CalendarQuery(CalendarRange.WEEK, competition = CalendarFilters.TENNIS_KEY), now, clock).map { it.id }
        )
        val chips = CalendarFilters.chips(events).map { it.label }
        assertEquals(listOf("Todos", "LaLiga", "Champions", "Premier", "Serie A", "Bundesliga", "F1", "MotoGP", "Tenis"), chips.take(9))
        assertTrue("Ligue 1" in chips)                                           // otras, solo si tienen eventos
        assertFalse("Conference" in chips)
    }

    @Test fun competitionPageTemplates() {
        val football = listOf(ev("l", start = now - hour), ev("h", start = now + hour), ev("p", start = at(2026, 10, 30, 21, 0)))
        assertEquals(listOf("En directo", "Hoy", "Próximos"), CalendarFilters.competitionSections(Competition.LALIGA, football, now, clock).map { it.first })
        val f1 = listOf(
            ev("q", sport = "formula1", start = at(2026, 10, 25, 20, 0), home = null, away = null),
            ev("next", sport = "formula1", start = at(2026, 11, 8, 15, 0), home = null, away = null)
        )
        val sections = CalendarFilters.competitionSections(Competition.FORMULA1, f1, now, clock)
        assertEquals(listOf("Este fin de semana", "Próximos grandes premios"), sections.map { it.first })
    }

    // ---------------------------------------------------------------- Canales agrupados

    private fun ch(name: String, url: String = "acestream://${name.hashCode()}") =
        Channel(name, name, url, null, ChannelKind.ACE, "XX", 0)

    @Test fun channelQualityAndNames() {
        assertEquals(Quality.FHD, ChannelGrouping.quality("DAZN 1 FHD"))
        assertEquals(Quality.FHD, ChannelGrouping.quality("M+ LaLiga 1080p"))
        assertEquals(Quality.FHD, ChannelGrouping.quality("DAZN 1 Full HD"))
        assertEquals(Quality.UHD, ChannelGrouping.quality("Eurosport 4K"))
        assertEquals(Quality.HD, ChannelGrouping.quality("Arena Premium 1 HD"))
        assertNull(ChannelGrouping.quality("Teledeporte"))
        assertEquals("DAZN 1", ChannelGrouping.cleanName("DAZN 1 FHD [ES]"))
        assertEquals("M+ LaLiga", ChannelGrouping.cleanName("M+ LaLiga 1080p"))
        assertEquals(ChannelGrouping.key("DAZN 1 FHD"), ChannelGrouping.key("dazn 1 hd"))
        assertTrue(ChannelGrouping.key("DAZN 1") != ChannelGrouping.key("DAZN 2"))      // los números importan
        assertEquals(ChannelGrouping.key("Movistar LaLiga"), ChannelGrouping.key("M+ LaLiga"))
    }

    @Test fun groupsSameChannelFromSeveralProviders() {
        val groups = ChannelGrouping.group(
            listOf(
                Triple("dazn", "DAZN", listOf(ch("DAZN 1 FHD", "acestream://a"), ch("DAZN F1", "acestream://f1"))),
                Triple("orange", "Orange", listOf(ch("DAZN 1 HD", "acestream://b"), ch("Teledeporte", "acestream://t"))),
                Triple("arena", "Arena", listOf(ch("DAZN 1", "acestream://c"), ch("dazn 1 fhd", "acestream://a")))   // enlace repetido
            )
        )
        val dazn1 = groups.first { it.name == "DAZN 1" }                        // solo hay DAZN 1: no se parte en "DAZN"
        assertEquals(listOf("DAZN 1"), dazn1.variants.map { it.name })
        assertEquals(3, dazn1.sources.size)                                      // "3 fuentes"; el enlace repetido cuenta una vez
        assertEquals(listOf("DAZN", "Orange", "Arena"), dazn1.providers)
        assertEquals(Quality.FHD, dazn1.bestQuality)
        assertEquals("DAZN", dazn1.first.providerName)                          // mejor calidad primero
        assertEquals(listOf("DAZN 1", "DAZN F1", "Teledeporte"), groups.map { it.name })
        assertEquals(1, dazn1.only("orange")?.sources?.size)
        assertNull(dazn1.only("nadie"))
    }

    // ---------------------------------------------------------------- Preparar la reproducción

    private val aceReady = AceStatus(AcePhase.READY, 6878, "3.1.74")
    private val aceStopped = AceStatus(AcePhase.STOPPED, 6878)
    private val vpnOn = WarpStatus(WarpPhase.CONNECTED)
    private val vpnOff = WarpStatus(WarpPhase.DISCONNECTED)

    @Test fun nothingNeededLaunchesDirectly() {
        assertEquals(PrepDecision.Launch, PlaybackPlanner(PlaybackNeeds(ace = false, vpn = false)).next(aceStopped, vpnOff))
        assertEquals(PrepDecision.Launch, PlaybackPlanner(PlaybackNeeds(ace = true, vpn = true)).next(aceReady, vpnOn))
    }

    @Test fun aceAndVpnPreparedTogetherWithoutCascade() {
        val p = PlaybackPlanner(PlaybackNeeds(ace = true, vpn = true))
        val first = p.next(aceStopped, vpnOff) as PrepDecision.Work
        assertTrue(first.startAce && first.connectVpn)                          // las dos órdenes a la vez
        val again = p.next(aceStopped, vpnOff) as PrepDecision.Work
        assertFalse(again.startAce || again.connectVpn)                         // cada orden una sola vez
        val mid = p.next(AceStatus(AcePhase.STARTING), WarpStatus(WarpPhase.CONNECTING)) as PrepDecision.Work
        assertEquals(StepState.WORKING, mid.ace)
        val half = p.next(aceReady, WarpStatus(WarpPhase.CONNECTING)) as PrepDecision.Work
        assertEquals(StepState.DONE, half.ace)
        assertEquals(PrepDecision.Launch, p.next(aceReady, vpnOn))               // y continúa con el mismo contenido
    }

    @Test fun vpnAuthorizationAsksOnceAndContinues() {
        val p = PlaybackPlanner(PlaybackNeeds(ace = false, vpn = true))
        assertEquals(PrepDecision.Ask(PrepProblem.VPN_AUTH), p.next(aceReady, WarpStatus(WarpPhase.PERMISSION_REQUIRED)))
        p.onAuthorizeRequested()
        assertTrue(p.next(aceReady, WarpStatus(WarpPhase.PERMISSION_REQUIRED)) is PrepDecision.Work)   // esperando a Android
        assertEquals(PrepDecision.Launch, p.next(aceReady, vpnOn))                                     // vuelve conectada → reproduce
        val q = PlaybackPlanner(PlaybackNeeds(ace = false, vpn = true))
        q.next(aceReady, WarpStatus(WarpPhase.PERMISSION_REQUIRED)); q.onAuthorizeRequested(); q.onForeground()
        assertEquals(PrepDecision.Ask(PrepProblem.VPN_AUTH), q.next(aceReady, WarpStatus(WarpPhase.PERMISSION_REQUIRED)))  // denegado: se pregunta otra vez
    }

    @Test fun failuresAskWithRetry() {
        val p = PlaybackPlanner(PlaybackNeeds(ace = true, vpn = false))
        val error = AceStatus(AcePhase.ERROR, error = AceError.START_FAILED)
        assertTrue((p.next(error, vpnOff) as PrepDecision.Work).startAce)       // un reintento automático
        assertEquals(PrepDecision.Ask(PrepProblem.ACE_FAILED), p.next(error, vpnOff))
        p.retry(PrepProblem.ACE_FAILED)
        assertTrue((p.next(error, vpnOff) as PrepDecision.Work).startAce)
        // VPN: conectando → vuelve a desconectada = fallo, no un spinner infinito.
        val v = PlaybackPlanner(PlaybackNeeds(ace = false, vpn = true))
        v.next(aceReady, vpnOff); v.next(aceReady, WarpStatus(WarpPhase.CONNECTING))
        assertEquals(PrepDecision.Ask(PrepProblem.VPN_FAILED), v.next(aceReady, vpnOff))
        assertEquals(PrepDecision.Ask(PrepProblem.VPN_MISSING), PlaybackPlanner(PlaybackNeeds(false, true)).next(aceReady, WarpStatus(WarpPhase.NOT_INSTALLED)))
        assertEquals(PrepDecision.Ask(PrepProblem.VPN_SETUP), PlaybackPlanner(PlaybackNeeds(false, true)).next(aceReady, WarpStatus(WarpPhase.NEEDS_SETUP)))
        // Ace no instalado: se deja al reproductor externo, no se bloquea.
        assertEquals(PrepDecision.Launch, PlaybackPlanner(PlaybackNeeds(true, false)).next(AceStatus(AcePhase.NOT_INSTALLED), vpnOff))
    }

    // ---------------------------------------------------------------- Estados de los servicios (brief §19–22)

    @Test fun serviceStatesMatchBrief() {
        fun ace(p: AcePhase, e: AceError? = null) = QuickServiceText.ace(AceStatus(p, error = e))
        fun warp(p: WarpPhase, d: String? = null) = QuickServiceText.warp(WarpStatus(p, d))
        assertEquals("Activo" to Indicator.OK, ace(AcePhase.READY).let { it.status to it.indicator })
        assertNull(ace(AcePhase.READY).actionLabel)                              // normalidad silenciosa
        assertEquals("Motor detenido" to "Iniciar", ace(AcePhase.STOPPED).let { it.status to it.actionLabel })
        assertEquals(Indicator.BUSY, ace(AcePhase.STARTING).indicator)
        assertEquals("No se pudo iniciar" to "Reintentar", ace(AcePhase.ERROR, AceError.START_FAILED).let { it.status to it.actionLabel })
        assertEquals("No instalado" to "Cómo instalar", ace(AcePhase.NOT_INSTALLED).let { it.status to it.actionLabel })
        assertEquals(RowAction.ACE_INSTALL_HELP, ace(AcePhase.NOT_INSTALLED).onOk)

        assertEquals("Conectada" to Indicator.OK, warp(WarpPhase.CONNECTED).let { it.status to it.indicator })
        assertEquals(Indicator.NEUTRAL, warp(WarpPhase.DISCONNECTED).indicator)   // desconectada ≠ error
        assertEquals("Conectar", warp(WarpPhase.DISCONNECTED).actionLabel)
        assertEquals("Conectando…", warp(WarpPhase.CONNECTING).status)
        assertEquals("Desconectando…", warp(WarpPhase.DISCONNECTING).status)
        assertEquals("Requiere autorización" to "Autorizar", warp(WarpPhase.PERMISSION_REQUIRED).let { it.status to it.actionLabel })
        assertEquals("Falta configurar" to "Configurar", warp(WarpPhase.NEEDS_SETUP).let { it.status to it.actionLabel })
        assertEquals(Indicator.WARNING, warp(WarpPhase.NEEDS_SETUP).indicator)
        assertEquals("No se pudo conectar" to Indicator.ERROR, warp(WarpPhase.ERROR, "CONNECT_TIMEOUT").let { it.status to it.indicator })
        assertEquals("No instalada" to "Cómo instalar", warp(WarpPhase.NOT_INSTALLED).let { it.status to it.actionLabel })
    }

    // ---------------------------------------------------------------- Actualización de proveedores

    @Test fun providerRefreshPolicy() {
        val min = 60_000L
        val t = 10_000_000L
        assertTrue(com.mivisor.app.data.RefreshPolicy.shouldCheck(0L, 0L, t, loading = false))          // nunca descargado
        assertFalse(com.mivisor.app.data.RefreshPolicy.shouldCheck(t - 5 * min, 0L, t, loading = false)) // caché fresca: no se descarga
        assertTrue(com.mivisor.app.data.RefreshPolicy.shouldCheck(t - 11 * min, 0L, t, loading = false)) // copia vieja
        assertFalse(com.mivisor.app.data.RefreshPolicy.shouldCheck(t - 11 * min, t - min, t, loading = false))  // comprobado hace 1 min
        assertTrue(com.mivisor.app.data.RefreshPolicy.shouldCheck(t - 11 * min, t - 3 * min, t, loading = false))
        assertFalse(com.mivisor.app.data.RefreshPolicy.shouldCheck(0L, 0L, t, loading = true))          // ya se está descargando
    }

    // ---------------------------------------------------------------- Historial de reproducciones

    @Test fun playbackHistory() {
        val channel = ch("DAZN 1 FHD", "acestream://a").withProvider("dazn", "DAZN")
        val event = ev("m1")
        val entry = PlayRequest(channel, event.displayTitle(), "LaLiga", event.id).toEntry(now)
        assertEquals("Atlético de Madrid vs Deportivo", entry.title)
        assertEquals("DAZN", entry.provider)
        assertTrue(entry.isEvent)
        var list = PlaybackEntry.push(emptyList(), entry)
        list = PlaybackEntry.push(list, PlayRequest(ch("Teledeporte", "acestream://t")).toEntry(now + 1))
        list = PlaybackEntry.push(list, entry.copy(at = now + 2))                // repetir: sube, no duplica
        assertEquals(listOf("Atlético de Madrid vs Deportivo", "Teledeporte"), list.map { it.title })
        val back = list.first().toChannel()
        assertEquals("acestream://a", back.url)
        assertEquals("dazn", back.providerId)
        assertEquals(ChannelKind.ACE, back.kind)
        assertEquals("dazn/DAZN 1 FHD", channel.id)
    }
}
