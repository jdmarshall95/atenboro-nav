package com.atenboro.nav.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Тесты разбора JSON из 2GIS Dashboard AIDL API.
 * Robolectric нужен ради org.json (в чистом JVM-юните он заглушен Android SDK).
 */
@RunWith(RobolectricTestRunner::class)
class GisDashboardInfoTest {

    @Test
    fun emptyModeMeansNavigationInactive() {
        val info = GisDashboardInfo.parse("""{"activeNavigationMode":""}""")
        assertFalse(info.navigationActive)
        assertFalse(info.toNavUpdate().navigating)
        assertEquals("none", info.turn)
    }

    @Test
    fun nullOrBlankJsonIsSafe() {
        assertFalse(GisDashboardInfo.parse(null).navigationActive)
        assertFalse(GisDashboardInfo.parse("   ").navigationActive)
        // Мусор вместо JSON не должен бросать
        val broken = GisDashboardInfo.parse("not json at all")
        assertFalse(broken.navigationActive)
        assertEquals("not json at all", broken.raw)
    }

    @Test
    fun motorcycleNavigationParsesManeuver() {
        val json = """
            {
              "activeNavigationMode":"motorcycle",
              "maneuverIcon":"crossroad_right",
              "maneuverDescription":"улица Рубежная",
              "maneuverDistance":"500 м",
              "progress":42
            }
        """.trimIndent()
        val info = GisDashboardInfo.parse(json, apiVersion = 25)
        assertTrue(info.navigationActive)
        assertEquals(GisManeuverCodenames.RIGHT, info.turn)
        assertEquals(500, info.maneuverDistM)
        assertEquals(42, info.progress)
        assertEquals(25, info.apiVersion)

        val u = info.toNavUpdate()
        assertEquals("right", u.turn)
        assertEquals("crossroad_right", u.maneuverIcon)
        assertEquals(500, u.distM)
        assertTrue(u.navigating)
        assertEquals("motorcycle", u.navMode)
        assertEquals(42, u.progress)
        assertEquals("улица Рубежная", u.street)
    }

    @Test
    fun slightRightCodenameMapsAndPassesThrough() {
        val info = GisDashboardInfo.parse(
            """
              {
                "activeNavigationMode":"motorcycle",
                "maneuverIcon":"crossroad_slightly_right",
                "maneuverDistance":"1,1 км"
              }
            """.trimIndent()
        )
        assertEquals(GisManeuverCodenames.SLIGHT_RIGHT, info.turn)
        val u = info.toNavUpdate()
        assertEquals("slight_right", u.turn)
        assertEquals("crossroad_slightly_right", u.maneuverIcon)
        assertEquals(1100, u.distM)
        assertTrue(u.toJson().contains(""""maneuver_icon":"crossroad_slightly_right""""))
    }

    @Test
    fun kilometersInManeuverDistance() {
        val info = GisDashboardInfo.parse(
            """{"activeNavigationMode":"car","maneuverDistance":"1,2 км"}"""
        )
        assertEquals(1200, info.maneuverDistM)
    }

    @Test
    fun speedLimitIsMetersPerSecond() {
        // 2ГИС отдаёт speedLimit в м/с: 16.6667 ≈ 60 км/ч
        val info = GisDashboardInfo.parse(
            """{"activeNavigationMode":"car","speedLimit":16.6667,"exceedingMaxSpeedLimit":true}"""
        )
        assertEquals(60, info.speedLimitKmh)
        assertTrue(info.exceedingSpeedLimit)
    }

    @Test
    fun noSpeedLimitMeansMinusOne() {
        assertEquals(-1, GisDashboardInfo.parse("""{"speedLimit":0}""").speedLimitKmh)
        assertEquals(-1, GisDashboardInfo.parse("""{}""").speedLimitKmh)
    }

    @Test
    fun cameraPercentOnlyWhenTypePresent() {
        val withCam = GisDashboardInfo.parse(
            """
                {
                  "activeNavigationMode":"car",
                  "trafficCameraType":"speed",
                  "trafficCameraSubtype":"front",
                  "trafficCameraDistancePercent":37.8,
                  "speedLimit":22.2222
                }
            """.trimIndent()
        )
        assertTrue(withCam.cameraPresent)
        assertEquals(37, withCam.cameraApproachPercent)
        val u = withCam.toNavUpdate()
        assertTrue(u.camera)
        assertEquals(37, u.camPct)
        assertEquals(-1, u.camM)
        assertEquals(80, u.camKmh)

        // Процент есть, типа камеры нет — значение бессмысленно
        val noType = GisDashboardInfo.parse(
            """{"activeNavigationMode":"car","trafficCameraDistancePercent":50}"""
        )
        assertFalse(noType.cameraPresent)
        assertEquals(-1, noType.cameraApproachPercent)
        assertEquals(-1, noType.toNavUpdate().camPct)
    }

    @Test
    fun cameraPercentOutOfRangeIsIgnored() {
        val info = GisDashboardInfo.parse(
            """{"trafficCameraType":"speed","trafficCameraDistancePercent":null}"""
        )
        assertEquals(-1, info.cameraApproachPercent)
    }

    @Test
    fun freeRoamIsNotNavigation() {
        val info = GisDashboardInfo.parse("""{"activeNavigationMode":"freeroam"}""")
        assertTrue(info.isFreeRoam)
        assertFalse(info.navigationActive)
    }

    @Test
    fun trafficLightFields() {
        val info = GisDashboardInfo.parse(
            """
                {
                  "activeNavigationMode":"car",
                  "trafficLightColor":"red",
                  "trafficLightArrow":"up",
                  "trafficLightCountdown":7
                }
            """.trimIndent()
        )
        assertEquals("red", info.trafficLightColor)
        assertEquals("up", info.trafficLightArrow)
        assertEquals(7, info.trafficLightCountdown)
        val u = info.toNavUpdate()
        assertEquals("red", u.trafficLightColor)
        assertEquals(7, u.trafficLightCountdown)
    }

    @Test
    fun jamFields() {
        val info = GisDashboardInfo.parse(
            """{"activeNavigationMode":"car","jamInfoDuration":12,"jamInfoLengthMeters":3400}"""
        )
        assertEquals(12, info.jamDurationMin)
        assertEquals(3400, info.jamLengthM)
        assertEquals(12, info.toNavUpdate().jamMin)
    }

    @Test
    fun unknownCodenameIsFlaggedForLogging() {
        val known = GisDashboardInfo.parse(
            """{"activeNavigationMode":"car","maneuverIcon":"ringroad_exit"}"""
        )
        assertFalse(known.unknownIconCodename)

        val unknown = GisDashboardInfo.parse(
            """{"activeNavigationMode":"car","maneuverIcon":"brand_new_icon_2027"}"""
        )
        assertTrue(unknown.unknownIconCodename)

        val noIcon = GisDashboardInfo.parse("""{"activeNavigationMode":"car"}""")
        assertFalse(noIcon.unknownIconCodename)
    }

    @Test
    fun jsonCarriesNewFieldsToEspPayload() {
        val info = GisDashboardInfo.parse(
            """
                {
                  "activeNavigationMode":"car",
                  "maneuverIcon":"crossroad_left",
                  "maneuverDistance":"120 м",
                  "trafficCameraType":"speed",
                  "trafficCameraDistancePercent":64,
                  "speedLimit":11.1111,
                  "progress":80,
                  "trafficLightColor":"green",
                  "trafficLightCountdown":3,
                  "jamInfoDuration":5
                }
            """.trimIndent()
        )
        val json = info.toNavUpdate().toJson()
        assertTrue(json, json.contains("\"turn\":\"left\""))
        assertTrue(json, json.contains("\"dist_m\":120"))
        assertTrue(json, json.contains("\"cam_pct\":64"))
        assertTrue(json, json.contains("\"nav_mode\":\"car\""))
        assertTrue(json, json.contains("\"progress\":80"))
        assertTrue(json, json.contains("\"tl\":\"green\""))
        assertTrue(json, json.contains("\"tl_s\":3"))
        assertTrue(json, json.contains("\"jam_min\":5"))
    }

    @Test
    fun absentFieldsAreOmittedFromPayload() {
        val json = GisDashboardInfo.parse("""{"activeNavigationMode":"car"}""")
            .toNavUpdate().toJson()
        assertFalse(json, json.contains("cam_pct"))
        assertFalse(json, json.contains("progress"))
        assertFalse(json, json.contains("\"tl\""))
        assertFalse(json, json.contains("jam_min"))
    }
}
