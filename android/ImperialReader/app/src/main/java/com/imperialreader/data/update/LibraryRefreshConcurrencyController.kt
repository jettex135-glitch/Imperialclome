package com.imperialreader.data.update

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class LibraryRefreshConcurrencyController(
    private val onOperationTiming: (sourceId: String, operation: String, queueWaitMs: Long, durationMs: Long) -> Unit =
        { _, _, _, _ -> }
) {
    private val semaphore = Semaphore(MAX_CONCURRENT_SOURCE_OPERATIONS)

    suspend fun <T> withSourcePermit(
        sourceId: String,
        operation: String,
        block: suspend () -> T
    ): T {
        val queuedAt = System.nanoTime()
        return semaphore.withPermit {
            val startedAt = System.nanoTime()
            val queueWaitMs = (startedAt - queuedAt).toMillis()
            try {
                block()
            } finally {
                onOperationTiming(
                    sourceId,
                    operation,
                    queueWaitMs,
                    (System.nanoTime() - startedAt).toMillis()
                )
            }
        }
    }

    companion object {
        const val MAX_CONCURRENT_SOURCE_OPERATIONS = 10

        private fun Long.toMillis(): Long = this / 1_000_000L
    }
}
