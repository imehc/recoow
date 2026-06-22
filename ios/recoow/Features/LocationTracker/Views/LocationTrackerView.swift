import SwiftUI

struct LocationTrackerView: View {
    @Environment(AppContainer.self) private var container
    @State private var detailRoute: TrackDetailRoute?

    var body: some View {
        LocationTrackerContent(
            viewModel: container.locationTrackerViewModel,
            detailRoute: $detailRoute
        )
        .navigationTitle(AppLocalization.string("轨迹记录"))
        .toolbar(.hidden, for: .tabBar)
        .navigationDestination(item: $detailRoute) { route in
            TrackDetailView(trackID: route.id)
        }
        .task {
            await container.locationTrackerViewModel.pauseInterruptedRecordingIfNeeded()
        }
    }
}

#Preview {
    NavigationStack {
        LocationTrackerView()
            .environment(AppContainer.preview)
    }
}
