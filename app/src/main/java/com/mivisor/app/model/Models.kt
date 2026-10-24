package com.mivisor.app.model

/**
 * Elemento tal y como llega en el JSON del registro TXT (formato heredado de MyLinksPaste).
 *
 * Todos los campos son anulables a propósito: Gson no respeta la nulabilidad de Kotlin
 * y un `"name": null` en el JSON rompería un campo no anulable en tiempo de ejecución.
 * Los nombres de campo forman parte del formato: no renombrar (ver proguard-rules.pro).
 */
data class StreamItem(
    val name: String? = null,
    val url: String? = null,
    val type: String? = null,
    val ref: String? = null,
    val subLinks: List<StreamItem>? = null
) {
    val hasUrl: Boolean get() = !url.isNullOrBlank()
    val hasChildren: Boolean get() = !subLinks.isNullOrEmpty()
    val hasRef: Boolean get() = !ref.isNullOrBlank()
}

/** Historial persistido en DataStore. Mismos nombres de campo que Mi Visor v3 (compatibilidad). */
data class HistoryItem(
    val code: String = "",
    val title: String = "",
    val lastOpenedAt: Long = 0L,
    val type: String? = null
)

/** Categoría guardada (nombre + código). Mismos nombres de campo que Mi Visor v3. */
data class SourceProfile(
    val id: String = "",
    val code: String = "",
    val name: String = "",
    val order: Int = 0
)
