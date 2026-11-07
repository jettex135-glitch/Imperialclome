package com.imperialreader.data.search

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SearchConcurrencyControllerTest {
    @Test
    fun limitsSharedOperationsAndQueuesTheRest() = runBlocking {
        val controller = SearchConcurrencyController(5)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val firstFiveStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val jobs = List(12) {
            launch {
                controller.withPermit {
                    val nowActive = active.incrementAndGet()
                    maximum.updateAndGet { maxOf(it, nowActive) }
                    if (nowActive == 5) firstFiveStarted.complete(Unit)
                    release.await()
                    active.decrementAndGet()
                }
            }
        }

        firstFiveStarted.await()
        assertEquals(5, active.get())
        release.complete(Unit)
        jobs.joinAll()

        assertEquals(0, active.get())
        assertEquals(5, maximum.get())
    }

    @Test
    fun cancellationOfQueuedOperationDoesNotConsumeAPermit() = runBlocking {
        val controller = SearchConcurrencyController(1)
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = launch {
            controller.withPermit {
                held.complete(Unit)
                release.await()
            }
        }
        held.await()

        val cancelled = launch { controller.withPermit { error("Cancelled waiter must not run") } }
        cancelled.cancelAndJoin()
        release.complete(Unit)
        owner.join()

        var acquired = false
        controller.withPermit { acquired = true }
        assertTrue(acquired)
    }

    @Test
    fun cancellationAndFailureReleaseAnActivePermit() = runBlocking {
        val controller = SearchConcurrencyController(1)
        val entered = CompletableDeferred<Unit>()
        val active = launch {
            controller.withPermit {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        active.cancelAndJoin()

        assertTrue(runCatching {
            controller.withPermit { error("Expected operation failure") }
        }.isFailure)

        var acquiredAfterFailure = false
        controller.withPermit { acquiredAfterFailure = true }
        assertTrue(acquiredAfterFailure)
    }

    @Test
    fun raisingLimitWakesQueuedOperationsWithoutExceedingNewLimit() = runBlocking {
        val controller = SearchConcurrencyController(1)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val firstStarted = CompletableDeferred<Unit>()
        val threeStarted = CompletableDeferred<Unit>()
        val jobs = List(4) {
            launch {
                controller.withPermit {
                    val count = active.incrementAndGet()
                    maximum.updateAndGet { maxOf(it, count) }
                    firstStarted.complete(Unit)
                    if (count == 3) threeStarted.complete(Unit)
                    release.await()
                    active.decrementAndGet()
                }
            }
        }

        firstStarted.await()
        controller.setLimit(3)
        threeStarted.await()
        release.complete(Unit)
        jobs.joinAll()

        assertEquals(0, active.get())
        assertTrue(maximum.get() <= 3)
        assertTrue(maximum.get() >= 2)
    }
}
