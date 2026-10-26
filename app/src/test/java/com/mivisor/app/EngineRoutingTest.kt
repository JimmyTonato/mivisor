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
}
