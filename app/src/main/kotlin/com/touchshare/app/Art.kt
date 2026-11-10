package com.touchshare.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject

/**
 * Screen art from the App Visual Builder export (see tools/prepare_art.py). Pages are looked up by
 * permanent page id, regions by label, as fractions of the image, so new art can be dropped in without code changes.
 */
object Art {
    const val HOME = "page_01"
    const val SENDING = "page_02"
    const val RECEIVING = "page_03"
    const val ALLOW_DEVICE = "page_04"

    data class Region(val x: Float, val y: Float, val w: Float, val h: Float)
    class Page(val id: String, val bitmap: Bitmap, private val regions: Map<String, Region>) {
        fun region(label: String): Region = regions[label] ?: error("Art page $id has no region '$label'")
    }

    private val cache = HashMap<String, Page>()

    @Synchronized
    fun page(ctx: Context, id: String): Page = cache.getOrPut(id) {
        val am = ctx.applicationContext.assets
        val cfg = JSONObject(am.open("art/art-config.json").bufferedReader().use { it.readText() })
        val p = cfg.getJSONObject("pages").optJSONObject(id) ?: error("Art page $id missing from art-config.json")
        val bmp = am.open("art/" + p.getString("image")).use { BitmapFactory.decodeStream(it) }
            ?: error("Could not decode art for $id")
        val regions = HashMap<String, Region>()
        p.optJSONObject("regions")?.let { r ->
            for (k in r.keys()) {
                val o = r.getJSONObject(k)
                regions[k] = Region(o.getDouble("x").toFloat(), o.getDouble("y").toFloat(), o.getDouble("w").toFloat(), o.getDouble("h").toFloat())
            }
        }
        Page(id, bmp, regions)
    }
}
