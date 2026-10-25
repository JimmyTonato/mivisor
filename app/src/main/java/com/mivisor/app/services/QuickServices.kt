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

// ============================================================================ Presentación (bloque "Servicios de reproducción")

/**
 * Jerarquía visual: correcto → verde discreto · desconectado normal → gris · requiere acción → ámbar ·
 * error real → rojo · en curso → spinner. "VPN desconectada" (gris) no es "VPN con error" (rojo).
 */
enum class Indicator { OK, NEUTRAL, WARNING, ERROR, BUSY }

enum class RowAction {
    NONE, REFRESH,
    /** Fila correcta: OK abre el panel rápido (detalle, detener/desconectar, gestionar). */
    PANEL,
    ACE_START, ACE_INSTALL_HELP,
    WARP_CONNECT, WARP_DISCONNECT, WARP_AUTHORIZE, WARP_OPEN_APP, WARP_INSTALL_HELP, WARP_SIGNATURE_HELP
}

data class ServiceRow(
    val title: String,
    val status: String,
    val indicator: Indicator,
    /** Botón de la fila ("Iniciar", "Conectar", "Autorizar"…) o null si no hay nada que hacer. */
    val actionLabel: String?,
    /** Estado ON/OFF para Ajustes. null = no aplica (Ace Stream no tiene apagado oficial). */
    val toggle: Boolean?,
    /** Lo que hace OK sobre la fila (toda la fila es el control). */
    val onOk: RowAction,
    /** Explicación de una línea (panel rápido y Ajustes). */
    val detail: String = ""
)

object QuickServiceText {
    fun ace(status: AceStatus): ServiceRow {
        val t = "Ace Stream"
        return when (status.phase) {
            AcePhase.CHECKING -> ServiceRow(t, "Comprobando…", Indicator.BUSY, null, null, RowAction.NONE, "Consultando el estado del motor.")
            AcePhase.READY -> ServiceRow(t, "Activo", Indicator.OK, null, null, RowAction.PANEL, "Motor funcionando correctamente.")
            AcePhase.STARTING -> ServiceRow(t, "Iniciando…", Indicator.BUSY, null, null, RowAction.NONE, "Puede tardar unos segundos.")
            AcePhase.STOPPED -> ServiceRow(t, "Motor detenido", Indicator.WARNING, "Iniciar", null, RowAction.ACE_START, "El motor no está en ejecución.")
            AcePhase.NOT_INSTALLED -> ServiceRow(t, "No instalado", Indicator.WARNING, "Cómo instalar", null, RowAction.ACE_INSTALL_HELP, "Necesitas instalar Ace Stream.")
            AcePhase.ERROR -> ServiceRow(
                t,
                if (status.error == AceError.NOT_RESPONDING) "El motor no responde" else "No se pudo iniciar",
                Indicator.ERROR, "Reintentar", null, RowAction.ACE_START, "No se pudo iniciar el motor."
            )
        }
    }

    fun warp(status: WarpStatus): ServiceRow {
        val t = "VPN / WARP"
        return when (status.phase) {
            WarpPhase.CHECKING -> ServiceRow(t, "Comprobando…", Indicator.BUSY, null, null, RowAction.NONE, "Consultando el estado de la VPN.")
            WarpPhase.CONNECTED -> ServiceRow(t, "Conectada", Indicator.OK, null, true, RowAction.PANEL, "Tu conexión está protegida.")
            WarpPhase.DISCONNECTED -> ServiceRow(t, "Desconectada", Indicator.NEUTRAL, "Conectar", false, RowAction.WARP_CONNECT, "La VPN no está activa.")
            WarpPhase.CONNECTING -> ServiceRow(t, "Conectando…", Indicator.BUSY, null, true, RowAction.NONE, "Estableciendo conexión…")
            WarpPhase.DISCONNECTING -> ServiceRow(t, "Desconectando…", Indicator.BUSY, null, false, RowAction.NONE, "Cerrando conexión…")
            WarpPhase.PERMISSION_REQUIRED ->
                ServiceRow(t, "Requiere autorización", Indicator.WARNING, "Autorizar", null, RowAction.WARP_AUTHORIZE, "Android necesita tu permiso.")
            WarpPhase.NEEDS_SETUP ->
                ServiceRow(t, "Falta configurar", Indicator.WARNING, "Configurar", null, RowAction.WARP_OPEN_APP, "Configura la VPN / WARP.")
            WarpPhase.NOT_INSTALLED ->
                ServiceRow(t, "No instalada", Indicator.WARNING, "Cómo instalar", null, RowAction.WARP_INSTALL_HELP, "No se encontró la aplicación.")
            WarpPhase.SIGNATURE_MISMATCH ->
                ServiceRow(t, "Sin permiso de control", Indicator.ERROR, "Más información", null, RowAction.WARP_SIGNATURE_HELP,
                    "La VPN instalada no es la versión firmada para MiVisor.")
            WarpPhase.ERROR -> when (status.detail) {
                WarpProtocol.DETAIL_DISCONNECT_TIMEOUT ->
                    ServiceRow(t, "No se pudo desconectar", Indicator.ERROR, "Reintentar", null, RowAction.REFRESH, "La VPN no respondió a tiempo.")
                "NO_RESPONSE" ->
                    ServiceRow(t, "No se pudo comunicar con WARP", Indicator.ERROR, "Reintentar", null, RowAction.REFRESH, "WARP no respondió.")
                else ->
                    ServiceRow(t, "No se pudo conectar", Indicator.ERROR, "Reintentar", null, RowAction.WARP_CONNECT, "No se pudo conectar.")
            }
        }
    }

    /** Texto de "Cómo instalar" (sin enlaces externos ni descargas desde la app). */
    const val ACE_INSTALL_HELP =
        "Instala «Ace Stream Media» o «Ace Stream Engine» desde la tienda de aplicaciones de tu TV (o desde acestream.org " +
            "en otro dispositivo). Después vuelve a MiVisor: el estado se actualizará solo."
    const val WARP_INSTALL_HELP =
        "Instala la versión de WARP preparada para MiVisor (la que está firmada con la misma clave que esta app). " +
            "Después vuelve a MiVisor: el estado se actualizará solo."
    const val WARP_SIGNATURE_HELP =
        "La VPN instalada no permite que MiVisor la controle: no está firmada con la misma clave. " +
            "Instala la versión de WARP para MiVisor. Por seguridad, MiVisor no rebaja este permiso."
}
