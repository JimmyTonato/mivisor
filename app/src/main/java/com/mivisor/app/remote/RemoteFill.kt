package com.mivisor.app.remote

/**
 * Qué hace el formulario con el texto llegado del móvil: lo que hubiera en el campo se SUSTITUYE
 * por el texto recibido (sin espacios ni saltos de línea al principio/final). No guarda nada:
 * el usuario sigue teniendo que pulsar Guardar/Abrir.
 */
object RemoteFill {
    fun apply(@Suppress("UNUSED_PARAMETER") current: String, received: String): String = received.trim()
}
