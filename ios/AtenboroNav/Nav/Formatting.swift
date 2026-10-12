import Foundation

/// Форматирование значений для HUD и карточек. Чистые функции — чтобы
/// их можно было тестировать без UI и без часов.
public enum Formatting {

    /// Метры → «420 м» / «1,2 км». Отрицательное значение — нет данных.
    public static func distance(_ meters: Int, locale: Locale = .current) -> String {
        guard meters >= 0 else { return "—" }
        if meters < 1000 {
            return "\(meters) м"
        }
        let km = Double(meters) / 1000.0
        let formatter = NumberFormatter()
        formatter.locale = locale
        formatter.numberStyle = .decimal
        formatter.minimumFractionDigits = 1
        formatter.maximumFractionDigits = 1
        let text = formatter.string(from: NSNumber(value: km)) ?? String(format: "%.1f", km)
        return "\(text) км"
    }

    /// Секунды → «12 мин» / «1 ч 05 мин».
    public static func duration(_ seconds: Int) -> String {
        guard seconds >= 0 else { return "—" }
        let minutes = Int((Double(seconds) / 60.0).rounded())
        if minutes < 60 { return "\(minutes) мин" }
        let hours = minutes / 60
        let rest = minutes % 60
        return String(format: "%d ч %02d мин", hours, rest)
    }

    /// Время прибытия из «оставшегося времени» — «прибытие в 14:35».
    public static func arrival(remainingSeconds: Int, from date: Date = Date(),
                               calendar: Calendar = .current) -> String {
        guard remainingSeconds >= 0 else { return "—" }
        let arrival = date.addingTimeInterval(TimeInterval(remainingSeconds))
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "ru_RU")
        formatter.dateFormat = "HH:mm"
        return "в \(formatter.string(from: arrival))"
    }

    /// Скорость: «54 км/ч».
    public static func speed(_ kmh: Int) -> String {
        guard kmh >= 0 else { return "—" }
        return "\(kmh) км/ч"
    }

    /// Прогресс маршрута: «41 %», вне диапазона — прочерк.
    public static func progress(_ percent: Int) -> String {
        guard (0...100).contains(percent) else { return "—" }
        return "\(percent) %"
    }

    /// Пробка: «7 мин» или прочерк, если пробок нет.
    public static func jam(_ minutes: Int) -> String {
        guard minutes > 0 else { return "без пробок" }
        return duration(minutes * 60)
    }

    /// Секунды до светофора: «12 с».
    public static func trafficLight(_ seconds: Int) -> String {
        guard seconds >= 0 else { return "—" }
        return "\(seconds) с"
    }

    /// Возраст данных: «только что» / «12 с назад» / «3 мин назад».
    public static func age(_ seconds: TimeInterval) -> String {
        if seconds < 2 { return "только что" }
        if seconds < 60 { return "\(Int(seconds)) с назад" }
        return "\(Int(seconds / 60)) мин назад"
    }
}
