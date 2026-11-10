package com.touchshare.core

import java.io.InputStream

data class ShareFile(val id: String, val name: String, val size: Long, val mime: String)

/** Files offered by Send mode. */
interface FileSource {
    fun list(): List<ShareFile>
    fun open(id: String): InputStream?

    /** Opens a file starting at [offset] (used for resumed downloads). Override for a faster seek. */
    fun openAt(id: String, offset: Long): InputStream? {
        val s = open(id) ?: return null
        var left = offset
        while (left > 0) {
            val n = s.skip(left)
            if (n <= 0) { if (s.read() < 0) break else left-- } else left -= n
        }
        return s
    }
}

/** One file being received in pieces; bytes arrive in order and are written straight to their destination. */
interface IncomingUpload {
    fun write(buf: ByteArray, off: Int, len: Int)
    /** All bytes received: make the file visible and return its final name. */
    fun finish(): String
    /** Give up: remove anything partially written. */
    fun abort()
}

/** Where Receive mode stores incoming files. */
interface FileSink {
    /** Stores a whole file and returns its final saved name. */
    fun save(name: String, data: InputStream, size: Long): String

    /** Starts a resumable upload of [size] bytes. The default buffers to a temp file then calls [save]. */
    fun begin(name: String, size: Long): IncomingUpload {
        val tmp = java.io.File.createTempFile("ts-up", ".part")
        val out = java.io.FileOutputStream(tmp)
        return object : IncomingUpload {
            override fun write(buf: ByteArray, off: Int, len: Int) = out.write(buf, off, len)
            override fun finish(): String {
                out.close()
                try { return java.io.FileInputStream(tmp).use { save(name, it, tmp.length()) } } finally { tmp.delete() }
            }
            override fun abort() { try { out.close() } catch (_: Exception) {}; tmp.delete() }
        }
    }

    /** False if the device clearly cannot hold [size] more bytes. */
    fun hasSpace(size: Long): Boolean = true
}

/** Read-only art/CSS the web pages may fetch (served under /assets/<name>). */
interface StaticAssets {
    fun open(name: String): InputStream?
}

enum class Mode { SEND, RECEIVE }

sealed class ShareEvent {
    data class DownloadStarted(val name: String) : ShareEvent()
    data class DownloadFinished(val name: String) : ShareEvent()
    data class UploadStarted(val name: String, val size: Long) : ShareEvent()
    data class UploadProgress(val name: String, val received: Long, val total: Long) : ShareEvent()
    data class Uploaded(val name: String, val savedAs: String, val size: Long) : ShareEvent()
    data class Rejected(val reason: String) : ShareEvent()
    data class Failed(val name: String, val message: String) : ShareEvent()
}
