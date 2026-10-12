import UIKit

/// Делегат сцены: создаёт окно с единственным экраном [`MainViewController`](../UI/MainViewController.swift)
/// и прокидывает входящие URL (`atenboro://…`) в [`NavProxy`](../Nav/NavProxy.swift).
///
/// Тема (день/ночь) применяется здесь же, потому что окно появляется только на этом шаге,
/// а `AppDelegate.didFinishLaunching` про `overrideUserInterfaceStyle` знает только в теории.
final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    private var app: AppDelegate {
        UIApplication.shared.delegate as! AppDelegate
    }

    func scene(_ scene: UIScene,
               willConnectTo session: UISceneSession,
               options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }

        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = MainViewController(proxy: app.proxy, prefs: app.prefs)
        self.window = window
        window.makeKeyAndVisible()

        app.prefs.applyTheme(to: windowScene)

        if let url = connectionOptions.urlContexts.first?.url {
            app.proxy.handle(url: url)
        }
    }

    func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
        guard let url = URLContexts.first?.url else { return }
        app.proxy.handle(url: url)
    }

    func sceneDidBecomeActive(_ scene: UIScene) {
        app.prefs.applyTheme(to: scene as? UIWindowScene)
    }

    func sceneDidEnterBackground(_ scene: UIScene) {
        // Live Activity — единственный канал, который живёт, пока приложение свёрнуто,
        // поэтому прокси не останавливаем: он продолжает читать состояние активности.
    }
}
