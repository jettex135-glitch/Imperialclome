package com.imperialreader.data.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.imperialreader.data.db.ChapterEntity
import com.imperialreader.data.db.MangaEntity
import com.imperialreader.util.NaturalOrder
import com.imperialreader.util.app
import com.imperialreader.util.isReaderPageName
import com.imperialreader.util.sanitize
import java.io.File

/**
 * Handles the user-selected storage folder and the Mihon-style layout inside it:
 *
 *   <picked folder>/downloads/<Source (LANG)>/<Manga>/<Chapter>/001.jpg ...
 *   <picked folder>/local/<Manga>/<chapter>.cbz | .epub | <chapter folder>/...
 *   <picked folder>/autobackup/
 *
 * If no folder is picked, downloads go to the app's private external folder.
 */
class StorageManager(private val ctx: Context) {

    private val prefs get() = ctx.app.prefs

    fun root(): DocumentFile? {
        val uri = prefs.storageUri?.let { Uri.parse(it) } ?: return null
        val doc = runCatching { DocumentFile.fromTreeUri(ctx, uri) }.getOrNull() ?: return null
        return doc.takeIf { it.exists() && it.canRead() }
    }

    /** Persists permission for the picked tree and creates downloads/, local/ and autobackup/. */
    fun setRoot(uri: Uri): Boolean {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        prefs.storageUri = uri.toString()
        return createTree()
    }

    fun createTree(): Boolean {
        val r = root() ?: return false
        listOf("downloads", "local", "autobackup").forEach { findOrCreateDir(r, it) }
        if (r.findFile(".nomedia") == null) r.createFile("application/octet-stream", ".nomedia")
        return true
    }

    fun displayPath(): String {
        val uri = prefs.storageUri ?: return "Not set — using app storage"
        return Uri.decode(Uri.parse(uri).lastPathSegment ?: uri)
    }

    /** Rolling automatic backups use the selected root, or private app storage if none is selected. */
    fun automaticBackupDir(): DocumentFile {
        root()?.let { selected ->
            findOrCreateDir(selected, "autobackup")?.let { return it }
        }
        val dir = File(ctx.filesDir, "autobackup")
        dir.mkdirs()
        return DocumentFile.fromFile(dir)
    }

    /** The local/ folder; falls back to app storage when no folder was picked (same as downloads/). */
    fun localDir(): DocumentFile? {
        root()?.let { r -> return findOrCreateDir(r, "local") }
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "local")
        dir.mkdirs()
        return DocumentFile.fromFile(dir)
    }

    fun findOrCreateDir(parent: DocumentFile, name: String): DocumentFile? =
        parent.findFile(name)?.takeIf { it.isDirectory } ?: parent.createDirectory(name)

    // ---------------------------------------------------------------- downloads

    fun downloadsDir(): DocumentFile {
        root()?.let { r -> findOrCreateDir(r, "downloads")?.let { return it } }
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "downloads")
        dir.mkdirs()
        return DocumentFile.fromFile(dir)
    }

    private fun sourceFolderName(manga: MangaEntity): String {
        val src = ctx.app.sources.get(manga.source)
        return (if (src != null) "${src.name} (${src.lang.uppercase()})" else manga.source).sanitize()
    }

    private fun chapterFolderName(chapter: ChapterEntity): String {
        val prefix = chapter.scanlator?.takeIf { it.isNotBlank() }?.let { "${it.sanitize()}_" }.orEmpty()
        return (prefix + chapter.name.sanitize()).take(150)
    }

    fun chapterDir(manga: MangaEntity, chapter: ChapterEntity, create: Boolean): DocumentFile? {
        var dir: DocumentFile = downloadsDir()
        val parts = listOf(sourceFolderName(manga), manga.title.sanitize(), chapterFolderName(chapter))
        for (part in parts) {
            dir = if (create) findOrCreateDir(dir, part) ?: return null
            else dir.findFile(part)?.takeIf { it.isDirectory } ?: return null
        }
        return dir
    }

    fun downloadedPages(manga: MangaEntity, chapter: ChapterEntity): List<DocumentFile> =
        chapterDir(manga, chapter, create = false)
            ?.listFiles()
            ?.filter { !it.isDirectory && isReaderPageName(it.name) }
            ?.sortedWith(compareBy(NaturalOrder) { it.name ?: "" })
            ?: emptyList()

    fun deleteDownload(manga: MangaEntity, chapter: ChapterEntity): Boolean =
        chapterDir(manga, chapter, create = false)?.delete() ?: false

    // ---------------------------------------------------------------- local library

    fun localChapterFile(manga: MangaEntity, chapter: ChapterEntity): DocumentFile? =
        localDir()?.findFile(manga.url)?.findFile(chapter.url)
}
