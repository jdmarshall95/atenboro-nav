import XCTest
@testable import AtenboroNav

// MARK: - Двойники

/// Фейковый читатель Live Activities: отдаёт то, что ему положили.
private final class FakeLiveActivityReader: LiveActivityReading {
    var states: [[String: Any]]
    private(set) var calls = 0

    init(_ states: [[String: Any]] = []) {
        self.states = states
    }

    func snapshots() -> [[String: Any]] {
        calls += 1
        return states
    }
}

/// Источник №1 — Live Activity. По реверсу это единственная поверхность iOS,
/// где в навигации едут настоящие числа.
final class LiveActivitySourceTests: XCTestCase {

    private let fixed = Date(timeIntervalSince1970: 1_700_000_000)

    private func state(_ extra: [String: Any] = [:]) -> [String: Any] {
        var base: [String: Any] = [
            "activeNavigationMode": "car",
            "maneuverType": "ManeuverType_Right",
            "maneuverDistance": 420,
            "remainingTime": 1_320
        ]
        extra.forEach { base[$0] = $1 }
        return base
    }

    func testRecognizesNavigationActivityByMarkerKeys() {
        XCTAssertTrue(LiveActivitySource.accepts(state()))
        XCTAssertTrue(LiveActivitySource.accepts(["remainingTime": 60]))
        XCTAssertFalse(LiveActivitySource.accepts(["title": "Рестораны рядом"]))
        XCTAssertFalse(LiveActivitySource.accepts([:]))
    }

    func testPollDecodesState() {
        let reader = FakeLiveActivityReader([state()])
        let source = LiveActivitySource(reader: reader, interval: 1, now: { self.fixed })

        var published: [DashboardInfo] = []
        source.onInfo = { published.append($0) }

        let info = source.poll()
        XCTAssertEqual(info?.maneuverType, .right)
        XCTAssertEqual(info?.maneuverDistance, 420)
        XCTAssertEqual(published.count, 1)
        XCTAssertTrue(source.isLive)
    }

    func testPrefersStateWithNavigationMode() {
        let idle: [String: Any] = ["maneuverType": "ManeuverType_Undef", "remainingTime": 0]
        let reader = FakeLiveActivityReader([idle, state()])
        let source = LiveActivitySource(reader: reader, now: { self.fixed })
        XCTAssertEqual(source.poll()?.activeNavigationMode, "car")
    }

    func testNoActivityReportsErrorAndDiesQuietly() {
        let reader = FakeLiveActivityReader([])
        let source = LiveActivitySource(reader: reader, now: { self.fixed })

        var errors: [String] = []
        source.onError = { errors.append($0) }

        XCTAssertNil(source.poll())
        XCTAssertEqual(errors, ["нет активности 2ГИС"])
        XCTAssertFalse(source.isLive)
    }

    func testIgnoresNonNavigationActivities() {
        let reader = FakeLiveActivityReader([["title": "Погода"], state()])
        let source = LiveActivitySource(reader: reader, now: { self.fixed })
        XCTAssertEqual(source.poll()?.maneuverType, .right)
    }

    func testStopMakesSourceStale() {
        let source = LiveActivitySource(reader: FakeLiveActivityReader([state()]),
                                        interval: 1, now: { self.fixed })
        source.start()
        XCTAssertTrue(source.isLive)
        source.stop()
        // Таймер снят, но последний снимок ещё живой — это ожидаемо.
        XCTAssertTrue(source.isLive)
    }

    func testReaderIsCalledOncePerPoll() {
        let reader = FakeLiveActivityReader([state()])
        let source = LiveActivitySource(reader: reader, now: { self.fixed })
        source.poll()
        source.poll()
        XCTAssertEqual(reader.calls, 2)
    }
}

/// Источник №2 — CarPlay: только факт живой сцены, чисел наружу нет.
final class CarPlaySourceTests: XCTestCase {

    func testNoSceneNoData() {
        let source = CarPlaySource(observer: StubCarPlayObserver())
        XCTAssertNil(source.poll())
        XCTAssertFalse(source.isLive)
    }

    func testConnectedButNavigationSceneInactive() {
        let observer = StubCarPlayObserver(connected: true, navigationActive: false)
        XCTAssertNil(CarPlaySource(observer: observer).poll())
    }

    func testActiveNavigationSceneReportsModeOnly() {
        let observer = StubCarPlayObserver(connected: true, navigationActive: true)
        let source = CarPlaySource(observer: observer)

        var published: [DashboardInfo] = []
        source.onInfo = { published.append($0) }

        let info = source.poll()
        XCTAssertEqual(info?.activeNavigationMode, "carplay")
        XCTAssertEqual(info?.maneuverType, .forward)
        XCTAssertEqual(info?.maneuverDistance, -1, "чисел от CarPlay мы не получаем")
        XCTAssertTrue(info?.isNavigating == true)
        XCTAssertEqual(published.count, 1)
        XCTAssertTrue(source.isLive)
    }
}

/// Источник №3 — URL-схемы. Обратного канала (`x-callback-url`) в бинарнике нет,
/// поэтому источник односторонний.
final class URLSchemeSourceTests: XCTestCase {

    private let fixed = Date(timeIntervalSince1970: 1_700_000_000)

    func testQueryForm() throws {
        let source = URLSchemeSource(now: { self.fixed })
        let url = try XCTUnwrap(URL(string: "atenboro://nav?maneuverType=left&maneuverDistance=250&street=Мясницкая"))

        let info = try XCTUnwrap(source.handle(url: url))
        XCTAssertEqual(info.maneuverType, .left)
        XCTAssertEqual(info.maneuverDistance, 250)
        XCTAssertEqual(info.street, "Мясницкая")
        XCTAssertEqual(info.timestamp, self.fixed)
        XCTAssertTrue(source.isLive)
    }

    func testPathFormOfManeuver() throws {
        let source = URLSchemeSource(now: { self.fixed })
        let url = try XCTUnwrap(URL(string: "atenboro://turnover"))
        XCTAssertEqual(source.handle(url: url)?.maneuverType, .turnover)
    }

    func testForeignSchemeIsIgnored() throws {
        let source = URLSchemeSource()
        let url = try XCTUnwrap(URL(string: "dgis://2gis.ru/routeSearch"))
        XCTAssertNil(source.handle(url: url))
    }

    func testStopClearsLiveness() throws {
        let source = URLSchemeSource(ttl: 15, now: { self.fixed })
        source.handle(url: try XCTUnwrap(URL(string: "atenboro://nav?maneuverType=right")))
        XCTAssertTrue(source.isLive)
        source.stop()
        XCTAssertFalse(source.isLive)
    }

    func testTtlExpires() {
        var current = Date(timeIntervalSince1970: 1_000)
        let source = URLSchemeSource(ttl: 15, now: { current })
        source.handle(url: URL(string: "atenboro://nav?maneuverType=right")!)
        current = current.addingTimeInterval(20)
        XCTAssertFalse(source.isLive)
    }

    func testGisRouteURL() throws {
        let url = try XCTUnwrap(URLSchemeSource.gisRouteURL(from: "Дом", to: "Работа"))
        XCTAssertEqual(url.scheme, "dgis")
        XCTAssertTrue(url.absoluteString.hasPrefix("dgis://2gis.ru/routeSearch/to/"))
        XCTAssertTrue(url.absoluteString.contains("/go?from="))
    }
}

/// Источник №4 — демо-маршрут для симулятора и превью.
final class MockRouteSourceTests: XCTestCase {

    func testDefaultFramesCoverInterestingCases() {
        let frames = MockRouteSource.defaultFrames
        XCTAssertEqual(frames.count, 4)
        XCTAssertTrue(frames.allSatisfy { $0.isNavigating })
        XCTAssertEqual(frames.map(\.maneuverType), [.right, .slightlyLeft, .turnover, .forward])
        XCTAssertTrue(frames.contains { $0.cameraPresent })
        XCTAssertTrue(frames.contains { $0.trafficLightColor == "red" })
        XCTAssertTrue(frames.contains { ($0.street ?? "").isEmpty == false })
    }

    func testAdvanceCyclesFrames() {
        let source = MockRouteSource()
        var emitted: [DashboardInfo] = []
        source.onInfo = { emitted.append($0) }

        for _ in 0..<5 { source.advance() }

        XCTAssertEqual(emitted.count, 5)
        XCTAssertEqual(emitted[4].maneuverType, emitted[0].maneuverType, "кадры зациклены")
    }

    func testEmptyFramesFallBackToDefaults() {
        let source = MockRouteSource(frames: [])
        XCTAssertEqual(source.advance().maneuverType, .right)
    }

    func testFramesGetFreshTimestamp() {
        let fixed = Date(timeIntervalSince1970: 500)
        let source = MockRouteSource(now: { fixed })
        XCTAssertEqual(source.advance().timestamp, fixed)
    }
}
