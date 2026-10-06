package com.atenboro.nav.parse

import java.util.Locale

/**
 * Отсекает chrome 2ГИС / system UI, который ломает парсер манёвра.
 */
object NavTextFilter {

    private val junkExact = setOf(
        "update", "search", "close", "my location", "step by step", "route overview",
        "overview", "complete", "done", "ok", "cancel", "more", "share", "save",
        "parking area", "surface", "quick actions", "collapse", "expand",
        "open", "2gis", "2гис", "дгс", "complete the route", "complete route"
    )

    private val junkContains = listOf(
        "duration:", "collapse route", "route from", "route search",
        "parking area", "step by step", "quick actions", "my location",
        "осталось ехать", "время в пути", "arrival", "eta:",
        "complete the route", "complete route"
    )

    /** Длина всего маршрута без манёвра: «Осталось 17 км» */
    private val totalRemainOnly = Regex(
        """^осталось\s+\d{1,3}(?:[.,]\d)?\s*(км|km)\.?$""",
        RegexOption.IGNORE_CASE
    )

    /** Баннер кармана: «400 m — …» / «3,8 km — …» / «12 km — …» */
    private val pocketBanner = Regex(
        """^(\d{1,4}(?:[.,]\d)?)\s*(м|m|км|km)\s*[—\-–]\s*(.+)$""",
        RegexOption.IGNORE_CASE
    )

    fun sanitize(texts: List<String>): List<String> =
        texts
            .asSequence()
            .flatMap { it.split('\n') }
            .map { it.replace('\u00A0', ' ').trim() }
            .filter { it.isNotEmpty() }
            .filterNot { isJunk(it) }
            .distinct()
            .toList()

    fun isJunk(line: String): Boolean {
        val t = line.trim()
        if (t.length < 2) return true
        val l = t.lowercase(Locale.ROOT)
        if (l in junkExact) return true
        if (junkContains.any { l.contains(it) }) return true
        if (totalRemainOnly.matches(l)) return true
        // Чистое ETA без дистанции манёвра: «32 min», «29 мин»
        if (Regex("""^\d{1,3}\s*(min|мин|минут|минуты)\.?$""", RegexOption.IGNORE_CASE).matches(t)) {
            return true
        }
        return false
    }

    data class PocketBanner(val distM: Int, val street: String)

    fun parsePocketBanner(texts: List<String>): PocketBanner? {
        for (raw in texts.flatMap { it.split('\n') }.map { it.trim() }) {
            val m = pocketBanner.matchEntire(raw) ?: continue
            val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: continue
            val unit = m.groupValues[2].lowercase(Locale.ROOT)
            val meters = when {
                unit.startsWith("к") || unit == "km" -> (value * 1000).toInt()
                else -> value.toInt()
            }
            // До ~200 км до манёвра (дальняк); раньше >8 км отбрасывались → на OLED «20–30 м»
            if (meters !in 0..200_000) continue
            val street = m.groupValues[3].trim().take(60)
            if (street.length < 2) continue
            return PocketBanner(meters, street)
        }
        return null
    }
}
