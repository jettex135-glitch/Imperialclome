package com.imperialreader.ui.reader

import com.imperialreader.data.db.ChapterEntity
import java.util.Locale
import kotlin.math.abs

internal data class ChapterNavigationTarget(
    val index: Int,
    val skippedChapterIds: Set<Long>
)

internal object ChapterNavigation {
    fun next(chapters: List<ChapterEntity>, currentIndex: Int): ChapterNavigationTarget? {
        val current = chapters.getOrNull(currentIndex) ?: return null
        val nextIndices = (currentIndex + 1 until chapters.size).toList()
        if (nextIndices.isEmpty()) return null

        val currentNumber = current.chapterNumber
        val currentScanlator = current.scanlator.normalizedScanlator()
        val hasNumber = currentNumber >= 0f
        val candidates = nextIndices.filter { index ->
            val chapter = chapters[index]
            !chapter.read &&
                !chapter.name.isGaiden() &&
                !hasReadDuplicate(chapters, chapter) &&
                (!hasNumber || chapter.chapterNumber > currentNumber)
        }
        val preferred = if (currentScanlator.isNullOrBlank()) {
            emptyList()
        } else {
            candidates.filter { chapters[it].scanlator.normalizedScanlator() == currentScanlator }
        }
        val orderedCandidate = (preferred.ifEmpty { candidates })
            .minWithOrNull(
                compareBy<Int> { chapters[it].chapterNumber.takeIf { number -> number >= 0f } ?: Float.MAX_VALUE }
                    .thenBy { it }
            )
        val fallbackCandidates = nextIndices.filter { index ->
            val chapter = chapters[index]
            !chapter.read &&
                !chapter.name.isGaiden() &&
                (!hasNumber || chapter.chapterNumber < 0f)
        }
        val fallbackPreferred = if (currentScanlator.isNullOrBlank()) {
            emptyList()
        } else {
            fallbackCandidates.filter { chapters[it].scanlator.normalizedScanlator() == currentScanlator }
        }
        val targetIndex = orderedCandidate
            ?: fallbackPreferred.firstOrNull()
            ?: fallbackCandidates.firstOrNull()
            ?: return null

        val target = chapters[targetIndex]
        val targetNumber = target.chapterNumber
        val targetScanlator = target.scanlator.normalizedScanlator()
        val skipped = chapters.indices.asSequence()
            .filter { it != currentIndex && it != targetIndex }
            .filter { index ->
                val chapter = chapters[index]
                if (chapter.read) return@filter false

                val sameAsCurrent = hasNumber &&
                    chapter.chapterNumber.isSameChapterNumber(currentNumber)
                val sameAsTarget = targetNumber >= 0f &&
                    chapter.chapterNumber.isSameChapterNumber(targetNumber)
                val alreadyReadDuplicate = hasReadDuplicate(chapters, chapter)
                val skippedGaiden = index > currentIndex &&
                    index < targetIndex &&
                    chapter.name.isGaiden()
                val skippedOtherScanlation = hasNumber &&
                    targetNumber >= 0f &&
                    chapter.chapterNumber > currentNumber &&
                    chapter.chapterNumber <= targetNumber &&
                    chapter.scanlator.normalizedScanlator() != targetScanlator
                sameAsCurrent || sameAsTarget || alreadyReadDuplicate ||
                    skippedGaiden || skippedOtherScanlation
            }
            .map { chapters[it].id }
            .toSet()
        return ChapterNavigationTarget(targetIndex, skipped)
    }

    private fun Float.isSameChapterNumber(other: Float): Boolean =
        this >= 0f && other >= 0f && abs(this - other) < 0.001f

    private fun hasReadDuplicate(chapters: List<ChapterEntity>, chapter: ChapterEntity): Boolean =
        chapter.chapterNumber >= 0f && chapters.any {
            it.id != chapter.id &&
                it.read &&
                it.chapterNumber.isSameChapterNumber(chapter.chapterNumber)
        }

    private fun String.isGaiden(): Boolean = GAIDEN_PATTERN.containsMatchIn(this)

    private fun String?.normalizedScanlator(): String? =
        this?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }

    private val GAIDEN_PATTERN = Regex("""\bgaiden\b""", RegexOption.IGNORE_CASE)
}
