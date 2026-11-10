package com.touchshare.app

import android.content.Context
import android.net.Uri

/** Where the "Anywhere" matchmaker lives. Kept in app settings, not in the code, because the repository is public. */
object RemoteConfig {
    private const val PREFS = "touchshare"
    private const val KEY = "remote_server"

    fun serverUrl(ctx: Context): String? = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
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
