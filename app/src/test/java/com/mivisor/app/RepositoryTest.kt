package com.mivisor.app

import com.mivisor.app.data.CatalogBuilder
import com.mivisor.app.data.NotCachedException
import com.mivisor.app.data.PayloadDecoder
import com.mivisor.app.data.RawCache
import com.mivisor.app.data.StreamRepository
import com.mivisor.app.data.StreamRepository.Mode
import com.mivisor.app.model.ChannelKind
import com.mivisor.app.model.StreamItem
import com.mivisor.app.network.CodeNotFoundException
import com.mivisor.app.network.DnsTxtResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

class RepositoryTest {

    private class MapResolver(val data: MutableMap<String, String>) : DnsTxtResolver {
        var online = true
        val calls = mutableListOf<String>()
        override suspend fun resolveTxt(host: String): List<String> {
            calls += host
            if (!online) throw IOException("offline")
            val code = host.removeSuffix(".elcano.top")
            return listOf(TestData.gzipB64(data[code] ?: throw CodeNotFoundException(host)))
        }
    }

    private var now = 1_000_000L

    private fun repo(resolver: DnsTxtResolver, cache: RawCache = RawCache(Files.createTempDirectory("mv").toFile())) =
        StreamRepository(resolver, PayloadDecoder(), cache, "elcano.top", freshTtlMs = 60_000, clock = { now })

    private val tree = mutableMapOf(
        "root" to """[{"name":"Directo","url":"https://a/1.m3u8"},
                      {"name":"Fútbol","type":"category","ref":"futbol"},
                      {"name":"Roto","type":"category","ref":"noexiste"},
                      {"name":"Bucle","type":"category","ref":"ciclo"}]""",
        "futbol" to """[{"name":"Liga","url":"acestream://liga"},{"name":"Copa","type":"category","ref":"copa"}]""",
        "copa" to """[{"name":"Final","url":"acestream://final"}]""",
        "ciclo" to """[{"name":"Vuelta","type":"category","ref":"root"},{"name":"C1","url":"https://c/1.mp4"}]"""
    )

    @Test fun resolvesReferencesRecursivelyWithCycleProtection() = runBlocking {
        val resolver = MapResolver(tree)
        val result = repo(resolver).load("root", Mode.NETWORK)
        val futbol = result.items.first { it.name == "Fútbol" }
        assertEquals("Liga", futbol.subLinks!![0].name)
        assertEquals("Final", futbol.subLinks!![1].subLinks!![0].name)
        // "noexiste" y el ciclo ciclo → root cuentan como fallos; root no se vuelve a pedir
        assertEquals(2, result.failedRefs)
        assertEquals(1, resolver.calls.count { it == "root.elcano.top" })
        assertFalse(result.offline)

        val catalog = CatalogBuilder.build(result.items, aceOnly = false)
        val titles = catalog.sections.map { it.title }
        assertEquals(listOf("Canales", "Fútbol", "Fútbol · Copa", "Bucle"), titles)
        assertEquals(4, catalog.channelCount)
        assertEquals(ChannelKind.ACE, catalog.sections[1].channels[0].kind)
    }

    @Test fun cacheOnlyAndOfflineFallback() = runBlocking {
        val resolver = MapResolver(tree)
        val repository = repo(resolver)
        try { repository.load("root", Mode.CACHE_ONLY); fail() } catch (e: NotCachedException) { /* ok */ }

        repository.load("root", Mode.NETWORK)
        val callsAfterFirst = resolver.calls.size
        val cached = repository.load("root", Mode.CACHE_ONLY)
        assertEquals(callsAfterFirst, resolver.calls.size)
        assertEquals(4, cached.items.size)
        assertTrue(repository.isFresh(cached.savedAt))

        // Fresco: PREFER_CACHE no vuelve a pedir lo que ya está en caché
        // (solo reintenta la referencia rota "noexiste")
        repository.load("root", Mode.PREFER_CACHE)
        assertEquals(listOf("noexiste.elcano.top"), resolver.calls.drop(callsAfterFirst))

        // Sin red y caducado: se usa la copia local y se marca offline
        now += 120_000
        resolver.online = false
        val offline = repository.load("root", Mode.NETWORK)
        assertTrue(offline.offline)
        assertEquals("Final", offline.items.first { it.name == "Fútbol" }.subLinks!![1].subLinks!![0].name)
        assertFalse(repository.isFresh(offline.savedAt))
    }

    @Test fun unknownCodeIsReported() = runBlocking {
        try { repo(MapResolver(tree)).load("nada", Mode.NETWORK); fail() } catch (e: CodeNotFoundException) { /* ok */ }
    }

    @Test fun catalogHandlesDuplicatesAndGroupUrls() {
        val items = listOf(
            StreamItem("Mismo", "https://x/1.m3u8"),
            StreamItem("Mismo", "https://x/1.m3u8"),
            StreamItem("Grupo con URL", "https://x/g.m3u8", subLinks = listOf(StreamItem("Hijo", "magnet:?x"))),
            StreamItem("Sin url"),
            StreamItem("Vacío", subLinks = emptyList())
        )
        val catalog = CatalogBuilder.build(items, aceOnly = false)
        val ids = catalog.sections.flatMap { s -> s.channels.map { it.id } } + catalog.sections.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(4, catalog.channelCount)
        assertEquals("Grupo con URL", catalog.sections[1].channels[0].name)
        assertEquals("GC", catalog.sections[1].channels[0].initials)
        assertEquals(ChannelKind.MAGNET, catalog.sections[1].channels[1].kind)
    }
}
