package com.mivisor.app

import com.mivisor.app.data.AceStreamLink
import com.mivisor.app.data.ChannelGrouping
import com.mivisor.app.engine.Availability
import com.mivisor.app.engine.FailureKind
import com.mivisor.app.engine.HealthPolicy
import com.mivisor.app.engine.HealthState
import com.mivisor.app.engine.SourceCandidate
import com.mivisor.app.engine.SourceHealth
import com.mivisor.app.engine.SourceRegistry
import com.mivisor.app.engine.SourceSelector
import com.mivisor.app.engine.SourceStatus
import com.mivisor.app.model.Channel
import com.mivisor.app.model.ChannelKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.TimeZone

/** Salud por Content ID, cuarentena temporal, dos pasadas sin bucles y deduplicación global por Content ID. */
class SourceHealthTest {
    private val hex = "0123456789abcdef0123456789abcdef01234567"
    private fun id(c: Char) = c.toString().repeat(40)
    private val utc = TimeZone.getTimeZone("UTC")
    private val day = HealthPolicy.DAY_MS

    private fun ace(cid: String, name: String, url: String, provider: String = "P") =
        Channel(cid, name, url, null, ChannelKind.ACE, "", 0, provider, provider.lowercase())

    // ------------------------------------------------------------------ normalización

    @Test fun sameContentIdWrittenDifferentlyIsOneIdentity() {
        val variants = listOf(
            "acestream://" + hex.uppercase(), hex, "acestream://$hex", "  acestream://$hex\n", "acestream://$hex?foo=1",
            "AceStream://${hex.uppercase()}/", "http://127.0.0.1:6878/ace/getstream?content_id=${hex.uppercase()}&x=1",
            "http://127.0.0.1:6878/ace/getstream?id=$hex"
        )
        variants.forEach { assertEquals(hex, AceStreamLink.normalizedId(it)) }
        assertNull(AceStreamLink.normalizedId("acestream://abc"))                    // no son 40 hex
        assertNull(AceStreamLink.normalizedId("acestream://" + "g".repeat(40)))
        assertNull(AceStreamLink.normalizedId("https://example.org/live.m3u8"))
    }

    // ------------------------------------------------------------------ registro global

    @Test fun registryKeepsOneSourcePerContentIdReferencedByEveryChannel() {
        val a = ace("1", "DAZN 1", "acestream://${hex.uppercase()}", "Uno")
        val b = ace("2", "Movistar Deportes", "acestream://$hex", "Dos")          // otro canal, mismo Content ID
        val c = ace("3", "DAZN 1", hex, "Dos")                                     // mismo canal repetido en otra lista
        val d = ace("4", "Eurosport", "acestream://" + id('e'), "Uno")
        val bad = ace("5", "Roto", "acestream://xyz", "Uno")
        val snap = SourceRegistry.build(listOf(Triple("uno", "Uno", listOf(a, d, bad)), Triple("dos", "Dos", listOf(b, c))))
        assertEquals(2, snap.sources.size)
        val shared = snap.of("acestream://$hex")!!
        assertEquals(3, shared.refs.size)
        assertEquals(listOf("DAZN 1", "Movistar Deportes"), shared.channelNames)
        val r = snap.report
        assertEquals(4, r.references)
        assertEquals(2, r.uniqueContentIds)
        assertEquals(2, r.unified)
        assertEquals(1, r.sameChannelRepeats)
        assertEquals(1, r.sharedByChannels)
        assertEquals(1, r.invalid)
    }

    @Test fun groupingNeverDuplicatesAContentIdInsideAChannelButKeepsDistinctChannels() {
        val a = ace("1", "DAZN 1", "acestream://${hex.uppercase()}", "Uno")
        val a2 = ace("2", "DAZN 1 FHD", "acestream://$hex", "Dos")               // mismo canal + mismo ID → 1 fuente
        val other = ace("3", "Movistar Deportes", hex, "Dos")                     // otro canal con ese ID → se conserva
        val groups = ChannelGrouping.group(listOf(Triple("uno", "Uno", listOf(a)), Triple("dos", "Dos", listOf(a2, other))))
        val dazn = groups.first { g -> g.sources.any { it.channel.id == "1" } }
        assertEquals(1, dazn.sources.size)
        assertTrue(groups.any { g -> g.sources.any { it.channel.id == "3" } })
        // Y dentro de una búsqueda tampoco se prueba dos veces (segunda defensa).
        val sel = SourceSelector(listOf(SourceCandidate(hex.uppercase(), "A"), SourceCandidate(hex, "B")))
        assertEquals(1, sel.order.size)
    }

    // ------------------------------------------------------------------ salud

    private fun health(now: LongArray, file: File? = null) = SourceHealth(file, { now[0] }, utc)

    @Test fun successFromAnyChannelClearsNegativeHistory() {
        val now = longArrayOf(10 * day)
        val h = health(now)
        h.onConfirmedFailure("acestream://${hex.uppercase()}", FailureKind.NO_PEERS)
        assertEquals(HealthState.SUSPECT, h.get(hex)!!.state)
        now[0] += day
        h.onSuccess(hex)                                                         // desde otro canal, otro formato
        val r = h.get("acestream://$hex")!!
        assertEquals(HealthState.ACTIVE, r.state)
        assertEquals(0, r.confirmedFailures)
        assertTrue(r.failureDays.isEmpty())
        assertEquals(1, h.all().size)                                            // un solo registro por Content ID
    }

    @Test fun notSourceFaultsAndRepeatedEntriesDoNotMultiplyFailures() {
        val now = longArrayOf(10 * day)
        val h = health(now)
        assertEquals(SourceHealth.Outcome.IGNORED, h.onConfirmedFailure(hex, FailureKind.ENGINE_DOWN))
        assertEquals(SourceHealth.Outcome.IGNORED, h.onConfirmedFailure(hex, FailureKind.CANCELLED))
        assertEquals(SourceHealth.Outcome.IGNORED, h.onConfirmedFailure(hex, FailureKind.DROPPED))
        assertNull(h.get(hex))
        assertEquals(SourceHealth.Outcome.COUNTED, h.onConfirmedFailure(hex, FailureKind.RESOLUTION))
        repeat(10) { now[0] += 30 * 60_000L; h.onConfirmedFailure(hex, FailureKind.RESOLUTION) }   // 10 veces en 5 h
        assertEquals(1, h.get(hex)!!.confirmedFailures)
        h.onSuspect(hex, FailureKind.TIMEOUT)                                    // primera pasada: no suma
        assertEquals(1, h.get(hex)!!.confirmedFailures)
    }

    @Test fun quarantineNeedsFailuresOnDistinctDaysAndIsTemporary() {
        val now = longArrayOf(100 * day + 12 * HealthPolicy.HOUR_MS)
        val h = health(now)
        // Un fallo por día (separados más que la ventana): con 3 aún no; al 4.º, cuarentena.
        h.onConfirmedFailure(hex, FailureKind.NO_PEERS); now[0] += 21 * HealthPolicy.HOUR_MS
        h.onConfirmedFailure(hex, FailureKind.NO_PEERS)
        assertEquals(2, h.get(hex)!!.failureDays.size)
        now[0] += 21 * HealthPolicy.HOUR_MS
        h.onConfirmedFailure(hex, FailureKind.NO_PEERS)
        assertEquals(HealthState.SUSPECT, h.get(hex)!!.state)
        now[0] += 21 * HealthPolicy.HOUR_MS
        val out = h.onConfirmedFailure(hex, FailureKind.NO_PEERS)
        val r = h.get(hex)!!
        assertTrue(r.failureDays.size >= HealthPolicy.QUARANTINE_MIN_DISTINCT_DAYS)
        assertEquals(SourceHealth.Outcome.QUARANTINED, out)
        assertEquals(HealthState.QUARANTINED, r.state)
        assertEquals(Availability.EXCLUDED, h.availability(hex))
        assertTrue(h.quarantined().isNotEmpty())                                // sigue guardada
        // Pasados los días de cuarentena: periodo de prueba (vuelve a entrar, al final de la cola).
        now[0] += HealthPolicy.QUARANTINE_DAYS * day
        assertEquals(Availability.PROBATION, h.availability(hex))
        // Si en la prueba vuelve a fallar de forma confirmada → otra vez en cuarentena.
        assertEquals(SourceHealth.Outcome.QUARANTINED, h.onConfirmedFailure(hex, FailureKind.TIMEOUT))
        assertEquals(Availability.EXCLUDED, h.availability(hex))
        // Reactivar a mano (diagnóstico).
        h.reactivate("acestream://$hex")
        assertEquals(Availability.NORMAL, h.availability(hex))
        assertEquals(0, h.get(hex)!!.confirmedFailures)
    }

    @Test fun probationSuccessGoesStraightBackToActive() {
        val now = longArrayOf(50 * day)
        val h = health(now)
        repeat(4) { h.onConfirmedFailure(hex, FailureKind.NO_PEERS); now[0] += day }
        assertEquals(HealthState.QUARANTINED, h.get(hex)!!.state)
        now[0] += HealthPolicy.QUARANTINE_DAYS * day
        h.onSuccess(hex)
        assertEquals(HealthState.ACTIVE, h.get(hex)!!.state)
        assertEquals(Availability.NORMAL, h.availability(hex))
    }

    @Test fun globalOutageIsDetectedOnlyFromOtherChannelsAndWithoutAnySuccess() {
        val now = longArrayOf(20 * day)
        val h = health(now)
        listOf('a', 'b', 'c').forEach { h.onSuspect(id(it), FailureKind.RESOLUTION) }
        assertFalse(h.looksLikeGlobalOutage(exclude = setOf(id('a'), id('b'), id('c'))))   // solo las de este canal
        assertTrue(h.looksLikeGlobalOutage(exclude = setOf(id('z'))))
        h.onSuccess(id('d'))
        assertFalse(h.looksLikeGlobalOutage(exclude = setOf(id('z'))))                     // algo funciona: no es global
    }

    @Test fun persistsOneRecordPerContentId() {
        val dir = Files.createTempDirectory("health").toFile()
        try {
            val f = File(dir, "health.json")
            val now = longArrayOf(30 * day)
            health(now, f).onConfirmedFailure("acestream://${hex.uppercase()}", FailureKind.NO_PEERS)
            val again = health(now, f)
            assertEquals(1, again.get(hex)!!.confirmedFailures)
            assertEquals(1, again.all().size)
            assertFalse(f.readText().contains(hex.uppercase()))
        } finally { dir.deleteRecursively() }
    }

    // ------------------------------------------------------------------ selector: dos pasadas y cuarentena

    @Test fun twoPassesThenStopWithoutLoopsAndOnlySecondPassConfirms() {
        val c = ('a'..'c').map { SourceCandidate(id(it), "S$it") }
        val sel = SourceSelector(c)
        var s = sel.start() as SourceSelector.State.Trying
        s = sel.onFailure(s.generation, FailureKind.NO_PEERS) as SourceSelector.State.Trying
        s = sel.onFailure(s.generation, FailureKind.ENGINE_DOWN) as SourceSelector.State.Trying     // no es culpa de la fuente
        val end1 = sel.onFailure(s.generation, FailureKind.TIMEOUT) as SourceSelector.State.Exhausted
        assertTrue(end1.recoverable)
        assertTrue(sel.confirmedFailures().isEmpty())                            // la primera pasada no confirma
        assertEquals(setOf(id('a'), id('c')), sel.suspectIds().keys)            // la del motor caído no es sospechosa
        // Segunda pasada: solo las sospechosas, una vez cada una.
        var r = sel.startRecovery() as SourceSelector.State.Trying
        assertEquals(id('a'), r.source.contentId)
        r = sel.onFailure(r.generation, FailureKind.NO_PEERS) as SourceSelector.State.Trying
        assertEquals(id('c'), r.source.contentId)
        val end2 = sel.onFailure(r.generation, FailureKind.RESOLUTION) as SourceSelector.State.Exhausted
        assertFalse(end2.recoverable)                                            // fin: nunca vuelve al principio
        assertEquals(setOf(id('a'), id('c')), sel.confirmedFailures().keys)
        assertEquals(end2, sel.startRecovery())                                  // no hay tercera pasada
    }

    @Test fun successInSecondPassIsNotAFailure() {
        val sel = SourceSelector(listOf(SourceCandidate(id('a'), "A"), SourceCandidate(id('b'), "B")))
        var s = sel.start() as SourceSelector.State.Trying
        s = sel.onFailure(s.generation, FailureKind.NO_PEERS) as SourceSelector.State.Trying
        sel.onFailure(s.generation, FailureKind.NO_PEERS)
        val r = sel.startRecovery() as SourceSelector.State.Trying
        sel.onVideo(r.generation)
        assertTrue(sel.confirmedFailures().isEmpty())
        assertEquals(SourceStatus.PLAYING, sel.statusOf(r.index))
    }

    @Test fun quarantinedSourcesAreNotTriedAutomaticallyButCanBeChosen() {
        val c = listOf(SourceCandidate(id('a'), "A", quality = 3), SourceCandidate(id('b'), "B", quality = 1), SourceCandidate(id('c'), "C", quality = 2))
        val sel = SourceSelector(c, availability = mapOf(
            "acestream://" + id('a').uppercase() to Availability.EXCLUDED,
            id('c') to Availability.PROBATION
        ))
        assertEquals(listOf(id('b'), id('c'), id('a')), sel.order.map { it.contentId })   // prueba al final, cuarentena detrás
        assertEquals(2, sel.automaticCount)
        assertEquals(SourceStatus.QUARANTINED, sel.statusOf(2))
        var s = sel.start() as SourceSelector.State.Trying
        assertEquals(id('b'), s.source.contentId)
        s = sel.onFailure(s.generation, FailureKind.NO_PEERS) as SourceSelector.State.Trying
        assertEquals(id('c'), s.source.contentId)
        assertTrue(sel.onFailure(s.generation, FailureKind.NO_PEERS) is SourceSelector.State.Exhausted)   // la "a" no
        // A mano sí (Probar de nuevo).
        val manual = sel.choose(2) as SourceSelector.State.Trying
        assertEquals(id('a'), manual.source.contentId)
    }
}
