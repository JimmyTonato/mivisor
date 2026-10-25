package com.mivisor.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mivisor.app.data.AgendaRepository
import com.mivisor.app.data.ChannelMatcher
import com.mivisor.app.data.TvChannelsRepository
import com.mivisor.app.model.Agenda
import com.mivisor.app.model.AgendaChannel
import com.mivisor.app.model.AgendaEvent
import com.mivisor.app.model.CalendarChip
import com.mivisor.app.model.CalendarFilters
import com.mivisor.app.model.CalendarQuery
import com.mivisor.app.model.CalendarRange
import com.mivisor.app.model.Channel
import com.mivisor.app.model.Competition
import com.mivisor.app.model.DayClock
import com.mivisor.app.model.HomeFeed
import com.mivisor.app.model.HomeFeedBuilder
import com.mivisor.app.model.LiveFilters
import com.mivisor.app.player.PlayerPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class AgendaUi(
    val loading: Boolean = true,
    val agenda: Agenda? = null,
    val fetchedAt: Long = 0L,
    val offline: Boolean = false,
    val error: String? = null,
    /** Hora con la que se calcularon los estados (se recalcula cada minuto). */
    val now: Long = System.currentTimeMillis(),

    // Inicio
    val home: HomeFeed = HomeFeed.EMPTY,

    // En directo
    val liveSport: String? = null,
    val liveSports: List<String> = LiveFilters.ALWAYS,
    val live: List<AgendaEvent> = emptyList(),

    // Calendario
    val calendar: CalendarQuery = CalendarQuery(),
    val calendarChips: List<CalendarChip> = emptyList(),
    val calendarEvents: List<AgendaEvent> = emptyList(),

    /** Nombre de canal de la agenda → tus fuentes equivalentes (de cualquier proveedor). */
    val links: Map<String, List<Channel>> = emptyMap(),
    val indexedChannels: Int = 0,
    /** Canales pedidos a TheSportsDB al abrir un partido (id del evento → canales). */
    val tvChannels: Map<String, List<AgendaChannel>> = emptyMap(),
    val tvLoading: Set<String> = emptySet(),
    val tvFailed: Set<String> = emptySet()
) {
    /** Canales del partido: los de agenda.json o, si no trae, los pedidos al abrirlo. */
    fun channelsOf(event: AgendaEvent): List<AgendaChannel> =
        event.channels.ifEmpty { tvChannels[event.id].orEmpty() }

    /** Tus fuentes (enlaces distintos) para un partido, de todos sus canales. */
    fun sourcesOf(event: AgendaEvent): List<Channel> =
        channelsOf(event).flatMap { links[it.name].orEmpty() }.distinctBy { it.url }

    fun optionCount(event: AgendaEvent): Int = sourcesOf(event).size

    fun event(id: String?): AgendaEvent? = agenda?.events?.firstOrNull { it.id == id }
}

/**
 * Agenda deportiva para Inicio, En directo, Calendario, competiciones y "Canales del encuentro".
 * Los canales del usuario llegan de la biblioteca ([setChannels]); aquí no se descargan proveedores.
 */
class AgendaViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AgendaRepository(File(application.cacheDir, "agenda"))
    private val prefs = PlayerPreferences(application)
    private val tvRepository = TvChannelsRepository(File(application.cacheDir, "agenda"))
    private val clock = DayClock()

    private val _ui = MutableStateFlow(AgendaUi())
    val ui: StateFlow<AgendaUi> = _ui.asStateFlow()

    private var loadJob: Job? = null
    private var matcher: ChannelMatcher = ChannelMatcher(emptyList())
    private var lastChannels: List<Channel>? = null

    val agendaUrl: String get() = prefs.agendaUrl.ifBlank { BuildConfig.AGENDA_URL }

    init {
        load()
        // Los estados (en directo, finalizado…) cambian con la hora: se recalculan cada minuto.
        viewModelScope.launch {
            while (true) {
                delay(60_000)
                recompute()
            }
        }
    }

    fun load(force: Boolean = false) {
        loadJob?.cancel()
        _ui.update { it.copy(loading = it.agenda == null, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val result = repository.load(agendaUrl, force)
                _ui.update { it.copy(loading = false, agenda = result.agenda, fetchedAt = result.fetchedAt, offline = result.offline) }
                recompute()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = e.message ?: "No se pudo cargar la agenda") }
            }
        }
    }

    /** Canales de todos tus proveedores (de la biblioteca): para ofrecer "Ver ahora" en cada partido. */
    fun setChannels(channels: List<Channel>) {
        if (channels === lastChannels) return
        lastChannels = channels
        viewModelScope.launch {
            matcher = withContext(Dispatchers.Default) { ChannelMatcher(channels) }
            _ui.update { it.copy(indexedChannels = channels.size) }
            recompute()
        }
    }

    fun setLiveSport(sport: String?) = update { it.copy(liveSport = if (it.liveSport == sport) null else sport) }

    fun setCalendarRange(range: CalendarRange) = update { it.copy(calendar = it.calendar.copy(range = range)) }

    fun setCalendarDay(offset: Int) = update {
        it.copy(calendar = it.calendar.copy(range = CalendarRange.DATE, dayOffset = offset.coerceIn(0, CalendarFilters.DAYS_AHEAD - 1)))
    }

    fun setCalendarCompetition(key: String?) = update { it.copy(calendar = it.calendar.copy(competition = key)) }

    /** "Ver calendario completo" desde una competición: la semana, filtrada por ella. */
    fun openCalendarFor(competition: Competition?) = update {
        val key = when {
            competition == null -> null
            competition.sport == "tenis" -> CalendarFilters.TENNIS_KEY
            else -> competition.name
        }
        it.copy(calendar = CalendarQuery(range = if (competition == null) CalendarRange.TODAY else CalendarRange.WEEK, competition = key))
    }

    /**
     * Al abrir un partido: si no trae canales, se piden a TheSportsDB UNA vez y se guardan.
     * Si ya los tiene (de agenda.json o de una consulta anterior) no se hace ninguna llamada.
     */
    fun requestChannels(event: AgendaEvent) {
        val state = _ui.value
        if (event.channels.isNotEmpty() || event.tvId == null) return
        if (event.id in state.tvChannels || event.id in state.tvLoading) return
        tvRepository.known(event)?.let { known ->
            _ui.update { it.copy(tvChannels = it.tvChannels + (event.id to known)) }
            viewModelScope.launch { recompute() }
            return
        }
        _ui.update { it.copy(tvLoading = it.tvLoading + event.id, tvFailed = it.tvFailed - event.id) }
        viewModelScope.launch {
            val result = tvRepository.channelsFor(event)
            _ui.update {
                it.copy(
                    tvLoading = it.tvLoading - event.id,
                    // Un fallo no se guarda: al volver a abrir el partido se reintenta.
                    tvChannels = if (result.failed) it.tvChannels else it.tvChannels + (event.id to result.channels),
                    tvFailed = if (result.failed) it.tvFailed + event.id else it.tvFailed
                )
            }
            recompute()
        }
    }

    fun competitionSections(competition: Competition): List<Pair<String, List<AgendaEvent>>> {
        val events = _ui.value.agenda?.events.orEmpty()
        return CalendarFilters.competitionSections(competition, events, System.currentTimeMillis(), clock)
    }

    // ---------------------------------------------------------------------------------

    private fun update(change: (AgendaUi) -> AgendaUi) {
        _ui.update(change)
        viewModelScope.launch { recompute() }
    }

    private suspend fun recompute() {
        val state = _ui.value
        val agenda = state.agenda ?: return
        val now = System.currentTimeMillis()
        val computed = withContext(Dispatchers.Default) {
            val links = buildLinks(agenda, state.tvChannels)
            val options: (AgendaEvent) -> Int = { e ->
                e.channels.ifEmpty { state.tvChannels[e.id].orEmpty() }.flatMap { links[it.name].orEmpty() }.distinctBy { it.url }.size
            }
            val events = agenda.events
            val liveAll = LiveFilters.apply(events, now, null)
            Computed(
                links = links,
                home = HomeFeedBuilder.build(events, now, clock, options),
                live = LiveFilters.apply(events, now, state.liveSport),
                liveSports = LiveFilters.sports(liveAll),
                chips = CalendarFilters.chips(events),
                calendar = CalendarFilters.apply(events, state.calendar, now, clock)
            )
        }
        _ui.update {
            it.copy(
                now = now, links = computed.links, home = computed.home, live = computed.live, liveSports = computed.liveSports,
                calendarChips = computed.chips, calendarEvents = computed.calendar
            )
        }
    }

    private class Computed(
        val links: Map<String, List<Channel>>,
        val home: HomeFeed,
        val live: List<AgendaEvent>,
        val liveSports: List<String>,
        val chips: List<CalendarChip>,
        val calendar: List<AgendaEvent>
    )

    private fun buildLinks(agenda: Agenda, tvChannels: Map<String, List<AgendaChannel>>): Map<String, List<Channel>> {
        if (matcher.isEmpty) return emptyMap()
        val names = (agenda.events.asSequence().flatMap { it.channels.asSequence() } +
            tvChannels.values.asSequence().flatten()).map { it.name }.toSet()
        return names.associateWith { matcher.match(it) }.filterValues { it.isNotEmpty() }
    }
}
