package com.mivisor.app.model

/** Modelos de presentación: inmutables, con ids únicos y estables para las listas perezosas. */

enum class ChannelKind(val label: String) {
    ACE("ACE"),
    HLS("HLS"),
    VIDEO("VÍDEO"),
    PLAYLIST("LISTA"),
    MAGNET("MAGNET"),
    TELEGRAM("TG"),
    WEB("WEB"),
    OTHER("LINK")
}

class Channel(
    /** Único dentro del catálogo (no depende del nombre: los JSON suelen repetir nombres). */
    val id: String,
    val name: String,
    val url: String,
    /** Grupo inmediato; se usa para recordar el reproductor por grupo. */
    val group: String?,
    val kind: ChannelKind,
    val initials: String,
    /** Índice de color precalculado para el monograma (evita trabajo en cada recomposición). */
    val tint: Int
)

class Section(
    val id: String,
    val title: String,
    val channels: List<Channel>
)

class Catalog(
    val sections: List<Section>,
    val channelCount: Int
) {
    val isEmpty: Boolean get() = channelCount == 0

    companion object {
        val EMPTY = Catalog(emptyList(), 0)
    }
}
