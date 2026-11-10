package com.touchshare.core

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.*

class ApprovalTest {
    private class Src : FileSource {
        override fun list() = listOf(ShareFile("1", "a.txt", 5, "text/plain"))
        override fun open(id: String): InputStream? = ByteArrayInputStream("hello".toByteArray())
    }

    private class Resp(val code: Int, val body: String, val cookie: String?)

    private fun req(url: String, cookie: String? = null): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        if (cookie != null) c.setRequestProperty("Cookie", "ts_dev=$cookie")
        c.setRequestProperty("User-Agent", "TestBrowser/1.0")
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.readBytes()?.toString(Charsets.UTF_8) ?: ""
        val set = c.getHeaderField("Set-Cookie")?.substringBefore(';')?.substringAfter("ts_dev=")
        return Resp(code, body, set)
    }

    @Test fun registryDecisionsAndLimits() {
        val pend = mutableListOf<Device>(); val dec = mutableListOf<Pair<String, Boolean>>()
        val r = DeviceRegistry(maxPending = 2, onPending = { pend += it }, onDecision = { d, ok -> dec += d.id to ok })
        val a = r.register("10.0.0.2", "ua")!!; val b = r.register("10.0.0.3", "ua")!!
        assertNull(r.register("10.0.0.4", "ua"))              // cap reached
        assertEquals(DeviceState.PENDING, r.state(a.id))
        assertTrue(r.allow(a.id)); assertFalse(r.allow(a.id)) // decided once
        assertEquals(DeviceState.ALLOWED, r.state(a.id))
        assertTrue(r.deny(b.id)); assertTrue(r.isIpDenied("10.0.0.3")); assertFalse(r.isIpDenied("10.0.0.2"))
        assertEquals(2, pend.size); assertEquals(2, dec.size)
        assertNotNull(r.register("10.0.0.4", "ua"))           // room again once decided
    }

    @Test fun newDeviceWaitsThenGetsInAfterAllow() {
        val reg = DeviceRegistry()
        val s = ShareServer(Mode.SEND, "secret", source = Src(), registry = reg)
        s.start(); val base = "http://127.0.0.1:${s.listeningPort}"
        try {
            val first = req("$base/?t=secret")
            assertEquals(200, first.code); assertTrue(first.body.contains("Waiting for approval"))
            assertFalse(first.body.contains("a.txt"))
            val id = assertNotNull(first.cookie)
            assertEquals(1, reg.pending().size)
            // pending device cannot download or see the list, and /status says pending
            assertTrue(req("$base/f/1?t=secret", id).body.contains("Waiting for approval"))
            assertTrue(req("$base/status?t=secret", id).body.contains("pending"))
            assertTrue(reg.allow(id))
            assertTrue(req("$base/status?t=secret", id).body.contains("allowed"))
            assertTrue(req("$base/?t=secret", id).body.contains("a.txt"))
            assertEquals("hello", req("$base/f/1?t=secret", id).body)
            assertEquals(1, reg.pending().size + 1)           // nothing new was registered
        } finally { s.stop() }
    }

    @Test fun deniedDeviceStaysBlockedEvenWithFreshCookie() {
        val reg = DeviceRegistry()
        val s = ShareServer(Mode.SEND, "secret", source = Src(), registry = reg)
        s.start(); val base = "http://127.0.0.1:${s.listeningPort}"
        try {
            val id = assertNotNull(req("$base/?t=secret").cookie)
            reg.deny(id)
            assertEquals(403, req("$base/?t=secret", id).code)
            assertEquals(403, req("$base/?t=secret").code)     // cleared cookies, same IP
            assertEquals(403, req("$base/status?t=secret", id).code)
        } finally { s.stop() }
    }

    @Test fun tokenStillCheckedBeforeApproval() {
        val reg = DeviceRegistry()
        val s = ShareServer(Mode.SEND, "secret", source = Src(), registry = reg)
        s.start(); val base = "http://127.0.0.1:${s.listeningPort}"
        try {
            assertEquals(403, req("$base/?t=nope").code)
            assertTrue(reg.pending().isEmpty())               // bad-token requests never reach the host
        } finally { s.stop() }
    }

    @Test fun forgedOrUnknownCookieIsTreatedAsNewDevice() {
        val reg = DeviceRegistry()
        val s = ShareServer(Mode.SEND, "secret", source = Src(), registry = reg)
        s.start(); val base = "http://127.0.0.1:${s.listeningPort}"
        try {
            val r = req("$base/?t=secret", "deadbeefdeadbeef")
            assertTrue(r.body.contains("Waiting for approval"))
            assertNotEquals("deadbeefdeadbeef", r.cookie)
        } finally { s.stop() }
    }

    @Test fun tooManyWaitingDevicesGets429() {
        val reg = DeviceRegistry(maxPending = 1)
        val s = ShareServer(Mode.SEND, "secret", source = Src(), registry = reg)
        s.start(); val base = "http://127.0.0.1:${s.listeningPort}"
        try {
            assertEquals(200, req("$base/?t=secret").code)
            assertEquals(429, req("$base/?t=secret").code)
        } finally { s.stop() }
    }
}

class AssetsTest {
    private val assets = object : StaticAssets {
        override fun open(name: String): InputStream? = if (name == "page_05.webp") ByteArrayInputStream("ART".toByteArray()) else null
    }

    private fun get(url: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        val code = c.responseCode
        return code to ((if (code < 400) c.inputStream else c.errorStream)?.readBytes()?.toString(Charsets.UTF_8) ?: "")
    }

    @Test fun artIsServedToWaitingDevicesButStillNeedsToken() {
        val reg = DeviceRegistry()
        val s = ShareServer(Mode.RECEIVE, "secret", sink = null, registry = reg, assets = assets)
        s.start(); val base = "http://127.0.0.1:${s.listeningPort}"
        try {
            assertEquals(200 to "ART", get("$base/assets/page_05.webp?t=secret"))   // no cookie, still pending
            assertTrue(reg.pending().isEmpty())                                      // asset fetch does not register a device
            assertEquals(403, get("$base/assets/page_05.webp").first)                // token required
            assertEquals(404, get("$base/assets/missing.webp?t=secret").first)
            assertEquals(404, get("$base/assets/..%2Fsecret.txt?t=secret").first)    // no path tricks
            assertEquals(404, get("$base/assets/.hidden?t=secret").first)
        } finally { s.stop() }
    }

    @Test fun pagesReferenceTheArtWithTheToken() {
        assertTrue(Pages.listPage("abc123", emptyList()).contains("/assets/page_05.webp?t=abc123"))
        assertTrue(Pages.uploadPage("abc123").contains("/assets/page_05.webp?t=abc123"))
        assertTrue(Pages.waitingPage("abc123").contains("/assets/page_05.webp?t=abc123"))
    }
}
