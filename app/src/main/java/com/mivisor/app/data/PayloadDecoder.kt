package com.mivisor.app.data

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.mivisor.app.model.StreamItem
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.zip.GZIPInputStream

class InvalidPayloadException : Exception("Los datos del código no tienen un formato válido")

/**
 * Decodifica los registros TXT con los mismos formatos que MyLinksPaste:
 * JSON directo, Base64, Base64+GZIP y "mosaico" (el payload repartido en varios TXT).
 *
 * Si hay varios registros válidos por separado se queda con el más completo
 * (igual que la app original: protege de réplicas DNS desactualizadas).
 */
class PayloadDecoder(
    private val gson: Gson = Gson(),
    private val maxBytes: Int = 4 * 1024 * 1024
) {
    fun decode(records: List<String>): List<StreamItem> {
        val cleaned = records.map(::normalize).filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) throw InvalidPayloadException()

        var best: List<StreamItem>? = null
        var bestSize = -1
        for (record in cleaned) {
            val items = tryDecode(record) ?: continue
            val size = countNodes(items)
            if (size > bestSize) {
                best = items; bestSize = size
            }
        }
        best?.let { return it }

        if (cleaned.size > 1) tryDecode(cleaned.joinToString(""))?.let { return it }
        throw InvalidPayloadException()
    }

    /**
     * Las versiones anteriores borraban TODAS las comillas, lo que rompía los TXT con JSON
     * en claro. Ahora solo se quita el envoltorio de comillas cuando lo hay.
     */
    private fun normalize(raw: String): String {
        val text = raw.trim().removePrefix("text = ").trim()
        return if (text.length >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            com.mivisor.app.network.DohResolver.parseTxtData(text).trim()
        } else {
            text
        }
    }

    private fun tryDecode(text: String): List<StreamItem>? {
        parseJson(text)?.let { return it }
        if ('%' in text) {
            runCatching { URLDecoder.decode(text, "UTF-8") }.getOrNull()
                ?.let(::parseJson)?.let { return it }
        }
        val bytes = Base64Lite.decode(text) ?: return null
        val payload = if (isGzip(bytes)) ungzip(bytes) ?: return null else bytes
        return parseJson(String(payload, Charsets.UTF_8))
    }

    private fun parseJson(value: String): List<StreamItem>? {
        val text = value.trim().removePrefix("﻿")
        if (text.isEmpty() || (text[0] != '[' && text[0] != '{')) return null
        return runCatching {
            val root = JsonParser.parseString(text)
            val array: JsonElement = when {
                root.isJsonArray -> root
                root.isJsonObject -> {
                    val obj = root.asJsonObject
                    obj.get("streams") ?: obj.get("items") ?: obj.get("channels")
                    ?: if (obj.has("url") || obj.has("subLinks") || obj.has("ref")) {
                        com.google.gson.JsonArray().apply { add(obj) }
                    } else null
                }
                else -> null
            } ?: return@runCatching null
            if (!array.isJsonArray) return@runCatching null
            gson.fromJson(array, Array<StreamItem?>::class.java)
                ?.let { sanitize(it.asList(), 0) }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** Gson puede dejar nulos dentro de listas; se limpian y se limita la profundidad. */
    private fun sanitize(items: List<StreamItem?>, depth: Int): List<StreamItem> =
        items.mapNotNull { item ->
            if (item == null) return@mapNotNull null
            val children = item.subLinks
            when {
                children == null -> item
                depth >= MAX_DEPTH -> item.copy(subLinks = null)
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    val clean = sanitize(children as List<StreamItem?>, depth + 1)
                    item.copy(subLinks = clean.ifEmpty { null })
                }
            }
        }

    private fun countNodes(items: List<StreamItem>): Int =
        items.sumOf { 1 + countNodes(it.subLinks.orEmpty()) }

    private fun isGzip(bytes: ByteArray) =
        bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

    /** Con límite de tamaño: un payload malicioso no debe tumbar una TV con 1 GB de RAM. */
    private fun ungzip(bytes: ByteArray): ByteArray? = runCatching {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            val out = ByteArrayOutputStream(bytes.size * 4)
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > maxBytes) return null
            }
            out.toByteArray()
        }
    }.getOrNull()

    private companion object {
        const val MAX_DEPTH = 12
    }
}
