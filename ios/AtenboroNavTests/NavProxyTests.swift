import XCTest
@testable import AtenboroNav

/// Фейковый читатель для интеграционных тестов (живёт в этом файле).
private final class StubReader: LiveActivityReading {
    var states: [[String: Any]]

    init(_ states: [[String: Any]]) {
        self.states = states
    }

    func snapshots() -> [[String: Any]] { states }
}

/// Сквозной путь «источник → шина → плата». Именно это происходит
/// в живом приложении, только вместо ActivityKit — подставной читатель.
final class NavProxyTests: XCTestCase {

    private var suiteName: String!
    private var defaults: UserDefaults!
    private var prefs: AppPrefs!
    private var board: MemoryTransport!
    private var reader: StubReader!
    private var proxy: NavProxy!

    override func setUp() {
        super.setUp()
        suiteName = "proxy.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        prefs = AppPrefs(defaults: defaults)

        reader = StubReader([[
            "activeNavigationMode": "car",
            "maneuverType": "ManeuverType_Right",
            "maneuverDistance": 420,
            "remainingTime": 1_320,
            "progress": 12
        ]])
        board = MemoryTransport()

        proxy = NavProxy(
            prefs: prefs,
            liveActivity: LiveActivitySource(reader: reader, interval: 3_600),
            carPlay: CarPlaySource(observer: StubCarPlayObserver(), interval: 3_600),
            urlScheme: URLSchemeSource(),
            mock: MockRouteSource(interval: 3_600),
            esp: EspClient(transport: board)
        )
    }

    override func tearDown() {
        proxy.stop()
        defaults.removePersistentDomain(forName: suiteName)
        proxy = nil
        board = nil
        reader = nil
        prefs = nil
        defaults = nil
        suiteName = nil
        super.tearDown()
    }

    /// Ждём, пока асинхронная отправка на плату завершится.
    private func waitForBoardPayloads(_ count: Int, timeout: TimeInterval = 2) {
        let deadline = Date().addingTimeInterval(timeout)
        while board.payloads.count < count && Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.02))
        }
    }

    func testStartReadsLiveActivityAndPushesToBoard() {
        proxy.start()
        waitForBoardPayloads(1)

        XCTAssertEqual(proxy.state, .running)
        XCTAssertEqual(proxy.bus.activeSource, .liveActivity)
        XCTAssertEqual(proxy.bus.lastUpdate?.turn, "right")
        XCTAssertEqual(proxy.bus.lastUpdate?.distM, 420)
        XCTAssertEqual(board.payloads.count, 1)
    }

    func testStartIsIdempotent() {
        proxy.start()
        proxy.start()
        XCTAssertEqual(proxy.state, .running)
    }

    func testStoppedProxyDoesNotForward() {
        XCTAssertEqual(proxy.state, .stopped)
        _ = proxy.liveActivity.poll()
        XCTAssertNotNil(proxy.bus.lastUpdate, "шина всё равно принимает данные")
        XCTAssertTrue(board.payloads.isEmpty, "а прошивка в выключенном состоянии не кормится")
    }

    func testStopSilencesSources() {
        proxy.start()
        waitForBoardPayloads(1)
        proxy.stop()
        XCTAssertEqual(proxy.state, .stopped)
        XCTAssertFalse(proxy.carPlay.isLive)
    }

    func testDisabledSecondarySourceIsNotStarted() {
        XCTAssertFalse(prefs.isEnabled(.mock))
        proxy.start()
        XCTAssertFalse(proxy.mock.isLive)
    }

    func testEnablingMockStartsItOnTheFly() {
        proxy.start()
        prefs.setEnabled(true, for: .mock)
        proxy.applySourceSettings()
        XCTAssertTrue(proxy.mock.isLive)

        prefs.setEnabled(false, for: .mock)
        proxy.applySourceSettings()
        XCTAssertFalse(proxy.mock.isLive)
    }

    func testSettingsDoNotApplyWhileStopped() {
        prefs.setEnabled(true, for: .mock)
        proxy.applySourceSettings()
        XCTAssertFalse(proxy.mock.isLive, "пока прокси не запущен, настройки не запускают источники")
    }

    func testURLSchemeIsAcceptedOnlyWhenEnabled() {
        let url = URL(string: "atenboro://nav?maneuverType=left&maneuverDistance=100")!

        // Источник выключен по умолчанию — публикация не проходит.
        XCTAssertFalse(proxy.handle(url: url))
        XCTAssertNil(proxy.bus.lastUpdate)

        prefs.setEnabled(true, for: .urlScheme)
        XCTAssertTrue(proxy.handle(url: url))
        XCTAssertEqual(proxy.bus.lastUpdate?.turn, "left")
    }

    func testPushToBoardWithoutDataReturnsFalse() async {
        let ok = await proxy.pushToBoard()
        XCTAssertFalse(ok)
    }

    func testPushToBoardSendsCurrentSnapshot() async {
        proxy.start()
        let ok = await proxy.pushToBoard()
        XCTAssertTrue(ok)
        let state = await proxy.boardState()
        XCTAssertEqual(state, .online)
    }

    func testLiveActivityErrorReachesBusStatus() {
        reader.states = []
        proxy.start()
        XCTAssertEqual(proxy.bus.lastError, "нет активности 2ГИС")
    }
}
