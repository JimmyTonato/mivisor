package com.mivisor.app

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

object TestData {
    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun b64(text: String): String = b64(text.toByteArray())

    fun gzipB64(text: String): String {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        return b64(out.toByteArray())
    }

    const val SIMPLE_JSON = """[
        {"name":"La 1","url":"https://example.com/la1.m3u8"},
        {"name":"Deportes","subLinks":[
            {"name":"Canal A","url":"acestream://abc123"},
            {"name":"Canal B","url":"magnet:?xt=urn:btih:xyz"},
            {"name":"Extra","subLinks":[{"name":"Canal C","url":"https://example.com/c.mp4"}]}
        ]}
    ]"""
}
