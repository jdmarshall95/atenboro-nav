import XCTest
@testable import AtenboroNav

/// Словарь манёвров: 10 значений `ManeuverType_*` из бинарника 2ГИС 7.29
/// и их соответствие токенам стрелок прошивки.
final class ManeuverTypeTests: XCTestCase {

    func testVocabularyMatchesReverseEngineeringNotes() {
        XCTAssertEqual(ManeuverType.allCases.count, 10)
        XCTAssertEqual(ManeuverType.allCases.map(\.rawGisName), [
            "ManeuverType_Undef",
            "ManeuverType_Forward",
            "ManeuverType_Left",
            "ManeuverType_Right",
            "ManeuverType_SlightlyLeft",
            "ManeuverType_SlightlyRight",
            "ManeuverType_SharplyLeft",
            "ManeuverType_SharplyRight",
            "ManeuverType_Turnover",
            "ManeuverType_RightWithLeftTurn"
        ])
    }

    func testGisCodeIsStable() {
        XCTAssertEqual(ManeuverType.undef.gisCode, 0)
        XCTAssertEqual(ManeuverType.right.gisCode, 3)
        XCTAssertEqual(ManeuverType.rightWithLeftTurn.gisCode, 9)
    }

    func testHudTokensAreUnderstoodByFirmwareParser() {
        let known: Set<String> = [
            "none", "straight", "left", "right",
            "slight_left", "slight_right", "u_turn", "roundabout", "arrive"
        ]
        for type in ManeuverType.allCases {
            XCTAssertTrue(known.contains(type.hudToken),
                          "\(type.rawValue) → \(type.hudToken) вне словаря прошивки")
        }
    }

    func testParseAcceptsBinaryNamesShortNamesAndJSONKeys() {
        XCTAssertEqual(ManeuverType.parse("ManeuverType_Left"), .left)
        XCTAssertEqual(ManeuverType.parse("left"), .left)
        XCTAssertEqual(ManeuverType.parse("slightlyLeft"), .slightlyLeft)
        XCTAssertEqual(ManeuverType.parse("ManeuverType_Sharply_Right"), .sharplyRight)
        XCTAssertEqual(ManeuverType.parse("uturn"), .turnover)
        XCTAssertEqual(ManeuverType.parse("straight"), .forward)
    }

    func testParseIsTolerantToUnknownValues() {
        XCTAssertEqual(ManeuverType.parse(nil), .undef)
        XCTAssertEqual(ManeuverType.parse(""), .undef)
        XCTAssertEqual(ManeuverType.parse("ManeuverType_Jetpack"), .undef)
    }

    func testFromAndroidCodename() {
        XCTAssertEqual(ManeuverType.fromAndroidCodename("crossroad_left"), .left)
        XCTAssertEqual(ManeuverType.fromAndroidCodename("crossroad_slightly_right"), .slightlyRight)
        XCTAssertEqual(ManeuverType.fromAndroidCodename("crossroad_sharply_left"), .sharplyLeft)
        XCTAssertEqual(ManeuverType.fromAndroidCodename("crossroad_uturn"), .turnover)
        XCTAssertEqual(ManeuverType.fromAndroidCodename("turn_over"), .turnover)
        XCTAssertEqual(ManeuverType.fromAndroidCodename("crossroad_straight"), .forward)
        // Кольцо на iOS сворачивается в «прямо»: отдельной стрелки кольца в 10 значениях нет.
        XCTAssertEqual(ManeuverType.fromAndroidCodename("ringroad_right_90"), .forward)
        XCTAssertEqual(ManeuverType.fromAndroidCodename("left_ring_exit"), .forward)
        XCTAssertEqual(ManeuverType.fromAndroidCodename("finish"), .forward)
        XCTAssertEqual(ManeuverType.fromAndroidCodename("stairs_up"), .undef)
    }

    func testDescriptionsArePresentForEveryCase() {
        for type in ManeuverType.allCases {
            XCTAssertFalse(type.descriptionRu.isEmpty)
        }
    }
}
