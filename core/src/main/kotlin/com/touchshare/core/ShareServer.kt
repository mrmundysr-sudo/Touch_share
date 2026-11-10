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
                mode == Mode.SEND && (session.method == Method.GET || session.method == Method.HEAD) && path.startsWith("/f/") ->
                    download(path.removePrefix("/f/"), session)
                mode == Mode.RECEIVE && session.method == Method.POST && path == "/upload" -> upload(session)
                mode == Mode.RECEIVE && session.method == Method.POST && path == "/upload/start" -> uploadStart(session)
                mode == Mode.RECEIVE && session.method == Method.GET && path == "/upload/status" -> uploadStatus(session)
                mode == Mode.RECEIVE && session.method == Method.PUT && path == "/upload/chunk" -> uploadChunk(session)
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

    /** Parses one "bytes=a-b" range. null = ignore the header (send everything); EMPTY = unsatisfiable (416). */
    private fun parseRange(h: String, size: Long): LongRange? {
        if (!h.startsWith("bytes=")) return null
        val spec = h.removePrefix("bytes=").trim()
        if (',' in spec) return null
        val dash = spec.indexOf('-'); if (dash < 0) return null
        val a = spec.substring(0, dash).trim(); val b = spec.substring(dash + 1).trim()
        if (a.isEmpty()) {
            val n = b.toLongOrNull() ?: return null
            if (n <= 0 || size == 0L) return LongRange.EMPTY
            return maxOf(0L, size - n)..(size - 1)
        }
        val start = a.toLongOrNull() ?: return null
        val end = if (b.isEmpty()) size - 1 else (b.toLongOrNull() ?: return null)
        if (b.isNotEmpty() && end < start) return null
        if (start >= size) return LongRange.EMPTY
        return start..minOf(end, size - 1)
    }

    private fun download(id: String, session: IHTTPSession): Response {
        val f = source!!.list().firstOrNull { it.id == id }
            ?: return reply(Status.NOT_FOUND, "text/plain", "Not found")
        val head = session.method == Method.HEAD
        val etag = "\"${f.id}-${f.size}\""
        var range: LongRange? = null
        val rh = session.headers["range"]
        val ifRange = session.headers["if-range"]
        if (rh != null && f.size >= 0 && (ifRange == null || ifRange == etag)) {
            range = parseRange(rh, f.size)
            if (range != null && range.isEmpty()) {
                val r = reply(RangeBad, "text/plain", "Range not satisfiable")
                r.addHeader("Content-Range", "bytes */${f.size}")
                return r
            }
        }
        val start = range?.first ?: 0L
        val length = if (range != null) range.last - range.first + 1 else f.size
        val stream = (if (start > 0) source.openAt(id, start) else source.open(id))
            ?: return reply(Status.NOT_FOUND, "text/plain", "Not found")
        if (!head) onEvent(ShareEvent.DownloadStarted(f.name))
        val done = object : java.io.FilterInputStream(stream) {
            private var fired = false
            private var total = 0L
            private fun finish() { if (!fired && !head) { fired = true; onEvent(ShareEvent.DownloadFinished(f.name)) } }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n > 0) total += n
                if (n < 0 || (length >= 0 && total >= length)) finish()
                return n
            }
        }
        val r = if (length >= 0) newFixedLengthResponse(if (range != null) Status.PARTIAL_CONTENT else Status.OK, f.mime, done, length)
        else newChunkedResponse(Status.OK, f.mime, done)
        if (range != null) r.addHeader("Content-Range", "bytes ${range.first}-${range.last}/${f.size}")
        if (f.size >= 0) { r.addHeader("Accept-Ranges", "bytes"); r.addHeader("ETag", etag) }
        val safe = FileNames.sanitize(f.name)
        r.addHeader(
            "Content-Disposition",
            "attachment; filename=\"${safe.replace("\"", "")}\"; filename*=UTF-8''" +
                URLEncoder.encode(safe, "UTF-8").replace("+", "%20"),
        )
        harden(r)
        return r
    }

    // ---- resumable uploads: start, then PUT sequential chunks, ask status to resync after a drop ----
    private class UploadState(val name: String, val size: Long, val upload: IncomingUpload) { var offset = 0L }

    private val uploads = java.util.concurrent.ConcurrentHashMap<String, UploadState>()

    private fun json(status: Response.IStatus, body: String) = reply(status, "application/json", body)
    private fun jstr(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun uploadStart(session: IHTTPSession): Response {
        val name = FileNames.sanitize(session.parameters["name"]?.firstOrNull())
        val size = session.parameters["size"]?.firstOrNull()?.toLongOrNull()
        if (size == null || size < 0) return json(Status.BAD_REQUEST, "{\"error\":\"bad size\"}")
        if (uploads.size >= MAX_UPLOADS) return json(TooMany, "{\"error\":\"too many uploads\"}")
        if (!sink!!.hasSpace(size)) {
            onEvent(ShareEvent.Failed(name, "not enough free space"))
            return json(NoSpace, "{\"error\":\"not enough space\"}")
        }
        val up = sink.begin(name, size)
        if (size == 0L) {
            val saved = up.finish()
            onEvent(ShareEvent.Uploaded(name, saved, 0))
            return json(Status.OK, "{\"id\":\"empty\",\"offset\":0,\"done\":true}")
        }
        val id = TokenAuth.generate(8)
        uploads[id] = UploadState(name, size, up)
        return json(Status.OK, "{\"id\":\"$id\",\"offset\":0}")
    }

    private fun uploadStatus(session: IHTTPSession): Response {
        val u = uploads[session.parameters["id"]?.firstOrNull() ?: ""]
            ?: return json(Status.NOT_FOUND, "{\"error\":\"unknown upload\"}")
        return json(Status.OK, "{\"offset\":${u.offset},\"size\":${u.size}}")
    }

    private fun uploadChunk(session: IHTTPSession): Response {
        val tooBig = (session.headers["content-length"]?.toLongOrNull() ?: 0L) > MAX_CHUNK
        if (tooBig) {
            return json(TooLarge, "{\"error\":\"chunk too large\"}").also { it.addHeader("Connection", "close") }
        }
        val files = HashMap<String, String>()
        session.parseBody(files)                       // always read the body so the connection stays usable
        val tmp = files["content"]?.let { File(it) }
        try {
            val id = session.parameters["id"]?.firstOrNull() ?: ""
            val off = session.parameters["offset"]?.firstOrNull()?.toLongOrNull()
                ?: return json(Status.BAD_REQUEST, "{\"error\":\"bad offset\"}")
            val u = uploads[id] ?: return json(Status.NOT_FOUND, "{\"error\":\"unknown upload\"}")
            if (tmp == null) return json(Status.BAD_REQUEST, "{\"error\":\"no data\"}")
            synchronized(u) {
                if (off != u.offset) return json(Conflict, "{\"offset\":${u.offset}}")
                val len = tmp.length()
                if (u.offset + len > u.size) {
                    uploads.remove(id); u.upload.abort()
                    onEvent(ShareEvent.Failed(u.name, "sent more data than announced"))
                    return json(Status.BAD_REQUEST, "{\"error\":\"too much data\"}")
                }
                try {
                    FileInputStream(tmp).use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) { val n = input.read(buf); if (n < 0) break; u.upload.write(buf, 0, n) }
                    }
                } catch (e: Exception) {
                    uploads.remove(id); runCatching { u.upload.abort() }
                    onEvent(ShareEvent.Failed(u.name, e.message ?: "write failed"))
                    return json(Status.INTERNAL_ERROR, "{\"error\":\"write failed\"}")
                }
                u.offset += len
                if (u.offset == u.size) {
                    val saved = try { u.upload.finish() } catch (e: Exception) {
                        uploads.remove(id); runCatching { u.upload.abort() }
                        onEvent(ShareEvent.Failed(u.name, e.message ?: "save failed"))
                        return json(Status.INTERNAL_ERROR, "{\"error\":\"save failed\"}")
                    }
                    uploads.remove(id)
                    onEvent(ShareEvent.Uploaded(u.name, saved, u.size))
                    return json(Status.OK, "{\"offset\":${u.offset},\"done\":true,\"savedAs\":${jstr(saved)}}")
                }
                onEvent(ShareEvent.UploadProgress(u.name, u.offset, u.size))
                return json(Status.OK, "{\"offset\":${u.offset}}")
            }
        } finally {
            tmp?.delete()
        }
    }

    override fun stop() {
        super.stop()
        // a session that ends mid-upload leaves nothing half-written behind
        uploads.values.forEach { runCatching { it.upload.abort() } }
        uploads.clear()
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
        const val MAX_UPLOADS = 16
        const val MAX_CHUNK = 16L * 1024 * 1024
        fun st(code: Int, text: String) = object : Response.IStatus {
            override fun getRequestStatus() = code
            override fun getDescription() = "$code $text"
        }
        val TooMany = st(429, "Too Many Requests")
        val Conflict = st(409, "Conflict")
        val TooLarge = st(413, "Payload Too Large")
        val RangeBad = st(416, "Range Not Satisfiable")
        val NoSpace = st(507, "Insufficient Storage")
    }
}
