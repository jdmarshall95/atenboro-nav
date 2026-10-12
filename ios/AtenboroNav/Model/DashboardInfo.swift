import Foundation

/// «Дамп» навигационного состояния 2ГИС, который удаётся получить внешним
/// способом на iOS.
///
/// На Android это JSON из `getDashboardInformationJSON()` (AIDL). На iOS
/// межпроцессного сервиса нет вовсе (`docs/IOS-2GIS-REVERSE.md`, §5),
/// поэтому дамп собирается из доступных наружу поверхностей:
/// content-state Live Activity, данных CarPlay-шаблона и query-параметров
/// входящего URL. Ключи намеренно совпадают с Android-именами — так
/// прошивка получает один и тот же формат с обеих платформ.
public struct DashboardInfo: Equatable, Sendable {

    /// Активный режим навигации (`""` — навигация не активна).
    public var activeNavigationMode: String
    /// Тип манёвра из ядра 2ГИС (10 значений, §6 документа RE).
    public var maneuverType: ManeuverType
    /// Расстояние до манёвра, метры (−1 — нет данных).
    public var maneuverDistance: Int
    /// Подпись манёвра текстом (если пришла).
    public var maneuverDescription: String?
    /// Активна ли камера ограничения на участке.
    public var cameraPresent: Bool
    /// Расстояние до камеры, метры (−1 — нет данных).
    public var cameraDistance: Int
    /// Лимит скорости камеры, км/ч (−1 — нет данных).
    public var cameraSpeedLimit: Int
    /// 0…100 — приближение к камере (−1 — нет данных).
    public var cameraDistancePercent: Int
    /// Пробка: минуты (−1 — нет данных).
    public var jamInfoDuration: Int
    /// Пробка: метры (−1 — нет данных).
    public var jamInfoLengthMeters: Int
    /// Цвет сигнала светофора (`red` / `green` / `yellow`, `""` — нет).
    public var trafficLightColor: String
    /// Секунды до смены сигнала (−1 — прятать).
    public var trafficLightCountdown: Int
    /// Ограничение скорости на участке, км/ч (−1 — нет данных).
    public var speedLimit: Int
    /// Текущая скорость, км/ч (−1 — нет данных).
    public var speed: Int
    /// 0…100 — прохождение маршрута (−1 — нет данных).
    public var progress: Int
    /// Оставшееся время маршрута, секунды (−1 — нет данных).
    public var remainingTime: Int
    /// Оставшееся расстояние маршрута, метры (−1 — нет данных).
    public var totalDistance: Int
    /// Время прибытия (unix time, nil — нет данных).
    public var arrivalTime: Date?
    /// Улица, на которой едем (если известна).
    public var street: String?
    /// Плохая ли позиция GPS.
    public var badLocation: Bool
    /// Метка времени снимка.
    public var timestamp: Date

    public init(
        activeNavigationMode: String = "",
        maneuverType: ManeuverType = .undef,
        maneuverDistance: Int = -1,
        maneuverDescription: String? = nil,
        cameraPresent: Bool = false,
        cameraDistance: Int = -1,
        cameraSpeedLimit: Int = -1,
        cameraDistancePercent: Int = -1,
        jamInfoDuration: Int = -1,
        jamInfoLengthMeters: Int = -1,
        trafficLightColor: String = "",
        trafficLightCountdown: Int = -1,
        speedLimit: Int = -1,
        speed: Int = -1,
        progress: Int = -1,
        remainingTime: Int = -1,
        totalDistance: Int = -1,
        arrivalTime: Date? = nil,
        street: String? = nil,
        badLocation: Bool = false,
        timestamp: Date = Date()
    ) {
        self.activeNavigationMode = activeNavigationMode
        self.maneuverType = maneuverType
        self.maneuverDistance = maneuverDistance
        self.maneuverDescription = maneuverDescription
        self.cameraPresent = cameraPresent
        self.cameraDistance = cameraDistance
        self.cameraSpeedLimit = cameraSpeedLimit
        self.cameraDistancePercent = cameraDistancePercent
        self.jamInfoDuration = jamInfoDuration
        self.jamInfoLengthMeters = jamInfoLengthMeters
        self.trafficLightColor = trafficLightColor
        self.trafficLightCountdown = trafficLightCountdown
        self.speedLimit = speedLimit
        self.speed = speed
        self.progress = progress
        self.remainingTime = remainingTime
        self.totalDistance = totalDistance
        self.arrivalTime = arrivalTime
        self.street = street
        self.badLocation = badLocation
        self.timestamp = timestamp
    }

    /// Навигация активна, если ядро сообщает режим (motorcycle / car / …).
    public var isNavigating: Bool {
        !activeNavigationMode.isEmpty && maneuverType != .undef
    }
}

// MARK: - Lenient JSON

public extension DashboardInfo {

    /// Ключи, которые мы сами эмитим в мосту (совпадают с Android AIDL).
    ///
    /// Разбор нарочно снисходительный: значения могут приходить числами,
    /// строками, отсутствовать или быть `null` — ни один вариант не должен
    /// обрывать поток данных.
    static func decode(from data: Data) -> DashboardInfo? {
        guard let raw = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            return nil
        }
        return decode(from: raw)
    }

    static func decode(from raw: [String: Any]) -> DashboardInfo {
        var info = DashboardInfo()
        info.activeNavigationMode = raw.string(forKey: "activeNavigationMode") ?? ""
        info.maneuverType = ManeuverType.parse(raw.string(forKey: "maneuverType"))
        info.maneuverDistance = raw.int(forKey: "maneuverDistance") ?? -1
        info.maneuverDescription = raw.string(forKey: "maneuverDescription")
        info.cameraPresent = raw.bool(forKey: "cameraPresent") ?? false
        info.cameraDistance = raw.int(forKey: "cameraDistance") ?? -1
        info.cameraSpeedLimit = raw.int(forKey: "cameraSpeedLimit") ?? -1
        info.cameraDistancePercent = raw.int(forKey: "cameraDistancePercent") ?? -1
        info.jamInfoDuration = raw.int(forKey: "jamInfoDuration") ?? -1
        info.jamInfoLengthMeters = raw.int(forKey: "jamInfoLengthMeters") ?? -1
        info.trafficLightColor = raw.string(forKey: "trafficLightColor") ?? ""
        info.trafficLightCountdown = raw.int(forKey: "trafficLightCountdown") ?? -1
        info.speedLimit = raw.int(forKey: "speedLimit") ?? -1
        info.speed = raw.int(forKey: "speed") ?? -1
        info.progress = raw.int(forKey: "progress") ?? -1
        info.remainingTime = raw.int(forKey: "remainingTime") ?? -1
        info.totalDistance = raw.int(forKey: "totalDistance") ?? -1
        if let seconds = raw.double(forKey: "arrivalTime") {
            info.arrivalTime = Date(timeIntervalSince1970: seconds)
        }
        info.street = raw.string(forKey: "street")
        info.badLocation = raw.bool(forKey: "badLocation") ?? false
        if let seconds = raw.double(forKey: "ts") {
            info.timestamp = Date(timeIntervalSince1970: seconds)
        }
        return info
    }

    /// Дамп в тот же формат, что отдаёт Android-прошивка.
    func toJSONData(pretty: Bool = false) -> Data {
        var raw: [String: Any] = [
            "activeNavigationMode": activeNavigationMode,
            "maneuverType": maneuverType.rawValue,
            "maneuverDistance": maneuverDistance,
            "cameraPresent": cameraPresent,
            "cameraDistance": cameraDistance,
            "cameraSpeedLimit": cameraSpeedLimit,
            "cameraDistancePercent": cameraDistancePercent,
            "jamInfoDuration": jamInfoDuration,
            "jamInfoLengthMeters": jamInfoLengthMeters,
            "trafficLightColor": trafficLightColor,
            "trafficLightCountdown": trafficLightCountdown,
            "speedLimit": speedLimit,
            "speed": speed,
            "progress": progress,
            "remainingTime": remainingTime,
            "totalDistance": totalDistance,
            "badLocation": badLocation,
            "ts": timestamp.timeIntervalSince1970
        ]
        if let maneuverDescription { raw["maneuverDescription"] = maneuverDescription }
        if let arrivalTime { raw["arrivalTime"] = arrivalTime.timeIntervalSince1970 }
        if let street { raw["street"] = street }
        let opts: JSONSerialization.WritingOptions = pretty
            ? [.prettyPrinted, .sortedKeys]
            : [.sortedKeys]
        return (try? JSONSerialization.data(withJSONObject: raw, options: opts)) ?? Data("{}".utf8)
    }
}

private extension Dictionary where Key == String, Value == Any {

    func string(forKey key: String) -> String? {
        switch self[key] {
        case let v as String: return v
        case let v as NSNumber: return v.stringValue
        default: return nil
        }
    }

    func int(forKey key: String) -> Int? {
        switch self[key] {
        case let v as NSNumber: return v.intValue
        case let v as String: return Int(v)
        default: return nil
        }
    }

    func double(forKey key: String) -> Double? {
        switch self[key] {
        case let v as NSNumber: return v.doubleValue
        case let v as String: return Double(v)
        default: return nil
        }
    }

    func bool(forKey key: String) -> Bool? {
        switch self[key] {
        case let v as NSNumber: return v.boolValue
        case let v as String: return ["true", "1", "yes"].contains(v.lowercased())
        default: return nil
        }
    }
}
