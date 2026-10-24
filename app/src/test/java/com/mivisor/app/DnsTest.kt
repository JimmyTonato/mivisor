package com.mivisor.app

import com.mivisor.app.network.CodeNotFoundException
import com.mivisor.app.network.CompositeDnsResolver
import com.mivisor.app.network.DnsMessage
import com.mivisor.app.network.DnsTxtResolver
import com.mivisor.app.network.DnsUnavailableException
import com.mivisor.app.network.DohResolver
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

class DnsTest {

    /** Respuesta DNS sintética: copia la pregunta y añade respuestas TXT con puntero de compresión. */
    private fun response(query: ByteArray, flags: Int, answers: List<List<String>>): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write((v ushr 8) and 0xff); out.write(v and 0xff) }
        fun u32(v: Int) { u16(v ushr 16); u16(v and 0xffff) }
        out.write(query, 0, 2)
        u16(flags); u16(1); u16(answers.size); u16(0); u16(0)
        // pregunta: desde el byte 12 hasta el final de QTYPE/QCLASS (antes del OPT)
        var p = 12
        while (query[p].toInt() != 0) p += (query[p].toInt() and 0xff) + 1
        out.write(query, 12, p + 5 - 12)
        for (chunks in answers) {
            u16(0xC00C); u16(16); u16(1); u32(300)
            val rdata = ByteArrayOutputStream()
            chunks.forEach { c -> val b = c.toByteArray(); rdata.write(b.size); rdata.write(b) }
            u16(rdata.size()); out.write(rdata.toByteArray())
        }
        return out.toByteArray()
    }

    @Test fun queryHasEdns0() {
        val q = DnsMessage.buildTxtQuery(0x1234, "abc.elcano.top")
        assertEquals(0x12, q[0].toInt()); assertEquals(0x34, q[1].toInt())
        assertEquals(1, q[11].toInt()) // ARCOUNT = 1
        val tail = q.takeLast(11)
        assertEquals(41, tail[2].toInt()) // tipo OPT
    }

    @Test fun parsesSplitTxtStrings() {
        val q = DnsMessage.buildTxtQuery(7, "abc.elcano.top")
        val r = response(q, 0x8180, listOf(listOf("hola ", "mundo"), listOf("segundo")))
        val parsed = DnsMessage.parseTxtResponse(r, r.size, 7)
        assertTrue(parsed is DnsMessage.Result.Records)
        assertEquals(listOf("hola mundo", "segundo"), (parsed as DnsMessage.Result.Records).txt)
    }

    @Test fun detectsTruncationNxdomainAndWrongId() {
        val q = DnsMessage.buildTxtQuery(7, "abc.elcano.top")
        val trunc = response(q, 0x8380, emptyList())
        assertTrue(DnsMessage.parseTxtResponse(trunc, trunc.size, 7) is DnsMessage.Result.Truncated)
        val nx = response(q, 0x8183, emptyList())
        assertTrue(DnsMessage.parseTxtResponse(nx, nx.size, 7) is DnsMessage.Result.NotFound)
        val ok = response(q, 0x8180, listOf(listOf("x")))
        assertTrue(DnsMessage.parseTxtResponse(ok, ok.size, 8) is DnsMessage.Result.Failure)
    }

    @Test fun dohTxtData() {
        assertEquals("abcdef", DohResolver.parseTxtData("\"abc\" \"def\""))
        assertEquals("a\"b\\c", DohResolver.parseTxtData("\"a\\\"b\\\\c\""))
        assertEquals("A", DohResolver.parseTxtData("\"\\065\""))
        assertEquals("plain", DohResolver.parseTxtData("plain"))
        val json = """{"Status":0,"Answer":[{"name":"x.","type":5,"data":"y."},{"name":"x.","type":16,"data":"\"p1\" \"p2\""}]}"""
        assertEquals(listOf("p1p2"), DohResolver.parseJson(json, "x"))
        try {
            DohResolver.parseJson("""{"Status":3}""", "x"); fail()
        } catch (e: CodeNotFoundException) { /* ok */ }
    }

    private class Fake(val delayMs: Long, val result: () -> List<String>) : DnsTxtResolver {
        var calls = 0
        override suspend fun resolveTxt(host: String): List<String> { calls++; delay(delayMs); return result() }
    }

    @Test fun raceReturnsFastestAndFallsBack() = runBlocking {
        val slow = Fake(2_000) { listOf("slow") }
        val fast = Fake(10) { listOf("fast") }
        val started = System.currentTimeMillis()
        assertEquals(listOf("fast"), CompositeDnsResolver(listOf(listOf(slow, fast))).resolveTxt("h"))
        assertTrue("no debe esperar al lento", System.currentTimeMillis() - started < 1_000)

        val broken = Fake(5) { throw IOException("x") }
        val doh = Fake(5) { listOf("doh") }
        assertEquals(listOf("doh"), CompositeDnsResolver(listOf(listOf(broken, broken), listOf(doh))).resolveTxt("h"))
    }

    @Test fun nxdomainIsDefinitive() = runBlocking {
        val nx = Fake(5) { throw CodeNotFoundException("h") }
        val broken = Fake(5) { throw IOException("x") }
        val doh = Fake(5) { listOf("doh") }
        try {
            CompositeDnsResolver(listOf(listOf(nx, broken), listOf(doh))).resolveTxt("h"); fail()
        } catch (e: CodeNotFoundException) { /* ok */ }
        assertEquals(0, doh.calls)
        try {
            CompositeDnsResolver(listOf(listOf(broken))).resolveTxt("h"); fail()
        } catch (e: DnsUnavailableException) { /* ok */ }
    }
}
