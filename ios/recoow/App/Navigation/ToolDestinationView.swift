import SwiftUI

struct ToolDestinationView: View {
    let route: ToolRoute

    var body: some View {
        ToolModule(route: route).destinationView()
        .toolbar(.hidden, for: .tabBar)
    }
}
