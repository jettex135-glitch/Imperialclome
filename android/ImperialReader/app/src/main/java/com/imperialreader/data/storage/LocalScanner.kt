package com.imperialreader.data.storage

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.imperialreader.data.db.LOCAL_SOURCE_ID
import com.imperialreader.data.db.MangaEntity
import com.imperialreader.data.source.SChapter
import com.imperialreader.util.NaturalOrder
import com.imperialreader.util.app
import com.imperialreader.util.isImageName
import com.imperialreader.util.parseChapterNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Reads <storage folder>/local exactly like Mihon:
 * one folder = one manga; each supported file or image sub-folder = one chapter.
 */
class LocalScanner(private val ctx: Context) {

    companion object {
        fun isArchive(name: String?): Boolean {
            val n = name?.lowercase() ?: return false
            return n.endsWith(".cbz") || n.endsWith(".zip") || n.endsWith(".epub") ||
                n.endsWith(".cbr") || n.endsWith(".rar") || n.endsWith(".pdf") ||
                n.endsWith(".psd") || n.endsWith(".psb") || n.endsWith(".clip")
        }
    }

    /** Returns the number of manga folders imported. */
    suspend fun scan(): Int = withContext(Dispatchers.IO) {
        val app = ctx.app
        val local = app.storage.localDir()
            ?: throw IOException("Pick a storage folder first (More → Storage folder).")
        val mangaDao = app.db.mangaDao()
        var count = 0

        for (folder in local.listFiles()) {
            if (!folder.isDirectory) continue
            val folderName = folder.name ?: continue
            val children = folder.listFiles()

            val chapterDocs = children.filter {
                val n = it.name
                n != null && !n.startsWith(".") &&
                    ((it.isDirectory && hasImages(it)) || (!it.isDirectory && isArchive(n)))
            }.sortedWith(compareBy(NaturalOrder) { it.name ?: "" })
            if (chapterDocs.isEmpty()) {
                mangaDao.find(LOCAL_SOURCE_ID, folderName)?.let { existing ->
                    app.db.chapterDao().deleteAllForManga(existing.id)
                }
                continue
            }

            val coverDoc = children.firstOrNull {
                !it.isDirectory && it.name?.matches(Regex("(?i)cover\\.(jpe?g|png|webp|psd|psb|clip)")) == true
            }
            val cover = resolveCover(folderName, coverDoc, chapterDocs.first())

            val existing = mangaDao.find(LOCAL_SOURCE_ID, folderName)
            val mangaId: Long = if (existing == null) {
                mangaDao.insert(
                    MangaEntity(
                        source = LOCAL_SOURCE_ID, url = folderName, title = folderName,
                        description = "Local entry from the local/ folder", coverUrl = cover,
                        favorite = true, dateAdded = System.currentTimeMillis(), initialized = true
                    )
                )
                mangaDao.find(LOCAL_SOURCE_ID, folderName)!!.id
            } else {
                mangaDao.update(existing.copy(coverUrl = cover ?: existing.coverUrl, initialized = true))
                existing.id
            }

            // newest (highest natural name) first, to match the "ascending sourceOrder = newest" convention
            val chapters = chapterDocs.reversed().map {
                val raw = it.name ?: "Chapter"
                val display = if (it.isDirectory) raw else raw.substringBeforeLast('.')
                SChapter(url = raw, name = display, dateUpload = it.lastModified(), chapterNumber = parseChapterNumber(display))
            }
            app.repo.syncChapters(mangaId, chapters, trackUpdates = false)
            count++
        }
        count
    }

    private fun hasImages(dir: DocumentFile): Boolean =
        dir.listFiles().any { !it.isDirectory && (isImageName(it.name) || LocalArtworkPreview.supports(it.name)) }

    /**
     * Local cover for an entry: cover.jpg/png in the folder wins, otherwise the first page of the first
     * chapter (CBZ / CBR / image folder) or the EPUB's cover. The copy is named after the source file's
     * date + size, so a changed file produces a new cover and the old one is dropped (= refresh).
     */
    private fun resolveCover(folderName: String, coverDoc: DocumentFile?, first: DocumentFile): String? {
        val extract = ctx.app.prefs.localCovers
        if (coverDoc == null && !extract) return null
        val signatureDoc: DocumentFile = coverDoc ?: if (first.isDirectory) {
            first.listFiles()
                .filter { isImageName(it.name) || LocalArtworkPreview.supports(it.name) }
                .minWithOrNull(compareBy(NaturalOrder) { it.name ?: "" }) ?: return null
        } else first

        val dir = File(ctx.filesDir, "covers").apply { mkdirs() }
        val prefix = "${folderName.hashCode()}_"
        val extension = if (LocalArtworkPreview.supports(signatureDoc.name)) "png" else "jpg"
        val target = File(dir, "$prefix${signatureDoc.lastModified()}_${signatureDoc.length()}.$extension")
        if (target.exists() && target.length() > 0) return android.net.Uri.fromFile(target).toString()

        val ok = try {
            when {
                LocalArtworkPreview.supports(signatureDoc.name) ->
                    LocalArtworkPreview.extract(ctx, signatureDoc, target)
                coverDoc != null || first.isDirectory ->
                    ctx.contentResolver.openInputStream(signatureDoc.uri)?.use { i -> target.outputStream().use { i.copyTo(it) } } != null
                first.name.orEmpty().lowercase().endsWith(".epub") -> extractEpubCover(first, target)
                first.name.orEmpty().lowercase().endsWith(".pdf") -> extractPdfCover(first, target)
                else -> Archives.extractFirstImage(ctx, first, target)
            }
        } catch (_: Exception) {
            false
        }
        if (!ok || !target.exists() || target.length() == 0L) {
            target.delete()
            return null
        }
        dir.listFiles()?.filter { it.name.startsWith(prefix) && it != target }?.forEach { it.delete() }
        return android.net.Uri.fromFile(target).toString()
    }

    private fun normalizePath(p: String): String {
        val out = ArrayDeque<String>()
        for (seg in p.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> out.removeLastOrNull()
                else -> out.addLast(seg)
            }
        }
        return out.joinToString("/")
    }

    private fun extractEpubCover(doc: DocumentFile, target: File): Boolean {
        val tmp = File(ctx.cacheDir, "epubcover.tmp")
        ctx.contentResolver.openInputStream(doc.uri)?.use { i -> tmp.outputStream().use { i.copyTo(it) } } ?: return false
        try {
            java.util.zip.ZipFile(tmp).use { zip ->
                fun text(name: String): String? =
                    zip.getEntry(name)?.let { e -> zip.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) } }

                val container = text("META-INF/container.xml") ?: return false
                val opfPath = Regex("""full-path=["']([^"']+)["']""").find(container)?.groupValues?.get(1) ?: return false
                val opf = text(opfPath) ?: return false
                val base = opfPath.substringBeforeLast('/', "")
                val coverId = Regex("""<meta[^>]+name=["']cover["'][^>]+content=["']([^"']+)["']""").find(opf)?.groupValues?.get(1)
                    ?: Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+name=["']cover["']""").find(opf)?.groupValues?.get(1)
                val items = Regex("""<item\b[^>]*>""").findAll(opf).map { it.value }.toList()
                fun attr(tag: String, name: String) = Regex("""\b$name=["']([^"']+)["']""").find(tag)?.groupValues?.get(1)
                val coverItem = items.firstOrNull { attr(it, "properties")?.contains("cover-image") == true }
                    ?: items.firstOrNull { coverId != null && attr(it, "id") == coverId }
                val href = coverItem?.let { attr(it, "href") }
                val path = href?.let { normalizePath(if (base.isEmpty()) it else "$base/$it") }
                val entry = path?.let { zip.getEntry(java.net.URLDecoder.decode(it, "UTF-8")) }
                    ?: zip.entries().asSequence().firstOrNull { isImageName(it.name) && it.name.contains("cover", true) }
                    ?: zip.entries().asSequence().firstOrNull { isImageName(it.name) }
                    ?: return false
                zip.getInputStream(entry).use { i -> target.outputStream().use { i.copyTo(it) } }
                return true
            }
        } finally {
            tmp.delete()
        }
    }

    private fun extractPdfCover(doc: DocumentFile, target: File): Boolean {
        val descriptor = ctx.contentResolver.openFileDescriptor(doc.uri, "r") ?: return false
        descriptor.use { file ->
            android.graphics.pdf.PdfRenderer(file).use { renderer ->
                if (renderer.pageCount == 0) return false
                renderer.openPage(0).use { page ->
                    val scale = minOf(1200f / page.width, 1800f / page.height, 1f)
                    val bitmap = android.graphics.Bitmap.createBitmap(
                        (page.width * scale).toInt().coerceAtLeast(1),
                        (page.height * scale).toInt().coerceAtLeast(1),
                        android.graphics.Bitmap.Config.ARGB_8888
                    )
                    try {
                        page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        return target.outputStream().use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    }
}
