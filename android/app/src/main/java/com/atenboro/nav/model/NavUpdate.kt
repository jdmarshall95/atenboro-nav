package com.atenboro.nav.model

import com.atenboro.nav.parse.AsciiLatin

data class NavUpdate(
    val turn: String = "none",
    /** Сырое кодоимя иконки 2ГИС (PDF-каталог), напр. crossroad_slightly_right */
    val maneuverIcon: String = "",
    val distM: Int = -1,
    val camera: Boolean = false,
    val camM: Int = -1,
    /** Лимит скорости камеры, км/ч (−1 = нет) */
    val camKmh: Int = -1,
    /** 2GIS AIDL: 0..100 приближения к камере (−1 = нет данных) */
    val camPct: Int = -1,
    val ts: Long = System.currentTimeMillis() / 1000,
    val rawSnippet: String = "",
    val allDistances: List<Int> = emptyList(),
    val allTexts: List<String> = emptyList(),
    /** 32×32 mono icon as hex (128 bytes) — legacy; OLED рисует PROGMEM по maneuverIcon */
    val iconHex: String? = null,
    val street: String? = null,
    val navigating: Boolean = false,
    /** 2GIS AIDL: activeNavigationMode ("" = не активна, "motorcycle" и т.д.) */
    val navMode: String = "",
    /** 2GIS AIDL: 0..100 прохождения маршрута */
    val progress: Int = -1,
    /** 2GIS AIDL: red / green / yellow ("") */
    val trafficLightColor: String = "",
    /** 2GIS AIDL: секунды до сигнала (−1 = прятать) */
    val trafficLightCountdown: Int = -1,
    /** 2GIS AIDL: минуты пробки (−1 = нет) */
    val jamMin: Int = -1,
    val httpStatus: String = "Ожидание",
    val lastError: String? = null
) {
    fun toJson(): String {
        val cam = if (camera) "true" else "false"
        val sb = StringBuilder(220 + (iconHex?.length ?: 0) + maneuverIcon.length)
        sb.append("""{"turn":"$turn","dist_m":$distM,"camera":$cam,"cam_m":$camM,"cam_kmh":$camKmh,"ts":$ts""")
        if (maneuverIcon.isNotBlank()) {
            val safe = maneuverIcon.replace("\\", "\\\\").replace("\"", "\\\"")
            sb.append(""","maneuver_icon":"$safe"""")
        }
        if (camPct in 0..100) sb.append(""","cam_pct":$camPct""")
        if (navMode.isNotBlank()) sb.append(""","nav_mode":"$navMode"""")
        if (progress in 0..100) sb.append(""","progress":$progress""")
        if (trafficLightColor.isNotBlank()) sb.append(""","tl":"$trafficLightColor"""")
        if (trafficLightCountdown >= 0) sb.append(""","tl_s":$trafficLightCountdown""")
        if (jamMin > 0) sb.append(""","jam_min":$jamMin""")
        if (!iconHex.isNullOrEmpty()) {
            sb.append(""","icon_w":32,"icon_h":32,"icon":"$iconHex"""")
        }
        if (!street.isNullOrBlank()) {
            val safe = AsciiLatin.transliterate(street)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
            if (safe.isNotEmpty()) {
                sb.append(""","street":"$safe"""")
            }
        }
        sb.append('}')
        return sb.toString()
    }

    fun previewText(): String {
        val dist = if (distM >= 0) "$distM м" else "—"
        val cam = when {
            camera && camKmh > 0 && camPct in 0..100 -> "да (${camKmh} км/ч, $camPct%)"
            camera && camKmh > 0 -> "да (${camKmh} км/ч)"
            camera && camM >= 0 -> "да ($camM м)"
            camera && camPct in 0..100 -> "да ($camPct%)"
            camera -> "да"
            else -> "нет"
        }
        val turnRu = when (turn) {
            "right" -> "направо"
            "left" -> "налево"
            "slight_right" -> "плавно вправо"
            "slight_left" -> "плавно влево"
            "straight" -> "прямо"
            "u_turn" -> "разворот"
            "roundabout" -> "круговое движение"
            "arrive" -> "прибытие"
            else -> "нет"
        }
        val extra = StringBuilder()
        if (maneuverIcon.isNotBlank()) extra.append("Иконка 2ГИС: ").append(maneuverIcon).append("\n")
        if (navMode.isNotBlank()) extra.append("Режим: ").append(navMode).append("\n")
        if (progress in 0..100) extra.append("Прогресс маршрута: ").append(progress).append("%\n")
        if (trafficLightColor.isNotBlank()) {
            extra.append("Светофор: ").append(trafficLightColor)
            if (trafficLightCountdown >= 0) extra.append(" (").append(trafficLightCountdown).append(" с")
            extra.append(")\n")
        }
        return "Поворот: $turnRu ($turn)\nДистанция: $dist\nКамера: $cam\n" +
            extra.toString() + "HTTP статус: $httpStatus"
    }

    fun debugDetailsText(): String {
        val sb = StringBuilder()
        sb.append("=== DUMP & PARSER DEBUG ===\n")
        sb.append("Время: ").append(ts).append("\n")
        sb.append("Определён поворот: ").append(turn).append("\n")
        if (maneuverIcon.isNotBlank()) {
            sb.append("Кодоимя иконки 2ГИС: ").append(maneuverIcon).append("\n")
        }
        sb.append("Дистанция маневра: ").append(if (distM >= 0) "$distM м" else "не найдена").append("\n")
        sb.append("Камера: ").append(camera)
            .append(" (dist: ").append(camM).append(" м, speed: ").append(camKmh).append(" км/ч")
            .append(", pct: ").append(camPct).append(")\n")
        if (navMode.isNotBlank()) {
            sb.append("AIDL: mode=").append(navMode)
                .append(", progress=").append(progress)
                .append(", tl=").append(trafficLightColor.ifBlank { "—" })
                .append(", tl_s=").append(trafficLightCountdown)
                .append(", jam_min=").append(jamMin).append("\n")
        }
        sb.append("Все найденные дистанции: ").append(if (allDistances.isEmpty()) "нет" else allDistances.joinToString { "$it м" }).append("\n")
        if (lastError != null) {
            sb.append("Ошибка ESP: ").append(lastError).append("\n")
        }
        sb.append("\n--- Извлечённый текст из 2ГИС (").append(allTexts.size).append(" элементов) ---\n")
        if (allTexts.isEmpty()) {
            sb.append("(нет текста)\n")
        } else {
            allTexts.forEachIndexed { i, txt ->
                sb.append("[").append(i + 1).append("] ").append(txt).append("\n")
            }
        }
        return sb.toString()
    }
}
