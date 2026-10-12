import Foundation
import ActivityKit

/// Читатель Live Activities: перечисляет активности 2ГИС и превращает
/// их content-state в наш `DashboardInfo`.
///
/// Вынесено в протокол, чтобы тесты могли подменить ActivityKit
/// (в симуляторе 2ГИС не установлен, и настоящих активностей нет).
public protocol LiveActivityReading: AnyObject {
    /// Текущие content-state активностей в виде словарей.
    func snapshots() -> [[String: Any]]
}

/// Зеркало атрибутов активности 2ГИС.
///
/// Имя типа в ActivityKit — часть ключа, по которому система отдаёт
/// активности: в бинаре 2ГИС есть `Activity<NavigatorLiveAttributes>`,
/// поэтому наш тип называется так же. `AnyActivityAttributes` появился
/// только в iOS 18, а хука вроде `activityAttributesName` в нашем SDK нет,
/// так что совпадение по имени — единственный доступный способ.
///
/// Тип атрибутов объявлен пустым: лишние поля игнорируются декодером,
/// а нам важен только content-state.
public struct NavigatorLiveAttributes: ActivityAttributes {

    /// Content-state навигации: все поля опциональны, потому что 2ГИС
    /// заполняет их по мере появления данных в маршруте.
    public struct ContentState: Codable, Hashable {
        public var activeNavigationMode: String?
        public var maneuverType: String?
        public var maneuverDistance: Int?
        public var maneuverDescription: String?
        public var cameraPresent: Bool?
        public var cameraDistance: Int?
        public var cameraSpeedLimit: Int?
        public var cameraDistancePercent: Int?
        public var jamInfoDuration: Int?
        public var jamInfoLengthMeters: Int?
        public var trafficLightColor: String?
        public var trafficLightCountdown: Int?
        public var speedLimit: Int?
        public var speed: Int?
        public var progress: Int?
        public var remainingTime: Int?
        public var totalDistance: Int?
        public var arrivalTime: Double?
        public var street: String?
        public var badLocation: Bool?
        public var ts: Double?

        public init() {}
    }

    public init() {}
}

/// Боевой читатель на ActivityKit.
///
/// Content-state декодируется в `NavigatorLiveAttributes.ContentState`
/// и перекладывается в словарь, чтобы дальше шёл общим путём разбора.
public final class ActivityKitLiveActivityReader: LiveActivityReading {

    public init() {}

    public func snapshots() -> [[String: Any]] {
        guard #available(iOS 16.2, *) else { return [] }
        return Activity<NavigatorLiveAttributes>.activities.compactMap { activity in
            let state = activity.content.state
            guard let data = try? JSONEncoder().encode(state) else { return nil }
            return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        }
    }
}

/// Источник №1 — Live Activity / Dynamic Island 2ГИС.
///
/// Теория: 2ГИС на iOS не отдаёт навигацию наружу через IPC, но сам
/// публикует её в Live Activity (207 упоминаний ActivityKit в бинарнике,
/// включая `tapOnNaviDynamicIslandWhileNavigation`). Значит content-state —
/// единственная внешняя поверхность, где едут настоящие числа.
public final class LiveActivitySource: NavSource {

    public let id: SourceID = .liveActivity

    /// Ключ, по которому активность считается «навигационной».
    static let markerKeys: [String] = [
        "maneuverType", "activeNavigationMode", "maneuverDistance", "remainingTime"
    ]

    private let reader: LiveActivityReading
    private let interval: TimeInterval
    private let now: () -> Date

    private var timer: Timer?
    private var lastSeen: Date?
    private var lastError: String?

    /// Сюда выкладываем разобранный дамп.
    public var onInfo: ((DashboardInfo) -> Void)?
    /// Сюда — текст ошибки для карточки статуса.
    public var onError: ((String) -> Void)?

    public init(
        reader: LiveActivityReading = ActivityKitLiveActivityReader(),
        interval: TimeInterval = 2.0,
        now: @escaping () -> Date = Date.init
    ) {
        self.reader = reader
        self.interval = interval
        self.now = now
    }

    public var isLive: Bool {
        guard let lastSeen else { return false }
        return now().timeIntervalSince(lastSeen) < interval * 3
    }

    public var lastErrorMessage: String? { lastError }

    public func start() {
        stop()
        let timer = Timer(timeInterval: interval, repeats: true) { [weak self] _ in
            self?.poll()
        }
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
        poll()
    }

    public func stop() {
        timer?.invalidate()
        timer = nil
    }

    /// Один шаг опроса. Публичный — чтобы тесты дёргали его напрямую,
    /// без ожидания таймера.
    @discardableResult
    public func poll() -> DashboardInfo? {
        let candidates = reader.snapshots().filter { Self.accepts($0) }
        guard let best = candidates.first(where: { $0.string("activeNavigationMode")?.isEmpty == false })
            ?? candidates.first else {
            lastSeen = nil
            onError?("нет активности 2ГИС")
            return nil
        }
        let info = DashboardInfo.decode(from: best)
        lastSeen = now()
        lastError = nil
        onInfo?(info)
        return info
    }

    /// Активность считается навигационной, если в content-state есть
    /// хотя бы один известный ключ.
    public static func accepts(_ state: [String: Any]) -> Bool {
        markerKeys.contains { state[$0] != nil }
    }
}

private extension Dictionary where Key == String, Value == Any {
    func string(_ key: String) -> String? { self[key] as? String }
}
