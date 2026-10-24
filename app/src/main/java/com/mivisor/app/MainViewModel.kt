package com.mivisor.app

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mivisor.app.data.CatalogBuilder
import com.mivisor.app.data.Codes
import com.mivisor.app.data.HistoryStore
import com.mivisor.app.data.InvalidPayloadException
import com.mivisor.app.data.NotCachedException
import com.mivisor.app.data.PayloadDecoder
import com.mivisor.app.data.RawCache
import com.mivisor.app.data.SourceStore
import com.mivisor.app.data.StreamRepository
import com.mivisor.app.data.StreamRepository.Mode
import com.mivisor.app.data.sharedGson
import com.mivisor.app.model.Catalog
import com.mivisor.app.model.HistoryItem
import com.mivisor.app.model.SourceProfile
import com.mivisor.app.network.CodeNotFoundException
import com.mivisor.app.network.CompositeDnsResolver
import com.mivisor.app.network.DnsUnavailableException
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
import kotlinx.coroutines.withContext
import java.io.File

enum class ErrorKind { NOT_FOUND, OFFLINE, INVALID, OTHER }

class UiError(val kind: ErrorKind, val title: String, val detail: String)

data class HomeUiState(
    val activeSourceId: String? = null,
    val code: String = "",
    val title: String = "",
    val catalog: Catalog? = null,
    /** Cargando sin nada que enseñar todavía. */
    val loading: Boolean = false,
    /** Mostrando datos guardados mientras se actualiza en segundo plano. */
    val refreshing: Boolean = false,
    val updatedAt: Long = 0L,
    val offline: Boolean = false,
    val failedRefs: Int = 0,
    val error: UiError? = null,
    /** Se incrementa en cada carga pedida por el usuario: la UI lleva el foco al contenido. */
    val focusToken: Int = 0
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val historyStore = HistoryStore(application)
    private val sourceStore = SourceStore(application)
    private val repository = StreamRepository(
        resolver = CompositeDnsResolver(),
        decoder = PayloadDecoder(sharedGson),
        cache = RawCache(File(application.cacheDir, "txt_cache")),
        domain = BuildConfig.STREAM_DOMAIN
    )

    private val _ui = MutableStateFlow(HomeUiState())
    val ui: StateFlow<HomeUiState> = _ui.asStateFlow()

    val history: StateFlow<List<HistoryItem>> =
        historyStore.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** null = todavía leyendo de disco (evita parpadeo de la pantalla de bienvenida). */
    val sources: StateFlow<List<SourceProfile>?> =
        sourceStore.items.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var loadJob: Job? = null
    private var autoOpened = false

    /** Abre la primera categoría al arrancar (una sola vez), si no llegó un deep link. */
    fun autoOpen(sources: List<SourceProfile>) {
        if (autoOpened || _ui.value.code.isNotEmpty()) return
        autoOpened = true
        sources.firstOrNull()?.let { open(it.code, it.name, it.id) }
    }

    fun openSource(source: SourceProfile, force: Boolean = false) =
        open(source.code, source.name, source.id, force = force)

    fun openHistory(item: HistoryItem) {
        val source = sources.value?.firstOrNull { it.code.equals(item.code, true) }
        open(item.code, source?.name ?: item.title, source?.id)
    }

    fun openCode(raw: String, title: String? = null) {
        autoOpened = true
        val code = Codes.normalizeOrNull(raw) ?: return toast("Código no válido")
        val source = sources.value?.firstOrNull { it.code == code }
        open(code, title ?: source?.name ?: code, source?.id)
    }

    fun refresh() {
        val state = _ui.value
        if (state.code.isNotEmpty()) open(state.code, state.title, state.activeSourceId, force = true, userInitiated = false)
    }

    /** Crea, renombra o cambia el código de una categoría (`replacing` = la que se edita). */
    fun saveSource(name: String, code: String, openAfter: Boolean, replacing: SourceProfile? = null) {
        viewModelScope.launch {
            runCatching {
                val saved = sourceStore.upsert(name, code)
                if (replacing != null && replacing.id != saved.id) sourceStore.remove(replacing.id)
                saved
            }.onSuccess { saved ->
                if (_ui.value.activeSourceId == replacing?.id) {
                    _ui.update { it.copy(activeSourceId = saved.id, title = saved.name) }
                }
                if (openAfter) openSource(saved, force = true) else toast("Categoría guardada")
            }.onFailure { toast(it.message ?: "No se pudo guardar") }
        }
    }

    fun removeSource(source: SourceProfile) {
        viewModelScope.launch {
            sourceStore.remove(source.id)
            if (_ui.value.activeSourceId == source.id) _ui.update { it.copy(activeSourceId = null) }
        }
    }

    fun moveSource(source: SourceProfile, delta: Int) {
        viewModelScope.launch { sourceStore.move(source.id, delta) }
    }

    fun removeHistory(code: String) {
        viewModelScope.launch { historyStore.remove(code) }
    }

    fun clearHistory() {
        viewModelScope.launch { historyStore.clear() }
    }

    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.clearCache() }
            toast("Caché borrada")
        }
    }

    // ---------------------------------------------------------------------------------

    private fun open(
        rawCode: String,
        title: String,
        sourceId: String?,
        force: Boolean = false,
        userInitiated: Boolean = true
    ) {
        val code = Codes.normalizeOrNull(rawCode) ?: return toast("Código no válido")
        // Cancela la carga anterior: cambiar de categoría a mitad de carga ya no se ignora.
        loadJob?.cancel()
        val sameCode = _ui.value.code == code
        _ui.update {
            it.copy(
                activeSourceId = sourceId,
                code = code,
                title = title.ifBlank { code },
                catalog = if (sameCode) it.catalog else null,
                loading = !sameCode || it.catalog == null,
                refreshing = sameCode && it.catalog != null,
                error = null,
                failedRefs = if (sameCode) it.failedRefs else 0,
                offline = if (sameCode) it.offline else false,
                focusToken = if (userInitiated) it.focusToken + 1 else it.focusToken
            )
        }
        loadJob = viewModelScope.launch { load(code, force) }
    }

    private suspend fun load(code: String, force: Boolean) {
        // 1) Copia local al instante (si existe y no se fuerza la actualización).
        if (!force) {
            val cached = try {
                repository.load(code, Mode.CACHE_ONLY)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (cached != null) {
                publish(code, cached)
                if (repository.isFresh(cached.savedAt) && cached.failedRefs == 0) {
                    _ui.update { it.copy(refreshing = false) }
                    return
                }
                _ui.update { it.copy(refreshing = true) }
            }
        }
        // 2) Red (las referencias aún frescas se reutilizan salvo actualización forzada).
        try {
            val result = repository.load(code, Mode.NETWORK, if (force) Mode.NETWORK else Mode.PREFER_CACHE)
            publish(code, result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val hasContent = _ui.value.catalog != null
            if (hasContent && e !is CodeNotFoundException) {
                _ui.update { it.copy(refreshing = false, offline = true) }
                toast("Sin conexión: mostrando la copia guardada")
            } else {
                _ui.update { it.copy(loading = false, refreshing = false, catalog = null, error = errorFor(e)) }
            }
        }
    }

    private suspend fun publish(code: String, result: StreamRepository.LoadResult) {
        val catalog = withContext(Dispatchers.Default) { CatalogBuilder.build(result.items) }
        _ui.update {
            if (it.code != code) return@update it
            it.copy(
                catalog = catalog,
                loading = false,
                refreshing = false,
                updatedAt = result.savedAt,
                offline = result.offline,
                failedRefs = result.failedRefs,
                error = null
            )
        }
        val state = _ui.value
        historyStore.add(
            HistoryItem(
                code = code,
                title = state.title.ifBlank { code },
                lastOpenedAt = System.currentTimeMillis(),
                type = "${catalog.channelCount}"
            )
        )
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
