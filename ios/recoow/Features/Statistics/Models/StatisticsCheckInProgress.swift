import Foundation

struct StatisticsCheckInProgress: Identifiable, Hashable {
    let id: String
    let title: String
    let completedDays: Int
    let totalDays: Int?
    let progressFraction: Double?
    let detailText: String
    let isCompleted: Bool
}
