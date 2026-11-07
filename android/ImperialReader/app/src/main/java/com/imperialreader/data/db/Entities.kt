package com.imperialreader.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

const val LOCAL_SOURCE_ID = "local"

@Entity(tableName = "manga", indices = [Index(value = ["source", "url"], unique = true)])
data class MangaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    val url: String,
    val title: String,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val genre: String? = null,
    val status: Int = 0,
    val coverUrl: String? = null,
    val customCoverUri: String? = null,
    val favorite: Boolean = false,
    val dateAdded: Long = 0,
    val lastUpdate: Long = 0,
    val initialized: Boolean = false,
    /** 0 = Default (no category). */
    val categoryId: Long = 0
) {
    val displayCoverUrl: String? get() = customCoverUri ?: coverUrl
}

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0
)

@Entity(
    tableName = "chapters",
    foreignKeys = [ForeignKey(
        entity = MangaEntity::class,
        parentColumns = ["id"],
        childColumns = ["mangaId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("mangaId"), Index(value = ["mangaId", "url"], unique = true)]
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mangaId: Long,
    val url: String,
    val name: String,
    val scanlator: String? = null,
    val dateUpload: Long = 0,
    val dateFetch: Long = 0,
    val chapterNumber: Float = -1f,
    /** Ascending sourceOrder == newest first (the order the source returned them). */
    val sourceOrder: Int = 0,
    val read: Boolean = false,
    val bookmark: Boolean = false,
    val lastPageRead: Int = 0,
    val downloaded: Boolean = false,
    val updateSeen: Boolean = false
)

@Entity(
    tableName = "history",
    foreignKeys = [ForeignKey(
        entity = ChapterEntity::class,
        parentColumns = ["id"],
        childColumns = ["chapterId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["chapterId"], unique = true)]
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chapterId: Long,
    val lastRead: Long
)

data class LibraryItem(
    @Embedded val manga: MangaEntity,
    val unread: Int,
    val total: Int,
    val downloadedCount: Int,
    val lastRead: Long?,
    val bookmarked: Int,
    val lastUpdated: Long?
)

data class UpdateItem(
    @Embedded val chapter: ChapterEntity,
    val mangaTitle: String,
    val mangaCover: String?,
    val mangaSource: String
)

data class HistoryItem(
    val lastRead: Long,
    val chapterId: Long,
    val chapterName: String,
    val chapterNumber: Float,
    val mangaId: Long,
    val mangaTitle: String,
    val mangaCover: String?,
    val mangaSource: String
)
