import XCTest
@testable import AtenboroNav

/// Настройки: тема и включённые источники. Основной источник выключить нельзя.
final class AppPrefsTests: XCTestCase {

    private var suiteName: String!
    private var defaults: UserDefaults!
    private var prefs: AppPrefs!

    override func setUp() {
        super.setUp()
        suiteName = "tests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        prefs = AppPrefs(defaults: defaults)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        prefs = nil
        defaults = nil
        suiteName = nil
        super.tearDown()
    }

    func testThemeDefaultsToAuto() {
        XCTAssertEqual(prefs.themeMode, .auto)
    }

    func testThemeCyclesAutoDayNight() {
        XCTAssertEqual(prefs.cycleTheme(), .day)
        XCTAssertEqual(prefs.cycleTheme(), .night)
        XCTAssertEqual(prefs.cycleTheme(), .auto)
        XCTAssertEqual(prefs.themeMode, .auto)
    }

    func testThemePersists() {
        prefs.themeMode = .night
        XCTAssertEqual(AppPrefs(defaults: defaults).themeMode, .night)
    }

    func testUnknownThemeValueFallsBackToAuto() {
        defaults.set("лунный", forKey: "theme_mode")
        XCTAssertEqual(prefs.themeMode, .auto)
    }

    func testPrimarySourceIsAlwaysOn() {
        XCTAssertFalse(prefs.isEnabled(.mock), "демо-маршрут по умолчанию выключен")
        XCTAssertTrue(prefs.liveActivityEnabled)
        XCTAssertTrue(prefs.isEnabled(.liveActivity))
    }

    func testPrimarySourceIgnoresSetEnabled() {
        prefs.setEnabled(false, for: .liveActivity)
        XCTAssertTrue(prefs.isEnabled(.liveActivity))
    }

    func testSecondarySourcesToggle() {
        prefs.setEnabled(true, for: .carPlay)
        prefs.setEnabled(true, for: .urlScheme)
        XCTAssertTrue(prefs.isEnabled(.carPlay))
        XCTAssertTrue(prefs.isEnabled(.urlScheme))
        XCTAssertFalse(prefs.isEnabled(.mock))

        prefs.setEnabled(false, for: .carPlay)
        XCTAssertFalse(AppPrefs(defaults: defaults).isEnabled(.carPlay))
    }

    func testBoardDefaults() {
        XCTAssertEqual(prefs.espHost, "192.168.4.1")
        XCTAssertEqual(prefs.espPort, 18_765)
    }

    func testBoardSettingsPersist() {
        prefs.espHost = "10.0.0.5"
        prefs.espPort = 9_000
        let restored = AppPrefs(defaults: defaults)
        XCTAssertEqual(restored.espHost, "10.0.0.5")
        XCTAssertEqual(restored.espPort, 9_000)
    }

    func testThemeLabels() {
        XCTAssertEqual(AppPrefs.ThemeMode.allCases.map(\.labelRu), ["авто", "день", "ночь"])
    }
}
