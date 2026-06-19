import Foundation

/// 距离、时长和速度格式化集中管理，避免各页面展示口径不一致。
enum AppFormatters {
    static func distance(_ meters: Double) -> String {
        if meters >= 1000 {
            return String(format: "%.2f km", meters / 1000)
        }
        return String(format: "%.0f m", meters)
    }

    static func duration(_ seconds: Int64) -> String {
        let hours = seconds / 3600
        let minutes = (seconds % 3600) / 60
        let remainingSeconds = seconds % 60

        if hours > 0 {
            return AppLocalization.format("%lld小时 %lld分钟", hours, minutes)
        }

        if minutes > 0 {
            return AppLocalization.format("%lld分钟 %lld秒", minutes, remainingSeconds)
        }

        return AppLocalization.format("%lld秒", remainingSeconds)
    }

    static func speed(_ metersPerSecond: Double?) -> String {
        guard let metersPerSecond else { return "--" }
        return String(format: "%.2f m/s", metersPerSecond)
    }

    static func coordinate(latitude: Double, longitude: Double) -> String {
        String(format: "%.6f, %.6f", latitude, longitude)
    }

    static func dateTime(milliseconds: Int64, locale: Locale = AppLocalization.currentLocale) -> String {
        let date = Date(timeIntervalSince1970: Double(milliseconds) / 1000)
        return date.formatted(
            Date.FormatStyle(date: .abbreviated, time: .shortened)
                .locale(locale)
        )
    }

    static func date(milliseconds: Int64, locale: Locale = AppLocalization.currentLocale) -> String {
        let date = Date(timeIntervalSince1970: Double(milliseconds) / 1000)
        return date.formatted(
            Date.FormatStyle(date: .abbreviated, time: .omitted)
                .locale(locale)
        )
    }

    static func money(cents: Int64) -> String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .currency
        formatter.locale = AppLocalization.currentLocale
        formatter.currencyCode = Locale.autoupdatingCurrent.currency?.identifier ?? "CNY"
        formatter.maximumFractionDigits = 2
        formatter.minimumFractionDigits = 2

        let amount = Decimal(cents) / Decimal(100)
        return formatter.string(from: amount as NSDecimalNumber) ?? String(format: "%.2f", Double(cents) / 100)
    }

    static func amountInput(cents: Int64) -> String {
        String(format: "%.2f", Double(cents) / 100)
    }

    static func cents(from amountText: String) -> Int64? {
        let sanitized = amountText
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: ",", with: "")
            .replacingOccurrences(of: "¥", with: "")

        guard sanitized.isEmpty == false,
              let amount = Decimal(string: sanitized),
              amount >= 0
        else {
            return nil
        }

        let cents = amount * Decimal(100)
        var rounded = Decimal()
        var source = cents
        NSDecimalRound(&rounded, &source, 0, .plain)
        return NSDecimalNumber(decimal: rounded).int64Value
    }

    static func sampleCount(_ count: Int) -> String {
        AppLocalization.format("%d 个采样点", count)
    }
}
