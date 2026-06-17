import CoreLocation
import SwiftUI

struct LocationTrackerView: View {
    @Environment(AppContainer.self) private var container
    @State private var viewModel: LocationTrackerViewModel?
    @State private var detailRoute: TrackDetailRoute?

    var body: some View {
        Group {
            if let viewModel {
                LocationTrackerContent(viewModel: viewModel, detailRoute: $detailRoute)
            } else {
                ProgressView("正在准备")
            }
        }
        .navigationTitle("轨迹记录")
        .navigationDestination(item: $detailRoute) { route in
            TrackDetailView(trackID: route.id)
        }
        .task {
            if viewModel == nil {
                viewModel = LocationTrackerViewModel(
                    repository: container.trackRepository,
                    locationService: container.locationService,
                    syncEngine: container.syncEngine
                )
            }
        }
    }
}

private struct LocationTrackerContent: View {
    @Bindable var viewModel: LocationTrackerViewModel
    @Binding var detailRoute: TrackDetailRoute?

    var body: some View {
        List {
            Section("采样设置") {
                Picker("精度", selection: $viewModel.selectedAccuracy) {
                    ForEach(LocationAccuracy.allCases) { accuracy in
                        Text(accuracy.title).tag(accuracy)
                    }
                }
                .pickerStyle(.segmented)
                .disabled(viewModel.isRecording)
            }

            Section("当前状态") {
                LabeledContent("状态", value: viewModel.state.title)
                LabeledContent("时长", value: AppFormatters.duration(viewModel.elapsedSeconds))
                LabeledContent("点数", value: "\(viewModel.pointCount)")

                if let coordinate = viewModel.currentCoordinate {
                    LabeledContent(
                        "坐标",
                        value: AppFormatters.coordinate(latitude: coordinate.latitude, longitude: coordinate.longitude)
                    )
                } else {
                    LabeledContent("坐标", value: "--")
                }

                if case .failed(let message) = viewModel.state {
                    Text(message)
                        .font(.footnote)
                        .foregroundStyle(.red)
                }
            }

            Section {
                Button {
                    Task {
                        if viewModel.state == .recording || viewModel.state == .requestingAuthorization {
                            await viewModel.stop()
                            if let finishedTrackID = viewModel.finishedTrackID {
                                detailRoute = TrackDetailRoute(id: finishedTrackID)
                            }
                        } else {
                            await viewModel.start()
                        }
                    }
                } label: {
                    Label(
                        viewModel.isRecording ? "停止" : "开始",
                        systemImage: viewModel.isRecording ? "stop.fill" : "play.fill"
                    )
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(viewModel.isRecording ? .red : .blue)
            }
        }
    }
}

struct TrackDetailRoute: Identifiable, Hashable {
    let id: String
}

#Preview {
    NavigationStack {
        LocationTrackerView()
            .environment(AppContainer.preview)
    }
}
