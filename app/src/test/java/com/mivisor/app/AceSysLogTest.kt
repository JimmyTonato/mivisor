package com.mivisor.app

import com.mivisor.app.diagnostics.AceSysLogFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** Líneas de Ace Stream del log del sistema: solo Ace, solo el intento, sin IDs ni IPs de peers. */
class AceSysLogTest {
    private val year = 2026
    private fun at(h: Int, m: Int, s: Int) = Calendar.getInstance().apply { clear(); set(year, 9, 25, h, m, s) }.timeInMillis

    private val raw = listOf(
        "10-25 20:24:20.000 D/AS/Player( 4321): too early",
        "10-25 20:24:26.100 I/ActivityTaskManager(  800): START u0 {act=android.intent.action.VIEW dat=acestream://aa11bb22cc33dd44ee55ff6600778899aabbccdd cmp=org.acestream.node/org.acestream.engine.ContentStartActivity}",
        "10-25 20:24:26.300 D/AS/ContentStart( 4321): start: infohash=aa11bb22cc33dd44ee55ff6600778899aabbccdd",
        "10-25 20:24:27.000 D/WifiService(  900): something else entirely",
        "10-25 20:24:28.000 W/AS/Player( 4321): prebuffering 0% peers=0 from 83.45.120.7:8621",
        "10-25 20:24:29.000 E/AceStream/engine( 4400): tracker error: http://tracker.example/announce?key=abc",
        "10-25 20:24:30.000 D/chromium( 5000): org.acestream mention in unrelated tag",
        "10-25 20:26:00.000 D/AS/Player( 4321): too late",
        "garbage line"
    )

    @Test fun keepsOnlyAceLinesInsideTheAttemptAndCleansThem() {
        val sel = AceSysLogFilter.select(raw, at(20, 24, 25), at(20, 25, 0), year)
        assertEquals(4, sel.total)
        val text = sel.lines.joinToString("\n")
        assertTrue(text.contains("ActivityTaskManager"))                  // Android abrió ContentStartActivity de Ace
        assertTrue(text.contains("W/AS/Player: prebuffering 0% peers=0"))
        assertTrue(text.contains("E/AceStream/engine: tracker error"))
        assertFalse(text.contains("too early") || text.contains("too late"))
        assertFalse(text.contains("WifiService") || text.contains("chromium"))
        assertFalse(text.contains("aa11bb22cc33dd44ee55ff6600778899aabbccdd"))   // sin Content ID
        assertFalse(text.contains("83.45.120.7"))                                 // sin IP del peer
        assertFalse(text.contains("tracker.example"))                             // sin URL
    }

    @Test fun manyLinesKeepErrorsAndTheEnd() {
        val many = (0 until 400).map { i -> "10-25 20:24:%02d.%03d D/AS/Player( 1): tick $i".format(30 + i / 1000, i % 1000) } +
            listOf("10-25 20:24:31.000 E/AS/Player( 1): fatal thing")
        val sel = AceSysLogFilter.select(many, at(20, 24, 25), at(20, 25, 0), year)
        assertEquals(401, sel.total)
        assertTrue(sel.lines.size <= AceSysLogFilter.MAX_LINES)
        assertTrue(sel.lines.any { it.contains("fatal thing") })
        assertTrue(sel.lines.last().contains("fatal thing"))                 // orden cronológico
        assertTrue(sel.lines.any { it.contains("tick 399") })                 // se conserva el final
        assertFalse(sel.lines.any { it.contains("tick 0\n") || it.endsWith("tick 0") })
        assertTrue(AceSysLogFilter.block(sel).startsWith("  📋 Log interno de Ace Stream"))
    }
}
