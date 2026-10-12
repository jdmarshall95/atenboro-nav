import Foundation
import UIKit

/// Пользовательские настройки: тема и включённые источники.
///
/// Хранится в `UserDefaults`. Логика повторяет Android-версию
/// (`settings/AppPrefs.kt`): основной источник выключить нельзя,
/// остальные — по умолчанию выключены.
public final class AppPrefs {

    public enum ThemeMode: String, CaseIterable, Sendable {
        case auto
        case day
        case night

        public var labelRu: String {
            switch self {
            case .auto: return "авто"
            case .day: return "день"
            case .night: return "ночь"
            }
        }

        var userInterfaceStyle: UIUserInterfaceStyle {
            switch self {
            case .auto: return .unspecified
            case .day: return .light
            case .night: return .dark
            }
        }

        /// auto → day → night → auto
        var next: ThemeMode {
            switch self {
            case .auto: return .day
            case .day: return .night
            case .night: return .auto
            }
        }
    }

    public static let shared = AppPrefs()

    private let defaults: UserDefaults

    private enum Key {
        static let theme = "theme_mode"
        static let carPlay = "src_carplay"
        static let urlScheme = "src_urlscheme"
        static let mock = "src_mock"
        static let host = "esp_host"
        static let port = "esp_port"
    }

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    // MARK: - Тема

    public var themeMode: ThemeMode {
        get { ThemeMode(rawValue: defaults.string(forKey: Key.theme) ?? "") ?? .auto }
        set { defaults.set(newValue.rawValue, forKey: Key.theme) }
    }

    /// Прокрутить день/ночь и вернуть новый режим.
    @discardableResult
    public func cycleTheme() -> ThemeMode {
        let next = themeMode.next
        themeMode = next
        return next
    }

    public func applyTheme(to window: UIWindow? = nil) {
        let style = themeMode.userInterfaceStyle
        if let window {
            window.overrideUserInterfaceStyle = style
        } else {
            applyThemeToAllWindows(style)
        }
    }

    /// Вариант для `UIWindowScene` — им удобно пользоваться из `SceneDelegate`.
    public func applyTheme(to scene: UIWindowScene?) {
        applyThemeToAllWindows(themeMode.userInterfaceStyle, only: scene)
    }

    private func applyThemeToAllWindows(_ style: UIUserInterfaceStyle,
                                       only target: UIWindowScene? = nil) {
        let scenes: [UIWindowScene]
        if let target {
            scenes = [target]
        } else {
            scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        }
        scenes.flatMap(\.windows).forEach { $0.overrideUserInterfaceStyle = style }
    }

    // MARK: - Источники

    /// Live Activity — основной, всегда включён.
    public var liveActivityEnabled: Bool { true }

    public var carPlayEnabled: Bool {
        get { defaults.bool(forKey: Key.carPlay) }
        set { defaults.set(newValue, forKey: Key.carPlay) }
    }

    public var urlSchemeEnabled: Bool {
        get { defaults.bool(forKey: Key.urlScheme) }
        set { defaults.set(newValue, forKey: Key.urlScheme) }
    }

    public var mockEnabled: Bool {
        get { defaults.bool(forKey: Key.mock) }
        set { defaults.set(newValue, forKey: Key.mock) }
    }

    public func isEnabled(_ source: SourceID) -> Bool {
        switch source {
        case .liveActivity: return true
        case .carPlay: return carPlayEnabled
        case .urlScheme: return urlSchemeEnabled
        case .mock: return mockEnabled
        }
    }

    public func setEnabled(_ value: Bool, for source: SourceID) {
        switch source {
        case .liveActivity: break
        case .carPlay: carPlayEnabled = value
        case .urlScheme: urlSchemeEnabled = value
        case .mock: mockEnabled = value
        }
    }

    // MARK: - Плата

    public var espHost: String {
        get { defaults.string(forKey: Key.host) ?? "192.168.4.1" }
        set { defaults.set(newValue, forKey: Key.host) }
    }

    public var espPort: UInt16 {
        get {
            let stored = defaults.integer(forKey: Key.port)
            guard stored > 0 else { return 18_765 }
            return UInt16(min(65_535, stored))
        }
        set { defaults.set(Int(newValue), forKey: Key.port) }
    }
}
