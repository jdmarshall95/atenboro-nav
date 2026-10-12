import Foundation

/// Шина навигации: принимает дампы от всех включённых источников,
/// выбирает победителя по приоритету и отдаёт наружу `NavUpdate`.
///
/// Логика повторяет Android-версию (`NavFeed.publish` + `isSourceEnabled`):
/// выключенный источник не может ничего опубликовать, а основной
/// (Live Activity) выключить нельзя.
public final class NavBus {

    /// Последний опубликованный снапшот.
    public private(set) var lastUpdate: NavUpdate?
    /// Последний дамп победителя — для карточек статуса.
    public private(set) var lastInfo: DashboardInfo?
    /// Кто победил в последний раз.
    public private(set) var activeSource: SourceID?
    /// Текст последней ошибки источника.
    public private(set) var lastError: String?

    /// Вызывается при каждом успешном publish().
    public var onUpdate: ((NavUpdate) -> Void)?

    private let enabled: (SourceID) -> Bool
    private let now: () -> Date

    public init(
        enabled: @escaping (SourceID) -> Bool = { _ in true },
        now: @escaping () -> Date = Date.init
    ) {
        self.enabled = enabled
        self.now = now
    }

    /// Основной источник включён всегда, даже если настройка сбилась.
    public func isEnabled(_ source: SourceID) -> Bool {
        source.isPrimary || enabled(source)
    }

    /// Публикация дампа от источника. Возвращает false, если источник
    /// выключен или проиграл более приоритетному.
    @discardableResult
    public func publish(_ info: DashboardInfo, from source: SourceID) -> Bool {
        guard isEnabled(source) else { return false }

        // Более слабый источник не перекрывает активного более сильного.
        if let current = activeSource, current.priority < source.priority {
            return false
        }

        // Пустой дамп не должен стирать живые данные: если навигация
        // не активна, а у предыдущего снапшота она была — не публикуем.
        if !info.isNavigating, lastInfo?.isNavigating == true, activeSource == source {
            lastError = nil
            return false
        }

        activeSource = source
        lastInfo = info
        lastError = nil
        let update = NavUpdate(info: info, source: source.rawValue)
        lastUpdate = update
        onUpdate?(update)
        return true
    }

    /// Сброс состояния (например, при остановке прокси).
    public func reset() {
        lastUpdate = nil
        lastInfo = nil
        activeSource = nil
        lastError = nil
    }

    public func reportError(_ message: String, from source: SourceID) {
        guard activeSource == source || activeSource == nil else { return }
        lastError = message
    }
}
