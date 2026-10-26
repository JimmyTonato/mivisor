package com.mivisor.app

import com.mivisor.app.data.ChannelGrouping
import com.mivisor.app.engine.EngineRouting
import com.mivisor.app.engine.EngineRouting.Route
import com.mivisor.app.model.Channel
import com.mivisor.app.model.ChannelKind
import com.mivisor.app.player.EngineMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineRoutingTest {
    private fun id(c: Char) = c.toString().repeat(40)
    private fun ace(cid: String, name: String, c: Char, provider: String) =
        Channel(cid, name, "acestream://" + id(c), null, ChannelKind.ACE, "", 0, provider, provider.lowercase())

    @Test fun routeByModeAndSupport() {
        val ch = ace("1", "DAZN 1", 'a', "P")
        val hls = Channel("2", "Web", "https://example.org/x.m3u8", null, ChannelKind.HLS, "", 0)
        assertEquals(Route.INTERNAL, EngineRouting.route(EngineMode.AUTO, ch, true))
        assertEquals(Route.EXTERNAL, EngineRouting.route(EngineMode.AUTO, ch, false))      // Android < 6 / sin binario
        assertEquals(Route.INTERNAL, EngineRouting.route(EngineMode.INTERNAL, ch, true))
        assertEquals(Route.UNSUPPORTED, EngineRouting.route(EngineMode.INTERNAL, ch, false))
        assertEquals(Route.EXTERNAL, EngineRouting.route(EngineMode.EXTERNAL, ch, true))
        assertEquals(Route.NOT_ACE, EngineRouting.route(EngineMode.INTERNAL, hls, true))
    }

    @Test fun planUsesSameVariantAcrossProvidersClickedFirstNoDuplicates() {
        val a1 = ace("a1", "M LaLiga 1", 'a', "Uno")
        val a2 = ace("a2", "M LaLiga 2", 'b', "Uno")
        val b1 = ace("b1", "M LaLiga 1 FHD", 'c', "Dos")
        val b1dup = ace("b1d", "M LaLiga 1", 'C', "Tres")               // mismo Content ID (otra caja): una sola fuente
        val groups = ChannelGrouping.group(listOf(
            Triple("uno", "Uno", listOf(a1, a2)),
            Triple("dos", "Dos", listOf(b1)),
            Triple("tres", "Tres", listOf(b1dup))
        ))
        val plan = EngineRouting.plan(b1, groups)
        assertEquals(id('c'), plan.preferred)
        assertEquals(id('c'), plan.candidates.first().contentId)        // la pulsada, primero
        assertTrue(plan.candidates.any { it.contentId == id('a') })     // misma variante en otro proveedor
        assertTrue(plan.candidates.none { it.contentId == id('b') })    // "M LaLiga 2" no se mezcla
        assertEquals(2, plan.candidates.size)
        assertTrue(plan.channelKey.isNotBlank())
        assertEquals(plan.channelKey, EngineRouting.plan(a1, groups).channelKey)   // misma clave para recordar la buena
    }

    @Test fun planForEventUsesAllItsChannelsAndCapsCount() {
        val clicked = ace("x", "DAZN 1", 'a', "P")
        val others = ('b'..'m').map { c -> ace("e$c", "Canal $c", c, "P") }
        val plan = EngineRouting.plan(clicked, emptyList(), eventSources = others, max = 5)
        assertEquals(5, plan.candidates.size)
        assertEquals(id('a'), plan.candidates.first().contentId)
    }

    @Test fun higherResolutionAlwaysFirstUnlessUserPickedOne() {
        val sd = com.mivisor.app.engine.SourceCandidate(id('a'), "SD", quality = 1)
        val fhd = com.mivisor.app.engine.SourceCandidate(id('b'), "FHD", quality = 3)
        val hd = com.mivisor.app.engine.SourceCandidate(id('c'), "HD", quality = 2)
        val unknown = com.mivisor.app.engine.SourceCandidate(id('d'), "?")
        val hd2 = com.mivisor.app.engine.SourceCandidate(id('e'), "HD2", quality = 2)
        // La última buena (SD) ya no gana a una FHD; a igual resolución sí manda (HD2 antes que HD).
        val auto = com.mivisor.app.engine.SourceSelector(listOf(sd, unknown, hd, fhd, hd2), rememberedGood = id('e')).order
        assertEquals(listOf(id('b'), id('e'), id('c'), id('a'), id('d')), auto.map { it.contentId })
        // Recién fallada: al final aunque sea FHD.
        val failed = com.mivisor.app.engine.SourceSelector(listOf(sd, fhd, hd), recentlyFailed = setOf(id('b'))).order
        assertEquals(id('b'), failed.last().contentId)
        // Elegida a mano: primero esa; el resto por resolución.
        val picked = com.mivisor.app.engine.SourceSelector(listOf(fhd, sd, hd), preferred = id('a'), explicit = true).order
        assertEquals(listOf(id('a'), id('b'), id('c')), picked.map { it.contentId })
        // Preferida sin elegir a mano (tarjeta del canal): no adelanta a una de más resolución.
        val notPinned = com.mivisor.app.engine.SourceSelector(listOf(fhd, sd), preferred = id('a')).order
        assertEquals(id('b'), notPinned.first().contentId)
    }

    @Test fun groupPlanUsesFirstVariantBestResolutionFirst() {
        val sd = ace("s", "DAZN 1 SD", 'a', "Uno")
        val fhd = ace("f", "DAZN 1 FHD", 'b', "Dos")
        val hd = ace("h", "DAZN 1 720p", 'c', "Tres")
        val two = ace("t", "DAZN 2 4K", 'd', "Uno")
        val groups = ChannelGrouping.group(listOf(
            Triple("uno", "Uno", listOf(sd, two)), Triple("dos", "Dos", listOf(fhd)), Triple("tres", "Tres", listOf(hd))
        ))
        val g = groups.first { gr -> gr.sources.any { it.channel.id == "s" } }
        val plan = EngineRouting.planGroup(g)
        assertEquals(listOf(id('b'), id('c'), id('a')), plan.candidates.map { it.contentId })
        assertEquals("FHD", plan.candidates.first().qualityLabel)
        assertEquals(false, plan.explicit)
        // Misma clave que al elegir una fuente de esa variante: la sesión de la vista previa se reutiliza.
        assertEquals(plan.channelKey, EngineRouting.plan(sd, groups).channelKey)
    }

    @Test fun sourcesMenuCanGoToAnyIncludingPreviousAndFailed() {
        val c = ('a'..'d').map { com.mivisor.app.engine.SourceCandidate(id(it), "S$it") }
        val sel = com.mivisor.app.engine.SourceSelector(c)
        val s1 = sel.start() as com.mivisor.app.engine.SourceSelector.State.Trying
        val s2 = sel.onFailure(s1.generation, com.mivisor.app.engine.FailureKind.NO_PEERS) as com.mivisor.app.engine.SourceSelector.State.Trying
        sel.onVideo(s2.generation)
        assertEquals(com.mivisor.app.engine.SourceStatus.FAILED, sel.statusOf(0))
        assertEquals(com.mivisor.app.engine.SourceStatus.PLAYING, sel.statusOf(1))
        assertEquals(com.mivisor.app.engine.SourceStatus.PENDING, sel.statusOf(3))
        // Saltar a la 4.ª: la que se veía queda "cambiada a mano".
        val s4 = sel.choose(3) as com.mivisor.app.engine.SourceSelector.State.Trying
        assertEquals(3, s4.index)
        assertEquals(com.mivisor.app.engine.SourceStatus.LEFT, sel.statusOf(1))
        // Volver a la anterior que falló (reintento): deja de constar como fallida.
        val back = sel.choose(0) as com.mivisor.app.engine.SourceSelector.State.Trying
        assertEquals(0, back.index)
        assertEquals(com.mivisor.app.engine.SourceStatus.TRYING, sel.statusOf(0))
        // Si falla, sigue con la única no probada (la 3.ª), no con una ya vista.
        val next = sel.onFailure(back.generation, com.mivisor.app.engine.FailureKind.TIMEOUT) as com.mivisor.app.engine.SourceSelector.State.Trying
        assertEquals(2, next.index)
        assertEquals(sel.choose(2), sel.state)                              // elegir la activa no reinicia nada
    }
}
