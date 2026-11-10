package com.touchshare.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.touchshare.core.IncomingUpload
import com.touchshare.core.Mode
import org.json.JSONArray
import org.json.JSONObject

/**
 * The phone as the HOST of a remote ("Anywhere") share. A hidden WebView runs the same script as the other person's
 * browser (peer.js, via app-peer.js); this class feeds it files and stores what it receives. Create and use on the main thread.
 */
class RemoteHost(private val ctx: Context, private val listener: Listener) {
    interface Listener {
        fun onRoom(code: String, secret: String, link: String)
        fun onState(state: String, reason: String?, code: String?)
        fun onApprove(code: String, typed: Boolean)
        fun onProgress(dir: String, name: String, done: Long, total: Long)
        fun onFileDone(dir: String, name: String, ok: Boolean)
        fun onConnection(kind: String)
        fun onDeclined()
        fun onRejected(reason: String)
        fun onError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var options: String? = null
    private var source: UriSource? = null
    private val sink = DownloadsSink(ctx)
    private val uploads = HashMap<String, IncomingUpload>()
    private var nextSink = 1

    /** mode SEND: share [uris] once the other person is approved. RECEIVE: save what they send. */
    fun start(mode: Mode, uris: List<Uri>?, serverUrl: String) {
        val files = JSONArray()
        if (mode == Mode.SEND && uris != null) {
            val src = UriSource(ctx.contentResolver, uris); source = src
            for (f in src.list()) {
                if (f.size < 0) { listener.onError("Can't share ${f.name}: its size is unknown."); return }
                files.put(JSONObject().put("id", f.id).put("name", f.name).put("size", f.size).put("type", f.mime))
            }
        }
        options = JSONObject().put("signalUrl", serverUrl).put("mode", if (mode == Mode.SEND) "send" else "receive").put("files", files).toString()
        val w = try { WebView(ctx.applicationContext) } catch (e: Exception) { listener.onError("This phone's web engine isn't available (${e.message})."); return }
        web = w
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx.applicationContext)).build()
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = loader.shouldInterceptRequest(request.url)
        }
        w.addJavascriptInterface(Bridge(), "TouchBridge")
        w.loadUrl("https://appassets.androidplatform.net/assets/remote/app-peer.html")
    }

    fun decide(allow: Boolean) { web?.evaluateJavascript("TS.decide($allow)", null) }

    fun stop() {
        val w = web ?: return
        web = null
        w.evaluateJavascript("TS.stop()", null)
        for (u in uploads.values) try { u.abort() } catch (_: Exception) {}
        uploads.clear()
        main.postDelayed({ try { w.destroy() } catch (_: Exception) {} }, 2000)     // give the page time to tell the matchmaker
    }

    private fun handle(json: String) {
        val o = try { JSONObject(json) } catch (e: Exception) { return }
        when (o.optString("type")) {
            "ready" -> web?.evaluateJavascript("TS.start(" + JSONObject.quote(options ?: "{}") + ")", null)
            "room" -> listener.onRoom(o.getString("code"), o.getString("secret"), o.getString("link"))
            "state" -> listener.onState(o.optString("state"), o.optString("reason").ifEmpty { null }, o.optString("code").ifEmpty { null })
            "approve" -> listener.onApprove(o.optString("code"), o.optBoolean("typed"))
            "progress" -> listener.onProgress(o.optString("dir"), o.optString("name"), o.optLong("done"), o.optLong("total"))
            "file-done" -> listener.onFileDone(o.optString("dir"), o.optString("name"), o.optBoolean("ok"))
            "connection" -> listener.onConnection(o.optString("kind"))
            "declined" -> listener.onDeclined()
            "rejected" -> listener.onRejected(o.optString("reason"))
            "error" -> listener.onError(o.optString("message"))
        }
    }

    /** Called from the page. Methods run on a background thread; they only touch files and thread-safe state. */
    private inner class Bridge {
        @JavascriptInterface fun event(json: String) { main.post { handle(json) } }

        @JavascriptInterface fun readChunk(id: String, offset: Double, len: Double): String {
            val s = source ?: return ""
            val buf = ByteArray(len.toInt())
            var n = 0
            s.openAt(id, offset.toLong())?.use { input ->
                while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) break; n += r }
            }
            return Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)
        }

        @JavascriptInterface fun sinkOpen(name: String, size: Double, type: String): String {
            if (!sink.hasSpace(size.toLong())) return ""
            return try {
                val id = synchronized(this@RemoteHost) { (nextSink++).toString() }
                uploads[id] = sink.begin(name, size.toLong()); id
            } catch (e: Exception) { "" }
        }

        @JavascriptInterface fun sinkWrite(id: String, b64: String) {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            uploads[id]?.write(bytes, 0, bytes.size)
        }

        @JavascriptInterface fun sinkFinish(id: String): String = uploads.remove(id)?.finish() ?: ""
        @JavascriptInterface fun sinkAbort(id: String) { uploads.remove(id)?.abort() }
    }
}
