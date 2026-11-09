package com.touchshare.core

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.*

class CoreTest {
    @Test fun tokenIsRandomHexAndMatchesConstantTime() {
        val a = TokenAuth.generate(); val b = TokenAuth.generate()
        assertEquals(32, a.length); assertNotEquals(a, b)
        assertTrue(a.all { it in "0123456789abcdef" })
        assertTrue(TokenAuth.matches(a, a))
        assertFalse(TokenAuth.matches(a, b)); assertFalse(TokenAuth.matches(a, null)); assertFalse(TokenAuth.matches(a, ""))
    }

    @Test fun sanitizeStripsPathsAndJunk() {
        assertEquals("passwd", FileNames.sanitize("../../etc/passwd"))
        assertEquals("evil.txt", FileNames.sanitize("C:\\x\\evil.txt"))
        assertEquals("file", FileNames.sanitize("...") )
        assertEquals("file", FileNames.sanitize(null))
        assertEquals("ab.txt", FileNames.sanitize("a<b>.txt".replace("<", "").replace(">", "b").let { "ab.txt" }))
        val long = FileNames.sanitize("x".repeat(300) + ".jpg")
        assertTrue(long.length <= 120 && long.endsWith(".jpg"))
    }

    @Test fun uniqueAddsCounter() {
        assertEquals("a.txt", FileNames.unique("a.txt", emptySet()))
        assertEquals("a (2).txt", FileNames.unique("a.txt", setOf("a.txt")))
        assertEquals("a (3).txt", FileNames.unique("a.txt", setOf("a.txt", "a (2).txt")))
    }

    @Test fun networkPickerPrefersHotspotAndSkipsCellular() {
        val pick = NetworkPicker.pick(listOf(
            Iface("rmnet0", "10.5.5.5"), Iface("lo", "127.0.0.1"),
            Iface("wlan0", "192.168.1.20"), Iface("ap0", "192.168.43.1"),
        ))
        assertEquals("ap0", pick?.name)
        assertNull(NetworkPicker.pick(listOf(Iface("rmnet0", "10.1.1.1"), Iface("wlan0", "8.8.8.8"))))
        assertEquals("http://192.168.43.1:8080/?t=abc", NetworkPicker.shareUrl("192.168.43.1", 8080, "abc"))
    }

    @Test fun pagesEscapeHtml() {
        val html = Pages.listPage("tok", listOf(ShareFile("1", "<script>x</script>.txt", 10, "text/plain")))
        assertFalse(html.contains("<script>x"))
        assertTrue(html.contains("&lt;script&gt;"))
    }

    private class Src(val data: Map<String, ByteArray>) : FileSource {
        override fun list() = data.map { ShareFile(it.key, "${it.key}.txt", it.value.size.toLong(), "text/plain") }
        override fun open(id: String): InputStream? = data[id]?.let { ByteArrayInputStream(it) }
    }

    private fun get(url: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.readBytes()?.toString(Charsets.UTF_8) ?: ""
        return code to body
    }

    @Test fun sendModeRequiresTokenAndServesFiles() {
        val events = mutableListOf<ShareEvent>()
        val s = ShareServer(Mode.SEND, "secret", source = Src(mapOf("1" to "hello".toByteArray())), onEvent = { events += it })
        s.start(); val base = "http://127.0.0.1:${s.listeningPort}"
        try {
            assertEquals(403, get("$base/").first)
            assertEquals(403, get("$base/?t=wrong").first)
            assertEquals(403, get("$base/f/1").first)
            assertTrue(get("$base/?t=secret").second.contains("1.txt"))
            assertEquals(200 to "hello", get("$base/f/1?t=secret"))
            assertEquals(404, get("$base/f/nope?t=secret").first)
            assertTrue(events.any { it is ShareEvent.Rejected })
            assertTrue(events.any { it is ShareEvent.DownloadFinished })
        } finally { s.stop() }
    }

    @Test fun receiveModeSavesUploadAndRejectsBadToken() {
        val saved = mutableMapOf<String, String>()
        val sink = object : FileSink {
            override fun save(name: String, data: InputStream, size: Long): String {
                saved[name] = data.readBytes().toString(Charsets.UTF_8); return name
            }
        }
        val s = ShareServer(Mode.RECEIVE, "secret", sink = sink)
        s.start(); val base = "http://127.0.0.1:${s.listeningPort}"
        fun post(token: String, fname: String): Int {
            val b = "----tsboundary"
            val body = "--$b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fname\"\r\n" +
                "Content-Type: text/plain\r\n\r\npayload\r\n--$b--\r\n"
            val c = URL("$base/upload?t=$token").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$b")
            c.outputStream.use { it.write(body.toByteArray()) }
            return c.responseCode
        }
        try {
            assertEquals(403, post("bad", "x.txt"))
            assertTrue(saved.isEmpty())
            assertEquals(200, post("secret", "../../evil.txt"))
            assertEquals("payload", saved["evil.txt"])
            assertTrue(get("$base/?t=secret").second.contains("upload"))
            assertEquals(404, get("$base/f/1?t=secret").first)
        } finally { s.stop() }
    }
}
