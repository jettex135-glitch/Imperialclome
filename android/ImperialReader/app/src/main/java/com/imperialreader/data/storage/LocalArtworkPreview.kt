package com.imperialreader.data.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.imperialreader.data.network.await
import com.imperialreader.util.app
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.InflaterInputStream
import java.util.zip.ZipException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

object LocalArtworkPreview {

    fun supports(name: String?): Boolean {
        val extension = name?.substringAfterLast('.', "")?.lowercase() ?: return false
        return extension == "psd" || extension == "psb" || extension == "clip"
    }

    fun readerPage(context: Context, document: DocumentFile): String {
        val directory = File(context.cacheDir, "local-artwork")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create the local artwork preview folder")
        }
        val isPhotoshop = document.name.orEmpty().let {
            it.endsWith(".psd", true) || it.endsWith(".psb", true)
        }
        val decoderVersion = if (isPhotoshop) "_photoshop2" else ""
        val key = "${document.uri.hashCode()}_${document.lastModified()}_${document.length()}$decoderVersion"
        val target = File(directory, "$key.png")
        if ((!target.exists() || target.length() == 0L) && !extract(context, document, target)) {
            throw IOException("Could not decode a preview from ${document.name}")
        }
        return Uri.fromFile(target).toString()
    }

    fun extract(context: Context, document: DocumentFile, target: File): Boolean {
        val stageDirectory = File(context.cacheDir, "local-artwork").apply {
            if (!exists() && !mkdirs()) throw IOException("Could not create the local artwork preview folder")
        }
        val staged = File.createTempFile("artwork-", ".bin", stageDirectory)
        try {
            val input = context.contentResolver.openInputStream(document.uri)
                ?: throw IOException("Cannot open ${document.name}")
            input.use { source -> staged.outputStream().buffered().use { source.copyTo(it) } }

            return extractStaged(staged, document.name.orEmpty(), target)
        } finally {
            staged.delete()
        }
    }

    suspend fun readerPage(context: Context, url: String, headers: Map<String, String>): String {
        val extension = Uri.parse(url).lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase()
        if (extension !in PHOTOSHOP_EXTENSIONS) {
            throw IOException("Unsupported online artwork format: $extension")
        }
        val directory = File(context.cacheDir, "local-artwork").apply {
            if (!exists() && !mkdirs()) throw IOException("Could not create the local artwork preview folder")
        }
        val cacheKey = "${url.hashCode()}_${headers.toSortedMap().hashCode()}_photoshop2"
        val target = File(directory, "remote_$cacheKey.png")
        if (!target.exists() || target.length() == 0L) {
            val staged = File.createTempFile("remote-artwork-", ".$extension", directory)
            try {
                val request = okhttp3.Request.Builder().url(url).apply {
                    headers.forEach { (name, value) -> header(name, value) }
                }.build()
                try {
                    context.app.network.client.await(request).use { response ->
                        if (!response.isSuccessful) {
                            throw IOException("Could not load online artwork (HTTP ${response.code})")
                        }
                        val body = response.body ?: throw IOException("Online artwork response was empty")
                        if (body.contentLength() > MAX_REMOTE_ARTWORK_BYTES) {
                            throw IOException("Online artwork exceeds the supported download size")
                        }
                        body.byteStream().use { input ->
                            staged.outputStream().buffered().use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var total = 0L
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    if (total > MAX_REMOTE_ARTWORK_BYTES) {
                                        throw IOException("Online artwork exceeds the supported download size")
                                    }
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                    }
                    if (!extractStaged(staged, extension, target)) {
                        throw IOException("Could not decode online $extension artwork")
                    }
                } catch (e: Exception) {
                    target.delete()
                    throw e
                }
            } finally {
                staged.delete()
            }
        }
        return Uri.fromFile(target).toString()
    }

    private fun extractStaged(staged: File, name: String, target: File): Boolean {
        if (name.endsWith(".clip", true) && extractClipZipPreview(staged, target)) return true
        if (name.endsWith(".psd", true) && extractPsdThumbnail(staged, target)) return true
        if (name.endsWith(".psd", true) || name.endsWith(".psb", true)) {
            if (extractPsdComposite(staged, target)) return true
        }
        return extractEmbeddedImage(staged, target)
    }

    private fun extractClipZipPreview(file: File, target: File): Boolean {
        return try {
            ZipFile(file).use { zip ->
                val candidates = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.size in 1..MAX_CLIP_ENTRY_BYTES }
                    .filter { isClipImageEntry(it.name) || hasPreviewMarker(it.name) }
                    .sortedWith(
                        compareBy<ZipEntry> { clipPreviewPriority(it.name) }
                            .thenBy { if (isClipImageEntry(it.name)) 0 else 1 }
                            .thenByDescending { it.size }
                    )
                    .take(MAX_CLIP_CANDIDATES)
                    .toList()
                for (entry in candidates) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) continue
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
                    }
                    val bitmap = zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, options) }
                        ?: continue
                    save(bitmap, target)
                    return true
                }
                false
            }
        } catch (_: ZipException) {
            false
        }
    }

    private fun isClipImageEntry(path: String): Boolean {
        val extension = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return extension in PREVIEW_EXTENSIONS
    }

    private fun hasPreviewMarker(path: String): Boolean {
        val name = path.substringAfterLast('/').lowercase()
        return PREVIEW_MARKERS.any(name::contains)
    }

    private fun clipPreviewPriority(path: String): Int {
        val name = path.substringAfterLast('/').lowercase()
        return PREVIEW_MARKERS.indexOfFirst(name::contains).takeIf { it >= 0 } ?: PREVIEW_MARKERS.size
    }

    private fun extractPsdThumbnail(file: File, target: File): Boolean {
        RandomAccessFile(file, "r").use { input ->
            if (input.length() < PSD_HEADER_SIZE) return false
            val signature = ByteArray(4)
            input.readFully(signature)
            if (String(signature, Charsets.US_ASCII) != "8BPS") return false

            input.seek(26)
            val colorModeLength = readUInt32(input)
            input.seek(input.filePointer + colorModeLength)
            val resourcesLength = readUInt32(input)
            val end = input.filePointer + resourcesLength
            while (input.filePointer + 12 <= end) {
                input.readFully(signature)
                if (String(signature, Charsets.US_ASCII) != "8BIM") break
                val resourceId = input.readUnsignedShort()
                val nameLength = input.readUnsignedByte()
                input.seek(input.filePointer + nameLength + ((1 + nameLength) % 2))
                val dataLength = readUInt32(input)
                val dataStart = input.filePointer
                if (resourceId in THUMBNAIL_RESOURCE_IDS && dataLength >= THUMBNAIL_HEADER_SIZE) {
                    val format = readUInt32(input)
                    input.seek(dataStart + 20)
                    val compressedLength = readUInt32(input)
                    if (format == JPEG_THUMBNAIL_FORMAT &&
                        compressedLength in 1..MAX_THUMBNAIL_BYTES &&
                        compressedLength <= dataLength - THUMBNAIL_HEADER_SIZE
                    ) {
                        input.seek(dataStart + THUMBNAIL_HEADER_SIZE)
                        val jpeg = ByteArray(compressedLength.toInt())
                        input.readFully(jpeg)
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
                        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                            val options = BitmapFactory.Options().apply {
                                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
                            }
                            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
                            if (bitmap != null) {
                                save(bitmap, target)
                                return true
                            }
                        }
                    }
                }
                input.seek(dataStart + dataLength + (dataLength % 2))
            }
        }
        return false
    }

    private fun extractPsdComposite(file: File, target: File): Boolean {
        return try {
            RandomAccessFile(file, "r").use { input ->
                if (input.length() < PSD_HEADER_SIZE) return false
                val signature = ByteArray(4)
                input.readFully(signature)
                if (String(signature, Charsets.US_ASCII) != "8BPS") return false
                val version = input.readUnsignedShort()
                if (version !in PSD_VERSION..PSB_VERSION) return false
                val largeDocument = version == PSB_VERSION
                input.skipBytes(6)

                val channelCount = input.readUnsignedShort()
                val height = readUInt32(input)
                val width = readUInt32(input)
                val depth = input.readUnsignedShort()
                val colorMode = input.readUnsignedShort()
                val maxDimension = if (largeDocument) MAX_PSB_DIMENSION else MAX_PSD_DIMENSION
                if (width !in 1L..maxDimension.toLong() ||
                    height !in 1L..maxDimension.toLong() ||
                    channelCount !in 1..MAX_PSD_CHANNELS || depth !in setOf(8, 16) ||
                    (colorMode == PSD_INDEXED && depth != 8) ||
                    colorMode !in setOf(PSD_RGB, PSD_GRAYSCALE, PSD_INDEXED)
                ) return false
                if (width * height * channelCount * (depth / 8) > MAX_PSD_COMPOSITE_BYTES) {
                    return false
                }
                val widthPx = width.toInt()
                val heightPx = height.toInt()
                val baseChannels = when (colorMode) {
                    PSD_RGB -> 3
                    PSD_GRAYSCALE, PSD_INDEXED -> 1
                    else -> return false
                }
                if (channelCount < baseChannels) return false

                val colorDataLength = readSectionLength(input, largeDocument)
                if (!sectionFits(input, colorDataLength)) return false
                val colorDataStart = input.filePointer
                val palette = if (colorMode == PSD_INDEXED && colorDataLength >= PSD_PALETTE_SIZE) {
                    ByteArray(PSD_PALETTE_SIZE.toInt()).also(input::readFully)
                } else {
                    null
                }
                input.seek(colorDataStart + colorDataLength)
                val resourcesLength = readSectionLength(input, largeDocument)
                if (!sectionFits(input, resourcesLength)) return false
                input.seek(input.filePointer + resourcesLength)
                val layerMaskLength = readSectionLength(input, largeDocument)
                if (!sectionFits(input, layerMaskLength)) return false
                input.seek(input.filePointer + layerMaskLength)

                val compression = input.readUnsignedShort()
                if (compression !in PSD_RAW..PSD_ZIP_PREDICTION ||
                    (compression == PSD_ZIP_PREDICTION && depth != 8)
                ) return false

                val sample = psdSampleSize(widthPx, heightPx)
                val outputWidth = (widthPx + sample - 1) / sample
                val outputHeight = (heightPx + sample - 1) / sample
                val pixelCount = outputWidth * outputHeight
                val rowByteCount = widthPx * (depth / 8)
                val sampledChannels = Array(baseChannels + if (channelCount > baseChannels) 1 else 0) {
                    ByteArray(pixelCount)
                }
                val row = ByteArray(rowByteCount)
                val rowLengths = if (compression == PSD_RLE) {
                    val count = channelCount.toLong() * height
                    if (count > Int.MAX_VALUE) return false
                    IntArray(count.toInt()) {
                        val length = if (largeDocument) {
                            input.readInt().toLong() and 0xffffffffL
                        } else {
                            input.readUnsignedShort().toLong()
                        }
                        if (length > Int.MAX_VALUE) return false
                        length.toInt()
                    }.also { lengths ->
                        if (lengths.any { it > rowByteCount * 2 + 16 }) return false
                    }
                } else {
                    null
                }

                val imageStream = if (compression == PSD_ZIP || compression == PSD_ZIP_PREDICTION) {
                    InflaterInputStream(object : InputStream() {
                        override fun read(): Int = input.read()

                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                            input.read(buffer, offset, length)
                    })
                } else {
                    null
                }

                try {
                    for (channel in 0 until channelCount) {
                        val outputChannel = when {
                            channel < baseChannels -> channel
                            channel == baseChannels -> baseChannels
                            else -> -1
                        }
                        for (y in 0 until heightPx) {
                            when (compression) {
                                PSD_RAW -> input.readFully(row)
                                PSD_RLE -> {
                                    val length = rowLengths!![channel * heightPx + y]
                                    val packed = ByteArray(length)
                                    input.readFully(packed)
                                    if (!decodePackBits(packed, row)) return false
                                }
                                PSD_ZIP, PSD_ZIP_PREDICTION -> {
                                    if (!readFully(imageStream!!, row)) return false
                                    if (compression == PSD_ZIP_PREDICTION) undoPsdPrediction(row)
                                }
                            }
                            if (outputChannel >= 0 && y % sample == 0) {
                                val sampledY = y / sample
                                for (x in 0 until widthPx step sample) {
                                    val sampleIndex = x * (depth / 8)
                                    sampledChannels[outputChannel][sampledY * outputWidth + x / sample] =
                                        row[sampleIndex]
                                }
                            }
                        }
                    }
                } finally {
                    imageStream?.close()
                }

                if (colorMode == PSD_INDEXED && palette == null) return false
                val bitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
                try {
                    val pixels = IntArray(outputWidth)
                    val alphaChannel = if (channelCount > baseChannels) baseChannels else -1
                    for (y in 0 until outputHeight) {
                        for (x in 0 until outputWidth) {
                            val index = y * outputWidth + x
                            val alpha = if (alphaChannel >= 0) {
                                unsignedSample(sampledChannels[alphaChannel][index])
                            } else {
                                255
                            }
                            val red: Int
                            val green: Int
                            val blue: Int
                            when (colorMode) {
                                PSD_RGB -> {
                                    red = unsignedSample(sampledChannels[0][index])
                                    green = unsignedSample(sampledChannels[1][index])
                                    blue = unsignedSample(sampledChannels[2][index])
                                }
                                PSD_GRAYSCALE -> {
                                    red = unsignedSample(sampledChannels[0][index])
                                    green = red
                                    blue = red
                                }
                                else -> {
                                    val paletteIndex = sampledChannels[0][index].toInt() and 0xff
                                    red = palette!![paletteIndex].toInt() and 0xff
                                    green = palette[paletteIndex + 256].toInt() and 0xff
                                    blue = palette[paletteIndex + 512].toInt() and 0xff
                                }
                            }
                            pixels[x] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
                        }
                        bitmap.setPixels(pixels, 0, outputWidth, 0, y, outputWidth, 1)
                    }
                    save(bitmap, target)
                } catch (e: Exception) {
                    bitmap.recycle()
                    throw e
                }
                true
            }
        } catch (_: EOFException) {
            false
        } catch (_: ZipException) {
            false
        }
    }

    private fun sectionFits(input: RandomAccessFile, length: Long): Boolean =
        length <= input.length() - input.filePointer

    private fun readSectionLength(input: RandomAccessFile, largeDocument: Boolean): Long =
        if (largeDocument) input.readLong().also {
            if (it < 0L) throw IOException("Invalid large Photoshop section length")
        } else {
            readUInt32(input)
        }

    private fun psdSampleSize(width: Int, height: Int): Int {
        var sample = sampleSize(width, height)
        while ((width.toLong() + sample - 1) / sample *
            ((height.toLong() + sample - 1) / sample) > MAX_PSD_PREVIEW_PIXELS
        ) {
            sample *= 2
        }
        return sample
    }

    private fun decodePackBits(packed: ByteArray, output: ByteArray): Boolean {
        var inputIndex = 0
        var outputIndex = 0
        while (inputIndex < packed.size && outputIndex < output.size) {
            val control = packed[inputIndex++].toInt()
            when {
                control in 0..127 -> {
                    val count = control + 1
                    if (inputIndex + count > packed.size || outputIndex + count > output.size) return false
                    packed.copyInto(output, outputIndex, inputIndex, inputIndex + count)
                    inputIndex += count
                    outputIndex += count
                }
                control in -127..-1 -> {
                    if (inputIndex >= packed.size) return false
                    val count = 1 - control
                    if (outputIndex + count > output.size) return false
                    output.fill(packed[inputIndex++], outputIndex, outputIndex + count)
                    outputIndex += count
                }
                else -> Unit
            }
        }
        return outputIndex == output.size
    }

    private fun undoPsdPrediction(row: ByteArray) {
        for (index in 1 until row.size) {
            row[index] = (row[index].toInt() + row[index - 1].toInt()).toByte()
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count < 0) return false
            offset += count
        }
        return true
    }

    private fun unsignedSample(value: Byte): Int = value.toInt() and 0xff

    private fun extractEmbeddedImage(file: File, target: File): Boolean {
        val offsets = imageOffsets(file)
        val best = offsets.mapNotNull { offset ->
            val bounds = decodeBounds(file, offset) ?: return@mapNotNull null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
            else offset to bounds
        }.maxByOrNull { (_, bounds) -> bounds.outWidth.toLong() * bounds.outHeight }
            ?: return false
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(best.second.outWidth, best.second.outHeight)
        }
        val bitmap = decode(file, best.first, options) ?: return false
        save(bitmap, target)
        return true
    }

    private fun imageOffsets(file: File): List<Long> {
        val found = LinkedHashSet<Long>()
        val buffer = ByteArray(SCAN_CHUNK + 11)
        var carry = 0
        var consumed = 0L
        RandomAccessFile(file, "r").use { input ->
            while (found.size < MAX_IMAGE_CANDIDATES) {
                val count = input.read(buffer, carry, SCAN_CHUNK)
                if (count < 0) break
                val total = carry + count
                val base = consumed - carry
                for (index in 0..(total - 3).coerceAtLeast(-1)) {
                    if (matchesPng(buffer, index, total) || matchesJpeg(buffer, index, total) ||
                        matchesWebp(buffer, index, total) || matchesGif(buffer, index, total) ||
                        matchesBmp(buffer, index, total)
                    ) {
                        found += base + index
                        if (found.size >= MAX_IMAGE_CANDIDATES) break
                    }
                }
                consumed += count
                carry = minOf(11, total)
                System.arraycopy(buffer, total - carry, buffer, 0, carry)
            }
        }
        return found.toList()
    }

    private fun matchesPng(data: ByteArray, offset: Int, limit: Int): Boolean =
        offset + 8 <= limit &&
            data[offset] == 0x89.toByte() && data[offset + 1] == 0x50.toByte() &&
            data[offset + 2] == 0x4e.toByte() && data[offset + 3] == 0x47.toByte() &&
            data[offset + 4] == 0x0d.toByte() && data[offset + 5] == 0x0a.toByte() &&
            data[offset + 6] == 0x1a.toByte() && data[offset + 7] == 0x0a.toByte()

    private fun matchesJpeg(data: ByteArray, offset: Int, limit: Int): Boolean =
        offset + 3 <= limit &&
            data[offset] == 0xff.toByte() && data[offset + 1] == 0xd8.toByte() &&
            data[offset + 2] == 0xff.toByte()

    private fun matchesWebp(data: ByteArray, offset: Int, limit: Int): Boolean =
        offset + 12 <= limit &&
            String(data, offset, 4, Charsets.US_ASCII) == "RIFF" &&
            String(data, offset + 8, 4, Charsets.US_ASCII) == "WEBP"

    private fun matchesGif(data: ByteArray, offset: Int, limit: Int): Boolean =
        offset + 6 <= limit &&
            (String(data, offset, 6, Charsets.US_ASCII) == "GIF87a" ||
                String(data, offset, 6, Charsets.US_ASCII) == "GIF89a")

    private fun matchesBmp(data: ByteArray, offset: Int, limit: Int): Boolean =
        offset + 2 <= limit &&
            data[offset] == 'B'.code.toByte() && data[offset + 1] == 'M'.code.toByte()

    private fun decodeBounds(file: File, offset: Long): BitmapFactory.Options? =
        BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            openAt(file, offset).use { BitmapFactory.decodeStream(it, null, this) }
        }

    private fun decode(file: File, offset: Long, options: BitmapFactory.Options): Bitmap? =
        openAt(file, offset).use { BitmapFactory.decodeStream(it, null, options) }

    private fun openAt(file: File, offset: Long): BufferedInputStream {
        val input = BufferedInputStream(FileInputStream(file))
        var remaining = offset
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() < 0) {
                    input.close()
                    throw IOException("Could not read the artwork preview")
                }
                remaining--
            } else {
                remaining -= skipped
            }
        }
        return input
    }

    private fun save(bitmap: Bitmap, target: File) {
        try {
            target.outputStream().use {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                    throw IOException("Could not save the artwork preview")
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > MAX_PREVIEW_DIMENSION || height / sample > MAX_PREVIEW_DIMENSION) {
            sample *= 2
        }
        return sample
    }

    private fun readUInt32(input: RandomAccessFile): Long = input.readInt().toLong() and 0xffffffffL

    private const val SCAN_CHUNK = 64 * 1024
    private const val MAX_IMAGE_CANDIDATES = 64
    private const val MAX_PREVIEW_DIMENSION = 2400
    private const val THUMBNAIL_HEADER_SIZE = 28L
    private const val MAX_THUMBNAIL_BYTES = 64L * 1024L * 1024L
    private const val MAX_CLIP_ENTRY_BYTES = 128L * 1024L * 1024L
    private const val MAX_CLIP_CANDIDATES = 64
    private const val PSD_HEADER_SIZE = 30L
    private const val PSD_VERSION = 1
    private const val PSB_VERSION = 2
    private const val PSD_RGB = 3
    private const val PSD_INDEXED = 2
    private const val PSD_GRAYSCALE = 1
    private const val PSD_RAW = 0
    private const val PSD_RLE = 1
    private const val PSD_ZIP = 2
    private const val PSD_ZIP_PREDICTION = 3
    private const val PSD_PALETTE_SIZE = 768L
    private const val MAX_PSD_DIMENSION = 30000
    private const val MAX_PSB_DIMENSION = 300000
    private const val MAX_PSD_CHANNELS = 8
    private const val MAX_PSD_PREVIEW_PIXELS = 3_000_000L
    private const val MAX_PSD_COMPOSITE_BYTES = 512L * 1024L * 1024L
    private const val MAX_REMOTE_ARTWORK_BYTES = 1024L * 1024L * 1024L
    private val PHOTOSHOP_EXTENSIONS = setOf("psd", "psb")
    private const val JPEG_THUMBNAIL_FORMAT = 1L
    private val THUMBNAIL_RESOURCE_IDS = setOf(1033, 1036)
    private val PREVIEW_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
    private val PREVIEW_MARKERS = listOf(
        "mergedimage", "merged_image", "preview", "thumbnail", "canvas", "clipstudio", "clip_studio"
    )
}
