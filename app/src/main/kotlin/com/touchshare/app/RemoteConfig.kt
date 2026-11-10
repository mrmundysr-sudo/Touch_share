package com.touchshare.app

import android.content.Context
import android.net.Uri

/** Where the "Anywhere" matchmaker lives. Comes from the build (CI variable) or an in-app setting, never from the source code. */
object RemoteConfig {
    private const val PREFS = "touchshare"
    private const val KEY = "remote_server"

    /** An address saved in the app wins; otherwise the one baked in at build time (BuildConfig.ANYWHERE_SERVER); otherwise none. */
    fun serverUrl(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: normalize(BuildConfig.ANYWHERE_SERVER)
    fun setServerUrl(ctx: Context, url: String) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, url).apply() }

    /** Accepts what a person pastes ("https://name.account.workers.dev", maybe with a path) and returns "https://host", or null. */
    fun normalize(text: String?): String? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        val u = try { Uri.parse(if (t.contains("://")) t else "https://$t") } catch (e: Exception) { return null }
        val host = u.host ?: return null
        if (u.scheme != "https" || !host.contains('.')) return null
        return "https://" + host + (if (u.port > 0 && u.port != 443) ":${u.port}" else "")
    }
}
