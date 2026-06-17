import SwiftUI

struct TrackHistoryRow: View {
    let track: Track
    let pointCount: Int
    let isActive: Bool
    let activeElapsedSeconds: Int64
    let activeDistanceMeters: Double

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 8) {
                Text(track.name)
                    .font(.headline)
                    .lineLimit(2)

                Spacer(minLength: 8)

                if isActive {
                    Label("记录中", systemImage: "dot.radiowaves.left.and.right")
                        .font(.footnote)
                        .foregroundStyle(.green)
                }
            }

            HStack(spacing: 12) {
                Label(AppFormatters.distance(displayDistance), systemImage: "point.topleft.down.curvedto.point.bottomright.up")
                Label(AppFormatters.duration(displayDuration), systemImage: "timer")
                Label("\(pointCount)", systemImage: "number")
            }
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .lineLimit(1)
        }
        .padding(.vertical, 6)
    }

    private var displayDistance: Double {
        isActive ? activeDistanceMeters : track.distanceMeters
    }

    private var displayDuration: Int64 {
        isActive ? activeElapsedSeconds : track.durationSeconds
    }
}
