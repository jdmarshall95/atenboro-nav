import Foundation

/// Оркестратор: источники → шина → плата. Аналог `NavProxyService`
/// из Android-версии, только без foreground-сервиса (на iOS его роль
/// играет фоновый режим `location` + Live Activity).
public final class NavProxy {

    public enum Running: Equatable {
        case stopped
        case running
    }

    public private(set) var state: Running = .stopped
    public let bus: NavBus
    public let liveActivity: LiveActivitySource
    public let carPlay: CarPlaySource
    public let urlScheme: URLSchemeSource
    public let mock: MockRouteSource

    private let prefs: AppPrefs
    private let esp: EspClient
    /// Результат последней публикации от URL-источника: нужен, чтобы
    /// handle(url:) отвечал честно — принят дамп шиной или источник выключен.
    private var lastURLAccepted = false

    /// Вызывается при каждом новом снапшоте — UI подписывается на него.
    public var onUpdate: ((NavUpdate) -> Void)?
    /// Вызывается при смене состояния прокси/платы.
    public var onStateChange: (() -> Void)?

    public init(
        prefs: AppPrefs = .shared,
        bus: NavBus? = nil,
        liveActivity: LiveActivitySource? = nil,
        carPlay: CarPlaySource? = nil,
        urlScheme: URLSchemeSource? = nil,
        mock: MockRouteSource? = nil,
        esp: EspClient? = nil
    ) {
        let prefs = prefs
        self.prefs = prefs
        self.bus = bus ?? NavBus(enabled: { prefs.isEnabled($0) })
        self.liveActivity = liveActivity ?? LiveActivitySource()
        self.carPlay = carPlay ?? CarPlaySource(observer: StubCarPlayObserver())
        self.urlScheme = urlScheme ?? URLSchemeSource()
        self.mock = mock ?? MockRouteSource()
        self.esp = esp ?? EspClient(host: prefs.espHost, port: prefs.espPort)
        wire()
    }

    private func wire() {
        bus.onUpdate = { [weak self] update in
            guard let self else { return }
            self.onUpdate?(update)
            self.forward(update)
        }
        liveActivity.onInfo = { [weak self] info in
            self?.bus.publish(info, from: .liveActivity)
        }
        liveActivity.onError = { [weak self] message in
            self?.bus.reportError(message, from: .liveActivity)
            self?.onStateChange?()
        }
        carPlay.onInfo = { [weak self] info in
            self?.bus.publish(info, from: .carPlay)
        }
        urlScheme.onInfo = { [weak self] info in
            guard let self else { return }
            self.lastURLAccepted = self.bus.publish(info, from: .urlScheme)
        }
        mock.onInfo = { [weak self] info in
            self?.bus.publish(info, from: .mock)
        }
    }

    public func start() {
        guard state != .running else { return }
        state = .running
        liveActivity.start()
        if prefs.isEnabled(.carPlay) { carPlay.start() } else { carPlay.stop() }
        if prefs.isEnabled(.mock) { mock.start() } else { mock.stop() }
        onStateChange?()
    }

    public func stop() {
        state = .stopped
        liveActivity.stop()
        carPlay.stop()
        mock.stop()
        onStateChange?()
    }

    /// Применение изменений настроек источников на лету.
    public func applySourceSettings() {
        guard state == .running else { return }
        if prefs.isEnabled(.carPlay) { carPlay.start() } else { carPlay.stop() }
        if prefs.isEnabled(.mock) { mock.start() } else { mock.stop() }
    }

    /// Обработка входящего URL (из SceneDelegate).
    ///
    /// Возвращает true, только если дамп реально принят шиной. URL может быть
    /// нашим и разобранным, но источник «URL-схема» выключен в настройках —
    /// тогда публикация не проходит и мы отвечаем false.
    @discardableResult
    public func handle(url: URL) -> Bool {
        lastURLAccepted = false
        guard urlScheme.handle(url: url) != nil else { return false }
        return lastURLAccepted
    }

    /// Отправить текущий снапшот на платы вручную (кнопка «Подключить к плате»).
    @discardableResult
    public func pushToBoard() async -> Bool {
        guard let update = bus.lastUpdate else { return false }
        let ok = await esp.send(update)
        onStateChange?()
        return ok
    }

    public func boardState() async -> EspClient.State {
        await esp.stateValue
    }

    private func forward(_ update: NavUpdate) {
        guard prefs.isEnabled(.mock) || state == .running else { return }
        Task { [esp] in
            _ = await esp.send(update)
            self.onStateChange?()
        }
    }
}

public extension EspClient {
    /// Читатель состояния для UI: actor-isolated свойство наружу отдаётся
    /// только через async-геттер.
    var stateValue: EspClient.State {
        get async { self.state }
    }
}
