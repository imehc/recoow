import MapKit
import SwiftUI

struct TrackDetailView: View {
    @Environment(AppContainer.self) private var container
    @State private var track: Track?
    @State private var points: [TrackPoint] = []
    @State private var displayCoordinates: [CLLocationCoordinate2D] = []
    @State private var errorMessage: String?
    @State private var cameraPosition: MapCameraPosition = .automatic
    @State private var editingTrack: Track?

    let trackID: String

    var body: some View {
        Form {
            Section {
                TrackMapView(
                    cameraPosition: $cameraPosition,
                    points: points,
                    displayCoordinates: displayCoordinates
                )
                .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 0, trailing: 0))
            }

            if let track {
                Section("摘要") {
                    LabeledContent("名称", value: track.name)

                    if let note = track.note, note.isEmpty == false {
                        LabeledContent {
                            Text(note)
                                .multilineTextAlignment(.trailing)
                        } label: {
                            Text("备注")
                        }
                    }

                    LabeledContent("状态") {
                        Text(LocalizedStringKey(statusTitle))
                            .foregroundStyle(statusColor)
                    }

                    LabeledContent("距离", value: AppFormatters.distance(displayDistanceMeters))
                    LabeledContent("时长", value: AppFormatters.duration(displayDurationSeconds))
                    LabeledContent("采样点", value: "\(displayPointCount)")
                    LabeledContent("精度", value: "\(track.desiredAccuracyMeters)m")
                    LabeledContent("平均速度", value: AppFormatters.speed(displayAverageSpeed))
                    LabeledContent("最高速度", value: AppFormatters.speed(displayMaxSpeed))
                }
            } else if errorMessage == nil {
                Section {
                    ProgressView("正在加载")
                }
            }

            if let errorMessage {
                Section {
                    Label(errorMessage, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                }
            }
        }
        .navigationTitle(track?.name ?? "轨迹详情")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if let track {
                    Button("编辑", systemImage: "pencil") {
                        editingTrack = track
                    }
                }
            }
        }
        .sheet(item: $editingTrack) { track in
            NavigationStack {
                TrackEditView(track: track) { updatedTrack in
                    self.track = updatedTrack
                }
            }
        }
        .task(id: trackID) {
            load()
        }
        .onChange(of: container.locationTrackerViewModel.pointCount) { _, _ in
            reloadIfActive()
        }
    }

    private func load() {
        do {
            track = try container.trackRepository.fetchTrack(id: trackID)
            points = try container.trackRepository.fetchPoints(trackID: trackID)
            displayCoordinates = points.map { point in
                CoordinateTransform.mapDisplayCoordinate(forWGS84: point.coordinate)
            }
            cameraPosition = Self.cameraPosition(for: displayCoordinates)
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func reloadIfActive() {
        guard isActiveTrack else { return }
        load()
    }

    private var isActiveTrack: Bool {
        container.locationTrackerViewModel.isRecording &&
            container.locationTrackerViewModel.currentTrackID == trackID
    }

    private var statusTitle: String {
        if isActiveTrack {
            return "记录中"
        }

        if track?.endedAt == nil {
            return "未结束"
        }

        return "已完成"
    }

    private var statusColor: Color {
        if isActiveTrack {
            return .green
        }

        if track?.endedAt == nil {
            return .orange
        }

        return .secondary
    }

    private var displayPointCount: Int {
        if isActiveTrack {
            return max(points.count, container.locationTrackerViewModel.pointCount)
        }

        return points.count
    }

    private var displayDistanceMeters: Double {
        if isActiveTrack {
            return container.locationTrackerViewModel.currentDistanceMeters
        }

        if let distanceMeters = track?.distanceMeters, distanceMeters > 0 {
            return distanceMeters
        }

        return calculatedDistanceMeters
    }

    private var displayDurationSeconds: Int64 {
        if isActiveTrack {
            return container.locationTrackerViewModel.elapsedSeconds
        }

        if let durationSeconds = track?.durationSeconds, durationSeconds > 0 {
            return durationSeconds
        }

        return calculatedDurationSeconds
    }

    private var displayAverageSpeed: Double? {
        if let averageSpeed = track?.averageSpeedMetersPerSecond {
            return averageSpeed
        }

        guard displayDistanceMeters > 0, displayDurationSeconds > 0 else { return nil }
        return displayDistanceMeters / Double(displayDurationSeconds)
    }

    private var displayMaxSpeed: Double? {
        if isActiveTrack, let speed = container.locationTrackerViewModel.currentMaxSpeedMetersPerSecond {
            return speed
        }

        if let speed = track?.maxSpeedMetersPerSecond {
            return speed
        }

        return points.compactMap(\.speedMetersPerSecond).max()
    }

    private var calculatedDurationSeconds: Int64 {
        guard let first = points.first, let last = points.last else { return 0 }
        return max(0, (last.timestampMilliseconds - first.timestampMilliseconds) / 1000)
    }

    private var calculatedDistanceMeters: Double {
        guard points.count > 1 else { return 0 }

        return zip(points, points.dropFirst()).reduce(0) { partialResult, pair in
            let start = CLLocation(latitude: pair.0.latitude, longitude: pair.0.longitude)
            let end = CLLocation(latitude: pair.1.latitude, longitude: pair.1.longitude)
            return partialResult + end.distance(from: start)
        }
    }

    private static func cameraPosition(for coordinates: [CLLocationCoordinate2D]) -> MapCameraPosition {
        guard let first = coordinates.first else { return .automatic }

        var minLatitude = first.latitude
        var maxLatitude = first.latitude
        var minLongitude = first.longitude
        var maxLongitude = first.longitude

        for coordinate in coordinates {
            minLatitude = min(minLatitude, coordinate.latitude)
            maxLatitude = max(maxLatitude, coordinate.latitude)
            minLongitude = min(minLongitude, coordinate.longitude)
            maxLongitude = max(maxLongitude, coordinate.longitude)
        }

        let center = CLLocationCoordinate2D(
            latitude: (minLatitude + maxLatitude) / 2,
            longitude: (minLongitude + maxLongitude) / 2
        )
        let span = MKCoordinateSpan(
            latitudeDelta: max((maxLatitude - minLatitude) * 1.4, 0.01),
            longitudeDelta: max((maxLongitude - minLongitude) * 1.4, 0.01)
        )
        return .region(MKCoordinateRegion(center: center, span: span))
    }
}

#Preview {
    NavigationStack {
        TrackDetailView(trackID: "preview")
            .environment(AppContainer.preview)
    }
}
