package com.mivisor.app.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

/** Interfaces de red del dispositivo, marcando cuáles son Wi-Fi/Ethernet y cuáles VPN (WARP) según Android. */
object AndroidLan {
    fun candidates(context: Context): List<NetCandidate> {
        val transports = HashMap<String, NetCandidate.Transport>()
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION")
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                val name = cm.getLinkProperties(network)?.interfaceName ?: continue
                transports[name] = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetCandidate.Transport.VPN
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetCandidate.Transport.ETHERNET
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetCandidate.Transport.WIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetCandidate.Transport.CELLULAR
                    else -> NetCandidate.Transport.UNKNOWN
                }
            }
        }
        val out = ArrayList<NetCandidate>()
        runCatching {
            for (iface in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
                for (addr in iface.inetAddresses.toList()) {
                    if (addr !is Inet4Address) continue
                    out += NetCandidate(
                        interfaceName = iface.name,
                        address = addr.hostAddress ?: continue,
                        ipv4 = true,
                        loopback = addr.isLoopbackAddress || iface.isLoopback,
                        up = iface.isUp,
                        pointToPoint = iface.isPointToPoint,
                        virtual = iface.isVirtual,
                        transport = transports[iface.name] ?: NetCandidate.Transport.UNKNOWN
                    )
                }
            }
        }
        return out
    }

    fun pick(context: Context): NetCandidate? = LanAddressPicker.choose(candidates(context))
}
