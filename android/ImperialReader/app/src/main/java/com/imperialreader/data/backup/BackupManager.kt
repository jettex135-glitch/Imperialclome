package com.imperialreader.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.imperialreader.data.db.CategoryEntity
import com.imperialreader.data.db.ChapterEntity
import com.imperialreader.data.db.HistoryEntity
import com.imperialreader.data.db.MangaEntity
import com.imperialreader.data.source.normalizeTitle
import com.imperialreader.data.source.Source
import com.imperialreader.util.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * JSON backup of the library: entries, categories, chapters (read / bookmark / progress) and history.
 * Restoring merges into what you already have (nothing is deleted, read state is never lost).
 */
class BackupManager(private val ctx: Context) {

    private val db get() = ctx.app.db
    private val backupMutex get() = automaticBackupMutex

    companion object {
        private const val AUTO_PREFIX = "imperial_reader_auto_"
        private const val AUTO_LIMIT = 5
        private const val MAX_BACKUP_BYTES = 200 * 1024 * 1024
        private const val PER_MANGA_READER_MODE_PREFIX = "reader_mode_"
        private val DEVICE_ONLY_SETTINGS = setOf(
            "storage_uri",
            "automatic_backup_at",
            "automatic_backup_status",
            "automatic_app_update_last_asset_id"
        )
        private val automaticBackupMutex = Mutex()
    }

    /**
     * Creates a complete temporary JSON backup, promotes it to the rolling backup set, then
     * removes the oldest automatic backups. User-created manual exports are never pruned.
     */
    suspend fun createAutomaticBackup(): Int = backupMutex.withLock {
        withContext(Dispatchers.IO) {
            val dir = ctx.app.storage.automaticBackupDir()
            val stamp = System.currentTimeMillis()
            val finalName = "$AUTO_PREFIX$stamp.json"
            val temporary = dir.createFile("application/json", "$finalName.part")
                ?: throw IOException("Cannot create an automatic backup file")
            var promoted = false
            try {
                val entryCount = export(temporary.uri)
                if (!temporary.renameTo(finalName)) {
                    throw IOException("Could not finish the automatic backup file")
                }
                promoted = true
                pruneAutomaticBackups(dir)
                entryCount
            } catch (e: Exception) {
                if (!promoted) temporary.delete()
                throw e
            }
        }

    }

    private fun pruneAutomaticBackups(dir: androidx.documentfile.provider.DocumentFile) {
        val backups = dir.listFiles()
            .filter { !it.isDirectory && it.name.orEmpty().startsWith(AUTO_PREFIX) && it.name.orEmpty().endsWith(".json") }
            .sortedWith(
                compareBy<androidx.documentfile.provider.DocumentFile> {
                    it.lastModified().takeIf { modified -> modified > 0L }
                        ?: it.name.orEmpty().removePrefix(AUTO_PREFIX).removeSuffix(".json").toLongOrNull()
                        ?: 0L
                }.thenBy { it.name.orEmpty() }
            )
        val excess = (backups.size - AUTO_LIMIT).coerceAtLeast(0)
        backups.take(excess).forEach { old ->
            if (!old.delete() && old.exists()) {
                throw IOException("Could not remove an old automatic backup")
            }
        }
    }

    suspend fun export(uri: Uri): Int = withContext(Dispatchers.IO) {
        val categories = db.categoryDao().getAll()
        val manga = db.mangaDao().getFavoritesList()
        val root = JSONObject().put("version", 1).put("exported", System.currentTimeMillis())

        root.put("categories", JSONArray().also { arr ->
            categories.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("order", it.sortOrder)) }
        })

        val mangaArr = JSONArray()
        for (m in manga) {
            val chapters = db.chapterDao().getAll(m.id)
            val chArr = JSONArray()
            chapters.forEach { c ->
                chArr.put(
                    JSONObject().put("url", c.url).put("name", c.name).put("scanlator", c.scanlator)
                        .put("dateUpload", c.dateUpload).put("number", c.chapterNumber.toDouble())
                        .put("order", c.sourceOrder).put("read", c.read).put("bookmark", c.bookmark)
                        .put("page", c.lastPageRead)
                )
            }
            val mangaObject = JSONObject().put("source", m.source).put("url", m.url).put("title", m.title)
                    .put("author", m.author).put("artist", m.artist).put("description", m.description)
                    .put("genre", m.genre).put("status", m.status).put("cover", m.coverUrl)
                    .put("dateAdded", m.dateAdded).put("category", categories.firstOrNull { it.id == m.categoryId }?.name)
                    .put("chapters", chArr)
            val readerModeKey = "$PER_MANGA_READER_MODE_PREFIX${m.id}"
            if (ctx.app.prefs.sp.contains(readerModeKey)) {
                ctx.app.prefs.sp.getString(readerModeKey, null)?.toIntOrNull()
                    ?.takeIf { it in 0..2 }
                    ?.let { mangaObject.put("readerMode", it) }
            }
            mangaArr.put(mangaObject)
        }
        root.put("manga", mangaArr)

        val historyArr = JSONArray()
        db.historyDao().all().forEach { h ->
            val ch = db.chapterDao().getById(h.chapterId) ?: return@forEach
            val m = db.mangaDao().getById(ch.mangaId) ?: return@forEach
            historyArr.put(JSONObject().put("source", m.source).put("mangaUrl", m.url).put("chapterUrl", ch.url).put("lastRead", h.lastRead))
        }
        root.put("history", historyArr)
        root.put("settings", encodeSettings())

        val out = ctx.contentResolver.openOutputStream(uri, "w") ?: throw IOException("Cannot write the backup file")
        out.use { it.write(root.toString().toByteArray(Charsets.UTF_8)) }
        manga.size
    }

    /** Returns the number of library entries restored. */
    suspend fun restore(
        uri: Uri,
        onProgress: suspend (BackupRestoreProgress) -> Unit = {}
    ): Int = withContext(Dispatchers.IO) {
        onProgress(BackupRestoreProgress("Reading backup"))
        val bytes = ctx.contentResolver.openInputStream(uri)?.use(::readBounded)
            ?: throw IOException("Cannot read the backup file")
        val decoded = if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            GZIPInputStream(ByteArrayInputStream(bytes)).use(::readBounded)
        } else {
            bytes
        }
        val text = decoded.toString(Charsets.UTF_8).trimStart()
        if (!text.startsWith("{")) return@withContext restoreMihon(decoded, onProgress)
        val root = JSONObject(text)
        if (!root.has("manga")) return@withContext restoreMihon(decoded, onProgress)
        val settings = root.optJSONObject("settings")?.let(::decodeSettings)

        val catDao = db.categoryDao()
        val mangaDao = db.mangaDao()
        val chapterDao = db.chapterDao()
        val categories = root.optJSONArray("categories") ?: JSONArray()
        val mangaArr = root.getJSONArray("manga")
        val historyArr = root.optJSONArray("history") ?: JSONArray()
        val readerModeCount = (0 until mangaArr.length()).count {
            mangaArr.getJSONObject(it).optInt("readerMode", -1) in 0..2
        }
        val total = categories.length() + mangaArr.length() + historyArr.length() +
            (0 until mangaArr.length()).sumOf { mangaArr.getJSONObject(it).optJSONArray("chapters")?.length() ?: 0 } +
            readerModeCount + if (settings != null) 1 else 0
        var completed = 0
        suspend fun report(stage: String) {
            completed++
            onProgress(BackupRestoreProgress(stage, completed, total))
        }
        onProgress(BackupRestoreProgress("Preparing restoration", 0, total))

        // categories by name
        val categoryByName = catDao.getAll()
            .associateBy { normalizeCategoryName(it.name) }
            .toMutableMap()
        for (i in 0 until categories.length()) {
            val name = categories.getJSONObject(i).getString("name")
            val key = normalizeCategoryName(name)
            if (key !in categoryByName) {
                val id = catDao.insert(CategoryEntity(name = name, sortOrder = categories.getJSONObject(i).optInt("order")))
                categoryByName[key] = CategoryEntity(id = id, name = name)
            }
            report("Restoring categories")
        }

        var restored = 0
        val readerModes = mutableMapOf<Long, Int>()
        for (i in 0 until mangaArr.length()) {
            val o = mangaArr.getJSONObject(i)
            val source = o.getString("source")
            val url = o.getString("url")
            val categoryId = o.optString("category")
                .takeIf { it.isNotBlank() && it != "null" }
                ?.let { categoryByName[normalizeCategoryName(it)]?.id } ?: 0L

            val existing = mangaDao.find(source, url)
            val mangaId: Long
            if (existing == null) {
                mangaDao.insert(
                    MangaEntity(
                        source = source, url = url, title = o.getString("title"),
                        author = o.optString("author").takeIf { it != "null" && it.isNotBlank() },
                        artist = o.optString("artist").takeIf { it != "null" && it.isNotBlank() },
                        description = o.optString("description").takeIf { it != "null" && it.isNotBlank() },
                        genre = o.optString("genre").takeIf { it != "null" && it.isNotBlank() },
                        status = o.optInt("status"),
                        coverUrl = o.optString("cover").takeIf { it != "null" && it.isNotBlank() },
                        favorite = true, dateAdded = o.optLong("dateAdded"), initialized = true, categoryId = categoryId
                    )
                )
                mangaId = mangaDao.find(source, url)!!.id
            } else {
                mangaDao.update(existing.copy(favorite = true, categoryId = categoryId, initialized = true))
                mangaId = existing.id
            }
            o.optInt("readerMode", -1)
                .takeIf { it in 0..2 }
                ?.let { readerModes[mangaId] = it }

            val have = chapterDao.getAll(mangaId).associateBy { it.url }
            val chArr = o.optJSONArray("chapters") ?: JSONArray()
            val toInsert = mutableListOf<ChapterEntity>()
            for (j in 0 until chArr.length()) {
                val c = chArr.getJSONObject(j)
                val old = have[c.getString("url")]
                if (old == null) {
                    toInsert.add(
                        ChapterEntity(
                            mangaId = mangaId, url = c.getString("url"), name = c.getString("name"),
                            scanlator = c.optString("scanlator").takeIf { it != "null" && it.isNotBlank() },
                            dateUpload = c.optLong("dateUpload"), chapterNumber = c.optDouble("number", -1.0).toFloat(),
                            sourceOrder = c.optInt("order"), read = c.optBoolean("read"),
                            bookmark = c.optBoolean("bookmark"), lastPageRead = c.optInt("page")
                        )
                    )
                } else {
                    chapterDao.update(
                        old.copy(
                            read = old.read || c.optBoolean("read"), bookmark = old.bookmark || c.optBoolean("bookmark"),
                            lastPageRead = maxOf(old.lastPageRead, c.optInt("page"))
                        )
                    )
                }
                report("Restoring chapters for ${o.optString("title").ifBlank { "entry ${i + 1}" }}")
            }
            if (toInsert.isNotEmpty()) chapterDao.insertAll(toInsert)
            restored++
            report("Restoring entry $restored of ${mangaArr.length()}")
        }

        run {
            val latestByManga = mutableMapOf<Long, HistoryEntity>()
            for (i in 0 until historyArr.length()) {
                val h = historyArr.getJSONObject(i)
                val m = mangaDao.find(h.getString("source"), h.getString("mangaUrl"))
                val ch = m?.let { manga ->
                    chapterDao.getAll(manga.id).firstOrNull { it.url == h.getString("chapterUrl") }
                }
                if (m != null && ch != null) {
                    val history = HistoryEntity(chapterId = ch.id, lastRead = h.optLong("lastRead"))
                    if (history.lastRead > (latestByManga[m.id]?.lastRead ?: Long.MIN_VALUE)) {
                        latestByManga[m.id] = history
                    }
                }
                report("Restoring reading history")
            }
            db.withTransaction {
                latestByManga.forEach { (mangaId, history) ->
                    val existing = db.historyDao().latestForManga(mangaId)
                    val latest = if (existing != null && existing.lastRead > history.lastRead) existing else history
                    db.historyDao().deleteForManga(mangaId)
                    db.historyDao().upsert(latest)
                }
            }
        }
        if (settings != null) {
            restoreSettings(settings)
            report("Restoring settings")
        }
        readerModes.forEach { (mangaId, mode) ->
            ctx.app.prefs.setReaderModeFor(mangaId, mode)
            report("Restoring reading preferences")
        }
        restored
    }

    private suspend fun restoreMihon(
        bytes: ByteArray,
        onProgress: suspend (BackupRestoreProgress) -> Unit
    ): Int {
        val backup = decodeMihon(bytes)
        if (backup.entries.isEmpty()) {
            throw IOException("This is not a supported Mihon backup")
        }
        val total = backup.categories.size + backup.entries.sumOf {
            1 + it.chapters.size + it.histories.size
        }
        var completed = 0
        suspend fun report(stage: String) {
            completed++
            onProgress(BackupRestoreProgress(stage, completed, total))
        }
        onProgress(BackupRestoreProgress("Preparing restoration", 0, total))

        val catDao = db.categoryDao()
        val mangaDao = db.mangaDao()
        val chapterDao = db.chapterDao()
        val sourcesById = backup.sources.associate { it.id to it.name }
        val sourceManager = ctx.app.sources
        var importedSources = sourceManager.all()
        val existingTitles = mangaDao.getFavoritesList()
            .mapNotNull { normalizeTitle(it.title).takeIf(String::isNotBlank) }
            .toSet()
        val unresolvedEntries = backup.entries.filter { entry ->
            resolveMihonSource(entry.sourceId, entry.url, sourcesById, importedSources) == null
        }
        val sourcesToAdd = LinkedHashMap<String, Pair<String, String>>()
        unresolvedEntries.forEach { entry ->
            if (normalizeTitle(entry.title) in existingTitles) return@forEach
            val hostBaseUrl = mihonHostBaseUrl(entry.url)
            if (hostBaseUrl == null) return@forEach
            val host = runCatching { java.net.URI(hostBaseUrl).host }
                .getOrNull()
                ?.removePrefix("www.")
                ?.lowercase()
                ?: return@forEach
            val sourceName = sourcesById[entry.sourceId]
                ?.takeIf { it.isNotBlank() }
                ?: host
            sourcesToAdd.putIfAbsent(host, sourceName to hostBaseUrl)
        }
        sourcesToAdd.values.forEach { (name, baseUrl) ->
            sourceManager.addCustom(name, baseUrl)
        }
        importedSources = sourceManager.all()

        val categoryById = mutableMapOf<Long, CategoryEntity>()
        val categoryByName = catDao.getAll()
            .associateBy { normalizeCategoryName(it.name) }
            .toMutableMap()
        backup.categories.forEach { category ->
            if (category.name.isBlank()) {
                report("Restoring categories")
                return@forEach
            }
            val key = normalizeCategoryName(category.name)
            val saved = categoryByName[key] ?: run {
                val sortOrder = category.order.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                val id = catDao.insert(CategoryEntity(name = category.name, sortOrder = sortOrder))
                CategoryEntity(id = id, name = category.name, sortOrder = sortOrder)
                    .also { categoryByName[key] = it }
            }
            categoryById[category.id] = saved
            report("Restoring categories")
        }

        val libraryByTitle = mangaDao.getFavoritesList()
            .filter { normalizeTitle(it.title).isNotBlank() }
            .groupBy { normalizeTitle(it.title) }
            .mapValues { (_, matches) -> matches.toMutableList() }
            .toMutableMap()
        var restored = 0
        for ((entryIndex, entry) in backup.entries.withIndex()) {
            val source = resolveMihonSource(entry.sourceId, entry.url, sourcesById, importedSources)
            val mangaSourceId = source?.id ?: "mihon_backup_${entry.sourceId}"
            val mangaUrl = sourceBackupUrl(entry.url)
                ?: entry.url.takeIf { it.isNotBlank() }
                ?: "mihon://source/${entry.sourceId}/${normalizeTitle(entry.title).ifBlank { entryIndex.toString() }}"
            val categoryId = entry.categoryRefs.firstNotNullOfOrNull { categoryRef ->
                categoryById[categoryRef]?.id
            }
            val existingByUrl = mangaDao.find(mangaSourceId, mangaUrl)
            val titleKey = normalizeTitle(entry.title)
            val existingByTitle = titleKey
                .takeIf { it.isNotBlank() }
                ?.let(libraryByTitle::get)
                ?.let { matches ->
                    matches.firstOrNull { it.title.equals(entry.title, ignoreCase = true) }
                        ?: matches.firstOrNull()
                }
            val existing = existingByUrl ?: existingByTitle
            val mergingByTitle = existingByUrl == null && existingByTitle != null
            val mangaId = if (existing == null) {
                val inserted = mangaDao.insert(
                    MangaEntity(
                        source = mangaSourceId,
                        url = mangaUrl,
                        title = entry.title.ifBlank { mangaUrl },
                        favorite = true,
                        dateAdded = entry.dateAdded.takeIf { it > 0L } ?: System.currentTimeMillis(),
                        categoryId = categoryId ?: 0L
                    )
                )
                if (inserted <= 0L) mangaDao.find(mangaSourceId, mangaUrl)?.id
                    ?: throw IOException("Could not restore ${entry.title}")
                else inserted
            } else {
                mangaDao.update(
                    existing.copy(
                        title = entry.title.ifBlank { existing.title },
                        favorite = true,
                        dateAdded = existing.dateAdded.takeIf { it > 0L }
                            ?: entry.dateAdded.takeIf { it > 0L }
                            ?: System.currentTimeMillis(),
                        categoryId = categoryId ?: existing.categoryId
                    )
                )
                existing.id
            }
            val restoredManga = mangaDao.getById(mangaId)
                ?: throw IOException("Could not restore ${entry.title}")
            val restoredTitleKey = normalizeTitle(restoredManga.title)
            if (!mergingByTitle && restoredTitleKey.isNotBlank()) {
                libraryByTitle.getOrPut(restoredTitleKey) { mutableListOf() }.add(restoredManga)
            }

            val mangaChapters = chapterDao.getAll(mangaId)
            val savedByUrl = mangaChapters.associateBy { it.url }.toMutableMap()
            val chapterByBackupUrl = mutableMapOf<String, ChapterEntity>()
            for ((backupOrder, backupChapter) in entry.chapters.withIndex()) {
                val sourceOrder = backupChapter.sourceOrder ?: backupOrder
                val chapterUrl = sourceBackupUrl(backupChapter.url)
                val matching = savedByUrl[chapterUrl] ?: findMatchingChapter(
                    backupChapter,
                    mangaChapters,
                    chapterUrl
                )
                if (matching != null) {
                    val updated = matching.copy(
                        read = matching.read || backupChapter.read,
                        lastPageRead = maxOf(matching.lastPageRead, backupChapter.lastPageRead),
                        sourceOrder = if (mergingByTitle) matching.sourceOrder else sourceOrder
                    )
                    if (updated != matching) chapterDao.update(updated)
                    savedByUrl[updated.url] = updated
                    if (chapterUrl.isNotBlank()) chapterByBackupUrl[chapterUrl] = updated
                } else if (!mergingByTitle && chapterUrl.isNotBlank()) {
                    val imported = ChapterEntity(
                        mangaId = mangaId,
                        url = chapterUrl,
                        name = backupChapter.name.ifBlank { "Chapter" },
                        chapterNumber = backupChapter.number,
                        sourceOrder = sourceOrder,
                        read = backupChapter.read,
                        lastPageRead = backupChapter.lastPageRead
                    )
                    chapterDao.insertAll(listOf(imported))
                    val inserted = chapterDao.getAll(mangaId).firstOrNull { it.url == chapterUrl }
                        ?: throw IOException("Could not restore chapter for ${entry.title}")
                    savedByUrl[chapterUrl] = inserted
                    chapterByBackupUrl[chapterUrl] = inserted
                }
                report("Restoring chapters for ${entry.title.ifBlank { "entry ${entryIndex + 1}" }}")
            }
            val readThrough = entry.chapters.asSequence()
                .filter { it.read && it.number >= 0f }
                .maxOfOrNull { it.number }
            if (readThrough != null) {
                savedByUrl.values
                    .filter { it.chapterNumber >= 0f && it.chapterNumber <= readThrough && !it.read }
                    .forEach { chapterDao.update(it.copy(read = true)) }
            }
            entry.histories.forEach { history ->
                if (history.url.isBlank()) {
                    report("Restoring reading history")
                    return@forEach
                }
                val chapterUrl = sourceBackupUrl(history.url)
                val matchingBackupChapter = entry.chapters.firstOrNull {
                    sourceBackupUrl(it.url) == chapterUrl
                }
                val chapter = chapterByBackupUrl[chapterUrl]
                    ?: savedByUrl[chapterUrl]
                    ?: matchingBackupChapter?.let {
                        findMatchingChapter(
                            it,
                            savedByUrl.values,
                            sourceBackupUrl(it.url)
                        )
                    }
                    ?: if (mergingByTitle) null else run {
                    val newChapter = ChapterEntity(
                        mangaId = mangaId,
                        url = chapterUrl,
                        name = matchingBackupChapter?.name?.ifBlank { null } ?: "History",
                        chapterNumber = matchingBackupChapter?.number ?: -1f,
                        sourceOrder = (savedByUrl.values.maxOfOrNull { it.sourceOrder } ?: -1) + 1
                    )
                    chapterDao.insertAll(listOf(newChapter))
                    chapterDao.getAll(mangaId).firstOrNull { it.url == chapterUrl }
                        ?: throw IOException("Could not restore history for ${entry.title}")
                }
                if (chapter == null) {
                    report("Restoring reading history")
                    return@forEach
                }
                savedByUrl[chapterUrl] = chapter
                if (history.lastRead > 0L) {
                    val previous = db.historyDao().forChapter(chapter.id)
                    val currentLatest = db.historyDao().latestForManga(mangaId)
                    val currentLatestChapter = currentLatest?.let {
                        chapterDao.getById(it.chapterId)
                    }
                    val doesNotRegressProgress = currentLatest == null ||
                        compareChapterProgress(chapter, currentLatestChapter) >= 0
                    if (doesNotRegressProgress &&
                        history.lastRead > (previous?.lastRead ?: Long.MIN_VALUE)
                    ) {
                        db.historyDao().upsert(
                            HistoryEntity(chapterId = chapter.id, lastRead = history.lastRead)
                        )
                    }
                }
                report("Restoring reading history")
            }
            restored++
            report("Restoring entry $restored of ${backup.entries.size}")
        }
        return restored
    }

    private fun normalizeCategoryName(name: String): String =
        name.trim().lowercase()

    private fun findMatchingChapter(
        backup: MihonChapter,
        existing: Collection<ChapterEntity>,
        normalizedUrl: String
    ): ChapterEntity? {
        existing.firstOrNull { it.url == normalizedUrl }?.let { return it }
        val name = normalizeTitle(backup.name)
        name.takeIf { it.isNotBlank() }
            ?.let { normalizedName -> existing.firstOrNull { normalizeTitle(it.name) == normalizedName } }
            ?.let { return it }
        if (backup.number >= 0f) {
            val byNumber = existing.filter {
                it.chapterNumber >= 0f && kotlin.math.abs(it.chapterNumber - backup.number) < 0.001f
            }
            byNumber.firstOrNull()?.let { return it }
        }
        return null
    }

    private fun compareChapterProgress(first: ChapterEntity?, second: ChapterEntity?): Int {
        if (first == null) return if (second == null) 0 else -1
        if (second == null) return 1
        if (first.chapterNumber >= 0f && second.chapterNumber >= 0f) {
            val numberComparison = first.chapterNumber.compareTo(second.chapterNumber)
            if (numberComparison != 0) return numberComparison
        }
        if (first.lastPageRead != second.lastPageRead) {
            return first.lastPageRead.compareTo(second.lastPageRead)
        }
        return second.sourceOrder.compareTo(first.sourceOrder)
    }

    private fun resolveMihonSource(
        sourceId: Long,
        mangaUrl: String,
        sourceNames: Map<Long, String>,
        sources: List<Source>
    ): Source? {
        val host = runCatching { java.net.URI(mangaUrl).host?.removePrefix("www.") }
            .getOrNull()
            ?.lowercase()
        if (!host.isNullOrBlank()) {
            sources.firstOrNull { source ->
                runCatching { java.net.URI(source.baseUrl).host?.removePrefix("www.")?.lowercase() }
                    .getOrNull() == host
            }?.let { return it }
        }

        val sourceName = sourceNames[sourceId]?.let(::normalizeTitle)?.takeIf { it.isNotBlank() }
            ?: return null
        return sources.firstOrNull {
            normalizeTitle(it.name) == sourceName ||
                normalizeTitle(it.id) == sourceName ||
                normalizeTitle(runCatching { java.net.URI(it.baseUrl).host.orEmpty() }.getOrDefault("")) == sourceName
        }
    }

    private fun mihonHostBaseUrl(value: String): String? {
        val uri = runCatching {
            java.net.URI(if (value.startsWith("//")) "https:$value" else value)
        }.getOrNull() ?: return null
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
        return "${uri.scheme}://${uri.rawAuthority}"
    }

    private fun sourceBackupUrl(value: String): String {
        val uri = runCatching {
            java.net.URI(if (value.startsWith("//")) "https:$value" else value)
        }.getOrNull() ?: return value
        if (!uri.isAbsolute) return value
        return uri.toString()
    }

    private fun decodeMihon(bytes: ByteArray): MihonBackup {
        val reader = ProtoReader(bytes)
        val entries = mutableListOf<MihonEntry>()
        val categories = mutableListOf<MihonCategory>()
        val sources = mutableListOf<MihonSource>()
        while (!reader.exhausted()) {
            val tag = reader.readVarint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            if (field == 0) throw IOException("Invalid Mihon backup")
            when {
                field == 1 && wire == 2 -> entries += decodeMihonEntry(reader.readBytes())
                field == 2 && wire == 2 -> categories += decodeMihonCategory(reader.readBytes())
                field == 101 && wire == 2 -> sources += decodeMihonSource(reader.readBytes())
                else -> reader.skip(wire)
            }
        }
        return MihonBackup(entries, categories, sources)
    }

    private fun decodeMihonEntry(bytes: ByteArray): MihonEntry {
        val reader = ProtoReader(bytes)
        var sourceId = 0L
        var url = ""
        var title = ""
        var dateAdded = 0L
        val categories = mutableListOf<Long>()
        val chapters = mutableListOf<MihonChapter>()
        val histories = mutableListOf<MihonHistory>()
        while (!reader.exhausted()) {
            val tag = reader.readVarint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            when {
                field == 1 && wire == 0 -> sourceId = reader.readVarint()
                field == 2 && wire == 2 -> url = reader.readString()
                field == 3 && wire == 2 -> title = reader.readString()
                field == 13 && wire == 0 -> dateAdded = reader.readVarint()
                field == 16 && wire == 2 -> chapters += decodeMihonChapter(reader.readBytes())
                field == 17 && wire == 0 -> categories += reader.readVarint()
                field == 17 && wire == 2 -> categories += reader.readPackedVarints()
                field == 104 && wire == 2 -> histories += decodeMihonHistory(reader.readBytes())
                else -> reader.skip(wire)
            }
        }
        return MihonEntry(sourceId, url, title, dateAdded, categories, chapters, histories)
    }

    private fun decodeMihonChapter(bytes: ByteArray): MihonChapter {
        val reader = ProtoReader(bytes)
        var url = ""
        var name = ""
        var number = -1f
        var sourceOrder: Int? = null
        var read = false
        var lastPageRead = 0
        while (!reader.exhausted()) {
            val tag = reader.readVarint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            when {
                field == 1 && wire == 2 -> url = reader.readString()
                field == 2 && wire == 2 -> name = reader.readString()
                field == 4 && wire == 0 -> read = reader.readVarint() != 0L
                field == 6 && wire == 0 -> lastPageRead = reader.readVarint().toInt().coerceAtLeast(0)
                field == 9 && wire == 5 -> number = reader.readFloat()
                field == 10 && wire == 0 -> {
                    sourceOrder = reader.readVarint().toInt().takeIf { it >= 0 }
                }
                else -> reader.skip(wire)
            }
        }
        return MihonChapter(url, name, number, sourceOrder, read, lastPageRead)
    }

    private fun decodeMihonHistory(bytes: ByteArray): MihonHistory {
        val reader = ProtoReader(bytes)
        var url = ""
        var lastRead = 0L
        while (!reader.exhausted()) {
            val tag = reader.readVarint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            when {
                field == 1 && wire == 2 -> url = reader.readString()
                field == 2 && wire == 0 -> lastRead = reader.readVarint()
                else -> reader.skip(wire)
            }
        }
        return MihonHistory(url, lastRead)
    }

    private fun decodeMihonCategory(bytes: ByteArray): MihonCategory {
        val reader = ProtoReader(bytes)
        var name = ""
        var order = 0L
        var id = 0L
        while (!reader.exhausted()) {
            val tag = reader.readVarint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            when {
                field == 1 && wire == 2 -> name = reader.readString()
                field == 2 && wire == 0 -> order = reader.readVarint()
                field == 3 && wire == 0 -> id = reader.readVarint()
                else -> reader.skip(wire)
            }
        }
        return MihonCategory(name, order, id)
    }

    private fun decodeMihonSource(bytes: ByteArray): MihonSource {
        val reader = ProtoReader(bytes)
        var name = ""
        var id = 0L
        while (!reader.exhausted()) {
            val tag = reader.readVarint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            when {
                field == 1 && wire == 2 -> name = reader.readString()
                field == 2 && wire == 0 -> id = reader.readVarint()
                else -> reader.skip(wire)
            }
        }
        return MihonSource(id, name)
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_BACKUP_BYTES) throw IOException("Backup file is too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun encodeSettings(): JSONObject {
        val settings = JSONObject()
        val values = ctx.app.prefs.sp.all
        values.forEach { (key, value) ->
            if (key in DEVICE_ONLY_SETTINGS || key.startsWith(PER_MANGA_READER_MODE_PREFIX)) return@forEach
            val encoded = JSONObject()
            when (value) {
                is String -> encoded.put("type", "string").put("value", value)
                is Boolean -> encoded.put("type", "boolean").put("value", value)
                is Int -> encoded.put("type", "int").put("value", value)
                is Long -> encoded.put("type", "long").put("value", value)
                is Float -> encoded.put("type", "float").put("value", value.toDouble())
                is Set<*> -> {
                    val strings = value.mapNotNull { it as? String }
                    if (strings.size != value.size) {
                        throw IOException("Unsupported preference set: $key")
                    }
                    encoded.put("type", "string_set")
                        .put("value", JSONArray(strings.sorted()))
                }
                else -> throw IOException("Unsupported preference type for: $key")
            }
            settings.put(key, encoded)
        }
        return settings
    }

    private fun decodeSettings(settings: JSONObject): Map<String, Any> {
        val values = mutableMapOf<String, Any>()
        val keys = settings.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key in DEVICE_ONLY_SETTINGS || key.startsWith(PER_MANGA_READER_MODE_PREFIX)) continue
            val encoded = settings.optJSONObject(key)
                ?: throw IOException("Invalid setting entry: $key")
            val value = encoded.opt("value")
            values[key] = when (encoded.optString("type")) {
                "string" -> value as? String ?: throw IOException("Invalid string setting: $key")
                "boolean" -> value as? Boolean ?: throw IOException("Invalid boolean setting: $key")
                "int" -> (value as? Number)?.toInt() ?: throw IOException("Invalid integer setting: $key")
                "long" -> (value as? Number)?.toLong() ?: throw IOException("Invalid long setting: $key")
                "float" -> (value as? Number)?.toFloat() ?: throw IOException("Invalid float setting: $key")
                "string_set" -> {
                    val array = value as? JSONArray ?: throw IOException("Invalid string set: $key")
                    buildSet {
                        for (index in 0 until array.length()) {
                            add(array.optString(index))
                        }
                    }
                }
                else -> throw IOException("Unknown setting type for: $key")
            }
        }
        return values
    }

    private fun restoreSettings(settings: Map<String, Any>) {
        val editor = ctx.app.prefs.sp.edit()
        ctx.app.prefs.sp.all.keys
            .filterNot { it in DEVICE_ONLY_SETTINGS || it.startsWith(PER_MANGA_READER_MODE_PREFIX) }
            .forEach(editor::remove)
        settings.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> {
                    if (value.any { it !is String }) throw IOException("Invalid string set: $key")
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, value as Set<String>)
                }
                else -> throw IOException("Unsupported setting type for: $key")
            }
        }
        if (!editor.commit()) throw IOException("Could not restore app settings")
    }
}

private data class MihonBackup(
    val entries: List<MihonEntry>,
    val categories: List<MihonCategory>,
    val sources: List<MihonSource>
)

private data class MihonEntry(
    val sourceId: Long,
    val url: String,
    val title: String,
    val dateAdded: Long,
    val categoryRefs: List<Long>,
    val chapters: List<MihonChapter>,
    val histories: List<MihonHistory>
)

private data class MihonChapter(
    val url: String,
    val name: String,
    val number: Float,
    val sourceOrder: Int?,
    val read: Boolean,
    val lastPageRead: Int
)
private data class MihonHistory(val url: String, val lastRead: Long)
private data class MihonCategory(val name: String, val order: Long, val id: Long)
private data class MihonSource(val id: Long, val name: String)

private class ProtoReader(private val bytes: ByteArray) {
    private var position = 0

    fun exhausted(): Boolean = position >= bytes.size

    fun readVarint(): Long {
        var result = 0L
        for (shift in 0..63 step 7) {
            if (position >= bytes.size) throw IOException("Truncated Mihon backup")
            val byte = bytes[position++].toInt() and 0xff
            if (shift == 63 && byte and 0xfe != 0) throw IOException("Malformed Mihon backup")
            result = result or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) return result
        }
        throw IOException("Malformed Mihon backup")
    }

    fun readBytes(): ByteArray {
        val length = readVarint()
        if (length < 0L || length > (bytes.size - position).toLong()) {
            throw IOException("Invalid Mihon backup field length")
        }
        val end = position + length.toInt()
        return bytes.copyOfRange(position, end).also { position = end }
    }

    fun readString(): String = readBytes().toString(Charsets.UTF_8)

    fun readFloat(): Float {
        if (bytes.size - position < 4) throw IOException("Truncated Mihon backup")
        val bits = (bytes[position].toInt() and 0xff) or
            ((bytes[position + 1].toInt() and 0xff) shl 8) or
            ((bytes[position + 2].toInt() and 0xff) shl 16) or
            ((bytes[position + 3].toInt() and 0xff) shl 24)
        position += 4
        return Float.fromBits(bits)
    }

    fun readPackedVarints(): List<Long> {
        val packed = ProtoReader(readBytes())
        return buildList {
            while (!packed.exhausted()) add(packed.readVarint())
        }
    }

    fun skip(wireType: Int) {
        when (wireType) {
            0 -> readVarint()
            1 -> advance(8)
            2 -> {
                val length = readVarint()
                if (length < 0L || length > (bytes.size - position).toLong()) {
                    throw IOException("Invalid Mihon backup field length")
                }
                advance(length.toInt())
            }
            5 -> advance(4)
            else -> throw IOException("Unsupported Mihon backup field")
        }
    }

    private fun advance(count: Int) {
        if (count < 0 || count > bytes.size - position) throw IOException("Truncated Mihon backup")
        position += count
    }
}
