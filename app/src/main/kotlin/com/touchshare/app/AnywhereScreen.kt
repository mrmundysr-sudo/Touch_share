package com.touchshare.app

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.touchshare.core.Mode

/**
 * The "Anywhere (beta)" screen, built from plain native controls on purpose: it shows every element this screen needs
 * (steps, Share link, Show QR, code and site, status, expiry, Stop) so themed artwork can be designed around them.
 * The local Same Wi-Fi screens keep their painted art.
 */
class AnywhereScreen(private val act: Activity, private val onStop: () -> Unit) {
    private val dp = act.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()
    private val green = Color.parseColor("#39FF14")
    private val text = Color.parseColor("#E6FBE3")

    private fun label(t: String, size: Float, color: Int = text, bold: Boolean = false) = TextView(act).apply {
        this.text = t; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, px(6), 0, px(6))
    }

    private fun button(t: String, onClick: () -> Unit) = Button(act).apply { this.text = t; setOnClickListener { onClick() } }

    private val title = label("Share from anywhere (beta)", 22f, green, true)
    private val steps = label("1. Send them the link.\n2. They open it in any browser. Nothing to install.\n3. You approve when they connect.", 15f)
    private val shareBtn = button("Share link") { shareLink() }
    private val qrBtn = button("Show QR") { showQr() }
    private val copyBtn = button("Copy link") { copyLink() }
    private val codeLine = label("", 18f, Color.WHITE, true)
    private val expiry = label("", 14f, Color.parseColor("#8FD68A"))
    private val status = label("", 14f)
    private val stopBtn = button("Stop sharing") { onStop() }

    val view: ScrollView = ScrollView(act).apply {
        setBackgroundColor(Color.parseColor("#05080A"))
        addView(LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(24), px(18), px(24))
            addView(title); addView(steps); addView(shareBtn)
            addView(LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; addView(qrBtn); addView(copyBtn) })
            addView(codeLine); addView(expiry); addView(status); addView(stopBtn)
        })
    }

    private fun site(): String = Uri.parse(SessionHolder.url ?: "").host ?: ""

    /** Redraws from the session state. Returns true while a countdown is showing (so the caller keeps refreshing). */
    fun update(): Boolean {
        val code = SessionHolder.remoteCode ?: ""
        codeLine.text = "Code $code\nor go to ${site()} and type it"
        val sending = SessionHolder.mode == Mode.SEND
        title.text = if (sending) "Share files from anywhere (beta)" else "Receive files from anywhere (beta)"
        status.text = SessionHolder.logLines().joinToString("\n")
        var ticking = false
        if (SessionHolder.remoteConnected) expiry.text = ""
        else {
            val left = 600 - (SystemClock.elapsedRealtime() - SessionHolder.remoteStartedAt) / 1000
            expiry.text = if (left > 0) "Link works for about ${(left + 59) / 60} more minute(s)" else "This link has expired. Stop and start again."
            ticking = left > 0
        }
        return ticking
    }

    private fun friendlyMessage(): String {
        val what = if (SessionHolder.mode == Mode.SEND) "to get the files I'm sending you" else "to send me files"
        return "Open this link $what. It works in any browser, nothing to install. I'll approve when you connect.\n${SessionHolder.url}"
    }

    private fun shareLink() {
        if (SessionHolder.url == null) return
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, friendlyMessage()); putExtra(Intent.EXTRA_SUBJECT, "Touch Share link") }
        act.startActivity(Intent.createChooser(send, "Share link"))
    }

    private fun copyLink() {
        val url = SessionHolder.url ?: return
        (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Touch Share link", url))
        Toast.makeText(act, "Link copied", Toast.LENGTH_SHORT).show()
    }

    /** Full-screen dim overlay with the real QR on a plain white square, the code below, at full brightness. Tap to close. */
    private fun showQr() {
        val url = SessionHolder.url ?: return
        val dlg = Dialog(act, android.R.style.Theme_Translucent_NoTitleBar)
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(0xE6000000.toInt())
            val qr = ImageView(act).apply {
                setImageBitmap(Qr.bitmap(url, 640)); setBackgroundColor(Color.WHITE); setPadding(px(16), px(16), px(16), px(16))
                layoutParams = LinearLayout.LayoutParams(px(300), px(300))
            }
            addView(qr)
            addView(label("Code ${SessionHolder.remoteCode}", 26f, Color.WHITE, true).apply { gravity = Gravity.CENTER })
            addView(label("or go to ${site()} and type it", 14f).apply { gravity = Gravity.CENTER })
            addView(label("Tap anywhere to close", 12f, Color.parseColor("#8FD68A")).apply { gravity = Gravity.CENTER })
            setOnClickListener { dlg.dismiss() }
        }
        dlg.setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dlg.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = 1f } }      // QR codes scan best at full brightness
        dlg.show()
    }
}
