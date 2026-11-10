package com.touchshare.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.touchshare.core.Device
import com.touchshare.core.Mode

class MainActivity : Activity() {
    private class SessionScreen(val layout: ArtLayout, val qr: ImageView, val link: TextView, val line: TextView)

    private lateinit var container: FrameLayout
    private lateinit var home: ArtLayout
    private lateinit var sending: SessionScreen
    private lateinit var receiving: SessionScreen
    private var current: View? = null
    private var qrFor: String? = null
    private var qrBitmap: Bitmap? = null
    private var dialog: Dialog? = null
    private var promptDialog: Dialog? = null
    private var dialogFor: String? = null
    private var lastError: String? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.setBackgroundDrawable(ColorDrawable(0xFF05080A.toInt()))
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val homeArt = Art.page(this, Art.HOME)
        home = ArtLayout(this, homeArt.bitmap).apply {
            region(tapArea { pickFiles() }, homeArt.region("send_button"))
            region(tapArea { startSession(Mode.RECEIVE, null) }, homeArt.region("receive_button"))
        }
        sending = sessionScreen(Art.SENDING)
        receiving = sessionScreen(Art.RECEIVING)

        container = FrameLayout(this).apply { setBackgroundColor(0xFF05080A.toInt()) }
        setContentView(container, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** A see-through button laid over painted artwork, with a light ripple when pressed. */
    private fun tapArea(onClick: () -> Unit): View = View(this).apply {
        isClickable = true
        background = RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), null, ColorDrawable(Color.WHITE))
        setOnClickListener { onClick() }
    }

    private fun textIn(sizeFactor: Float, color: Int = 0xFFD8FFD2.toInt()) = TextView(this).apply {
        setTextColor(color); gravity = Gravity.CENTER; setSingleLine(true)
        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        tag = sizeFactor
    }

    private fun sized(v: View, @Suppress("UNUSED_PARAMETER") w: Int, h: Int) {
        val t = v as TextView
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, h * (t.tag as Float))
    }

    private fun sessionScreen(pageId: String): SessionScreen {
        val art = Art.page(this, pageId)
        val qr = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.WHITE) }
        val link = textIn(0.5f).apply { setOnClickListener { copyLink() } }
        val line = textIn(0.5f)
        val layout = ArtLayout(this, art.bitmap).apply {
            region(qr, art.region("qr_square"))
            region(link, art.region("link_area"), ::sized)
            region(line, art.region("file_list"), ::sized)
            region(tapArea { stopSession() }, art.region("stop_button"))
        }
        return SessionScreen(layout, qr, link, line)
    }

    private fun copyLink() {
        val url = SessionHolder.url ?: return
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Touch Share link", url))
        Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
    }

    override fun onStart() {
        super.onStart()
        SessionHolder.listener = ::render
        render()
    }

    override fun onStop() {
        SessionHolder.listener = null
        dialog?.dismiss(); dialog = null; dialogFor = null
        promptDialog?.dismiss(); promptDialog = null
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

    private val prefs by lazy { getSharedPreferences("touchshare", MODE_PRIVATE) }

    private fun needsNotificationPrompt() = Build.VERSION.SDK_INT >= 33 &&
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
        !prefs.getBoolean("asked_notifications", false)

    /** Temporary plain chooser (themed art for it is still to come): same Wi-Fi, or Anywhere (beta). */
    private fun startSession(mode: Mode, uris: List<Uri>?) {
        val items = arrayOf("Same Wi-Fi or hotspot", "Anywhere (beta)", "Anywhere server address\u2026")
        AlertDialog.Builder(this)
            .setTitle(if (mode == Mode.SEND) "Send how?" else "Receive how?")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startAfterChoice(mode, uris, false)
                    1 -> if (RemoteConfig.serverUrl(this) == null) askServerAddress { startAfterChoice(mode, uris, true) } else startAfterChoice(mode, uris, true)
                    2 -> askServerAddress { startSession(mode, uris) }
                }
            }
            .show()
    }

    /** Asks for the matchmaker address once; fills it in from the clipboard if a web address was just copied. */
    private fun askServerAddress(done: () -> Unit) {
        val box = EditText(this).apply {
            hint = "https://your-name.workers.dev"
            setSingleLine(true)
            val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()
            setText(RemoteConfig.serverUrl(this@MainActivity) ?: RemoteConfig.normalize(clip) ?: "")
        }
        AlertDialog.Builder(this)
            .setTitle("Anywhere server address")
            .setMessage("The address of your Touch Share matchmaker (from Cloudflare). Copy it first and it fills in here.")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val url = RemoteConfig.normalize(box.text.toString())
                if (url == null) Toast.makeText(this, "That doesn't look like a web address (it must start with https://).", Toast.LENGTH_LONG).show()
                else { RemoteConfig.setServerUrl(this, url); done() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startAfterChoice(mode: Mode, uris: List<Uri>?, remote: Boolean) {
        if (!needsNotificationPrompt()) { launchSession(mode, uris, remote); return }
        // Themed explainer first; Android's own permission box only appears if the user continues.
        promptDialog?.dismiss()
        promptDialog = artDialog(
            "Get an alert when another device wants to connect, even when Touch Share is in the background?\n\nYou can still use the app if you say no.",
            onAllow = {
                prefs.edit().putBoolean("asked_notifications", true).apply()
                if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
                launchSession(mode, uris, remote)
            },
            onDeny = {
                prefs.edit().putBoolean("asked_notifications", true).apply()
                launchSession(mode, uris, remote)
            },
        ).also { it.show() }
    }

    private fun launchSession(mode: Mode, uris: List<Uri>?, remote: Boolean) {
        SessionHolder.requestStart(mode, uris, remote)
        val i = Intent(this, ShareService::class.java).setAction(ShareService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        render()
    }

    private fun stopSession() {
        startService(Intent(this, ShareService::class.java).setAction(ShareService.ACTION_STOP))
    }

    private fun render() {
        val active = SessionHolder.active
        val screen = if (active) (if (SessionHolder.mode == Mode.SEND) sending else receiving) else null
        val target: View = screen?.layout ?: home
        if (current !== target) { container.removeAllViews(); container.addView(target); current = target }

        val url = SessionHolder.url
        if (screen != null && url != null) {
            if (qrFor != url) { qrBitmap = Qr.bitmap(url); qrFor = url }
            screen.qr.setImageBitmap(qrBitmap)
            screen.link.text = if (SessionHolder.remote) "Code ${SessionHolder.remoteCode} (tap to copy link)" else url
            screen.line.text = SessionHolder.logLines().firstOrNull() ?: "Waiting for the other phone…"
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            qrFor = null; qrBitmap = null
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val err = SessionHolder.error
        if (err != null && err != lastError) Toast.makeText(this, err, Toast.LENGTH_LONG).show()
        lastError = err

        showApprovalPrompt(SessionHolder.pendingDevices().firstOrNull())
    }

    private fun browserName(ua: String): String {
        val m = Regex("\\(([^)]*)\\)").find(ua)?.groupValues?.get(1)
        return (m ?: ua).ifBlank { "Unknown browser" }.take(60)
    }

    private fun showApprovalPrompt(d: Device?) {
        if (d == null) { dialog?.dismiss(); dialog = null; dialogFor = null; return }
        if (dialogFor == d.id) return
        dialog?.dismiss()
        dialogFor = d.id

        val text = if (d.id == SessionHolder.REMOTE_ID) "Someone online wants to connect.\n${d.userAgent}\n\nOnly allow a person you know."
        else "Device at ${d.ip}\n${browserName(d.userAgent)}\n\nOnly allow a phone or computer you recognise."
        dialog = artDialog(
            text,
            onAllow = { SessionHolder.decide(d.id, true) },
            onDeny = { SessionHolder.decide(d.id, false) },
        ).also { it.show() }
    }

    /** The themed pop-up (page_04 art): a message, plus the painted Allow and Deny buttons. */
    private fun artDialog(text: String, onAllow: () -> Unit, onDeny: () -> Unit): Dialog {
        val art = Art.page(this, Art.ALLOW_DEVICE)
        val message = textIn(0.088f, Color.WHITE).apply { setSingleLine(false); maxLines = 8; ellipsize = null }
        message.text = text
        val dlg = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        val layout = ArtLayout(this, art.bitmap, scaleFactor = 0.94f, backdrop = Color.TRANSPARENT).apply {
            region(message, art.region("message_area"), ::sized)
            region(tapArea { onAllow(); dlg.dismiss() }, art.region("allow_button"))
            region(tapArea { onDeny(); dlg.dismiss() }, art.region("deny_button"))
        }
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dlg.setContentView(layout, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dlg.setCancelable(false)
        dlg.window?.apply { addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); setDimAmount(0.75f) }
        return dlg
    }


    override fun onDestroy() { dialog?.dismiss(); promptDialog?.dismiss(); super.onDestroy() }
}
