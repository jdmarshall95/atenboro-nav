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
    fun parsesPocketBannerDistanceAndStreet() {
        val update = NavParser.parse(
            listOf("400 m — Большой Строченовский переулок", "Update", "Step by Step", "32 min")
        )
        assertEquals(400, update.distM)
        assertEquals("Большой Строченовский переулок", update.street)
        assertEquals("none", update.turn) // turn только из иконки/слов
        assertFalse(update.allTexts.any { it.equals("Update", ignoreCase = true) })
    }

    @Test
    fun dropsJunkChromeWithoutInventingStraight() {
        val update = NavParser.parse(
            listOf("Update", "Step by Step", "Duration: 32 min", "Search")
        )
        assertEquals("none", update.turn)
        assertEquals(-1, update.distM)
        assertTrue(update.allTexts.isEmpty())
    }

    @Test
    fun filtersOutTotalRouteDistanceWithTripTime() {
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
        // «Осталось 17 км» — junk; «17 км • 25 мин» остаётся в allDistances
        assertEquals(listOf(400, 17000), update.allDistances)
    }

    @Test
    fun prefersPocketLongDistanceOverNoiseMeters() {
        val update = NavParser.parse(
            listOf(
                "12 km — Каширское шоссе",
                "Update",
                "32 min",
                "Parking"
            )
        )
        assertEquals(12000, update.distM)
        assertEquals("Каширское шоссе", update.street)
    }

    @Test
    fun parsesSlightRightAndCameraSpeed() {
        val update = NavParser.parse(
            listOf("Через 180 м держитесь правее", "Камера 60 км/ч через 90 м")
        )
        assertEquals("slight_right", update.turn)
        assertEquals(180, update.distM)
        assertTrue(update.camera)
        assertEquals(60, update.camKmh)
    }

    @Test
    fun parsesKeepLeftAsSlight() {
        val update = NavParser.parse(listOf("In 200 m keep left"))
        assertEquals("slight_left", update.turn)
        assertEquals(200, update.distM)
    }

    @Test
    fun parsesRazvernitesAsUTurn() {
        val update = NavParser.parse(listOf("Через 60 м развернитесь"))
        assertEquals("u_turn", update.turn)
        assertEquals(60, update.distM)
    }

    @Test
    fun parsesPlavnoLeftBeforeHardLeft() {
        val update = NavParser.parse(listOf("Через 120 м плавно левее"))
        assertEquals("slight_left", update.turn)
    }

    @Test
    fun parsesPlavnoPoverniteNapravoAsSlightRight() {
        // Поле: «плавно поверните направо» — не подряд «плавно прав», раньше схлопывалось в hard right
        val update = NavParser.parse(listOf("Через 1,1 км плавно поверните направо"))
        assertEquals("slight_right", update.turn)
        assertEquals(1100, update.distM)
    }
}
