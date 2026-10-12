import XCTest
@testable import AtenboroNav

/// Форматирование значений для карточек — чистые функции, без часов и UI.
final class FormattingTests: XCTestCase {

    private let ru = Locale(identifier: "ru_RU")

    func testDistanceInMeters() {
        XCTAssertEqual(Formatting.distance(0), "0 м")
        XCTAssertEqual(Formatting.distance(420), "420 м")
        XCTAssertEqual(Formatting.distance(999), "999 м")
    }

    func testDistanceInKilometers() {
        XCTAssertEqual(Formatting.distance(1000, locale: ru), "1,0 км")
        XCTAssertEqual(Formatting.distance(1234, locale: ru), "1,2 км")
        XCTAssertEqual(Formatting.distance(12_500, locale: ru), "12,5 км")
    }

    func testDistanceWithoutData() {
        XCTAssertEqual(Formatting.distance(-1), "—")
    }

    func testDuration() {
        XCTAssertEqual(Formatting.duration(0), "0 мин")
        XCTAssertEqual(Formatting.duration(59), "1 мин")
        XCTAssertEqual(Formatting.duration(720), "12 мин")
        XCTAssertEqual(Formatting.duration(3_600), "1 ч 00 мин")
        XCTAssertEqual(Formatting.duration(4_525), "1 ч 15 мин")
        XCTAssertEqual(Formatting.duration(-1), "—")
    }

    func testArrivalTime() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Moscow")!
        let base = calendar.date(from: DateComponents(year: 2026, month: 10, day: 11,
                                                     hour: 14, minute: 20))!
        XCTAssertEqual(Formatting.arrival(remainingSeconds: 900, from: base, calendar: calendar),
                       "в 14:35")
        XCTAssertEqual(Formatting.arrival(remainingSeconds: -1, from: base, calendar: calendar), "—")
    }

    func testSpeedProgressAndJam() {
        XCTAssertEqual(Formatting.speed(54), "54 км/ч")
        XCTAssertEqual(Formatting.speed(-1), "—")
        XCTAssertEqual(Formatting.progress(41), "41 %")
        XCTAssertEqual(Formatting.progress(-1), "—")
        XCTAssertEqual(Formatting.progress(101), "—")
        XCTAssertEqual(Formatting.jam(0), "без пробок")
        XCTAssertEqual(Formatting.jam(-1), "без пробок")
        XCTAssertEqual(Formatting.jam(7), "7 мин")
        XCTAssertEqual(Formatting.trafficLight(12), "12 с")
        XCTAssertEqual(Formatting.trafficLight(-1), "—")
    }

    func testAge() {
        XCTAssertEqual(Formatting.age(0.4), "только что")
        XCTAssertEqual(Formatting.age(12), "12 с назад")
        XCTAssertEqual(Formatting.age(185), "3 мин назад")
    }
}
