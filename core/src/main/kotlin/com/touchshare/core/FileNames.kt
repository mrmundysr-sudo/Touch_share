package com.touchshare.core

/** Makes untrusted file names safe to store or put in headers. */
object FileNames {
    private const val MAX_LEN = 120

    fun sanitize(raw: String?): String {
        var n = (raw ?: "").substringAfterLast('/').substringAfterLast('\\')
        n = n.filter { it.code >= 32 && it != '\u007f' && it !in "<>:\"|?*" }.trim().trimStart('.')
        if (n.length > MAX_LEN) {
            val dot = n.lastIndexOf('.')
            val ext = if (dot > 0 && n.length - dot <= 10) n.substring(dot) else ""
            n = n.substring(0, MAX_LEN - ext.length) + ext
        }
        return n.ifEmpty { "file" }
    }

    /** Returns [name] or "name (2).ext" etc. so it does not collide with [existing]. */
    fun unique(name: String, existing: Set<String>): String {
        if (name !in existing) return name
        val dot = name.lastIndexOf('.')
        val (base, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        var i = 2
        while ("$base ($i)$ext" in existing) i++
        return "$base ($i)$ext"
    }
}
