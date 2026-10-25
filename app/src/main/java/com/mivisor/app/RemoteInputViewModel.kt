package com.mivisor.app

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mivisor.app.remote.AndroidLan
import com.mivisor.app.remote.MobileInputManager
import com.mivisor.app.remote.RemoteState
import com.mivisor.app.remote.FieldEdit
import kotlinx.coroutines.flow.StateFlow

/**
 * "Usar móvil" en el formulario de código. Solo entrega el texto recibido: guardar sigue siendo
 * cosa del botón del formulario. Si la Activity se destruye, la sesión se invalida (onCleared).
 */
class RemoteInputViewModel(application: Application) : AndroidViewModel(application) {
    private val manager = MobileInputManager(
        scope = viewModelScope,
        lanAddress = { AndroidLan.pick(application) },
        log = { Log.i("MiVisor/Remote", it) }          // mensajes fijos: nunca el texto ni el token
    )
    val state: StateFlow<RemoteState> = manager.state
    val edits: StateFlow<FieldEdit?> = manager.edits

    fun startInput() = manager.startInput()
    fun startShow(text: String) = manager.startShow(text)
    fun startEdit(heading: String, title: String, code: String) = manager.startEdit(heading, title, code)
    fun updateFields(title: String, code: String) = manager.updateFields(title, code)
    fun consume() = manager.consume()
    fun cancel() = manager.cancel()

    override fun onCleared() = manager.cancel()
}
