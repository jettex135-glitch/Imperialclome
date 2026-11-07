package com.imperialreader.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class LibraryRefreshConcurrencyControllerTest {
    @Test
    fun sourceOperationsStayWithinTheSharedLimit() = runBlocking {
        val controller = LibraryRefreshConcurrencyController()
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()

        coroutineScope {
            (1..40).map { index ->
                async(Dispatchers.Default) {
                    controller.withSourcePermit("test-source-$index", "chapters") {
                        val nowActive = active.incrementAndGet()
                        maximumActive.accumulateAndGet(nowActive, ::maxOf)
                        delay(25)
                        active.decrementAndGet()
                    }
                }
            }.awaitAll()
        }

        assertEquals(
            10,
            maximumActive.get()
        )
    }

    @Test
    fun failedOperationReleasesItsPermit() = runBlocking {
        val controller = LibraryRefreshConcurrencyController()

        try {
            controller.withSourcePermit("test-source", "details") {
                throw IOException("temporary failure")
            }
        } catch (_: IOException) {
        }

        val result = controller.withSourcePermit("test-source", "chapters") { "completed" }
        assertEquals("completed", result)
        assertTrue(controller.withSourcePermit("test-source", "details") { true })
    }
}
