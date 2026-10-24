package com.mivisor.app.network

import java.io.ByteArrayOutputStream

/**
 * Construcción y lectura mínima de mensajes DNS (RFC 1035) para consultas TXT.
 * Sin dependencias: sustituye a dnsjava (≈700 KB menos en el APK).
 */
object DnsMessage {
    const val TYPE_TXT = 16
    private const val TYPE_OPT = 41
    private const val FLAG_QR = 0x8000
    private const val FLAG_TC = 0x0200
    private const val RCODE_NXDOMAIN = 3

    /** Tamaño UDP anunciado vía EDNS0. Sin esto los servidores truncan a 512 bytes. */
    const val EDNS_UDP_SIZE = 4096

    sealed class Result {
        class Records(val txt: List<String>) : Result()
        object NotFound : Result()
        object Truncated : Result()
        class Failure(val rcode: Int) : Result()
    }

    fun buildTxtQuery(id: Int, host: String): ByteArray {
        val out = ByteArrayOutputStream(64)
        fun u16(v: Int) {
            out.write((v ushr 8) and 0xff); out.write(v and 0xff)
        }
        u16(id)
        u16(0x0100)   // RD (recursion desired)
        u16(1)        // QDCOUNT
        u16(0)        // ANCOUNT
        u16(0)        // NSCOUNT
        u16(1)        // ARCOUNT (registro OPT de EDNS0)
        val labels = host.trim().trim('.').split('.')
        require(labels.isNotEmpty()) { "Dominio DNS no válido" }
        for (label in labels) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.size in 1..63) { "Dominio DNS no válido" }
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        u16(TYPE_TXT)
        u16(1)        // IN
        // OPT RR: nombre raíz, tipo 41, "clase" = tamaño UDP, TTL 0, RDLEN 0
        out.write(0)
        u16(TYPE_OPT)
        u16(EDNS_UDP_SIZE)
        u16(0); u16(0)
        u16(0)
        return out.toByteArray()
    }

    fun parseTxtResponse(data: ByteArray, length: Int, expectedId: Int): Result {
        if (length < 12) return Result.Failure(-1)
        val id = u16(data, 0)
        if (id != expectedId) return Result.Failure(-2)
        val flags = u16(data, 2)
        if (flags and FLAG_QR == 0) return Result.Failure(-3)
        if (flags and FLAG_TC != 0) return Result.Truncated
        val rcode = flags and 0x000f
        if (rcode == RCODE_NXDOMAIN) return Result.NotFound
        if (rcode != 0) return Result.Failure(rcode)

        val qd = u16(data, 4)
        val an = u16(data, 6)
        var pos = 12
        repeat(qd) {
            pos = skipName(data, pos, length)
            pos += 4
            if (pos > length) return Result.Failure(-4)
        }
        val records = ArrayList<String>(an)
        repeat(an) {
            pos = skipName(data, pos, length)
            if (pos + 10 > length) return@repeat
            val type = u16(data, pos)
            val rdLength = u16(data, pos + 8)
            pos += 10
            val end = minOf(pos + rdLength, length)
            if (type == TYPE_TXT) {
                val text = StringBuilder(rdLength)
                var p = pos
                while (p < end) {
                    val len = data[p].toInt() and 0xff
                    p++
                    if (p + len > end) break
                    text.append(String(data, p, len, Charsets.UTF_8))
                    p += len
                }
                if (text.isNotEmpty()) records += text.toString()
            }
            pos = end
        }
        return if (records.isEmpty()) Result.NotFound else Result.Records(records)
    }

    private fun skipName(data: ByteArray, start: Int, length: Int): Int {
        var pos = start
        var guard = 0
        while (pos < length && guard++ < 128) {
            val len = data[pos].toInt() and 0xff
            when {
                len == 0 -> return pos + 1
                len and 0xc0 == 0xc0 -> return pos + 2 // puntero de compresión
                else -> pos += 1 + len
            }
        }
        return length
    }

    private fun u16(data: ByteArray, pos: Int): Int =
        ((data[pos].toInt() and 0xff) shl 8) or (data[pos + 1].toInt() and 0xff)
}
