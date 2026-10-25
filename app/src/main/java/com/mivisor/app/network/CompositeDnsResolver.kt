package com.mivisor.app.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Resolución IGUAL que MyLinksPaste 4.6 (MainActivity.consultarDatos, revisado en su bytecode):
 *
 *  1. DNS clásico a 8.8.8.8 (Google) y 1.1.1.1 (Cloudflare). La app antigua pregunta a LOS DOS y junta sus
 *     respuestas (primero las de Google, luego las de Cloudflare, sin repetidos). Así, si cada servidor tiene en
 *     caché una versión distinta de la lista (pasa justo después de que el dueño la actualice), se ven las dos y
 *     después se elige igual que la app antigua ([com.mivisor.app.data.PayloadDecoder]).
 *     Antes MiVisor se quedaba con el que contestara primero → podía usar otra versión → otros IDs.
 *  2. Solo si ninguno da datos: DNS-over-HTTPS (Google y Cloudflare) por si el puerto 53 está bloqueado.
 *
 * Un NXDOMAIN es definitivo: se informa de "código inexistente" sin probar más etapas.
 */
class CompositeDnsResolver(
    private val stages: List<List<DnsTxtResolver>> = listOf(
        listOf(PlainDnsResolver("8.8.8.8"), PlainDnsResolver("1.1.1.1")),
        listOf(DohResolver(DohResolver.GOOGLE), DohResolver(DohResolver.CLOUDFLARE))
    ),
    /** Etapas en las que se espera a todos y se juntan las respuestas (la 0, como la app antigua). */
    private val mergeStages: Set<Int> = setOf(0),
    private val names: List<List<String>> = listOf(listOf("Google", "Cloudflare"), listOf("Google DoH", "Cloudflare DoH"))
) : DnsTxtResolver {

    /** Último resumen por host ("Google 2 · Cloudflare 1 → 3 versiones"), para el diagnóstico. */
    private val summaries = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun summary(host: String): String? = summaries[host]

    override suspend fun resolveTxt(host: String): List<String> {
        var lastError: Throwable? = null
        for ((index, stage) in stages.withIndex()) {
            if (index in mergeStages) {
                val failures = mutableListOf<Throwable>()
                val merged = mergeAll(stage, host, failures, names.getOrNull(index).orEmpty())
                if (merged != null) return merged
                failures.firstOrNull { it is CodeNotFoundException }?.let { throw it }
                lastError = failures.lastOrNull() ?: lastError
                continue
            }
            val failures = mutableListOf<Throwable>()
            val records = race(stage, host, failures)
            if (records != null) {
                summaries[host] = "${names.getOrNull(index)?.joinToString("/") ?: "DoH"}: ${records.size} versión(es) (respaldo por HTTPS)"
                return records
            }
            failures.firstOrNull { it is CodeNotFoundException }?.let { throw it }
            lastError = failures.lastOrNull() ?: lastError
        }
        throw DnsUnavailableException(lastError)
    }

    /** Pregunta a todos a la vez, espera a todos y junta las respuestas en el orden de [resolvers], sin repetidos. */
    private suspend fun mergeAll(
        resolvers: List<DnsTxtResolver>,
        host: String,
        failures: MutableList<Throwable>,
        labels: List<String>
    ): List<String>? = coroutineScope {
        val jobs = resolvers.map { resolver ->
            async {
                try {
                    Result.success(resolver.resolveTxt(host))
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Result.failure(t)
                }
            }
        }
        val results = jobs.map { it.await() }
        results.forEach { r -> r.exceptionOrNull()?.let(failures::add) }
        val merged = results.flatMap { it.getOrNull().orEmpty() }.distinct()
        if (merged.isEmpty()) return@coroutineScope null
        val parts = results.mapIndexed { i, r -> "${labels.getOrElse(i) { "#$i" }} ${r.getOrNull()?.size ?: "sin respuesta"}" }
        val differ = results.mapNotNull { it.getOrNull()?.toSet() }.distinct().size > 1
        summaries[host] = parts.joinToString(" · ") + " → ${merged.size} versión(es)" + if (differ) " (los servidores NO coinciden)" else ""
        merged
    }

    private suspend fun race(
        resolvers: List<DnsTxtResolver>,
        host: String,
        failures: MutableList<Throwable>
    ): List<String>? = coroutineScope {
        val results = Channel<Result<List<String>>>(resolvers.size)
        val jobs = resolvers.map { resolver ->
            launch {
                val result = try {
                    Result.success(resolver.resolveTxt(host))
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Result.failure(t)
                }
                results.trySend(result)
            }
        }
        var winner: List<String>? = null
        for (i in resolvers.indices) {
            val result = results.receive()
            val value = result.getOrNull()
            if (value != null && value.isNotEmpty()) {
                winner = value
                break
            }
            result.exceptionOrNull()?.let(failures::add)
        }
        // Cancela a los perdedores: closeOnCancel cierra sus sockets al instante.
        jobs.forEach { it.cancel() }
        winner
    }
}
