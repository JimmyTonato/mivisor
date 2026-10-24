package com.mivisor.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mivisor.app.data.AgendaRepository
import com.mivisor.app.data.CatalogBuilder
import com.mivisor.app.data.ChannelMatcher
import com.mivisor.app.data.NotCachedException
import com.mivisor.app.data.PayloadDecoder
import com.mivisor.app.data.RawCache
import com.mivisor.app.data.SourceStore
import com.mivisor.app.data.StreamRepository
import com.mivisor.app.data.StreamRepository.Mode
import com.mivisor.app.data.sharedGson
import com.mivisor.app.model.Agenda
import com.mivisor.app.model.AgendaEvent
import com.mivisor.app.model.Channel
import com.mivisor.app.model.EventStatus
import com.mivisor.app.model.SourceProfile
import com.mivisor.app.network.CompositeDnsResolver
import com.mivisor.app.player.PlayerPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

enum class DayFilter(val label: String) {
    TODAY("Hoy"), TOMORROW("Mañana"), LIVE("En vivo"), NEXT("Próximos")
}

enum class TimeSlot(val label: String, val fromHour: Int, val toHour: Int) {
    ALL("Todo el día", 0, 24), MORNING("Mañana", 0, 14), AFTERNOON("Tarde", 14, 20), NIGHT("Noche", 20, 24)
}

data class AgendaUi(
    val loading: Boolean = true,
    val agenda: Agenda? = null,
    val fetchedAt: Long = 0L,
    val offline: Boolean = false,
    val error: String? = null,
    val day: DayFilter = DayFilter.TODAY,
    val slot: TimeSlot = TimeSlot.ALL,
    val sport: String? = null,
    val visible: List<AgendaEvent> = emptyList(),
    /** Destacados de hoy: en directo primero y luego las competiciones más vistas. */
    val featured: List<AgendaEvent> = emptyList(),
    val liveCount: Int = 0,
    /** Nombre de canal de la agenda → tus canales equivalentes. */
    val links: Map<String, List<Channel>> = emptyMap(),
    val indexedChannels: Int = 0
)

class AgendaViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AgendaRepository(File(application.cacheDir, "agenda"))
    private val prefs = PlayerPreferences(application)
    private val sourceStore = SourceStore(application)
    // Misma caché que el catálogo: el índice de canales se construye sin red si ya abriste las categorías.
    private val streams = StreamRepository(
        resolver = CompositeDnsResolver(),
        decoder = PayloadDecoder(sharedGson),
        cache = RawCache(File(application.cacheDir, "txt_cache")),
        domain = BuildConfig.STREAM_DOMAIN
    )

    private val _ui = MutableStateFlow(AgendaUi())
    val ui: StateFlow<AgendaUi> = _ui.asStateFlow()

    private var loadJob: Job? = null
    private var matcher: ChannelMatcher = ChannelMatcher(emptyList())

    val agendaUrl: String get() = prefs.agendaUrl.ifBlank { BuildConfig.AGENDA_URL }

    init {
        load()
        viewModelScope.launch {
            sourceStore.items.collectLatest { sources -> rebuildIndex(sources) }
        }
    }

    fun load(force: Boolean = false) {
        loadJob?.cancel()
        _ui.update { it.copy(loading = it.agenda == null, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val result = repository.load(agendaUrl, force)
                _ui.update {
                    it.copy(loading = false, agenda = result.agenda, fetchedAt = result.fetchedAt, offline = result.offline)
                }
                recompute()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = e.message ?: "No se pudo cargar la agenda") }
            }
        }
    }

    fun setDay(day: DayFilter) = update { it.copy(day = day) }
    fun setSlot(slot: TimeSlot) = update { it.copy(slot = slot) }
    /** Pulsar el deporte activo lo desactiva (vuelve a "todos"). */
    fun toggleSport(sport: String) = update { it.copy(sport = if (it.sport == sport) null else sport) }

    fun linksFor(channelName: String): List<Channel> = _ui.value.links[channelName].orEmpty()

    /** Otros eventos de hoy para la columna "Más partidos de hoy" del detalle. */
    fun moreToday(excludeId: String, max: Int = 3): List<AgendaEvent> {
        val agenda = _ui.value.agenda ?: return emptyList()
        val now = System.currentTimeMillis()
        val end = startOfDay(now, 1)
        return agenda.events.asSequence()
            .filter { it.id != excludeId && it.startMillis < end && it.status(now) != EventStatus.FINISHED }
            .take(max)
            .toList()
    }

    // ---------------------------------------------------------------------------------

    private fun update(change: (AgendaUi) -> AgendaUi) {
        _ui.update(change)
        viewModelScope.launch { recompute() }
    }

    private suspend fun recompute() {
        val state = _ui.value
        val agenda = state.agenda ?: return
        val computed = withContext(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            val today = startOfDay(now, 0)
            val tomorrow = startOfDay(now, 1)
            val dayAfter = startOfDay(now, 2)
            val weekEnd = startOfDay(now, 8)
            val bySport = agenda.events.filter { state.sport == null || it.sport == state.sport }
            val byDay = when (state.day) {
                DayFilter.TODAY -> bySport.filter { it.startMillis in today until tomorrow }
                    .sortedBy { if (it.status(now) == EventStatus.FINISHED) 1 else 0 }
                DayFilter.TOMORROW -> bySport.filter { it.startMillis in tomorrow until dayAfter }
                DayFilter.NEXT -> bySport.filter { it.startMillis in dayAfter until weekEnd }
                DayFilter.LIVE -> bySport.filter { it.status(now) == EventStatus.LIVE }
            }
            val visible = if (state.slot == TimeSlot.ALL) byDay else byDay.filter {
                hourOf(it.startMillis) in state.slot.fromHour until state.slot.toHour
            }
            val todayEvents = agenda.events.filter { it.startMillis in today until tomorrow && it.status(now) != EventStatus.FINISHED }
            val featured = todayEvents
                .sortedWith(compareBy<AgendaEvent>({ if (it.status(now) == EventStatus.LIVE) 0 else 1 }, { -priority(it) }, { it.startMillis }))
                .take(3)
            Computed(visible, featured, agenda.events.count { it.status(now) == EventStatus.LIVE }, buildLinks(agenda))
        }
        _ui.update {
            it.copy(visible = computed.visible, featured = computed.featured, liveCount = computed.live, links = computed.links)
        }
    }

    private class Computed(
        val visible: List<AgendaEvent>,
        val featured: List<AgendaEvent>,
        val live: Int,
        val links: Map<String, List<Channel>>
    )

    /** Más puntos = más interés para "Destacados". Ajustable sin tocar la UI. */
    private fun priority(event: AgendaEvent): Int {
        val text = "${event.competition.orEmpty()} ${event.title}".lowercase()
        return PRIORITY.entries.firstOrNull { (key, _) -> key in text }?.value ?: 0
    }

    private fun buildLinks(agenda: Agenda): Map<String, List<Channel>> {
        if (matcher.isEmpty) return emptyMap()
        val names = agenda.events.asSequence().flatMap { it.channels.asSequence() }.map { it.name }.toSet()
        return names.associateWith { matcher.match(it) }.filterValues { it.isNotEmpty() }
    }

    /** Reúne los canales de todas tus categorías (de caché; si falta, la descarga una vez). */
    private suspend fun rebuildIndex(sources: List<SourceProfile>) {
        val channels = ArrayList<Channel>()
        for (source in sources) {
            val result = try {
                streams.load(source.code, Mode.CACHE_ONLY)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NotCachedException) {
                runCatching { streams.load(source.code, Mode.PREFER_CACHE) }.getOrNull()
            } catch (e: Exception) {
                null
            } ?: continue
            val catalog = withContext(Dispatchers.Default) { CatalogBuilder.build(result.items) }
            catalog.sections.forEach { channels += it.channels }
        }
        matcher = withContext(Dispatchers.Default) { ChannelMatcher(channels) }
        _ui.update { it.copy(indexedChannels = channels.size) }
        recompute()
    }

    private fun hourOf(millis: Long): Int = Calendar.getInstance().run {
        timeInMillis = millis
        get(Calendar.HOUR_OF_DAY)
    }

    private fun startOfDay(now: Long, plusDays: Int): Long = Calendar.getInstance().run {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, plusDays)
        timeInMillis
    }

    private companion object {
        val PRIORITY = linkedMapOf(
            "champions" to 100, "liga de campeones" to 100, "real madrid" to 95, "barcelona" to 95,
            "la liga" to 90, "laliga" to 90, "copa del rey" to 85, "premier" to 80, "nba" to 75,
            "fórmula 1" to 75, "formula 1" to 75, "motogp" to 70, "atp" to 65, "wta" to 60,
            "europa league" to 60, "serie a" to 55, "bundesliga" to 55, "ligue 1" to 50, "acb" to 50, "endesa" to 50
        )
    }
}
