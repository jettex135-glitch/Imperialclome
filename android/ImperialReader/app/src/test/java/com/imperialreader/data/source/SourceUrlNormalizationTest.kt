package com.imperialreader.data.source

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceUrlNormalizationTest {
    @Test
    fun makesRestoredRelativePathsUsableAsHttpUrls() {
        assertEquals(
            "https://mangapill.com/manga/example",
            resolveHttpSourceUrl("https://mangapill.com", "/manga/example")
        )
        assertEquals(
            "https://mangapill.com/manga/example",
            resolveHttpSourceUrl("https://mangapill.com", "manga/example")
        )
    }

    @Test
    fun preservesAbsoluteAndProtocolRelativeUrls() {
        assertEquals(
            "https://mangapill.com/manga/example",
            resolveHttpSourceUrl("https://mangapill.com", "https://mangapill.com/manga/example")
        )
        assertEquals(
            "https://cdn.example/image.webp",
            resolveHttpSourceUrl("https://mangapill.com", "//cdn.example/image.webp")
        )
    }
}
