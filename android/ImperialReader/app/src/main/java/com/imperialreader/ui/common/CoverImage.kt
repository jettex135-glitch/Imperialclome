package com.imperialreader.ui.common

import android.net.Uri
import android.widget.ImageView
import coil.imageLoader
import coil.load
import com.imperialreader.App

fun ImageView.loadCover(url: String?, headers: Map<String, String> = emptyMap()) {
    val imageLoader = if (url.isWitchToonsCover()) {
        App.instance.witchToonsCoverImageLoader
    } else {
        context.imageLoader
    }
    load(url, imageLoader) {
        headers.forEach { (key, value) -> addHeader(key, value) }
    }
}

private fun String?.isWitchToonsCover(): Boolean {
    if (isNullOrBlank()) return false
    val uri = Uri.parse(this)
    val host = uri.host ?: return false
    val isWitchToonsHost = host.equals("witchtoons.net", ignoreCase = true) ||
        host.endsWith(".witchtoons.net", ignoreCase = true)
    return isWitchToonsHost && uri.path?.startsWith("/uploads/series/") == true
}
