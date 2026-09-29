package com.atenboro.nav.parse

/**
 * Штатный шрифт Adafruit GFX на SSD1306 — только ASCII.
 * Кириллица на OLED даёт квадраты/мусор → латиница до отправки.
 */
object AsciiLatin {

    private val map = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e",
        'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
        'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
        'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch",
        'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
        'А' to "A", 'Б' to "B", 'В' to "V", 'Г' to "G", 'Д' to "D", 'Е' to "E", 'Ё' to "E",
        'Ж' to "Zh", 'З' to "Z", 'И' to "I", 'Й' to "Y", 'К' to "K", 'Л' to "L", 'М' to "M",
        'Н' to "N", 'О' to "O", 'П' to "P", 'Р' to "R", 'С' to "S", 'Т' to "T", 'У' to "U",
        'Ф' to "F", 'Х' to "H", 'Ц' to "Ts", 'Ч' to "Ch", 'Ш' to "Sh", 'Щ' to "Sch",
        'Ъ' to "", 'Ы' to "Y", 'Ь' to "", 'Э' to "E", 'Ю' to "Yu", 'Я' to "Ya",
        '—' to "-", '–' to "-", '−' to "-", '«' to "\"", '»' to "\"", '№' to "No",
        '\u00A0' to " "
    )

    fun transliterate(input: String?, maxLen: Int = 24): String {
        if (input.isNullOrBlank()) return ""
        val sb = StringBuilder(input.length)
        for (ch in input.trim()) {
            when {
                map.containsKey(ch) -> sb.append(map[ch])
                ch.code in 0x20..0x7E -> sb.append(ch)
                else -> { /* skip non-ASCII */ }
            }
        }
        val out = sb.toString().replace(Regex("\\s+"), " ").trim()
        return if (out.length <= maxLen) out else out.take(maxLen - 1) + "."
    }
}
