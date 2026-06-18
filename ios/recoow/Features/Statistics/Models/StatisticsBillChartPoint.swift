import Foundation

struct StatisticsBillChartPoint: Identifiable, Hashable {
    let id: String
    let label: String
    let totalCents: Int64
    let count: Int
}
