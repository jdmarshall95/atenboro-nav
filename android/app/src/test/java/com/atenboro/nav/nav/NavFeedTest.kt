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
    fun mergeCamera_stickyThenClearsAfterHold() {
        val t0 = 1_000_000L
        val on = NavFeed.mergeCamera(
            newCamera = true, newCamM = 80, newCamKmh = 60,
            prevCamera = false, prevCamM = -1, prevCamKmh = -1,
            lastCameraTrueAt = t0, now = t0
        )
        assertTrue(on.camera)
        assertEquals(60, on.camKmh)

        val within = NavFeed.mergeCamera(
            newCamera = false, newCamM = -1, newCamKmh = -1,
            prevCamera = true, prevCamM = 80, prevCamKmh = 60,
            lastCameraTrueAt = t0, now = t0 + 1_000L
        )
        assertTrue(within.camera)
        assertEquals(60, within.camKmh)

        val after = NavFeed.mergeCamera(
            newCamera = false, newCamM = -1, newCamKmh = -1,
            prevCamera = true, prevCamM = 80, prevCamKmh = 60,
            lastCameraTrueAt = t0, now = t0 + 4_000L
        )
        assertFalse(after.camera)
        assertEquals(-1, after.camM)
        assertEquals(-1, after.camKmh)
    }

    @Test
    fun isAidlHolding_falseByDefault() {
        assertFalse(NavFeed.isAidlHolding())
    }

    @Test
    fun clampToAidlDistance_coarseKilometresCannotIncreaseDistance() {
        val aidl = NavUpdate(turn = "right", distM = 16_000, navigating = true)
        val notif = NavUpdate(turn = "right", distM = 17_000)
        assertEquals(16_000, NavFeed.clampToAidlDistance(notif, aidl, 15_000L).distM)
    }

    @Test
    fun clampToAidlDistance_countdownAndMissingPassThrough() {
        val aidl = NavUpdate(turn = "right", distM = 16_000, navigating = true)
        assertEquals(15_000, NavFeed.clampToAidlDistance(NavUpdate(distM = 15_000), aidl, 15_000L).distM)
        assertEquals(-1, NavFeed.clampToAidlDistance(NavUpdate(distM = -1), aidl, 15_000L).distM)
        assertEquals(300, NavFeed.clampToAidlDistance(NavUpdate(distM = 300), null, 15_000L).distM)
    }

    @Test
    fun clampToAidlDistance_staleAidlNoLongerClamps() {
        val aidl = NavUpdate(turn = "right", distM = 16_000, navigating = true)
        val notif = NavUpdate(turn = "right", distM = 17_000)
        assertEquals(17_000, NavFeed.clampToAidlDistance(notif, aidl, 61_000L).distM)
        // AIDL ещё не приходил ни разу (lastAidlAt = 0 -> age = -1)
        assertEquals(17_000, NavFeed.clampToAidlDistance(notif, aidl, -1L).distM)
    }
}
