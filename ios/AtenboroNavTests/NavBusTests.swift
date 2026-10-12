import XCTest
@testable import AtenboroNav

/// Арбитраж источников: кто имеет право публиковаться и кого перекрывают.
/// Логика повторяет Android `NavFeed.publish` + `isSourceEnabled`.
final class NavBusTests: XCTestCase {

    private func navigating(_ type: ManeuverType = .right, meters: Int = 300) -> DashboardInfo {
        DashboardInfo(activeNavigationMode: "car", maneuverType: type, maneuverDistance: meters)
    }

    func testPrimarySourceIsAlwaysEnabled() {
        let bus = NavBus(enabled: { _ in false })
        XCTAssertTrue(bus.isEnabled(.liveActivity), "AIDL-аналог нельзя выключить")
        XCTAssertFalse(bus.isEnabled(.carPlay))
    }

    func testDisabledSourceCannotPublish() {
        let bus = NavBus(enabled: { $0 != .urlScheme })
        XCTAssertFalse(bus.publish(navigating(), from: .urlScheme))
        XCTAssertNil(bus.lastUpdate)
    }

    func testPublishStoresUpdateAndNotifies() {
        let bus = NavBus()
        var received: [NavUpdate] = []
        bus.onUpdate = { received.append($0) }

        XCTAssertTrue(bus.publish(navigating(meters: 420), from: .liveActivity))
        XCTAssertEqual(received.count, 1)
        XCTAssertEqual(bus.lastUpdate?.turn, "right")
        XCTAssertEqual(bus.lastUpdate?.distM, 420)
        XCTAssertEqual(bus.lastUpdate?.source, "liveActivity")
        XCTAssertEqual(bus.activeSource, .liveActivity)
    }

    func testWeakerSourceDoesNotOverrideStronger() {
        let bus = NavBus()
        XCTAssertTrue(bus.publish(navigating(meters: 500), from: .liveActivity))
        XCTAssertFalse(bus.publish(navigating(meters: 100), from: .mock))
        XCTAssertEqual(bus.lastUpdate?.distM, 500, "демо-маршрут не должен перебивать Live Activity")
        XCTAssertEqual(bus.activeSource, .liveActivity)
    }

    func testStrongerSourceOverridesWeaker() {
        let bus = NavBus()
        XCTAssertTrue(bus.publish(navigating(meters: 100), from: .mock))
        XCTAssertTrue(bus.publish(navigating(meters: 500), from: .carPlay))
        XCTAssertTrue(bus.publish(navigating(meters: 900), from: .liveActivity))
        XCTAssertEqual(bus.lastUpdate?.distM, 900)
        XCTAssertEqual(bus.activeSource, .liveActivity)
    }

    func testSameSourceCanOverrideItself() {
        let bus = NavBus()
        XCTAssertTrue(bus.publish(navigating(meters: 300), from: .liveActivity))
        XCTAssertTrue(bus.publish(navigating(meters: 120), from: .liveActivity))
        XCTAssertEqual(bus.lastUpdate?.distM, 120)
    }

    /// Пустой дамп не должен стирать живую навигацию: иначе экран мигает
    /// между «едет» и «—» на каждом кадре без данных.
    func testEmptyDumpDoesNotEraseLiveNavigation() {
        let bus = NavBus()
        XCTAssertTrue(bus.publish(navigating(), from: .liveActivity))
        XCTAssertFalse(bus.publish(DashboardInfo(), from: .liveActivity))
        XCTAssertEqual(bus.activeSource, .liveActivity, "источник остаётся прежним")
        XCTAssertNotNil(bus.lastUpdate)
    }

    func testEmptyDumpIsAcceptedWhenNothingWasLive() {
        let bus = NavBus()
        XCTAssertTrue(bus.publish(DashboardInfo(), from: .mock))
        XCTAssertFalse(bus.lastUpdate?.navigating ?? true)
    }

    func testResetClearsEverything() {
        let bus = NavBus()
        bus.publish(navigating(), from: .liveActivity)
        bus.reset()
        XCTAssertNil(bus.lastUpdate)
        XCTAssertNil(bus.lastInfo)
        XCTAssertNil(bus.activeSource)
        XCTAssertNil(bus.lastError)
    }

    func testReportErrorOnlyForActiveOrNoSource() {
        let bus = NavBus()
        bus.publish(navigating(), from: .liveActivity)
        bus.reportError("нет активности 2ГИС", from: .liveActivity)
        XCTAssertEqual(bus.lastError, "нет активности 2ГИС")

        bus.reportError("шум", from: .mock)
        XCTAssertEqual(bus.lastError, "нет активности 2ГИС", "тихий источник не пишет в статус")
    }

    func testSuccessfulPublishClearsError() {
        let bus = NavBus()
        bus.reportError("нет активности 2ГИС", from: .liveActivity)
        bus.publish(navigating(), from: .liveActivity)
        XCTAssertNil(bus.lastError)
    }

    func testPriorityOrderMatchesReverseEngineeringNotes() {
        XCTAssertEqual(SourceID.liveActivity.priority, 0)
        XCTAssertEqual(SourceID.carPlay.priority, 1)
        XCTAssertEqual(SourceID.urlScheme.priority, 2)
        XCTAssertEqual(SourceID.mock.priority, 3)
        XCTAssertTrue(SourceID.liveActivity.isPrimary)
        XCTAssertFalse(SourceID.mock.isPrimary)
    }
}
