package com.mivisor.app.data

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Caché en disco de los registros TXT tal cual llegan (Base64/GZIP: el formato más compacto).
 * Formato binario propio: sin reflexión, sin JSON intermedio y robusto frente a cambios
 * del modelo entre versiones. Escritura atómica (tmp + rename) y tamaño acotado.
 */
class RawCache(private val dir: File, private val maxEntries: Int = 60) {

    class Entry(val savedAt: Long, val records: List<String>)

    @Synchronized
    fun read(code: String): Entry? {
        val file = fileFor(code)
        if (!file.isFile) return null
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) return@use null
                val savedAt = input.readLong()
                val count = input.readInt()
                if (count !in 1..MAX_RECORDS) return@use null
                val records = ArrayList<String>(count)
                repeat(count) {
                    val length = input.readInt()
                    if (length !in 0..MAX_RECORD_BYTES) return@use null
                    val bytes = ByteArray(length)
                    input.readFully(bytes)
                    records += String(bytes, Charsets.UTF_8)
                }
                Entry(savedAt, records)
            }
        }.getOrNull() ?: run { file.delete(); null }
    }

    @Synchronized
    fun write(code: String, records: List<String>, savedAt: Long = System.currentTimeMillis()) {
        runCatching {
            if (!dir.isDirectory) dir.mkdirs()
            val target = fileFor(code)
            val temp = File(dir, target.name + ".tmp")
            DataOutputStream(temp.outputStream().buffered()).use { out ->
                out.writeInt(MAGIC)
                out.writeLong(savedAt)
                out.writeInt(records.size)
                for (record in records) {
                    val bytes = record.toByteArray(Charsets.UTF_8)
                    out.writeInt(bytes.size)
                    out.write(bytes)
                }
            }
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
            prune()
        }
    }

    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun prune() {
        val files = dir.listFiles { f -> f.name.endsWith(EXT) } ?: return
        if (files.size <= maxEntries) return
        files.sortedBy { it.lastModified() }
            .take(files.size - maxEntries)
            .forEach { it.delete() }
    }

    private fun fileFor(code: String): File =
        File(dir, code.lowercase().replace(Regex("[^a-z0-9._-]"), "_") + EXT)

    private companion object {
        const val MAGIC = 0x4D560003
        const val EXT = ".bin"
        const val MAX_RECORDS = 4096
        const val MAX_RECORD_BYTES = 4 * 1024 * 1024
    }
}
