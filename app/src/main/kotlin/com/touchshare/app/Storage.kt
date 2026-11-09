package com.touchshare.app

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.touchshare.core.FileNames
import com.touchshare.core.FileSink
import com.touchshare.core.FileSource
import com.touchshare.core.ShareFile
import java.io.File
import java.io.InputStream

/** Send mode: files the user picked with the system picker (no storage permission needed). */
class UriSource(private val cr: ContentResolver, uris: List<Uri>) : FileSource {
    private val items: List<Pair<ShareFile, Uri>> = uris.mapIndexed { i, u ->
        var name = "file$i"; var size = -1L
        cr.query(u, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
            }
        }
        ShareFile(i.toString(), FileNames.sanitize(name), size, cr.getType(u) ?: "application/octet-stream") to u
    }

    override fun list() = items.map { it.first }
    override fun open(id: String): InputStream? = items.firstOrNull { it.first.id == id }?.let { cr.openInputStream(it.second) }
}

/** Receive mode: Downloads/TouchShare via MediaStore on Android 10+, app-specific Downloads before that. */
class DownloadsSink(private val ctx: Context) : FileSink {
    private val used = HashSet<String>()

    @Synchronized
    override fun save(name: String, data: InputStream, size: Long): String {
        val finalName = FileNames.unique(name, used).also { used += it }
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, finalName)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/TouchShare")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val cr = ctx.contentResolver
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: error("MediaStore insert failed")
            try {
                cr.openOutputStream(uri)!!.use { data.copyTo(it) }
                cv.clear(); cv.put(MediaStore.Downloads.IS_PENDING, 0); cr.update(uri, cv, null, null)
            } catch (e: Exception) { cr.delete(uri, null, null); throw e }
        } else {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
            var target = File(dir, finalName)
            if (target.exists()) target = File(dir, FileNames.unique(finalName, dir.list()?.toSet() ?: emptySet()))
            target.outputStream().use { data.copyTo(it) }
        }
        return finalName
    }
}
