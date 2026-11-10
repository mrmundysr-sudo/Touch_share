package com.touchshare.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.*

private class Http(val code: Int, val body: ByteArray, val headers: Map<String, String>) {
    val text get() = body.toString(Charsets.UTF_8)
}

private fun call(method: String, url: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null): Http {
    val c = URL(url).openConnection() as HttpURLConnection
    c.requestMethod = method
    headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
    if (body != null) { c.doOutput = true; c.setFixedLengthStreamingMode(body.size); c.outputStream.use { it.write(body) } }
    val code = c.responseCode
    val bytes = (if (code < 400) c.inputStream else c.errorStream)?.readBytes() ?: ByteArray(0)
    val h = c.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.first() }
    return Http(code, bytes, h)
}

class RangeTest {
    private val data = "0123456789".toByteArray()
    private val src = object : FileSource {
        override fun list() = listOf(ShareFile("1", "digits.txt", data.size.toLong(), "text/plain"))
        override fun open(id: String): InputStream? = ByteArrayInputStream(data)   // openAt uses the default skip
    }

    private fun withServer(block: (String) -> Unit, events: MutableList<ShareEvent> = mutableListOf()) {
        val s = ShareServer(Mode.SEND, "secret", source = src, onEvent = { events += it })
        s.start()
        try { block("http://127.0.0.1:${s.listeningPort}/f/1?t=secret") } finally { s.stop() }
    }

    @Test fun fullDownloadAdvertisesRangesAndEtag() = withServer({ u ->
        val r = call("GET", u)
        assertEquals(200, r.code); assertEquals("0123456789", r.text)
        assertEquals("bytes", r.headers["accept-ranges"]); assertEquals("\"1-10\"", r.headers["etag"])
    })

    @Test fun rangeVariants() = withServer({ u ->
        call("GET", u, mapOf("Range" to "bytes=3-5")).let {
            assertEquals(206, it.code); assertEquals("345", it.text); assertEquals("bytes 3-5/10", it.headers["content-range"])
        }
        call("GET", u, mapOf("Range" to "bytes=7-")).let { assertEquals(206, it.code); assertEquals("789", it.text) }
        call("GET", u, mapOf("Range" to "bytes=-4")).let { assertEquals("6789", it.text); assertEquals("bytes 6-9/10", it.headers["content-range"]) }
        call("GET", u, mapOf("Range" to "bytes=8-99")).let { assertEquals("89", it.text); assertEquals("bytes 8-9/10", it.headers["content-range"]) }
        call("GET", u, mapOf("Range" to "bytes=0-0")).let { assertEquals("0", it.text) }
    })

    @Test fun badRangesAreHandledPerSpec() = withServer({ u ->
        call("GET", u, mapOf("Range" to "bytes=50-")).let { assertEquals(416, it.code); assertEquals("bytes */10", it.headers["content-range"]) }
        assertEquals("0123456789", call("GET", u, mapOf("Range" to "bytes=1-2,5-6")).text)   // multi-range ignored
        assertEquals("0123456789", call("GET", u, mapOf("Range" to "items=1-2")).text)
        assertEquals("0123456789", call("GET", u, mapOf("Range" to "bytes=5-2")).text)
    })

    @Test fun ifRangeMismatchSendsWholeFileAndMatchResumes() = withServer({ u ->
        assertEquals("0123456789", call("GET", u, mapOf("Range" to "bytes=5-", "If-Range" to "\"stale\"")).text)
        assertEquals("56789", call("GET", u, mapOf("Range" to "bytes=5-", "If-Range" to "\"1-10\"")).text)
    })

    @Test fun headReturnsHeadersWithoutBodyOrEvents() {
        val ev = mutableListOf<ShareEvent>()
        withServer({ u ->
            val r = call("HEAD", u)
            assertEquals(200, r.code); assertEquals(0, r.body.size); assertEquals("10", r.headers["content-length"])
        }, ev)
        assertTrue(ev.none { it is ShareEvent.DownloadStarted })
    }
}

class ResumableUploadTest {
    private class Mem : FileSink {
        val done = mutableMapOf<String, ByteArray>(); var aborted = 0; var freeBytes = Long.MAX_VALUE
        override fun save(name: String, data: InputStream, size: Long): String { done[name] = data.readBytes(); return name }
        override fun hasSpace(size: Long) = size <= freeBytes
        override fun begin(name: String, size: Long): IncomingUpload {
            val buf = ByteArrayOutputStream()
            return object : IncomingUpload {
                override fun write(buf2: ByteArray, off: Int, len: Int) = buf.write(buf2, off, len)
                override fun finish(): String { done[name] = buf.toByteArray(); return name }
                override fun abort() { aborted++ }
            }
        }
    }

    private fun run(reg: DeviceRegistry? = null, sink: Mem = Mem(), events: MutableList<ShareEvent> = mutableListOf(), block: (String, Mem) -> Unit) {
        val s = ShareServer(Mode.RECEIVE, "secret", sink = sink, registry = reg, onEvent = { events += it })
        s.start()
        try { block("http://127.0.0.1:${s.listeningPort}", sink) } finally { s.stop() }
    }

    private fun start(base: String, name: String, size: Int) =
        call("POST", "$base/upload/start?t=secret&name=$name&size=$size")

    private fun id(r: Http) = Regex("\"id\":\"([0-9a-f]+)\"").find(r.text)!!.groupValues[1]
    private fun chunk(base: String, id: String, off: Int, data: ByteArray) =
        call("PUT", "$base/upload/chunk?t=secret&id=$id&offset=$off", mapOf("Content-Type" to "application/octet-stream"), data)

    @Test fun wholeFileInChunksIsAssembledInOrder() = run { base, sink ->
        val payload = ByteArray(10_000) { (it % 251).toByte() }
        val id = id(start(base, "big.bin", payload.size))
        var off = 0
        while (off < payload.size) {
            val end = minOf(off + 3000, payload.size)
            val r = chunk(base, id, off, payload.copyOfRange(off, end))
            assertEquals(200, r.code)
            off = end
            if (off == payload.size) assertTrue(r.text.contains("\"done\":true"))
        }
        assertContentEquals(payload, sink.done["big.bin"])
    }

    @Test fun resumeAfterDropAskStatusAndContinue() = run { base, sink ->
        val payload = "ABCDEFGHIJ".repeat(100).toByteArray()
        val id = id(start(base, "r.bin", payload.size))
        assertEquals(200, chunk(base, id, 0, payload.copyOfRange(0, 400)).code)
        // connection "dropped": client asks where it stands
        assertTrue(call("GET", "$base/upload/status?t=secret&id=$id").text.contains("\"offset\":400"))
        // a retry of the chunk it already sent is refused with the real offset
        val dup = chunk(base, id, 0, payload.copyOfRange(0, 400))
        assertEquals(409, dup.code); assertTrue(dup.text.contains("\"offset\":400"))
        // a gap is refused too
        assertEquals(409, chunk(base, id, 600, payload.copyOfRange(600, 700)).code)
        assertEquals(200, chunk(base, id, 400, payload.copyOfRange(400, 1000)).code)
        assertContentEquals(payload, sink.done["r.bin"])
    }

    @Test fun tooMuchDataAbortsAndNothingIsSaved() = run { base, sink ->
        val id = id(start(base, "x.bin", 10))
        assertEquals(400, chunk(base, id, 0, ByteArray(11)).code)
        assertTrue(sink.done.isEmpty()); assertEquals(1, sink.aborted)
        assertEquals(404, chunk(base, id, 0, ByteArray(5)).code)
    }

    @Test fun emptyFileCompletesImmediately() = run { base, sink ->
        assertTrue(start(base, "empty.txt", 0).text.contains("\"done\":true"))
        assertEquals(0, sink.done["empty.txt"]!!.size)
    }

    @Test fun namesAreSanitizedAndSpaceIsChecked() = run(sink = Mem().apply { freeBytes = 50 }) { base, sink ->
        assertEquals(507, start(base, "big.bin", 100).code)
        val id = id(start(base, "../../evil.txt", 3))
        chunk(base, id, 0, "abc".toByteArray())
        assertEquals("abc", sink.done["evil.txt"]!!.toString(Charsets.UTF_8))
    }

    @Test fun oversizedChunkIsRefused() = run { base, _ ->
        val id = id(start(base, "z.bin", 40_000_000))
        val c = URL("$base/upload/chunk?t=secret&id=$id&offset=0").openConnection() as HttpURLConnection
        c.requestMethod = "PUT"; c.doOutput = true; c.setFixedLengthStreamingMode(17L * 1024 * 1024)
        val code = try { c.outputStream.use { it.write(ByteArray(64 * 1024)) }; c.responseCode } catch (e: java.io.IOException) { 413 }
        assertEquals(413, code)
    }

    @Test fun stoppingTheServerAbortsPartialUploads() {
        val sink = Mem()
        val s = ShareServer(Mode.RECEIVE, "secret", sink = sink); s.start()
        val base = "http://127.0.0.1:${s.listeningPort}"
        val id = id(start(base, "p.bin", 100)); chunk(base, id, 0, ByteArray(10))
        s.stop()
        assertEquals(1, sink.aborted); assertTrue(sink.done.isEmpty())
    }

    @Test fun tokenAndApprovalStillApply() {
        val reg = DeviceRegistry()
        run(reg) { base, sink ->
            assertEquals(403, call("POST", "$base/upload/start?t=bad&name=a&size=1").code)
            val first = call("POST", "$base/upload/start?t=secret&name=a&size=1")          // new device: waiting page, no upload begun
            assertTrue(first.text.contains("Waiting for approval"))
            assertTrue(sink.done.isEmpty())
        }
    }

    @Test fun progressEventsAreEmitted() {
        val ev = mutableListOf<ShareEvent>()
        run(events = ev) { base, _ ->
            val id = id(start(base, "e.bin", 20)); chunk(base, id, 0, ByteArray(10))
        }
        assertTrue(ev.any { it is ShareEvent.UploadProgress && it.received == 10L && it.total == 20L })
    }

    @Test fun aPhoneThatPausesMidChunkIsNotCutOff() = run { base, sink ->
        // regression: NanoHTTPD's default 5 s read timeout dropped uploads when the sender stalled (e.g. reading a big file)
        val id = id(start(base, "slow.bin", 1000))
        val port = URL(base).port
        val body = ByteArray(1000) { 7 }
        java.net.Socket("127.0.0.1", port).use { sock ->
            sock.soTimeout = 20_000
            val out = sock.getOutputStream()
            out.write("PUT /upload/chunk?t=secret&id=$id&offset=0 HTTP/1.1\r\nHost: x\r\nContent-Length: 1000\r\n\r\n".toByteArray())
            out.write(body, 0, 400); out.flush()
            Thread.sleep(6_500)                                   // longer than the old 5 s limit
            out.write(body, 400, 600); out.flush()
            val reply = sock.getInputStream().bufferedReader().readLine()
            assertTrue(reply.contains("200"), "got: $reply")
        }
        assertEquals(1000, sink.done["slow.bin"]!!.size)
    }
}
