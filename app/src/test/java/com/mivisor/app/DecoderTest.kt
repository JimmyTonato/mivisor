package com.mivisor.app

import com.mivisor.app.data.Base64Lite
import com.mivisor.app.data.Codes
import com.mivisor.app.data.InvalidPayloadException
import com.mivisor.app.data.PayloadDecoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DecoderTest {
    private val decoder = PayloadDecoder()

    @Test fun base64LiteMatchesJdk() {
        val samples = listOf("", "a", "ab", "abc", "hola mundo", "ñandú €", String(CharArray(300) { (it % 90 + 32).toChar() }))
        for (s in samples.drop(1)) {
            val encoded = TestData.b64(s)
            assertArrayEquals(s.toByteArray(), Base64Lite.decode(encoded))
            assertArrayEquals(s.toByteArray(), Base64Lite.decode(encoded.trimEnd('=')))
            assertArrayEquals(s.toByteArray(), Base64Lite.decode(encoded.chunked(7).joinToString("\n")))
        }
        val urlSafe = java.util.Base64.getUrlEncoder().encodeToString(byteArrayOf(-1, -2, -3, 62, 63))
        assertArrayEquals(byteArrayOf(-1, -2, -3, 62, 63), Base64Lite.decode(urlSafe))
        assertNull(Base64Lite.decode("[{\"a\":1}]"))
    }

    @Test fun rawJson() {
        val items = decoder.decode(listOf(TestData.SIMPLE_JSON))
        assertEquals(2, items.size)
        assertEquals("La 1", items[0].name)
        assertEquals(3, items[1].subLinks!!.size)
    }

    @Test fun rawJsonInsideQuotesIsNotBroken() {
        // Antes se borraban todas las comillas y el JSON en claro dejaba de ser válido.
        val quoted = "\"" + TestData.SIMPLE_JSON.replace("\"", "\\\"") + "\""
        assertEquals(2, decoder.decode(listOf(quoted)).size)
    }

    @Test fun base64AndGzip() {
        assertEquals(2, decoder.decode(listOf(TestData.b64(TestData.SIMPLE_JSON))).size)
        assertEquals(2, decoder.decode(listOf(TestData.gzipB64(TestData.SIMPLE_JSON))).size)
        assertEquals(2, decoder.decode(listOf("text = \"${TestData.gzipB64(TestData.SIMPLE_JSON)}\"")).size)
    }

    @Test fun mosaicOfFragments() {
        val payload = TestData.gzipB64(TestData.SIMPLE_JSON)
        val parts = payload.chunked(payload.length / 3 + 1)
        assertEquals(3, parts.size)
        assertEquals(2, decoder.decode(parts).size)
    }

    @Test fun picksMostCompleteVersion() {
        val small = """[{"name":"Solo","url":"https://x/y.m3u8"}]"""
        val records = listOf(TestData.b64(small), TestData.gzipB64(TestData.SIMPLE_JSON))
        assertEquals(2, decoder.decode(records).size)
        assertEquals(2, decoder.decode(records.reversed()).size)
    }

    @Test fun wrapperObjectAndNulls() {
        val json = """{"streams":[null,{"name":null,"url":"https://a/b.m3u8","subLinks":[null]}]}"""
        val items = decoder.decode(listOf(json))
        assertEquals(1, items.size)
        assertNull(items[0].name)
        assertNull(items[0].subLinks)
    }

    @Test fun garbageThrows() {
        try {
            decoder.decode(listOf("v=spf1 -all"))
            fail("debería fallar")
        } catch (e: InvalidPayloadException) {
            // ok
        }
    }

    @Test fun codes() {
        assertEquals("deportes", Codes.normalize("  mylinkspaste://Deportes/ "))
        assertEquals("abc", Codes.normalize("mivisor://abc"))
        assertNull(Codes.normalizeOrNull("hola mundo"))
        assertNull(Codes.normalizeOrNull(""))
        assertNull(Codes.normalizeOrNull("a..b"))
        assertEquals("futbol.hoy.elcano.top", Codes.hostFor("futbol.hoy", "elcano.top"))
        assertEquals("x.elcano.top", Codes.hostFor("x.elcano.top", "elcano.top"))
        assertTrue(Codes.hostFor("abc", "elcano.top").endsWith(".elcano.top"))
    }
}
