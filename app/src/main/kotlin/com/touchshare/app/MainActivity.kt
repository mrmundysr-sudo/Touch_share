package com.touchshare.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.touchshare.core.Device
import com.touchshare.core.Mode

class MainActivity : Activity() {
    private lateinit var qr: ImageView
    private lateinit var urlText: TextView
    private lateinit var status: TextView
    private lateinit var sendBtn: Button
    private lateinit var receiveBtn: Button
    private lateinit var stopBtn: Button
    private var qrFor: String? = null
    private var qrBitmap: Bitmap? = null
    private var dialog: AlertDialog? = null
    private var dialogFor: String? = null

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
        root.addView(tv("Connect both phones to the same hotspot or Wi-Fi, then share. The other phone just scans the QR code in its browser. You approve each new device.", 14f))
        sendBtn = Button(this).apply { text = "Send files"; setOnClickListener { pickFiles() } }
        receiveBtn = Button(this).apply { text = "Receive files"; setOnClickListener { startSession(Mode.RECEIVE, null) } }
        stopBtn = Button(this).apply { text = "Stop sharing"; visibility = View.GONE; setOnClickListener { stopSession() } }
        qr = ImageView(this).apply {
            visibility = View.GONE; setBackgroundColor(Color.WHITE); setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(640, 640)
        }
        urlText = tv("", 13f).apply { setTextIsSelectable(true) }
        status = tv("", 14f)
        listOf(sendBtn, receiveBtn, qr, urlText, stopBtn, status).forEach { root.addView(it) }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor("#12151c")); addView(root) })
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2) // optional: needed only for background prompts
        }
    }

    override fun onStart() {
        super.onStart()
        SessionHolder.listener = ::render
        render()
    }

    override fun onStop() {
        SessionHolder.listener = null
        dialog?.dismiss(); dialog = null; dialogFor = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(i, 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != 1 || res != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { c -> for (i in 0 until c.itemCount) uris += c.getItemAt(i).uri } ?: data.data?.let { uris += it }
        // keep access while the service shares them in the background
        uris.forEach { try { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {} }
        if (uris.isNotEmpty()) startSession(Mode.SEND, uris)
    }

    private fun startSession(mode: Mode, uris: List<Uri>?) {
        SessionHolder.requestStart(mode, uris)
        val i = Intent(this, ShareService::class.java).setAction(ShareService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        render()
    }

    private fun stopSession() {
        startService(Intent(this, ShareService::class.java).setAction(ShareService.ACTION_STOP))
    }

    private fun render() {
        val active = SessionHolder.active
        sendBtn.isEnabled = !active; receiveBtn.isEnabled = !active
        stopBtn.visibility = if (active) View.VISIBLE else View.GONE
        val url = SessionHolder.url
        if (active && url != null) {
            if (qrFor != url) { qrBitmap = Qr.bitmap(url); qrFor = url }
            qr.setImageBitmap(qrBitmap); qr.visibility = View.VISIBLE; urlText.text = url
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            qr.visibility = View.GONE; urlText.text = ""; qrFor = null; qrBitmap = null
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val head = when {
            SessionHolder.error != null -> SessionHolder.error!!
            active && SessionHolder.mode == Mode.SEND -> "Sharing. Scan the code on the other phone, then tap Allow here."
            active -> "Ready to receive (saved to Downloads/TouchShare). Scan the code on the other phone, then tap Allow here."
            else -> ""
        }
        status.text = (listOf(head) + SessionHolder.logLines()).filter { it.isNotEmpty() }.joinToString("\n")
        showApprovalPrompt(SessionHolder.pendingDevices().firstOrNull())
    }

    private fun showApprovalPrompt(d: Device?) {
        if (d == null) { dialog?.dismiss(); dialog = null; dialogFor = null; return }
        if (dialogFor == d.id) return
        dialog?.dismiss()
        dialogFor = d.id
        dialog = AlertDialog.Builder(this)
            .setTitle("Allow this device?")
            .setMessage("${d.ip} wants to connect.\n${d.userAgent.ifEmpty { "Unknown browser" }}\n\nOnly allow a phone or computer you recognise.")
            .setCancelable(false)
            .setPositiveButton("Allow") { _, _ -> SessionHolder.registry?.allow(d.id) }
            .setNegativeButton("Deny") { _, _ -> SessionHolder.registry?.deny(d.id) }
            .show()
    }

    override fun onDestroy() { dialog?.dismiss(); super.onDestroy() }
}
