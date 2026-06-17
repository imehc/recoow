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
            return AppLocalization.format("duration.hours.minutes", hours, minutes)
        }

        if minutes > 0 {
            return AppLocalization.format("duration.minutes.seconds", minutes, remainingSeconds)
        }

        return AppLocalization.format("duration.seconds", remainingSeconds)
    }

    static func speed(_ metersPerSecond: Double?) -> String {
        guard let metersPerSecond else { return "--" }
        return String(format: "%.2f m/s", metersPerSecond)
    }

    static func coordinate(latitude: Double, longitude: Double) -> String {
        String(format: "%.6f, %.6f", latitude, longitude)
    }

    static func dateTime(milliseconds: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(milliseconds) / 1000)
        return date.formatted(
            Date.FormatStyle(date: .abbreviated, time: .shortened)
                .locale(AppLocalization.currentLocale)
        )
    }

    static func sampleCount(_ count: Int) -> String {
        AppLocalization.format("sample.count", count)
    }
}
