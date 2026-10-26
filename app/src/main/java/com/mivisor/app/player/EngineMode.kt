package com.mivisor.app.player

/** Motor para los enlaces Ace Stream (Ajustes → Reproductor). */
enum class EngineMode(val id: String, val label: String, val help: String) {
    AUTO("auto", "Automático (recomendado)", "Motor interno primero; si no reproduce, Ace Stream externo (solo al pulsar Ver)"),
    INTERNAL("internal", "Interno", "Solo el motor de MiVisor. Si falla, puedes reintentar o cambiar de fuente"),
    EXTERNAL("external", "Ace Stream externo", "Abre la app de Ace Stream instalada (sin preview dentro de MiVisor)");

    companion object {
        fun of(id: String?) = values().firstOrNull { it.id == id } ?: AUTO
    }
}
