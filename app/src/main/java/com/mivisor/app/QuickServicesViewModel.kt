package com.mivisor.app

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mivisor.app.services.AceStatus
import com.mivisor.app.services.AceStreamEngineController
import com.mivisor.app.services.QuickServiceText
import com.mivisor.app.services.RowAction
import com.mivisor.app.services.ServiceRow
import com.mivisor.app.services.WarpController
import com.mivisor.app.services.WarpStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class QuickServicesUi(val ace: ServiceRow, val warp: ServiceRow, val aceStatus: AceStatus, val warpStatus: WarpStatus)

/**
 * Servicios rápidos de la barra lateral. La UI solo llama a [onOk]; toda la comunicación con
 * Ace Stream y WARP está en los controladores (nada de broadcasts desde composables).
 */
class QuickServicesViewModel(application: Application) : AndroidViewModel(application) {
    val ace = AceStreamEngineController(application, viewModelScope)
    val warp = WarpController(application, viewModelScope)

    val ui: StateFlow<QuickServicesUi> = combine(ace.state, warp.state) { a, w ->
        QuickServicesUi(QuickServiceText.ace(a), QuickServiceText.warp(w), a, w)
    }.stateIn(
        viewModelScope, SharingStarted.Eagerly,
        QuickServicesUi(QuickServiceText.ace(ace.state.value), QuickServiceText.warp(warp.state.value), ace.state.value, warp.state.value)
    )

    /** onResume (abrir MiVisor, volver de la autorización VPN o de WARP): estado real, sin pulsar nada. */
    fun onForeground() {
        warp.startListening()
        viewModelScope.launch { warp.refresh() }
        viewModelScope.launch { ace.refresh() }
    }

    /** onStop: sin receptores ni enlaces innecesarios. Esto NO apaga el motor ni la VPN. */
    fun onBackground() {
        warp.stopListening()
        ace.release()
    }

    fun onOk(action: RowAction) {
        when (action) {
            RowAction.NONE -> Unit
            RowAction.REFRESH -> onForeground()
            RowAction.ACE_START -> ace.start()
            RowAction.WARP_CONNECT -> warp.connect()
            RowAction.WARP_DISCONNECT -> warp.disconnect()
            RowAction.WARP_AUTHORIZE -> if (!warp.requestPermission()) toast("No se pudo abrir la autorización de WARP")
            RowAction.WARP_OPEN_APP -> if (!warp.openApp()) toast("No se pudo abrir WARP")
        }
    }

    override fun onCleared() {
        onBackground()
    }

    private fun toast(text: String) = Toast.makeText(getApplication(), text, Toast.LENGTH_SHORT).show()
}
