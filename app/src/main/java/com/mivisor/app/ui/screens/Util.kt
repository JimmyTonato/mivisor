package com.mivisor.app.ui.screens

fun relativeTime(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = ((now - timestamp) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> "ahora mismo"
        minutes < 60 -> "hace $minutes min"
        minutes < 60 * 24 -> "hace ${minutes / 60} h"
        minutes < 60 * 48 -> "ayer"
        else -> "hace ${minutes / (60 * 24)} d"
    }
}
