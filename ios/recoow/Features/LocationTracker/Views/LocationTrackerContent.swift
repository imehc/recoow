import CoreLocation
import SwiftUI

struct LocationTrackerContent: View {
    @Bindable var viewModel: LocationTrackerViewModel
    @Binding var detailRoute: TrackDetailRoute?

    var body: some View {
        Form {
            Section("状态") {
                LabeledContent {
                    if let currentTrackName = viewModel.currentTrackName {
                        Text(currentTrackName)
                            .foregroundStyle(.secondary)
                    } else {
                        Text("准备开始新的轨迹")
                            .foregroundStyle(.secondary)
                    }
                } label: {
                    Label {
                        Text(LocalizedStringKey(viewModel.state.title))
                    } icon: {
                        Image(systemName: statusSystemImage)
                    }
                    .foregroundStyle(statusColor)
                }

                LabeledContent("时长", value: AppFormatters.duration(viewModel.elapsedSeconds))
                LabeledContent("采样点", value: "\(viewModel.pointCount)")
                LabeledContent("距离", value: AppFormatters.distance(viewModel.currentDistanceMeters))
                LabeledContent("最高速度", value: AppFormatters.speed(viewModel.currentMaxSpeedMetersPerSecond))

                if let coordinate = viewModel.currentCoordinate {
                    LabeledContent("当前位置") {
                        Text(AppFormatters.coordinate(latitude: coordinate.latitude, longitude: coordinate.longitude))
                            .monospacedDigit()
                            .lineLimit(1)
                            .minimumScaleFactor(0.75)
                    }
                }
            }

            Section("采样设置") {
                Picker("精度", selection: $viewModel.selectedAccuracy) {
                    ForEach(LocationAccuracy.allCases) { accuracy in
                        Text(accuracy.title).tag(accuracy)
                    }
                }
                .pickerStyle(.segmented)
                .disabled(viewModel.isRecording)
            }

            if let failedMessage {
                Section {
                    Label(failedMessage, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                }
            }

            Section {
                Button(action: toggleRecording) {
                    Label {
                        Text(LocalizedStringKey(recordingButtonTitle))
                    } icon: {
                        Image(systemName: recordingButtonImage)
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(viewModel.isRecording ? .red : .blue)
            }
        }
    }

    private var failedMessage: String? {
        if case .failed(let message) = viewModel.state {
            return message
        }

        return nil
    }

    private var recordingButtonTitle: String {
        viewModel.isRecording ? "停止记录" : "开始记录"
    }

    private var recordingButtonImage: String {
        viewModel.isRecording ? "stop.fill" : "play.fill"
    }

    private var statusSystemImage: String {
        switch viewModel.state {
        case .idle:
            "pause.circle"
        case .requestingAuthorization:
            "location.circle"
        case .recording:
            "dot.radiowaves.left.and.right"
        case .stopped:
            "checkmark.circle"
        case .failed:
            "exclamationmark.triangle"
        }
    }

    private var statusColor: Color {
        switch viewModel.state {
        case .idle, .stopped:
            .secondary
        case .requestingAuthorization:
            .blue
        case .recording:
            .green
        case .failed:
            .red
        }
    }

    private func toggleRecording() {
        Task {
            if viewModel.isRecording {
                await viewModel.stop()

                if let finishedTrackID = viewModel.finishedTrackID {
                    detailRoute = TrackDetailRoute(id: finishedTrackID)
                }
            } else {
                await viewModel.start()
            }
        }
    }
}
