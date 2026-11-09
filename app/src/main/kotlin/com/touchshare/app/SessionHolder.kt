package com.touchshare.app

import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.touchshare.core.Device
import com.touchshare.core.DeviceRegistry
import com.touchshare.core.Mode

/**
 * In-process state of the current share session. The service owns the server; the activity only reads
 * this and renders it, so the session survives the screen closing.
 */
object SessionHolder {
    private val main = Handler(Looper.getMainLooper())

    @Volatile var listener: (() -> Unit)? = null

    var active = false; private set
    var mode: Mode? = null; private set
    var url: String? = null; private set
    var error: String? = null; private set
    var registry: DeviceRegistry? = null; private set
    private val log = ArrayDeque<String>()
    private val pending = LinkedHashMap<String, Device>()
    private var startRequest: Pair<Mode, List<Uri>?>? = null

    @Synchronized fun requestStart(m: Mode, uris: List<Uri>?) { startRequest = m to uris; error = null }
    @Synchronized fun takeStartRequest() = startRequest.also { startRequest = null }

    @Synchronized fun begin(m: Mode, u: String, r: DeviceRegistry) {
        active = true; mode = m; url = u; registry = r; error = null; log.clear(); pending.clear(); changed()
    }

    @Synchronized fun end() {
        active = false; mode = null; url = null; registry = null; log.clear(); pending.clear(); changed()
    }

    @Synchronized fun fail(msg: String) { error = msg; changed() }
    @Synchronized fun addLog(line: String) { log.addFirst(line); while (log.size > 8) log.removeLast(); changed() }
    @Synchronized fun addPending(d: Device) { pending[d.id] = d; changed() }
    @Synchronized fun removePending(id: String) { pending.remove(id); changed() }

    @Synchronized fun logLines(): List<String> = log.toList()
    @Synchronized fun pendingDevices(): List<Device> = pending.values.toList()

    private fun changed() { main.post { listener?.invoke() } }
}
