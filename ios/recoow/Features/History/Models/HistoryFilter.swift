import Foundation

struct HistoryFilter: Identifiable, Equatable {
    let id: UUID
    let route: ToolRoute?
    let dateInterval: DateInterval?
    let titleKey: String

    init(
        id: UUID = UUID(),
        route: ToolRoute?,
        dateInterval: DateInterval?,
        titleKey: String
    ) {
        self.id = id
        self.route = route
        self.dateInterval = dateInterval
        self.titleKey = titleKey
    }
}
