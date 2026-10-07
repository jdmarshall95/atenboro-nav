package com.atenboro.nav.parse

import com.atenboro.nav.model.NavUpdate
import org.json.JSONObject

/**
 * Разбор JSON из 2GIS Dashboard AIDL API
 * (`IDashboardInformationService.getDashboardInformationJSON()`).
 *
 * Ключи — из `ru/dublgis/api/DashboardInformationConstants.aidl`, группы в порядке
 * появления в API:
 *  Навигация  activeNavigationMode, maneuverIcon/Description/Distance, progress,
 *             remainingTime, arrivalTime, totalDistance, barrier*
 *  Камеры     speedLimit (м/с!), exceedingMaxSpeedLimit, badLocation,
 *             trafficCameraType/Subtype/DistancePercent
 *  Пробки     jamInfoDuration, jamInfoLengthMeters
 *  Светофор   trafficLightColor/Arrow/Countdown
 *
 * Пустой `activeNavigationMode` = навигация не активна, остальные поля невалидны.
 */
data class GisDashboardInfo(
    val navigationMode: String = "",
    val maneuverIcon: String = "",
    val maneuverDescription: String = "",
    val maneuverDistance: String = "",
    /** м/с, как отдаёт 2ГИС; ≤0 = лимита нет */
    val speedLimitMs: Double = 0.0,
    val exceedingSpeedLimit: Boolean = false,
    val badLocation: Boolean = false,
    /** "" = камеры рядом нет */
    val cameraType: String = "",
    val cameraSubtype: String = "",
    /** 0..100 по мере приближения к камере; вне диапазона — бессмысленно */
    val cameraDistancePercent: Double = -1.0,
    /** 0..100 процент прохождения маршрута */
    val progress: Int = -1,
    val jamDurationMin: Int = -1,
    val jamLengthM: Int = -1,
    val trafficLightColor: String = "",
    val trafficLightArrow: String = "",
    /** секунды до следующего сигнала; <0 = виджет прячем */
    val trafficLightCountdown: Int = -1,
    val remainingTime: String = "",
    val arrivalTime: String = "",
    val totalDistance: String = "",
    val apiVersion: Int = 0,
    val raw: String = ""
) {
    val navigationActive: Boolean
        get() = navigationMode.isNotBlank() && navigationMode != MODE_FREE_ROAM

    /** Свободная езда: манёвра нет, но дорога/лимиты есть. */
    val isFreeRoam: Boolean get() = navigationMode == MODE_FREE_ROAM

    /** Кононим иконки → токен прошивки. */
    val turn: String get() = GisManeuverCodenames.toTurn(maneuverIcon)

    /** «500 м» / «1,2 км» из человекочитаемой строки 2ГИС. */
    val maneuverDistM: Int
        get() = NavParser.extractDistancesMeters(maneuverDistance).firstOrNull() ?: -1

    /** speedLimit м/с → км/ч (округление). −1 если лимита нет. */
    val speedLimitKmh: Int
        get() = if (speedLimitMs > 0.0) Math.round(speedLimitMs * 3.6).toInt() else -1

    val cameraPresent: Boolean get() = cameraType.isNotBlank()

    /** 0..100 только когда тип камеры непустой и значение в диапазоне. */
    val cameraApproachPercent: Int
        get() {
            if (!cameraPresent) return -1
            val p = cameraDistancePercent
            return if (p in 0.0..100.0) p.toInt() else -1
        }

    /** Неизвестный кодоным = 2ГИС расширил каталог, надо дополнить таблицу. */
    val unknownIconCodename: Boolean
        get() = maneuverIcon.isNotBlank() && !GisManeuverCodenames.isKnown(maneuverIcon)

    /**
     * Собираем [NavUpdate] для общего пайплайна.
     * Камеру считаем «рядом» по факту непустого trafficCameraType — метр из API
     * не приходит, поэтому camM = −1, а camPct несёт прогресс приближения.
     */
    fun toNavUpdate(): NavUpdate {
        val cam = cameraPresent
        return NavUpdate(
            turn = turn,
            distM = maneuverDistM,
            camera = cam,
            camM = -1,
            camKmh = speedLimitKmh,
            camPct = cameraApproachPercent,
            navigating = navigationActive,
            navMode = navigationMode,
            progress = progress,
            trafficLightColor = trafficLightColor,
            trafficLightCountdown = trafficLightCountdown,
            jamMin = jamDurationMin,
            street = maneuverDescription.takeIf { it.isNotBlank() },
            rawSnippet = raw.take(240),
            allTexts = listOfNotNull(
                maneuverDescription.takeIf { it.isNotBlank() },
                maneuverDistance.takeIf { it.isNotBlank() }
            )
        )
    }

    companion object {
        const val MODE_FREE_ROAM = "freeroam"
        const val MODE_MOTORCYCLE = "motorcycle"

        fun parse(json: String?, apiVersion: Int = 0): GisDashboardInfo {
            val text = json?.takeIf { it.isNotBlank() } ?: return GisDashboardInfo(apiVersion = apiVersion)
            return try {
                val o = JSONObject(text)
                GisDashboardInfo(
                    navigationMode = o.optString("activeNavigationMode"),
                    maneuverIcon = o.optString("maneuverIcon"),
                    maneuverDescription = o.optString("maneuverDescription"),
                    maneuverDistance = o.optString("maneuverDistance"),
                    speedLimitMs = o.optDouble("speedLimit", 0.0),
                    exceedingSpeedLimit = o.optBoolean("exceedingMaxSpeedLimit", false),
                    badLocation = o.optBoolean("badLocation", false),
                    cameraType = o.optString("trafficCameraType"),
                    cameraSubtype = o.optString("trafficCameraSubtype"),
                    cameraDistancePercent = optDoubleOrNull(o, "trafficCameraDistancePercent") ?: -1.0,
                    progress = optIntOrNull(o, "progress") ?: -1,
                    jamDurationMin = optIntOrNull(o, "jamInfoDuration") ?: -1,
                    jamLengthM = optIntOrNull(o, "jamInfoLengthMeters") ?: -1,
                    trafficLightColor = o.optString("trafficLightColor"),
                    trafficLightArrow = o.optString("trafficLightArrow"),
                    trafficLightCountdown = optIntOrNull(o, "trafficLightCountdown") ?: -1,
                    remainingTime = o.optString("remainingTime"),
                    arrivalTime = o.optString("arrivalTime"),
                    totalDistance = o.optString("totalDistance"),
                    apiVersion = apiVersion,
                    raw = text
                )
            } catch (_: Exception) {
                GisDashboardInfo(apiVersion = apiVersion, raw = text)
            }
        }

        private fun optDoubleOrNull(o: JSONObject, key: String): Double? {
            if (o.isNull(key)) return null
            val v = o.optDouble(key, Double.NaN)
            return if (v.isNaN()) null else v
        }

        private fun optIntOrNull(o: JSONObject, key: String): Int? {
            if (o.isNull(key)) return null
            if (!o.has(key)) return null
            val v = o.optDouble(key, Double.NaN)
            return if (v.isNaN()) null else Math.round(v).toInt()
        }
    }
}
