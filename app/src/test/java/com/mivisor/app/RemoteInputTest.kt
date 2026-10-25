package com.mivisor.app

import com.mivisor.app.diagnostics.LogSanitizer
import com.mivisor.app.remote.LanAddressPicker
import com.mivisor.app.remote.MobileInputManager
import com.mivisor.app.remote.MobileInputServer
import com.mivisor.app.remote.MobilePages
import com.mivisor.app.remote.NetCandidate
import com.mivisor.app.remote.NetCandidate.Transport
import com.mivisor.app.remote.QrGenerator
import com.mivisor.app.remote.RemoteFill
import com.mivisor.app.remote.RemoteSession
import com.mivisor.app.remote.RemoteState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/** "Escribir con el móvil" (QR) y registro de errores: los casos obligatorios del encargo. */
class RemoteInputTest {

    private var now = 1_000_000L
    private val clock = { now }

    private fun server(received: MutableList<String> = mutableListOf()) =
        MobileInputServer(InetAddress.getByName("127.0.0.1"), clock) { received += it }

    private fun post(srv: MobileInputServer, token: String, body: String) =
        srv.route(MobileInputServer.Request("POST", "/pair/$token/send", emptyMap(), body.toByteArray(Charsets.UTF_8)))

    private fun json(text: String) = "{\"text\":" + com.google.gson.Gson().toJson(text) + "}"

    // 1. generación del token
    @Test fun tokenIsRandomUrlSafeAndLongEnough() {
        val t = RemoteSession.newToken()
        assertEquals(32, t.length)                                   // 24 bytes → 32 caracteres Base64 URL
        assertTrue(t.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        // Base64 URL manual (Android 5–7 no tienen java.util.Base64): coincide con el estándar.
        val bytes = ByteArray(24) { (it * 37 + 11).toByte() }
        assertEquals(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), RemoteSession.base64Url(bytes))
        assertEquals("_-8", RemoteSession.base64Url(byteArrayOf(-1, -17)))
    }

    // 2. token único
    @Test fun tokensAreUnique() {
        val set = (1..2000).map { RemoteSession.newToken() }.toSet()
        assertEquals(2000, set.size)
    }

    // 3. token inválido
    @Test fun invalidTokenRejected() {
        val srv = server()
        val s = RemoteSession.create(RemoteSession.Mode.INPUT, now)
        srv.session = s
        assertEquals(RemoteSession.Check.INVALID, s.check("otro", now))
        assertEquals(RemoteSession.Check.INVALID, s.check(null, now))
        assertEquals(410, post(srv, "otro", json("abc")).status)
        assertEquals(410, srv.route(MobileInputServer.Request("GET", "/pair/otro", emptyMap(), ByteArray(0))).status)
        assertEquals(404, srv.route(MobileInputServer.Request("GET", "/", emptyMap(), ByteArray(0))).status)
        assertEquals(404, srv.route(MobileInputServer.Request("GET", "/etc/passwd", emptyMap(), ByteArray(0))).status)
        assertEquals(405, srv.route(MobileInputServer.Request("GET", "/pair/${s.token}/send", emptyMap(), ByteArray(0))).status)
        assertEquals(405, srv.route(MobileInputServer.Request("DELETE", "/pair/${s.token}", emptyMap(), ByteArray(0))).status)
    }

    // 4. token caducado
    @Test fun expiredTokenRejected() {
        val received = mutableListOf<String>()
        val srv = server(received)
        val s = RemoteSession.create(RemoteSession.Mode.INPUT, now)
        srv.session = s
        now += RemoteSession.DEFAULT_TTL_MS
        assertEquals(RemoteSession.Check.EXPIRED, s.check(s.token, now))
        assertEquals(410, post(srv, s.token, json("abc")).status)
        assertEquals(410, srv.route(MobileInputServer.Request("GET", "/pair/${s.token}", emptyMap(), ByteArray(0))).status)
        assertTrue(received.isEmpty())
    }

    // 5. POST correcto
    @Test fun validPostDeliversText() {
        val received = mutableListOf<String>()
        val srv = server(received)
        val s = RemoteSession.create(RemoteSession.Mode.INPUT, now)
        srv.session = s
        val page = srv.route(MobileInputServer.Request("GET", "/pair/${s.token}", emptyMap(), ByteArray(0)))
        assertEquals(200, page.status)
        assertTrue(String(page.body, Charsets.UTF_8).contains("ENVIAR AL TELEVISOR"))
        val r = post(srv, s.token, json("deportes"))
        assertEquals(200, r.status)
        assertEquals(listOf("deportes"), received)
    }

    // 6. UTF-8
    @Test fun utf8TextPreserved() {
        val received = mutableListOf<String>()
        val srv = server(received)
        val s = RemoteSession.create(RemoteSession.Mode.INPUT, now)
        srv.session = s
        val text = "Fútbol ñandú — 日本語 🎉"
        assertEquals(200, post(srv, s.token, json(text)).status)
        assertEquals(listOf(text), received)
    }

    // 7. texto largo dentro del límite
    @Test fun longTextWithinLimitAccepted() {
        val received = mutableListOf<String>()
        val srv = server(received)
        val s = RemoteSession.create(RemoteSession.Mode.INPUT, now)
        srv.session = s
        val text = "a".repeat(MobileInputServer.MAX_TEXT_BYTES)
        assertEquals(200, post(srv, s.token, json(text)).status)
        assertEquals(MobileInputServer.MAX_TEXT_BYTES, received.single().length)
    }

    // 8. texto que excede el límite
    @Test fun oversizeTextRejected() {
        val received = mutableListOf<String>()
        val srv = server(received)
        val s = RemoteSession.create(RemoteSession.Mode.INPUT, now)
        srv.session = s
        assertEquals(413, post(srv, s.token, json("a".repeat(MobileInputServer.MAX_TEXT_BYTES + 1))).status)
        // 6000 "ñ" = 12 000 bytes → dentro; 9000 "ñ" = 18 000 bytes → fuera (se mide en bytes, no en caracteres).
        assertEquals(413, post(srv, s.token, json("ñ".repeat(9000))).status)
        assertTrue(received.isEmpty())
        assertEquals(400, post(srv, s.token, "no es json").status)
        assertEquals(400, post(srv, s.token, "{\"text\":5}").status)
        assertEquals(400, post(srv, s.token, "{\"otro\":\"x\"}").status)
        // y la sesión sigue disponible para un envío correcto
        assertEquals(200, post(srv, s.token, json("ñ".repeat(6000))).status)
    }

    // 9. segundo uso rechazado
    @Test fun secondUseRejected() {
        val received = mutableListOf<String>()
        val srv = server(received)
        val s = RemoteSession.create(RemoteSession.Mode.INPUT, now)
        srv.session = s
        assertEquals(200, post(srv, s.token, json("uno")).status)
        assertEquals(410, post(srv, s.token, json("dos")).status)
        assertEquals(RemoteSession.Check.USED, s.check(s.token, now))
        assertEquals(listOf("uno"), received)
        assertEquals(410, srv.route(MobileInputServer.Request("GET", "/pair/${s.token}", emptyMap(), ByteArray(0))).status)
        assertTrue(String(MobilePages.unavailable().toByteArray()).contains("Esta sesión ya no está disponible."))
    }

    // 10. cancelar invalida la sesión
    @Test fun cancelInvalidatesSessionAndStopsServer() {
        val m = manager()
        m.startInput()
        val waiting = m.state.value as RemoteState.Waiting
        assertTrue(m.isServerRunning)
        m.cancel()
        assertFalse(m.isServerRunning)
        assertEquals(RemoteState.Idle, m.state.value)
        assertTrue(runCatching { postHttp(waiting.url + "/send", json("x")) }.isFailure)   // servidor cerrado
        scope.cancel()
    }

    // 11. salir del formulario invalida la sesión (el formulario llama a cancel() en onDispose)
    @Test fun leavingFormInvalidatesSession() {
        val m = manager()
        m.startInput()
        val first = m.state.value as RemoteState.Waiting
        // salir y volver a entrar: el QR anterior ya no sirve, el nuevo sí
        m.cancel()
        m.startInput()
        val second = m.state.value as RemoteState.Waiting
        assertTrue(first.url != second.url)
        assertTrue(runCatching { postHttp(first.url + "/send", json("x")).first }.getOrDefault(410) == 410)
        m.cancel()
        assertFalse(m.isServerRunning)
        scope.cancel()
    }

    // 12. selección de IP LAN (Ethernet en la TV, móvil en Wi-Fi: vale cualquier IP de la LAN)
    @Test fun lanAddressSelection() {
        val eth = NetCandidate("eth0", "192.168.1.40", transport = Transport.ETHERNET)
        val wifi = NetCandidate("wlan0", "192.168.1.41", transport = Transport.WIFI)
        assertEquals(eth, LanAddressPicker.choose(listOf(wifi, eth)))
        assertEquals(wifi, LanAddressPicker.choose(listOf(wifi)))
        // sin información de transporte (Android antiguo) también por nombre
        assertEquals("192.168.0.10", LanAddressPicker.choose(listOf(NetCandidate("wlan0", "192.168.0.10")))?.address)
        assertNull(LanAddressPicker.choose(emptyList()))
        assertNull(LanAddressPicker.choose(listOf(NetCandidate("eth0", "169.254.3.4"))))   // sin DHCP
        assertNull(LanAddressPicker.choose(listOf(NetCandidate("eth0", "fe80::1", ipv4 = false))))
        assertNull(LanAddressPicker.choose(listOf(NetCandidate("eth0", "192.168.1.40", up = false))))
        assertNull(LanAddressPicker.choose(listOf(NetCandidate("rmnet0", "10.20.30.40", transport = Transport.CELLULAR))))
    }

    // 13. no loopback
    @Test fun neverLoopback() {
        assertNull(LanAddressPicker.choose(listOf(NetCandidate("lo", "127.0.0.1", loopback = true))))
        assertNull(LanAddressPicker.choose(listOf(NetCandidate("eth0", "127.0.0.1"))))
        assertEquals("10.0.0.5", LanAddressPicker.choose(listOf(NetCandidate("lo", "127.0.0.1", loopback = true), NetCandidate("eth0", "10.0.0.5")))?.address)
    }

    // 14. no IP VPN/WARP
    @Test fun neverVpnAddress() {
        val warpTun = NetCandidate("tun0", "172.16.0.2", transport = Transport.VPN, pointToPoint = true)
        val warpByName = NetCandidate("tun0", "172.16.0.2")                 // aunque Android no la marque como VPN
        val wg = NetCandidate("wg0", "10.2.0.2")
        val lan = NetCandidate("eth0", "192.168.1.40", transport = Transport.ETHERNET)
        assertEquals(lan, LanAddressPicker.choose(listOf(warpTun, warpByName, wg, lan)))
        assertNull(LanAddressPicker.choose(listOf(warpTun, warpByName, wg)))
        assertNull(LanAddressPicker.choose(listOf(NetCandidate("eth0", "172.16.0.2", transport = Transport.VPN))))
    }

    // 15. campo rellenado  · 16. contenido previo sustituido
    @Test fun fieldFilledAndPreviousReplaced() {
        assertEquals("deportes", RemoteFill.apply("", "deportes"))
        assertEquals("deportes", RemoteFill.apply("viejo-codigo", "  deportes\n"))
        assertEquals("", RemoteFill.apply("viejo", "   "))                  // nunca concatena con lo anterior
    }

    // 17. no se guarda automáticamente: el gestor solo entrega el texto y vuelve a reposo cuando el formulario lo usa
    @Test fun noAutoSave() {
        val m = manager()
        m.startInput()
        val w = m.state.value as RemoteState.Waiting
        val (status, _) = postHttp(w.url + "/send", json("deportes"))
        assertEquals(200, status)
        val got = waitFor { m.state.value as? RemoteState.Received }
        assertEquals("deportes", got.text)
        // El gestor no conoce el formulario ni el repositorio: no hay ninguna ruta de guardado. Solo consume().
        m.consume()
        assertEquals(RemoteState.Idle, m.state.value)
        waitFor { if (!m.isServerRunning) true else null }                 // un solo uso: el servidor se apaga
        scope.cancel()
    }

    // 18. funciona sin Internet, solo LAN (servidor real en la interfaz local, sin backend externo)
    @Test fun worksOfflineOverLocalSocket() {
        val m = manager()
        m.startInput()
        val w = m.state.value as RemoteState.Waiting
        assertTrue(w.url.startsWith("http://127.0.0.1:"))
        val port = w.url.substringAfter("127.0.0.1:").substringBefore('/').toInt()
        assertTrue(port in 8765..8774 || port > 0)                            // el QR lleva el puerto real
        val (pageStatus, page) = getHttp(w.url)
        assertEquals(200, pageStatus)
        assertTrue(page.contains("<textarea"))
        assertEquals(200, postHttp(w.url + "/send", json("código ñ")).first)
        assertEquals("código ñ", waitFor { m.state.value as? RemoteState.Received }.text)
        // QR generado en local con la URL exacta
        val qr = QrGenerator.encode(w.url)
        assertTrue(qr.size >= 21 && qr.size == w.qr.size)
        m.cancel()
        scope.cancel()
    }

    // Extra: puerto de reserva si 8765 está ocupado, y la URL lo refleja.
    @Test fun portFallback() {
        val blocker = java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val p = blocker.localPort
        val srv = server()
        val got = srv.start(p..p)
        assertTrue(got != p && got > 0)
        srv.stop()
        blocker.close()
    }

    // Extra: sesión SHOW (registro de errores en el móvil): solo lectura y escapada.
    @Test fun showSessionIsReadOnlyAndEscaped() {
        val srv = server()
        val s = RemoteSession.create(RemoteSession.Mode.SHOW, now, shownText = "<script>x</script> Traza")
        srv.session = s
        val r = srv.route(MobileInputServer.Request("GET", "/log/${s.token}", emptyMap(), ByteArray(0)))
        assertEquals(200, r.status)
        val html = String(r.body, Charsets.UTF_8)
        assertFalse(html.contains("<script>x</script>"))
        assertTrue(html.contains("&lt;script&gt;"))
        assertEquals(410, post(srv, s.token, json("x")).status)            // no admite escritura
    }

    // Extra: el registro de errores no guarda URLs, códigos largos ni hashes, pero sí la traza útil.
    @Test fun logSanitizer() {
        val raw = "Falló http://example.com/x?code=secreto acestream://0123456789abcdef0123456789abcdef01234567 " +
            "token=Ab12Cd34Ef56Gh78Ij90Kl12Mn34 at androidx.compose.foundation.lazy.layout.LazyLayoutBeyondBoundsModifierLocal.layout"
        val out = LogSanitizer.clean(raw)
        assertFalse(out.contains("secreto"))
        assertFalse(out.contains("0123456789abcdef"))
        assertFalse(out.contains("Ab12Cd34Ef56Gh78Ij90Kl12Mn34"))
        assertTrue(out.contains("http://[…]"))
        assertTrue(out.contains("LazyLayoutBeyondBoundsModifierLocal"))
    }

    // Extra: los mensajes de log del gestor nunca contienen el texto ni el token.
    @Test fun managerLogsNeverContainSecrets() {
        val logs = mutableListOf<String>()
        val m = manager(logs)
        m.startInput()
        val w = m.state.value as RemoteState.Waiting
        postHttp(w.url + "/send", json("mi-codigo-secreto"))
        waitFor { m.state.value as? RemoteState.Received }
        val token = w.url.substringAfterLast('/')
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.none { it.contains("mi-codigo-secreto") || it.contains(token) || it.contains("http") })
        m.cancel()
        scope.cancel()
    }

    // Extra: sin red local → mensaje de error, sin servidor.
    @Test fun noLanFails() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val m = MobileInputManager(scope, lanAddress = { null })
        m.startInput()
        assertTrue((m.state.value as RemoteState.Failed).reason == RemoteState.Reason.NO_LAN)
        assertFalse(m.isServerRunning)
        scope.cancel()
    }


    // ---------------------------------------------------------------- Editar con móvil (sesión EDIT)

    private fun editServer(got: MutableList<Pair<String, String>>) =
        MobileInputServer(InetAddress.getByName("127.0.0.1"), clock, onFields = { t, c -> got += t to c }) { }

    private fun postEdit(srv: MobileInputServer, token: String, body: String) =
        srv.route(MobileInputServer.Request("POST", "/edit/$token/send", emptyMap(), body.toByteArray(Charsets.UTF_8)))

    private fun editJson(title: String, code: String) =
        "{\"title\":" + com.google.gson.Gson().toJson(title) + ",\"code\":" + com.google.gson.Gson().toJson(code) + "}"

    @Test fun editPageIsPrefilledWithCurrentFormValues() {
        val srv = editServer(mutableListOf())
        val s = RemoteSession.create(RemoteSession.Mode.EDIT, now).apply { heading = "Editar proveedor"; title = "DAZN F1"; code = "dazn-f1" }
        srv.session = s
        val page = String(srv.route(MobileInputServer.Request("GET", "/edit/${s.token}", emptyMap(), ByteArray(0))).body, Charsets.UTF_8)
        assertTrue(page.contains("value=\"DAZN F1\""))
        assertTrue(page.contains(">dazn-f1</textarea>"))
        assertTrue(page.contains("ENVIAR AL TELEVISOR") && page.contains("Título") && page.contains("Código"))
        // Los valores del formulario se escapan (nunca se interpretan como HTML).
        s.title = "\"><script>x</script>"
        val evil = String(srv.route(MobileInputServer.Request("GET", "/edit/${s.token}", emptyMap(), ByteArray(0))).body, Charsets.UTF_8)
        assertFalse(evil.contains("<script>x</script>"))
    }

    @Test fun editAcceptsSeveralSendsDuringSession() {
        val got = mutableListOf<Pair<String, String>>()
        val srv = editServer(got)
        val s = RemoteSession.create(RemoteSession.Mode.EDIT, now)
        srv.session = s
        assertEquals(200, postEdit(srv, s.token, editJson("DAZN F1", "dazn-f")).status)
        now += 60_000
        assertEquals(200, postEdit(srv, s.token, editJson("DAZN F1", "dazn-f1")).status)        // corrección
        now += 60_000
        val third = postEdit(srv, s.token, editJson("DAZN Fórmula 1", "dazn-f1"))
        assertEquals(200, third.status)
        assertTrue(String(third.body).contains("\"n\":3"))
        assertEquals(listOf("DAZN F1" to "dazn-f", "DAZN F1" to "dazn-f1", "DAZN Fórmula 1" to "dazn-f1"), got)
        // La página se reabre con lo último enviado.
        assertEquals("DAZN Fórmula 1", s.title)
    }

    @Test fun editExpiresAfterFiveMinutes() {
        val got = mutableListOf<Pair<String, String>>()
        val srv = editServer(got)
        val s = RemoteSession.create(RemoteSession.Mode.EDIT, now)
        srv.session = s
        assertEquals(200, postEdit(srv, s.token, editJson("A", "a")).status)
        now += RemoteSession.DEFAULT_TTL_MS
        assertEquals(410, postEdit(srv, s.token, editJson("B", "b")).status)
        val page = srv.route(MobileInputServer.Request("GET", "/edit/${s.token}", emptyMap(), ByteArray(0)))
        assertEquals(410, page.status)
        assertTrue(String(page.body, Charsets.UTF_8).contains("Genera un nuevo QR desde MiVisor"))
        assertEquals(1, got.size)
    }

    @Test fun editRejectsInvalidWrongModeAndOversize() {
        val got = mutableListOf<Pair<String, String>>()
        val srv = editServer(got)
        val s = RemoteSession.create(RemoteSession.Mode.EDIT, now)
        srv.session = s
        assertEquals(400, postEdit(srv, s.token, "{\"title\":\"x\"}").status)          // falta el código
        assertEquals(400, postEdit(srv, s.token, "{\"title\":1,\"code\":\"a\"}").status)
        assertEquals(413, postEdit(srv, s.token, editJson("t".repeat(MobileInputServer.MAX_TITLE_BYTES + 1), "a")).status)
        assertEquals(413, postEdit(srv, s.token, editJson("t", "c".repeat(MobileInputServer.MAX_TEXT_BYTES + 1))).status)
        assertEquals(410, postEdit(srv, "otro", editJson("t", "c")).status)
        assertEquals(405, srv.route(MobileInputServer.Request("GET", "/edit/${s.token}/send", emptyMap(), ByteArray(0))).status)
        // Una sesión de "pegar un texto" no acepta envíos de edición, ni al revés.
        val input = RemoteSession.create(RemoteSession.Mode.INPUT, now)
        srv.session = input
        assertEquals(410, postEdit(srv, input.token, editJson("t", "c")).status)
        srv.session = s
        assertEquals(410, post(srv, s.token, json("x")).status)
        assertTrue(got.isEmpty())
        // Sin código de título: se acepta (título vacío).
        assertEquals(200, postEdit(srv, s.token, "{\"code\":\"solo-codigo\"}").status)
        assertEquals(listOf("" to "solo-codigo"), got)
    }

    @Test fun editSessionHasSendLimit() {
        val s = RemoteSession.create(RemoteSession.Mode.EDIT, now)
        repeat(RemoteSession.MAX_EDITS) { assertTrue(s.acceptEdit(s.token, now)) }
        assertFalse(s.acceptEdit(s.token, now))
        assertFalse(RemoteSession.create(RemoteSession.Mode.INPUT, now).let { it.acceptEdit(it.token, now) })
    }

    // Extremo a extremo con sockets reales: varios envíos, el servidor sigue vivo, el formulario no se guarda solo.
    @Test fun editEndToEndOverLocalSocket() {
        val m = manager()
        m.startEdit("Editar proveedor", "DAZN F1", "dazn")
        val w = m.state.value as RemoteState.Waiting
        assertTrue(w.url.contains("/edit/"))
        val (status, page) = getHttp(w.url)
        assertEquals(200, status)
        assertTrue(page.contains("value=\"DAZN F1\"") && page.contains(">dazn</textarea>"))
        // La TV cambia el formulario: quien recargue la página lo verá.
        m.updateFields("DAZN F1 HD", "dazn")
        assertTrue(getHttp(w.url).second.contains("value=\"DAZN F1 HD\""))
        assertEquals(200, postHttp(w.url + "/send", editJson("DAZN F1", "dazn-f1")).first)
        assertEquals(1, waitFor { m.edits.value }.seq)
        assertEquals(200, postHttp(w.url + "/send", editJson("DAZN F1", "dazn-f1-hd")).first)
        val second = waitFor { m.edits.value?.takeIf { it.seq == 2 } }
        assertEquals("dazn-f1-hd", second.code)
        assertTrue(m.isServerRunning)                                           // no se invalida tras el primer envío
        assertTrue(m.state.value is RemoteState.Waiting)
        m.cancel()                                                              // cerrar el formulario
        assertFalse(m.isServerRunning)
        assertNull(m.edits.value)
        scope.cancel()
    }

    @Test fun editExpiryStopsServer() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val m = MobileInputManager(scope, lanAddress = { NetCandidate("lo", "127.0.0.1") }, ttlMs = 300)
        m.startEdit("Editar", "a", "b")
        val w = m.state.value as RemoteState.Waiting
        waitFor { if (m.state.value == RemoteState.Expired) true else null }
        assertFalse(m.isServerRunning)
        assertTrue(runCatching { postHttp(w.url + "/send", editJson("x", "y")) }.isFailure)
        scope.cancel()
    }

    // ------------------------------------------------------------------ utilidades

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** En las pruebas el "móvil" es este mismo proceso: se inyecta 127.0.0.1 (en la app, el selector lo prohíbe). */
    private fun manager(logs: MutableList<String> = mutableListOf()): MobileInputManager {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return MobileInputManager(scope, lanAddress = { NetCandidate("lo", "127.0.0.1") }, log = { synchronized(logs) { logs += it } })
    }

    private fun getHttp(url: String): Pair<Int, String> {
        val c = URL(url).openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
        c.connectTimeout = 2000; c.readTimeout = 2000
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
        c.disconnect()
        return code to body
    }

    private fun postHttp(url: String, body: String): Pair<Int, String> {
        val c = URL(url).openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
        c.connectTimeout = 2000; c.readTimeout = 2000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
        c.disconnect()
        return code to text
    }

    private fun <T> waitFor(block: () -> T?): T {
        repeat(100) {
            block()?.let { return it }
            Thread.sleep(30)
        }
        throw AssertionError("timeout")
    }
}
