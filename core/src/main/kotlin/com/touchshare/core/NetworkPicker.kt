package com.touchshare.core

data class Iface(val name: String, val address: String)

/** Chooses the address to advertise: hotspot/Wi-Fi interfaces first, never loopback or cellular. */
object NetworkPicker {
    private val preferred = listOf("ap", "swlan", "wlan", "eth", "p2p")
    private val ignored = listOf("rmnet", "ccmni", "lo", "dummy", "tun", "v4-")

    fun pick(candidates: List<Iface>): Iface? {
        val usable = candidates.filter { c ->
            isPrivateV4(c.address) && ignored.none { c.name.startsWith(it) }
        }
        for (p in preferred) usable.firstOrNull { it.name.startsWith(p) }?.let { return it }
        return usable.firstOrNull()
    }

    fun isPrivateV4(a: String): Boolean {
        val p = a.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
    }

    fun shareUrl(ip: String, port: Int, token: String) = "http://$ip:$port/?t=$token"
}
