import OSLog

/// 统一管理 OSLog 分类，后续模块追加 category 即可。
enum AppLogger {
    nonisolated private static let subsystem = Bundle.main.bundleIdentifier ?? "recoow"

    nonisolated static let database = Logger(subsystem: subsystem, category: "database")
    nonisolated static let sync = Logger(subsystem: subsystem, category: "sync")
    nonisolated static let location = Logger(subsystem: subsystem, category: "location")
    nonisolated static let ui = Logger(subsystem: subsystem, category: "ui")
}
