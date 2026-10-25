package com.mivisor.app.remote

import com.google.gson.JsonParser
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Servidor HTTP mínimo y temporal en la red local (sin librerías ni Internet).
 *
 * Solo hace dos cosas:
 *   GET  /pair/<token>       página para pegar el código        (sesión INPUT)
 *   POST /pair/<token>/send  recibe {"text": "…"} (≤ 16 KB)     (sesión INPUT, un solo uso)
 *   GET  /log/<token>        muestra un texto de solo lectura   (sesión SHOW: registro de errores)
 *   GET  /edit/<token>       página "Editar con móvil" con título y código actuales del formulario (sesión EDIT)
 *   POST /edit/<token>/send  recibe {"title": "…", "code": "…"}; VARIOS envíos hasta que caduca (5 min)
 * Todo lo demás: 404/405. No sirve ficheros, no ejecuta nada, no tiene endpoints de administración
 * y nunca registra el texto recibido ni el token.
 *
 * Escucha solo en la IP LAN elegida (nunca en la interfaz de la VPN) y solo mientras hay sesión.
 */
class MobileInputServer(
    private val bindAddress: InetAddress,
    private val clock: () -> Long = System::currentTimeMillis,
    /** EDIT: título y código enviados desde el móvil (nunca se registran ni se guardan aquí). */
    private val onFields: (title: String, code: String) -> Unit = { _, _ -> },
    private val onText: (String) -> Unit
) {
    @Volatile var session: RemoteSession? = null
    private var socket: ServerSocket? = null
    private var pool: ExecutorService? = null

    val port: Int get() = socket?.localPort ?: -1
    val isRunning: Boolean get() = socket?.isClosed == false

    /** Intenta 8765…8774 y, si están ocupados, un puerto libre cualquiera. Devuelve el puerto real. */
    @Synchronized
    fun start(preferred: IntRange = DEFAULT_PORTS): Int {
        if (isRunning) return port
        val server = (preferred.asSequence() + sequenceOf(0)).firstNotNullOfOrNull { p ->
            try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(bindAddress, p), 8)
                }
            } catch (e: IOException) {
                null
            }
        } ?: throw IOException("no port available")
        socket = server
        val executor = Executors.newFixedThreadPool(2) { r -> Thread(r, "mivisor-remote").apply { isDaemon = true } }
        pool = executor
        Thread({ acceptLoop(server, executor) }, "mivisor-remote-accept").apply { isDaemon = true }.start()
        return server.localPort
    }

    @Synchronized
    fun stop() {
        session?.cancel()
        session = null
        runCatching { socket?.close() }
        socket = null
        pool?.shutdownNow()
        pool = null
    }

    private fun acceptLoop(server: ServerSocket, executor: ExecutorService) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: SocketException) {
                break                                   // cerrado: fin
            } catch (e: IOException) {
                continue
            }
            try {
                executor.execute { handle(client) }
            } catch (e: Exception) {
                runCatching { client.close() }
            }
        }
    }

    // ------------------------------------------------------------------ HTTP

    class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)
    class Response(val status: Int, val contentType: String, val body: ByteArray)

    private fun handle(client: Socket) {
        client.use { s ->
            try {
                s.soTimeout = SOCKET_TIMEOUT_MS
                val request = readRequest(BufferedInputStream(s.getInputStream()))
                val response = if (request == null) text(400, "Petición no válida") else route(request)
                write(s, response)
            } catch (e: TooLarge) {
                runCatching { write(s, text(413, "El texto es demasiado largo")) }
            } catch (e: Exception) {
                runCatching { write(s, text(400, "Petición no válida")) }
            }
        }
    }

    /** Lógica de rutas, separada del socket para poder probarla. */
    fun route(r: Request): Response {
        val parts = r.path.substringBefore('?').trim('/').split('/')
        val current = session
        return when {
            parts.size == 2 && parts[0] == "pair" -> {
                if (r.method != "GET") return text(405, "Método no permitido")
                when (current?.takeIf { it.mode == RemoteSession.Mode.INPUT }?.check(parts[1], clock())) {
                    RemoteSession.Check.OK -> html(200, MobilePages.input(current.expiresAt - clock()))
                    else -> html(410, MobilePages.unavailable())
                }
            }
            parts.size == 3 && parts[0] == "pair" && parts[2] == "send" -> {
                if (r.method != "POST") return text(405, "Método no permitido")
                val s = current?.takeIf { it.mode == RemoteSession.Mode.INPUT }
                    ?: return json(410, """{"ok":false,"error":"expired"}""")
                if (s.check(parts[1], clock()) != RemoteSession.Check.OK) return json(410, """{"ok":false,"error":"expired"}""")
                val text = parseText(r.body) ?: return json(400, """{"ok":false,"error":"invalid"}""")
                if (text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) return json(413, """{"ok":false,"error":"too_large"}""")
                if (!s.consume(parts[1], clock())) return json(410, """{"ok":false,"error":"expired"}""")
                onText(text)                            // nunca se registra ni se ejecuta: solo se entrega al formulario
                json(200, """{"ok":true}""")
            }
            parts.size == 2 && parts[0] == "log" -> {
                if (r.method != "GET") return text(405, "Método no permitido")
                when (current?.takeIf { it.mode == RemoteSession.Mode.SHOW }?.check(parts[1], clock())) {
                    RemoteSession.Check.OK -> html(200, MobilePages.show(current.shownText.orEmpty()))
                    else -> html(410, MobilePages.unavailable())
                }
            }
            parts.size == 2 && parts[0] == "edit" -> {
                if (r.method != "GET") return text(405, "Método no permitido")
                when (current?.takeIf { it.mode == RemoteSession.Mode.EDIT }?.check(parts[1], clock())) {
                    RemoteSession.Check.OK -> html(200, MobilePages.edit(current.heading, current.title, current.code, current.expiresAt - clock()))
                    else -> html(410, MobilePages.unavailable())
                }
            }
            parts.size == 3 && parts[0] == "edit" && parts[2] == "send" -> {
                if (r.method != "POST") return text(405, "Método no permitido")
                val s = current?.takeIf { it.mode == RemoteSession.Mode.EDIT }
                    ?: return json(410, """{"ok":false,"error":"expired"}""")
                if (s.check(parts[1], clock()) != RemoteSession.Check.OK) return json(410, """{"ok":false,"error":"expired"}""")
                val fields = parseFields(r.body) ?: return json(400, """{"ok":false,"error":"invalid"}""")
                val (title, code) = fields
                if (title.toByteArray(Charsets.UTF_8).size > MAX_TITLE_BYTES || code.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) {
                    return json(413, """{"ok":false,"error":"too_large"}""")
                }
                if (!s.acceptEdit(parts[1], clock())) return json(410, """{"ok":false,"error":"expired"}""")
                s.title = title
                s.code = code
                onFields(title, code)                   // solo rellena el formulario: guardar sigue siendo cosa de la TV
                json(200, """{"ok":true,"n":${s.editCount}}""")
            }
            else -> text(404, "No encontrado")
        }
    }

    private fun parseText(body: ByteArray): String? = runCatching {
        val o = JsonParser.parseString(String(body, Charsets.UTF_8)).asJsonObject
        o.get("text")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    }.getOrNull()

    /** {"title": "...", "code": "..."}: ambos texto; el título puede ir vacío. */
    private fun parseFields(body: ByteArray): Pair<String, String>? = runCatching {
        val o = JsonParser.parseString(String(body, Charsets.UTF_8)).asJsonObject
        fun field(name: String): String? = o.get(name)?.let { if (it.isJsonPrimitive && it.asJsonPrimitive.isString) it.asString else null }
        val code = field("code") ?: return@runCatching null
        val title = if (o.has("title")) field("title") ?: return@runCatching null else ""
        title to code
    }.getOrNull()

    private class TooLarge : IOException()

    private fun readRequest(input: InputStream): Request? {
        // Cabeceras: como mucho 8 KB.
        val head = ByteArrayOutputStream()
        var matched = 0
        val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (matched < 4) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            matched = if (b.toByte() == end[matched]) matched + 1 else if (b.toByte() == end[0]) 1 else 0
            if (head.size() > MAX_HEADER_BYTES) throw TooLarge()
        }
        val lines = String(head.toByteArray(), Charsets.ISO_8859_1).split("\r\n")
        val requestLine = lines.first().split(' ')
        if (requestLine.size < 3) return null
        val headers = lines.drop(1).filter { ':' in it }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        if (headers["transfer-encoding"] != null) return null       // solo Content-Length
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0) return null
        if (length > MAX_BODY_BYTES) throw TooLarge()
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) return null
            read += n
        }
        return Request(requestLine[0].uppercase(), requestLine[1], headers, body)
    }

    private fun write(s: Socket, r: Response) {
        val reason = REASONS[r.status] ?: "OK"
        val head = "HTTP/1.1 ${r.status} $reason\r\n" +
            "Content-Type: ${r.contentType}\r\n" +
            "Content-Length: ${r.body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "Connection: close\r\n\r\n"
        val out = s.getOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(r.body)
        out.flush()
    }

    private fun html(status: Int, body: String) = Response(status, "text/html; charset=utf-8", body.toByteArray(Charsets.UTF_8))
    private fun json(status: Int, body: String) = Response(status, "application/json; charset=utf-8", body.toByteArray(Charsets.UTF_8))
    private fun text(status: Int, body: String) = Response(status, "text/plain; charset=utf-8", body.toByteArray(Charsets.UTF_8))

    companion object {
        val DEFAULT_PORTS = 8765..8774
        /** Texto máximo aceptado (los códigos reales son mucho más cortos). */
        const val MAX_TEXT_BYTES = 16 * 1024
        /** El JSON lleva comillas y escapes: algo de margen sobre el texto. */
        const val MAX_BODY_BYTES = 24 * 1024
        const val MAX_HEADER_BYTES = 8 * 1024
        /** Título del formulario (nombre del proveedor). */
        const val MAX_TITLE_BYTES = 1024
        const val SOCKET_TIMEOUT_MS = 5_000
        private val REASONS = mapOf(200 to "OK", 400 to "Bad Request", 404 to "Not Found", 405 to "Method Not Allowed",
            410 to "Gone", 413 to "Payload Too Large")
    }
}
