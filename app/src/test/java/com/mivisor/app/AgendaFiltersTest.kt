package com.mivisor.app

import com.mivisor.app.model.AgendaEvent
import com.mivisor.app.model.AgendaFilters
import com.mivisor.app.model.CompetitionKey
import com.mivisor.app.model.competitionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgendaFiltersTest {
    private fun ev(id: String, sport: String, competition: String?, start: Long = 1_000L) =
        AgendaEvent(id, sport, competition, start, "A", "B", "A - B", emptyList(), null, null)

    private val events = listOf(
        ev("f1", "futbol", "LaLiga", 1_000),
        ev("f2", "futbol", "Champions League", 2_000),
        ev("f3", "futbol", "Premier League", 3_000),
        ev("f4", "futbol", "LaLiga EA Sports", 4_000),
        ev("f5", "futbol", "Eurocopa", 5_000),
        ev("t1", "tenis", "ATP · Paris Masters · R32", 1_500),
        ev("m1", "motogp", "MotoGP", 2_500)
    )

    @Test fun normalization() {
        mapOf(
            "LaLiga EA Sports" to CompetitionKey.LALIGA,
            "LaLiga" to CompetitionKey.LALIGA,
            "La Liga" to CompetitionKey.LALIGA,
            "Primera Division" to CompetitionKey.LALIGA,
            "Primera División" to CompetitionKey.LALIGA,
            "Spanish La Liga" to CompetitionKey.LALIGA,
            "UEFA Champions League" to CompetitionKey.CHAMPIONS,
            "Champions League" to CompetitionKey.CHAMPIONS,
            "UCL" to CompetitionKey.CHAMPIONS,
            "Liga de Campeones" to CompetitionKey.CHAMPIONS,
            "Premier League" to CompetitionKey.PREMIER,
            "English Premier League" to CompetitionKey.PREMIER,
            "Serie A" to CompetitionKey.SERIE_A,
            "Italian Serie A" to CompetitionKey.SERIE_A,
            "Bundesliga" to CompetitionKey.BUNDESLIGA,
            "German Bundesliga" to CompetitionKey.BUNDESLIGA,
            "Ligue 1" to CompetitionKey.LIGUE_1,
            "French Ligue 1" to CompetitionKey.LIGUE_1,
            "UEFA Europa League" to CompetitionKey.EUROPA_LEAGUE,
            "UEFA Europa Conference League" to CompetitionKey.CONFERENCE,
            "Conference League" to CompetitionKey.CONFERENCE,
            "Copa del Rey" to CompetitionKey.COPA_DEL_REY,
            "Supercopa de España" to CompetitionKey.SUPERCOPA
        ).forEach { (name, key) -> assertEquals(name to key, name to CompetitionKey.of(name)) }
    }

    @Test fun lowerDivisionsAndUnknownGoToOtherWithoutCrash() {
        listOf(
            "LaLiga Hypermotion", "Segunda División", "2. Bundesliga", "Ligue 2", "Serie B",
            "Premier League 2", "Austrian Bundesliga", "AFC Champions League", "UEFA Youth League",
            "Women's Champions League", "Liga F", "Primera División RFEF", "Supercopa de Europa",
            "", "   ", "???", "Competición desconocida 123"
        ).forEach { assertEquals(it to CompetitionKey.OTHER, it to CompetitionKey.of(it)) }
        assertEquals(CompetitionKey.OTHER, CompetitionKey.of(null))
        assertEquals(CompetitionKey.OTHER, ev("x", "futbol", null).competitionKey())
    }

    @Test fun footballAllShowsEveryFootballEvent() {
        val visible = AgendaFilters.bySport(events, "futbol", null)
        assertEquals(listOf("f1", "f2", "f3", "f4", "f5"), visible.map { it.id })
    }

    @Test fun footballLaLigaOnlyLaLiga() {
        val visible = AgendaFilters.bySport(events, "futbol", CompetitionKey.LALIGA.name)
        assertEquals(listOf("f1", "f4"), visible.map { it.id })
    }

    @Test fun footballChampionsOnlyChampions() {
        val visible = AgendaFilters.bySport(events, "futbol", CompetitionKey.CHAMPIONS.name)
        assertEquals(listOf("f2"), visible.map { it.id })
    }

    @Test fun optionsAreRelevantOrderedAndStartWithAll() {
        val options = AgendaFilters.options(events, "futbol")
        assertEquals(listOf("Todos", "LaLiga", "Champions", "Premier", "Otras"), options.map { it.label })
        assertNull(options.first().key)
        assertFalse(options.any { it.label == "Bundesliga" })   // sin eventos → oculto
        // Sin ningún partido de fútbol: solo "Todos".
        assertEquals(listOf("Todos"), AgendaFilters.options(events.filter { it.sport != "futbol" }, "futbol").map { it.label })
    }

    @Test fun switchingToTennisHidesCompetitionFilters() {
        assertTrue(AgendaFilters.hasSubFilters("futbol"))
        assertFalse(AgendaFilters.hasSubFilters("tenis"))
        assertFalse(AgendaFilters.hasSubFilters(null))
        assertTrue(AgendaFilters.options(events, "tenis").isEmpty())
        assertTrue(AgendaFilters.options(events, null).isEmpty())
        val remembered = mapOf("futbol" to CompetitionKey.CHAMPIONS.name)
        // En tenis el subfiltro de fútbol no se aplica…
        assertNull(AgendaFilters.effective(events, "tenis", remembered))
        assertEquals(listOf("t1"), AgendaFilters.bySport(events, "tenis", null).map { it.id })
        // …y al volver a fútbol se recupera Champions.
        assertEquals("CHAMPIONS", AgendaFilters.effective(events, "futbol", remembered))
    }

    @Test fun rememberedCompetitionWithoutEventsFallsBackToAll() {
        val remembered = mapOf("futbol" to CompetitionKey.BUNDESLIGA.name)
        assertNull(AgendaFilters.effective(events, "futbol", remembered))
        assertNull(AgendaFilters.effective(events, "futbol", mapOf("futbol" to "NO_EXISTE")))
    }

    @Test fun noSportShowsEverything() {
        assertEquals(events.size, AgendaFilters.bySport(events, null, "CHAMPIONS").size)
    }

    @Test fun combinesWithTimeFilters() {
        // El ViewModel aplica después el filtro de día/franja sobre el resultado: "Hoy + Fútbol + LaLiga".
        val today = 0L until 2_500L
        val visible = AgendaFilters.bySport(events, "futbol", "LALIGA").filter { it.startMillis in today }
        assertEquals(listOf("f1"), visible.map { it.id })
    }
}
