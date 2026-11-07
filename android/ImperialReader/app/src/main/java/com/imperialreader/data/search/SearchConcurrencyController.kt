package com.imperialreader.data.search

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.ArrayDeque

/** Application-wide FIFO limiter for Global Search source operations and their retries. */
class SearchConcurrencyController(initialLimit: Int = MAX_CONCURRENCY) {
    private data class Waiter(
        val ready: CompletableDeferred<Unit> = CompletableDeferred(),
        var granted: Boolean = false
    )

    private val mutex = Mutex()
    private val waiters = ArrayDeque<Waiter>()
    private var active = 0
    private var limit = initialLimit.also {
        require(it in 1..MAX_CONCURRENCY) {
            "Search concurrency must be between 1 and $MAX_CONCURRENCY"
        }
    }

    suspend fun <T> withPermit(
        onAcquired: suspend () -> Unit = {},
        block: suspend () -> T
    ): T {
        acquire()
        try {
            onAcquired()
            return block()
        } finally {
            release()
        }
    }

    suspend fun setLimit(value: Int) {
        require(value in 1..MAX_CONCURRENCY) { "Search concurrency must be between 1 and $MAX_CONCURRENCY" }
        mutex.withLock {
            limit = value
            dispatchLocked()
        }
    }

    private suspend fun acquire() {
        val waiter = mutex.withLock {
            if (active < limit && waiters.isEmpty()) {
                active++
                null
            } else {
                Waiter().also(waiters::addLast)
            }
        } ?: return

        try {
            waiter.ready.await()
        } catch (e: CancellationException) {
            mutex.withLock {
                if (waiters.remove(waiter)) {
                    waiter.ready.cancel(e)
                } else if (waiter.granted) {
                    waiter.granted = false
                    active--
                    dispatchLocked()
                }
            }
            throw e
        }
    }

    private suspend fun release() {
        mutex.withLock {
            check(active > 0) { "Search concurrency permit released without acquisition" }
            active--
            dispatchLocked()
        }
    }

    private fun dispatchLocked() {
        while (active < limit && waiters.isNotEmpty()) {
            val waiter = waiters.removeFirst()
            if (waiter.ready.isCancelled) continue
            active++
            waiter.granted = true
            if (!waiter.ready.complete(Unit)) {
                waiter.granted = false
                active--
            }
        }
    }

    companion object {
        const val MAX_CONCURRENCY = 5
    }
}
