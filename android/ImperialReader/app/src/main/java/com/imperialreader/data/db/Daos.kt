package com.imperialreader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MangaDao {
    @Query(
        "SELECT m.*, " +
            "(SELECT COUNT(*) FROM chapters c WHERE c.mangaId = m.id AND c.read = 0) AS unread, " +
            "(SELECT COUNT(*) FROM chapters c WHERE c.mangaId = m.id) AS total, " +
            "(SELECT COUNT(*) FROM chapters c WHERE c.mangaId = m.id AND c.downloaded = 1) AS downloadedCount, " +
            "(SELECT MAX(h.lastRead) FROM history h JOIN chapters c ON c.id = h.chapterId WHERE c.mangaId = m.id) AS lastRead, " +
            "(SELECT COUNT(*) FROM chapters c WHERE c.mangaId = m.id AND c.bookmark = 1) AS bookmarked, " +
            "(SELECT MAX(CASE WHEN c.dateFetch > 0 THEN c.dateFetch ELSE c.dateUpload END) FROM chapters c WHERE c.mangaId = m.id) AS lastUpdated " +
            "FROM manga m WHERE m.favorite = 1"
    )
    fun observeLibrary(): Flow<List<LibraryItem>>

    @Query("SELECT * FROM manga WHERE id = :id")
    suspend fun getById(id: Long): MangaEntity?

    @Query("SELECT * FROM manga WHERE id = :id")
    fun observe(id: Long): Flow<MangaEntity?>

    @Query("SELECT * FROM manga WHERE source = :source AND url = :url LIMIT 1")
    suspend fun find(source: String, url: String): MangaEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(manga: MangaEntity): Long

    @Update
    suspend fun update(manga: MangaEntity)

    @Query("UPDATE manga SET favorite = :fav, dateAdded = :date WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean, date: Long)

    @Query("SELECT * FROM manga WHERE favorite = 1")
    suspend fun getFavorites(): List<MangaEntity>

    @Query("SELECT url FROM manga WHERE source = :source AND favorite = 1")
    suspend fun favoriteUrls(source: String): List<String>

    @Query("UPDATE manga SET categoryId = :categoryId WHERE id = :id")
    suspend fun setCategory(id: Long, categoryId: Long)

    @Query("UPDATE manga SET customCoverUri = :uri WHERE id = :id")
    suspend fun setCustomCover(id: Long, uri: String?)

    @Query("UPDATE manga SET categoryId = :categoryId WHERE id IN (:ids)")
    suspend fun setCategory(ids: List<Long>, categoryId: Long)

    @Query("UPDATE manga SET favorite = 0, dateAdded = 0 WHERE id IN (:ids)")
    suspend fun removeFromLibrary(ids: List<Long>)

    @Query("DELETE FROM manga WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE manga SET categoryId = 0 WHERE categoryId = :categoryId")
    suspend fun clearCategory(categoryId: Long)

    @Query("SELECT * FROM manga WHERE source = :source")
    suspend fun getBySource(source: String): List<MangaEntity>

    @Query("SELECT * FROM manga WHERE favorite = 1")
    suspend fun getFavoritesList(): List<MangaEntity>

    @Query("SELECT * FROM manga WHERE source = 'combined'")
    suspend fun getCombined(): List<MangaEntity>

    @Query("SELECT * FROM manga WHERE favorite = 1 AND title LIKE :pattern ORDER BY title COLLATE NOCASE")
    suspend fun searchLibrary(pattern: String): List<MangaEntity>
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE mangaId = :mangaId ORDER BY sourceOrder ASC")
    fun observe(mangaId: Long): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE mangaId = :mangaId ORDER BY sourceOrder ASC")
    suspend fun getAll(mangaId: Long): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE id = :id")
    suspend fun getById(id: Long): ChapterEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(list: List<ChapterEntity>)

    @Update
    suspend fun update(chapter: ChapterEntity)

    @Update
    suspend fun updateAll(chapters: List<ChapterEntity>)

    @Query("UPDATE chapters SET read = :read WHERE id IN (:ids)")
    suspend fun setRead(ids: List<Long>, read: Boolean)

    @Query("UPDATE chapters SET read = :read WHERE mangaId = :mangaId")
    suspend fun setReadForManga(mangaId: Long, read: Boolean)

    @Query("UPDATE chapters SET read = :read WHERE mangaId IN (:mangaIds)")
    suspend fun setReadForManga(mangaIds: List<Long>, read: Boolean)

    @Query("DELETE FROM chapters WHERE mangaId = :mangaId AND url NOT IN (:urls)")
    suspend fun deleteMissingForManga(mangaId: Long, urls: List<String>)

    @Query("DELETE FROM chapters WHERE mangaId = :mangaId")
    suspend fun deleteAllForManga(mangaId: Long)

    @Query("UPDATE chapters SET bookmark = :bookmark WHERE id = :id")
    suspend fun setBookmark(id: Long, bookmark: Boolean)

    @Query("UPDATE chapters SET lastPageRead = :page, read = :read WHERE id = :id")
    suspend fun saveProgress(id: Long, page: Int, read: Boolean)

    @Query("UPDATE chapters SET downloaded = :downloaded WHERE id = :id")
    suspend fun setDownloaded(id: Long, downloaded: Boolean)

    @Query("UPDATE chapters SET updateSeen = 1 WHERE dateFetch > 0 AND updateSeen = 0")
    suspend fun markUpdatesSeen()

    @Query(
        "SELECT c.*, m.title AS mangaTitle, COALESCE(m.customCoverUri, m.coverUrl) AS mangaCover, m.source AS mangaSource " +
            "FROM chapters c JOIN manga m ON m.id = c.mangaId " +
            "WHERE m.favorite = 1 AND c.dateFetch > 0 " +
            "ORDER BY c.dateFetch DESC, c.sourceOrder ASC LIMIT 300"
    )
    fun observeUpdates(): Flow<List<UpdateItem>>

    @Query(
        "SELECT COUNT(*) FROM chapters c JOIN manga m ON m.id = c.mangaId " +
            "WHERE m.favorite = 1 AND c.dateFetch > 0 AND c.updateSeen = 0"
    )
    fun observeUpdateCount(): Flow<Int>
}

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(history: HistoryEntity)

    @Query(
        "SELECT h.lastRead AS lastRead, c.id AS chapterId, c.name AS chapterName, " +
            "c.chapterNumber AS chapterNumber, m.id AS mangaId, m.title AS mangaTitle, " +
            "COALESCE(m.customCoverUri, m.coverUrl) AS mangaCover, m.source AS mangaSource " +
            "FROM history h JOIN chapters c ON c.id = h.chapterId JOIN manga m ON m.id = c.mangaId " +
            "WHERE h.id = (SELECT h2.id FROM history h2 JOIN chapters c2 ON c2.id = h2.chapterId " +
            "WHERE c2.mangaId = m.id ORDER BY h2.lastRead DESC, h2.id DESC LIMIT 1) " +
            "ORDER BY h.lastRead DESC LIMIT 300"
    )
    fun observe(): Flow<List<HistoryItem>>

    @Query(
        "SELECT h.* FROM history h JOIN chapters c ON c.id = h.chapterId WHERE h.id = " +
            "(SELECT h2.id FROM history h2 JOIN chapters c2 ON c2.id = h2.chapterId " +
            "WHERE c2.mangaId = c.mangaId ORDER BY h2.lastRead DESC, h2.id DESC LIMIT 1)"
    )
    suspend fun all(): List<HistoryEntity>

    @Query("DELETE FROM history WHERE chapterId IN (SELECT id FROM chapters WHERE mangaId = (SELECT mangaId FROM chapters WHERE id = :chapterId))")
    suspend fun delete(chapterId: Long)

    @Query("DELETE FROM history WHERE chapterId IN (SELECT id FROM chapters WHERE mangaId = :mangaId)")
    suspend fun deleteForManga(mangaId: Long)

    @Query("SELECT h.* FROM history h JOIN chapters c ON c.id = h.chapterId WHERE c.mangaId = :mangaId ORDER BY h.lastRead DESC, h.id DESC LIMIT 1")
    suspend fun latestForManga(mangaId: Long): HistoryEntity?

    @Query("SELECT * FROM history WHERE chapterId = :chapterId LIMIT 1")
    suspend fun forChapter(chapterId: Long): HistoryEntity?

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY sortOrder ASC, id ASC")
    suspend fun getAll(): List<CategoryEntity>

    @Insert
    suspend fun insert(category: CategoryEntity): Long

    @Update
    suspend fun update(category: CategoryEntity)

    @Query("UPDATE categories SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: Long, sortOrder: Int)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun delete(id: Long)
}
