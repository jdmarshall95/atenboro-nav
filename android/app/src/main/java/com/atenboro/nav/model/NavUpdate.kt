package com.atenboro.nav.model

data class NavUpdate(
    val turn: String = "none",
    val distM: Int = -1,
    val camera: Boolean = false,
    val camM: Int = -1,
    val ts: Long = System.currentTimeMillis() / 1000,
    val rawSnippet: String = "",
    val allDistances: List<Int> = emptyList(),
    val allTexts: List<String> = emptyList(),
    val httpStatus: String = "Ожидание",
    val lastError: String? = null
) {
    fun toJson(): String {
        val cam = if (camera) "true" else "false"
        return """{"turn":"$turn","dist_m":$distM,"camera":$cam,"cam_m":$camM,"ts":$ts}"""
    }

    fun previewText(): String {
        val dist = if (distM >= 0) {
            if (distM >= 1000) String.format("%.1f км", distM / 1000.0) else "$distM м"
        } else {
            "—"
        }
        val cam = when {
            camera && camM >= 0 -> "да ($camM м)"
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
        return "Поворот: $turnRu ($turn)\nДистанция: $dist\nКамера: $cam\nHTTP статус: $httpStatus"
    }

    fun debugDetailsText(): String {
        val sb = StringBuilder()
        sb.append("=== DUMP & PARSER DEBUG ===\n")
        sb.append("Время: ").append(ts).append("\n")
        sb.append("Определён поворот: ").append(turn).append("\n")
        sb.append("Дистанция маневра: ").append(if (distM >= 0) "$distM м" else "не найдена").append("\n")
        sb.append("Камера: ").append(camera).append(" (dist: ").append(camM).append(" м)\n")
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
