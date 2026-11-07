package com.imperialreader.ui.reader

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.imperialreader.data.db.ChapterEntity
import com.imperialreader.data.db.LOCAL_SOURCE_ID
import com.imperialreader.data.db.MangaEntity
import com.imperialreader.data.book.BookSources
import com.imperialreader.data.book.NovelChapterEpub
import com.imperialreader.data.source.SChapter
import com.imperialreader.data.source.SManga
import com.imperialreader.data.storage.Archives
import com.imperialreader.data.storage.LocalArtworkPreview
import com.imperialreader.util.NaturalOrder
import com.imperialreader.util.app
import com.imperialreader.util.isImageName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

sealed class LoadedChapter {
    /** Page list: each entry is an http(s) URL, a content:// URI or a file:// URI. */
    data class Images(
        val pages: List<String>,
        val headers: Map<String, String>,
        val audioUrl: String? = null
    ) : LoadedChapter()
    data class Epub(val uri: String) : LoadedChapter()
    data class Pdf(val uri: String) : LoadedChapter()
}

object ChapterLoader {

    suspend fun load(ctx: Context, manga: MangaEntity, chapter: ChapterEntity): LoadedChapter =
        withContext(Dispatchers.IO) {
            if (BookSources.get(manga.source)?.isWebNovel == true) {
                val downloaded = if (chapter.downloaded) NovelChapterEpub.downloadedFile(ctx, manga, chapter) else null
                val uri = downloaded?.uri?.toString()
                    ?: NovelChapterEpub.onlineUri(ctx, manga, chapter)
                return@withContext LoadedChapter.Epub(uri)
            }
            if (manga.source == LOCAL_SOURCE_ID) return@withContext loadLocal(ctx, manga, chapter)

            if (chapter.downloaded) {
                val files = ctx.app.storage.downloadedPages(manga, chapter)
                if (files.isNotEmpty()) {
                    val pages = files.map { file ->
                        if (LocalArtworkPreview.supports(file.name)) {
                            LocalArtworkPreview.readerPage(ctx, file)
                        } else {
                            file.uri.toString()
                        }
                    }
                    return@withContext LoadedChapter.Images(pages, emptyMap())
                }
            }
            val source = ctx.app.sources.get(manga.source)
                ?: throw IOException("Source '${manga.source}' is not available")
            val loaded = source.loadPages(
                SManga(url = manga.url, title = manga.title),
                SChapter(url = chapter.url, name = chapter.name)
            )
            if (loaded.urls.isEmpty()) throw IOException("The source returned no pages for this chapter")
            val pages = loaded.urls.map { url ->
                val extension = Uri.parse(url).lastPathSegment.orEmpty().substringAfterLast('.', "")
                if (url.startsWith("http", ignoreCase = true) && LocalArtworkPreview.supports(extension)) {
                    LocalArtworkPreview.readerPage(ctx, url, loaded.headers)
                } else {
                    url
                }
            }
            LoadedChapter.Images(pages, loaded.headers, loaded.audioUrl)
        }

    private fun loadLocal(ctx: Context, manga: MangaEntity, chapter: ChapterEntity): LoadedChapter {
        val doc = ctx.app.storage.localChapterFile(manga, chapter)
            ?: throw IOException("File not found in the local/ folder. Rescan from More → Scan local library.")
        val name = doc.name.orEmpty().lowercase()
        return when {
            doc.isDirectory -> {
                val files = doc.listFiles().filter {
                    !it.isDirectory && (isImageName(it.name) || LocalArtworkPreview.supports(it.name))
                }
                    .sortedWith(compareBy(NaturalOrder) { it.name ?: "" })
                if (files.isEmpty()) throw IOException("No images in this folder")
                LoadedChapter.Images(
                    files.map { file ->
                        if (LocalArtworkPreview.supports(file.name)) {
                            LocalArtworkPreview.readerPage(ctx, file)
                        } else {
                            file.uri.toString()
                        }
                    },
                    emptyMap()
                )
            }
            name.endsWith(".epub") -> LoadedChapter.Epub(doc.uri.toString())
            name.endsWith(".pdf") -> LoadedChapter.Pdf(doc.uri.toString())
            LocalArtworkPreview.supports(name) ->
                LoadedChapter.Images(listOf(LocalArtworkPreview.readerPage(ctx, doc)), emptyMap())
            else -> {
                val pages = extractArchive(ctx, doc)
                if (pages.isEmpty()) throw IOException("No images found inside the archive")
                LoadedChapter.Images(pages, emptyMap())
            }
        }
    }

    /** Extracts all images to the cache (flattened + naturally sorted) and returns file:// URIs. CBZ/ZIP/CBR/RAR. */
    private fun extractArchive(ctx: Context, doc: DocumentFile): List<String> {
        val root = File(ctx.cacheDir, "cbz").apply { mkdirs() }
        val dir = File(root, doc.uri.toString().hashCode().toString())
        // keep only the archive currently being read to bound cache growth
        root.listFiles()?.filter { it != dir }?.forEach { it.deleteRecursively() }

        val marker = File(dir, ".done")
        if (!marker.exists()) {
            dir.deleteRecursively()
            dir.mkdirs()
            Archives.extractImages(ctx, doc, dir)
            marker.writeText("ok")
        }
        return dir.listFiles()
            ?.filter { it.isFile && isImageName(it.name) }
            ?.sortedWith(compareBy(NaturalOrder) { it.name })
            ?.map { Uri.fromFile(it).toString() }
            ?: emptyList()
    }
}
