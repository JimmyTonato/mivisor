package com.mivisor.app.services

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Control de la WARP modificada con el MISMO protocolo que MiVisor-WARP-TestClient-1.2:
 *
 *  - STATUS inicial: ContentProvider (content://com.cloudflare.onedotonedotonedotone.mivisor.control,
 *    método "STATUS"), síncrono y fiable; si no está disponible, broadcast ordenado ACTION_STATUS.
 *  - Comandos: broadcasts ordenados ACTION_CONNECT (request_permission=false) / ACTION_DISCONNECT al
 *    paquete WARP, con request_id. El ACK llega en los "result extras".
 *  - Transiciones: ACTION_STATE_CHANGED, registrado exigiendo com.mivisor.permission.CONTROL_WARP al emisor
 *    (solo la WARP firmada con la misma clave puede enviarlo).
 *  - Autorización VPN: com.mivisor.warp.control.MiVisorVpnPermissionActivity (connect_after_grant=true).
 *    El consentimiento lo da el usuario en el diálogo oficial de Android (VpnService.prepare) dentro de WARP.
 *
 * La seguridad no se toca: si MiVisor no tiene el permiso (firma distinta) se muestra SIGNATURE_MISMATCH.
 */
class WarpController(
    context: Context,
    private val scope: CoroutineScope
) {
    private val app = context.applicationContext
    private val _state = MutableStateFlow(WarpStatus(WarpPhase.CHECKING))
    val state: StateFlow<WarpStatus> = _state.asStateFlow()

    private var requestCounter = 0
    private var listening = false
    private var timeoutJob: Job? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val extras = intent.extras ?: return
            val status = parse(extras)
            Log.i(TAG, "WarpController: STATE_CHANGED -> ${status.phase}${status.detail?.let { " ($it)" }.orEmpty()}")
            if (!status.transitional) timeoutJob?.cancel()
            set(status)
        }
    }

    // ------------------------------------------------------------------ ciclo de vida

    /** Al pasar a primer plano: escuchar STATE_CHANGED. */
    fun startListening() {
        if (listening || !isInstalled() || !hasControlPermission()) return
        val filter = IntentFilter(WarpProtocol.ACTION_STATE_CHANGED)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                // RECEIVER_EXPORTED (2): lo envía otra app, pero solo si tiene CONTROL_WARP (misma firma).
                app.registerReceiver(receiver, filter, WarpProtocol.PERMISSION_CONTROL_WARP, null, 2)
            } else {
                app.registerReceiver(receiver, filter, WarpProtocol.PERMISSION_CONTROL_WARP, null)
            }
            listening = true
        } catch (e: RuntimeException) {
            Log.w(TAG, "WarpController: cannot listen (${e.javaClass.simpleName})")
        }
    }

    /** Al pasar a segundo plano: dejar de escuchar (sin fugas). */
    fun stopListening() {
        if (!listening) return
        runCatching { app.unregisterReceiver(receiver) }
        listening = false
    }

    // ------------------------------------------------------------------ operaciones

    /** STATUS real (nunca se asume desconectada: puede estar conectada desde fuera de MiVisor). */
    suspend fun refresh(): WarpStatus {
        val result = when {
            !isInstalled() -> WarpStatus(WarpPhase.NOT_INSTALLED)
            !hasControlPermission() -> {
                Log.w(TAG, "WarpController: CONTROL_WARP not granted (signature mismatch?)")
                WarpStatus(WarpPhase.SIGNATURE_MISMATCH)
            }
            else -> statusViaProvider() ?: statusViaBroadcast() ?: WarpStatus(WarpPhase.ERROR, "NO_RESPONSE")
        }
        Log.i(TAG, "WarpController: STATUS -> ${result.phase}")
        val command = timeoutJob?.isActive == true
        val staleBefore = command && (
            (_state.value.phase == WarpPhase.CONNECTING && result.phase == WarpPhase.DISCONNECTED) ||
            (_state.value.phase == WarpPhase.DISCONNECTING && result.phase == WarpPhase.CONNECTED))
        if (!staleBefore) set(result)                      // un STATUS "de antes" no pisa un comando en curso
        // Transición iniciada fuera (p. ej. connect_after_grant al volver de la autorización): con límite de tiempo.
        if (result.transitional && !command) {
            armTimeout(if (result.phase == WarpPhase.DISCONNECTING) WarpProtocol.DISCONNECT_TIMEOUT_MS else WarpProtocol.CONNECT_TIMEOUT_MS)
        }
        return result
    }

    fun connect() {
        if (_state.value.phase == WarpPhase.CONNECTED || _state.value.transitional) return
        Log.i(TAG, "WarpController: CONNECT requested")
        set(WarpStatus(WarpPhase.CONNECTING))
        send(WarpProtocol.ACTION_CONNECT, Bundle().apply { putBoolean(WarpProtocol.EXTRA_REQUEST_PERMISSION, false) })
        armTimeout(WarpProtocol.CONNECT_TIMEOUT_MS)
    }

    fun disconnect() {
        if (_state.value.phase != WarpPhase.CONNECTED) return
        Log.i(TAG, "WarpController: DISCONNECT requested")
        set(WarpStatus(WarpPhase.DISCONNECTING))
        send(WarpProtocol.ACTION_DISCONNECT, null)
        armTimeout(WarpProtocol.DISCONNECT_TIMEOUT_MS)
    }

    /** Abre la Activity de autorización de WARP. Al volver, onResume → refresh() automático. */
    fun requestPermission(): Boolean = launch(Intent().apply {
        setClassName(WarpProtocol.WARP_PACKAGE, WarpProtocol.PERMISSION_ACTIVITY)
        putExtra(WarpProtocol.EXTRA_CONNECT_AFTER_GRANT, true)
    }, "permission activity")

    /** Abre WARP para completar su configuración/onboarding. */
    fun openApp(): Boolean {
        val pm = app.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(WarpProtocol.WARP_PACKAGE)
            ?: pm.getLaunchIntentForPackage(WarpProtocol.WARP_PACKAGE)
            ?: return false
        return launch(intent, "WARP app")
    }

    // ------------------------------------------------------------------ interno

    fun isInstalled(): Boolean = try {
        app.packageManager.getPackageInfo(WarpProtocol.WARP_PACKAGE, 0); true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** Igual que el cliente de prueba (y válido desde Android 5). */
    private fun hasControlPermission(): Boolean =
        app.checkCallingOrSelfPermission(WarpProtocol.PERMISSION_CONTROL_WARP) == PackageManager.PERMISSION_GRANTED

    private suspend fun statusViaProvider(): WarpStatus? = withContext(Dispatchers.IO) {
        try {
            app.contentResolver.call(Uri.parse(WarpProtocol.PROVIDER_URI), WarpProtocol.METHOD_STATUS, null, null)
                ?.let(::parse)
        } catch (e: SecurityException) {
            Log.w(TAG, "WarpController: provider SecurityException (signature?)")
            WarpStatus(WarpPhase.SIGNATURE_MISMATCH)
        } catch (e: Exception) {
            Log.i(TAG, "WarpController: provider unavailable (${e.javaClass.simpleName}), using broadcast")
            null
        }
    }

    private suspend fun statusViaBroadcast(): WarpStatus? = withTimeoutOrNull(STATUS_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            send(WarpProtocol.ACTION_STATUS, null) { ack -> if (cont.isActive) cont.resume(ack?.let(::parse)) }
        }
    }

    /** Broadcast ordenado al paquete WARP (como el cliente de prueba). El ACK llega en los result extras. */
    private fun send(action: String, extras: Bundle?, onAck: ((Bundle?) -> Unit)? = null) {
        val intent = Intent(action).apply {
            setPackage(WarpProtocol.WARP_PACKAGE)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            putExtra(WarpProtocol.EXTRA_REQUEST_ID, "mivisor-${++requestCounter}")
            extras?.let { putExtras(it) }
        }
        try {
            app.sendOrderedBroadcast(intent, null, object : BroadcastReceiver() {
                override fun onReceive(context: Context, result: Intent) {
                    val ack = getResultExtras(false)
                    if (onAck != null) {
                        onAck(ack)
                        return
                    }
                    val name = action.substringAfterLast('.')
                    if (ack == null) {
                        // Sin respuesta: WARP no instalada, receptor ausente o firma distinta.
                        Log.w(TAG, "WarpController: no ACK for $name")
                        timeoutJob?.cancel()
                        set(WarpStatus(WarpPhase.ERROR, "NO_RESPONSE"))
                        return
                    }
                    val status = parse(ack)
                    Log.i(TAG, "WarpController: ACK $name -> ${status.phase}${status.detail?.let { " ($it)" }.orEmpty()}")
                    val done = when (status.phase) {
                        WarpPhase.CONNECTING, WarpPhase.DISCONNECTING -> false
                        // Un ACK "DISCONNECTED" a un CONNECT es el estado anterior: se espera STATE_CHANGED.
                        WarpPhase.DISCONNECTED -> action == WarpProtocol.ACTION_DISCONNECT
                        WarpPhase.CONNECTED -> action == WarpProtocol.ACTION_CONNECT
                        else -> true                        // autorización, configuración o error: se muestra ya
                    }
                    if (done) {
                        timeoutJob?.cancel()
                        set(status)
                    }
                }
            }, null, 0, null, null)
        } catch (e: SecurityException) {
            Log.w(TAG, "WarpController: SecurityException sending ${action.substringAfterLast('.')}")
            onAck?.invoke(null) ?: set(WarpStatus(WarpPhase.SIGNATURE_MISMATCH))
        }
    }

    private fun armTimeout(ms: Long) {
        timeoutJob?.cancel()
        val pending = _state.value
        timeoutJob = scope.launch {
            delay(ms)
            val fresh = runCatching { statusViaProvider() ?: statusViaBroadcast() }.getOrNull()
            val result = WarpProtocol.afterTimeout(pending, fresh)
            Log.i(TAG, "WarpController: timeout -> ${result.phase}${result.detail?.let { " ($it)" }.orEmpty()}")
            set(result)
        }
    }

    private fun launch(intent: Intent, what: String): Boolean = try {
        app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Log.i(TAG, "WarpController: opened $what")
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "WarpController: $what not found")
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "WarpController: $what not allowed (signature?)")
        false
    }

    private fun parse(b: Bundle): WarpStatus = WarpProtocol.parse(
        b.getString(WarpProtocol.EXTRA_STATE),
        b.getString(WarpProtocol.EXTRA_DETAIL),
        if (b.containsKey(WarpProtocol.EXTRA_VPN_PERMISSION_GRANTED)) b.getBoolean(WarpProtocol.EXTRA_VPN_PERMISSION_GRANTED) else null
    )

    private fun set(status: WarpStatus) {
        _state.value = status
    }

    private companion object {
        const val TAG = "MiVisor/Services"
        const val STATUS_TIMEOUT_MS = 4_000L
    }
}
