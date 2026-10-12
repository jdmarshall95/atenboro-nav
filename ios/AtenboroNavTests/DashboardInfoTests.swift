import XCTest
@testable import AtenboroNav

/// Разбор и отдача «дампа» навигации. Ключи совпадают с Android AIDL,
/// поэтому прошивке всё равно, с какой платформы прилетели данные.
final class DashboardInfoTests: XCTestCase {

    func testEmptyInfoIsNotNavigating() {
        XCTAssertFalse(DashboardInfo().isNavigating)
    }

    func testNavigatingRequiresModeAndManeuver() {
        var info = DashboardInfo()
        info.activeNavigationMode = "car"
        XCTAssertFalse(info.isNavigating, "режим есть, манёвра нет — навигация ещё не поехала")

        info.maneuverType = .right
        XCTAssertTrue(info.isNavigating)

        info.maneuverType = .undef
        XCTAssertFalse(info.isNavigating)
    }

    func testDecodeFromJSONWithNumbers() throws {
        let json = """
        {"activeNavigationMode":"car","maneuverType":"ManeuverType_Left",
         "maneuverDistance":250,"cameraPresent":true,"cameraDistance":90,
         "cameraSpeedLimit":40,"cameraDistancePercent":65,
         "trafficLightColor":"red","trafficLightCountdown":12,
         "progress":41,"remainingTime":780,"street":"Тверская","ts":1700000000}
        """
        let info = try XCTUnwrap(DashboardInfo.decode(from: Data(json.utf8)))
        XCTAssertEqual(info.activeNavigationMode, "car")
        XCTAssertEqual(info.maneuverType, .left)
        XCTAssertEqual(info.maneuverDistance, 250)
        XCTAssertTrue(info.cameraPresent)
        XCTAssertEqual(info.cameraDistance, 90)
        XCTAssertEqual(info.cameraSpeedLimit, 40)
        XCTAssertEqual(info.cameraDistancePercent, 65)
        XCTAssertEqual(info.trafficLightColor, "red")
        XCTAssertEqual(info.trafficLightCountdown, 12)
        XCTAssertEqual(info.progress, 41)
        XCTAssertEqual(info.remainingTime, 780)
        XCTAssertEqual(info.street, "Тверская")
        XCTAssertEqual(info.timestamp.timeIntervalSince1970, 1_700_000_000, accuracy: 1)
    }

    /// Content-state Live Activity может приходить строками вместо чисел —
    /// ни один такой вариант не должен обрывать поток.
    func testDecodeAcceptsStringsAndNulls() {
        let raw: [String: Any] = [
            "activeNavigationMode": "motorcycle",
            "maneuverType": "slightlyRight",
            "maneuverDistance": "120",
            "cameraPresent": "true",
            "cameraDistance": NSNull(),
            "remainingTime": 60.0,
            "badLocation": "no"
        ]
        let info = DashboardInfo.decode(from: raw)
        XCTAssertEqual(info.activeNavigationMode, "motorcycle")
        XCTAssertEqual(info.maneuverType, .slightlyRight)
        XCTAssertEqual(info.maneuverDistance, 120)
        XCTAssertTrue(info.cameraPresent)
        XCTAssertEqual(info.cameraDistance, -1, "null = нет данных")
        XCTAssertEqual(info.remainingTime, 60)
        XCTAssertFalse(info.badLocation)
    }

    func testDecodeOfMissingKeysYieldsNoDataSentinels() {
        let info = DashboardInfo.decode(from: [String: Any]())
        XCTAssertEqual(info.maneuverDistance, -1)
        XCTAssertEqual(info.speedLimit, -1)
        XCTAssertEqual(info.trafficLightColor, "")
        XCTAssertNil(info.street)
        XCTAssertNil(info.arrivalTime)
    }

    func testDecodeOfGarbageDataReturnsNil() {
        XCTAssertNil(DashboardInfo.decode(from: Data("не json".utf8)))
    }

    func testRoundTripKeepsKeyFields() throws {
        var info = DashboardInfo(activeNavigationMode: "car", maneuverType: .turnover,
                                 maneuverDistance: 60, cameraPresent: true, cameraDistance: 15,
                                 cameraSpeedLimit: 80, trafficLightColor: "green",
                                 trafficLightCountdown: 7, progress: 78, remainingTime: 240,
                                 street: "Мясницкая")
        info.timestamp = Date(timeIntervalSince1970: 1_700_000_100)

        let restored = try XCTUnwrap(DashboardInfo.decode(from: info.toJSONData()))
        XCTAssertEqual(restored.activeNavigationMode, info.activeNavigationMode)
        XCTAssertEqual(restored.maneuverType, .turnover)
        XCTAssertEqual(restored.maneuverDistance, 60)
        XCTAssertEqual(restored.cameraDistance, 15)
        XCTAssertEqual(restored.trafficLightColor, "green")
        XCTAssertEqual(restored.progress, 78)
        XCTAssertEqual(restored.street, "Мясницкая")
        XCTAssertEqual(restored.timestamp.timeIntervalSince1970,
                       info.timestamp.timeIntervalSince1970, accuracy: 1)
    }

    func testJSONUsesAndroidKeyNames() throws {
        let data = DashboardInfo(activeNavigationMode: "car", maneuverType: .right,
                                 maneuverDistance: 420).toJSONData()
        let raw = try XCTUnwrap(
            JSONSerialization.jsonObject(with: data) as? [String: Any]
        )
        for key in ["activeNavigationMode", "maneuverType", "maneuverDistance",
                    "cameraDistancePercent", "trafficLightCountdown", "remainingTime", "ts"] {
            XCTAssertNotNil(raw[key], "пропал ключ \(key)")
        }
    }
}
