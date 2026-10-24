package com.mivisor.app.network

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * DNS-over-HTTPS con la API JSON (Google y Cloudflare usan el mismo formato).
 * Es el último recurso cuando el puerto 53 está bloqueado por la red/operador.
 */
class DohResolver(
    private val endpoint: String,
    private val timeoutMs: Int = 6_000
) : DnsTxtResolver {

    override suspend fun resolveTxt(host: String): List<String> = withContext(Dispatchers.IO) {
        val url = URL("$endpoint?name=${URLEncoder.encode(host, "UTF-8")}&type=TXT")
        val connection = url.openConnection() as HttpURLConnection
        closeOnCancel(connection::disconnect) {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/dns-json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
            val body = connection.inputStream.use { readLimited(it, MAX_BODY) }
            parseJson(body, host)
        }
    }

    companion object {
        const val GOOGLE = "https://dns.google/resolve"
        const val CLOUDFLARE = "https://cloudflare-dns.com/dns-query"
        private const val USER_AGENT = "MiVisor/3"
        private const val MAX_BODY = 1_048_576

        fun parseJson(body: String, host: String): List<String> {
            val json = JsonParser.parseString(body).asJsonObject
            val status = json.get("Status")?.asInt ?: -1
            if (status == 3) throw CodeNotFoundException(host)
            if (status != 0) throw IOException("DoH status $status")
            val answers = json.getAsJsonArray("Answer") ?: throw CodeNotFoundException(host)
            val records = answers.mapNotNull { element ->
                val obj = element.asJsonObject
                if (obj.get("type")?.asInt != DnsMessage.TYPE_TXT) return@mapNotNull null
                obj.get("data")?.asString?.let(::parseTxtData)?.takeIf { it.isNotEmpty() }
            }
            if (records.isEmpty()) throw CodeNotFoundException(host)
            return records
        }

        /**
         * El campo `data` puede venir como texto plano o como cadenas entre comillas
         * (`"trozo1" "trozo2"`, con escapes \" \\ \DDD). Se concatenan los trozos.
         */
        fun parseTxtData(data: String): String {
            val trimmed = data.trim()
            if (!trimmed.startsWith("\"")) return trimmed
            val out = StringBuilder(trimmed.length)
            var i = 0
            var inQuotes = false
            while (i < trimmed.length) {
                val c = trimmed[i]
                when {
                    c == '"' -> inQuotes = !inQuotes
                    !inQuotes -> Unit // espacios entre trozos
                    c == '\\' && i + 1 < trimmed.length -> {
                        val next = trimmed[i + 1]
                        if (next.isDigit() && i + 3 < trimmed.length &&
                            trimmed[i + 2].isDigit() && trimmed[i + 3].isDigit()
                        ) {
                            out.append(trimmed.substring(i + 1, i + 4).toInt().toChar())
                            i += 3
                        } else {
                            out.append(next)
                            i += 1
                        }
                    }
                    else -> out.append(c)
                }
                i++
            }
            return out.toString()
        }

        private fun readLimited(input: java.io.InputStream, limit: Int): String {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > limit) throw IOException("Respuesta demasiado grande")
            }
            return out.toString("UTF-8")
        }
    }
}
