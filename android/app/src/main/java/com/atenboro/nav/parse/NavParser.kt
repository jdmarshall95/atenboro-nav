package com.atenboro.nav.parse

import com.atenboro.nav.model.NavUpdate
import java.util.Locale
import java.util.regex.Pattern

/**
 * Парсер подсказок 2ГИС из accessibility / notification RemoteViews.
 * Отфильтровывает chrome UI и длину всего маршрута ("17 км • 25 мин").
 */
object NavParser {

    private val distPattern = Pattern.compile(
        "(?<![\\d.,])(\\d{1,4}(?:[.,]\\d)?)\\s*(км|м|km|m)(?![a-zA-Zа-яА-Я0-9])",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    )

    private val timePattern = Pattern.compile(
        "\\b\\d+\\s*(минут|минуты|мин|часов|часа|час|ч\\.|мин\\.|min)\\b",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    )

    private val cameraPattern = Pattern.compile(
        "камер|camera|radar|радар|speed\\s*cam|speedcam|" +
            "контроль\\s*скорост|стационарн|перед\\s+вами\\s+камер|впереди\\s+камер",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    )

    private val speedKmhPattern = Pattern.compile(
        "(?<![\\d])(\\d{2,3})\\s*(?:км/ч|кмч|km/h|kmh)",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    )

    private val turnHint = Pattern.compile(
        "налево|направо|влево|вправо|прямо|разворот|разверн|кольц|кругов|левее|правее|" +
            "slight|keep\\s*left|keep\\s*right|left|right|straight|u-?turn|turn|" +
            "поверните|съезд|держитесь|плавно",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    )

    fun parse(texts: List<String>): NavUpdate {
        val cleanTexts = NavTextFilter.sanitize(texts)
        val pocket = NavTextFilter.parsePocketBanner(texts + cleanTexts)

        val joined = cleanTexts.joinToString("\n")
        val lowerAll = joined.lowercase(Locale("ru"))

        // Строки маршрута целиком: "17 км • 25 мин"
        val routeSummaryLines = cleanTexts.filter { line ->
            val l = line.lowercase(Locale("ru"))
            timePattern.matcher(l).find() && distPattern.matcher(l).find()
        }

        val maneuverLines = cleanTexts.filter { line ->
            val l = line.lowercase(Locale("ru"))
            !routeSummaryLines.contains(line) && (
                turnHint.matcher(l).find() ||
                    extractDistancesMeters(line).any { it in 0..50_000 }
                )
        }

        val turn = detectTurnPriority(maneuverLines.ifEmpty { cleanTexts })

        val turnBearingLines = maneuverLines.filter { turnHint.matcher(it.lowercase(Locale("ru"))).find() }
        val turnLineDistances = extractDistancesMeters(turnBearingLines.joinToString("\n"))
            .filter { it <= 200_000 }
        val maneuverDistances = extractDistancesMeters(maneuverLines.joinToString("\n"))
            .filter { it <= 200_000 }
        val fallbackDistances = extractDistancesMeters(
            cleanTexts.filterNot { routeSummaryLines.contains(it) }.joinToString("\n")
        ).filter { it <= 200_000 }

        // Баннер кармана — самый надёжный источник дистанции манёвра
        val distM = when {
            pocket != null -> pocket.distM
            turnLineDistances.isNotEmpty() -> turnLineDistances.minOrNull()!!
            maneuverDistances.isNotEmpty() -> maneuverDistances.minOrNull()!!
            turn != "none" && fallbackDistances.isNotEmpty() -> fallbackDistances.minOrNull()!!
            else -> -1
        }

        val camera = cameraPattern.matcher(lowerAll).find()
        val camM = if (camera) {
            val camLine = cleanTexts.firstOrNull {
                cameraPattern.matcher(it.lowercase(Locale("ru"))).find()
            }
            if (camLine != null) extractDistancesMeters(camLine).firstOrNull() ?: -1
            else -1
        } else {
            -1
        }
        val camKmh = if (camera) extractCamSpeedKmh(cleanTexts) else -1

        return NavUpdate(
            turn = turn,
            distM = distM,
            camera = camera,
            camM = if (camera) camM else -1,
            camKmh = camKmh,
            street = pocket?.street,
            rawSnippet = joined.take(400),
            allDistances = extractDistancesMeters(joined),
            allTexts = cleanTexts
        )
    }

    private fun extractCamSpeedKmh(texts: List<String>): Int {
        for (line in texts) {
            val m = speedKmhPattern.matcher(line)
            if (m.find()) {
                val v = m.group(1)?.toIntOrNull() ?: continue
                if (v in 5..150) return v
            }
        }
        for (line in texts) {
            val l = line.lowercase(Locale("ru"))
            if (!cameraPattern.matcher(l).find()) continue
            val bare = Pattern.compile("(?<![\\d])(\\d{2,3})(?![\\d.,])").matcher(line)
            while (bare.find()) {
                val v = bare.group(1)?.toIntOrNull() ?: continue
                if (v in 20..130) return v
            }
        }
        return -1
    }

    private fun detectTurnPriority(texts: List<String>): String {
        val lowerLines = texts.map { it.lowercase(Locale("ru")) }

        for (line in lowerLines) {
            when {
                line.contains("разворот") || line.contains("u-turn") || line.contains("u turn") ||
                    line.contains("разверн") || line.contains("развернитесь") ->
                    return "u_turn"
                line.contains("кольцев") || line.contains("кругов") || line.contains("roundabout") ->
                    return "roundabout"
                line.contains("прибыл") || line.contains("назначен") || line.contains("финиш") ||
                    line.contains("прибытие") || line.contains("arrive") ->
                    return "arrive"
                // slight / keep — ДО hard left/right (иначе «левее» ловится как «лев»)
                line.contains("чуть лев") || line.contains("плавно лев") || line.contains("slight left") ||
                    line.contains("slightly left") || line.contains("keep left") ||
                    line.contains("левее") || line.contains("держитесь левее") ||
                    line.contains("держитесь лев") ->
                    return "slight_left"
                line.contains("чуть прав") || line.contains("плавно прав") || line.contains("slight right") ||
                    line.contains("slightly right") || line.contains("keep right") ||
                    line.contains("правее") || line.contains("держитесь правее") ||
                    line.contains("держитесь прав") ->
                    return "slight_right"
                line.contains("налево") || line.contains("влево") ||
                    line.contains("поверните налево") || line.contains("turn left") ->
                    return "left"
                line.contains("направо") || line.contains("вправо") ||
                    line.contains("поверните направо") || line.contains("turn right") ->
                    return "right"
            }
        }

        for (line in lowerLines) {
            if (line.contains("прямо") || line.contains("продолж") || line.contains("straight") ||
                line.contains("следуйте")
            ) {
                return "straight"
            }
        }

        return "none"
    }

    internal fun extractDistancesMeters(text: String): List<Int> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<Int>()
        val matcher = distPattern.matcher(text)
        while (matcher.find()) {
            val raw = matcher.group(1)?.replace(',', '.') ?: continue
            val unit = matcher.group(2)?.lowercase(Locale.ROOT) ?: continue
            val value = raw.toDoubleOrNull() ?: continue
            val meters = when {
                unit.startsWith("к") || unit == "km" -> (value * 1000).toInt()
                else -> value.toInt()
            }
            if (meters in 0..200_000) out += meters
        }
        return out
    }
}
