import Foundation

struct StatisticsFeatureSummary: Identifiable, Hashable {
    let route: ToolRoute
    let count: Int
    let todayCount: Int
    let latestDate: Date?

    var id: String { route.rawValue }
}
