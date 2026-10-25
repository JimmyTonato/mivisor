package com.mivisor.app.diagnostics

import com.mivisor.app.data.AceStreamLink
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro Ace Stream: qué pasa alrededor de cada apertura de un canal Ace, para comparar códigos/proveedores
 * que funcionan con los que se quedan cargando.
 *
 * MiVisor NO puede leer el log interno de Ace Stream (Android solo lo permite con ADB/root), así que registra
 * todo lo que sí ve: qué app abre el enlace y por qué, si el Content ID llega intacto, VPN, estado del motor
 * antes y al volver, cuánto tardas en volver, tu respuesta ("¿se ha visto?") y la comprobación real del motor
 * (getstream → stat: peers, velocidad, datos). Content IDs siempre enmascarados (4…4), nunca URLs completas.
 *
 * Fichero: crashes/diag-ace.txt (aparece en Ajustes → Registro de errores como "Registro Ace Stream").
 */
object AceLog {
    const val FILE = "diag-ace.txt"
    const val MAX_ENTRIES = 40
    private const val SEP = "──"
    private const val HEADER = "Registro Ace Stream (últimas $MAX_ENTRIES aperturas; la más reciente al final)\n\n"

    /** Tras volver a MiVisor dentro de este margen se pregunta "¿se ha visto el canal?". */
    const val ASK_WINDOW_MS = 10 * 60 * 1000L

    data class Launch(
        val channel: String,
        val provider: String?,
        val group: String?,
        val storedUrl: String,
        val sentUri: String,
        /** Paquete que abre el enlace (null = lo decide el sistema / selector). */
        val targetPackage: String?,
        /** Por qué ese paquete: "recordada para el grupo «DAZN»", "única app compatible", "elegida ahora"… */
        val reason: String,
        val handlers: List<String>,
        val systemDefault: String?,
        val providerVpn: Boolean,
        val systemVpn: Boolean?,
        /** Estado de WARP según MiVisor ("conectada", "desconectada"…) y si se conecta sola al abrir MiVisor. */
        val warp: String? = null,
        val vpnAutoConnect: Boolean = false,
        val httpEngineMode: Boolean,
        val engineBefore: String,
        /** ¿Arrancó MiVisor el motor (y cuándo)? La app antigua nunca lo hace. */
        val engineOrigin: String? = null,
        val aceAutoStart: Boolean = false
    )

    /** Texto de una apertura (puro: se prueba en la JVM). */
    fun entry(l: Launch, time: Date = Date()): String {
        val idStored = AceStreamLink.contentId(l.storedUrl)
        val idSent = AceStreamLink.contentId(l.sentUri) ?: idStored?.takeIf { l.sentUri.contains(it) }
        val stamp = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(time)
        return buildString {
            appendLine("$SEP $stamp · ${l.channel}${l.provider?.let { " · $it" }.orEmpty()}")
            appendLine("ID: ${AceStreamLink.masked(idStored)} · ${when {
                idStored == null -> "NO es un Content ID válido"
                idSent == idStored -> "llega intacto a Ace"
                else -> "¡DISTINTO al enviado!"
            }}")
            appendLine("Grupo del canal: ${l.group ?: "—"}")
            appendLine("Envío: ${if (l.httpEngineMode) "URL HTTP del motor local (Ajustes)" else "Intent VIEW acestream:// + NEW_TASK (igual que MyLinksPaste)"}")
            appendLine("App que lo abre: ${l.targetPackage ?: "la decide el sistema"} — ${l.reason}")
            appendLine("Apps para acestream:// en esta TV: ${l.handlers.ifEmpty { listOf("ninguna") }.joinToString()}")
            appendLine("Predeterminada del sistema: ${l.systemDefault ?: "ninguna (pregunta)"}")
            appendLine("VPN: proveedor ${if (l.providerVpn) "con VPN" else "sin VPN"} · VPN activa en la TV: ${yesNo(l.systemVpn)}" +
                (l.warp?.let { " · WARP: $it" } ?: "") + " · Conectar VPN al abrir MiVisor: ${if (l.vpnAutoConnect) "sí" else "no"}")
            appendLine("Motor antes de abrir: ${l.engineBefore}${if (l.engineBefore.startsWith("responde")) " → ya estaba en marcha: Ace NO mostrará su pantalla de arranque" else " → Ace lo arrancará (como con la app antigua)"}")
            l.engineOrigin?.let { appendLine("Origen del motor: $it · Iniciar Ace al abrir MiVisor: ${if (l.aceAutoStart) "SÍ" else "no"}") }
        }
    }

    fun returned(seconds: Long, engine: String, vpnNow: Boolean? = null) =
        "  ↩ Volviste a MiVisor tras ${duration(seconds)} · motor: $engine" + (vpnNow?.let { " · VPN activa ahora: ${yesNo(it)}" } ?: "")

    private fun yesNo(b: Boolean?) = when (b) { true -> "SÍ"; false -> "no"; null -> "desconocido" }

    fun answer(watched: Boolean) =
        "  ✎ Tu respuesta: ${if (watched) "SÍ se veía" else "NO se veía (se quedó cargando)"}"

    fun probe(channel: String, verdict: String, peers: Int?, speedKbps: Int?, status: String?, seconds: Int, detail: String) =
        "  ⚙ Comprobación del motor (${channel}): $verdict · estado ${status ?: "—"} · peers ${peers ?: 0} · ${speedKbps ?: 0} KB/s · ${seconds}s — $detail"

    fun duration(seconds: Long): String = if (seconds < 60) "$seconds s" else "${seconds / 60} min ${seconds % 60} s"

    /** ¿Preguntar "¿se ha visto?" al volver? Solo si vuelves pronto (si estuviste viendo el partido, no molesta). */
    fun shouldAsk(launchedAt: Long, now: Long): Boolean = now - launchedAt in 0..ASK_WINDOW_MS

    /** Conserva las [max] últimas aperturas (cada una con sus líneas de seguimiento). */
    fun trim(text: String, max: Int = MAX_ENTRIES): String {
        val blocks = text.split(Regex("""(?m)^(?=$SEP )""")).filter { it.isNotBlank() }
        return blocks.takeLast(max).joinToString("")
    }

    // ------------------------------------------------------------------ fichero

    private fun file(dir: File) = File(dir.apply { mkdirs() }, FILE)

    @Synchronized
    fun append(dir: File, text: String) {
        runCatching {
            val f = file(dir)
            val clean = LogSanitizer.clean(text.trimEnd() + "\n")
            val old = if (f.exists()) f.readText().removePrefix(HEADER) else ""
            f.writeText(HEADER + trim(old + clean))
        }
    }
}
