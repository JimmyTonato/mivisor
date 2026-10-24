package com.mivisor.app.network

interface DnsTxtResolver {
    /** Devuelve los registros TXT del host (cada registro ya concatenado). */
    suspend fun resolveTxt(host: String): List<String>
}

/** El dominio no existe (NXDOMAIN) o no tiene registros TXT: el código es incorrecto. */
class CodeNotFoundException(host: String) : Exception("El código no existe ($host)")

/** La respuesta UDP venía truncada (flag TC); hay que repetir por TCP. */
class TruncatedResponseException : Exception("Respuesta DNS truncada")

/** Ningún servidor DNS respondió. */
class DnsUnavailableException(cause: Throwable?) :
    Exception("Sin conexión con los servidores DNS", cause)
