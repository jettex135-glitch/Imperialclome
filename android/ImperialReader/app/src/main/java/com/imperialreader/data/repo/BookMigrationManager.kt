package com.imperialreader.data.repo

import androidx.room.withTransaction
import com.imperialreader.data.book.BookItem
import com.imperialreader.data.book.BookSources
import com.imperialreader.data.db.HistoryEntity
import com.imperialreader.data.db.MangaEntity
import com.imperialreader.data.source.SManga
import com.imperialreader.data.source.normalizeTitle
import com.imperialreader.util.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

data class BookMigrationResult(val manga: MangaEntity, val copiedChapters: Int, val unmatchedChapters: Int)

class BookMigrationManager(private val repo: MangaRepository) {
    suspend fun migrate(from: MangaEntity, target: BookItem): BookMigrationResult =
        withContext(Dispatchers.IO) {
            val app = com.imperialreader.App.instance
            val source = BookSources.get(target.sourceId)
                ?.takeIf { it.isWebNovel }
                ?: throw IOException("The destination is not a web-novel source")
            val details = source.details(target)
            val dao = app.db.mangaDao()
            val existing = dao.find(source.id, details.id)
            val destination = if (existing == null) {
                val inserted = repo.getOrCreate(
                    source.id,
                    SManga(
                        url = details.id,
                        title = details.title,
                        thumbnailUrl = details.coverUrl,
                        author = details.author,
                        artist = details.artist,
                        description = details.description,
                        genre = details.genre
                    )
                )
                inserted
            } else {
                existing
            }
            val ready = destination.copy(
                title = details.title,
                author = details.author ?: destination.author,
                artist = details.artist ?: destination.artist,
                description = details.description ?: destination.description,
                genre = details.genre ?: destination.genre,
                coverUrl = details.coverUrl ?: destination.coverUrl,
                favorite = true,
                dateAdded = destination.dateAdded.takeIf { it > 0L }
                    ?: from.dateAdded.takeIf { it > 0L }
                    ?: System.currentTimeMillis(),
                categoryId = from.categoryId
            )
            dao.update(ready)
            repo.refresh(ready)

            val oldChapters = app.db.chapterDao().getAll(from.id)
            val newChapters = app.db.chapterDao().getAll(destination.id)
            val oldProgress = oldChapters.filter { it.read || it.bookmark || it.lastPageRead > 0 }
            val progressByTitle = oldProgress.groupBy { normalizeTitle(it.name) }
            var copied = 0
            val updated = newChapters.map { chapter ->
                val match = progressByTitle[normalizeTitle(chapter.name)]
                    ?.maxByOrNull { it.lastPageRead }
                    ?: return@map chapter
                copied++
                chapter.copy(
                    read = chapter.read || match.read,
                    bookmark = chapter.bookmark || match.bookmark,
                    lastPageRead = maxOf(chapter.lastPageRead, match.lastPageRead)
                )
            }
            val matchedTitles = newChapters.mapTo(HashSet()) { normalizeTitle(it.name) }
            val unmatched = oldProgress.map { normalizeTitle(it.name) }.distinct().count { it !in matchedTitles }
            val oldHistory = app.db.historyDao().latestForManga(from.id)
            val historyChapter = oldHistory?.let { history ->
                oldChapters.firstOrNull { it.id == history.chapterId }
                    ?.let { old -> newChapters.firstOrNull { normalizeTitle(it.name) == normalizeTitle(old.name) } }
            }

            app.db.withTransaction {
                app.db.chapterDao().updateAll(updated)
                if (oldHistory != null && historyChapter != null) {
                    val existingHistory = app.db.historyDao().latestForManga(destination.id)
                    if (existingHistory == null || oldHistory.lastRead > existingHistory.lastRead) {
                        app.db.historyDao().deleteForManga(destination.id)
                        app.db.historyDao().upsert(
                            HistoryEntity(chapterId = historyChapter.id, lastRead = oldHistory.lastRead)
                        )
                    }
                }
            }
            BookMigrationResult(dao.getById(destination.id) ?: ready, copied, unmatched)
        }
}
