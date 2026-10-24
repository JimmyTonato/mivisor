package com.mivisor.app

import com.mivisor.app.services.AceError
import com.mivisor.app.services.AcePhase
import com.mivisor.app.services.AceProtocol
import com.mivisor.app.services.AceStatus
import com.mivisor.app.services.Indicator
import com.mivisor.app.services.QuickServiceText
import com.mivisor.app.services.RowAction
import com.mivisor.app.services.WarpPhase
import com.mivisor.app.services.WarpProtocol
import com.mivisor.app.services.WarpStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Servicios rápidos: lógica pura de WARP y Ace Stream (los casos obligatorios del encargo). */
class QuickServicesTest {

    // ---------------------------------------------------------------- contrato WARP (idéntico al cliente de prueba)

    @Test fun warpContractMatchesTestClient() {
        assertEquals("com.cloudflare.onedotonedotonedotone", WarpProtocol.WARP_PACKAGE)
        assertEquals("com.mivisor.permission.CONTROL_WARP", WarpProtocol.PERMISSION_CONTROL_WARP)
        assertEquals("com.mivisor.warp.CONNECT", WarpProtocol.ACTION_CONNECT)
        assertEquals("com.mivisor.warp.DISCONNECT", WarpProtocol.ACTION_DISCONNECT)
        assertEquals("com.mivisor.warp.SET_STATE", WarpProtocol.ACTION_SET_STATE)
        assertEquals("com.mivisor.warp.STATUS", WarpProtocol.ACTION_STATUS)
        assertEquals("com.mivisor.warp.STATE_CHANGED", WarpProtocol.ACTION_STATE_CHANGED)
        assertEquals("content://com.cloudflare.onedotonedotonedotone.mivisor.control", WarpProtocol.PROVIDER_URI)
        assertEquals("com.mivisor.warp.control.MiVisorVpnPermissionActivity", WarpProtocol.PERMISSION_ACTIVITY)
        assertEquals("connect_after_grant", WarpProtocol.EXTRA_CONNECT_AFTER_GRANT)
        assertEquals("request_permission", WarpProtocol.EXTRA_REQUEST_PERMISSION)
        assertEquals("vpn_permission_granted", WarpProtocol.EXTRA_VPN_PERMISSION_GRANTED)
    }

    private fun row(state: String?, detail: String? = null, granted: Boolean? = null) =
        QuickServiceText.warp(WarpProtocol.parse(state, detail, granted))

    // Caso 1: instalada, autorizada y desconectada → OFF; OK conecta; CONNECTING → CONNECTED → ON.
    @Test fun warpCase1ConnectFlow() {
        val off = row("DISCONNECTED", "NONE", true)
        assertEquals(false, off.toggle)
        assertEquals("Desconectada", off.status)
        assertEquals(Indicator.NEUTRAL, off.indicator)
        assertEquals(RowAction.WARP_CONNECT, off.onOk)
        val connecting = row("CONNECTING", "TUNNEL_STARTING")
        assertEquals(Indicator.BUSY, connecting.indicator)
        assertEquals(RowAction.NONE, connecting.onOk)                 // sin dobles pulsaciones durante la transición
        val on = row("CONNECTED")
        assertEquals(true, on.toggle)
        assertEquals(Indicator.OK, on.indicator)
    }

    // Caso 2: conectada antes de abrir MiVisor → STATUS CONNECTED se muestra tal cual (no se reconecta).
    @Test fun warpCase2AlreadyConnected() {
        val status = WarpProtocol.parse("CONNECTED", "ALREADY_CONNECTED", true)
        assertEquals(WarpPhase.CONNECTED, status.phase)
        assertEquals(RowAction.WARP_DISCONNECT, QuickServiceText.warp(status).onOk)
    }

    // Caso 3: conectada → OFF → DISCONNECTING → DISCONNECTED.
    @Test fun warpCase3Disconnect() {
        assertEquals("Desconectando…", row("DISCONNECTING").status)
        assertEquals(false, row("DISCONNECTING").toggle)
        assertEquals(false, row("DISCONNECTED").toggle)
    }

    // Caso 4: VPN no autorizada → "AUTORIZAR VPN", venga como estado, detalle o extra.
    @Test fun warpCase4PermissionRequired() {
        for (r in listOf(row("PERMISSION_REQUIRED"), row("ERROR", "VPN_CONSENT_MISSING"), row("DISCONNECTED", null, false))) {
            assertEquals("AUTORIZAR VPN", r.actionLabel)
            assertEquals(RowAction.WARP_AUTHORIZE, r.onOk)
            assertEquals(Indicator.WARNING, r.indicator)
            assertNull(r.toggle)                                        // no se muestra el interruptor
        }
        // Tras volver de la autorización con connect_after_grant: CONNECTED directamente.
        assertEquals(true, row("CONNECTED", "NONE", true).toggle)
    }

    // Caso 5: WARP no instalada → sin crash, "No instalada".
    @Test fun warpCase5NotInstalled() {
        val r = QuickServiceText.warp(WarpStatus(WarpPhase.NOT_INSTALLED))
        assertEquals("No instalada", r.status)
        assertNull(r.toggle)
    }

    // Caso 6: firma incorrecta → error manejado (sin texto técnico) y sin rebajar la seguridad.
    @Test fun warpCase6SignatureMismatch() {
        val r = QuickServiceText.warp(WarpStatus(WarpPhase.SIGNATURE_MISMATCH))
        assertEquals(Indicator.ERROR, r.indicator)
        assertTrue("SecurityException" !in r.status)
        assertEquals("No se pudo comunicar con WARP", QuickServiceText.warp(WarpStatus(WarpPhase.ERROR, "NO_RESPONSE")).status)
    }

    // Caso 7: tarda en conectar → spinner hasta CONNECTED o error por tiempo (nunca infinito).
    @Test fun warpCase7Timeout() {
        val pending = WarpStatus(WarpPhase.CONNECTING)
        assertEquals(WarpPhase.CONNECTED, WarpProtocol.afterTimeout(pending, WarpStatus(WarpPhase.CONNECTED)).phase)
        val stuck = WarpProtocol.afterTimeout(pending, WarpStatus(WarpPhase.CONNECTING))
        assertEquals(WarpStatus(WarpPhase.ERROR, "CONNECT_TIMEOUT"), stuck)
        assertEquals("No se pudo conectar", QuickServiceText.warp(stuck).status)
        assertEquals(RowAction.REFRESH, QuickServiceText.warp(stuck).onOk)
        assertEquals("DISCONNECT_TIMEOUT", WarpProtocol.afterTimeout(WarpStatus(WarpPhase.DISCONNECTING), null).detail)
    }

    @Test fun warpNeedsSetupOpensApp() {
        val r = row("ERROR", "MISSING_CERTIFICATE")
        assertEquals("Requiere configuración", r.status)
        assertEquals("ABRIR WARP", r.actionLabel)
        assertEquals(RowAction.WARP_OPEN_APP, r.onOk)
        assertEquals(WarpPhase.ERROR, WarpProtocol.parse("ERROR", "START_FAILED").phase)
        assertEquals(WarpPhase.ERROR, WarpProtocol.parse("¿?", null).phase)       // estado desconocido: sin crash
    }

    // ---------------------------------------------------------------- Ace Stream

    // Caso 1: instalado y parado → "Motor detenido" + INICIAR; luego "Iniciando…" → READY.
    @Test fun aceCase1StoppedThenStarting() {
        val stopped = QuickServiceText.ace(AceStatus(AcePhase.STOPPED, 6878))
        assertEquals("Motor detenido", stopped.status)
        assertEquals("INICIAR", stopped.actionLabel)
        assertEquals(RowAction.ACE_START, stopped.onOk)
        assertEquals("Iniciando…", QuickServiceText.ace(AceStatus(AcePhase.STARTING)).status)
        assertEquals(Indicator.BUSY, QuickServiceText.ace(AceStatus(AcePhase.STARTING)).indicator)
    }

    // Caso 2: ya READY → "Motor activo", y SIN interruptor OFF falso (no hay apagado oficial).
    @Test fun aceCase2ReadyHasNoFakeOff() {
        val ready = QuickServiceText.ace(AceStatus(AcePhase.READY, 6878, "3.1.70"))
        assertEquals("Motor activo", ready.status)
        assertNull(ready.toggle)
        assertNull(ready.actionLabel)
        assertEquals(RowAction.REFRESH, ready.onOk)
    }

    // Caso 3: no instalado.
    @Test fun aceCase3NotInstalled() {
        assertEquals("No instalado", QuickServiceText.ace(AceStatus(AcePhase.NOT_INSTALLED)).status)
    }

    // Caso 4: el servicio falla al arrancar → error y REINTENTAR.
    @Test fun aceCase4StartFailed() {
        val r = QuickServiceText.ace(AceStatus(AcePhase.ERROR, error = AceError.START_FAILED))
        assertEquals("No se pudo iniciar", r.status)
        assertEquals("REINTENTAR", r.actionLabel)
        assertEquals(RowAction.ACE_START, r.onOk)
    }

    // Caso 5: el servicio dice que arrancó pero la HTTP API no responde → ERROR, nunca READY falso.
    @Test fun aceCase5HttpNotResponding() = runBlocking {
        var calls = 0
        val result = AceProtocol.awaitReady(port = 6890, timeoutMs = 120, probe = { calls++; null }, intervalMs = 20)
        assertEquals(AcePhase.ERROR, result.phase)
        assertEquals(AceError.NOT_RESPONDING, result.error)
        assertTrue(calls >= 2)
        assertEquals("Ace Stream Engine no responde", QuickServiceText.ace(result).status)
    }

    @Test fun aceReadyOnlyWithEvidenceOnTheRealPort() = runBlocking {
        val ports = mutableListOf<Int>()
        var calls = 0
        val result = AceProtocol.awaitReady(port = 6890, timeoutMs = 2_000, probe = { p ->
            ports += p; if (++calls >= 3) "3.1.70" else null
        }, intervalMs = 10)
        assertEquals(AceStatus(AcePhase.READY, 6890, "3.1.70"), result)
        assertTrue(ports.all { it == 6890 })                            // el puerto que dio el servicio, no 6878 fijo
        assertEquals("http://127.0.0.1:6890/webui/api/service?method=get_version", AceProtocol.versionUrl(6890))
    }

    @Test fun aceVersionParsing() {
        assertEquals("3.1.70", AceProtocol.parseVersion("""{"result":{"code":3017000,"platform":"android","version":"3.1.70"},"error":null}"""))
        assertNull(AceProtocol.parseVersion("""{"result":null,"error":"unknown method"}"""))
        assertNull(AceProtocol.parseVersion("<html>otro servicio</html>"))
        assertNull(AceProtocol.parseVersion(""))
    }
}
