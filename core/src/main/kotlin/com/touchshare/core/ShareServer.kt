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
    private val onEvent: (ShareEvent) -> Unit = {},
) : NanoHTTPD(port) {

    private fun reply(status: Status, mime: String, body: String) =
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
}
