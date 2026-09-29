package com.atenboro.nav.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavParserTest {

    @Test
    fun parsesLeftTurnAndDistance() {
        val update = NavParser.parse(
            listOf("Через 250 м", "Поверните налево на Ленина")
        )
        assertEquals("left", update.turn)
        assertEquals(250, update.distM)
        assertFalse(update.camera)
    }

    @Test
    fun parsesKilometers() {
        val update = NavParser.parse(listOf("через 1,2 км прямо"))
        assertEquals("straight", update.turn)
        assertEquals(1200, update.distM)
    }

    @Test
    fun parsesCamera() {
        val update = NavParser.parse(
            listOf("Камера через 120 м", "через 400 м направо")
        )
        assertTrue(update.camera)
        assertEquals("right", update.turn)
    }

    @Test
    fun parsesUTurn() {
        val update = NavParser.parse(listOf("Через 80 м разворот"))
        assertEquals("u_turn", update.turn)
        assertEquals(80, update.distM)
    }

    @Test
    fun filtersOutTotalRouteDistanceWithTripTime() {
        // Real-world 2GIS case: Screen contains total route info "17 км • 25 мин"
        // and maneuver info "400 м" "направо на ул. Чехова"
        val update = NavParser.parse(
            listOf(
                "400 м",
                "Направо на ул. Чехова",
                "17 км • 25 мин",
                "Осталось 17 км"
            )
        )
        assertEquals("right", update.turn)
        assertEquals(400, update.distM)
        assertEquals(listOf(400, 17000, 17000), update.allDistances)
    }
}
