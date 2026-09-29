package com.atenboro.nav.parse

import java.util.Locale
import java.util.regex.Pattern

/**
 * Парсит строки логов 2ГИС (logcat tag 2GIS / файл logs).
 *
 * DomainSynthesizerPlayer: going to play ... of 'CrossroadsTurnDirectionRight...Over400'
 * CoreTransportRouting: Updating remaining length ... to 13270 meters
 */
object GisLogParser {

    data class ManeuverHint(
        val turn: String,
        val distM: Int,
        val rawClip: String,
        val routeRemainM: Int = -1
    )

    private val clipPattern = Pattern.compile(
        "going to play\\s+\\d+ms\\s+of\\s+'([^']+)'",
        Pattern.CASE_INSENSITIVE
    )

    private val remainPattern = Pattern.compile(
        "Updating remaining length[^\\d]*to\\s+(\\d+)\\s+meters",
        Pattern.CASE_INSENSITIVE
    )

    private val passedPattern = Pattern.compile(
        "passed distance\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)",
        Pattern.CASE_INSENSITIVE
    )

    private val distInClip = Pattern.compile(
        "(?:over|after|in|under)(\\d{2,4})",
        Pattern.CASE_INSENSITIVE
    )

    fun parseLine(line: String): ManeuverHint? {
        val clipM = clipPattern.matcher(line)
        if (clipM.find()) {
            val clip = clipM.group(1) ?: return null
            val (turn, dist) = decodeClip(clip)
            return ManeuverHint(turn = turn, distM = dist, rawClip = clip)
        }
        return null
    }

    fun parseRouteRemain(line: String): Int? {
        val m = remainPattern.matcher(line)
        if (!m.find()) return null
        return m.group(1)?.toIntOrNull()
    }

    fun parsePassedDistance(line: String): Double? {
        val m = passedPattern.matcher(line)
        if (!m.find()) return null
        return m.group(1)?.toDoubleOrNull()
    }

    /** Clip = TurnDirection* + Over/After distance. */
    fun decodeClip(clip: String): Pair<String, Int> {
        val c = clip.lowercase(Locale.US)
        val turn = when {
            "uturn" in c || "turnover" in c || "turnaround" in c -> "u_turn"
            "roundabout" in c -> "roundabout"
            "arrive" in c || "finish" in c -> "arrive"
            "sharplyleft" in c || "sharply_left" in c -> "left"
            "sharplyright" in c || "sharply_right" in c -> "right"
            "slightlyleft" in c || "slightly_left" in c || "turndirectionslightleft" in c ->
                "slight_left"
            "slightlyright" in c || "slightly_right" in c || "turndirectionslightright" in c ->
                "slight_right"
            "turndirectionleft" in c || "turnleft" in c ||
                ("left" in c && "right" !in c && "slight" !in c) -> "left"
            "turndirectionright" in c || "turnright" in c ||
                ("right" in c && "slight" !in c) -> "right"
            "keepleft" in c -> "slight_left"
            "keepright" in c -> "slight_right"
            "straight" in c || "forward" in c -> "straight"
            else -> "none"
        }

        val distM = distInClip.matcher(clip).let { m ->
            if (m.find()) m.group(1)?.toIntOrNull() ?: -1 else -1
        }
        return turn to distM
    }
}
