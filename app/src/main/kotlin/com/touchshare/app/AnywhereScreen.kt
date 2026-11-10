package com.touchshare.app

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.SystemClock
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.touchshare.core.Mode

/**
 * The "Anywhere (beta)" screen: the painted art (a sending look and a receiving look that share one layout) with live
 * text placed in the areas the artist marked (code and site, countdown, status) and invisible buttons over the painted
 * ones (Share, Show QR, Copy, Stop). Tapping Show QR opens the full-screen QR overlay.
 */
class AnywhereScreen(private val act: Activity, private val onStop: () -> Unit) {
    private class Pane(val layout: ArtLayout, val code: TextView, val timer: TextView, val status: TextView)

    private val green = Color.parseColor("#BFF5B8")

    private fun live(size: Float, color: Int = Color.WHITE, bold: Boolean = false, lines: Int = 1) = TextView(act).apply {
        setTextColor(color); gravity = Gravity.CENTER; maxLines = lines; ellipsize = TextUtils.TruncateAt.END
        if (bold) setTypeface(typeface, Typeface.BOLD)
        tag = size
    }

    private fun fit(v: View, @Suppress("UNUSED_PARAMETER") w: Int, h: Int) {
        val t = v as TextView; t.setTextSize(TypedValue.COMPLEX_UNIT_PX, h * (t.tag as Float))
    }

    private fun tapArea(onClick: () -> Unit): View = View(act).apply {
        isClickable = true
        background = RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), null, ColorDrawable(Color.WHITE))
        setOnClickListener { onClick() }
    }

    private fun pane(pageId: String): Pane {
        val art = Art.page(act, pageId)
        val code = live(0.34f, bold = true, lines = 2)
        val timer = live(0.5f, bold = true)
        val status = live(0.28f, green, lines = 2)
        val layout = ArtLayout(act, art.bitmap).apply {
            region(tapArea { shareLink() }, art.region("share_button"))
            region(tapArea { showQr() }, art.region("qr_button"))
            region(tapArea { copyLink() }, art.region("copy_button"))
            region(tapArea { onStop() }, art.region("stop_button"))
            region(code, art.region("code_area"), ::fit)
            region(timer, art.region("timer_area"), ::fit)
            region(status, art.region("status_line"), ::fit)
        }
        return Pane(layout, code, timer, status)
    }

    private val send = pane(Art.ANYWHERE_SEND)
    private val receive = pane(Art.ANYWHERE_RECEIVE)

    fun view(mode: Mode?): View = (if (mode == Mode.RECEIVE) receive else send).layout

    private fun site(): String = Uri.parse(SessionHolder.url ?: "").host ?: ""

    /** Redraws from the session state. Returns true while a countdown is running (so the caller keeps refreshing). */
    fun update(): Boolean {
        val head = "Code ${SessionHolder.remoteCode ?: ""}\n"
        val codeText = SpannableString(head + site()).apply { setSpan(RelativeSizeSpan(0.55f), head.length, length, 0) }
        val lines = SessionHolder.logLines().take(2).joinToString("\n")
        var ticking = false
        val timerText: String
        if (SessionHolder.remoteConnected) timerText = "Connected"
        else {
            val left = 600 - (SystemClock.elapsedRealtime() - SessionHolder.remoteStartedAt) / 1000
            timerText = if (left > 0) "%d:%02d".format(left / 60, left % 60) else "Expired"
            ticking = left > 0
        }
        for (p in listOf(send, receive)) { p.code.text = codeText; p.timer.text = timerText; p.status.text = lines }
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

    private fun copy(label: String, text: String, toast: String) {
        (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(act, toast, Toast.LENGTH_SHORT).show()
    }

    private fun copyLink() { SessionHolder.url?.let { copy("Touch Share link", it, "Link copied") } }

    /** Full-screen QR overlay in the artist's frame: real QR on the plain white square, the code and the site written live.
     *  Tap the code or the site to copy it; tap anywhere else to close. Full brightness so cameras read it easily. */
    private fun showQr() {
        val url = SessionHolder.url ?: return
        val art = Art.page(act, Art.ANYWHERE_QR)
        val dlg = Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val qr = ImageView(act).apply { setImageBitmap(Qr.bitmap(url, 640)); scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.WHITE) }
        val code = live(0.6f, Color.WHITE, true).apply { text = SessionHolder.remoteCode ?: ""; letterSpacingCompat() }
        val site = live(0.5f, Color.WHITE).apply { text = site() }
        code.setOnClickListener { SessionHolder.remoteCode?.let { copy("Touch Share code", it, "Code copied") } }
        site.setOnClickListener { copy("Touch Share website", "https://" + site(), "Address copied") }
        val layout = ArtLayout(act, art.bitmap).apply {
            region(qr, art.region("qr_square"))
            region(code, art.region("code_area"), ::fit)
            region(site, art.region("site_area"), ::fit)
            setOnClickListener { dlg.dismiss() }
        }
        dlg.setContentView(layout, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dlg.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = 1f } }
        dlg.show()
    }

    private fun TextView.letterSpacingCompat() { letterSpacing = 0.12f }
}
