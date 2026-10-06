package com.atenboro.nav.nav

import com.atenboro.nav.model.NavUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavFeedTest {

    @Test
    fun mergeDistance_explicitNewAlwaysWinsIncludingCountdown() {
        assertEquals(300, NavFeed.mergeDistance(300, 400))
        assertEquals(20, NavFeed.mergeDistance(20, 180))
        assertEquals(0, NavFeed.mergeDistance(0, 50))
        assertEquals(1200, NavFeed.mergeDistance(1200, 100))
    }

    @Test
    fun mergeDistance_missingNewKeepsPrev() {
        assertEquals(400, NavFeed.mergeDistance(-1, 400))
        assertEquals(-1, NavFeed.mergeDistance(-1, -1))
    }

    @Test
    fun isUseful_acceptsSub30mPocketDistance() {
        // Раньше 30..2500 — на подъезде <30 м update отбрасывался и OLED слал lastSent
        val near = NavUpdate(turn = "none", distM = 20, street = "Sayanskaya", navigating = true)
        assertTrue(NavFeed.isUseful(near))
        val far = NavUpdate(turn = "none", distM = 12_000, street = "Ring", navigating = true)
        assertTrue(NavFeed.isUseful(far))
        val empty = NavUpdate(turn = "none", distM = -1, navigating = false)
        assertFalse(NavFeed.isUseful(empty))
    }
}
