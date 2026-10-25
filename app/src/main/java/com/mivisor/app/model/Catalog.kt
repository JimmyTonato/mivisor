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
    val tint: Int,
    /** Proveedor (nombre) del que viene; la agenda y "Canales" lo muestran en cada fuente. */
    val source: String? = null,
    /** Id del proveedor (código normalizado): para saber si ese proveedor necesita VPN. */
    val providerId: String? = null
) {
    /** Copia marcada con su proveedor; el id pasa a ser único entre proveedores ("proveedor/s1:0"). */
    fun withProvider(id: String, name: String) = Channel("$id/${this.id}", this.name, url, group, kind, initials, tint, name, id)
}

class Section(
    val id: String,
    val title: String,
    val channels: List<Channel>
)

/** Resumen de una importación (sin URLs ni Content IDs). */
data class ImportStats(
    /** Elementos leídos que podían ser canal (no cuentan los grupos sin enlace propio). */
    val entries: Int = 0,
    /** Canales Ace Stream válidos importados. */
    val valid: Int = 0,
    /** Entradas descartadas: separadores, títulos, webs, URL vacía u otros enlaces que no son Ace Stream. */
    val ignored: Int = 0,
    /** Nombres que cambiaron al normalizarlos. */
    val normalized: Int = 0
) {
    fun summary(): String =
        "Imported entries: $entries · Valid Ace Stream channels: $valid · Ignored non-AceStream entries: $ignored · Normalized names: $normalized"
}

class Catalog(
    val sections: List<Section>,
    val channelCount: Int,
    val stats: ImportStats = ImportStats()
) {
    val isEmpty: Boolean get() = channelCount == 0

    companion object {
        val EMPTY = Catalog(emptyList(), 0)
    }
}
