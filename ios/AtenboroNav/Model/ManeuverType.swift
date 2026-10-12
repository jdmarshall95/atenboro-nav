import Foundation

/// Словарь манёвров, восстановленный из iOS-бинарника 2ГИС 7.29
/// (см. `docs/IOS-2GIS-REVERSE.md`, §6).
///
/// На iOS навигация оперирует грубым перечислимым типом из 10 значений
/// `ManeuverType_*` — в отличие от Android, где приходится разбирать
/// ~40 кодовых имён PDF-глифов (`crossroad_left`, `ringroad_right_90`, …).
/// Поэтому на iOS маппинг «тип → стрелка прошивки» почти тавтология,
/// а обратный маппинг (из Android-кодоимён) нужен для общих тестовых
/// фикстур и для переноса данных между платформами.
public enum ManeuverType: String, Codable, CaseIterable, Sendable {
    case undef
    case forward
    case left
    case right
    case slightlyLeft
    case slightlyRight
    case sharplyLeft
    case sharplyRight
    case turnover
    case rightWithLeftTurn

    /// Имя значения в бинарнике 2ГИС (`ManeuverType_Left` и т.д.).
    public var rawGisName: String {
        switch self {
        case .undef: return "ManeuverType_Undef"
        case .forward: return "ManeuverType_Forward"
        case .left: return "ManeuverType_Left"
        case .right: return "ManeuverType_Right"
        case .slightlyLeft: return "ManeuverType_SlightlyLeft"
        case .slightlyRight: return "ManeuverType_SlightlyRight"
        case .sharplyLeft: return "ManeuverType_SharplyLeft"
        case .sharplyRight: return "ManeuverType_SharplyRight"
        case .turnover: return "ManeuverType_Turnover"
        case .rightWithLeftTurn: return "ManeuverType_RightWithLeftTurn"
        }
    }

    /// Числовой код перечисления из бинарника (порядок значений сохранён).
    public var gisCode: Int {
        Self.allCases.firstIndex(of: self) ?? 0
    }

    /// Токен стрелки, который понимает парсер прошивки (`parseTurn`).
    ///
    /// Прошивка рисует: left / right / slight_left / slight_right /
    /// u_turn / straight / roundabout / arrive. Всё, что не удаётся
    /// свести без потери смысла, уходит в `none`, но исходный тип
    /// сохраняется в `NavUpdate.maneuverIcon` для отладки.
    public var hudToken: String {
        switch self {
        case .undef: return "none"
        case .forward: return "straight"
        case .left: return "left"
        case .right: return "right"
        case .slightlyLeft: return "slight_left"
        case .slightlyRight: return "slight_right"
        case .sharplyLeft: return "left"
        case .sharplyRight: return "right"
        case .turnover: return "u_turn"
        case .rightWithLeftTurn: return "right"
        }
    }

    /// Человекочитаемое описание манёвра (подпись на HUD и в настройках).
    public var descriptionRu: String {
        switch self {
        case .undef: return "—"
        case .forward: return "Прямо"
        case .left: return "Налево"
        case .right: return "Направо"
        case .slightlyLeft: return "Плавно налево"
        case .slightlyRight: return "Плавно направо"
        case .sharplyLeft: return "Резко налево"
        case .sharplyRight: return "Резко направо"
        case .turnover: return "Разворот"
        case .rightWithLeftTurn: return "Направо с заездом налево"
        }
    }

    /// Разбор по имени из бинарника (`ManeuverType_Left`), по короткому
    /// имени (`left`) и по camelCase-ключу JSON (`slightlyLeft`).
    /// Неизвестное значение не ронает HUD, а превращается в `undef`.
    public static func parse(_ raw: String?) -> ManeuverType {
        guard let raw, !raw.isEmpty else { return .undef }
        if let exact = ManeuverType(rawValue: raw) { return exact }

        var key = raw
        if let range = key.range(of: "ManeuverType_") {
            key = String(key[range.upperBound...])
        }
        key = key.lowercased().replacingOccurrences(of: "_", with: "")

        switch key {
        case "undef", "none", "": return .undef
        case "forward", "straight": return .forward
        case "left": return .left
        case "right": return .right
        case "slightlyleft": return .slightlyLeft
        case "slightlyright": return .slightlyRight
        case "sharplyleft": return .sharplyLeft
        case "sharplyright": return .sharplyRight
        case "turnover", "uturn": return .turnover
        case "rightwithleftturn": return .rightWithLeftTurn
        default: return .undef
        }
    }

    /// Из Android-кодоимени глифа (`crossroad_slightly_right`) в тип iOS-ядра.
    /// Нужно только для общих фикстур: на реальном iOS глифов по имени нет.
    public static func fromAndroidCodename(_ name: String) -> ManeuverType {
        let lower = name.lowercased()
        // Кольца на iOS сворачиваются в «прямо»: в 10 значениях ManeuverType
        // отдельной стрелки кольца нет (см. §6 документа RE).
        if lower.contains("ring") { return .forward }

        var key = lower
        for prefix in ["crossroad_", "ringroad_", "left_", "right_"] {
            if key.hasPrefix(prefix) {
                key = String(key.dropFirst(prefix.count))
                break
            }
        }
        key = key.replacingOccurrences(of: "_", with: "")
        switch key {
        case "finish", "arrive": return .forward
        case "left": return .left
        case "right": return .right
        case "slightlyleft", "keepleft": return .slightlyLeft
        case "slightlyright", "keepright": return .slightlyRight
        case "sharplyleft": return .sharplyLeft
        case "sharplyright": return .sharplyRight
        case "uturn", "turnover", "turnoverlefthand", "turnoverrighthand": return .turnover
        case "straight": return .forward
        default: return .undef
        }
    }
}
