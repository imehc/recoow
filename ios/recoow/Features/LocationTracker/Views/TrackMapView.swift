import MapKit
import SwiftUI

struct TrackMapView: View {
    @Binding var cameraPosition: MapCameraPosition

    let points: [TrackPoint]
    let displayCoordinates: [CLLocationCoordinate2D]

    var body: some View {
        ZStack {
            Map(position: $cameraPosition) {
                if displayCoordinates.count > 1 {
                    MapPolyline(coordinates: displayCoordinates)
                        .stroke(.blue, lineWidth: 4)
                }

                ForEach(points) { point in
                    Annotation("采样点", coordinate: displayCoordinate(for: point)) {
                        ZStack {
                            Circle()
                                .fill(.blue)

                            Circle()
                                .stroke(.white, lineWidth: 1)
                        }
                        .frame(width: 7, height: 7)
                    }
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
            .frame(minHeight: AppDesign.mapMinimumHeight)
            .clipShape(.rect(cornerRadius: AppDesign.cornerRadius))

            if points.isEmpty {
                ContentUnavailableView("暂无采样点", systemImage: "location.slash")
                    .background(.thinMaterial, in: .rect(cornerRadius: AppDesign.cornerRadius))
            }
        }
    }

    private func displayCoordinate(for point: TrackPoint) -> CLLocationCoordinate2D {
        CoordinateTransform.mapDisplayCoordinate(forWGS84: point.coordinate)
    }
}
