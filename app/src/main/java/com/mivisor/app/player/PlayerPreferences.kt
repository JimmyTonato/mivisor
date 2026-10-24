package com.mivisor.app.player

import android.app.ActivityManager
import android.content.Context

/**
 * Preferencias en SharedPreferences "StreamsPrefs" con las mismas claves que MyLinksPaste
 * y Mi Visor v3 (c_app_sel_e, c_ace_e, app_n_…), así se conservan los ajustes.
 */
class PlayerPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("StreamsPrefs", Context.MODE_PRIVATE)
    private val lowRam = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
        ?.isLowRamDevice == true

    var selectorEnabled: Boolean
        get() = prefs.getBoolean("c_app_sel_e", true)
        set(value) = prefs.edit().putBoolean("c_app_sel_e", value).apply()

    var customAceEnabled: Boolean
        get() = prefs.getBoolean("c_ace_e", false)
        set(value) = prefs.edit().putBoolean("c_ace_e", value).apply()

    var customAceTemplate: String
        get() = prefs.getString("c_ace_t", null).orEmpty()
        set(value) = prefs.edit().putString("c_ace_t", value).apply()

    var customMagnetEnabled: Boolean
        get() = prefs.getBoolean("c_mag_e", false)
        set(value) = prefs.edit().putBoolean("c_mag_e", value).apply()

    var customMagnetTemplate: String
        get() = prefs.getString("c_mag_t", null).orEmpty()
        set(value) = prefs.edit().putString("c_mag_t", value).apply()

    /** Animaciones de foco. Por defecto desactivadas en dispositivos de poca RAM. */
    var animations: Boolean
        get() = prefs.getBoolean("ui_anim", !lowRam)
        set(value) = prefs.edit().putBoolean("ui_anim", value).apply()

    fun preferredPackage(name: String?, group: String?, scheme: String?): String? = listOfNotNull(
        name?.let { prefs.getString("app_n_$it", null) },
        group?.let { prefs.getString("app_g_$it", null) },
        scheme?.let { prefs.getString("app_s_$it", null) }
    ).firstOrNull { it.isNotBlank() }

    fun remember(packageName: String, name: String?, group: String?, scheme: String?, scope: RememberScope) {
        val key = when (scope) {
            RememberScope.ONCE -> return
            RememberScope.CHANNEL -> name?.let { "app_n_$it" }
            RememberScope.GROUP -> group?.let { "app_g_$it" }
            RememberScope.PROTOCOL -> scheme?.let { "app_s_$it" }
        } ?: return
        prefs.edit().putString(key, packageName).apply()
    }

    fun forgetChannel(name: String) {
        prefs.edit().remove("app_n_$name").apply()
    }

    fun clearPreferredApps() {
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith("app_n_") || it.startsWith("app_g_") || it.startsWith("app_s_") }
            .forEach(editor::remove)
        editor.apply()
    }
}

enum class RememberScope(val label: String) {
    ONCE("Solo esta vez"),
    CHANNEL("Siempre en este canal"),
    GROUP("Siempre en este grupo"),
    PROTOCOL("Siempre para este tipo de enlace")
}
