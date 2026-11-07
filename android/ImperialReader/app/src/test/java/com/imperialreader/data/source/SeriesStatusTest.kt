package com.imperialreader.data.source

import com.imperialreader.util.statusLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesStatusTest {
    @Test
    fun parsesDistinctSeriesStates() {
        assertEquals(1, statusFromText("Ongoing"))
        assertEquals(2, statusFromText("Completed"))
        assertEquals(3, statusFromText("On hiatus"))
        assertEquals(4, statusFromText("Cancelled"))
        assertEquals(4, statusFromText("Discontinued"))
        assertEquals(0, statusFromText("Unknown status"))
    }

    @Test
    fun labelsDistinctSeriesStates() {
        assertEquals("Ongoing", statusLabel(1))
        assertEquals("Completed", statusLabel(2))
        assertEquals("Hiatus", statusLabel(3))
        assertEquals("Axed", statusLabel(4))
    }
}
