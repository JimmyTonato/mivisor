package com.mivisor.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mivisor.app.data.ServicesRepository
import com.mivisor.app.model.ServicesReport
import com.mivisor.app.player.PlayerPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class ServicesUi(
    val loading: Boolean = true,
    val report: ServicesReport? = null,
    val fetchedAt: Long = 0L,
    /** Copia guardada: puede no estar actualizada. */
    val offline: Boolean = false,
    val error: String? = null,
    val selected: String? = null
)

/** Sección Servicios: solo descarga services.json. Nunca llama a las APIs ni lanza GitHub Actions. */
class ServicesViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ServicesRepository(File(application.cacheDir, "agenda"))
    private val prefs = PlayerPreferences(application)
    private val _ui = MutableStateFlow(ServicesUi())
    val ui: StateFlow<ServicesUi> = _ui.asStateFlow()
    private var job: Job? = null

    private val url: String get() = ServicesRepository.urlFor(prefs.agendaUrl.ifBlank { BuildConfig.AGENDA_URL })

    init { load() }

    fun load(force: Boolean = false) {
        job?.cancel()
        _ui.update { it.copy(loading = it.report == null, error = null) }
        job = viewModelScope.launch {
            try {
                val result = repository.load(url, force)
                _ui.update { it.copy(loading = false, report = result.report, fetchedAt = result.fetchedAt, offline = result.offline) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = e.message ?: "No se pudo cargar el diagnóstico") }
            }
        }
    }

    fun select(id: String?) = _ui.update { it.copy(selected = id) }
}
