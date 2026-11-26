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

    static func time(milliseconds: Int64, locale: Locale = AppLocalization.currentLocale) -> String {
        let date = Date(timeIntervalSince1970: Double(milliseconds) / 1000)
        return date.formatted(
            Date.FormatStyle(date: .omitted, time: .shortened)
                .locale(locale)
        )
    }

    /// 服务端时间统一按 RFC 3339（UTC）返回，展示时换成用户所选语言的本地时间。
    /// 解析失败返回 nil，由调用方保留原始文本，避免把未识别的格式静默改写成错误时间。
    static func dateTime(iso8601 value: String, locale: Locale = AppLocalization.currentLocale) -> String? {
        guard let date = iso8601Date(value) else { return nil }
        return date.formatted(
            Date.FormatStyle(date: .abbreviated, time: .shortened)
                .locale(locale)
        )
    }

    /// 秒级与带小数秒两种写法并存：标准样式不接受小数秒，因此在第二次解析时显式开启。
    private static func iso8601Date(_ value: String) -> Date? {
        if let date = try? Date(value, strategy: .iso8601) { return date }
        return try? Date(value, strategy: Date.ISO8601FormatStyle(includingFractionalSeconds: true))
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
