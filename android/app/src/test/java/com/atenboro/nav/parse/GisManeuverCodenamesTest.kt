package com.atenboro.nav.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты маппинга кодовых имён манёвров 2ГИС в токены прошивки:
 * left/right/slight_left/slight_right/u_turn/straight/roundabout/arrive.
 */
class GisManeuverCodenamesTest {

    @Test
    fun basicManeuvers() {
        assertEquals(GisManeuverCodenames.LEFT, GisManeuverCodenames.toTurn("left"))
        assertEquals(GisManeuverCodenames.RIGHT, GisManeuverCodenames.toTurn("right"))
        assertEquals(GisManeuverCodenames.STRAIGHT, GisManeuverCodenames.toTurn("straight"))
        assertEquals(GisManeuverCodenames.SLIGHT_LEFT, GisManeuverCodenames.toTurn("slightly_left"))
        assertEquals(GisManeuverCodenames.SLIGHT_RIGHT, GisManeuverCodenames.toTurn("slightly_right"))
        assertEquals(GisManeuverCodenames.U_TURN, GisManeuverCodenames.toTurn("uturn"))
    }

    @Test
    fun crossroadVariants() {
        assertEquals(GisManeuverCodenames.LEFT, GisManeuverCodenames.toTurn("crossroad_left"))
        assertEquals(GisManeuverCodenames.RIGHT, GisManeuverCodenames.toTurn("crossroad_right"))
        assertEquals(GisManeuverCodenames.STRAIGHT, GisManeuverCodenames.toTurn("crossroad_straight"))
        assertEquals(
            GisManeuverCodenames.SLIGHT_RIGHT,
            GisManeuverCodenames.toTurn("crossroad_keep_right")
        )
        assertEquals(GisManeuverCodenames.U_TURN, GisManeuverCodenames.toTurn("crossroad_uturn"))
    }

    @Test
    fun ringRoadVariantsAllMapToRoundabout() {
        val ring = listOf(
            "ringroad_exit", "ringroad_forward", "ringroad_backward",
            "ringroad_leftside_forward", "ringroad_leftside_backward",
            "left_ring_exit", "right_ring_exit",
            "ringroad_left_90", "ringroad_right_135",
            "ringroad_leftside_left_225", "ringroad_leftside_right_315"
        )
        for (c in ring) {
            assertEquals(c, GisManeuverCodenames.ROUNDABOUT, GisManeuverCodenames.toTurn(c))
        }
    }

    @Test
    fun finishAndNonManeuvers() {
        assertEquals(GisManeuverCodenames.ARRIVE, GisManeuverCodenames.toTurn("finish"))
        assertEquals(GisManeuverCodenames.NONE, GisManeuverCodenames.toTurn("barrier"))
        assertEquals(GisManeuverCodenames.NONE, GisManeuverCodenames.toTurn("stairs_up"))
        assertEquals(GisManeuverCodenames.NONE, GisManeuverCodenames.toTurn("toll_road"))
    }

    @Test
    fun normalizationHandlesCaseAndSeparators() {
        assertEquals(GisManeuverCodenames.LEFT, GisManeuverCodenames.toTurn("  LEFT "))
        assertEquals(GisManeuverCodenames.LEFT, GisManeuverCodenames.toTurn("crossroad-left"))
        assertEquals(GisManeuverCodenames.LEFT, GisManeuverCodenames.toTurn("crossroad left"))
    }

    @Test
    fun unknownCodenameFallsBackToInference() {
        // Новый кодename, которого нет в таблице, но направление угадывается
        assertEquals(GisManeuverCodenames.LEFT, GisManeuverCodenames.toTurn("crossroad_left_new"))
        assertEquals(
            GisManeuverCodenames.ROUNDABOUT,
            GisManeuverCodenames.toTurn("ringroad_something_new")
        )
        assertEquals(GisManeuverCodenames.NONE, GisManeuverCodenames.toTurn("totally_unknown"))
        assertEquals(GisManeuverCodenames.NONE, GisManeuverCodenames.toTurn(null))
        assertEquals(GisManeuverCodenames.NONE, GisManeuverCodenames.toTurn(""))
    }

    @Test
    fun isKnownFlagsCatalogAndBlanks() {
        assertTrue(GisManeuverCodenames.isKnown("crossroad_uturn"))
        assertFalse(GisManeuverCodenames.isKnown("brand_new_icon"))
        // Пустой codename — не «неизвестный», а «нет манёвра»
        assertTrue(GisManeuverCodenames.isKnown(null))
        assertTrue(GisManeuverCodenames.isKnown("  "))
    }

    @Test
    fun catalogIsNotEmpty() {
        assertTrue(GisManeuverCodenames.CATALOG.size > 30)
    }

    @Test
    fun pdfCatalogDriveIconsAreKnown() {
        // Имена из private/2GIS Navigation Maneuver Images (2).pdf
        val pdf = listOf(
            "crossroad_keep_left", "crossroad_keep_right",
            "crossroad_left", "crossroad_right",
            "crossroad_sharply_left", "crossroad_sharply_right",
            "crossroad_slightly_left", "crossroad_slightly_right",
            "crossroad_straight", "crossroad_uturn",
            "left_ring_exit", "right_ring_exit",
            "ringroad_exit", "ringroad_forward", "ringroad_backward",
            "turn_over", "turn_over_left_hand", "turn_over_right_hand",
            "finish"
        )
        for (c in pdf) {
            assertTrue("unknown $c", GisManeuverCodenames.isKnown(c))
            assertTrue("none for $c", GisManeuverCodenames.toTurn(c) != GisManeuverCodenames.NONE)
        }
        assertEquals(
            GisManeuverCodenames.SLIGHT_RIGHT,
            GisManeuverCodenames.toTurn("crossroad_slightly_right")
        )
    }
}
