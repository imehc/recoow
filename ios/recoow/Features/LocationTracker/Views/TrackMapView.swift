import MapKit
import SwiftUI

struct TrackMapView: View {
    @Environment(\.locale) private var locale

    @Binding var cameraPosition: MapCameraPosition
    @Binding var selectedSegmentID: String?

    let points: [TrackPoint]
    let segments: [TrackSegment]
    let displayCoordinates: [CLLocationCoordinate2D]
    let playbackCoordinate: CLLocationCoordinate2D?
    let playbackCoordinates: [CLLocationCoordinate2D]
    let detectedPlaces: [TrackDetectedPlace]
    let selectedPlaceID: String?
    let qualityOccurrences: [TrackQualityOccurrence]
    let selectedQualityOccurrenceID: String?
    @Binding var showsDetectedPlaces: Bool
    @Binding var showsQualityIssues: Bool
    @Binding var showsPlaybackPath: Bool
    let selectPlace: (TrackDetectedPlace) -> Void
    let selectQualityOccurrence: (TrackQualityOccurrence) -> Void
    let showOverview: () -> Void

    var body: some View {
        ZStack {
            Map(position: $cameraPosition) {
                if displaySegments.isEmpty == false {
                    ForEach(displaySegments) { segment in
                        MapPolyline(coordinates: segment.coordinates)
                            .stroke(
                                segment.motionType.color.opacity(segmentOpacity(for: segment)),
                                lineWidth: segmentLineWidth(for: segment)
                            )
                    }

                    ForEach(displaySegments) { segment in
                        Annotation(segment.motionType.title, coordinate: segment.midpoint) {
                            Button {
                                selectedSegmentID = segment.id
                                cameraPosition = Self.cameraPosition(for: segment.coordinates)
                            } label: {
                                Image(systemName: segment.motionType.systemImage)
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(.white)
                                    .frame(width: selectedSegmentID == segment.id ? 30 : 24, height: selectedSegmentID == segment.id ? 30 : 24)
                                    .background(segment.motionType.color, in: .circle)
                                    .overlay {
                                        Circle()
                                            .stroke(.white, lineWidth: selectedSegmentID == segment.id ? 3 : 2)
                                    }
                                    .shadow(radius: selectedSegmentID == segment.id ? 4 : 2)
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(segment.motionType.title)
                        }
                    }
                } else if displayCoordinates.count > 1 {
                    MapPolyline(coordinates: displayCoordinates)
                        .stroke(.blue, lineWidth: 4)
                }

                if playbackCoordinates.count > 1 {
                    MapPolyline(coordinates: playbackCoordinates)
                        .stroke(Color.accentColor, style: StrokeStyle(lineWidth: 7, lineCap: .round, lineJoin: .round))
                }

                if displaySegments.isEmpty {
                    ForEach(points) { point in
                        Annotation(AppLocalization.string("采样点"), coordinate: displayCoordinate(for: point)) {
                            ZStack {
                                Circle()
                                    .fill(.blue)

                                Circle()
                                    .stroke(.white, lineWidth: 1)
                            }
                            .frame(width: 7, height: 7)
                        }
                    }
                }

                if showsDetectedPlaces {
                    ForEach(detectedPlaces) { place in
                        Annotation(place.title, coordinate: place.displayCoordinate) {
                            Button {
                                selectPlace(place)
                            } label: {
                                Image(systemName: "mappin.circle.fill")
                                    .font(.system(size: selectedPlaceID == place.id ? 30 : 24, weight: .semibold))
                                    .foregroundStyle(.orange)
                                    .background(.white, in: .circle)
                                    .shadow(color: .black.opacity(0.22), radius: selectedPlaceID == place.id ? 5 : 3, y: 2)
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(place.title)
                        }
                    }
                }

                if showsQualityIssues {
                    ForEach(qualityOccurrences) { occurrence in
                        Annotation(occurrence.kind.title, coordinate: occurrence.displayCoordinate) {
                            Button {
                                selectQualityOccurrence(occurrence)
                            } label: {
                                Image(systemName: occurrence.kind.systemImage)
                                    .font(.caption.weight(.bold))
                                    .foregroundStyle(.white)
                                    .frame(
                                        width: selectedQualityOccurrenceID == occurrence.id ? 30 : 24,
                                        height: selectedQualityOccurrenceID == occurrence.id ? 30 : 24
                                    )
                                    .background(occurrence.kind.tint, in: .circle)
                                    .overlay {
                                        Circle()
                                            .stroke(.white, lineWidth: selectedQualityOccurrenceID == occurrence.id ? 3 : 2)
                                    }
                                    .shadow(color: .black.opacity(0.22), radius: selectedQualityOccurrenceID == occurrence.id ? 5 : 3, y: 2)
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(occurrence.kind.title)
                        }
                    }
                }

                if let first = displayCoordinates.first {
                    Marker(AppLocalization.string("起点"), systemImage: "play.fill", coordinate: first)
                        .tint(.green)
                }

                if let last = displayCoordinates.last, displayCoordinates.count > 1 {
                    Marker(AppLocalization.string("终点"), systemImage: "flag.checkered", coordinate: last)
                        .tint(.red)
                }

                if let playbackCoordinate {
                    Annotation(AppLocalization.string("回放位置"), coordinate: playbackCoordinate) {
                        ZStack {
                            Circle()
                                .fill(Color.accentColor.opacity(0.2))
                                .frame(width: 30, height: 30)

                            Circle()
                                .fill(Color.accentColor)
                                .frame(width: 14, height: 14)

                            Circle()
                                .stroke(.white, lineWidth: 3)
                                .frame(width: 14, height: 14)
                        }
                        .shadow(color: .black.opacity(0.25), radius: 4, y: 2)
                    }
                }
            }
            .frame(minHeight: AppDesign.mapMinimumHeight)
            .clipShape(.rect(cornerRadius: AppDesign.cornerRadius))

            if points.isEmpty {
                ContentUnavailableView(AppLocalization.string("暂无采样点"), systemImage: "location.slash")
                    .background(.thinMaterial, in: .rect(cornerRadius: AppDesign.cornerRadius))
            }

            if displayCoordinates.count > 1 {
                VStack {
                    HStack {
                        TrackMapLayerMenu(
                            showsDetectedPlaces: $showsDetectedPlaces,
                            showsQualityIssues: $showsQualityIssues,
                            showsPlaybackPath: $showsPlaybackPath,
                            hasDetectedPlaces: detectedPlaces.isEmpty == false,
                            hasQualityIssues: qualityOccurrences.isEmpty == false,
                            hasPlaybackPath: displayCoordinates.count > 1
                        )

                        Spacer()
                    }
                    .padding(12)

                    Spacer()
                }
                .zIndex(1)
            }

            if hasFocusedSelection, displayCoordinates.count > 1 {
                VStack {
                    HStack {
                        Spacer()

                        Button {
                            showOverview()
                        } label: {
                            Image(systemName: "map")
                                .font(.body.weight(.semibold))
                                .foregroundStyle(.primary)
                                .frame(width: 44, height: 44)
                                .background(.regularMaterial, in: .circle)
                                .overlay {
                                    Circle()
                                        .stroke(Color(.separator), lineWidth: 0.5)
                                }
                                .shadow(color: .black.opacity(0.18), radius: 6, y: 2)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(AppLocalization.string("恢复全览"))
                    }
                    .padding(12)

                    Spacer()
                }
                .zIndex(1)
            }

            if let selectedFocusInfo {
                VStack {
                    Spacer()

                    TrackMapSelectionBadge(info: selectedFocusInfo)
                        .padding(12)
                }
                .zIndex(2)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.snappy(duration: 0.2), value: selectedFocusInfo?.id)
    }

    private func displayCoordinate(for point: TrackPoint) -> CLLocationCoordinate2D {
        CoordinateTransform.mapDisplayCoordinate(forWGS84: point.coordinate)
    }

    private var displaySegments: [TrackMapDisplaySegment] {
        segments.compactMap { segment in
            let coordinates = points
                .filter { point in
                    point.timestampMilliseconds >= segment.startedAt &&
                        point.timestampMilliseconds <= segment.endedAt
                }
                .map(displayCoordinate)

            guard coordinates.count > 1 else { return nil }
            return TrackMapDisplaySegment(
                id: segment.id,
                motionType: segment.motionType,
                coordinates: coordinates,
                midpoint: coordinates[coordinates.count / 2]
            )
        }
    }

    private func segmentOpacity(for segment: TrackMapDisplaySegment) -> Double {
        guard let selectedSegmentID else { return 1 }
        return selectedSegmentID == segment.id ? 1 : 0.48
    }

    private func segmentLineWidth(for segment: TrackMapDisplaySegment) -> CGFloat {
        guard let selectedSegmentID else { return 5 }
        return selectedSegmentID == segment.id ? 7 : 4
    }

    private var hasFocusedSelection: Bool {
        selectedSegmentID != nil ||
            (showsDetectedPlaces && selectedPlaceID != nil) ||
            (showsQualityIssues && selectedQualityOccurrenceID != nil)
    }

    private var selectedFocusInfo: TrackMapFocusInfo? {
        if showsDetectedPlaces,
           let selectedPlaceID,
           let place = detectedPlaces.first(where: { $0.id == selectedPlaceID }) {
            return TrackMapFocusInfo(
                id: "place-\(place.id)",
                title: place.title,
                subtitle: "\(AppFormatters.time(milliseconds: place.startedAt, locale: locale)) - \(AppFormatters.time(milliseconds: place.endedAt, locale: locale)) · \(AppFormatters.duration(place.durationSeconds))",
                systemImage: "mappin.circle.fill",
                tint: .orange
            )
        }

        if showsQualityIssues,
           let selectedQualityOccurrenceID,
           let occurrence = qualityOccurrences.first(where: { $0.id == selectedQualityOccurrenceID }) {
            return TrackMapFocusInfo(
                id: "quality-\(occurrence.id)",
                title: occurrence.kind.title,
                subtitle: "\(AppFormatters.time(milliseconds: occurrence.timestampMilliseconds, locale: locale)) · \(occurrence.detail)",
                systemImage: occurrence.kind.systemImage,
                tint: occurrence.kind.tint
            )
        }

        if let selectedSegmentID,
           let segment = segments.first(where: { $0.id == selectedSegmentID }) {
            return TrackMapFocusInfo(
                id: "segment-\(segment.id)",
                title: segment.motionType.title,
                subtitle: "\(AppFormatters.time(milliseconds: segment.startedAt, locale: locale)) - \(AppFormatters.time(milliseconds: segment.endedAt, locale: locale)) · \(AppFormatters.distance(segment.distanceMeters))",
                systemImage: segment.motionType.systemImage,
                tint: segment.motionType.color
            )
        }

        return nil
    }

    private static func cameraPosition(
        for coordinates: [CLLocationCoordinate2D],
        paddingMultiplier: Double = 1.8,
        minimumDelta: CLLocationDegrees = 0.005
    ) -> MapCameraPosition {
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
            latitudeDelta: max((maxLatitude - minLatitude) * paddingMultiplier, minimumDelta),
            longitudeDelta: max((maxLongitude - minLongitude) * paddingMultiplier, minimumDelta)
        )
        return .region(MKCoordinateRegion(center: center, span: span))
    }
}

private struct TrackMapDisplaySegment: Identifiable {
    let id: String
    let motionType: TrackMotionType
    let coordinates: [CLLocationCoordinate2D]
    let midpoint: CLLocationCoordinate2D
}

private struct TrackMapFocusInfo: Identifiable {
    let id: String
    let title: String
    let subtitle: String
    let systemImage: String
    let tint: Color
}

private struct TrackMapLayerMenu: View {
    @Binding var showsDetectedPlaces: Bool
    @Binding var showsQualityIssues: Bool
    @Binding var showsPlaybackPath: Bool

    let hasDetectedPlaces: Bool
    let hasQualityIssues: Bool
    let hasPlaybackPath: Bool

    var body: some View {
        Menu {
            Toggle(isOn: $showsDetectedPlaces) {
                Label(AppLocalization.string("停留点"), systemImage: "mappin.circle.fill")
            }
            .disabled(hasDetectedPlaces == false)

            Toggle(isOn: $showsQualityIssues) {
                Label(AppLocalization.string("质量问题"), systemImage: "exclamationmark.triangle")
            }
            .disabled(hasQualityIssues == false)

            Toggle(isOn: $showsPlaybackPath) {
                Label(AppLocalization.string("已走轨迹"), systemImage: "point.topleft.down.curvedto.point.bottomright.up")
            }
            .disabled(hasPlaybackPath == false)
        } label: {
            Image(systemName: "square.3.layers.3d")
                .font(.body.weight(.semibold))
                .foregroundStyle(.primary)
                .frame(width: 44, height: 44)
                .background(.regularMaterial, in: .circle)
                .overlay {
                    Circle()
                        .stroke(Color(.separator), lineWidth: 0.5)
                }
                .shadow(color: .black.opacity(0.18), radius: 6, y: 2)
        }
        .accessibilityLabel(AppLocalization.string("地图图层"))
    }
}

private struct TrackMapSelectionBadge: View {
    let info: TrackMapFocusInfo

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: info.systemImage)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.white)
                .frame(width: 28, height: 28)
                .background(info.tint, in: .circle)

            VStack(alignment: .leading, spacing: 2) {
                Text(info.title)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.primary)

                Text(info.subtitle)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer(minLength: 0)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(.regularMaterial, in: .rect(cornerRadius: AppDesign.cornerRadius))
        .overlay {
            RoundedRectangle(cornerRadius: AppDesign.cornerRadius)
                .stroke(Color(.separator), lineWidth: 0.5)
        }
        .shadow(color: .black.opacity(0.16), radius: 8, y: 3)
        .accessibilityElement(children: .combine)
    }
}
