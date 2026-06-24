import Foundation

enum HistoryDetailRoute: Hashable {
    case track(String)
    case decisionChoice(String)
    case storedItem(String)
    case reminder(String)
    case bill(String)
    case foodDay(Date)
    case diary(String)
    case anniversary(String)
}
