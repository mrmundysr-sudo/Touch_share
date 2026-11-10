package com.touchshare.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.touchshare.core.*
import java.net.Inet4Address
import java.net.NetworkInterface

/** Foreground service that keeps the share server alive while the app is in the background. */
class ShareService : Service() {
    private var server: ShareServer? = null
    private var remoteHost: RemoteHost? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> stopSession()
            ACTION_ALLOW -> decide(intent.getStringExtra(EXTRA_DEVICE), true)
            ACTION_DENY -> decide(intent.getStringExtra(EXTRA_DEVICE), false)
        }
        return START_NOT_STICKY
    }

    private fun start() {
        createChannels()
        enterForeground(sessionNotification("Starting…"))
        if (server != null) return
        val req = SessionHolder.takeStartRequest()
        if (req == null) { stopSession(); return }
        val (mode, uris, remote) = req
        if (remote) { startRemote(mode, uris); return }
        val ip = pickAddress()
        if (ip == null) {
            SessionHolder.fail("No Wi-Fi or hotspot network found. Turn on a hotspot, or join the same Wi-Fi as the other phone.")
            stopSession(); return
        }
        val token = TokenAuth.generate()
        val registry = DeviceRegistry(
            onPending = { d -> onDevicePending(d) },
            onDecision = { d, ok -> onDeviceDecided(d, ok) },
        )
        val s = ShareServer(
            mode, token,
            source = uris?.let { UriSource(applicationContext.contentResolver, it) },
            sink = if (mode == Mode.RECEIVE) DownloadsSink(applicationContext) else null,
            registry = registry,
            assets = AppAssets(applicationContext),
            onEvent = { e ->
                if (e is ShareEvent.UploadProgress) SessionHolder.setProgress(e.name, describe(e)) else SessionHolder.addLog(describe(e))
            },
        )
        try { s.start() } catch (e: Exception) {
            SessionHolder.fail("Could not start server: ${e.message}"); stopSession(); return
        }
        server = s
        acquireWakeLock()
        SessionHolder.begin(mode, NetworkPicker.shareUrl(ip.address, s.listeningPort, token), registry)
        notify(NOTIF_SESSION, sessionNotification(if (mode == Mode.SEND) "Sharing files" else "Ready to receive files"))
    }

    private fun onDevicePending(d: Device) {
        SessionHolder.addPending(d)
        if (SessionHolder.listener == null) notify(approvalId(d.id), approvalNotification(d)) // app not on screen
    }

    private fun onDeviceDecided(d: Device, ok: Boolean) {
        SessionHolder.removePending(d.id)
        cancel(approvalId(d.id))
        SessionHolder.addLog(if (ok) "Allowed ${d.ip}" else "Denied ${d.ip}")
    }

    private fun decide(id: String?, allow: Boolean) {
        if (id == null) return
        SessionHolder.decide(id, allow)
    }

    // ---- "Anywhere": the phone hosts a remote share through the hidden WebView ----
    private fun startRemote(mode: Mode, uris: List<Uri>?) {
        val url = RemoteConfig.serverUrl(applicationContext)
        if (url == null) { SessionHolder.fail("Set the Anywhere server address first."); stopSession(); return }
        lateinit var host: RemoteHost
        host = RemoteHost(applicationContext, object : RemoteHost.Listener {
            override fun onRoom(code: String, secret: String, link: String) {
                SessionHolder.beginRemote(mode, link, code) { ok ->
                    SessionHolder.removePending(SessionHolder.REMOTE_ID); cancel(approvalId(SessionHolder.REMOTE_ID))
                    SessionHolder.addLog(if (ok) "Allowed. Connecting…" else "Denied.")
                    host.decide(ok)
                }
                SessionHolder.addLog("Waiting for the other person…")
            }
            override fun onState(state: String, reason: String?, code: String?) {
                when (state) {
                    "connecting" -> SessionHolder.addLog("Making a direct connection…")
                    "failed" -> {
                        SessionHolder.addLog("Couldn't connect directly.")
                        SessionHolder.fail("Couldn't connect directly. This can happen on some networks. Try the same Wi-Fi or hotspot.")
                    }
                    "closed" -> if (reason != "closed") SessionHolder.addLog("The connection was lost.")
                }
            }
            override fun onApprove(code: String, typed: Boolean) {
                val d = Device(SessionHolder.REMOTE_ID, "Someone online", "Check that their screen shows $code" + if (typed) ". They typed the code instead of scanning." else "")
                SessionHolder.addPending(d)
                if (SessionHolder.listener == null) notify(approvalId(d.id), approvalNotification(d))
            }
            override fun onProgress(dir: String, name: String, done: Long, total: Long) {
                val pct = if (total > 0) (done * 100 / total).toInt() else 0
                SessionHolder.setProgress(name, (if (dir == "send") "Sending " else "Receiving ") + "$name $pct%")
            }
            override fun onFileDone(dir: String, name: String, ok: Boolean) {
                SessionHolder.addLog(if (!ok) "Failed $name" else if (dir == "send") "Sent $name" else "Received $name")
            }
            override fun onConnection(kind: String) {
                SessionHolder.addLog(when (kind) { "same-network" -> "Connected: same network."; "internet-direct" -> "Connected directly across the internet."; else -> "Connected ($kind)." })
            }
            override fun onDeclined() { SessionHolder.addLog("Declined. Waiting for the other person…") }
            override fun onRejected(reason: String) { SessionHolder.addLog("Turned away a visitor ($reason).") }
            override fun onError(message: String) { SessionHolder.addLog("Error: $message"); SessionHolder.fail(message) }
        })
        remoteHost = host
        acquireWakeLock()
        host.start(mode, uris, url)
        notify(NOTIF_SESSION, sessionNotification("Sharing from anywhere"))
    }

    private fun stopSession() {
        remoteHost?.stop(); remoteHost = null
        server?.stop(); server = null
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        SessionHolder.end()
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        remoteHost?.stop(); remoteHost = null
        server?.stop(); server = null
        wakeLock?.let { if (it.isHeld) it.release() }
        if (SessionHolder.active) SessionHolder.end()
        super.onDestroy()
    }

    // ---- helpers ----
    private fun describe(e: ShareEvent) = when (e) {
        is ShareEvent.DownloadStarted -> "Sending ${e.name}…"
        is ShareEvent.DownloadFinished -> "Sent ${e.name}"
        is ShareEvent.UploadStarted -> "Incoming ${e.name} (${Pages.humanSize(e.size)})"
        is ShareEvent.UploadProgress -> "Receiving ${e.name} ${if (e.total > 0) (e.received * 100 / e.total).toInt() else 0}%"
        is ShareEvent.Uploaded -> "Received ${e.savedAs} (${Pages.humanSize(e.size)})"
        is ShareEvent.Rejected -> "Blocked a request (${e.reason})"
        is ShareEvent.Failed -> "Failed ${e.name}: ${e.message}"
    }

    private fun pickAddress(): Iface? {
        val all = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp }.flatMap { ni ->
            ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { Iface(ni.name, it.hostAddress ?: "") }
        }
        return NetworkPicker.pick(all)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TouchShare::session").apply {
            setReferenceCounted(false); acquire(2 * 60 * 60 * 1000L) // safety cap: 2 hours
        }
    }

    private fun enterForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_SESSION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIF_SESSION, n)
    }

    private fun nm() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private fun notify(id: Int, n: Notification) = nm().notify(id, n)
    private fun cancel(id: Int) = nm().cancel(id)
    private fun approvalId(deviceId: String) = NOTIF_APPROVAL_BASE + (deviceId.hashCode() and 0xFFFF)

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        nm().createNotificationChannel(NotificationChannel(CH_SESSION, "Sharing session", NotificationManager.IMPORTANCE_LOW))
        nm().createNotificationChannel(NotificationChannel(CH_APPROVAL, "Device requests", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun builder(channel: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channel) else @Suppress("DEPRECATION") Notification.Builder(this)

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun serviceIntent(action: String, code: Int, device: String? = null): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, ShareService::class.java).setAction(action).also { if (device != null) it.putExtra(EXTRA_DEVICE, device) },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @Suppress("DEPRECATION")
    private fun sessionNotification(text: String): Notification = builder(CH_SESSION)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle("Touch Share")
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(openAppIntent())
        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", serviceIntent(ACTION_STOP, 1))
        .build()

    @Suppress("DEPRECATION")
    private fun approvalNotification(d: Device): Notification = builder(CH_APPROVAL)
        .setSmallIcon(android.R.drawable.stat_sys_warning)
        .setContentTitle("Allow this device?")
        .setContentText(if (d.id == SessionHolder.REMOTE_ID) "${d.ip} wants to connect. ${d.userAgent}" else "${d.ip} wants to connect to your Touch Share session")
        .setAutoCancel(true)
        .setContentIntent(openAppIntent())
        .addAction(android.R.drawable.ic_menu_add, "Allow", serviceIntent(ACTION_ALLOW, approvalId(d.id) + 1, d.id))
        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Deny", serviceIntent(ACTION_DENY, approvalId(d.id) + 2, d.id))
        .build()

    companion object {
        const val ACTION_START = "com.touchshare.app.START"
        const val ACTION_STOP = "com.touchshare.app.STOP"
        const val ACTION_ALLOW = "com.touchshare.app.ALLOW"
        const val ACTION_DENY = "com.touchshare.app.DENY"
        const val EXTRA_DEVICE = "device"
        private const val CH_SESSION = "session"
        private const val CH_APPROVAL = "approval"
        private const val NOTIF_SESSION = 1
        private const val NOTIF_APPROVAL_BASE = 1000
    }
}

/** Lets the web pages fetch the theme art bundled in the app (images only). */
private class AppAssets(private val ctx: Context) : StaticAssets {
    override fun open(name: String): java.io.InputStream? {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext != "webp" && ext != "png") return null
        return try { ctx.assets.open("art/$name") } catch (_: Exception) { null }
    }
}
