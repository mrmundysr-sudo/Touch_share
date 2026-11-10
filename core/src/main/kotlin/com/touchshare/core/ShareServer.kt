package com.touchshare.core

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.File
import java.io.FileInputStream
import java.net.URLEncoder

/**
 * Local HTTP server for one share session. Every request needs the session token (?t=).
 * SEND serves [source]; RECEIVE accepts one-file-per-request multipart uploads into [sink].
 */
class ShareServer(
    private val mode: Mode,
    val token: String,
    private val source: FileSource? = null,
    private val sink: FileSink? = null,
    port: Int = 0,
    private val registry: DeviceRegistry? = null,
    private val assets: StaticAssets? = null,
    private val onEvent: (ShareEvent) -> Unit = {},
) : NanoHTTPD(port) {

    private fun reply(status: Response.IStatus, mime: String, body: String) =
        newFixedLengthResponse(status, mime, body).also { harden(it) }

    private fun harden(r: Response) {
        r.addHeader("Cache-Control", "no-store")
        r.addHeader("X-Content-Type-Options", "nosniff")
        r.addHeader("Referrer-Policy", "no-referrer")
    }

    override fun serve(session: IHTTPSession): Response {
        val supplied = session.parameters["t"]?.firstOrNull()
        if (!TokenAuth.matches(token, supplied)) {
            onEvent(ShareEvent.Rejected("bad or missing token"))
            return reply(Status.FORBIDDEN, "text/plain", "Forbidden")
        }
        val path = session.uri
        if (path.startsWith("/assets/") && session.method == Method.GET) return asset(path.removePrefix("/assets/"))
        var newCookie: String? = null
        if (registry != null) {
            val ip = session.remoteIpAddress ?: ""
            if (registry.isIpDenied(ip)) {
                onEvent(ShareEvent.Rejected("denied device"))
                return reply(Status.FORBIDDEN, "text/plain", "Denied")
            }
            var id = cookieValue(session.headers["cookie"], COOKIE)
            var state = id?.let { registry.state(it) }
            if (state == null) {
                val d = registry.register(ip, session.headers["user-agent"] ?: "")
                    ?: return reply(TooMany, "text/plain", "Too many waiting devices. Try again shortly.")
                id = d.id; state = DeviceState.PENDING; newCookie = d.id
            }
            when (state) {
                DeviceState.DENIED -> return reply(Status.FORBIDDEN, "text/plain", "Denied")
                DeviceState.PENDING -> {
                    val r = if (path == "/status") reply(Status.OK, "application/json", "{\"state\":\"pending\"}")
                    else reply(Status.OK, "text/html; charset=utf-8", Pages.waitingPage(token))
                    return withCookie(r, newCookie)
                }
                DeviceState.ALLOWED -> if (path == "/status") return reply(Status.OK, "application/json", "{\"state\":\"allowed\"}")
            }
        }
        return try {
            when {
                session.method == Method.GET && path == "/" ->
                    reply(
                        Status.OK, "text/html; charset=utf-8",
                        if (mode == Mode.SEND) Pages.listPage(token, source!!.list()) else Pages.uploadPage(token),
                    )
                mode == Mode.SEND && session.method == Method.GET && path.startsWith("/f/") ->
                    download(path.removePrefix("/f/"))
                mode == Mode.RECEIVE && session.method == Method.POST && path == "/upload" -> upload(session)
                else -> reply(Status.NOT_FOUND, "text/plain", "Not found")
            }
        } catch (e: Exception) {
            reply(Status.INTERNAL_ERROR, "text/plain", "Error")
        }
    }

    /** Theme art for the web pages. Token-protected like everything else, but open to devices still waiting. */
    private fun asset(name: String): Response {
        val ok = name.isNotEmpty() && name.length <= 64 && !name.startsWith(".") &&
            name.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
        val stream = if (ok) assets?.open(name) else null
        if (stream == null) return reply(Status.NOT_FOUND, "text/plain", "Not found")
        val mime = when (name.substringAfterLast('.', "").lowercase()) {
            "webp" -> "image/webp"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"
            "css" -> "text/css"; else -> "application/octet-stream"
        }
        val r = newChunkedResponse(Status.OK, mime, stream)
        r.addHeader("Cache-Control", "private, max-age=3600")
        r.addHeader("X-Content-Type-Options", "nosniff")
        return r
    }

    private fun withCookie(r: Response, id: String?): Response {
        if (id != null) r.addHeader("Set-Cookie", "$COOKIE=$id; Path=/; HttpOnly; SameSite=Lax")
        return r
    }

    private fun cookieValue(header: String?, name: String): String? =
        header?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')?.takeIf { it.length in 8..64 && it.all { c -> c.isLetterOrDigit() } }

    private fun download(id: String): Response {
        val f = source!!.list().firstOrNull { it.id == id }
            ?: return reply(Status.NOT_FOUND, "text/plain", "Not found")
        val stream = source.open(id) ?: return reply(Status.NOT_FOUND, "text/plain", "Not found")
        onEvent(ShareEvent.DownloadStarted(f.name))
        val done = object : java.io.FilterInputStream(stream) {
            private var fired = false
            private var total = 0L
            private fun finish() { if (!fired) { fired = true; onEvent(ShareEvent.DownloadFinished(f.name)) } }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n > 0) total += n
                if (n < 0 || (f.size >= 0 && total >= f.size)) finish()
                return n
            }
        }
        val r = if (f.size >= 0) newFixedLengthResponse(Status.OK, f.mime, done, f.size)
        else newChunkedResponse(Status.OK, f.mime, done)
        val safe = FileNames.sanitize(f.name)
        r.addHeader(
            "Content-Disposition",
            "attachment; filename=\"${safe.replace("\"", "")}\"; filename*=UTF-8''" +
                URLEncoder.encode(safe, "UTF-8").replace("+", "%20"),
        )
        harden(r)
        return r
    }

    private fun upload(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val tmpPath = files["file"] ?: return reply(Status.BAD_REQUEST, "text/plain", "No file")
        val original = session.parameters["file"]?.firstOrNull() ?: "file"
        val name = FileNames.sanitize(original)
        val tmp = File(tmpPath)
        return try {
            val saved = FileInputStream(tmp).use { sink!!.save(name, it, tmp.length()) }
            onEvent(ShareEvent.Uploaded(name, saved, tmp.length()))
            reply(Status.OK, "text/plain", "OK")
        } catch (e: Exception) {
            onEvent(ShareEvent.Failed(name, e.message ?: "save failed"))
            reply(Status.INTERNAL_ERROR, "text/plain", "Save failed")
        } finally {
            tmp.delete()
        }
    }

    private companion object {
        const val COOKIE = "ts_dev"
        val TooMany = object : Response.IStatus {
            override fun getRequestStatus() = 429
            override fun getDescription() = "429 Too Many Requests"
        }
    }
}
