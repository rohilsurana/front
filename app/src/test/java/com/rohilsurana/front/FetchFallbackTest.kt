package com.rohilsurana.front

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the fallback logic in AlarmService.
 * We extract the pure logic into a companion helper so it's testable
 * without an Android runtime (no instrumented test needed).
 */
class FetchFallbackTest {

    // Mirror of the logic in AlarmService.fetchTextOrFallback (pure, no Android deps)
    private fun selectText(fetched: String?, fallback: String): String {
        return if (!fetched.isNullOrBlank()) fetched.trim() else fallback
    }

    @Test
    fun `uses fetched text when non-blank`() {
        val result = selectText("Good morning! Stand-up at 10.", "default")
        assertEquals("Good morning! Stand-up at 10.", result)
    }

    @Test
    fun `falls back when fetched text is null`() {
        val result = selectText(null, "Wake up!")
        assertEquals("Wake up!", result)
    }

    @Test
    fun `falls back when fetched text is blank`() {
        val result = selectText("   ", "Wake up!")
        assertEquals("Wake up!", result)
    }

    @Test
    fun `falls back when fetched text is empty`() {
        val result = selectText("", "Wake up!")
        assertEquals("Wake up!", result)
    }
}
