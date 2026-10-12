import Foundation

/// Внешние поверхности iOS-версии 2ГИС, из которых мы можем читать данные
/// (`docs/IOS-2GIS-REVERSE.md`, §7). Приоритет — «самая лучшая теория»:
/// чем меньше потерь на границе процесса, тем выше источник в arbitration.
public enum SourceID: String, CaseIterable, Codable, Sendable {

    /// Live Activities / ActivityKit: 2ГИС сам публикует состояние навигации
    /// в Dynamic Island, и стороннее приложение может прочитать content-state.
    /// Единственная поверхность, где едут числа (дистанция, ETA, манёвр).
    case liveActivity

    /// CarPlay-сцена: Dashboard / Instrument Cluster. Данные не наши,
    /// но факт подключения и активная сцена — сигнал, что навигация жива.
    case carPlay

    /// URL-схема (`dgis://`, `atenboro://`). Обратного канала нет
    /// (`x-callback-url` в бинарнике не найден), поэтому источник
    /// односторонний: полезен для ручного ввода и автотестов.
    case urlScheme

    /// Синтетический маршрут для симулятора и превью — там, где 2ГИС
    /// не установлен.
    case mock

    /// Приоритет arbitration: меньше число — сильнее источник.
    public var priority: Int {
        switch self {
        case .liveActivity: return 0
        case .carPlay: return 1
        case .urlScheme: return 2
        case .mock: return 3
        }
    }

    public var titleRu: String {
        switch self {
        case .liveActivity: return "Live Activity"
        case .carPlay: return "CarPlay"
        case .urlScheme: return "URL-схема"
        case .mock: return "Демо-маршрут"
        }
    }

    public var subtitleRu: String {
        switch self {
        case .liveActivity: return "состояние Dynamic Island 2ГИС"
        case .carPlay: return "сцена Dashboard / приборки"
        case .urlScheme: return "dgis:// и atenboro://, без обратного канала"
        case .mock: return "проигрывает маршрут по таймеру"
        }
    }

    /// AIDL-аналог на Android — «основной» источник. На iOS это Live Activity:
    /// он всегда включён, остальные подключаются вручную.
    public var isPrimary: Bool { self == .liveActivity }
}

/// Контракт источника данных о навигации.
public protocol NavSource: AnyObject {

    var id: SourceID { get }

    /// Запустить чтение. Реализация обязана быть идемпотентной.
    func start()

    /// Остановить чтение и отпустить ресурсы.
    func stop()

    /// Активен ли источник прямо сейчас (для статуса на главном экране).
    var isLive: Bool { get }
}

/// Ошибка источника — текст в карточку статуса.
public enum NavSourceError: LocalizedError, Equatable {
    case notSupported(String)
    case decodeFailed(String)
    case transport(String)

    public var errorDescription: String? {
        switch self {
        case .notSupported(let what): return "\(what) недоступен"
        case .decodeFailed(let what): return "не удалось разобрать: \(what)"
        case .transport(let what): return "канал: \(what)"
        }
    }
}
