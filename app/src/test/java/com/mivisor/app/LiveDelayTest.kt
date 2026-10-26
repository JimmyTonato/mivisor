package com.mivisor.app

import com.mivisor.app.engine.EngineConfig
import com.mivisor.app.engine.LagEstimator
import com.mivisor.app.engine.LiveDelayController
import com.mivisor.app.engine.LiveDelayController.Action
import com.mivisor.app.engine.LiveDelayPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retraso respecto al directo: los cortes no se acumulan. Se simula un reproductor en directo segundo a segundo:
 * reproduciendo a 1.0x el retraso no cambia; parado (corte de red) crece 1 s por segundo; a 1.06x baja 0,06 s/s;
 * un salto lo deja en el objetivo.
 */
class LiveDelayTest {

    private class SimPlayer(var delayMs: Long) {
        var speed = 1f
        var now = 0L
        val log = ArrayList<Long>()

        /** Un segundo de reproducción (o de corte). Devuelve la acción del controlador. */
        fun second(c: LiveDelayController, target: Long, cut: Boolean): Action {
            now += 1_000
            if (cut) delayMs += 1_000 else delayMs -= ((speed - 1f) * 1_000).toLong()
            val a = c.onTick(now, delayMs, target, playing = !cut)
            when (a) {
                is Action.SetSpeed -> speed = a.speed
                is Action.Resync -> { delayMs = a.targetDelayMs; speed = 1f }
                Action.None -> Unit
            }
            log += delayMs
            return a
        }
    }

    @Test fun repeatedFiveSecondCutsDoNotAccumulateDelay() {
        val target = 8_000L
        val c = LiveDelayController()
        val p = SimPlayer(target)
        repeat(20) { p.second(c, target, cut = false) }
        val afterEachRecovery = ArrayList<Long>()
        repeat(4) {                                                      // 4 cortes de 5 s
            repeat(5) { p.second(c, target, cut = true) }
            assertEquals(target + 5_000, p.delayMs)                       // justo tras el corte: 13 s
            repeat(15) { p.second(c, target, cut = false) }
            afterEachRecovery += p.delayMs
        }
        // Tras cada corte vuelve al objetivo (no 13 → 18 → 23 → 28).
        afterEachRecovery.forEach { assertTrue("retraso $it", it in target - LiveDelayPolicy.TOLERANCE_MS..target + LiveDelayPolicy.TOLERANCE_MS) }
        assertEquals(4, c.stats.resyncs)
        assertEquals(20_000L, c.stats.discardedMs)
        assertEquals(1f, p.speed)
    }

    @Test fun smallExcessIsRecoveredSmoothlyWithSpeedUpThenBackToNormalSpeed() {
        val target = 6_000L
        val c = LiveDelayController()
        val p = SimPlayer(target)
        repeat(5) { p.second(c, target, cut = false) }
        repeat(2) { p.second(c, target, cut = true) }                    // corte corto: +2 s
        var sawSpeedUp = false
        repeat(60) { if (p.second(c, target, cut = false) == Action.SetSpeed(LiveDelayPolicy.SPEED_UP)) sawSpeedUp = true }
        assertTrue(sawSpeedUp)
        assertEquals(0, c.stats.resyncs)                                 // sin saltos: recuperación suave
        assertEquals(1, c.stats.speedUpsCompleted)
        assertEquals(1f, p.speed)
        assertTrue("retraso ${p.delayMs}", p.delayMs <= target + LiveDelayPolicy.SPEED_UP_DONE_MS)
    }

    @Test fun withinToleranceNothingHappensAndMeasuresWaitForStablePlayback() {
        val c = LiveDelayController()
        assertEquals(Action.None, c.onTick(1_000, 6_800, 6_000, playing = true))       // aún no estable
        assertEquals(Action.None, c.onTick(4_000, 6_800, 6_000, playing = true))       // dentro de tolerancia
        // Tras una interrupción se espera antes de decidir aunque el retraso sea grande.
        c.onInterruption(5_000)
        assertEquals(Action.None, c.onTick(5_500, 20_000, 6_000, playing = true))
        assertTrue(c.onTick(8_000, 20_000, 6_000, playing = true) is Action.Resync)
        // Y no salta en cadena: espera el enfriamiento.
        c.onTick(9_000, 20_000, 6_000, playing = true)
        assertTrue(c.onTick(12_000, 20_000, 6_000, playing = true) !is Action.Resync)
        assertEquals(1, c.stats.resyncs)
        assertEquals(14_000L, c.stats.deviationMs)
    }

    @Test fun tsEstimatorAddsStallsAndSubtractsSpeedUp() {
        val e = LagEstimator()
        e.start(nowMs = 0, positionMs = 0, baseDelayMs = 6_000)
        assertEquals(6_000L, e.estimate(10_000, 10_000))                // 10 s a 1.0x: igual
        assertEquals(11_000L, e.estimate(15_000, 10_000))               // 5 s parado: +5 s
        assertEquals(10_400L, e.estimate(25_000, 20_600))               // 10 s a 1.06x: −0,6 s
    }

    @Test fun hlsWindowCoversMaximumDelayOnEveryDevice() {
        listOf(true, false).forEach { low ->
            val cfg = EngineConfig(16900, "/d", "/h", lowRam = low)
            assertTrue(cfg.validate().toString(), cfg.validate().isEmpty())
            val env = cfg.environment()
            assertEquals("2", env["OUTPACE_HLS_STARTUP_SEGMENTS"])
            assertTrue(env.getValue("OUTPACE_HLS_WINDOW_SEGMENTS").toLong() * EngineConfig.HLS_SEGMENT_MS >= 12_000)
        }
    }
}
