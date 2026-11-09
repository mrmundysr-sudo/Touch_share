package com.touchshare.core

enum class DeviceState { PENDING, ALLOWED, DENIED }

data class Device(val id: String, val ip: String, val userAgent: String)

/**
 * Tracks which browsers/phones may use a session. A device is a browser cookie (random id), not an IP,
 * so two phones behind one address are approved separately. The host decides; denied IPs stay blocked.
 */
class DeviceRegistry(
    private val maxPending: Int = 5,
    private val onPending: (Device) -> Unit = {},
    private val onDecision: (Device, Boolean) -> Unit = { _, _ -> },
) {
    private val devices = LinkedHashMap<String, Device>()
    private val states = HashMap<String, DeviceState>()
    private val deniedIps = HashSet<String>()

    @Synchronized fun state(id: String): DeviceState? = states[id]

    @Synchronized fun isIpDenied(ip: String) = ip in deniedIps

    @Synchronized fun pending(): List<Device> = devices.values.filter { states[it.id] == DeviceState.PENDING }

    /** Registers a new pending device, or returns null when too many are already waiting. */
    fun register(ip: String, userAgent: String): Device? {
        val d: Device
        synchronized(this) {
            if (pending().size >= maxPending) return null
            d = Device(TokenAuth.generate(), ip, userAgent.take(120))
            devices[d.id] = d
            states[d.id] = DeviceState.PENDING
        }
        onPending(d)
        return d
    }

    fun allow(id: String): Boolean = decide(id, true)
    fun deny(id: String): Boolean = decide(id, false)

    private fun decide(id: String, allowed: Boolean): Boolean {
        val d: Device
        synchronized(this) {
            if (states[id] != DeviceState.PENDING) return false
            d = devices[id] ?: return false
            states[id] = if (allowed) DeviceState.ALLOWED else DeviceState.DENIED
            if (!allowed) deniedIps += d.ip
        }
        onDecision(d, allowed)
        return true
    }
}
