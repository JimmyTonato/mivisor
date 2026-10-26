package com.mivisor.app

import com.mivisor.app.engine.AdaptiveBuffer
import com.mivisor.app.engine.EngineConfig
import com.mivisor.app.engine.EngineStatus
import com.mivisor.app.engine.FailureKind
import com.mivisor.app.engine.FailurePolicy
import com.mivisor.app.engine.SourceCandidate
import com.mivisor.app.engine.SourceMemory
import com.mivisor.app.engine.SourceSelector
import com.mivisor.app.engine.SourceSelector.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Motor P2P integrado: lógica pura (buffer adaptativo, configuración, selección de fuentes, memoria). */
class EngineLogicTest {
    private fun id(n: Int) = n.toString(16).padStart(2, '0').repeat(20)
    private fun c(n: Int) = SourceCandidate(id(n), "Fuente $n")

    // ---------------------------------------------------------------- buffer

    @Test fun bufferStartsSmallGrowsWhenUnstableAndShrinksWhenStable() {
        var now = 0L
        val b = AdaptiveBuffer(lowRam = false) { now }
        assertFalse(b.shouldStartPlayback(2_000, rebuffering = false))
        assertTrue(b.shouldStartPlayback(2_500, rebuffering = false))          // arranca con ~2,5 s
        assertEquals(6_000, b.targetMs)                                        // colchón normal
        assertTrue(b.shouldContinueLoading(4_000))
        assertFalse(b.shouldContinueLoading(6_000))                           // no acumula de más
        assertFalse(b.shouldContinueLoading(5_500))                           // histéresis
        assertTrue(b.shouldContinueLoading(4_900))
        repeat(5) { b.onRebuffer() }
        assertEquals(10_000, b.targetMs)                                       // inestable: tope ~10 s
        assertFalse(b.shouldStartPlayback(3_000, rebuffering = true))          // tras corte pide algo más
        assertTrue(b.shouldStartPlayback(4_000, rebuffering = true))
        now += 60_000; b.onTick()
        assertEquals(10_000, b.targetMs)                                       // aún reciente: no baja
        now += 3 * 60_000; b.onTick()
        assertEquals(9_000, b.targetMs)                                        // estable: baja poco a poco
        now += 10_000; b.onTick()
        assertEquals(9_000, b.targetMs)                                        // como mucho 1 paso por minuto
        repeat(10) { now += 60_000; b.onTick() }
        assertEquals(6_000, b.targetMs)                                        // nunca por debajo del normal
        val low = AdaptiveBuffer(lowRam = true) { now }
        repeat(10) { low.onRebuffer() }
        assertEquals(8_000, low.targetMs)
        assertEquals(5_000, AdaptiveBuffer(lowRam = true).normalMs)
    }

    // ---------------------------------------------------------------- configuración

    @Test fun engineIsAPlaybackClientWithBoundedMemory() {
        val cfg = EngineConfig(16900, "/data/outpace", "/data", lowRam = false)
        val env = cfg.environment()
        assertEquals("127.0.0.1:16900", env["OUTPACE_BIND"])
        assertEquals("127.0.0.1:0", env["OUTPACE_RTMP_BIND"])                 // RTMP efímero, no fijo
        assertEquals("127.0.0.1:0", env["OUTPACE_PEER_LISTEN"])
        for (k in listOf("OUTPACE_ENABLE_INBOUND", "OUTPACE_ENABLE_SEEDING", "OUTPACE_ENABLE_PORT_MAPPING", "OUTPACE_ACE_COMPAT")) assertEquals("0", env[k])
        assertEquals("none", env["OUTPACE_PORT_MAP_BACKEND"])
        assertEquals("1", env["OUTPACE_BEP9_CONTENT_ID_FALLBACK"])
        assertTrue(env.getValue("OUTPACE_PREBUFFER_MS").toLong() <= 3_000)    // nada de 20-30 s
        assertTrue(env.keys.none { it.contains("CANALPEER", true) } && env.values.none { it.contains("canalpeer", true) })
        assertEquals(emptyList<String>(), cfg.validate())
        val low = EngineConfig(40000, "/d", "/h", lowRam = true)
        assertEquals(emptyList<String>(), low.validate())
        assertTrue(low.environment().getValue("OUTPACE_PREBUFFER_BYTES").toLong() < env.getValue("OUTPACE_PREBUFFER_BYTES").toLong())
        assertTrue(low.environment().getValue("OUTPACE_MAX_ACTIVE_UPSTREAMS").toInt() <= 4)
        assertTrue(EngineConfig(0, "/d", "/h", false).validate().isNotEmpty())
    }

    // ---------------------------------------------------------------- selección de fuentes

    @Test fun sourcesAreTriedOneByOneUntilRealVideo() {
        val sel = SourceSelector(listOf(c(1), c(2), c(3)))
        val a = sel.start() as State.Trying
        assertEquals(id(1), a.source.contentId)
        val b = sel.onFailure(a.generation, FailureKind.NO_PEERS) as State.Trying
        assertEquals(id(2), b.source.contentId)
        assertEquals(b, sel.onFailure(a.generation, FailureKind.TIMEOUT))     // aviso viejo: se ignora
        assertEquals(b, sel.onVideo(a.generation))
        val playing = sel.onVideo(b.generation) as State.Playing
        assertEquals(id(2), playing.source.contentId)
        // La que se veía se cae → siguiente sin probar (la 1 ya falló).
        val c3 = sel.onFailure(b.generation, FailureKind.PLAYER) as State.Trying
        assertEquals(id(3), c3.source.contentId)
        assertEquals(FailureKind.DROPPED, sel.failureOf(id(2)))
        val end = sel.onFailure(c3.generation, FailureKind.TIMEOUT) as State.Exhausted
        assertEquals(FailureKind.TIMEOUT, end.lastFailure)
    }

    @Test fun rememberedGoodThenPreferredThenRestWithRecentFailuresLast() {
        val sel = SourceSelector(listOf(c(1), c(2), c(3), c(4), c(1)), rememberedGood = id(3), preferred = id(4), recentlyFailed = setOf(id(1)))
        assertEquals(listOf(id(3), id(4), id(2), id(1)), sel.order.map { it.contentId })   // sin duplicados
        val first = sel.start() as State.Trying
        val manual = sel.manualNext() as State.Trying                          // opción avanzada
        assertEquals(id(4), manual.source.contentId)
        assertTrue(manual.generation > first.generation)
    }

    @Test fun failurePolicyDecidesEarlyWithoutBlindTimeouts() {
        assertNull(FailurePolicy.earlyFailure(10_000, sawStatus = true, maxPeersSeen = 0))
        assertEquals(FailureKind.NO_PEERS, FailurePolicy.earlyFailure(15_000, sawStatus = true, maxPeersSeen = 0))
        assertNull(FailurePolicy.earlyFailure(20_000, sawStatus = true, maxPeersSeen = 3))   // hay peers: se espera
        assertEquals(FailureKind.TIMEOUT, FailurePolicy.earlyFailure(30_000, sawStatus = true, maxPeersSeen = 3))
        assertEquals(FailureKind.RESOLUTION, FailurePolicy.earlyFailure(30_000, sawStatus = false, maxPeersSeen = 0))
        assertEquals(FailureKind.RESOLUTION, FailurePolicy.fromHttpStatus(422))
        assertEquals(FailureKind.TIMEOUT, FailurePolicy.fromHttpStatus(503))          // HLS: primer segmento no llegó a tiempo
        assertEquals(FailureKind.ENGINE_DOWN, FailurePolicy.fromHttpStatus(500))
    }

    @Test fun statusParsesEngineJson() {
        val s = EngineStatus.parse("""{"network":"ace","id":"cid:x","clients":1,"peers":4,"bitrate":2450000,"buffer_ms":6200,"uploaded":0,"peers_served":0,
            "recovery":{"upstream_reconnects":2,"stale_teardowns":1,"skipped_evicted_gaps":0,"signature_rejects":0,"active_upstreams":4},
            "metadata":{"title":"Example Sports","bitrate":2450000,"categories":["sports"]}}""")!!
        assertEquals(4, s.peers); assertEquals(6200, s.engineBufferMs); assertEquals(3, s.recoveries); assertEquals("Example Sports", s.title)
        assertNull(EngineStatus.parse("not json"))
        assertEquals(0, EngineStatus.parse("""{"peers":0,"metadata":null}""")!!.activeUpstreams)
    }

    // ---------------------------------------------------------------- memoria

    @Test fun memoryRemembersGoodSourcePerChannelAndRecentFailures() {
        val dir = Files.createTempDirectory("eng").toFile()
        try {
            var now = 1_000_000L
            val f = java.io.File(dir, "engine/sources.json")
            val m = SourceMemory(f) { now }
            m.onSuccess("dazn laliga", id(2).uppercase(), EngineStatus(5, 3_000_000, 2000, 0, 0, 0, 4, "DAZN LaLiga"))
            m.onFailure(id(1), FailureKind.NO_PEERS)
            val m2 = SourceMemory(f) { now }                                    // persistido
            assertEquals(id(2), m2.rememberedGood("dazn laliga"))
            assertEquals(setOf(id(1)), m2.recentlyFailed(listOf(id(1), id(2))))
            assertEquals("DAZN LaLiga", m2.info(id(2))!!.title)
            now += 11 * 60_000
            assertTrue(m2.recentlyFailed(listOf(id(1))).isEmpty())             // ya no es "reciente"
            now += 4L * 24 * 60 * 60 * 1000
            assertNull(m2.rememberedGood("dazn laliga"))                       // caduca
        } finally { dir.deleteRecursively() }
    }
}
