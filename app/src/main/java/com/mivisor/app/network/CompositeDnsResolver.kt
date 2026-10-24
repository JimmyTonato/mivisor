package com.mivisor.app.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Resolución por etapas. Dentro de cada etapa los servidores compiten en paralelo y
 * gana la primera respuesta válida (antes eran secuenciales: hasta ~24 s de espera
 * en el peor caso; ahora ≈ 3,5 s + 6 s).
 *
 *  1. DNS clásico a 8.8.8.8 y 1.1.1.1 (UDP+EDNS0, TCP si se trunca).
 *  2. DNS-over-HTTPS de Google y Cloudflare (por si el puerto 53 está bloqueado).
 *
 * Un NXDOMAIN es definitivo: se informa de "código inexistente" sin probar más etapas.
 */
class CompositeDnsResolver(
    private val stages: List<List<DnsTxtResolver>> = listOf(
        listOf(PlainDnsResolver("8.8.8.8"), PlainDnsResolver("1.1.1.1")),
        listOf(DohResolver(DohResolver.GOOGLE), DohResolver(DohResolver.CLOUDFLARE))
    )
) : DnsTxtResolver {

    override suspend fun resolveTxt(host: String): List<String> {
        var lastError: Throwable? = null
        for (stage in stages) {
            val failures = mutableListOf<Throwable>()
            val records = race(stage, host, failures)
            if (records != null) return records
            failures.firstOrNull { it is CodeNotFoundException }?.let { throw it }
            lastError = failures.lastOrNull() ?: lastError
        }
        throw DnsUnavailableException(lastError)
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
