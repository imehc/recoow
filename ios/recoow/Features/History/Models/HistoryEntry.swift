import Foundation

enum HistoryEntry: Identifiable, Hashable {
    case track(Track)
    case decisionChoice(DecisionChoiceRecord)

    var id: String {
        switch self {
        case .track(let track):
            "track:\(track.id)"
        case .decisionChoice(let record):
            "decisionChoice:\(record.id)"
        }
    }

    var timestamp: Int64 {
        switch self {
        case .track(let track):
            track.startedAt
        case .decisionChoice(let record):
            record.selectedAt
        }
    }
}
