package com.imperialreader.data.download

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.documentfile.provider.DocumentFile
import com.imperialreader.data.db.ChapterEntity
import com.imperialreader.data.db.MangaEntity
import com.imperialreader.data.book.BookSources
import com.imperialreader.data.book.NovelChapterEpub
import com.imperialreader.data.source.SChapter
import com.imperialreader.data.source.MangaFirePageTransform
import com.imperialreader.data.source.SManga
import com.imperialreader.util.app
import com.imperialreader.util.mimeForExt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

data class DownloadQueueItem(
    val taskKey: String,
    val mangaId: Long?,
    val mangaTitle: String,
    val chapterId: Long?,
    val chapterName: String,
    val progress: Int,
    val downloading: Boolean,
    val paused: Boolean = false
)

/**
 * Configurable concurrent chapter queue (3 pages in parallel per chapter).
 * Progress is exposed per chapter id (0..100), with a separate ordered snapshot for the More tab.
 * The queue lives in memory; chapters that finished are flagged `downloaded` in the database.
 */
class DownloadManager(private val ctx: Context) {

    private data class Task(val manga: MangaEntity, val chapter: ChapterEntity)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = ArrayDeque<Task>()
    private val activeTasks = LinkedHashMap<Long, Task>()
    private val activeJobs = LinkedHashMap<Long, Job>()
    private val externalTasks = LinkedHashMap<String, DownloadQueueItem>()
    private val pausedMangaIds = mutableSetOf<Long>()
    private val pauseRequestedChapterIds = mutableSetOf<Long>()
    private val pausedChapterIds = mutableSetOf<Long>()
    private val pauseGates = mutableMapOf<Long, CompletableDeferred<Unit>>()
    private val cancelledChapterIds = mutableSetOf<Long>()
    private val activeCalls = mutableMapOf<Long, MutableSet<Call>>()

    private val _progress = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val progress: StateFlow<Map<Long, Int>> = _progress

    private val _queueItems = MutableStateFlow<List<DownloadQueueItem>>(emptyList())
    val queueItems: StateFlow<List<DownloadQueueItem>> = _queueItems

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val errors: SharedFlow<String> = _errors

    @Synchronized
    private fun setProgress(id: Long, value: Int?) {
        _progress.value = if (value == null) _progress.value - id else _progress.value + (id to value)
        publishQueueLocked()
    }

    /** Called under this manager's monitor. */
    private fun publishQueueLocked() {
        val values = _progress.value
        val items = buildList {
            activeTasks.values.forEach { task ->
                add(
                    DownloadQueueItem(
                        "chapter:${task.chapter.id}", task.manga.id, task.manga.title, task.chapter.id, task.chapter.name,
                        values[task.chapter.id] ?: 0, downloading = true,
                        paused = task.chapter.id in pausedChapterIds || task.manga.id in pausedMangaIds
                    )
                )
            }
            queue.forEach { task ->
                add(
                    DownloadQueueItem(
                        "chapter:${task.chapter.id}", task.manga.id, task.manga.title, task.chapter.id, task.chapter.name,
                        values[task.chapter.id] ?: 0, downloading = false,
                        paused = task.chapter.id in pausedChapterIds || task.manga.id in pausedMangaIds
                    )
                )
            }
            addAll(externalTasks.values)
        }
        _queueItems.value = items
    }

    /** Adds or updates a non-chapter-manager download (currently EPUB/catalog downloads). */
    @Synchronized
    fun reportExternalDownload(
        taskKey: String,
        title: String,
        detail: String,
        progress: Int = 0
    ) {
        externalTasks[taskKey] = DownloadQueueItem(
            taskKey = taskKey,
            mangaId = null,
            mangaTitle = title,
            chapterId = null,
            chapterName = detail,
            progress = progress.coerceIn(0, 100),
            downloading = true
        )
        publishQueueLocked()
    }

    @Synchronized
    fun removeExternalDownload(taskKey: String) {
        externalTasks.remove(taskKey)
        publishQueueLocked()
    }

    @Synchronized
    fun enqueue(manga: MangaEntity, chapters: List<ChapterEntity>) {
        for (c in chapters) {
            if (c.downloaded || _progress.value.containsKey(c.id)) continue
            queue.addLast(Task(manga, c))
            setProgress(c.id, 0)
        }
        startWorker()
    }

    fun pauseChapter(chapterId: Long) {
        val calls = synchronized(this) {
            if (chapterId !in activeTasks && queue.none { it.chapter.id == chapterId }) return
            pausedChapterIds += chapterId
            pauseGates.getOrPut(chapterId) { CompletableDeferred() }
            publishQueueLocked()
            activeCalls[chapterId]?.toList().orEmpty().also {
                activeJobs[chapterId]?.cancel()
            }
        }
        calls.forEach(Call::cancel)
        synchronized(this) { startWorker() }
    }

    fun resumeChapter(chapterId: Long) {
        synchronized(this) {
            pausedChapterIds -= chapterId
            pauseGates.remove(chapterId)?.complete(Unit)
            publishQueueLocked()
            startWorker()
        }
    }

    fun pauseManga(mangaId: Long) {
        val calls = synchronized(this) {
            val tasks = activeTasks.values.filter { it.manga.id == mangaId }
            if (tasks.isEmpty() && queue.none { it.manga.id == mangaId }) return
            pausedMangaIds += mangaId
            tasks.forEach { task ->
                val id = task.chapter.id
                pauseRequestedChapterIds += id
                pausedChapterIds += id
                pauseGates.getOrPut(id) { CompletableDeferred() }
                activeJobs[id]?.cancel()
            }
            publishQueueLocked()
            tasks.flatMap { activeCalls[it.chapter.id]?.toList().orEmpty() }.also {
                startWorker()
            }
        }
        calls.forEach(Call::cancel)
    }

    fun resumeManga(mangaId: Long) {
        synchronized(this) {
            pausedMangaIds -= mangaId
            val chapterIds = (queue.filter { it.manga.id == mangaId }.map { it.chapter.id } +
                activeTasks.values.filter { it.manga.id == mangaId }.map { it.chapter.id })
            chapterIds.forEach { id ->
                pausedChapterIds -= id
                pauseGates.remove(id)?.complete(Unit)
            }
            publishQueueLocked()
            startWorker()
        }
    }

    fun pauseAll() {
        val calls = synchronized(this) {
            val chapterIds = queue.map { it.chapter.id } + activeTasks.keys
            if (chapterIds.isEmpty()) return
            pausedChapterIds += chapterIds
            chapterIds.forEach { id -> pauseGates.getOrPut(id) { CompletableDeferred() } }
            publishQueueLocked()
            chapterIds.flatMap { id ->
                activeJobs[id]?.cancel()
                activeCalls[id]?.toList().orEmpty()
            }.also { startWorker() }
        }
        calls.forEach(Call::cancel)
    }

    fun resumeAll() {
        synchronized(this) {
            pausedMangaIds.clear()
            pausedChapterIds.toList().forEach { id -> pauseGates.remove(id)?.complete(Unit) }
            pausedChapterIds.clear()
            publishQueueLocked()
            startWorker()
        }
    }

    fun cancelChapter(chapterId: Long) {
        val calls = synchronized(this) {
            val removedQueued = queue.removeAll { it.chapter.id == chapterId }
            val isActive = chapterId in activeTasks
            if (!removedQueued && !isActive) return
            if (isActive) cancelledChapterIds += chapterId
            else _progress.value = _progress.value - chapterId
            pausedChapterIds -= chapterId
            pauseGates.remove(chapterId)?.complete(Unit)
            if (isActive) activeJobs[chapterId]?.cancel()
            publishQueueLocked()
            (if (isActive) activeCalls[chapterId]?.toList().orEmpty() else emptyList()).also {
                startWorker()
            }
        }
        calls.forEach(Call::cancel)
    }

    fun cancelAllForManga(mangaId: Long) {
        val calls = synchronized(this) {
            pausedMangaIds -= mangaId
            val queuedIds = queue.filter { it.manga.id == mangaId }.map { it.chapter.id }
            queue.removeAll { it.manga.id == mangaId }
            queuedIds.forEach { id ->
                _progress.value = _progress.value - id
                pauseRequestedChapterIds -= id
                pausedChapterIds -= id
                pauseGates.remove(id)?.complete(Unit)
            }
            val active = activeTasks.values.filter { it.manga.id == mangaId }
            active.forEach { task ->
                val id = task.chapter.id
                cancelledChapterIds += id
                pauseRequestedChapterIds -= id
                pausedChapterIds -= id
                pauseGates.remove(id)?.complete(Unit)
                activeJobs[id]?.cancel()
            }
            publishQueueLocked()
            active.flatMap { activeCalls[it.chapter.id]?.toList().orEmpty() }.also {
                startWorker()
            }
        }
        calls.forEach(Call::cancel)
    }

    @Synchronized
    private fun startWorker() {
        while (activeTasks.values.count {
                it.chapter.id !in pausedChapterIds && it.manga.id !in pausedMangaIds
            } < ctx.app.prefs.downloadConcurrency
        ) {
            val queueIndex = queue.indexOfFirst {
                it.chapter.id !in pausedChapterIds && it.manga.id !in pausedMangaIds
            }
            if (queueIndex < 0) break
            val task = queue.removeAt(queueIndex)
            val chapterId = task.chapter.id
            activeTasks[chapterId] = task
            publishQueueLocked()
            val job = scope.launch(start = CoroutineStart.LAZY) { runTask(task) }
            activeJobs[chapterId] = job
            job.start()
        }
        if (activeTasks.isNotEmpty()) {
            DownloadService.start(ctx)
        } else {
            DownloadService.stop(ctx)
        }
    }

    private suspend fun runTask(task: Task) {
        val chapterId = task.chapter.id
        var requeue = false
        try {
            awaitIfResumed(chapterId)
            download(task)
        } catch (e: CancellationException) {
            val paused = synchronized(this) {
                (chapterId in pausedChapterIds || task.manga.id in pausedMangaIds ||
                    chapterId in pauseRequestedChapterIds) && chapterId !in cancelledChapterIds
            }
            val cancelled = synchronized(this) { chapterId in cancelledChapterIds }
            when {
                paused -> requeue = true
                cancelled -> {
                    ctx.app.storage.deleteDownload(task.manga, task.chapter)
                }
                else -> throw e
            }
        } catch (e: Exception) {
            val paused = synchronized(this) {
                (chapterId in pausedChapterIds || task.manga.id in pausedMangaIds ||
                    chapterId in pauseRequestedChapterIds) && chapterId !in cancelledChapterIds
            }
            if (paused) {
                requeue = true
            } else {
                ctx.app.storage.deleteDownload(task.manga, task.chapter)
                _errors.tryEmit("Download failed: ${task.chapter.name} (${e.message})")
            }
        } finally {
            synchronized(this) {
                activeTasks.remove(chapterId)
                activeJobs.remove(chapterId)
                val userCancelled = chapterId in cancelledChapterIds
                cancelledChapterIds -= chapterId
                if (requeue && !userCancelled) {
                    if (queue.none { it.chapter.id == chapterId }) queue.addFirst(task)
                } else {
                    _progress.value = _progress.value - chapterId
                    pausedChapterIds -= chapterId
                    pauseGates.remove(chapterId)?.complete(Unit)
                }
                pauseRequestedChapterIds -= chapterId
                publishQueueLocked()
                startWorker()
            }
        }
    }

    private suspend fun download(task: Task) {
        val app = ctx.app
        awaitIfResumed(task.chapter.id)
        if (BookSources.get(task.manga.source)?.isWebNovel == true) {
            NovelChapterEpub.download(ctx, task.manga, task.chapter)
            app.db.chapterDao().setDownloaded(task.chapter.id, true)
            return
        }
        val source = app.sources.get(task.manga.source) ?: throw IOException("Source not available")
        val loaded = source.loadPages(
            SManga(url = task.manga.url, title = task.manga.title),
            SChapter(url = task.chapter.url, name = task.chapter.name)
        )
        val pages = loaded.urls
        if (pages.isEmpty()) throw IOException("No pages found")
        val dir = app.storage.chapterDir(task.manga, task.chapter, create = true)
            ?: throw IOException("Cannot create download folder")
        dir.listFiles().forEach { it.delete() }

        val done = AtomicInteger(0)
        val sem = Semaphore(3)
        coroutineScope {
            pages.mapIndexed { index, url ->
                async {
                    awaitIfResumed(task.chapter.id)
                    sem.withPermit {
                        awaitIfResumed(task.chapter.id)
                        downloadPage(task.chapter.id, loaded.headers, url, index, dir)
                        setProgress(task.chapter.id, done.incrementAndGet() * 100 / pages.size)
                    }
                }
            }.awaitAll()
        }
        app.db.chapterDao().setDownloaded(task.chapter.id, true)
    }

    private suspend fun downloadPage(
        chapterId: Long,
        headers: Map<String, String>,
        url: String,
        index: Int,
        dir: DocumentFile
    ) {
        var lastError: Exception? = null
        for (attempt in 0 until 3) {
            awaitIfResumed(chapterId)
            var created: DocumentFile? = null
            var call: Call? = null
            try {
                val request = Request.Builder().url(url).apply {
                    headers.forEach { (k, v) -> header(k, v) }
                }.build()
                call = ctx.app.network.client.newCall(request)
                synchronized(this) { activeCalls.getOrPut(chapterId) { mutableSetOf() }.add(call) }
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val ct = resp.header("Content-Type").orEmpty()
                    val scrambleOffset = MangaFirePageTransform.offset(url)
                    val urlExtension = url.substringBefore('?').substringAfterLast('.', "").lowercase()
                    val ext = if (scrambleOffset != null) "jpg" else when {
                        ct.substringBefore(';').trim().equals("video/mp4", ignoreCase = true) ||
                            urlExtension == "mp4" -> "mp4"
                        urlExtension == "psd" || urlExtension == "psb" -> urlExtension
                        "png" in ct -> "png"
                        "webp" in ct -> "webp"
                        "gif" in ct -> "gif"
                        "avif" in ct -> "avif"
                        else -> "jpg"
                    }
                    val file = dir.createFile(mimeForExt(ext), "%03d.%s".format(index + 1, ext))
                        ?: throw IOException("Cannot create file")
                    created = file
                    val out = ctx.contentResolver.openOutputStream(file.uri, "w")
                        ?: throw IOException("Cannot open file for writing")
                    out.use {
                        if (scrambleOffset == null) {
                            resp.body!!.byteStream().copyTo(it)
                        } else {
                            val bytes = resp.body!!.bytes()
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                ?: throw IOException("Could not decode a MangaFire page image")
                            val transformed = MangaFirePageTransform.unscramble(bitmap, scrambleOffset)
                            if (!transformed.compress(Bitmap.CompressFormat.JPEG, 95, it)) {
                                throw IOException("Could not save the decoded MangaFire page image")
                            }
                            if (transformed !== bitmap) transformed.recycle()
                            bitmap.recycle()
                        }
                    }
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isCancelled(chapterId)) throw CancellationException("Download canceled")
                lastError = e
                created?.delete()
                delay(1000L * (attempt + 1))
            } finally {
                call?.let { finished ->
                    synchronized(this) {
                        activeCalls[chapterId]?.let { calls ->
                            calls.remove(finished)
                            if (calls.isEmpty()) activeCalls.remove(chapterId)
                        }
                    }
                }
            }
        }
        throw lastError ?: IOException("Download failed")
    }

    private suspend fun awaitIfResumed(chapterId: Long) {
        while (true) {
            val gate = synchronized(this) { pauseGates[chapterId] } ?: break
            gate.await()
        }
        if (isCancelled(chapterId)) throw CancellationException("Download canceled")
        currentCoroutineContext().ensureActive()
    }

    private fun isCancelled(chapterId: Long): Boolean =
        synchronized(this) { chapterId in cancelledChapterIds }

    suspend fun deleteDownload(manga: MangaEntity, chapter: ChapterEntity) {
        val dir = ctx.app.storage.chapterDir(manga, chapter, create = false)
        if (dir != null && dir.exists() && !ctx.app.storage.deleteDownload(manga, chapter)) {
            throw IOException("Could not delete downloaded files for “${chapter.name}”")
        }
        ctx.app.db.chapterDao().setDownloaded(chapter.id, false)
    }
}
