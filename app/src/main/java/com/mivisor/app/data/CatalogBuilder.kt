package com.mivisor.app.data

import com.mivisor.app.model.Catalog
import com.mivisor.app.model.Channel
import com.mivisor.app.model.ChannelKind
import com.mivisor.app.model.Section
import com.mivisor.app.model.StreamItem

/**
 * Convierte el árbol del JSON en filas para la TV. Se ejecuta UNA vez por carga y fuera
 * del hilo principal; la UI solo pinta el resultado.
 *
 *  - Canales sueltos de primer nivel → fila "Canales".
 *  - Cada grupo → una fila con sus canales directos; los subgrupos generan filas
 *    "Padre · Hijo".
 *  - Si un grupo tiene URL propia, también es reproducible y aparece el primero de su fila
 *    (antes esa URL se perdía).
 *  - Los ids son posicionales y únicos: nombres repetidos ya no provocan el crash de
 *    "Key was already used" en las listas perezosas.
 */
object CatalogBuilder {
    private const val MAX_DEPTH = 10

    fun build(items: List<StreamItem>): Catalog {
        val sections = ArrayList<Section>()
        val root = ArrayList<Channel>()
        var sectionIndex = 1
        var channelCount = 0

        fun channel(item: StreamItem, group: String?, sectionId: String, index: Int): Channel {
            val name = item.name?.trim().takeUnless { it.isNullOrEmpty() } ?: "Canal ${index + 1}"
            val url = item.url!!.trim()
            return Channel(
                id = "$sectionId:$index",
                name = name,
                url = url,
                group = group,
                kind = kindOf(url),
                initials = initialsOf(name),
                tint = (name.hashCode() and 0x7fffffff) % TINTS
            )
        }

        fun collect(group: StreamItem, parents: List<String>, depth: Int) {
            val groupName = group.name?.trim().takeUnless { it.isNullOrEmpty() }
                ?: group.ref?.trim().takeUnless { it.isNullOrEmpty() }
                ?: "Grupo"
            val path = parents + groupName
            val sectionId = "s${sectionIndex++}"
            val direct = ArrayList<Channel>()
            if (group.hasUrl) direct += channel(group, groupName, sectionId, direct.size)
            val nested = ArrayList<StreamItem>()
            for (child in group.subLinks.orEmpty()) {
                when {
                    child.hasChildren && depth < MAX_DEPTH -> nested += child
                    child.hasUrl -> direct += channel(child, groupName, sectionId, direct.size)
                }
            }
            if (direct.isNotEmpty()) {
                sections += Section(sectionId, path.joinToString(" · "), direct)
                channelCount += direct.size
            }
            nested.forEach { collect(it, path, depth + 1) }
        }

        for (item in items) {
            when {
                item.hasChildren -> collect(item, emptyList(), 0)
                item.hasUrl -> root += channel(item, null, "s0", root.size)
            }
        }
        if (root.isNotEmpty()) {
            sections.add(0, Section("s0", "Canales", root))
            channelCount += root.size
        }
        return Catalog(sections, channelCount)
    }

    const val TINTS = 8

    fun kindOf(url: String): ChannelKind {
        val lower = url.lowercase()
        val scheme = lower.substringBefore(':', "")
        return when (scheme) {
            "acestream" -> ChannelKind.ACE
            "magnet" -> ChannelKind.MAGNET
            "tvgram", "tg" -> ChannelKind.TELEGRAM
            "http", "https" -> {
                val path = lower.substringBefore('?')
                when {
                    "/ace/" in path || ":6878" in path -> ChannelKind.ACE
                    path.endsWith(".m3u8") || ".m3u8" in lower -> ChannelKind.HLS
                    path.endsWith(".m3u") -> ChannelKind.PLAYLIST
                    VIDEO_EXT.any { path.endsWith(it) } -> ChannelKind.VIDEO
                    else -> ChannelKind.WEB
                }
            }
            "rtmp", "rtsp", "udp", "rtp", "srt" -> ChannelKind.VIDEO
            else -> ChannelKind.OTHER
        }
    }

    private val VIDEO_EXT = listOf(".mp4", ".mkv", ".ts", ".avi", ".mov", ".webm", ".mpd")

    fun initialsOf(name: String): String {
        val words = name.split(' ', '-', '_', '.', '|', '/')
            .map { w -> w.filter { it.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }
        return when {
            words.isEmpty() -> "?"
            words.size == 1 -> words[0].take(2).uppercase()
            else -> (words[0].take(1) + words[1].take(1)).uppercase()
        }
    }
}
