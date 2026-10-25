package com.mivisor.app.model

/**
 * Una reproducción del historial: qué se vio (evento o canal), por qué canal, de qué proveedor y cuándo.
 * Campos anulables/por defecto: Gson no respeta la nulabilidad de Kotlin.
 */
data class PlaybackEntry(
    /** "Atlético de Madrid vs Deportivo" o el nombre del canal si se abrió directamente. */
    val title: String = "",
    val competition: String? = null,
    val channelName: String = "",
    val provider: String? = null,
    val providerId: String? = null,
    val url: String = "",
    val kind: String? = null,
    val eventId: String? = null,
    val at: Long = 0L
) {
    val isEvent: Boolean get() = !eventId.isNullOrBlank()

    fun toChannel(): Channel = Channel(
        id = "history/${url.hashCode()}",
        name = channelName.ifBlank { title },
        url = url,
        group = null,
        kind = runCatching { ChannelKind.valueOf(kind.orEmpty()) }.getOrDefault(com.mivisor.app.data.CatalogBuilder.kindOf(url)),
        initials = com.mivisor.app.data.CatalogBuilder.initialsOf(channelName.ifBlank { title }),
        tint = (channelName.hashCode() and 0x7fffffff) % com.mivisor.app.data.CatalogBuilder.TINTS,
        source = provider,
        providerId = providerId
    )

    companion object {
        const val MAX = 60

        /** Nueva reproducción arriba; la misma (mismo enlace y evento) no se repite. */
        fun push(list: List<PlaybackEntry>, entry: PlaybackEntry): List<PlaybackEntry> =
            (listOf(entry) + list.filterNot { it.url == entry.url && it.eventId == entry.eventId }).take(MAX)
    }
}

/** Contexto de lo que se va a reproducir (para el historial y para continuar tras preparar Ace/VPN). */
data class PlayRequest(
    val channel: Channel,
    val eventTitle: String? = null,
    val competition: String? = null,
    val eventId: String? = null,
    val forceChooser: Boolean = false
) {
    fun toEntry(now: Long) = PlaybackEntry(
        title = eventTitle ?: channel.name,
        competition = competition,
        channelName = channel.name,
        provider = channel.source,
        providerId = channel.providerId,
        url = channel.url,
        kind = channel.kind.name,
        eventId = eventId,
        at = now
    )
}

/** Título visible de un evento: "Local vs Visitante" o su título. */
fun AgendaEvent.displayTitle(): String =
    if (!home.isNullOrBlank() && !away.isNullOrBlank()) "$home vs $away" else title
