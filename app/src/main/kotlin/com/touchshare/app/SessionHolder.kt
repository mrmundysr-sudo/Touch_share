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
    const val REMOTE_ID = "remote"
    private val main = Handler(Looper.getMainLooper())

    @Volatile var listener: (() -> Unit)? = null

    var active = false; private set
    var mode: Mode? = null; private set
    var url: String? = null; private set
    var error: String? = null; private set
    var registry: DeviceRegistry? = null; private set
    var remote = false; private set                 // an "Anywhere" session (hidden WebView) instead of a local server
    var remoteCode: String? = null; private set
    private var remoteDecider: ((Boolean) -> Unit)? = null
    private val log = ArrayDeque<String>()
    private val pending = LinkedHashMap<String, Device>()
    private var startRequest: Triple<Mode, List<Uri>?, Boolean>? = null

    @Synchronized fun requestStart(m: Mode, uris: List<Uri>?, remote: Boolean = false) { startRequest = Triple(m, uris, remote); error = null }
    @Synchronized fun takeStartRequest() = startRequest.also { startRequest = null }

    @Synchronized fun begin(m: Mode, u: String, r: DeviceRegistry) {
        active = true; mode = m; url = u; registry = r; error = null; log.clear(); pending.clear(); changed()
    }

    @Synchronized fun beginRemote(m: Mode, link: String, code: String, decider: (Boolean) -> Unit) {
        active = true; mode = m; url = link; registry = null; remote = true; remoteCode = code; remoteDecider = decider
        error = null; log.clear(); pending.clear(); changed()
    }

    @Synchronized fun end() {
        active = false; mode = null; url = null; registry = null; remote = false; remoteCode = null; remoteDecider = null
        log.clear(); pending.clear(); changed()
    }

    /** The person tapped Allow or Deny, in the app or on the notification. */
    fun decide(id: String, allow: Boolean) {
        if (id == REMOTE_ID) { remoteDecider?.invoke(allow); return }
        val r = registry ?: return
        if (allow) r.allow(id) else r.deny(id)
    }

    @Synchronized fun fail(msg: String) { error = msg; changed() }
    private var progressKey: String? = null

    @Synchronized fun addLog(line: String) { progressKey = null; log.addFirst(line); while (log.size > 8) log.removeLast(); changed() }

    /** A running transfer updates one line in place instead of flooding the log. */
    @Synchronized fun setProgress(key: String, line: String) {
        if (progressKey == key && log.isNotEmpty()) log[0] = line else log.addFirst(line)
        progressKey = key
        while (log.size > 8) log.removeLast()
        changed()
    }
    @Synchronized fun addPending(d: Device) { pending[d.id] = d; changed() }
    @Synchronized fun removePending(id: String) { pending.remove(id); changed() }

    @Synchronized fun logLines(): List<String> = log.toList()
    @Synchronized fun pendingDevices(): List<Device> = pending.values.toList()

    private fun changed() { main.post { listener?.invoke() } }
}
