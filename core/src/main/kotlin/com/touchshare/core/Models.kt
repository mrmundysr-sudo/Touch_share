package com.touchshare.core

import java.io.InputStream

data class ShareFile(val id: String, val name: String, val size: Long, val mime: String)

/** Files offered by Send mode. */
interface FileSource {
    fun list(): List<ShareFile>
    fun open(id: String): InputStream?
}

/** Where Receive mode stores incoming files. Returns the final saved name. */
interface FileSink {
    fun save(name: String, data: InputStream, size: Long): String
}

/** Read-only art/CSS the web pages may fetch (served under /assets/<name>). */
interface StaticAssets {
    fun open(name: String): InputStream?
}

enum class Mode { SEND, RECEIVE }

sealed class ShareEvent {
    data class DownloadStarted(val name: String) : ShareEvent()
    data class DownloadFinished(val name: String) : ShareEvent()
    data class Uploaded(val name: String, val savedAs: String, val size: Long) : ShareEvent()
    data class Rejected(val reason: String) : ShareEvent()
    data class Failed(val name: String, val message: String) : ShareEvent()
}
