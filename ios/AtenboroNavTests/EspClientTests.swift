import XCTest
@testable import AtenboroNav

/// Отправка снапшота на ESP8266. Плата подменяется памятью,
/// поэтому тесты не требуют ни Wi-Fi, ни прошивки.
final class EspClientTests: XCTestCase {

    private func update(_ turn: String = "left", meters: Int = 250) -> NavUpdate {
        NavUpdate(turn: turn, distM: meters, ts: Date(timeIntervalSince1970: 1_700_000_000))
    }

    func testDefaultsMatchAndroidContract() async {
        let client = EspClient()
        let host = await client.currentHost()
        let port = await client.currentPort()
        XCTAssertEqual(host, "192.168.4.1")
        XCTAssertEqual(port, 18_765)
    }

    func testSendDeliversPayload() async {
        let board = MemoryTransport()
        let client = EspClient(host: "10.0.0.5", port: 9000, transport: board)

        let ok = await client.send(update())
        XCTAssertTrue(ok)
        XCTAssertEqual(board.payloads.count, 1)
        XCTAssertTrue(board.lastString?.contains("\"turn\":\"left\"") ?? false)
        XCTAssertTrue(board.lastString?.contains("\"dist_m\":250") ?? false)

        let state = await client.state
        XCTAssertEqual(state, .online)
        let sent = await client.sentCount
        XCTAssertEqual(sent, 1)
    }

    func testSendFailureIsReportedNotThrown() async {
        let board = MemoryTransport()
        board.failNext = NavSourceError.transport("плата не отвечает")
        let client = EspClient(transport: board)

        let ok = await client.send(update())
        XCTAssertFalse(ok)

        let state = await client.state
        if case .failed(let message) = state {
            XCTAssertTrue(message.contains("плата"))
        } else {
            XCTFail("ожидался .failed, получено \(state)")
        }
        let sent = await client.sentCount
        XCTAssertEqual(sent, 0)
    }

    func testDisconnectedBoardFails() async {
        let board = MemoryTransport(connected: false)
        let client = EspClient(transport: board)
        let sent = await client.send(update())
        XCTAssertFalse(sent)
    }

    func testProbeReflectsTransport() async {
        let online = await EspClient(transport: MemoryTransport()).probe()
        XCTAssertTrue(online)
        let offline = await EspClient(transport: MemoryTransport(connected: false)).probe()
        XCTAssertFalse(offline)
        let noTransport = await EspClient().probe()
        XCTAssertFalse(noTransport, "без транспорта probe не работает")
    }

    func testResetClearsError() async {
        let board = MemoryTransport()
        board.failNext = NavSourceError.transport("таймаут")
        let client = EspClient(transport: board)
        _ = await client.send(update())
        await client.reset()
        let state = await client.state
        XCTAssertEqual(state, .idle)
        let error = await client.lastError
        XCTAssertNil(error)
    }

    func testSuccessClearsPreviousError() async {
        let board = MemoryTransport()
        board.failNext = NavSourceError.transport("таймаут")
        let client = EspClient(transport: board)
        _ = await client.send(update())
        let retry = await client.send(update())
        XCTAssertTrue(retry)
        let error = await client.lastError
        XCTAssertNil(error)
    }
}
