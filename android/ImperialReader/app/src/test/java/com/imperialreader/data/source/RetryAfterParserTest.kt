package com.imperialreader.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class RetryAfterParserTest {
    @Test
    fun parsesDeltaSecondsAndRejectsInvalidValues() {
        assertEquals(3_000L, parseRetryAfterMillis("3", nowMillis = 0L))
        assertEquals(0L, parseRetryAfterMillis("-2", nowMillis = 0L))
        assertNull(parseRetryAfterMillis("soon", nowMillis = 0L))
        assertNull(parseRetryAfterMillis(null, nowMillis = 0L))
    }

    @Test
    fun parsesHttpDateRelativeToCurrentTime() {
        val now = Instant.parse("2025-01-01T00:00:00Z")
        val header = DateTimeFormatter.RFC_1123_DATE_TIME.format(
            Instant.parse("2025-01-01T00:00:05Z").atZone(ZoneOffset.UTC)
        )
        assertEquals(5_000L, parseRetryAfterMillis(header, now.toEpochMilli()))
    }
}
