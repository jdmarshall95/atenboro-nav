package com.atenboro.nav.parse

/**
 * Кодовые имена иконок манёвров 2ГИС → словарь прошивки.
 *
 * Источник списка — каталог кодовых имён иконок манёвров 2ГИС.
 * AIDL-поле `maneuverIcon` / `barrierIcon` возвращает ровно эти строки.
 *
 * Прошивка ([firmware/src/main.cpp] parseTurn) понимает только
 * left / right / slight_left / slight_right / u_turn / straight / roundabout / arrive.
 * Всё остальное сворачиваем в "none", но исходный кодоным сохраняем для отладки.
 *
 * ВАЖНО: в каталоге нет «базовых» left / right / straight —
 * список выглядит алфавитным подмножеством, поэтому маппинг нормализующий,
 * а не строгое равенство, и неизвестные имена не должны ронать HUD.
 */
object GisManeuverCodenames {

    const val NONE = "none"
    const val LEFT = "left"
    const val RIGHT = "right"
    const val SLIGHT_LEFT = "slight_left"
    const val SLIGHT_RIGHT = "slight_right"
    const val STRAIGHT = "straight"
    const val U_TURN = "u_turn"
    const val ROUNDABOUT = "roundabout"
    const val ARRIVE = "arrive"

    /** Полная таблица из PDF + очевидные базовые имена. */
    private val TABLE: Map<String, String> = buildMap {
        // --- базовые (в PDF не попали, но используются 2ГИС) ---
        put("left", LEFT)
        put("right", RIGHT)
        put("straight", STRAIGHT)
        put("slightly_left", SLIGHT_LEFT)
        put("slightly_right", SLIGHT_RIGHT)
        put("sharply_left", LEFT)
        put("sharply_right", RIGHT)
        put("uturn", U_TURN)
        put("u_turn", U_TURN)

        // --- перекрёстки (crossroad_*) ---
        put("crossroad_left", LEFT)
        put("crossroad_right", RIGHT)
        put("crossroad_straight", STRAIGHT)
        put("crossroad_slightly_left", SLIGHT_LEFT)
        put("crossroad_slightly_right", SLIGHT_RIGHT)
        put("crossroad_sharply_left", LEFT)
        put("crossroad_sharply_right", RIGHT)
        put("crossroad_keep_left", SLIGHT_LEFT)
        put("crossroad_keep_right", SLIGHT_RIGHT)
        put("crossroad_uturn", U_TURN)

        // --- кольцевое (ringroad*) ---
        put("ringroad_exit", ROUNDABOUT)
        put("left_ring_exit", ROUNDABOUT)
        put("right_ring_exit", ROUNDABOUT)
        put("ringroad_forward", ROUNDABOUT)
        put("ringroad_backward", ROUNDABOUT)
        put("ringroad_leftside_forward", ROUNDABOUT)
        put("ringroad_leftside_backward", ROUNDABOUT)
        for (deg in listOf(45, 90, 135, 180)) {
            put("ringroad_left_$deg", ROUNDABOUT)
            put("ringroad_right_$deg", ROUNDABOUT)
        }
        // левостороннее кольцо: leftside_{left,right}_{45..360}
        for (side in listOf("left", "right")) {
            for (deg in listOf(45, 90, 135, 180, 225, 270, 315, 360)) {
                put("ringroad_leftside_${side}_$deg", ROUNDABOUT)
            }
        }

        // --- развороты ---
        put("turn_over", U_TURN)
        put("turn_over_left_hand", U_TURN)
        put("turn_over_right_hand", U_TURN)

        // --- служебные / не-стрелки ---
        put("finish", ARRIVE)
        put("start", NONE)
        put("startA", NONE)
        put("barrier", NONE)
        put("gate", NONE)
        put("stairs", NONE)
        put("stairs_up", NONE)
        put("stairs_down", NONE)
        put("bad_road", NONE)
        put("car_road", NONE)
        put("dirt_road", NONE)
        put("toll_road", NONE)
        put("from", NONE)
        put("to", NONE)
        put("toRus", NONE)
    }

    /** Все кодоимена каталога 2ГИС (для тестов и отладочного дампа). */
    val CATALOG: Set<String> = TABLE.keys.toSet()

    /**
     * @return один из токенов прошивки; "none" если стрелку нарисовать нечем.
     */
    fun toTurn(codename: String?): String {
        val key = normalize(codename) ?: return NONE
        TABLE[key]?.let { return it }
        return infer(key)
    }

    /** Есть ли кодоимя в известном каталоге (нет = 2ГИС добавил новый, надо обновить таблицу). */
    fun isKnown(codename: String?): Boolean {
        val key = normalize(codename) ?: return true // пустое = «нет манёвра», это нормально
        return TABLE.containsKey(key)
    }

    fun normalize(codename: String?): String? {
        val raw = codename?.trim()?.lowercase()?.replace('-', '_')?.replace(' ', '_')
        return if (raw.isNullOrEmpty()) null else raw
    }

    /**
     * Запасная эвристика для незнакомых имён (2ГИС расширяет каталог без обновления API).
     * Порядок проверок важен: «keep_left»/«slightly_left» раньше общего «left».
     */
    private fun infer(key: String): String = when {
        key.contains("ring") || key.contains("round") -> ROUNDABOUT
        key.contains("uturn") || key.contains("u_turn") || key.contains("over") -> U_TURN
        key.contains("finish") || key.contains("arrive") || key == "end" -> ARRIVE
        key.contains("slight") || key.contains("keep") ->
            if (key.contains("left")) SLIGHT_LEFT else if (key.contains("right")) SLIGHT_RIGHT else NONE
        key.contains("left") -> LEFT
        key.contains("right") -> RIGHT
        key.contains("straight") || key.contains("forward") -> STRAIGHT
        else -> NONE
    }
}
