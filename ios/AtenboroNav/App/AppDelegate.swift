import UIKit

@main
public final class AppDelegate: UIResponder, UIApplicationDelegate {

    /// Единый оркестратор на всё приложение.
    public private(set) lazy var proxy: NavProxy = NavProxy(prefs: prefs)

    public let prefs = AppPrefs()

    public func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        prefs.applyTheme()
        return true
    }

    // MARK: - Сцены

    public func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        let config = UISceneConfiguration(name: "Default Configuration", sessionRole: connectingSceneSession.role)
        config.delegateClass = SceneDelegate.self
        return config
    }

    public func application(
        _ application: UIApplication,
        supportedInterfaceOrientationsFor window: UIWindow?
    ) -> UIInterfaceOrientationMask {
        .portrait
    }
}
