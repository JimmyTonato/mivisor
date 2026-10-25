package com.mivisor.app.data

import com.mivisor.app.model.StreamItem
import com.mivisor.app.network.CodeNotFoundException
import com.mivisor.app.network.DnsTxtResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class NotCachedException : Exception("Sin copia local")

/**
 * Carga un código y resuelve sus referencias (`type: category` + `ref`) de forma recursiva.
 *
 * Diferencias con Mi Visor v3:
 *  - Protección real contra ciclos (A → B → A) por camino, no solo por profundidad.
 *  - Referencias resueltas en paralelo (máx. 4 a la vez) y deduplicadas dentro de la carga.
 *  - Modo "solo caché" para pintar al instante y refrescar después (stale-while-revalidate).
 *  - Informa de cuántas subcategorías fallaron en vez de ocultarlo.
 */
class StreamRepository(
    private val resolver: DnsTxtResolver,
    private val decoder: PayloadDecoder,
    private val cache: RawCache,
    private val domain: String,
    private val freshTtlMs: Long = TimeUnit.MINUTES.toMillis(10),
    private val clock: () -> Long = System::currentTimeMillis
) {
    enum class Mode { CACHE_ONLY, PREFER_CACHE, NETWORK }

    class LoadResult(
        val items: List<StreamItem>,
        /** Momento de los datos más antiguos usados (raíz o referencias). */
        val savedAt: Long,
        /** Algún dato es una copia local porque la red falló. */
        val offline: Boolean,
        val failedRefs: Int,
        /** Cómo se obtuvo la lista raíz: servidores DNS y versión elegida (diagnóstico; sin datos del código). */
        val sourceInfo: String? = null
    )

    fun isFresh(savedAt: Long): Boolean = clock() - savedAt in 0..freshTtlMs

    suspend fun load(code: String, rootMode: Mode, refMode: Mode = rootMode): LoadResult = supervisorScope {
        // supervisorScope: si falla una referencia (async), no se cancela toda la carga.
        val normalized = Codes.normalize(code)
        val session = Session(this, refMode)
        val root = fetch(normalized, rootMode)
        session.track(root)
        val items = expand(root.items, setOf(normalized), 0, session)
        LoadResult(items, session.oldest, session.offline, session.failedRefs, root.info)
    }

    fun clearCache() = cache.clear()

    // ---------------------------------------------------------------------------------

    private class Fetched(val items: List<StreamItem>, val savedAt: Long, val offline: Boolean, val info: String? = null)

    private inner class Session(val scope: CoroutineScope, val refMode: Mode) {
        val gate = Semaphore(4)
        val lock = Mutex()
        val inflight = HashMap<String, Deferred<Fetched>>()
        @Volatile var oldest = Long.MAX_VALUE
        @Volatile var offline = false
        @Volatile var failedRefs = 0

        @Synchronized fun track(f: Fetched) {
            if (f.savedAt < oldest) oldest = f.savedAt
            if (f.offline) offline = true
        }

        @Synchronized fun fail() { failedRefs++ }

        suspend fun fetchRef(code: String): Fetched {
            val deferred = lock.withLock {
                inflight.getOrPut(code) {
                    scope.async { gate.withPermit { fetch(code, refMode) } }
                }
            }
            return deferred.await()
        }
    }

    private suspend fun fetch(code: String, mode: Mode): Fetched {
        val cached = withContext(Dispatchers.IO) { cache.read(code) }
        val cachedItems: List<StreamItem>? by lazy(LazyThreadSafetyMode.NONE) {
            cached?.let { runCatching { decoder.decode(it.records) }.getOrNull() }
        }

        if (mode == Mode.CACHE_ONLY) {
            val items = cachedItems ?: throw NotCachedException()
            return Fetched(items, cached!!.savedAt, offline = false)
        }
        if (mode == Mode.PREFER_CACHE && cached != null && isFresh(cached.savedAt)) {
            cachedItems?.let { return Fetched(it, cached.savedAt, offline = false) }
        }

        return try {
            val host = Codes.hostFor(code, domain)
            val records = resolver.resolveTxt(host)
            val decoded = withContext(Dispatchers.Default) { decoder.decodeDetailed(records) }
            val now = clock()
            withContext(Dispatchers.IO) { cache.write(code, records, now) }
            val dns = (resolver as? com.mivisor.app.network.CompositeDnsResolver)?.summary(host)
            Fetched(decoded.items, now, offline = false, info = listOfNotNull(dns, decoded.summary()).joinToString(" · "))
        } catch (e: CancellationException) {
            throw e
        } catch (e: CodeNotFoundException) {
            throw e
        } catch (e: Exception) {
            val fallback = cachedItems ?: throw e
            Fetched(fallback, cached!!.savedAt, offline = true)
        }
    }

    private suspend fun expand(
        items: List<StreamItem>,
        path: Set<String>,
        depth: Int,
        session: Session
    ): List<StreamItem> {
        if (items.none { it.hasRef || it.hasChildren }) return items
        val results = arrayOfNulls<StreamItem>(items.size)
        coroutineScope {
            items.forEachIndexed { index, item ->
                if (!item.hasRef && !item.hasChildren) {
                    results[index] = item
                } else {
                    launch { results[index] = expandItem(item, path, depth, session) }
                }
            }
        }
        return results.map { it!! }
    }

    private suspend fun expandItem(
        item: StreamItem,
        path: Set<String>,
        depth: Int,
        session: Session
    ): StreamItem {
        if (item.hasChildren) {
            return item.copy(subLinks = expand(item.subLinks.orEmpty(), path, depth + 1, session))
        }
        val ref = Codes.normalizeOrNull(item.ref)
        if (ref == null || ref in path || depth >= MAX_DEPTH) {
            session.fail()
            return item.copy(subLinks = null)
        }
        return try {
            val fetched = session.fetchRef(ref)
            session.track(fetched)
            val children = expand(fetched.items, path + ref, depth + 1, session)
            item.copy(name = item.name ?: ref, subLinks = children)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            session.fail()
            item.copy(subLinks = null)
        }
    }

    private companion object {
        const val MAX_DEPTH = 6
    }
}
