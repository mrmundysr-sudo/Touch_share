package com.touchshare.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.touchshare.core.*
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {
    private var server: ShareServer? = null
    private lateinit var qr: ImageView
    private lateinit var urlText: TextView
    private lateinit var status: TextView
    private lateinit var sendBtn: Button
    private lateinit var receiveBtn: Button
    private lateinit var stopBtn: Button
    private val log = ArrayDeque<String>()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#12151c")); gravity = Gravity.CENTER_HORIZONTAL
        }
        fun tv(text: String, size: Float = 16f) = TextView(this).apply {
            this.text = text; textSize = size; setTextColor(Color.parseColor("#e8ecf4"))
        }
        root.addView(tv("Touch Share", 26f))
        root.addView(tv("Connect both phones to the same hotspot or Wi-Fi, then share. The other phone just scans the QR code in its browser.", 14f))
        sendBtn = Button(this).apply { text = "Send files"; setOnClickListener { pickFiles() } }
        receiveBtn = Button(this).apply { text = "Receive files"; setOnClickListener { startSession(Mode.RECEIVE, null) } }
        stopBtn = Button(this).apply { text = "Stop sharing"; visibility = View.GONE; setOnClickListener { stopSession() } }
        qr = ImageView(this).apply { visibility = View.GONE; setBackgroundColor(Color.WHITE); setPadding(pad, pad, pad, pad) }
        urlText = tv("", 13f).apply { setTextIsSelectable(true) }
        status = tv("", 14f)
        listOf(sendBtn, receiveBtn, qr, urlText, stopBtn, status).forEach { root.addView(it) }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor("#12151c")); addView(root) })
    }

    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(i, 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != 1 || res != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { c -> for (i in 0 until c.itemCount) uris += c.getItemAt(i).uri } ?: data.data?.let { uris += it }
        if (uris.isNotEmpty()) startSession(Mode.SEND, uris)
    }

    private fun localAddress(): Iface? {
        val all = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp }.flatMap { ni ->
            ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { Iface(ni.name, it.hostAddress ?: "") }
        }
        return NetworkPicker.pick(all)
    }

    private fun startSession(mode: Mode, uris: List<Uri>?) {
        stopSession()
        val ip = localAddress()
        if (ip == null) { status.text = "No Wi-Fi or hotspot network found. Turn on a hotspot, or join the same Wi-Fi as the other phone."; return }
        val token = TokenAuth.generate()
        val s = ShareServer(
            mode, token,
            source = uris?.let { UriSource(contentResolver, it) },
            sink = if (mode == Mode.RECEIVE) DownloadsSink(this) else null,
            onEvent = { e -> runOnUiThread { note(e) } },
        )
        try { s.start() } catch (e: Exception) { status.text = "Could not start server: ${e.message}"; return }
        server = s
        val url = NetworkPicker.shareUrl(ip.address, s.listeningPort, token)
        qr.setImageBitmap(Qr.bitmap(url)); qr.visibility = View.VISIBLE
        qr.layoutParams = LinearLayout.LayoutParams(640, 640)
        urlText.text = url; stopBtn.visibility = View.VISIBLE
        sendBtn.isEnabled = false; receiveBtn.isEnabled = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        log.clear()
        status.text = if (mode == Mode.SEND) "Sharing ${uris!!.size} file(s). Scan the code on the other phone."
        else "Ready to receive. Saved to Downloads/TouchShare."
    }

    private fun note(e: ShareEvent) {
        val line = when (e) {
            is ShareEvent.DownloadStarted -> "Sending ${e.name}…"
            is ShareEvent.DownloadFinished -> "Sent ${e.name}"
            is ShareEvent.Uploaded -> "Received ${e.savedAs} (${Pages.humanSize(e.size)})"
            is ShareEvent.Rejected -> "Blocked a request (${e.reason})"
            is ShareEvent.Failed -> "Failed ${e.name}: ${e.message}"
        }
        log.addFirst(line); while (log.size > 8) log.removeLast()
        status.text = log.joinToString("\n")
    }

    private fun stopSession() {
        server?.stop(); server = null
        qr.visibility = View.GONE; urlText.text = ""; stopBtn.visibility = View.GONE
        sendBtn.isEnabled = true; receiveBtn.isEnabled = true
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() { server?.stop(); super.onDestroy() }
}
