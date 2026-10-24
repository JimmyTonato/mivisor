package com.mivisor.app.services

import com.google.gson.JsonParser
import kotlinx.coroutines.delay

/*
 * Servicios rápidos (Ace Stream Engine y VPN/WARP): estados, protocolo y presentación.
 * Todo lo de este fichero es lógica pura (sin Android) para poder probarlo en la JVM.
 */

// ============================================================================ Ace Stream

enum class AcePhase { CHECKING, NOT_INSTALLED, STOPPED, STARTING, READY, ERROR }

enum class AceError {
    /** El servicio de Ace Stream respondió onFailed() o no se pudo enlazar. */
    START_FAILED,
    /** El servicio dijo que había arrancado, pero la HTTP API no responde (no se marca READY en falso). */
    NOT_RESPONDING,
    /** No hubo confirmación en el tiempo límite. */
    TIMEOUT
}

data class AceStatus(
    val phase: AcePhase,
    /** Puerto real de la HTTP API (el que devuelve el servicio; 6878 solo si no se conoce otro). */
    val httpPort: Int? = null,
    val version: String? = null,
    val error: AceError? = null
)

object AceProtocol {
    const val DEFAULT_HTTP_PORT = 6878
    const val START_TIMEOUT_MS = 15_000L
    const val PROBE_INTERVAL_MS = 700L

    fun versionUrl(port: Int) = "http://127.0.0.1:$port/webui/api/service?method=get_version"

    /** {"result":{"code":…,"platform":"android","version":"3.1.70"},"error":null} → "3.1.70". Null si no es el motor. */
    fun parseVersion(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return runCatching {
            val root = JsonParser.parseString(body).asJsonObject
            val error = root.get("error")
            if (error != null && !error.isJsonNull) return null
            root.getAsJsonObject("result")?.get("version")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * Espera a que la HTTP API responda en [port]: READY solo con evidencia real.
     * [probe] devuelve la versión o null. Si se agota [timeoutMs] → ERROR(NOT_RESPONDING).
     */
    suspend fun awaitReady(
        port: Int,
        timeoutMs: Long,
        probe: suspend (Int) -> String?,
        intervalMs: Long = PROBE_INTERVAL_MS,
        clock: () -> Long = System::currentTimeMillis
    ): AceStatus {
        val deadline = clock() + timeoutMs
        while (true) {
            val version = runCatching { probe(port) }.getOrNull()
            if (version != null) return AceStatus(AcePhase.READY, port, version)
            if (clock() + intervalMs > deadline) return AceStatus(AcePhase.ERROR, port, error = AceError.NOT_RESPONDING)
            delay(intervalMs)
        }
    }
}

// ============================================================================ WARP

enum class WarpPhase {
    CHECKING,
    NOT_INSTALLED,
    /** MiVisor no tiene com.mivisor.permission.CONTROL_WARP (firma distinta): no se degrada la seguridad. */
    SIGNATURE_MISMATCH,
    DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING,
    PERMISSION_REQUIRED,
    /** WARP necesita completar su configuración/onboarding (abrir la app). */
    NEEDS_SETUP,
    ERROR
}

data class WarpStatus(val phase: WarpPhase, val detail: String? = null) {
    val transitional: Boolean get() = phase == WarpPhase.CONNECTING || phase == WarpPhase.DISCONNECTING
}

/**
 * Contrato del control externo de la WARP modificada, idéntico al de
 * MiVisor-WARP-TestClient-1.2 (com.mivisor.warp.control.WarpControlContract). No es un protocolo nuevo.
 */
object WarpProtocol {
    const val WARP_PACKAGE = "com.cloudflare.onedotonedotonedotone"
    const val PERMISSION_CONTROL_WARP = "com.mivisor.permission.CONTROL_WARP"
    const val ACTION_CONNECT = "com.mivisor.warp.CONNECT"
    const val ACTION_DISCONNECT = "com.mivisor.warp.DISCONNECT"
    const val ACTION_SET_STATE = "com.mivisor.warp.SET_STATE"
    const val ACTION_STATUS = "com.mivisor.warp.STATUS"
    const val ACTION_STATE_CHANGED = "com.mivisor.warp.STATE_CHANGED"
    const val PROVIDER_URI = "content://com.cloudflare.onedotonedotonedotone.mivisor.control"
    const val METHOD_STATUS = "STATUS"
    const val PERMISSION_ACTIVITY = "com.mivisor.warp.control.MiVisorVpnPermissionActivity"

    const val EXTRA_STATE = "state"
    const val EXTRA_DETAIL = "detail"
    const val EXTRA_REQUEST_ID = "request_id"
    const val EXTRA_REQUEST_PERMISSION = "request_permission"
    const val EXTRA_CONNECT_AFTER_GRANT = "connect_after_grant"
    const val EXTRA_VPN_PERMISSION_GRANTED = "vpn_permission_granted"
    const val EXTRA_ERROR = "error"

    const val STATE_CONNECTED = "CONNECTED"
    const val STATE_CONNECTING = "CONNECTING"
    const val STATE_DISCONNECTED = "DISCONNECTED"
    const val STATE_DISCONNECTING = "DISCONNECTING"
    const val STATE_PERMISSION_REQUIRED = "PERMISSION_REQUIRED"
    const val STATE_ERROR = "ERROR"

    const val DETAIL_VPN_CONSENT_MISSING = "VPN_CONSENT_MISSING"
    const val DETAIL_PERMISSION_UI_LAUNCHED = "PERMISSION_UI_LAUNCHED"
    const val DETAIL_CONNECT_TIMEOUT = "CONNECT_TIMEOUT"
    const val DETAIL_DISCONNECT_TIMEOUT = "DISCONNECT_TIMEOUT"

    /** Detalles que indican que WARP aún no está preparada: hay que abrir la app una vez. */
    val SETUP_DETAILS = setOf("MISSING_CERTIFICATE", "SETUP_FAILED", "FGS_START_NOT_ALLOWED")

    const val CONNECT_TIMEOUT_MS = 45_000L
    const val DISCONNECT_TIMEOUT_MS = 20_000L

    /**
     * Extras de STATUS / ACK / STATE_CHANGED → estado de la UI.
     * [vpnPermissionGranted] = extra "vpn_permission_granted" (null si no viene).
     */
    fun parse(state: String?, detail: String?, vpnPermissionGranted: Boolean? = null): WarpStatus {
        val d = detail?.takeIf { it.isNotBlank() && it != "NONE" }
        return when {
            state == STATE_PERMISSION_REQUIRED || d == DETAIL_VPN_CONSENT_MISSING ->
                WarpStatus(WarpPhase.PERMISSION_REQUIRED, d)
            state == STATE_CONNECTED -> WarpStatus(WarpPhase.CONNECTED, d)
            vpnPermissionGranted == false && state == STATE_DISCONNECTED -> WarpStatus(WarpPhase.PERMISSION_REQUIRED, d)
            state == STATE_CONNECTING -> WarpStatus(WarpPhase.CONNECTING, d)
            state == STATE_DISCONNECTING -> WarpStatus(WarpPhase.DISCONNECTING, d)
            state == STATE_DISCONNECTED -> WarpStatus(WarpPhase.DISCONNECTED, d)
            state == STATE_ERROR && d in SETUP_DETAILS -> WarpStatus(WarpPhase.NEEDS_SETUP, d)
            state == STATE_ERROR -> WarpStatus(WarpPhase.ERROR, d)
            else -> WarpStatus(WarpPhase.ERROR, d ?: "UNKNOWN_STATE")
        }
    }

    /**
     * Tras agotarse el tiempo de una conexión/desconexión se vuelve a consultar STATUS ([fresh]).
     * Si sigue en transición (o no hay respuesta) → ERROR con el detalle de timeout: nunca un spinner infinito.
     */
    fun afterTimeout(pending: WarpStatus, fresh: WarpStatus?): WarpStatus = when {
        fresh != null && !fresh.transitional -> fresh
        pending.phase == WarpPhase.DISCONNECTING -> WarpStatus(WarpPhase.ERROR, DETAIL_DISCONNECT_TIMEOUT)
        else -> WarpStatus(WarpPhase.ERROR, DETAIL_CONNECT_TIMEOUT)
    }
}

// ============================================================================ Presentación (filas de la barra lateral)

enum class Indicator { OK, NEUTRAL, WARNING, ERROR, BUSY }

enum class RowAction {
    NONE, REFRESH,
    ACE_START,
    WARP_CONNECT, WARP_DISCONNECT, WARP_AUTHORIZE, WARP_OPEN_APP
}

data class ServiceRow(
    val title: String,
    val status: String,
    val indicator: Indicator,
    /** Texto del botón de la fila (INICIAR, AUTORIZAR VPN…) o null. */
    val actionLabel: String?,
    /** Estado del interruptor: true = ON, false = OFF, null = no hay interruptor (p. ej. Ace Stream). */
    val toggle: Boolean?,
    /** Lo que hace OK sobre la fila (toda la fila es el control). */
    val onOk: RowAction
)

object QuickServiceText {
    fun ace(status: AceStatus): ServiceRow {
        val t = "Ace Stream"
        return when (status.phase) {
            AcePhase.CHECKING -> ServiceRow(t, "Comprobando…", Indicator.BUSY, null, null, RowAction.NONE)
            AcePhase.READY -> ServiceRow(t, "Motor activo", Indicator.OK, null, null, RowAction.REFRESH)
            AcePhase.STARTING -> ServiceRow(t, "Iniciando…", Indicator.BUSY, null, null, RowAction.NONE)
            AcePhase.STOPPED -> ServiceRow(t, "Motor detenido", Indicator.WARNING, "INICIAR", null, RowAction.ACE_START)
            AcePhase.NOT_INSTALLED -> ServiceRow(t, "No instalado", Indicator.WARNING, null, null, RowAction.REFRESH)
            AcePhase.ERROR -> ServiceRow(
                t,
                if (status.error == AceError.START_FAILED) "No se pudo iniciar" else "Ace Stream Engine no responde",
                Indicator.ERROR, "REINTENTAR", null, RowAction.ACE_START
            )
        }
    }

    fun warp(status: WarpStatus): ServiceRow {
        val t = "VPN / WARP"
        return when (status.phase) {
            WarpPhase.CHECKING -> ServiceRow(t, "Comprobando…", Indicator.BUSY, null, null, RowAction.NONE)
            WarpPhase.CONNECTED -> ServiceRow(t, "Conectada", Indicator.OK, null, true, RowAction.WARP_DISCONNECT)
            WarpPhase.DISCONNECTED -> ServiceRow(t, "Desconectada", Indicator.NEUTRAL, null, false, RowAction.WARP_CONNECT)
            WarpPhase.CONNECTING -> ServiceRow(t, "Conectando…", Indicator.BUSY, null, true, RowAction.NONE)
            WarpPhase.DISCONNECTING -> ServiceRow(t, "Desconectando…", Indicator.BUSY, null, false, RowAction.NONE)
            WarpPhase.PERMISSION_REQUIRED ->
                ServiceRow(t, "Se necesita autorización", Indicator.WARNING, "AUTORIZAR VPN", null, RowAction.WARP_AUTHORIZE)
            WarpPhase.NEEDS_SETUP ->
                ServiceRow(t, "Requiere configuración", Indicator.WARNING, "ABRIR WARP", null, RowAction.WARP_OPEN_APP)
            WarpPhase.NOT_INSTALLED -> ServiceRow(t, "No instalada", Indicator.WARNING, null, null, RowAction.REFRESH)
            WarpPhase.SIGNATURE_MISMATCH ->
                ServiceRow(t, "Sin permiso de control (firma)", Indicator.ERROR, null, null, RowAction.REFRESH)
            WarpPhase.ERROR -> ServiceRow(
                t,
                when (status.detail) {
                    WarpProtocol.DETAIL_CONNECT_TIMEOUT -> "No se pudo conectar"
                    WarpProtocol.DETAIL_DISCONNECT_TIMEOUT -> "No se pudo desconectar"
                    "NO_RESPONSE" -> "No se pudo comunicar con WARP"
                    else -> "Error de WARP"
                },
                Indicator.ERROR, "REINTENTAR", null, RowAction.REFRESH
            )
        }
    }
}
