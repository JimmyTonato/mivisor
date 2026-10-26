package com.mivisor.app

import com.mivisor.app.engine.EngineApi
import com.mivisor.app.engine.EngineSupervisor
import com.mivisor.app.engine.EngineSupervisor.Phase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files

/**
 * Ciclo de vida del proceso del motor con un motor FALSO que imita "outpace serve" (OUTPACE_BIND, /healthz,
 * status, DELETE). Ruta del falso en la variable MIVISOR_FAKE_ENGINE; si no está (CI), las pruebas se saltan.
 */
class EngineSupervisorTest {
    private val fake = System.getenv("MIVISOR_FAKE_ENGINE")?.let(::File)?.takeIf { it.canExecute() }
    private val port = 46_900 + (System.nanoTime() % 500).toInt()
    private val killPid: (Int) -> Unit = { pid -> Runtime.getRuntime().exec(arrayOf("kill", "-9", pid.toString())).waitFor() }

    private fun withDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("engine").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }

    private fun sup(dir: File, preferred: Int = port, timeout: Long = 8_000) =
        EngineSupervisor(fake!!, dir, lowRam = true, killPid = killPid, preferredPort = preferred, readyTimeoutMs = timeout)

    @Test fun singleProcessOnPreferredPortAndCleanStop() = runBlocking {
        if (fake == null) return@runBlocking
        withDir { dir -> runBlocking {
            val s = sup(dir)
            val st = s.ensureStarted()
            assertEquals(Phase.READY, st.phase)
            assertEquals(port, st.port)
            assertTrue(File(dir, "engine.pid").isFile)
            assertEquals(3, EngineApi.status(st.baseUrl!!, "AB".repeat(20))!!.peers)
            assertTrue(EngineApi.stop(st.baseUrl!!, "ab".repeat(20)))
            assertEquals(st.pid, s.ensureStarted().pid)                         // reutiliza: un solo proceso
            s.stop()
            assertFalse(s.isRunning())
            assertFalse(File(dir, "engine.pid").exists())
            assertTrue(EngineSupervisor.portFree(port))                       // sin listeners huérfanos
        } }
    }

    @Test fun foreignAppOnPreferredPortIsNotTouched() = runBlocking {
        if (fake == null) return@runBlocking
        withDir { dir -> runBlocking {
            ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).use { foreign ->
                val st = sup(dir).ensureStarted()
                assertEquals(Phase.READY, st.phase)
                assertTrue(port != st.port)                                // otro puerto libre
                assertFalse(foreign.isClosed)                                 // la otra app sigue ahí
                sup(dir).stop()
            }
        } }
    }

    @Test fun orphanOfOurOwnIsClosedOnNextStart() = runBlocking {
        if (fake == null) return@runBlocking
        withDir { dir -> runBlocking {
            val first = sup(dir).ensureStarted()                              // "la app muere" sin parar el motor
            assertEquals(Phase.READY, first.phase)
            val second = sup(dir)
            val st = second.ensureStarted()
            assertEquals(Phase.READY, st.phase)
            assertEquals(port, st.port)                                       // el huérfano liberó el puerto
            assertTrue(first.pid != st.pid)
            assertFalse(File("/proc/${first.pid}").exists())
            second.stop()
        } }
    }

    @Test fun stubbornProcessIsForcedAndCrashIsReported() = runBlocking {
        if (fake == null) return@runBlocking
        withDir { dir -> runBlocking {
            File(dir, "fake_mode").writeText("ignore_term")
            val s = sup(dir)
            val st = s.ensureStarted()
            assertEquals(Phase.READY, st.phase)
            s.stop()                                                          // no atiende SIGTERM → forzado
            assertFalse(File("/proc/${st.pid}").exists())
            File(dir, "fake_mode").writeText("crash")
            val failed = sup(dir, timeout = 3_000).ensureStarted()
            assertEquals(Phase.FAILED, failed.phase)
            assertTrue(failed.message!!.contains("puerto"))                   // se distingue el conflicto de puertos
        } }
    }
}
