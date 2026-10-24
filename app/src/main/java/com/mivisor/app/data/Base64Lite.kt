package com.mivisor.app.data

/**
 * Decodificador Base64 tolerante y sin dependencias de Android (testeable en JVM y
 * compatible con API 21, donde java.util.Base64 no existe).
 * Acepta alfabeto estándar y URL-safe, ignora espacios/saltos y el relleno es opcional.
 */
object Base64Lite {
    private val TABLE = IntArray(128) { -1 }.apply {
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".forEachIndexed { i, c -> this[c.code] = i }
        this['+'.code] = 62; this['-'.code] = 62
        this['/'.code] = 63; this['_'.code] = 63
    }

    fun decode(input: String): ByteArray? {
        val out = java.io.ByteArrayOutputStream(input.length * 3 / 4)
        var buffer = 0
        var bits = 0
        var symbols = 0
        for (c in input) {
            if (c == '=') break
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') continue
            val v = if (c.code < 128) TABLE[c.code] else -1
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            symbols++
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xff)
            }
        }
        if (symbols == 0 || symbols % 4 == 1) return null
        return out.toByteArray()
    }
}
