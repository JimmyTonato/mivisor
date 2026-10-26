package com.mivisor.app.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/** ¿Hay una VPN activa en la TV (WARP u otra)? Solo lectura del estado de red (ACCESS_NETWORK_STATE). */
object NetState {
    fun vpnActive(context: Context): Boolean? = runCatching {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        if (Build.VERSION.SDK_INT >= 23) {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } else {
            @Suppress("DEPRECATION")
            cm.allNetworks.any { cm.getNetworkInfo(it)?.type == ConnectivityManager.TYPE_VPN && cm.getNetworkInfo(it)?.isConnected == true }
        }
    }.getOrNull()

    /**
     * ¿Tiene la TV Internet de verdad (red validada)? null = no se sabe (entonces no se asume que no).
     * Sirve para no culpar a una fuente cuando lo que falla es la conexión del dispositivo.
     */
    fun online(context: Context): Boolean? = runCatching {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        if (Build.VERSION.SDK_INT >= 23) {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }.getOrNull()
}
