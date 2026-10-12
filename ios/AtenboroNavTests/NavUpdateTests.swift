import XCTest
@testable import AtenboroNav

/// Снапшот для прошивки: имена ключей обязаны совпадать с Android-версией,
/// иначе ESP8266 придётся различать платформы.
final class NavUpdateTests: XCTestCase {

    private func makeInfo() -> DashboardInfo {
        var info = DashboardInfo(activeNavigationMode: "car", maneuverType: .slightlyLeft,
                                 maneuverDistance: 180)
        info.cameraPresent = true
        info.cameraDistance = 90
        info.cameraSpeedLimit = 40
        info.cameraDistancePercent = 65
        info.jamInfoDuration = 7
        info.trafficLightColor = "red"
        info.trafficLightCountdown = 12
        info.progress = 41
        info.street = "Тверская"
        info.timestamp = Date(timeIntervalSince1970: 1_700_000_000)
        return info
    }

    func testBridgeFromDashboardInfo() {
        let update = NavUpdate(info: makeInfo(), source: SourceID.liveActivity.rawValue)
        XCTAssertEqual(update.turn, "slight_left")
        XCTAssertEqual(update.maneuverType, .slightlyLeft)
        XCTAssertEqual(update.distM, 180)
        XCTAssertTrue(update.camera)
        XCTAssertEqual(update.camM, 90)
        XCTAssertEqual(update.camKmh, 40)
        XCTAssertEqual(update.camPct, 65)
        XCTAssertEqual(update.navMode, "car")
        XCTAssertEqual(update.progress, 41)
        XCTAssertEqual(update.trafficLightColor, "red")
        XCTAssertEqual(update.trafficLightCountdown, 12)
        XCTAssertEqual(update.jamMin, 7)
        XCTAssertEqual(update.street, "Тверская")
        XCTAssertTrue(update.navigating)
        XCTAssertEqual(update.source, "liveActivity")
    }

    func testJSONKeyNamesMatchAndroid() throws {
        let update = NavUpdate(info: makeInfo(), source: "liveActivity")
        let raw = try XCTUnwrap(
            JSONSerialization.jsonObject(with: update.toJSONData()) as? [String: Any]
        )
        XCTAssertEqual(raw["turn"] as? String, "slight_left")
        XCTAssertEqual(raw["dist_m"] as? Int, 180)
        XCTAssertEqual(raw["camera"] as? Bool, true)
        XCTAssertEqual(raw["cam_m"] as? Int, 90)
        XCTAssertEqual(raw["cam_kmh"] as? Int, 40)
        XCTAssertEqual(raw["cam_pct"] as? Int, 65)
        XCTAssertEqual(raw["nav_mode"] as? String, "car")
        XCTAssertEqual(raw["progress"] as? Int, 41)
        XCTAssertEqual(raw["tl"] as? String, "red")
        XCTAssertEqual(raw["tl_s"] as? Int, 12)
        XCTAssertEqual(raw["jam_min"] as? Int, 7)
        XCTAssertEqual(raw["street"] as? String, "Тверская")
        XCTAssertEqual(raw["ts"] as? Int, 1_700_000_000)
    }

    /// Пустые и «нет данных» значения в JSON не едут — прошивка экономит байты.
    func testJSONOmitsAbsentValues() throws {
        let update = NavUpdate(turn: "none", ts: Date(timeIntervalSince1970: 1))
        let raw = try XCTUnwrap(
            JSONSerialization.jsonObject(with: update.toJSONData()) as? [String: Any]
        )
        XCTAssertEqual(raw["turn"] as? String, "none")
        XCTAssertNil(raw["maneuver_type"])
        XCTAssertNil(raw["cam_pct"])
        XCTAssertNil(raw["nav_mode"])
        XCTAssertNil(raw["progress"])
        XCTAssertNil(raw["tl"])
        XCTAssertNil(raw["tl_s"])
        XCTAssertNil(raw["jam_min"])
        XCTAssertNil(raw["street"])
        XCTAssertNil(raw["source"])
    }

    func testUndefManeuverStillProducesValidArrowToken() {
        let update = NavUpdate(info: DashboardInfo(), source: "mock")
        XCTAssertEqual(update.turn, "none")
        XCTAssertFalse(update.navigating)
    }
}
