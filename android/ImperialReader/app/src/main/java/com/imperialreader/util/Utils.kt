package com.imperialreader.util

import android.content.Context
import android.text.format.DateUtils
import android.widget.Toast
import com.imperialreader.App

val Context.app: App get() = applicationContext as App

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

/** Makes a string safe to use as a file/folder name. */
fun String.sanitize(): String =
    replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim().trimEnd('.').take(120).ifBlank { "_" }

val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif")

fun isImageName(name: String?): Boolean {
    val ext = name?.substringAfterLast('.', "")?.lowercase() ?: return false
    return ext in IMAGE_EXTENSIONS
}

fun isReaderPageName(name: String?): Boolean {
    val extension = name?.substringAfterLast('.', "")?.lowercase() ?: return false
    return extension in IMAGE_EXTENSIONS || extension == "mp4" || extension == "psd" || extension == "psb"
}

fun mimeForExt(ext: String): String = when (ext.lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "avif" -> "image/avif"
    "epub" -> "application/epub+zip"
    "bmp" -> "image/bmp"
    "mp4" -> "video/mp4"
    else -> "application/octet-stream"
}

/** Compares strings the way humans expect: "page2" < "page10". */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ei
                j = ej
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}

private val chapterNumberRegex = Regex("""(?i)(?:ch(?:apter|\.)?|ep(?:isode|\.)?|#)\s*(\d+(?:\.\d+)?)""")
private val anyNumberRegex = Regex("""(\d+(?:\.\d+)?)""")

fun parseChapterNumber(name: String): Float {
    chapterNumberRegex.find(name)?.groupValues?.get(1)?.toFloatOrNull()?.let { return it }
    return anyNumberRegex.findAll(name).lastOrNull()?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
}

fun relativeTime(ms: Long): String {
    if (ms <= 0L) return ""
    return DateUtils.getRelativeTimeSpanString(
        ms, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS
    ).toString()
}

fun statusLabel(status: Int): String = when (status) {
    1 -> "Ongoing"
    2 -> "Completed"
    3 -> "Hiatus"
    4 -> "Axed"
    else -> "Unknown"
}
