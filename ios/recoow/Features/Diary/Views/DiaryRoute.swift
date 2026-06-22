import Foundation

struct DiaryRoute: Hashable {
    let id: String
}

extension HistoryDetailRoute {
    init?(diaryLink link: DiaryLink) {
        switch link.type {
        case .track:
            self = .track(link.sourceID)
        case .bill:
            self = .bill(link.sourceID)
        case .reminder:
            self = .reminder(link.sourceID)
        case .anniversary:
            self = .anniversary(link.sourceID)
        case .storedItem:
            self = .storedItem(link.sourceID)
        case .decisionChoice:
            self = .decisionChoice(link.sourceID)
        }
    }
}
