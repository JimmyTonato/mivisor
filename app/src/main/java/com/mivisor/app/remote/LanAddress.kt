package com.mivisor.app.remote

/**
 * Elección de la IP del televisor que el móvil puede alcanzar en la red local.
 *
 * Lo importante: con WARP/VPN activa, el QR NO debe llevar la IP del túnel (WARP usa p. ej. 172.16.0.2 en tun0,
 * que también "parece" privada). Por eso se descartan interfaces VPN/túnel por tipo y por nombre, y se prefiere
 * la red Wi-Fi/Ethernet que Android marca como tal (ConnectivityManager, sin TRANSPORT_VPN).
 */
data class NetCandidate(
    val interfaceName: String,
    val address: String,
    val ipv4: Boolean = true,
    val loopback: Boolean = false,
    val up: Boolean = true,
    val pointToPoint: Boolean = false,
    val virtual: Boolean = false,
    val transport: Transport = Transport.UNKNOWN
) {
    enum class Transport { ETHERNET, WIFI, VPN, CELLULAR, UNKNOWN }
}

object LanAddressPicker {
    private val TUNNEL_NAMES = Regex("^(tun|tap|ppp|wg|ipsec|utun|warp|cloudflare|nordlynx|zt|tailscale|rmnet|ccmni|pdp|dummy|lo|p2p|aware)", RegexOption.IGNORE_CASE)
    private val LAN_NAMES = Regex("^(eth|wlan|en|wl|br|ap)", RegexOption.IGNORE_CASE)

    /** Mejor candidata o null (sin red local válida). */
    fun choose(candidates: List<NetCandidate>): NetCandidate? = candidates
        .filter { usable(it) }
        .maxByOrNull { score(it) }

    fun usable(c: NetCandidate): Boolean {
        if (!c.up || c.loopback || !c.ipv4 || c.pointToPoint || c.virtual) return false
        if (c.transport == NetCandidate.Transport.VPN || c.transport == NetCandidate.Transport.CELLULAR) return false
        if (TUNNEL_NAMES.containsMatchIn(c.interfaceName)) return false
        val o = octets(c.address) ?: return false
        if (o[0] == 127 || o[0] == 0 || (o[0] == 169 && o[1] == 254) || o[0] >= 224) return false
        return true
    }

    private fun score(c: NetCandidate): Int {
        var s = 0
        if (c.transport == NetCandidate.Transport.ETHERNET) s += 40
        if (c.transport == NetCandidate.Transport.WIFI) s += 35
        if (LAN_NAMES.containsMatchIn(c.interfaceName)) s += 20
        if (isPrivate(c.address)) s += 10
        return s
    }

    fun isPrivate(address: String): Boolean {
        val o = octets(address) ?: return false
        return o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168)
    }

    private fun octets(address: String): List<Int>? =
        address.split('.').takeIf { it.size == 4 }?.map { it.toIntOrNull() ?: return null }?.takeIf { l -> l.all { it in 0..255 } }
}
