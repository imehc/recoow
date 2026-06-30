import CoreLocation
import SwiftUI
import UIKit

struct LocationTrackerContent: View {
    @Environment(\.openURL) private var openURL

    @Bindable var viewModel: LocationTrackerViewModel
    @Binding var detailRoute: TrackDetailRoute?

    var body: some View {
        Form {
            Section(AppLocalization.string("状态")) {
                LabeledContent {
                    if let currentTrackName = viewModel.currentTrackName {
                        Text(currentTrackName)
                            .foregroundStyle(.secondary)
                    } else {
                        Text(AppLocalization.string("准备开始新的轨迹"))
                            .foregroundStyle(.secondary)
                    }
                } label: {
                    MetadataItemView(
                        title: viewModel.state.title,
                        systemImage: statusSystemImage
                    )
                    .foregroundStyle(statusColor)
                }

                LabeledContent(AppLocalization.string("时长"), value: AppFormatters.duration(viewModel.elapsedSeconds))
                LabeledContent(AppLocalization.string("采样点"), value: "\(viewModel.pointCount)")
                LabeledContent(AppLocalization.string("距离"), value: AppFormatters.distance(viewModel.currentDistanceMeters))
                LabeledContent(AppLocalization.string("最高速度"), value: AppFormatters.speed(viewModel.currentMaxSpeedMetersPerSecond))

                if let coordinate = viewModel.currentCoordinate {
                    LabeledContent(AppLocalization.string("当前位置")) {
                        Text(AppFormatters.coordinate(latitude: coordinate.latitude, longitude: coordinate.longitude))
                            .monospacedDigit()
                            .lineLimit(1)
                            .minimumScaleFactor(0.75)
                    }
                }
            }

            Section(AppLocalization.string("采样设置")) {
                Picker(AppLocalization.string("精度"), selection: $viewModel.selectedAccuracy) {
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

                    if viewModel.isLocationPermissionBlocked {
                        Button(action: openAppSettings) {
                            Label(AppLocalization.string("打开设置"), systemImage: "gearshape")
                        }
                    }
                }
            }

            Section {
                if viewModel.isRecording {
                    Button(action: pauseRecording) {
                        actionLabel(title: AppLocalization.string("暂停记录"), systemImage: "pause.fill", tint: .orange)
                    }
                    .buttonStyle(.plain)

                    Button(action: finishRecording) {
                        actionLabel(title: AppLocalization.string("结束记录"), systemImage: "stop.fill", tint: .red)
                    }
                    .buttonStyle(.plain)
                } else if viewModel.isPaused {
                    Button(action: startRecording) {
                        actionLabel(title: AppLocalization.string("恢复记录"), systemImage: "play.fill", tint: .green)
                    }
                    .buttonStyle(.plain)

                    Button(action: finishRecording) {
                        actionLabel(title: AppLocalization.string("结束记录"), systemImage: "stop.fill", tint: .red)
                    }
                    .buttonStyle(.plain)
                } else {
                    Button(action: startRecording) {
                        actionLabel(title: AppLocalization.string("开始记录"), systemImage: "play.fill", tint: .blue)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .alert(AppLocalization.string("定位权限未开启"), isPresented: $viewModel.isShowingLocationPermissionSettingsPrompt) {
            Button(AppLocalization.string("打开设置")) {
                openAppSettings()
            }

            Button(AppLocalization.string("取消"), role: .cancel) { }
        } message: {
            Text(AppLocalization.string("请在系统设置中允许定位权限后再开始记录。"))
        }
    }

    private func actionLabel(title: String, systemImage: String, tint: Color) -> some View {
        HStack(spacing: 8) {
            Image(systemName: systemImage)
                .symbolRenderingMode(.monochrome)
                .foregroundStyle(.white)

            Text(title)
                .foregroundStyle(.white)
        }
        .font(.body)
        .frame(maxWidth: .infinity)
        .frame(height: 32)
        .background(tint, in: .rect(cornerRadius: AppDesign.cornerRadius))
        .contentShape(.rect)
    }

    private var failedMessage: String? {
        if case .failed(let message) = viewModel.state {
            return message
        }

        return nil
    }

    private var statusSystemImage: String {
        switch viewModel.state {
        case .idle:
            "pause.circle"
        case .requestingAuthorization:
            "location.circle"
        case .recording:
            "dot.radiowaves.left.and.right"
        case .paused:
            "pause.circle"
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
        case .paused:
            .orange
        case .failed:
            .red
        }
    }

    private func startRecording() {
        Task {
            await viewModel.start()
        }
    }

    private func pauseRecording() {
        Task {
            await viewModel.pause()
        }
    }

    private func finishRecording() {
        Task {
            await viewModel.stop()

            if let finishedTrackID = viewModel.finishedTrackID {
                detailRoute = TrackDetailRoute(id: finishedTrackID)
            }
        }
    }

    private func openAppSettings() {
        guard let settingsURL = URL(string: UIApplication.openSettingsURLString) else {
            return
        }

        openURL(settingsURL)
    }
}
