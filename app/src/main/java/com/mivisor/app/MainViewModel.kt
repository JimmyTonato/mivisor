package com.mivisor.app

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mivisor.app.data.CatalogBuilder
import com.mivisor.app.data.ChannelGroup
import com.mivisor.app.data.ChannelGrouping
import com.mivisor.app.data.Codes
import com.mivisor.app.data.InvalidPayloadException
import com.mivisor.app.data.NotCachedException
import com.mivisor.app.data.PayloadDecoder
import com.mivisor.app.data.PlaybackHistoryStore
import com.mivisor.app.data.RefreshPolicy
import com.mivisor.app.data.RawCache
import com.mivisor.app.data.SourceStore
import com.mivisor.app.data.StreamRepository
import com.mivisor.app.data.StreamRepository.Mode
import com.mivisor.app.data.sharedGson
import com.mivisor.app.model.Catalog
import com.mivisor.app.model.Channel
import com.mivisor.app.model.PlayRequest
import com.mivisor.app.model.PlaybackEntry
import com.mivisor.app.model.SourceProfile
import com.mivisor.app.network.CodeNotFoundException
import com.mivisor.app.network.CompositeDnsResolver
import com.mivisor.app.network.DnsUnavailableException
import com.mivisor.app.player.PlayerPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

enum class ErrorKind { NOT_FOUND, OFFLINE, INVALID, OTHER }

class UiError(val kind: ErrorKind, val title: String, val detail: String)

/** Un proveedor (un código) y su lista de canales. */
data class ProviderState(
    val source: SourceProfile,
    val catalog: Catalog? = null,
    val loading: Boolean = false,
    val error: UiError? = null,
    val updatedAt: Long = 0L,
    val offline: Boolean = false,
    val failedRefs: Int = 0,
    /** Abierto con "Abrir" sin guardarlo: desaparece al cerrar MiVisor. */
    val transient: Boolean = false,
    /** Última actualización fallida mientras se muestran los datos anteriores. */
    val lastFailure: UiError? = null
) {
    val id: String get() = source.id
    val name: String get() = source.name
    val channelCount: Int get() = catalog?.channelCount ?: 0
}

data class LibraryUi(
    /** null = todavía leyendo de disco (evita parpadeos de la pantalla de bienvenida). */
    val providers: List<ProviderState>? = null,
    /** Canales agrupados de todos los proveedores ("DAZN 1 · 3 fuentes"). */
    val groups: List<ChannelGroup> = emptyList(),
    /** Proveedor elegido en Canales; null = "Todos". */
    val selected: String? = null,
    val refreshing: Boolean = false,
    /** Se incrementa cuando el usuario abre un código: Canales lleva el foco al contenido. */
    val focusToken: Int = 0
) {
    val channelTotal: Int get() = groups.size
    val providerCount: Int get() = providers.orEmpty().size
    fun provider(id: String?): ProviderState? = providers.orEmpty().firstOrNull { it.id == id }

    /** Grupos visibles con el filtro de proveedor (con solo las fuentes de ese proveedor). */
    fun visibleGroups(): List<ChannelGroup> {
        val id = selected ?: return groups
        return groups.mapNotNull { it.only(id) }
    }
}

/**
 * Biblioteca de canales: todos los proveedores (códigos) cargados a la vez, de la copia local al instante
 * y actualizados por red si están viejos. Canales agrupados entre proveedores.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val sourceStore = SourceStore(application)
    private val playbackStore = PlaybackHistoryStore(application)
    val preferences = PlayerPreferences(application)
    private val repository = StreamRepository(
        resolver = CompositeDnsResolver(),
        decoder = PayloadDecoder(sharedGson),
        cache = RawCache(File(application.cacheDir, "txt_cache")),
        domain = BuildConfig.STREAM_DOMAIN
    )

    private val _ui = MutableStateFlow(LibraryUi())
    val ui: StateFlow<LibraryUi> = _ui.asStateFlow()

    val playback: StateFlow<List<PlaybackEntry>> =
        playbackStore.items.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Canales de todos los proveedores (para enlazar los canales de la agenda con tus fuentes). */
    private val _allChannels = MutableStateFlow<List<Channel>>(emptyList())
    val allChannels: StateFlow<List<Channel>> = _allChannels.asStateFlow()

    private val jobs = HashMap<String, Job>()
    private val lastCheck = HashMap<String, Long>()
    private val limiter = Semaphore(3)
    private var groupJob: Job? = null
    private var transient: List<ProviderState> = emptyList()

    init {
        viewModelScope.launch {
            sourceStore.items.collect { saved -> onSourcesChanged(saved) }
        }
    }

    // ------------------------------------------------------------------ acciones

    fun select(providerId: String?) = _ui.update { it.copy(selected = providerId) }

    /** "Actualizar": fuerza la descarga de todos los proveedores (o de uno). */
    fun refresh(providerId: String? = null) {
        val list = _ui.value.providers.orEmpty().filter { providerId == null || it.id == providerId }
        list.forEach { load(it.source, force = true, userForced = true) }
    }

    /**
     * Comprobación automática (al abrir MiVisor, al volver a ella y al entrar en Canales): solo los proveedores
     * con copia vieja o fallida, y sin repetir si se comprobó hace poco. Usa la caché si sigue fresca.
     */
    fun refreshStale() {
        val now = System.currentTimeMillis()
        _ui.value.providers.orEmpty()
            .filter { p -> RefreshPolicy.shouldCheck(p.updatedAt, lastCheck[p.id] ?: 0L, now, p.loading || jobs[p.id]?.isActive == true) }
            .forEach { load(it.source, force = false) }
    }

    /** "Abrir": el código se carga sin guardarlo y queda seleccionado. */
    fun openCode(raw: String, title: String? = null) {
        val code = Codes.normalizeOrNull(raw) ?: return toast("Código no válido")
        val saved = _ui.value.providers.orEmpty().firstOrNull { it.source.code == code }
        if (saved != null) {
            _ui.update { it.copy(selected = saved.id, focusToken = it.focusToken + 1) }
            load(saved.source, force = false)
            return
        }
        val source = SourceProfile(id = code, code = code, name = title?.trim()?.ifBlank { null } ?: code, order = Int.MAX_VALUE)
        transient = transient.filterNot { it.id == code } + ProviderState(source, loading = true, transient = true)
        publishProviders { list -> list }
        _ui.update { it.copy(selected = code, focusToken = it.focusToken + 1) }
        load(source, force = false)
    }

    /** Crea, renombra o cambia el código de un proveedor (`replacing` = el que se edita). */
    fun saveSource(name: String, code: String, openAfter: Boolean, replacing: SourceProfile? = null) {
        viewModelScope.launch {
            runCatching {
                val saved = sourceStore.upsert(name, code)
                if (replacing != null && replacing.id != saved.id) {
                    sourceStore.remove(replacing.id)
                    preferences.setVpnFor(saved.id, preferences.vpnFor(replacing.id))
                }
                saved
            }.onSuccess { saved ->
                transient = transient.filterNot { it.id == saved.id }
                if (openAfter) _ui.update { it.copy(selected = saved.id, focusToken = it.focusToken + 1) }
                else toast("Proveedor guardado")
            }.onFailure { toast(it.message ?: "No se pudo guardar") }
        }
    }

    /** Cambió una regla de importación: se reconstruyen los catálogos (desde la caché si está fresca). */
    fun rebuildAll() {
        _ui.value.providers.orEmpty().forEach { load(it.source, force = false) }
    }

    fun removeSource(source: SourceProfile) {
        viewModelScope.launch {
            sourceStore.remove(source.id)
            if (_ui.value.selected == source.id) _ui.update { it.copy(selected = null) }
        }
    }

    fun moveSource(source: SourceProfile, delta: Int) {
        viewModelScope.launch { sourceStore.move(source.id, delta) }
    }

    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.clearCache() }
            toast("Caché borrada")
        }
    }

    fun recordPlayback(request: PlayRequest) {
        viewModelScope.launch { playbackStore.add(request.toEntry(System.currentTimeMillis())) }
    }

    fun removePlayback(entry: PlaybackEntry) {
        viewModelScope.launch { playbackStore.remove(entry) }
    }

    fun clearPlayback() {
        viewModelScope.launch { playbackStore.clear() }
    }

    // ------------------------------------------------------------------ carga

    private fun onSourcesChanged(saved: List<SourceProfile>) {
        val savedIds = saved.map { it.id }.toSet()
        transient = transient.filterNot { it.id in savedIds }
        val previous = _ui.value.providers.orEmpty().associateBy { it.id }
        // Proveedores eliminados: se cancela su carga.
        (jobs.keys - savedIds - transient.map { it.id }.toSet()).toList().forEach { jobs.remove(it)?.cancel() }
        _ui.update { state ->
            state.copy(
                providers = saved.map { s -> previous[s.id]?.copy(source = s) ?: ProviderState(s, loading = true) } + transient,
                selected = state.selected?.takeIf { id -> id in savedIds || transient.any { it.id == id } }
            )
        }
        saved.filter { previous[it.id]?.catalog == null && jobs[it.id]?.isActive != true }.forEach { load(it, force = false) }
        regroup()
    }

    private fun load(source: SourceProfile, force: Boolean, userForced: Boolean = false) {
        jobs[source.id]?.cancel()
        lastCheck[source.id] = System.currentTimeMillis()
        jobs[source.id] = viewModelScope.launch {
            limiter.withPermit { loadNow(source, force, userForced) }
        }
    }

    private suspend fun loadNow(source: SourceProfile, force: Boolean, userForced: Boolean) {
        val code = source.code
        updateProvider(source.id) { it.copy(loading = it.catalog == null, error = null) }
        _ui.update { it.copy(refreshing = true) }
        try {
            // 1) Copia local al instante.
            if (!force) {
                val cached = try {
                    repository.load(code, Mode.CACHE_ONLY)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (cached != null) {
                    publish(source.id, cached)
                    if (repository.isFresh(cached.savedAt) && cached.failedRefs == 0) return
                }
            }
            // 2) Red.
            try {
                val result = repository.load(code, Mode.NETWORK, if (force) Mode.NETWORK else Mode.PREFER_CACHE)
                publish(source.id, result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val hasContent = _ui.value.provider(source.id)?.catalog != null
                if (hasContent) {
                    // Fallo con datos previos: se conservan los últimos válidos (la lista no se vacía).
                    updateProvider(source.id) { it.copy(loading = false, offline = true, lastFailure = errorFor(e)) }
                    if (userForced) toast("No se pudo actualizar «${source.name}»: se mantienen los últimos canales")
                } else {
                    updateProvider(source.id) { it.copy(loading = false, catalog = null, error = errorFor(e)) }
                }
            }
        } finally {
            _ui.update { s -> s.copy(refreshing = s.providers.orEmpty().any { it.loading } || jobs.values.count { it.isActive } > 1) }
        }
    }

    private suspend fun publish(id: String, result: StreamRepository.LoadResult) {
        val catalog = withContext(Dispatchers.Default) { CatalogBuilder.build(result.items, aceOnly = preferences.aceOnlyImport) }
        // Resumen de importación: solo números (nunca nombres, URLs ni Content IDs).
        android.util.Log.i("MiVisor/Import", catalog.stats.summary())
        updateProvider(id) {
            it.copy(catalog = catalog, loading = false, updatedAt = result.savedAt, offline = result.offline, failedRefs = result.failedRefs,
                error = null, lastFailure = if (result.offline) it.lastFailure else null)
        }
        regroup()
    }

    private fun updateProvider(id: String, change: (ProviderState) -> ProviderState) {
        transient = transient.map { if (it.id == id) change(it) else it }
        _ui.update { state -> state.copy(providers = state.providers?.map { if (it.id == id) change(it) else it }) }
    }

    private fun publishProviders(change: (List<ProviderState>) -> List<ProviderState>) {
        _ui.update { state ->
            val saved = state.providers.orEmpty().filterNot { it.transient }
            state.copy(providers = change(saved + transient))
        }
    }

    /** Reagrupa los canales (fuera del hilo principal; las ráfagas de cargas se juntan). */
    private fun regroup() {
        groupJob?.cancel()
        groupJob = viewModelScope.launch {
            kotlinx.coroutines.delay(120)
            val providers = _ui.value.providers.orEmpty()
            val (groups, all) = withContext(Dispatchers.Default) {
                val input = providers.map { p ->
                    val channels = p.catalog?.sections.orEmpty().flatMap { s -> s.channels }.map { it.withProvider(p.id, p.name) }
                    Triple(p.id, p.name, channels)
                }
                ChannelGrouping.group(input) to input.flatMap { it.third }
            }
            _ui.update { it.copy(groups = groups) }
            _allChannels.value = all
        }
    }

    private fun errorFor(e: Throwable): UiError = when (e) {
        is CodeNotFoundException -> UiError(
            ErrorKind.NOT_FOUND, "Este código no existe",
            "Revisa que esté bien escrito. Los códigos no distinguen mayúsculas."
        )
        is DnsUnavailableException, is java.io.IOException -> UiError(
            ErrorKind.OFFLINE, "Sin conexión",
            "No se pudo contactar con ningún servidor DNS. Comprueba la red de la TV y vuelve a intentarlo."
        )
        is InvalidPayloadException -> UiError(
            ErrorKind.INVALID, "Datos no válidos",
            "El código existe pero su contenido no tiene un formato reconocible."
        )
        is NotCachedException -> UiError(ErrorKind.OFFLINE, "Sin conexión", "Y no hay copia guardada de este código.")
        else -> UiError(ErrorKind.OTHER, "Algo ha fallado", e.message ?: e.javaClass.simpleName)
    }

    private fun toast(message: String) {
        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
    }
}
