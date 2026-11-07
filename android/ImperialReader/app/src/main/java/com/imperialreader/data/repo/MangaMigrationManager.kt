package com.imperialreader.data.repo

import androidx.room.withTransaction
import com.imperialreader.data.db.MangaEntity
import com.imperialreader.data.source.CombinedSource
import com.imperialreader.data.source.SChapter
import com.imperialreader.data.source.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MangaMigrationResult(
    val manga: MangaEntity,
    val copiedChapters: Int,
    val unmatchedChapters: Int
)

data class MangaMigrationOptions(
    val chapters: Boolean = true,
    val category: Boolean = true,
    val history: Boolean = true,
    val readerSettings: Boolean = true
)

class MangaMigrationManager(private val repo: MangaRepository) {

    suspend fun migrate(
        from: MangaEntity,
        target: SManga,
        targetSourceId: String,
        combinedMembers: List<CombinedSource.Member>? = null,
        options: MangaMigrationOptions = MangaMigrationOptions(),
        replaceOriginal: Boolean = false
    ): MangaMigrationResult = withContext(Dispatchers.IO) {
        val app = com.imperialreader.App.instance
        val dao = app.db.mangaDao()
        val destination = if (combinedMembers != null) {
            repo.getOrCreateCombined(from.title, combinedMembers, target.thumbnailUrl)
        } else {
            repo.getOrCreate(targetSourceId, target)
        }

        repo.refresh(destination)
        val refreshed = dao.getById(destination.id)
            ?: throw IllegalStateException("Migrated entry disappeared")
        val oldChapters = app.db.chapterDao().getAll(from.id)
        val targetChapters = app.db.chapterDao().getAll(refreshed.id)
        val sourceProgress = if (options.chapters) {
            oldChapters.filter { it.read || it.bookmark || it.lastPageRead > 0 }
        } else {
            emptyList()
        }
        val progressByKey = sourceProgress.groupBy(::chapterKey)
        var copied = 0
        val updated = targetChapters.map { chapter ->
            val matches = progressByKey[chapterKey(chapter)].orEmpty()
            if (matches.isEmpty()) {
                chapter
            } else {
                copied++
                chapter.copy(
                    read = chapter.read || matches.any { it.read },
                    bookmark = chapter.bookmark || matches.any { it.bookmark },
                    lastPageRead = maxOf(chapter.lastPageRead, matches.maxOf { it.lastPageRead })
                )
            }
        }
        val targetKeys = targetChapters.mapTo(HashSet(), ::chapterKey)
        val unmatched = sourceProgress.map(::chapterKey).distinct().count { it !in targetKeys }
        val sourceHistory = if (options.history) app.db.historyDao().latestForManga(from.id) else null
        val historyTarget = sourceHistory?.let { history ->
            val oldChapter = oldChapters.firstOrNull { it.id == history.chapterId }
            oldChapter?.let { sourceChapter ->
                updated.firstOrNull { chapterKey(it) == chapterKey(sourceChapter) }
                    ?.let { it.id to history.lastRead }
            }
        }

        app.db.withTransaction {
            app.db.chapterDao().updateAll(updated)
            val latest = dao.getById(refreshed.id) ?: refreshed
            dao.update(
                latest.copy(
                    favorite = true,
                    dateAdded = latest.dateAdded.takeIf { it > 0L } ?: from.dateAdded.takeIf { it > 0L }
                        ?: System.currentTimeMillis(),
                    categoryId = if (options.category) {
                        from.categoryId.takeIf { it != 0L } ?: latest.categoryId
                    } else {
                        latest.categoryId
                    }
                )
            )
            if (options.readerSettings) {
                app.prefs.setReaderModeFor(refreshed.id, app.prefs.readerModeFor(from.id))
            }
            if (historyTarget != null) {
                val existing = app.db.historyDao().latestForManga(refreshed.id)
                val selected = if (existing != null && existing.lastRead > historyTarget.second) {
                    existing
                } else {
                    app.db.historyDao().deleteForManga(refreshed.id)
                    com.imperialreader.data.db.HistoryEntity(
                        chapterId = historyTarget.first,
                        lastRead = historyTarget.second
                    )
                }
                app.db.historyDao().upsert(selected)
            }
            if (replaceOriginal && from.id != refreshed.id) {
                dao.setFavorite(from.id, false, 0L)
            }
        }

        MangaMigrationResult(
            manga = dao.getById(refreshed.id) ?: refreshed,
            copiedChapters = copied,
            unmatchedChapters = unmatched
        )
    }

    private fun chapterKey(chapter: com.imperialreader.data.db.ChapterEntity): String =
        CombinedSource.chapterKey(
            SChapter(
                url = chapter.url,
                name = chapter.name,
                chapterNumber = chapter.chapterNumber
            )
        )
}
