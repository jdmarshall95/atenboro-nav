package com.atenboro.nav.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavTextFilterTest {

    @Test
    fun parsesPocketBanner() {
        val b = NavTextFilter.parsePocketBanner(
            listOf("400 m — Большой Строченовский переулок")
        )
        assertNotNull(b)
        assertEquals(400, b!!.distM)
        assertEquals("Большой Строченовский переулок", b.street)
    }

    @Test
    fun sanitizeDropsChrome() {
        val clean = NavTextFilter.sanitize(
            listOf("Update", "Step by Step", "400 m — Улица Ленина", "32 min", "Duration: 29 min")
        )
        assertEquals(listOf("400 m — Улица Ленина"), clean)
    }

    @Test
    fun junkExact() {
        assertTrue(NavTextFilter.isJunk("Update"))
        assertTrue(NavTextFilter.isJunk("Search"))
        assertNull(NavTextFilter.parsePocketBanner(listOf("Update | Step by Step")))
    }
}
