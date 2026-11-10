package com.touchshare.app

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView

/**
 * Draws a screen's art fitted to the available space (never cropped, so tap areas always line up)
 * and places live views (QR, text, tap areas) over the regions the artist marked.
 */
class ArtLayout(
    ctx: Context,
    private val art: Bitmap,
    private val scaleFactor: Float = 1f,
    backdrop: Int = 0xFF05080A.toInt(),
) : ViewGroup(ctx) {
    private class Slot(val view: View, val r: Art.Region, val onSized: (View, Int, Int) -> Unit) {
        var lastW = -1; var lastH = -1
    }

    private val slots = ArrayList<Slot>()
    private val image = ImageView(ctx).apply { setImageBitmap(art); scaleType = ImageView.ScaleType.FIT_XY }

    init { setBackgroundColor(backdrop); addView(image) }

    /** [onSized] is called (on the UI thread) with the region's pixel size, e.g. to pick a text size. */
    fun region(v: View, r: Art.Region, onSized: (View, Int, Int) -> Unit = { _, _, _ -> }) {
        slots += Slot(v, r, onSized); addView(v)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l; val h = b - t
        if (w <= 0 || h <= 0) return
        val scale = minOf(w.toFloat() / art.width, h.toFloat() / art.height) * scaleFactor
        val iw = (art.width * scale).toInt(); val ih = (art.height * scale).toInt()
        val left = (w - iw) / 2; val top = (h - ih) / 2
        image.measure(MeasureSpec.makeMeasureSpec(iw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ih, MeasureSpec.EXACTLY))
        image.layout(left, top, left + iw, top + ih)
        for (s in slots) {
            val x = left + (s.r.x * iw).toInt(); val y = top + (s.r.y * ih).toInt()
            val sw = (s.r.w * iw).toInt().coerceAtLeast(1); val sh = (s.r.h * ih).toInt().coerceAtLeast(1)
            s.view.measure(MeasureSpec.makeMeasureSpec(sw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(sh, MeasureSpec.EXACTLY))
            s.view.layout(x, y, x + sw, y + sh)
            if (s.lastW != sw || s.lastH != sh) {
                s.lastW = sw; s.lastH = sh
                s.view.post { s.onSized(s.view, sw, sh) }
            }
        }
    }
}
