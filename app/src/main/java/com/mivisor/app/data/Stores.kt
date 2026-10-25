package com.mivisor.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.mivisor.app.model.HistoryItem
import com.mivisor.app.model.SourceProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// Mismos nombres de DataStore y clave que Mi Visor v3: el historial y las categorías se conservan.
private val Context.historyDataStore by preferencesDataStore(name = "history")
private val Context.sourceDataStore by preferencesDataStore(name = "sources")
private val ITEMS = stringPreferencesKey("items")

/** Gson compartido: crear instancias de Gson es caro en dispositivos lentos. */
internal val sharedGson: Gson by lazy { Gson() }

class HistoryStore(context: Context) {
    private val store = context.applicationContext.historyDataStore

    val items: Flow<List<HistoryItem>> = store.data
        .map { prefs -> read(prefs).sortedByDescending { it.lastOpenedAt } }
        .distinctUntilChanged()

    suspend fun add(item: HistoryItem) {
        store.edit { prefs ->
            val next = buildList {
                add(item)
                addAll(read(prefs).filterNot { it.code.equals(item.code, ignoreCase = true) })
            }.take(MAX_ITEMS)
            prefs[ITEMS] = sharedGson.toJson(next.toTypedArray())
        }
    }

    suspend fun remove(code: String) {
        store.edit { prefs ->
            prefs[ITEMS] = sharedGson.toJson(read(prefs).filterNot { it.code.equals(code, true) }.toTypedArray())
        }
    }

    suspend fun clear() {
        store.edit { it.remove(ITEMS) }
    }

    private fun read(prefs: Preferences): List<HistoryItem> = prefs[ITEMS]
        ?.let { json -> runCatching { sharedGson.fromJson(json, Array<HistoryItem?>::class.java) }.getOrNull() }
        ?.filterNotNull()
        ?.filter { !it.code.isNullOrBlank() }
        .orEmpty()

    private companion object {
        const val MAX_ITEMS = 40
    }
}

class SourceStore(context: Context) {
    private val store = context.applicationContext.sourceDataStore

    val items: Flow<List<SourceProfile>> = store.data
        .map { prefs -> read(prefs) }
        .distinctUntilChanged()

    /** Crea o renombra (el id es el código normalizado). */
    suspend fun upsert(name: String, code: String): SourceProfile {
        val normalized = Codes.normalize(code)
        var saved = SourceProfile(id = normalized, code = normalized, name = name.trim().ifBlank { normalized })
        store.edit { prefs ->
            val current = read(prefs)
            val index = current.indexOfFirst { it.id == normalized }
            val next = if (index >= 0) {
                current.toMutableList().also { it[index] = saved.copy(order = index) }
            } else {
                current + saved.copy(order = current.size)
            }
            saved = next.first { it.id == normalized }
            write(prefs, next)
        }
        return saved
    }

    suspend fun remove(id: String) {
        store.edit { prefs -> write(prefs, read(prefs).filterNot { it.id == id }) }
    }

    /** Mueve una categoría arriba (-1) o abajo (+1) en la barra lateral. */
    suspend fun move(id: String, delta: Int) {
        store.edit { prefs ->
            val list = read(prefs).toMutableList()
            val from = list.indexOfFirst { it.id == id }
            val to = from + delta
            if (from < 0 || to !in list.indices) return@edit
            list.add(to, list.removeAt(from))
            write(prefs, list)
        }
    }

    private fun write(prefs: androidx.datastore.preferences.core.MutablePreferences, list: List<SourceProfile>) {
        prefs[ITEMS] = sharedGson.toJson(list.mapIndexed { i, s -> s.copy(order = i) }.toTypedArray())
    }

    private fun read(prefs: Preferences): List<SourceProfile> = prefs[ITEMS]
        ?.let { json -> runCatching { sharedGson.fromJson(json, Array<SourceProfile?>::class.java) }.getOrNull() }
        ?.filterNotNull()
        ?.filter { !it.id.isNullOrBlank() && !it.code.isNullOrBlank() }
        ?.sortedBy { it.order }
        .orEmpty()
}

private val Context.playbackDataStore by preferencesDataStore(name = "playback_history")

/** Historial de reproducciones (evento, canal, proveedor y cuándo). Solo en el dispositivo. */
class PlaybackHistoryStore(context: Context) {
    private val store = context.applicationContext.playbackDataStore

    val items: Flow<List<com.mivisor.app.model.PlaybackEntry>> = store.data
        .map { prefs -> read(prefs) }
        .distinctUntilChanged()

    suspend fun add(entry: com.mivisor.app.model.PlaybackEntry) {
        store.edit { prefs ->
            prefs[ITEMS] = sharedGson.toJson(com.mivisor.app.model.PlaybackEntry.push(read(prefs), entry).toTypedArray())
        }
    }

    suspend fun remove(entry: com.mivisor.app.model.PlaybackEntry) {
        store.edit { prefs ->
            prefs[ITEMS] = sharedGson.toJson(read(prefs).filterNot { it.url == entry.url && it.eventId == entry.eventId }.toTypedArray())
        }
    }

    suspend fun clear() {
        store.edit { it.remove(ITEMS) }
    }

    private fun read(prefs: Preferences): List<com.mivisor.app.model.PlaybackEntry> = prefs[ITEMS]
        ?.let { json -> runCatching { sharedGson.fromJson(json, Array<com.mivisor.app.model.PlaybackEntry?>::class.java) }.getOrNull() }
        ?.filterNotNull()
        ?.filter { !it.url.isNullOrBlank() }
        ?.sortedByDescending { it.at }
        .orEmpty()
}
