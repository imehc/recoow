import SwiftUI

struct ToolDestinationView: View {
    let route: ToolRoute

    var body: some View {
        Group {
            switch route {
            case .locationTracker:
                LocationTrackerView()
            }
        }
        .toolbar(.hidden, for: .tabBar)
    }
}
