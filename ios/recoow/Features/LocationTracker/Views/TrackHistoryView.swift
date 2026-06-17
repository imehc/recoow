import SwiftUI

struct TrackHistoryView: View {
    @Environment(AppContainer.self) private var container
    @State private var viewModel: TrackHistoryViewModel?

    var body: some View {
        Group {
            if let viewModel {
                TrackHistoryContent(viewModel: viewModel)
            } else {
                ProgressView("正在加载")
            }
        }
        .navigationTitle("历史")
        .navigationDestination(for: String.self) { trackID in
            TrackDetailView(trackID: trackID)
        }
        .task {
            if viewModel == nil {
                let model = TrackHistoryViewModel(repository: container.trackRepository)
                model.startObserving()
                viewModel = model
            }
        }
    }
}

private struct TrackHistoryContent: View {
    @Bindable var viewModel: TrackHistoryViewModel

    var body: some View {
        List {
            if let errorMessage = viewModel.errorMessage {
                Text(errorMessage)
                    .font(.footnote)
                    .foregroundStyle(.red)
            }

            if viewModel.tracks.isEmpty {
                ContentUnavailableView("暂无轨迹", systemImage: "map")
            } else {
                ForEach(viewModel.tracks) { track in
                    NavigationLink(value: track.id) {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(track.name)
                                .font(.headline)
                            HStack {
                                Label(AppFormatters.distance(track.distanceMeters), systemImage: "point.topleft.down.curvedto.point.bottomright.up")
                                Label(AppFormatters.duration(track.durationSeconds), systemImage: "timer")
                                Label("\(track.desiredAccuracyMeters)m", systemImage: "scope")
                            }
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        }
                        .padding(.vertical, 4)
                    }
                }
            }
        }
    }
}

#Preview {
    NavigationStack {
        TrackHistoryView()
            .environment(AppContainer.preview)
    }
}
