import SwiftUI

struct ToolDestinationView: View {
    let route: ToolRoute

    var body: some View {
        Group {
            switch route {
            case .locationTracker:
                LocationTrackerView()
            case .decisionMaker:
                DecisionCollectionsView()
            case .itemLocator:
                ItemLocatorView()
            case .reminders:
                RemindersView()
            case .bills:
                BillsView()
            case .anniversaries:
                AnniversariesView()
            }
        }
        .toolbar(.hidden, for: .tabBar)
    }
}
