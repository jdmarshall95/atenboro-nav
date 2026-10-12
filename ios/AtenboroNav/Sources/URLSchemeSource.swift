import Foundation

/// Источник №3 — URL-схемы.
///
/// Из реверса: у 2ГИС на iOS есть `dgis://`, `grymmobile://`,
/// `ru-doublegis-grymmobile://`, `urbi://`, но `x-callback-url` в бинарнике
/// не найден (0 хитов). Значит обратного канала нет: мы можем открыть
/// маршрут, а «ответа» с данными не получим. Поэтому источник
/// односторонний и полезен для ручного ввода и автотестов —
/// формат `atenboro://nav?maneuverType=left&maneuverDistance=250`.
public final class URLSchemeSource: NavSource {

    public let id: SourceID = .urlScheme

    /// Схема нашего приложения, на которую приходит дамп.
    public static let scheme = "atenboro"

    private let now: () -> Date
    private var lastSeen: Date?
    private let ttl: TimeInterval

    public var onInfo: ((DashboardInfo) -> Void)?

    public init(ttl: TimeInterval = 15, now: @escaping () -> Date = Date.init) {
        self.ttl = ttl
        self.now = now
    }

    public var isLive: Bool {
        guard let lastSeen else { return false }
        return now().timeIntervalSince(lastSeen) < ttl
    }

    public func start() {}
    public func stop() { lastSeen = nil }

    /// Обработка входящего URL. Возвращает разобранный дамп или nil,
    /// если URL не наш.
    @discardableResult
    public func handle(url: URL) -> DashboardInfo? {
        guard url.scheme == Self.scheme else { return nil }
        let query = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        var raw: [String: Any] = [:]
        for item in query {
            guard let key = item.name as String?, let value = item.value else { continue }
            raw[key] = value
        }
        // Манёвр допускается задавать хостом или путём: atenboro://turnover,
        // atenboro://nav/left?… Для atenboro://turnover путь пустой — токен
        // лежит в хосте, поэтому смотрим и туда, и туда.
        if raw["maneuverType"] == nil {
            let host = (url.host ?? "").lowercased()
            let path = url.path.lowercased().replacingOccurrences(of: "/", with: "")
            let token = (host == Self.scheme || host.isEmpty) ? path : host
            if !token.isEmpty, token != "nav" { raw["maneuverType"] = token }
        }
        var info = DashboardInfo.decode(from: raw)
        info.timestamp = now()
        lastSeen = info.timestamp
        onInfo?(info)
        return info
    }

    /// Собирает URL для открытия 2ГИС с маршрутом «дом → работа».
    public static func gisRouteURL(from: String, to: String) -> URL? {
        let allowed = CharacterSet.urlQueryAllowed
        guard let f = from.addingPercentEncoding(withAllowedCharacters: allowed),
              let t = to.addingPercentEncoding(withAllowedCharacters: allowed) else { return nil }
        return URL(string: "dgis://2gis.ru/routeSearch/to/\(t)/go?from=\(f)")
    }
}

/// Источник №4 — синтетический маршрут. Нужен там, где 2ГИС не установлен:
/// симулятор, SwiftUI Preview, скриншоты.
public final class MockRouteSource: NavSource {

    public let id: SourceID = .mock

    /// Кадры демонстрационного маршрута.
    public static let defaultFrames: [DashboardInfo] = [
        DashboardInfo(activeNavigationMode: "car", maneuverType: .right,
                      maneuverDistance: 420, speedLimit: 60, speed: 54,
                      progress: 12, remainingTime: 1_320, totalDistance: 9_400),
        DashboardInfo(activeNavigationMode: "car", maneuverType: .slightlyLeft,
                      maneuverDistance: 180, cameraPresent: true, cameraDistance: 90,
                      cameraSpeedLimit: 40, cameraDistancePercent: 65,
                      speedLimit: 40, speed: 38, progress: 41, remainingTime: 780,
                      totalDistance: 5_600, street: "Тверская"),
        DashboardInfo(activeNavigationMode: "car", maneuverType: .turnover,
                      maneuverDistance: 60, jamInfoDuration: 7,
                      trafficLightColor: "red", trafficLightCountdown: 12,
                      progress: 78, remainingTime: 240, totalDistance: 2_100),
        DashboardInfo(activeNavigationMode: "car", maneuverType: .forward,
                      maneuverDistance: 30, progress: 99, remainingTime: 40,
                      totalDistance: 300, street: "Мясницкая")
    ]

    private let frames: [DashboardInfo]
    private let interval: TimeInterval
    private let now: () -> Date

    private var timer: Timer?
    private(set) var index = 0

    public var onInfo: ((DashboardInfo) -> Void)?

    public init(
        frames: [DashboardInfo] = MockRouteSource.defaultFrames,
        interval: TimeInterval = 4.0,
        now: @escaping () -> Date = Date.init
    ) {
        self.frames = frames.isEmpty ? MockRouteSource.defaultFrames : frames
        self.interval = interval
        self.now = now
    }

    public var isLive: Bool { timer != nil }

    public func start() {
        stop()
        let timer = Timer(timeInterval: interval, repeats: true) { [weak self] _ in
            self?.advance()
        }
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
        advance()
    }

    public func stop() {
        timer?.invalidate()
        timer = nil
    }

    /// Следующий кадр маршрута. Публичный — для тестов и кнопки «вперёд».
    @discardableResult
    public func advance() -> DashboardInfo {
        let frame = frames[index % frames.count]
        index += 1
        var stamped = frame
        stamped.timestamp = now()
        onInfo?(stamped)
        return stamped
    }
}
