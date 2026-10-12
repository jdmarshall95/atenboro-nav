import Foundation

/// Наблюдатель за CarPlay-сценами. Вынесен в протокол, потому что
/// `CPInterfaceController` недоступен в юнит-тестах и в симуляторе без
/// подключенного автомобиля.
public protocol CarPlaySceneObserving: AnyObject {
    /// Подключён ли экран CarPlay прямо сейчас.
    var isSceneConnected: Bool { get }
    /// Активна ли навигационная сцена (Dashboard / Instrument Cluster).
    var isNavigationSceneActive: Bool { get }
}

/// Источник №2 — CarPlay.
///
/// Из реверса: `CPSupportsDashboardNavigationScene` и
/// `CPSupportsInstrumentClusterNavigationScene` объявлены в манифесте 2ГИС,
/// то есть «дашборд» и «приборка» на iOS есть — но живут они в процессе 2ГИС.
/// Наружу отсюда уходит только факт живой сцены: он годится как резервный
/// индикатор навигации, когда Live Activity не публикуется.
public final class CarPlaySource: NavSource {

    public let id: SourceID = .carPlay

    private let observer: CarPlaySceneObserving
    private let interval: TimeInterval
    private let now: () -> Date

    private var timer: Timer?
    private var lastLive: Date?

    public var onInfo: ((DashboardInfo) -> Void)?

    public init(
        observer: CarPlaySceneObserving,
        interval: TimeInterval = 3.0,
        now: @escaping () -> Date = Date.init
    ) {
        self.observer = observer
        self.interval = interval
        self.now = now
    }

    public var isLive: Bool {
        guard let lastLive else { return false }
        return now().timeIntervalSince(lastLive) < interval * 3
    }

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

    @discardableResult
    public func poll() -> DashboardInfo? {
        guard observer.isSceneConnected, observer.isNavigationSceneActive else {
            lastLive = nil
            return nil
        }
        lastLive = now()
        // Чисел от CarPlay наружу нет — сообщаем только режим и факт навигации.
        let info = DashboardInfo(
            activeNavigationMode: "carplay",
            maneuverType: .forward,
            timestamp: now()
        )
        onInfo?(info)
        return info
    }
}

/// Заглушка для симулятора и тестов: сцена никогда не подключена,
/// если не сказать ей обратное явно.
public final class StubCarPlayObserver: CarPlaySceneObserving {

    public var isSceneConnected: Bool
    public var isNavigationSceneActive: Bool

    public init(connected: Bool = false, navigationActive: Bool = false) {
        self.isSceneConnected = connected
        self.isNavigationSceneActive = navigationActive
    }
}
