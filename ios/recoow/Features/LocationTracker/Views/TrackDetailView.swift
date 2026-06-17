import MapKit
import SwiftUI

struct TrackDetailView: View {
    @Environment(AppContainer.self) private var container
    @State private var track: Track?
    @State private var points: [TrackPoint] = []
    @State private var displayCoordinates: [CLLocationCoordinate2D] = []
    @State private var errorMessage: String?
    @State private var cameraPosition: MapCameraPosition = .automatic

    let trackID: String

    var body: some View {
        List {
            Section {
                ZStack {
                    Map(position: $cameraPosition) {
                        if displayCoordinates.count > 1 {
                            MapPolyline(coordinates: displayCoordinates)
                                .stroke(.blue, lineWidth: 4)
                        }

                        if let first = displayCoordinates.first {
                            Marker("起点", systemImage: "play.fill", coordinate: first)
                                .tint(.green)
                        }

                        if let last = displayCoordinates.last, displayCoordinates.count > 1 {
                            Marker("终点", systemImage: "flag.checkered", coordinate: last)
                                .tint(.red)
                        }
                    }
                    .frame(minHeight: 320)
                    .clipShape(.rect(cornerRadius: 8))

                    if points.isEmpty {
                        ContentUnavailableView("暂无采样点", systemImage: "location.slash")
                            .background(.thinMaterial)
                            .clipShape(.rect(cornerRadius: 8))
                    }
                }
                .listRowInsets(EdgeInsets())
            }

            if let track {
                Section("概要") {
                    LabeledContent("名称", value: track.name)
                    LabeledContent("距离", value: AppFormatters.distance(track.distanceMeters))
                    LabeledContent("时长", value: AppFormatters.duration(track.durationSeconds))
                    LabeledContent("平均速度", value: AppFormatters.speed(track.averageSpeedMetersPerSecond))
                    LabeledContent("最大速度", value: AppFormatters.speed(track.maxSpeedMetersPerSecond))
                    LabeledContent("采样点", value: "\(points.count)")
                }
            }

            if let errorMessage {
                Section("错误") {
                    Text(errorMessage)
                        .font(.footnote)
                        .foregroundStyle(.red)
                }
            }
        }
        .navigationTitle(track?.name ?? "轨迹详情")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: trackID) {
            load()
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
