package com.imperialreader.ui.reader

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.imperialreader.data.source.MangaFirePageTransform
import com.imperialreader.util.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import okhttp3.Request

object GalleryPageSaver {
    const val WRITE_PERMISSION = Manifest.permission.WRITE_EXTERNAL_STORAGE

    fun needsLegacyPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    fun hasLegacyPermission(context: Context): Boolean =
        !needsLegacyPermission() ||
            ContextCompat.checkSelfPermission(context, WRITE_PERMISSION) == PackageManager.PERMISSION_GRANTED

    suspend fun copyDisplayedPage(view: ImageView): Bitmap? {
        val drawable = withContext(Dispatchers.Main.immediate) { view.drawable } ?: return null
        if (drawable is BitmapDrawable) {
            return withContext(Dispatchers.IO) {
                val bitmap = drawable.bitmap
                if (!bitmap.isRecycled) bitmap.copy(Bitmap.Config.ARGB_8888, false) else null
            }
        }
        return withContext(Dispatchers.Main.immediate) {
                val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: view.width
                val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: view.height
                if (width <= 0 || height <= 0) return@withContext null
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val bounds = Rect(drawable.bounds)
                try {
                    drawable.setBounds(0, 0, width, height)
                    drawable.draw(Canvas(bitmap))
                    bitmap
                } catch (_: Exception) {
                    bitmap.recycle()
                    null
                } finally {
                    drawable.bounds = bounds
                }
        }
    }

    suspend fun loadPageBitmap(context: Context, url: String, headers: Map<String, String>): Bitmap =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(url)
            val localFile = if (uri.scheme.equals("file", true)) uri.path?.let(::File) else null
            val tempFile = if (localFile == null) File.createTempFile("page-save-", ".image", context.cacheDir) else null
            try {
                if (localFile != null) {
                    if (!localFile.isFile) throw IOException("The selected page file is unavailable")
                } else {
                    val output = tempFile ?: throw IOException("Could not prepare a page image")
                    val input = when (uri.scheme?.lowercase()) {
                        "http", "https" -> {
                            val cleanUrl = uri.buildUpon().fragment(null).build().toString()
                            val request = Request.Builder().url(cleanUrl).apply {
                                headers.forEach { (name, value) -> header(name, value) }
                            }.build()
                            context.app.network.client.newCall(request).execute().let { response ->
                                if (!response.isSuccessful) {
                                    response.close()
                                    throw IOException("Image request failed with HTTP ${response.code}")
                                }
                                response.body?.byteStream()?.use { body -> output.outputStream().use(body::copyTo) }
                                    ?: run {
                                        response.close()
                                        throw IOException("The image response was empty")
                                    }
                                response.close()
                            }
                            null
                        }
                        else -> context.contentResolver.openInputStream(uri)
                            ?: throw IOException("The selected page file is unavailable")
                    }
                    if (input != null) input.use { source -> output.outputStream().use { source.copyTo(it) } }
                }

                val path = (localFile ?: tempFile)?.absolutePath
                    ?: throw IOException("The selected page image is unavailable")
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("The page image could not be decoded")
                var sample = 1
                while (bounds.outWidth / sample > 12_000 || bounds.outHeight / sample > 12_000 ||
                    bounds.outWidth.toLong() * bounds.outHeight / (sample.toLong() * sample) > 40_000_000L
                ) sample *= 2
                val bitmap = BitmapFactory.decodeFile(
                    path,
                    BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inSampleSize = sample
                    }
                ) ?: throw IOException("The page image could not be decoded")
                val offset = MangaFirePageTransform.offset(url)
                if (offset == null) bitmap else MangaFirePageTransform.unscramble(bitmap, offset).also {
                    if (it !== bitmap) bitmap.recycle()
                }
            } finally {
                tempFile?.delete()
            }
        }

    suspend fun save(context: Context, source: Bitmap): String = withContext(Dispatchers.IO) {
        if (!hasLegacyPermission(context)) throw IOException("Storage permission is needed to save pages")
        val name = "ImperialReader-${System.currentTimeMillis()}.png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ImperialReader")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Gallery could not create an image entry")
            try {
                val output = resolver.openOutputStream(uri, "w")
                    ?: throw IOException("Gallery image output is unavailable")
                output.use {
                    if (!source.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                        throw IOException("Could not encode the selected page")
                    }
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri.toString()
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val directory = File(pictures, "ImperialReader")
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("Could not create the gallery folder")
            }
            val file = File(directory, name)
            try {
                file.outputStream().use {
                    if (!source.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                        throw IOException("Could not encode the selected page")
                    }
                }
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DATA, file.absolutePath)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                }
                context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Gallery could not register the saved page")
                file.absolutePath
            } catch (e: Exception) {
                file.delete()
                throw e
            }
        }
    }
}
